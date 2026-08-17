package app.nimarkogram.messenger.camera;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.AndroidUtilities;

import app.nimarkogram.messenger.NimarkoCameraLog;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

public final class NimarkoCameraXSurfaceSession {

    private static final AtomicInteger NEXT_RENDER_ID = new AtomicInteger();

    public interface Callback {
        void onReady(int width, int height);
        void onFailure(Throwable error);
    }

    private final Context context;
    private final SurfaceTexture surfaceTexture;
    private final Callback callback;
    private final NimarkoCameraXController controller;
    private final int renderId = NEXT_RENDER_ID.updateAndGet(value ->
            value >= Integer.MAX_VALUE - 1 ? 1 : value + 1);
    private volatile boolean closed;
    private volatile int previewWidth;
    private volatile int previewHeight;
    private volatile int rotationDegrees;
    private volatile boolean mirrored;
    private volatile boolean torchEnabled;
    private volatile boolean hasCameraTransform;
    private volatile boolean hasTransformationInfo;
    private volatile int surfaceRequestGeneration;
    private volatile int captureTargetRotation;
    @Nullable private final OrientationEventListener orientationListener;
    private final Object surfaceReleaseLock = new Object();
    private int providedSurfaceCount;
    @Nullable private Runnable closeCompletion;
    @Nullable private Runnable rebindCompletion;

    public NimarkoCameraXSurfaceSession(Context context, SurfaceTexture surfaceTexture,
                                        boolean frontFacing, int targetWidth, int targetHeight,
                                        Callback callback) {
        this(context, surfaceTexture, frontFacing, targetWidth, targetHeight,
                false, false, false, callback);
    }

    public NimarkoCameraXSurfaceSession(Context context, SurfaceTexture surfaceTexture,
                                        boolean frontFacing, int targetWidth, int targetHeight,
                                        boolean enableImageCapture, Callback callback) {
        this(context, surfaceTexture, frontFacing, targetWidth, targetHeight,
                enableImageCapture, false, false, callback);
    }

    public NimarkoCameraXSurfaceSession(Context context, SurfaceTexture surfaceTexture,
                                        boolean frontFacing, int targetWidth, int targetHeight,
                                        boolean enableImageCapture, boolean deferInitialBind,
                                        Callback callback) {
        this(context, surfaceTexture, frontFacing, targetWidth, targetHeight,
                enableImageCapture, deferInitialBind, false, callback);
    }

    public NimarkoCameraXSurfaceSession(Context context, SurfaceTexture surfaceTexture,
                                        boolean frontFacing, int targetWidth, int targetHeight,
                                        boolean enableImageCapture, boolean deferInitialBind,
                                        boolean normalizePortraitPhoto, Callback callback) {
        this(context, surfaceTexture, frontFacing, targetWidth, targetHeight,
                enableImageCapture, deferInitialBind, normalizePortraitPhoto,
                true, callback);
    }

    public NimarkoCameraXSurfaceSession(Context context, SurfaceTexture surfaceTexture,
                                        boolean frontFacing, int targetWidth, int targetHeight,
                                        boolean enableImageCapture, boolean deferInitialBind,
                                        boolean normalizePortraitPhoto,
                                        boolean useConfiguredUltraWide,
                                        Callback callback) {
        this.context = context.getApplicationContext();
        this.surfaceTexture = surfaceTexture;
        this.callback = callback;
        previewWidth = Math.max(1, targetWidth);
        previewHeight = Math.max(1, targetHeight);
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface create renderId=" + renderId
                + " front=" + frontFacing + " requested=" + targetWidth + "x" + targetHeight
                + " normalized=" + previewWidth + "x" + previewHeight
                + " imageCapture=" + enableImageCapture
                + " deferBind=" + deferInitialBind
                + " normalizePortrait=" + normalizePortraitPhoto
                + " configuredWide=" + useConfiguredUltraWide);
        final int captureWidth = normalizePortraitPhoto
                ? Math.min(previewWidth, previewHeight) : previewWidth;
        final int captureHeight = normalizePortraitPhoto
                ? Math.max(previewWidth, previewHeight) : previewHeight;
        NimarkoCameraXController.CameraLifecycle lifecycle =
                new NimarkoCameraXController.CameraLifecycle();
        Preview.SurfaceProvider provider = this::provideSurface;
        controller = new NimarkoCameraXController(
                lifecycle,
                new SurfaceOrientedMeteringPointFactory(captureWidth, captureHeight),
                provider,
                new Size(captureWidth, captureHeight),
                enableImageCapture,
                deferInitialBind);
        controller.setUseConfiguredUltraWide(useConfiguredUltraWide);
        captureTargetRotation = getDisplayRotation();
        controller.setTargetOrientation(captureTargetRotation);
        
