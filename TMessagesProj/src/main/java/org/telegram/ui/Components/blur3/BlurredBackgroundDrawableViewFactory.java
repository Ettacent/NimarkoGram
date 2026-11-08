/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.blur3;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.utils.glass.source.GlassSource;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.utils.glass.GlassEngine;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import me.vkryl.core.reference.ReferenceList;

public class BlurredBackgroundDrawableViewFactory {
    private static final ReferenceList<BlurredBackgroundDrawableViewFactory> factories = new ReferenceList<>();
    private final ReferenceList<BlurredBackgroundDrawable> createdDrawables = new ReferenceList<>();
    private final WeakHashMap<BlurredBackgroundDrawable, WeakReference<View>> drawableViews = new WeakHashMap<>();
    private final GlassSource glassSource;
    private final GlassSource frostedSource;
    private final GlassSource staticSource;
    private int outsetX, outsetY;

    public BlurredBackgroundDrawableViewFactory(GlassSource source) {
        this(source, source, source);
    }
    public static void invalidateGlassSettings() {
        for (BlurredBackgroundDrawableViewFactory factory : factories) {
            for (BlurredBackgroundDrawable drawable : factory.createdDrawables) {
                drawable.updateColors();
                drawable.invalidateDisplayList();
                drawable.invalidateSelf();
                final WeakReference<View> viewRef = factory.drawableViews.get(drawable);
                final View view = viewRef != null ? viewRef.get() : null;
                if (view != null) view.invalidate();
            }
            factory.invalidateAllLinkedViews();
            factory.dispatchSourcePositionChange();
            if (factory.engine != null) {
                factory.engine.invalidate();
            }
        }
    }
    public static boolean isLiquidGlassEnabled() {
        return GlassEngine.getCurrentGlassSettings().isLiquidGlassEnabled;
    }

    public BlurredBackgroundDrawableViewFactory(GlassSource glassSource, GlassSource frostedSource, GlassSource staticSource) {
        this.glassSource = glassSource;
        this.frostedSource = frostedSource;
        this.staticSource = staticSource;
        factories.add(this);
    }

    public GlassSource getSource() {
        return glassSource;
    }

    public GlassSource getFrostedSource() {
        return frostedSource;
    }

    public GlassSource getStaticSource() {
        return staticSource;
    }

    public BlurredBackgroundDrawableViewFactory(ViewPositionWatcher watcher, ViewGroup parent, GlassSource source) {
        this(source);
        setSourceRootView(watcher, parent);
    }

    public void setSourceRootView(ViewPositionWatcher watcher, ViewGroup parent) {
        this.viewPositionWatcher = watcher;
        this.parent = parent;
    }
    public void setGlassEngine(GlassEngine engine) {
        this.engine = engine;
    }
    public void setOutset(int outset) {
        setOutset(outset, outset);
    }
    public void setOutset(int dx, int dy) {
        outsetX = dx;
        outsetY = dy;
    }

    private @Nullable ReferenceList<BlurredBackgroundDrawable> linkedDrawables;
    private @Nullable ReferenceList<View> linkedViews;
    private @Nullable ViewPositionWatcher viewPositionWatcher;
    private @Nullable ViewGroup parent;
    private @Nullable GlassEngine engine;
    public View getSourceRootView() {
        return parent;
    }

    public void setLinkedViewsRef(@Nullable ReferenceList<View> linkedViews) {
        this.linkedViews = linkedViews;
    }

    public void setLinkedDrawablesRef(@Nullable ReferenceList<BlurredBackgroundDrawable> linkedDrawables) {
        this.linkedDrawables = linkedDrawables;
    }

    public void invalidateAllLinkedViews() {
        if (linkedViews != null) {
            for (View v : linkedViews) {
                v.invalidate();
            }
        }
    }
    public void release(View view, BlurredBackgroundDrawable drawable) {
        if (engine != null && view != null) engine.unregisterDrawable(view, drawable);
        createdDrawables.remove(drawable);
        drawableViews.remove(drawable);
        if (viewPositionWatcher != null) viewPositionWatcher.unsubscribe(view);
        if (linkedViews != null) linkedViews.remove(view);
        if (linkedDrawables != null) linkedDrawables.remove(drawable);
        drawable.setAlpha(0);
        drawable.setCallback(null);
        dispatchSourcePositionChange();
        if (engine != null) engine.invalidate();
    }

