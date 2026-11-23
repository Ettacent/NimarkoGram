/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Cells;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;

import app.nimarkogram.messenger.utils.UiAnimationClock;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

final class SettingsValueTransition {
    private static final int UNBOUND = -1, EMPTY = 0, LOADING = 1, VALUE = 2;
    private static final int DURATION_MS = 200;
    private int target = UNBOUND, skeletonWidth;
    private CharSequence presentedTitle, loadingTitle;
    private boolean revealFirst;
    private float skeleton, value = 1f, fromSkeleton, fromValue;
    private long start = -1;
    private Paint paint;

    static boolean motionEnabled() {
        return !UiAnimationClock.isPaused() && SharedConfig.animationsEnabled()
                && (Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled()
                : AndroidUtilities.getAnimatorDurationScale() > 0f);
    }

    boolean canChangeText(View row, CharSequence title) {
        return target == VALUE && row.isAttachedToWindow() && row.isShown()
                && TextUtils.equals(presentedTitle, title) && motionEnabled();
    }

    void loading(View row, CharSequence title, boolean show, int width, boolean sameRow) {
        if (!sameRow) reset();
        loadingTitle = show ? title : null;
        skeletonWidth = width;
        row.invalidate();
    }

    void revealOnFirstDraw() {
        if (target == UNBOUND) revealFirst = true;
    }

    void reset() {
        target = UNBOUND;
        presentedTitle = null;
        revealFirst = false;
        start = -1;
    }

    void draw(View row, Canvas canvas, CharSequence title, boolean hasValue, int insetDp,
              Theme.ResourcesProvider resources) {
        boolean sameRow = target != UNBOUND && TextUtils.equals(presentedTitle, title);
        if (!sameRow && loadingTitle != null && !TextUtils.equals(loadingTitle, title)) loadingTitle = null;
        int next = loadingTitle != null ? LOADING : hasValue ? VALUE : EMPTY;
        boolean enter = !sameRow && revealFirst && next == VALUE;
        if (!sameRow || next != target) {
            if (!sameRow) {
                skeleton = next == LOADING ? 1f : 0f;
                value = next == VALUE && !enter ? 1f : 0f;
            }
            fromValue = value;
            fromSkeleton = skeleton;
            target = next;
            presentedTitle = title;
            start = sameRow || enter ? UiAnimationClock.now() : -1;
            revealFirst = false;
        }
        if (start < 0 && next != LOADING) return;
        long now = UiAnimationClock.now();
        boolean animate = row.isAttachedToWindow() && row.isShown() && motionEnabled();
        if (start >= 0) {
            float t = animate ? Math.min(1f, Math.max(0f, (now - start) / (float) DURATION_MS)) : 1f;
            float eased = t * t * (3f - 2f * t);
            value = fromValue + ((next == VALUE ? 1f : 0f) - fromValue) * eased;
            skeleton = fromSkeleton + ((next == LOADING ? 1f : 0f) - fromSkeleton) * eased;
            if (t >= 1f) start = -1;
        }
        if (skeleton > 0f) {
            if (paint == null) paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Theme.getColor(Theme.key_dialogSearchBackground, resources));
            float pulse = animate ? .6f + .4f * Math.abs(now % 2000L - 1000L) / 1000f : .8f;
            paint.setAlpha(Math.round(255f * pulse * skeleton));
            int inset = AndroidUtilities.dp(insetDp), width = AndroidUtilities.dp(skeletonWidth);
            int left = LocaleController.isRTL ? inset : row.getWidth() - inset - width;
            int y = row.getHeight() / 2, radius = AndroidUtilities.dp(3);
            canvas.drawRoundRect(left, y - radius, left + width, y + radius, radius, radius, paint);
        }
        if (animate && (start >= 0 || next == LOADING)) row.postInvalidateOnAnimation();
    }

    float valueOpacity() {
        return value;
    }
}