        if (enableImageCapture) {
            orientationListener = new OrientationEventListener(this.context) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (closed || orientation == ORIENTATION_UNKNOWN) return;
                    final int rotation;
                    if (orientation >= 45 && orientation < 135) {
                        rotation = Surface.ROTATION_270;
                    } else if (orientation >= 135 && orientation < 225) {
                        rotation = Surface.ROTATION_180;
                    } else if (orientation >= 225 && orientation < 315) {
                        rotation = Surface.ROTATION_90;
                    } else {
                        rotation = Surface.ROTATION_0;
                    }
                    if (captureTargetRotation != rotation) {
                        captureTargetRotation = rotation;
                        controller.setImageCaptureTargetOrientation(rotation);
                    }
                }
            };
            if (orientationListener.canDetectOrientation()) {
                orientationListener.enable();
            }
        } else {
            
            orientationListener = null;
        }
        controller.initCamera(this.context, frontFacing,
                () -> notifyReady(),
                this::notifyFailure);
    }

    @android.annotation.SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private void provideSurface(SurfaceRequest request) {
        if (closed) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface request rejected: closed renderId=" + renderId);
            request.willNotProvideSurface();
            return;
        }
        Size resolution = request.getResolution();
        final int requestGeneration = ++surfaceRequestGeneration;
        String requestCameraId;
        try {
            requestCameraId = Camera2CameraInfo.from(
                    request.getCamera().getCameraInfo()).getCameraId();
        } catch (Throwable error) {
            requestCameraId = "error:" + error.getClass().getSimpleName();
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface request renderId=" + renderId
                + " generation=" + requestGeneration + " resolution=" + resolution
                + " ownerFront=" + controller.isFrontface()
                + " requestCamera=" + requestCameraId
                + " requestPrimary=" + request.isPrimary()
                + " expectedFps=" + request.getExpectedFrameRate()
                + " controller=" + Integer.toHexString(
                System.identityHashCode(controller)));
        previewWidth = resolution.getWidth();
        previewHeight = resolution.getHeight();
        boolean registeredSurface = false;
        Surface surface = null;
        try {
            request.setTransformationInfoListener(ContextCompat.getMainExecutor(context), info -> {
                if (closed || requestGeneration != surfaceRequestGeneration) return;
                rotationDegrees = ((info.getRotationDegrees() % 360) + 360) % 360;
                mirrored = info.isMirroring();
                hasCameraTransform = info.hasCameraTransform();
                hasTransformationInfo = true;
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface transform renderId=" + renderId
                        + " generation=" + requestGeneration
                        + " rotation=" + rotationDegrees + " mirrored=" + mirrored
                        + " cameraTransform=" + hasCameraTransform
                        + " crop=" + info.getCropRect());
                notifyReady();
            });
            surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight);
            surface = new Surface(surfaceTexture);
            boolean rejectClosedSurface;
            synchronized (surfaceReleaseLock) {
                rejectClosedSurface = closed;
                if (!rejectClosedSurface) {
                    providedSurfaceCount++;
                }
            }
            if (rejectClosedSurface) {
                surface.release();
                request.willNotProvideSurface();
                return;
            }
            registeredSurface = true;
            final Surface suppliedSurface = surface;
            request.provideSurface(suppliedSurface, ContextCompat.getMainExecutor(context), result -> {
                try { suppliedSurface.release(); } catch (Throwable ignored) {}
                onProvidedSurfaceReleased();
                int resultCode = result.getResultCode();
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface released renderId=" + renderId
                        + " generation=" + requestGeneration + " result=" + resultCode
                        + " remaining=" + providedSurfaceCount);
                if (!closed && requestGeneration == surfaceRequestGeneration
                        && resultCode != SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY
                        && resultCode != SurfaceRequest.Result.RESULT_REQUEST_CANCELLED) {
                    notifyFailure(new IllegalStateException(
                            "CameraX rejected preview surface, result=" + resultCode));
                }
            });
        } catch (Throwable t) {
            if (surface != null) {
                try { surface.release(); } catch (Throwable ignored) {}
            }
            if (registeredSurface) {
                onProvidedSurfaceReleased();
            }
            FileLog.e(t);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface provide FAILED renderId=" + renderId
                    + " generation=" + requestGeneration, t);
            request.willNotProvideSurface();
            notifyFailure(t);
        }
    }

    private void notifyReady() {
        if (closed || callback == null) return;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface ready renderId=" + renderId
                + " size=" + previewWidth + "x" + previewHeight
                + " transform=" + hasTransformationInfo
                + " cameraTransform=" + hasCameraTransform
                + " rotation=" + rotationDegrees + " mirrored=" + mirrored);
        try {
            callback.onReady(previewWidth, previewHeight);
        } catch (Throwable callbackError) {
            FileLog.e("CameraX surface ready callback failed", callbackError);
        }
    }

    private void notifyFailure(Throwable error) {
        if (closed || callback == null) return;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface failure renderId=" + renderId, error);
        try {
            callback.onFailure(error);
        } catch (Throwable callbackError) {
            FileLog.e("CameraX surface failure callback failed", callbackError);
        }
    }

    private void onProvidedSurfaceReleased() {
        Runnable completion = null;
        synchronized (surfaceReleaseLock) {
            providedSurfaceCount = Math.max(0, providedSurfaceCount - 1);
            if (closed && providedSurfaceCount == 0 && closeCompletion != null) {
                completion = closeCompletion;
                closeCompletion = null;
            } else if (!closed && providedSurfaceCount == 0 && rebindCompletion != null) {
                completion = rebindCompletion;
                rebindCompletion = null;
            }
        }
        if (completion != null) {
            try {
                completion.run();
            } catch (Throwable completionError) {
                FileLog.e("CameraX surface completion failed", completionError);
            }
        }
    }

    public void releaseSurfaceForRebind(Runnable onReleased) {
        if (onReleased == null) return;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface releaseForRebind renderId=" + renderId
                + " provided=" + providedSurfaceCount + " closed=" + closed);
        boolean runImmediately = false;
        synchronized (surfaceReleaseLock) {
            if (closed) {
                runImmediately = true;
            } else {
                surfaceRequestGeneration++;
                Runnable previous = rebindCompletion;
                rebindCompletion = previous == null ? onReleased : () -> {
                    previous.run();
                    onReleased.run();
                };
            }
        }
        if (runImmediately) {
            onReleased.run();
            return;
        }
        controller.unbindForSurfaceRebind();
        Runnable completion = null;
        synchronized (surfaceReleaseLock) {
            if (!closed && providedSurfaceCount == 0 && rebindCompletion != null) {
                completion = rebindCompletion;
                rebindCompletion = null;
            }
        }
        if (completion != null) completion.run();
    }

    public boolean isInitiated() {
        return !closed && controller.isInitiated();
    }

    public NimarkoCameraXController getController() {
        return controller;
    }

    public boolean isFrontFacing() {
        return controller.isFrontface();
    }

    public int getCameraId() {
        
        return renderId;
    }

    public int getWorldAngle() {
        
        int clockwise = hasCameraTransform ? getDisplayRotationDegrees() : rotationDegrees;
        return (360 - clockwise) % 360;
    }

    public int getDisplayOrientation() {
        return getWorldAngle();
    }

    public int getCurrentOrientation() {
        return rotationDegrees;
    }

    public boolean isMirrored() {
        
        return hasTransformationInfo && mirrored;
    }

    public boolean hasCameraTransform() {
        return hasCameraTransform;
    }

    public boolean hasTransformationInfo() {
        return hasTransformationInfo;
    }

    public int getPreviewWidth() {
        return previewWidth;
    }

    public int getPreviewHeight() {
        return previewHeight;
    }

    public void switchCamera(boolean frontFacing) {
        if (!closed) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface switch renderId=" + renderId
                    + " fromFront=" + controller.isFrontface() + " toFront=" + frontFacing);
            
            hasCameraTransform = false;
            hasTransformationInfo = false;
            mirrored = false;
            rotationDegrees = 0;
            controller.setFrontFace(frontFacing);
        }
    }

    public void setZoom(float zoom) {
        if (!closed) controller.setZoom(zoom);
    }

    public void setZoomRatio(float ratio) {
        if (!closed) controller.setZoomRatio(ratio);
    }

    public float getZoomRatio() {
        return closed ? 1f : controller.getZoomRatio();
    }

    public float getObservedZoomRatio() {
        return closed ? 1f : controller.getObservedZoomRatio();
    }

    public boolean isInitialLensReady() {
        return !closed && controller.isInitialLensReady();
    }

    @Nullable
    public String getActivePhysicalCameraId() {
        return closed ? null : controller.getActivePhysicalCameraId();
    }

    @Nullable
    public String getExpectedInitialPhysicalCameraId() {
        return closed ? null : controller.getExpectedInitialPhysicalCameraId();
    }

    public float getMinZoomRatio() {
        return closed ? 1f : controller.getMinZoomRatio();
    }

    public float getMaxZoomRatio() {
        return closed ? 1f : controller.getMaxZoomRatio();
    }

    public boolean isExposureCompensationSupported() {
        return !closed && controller.isExposureCompensationSupported();
    }

    public void setExposureCompensation(float value) {
        if (!closed) controller.setExposureCompensation(value);
    }

    public void focusToRect(@Nullable Rect focusRect) {
        if (closed || focusRect == null) return;
        
        int x = Math.round((focusRect.centerX() + 1000f) * previewWidth / 2000f);
        int y = Math.round((focusRect.centerY() + 1000f) * previewHeight / 2000f);
        x = Math.max(0, Math.min(previewWidth - 1, x));
        y = Math.max(0, Math.min(previewHeight - 1, y));
        controller.focusToPoint(x, y);
    }

    public void setTargetOrientation(int rotation) {
        if (!closed) controller.setTargetOrientation(rotation);
    }

    public void updateRotation() {
        if (!closed) controller.setImageCaptureTargetOrientation(captureTargetRotation);
    }

    private int getDisplayRotation() {
        try {
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return manager == null ? Surface.ROTATION_0 : manager.getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return Surface.ROTATION_0;
        }
    }

    private int getDisplayRotationDegrees() {
        switch (getDisplayRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    public boolean bindConcurrentWith(@Nullable NimarkoCameraXSurfaceSession other) {
        return bindConcurrentWith(other, false);
    }

    public boolean bindConcurrentWith(@Nullable NimarkoCameraXSurfaceSession other,
                                      boolean compatibilityProfile) {
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface concurrent request first=" + renderId
                + " second=" + (other == null ? "null" : other.renderId)
                + " compatibility=" + compatibilityProfile);
        boolean result = !closed && other != null && !other.closed
                && controller.bindConcurrentWith(
                        other.controller, compatibilityProfile);
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface concurrent result=" + result
                + " first=" + renderId + " second="
                + (other == null ? "null" : other.renderId));
        return result;
    }

    public boolean isConcurrentWith(@Nullable NimarkoCameraXSurfaceSession other) {
        return other != null && controller.isConcurrentWith(other.controller);
    }

    public boolean wasLastConcurrentBindBusy() {
        return controller.wasLastConcurrentBindBusy();
    }

    public boolean rebindSingle() {
        return !closed && controller.rebindSingle();
    }

    public boolean isPrepared() {
        return !closed && controller.isPrepared();
    }

    public String getCurrentFlashMode() {
        if (torchEnabled) {
            return android.hardware.Camera.Parameters.FLASH_MODE_TORCH;
        }
        switch (controller.getCurrentFlashMode()) {
            case androidx.camera.core.ImageCapture.FLASH_MODE_ON:
                return android.hardware.Camera.Parameters.FLASH_MODE_ON;
            case androidx.camera.core.ImageCapture.FLASH_MODE_OFF:
                return android.hardware.Camera.Parameters.FLASH_MODE_OFF;
            default:
                return android.hardware.Camera.Parameters.FLASH_MODE_AUTO;
        }
    }

    public void setCurrentFlashMode(String mode) {
        if (android.hardware.Camera.Parameters.FLASH_MODE_TORCH.equals(mode)) {
            torchEnabled = true;
            controller.enableTorch(true);
            return;
        }
        if (torchEnabled) {
            torchEnabled = false;
            controller.enableTorch(false);
        }
        int value;
        if (android.hardware.Camera.Parameters.FLASH_MODE_ON.equals(mode)) {
            value = androidx.camera.core.ImageCapture.FLASH_MODE_ON;
        } else if (android.hardware.Camera.Parameters.FLASH_MODE_OFF.equals(mode)) {
            value = androidx.camera.core.ImageCapture.FLASH_MODE_OFF;
        } else {
            value = androidx.camera.core.ImageCapture.FLASH_MODE_AUTO;
        }
        controller.setFlashMode(value);
    }

    public String getNextFlashMode() {
        int mode = controller.getCurrentFlashMode();
        if (mode == androidx.camera.core.ImageCapture.FLASH_MODE_AUTO) {
            return android.hardware.Camera.Parameters.FLASH_MODE_ON;
        } else if (mode == androidx.camera.core.ImageCapture.FLASH_MODE_ON) {
            return android.hardware.Camera.Parameters.FLASH_MODE_OFF;
        } else {
            return android.hardware.Camera.Parameters.FLASH_MODE_AUTO;
        }
    }

    public boolean hasFlash() {
        return controller.isFlashAvailable();
    }

    public void enableTorch(boolean enabled) {
        if (!closed) {
            torchEnabled = enabled;
            controller.enableTorch(enabled);
        }
    }

    public boolean takePicture(java.io.File file,
                               @Nullable org.telegram.messenger.Utilities.Callback<Integer> callback) {
        if (closed) return false;
        
        updateRotation();
        return controller.takePicture(file,
                () -> {
                    if (callback != null) {
                        android.util.Pair<Integer, Integer> orientation =
                                AndroidUtilities.getImageOrientationOrNull(file);
                        callback.run(orientation == null ? -1 : orientation.first);
                    }
                },
                error -> { if (callback != null) callback.run(-1); });
    }

    public boolean canTakePicture() {
        return !closed && controller.canTakePicture();
    }

    public void close() {
        close(null);
    }

    public void close(@Nullable Runnable onClosed) {
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSurface close renderId=" + renderId
                + " provided=" + providedSurfaceCount + " alreadyClosed=" + closed);
        boolean closeController = false;
        Runnable completion = null;
        synchronized (surfaceReleaseLock) {
            if (!closed) {
                closed = true;
                surfaceRequestGeneration++;
                closeController = true;
                rebindCompletion = null;
            }
            if (onClosed != null) {
                if (providedSurfaceCount == 0) {
                    completion = onClosed;
                } else {
                    Runnable previous = closeCompletion;
                    closeCompletion = previous == null ? onClosed : () -> {
                        previous.run();
                        onClosed.run();
                    };
                }
            }
        }
        if (closeController) {
            try {
                if (orientationListener != null) {
                    orientationListener.disable();
                }
            } catch (Throwable error) {
                FileLog.e(error);
            }
            try {
                controller.closeCamera();
            } catch (Throwable error) {
                
                FileLog.e(error);
            }
        }
        if (completion != null) completion.run();
    }
}
