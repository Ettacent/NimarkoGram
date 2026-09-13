package org.telegram.ui.web;

import android.os.Build;
import android.view.View;
import android.webkit.WebView;

import org.telegram.ui.Components.CubicBezierInterpolator;

final class BrowserContentReveal {
    private final View target;
    private WebView webView;
    private int generation;
    private boolean pending;
    private boolean ready;
    private boolean held;
    private Runnable reveal;
    private final Runnable scheduleOnFrame = this::schedule;

    BrowserContentReveal(View target) {
        this.target = target;
    }

    void prepare(WebView view, boolean contentReady) {
        cancel();
        webView = view;
        pending = view != null;
        ready = contentReady;
        target.setAlpha(pending ? 0f : 1f);
        schedule();
    }

    void ready(WebView view) {
        if (view != webView) return;
        ready = true;
        schedule();
    }

    void resume(WebView view, boolean loaded) {
        prepare(view, loaded || view == webView && ready);
    }

    void hold(boolean value) {
        if (held == value) return;
        held = value;
        if (held) {
            if (pending) cancel();
        } else {
            schedule();
        }
    }

    void attached() {
        if (webView == null) return;
        pending = true;
        target.setAlpha(0f);
        target.postOnAnimation(scheduleOnFrame);
    }

    void detached() {
        cancel();
        pending = webView != null;
        target.setAlpha(1f);
    }

    void clear() {
        cancel();
        webView = null;
        pending = ready = false;
        target.setAlpha(1f);
    }

    private void cancel() {
        generation++;
        target.removeCallbacks(scheduleOnFrame);
        if (reveal != null) target.removeCallbacks(reveal);
        reveal = null;
        target.animate().cancel();
    }

    private void schedule() {
        if (!pending || !ready || held || reveal != null || webView == null
                || !target.isAttachedToWindow() || !webView.isAttachedToWindow()) return;
        final int token = generation;
        final WebView view = webView;
        reveal = () -> {
            if (token != generation || view != webView || held || !pending) return;
            target.removeCallbacks(reveal);
            reveal = null;
            if (!target.isAttachedToWindow()) return;
            pending = false;
            target.animate().alpha(1f).setDuration(240L)
                    .setInterpolator(CubicBezierInterpolator.EASE_BOTH)
                    .withLayer().start();
        };
        final Runnable callback = reveal;
        target.postDelayed(callback, 600L);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                view.postVisualStateCallback(token, new WebView.VisualStateCallback() {
                    @Override
                    public void onComplete(long requestId) {
                        if (token != generation || reveal != callback || view != webView) return;
                        target.removeCallbacks(callback);
                        target.postOnAnimation(callback);
                    }
                });
                return;
            } catch (RuntimeException ignored) {
            }
        }
        target.removeCallbacks(callback);
        target.postOnAnimation(callback);
    }
}
