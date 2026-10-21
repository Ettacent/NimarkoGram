/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.SmoothRippleDrawable;

public final class IconPackApplyButton extends TextView {
    private final Theme.ResourcesProvider resourcesProvider;
    private final GradientDrawable surface = new GradientDrawable();
    private final SmoothRippleDrawable ripple;
    private ValueAnimator stateAnimator;
    private boolean initialized;
    private int backgroundColor, textColor, targetBackgroundColor, targetTextColor;

    public IconPackApplyButton(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        setTypeface(AndroidUtilities.bold());
        setGravity(Gravity.CENTER);
        setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(14));
        setMinHeight(AndroidUtilities.dp(52));
        surface.setCornerRadius(AndroidUtilities.dp(16));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(AndroidUtilities.dp(16));
        mask.setColor(0xffffffff);
        ripple = new SmoothRippleDrawable(ColorStateList.valueOf(color(Theme.key_listSelector)), surface, mask);
        setBackground(ripple);
    }

    public void setAppliedState(boolean applied, boolean animated) {
        int accent = color(Theme.key_featuredStickers_addButton);
        int nextBackground = applied ? Theme.multAlpha(accent, .12f) : accent;
        int nextText = color(applied ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_featuredStickers_buttonText);
        boolean animate = animated && initialized && isAttachedToWindow() && isLaidOut()
                && SharedConfig.animationsEnabled() && !AndroidUtilities.isAccessibilityTouchExplorationEnabled()
                && (Build.VERSION.SDK_INT >= 26
                ? ValueAnimator.areAnimatorsEnabled() : AndroidUtilities.getAnimatorDurationScale() > 0f);
        ripple.setColor(ColorStateList.valueOf(color(Theme.key_listSelector)));
        if (initialized && targetBackgroundColor == nextBackground && targetTextColor == nextText) {
            if (!animate) finishTransition();
            return;
        }
        cancelTransition();
        targetBackgroundColor = nextBackground;
        targetTextColor = nextText;
        initialized = true;
        if (!animate) {
            applyColors(nextBackground, nextText);
            return;
        }
        final int fromBackground = backgroundColor, fromText = textColor;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        stateAnimator = animator;
        animator.setDuration(220);
        animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        animator.addUpdateListener(value -> {
            if (stateAnimator != animator) return;
            float progress = (float) value.getAnimatedValue();
            applyColors(ColorUtils.blendARGB(fromBackground, nextBackground, progress),
                    ColorUtils.blendARGB(fromText, nextText, progress));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (stateAnimator != animator) return;
                stateAnimator = null;
                applyColors(nextBackground, nextText);
            }
        });
        animator.start();
    }

    private int color(int key) { return Theme.getColor(key, resourcesProvider); }

    private void applyColors(int background, int foreground) {
        backgroundColor = background;
        textColor = foreground;
        surface.setColor(background);
        setTextColor(foreground);
    }

    private void cancelTransition() {
        ValueAnimator previous = stateAnimator;
        stateAnimator = null;
        if (previous != null) previous.cancel();
    }

    public void finishTransition() {
        cancelTransition();
        if (initialized) applyColors(targetBackgroundColor, targetTextColor);
    }

    @Override protected void onDetachedFromWindow() {
        finishTransition();
        super.onDetachedFromWindow();
    }
}
