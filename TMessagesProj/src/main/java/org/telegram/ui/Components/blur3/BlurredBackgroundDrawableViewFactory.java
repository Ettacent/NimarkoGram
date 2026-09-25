package org.telegram.ui.Components.blur3;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.SharedConfig;

import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.utils.glass.GlassEngine;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import me.vkryl.core.reference.ReferenceList;

public class BlurredBackgroundDrawableViewFactory {
    private static final ReferenceList<BlurredBackgroundDrawableViewFactory> factories = new ReferenceList<>();
    private final ReferenceList<BlurredBackgroundDrawable> createdDrawables = new ReferenceList<>();
    private final WeakHashMap<BlurredBackgroundDrawable, WeakReference<View>> drawableViews = new WeakHashMap<>();

    private final BlurredBackgroundSource source;
    private int outsetX, outsetY;

    public BlurredBackgroundDrawableViewFactory(BlurredBackgroundSource source) {
        this.source = source;
        factories.add(this);
    }
    public static void invalidateGlassSettings() {
        for (BlurredBackgroundDrawableViewFactory factory : factories) {
            for (BlurredBackgroundDrawable drawable : factory.createdDrawables) {
                drawable.updateColors();
                drawable.invalidateSelf();
                final WeakReference<View> viewRef = factory.drawableViews.get(drawable);
                final View view = viewRef != null ? viewRef.get() : null;
                if (view != null) view.invalidate();
            }
            factory.invalidateAllLinkedViews();
            factory.source.dispatchOnDrawablesRelativePositionChange();
            if (factory.engine != null) {
                factory.engine.invalidate();
            }
        }
    }
    public static boolean isLiquidGlassEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)
                && SharedConfig.chatBlurEnabled();
    }

    public BlurredBackgroundDrawableViewFactory(ViewPositionWatcher watcher, ViewGroup parent, BlurredBackgroundSource source) {
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
        source.dispatchOnDrawablesRelativePositionChange();
    }

    private boolean isLiquidGlassEffectAllowed;

    public void setLiquidGlassEffectAllowed(boolean liquidGlassEffectAllowed) {
        isLiquidGlassEffectAllowed = liquidGlassEffectAllowed;
    }
    public boolean supportsLiquidGlass() {
        return isLiquidGlassEffectAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && source instanceof org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
    }

    public BlurredBackgroundDrawable create() {
        return create(null);
    }

    public BlurredBackgroundDrawable create(View view) {
        return create(view, null);
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
    private BlurredBackgroundDrawable create(View view, BlurredBackgroundColorProvider provider, boolean multiwindow, boolean trackPosition) {
        final BlurredBackgroundDrawable drawable = source.createDrawable();
        createdDrawables.add(drawable);
        if (view != null) {
            drawableViews.put(drawable, new WeakReference<>(view));
        }
        if (isLiquidGlassEffectAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (drawable instanceof BlurredBackgroundDrawableRenderNode) {
                ((BlurredBackgroundDrawableRenderNode) drawable).setLiquidGlassEffectAllowed();
            }
        }

        drawable.setColorProvider(provider);
        drawable.setOutset(outsetX, outsetY);

        if (linkedViews != null && view != null) {
            linkedViews.add(view);
        }

        if (trackPosition && !multiwindow && engine != null && view != null) {
            engine.registerDrawable(view, drawable);
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
