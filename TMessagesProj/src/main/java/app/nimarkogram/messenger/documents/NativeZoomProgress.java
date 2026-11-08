/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

final class NativeZoomProgress {
    private float submitted = 1f;
    private float requested = 1f;
    private float origin = 1f;
    private float confirmed = 1f;
    private float minimum = Float.NaN, maximum = Float.NaN;
    private float lastStep = 1f;
    private int issuedDirection;
    private boolean ready;
    private long observationEpoch;
    private boolean candidateReady;
    private float candidateScale;

    float submitted() { return submitted; }
    float target() { return clip(requested); }
    float origin() { return origin; }
    boolean ready() { return ready; }
    int direction() { return sign(target() - submitted); }
    int issuedDirection() { return issuedDirection; }
    long newObservation() { candidateReady = false; return ++observationEpoch; }
    boolean currentObservation(long token) { return token == observationEpoch; }

    void reset() {
        submitted = requested = origin = confirmed = lastStep = 1f;
        minimum = maximum = Float.NaN;
        issuedDirection = 0;
        ready = false;
        candidateReady = false;
        observationEpoch++;
    }

    void reset(float nativeScale) {
        positive(nativeScale);
        reset();
        submitted = requested = origin = confirmed = nativeScale;
        ready = true;
    }

    void step(float factor) {
        steps(factor, factor);
    }

    void steps(double product, float lastFactor) {
        positive(product);
        positive(lastFactor);
        requested = bound((double) requested * product);
        lastStep = lastFactor;
        trimDebt();
    }

    void resetToScale(float fitRelativeScale) {
        positive(fitRelativeScale);
        requested = fitRelativeScale;
        lastStep = 1f;
        trimDebt();
    }

    float advance(float next) {
        positive(next);
        if (!ready) throw new IllegalStateException("unobserved zoom");
        next = clip(next);
        float delta = next / submitted;
        if (Math.abs(delta - 1f) <= .000001f) return 1f;
        submitted = next;
        issuedDirection = sign(delta - 1f);
        return delta;
    }

    boolean observe(float actual, boolean canZoomIn, boolean canZoomOut) {
        positive(actual);
        if (!ready) {
            submitted = requested = origin = confirmed = actual;
            ready = true;
            return true;
        }
        boolean matched = near(actual, submitted);
        if (!matched) {
            if (issuedDirection > 0 && !canZoomIn && actual < submitted && actual >= confirmed - epsilon(confirmed)) {
                maximum = actual;
            } else if (issuedDirection < 0 && !canZoomOut && actual > submitted && actual <= confirmed + epsilon(confirmed)) {
                minimum = actual;
            } else return false;
        }
        submitted = confirmed = actual;
        issuedDirection = 0;
        trimDebt();
        return true;
    }

    boolean observe(long token, float actual, boolean canZoomIn, boolean canZoomOut) {
        return currentObservation(token) && observe(actual, canZoomIn, canZoomOut);
    }

    boolean observeSnapshot(long token, float actual, float rendererPhysicalScale,
                            float uiPhysicalScale, boolean canZoomIn, boolean canZoomOut) {
        if (!currentObservation(token)) return false;
        boolean aligned = valid(actual) && valid(rendererPhysicalScale) && valid(uiPhysicalScale)
                && Math.abs(rendererPhysicalScale - uiPhysicalScale) <= Math.max(.001f, rendererPhysicalScale * .001f);
        boolean stable = aligned && candidateReady && near(actual, candidateScale);
        candidateReady = aligned;
        candidateScale = actual;
        return stable && observe(token, actual, canZoomIn, canZoomOut);
    }

    void finish() {
        requested = submitted;
        lastStep = 1f;
    }

    private void trimDebt() {
        if (!Float.isNaN(minimum)) requested = Math.max(requested, minimum * Math.min(1f, lastStep));
        if (!Float.isNaN(maximum)) requested = Math.min(requested, maximum * Math.max(1f, lastStep));
    }

    private float clip(float value) {
        if (!Float.isNaN(minimum)) value = Math.max(minimum, value);
        if (!Float.isNaN(maximum)) value = Math.min(maximum, value);
        return value;
    }

    private static float epsilon(float value) { return Math.max(.000001f, Math.abs(value) * .0001f); }
    private static boolean near(float a, float b) { return Math.abs(a - b) <= epsilon(b); }
    private static int sign(float value) { return value > 0 ? 1 : value < 0 ? -1 : 0; }
    private static boolean valid(float value) { return !Float.isNaN(value) && !Float.isInfinite(value) && value > 0; }
    private static void positive(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) throw new IllegalArgumentException("zoom scale");
    }

    private static float bound(double value) {
        positive(value);
        return (float) Math.max(.000001, Math.min(1000000, value));
    }
}
