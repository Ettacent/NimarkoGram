package org.telegram.ui.ActionBar;

public final class MaterialSharedAxisMotion {
    static final long DURATION_MS = 360;
    private static final float[] TIMES = {0, 8, 17, 25, 34, 43, 50, 58, 67, 83, 101, 126, 151, 175, 200, 225, 250, 275, 300, 325, 360};
    private static final float[] POSITION = {0, .048f, .119f, .263f, .407f, .556f, .652f, .715f, .759f, .818f, .856f, .896f, .926f, .948f, .963f, .974f, .9815f, .989f, .9926f, .9963f, 1};
    private static final float[] POSITION_SLOPES = slopes(TIMES, POSITION);
    private static final float[] FADE_TIMES = {0, 25, 50, 75, 100, 130, 160, 200};
    private static final float[] ENTER_ALPHA = {0, .04f, .15f, .35f, .56f, .78f, .93f, 1};
    private static final float[] ENTER_SLOPES = slopes(FADE_TIMES, ENTER_ALPHA);

    private MaterialSharedAxisMotion() { }
    public static float appearanceAlpha(float fraction) {
        return enteringAlpha(clamp(fraction) * 200f / DURATION_MS);
    }

    static float clamp(float progress) {
        return Math.max(0f, Math.min(1f, progress));
    }

    static float enteringOffset(boolean forward, float progress) {
        float p = position(progress);
        return (forward ? 1f : -1f) * (1f - p);
    }

    static float leavingOffset(boolean forward, float progress) {
        float p = position(progress);
        return (forward ? -1f : 1f) * p;
    }

    static float enteringAlpha(float phase) { return sample(phase, FADE_TIMES, ENTER_ALPHA, ENTER_SLOPES); }
    static float leavingAlpha(float phase) { return 1f - enteringAlpha(phase); }
    static float topAlpha(boolean enteringOnTop, float mix) { return enteringOnTop ? clamp(mix) : 1f - clamp(mix); }
    static float gestureScale(float progress) { return 1f - .08f * clamp(progress); }
    static float gestureCornerProgress(float progress) {
        float p = clamp(progress / .2f);
        return p * p * (3f - 2f * p);
    }
    static float gestureOffset(float width, float progress) { return Math.max(0f, width) * (1f - gestureScale(progress)) * .4f; }
    static float gestureMix(float progress) { float p = clamp(progress); return p*p*(3f-2f*p); }
    static float slideDistance(float paneWidth) { return Math.max(0f, paneWidth) * .25f; }
    static float position(float phase) { return sample(phase, TIMES, POSITION, POSITION_SLOPES); }

    static float phaseForPosition(float progress) {
        float p = clamp(progress);
        if (p == 0f || p == 1f) return p;
        float lo = 0f, hi = 1f;
        for (int i = 0; i < 20; i++) {
            float mid = (lo + hi) * .5f;
            if (position(mid) < p) lo = mid; else hi = mid;
        }
        return (lo + hi) * .5f;
    }

    private static float sample(float phase, float[] times, float[] values, float[] slopes) {
        float ms = clamp(phase) * DURATION_MS;
        if (ms <= 0) return values[0];
        int last = times.length - 1;
        if (ms >= times[last]) return values[last];
        int i = 0;
        while (ms > times[i + 1]) i++;
        float h = times[i + 1] - times[i], t = (ms - times[i]) / h;
        float t2 = t * t, t3 = t2 * t;
        return clamp((2*t3-3*t2+1)*values[i] + (t3-2*t2+t)*h*slopes[i]
                + (-2*t3+3*t2)*values[i+1] + (t3-t2)*h*slopes[i+1]);
    }

    private static float[] slopes(float[] times, float[] values) {
        float[] result = new float[times.length];
        for (int i = 1; i < times.length - 1; i++) {
            float a = times[i] - times[i-1], b = times[i+1] - times[i];
            float left = (values[i] - values[i-1]) / a, right = (values[i+1] - values[i]) / b;
            if (left * right > 0) {
                float w1 = 2*b+a, w2 = b+2*a;
                result[i] = (w1+w2)/(w1/left+w2/right);
            }
        }
        return result;
    }

    static float dragProgress(float dx, float width) {
        return clamp(dx / Math.max(1f, width));
    }

    static boolean cancelDrag(float progress, float velocityX, float velocityY) {
        if (velocityX < -1000f && -velocityX > Math.abs(velocityY)) return true;
        return progress < 1f / 3f && (velocityX < 3500f || velocityX < Math.abs(velocityY));
    }

    static long settleDuration(float progress, boolean cancel, float velocity, float width) {
        float remaining = Math.abs((cancel ? 0f : 1f) - clamp(progress));
        float towardTarget = cancel ? -velocity : velocity;
        float duration = DURATION_MS * remaining;
        if (towardTarget > 0f) duration = Math.min(duration, 1000f * remaining * Math.max(1f, width) / towardTarget);
        return Math.round(Math.max(80f, Math.min(240f, duration)));
    }

    static float settleProgress(float start, boolean cancel, float velocity, float width, long duration, float fraction) {
        float t = clamp(fraction);
        float delta = (cancel ? 0f : 1f) - start;
        if (Math.abs(delta) < .00001f) return cancel ? 0f : 1f;
        float tangent = Math.max(0f, Math.min(3f, velocity / Math.max(1f, width) * (duration / 1000f) / delta));
        float t2 = t * t;
        float t3 = t2 * t;
        return clamp(start + delta * (-2f * t3 + 3f * t2 + (t3 - 2f * t2 + t) * tangent));
    }
}
