package app.nimarkogram.messenger.wsbypass;

public final class WlRetryGateTest {
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        WlRetryGate gate = new WlRetryGate();
        equal(0, gate.remaining(100_000));
        gate.rejected(100_000, 0);
        equal(5000, gate.remaining(100_000));
        for (int i = 0; i < 48; i++) gate.rejected(100_100, 0);
        equal(4900, gate.remaining(100_100));
        equal(0, gate.remaining(105_000));
        gate.rejected(105_000, 0);
        equal(10_000, gate.remaining(105_000));
        gate.reset();
        gate.rejected(200_000, 30_000);
        equal(30_000, gate.remaining(200_000));
        gate.reset();
        gate.rejected(300_000, Long.MAX_VALUE);
        equal(60_000, gate.remaining(300_000));
        gate.rejected(500_000, 0);
        equal(5000, gate.remaining(500_000));
        gate.reset();
        equal(0, gate.remaining(500_000));
        System.out.println("PASS: WL retry cooldown, concurrent rejection, server delay, cap, recovery and reset");
    }
}
