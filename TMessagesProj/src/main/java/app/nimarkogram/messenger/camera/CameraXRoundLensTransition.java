/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.camera;

public final class CameraXRoundLensTransition {
    public static final float MAX_STRENGTH = 0.72f;
    private static final long RECOVERY_MS = 1000;
    private static final long REVEAL_NANOS = 160_000_000L;
    private final long sourceTimestamp;
    private final long submittedAt;
    private final boolean targetWide;
    private final String sourcePhysicalId;
    private final boolean sourceAlreadyOnTargetSide;
    private float softenFrom;
    private long softenAt;
    private boolean recoveryRelease;
    private String confirmedPhysicalId;
    private String lastPhysicalId;
    private long releaseAt = -1;
    private float releaseStrength;
    private float strength;
    private long lastTimestamp;
    private long revealStart;

    public CameraXRoundLensTransition(long sourceTimestamp, float targetRatio,
            float sourceRatio, String sourcePhysicalId, long now, float initialStrength) {
        this.sourceTimestamp = sourceTimestamp;
        this.targetWide = targetRatio < 1f;
        this.sourcePhysicalId = sourcePhysicalId;
        this.lastPhysicalId = sourcePhysicalId;
        this.sourceAlreadyOnTargetSide = finite(sourceRatio) && (sourceRatio < 1f) == targetWide;
        this.submittedAt = now;
        this.softenFrom = Math.max(0f, Math.min(MAX_STRENGTH, initialStrength));
        this.softenAt = sourceTimestamp;
        this.strength = this.softenFrom;
    }

    public static boolean crossesWideBoundary(float from, float to, float minimum) {
        return finite(from) && finite(to) && finite(minimum) && minimum < 0.999f
                && (from < 1f) != (to < 1f);
    }

    public synchronized float getStrength() {
        return strength;
    }

    public synchronized void setRevealStart(long timestamp) {
        if (releaseAt < 0) revealStart = timestamp;
    }

    public synchronized float onFrame(long timestamp, long metadataTimestamp,
            float ratio, String physicalId, long now) {
        if (timestamp <= sourceTimestamp || timestamp <= lastTimestamp) return strength;
        lastTimestamp = timestamp;
        boolean fresh = metadataTimestamp > sourceTimestamp && metadataTimestamp <= timestamp
                && timestamp - metadataTimestamp <= 100_000_000L;
        boolean targetSide = fresh && finite(ratio) && (ratio < 1f) == targetWide;
        boolean physicalChanged = fresh && lastPhysicalId != null && physicalId != null
                && !lastPhysicalId.equals(physicalId);
        if (fresh && physicalId != null) lastPhysicalId = physicalId;
        boolean changed = sourcePhysicalId != null && physicalId != null
                && !sourcePhysicalId.equals(physicalId);
        boolean contradictory = fresh && ((physicalId == null || confirmedPhysicalId == null)
                && finite(ratio) && !targetSide
                || confirmedPhysicalId != null && physicalId != null
                && !confirmedPhysicalId.equals(physicalId));
        if (releaseAt >= 0 && !recoveryRelease && contradictory
                && now - submittedAt < RECOVERY_MS) {
            strength = releaseStrength * (1f - ease(timestamp - releaseAt, REVEAL_NANOS));
            softenFrom = strength;
            softenAt = timestamp;
            releaseAt = -1;
            confirmedPhysicalId = null;
        }
        if (releaseAt < 0) {
            strength = softenFrom * (1f - ease(timestamp - softenAt, REVEAL_NANOS));
            boolean ready = physicalChanged || targetSide && (changed || sourceAlreadyOnTargetSide
                    || sourcePhysicalId == null || physicalId == null)
                    || fresh && !finite(ratio) && changed;
            if (ready || now - submittedAt >= RECOVERY_MS) {
                if (ready && (!sourceAlreadyOnTargetSide || changed || physicalChanged)) strength = MAX_STRENGTH;
                releaseAt = revealStart > 0 ? Math.min(timestamp, revealStart) : timestamp;
                releaseStrength = strength;
                strength = releaseStrength * (1f - ease(timestamp - releaseAt, REVEAL_NANOS));
                confirmedPhysicalId = physicalId;
                recoveryRelease = now - submittedAt >= RECOVERY_MS;
            }
        } else {
            strength = releaseStrength * (1f - ease(timestamp - releaseAt, REVEAL_NANOS));
        }
        return strength;
    }

    private static float ease(long elapsed, long duration) {
        float t = Math.max(0f, Math.min(1f, (float) elapsed / duration));
        return t * t * (3f - 2f * t);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
