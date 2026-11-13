/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

public final class ListContentReveal {
    private static final long DURATION_MS = 200;
    private static final long MAX_FRAME_MS = 32;

    private boolean pending;
    private boolean started;
    private long elapsedMs;
    private long lastFrameMs = -1;
    private float alpha = 1f;

    public void start(boolean animated) {
        started = false;
        elapsedMs = 0;
        lastFrameMs = -1;
        pending = animated;
        alpha = animated ? 0f : 1f;
    }

    public float getAlpha() {
        return alpha;
    }

    public float advance(long nowMs) {
        if (!pending) return alpha;
        if (lastFrameMs >= 0 && nowMs > lastFrameMs) {
            elapsedMs += Math.min(MAX_FRAME_MS, nowMs - lastFrameMs);
        }
        started = true;
        lastFrameMs = nowMs;
        if (elapsedMs >= DURATION_MS) {
            finish();
        } else {
            alpha = CubicBezierInterpolator.EASE_OUT.getInterpolation(elapsedMs / (float) DURATION_MS);
        }
        return alpha;
    }

    public void suspend() {
        lastFrameMs = -1;
    }

    public void finish() {
        pending = false;
        alpha = 1f;
        lastFrameMs = -1;
    }

    public boolean isPending() {
        return pending;
    }

    public boolean hasStarted() {
        return started;
    }

    public static boolean isMotionEnabled() {
        return SharedConfig.animationsEnabled()
                && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? ValueAnimator.areAnimatorsEnabled() : AndroidUtilities.getAnimatorDurationScale() > 0f);
    }
}
