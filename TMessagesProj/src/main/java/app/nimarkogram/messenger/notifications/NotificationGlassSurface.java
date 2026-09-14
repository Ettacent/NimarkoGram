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
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

final class NotificationGlassSurface implements ViewTreeObserver.OnPreDrawListener {
    private final View view;
    private ViewTreeObserver observer;
    private Layer current, incoming;
    private SurfaceDrawable surface;
    private int background, fillColor;
    private final AnimatedColor animatedBackground, animatedFill, animatedStroke, animatedShadow;
    private Theme.ResourcesProvider resourcesProvider;
    private BlurredBackgroundProvider panelColors = BlurredBackgroundProviderImpl.topPanelChatActivity(null);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private long lastFrameTime;
    private final int[] overlayPosition = new int[2];

    NotificationGlassSurface(View view) {
        this.view = view;
        animatedBackground = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
        animatedFill = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
        animatedStroke = new AnimatedColor(view, 180, CubicBezierInterpolator.EASE_BOTH);
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
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        releaseLayers();
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
        if (incoming != null) incoming.updateOffset();
        return true;
    }

    private boolean isNavigationRunning() {
        BaseFragment root = LaunchActivity.getLastFragment();
        return root != null && ((root instanceof ViewPagerActivity
                && ((ViewPagerActivity) root).isPageTransitionRunning())
                || root.getParentLayout() != null && (root.getParentLayout().isTransitionAnimationInProgress()
                || root.getParentLayout().isSwipeInProgress()));
    }

    private void update(float elapsed) {
        BaseFragment active = LaunchActivity.getLastFragmentIncludeMainTabs();
        if (!isNavigationRunning() && active != null && resourcesProvider != active.getResourceProvider()) {
            resourcesProvider = active.getResourceProvider();
            panelColors = BlurredBackgroundProviderImpl.topPanelChatActivity(resourcesProvider);
        }
        background = animatedBackground.set(ColorUtils.setAlphaComponent(panelColors.getBackgroundColor(), 255));
        int nextFill = animatedFill.set(panelColors.getBackgroundColor());
        int stroke = animatedStroke.set(panelColors.getStrokeColorTop());
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
        surface.updateColors(background, stroke);
        if (!view.isAttachedToWindow() || !view.isHardwareAccelerated()
                || Build.VERSION.SDK_INT < 31 || !SharedConfig.chatBlurEnabled()) {
            if (current != null || incoming != null) releaseLayers();
            return;
        }
        if (incoming != null) {
            if (incoming.rendered && incoming.isReady()) {
                progress = Math.min(1f, progress + elapsed / 180f);
            }
            incoming.rendered = false;
            if (progress == 1f) {
                if (current != null) current.release();
                current = incoming;
                incoming = null;
            }
            view.postInvalidateOnAnimation();
        }

        if (isNavigationRunning() || incoming != null) return;
        BaseFragment fragment = LaunchActivity.getLastFragmentIncludeMainTabs();
        if (fragment == null) {
            if (current != null) releaseLayers();
            return;
        }
        if (fragment.getFragmentView() == null || !fragment.getFragmentView().isAttachedToWindow()) return;
        BlurredBackgroundDrawableViewFactory factory = fragment.getNotificationGlassFactory();
        if (factory == null) return;
        View sourceRoot = factory.getSourceRootView();
        if (sourceRoot == null || !sourceRoot.isAttachedToWindow()) return;
        if (current != null && current.factory == factory) {
            sourceRoot.getLocationOnScreen(current.origin);
            return;
        }
        incoming = new Layer(factory, sourceRoot);
        progress = 0;
        view.postInvalidateOnAnimation();
    }

    private final class Layer {
        final BlurredBackgroundDrawableViewFactory factory;
        final BlurredBackgroundDrawable glass;
        final int[] origin = new int[2];
        boolean rendered;

        boolean isReady() {
            BlurredBackgroundSource source = glass.getUnwrappedSource();
            return !(source instanceof BlurredBackgroundSourceRenderNode)
                    || ((BlurredBackgroundSourceRenderNode) source).isDisplayListReady();
        }

        Layer(BlurredBackgroundDrawableViewFactory factory, View sourceRoot) {
            this.factory = factory;
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
        private final GradientDrawable material, sheen;
        private final Paint blendPaint = new Paint();
        private int alpha = 255;
        private int materialColor, strokeColor, strokeWidth;

        SurfaceDrawable() {
            blendPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            material = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{background, background});
            sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.TRANSPARENT, Color.TRANSPARENT});
            sheen.setCornerRadius(AndroidUtilities.dp(18));
            updateColors(background, animatedStroke.get());
        }
        void updateColors(int color, int stroke) {
            int width = Math.max(1, Math.round(panelColors.getStrokeWidthTop()));
            if (materialColor != color) {
                materialColor = color;
                material.setColor(color);
                invalidateSelf();
            }
            if (strokeColor != stroke || strokeWidth != width) {
                strokeColor = stroke;
                strokeWidth = width;
                sheen.setStroke(width, stroke);
                invalidateSelf();
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
            int outer = alpha == 255 ? -1 : canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha);
            if (incoming != null) {
                float eased = progress * progress * (3f - 2f * progress);
                int weight = Math.round(255 * eased);
                int blend = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null);
                int save = canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, 255 - weight);
                drawCurrent(canvas);
                canvas.restoreToCount(save);
                blendPaint.setAlpha(weight);
                save = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, blendPaint);
                incoming.glass.draw(canvas);
                incoming.rendered = true;
                canvas.restoreToCount(save);
                canvas.restoreToCount(blend);
            } else {
                drawCurrent(canvas);
            }
            sheen.draw(canvas);
            if (outer != -1) canvas.restoreToCount(outer);
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
