package org.telegram.ui.Components;

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
    private FrameLayout root;
    private ImageView cover;
    private Bitmap snapshot;
    private ViewTreeObserver observer;
    private ViewTreeObserver.OnPreDrawListener readyListener;
    private Runnable commit;
    private Runnable reveal;
    private int generation;
    private boolean applying;

    public boolean isRunning() {
        return cover != null;
    }

    public boolean isApplying() {
        return applying;
    }

    public void start(FrameLayout parent, Window window, BooleanSupplier valid, Runnable change) {
        cancel();
        if (!valid.getAsBoolean()) return;
        if (Build.VERSION.SDK_INT < 26 || parent.getWidth() <= 0 || parent.getHeight() <= 0
                || (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            applyChange(change);
            return;
        }
        final int token = generation;
        root = parent;
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
            applyChange(change);
            if (token != generation) return;
            if (snapshot == null) {
                cancel();
                return;
            }
            reveal = () -> {
                if (token != generation) return;
                clearReadyListener();
                parent.removeCallbacks(reveal);
                reveal = null;
                view.animate().alpha(0f).setDuration(200)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .withEndAction(() -> { if (token == generation) cancel(); }).start();
            };
            observer = parent.getViewTreeObserver();
            readyListener = () -> {
                if (token != generation) return true;
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
        applying = true;
        try {
            change.run();
        } catch (RuntimeException | Error e) {
            cancel();
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
        }
        commit = reveal = null;
        ImageView old = cover;
        cover = null;
        root = null;
        if (old != null) {
            old.animate().withEndAction(null).cancel();
            old.setImageDrawable(null);
            if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        }
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
        }
    }
}
