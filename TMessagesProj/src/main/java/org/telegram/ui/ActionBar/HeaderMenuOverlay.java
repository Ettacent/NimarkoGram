/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.ActionBar;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

public final class HeaderMenuOverlay {
    public interface Bounds {
        void get(RectF out);
    }

    private static final WeakHashMap<View, WeakReference<Binding>> bindings = new WeakHashMap<>();
    private static final WeakHashMap<FrameLayout, WeakReference<Root>> roots = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<Binding>> iconBindings = new WeakHashMap<>();
    private static final BlurredBackgroundColorProvider transparentButtons = new BlurredBackgroundColorProvider() {
        public int getShadowColor() { return 0; }
        public int getBackgroundColor() { return 0; }
        public int getStrokeColorTop() { return 0; }
        public int getStrokeColorBottom() { return 0; }
    };

    private HeaderMenuOverlay() {}

    public static void bind(View anchor, Theme.ResourcesProvider resources) {
        bind(anchor, anchor, resources, null, null);
    }

    public static void bind(View anchor, Theme.ResourcesProvider resources, MenuOverlayHost material) {
        bind(anchor, anchor, resources, material, null);
    }

    public static void bind(View key, View anchor, Theme.ResourcesProvider resources, @Nullable MenuOverlayHost material, @Nullable Bounds bounds) {
        if (key == null || anchor == null) return;
        final WeakReference<Binding> ref = bindings.get(key);
        final Binding previous = ref == null ? null : ref.get();
        if (previous != null && previous.anchor == anchor && previous.material == material && previous.bounds == bounds) {
            if (previous.resources != resources) previous.fallback = BlurredBackgroundProviderImpl.simpleActionBar(resources);
            previous.resources = resources;
            return;
        }
        if (previous != null) {
            previous.release();
            if (bindings.get(key) != ref) return;
        }
        final Binding binding = new Binding(anchor, resources, material, bounds);
        bindings.put(key, new WeakReference<>(binding));
        final View icon = binding.iconView();
        if (icon != null) iconBindings.put(icon, new WeakReference<>(binding));
        anchor.addOnAttachStateChangeListener(binding);
        if (key instanceof ActionBarMenuItem) {
            ((ActionBarMenuItem) key).setCustomHeaderMenuOverlay(binding, binding::overlay);
            ((ActionBarMenuItem) key).setItemOptionsOverlayEnabled(true);
        }
    }

    public static boolean isBound(View key) {
        final WeakReference<Binding> ref = bindings.get(key);
        return ref != null && ref.get() != null;
    }

    public static MenuOverlayHost.SourceGuard captureSource(View key, @Nullable BaseFragment logical) {
        final WeakReference<Binding> ref = bindings.get(key);
        final Binding binding = ref == null ? null : ref.get();
        return new MenuOverlayHost.SourceState(binding, binding == null ? key : binding.anchor, logical);
    }

    public static boolean isDrawingSource(View view) {
        WeakReference<Binding> ref = iconBindings.get(view);
        Binding binding = ref == null ? null : ref.get();
        if (binding == null) {
            ref = bindings.get(view);
            binding = ref == null ? null : ref.get();
            if (binding != null && binding.anchor != view) return false;
        }
        return binding != null && binding.opened != null && binding.drawDepth == 0;
    }

    public static void setSourceDrawingManaged(View key) {
        final WeakReference<Binding> ref = bindings.get(key);
        final Binding binding = ref == null ? null : ref.get();
        if (binding != null) {
            binding.parentDrawsSource = true;
            if (!Float.isNaN(binding.borrowedAlpha)) {
                binding.anchor.setAlpha(binding.borrowedAlpha);
                binding.borrowedAlpha = Float.NaN;
            }
        }
    }

    @Nullable
    public static SimpleActionBarMenuOverlay.MenuSession openItemOptions(View key, ActionBarPopupWindow.ActionBarPopupWindowLayout content, Runnable onClosed) {
        return openItemOptions(key, content, onClosed, null);
    }

