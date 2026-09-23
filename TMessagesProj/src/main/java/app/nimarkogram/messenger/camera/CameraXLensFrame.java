package app.nimarkogram.messenger.camera;


public final class CameraXLensFrame {

    public final String physicalId;

    public final float zoomRatio;

    public final long timestampNanos;

    CameraXLensFrame(String physicalId, float zoomRatio, long timestampNanos) {
        this.physicalId = physicalId;
        this.zoomRatio = zoomRatio;
        this.timestampNanos = timestampNanos;
    }
}
