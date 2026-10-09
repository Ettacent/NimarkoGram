/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;


import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;
import android.animation.TimeInterpolator;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

public class AnimatedColor {

    private View parent;
    private Runnable invalidate;
    private int value;
    private int targetValue;
    private boolean firstSet;

    private long transitionDelay = 0;
    private long transitionDuration = 200;
    private TimeInterpolator transitionInterpolator = CubicBezierInterpolator.DEFAULT;
    private boolean transition;
    private long transitionStart;
    private int startValue;

    private long lastVisualTime;
    private int visualEpoch;
    public AnimatedColor() {
        this.parent = null;
        this.firstSet = true;
    }

    public AnimatedColor(long transitionDuration, TimeInterpolator transitionInterpolator) {
        this.parent = null;
        this.transitionDuration = transitionDuration;
        this.transitionInterpolator = transitionInterpolator;
        this.firstSet = true;
    }

    public AnimatedColor(long transitionDelay, long transitionDuration, TimeInterpolator transitionInterpolator) {
        this.parent = null;
        this.transitionDelay = transitionDelay;
        this.transitionDuration = transitionDuration;
        this.transitionInterpolator = transitionInterpolator;
        this.firstSet = true;
    }

    public AnimatedColor(View parentToInvalidate) {
        this.parent = parentToInvalidate;
        this.firstSet = true;
    }

    public AnimatedColor(View parentToInvalidate, long transitionDuration, TimeInterpolator transitionInterpolator) {
        this.parent = parentToInvalidate;
        this.transitionDuration = transitionDuration;
        this.transitionInterpolator = transitionInterpolator;
        this.firstSet = true;
    }

    public AnimatedColor(View parentToInvalidate, long transitionDelay, long transitionDuration, TimeInterpolator transitionInterpolator) {
        this.parent = parentToInvalidate;
        this.transitionDelay = transitionDelay;
        this.transitionDuration = transitionDuration;
        this.transitionInterpolator = transitionInterpolator;
        this.firstSet = true;
    }


    public AnimatedColor(Runnable invalidate) {
        this.invalidate = invalidate;
        this.firstSet = true;
    }

    public AnimatedColor(Runnable invalidate, long transitionDuration, TimeInterpolator transitionInterpolator) {
        this.invalidate = invalidate;
        this.transitionDuration = transitionDuration;
        this.transitionInterpolator = transitionInterpolator;
        this.firstSet = true;
    }

    public AnimatedColor(int initialValue, View parentToInvalidate) {
        this.parent = parentToInvalidate;
        this.value = targetValue = initialValue;
        this.firstSet = false;
    }

    public AnimatedColor(int initialValue, Runnable invalidate) {
        this.invalidate = invalidate;
        this.value = targetValue = initialValue;
        this.firstSet = false;
    }

    public AnimatedColor(int initialValue, View parentToInvalidate, long transitionDelay, long transitionDuration, TimeInterpolator transitionInterpolator) {
        this.parent = parentToInvalidate;
        this.value = targetValue = initialValue;
        this.transitionDelay = transitionDelay;
        this.transitionDuration = transitionDuration;
        this.transitionInterpolator = transitionInterpolator;
        this.firstSet = false;
    }

    public AnimatedColor(int initialValue, Runnable invalidate, long transitionDelay, long transitionDuration, TimeInterpolator transitionInterpolator) {
        this.invalidate = invalidate;
        this.value = targetValue = initialValue;
        this.transitionDelay = transitionDelay;
        this.transitionDuration = transitionDuration;
        this.transitionInterpolator = transitionInterpolator;
        this.firstSet = false;
    }

    public int get() {
        return value;
    }

    public int force(int mustBeColor) {
        return set(mustBeColor, true);
    }

    // set() must be called inside onDraw/dispatchDraw
    // the main purpose of AnimatedColor is to interpolate between abrupt changing states

    public int set(int mustBeColor) {
        return this.set(mustBeColor, false);
    }

    public int set(int mustBeColor, boolean force) {
        final long now = NimarkoUiAnimationClock.now();
        final boolean paused = NimarkoUiAnimationClock.isPaused();
        if (!paused) rebaseVisualTime(now);
        if (force || transitionDuration <= 0 || firstSet) {
            value = targetValue = mustBeColor;
            transition = false;
            firstSet = false;
        } else if (targetValue != mustBeColor) {
            transition = true;
            targetValue = mustBeColor;
            startValue = value;
            transitionStart = now;
            lastVisualTime = now;
        }
        if (transition && !paused) {
            final float t = MathUtils.clamp((now - transitionStart - transitionDelay) / (float) transitionDuration, 0, 1);
            if (now - transitionStart >= transitionDelay) {
                if (transitionInterpolator == null) {
                    value = ColorUtils.blendARGB(startValue, targetValue, t);
                } else {
                    value = ColorUtils.blendARGB(startValue, targetValue, transitionInterpolator.getInterpolation(t));
                }
            }
            if (t >= 1f) {
                transition = false;
            } else {
                if (parent != null) {
                    parent.invalidate();
                }
                if (invalidate != null) {
                    invalidate.run();
                }
            }
        }
        if (!paused) lastVisualTime = now;
        return value;
    }

    private void rebaseVisualTime(long now) {
        int epoch = NimarkoUiAnimationClock.epoch();
        if (transition && lastVisualTime != 0L && epoch != visualEpoch && transitionStart <= lastVisualTime) {
            transitionStart += Math.max(0L, now - lastVisualTime);
            lastVisualTime = now;
        }
        visualEpoch = epoch;
    }

    public float getTransitionProgress() {
        if (!transition) {
            return 0;
        }
        final long now = NimarkoUiAnimationClock.isPaused() ? lastVisualTime : NimarkoUiAnimationClock.now();
        if (!NimarkoUiAnimationClock.isPaused()) rebaseVisualTime(now);
        return MathUtils.clamp((now - transitionStart - transitionDelay) / (float) transitionDuration, 0, 1);
    }

    public float getTransitionProgressInterpolated() {
        if (transitionInterpolator != null) {
            return transitionInterpolator.getInterpolation(getTransitionProgress());
        } else {
            return getTransitionProgress();
        }
    }

    public void setParent(View parent) {
        this.parent = parent;
    }
}