    @Nullable
    public static SimpleActionBarMenuOverlay.MenuSession openItemOptions(View key, ActionBarPopupWindow.ActionBarPopupWindowLayout content, Runnable onClosed, @Nullable MenuOverlayHost.SourceGuard request) {
        final WeakReference<Binding> ref = bindings.get(key);
        final Binding binding = ref == null ? null : ref.get();
        if (binding == null) return null;
        if (content == null) return SimpleActionBarMenuOverlay.rejectedItemOptionsMenu(onClosed);
        final MenuOverlayHost.SourceState source = new MenuOverlayHost.SourceState(binding, binding.anchor, null);
        final MenuOverlayHost.SourceGuard current = () -> bindings.get(key) == ref && source.isCurrent() && (request == null || request.isCurrent());
        if (!current.isCurrent()) return SimpleActionBarMenuOverlay.rejectedItemOptionsMenu(onClosed);
        final SimpleActionBarMenuOverlay overlay = binding.overlay();
        if (!current.isCurrent()) return SimpleActionBarMenuOverlay.rejectedItemOptionsMenu(onClosed);
        if (overlay == null) return null;
        if (!overlay.isAttachedToWindow() || overlay.getRootView() != binding.anchor.getRootView()
                || overlay.getWindowToken() == null || overlay.getWindowToken() != binding.anchor.getWindowToken()) {
            return SimpleActionBarMenuOverlay.rejectedItemOptionsMenu(onClosed);
        }
        binding.popupBackground = content.getBackgroundColor();
        if (key instanceof ActionBarMenuItem && binding.anchor == key) return overlay.openItemOptions(binding, (ActionBarMenuItem) key, content, onClosed, current);
        return overlay.openCustomItemOptions(binding, binding.anchor, content, onClosed, current);
    }

    public static boolean close(View view, boolean animated) {
        final WeakReference<Binding> bindingRef = bindings.containsKey(view) ? bindings.get(view) : iconBindings.get(view);
        final Binding binding = bindingRef == null ? null : bindingRef.get();
        if (binding != null) {
            if (binding.opened == null) return false;
            binding.opened.close(animated);
            return true;
        }
        return closeOwned(view, animated);
    }

    public static boolean closeOwned(View owner, boolean animated) {
        final Root root = ownedRoot(owner);
        if (root == null) return false;
        root.overlay.close(animated);
        return true;
    }

    public static boolean hasOwnedMenu(View owner) {
        return ownedRoot(owner) != null;
    }

