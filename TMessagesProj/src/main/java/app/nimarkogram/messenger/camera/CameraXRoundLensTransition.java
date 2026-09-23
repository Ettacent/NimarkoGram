package app.nimarkogram.messenger.camera;


public final class CameraXRoundLensTransition {
    private static final long METADATA_FALLBACK_MS = 350;
    private boolean submitted;
    private boolean revealed;
    private long sourceTimestamp;
    private long submittedAt;
    private float targetRatio;
    private String sourcePhysicalId;

    public static boolean crossesWideBoundary(float from, float to, float minimum) {
        return finite(from) && finite(to) && finite(minimum) && minimum < 0.999f
                && (from < 1f) != (to < 1f);
    }

    public synchronized void submit(long sourceTimestamp, float targetRatio,
                                    String sourcePhysicalId, long now) {
        this.sourceTimestamp = sourceTimestamp;
        this.targetRatio = targetRatio;
        this.sourcePhysicalId = sourcePhysicalId;
        submittedAt = now;
        submitted = true;
    }

    public synchronized boolean canUpdateZoom(float ratio) {
        return submitted && finite(ratio) && (ratio < 1f) == (targetRatio < 1f);
    }

    public synchronized boolean onFrame(long timestamp, long metadataTimestamp,
                                        float ratio, String physicalId, long now) {
        if (!submitted || revealed || timestamp <= 0 || timestamp <= sourceTimestamp) {
            return false;
        }
        boolean freshMetadata = metadataTimestamp > sourceTimestamp
                && metadataTimestamp <= timestamp;
        boolean targetSide = freshMetadata && finite(ratio)
                && (ratio < 1f) == (targetRatio < 1f);
        boolean changedLens = freshMetadata && sourcePhysicalId != null
                && physicalId != null && !sourcePhysicalId.equals(physicalId);




        boolean fallback = now - submittedAt >= METADATA_FALLBACK_MS;
        if (!(targetSide && (sourcePhysicalId == null || physicalId == null || changedLens))
                && !(changedLens && !finite(ratio)) && !fallback) {
            return false;
        }
        revealed = true;
        return true;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
