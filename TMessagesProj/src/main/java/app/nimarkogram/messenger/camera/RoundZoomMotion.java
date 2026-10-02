/* Modifications Copyright (C) 2026 Ettacent */

/**
 * Adapted from Cherrygram's CherryZoomSliderView.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */
package app.nimarkogram.messenger.camera;

public final class RoundZoomMotion {
    private static final double TAU = .150;
    private static final double DRAG_TAU = .090;
    private static final double MAX_STEP = 1.0 / 60;
    private static final double ARRIVAL = .002;
    private static final double DRAG_ARRIVAL = .00002;
    private float minimum = .001f, maximum = Float.MAX_VALUE;
    private double filteredLog, intent = 1, destination = 1, start = 1;
    private double elapsed, duration;
    private boolean directInput;

    public void reset(float value) {
        intent = destination = start = bound(value);
        filteredLog = Math.log(intent);
        elapsed = duration = 0;
        directInput = false;
    }

    public void setBounds(float min, float max) {
        if (!finite(min) || !finite(max) || min <= 0 || max < min)
            throw new IllegalArgumentException("Invalid zoom bounds");
        if (min == minimum && max == maximum) return;
        boolean animated = duration > 0;
        minimum = min;
        maximum = max;
        filteredLog = clamp(filteredLog, Math.log(min), Math.log(max));
        intent = clamp(intent, min, max);
        destination = clamp(destination, min, max);
        elapsed = duration = 0;
        if (animated) beginPreset();
    }

    public void preset(float target) {
        directInput = false;
        destination = bound(target);
        beginPreset();
    }

    private void beginPreset() {
        start = intent;
        elapsed = duration = 0;
        if (Math.abs(destination - intent) < 1e-4) {
            intent = destination;
        } else {
            duration = Math.min(500, Math.rint(Math.max(start, destination)
                    / Math.min(start, destination) * 500 / 3)) / 1000;
        }
    }

    public void drag(float target) {
        directInput = true;
        intent = destination = start = bound(target);
        elapsed = duration = 0;
    }

    public float step(double dt) {
        if (!finite(dt)) throw new IllegalArgumentException("Invalid elapsed time");
        double remaining = Math.max(0, dt);
        while (remaining > 0 && duration > 0) {
            double slice = Math.min(remaining, Math.min(MAX_STEP, duration - elapsed));
            elapsed += slice;
            remaining = Math.max(0, remaining - slice);
            double u = clamp(elapsed / duration, 0, 1);
            intent = clamp(start + (destination - start) * ease(u), minimum, maximum);
            if (elapsed >= duration) {
                intent = destination;
                duration = elapsed = 0;
            }
            filter(slice);
        }
        if (remaining > 0) filter(remaining);
        if (dt > 0 && duration == 0 && Math.abs(Math.log(intent) - filteredLog) < arrival())
            filteredLog = Math.log(intent);
        return value();
    }

    private void filter(double dt) {
        double desired = Math.log(intent), difference = desired - filteredLog;
        if (Math.abs(difference) < arrival()) filteredLog = desired;
        else filteredLog = desired + (filteredLog - desired) * Math.exp(-dt / (directInput ? DRAG_TAU : TAU));
    }

    private double arrival() { return directInput ? DRAG_ARRIVAL : ARRIVAL; }

    public float value() { return (float) clamp(Math.exp(filteredLog), minimum, maximum); }
    public float intent() { return (float) intent; }
    public float target() { return (float) destination; }
    public boolean settled() { return duration == 0 && filteredLog == Math.log(intent); }

    private double bound(float value) {
        if (!finite(value)) throw new IllegalArgumentException("Invalid zoom ratio");
        return clamp(value, minimum, maximum);
    }

    private static double ease(double time) {
        if (time <= 0 || time >= 1) return clamp(time, 0, 1);
        double lo = 0, hi = 1;
        for (int i = 0; i < 32; i++) {
            double t = (lo + hi) / 2;
            if (bezier(t, .25, .25) < time) lo = t;
            else hi = t;
        }
        return bezier((lo + hi) / 2, .1, 1);
    }

    private static double bezier(double t, double a, double b) {
        double s = 1 - t;
        return 3 * s * s * t * a + 3 * s * t * t * b + t * t * t;
    }

    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
    private static boolean finite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
}
