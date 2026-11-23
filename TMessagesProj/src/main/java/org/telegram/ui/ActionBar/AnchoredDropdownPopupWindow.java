/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.View.MeasureSpec;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

final class AnchoredDropdownPopupWindow extends ActionBarPopupWindow {
    private final ActionBarPopupWindowLayout panel;
    private final ActionBarMenuItem owner;
    private final Rect visibleFrame = new Rect();
    private ValueAnimator motion;
    private float progress;
    private boolean opening;
    private boolean motionEnabled = true;

    AnchoredDropdownPopupWindow(ActionBarPopupWindowLayout panel, ActionBarMenuItem owner) {
        super(panel, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        this.panel = panel;
        this.owner = owner;
        setOverlapAnchor(false);
        setEnterTransition(null);
        setExitTransition(null);
        panel.setAnimationEnabled(false);
        panel.setBackAlpha(255);
    }

    void place(int xOffset, boolean show) {
        if (!owner.isAttachedToWindow() || !owner.isShown() || owner.getWindowVisibility() != View.VISIBLE
                || owner.getWindowToken() == null) {
            if (isShowing()) dismiss(false);
            return;
        }
        owner.getWindowVisibleDisplayFrame(visibleFrame);
        final int room = getMaxAvailableHeight(owner, 0);
        if (room <= 0 || visibleFrame.isEmpty()) {
            if (isShowing()) dismiss(false);
            return;
        }
        int widthLimit = Math.max(1, visibleFrame.width() - dp(40));
        panel.measure(MeasureSpec.makeMeasureSpec(widthLimit, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST));
        int width = Math.min(widthLimit, panel.getMeasuredWidth());
        int height = Math.min(room, panel.getMeasuredHeight());
        if (show && !isShowing()) {
            applyProgress(0f);
            setWidth(width);
            setHeight(height);
            super.showAsDropDown(owner, xOffset, 0);
        } else if (isShowing()) {
            super.update(owner, xOffset, 0, width, height);
        }
    }

    boolean isClosing() {
        return isShowing() && !opening && motion != null;
    }

    @Override public void startAnimation() {
        if (isShowing()) animateTo(true, true);
    }

    @Override public void dismiss(boolean animated) {
        animateTo(false, animated);
    }

    @Override public void setAnimationEnabled(boolean enabled) {
        super.setAnimationEnabled(enabled);
        motionEnabled = enabled;
        if (!enabled && motion != null) animateTo(opening, false);
    }

    private boolean shouldAnimate() {
        return motionEnabled && SharedConfig.animationsEnabled() && (Build.VERSION.SDK_INT >= 26
                ? ValueAnimator.areAnimatorsEnabled() : AndroidUtilities.getAnimatorDurationScale() > 0f);
    }

    private void applyProgress(float value) {
        progress = value;
        ActionBarMenuItem.AnchoredDropdownDelegate delegate = owner.dropdownDelegateFor(this);
        if (delegate == null) return;
        panel.setAlpha(value);
        panel.setTranslationY((isAboveAnchor() ? 1f : -1f) * dp(4) * (1f - value));
        delegate.onDropdownProgress(value);
    }

    private void cancelMotion() {
        if (motion == null) return;
        motion.removeAllUpdateListeners();
        motion.removeAllListeners();
        motion.cancel();
        motion = null;
    }

    void onWindowGone(boolean current) {
        cancelMotion();
        opening = false;
        if (current) applyProgress(0f);
    }

    private void animateTo(boolean open, boolean animated) {
        float target = open ? 1f : 0f;
        if (animated && motion != null && opening == open) return;
        cancelMotion();
        opening = open;
        if (!animated || !isShowing() || progress == target || !shouldAnimate()) {
            applyProgress(target);
            if (!open && isShowing()) super.dismiss(false);
            return;
        }
        final ValueAnimator next = ValueAnimator.ofFloat(progress, target);
        motion = next;
        next.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        next.setDuration(Math.max(1, Math.round((open ? 180 : 150) * Math.abs(target - progress))));
        next.addUpdateListener(a -> applyProgress((float) a.getAnimatedValue()));
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (motion != next) return;
                motion = null;
                applyProgress(target);
                if (!open) AnchoredDropdownPopupWindow.super.dismiss(false);
            }
        });
        next.start();
    }
}
