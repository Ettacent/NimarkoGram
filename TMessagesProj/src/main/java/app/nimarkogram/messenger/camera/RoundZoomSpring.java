package app.nimarkogram.messenger.camera;


public final class RoundZoomSpring {
    private static final double FREQUENCY = 24.0;
    private double position, target, velocity;

    public void reset(float ratio) {
        position = target = Math.log(Math.max(0.001f, ratio));
        velocity = 0;
    }

    public void target(float ratio) {
        target = Math.log(Math.max(0.001f, ratio));

        if (velocity * (target - position) < 0) velocity = 0;
    }

    public float target() { return (float) Math.exp(target); }

    public float step(double seconds) {
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
        position = next;
        if (settled()) { position = target; velocity = 0; }
        return (float) Math.exp(position);
    }

    public boolean settled() {
        return Math.abs(position - target) < 0.00001 && Math.abs(velocity) < 0.0002;
    }
}
