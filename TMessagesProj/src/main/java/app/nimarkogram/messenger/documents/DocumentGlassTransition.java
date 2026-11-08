/* Modifications Copyright (C) 2026 Ettacent. */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

final class DocumentGlassTransition {
    static final long DURATION_MS = 180;
    private long epoch;
    private long started = -1;
    private boolean captured;
    private float progress;
    private float startProgress;
    private long duration = DURATION_MS;

    long epoch() { return epoch; }
    float progress() { return progress; }
    boolean running() { return started >= 0 && progress < 1f; }

    void reset() {
        epoch++;
        started = -1;
        captured = false;
        progress = 0f;
        startProgress = 0f;
    }

    void suspend() {
        epoch++;
        started = -1;
        captured = false;
        startProgress = progress;
    }

    boolean captured(long owner, long now, boolean animate) {
        if (owner != epoch || captured) return false;
        captured = true;
        startProgress = progress;
        if (animate && progress < 1f) {
            started = now;
            duration = Math.max(1L, Math.round(DURATION_MS * (1f - progress)));
        } else {
            progress = 1f;
            started = -1;
        }
        return true;
    }

    boolean advance(long owner, long now, boolean animate) {
        if (owner != epoch || !captured) return false;
        float next;
        if (!animate) next = 1f;
        else if (started < 0) return false;
        else {
            float t = Math.max(0f, Math.min(1f, (now - started) / (float) duration));
            next = startProgress + (1f - startProgress) * t * t * (3f - 2f * t);
        }
        next = Math.max(progress, next);
        boolean changed = next != progress;
        progress = next;
        if (progress == 1f) started = -1;
        return changed;
    }
}