    @Nullable
    private static Root ownedRoot(View owner) {
        if (owner == null || !(owner.getRootView() instanceof FrameLayout)) return null;
        final WeakReference<Root> ref = roots.get((FrameLayout) owner.getRootView());
        final Root root = ref == null ? null : ref.get();
        if (root == null || root.active == null || root.active.opened != root.overlay) return null;
        for (View v = root.active.anchor; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == owner) {
                return root;
            }
        }
        return null;
    }

    public static int fallbackBackground(MenuOverlayHost host) {
        return host instanceof Binding ? ((Binding) host).colors.getBackgroundColor()
                : Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, host.getMenuResourcesProvider());
    }

    public static int fallbackBackground(MenuOverlayHost host, float progress) {
        return host instanceof Binding ? ColorUtils.blendARGB(host.getButtonsGlassBackgroundColor(), fallbackBackground(host), progress) : fallbackBackground(host);
    }

    private static final class Root implements View.OnAttachStateChangeListener {
        final FrameLayout view;
        final SimpleActionBarMenuOverlay overlay;
        Binding active;

        Root(FrameLayout view) {
            this.view = view;
            overlay = new SimpleActionBarMenuOverlay(view.getContext());
            overlay.addOnAttachStateChangeListener(this);
            view.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        Root(FrameLayout view, SimpleActionBarMenuOverlay overlay) {
            this.view = view;
            this.overlay = overlay;
        }

        public void onViewAttachedToWindow(View view) {}
        public void onViewDetachedFromWindow(View view) { overlay.close(false); }
    }

    private static final class Binding implements MenuOverlayHost, View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnWindowFocusChangeListener {
        final View anchor;
        final MenuOverlayHost material;
        final Bounds bounds;
        final RectF rect = new RectF();
        final Rect visible = new Rect();
        Theme.ResourcesProvider resources;
        BlurredBackgroundProvider fallback;
        Root root;
        SimpleActionBarMenuOverlay opened;
        ViewTreeObserver observer;
        int popupBackground;
        float borrowedAlpha = Float.NaN;
        int drawDepth;
        boolean parentDrawsSource;
        final RectF iconRect = new RectF();
        final BlurredBackgroundProvider colors = new BlurredBackgroundProvider() {
            private BlurredBackgroundColorProvider base() {
                return material != null ? material.getMenuGlassColorProvider() : fallback;
            }
            public int getShadowColor() { final BlurredBackgroundColorProvider p = base(); return p == null ? 0 : p.getShadowColor(); }
            public int getBackgroundColor() { final BlurredBackgroundColorProvider p = base(); return popupBackground != 0 ? popupBackground : p == null ? Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resources) : p.getBackgroundColor(); }
            public int getStrokeColorTop() { final BlurredBackgroundColorProvider p = base(); return p == null ? 0 : p.getStrokeColorTop(); }
            public int getStrokeColorBottom() { final BlurredBackgroundColorProvider p = base(); return p == null ? 0 : p.getStrokeColorBottom(); }
            public int getStrokeColorFull() { final BlurredBackgroundColorProvider p = base(); return p == null ? 0 : p.getStrokeColorFull(); }
            public int getOutlineColor() { final BlurredBackgroundColorProvider p = base(); return p == null ? 0 : p.getOutlineColor(); }
            public int getOutlineShadowColor() { final BlurredBackgroundColorProvider p = base(); return p == null ? 0 : p.getOutlineShadowColor(); }
            public float getOutlineWidth() { final BlurredBackgroundColorProvider p = base(); return p == null ? 0 : p.getOutlineWidth(); }
            public float getShadowRadiusOverride() { final BlurredBackgroundColorProvider p = base(); return p == null ? -1 : p.getShadowRadiusOverride(); }
            public float getShadowDyOverride() { final BlurredBackgroundColorProvider p = base(); return p == null ? -1 : p.getShadowDyOverride(); }
            public float getStrokeWidthTop() { final BlurredBackgroundColorProvider p = base(); return p instanceof BlurredBackgroundProvider ? ((BlurredBackgroundProvider) p).getStrokeWidthTop() : AndroidUtilities.dpf2(1); }
            public float getStrokeWidthBottom() { final BlurredBackgroundColorProvider p = base(); return p instanceof BlurredBackgroundProvider ? ((BlurredBackgroundProvider) p).getStrokeWidthBottom() : AndroidUtilities.dpf2(2f / 3f); }
            public float getShadowRadius() { final BlurredBackgroundColorProvider p = base(); return p instanceof BlurredBackgroundProvider ? ((BlurredBackgroundProvider) p).getShadowRadius() : 0; }
            public float getShadowDx() { final BlurredBackgroundColorProvider p = base(); return p instanceof BlurredBackgroundProvider ? ((BlurredBackgroundProvider) p).getShadowDx() : 0; }
            public float getShadowDy() { final BlurredBackgroundColorProvider p = base(); return p instanceof BlurredBackgroundProvider ? ((BlurredBackgroundProvider) p).getShadowDy() : 0; }
        };

        Binding(View anchor, Theme.ResourcesProvider resources, MenuOverlayHost material, Bounds bounds) {
            this.anchor = anchor;
            this.resources = resources;
            fallback = BlurredBackgroundProviderImpl.simpleActionBar(resources);
            this.material = material;
            this.bounds = bounds;
        }

        boolean eligible() {
            if (!anchor.isAttachedToWindow() || !anchor.isShown() || !anchor.isEnabled() || anchor.getWindowToken() == null
                    || anchor.getWindowVisibility() != View.VISIBLE || anchor.getWidth() <= 0 || anchor.getHeight() <= 0
                    || !anchor.getLocalVisibleRect(visible)) return false;
            for (View v = anchor; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                if (v.getVisibility() != View.VISIBLE || v.getScaleX() == 0 || v.getScaleY() == 0
                        || v.getAlpha() <= 0 && !(v == anchor && !Float.isNaN(borrowedAlpha))) return false;
            }
            if (material != null) {
                final View source = material.getMenuHostView();
                if (source == null || !source.isAttachedToWindow() || source.getRootView() != anchor.getRootView()
                        || source.getWindowToken() != anchor.getWindowToken()) return false;
            }
            return true;
        }

        SimpleActionBarMenuOverlay overlay() {
            if (!eligible() || !(anchor.getRootView() instanceof FrameLayout)) return null;
            if (anchor instanceof ActionBarMenuItem) popupBackground = ((ActionBarMenuItem) anchor).getPopupLayout().getBackgroundColor();
            if (material instanceof ActionBar) {
                final SimpleActionBarMenuOverlay shared = ((ActionBar) material).getHeaderMenuOverlay();
                if (shared != null && shared.getParent() instanceof FrameLayout && shared.isAttachedToWindow()
                        && shared.getRootView() == anchor.getRootView() && shared.getWindowToken() == anchor.getWindowToken()) {
                    root = new Root((FrameLayout) anchor.getRootView(), shared);
                    return shared;
                }
                return shared;
            }
            final FrameLayout view = (FrameLayout) anchor.getRootView();
            final WeakReference<Root> ref = roots.get(view);
            root = ref == null ? null : ref.get();
            if (root == null || root.overlay.getParent() != view) {
                root = new Root(view);
                roots.put(view, new WeakReference<>(root));
            }
            return root.overlay;
        }

        void release() {
            final View icon = iconView();
            final WeakReference<Binding> ref = iconBindings.get(icon);
            if (ref != null && ref.get() == this) iconBindings.remove(icon);
            if (opened != null) opened.close(false);
            anchor.removeOnAttachStateChangeListener(this);
            stopWatching();
        }

        void stopWatching() {
            if (observer != null && observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
                observer.removeOnWindowFocusChangeListener(this);
            }
            observer = null;
        }

        public boolean onPreDraw() {
            if (opened != null && !eligible()) opened.close(false);
            if (opened != null && !Float.isNaN(borrowedAlpha) && anchor.getAlpha() != 0f) {
                borrowedAlpha = anchor.getAlpha();
                anchor.setAlpha(0f);
            }
            return true;
        }

        public void onViewAttachedToWindow(View view) {}
        public void onViewDetachedFromWindow(View view) { if (opened != null) opened.close(false); }
        public void onWindowFocusChanged(boolean hasFocus) { if (!hasFocus && opened != null) opened.close(false); }
        public BlurredBackgroundDrawable createMenuGlass(View owner) { return material == null ? null : material.createMenuGlass(owner); }
        public BaseFragment getMenuOverlaySourceFragment() { return material == null ? null : material.getMenuOverlaySourceFragment(); }
        public boolean isMenuOverlaySourceActive() { return eligible() && (material == null || material.isMenuOverlaySourceActive()); }
        public boolean interpolateMenuColors() { return true; }
        public BlurredBackgroundColorProvider getMenuGlassColorProvider() { return colors; }
        public int getButtonsGlassBackgroundColor() { return material == null ? 0 : material.getButtonsGlassBackgroundColor(); }
        public BlurredBackgroundColorProvider getButtonsGlassColorProvider() { return material == null ? transparentButtons : material.getButtonsGlassColorProvider(); }
        public boolean hasMenuGlassStroke() { return material != null && material.hasMenuGlassStroke(); }
        public void setMenuGlassFrostedProgress(float progress) { if (material != null) material.setMenuGlassFrostedProgress(progress); }

        public boolean getMenuItemGlassRect(ActionBarMenuItem item, RectF out) {
            if (bounds != null) {
                bounds.get(out);
            } else {
                float cx = anchor.getWidth() / 2f, cy = anchor.getHeight() / 2f;
                if (anchor instanceof ImageView) {
                    final ImageView image = (ImageView) anchor;
                    final Drawable drawable = image.getDrawable();
                    if (drawable != null && !drawable.getBounds().isEmpty()) {
                        rect.set(drawable.getBounds());
                        image.getImageMatrix().mapRect(rect);
                        rect.offset(image.getPaddingLeft(), image.getPaddingTop());
                        cx = rect.centerX(); cy = rect.centerY();
                    }
                }
                final float half = Math.min(AndroidUtilities.dp(23), Math.min(anchor.getWidth(), anchor.getHeight()) / 2f);
                out.set(cx - half, cy - half, cx + half, cy + half);
            }
            mapToRoot(anchor, out);
            return false;
        }

        private void mapToRoot(View source, RectF out) {
            View v = source;
            while (root != null && v != root.view) {
                final ViewParent parent = v.getParent();
                if (!(parent instanceof View)) { out.setEmpty(); break; }
                v.getMatrix().mapRect(out);
                out.offset(v.getLeft() - ((View) parent).getScrollX(), v.getTop() - ((View) parent).getScrollY());
                v = (View) parent;
            }
        }

        private View iconView() {
            return anchor instanceof ActionBarMenuItem ? ((ActionBarMenuItem) anchor).getIconView() : anchor;
        }

        public boolean getMenuIconSourceBounds(RectF out) {
            final View icon = iconView();
            if (!(icon instanceof ImageView) && !(icon instanceof IconSource)) return false;
            if (icon instanceof IconSource) ((IconSource) icon).getMenuIconBounds(out);
            else out.set(0, 0, icon.getWidth(), icon.getHeight());
            mapToRoot(icon, out);
            return true;
        }

        private void transformToRoot(Canvas canvas, View view) {
            if (root == null || view == root.view || !(view.getParent() instanceof View)) return;
            final View parent = (View) view.getParent();
            transformToRoot(canvas, parent);
            canvas.translate(view.getLeft() - parent.getScrollX(), view.getTop() - parent.getScrollY());
            canvas.concat(view.getMatrix());
        }

        public void drawMenuIconSource(Canvas canvas) {
            final View icon = iconView();
            final int save = canvas.save();
            drawDepth++;
            try {
                transformToRoot(canvas, icon);
                final float alpha = anchor instanceof ActionBarMenuItem ? 1f : !Float.isNaN(borrowedAlpha) ? borrowedAlpha : icon.getAlpha();
                if (icon instanceof IconSource) ((IconSource) icon).getMenuIconBounds(iconRect);
                else iconRect.set(0, 0, icon.getWidth(), icon.getHeight());
                if (alpha < 1f) canvas.saveLayerAlpha(iconRect, Math.round(alpha * 255));
                if (icon instanceof IconSource) {
                    canvas.clipRect(iconRect);
                    ((IconSource) icon).drawMenuIcon(canvas);
                } else icon.draw(canvas);
            } finally {
                drawDepth--;
                canvas.restoreToCount(save);
            }
        }

        public View getMenuHostView() { return root == null ? anchor : root.view; }
        public View getMenuView() { return bounds == null && anchor instanceof ImageView ? anchor : null; }
        public Theme.ResourcesProvider getMenuResourcesProvider() { return resources; }
        public SimpleActionBarMenuOverlay getOpenMenuOverlay() { return opened; }

        public void setMenuOverlay(SimpleActionBarMenuOverlay overlay, ActionBarMenuItem item) {
            opened = overlay;
            if (anchor instanceof IconSource) ((IconSource) anchor).setMenuIconOverlayOwned(overlay != null);
            if (root != null) {
                if (overlay != null) root.active = this;
                else if (root.active == this) root.active = null;
            }
            if (material != null) material.setMenuOverlay(overlay, null);
            if (overlay != null) {
                stopWatching();
                observer = anchor.getViewTreeObserver();
                observer.addOnPreDrawListener(this);
                observer.addOnWindowFocusChangeListener(this);
                if (anchor.getParent() instanceof ActionBar) parentDrawsSource = true;
                if (anchor instanceof ImageView && bounds == null && !parentDrawsSource) {
                    borrowedAlpha = anchor.getAlpha();
                    anchor.setAlpha(0f);
                }
            } else {
                stopWatching();
                if (!Float.isNaN(borrowedAlpha)) {
                    anchor.setAlpha(borrowedAlpha);
                    borrowedAlpha = Float.NaN;
                }
            }
        }
    }
}
