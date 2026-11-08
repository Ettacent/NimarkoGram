/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.notifications;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ViewPagerActivity;
import org.telegram.ui.Components.AnimatedColor;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSource;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceRenderNode;

final class NotificationGlassSurface implements ViewTreeObserver.OnPreDrawListener {
    private final View view;
    private ViewTreeObserver observer;
    private Layer current, incoming;
    private SurfaceDrawable surface;
    private int background, fillColor;
    private final AnimatedColor animatedBackground, animatedFill, animatedStroke, animatedShadow;
    private final AnimatedColor animatedStrokeBottom, animatedStrokeFull;
    private Theme.ResourcesProvider resourcesProvider;
    private BlurredBackgroundProvider panelColors = BlurredBackgroundProviderImpl.topPanelChatActivity(null);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private boolean hasVisibleMaterial;
    private final Rect visibleMaterialBounds = new Rect();
    private long lastFrameTime;
    private final Runnable checkReadiness = this::checkReadiness;
    private final int[] overlayPosition = new int[2];

    NotificationGlassSurface(View view) {
        this.view = view;
        animatedBackground = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
        animatedFill = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
        animatedStroke = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
        animatedStrokeBottom = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
        animatedStrokeFull = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
        animatedShadow = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), AndroidUtilities.dp(18));
            }
        });
        update(0);
    }
    float getShadowOutset() {
        return (float) Math.ceil(3f * panelColors.getShadowRadius()
                + Math.max(Math.abs(panelColors.getShadowDx()), Math.abs(panelColors.getShadowDy())) + 1f);
    }
    void drawShadow(Canvas canvas, float alpha) {
        int color = animatedShadow.get();
        if (alpha <= 0 || Color.alpha(color) == 0) return;
        shadowPaint.setColor(Color.TRANSPARENT);
        shadowPaint.setShadowLayer(panelColors.getShadowRadius(), panelColors.getShadowDx(),
                panelColors.getShadowDy(), Theme.multAlpha(color, Math.min(1f, alpha)));
        float radius = AndroidUtilities.dp(18);
        canvas.drawRoundRect(0, 0, view.getWidth(), view.getHeight(), radius, radius, shadowPaint);
    }

    void attach() {
        detach();
        observer = view.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        lastFrameTime = SystemClock.uptimeMillis();
        update(0);
    }

    void detach() {
        view.removeCallbacks(checkReadiness);
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        releaseLayers();
        hasVisibleMaterial = false;
    }

    private void releaseLayers() {
        if (current != null) current.release();
        if (incoming != null) incoming.release();
        current = incoming = null;
        progress = 0;
        view.invalidate();
    }

    @Override public boolean onPreDraw() {
        if (observer == null) return true;
        long now = SystemClock.uptimeMillis();
        float elapsed = Math.max(0, Math.min(32, now - lastFrameTime));
        lastFrameTime = now;
        update(elapsed);
        view.getLocationOnScreen(overlayPosition);
        if (current != null) current.updateOffset();
        if (incoming != null) {
            incoming.updateOffset();
            incoming.requestReadyFrame();
            view.removeCallbacks(checkReadiness);
            view.post(checkReadiness);
        }
        return true;
    }

    private void checkReadiness() {
        if (observer != null && incoming != null) incoming.requestReadyFrame();
    }

    private boolean canRequestFrame(Layer layer) {
        return observer != null && surface != null && surface.getAlpha() > 0
                && !surface.getBounds().isEmpty()
                && isViewVisible(view) && isViewVisible(layer.sourceRoot);
    }

    private boolean isNavigationRunning() {
        BaseFragment root = LaunchActivity.getLastFragment();
        return root != null && ((root instanceof ViewPagerActivity
                && ((ViewPagerActivity) root).isPageTransitionRunning())
                || root.getParentLayout() != null && (root.getParentLayout().isTransitionAnimationInProgress()
                || root.getParentLayout().isSwipeInProgress()));
    }

    private boolean isViewVisible(View target) {
        if (!target.isAttachedToWindow() || !target.isShown()
                || target.getWindowVisibility() != View.VISIBLE) return false;
        for (View parent = target; parent != null;
             parent = parent.getParent() instanceof View ? (View) parent.getParent() : null) {
            if (parent.getAlpha() <= 0f) return false;
        }
        return target.getGlobalVisibleRect(visibleMaterialBounds);
    }

    private boolean isMaterialVisible(Canvas canvas, Rect bounds) {
        return observer != null && isViewVisible(view)
                && canvas.getClipBounds(visibleMaterialBounds)
                && visibleMaterialBounds.intersect(bounds);
    }
    private void update(float elapsed) {
        BaseFragment active = LaunchActivity.getLastFragmentIncludeMainTabs();
        boolean navigating = isNavigationRunning();
        if (!navigating && active != null && resourcesProvider != active.getResourceProvider()) {
            resourcesProvider = active.getResourceProvider();
            panelColors = BlurredBackgroundProviderImpl.topPanelChatActivity(resourcesProvider);
        }
        background = animatedBackground.set(ColorUtils.setAlphaComponent(panelColors.getBackgroundColor(), 255));
        int nextFill = animatedFill.set(panelColors.getBackgroundColor());
        animatedStroke.set(panelColors.getStrokeColorTop());
        animatedStrokeBottom.set(panelColors.getStrokeColorBottom());
        animatedStrokeFull.set(panelColors.getStrokeColorFull());
        int previousShadow = animatedShadow.get();
        if (previousShadow != animatedShadow.set(panelColors.getShadowColor()) && view.getParent() instanceof View) {
            ((View) view.getParent()).invalidate();
        }
        if (fillColor != nextFill) {
            fillColor = nextFill;
            if (current != null) current.glass.updateColors();
            if (incoming != null) incoming.glass.updateColors();
        }
        if (surface == null) {
            surface = new SurfaceDrawable();
            int l = view.getPaddingLeft(), t = view.getPaddingTop();
            int r = view.getPaddingRight(), b = view.getPaddingBottom();
            view.setBackground(surface);
            view.setPadding(l, t, r, b);
        }
        surface.updateColors(background);
        if (!view.isAttachedToWindow() || !view.isHardwareAccelerated()
                || Build.VERSION.SDK_INT < 31 || !SharedConfig.chatBlurEnabled()) {
            if (current != null || incoming != null) releaseLayers();
            return;
        }
        boolean activeAttached = active != null && active.getFragmentView() != null
                && active.getFragmentView().isAttachedToWindow();
        BlurredBackgroundDrawableViewFactory targetFactory = !navigating && activeAttached
                ? active.getNotificationGlassFactory() : null;
        if (current != null && !current.sourceRoot.isAttachedToWindow()) {
            current.release();
            current = null;
            view.invalidate();
        }
        if (incoming != null && (!incoming.sourceRoot.isAttachedToWindow()
                || (!navigating && activeAttached && incoming.factory != targetFactory))) {
            incoming.release();
            incoming = null;
            progress = 0;
            view.invalidate();
        }
        if (!navigating && activeAttached && targetFactory == null) {
            if (current != null || incoming != null) releaseLayers();
            return;
        }
        if (incoming != null) {
            if (incoming.rendered && incoming.isReady() && canRequestFrame(incoming)) {
                progress = Math.min(1f, progress + elapsed / 180f);
            }
            incoming.rendered = false;
            if (progress == 1f) {
                if (current != null) current.release();
                current = incoming;
                incoming = null;
            }
        }
        if (navigating || incoming != null) return;
        BaseFragment fragment = active;
        if (fragment == null) {
            if (current != null) releaseLayers();
            return;
        }
        if (fragment.getFragmentView() == null || !fragment.getFragmentView().isAttachedToWindow()) return;
        BlurredBackgroundDrawableViewFactory factory = targetFactory;
        if (factory == null) return;
        View sourceRoot = factory.getSourceRootView();
        if (sourceRoot == null || !sourceRoot.isAttachedToWindow()) return;
        if (current != null && current.factory == factory) {
            return;
        }
        incoming = new Layer(factory, sourceRoot);
        progress = 0;
        if (canRequestFrame(incoming)) view.postInvalidateOnAnimation();
    }

    private final class Layer {
        final BlurredBackgroundDrawableViewFactory factory;
        final View sourceRoot;
        final BlurredBackgroundDrawable glass;
        final int[] origin = new int[2];
        boolean rendered, readyFrameRequested;

        void requestReadyFrame() {
            boolean ready = canRequestFrame(this) && isReady();
            if (ready && !readyFrameRequested) view.invalidate();
            readyFrameRequested = ready;
        }

        boolean isReady() {
            GlassSource source = glass.getUnwrappedSource();
            return !(source instanceof GlassSourceRenderNode)
                    || ((GlassSourceRenderNode) source).isDisplayListReady();
        }

        Layer(BlurredBackgroundDrawableViewFactory factory, View sourceRoot) {
            this.factory = factory;
            this.sourceRoot = sourceRoot;
            sourceRoot.getLocationOnScreen(origin);
            glass = factory.createForOverlay(view, new BlurredBackgroundProviderBuilder(null)
                    .setBackgroundColor((r, isDark) -> fillColor)
                    .setStrokeColorTop(0, 0)
                    .setStrokeColorBottom(0, 0)
                    .setShadowColor(0, 0).setShadowLayer(0, 0, 0)
                    .setStrokeWidth(AndroidUtilities.dpf2(.75f), AndroidUtilities.dpf2(.5f))
                    .build());
            glass.setRadius(AndroidUtilities.dp(18)).setPadding(0);
            glass.setAlpha(255);
            glass.setBounds(surface.getBounds());
            glass.setCallback(surface);
            view.getLocationOnScreen(overlayPosition);
            updateOffset();
        }

        void updateOffset() {
            if (!isViewVisible(view) || !isViewVisible(sourceRoot)) return;
            if (sourceRoot.isAttachedToWindow()) sourceRoot.getLocationOnScreen(origin);
            int x = overlayPosition[0] - origin[0], y = overlayPosition[1] - origin[1];
            if (glass.getSourceOffsetX() != x || glass.getSourceOffsetY() != y) {
                glass.setSourceOffset(x, y);
                view.invalidate();
            }
        }

        void release() {
            factory.release(view, glass);
        }
    }

    private final class SurfaceDrawable extends Drawable implements Drawable.Callback {
        private final GradientDrawable material;
        private final BlurredBackgroundDrawable sheen;
        private final Paint blendPaint = new Paint();
        private int alpha = 255;
        private int materialColor;

        private boolean strokeColorsInitialized;
        private int strokeTop, strokeBottom, strokeFull;
        private float strokeWidthTop, strokeWidthBottom;
        SurfaceDrawable() {
            blendPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            material = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{background, background});
            material.setCornerRadius(AndroidUtilities.dp(18));
            GlassSourceColor transparentSource = new GlassSourceColor();
            transparentSource.setColor(Color.TRANSPARENT);
            sheen = BlurredBackgroundDrawableViewFactory.createDrawable(transparentSource);
            sheen.setColorProvider(new BlurredBackgroundProviderBuilder(null) {
                @Override public int getStrokeColorFull() { return animatedStrokeFull.get(); }
            }.setBackgroundColor((r, isDark) -> Color.TRANSPARENT)
                    .setStrokeColorTop((r, isDark) -> animatedStroke.get())
                    .setStrokeColorBottom((r, isDark) -> animatedStrokeBottom.get())
                    .setShadowColor(0, 0).setShadowLayer(0, 0, 0)
                    .setStrokeWidth(panelColors.getStrokeWidthTop(), panelColors.getStrokeWidthBottom())
                    .build());
            sheen.setRadius(AndroidUtilities.dp(18)).setPadding(0);
            sheen.setCallback(this);
            updateColors(background);
        }
        void updateColors(int color) {
            if (materialColor != color) {
                materialColor = color;
                material.setColor(color);
                invalidateSelf();
            }
            int top = animatedStroke.get(), bottom = animatedStrokeBottom.get(), full = animatedStrokeFull.get();
            float widthTop = panelColors.getStrokeWidthTop(), widthBottom = panelColors.getStrokeWidthBottom();
            if (!strokeColorsInitialized || strokeTop != top || strokeBottom != bottom || strokeFull != full
                    || strokeWidthTop != widthTop || strokeWidthBottom != widthBottom) {
                strokeColorsInitialized = true;
                strokeTop = top;
                strokeBottom = bottom;
                strokeFull = full;
                strokeWidthTop = widthTop;
                strokeWidthBottom = widthBottom;
                sheen.setStrokeWidth(widthTop, widthBottom);
            sheen.updateColors();
            }
        }

        @Override protected void onBoundsChange(Rect bounds) {
            material.setBounds(bounds);
            sheen.setBounds(bounds);
            if (current != null) current.glass.setBounds(bounds);
            if (incoming != null) incoming.glass.setBounds(bounds);
        }

        @Override public void draw(Canvas canvas) {
            if (alpha == 0) return;
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            if (!hasVisibleMaterial && incoming != null && incoming.isReady()) {
                if (current != null) current.release();
                current = incoming;
                incoming = null;
                progress = 1f;
            }
            int outer = alpha == 255 ? -1 : canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha);
            if (incoming != null && incoming.isReady()) {
                float eased = progress * progress * (3f - 2f * progress);
                int weight = Math.round(255 * eased);
                int blend = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null);
                int save = canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, 255 - weight);
                drawCurrent(canvas);
                canvas.restoreToCount(save);
                blendPaint.setAlpha(weight);
                save = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, blendPaint);
                incoming.glass.draw(canvas);
                incoming.rendered = isMaterialVisible(canvas, bounds) && isViewVisible(incoming.sourceRoot);
                if (incoming.rendered) view.postInvalidateOnAnimation();
                canvas.restoreToCount(save);
                canvas.restoreToCount(blend);
            } else {
                drawCurrent(canvas);
            }
            sheen.draw(canvas);
            if (outer != -1) canvas.restoreToCount(outer);
            hasVisibleMaterial = hasVisibleMaterial || isMaterialVisible(canvas, bounds);
        }
        private void drawCurrent(Canvas canvas) {
            if (current != null) current.glass.draw(canvas);
            else material.draw(canvas);
        }

        @Override public void setAlpha(int value) { if (alpha != value) { alpha = value; invalidateSelf(); } }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    }
}
