package app.nimarkogram.messenger.wsbypass;


final class WlRetryGate {
    private long retryAt;
    private int failures;

    synchronized long remaining(long now) {
        return Math.max(0L, retryAt - now);
    }

    synchronized void rejected(long now, long serverDelayMs) {


        if (now < retryAt) return;
        if (now - retryAt > 60_000L) failures = 0;
        failures = Math.min(5, failures + 1);
        long delay = Math.min(60_000L, 5_000L << (failures - 1));
        retryAt = now + Math.min(60_000L, Math.max(delay, serverDelayMs));
    }

    synchronized void reset() {
        retryAt = 0L;
        failures = 0;
    }
}
