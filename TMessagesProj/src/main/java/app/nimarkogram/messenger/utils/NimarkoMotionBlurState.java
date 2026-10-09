/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

public final class NimarkoMotionBlurState {
    private static final long MAX_FRAME_GAP_NS = 120_000_000L;
    private static final long MOTION_WINDOW_NS = 64_000_000L;
    private static final long MAX_SETTLE_NS = 96_000_000L;
    private static final float ATTACK_SECONDS = 0.016f;
    private static final float RELEASE_SECONDS = 0.024f;
    private final float[] samplesX = new float[32];
    private final float[] samplesY = new float[32];
    private final long[] sampleDurations = new long[32];
    private int sampleHead;
    private int sampleCount;
    private float velocityX;
    private float velocityY;
    private float length;
    private float pendingX;
    private float pendingY;
    private float density = 1f;
    private long lastFrameNs;
    private float referenceVelocityX;
    private float referenceVelocityY;
    private long stationaryNs;
    private float idleLength;
    private float idleVelocityX;
    private float idleVelocityY;

    public void prime(long timeNs, float density) {
        if (lastFrameNs == 0L && timeNs > 0L && Float.isFinite(density) && density > 0f) {
            this.density = density;
            lastFrameNs = timeNs;
        }
    }

    public void update(float dx, float dy, long timeNs, float density) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(density)
                || density <= 0f || timeNs <= 0L) {
            reset();
            return;
        }
        if (lastFrameNs != 0L && (timeNs < lastFrameNs || timeNs - lastFrameNs > MAX_FRAME_GAP_NS)
                || this.density != density) {
            reset();
        }
        this.density = density;
        if (lastFrameNs == 0L) lastFrameNs = dx == 0f && dy == 0f ? timeNs : Math.max(1L, timeNs - 16_666_667L);
        pendingX += dx;
        pendingY += dy;
        if (!Float.isFinite(pendingX) || !Float.isFinite(pendingY)) reset();
    }

    public void tick(long timeNs) {
        if (lastFrameNs == 0L || timeNs <= lastFrameNs) return;
        long elapsed = timeNs - lastFrameNs;
        if (elapsed > MAX_FRAME_GAP_NS) {
            reset();
            return;
        }
        float seconds = elapsed / 1_000_000_000f;
        lastFrameNs = timeNs;
        float dx = pendingX;
        float dy = pendingY;
        pendingX = pendingY = 0f;
        if (!Float.isFinite(dx / seconds) || !Float.isFinite(dy / seconds)) {
            reset();
            return;
        }
        boolean moving = dx != 0f || dy != 0f;
        double observedSpeed = Math.hypot(dx, dy) / seconds;
        double previousSpeed = Math.hypot(referenceVelocityX, referenceVelocityY);
        if ((double) dx * referenceVelocityX + (double) dy * referenceVelocityY < 0d
                || moving && previousSpeed > observedSpeed * 2d + 1_000_000_000d / MOTION_WINDOW_NS) {
            sampleHead = sampleCount = 0;
        }
        samplesX[sampleHead] = dx;
        samplesY[sampleHead] = dy;
        sampleDurations[sampleHead] = elapsed;
        sampleHead = (sampleHead + 1) % samplesX.length;
        sampleCount = Math.min(sampleCount + 1, samplesX.length);
        if (moving) {
            stationaryNs = 0L;
            double distance = 0d;
            long duration = 0L;
            for (int i = 0; i < sampleCount && duration < MOTION_WINDOW_NS; i++) {
                int index = (sampleHead - 1 - i + samplesX.length) % samplesX.length;
                long used = Math.min(sampleDurations[index], MOTION_WINDOW_NS - duration);
                double fraction = used / (double) sampleDurations[index];
                distance += Math.hypot(samplesX[index], samplesY[index]) * fraction;
                duration += used;
            }
            double speed = distance * 1_000_000_000d / duration;
            double displacement = Math.hypot(dx, dy);
            velocityX = (float) (dx / displacement * speed);
            velocityY = (float) (dy / displacement * speed);
            float target = (float) Math.min(Math.min(16d, 14d * density), Math.hypot(velocityX, velocityY) * 0.008d);
            length = smoothLength(length, target, elapsed);
            referenceVelocityX = velocityX;
            referenceVelocityY = velocityY;
        } else {
            if (stationaryNs == 0L) {
                idleLength = length;
                idleVelocityX = velocityX;
                idleVelocityY = velocityY;
            }
            stationaryNs += elapsed;
            float decay = releaseFraction(stationaryNs);
            velocityX = idleVelocityX * decay;
            velocityY = idleVelocityY * decay;
            length = idleLength * decay;
        }
        if (!Float.isFinite(length) || !Float.isFinite(velocityX) || !Float.isFinite(velocityY)) {
            reset();
        }
    }

    public boolean isActive() {
        return pendingX != 0f || pendingY != 0f || length > 0f;
    }

    public float getVelocityX() { return velocityX; }
    public float getVelocityY() { return velocityY; }
    public float getLength() { return length; }

    static float smoothLength(float previous, float target, long elapsedNs) {
        float seconds = elapsedNs / 1_000_000_000f;
        float response = -(float) Math.expm1(-seconds / (target > previous ? ATTACK_SECONDS : RELEASE_SECONDS));
        return previous + (target - previous) * response;
    }

    static float releaseFraction(long elapsedNs) {
        if (elapsedNs >= MAX_SETTLE_NS) return 0f;
        double u = Math.max(0d, (elapsedNs - 72_000_000d) / 24_000_000d);
        double taper = 1d - u * u * u * (10d + u * (-15d + 6d * u));
        return (float) (Math.exp(-elapsedNs / 1_000_000_000d / RELEASE_SECONDS) * taper);
    }

    public void stop() {
        velocityX = velocityY = length = pendingX = pendingY = 0f;
        referenceVelocityX = referenceVelocityY = 0f;
        sampleHead = sampleCount = 0;
        stationaryNs = 0L;
        idleLength = idleVelocityX = idleVelocityY = 0f;
    }

    public void reset() {
        stop();
        lastFrameNs = 0L;
    }
}
