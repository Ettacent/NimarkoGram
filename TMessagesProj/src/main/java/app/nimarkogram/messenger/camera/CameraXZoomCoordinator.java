package app.nimarkogram.messenger.camera;

import android.os.Looper;
import android.view.Choreographer;

import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.ZoomState;

import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import app.nimarkogram.messenger.NimarkoCameraLog;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

final class CameraXZoomCoordinator {

    private static final float RATIO_EPSILON = 0.0015f;
    private final String operation;
    private final Executor mainExecutor = command -> AndroidUtilities.runOnUIThread(command);
    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        frameCallbackPosted = false;
        dispatchLatest();
    };

    @Nullable private Camera camera;
    private int cameraGeneration;
    private long attachmentToken;
    private boolean ready;
    private boolean frameCallbackPosted;
    private float requestedRatio = Float.NaN;
    private float submittedRatio = Float.NaN;
    @Nullable private ListenableFuture<Void> inFlight;

    CameraXZoomCoordinator(String operation) {
        this.operation = operation;
    }

    void attach(@Nullable Camera camera, int generation) {
        detach();
        this.camera = camera;
        cameraGeneration = generation;
    }

    void detach() {
        ++attachmentToken;
        ready = false;
        camera = null;
        requestedRatio = Float.NaN;
        submittedRatio = Float.NaN;
        inFlight = null;
        if (frameCallbackPosted) {
            frameCallbackPosted = false;
            Runnable removeCallback = () -> {
                try {
                    Choreographer.getInstance().removeFrameCallback(frameCallback);
                } catch (Throwable ignored) {
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                removeCallback.run();
            } else {
                AndroidUtilities.runOnUIThread(removeCallback);
            }
        }
    }

    void setReady(Camera expectedCamera, int generation, boolean ready) {
        if (camera != expectedCamera || cameraGeneration != generation) return;
        this.ready = ready;
        if (ready && isFinite(requestedRatio)) scheduleForNextFrame();
    }

    void requestZoomRatio(float ratio) {
        Camera targetCamera = camera;
        if (targetCamera == null || !isFinite(ratio)) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXZoom request ignored camera="
                    + (targetCamera != null) + " ratio=" + ratio);
            return;
        }
        try {
            ZoomState state = targetCamera.getCameraInfo().getZoomState().getValue();
            if (state == null) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXZoom request ignored: state unavailable ratio="
                        + ratio);
                return;
            }
            requestedRatio = clamp(ratio,
                    state.getMinZoomRatio(), state.getMaxZoomRatio());
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXZoom request ratio=" + ratio
                    + " clamped=" + requestedRatio
                    + " range=" + state.getMinZoomRatio() + ".."
                    + state.getMaxZoomRatio() + " ready=" + ready);
            if (ready) scheduleForNextFrame();
        } catch (Throwable error) {
            FileLog.e(operation + " state lookup failed", error);
        }
    }

    float getRequestedOr(float fallback) {
        return isFinite(requestedRatio) ? requestedRatio : fallback;
    }

    private void scheduleForNextFrame() {
        if (!ready || camera == null || frameCallbackPosted) return;
        frameCallbackPosted = true;
        AndroidUtilities.runOnUIThread(() -> {
            if (!frameCallbackPosted) return;
            try {
                Choreographer.getInstance().postFrameCallback(frameCallback);
            } catch (Throwable error) {
                frameCallbackPosted = false;
                dispatchLatest();
            }
        });
    }

    private void dispatchLatest() {
        final Camera targetCamera = camera;
        if (!ready || targetCamera == null || !isFinite(requestedRatio)) {
            return;
        }
        final float targetRatio = requestedRatio;
        if (isFinite(submittedRatio)
                && Math.abs(targetRatio - submittedRatio) <= RATIO_EPSILON) {
            return;
        }

        final int generation = cameraGeneration;
        final long token = attachmentToken;
        final ListenableFuture<Void> future;
        try {
            submittedRatio = targetRatio;
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXZoom submit ratio=" + targetRatio
                    + " generation=" + generation);
            future = targetCamera.getCameraControl().setZoomRatio(targetRatio);
            inFlight = future;
        } catch (Throwable error) {
            FileLog.e(operation + " submit failed", error);
            return;
        }

        future.addListener(() -> {
            if (token != attachmentToken || targetCamera != camera
                    || generation != cameraGeneration || future != inFlight) {
                return;
            }
            inFlight = null;
            try {
                future.get();
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXZoom applied ratio=" + submittedRatio
                        + " generation=" + generation);
            } catch (Throwable error) {
                Throwable cause = error instanceof ExecutionException
                        && error.getCause() != null ? error.getCause() : error;
                if (!(cause instanceof CancellationException)
                        && !(cause instanceof CameraControl.OperationCanceledException)) {
                    FileLog.e(operation + " failed", cause);
                }
            }
            if (Math.abs(requestedRatio - submittedRatio) > RATIO_EPSILON) {
                scheduleForNextFrame();
            }
        }, mainExecutor);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
