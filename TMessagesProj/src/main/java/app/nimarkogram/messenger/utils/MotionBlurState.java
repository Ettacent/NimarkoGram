/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

public final class MotionBlurState {
    public static final float MAX_SPAN_PX = 24f;
    public static final float REDUCED_MAX_SPAN_PX = 12f;
    public static final float MAX_SPAN_DP = 8f;
    public static final float MIN_SPAN_PX = 0.75f;
    public static final long EXPOSURE_NS = 12_000_000L;
    private static final long MAX_FRAME_GAP_NS = 120_000_000L;
    private static final float ATTACK_SECONDS = 0.008f;
    private float velocityX;
    private float velocityY;
    private float length;
    private float pendingX;
    private float pendingY;
    private float density = 1f;
    private long lastFrameNs;

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
        final long elapsed = timeNs - lastFrameNs;
        if (elapsed > MAX_FRAME_GAP_NS) {
            reset();
            return;
        }
        lastFrameNs = timeNs;
        final float dx = pendingX;
        final float dy = pendingY;
        pendingX = pendingY = 0f;
        if (dx == 0f && dy == 0f) {
            stop();
            return;
        }
        final double scale = 1_000_000_000d / elapsed;
        final double vx = dx * scale;
        final double vy = dy * scale;
        if (!Double.isFinite(vx) || !Double.isFinite(vy)
                || Math.abs(vx) > Float.MAX_VALUE || Math.abs(vy) > Float.MAX_VALUE) {
            reset();
            return;
        }
        if ((double) dx * velocityX + (double) dy * velocityY < 0d) length = 0f;
        velocityX = (float) vx;
        velocityY = (float) vy;
        final float target = (float) Math.min(Math.min(MAX_SPAN_PX, MAX_SPAN_DP * density),
                Math.hypot(dx, dy) * exposureScale(elapsed));
            length = smoothLength(length, target, elapsed);
    }

    public boolean isActive() {
        return pendingX != 0f || pendingY != 0f || length >= MIN_SPAN_PX;
    }

    public float getVelocityX() { return velocityX; }
    public float getVelocityY() { return velocityY; }
    public float getLength() { return length >= MIN_SPAN_PX ? length : 0f; }

    static float exposureScale(long elapsedNs) {
        return elapsedNs <= 0L ? 0f : (float) Math.min(0.8d, EXPOSURE_NS / (double) elapsedNs);
    }

    static float smoothLength(float previous, float target, long elapsedNs) {
        if (target <= previous) return target;
        final float response = -(float) Math.expm1(-elapsedNs / 1_000_000_000d / ATTACK_SECONDS);
        return previous + (target - previous) * response;
    }

    public void stop() {
        velocityX = velocityY = length = pendingX = pendingY = 0f;
    }

    public void reset() {
        stop();
        lastFrameNs = 0L;
    }
}
