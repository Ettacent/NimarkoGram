package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewTreeObserver;

import org.telegram.messenger.SharedConfig;

import org.telegram.ui.ActionBar.MaterialSharedAxisMotion;

final class ProfileContentFade {
    private ValueAnimator animator;
    private View animatingView;
    private boolean presented;
    private View pendingView;
    private ViewTreeObserver pendingObserver;
    private ViewTreeObserver.OnPreDrawListener pendingDraw;
    private int generation;
    void markPresented() {
        View view = pendingView != null ? pendingView : animatingView;
        clearPending();
        if (animator != null) {
            ValueAnimator previous = animator;
            animator = null;
            previous.cancel();
        }
        animatingView = null;
        presented = true;
        if (view != null) view.setAlpha(1f);
    }

    void start(View view) {
        if (!SharedConfig.animationsEnabled()) {
            reset(view);
            presented = true;
            view.setAlpha(1f);
            return;
        }
        if (presented || animator != null || pendingView != null) return;
        view.setAlpha(0f);
        pendingView = view;
        pendingObserver = view.getViewTreeObserver();
        final int expectedGeneration = generation;
        pendingDraw = () -> {
            if (generation != expectedGeneration || pendingView != view) return true;
            if (!view.isAttachedToWindow() || !view.isShown() || view.getWidth() <= 0
                    || view.getHeight() <= 0 || view.isLayoutRequested()) return true;
            clearPending();
            begin(view);
            return true;
        };
        pendingObserver.addOnPreDrawListener(pendingDraw);
        view.invalidate();
    }
    private void clearPending() {
        generation++;
        if (pendingDraw != null) {
            if (pendingObserver != null && pendingObserver.isAlive()) {
                pendingObserver.removeOnPreDrawListener(pendingDraw);
            }
            if (pendingView != null && pendingView.getViewTreeObserver() != pendingObserver
                    && pendingView.getViewTreeObserver().isAlive()) {
                pendingView.getViewTreeObserver().removeOnPreDrawListener(pendingDraw);
            }
        }
        pendingView = null;
        pendingObserver = null;
        pendingDraw = null;
    }
    private void begin(View view) {
        presented = true;
        view.setAlpha(0f);
        ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        animator = next;
        animatingView = view;
        next.setDuration(200);
        next.setInterpolator(MaterialSharedAxisMotion::appearanceAlpha);
        next.addUpdateListener(value -> {
            if (animator != value) return;
            if (!SharedConfig.animationsEnabled()) {
                animator = null;
                animatingView = null;
                value.cancel();
                view.setAlpha(1f);
            } else {
                view.setAlpha((float) value.getAnimatedValue());
            }
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) {
                    animator = null;
                    animatingView = null;
                    view.setAlpha(1f);
                }
            }
        });
        next.start();
    }

    void reset(View view) {
        boolean ownsAlpha = pendingView != null || animator != null;
        clearPending();
        presented = false;
        if (animator != null) {
            ValueAnimator previous = animator;
            animator = null;
            previous.cancel();
        }
        animatingView = null;
        if (ownsAlpha) view.setAlpha(1f);
    }
}
