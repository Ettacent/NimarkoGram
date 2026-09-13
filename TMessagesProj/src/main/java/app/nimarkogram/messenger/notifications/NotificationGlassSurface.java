package app.nimarkogram.messenger.notifications;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
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
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

final class NotificationGlassSurface implements ViewTreeObserver.OnPreDrawListener {
    private final View view;
    private ViewTreeObserver observer;
    private Layer current, incoming;
    private SurfaceDrawable surface;
    private int background, accent;
    private float progress;
    private long lastFrameTime;
    private final int[] overlayPosition = new int[2];

    NotificationGlassSurface(View view) {
        this.view = view;
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), AndroidUtilities.dp(24));
            }
        });
        update(0);
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
        int nextBackground = Theme.getColor(Theme.key_windowBackgroundWhite);
        int nextAccent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        if (surface == null || background != nextBackground || accent != nextAccent) {
            releaseLayers();
            background = nextBackground;
            accent = nextAccent;
            surface = new SurfaceDrawable();
            int l = view.getPaddingLeft(), t = view.getPaddingTop();
            int r = view.getPaddingRight(), b = view.getPaddingBottom();
            view.setBackground(surface);
            view.setPadding(l, t, r, b);
        }
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
            boolean dark = ColorUtils.calculateLuminance(background) < .5;
            final int fill = ColorUtils.blendARGB(background, accent, dark ? .035f : .02f);
            glass = factory.createForOverlay(view, new BlurredBackgroundProviderBuilder(null)
                    .setBackgroundColor((r, isDark) -> Theme.multAlpha(fill, dark ? .90f : .86f))
                    .setStrokeColorTop(0xB0FFFFFF, 0x38FFFFFF)
                    .setStrokeColorBottom(0x18000000, 0x12FFFFFF)
                    .setShadowColor(0, 0).setShadowLayer(0, 0, 0)
                    .setStrokeWidth(AndroidUtilities.dpf2(.75f), AndroidUtilities.dpf2(.5f))
                    .build());
            glass.setRadius(AndroidUtilities.dp(24)).setPadding(0);
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
        private int alpha = 255;

        SurfaceDrawable() {
            boolean dark = ColorUtils.calculateLuminance(background) < .5;
            material = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{ColorUtils.blendARGB(background, Color.WHITE, dark ? .055f : .22f),
                            ColorUtils.blendARGB(background, accent, dark ? .09f : .055f)});
            sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{dark ? 0x0CFFFFFF : 0x22FFFFFF, Color.TRANSPARENT,
                            ColorUtils.setAlphaComponent(accent, dark ? 10 : 6)});
            sheen.setCornerRadius(AndroidUtilities.dp(24));
            sheen.setStroke(AndroidUtilities.dp(1), dark ? 0x28FFFFFF : 0xB0FFFFFF);
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
            material.draw(canvas);
            if (current != null) current.glass.draw(canvas);
            if (incoming != null) {
                float eased = progress * progress * (3f - 2f * progress);
                int save = canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, Math.round(255 * eased));

                material.draw(canvas);
                incoming.glass.draw(canvas);
                incoming.rendered = true;
                canvas.restoreToCount(save);
            }
            sheen.draw(canvas);
            if (outer != -1) canvas.restoreToCount(outer);
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
