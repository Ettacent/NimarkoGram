import app.nimarkogram.messenger.camera.RoundZoomSpring;
import app.nimarkogram.messenger.camera.RoundZoomGestureFilter;

public class RoundZoomSpringHarness {
    public static void main(String[] args) {
        for (int hz : new int[]{60, 120, 240}) {
            RoundZoomGestureFilter filter = new RoundZoomGestureFilter();
            filter.reset(100, 0);
            double error = 0;
            for (int i = 1; i <= hz; i++) {
                float value = filter.update(100 + (i % 2 == 0 ? 0.5f : -0.5f), i * 1000L / hz);
                error += Math.abs(value - 100);
            }
            if (error / hz > 0.15) throw new AssertionError("touch jitter at " + hz);
            filter.reset(0, 0);
            float value = 0;
            for (int i = 1; i <= hz; i++) {
                value = filter.update(200f * i / hz, i * 1000L / hz);
            }
            if (200 - value > 5) throw new AssertionError("drag lag at " + hz);
            for (int i = 1; i <= hz; i++) value = filter.update(200, 1000 + i * 1000L / hz);
            if (Math.abs(value - 200) > 0.001f) throw new AssertionError("filter convergence");
            if (filter.update(500, 1000) != value) throw new AssertionError("stale event");
        }
        for (int fps : new int[]{30, 60, 90, 120, 144}) {
            RoundZoomSpring spring = new RoundZoomSpring();
            spring.reset(0.5f);
            spring.target(8f);
            float previous = 0.5f;
            for (int i = 0; i < fps; i++) {
                float value = spring.step(1.0 / fps);
                if (value < previous || value > 8.00001f) throw new AssertionError("overshoot");
                previous = value;
            }
            if (!spring.settled() || Math.abs(previous - 8f) > 0.001f)
                throw new AssertionError("did not settle at " + fps);
            for (int i = 0; i < 10000; i++) {
                float target = i % 2 == 0 ? 0.5f : 8f;
                spring.target(target);
                float value = spring.step(1.0 / fps);
                if (!Float.isFinite(value) || value < 0.49999f || value > 8.00001f)
                    throw new AssertionError("reverse range");
                if ((value - previous) * (target - previous) < -0.00001f)
                    throw new AssertionError("reverse lag");
                previous = value;
            }
        }
        System.out.println("Round zoom spring: frame rates, convergence, 50000 reversals PASS");
    }
}
