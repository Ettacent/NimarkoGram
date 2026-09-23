package org.telegram.ui.Components;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.function.BooleanSupplier;

public final class AccountSwitchTransition {
    private static final long REVEAL_DURATION_MS = 180;
    public interface Overlay {
        void onCaptured();
        void setProgress(float progress);
        void finish();
    }
    private FrameLayout root;
    private ImageView cover;
    private Bitmap snapshot;
    private ViewTreeObserver observer;
    private ViewTreeObserver.OnPreDrawListener readyListener;
    private Runnable commit;
    private Runnable reveal;
    private ValueAnimator animator;
    private Overlay overlay;
    private View.OnAttachStateChangeListener attachListener;
    private int generation;
    private boolean applying;

    public boolean isRunning() {
        return cover != null;
    }

    public boolean isApplying() {
        return applying;
    }
    public boolean isPreparing() {
        return snapshot != null && animator == null;
    }

    public void start(FrameLayout parent, Window window, BooleanSupplier valid, Runnable change) {
        start(parent, window, valid, change, null);
    }
    public void start(FrameLayout parent, Window window, BooleanSupplier valid, Runnable change, Overlay popup) {
        start(parent, window, valid, change, popup, () -> true);
    }
    public void start(FrameLayout parent, Window window, BooleanSupplier valid, Runnable change, Overlay popup,
                      BooleanSupplier contentReady) {
        final int token = generation + 1;
        cancel();
        if (token != generation || !parent.isAttachedToWindow() || !valid.getAsBoolean()) {
            if (popup != null) popup.finish();
            return;
        }
        overlay = popup;
        if (Build.VERSION.SDK_INT < 26 || parent.getWidth() <= 0 || parent.getHeight() <= 0
                || (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            cancel();
            if (generation == token + 1 && valid.getAsBoolean()) applyChange(change);
            return;
        }
        root = parent;
        attachListener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}
            @Override
            public void onViewDetachedFromWindow(View v) {
                if (token == generation) cancel();
            }
        };
        parent.addOnAttachStateChangeListener(attachListener);
        final ImageView view = cover = new ImageView(parent.getContext());
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setClickable(true);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        commit = () -> {
            if (token != generation) return;
            clearReadyListener();
            parent.removeCallbacks(commit);
            commit = null;
            if (!parent.isAttachedToWindow() || !valid.getAsBoolean()) {
                cancel();
                return;
            }
            if (snapshot == null) {
                cancel();
                if (generation == token + 1 && valid.getAsBoolean()) applyChange(change);
                return;
            }
            try {
                if (popup != null) popup.onCaptured();
                if (token != generation) return;
                if (!valid.getAsBoolean()) {
                    cancel();
                    return;
                }
                applyChange(change);
            } catch (RuntimeException | Error e) {
                if (token == generation) cancel();
                throw e;
            }
            if (token != generation) return;
            reveal = () -> {
                if (token != generation) return;
                clearReadyListener();
                parent.removeCallbacks(reveal);
                reveal = null;
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(REVEAL_DURATION_MS);
                animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                animator.addUpdateListener(animation -> {
                    if (token != generation) return;
                    float progress = (float) animation.getAnimatedValue();
                    view.setAlpha(1f - progress);
                    if (popup != null) popup.setProgress(progress);
                });
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (token == generation) cancel();
                    }
                });
                animator.start();
            };
            observer = parent.getViewTreeObserver();
            readyListener = () -> {
                if (token != generation) return true;
                if (snapshot != null && !contentReady.getAsBoolean()) return true;
                clearReadyListener();
                if (token == generation && reveal != null) {
                    parent.removeCallbacks(reveal);
                    parent.postOnAnimation(reveal);
                }
                return true;
            };
            observer.addOnPreDrawListener(readyListener);
            parent.requestLayout();
            parent.postDelayed(reveal, 400);
        };
        final int width = parent.getWidth(), height = parent.getHeight();
        final double scale = Math.min(1d, Math.sqrt(4_000_000d / ((long) width * height)));
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError e) {
            commit.run();
            return;
        }
        int[] location = new int[2];
        parent.getLocationInWindow(location);
        parent.postDelayed(commit, 120);
        try {
            PixelCopy.request(window, new Rect(location[0], location[1], location[0] + width, location[1] + height), bitmap, result -> {
                if (token != generation || commit == null) {
                    bitmap.recycle();
                    return;
                }
                if (result == PixelCopy.SUCCESS) {
                    snapshot = bitmap;
                    bitmap.prepareToDraw();
                    view.setImageBitmap(bitmap);
                    observer = parent.getViewTreeObserver();
                    readyListener = () -> {
                        if (token != generation || commit == null) return true;
                        clearReadyListener();
                        parent.removeCallbacks(commit);
                        parent.postOnAnimation(commit);
                        return true;
                    };
                    observer.addOnPreDrawListener(readyListener);
                } else {
                    bitmap.recycle();
                    parent.removeCallbacks(commit);
                    parent.postOnAnimation(commit);
                }
            }, parent.getHandler());
        } catch (RuntimeException e) {
            bitmap.recycle();
            commit.run();
        }
    }

    private void applyChange(Runnable change) {
        final int token = generation;
        applying = true;
        try {
            change.run();
        } catch (RuntimeException | Error e) {
            if (token == generation) cancel();
            throw e;
        } finally {
            applying = false;
        }
    }

    private void clearReadyListener() {
        if (observer != null && observer.isAlive() && readyListener != null) {
            observer.removeOnPreDrawListener(readyListener);
        }
        observer = null;
        readyListener = null;
    }

    public void cancel() {
        generation++;
        clearReadyListener();
        if (root != null) {
            if (commit != null) root.removeCallbacks(commit);
            if (reveal != null) root.removeCallbacks(reveal);
            if (attachListener != null) root.removeOnAttachStateChangeListener(attachListener);
        }
        attachListener = null;
        commit = reveal = null;
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
            animator = null;
        }
        Overlay oldOverlay = overlay;
        overlay = null;
        ImageView old = cover;
        cover = null;
        root = null;
        if (old != null) {
            old.setImageDrawable(null);
            if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        }
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
        }
        if (oldOverlay != null) oldOverlay.finish();
    }
}
