package app.nimarkogram.messenger.camera;

import java.util.Arrays;


final class CameraXLensFrameTracker {
    private static final int CAPACITY = 32;
    private static final long MAX_AGE_NANOS = 100_000_000L;

    private final CameraXLensFrame[] frames = new CameraXLensFrame[CAPACITY];
    private Object graphToken;
    private int next;

    synchronized Object beginGraph() {
        retireGraph();
        graphToken = new Object();
        return graphToken;
    }

    synchronized void retireGraph() {
        graphToken = null;
        Arrays.fill(frames, null);
        next = 0;
    }

    synchronized boolean isCurrent(Object token) {
        return token != null && token == graphToken;
    }

    synchronized boolean hasActiveGraph() {
        return graphToken != null;
    }

    synchronized void record(Object token, String physicalId, float zoomRatio,
                             long timestampNanos) {
        if (!isCurrent(token) || timestampNanos <= 0) return;
        if (physicalId != null && physicalId.isEmpty()) physicalId = null;
        if (!isValidRatio(zoomRatio)) zoomRatio = Float.NaN;
        if (physicalId == null && Float.isNaN(zoomRatio)) return;
        CameraXLensFrame frame = new CameraXLensFrame(physicalId, zoomRatio, timestampNanos);

        for (int i = 0; i < frames.length; i++) {
            if (frames[i] != null && frames[i].timestampNanos == timestampNanos) {
                frames[i] = frame;
                return;
            }
        }
        frames[next] = frame;
        next = (next + 1) % frames.length;
    }

    synchronized CameraXLensFrame getLensFrame(long surfaceTimestampNanos) {
        if (graphToken == null || surfaceTimestampNanos <= 0) return null;
        CameraXLensFrame best = null;

        for (CameraXLensFrame frame : frames) {
            if (frame == null || frame.timestampNanos > surfaceTimestampNanos
                    || surfaceTimestampNanos - frame.timestampNanos > MAX_AGE_NANOS) {
                continue;
            }
            if (best == null || frame.timestampNanos > best.timestampNanos) best = frame;
        }
        return best;
    }

    static boolean isValidRatio(float ratio) {
        return ratio > 0f && !Float.isNaN(ratio) && !Float.isInfinite(ratio);
    }
}
