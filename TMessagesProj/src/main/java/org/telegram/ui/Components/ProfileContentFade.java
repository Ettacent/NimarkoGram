package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;

import org.telegram.ui.ActionBar.MaterialSharedAxisMotion;


final class ProfileContentFade {
    private ValueAnimator animator;

    void start(View view) {
        if (animator != null || !view.isAttachedToWindow() || !view.isShown()) return;
        view.setAlpha(0f);
        ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        animator = next;
        next.setDuration(200);
        next.setInterpolator(MaterialSharedAxisMotion::appearanceAlpha);
        next.addUpdateListener(value -> view.setAlpha((float) value.getAnimatedValue()));
        next.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) {
                    animator = null;
                    view.setAlpha(1f);
                }
            }
        });
        next.start();
    }

    void reset(View view) {
        if (animator != null) {
            animator.cancel();
            animator = null;
            view.setAlpha(1f);
        }
    }
}