    private void dispatchSourcePositionChange() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (glassSource instanceof org.telegram.utils.glass.source.GlassSourceRenderNode) {
            ((org.telegram.utils.glass.source.GlassSourceRenderNode) glassSource).dispatchOnDrawablesRelativePositionChange();
        }
        if (frostedSource != glassSource && frostedSource instanceof org.telegram.utils.glass.source.GlassSourceRenderNode) {
            ((org.telegram.utils.glass.source.GlassSourceRenderNode) frostedSource).dispatchOnDrawablesRelativePositionChange();
        }
    }

    private boolean isLiquidGlassEffectAllowed;

    public void setLiquidGlassEffectAllowed(boolean liquidGlassEffectAllowed) {
        isLiquidGlassEffectAllowed = liquidGlassEffectAllowed;
    }
    public boolean supportsLiquidGlass() {
        return isLiquidGlassEffectAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && glassSource instanceof DrawableOwner;
    }

    public BlurredBackgroundDrawable create() {
        return create(null);
    }

    public BlurredBackgroundDrawable create(View view) {
        return create(view, null);
    }

    public interface DrawableOwner {
        BlurredBackgroundDrawable createDrawable();

        default void attachDrawable(BlurredBackgroundDrawable drawable) {

        }
    }

    public static BlurredBackgroundDrawable createDrawable(GlassSource source) {
        if (source instanceof DrawableOwner) {
            return ((DrawableOwner) source).createDrawable();
        }
        return new BlurredBackgroundDrawable(source);
    }

    private BlurredBackgroundDrawable createDrawable() {
        if (glassSource == frostedSource && frostedSource == staticSource) {
            return createDrawable(glassSource);
        }
        final BlurredBackgroundDrawable drawable = new BlurredBackgroundDrawable(glassSource, frostedSource, staticSource);
        attach(glassSource, drawable);
        if (frostedSource != glassSource) {
            attach(frostedSource, drawable);
        }
        if (staticSource != glassSource && staticSource != frostedSource) {
            attach(staticSource, drawable);
        }
        return drawable;
    }

    private static void attach(GlassSource source, BlurredBackgroundDrawable drawable) {
        if (source instanceof DrawableOwner) {
            ((DrawableOwner) source).attachDrawable(drawable);
        }
    }
    public BlurredBackgroundDrawable create(View view, boolean multiwindow) {
        return create(view, null, multiwindow);
    }

    public BlurredBackgroundDrawable create(View view, BlurredBackgroundColorProvider provider) {
        return create(view, provider, false);
    }

    public BlurredBackgroundDrawable create(View view, BlurredBackgroundColorProvider provider, boolean multiwindow) {
        return create(view, provider, multiwindow, true);
    }
    public BlurredBackgroundDrawable createForOverlay(View view, BlurredBackgroundColorProvider provider) {
        return create(view, provider, false, false);
    }
    protected BlurredBackgroundDrawable decorateDrawable(View view, BlurredBackgroundDrawable drawable) {
        return drawable;
    }
    private BlurredBackgroundDrawable create(View view, BlurredBackgroundColorProvider provider, boolean multiwindow, boolean trackPosition) {
        final BlurredBackgroundDrawable drawable = decorateDrawable(view, createDrawable());
        createdDrawables.add(drawable);
        if (view != null) {
            drawableViews.put(drawable, new WeakReference<>(view));
        }
        if (isLiquidGlassEffectAllowed) {
            drawable.setLiquidGlassEffectAllowed();
        }

        drawable.setColorProvider(provider);
        drawable.setOutset(outsetX, outsetY);

        if (linkedViews != null && view != null) {
            linkedViews.add(view);
        }

        if (!multiwindow && engine != null && view != null) {
            if (trackPosition) {
            engine.registerDrawable(view, drawable);
            } else {
                engine.registerDrawableWithExplicitSourceOffset(view, drawable);
            }
        }
        if (trackPosition && (engine == null || multiwindow) && viewPositionWatcher != null && parent != null && view != null) {
            viewPositionWatcher.subscribe(view, parent, (v, pos) -> {
                drawable.setSourceOffset(pos.left, pos.top);
                v.invalidate();
            }, multiwindow);
        }

        if (linkedDrawables != null) {
            linkedDrawables.add(drawable);
        }

        return drawable;
    }
}
