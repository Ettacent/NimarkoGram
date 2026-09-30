package app.nimarkogram.messenger.camera;

public final class RoundZoomGestureFilter {
    private double filtered, previous, speed;
    private long time;

    public void reset(float value, long eventTime) {
        filtered = previous = value;
        speed = 0;
        time = eventTime;
    }

    public float update(float value, long eventTime) {
        if (!Float.isFinite(value) || eventTime <= time) return (float) filtered;
        double dt = (eventTime - time) / 1000.0;
        double derivative = (value - previous) / Math.max(dt, 0.001);
        speed += alpha(dt, 8) * (derivative - speed);
        double cutoff = Math.min(30, 4 + Math.abs(speed) * 0.04);
        filtered += alpha(dt, cutoff) * (value - filtered);
        previous = value;
        time = eventTime;
        return (float) filtered;
    }

    private static double alpha(double dt, double cutoff) {
        return 1 - Math.exp(-2 * Math.PI * cutoff * dt);
    }
}
