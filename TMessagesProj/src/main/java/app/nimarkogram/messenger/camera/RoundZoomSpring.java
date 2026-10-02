/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.camera;

public final class RoundZoomSpring {
    private static final double FREQUENCY = 24.0;
    private double position, target, velocity;
    private float minRatio = .001f, maxRatio = Float.MAX_VALUE;
    private double lower = Math.log(.001f), upper = Math.log(Float.MAX_VALUE);

    public void setBounds(float min, float max) {
        if (!finite(min) || !finite(max) || min <= 0 || max < min)
            throw new IllegalArgumentException("Invalid zoom bounds");
        if (min == minRatio && max == maxRatio) return;
        minRatio = min;
        maxRatio = max;
        lower = Math.log(min);
        upper = Math.log(max);
        double bounded = clamp(position, lower, upper);
        if (bounded != position) velocity = 0;
        position = bounded;
        target = clamp(target, lower, upper);
    }

    public void reset(float ratio) {
        position = target = logRatio(ratio);
        velocity = 0;
    }

    public void target(float ratio) {
        target = logRatio(ratio);
        if (velocity * (target - position) < 0) velocity = 0;
    }

    public float target() { return (float) clamp(Math.exp(target), minRatio, maxRatio); }
    public float value() { return (float) clamp(Math.exp(position), minRatio, maxRatio); }

    public float step(double seconds) {
        if (!finite(seconds)) throw new IllegalArgumentException("Invalid elapsed time");
        double dt = Math.max(0, Math.min(seconds, 0.05));
        double delta = position - target;
        double coefficient = velocity + FREQUENCY * delta;
        double decay = Math.exp(-FREQUENCY * dt);
        double next = target + (delta + coefficient * dt) * decay;
        velocity = (velocity - FREQUENCY * coefficient * dt) * decay;
        if ((next - target) * delta < 0) {
            next = target;
            velocity = 0;
        }
        position = clamp(next, lower, upper);
        if (position != next) velocity = 0;
        if (settled()) { position = target; velocity = 0; }
        return (float) Math.exp(position);
    }

    public boolean settled() {
        return Math.abs(position - target) < 0.00001 && Math.abs(velocity) < 0.0002;
    }

    private double logRatio(float ratio) {
        if (!finite(ratio)) throw new IllegalArgumentException("Invalid zoom ratio");
        return Math.log(clamp(ratio, minRatio, maxRatio));
    }

    private static boolean finite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
}
