package app.nimarkogram.messenger.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;
import android.util.Range;
import android.view.WindowManager;
import android.content.pm.PackageManager;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SessionConfig;
import androidx.camera.core.UseCase;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ZoomState;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.camera.Size;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.NimarkoCameraLog;

public class NimarkoCameraXController implements CameraXProviderCoordinator.Owner {

    public static final class ConcurrentCameraResourceException
            extends IllegalStateException {
        ConcurrentCameraResourceException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    private boolean isFrontface;
    private final CameraLifecycle lifecycle;
    private ProcessCameraProvider provider;
    private final MeteringPointFactory meteringPointFactory;
    private final Preview.SurfaceProvider surfaceProvider;

    @Nullable private Camera boundCamera;
    @Nullable private Preview boundPreview;
    @Nullable private ImageCapture boundImageCapture;
    @Nullable private CameraSelector boundSelector;
    @Nullable private SessionConfig boundSessionConfig;
    @Nullable private Range<Integer> appliedTargetFpsRange;
    @Nullable private LiveData<CameraState> boundCameraState;
    @Nullable private Observer<CameraState> boundCameraStateObserver;
    @Nullable private Runnable concurrentCameraInUseFailureRunnable;
    private int boundCameraGeneration;
    private int initialZoomPreparedGeneration = -1;
    private volatile boolean boundCameraReady;
    private float baseZoomRatio = 1f;
    @Nullable private volatile String activePhysicalCameraId;
    @Nullable private volatile String expectedInitialPhysicalCameraId;
    private final CameraXZoomCoordinator zoomCoordinator =
            new CameraXZoomCoordinator("CameraX surface zoom");
    private boolean initiated;
    private volatile boolean closed;
    private final Object initializationLock = new Object();
    private int initializationGeneration;
    private int targetRotation = android.view.Surface.ROTATION_0;
    private int imageCaptureTargetRotation = android.view.Surface.ROTATION_0;
    @Nullable private final android.util.Size targetResolution;
    private final boolean enableImageCapture;
    private final boolean deferInitialBind;
    @Nullable private NimarkoCameraXController concurrentPeer;
    private volatile boolean lastConcurrentBindBusy;
    private int flashMode = ImageCapture.FLASH_MODE_AUTO;
    private boolean torchRequested;
    private boolean useConfiguredUltraWide = true;
    @Nullable private Runnable readyCallback;
    @Nullable private org.telegram.messenger.Utilities.Callback<Throwable> failureCallback;

    public static final int CAMERA_NONE = 0;
    public static final int CAMERA_NIGHT = 1;
    public static final int CAMERA_HDR = 2;
    public static final int CAMERA_AUTO = 3;
    public static final int CAMERA_WIDE = 4;
    private int selectedEffect = CAMERA_NONE;

    public static class CameraLifecycle implements LifecycleOwner {

        private final LifecycleRegistry lifecycleRegistry;

        public CameraLifecycle() {
            lifecycleRegistry = new LifecycleRegistry(this);
            lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        }

        public void start() {
            try {
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
            } catch (IllegalStateException ignored) {
            }
        }

        public void stop() {
            try {
                
                lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
            } catch (IllegalStateException ignored) {
            }
        }

        @NonNull
        public Lifecycle getLifecycle() {
            return lifecycleRegistry;
        }

    }

    public NimarkoCameraXController(CameraLifecycle lifecycle, MeteringPointFactory factory, Preview.SurfaceProvider surfaceProvider) {
        this(lifecycle, factory, surfaceProvider, null);
    }

    public NimarkoCameraXController(CameraLifecycle lifecycle, MeteringPointFactory factory,
                                    Preview.SurfaceProvider surfaceProvider,
                                    @Nullable android.util.Size targetResolution) {
        this(lifecycle, factory, surfaceProvider, targetResolution, false);
    }

    public NimarkoCameraXController(CameraLifecycle lifecycle, MeteringPointFactory factory,
                                    Preview.SurfaceProvider surfaceProvider,
                                    @Nullable android.util.Size targetResolution,
                                    boolean enableImageCapture) {
        this(lifecycle, factory, surfaceProvider, targetResolution, enableImageCapture, false);
    }

    public NimarkoCameraXController(CameraLifecycle lifecycle, MeteringPointFactory factory,
                                    Preview.SurfaceProvider surfaceProvider,
                                    @Nullable android.util.Size targetResolution,
                                    boolean enableImageCapture, boolean deferInitialBind) {
        this.lifecycle = lifecycle;
        this.meteringPointFactory = factory;
        this.surfaceProvider = surfaceProvider;
        this.targetResolution = targetResolution;
        this.enableImageCapture = enableImageCapture;
        this.deferInitialBind = deferInitialBind;
    }

    public boolean isInitiated() {
        return initiated && boundCamera != null
                && CameraXProviderCoordinator.isOwner(this);
    }

    public void setFrontFace(boolean isFrontFace) {
        if (isFrontface == isFrontFace) return;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController switch requested fromFront=" + isFrontface
                + " toFront=" + isFrontFace + " provider=" + (provider != null));
        isFrontface = isFrontFace;
        if (provider != null && !bindUseCases()) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController switch bind FAILED front=" + isFrontface);
            reportFailure(new IllegalStateException("CameraX camera switch bind failed"));
        }
    }

    public boolean isFrontface() {
        return isFrontface;
    }

    public void initCamera(Context context, boolean isInitialFrontface, Runnable onPreInit) {
        initCamera(context, isInitialFrontface, onPreInit, null);
    }

    public void initCamera(Context context, boolean isInitialFrontface, @Nullable Runnable onReady,
                           @Nullable org.telegram.messenger.Utilities.Callback<Throwable> onFailure) {
        if (context == null) return;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.dumpCameraInventory(context, "CameraX controller init");
        final int requestGeneration;
        synchronized (initializationLock) {
            requestGeneration = ++initializationGeneration;
            closed = false;
            isFrontface = isInitialFrontface;
            readyCallback = onReady;
            failureCallback = onFailure;
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController init generation=" + requestGeneration
                + " front=" + isInitialFrontface + " target=" + targetResolution
                + " imageCapture=" + enableImageCapture
                + " deferBind=" + deferInitialBind);
        try {
            ListenableFuture<ProcessCameraProvider> future = CameraXUtils.getProviderFuture(context);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController provider future=" + future);
            future.addListener(() -> {
                synchronized (initializationLock) {
                    if (closed || requestGeneration != initializationGeneration) {
                        return;
                    }
                    try {
                        ProcessCameraProvider resolvedProvider = future.get();
                        if (closed || requestGeneration != initializationGeneration) {
                            return;
                        }
                        provider = resolvedProvider;
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController provider ready generation="
                                + requestGeneration + " cameras="
                                + resolvedProvider.getAvailableCameraInfos().size());
                        if (deferInitialBind) {
                            
                            lifecycle.stop();
                            if (prepareUseCases(false)) {
                                notifyReady();
                            } else {
                                reportFailure(new IllegalStateException("CameraX use-case preparation failed"));
                            }
                        } else {
                            lifecycle.start();
                            bindUseCasesAndReport();
                        }
                    } catch (Throwable t) {
                        if (closed || requestGeneration != initializationGeneration) {
                            return;
                        }
                        initiated = false;
                        FileLog.e(t);
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController provider/init FAILED generation="
                                + requestGeneration, t);
                        reportFailure(t);
                    }
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(context));
        } catch (Throwable t) {
            synchronized (initializationLock) {
                if (closed || requestGeneration != initializationGeneration) {
                    return;
                }
                initiated = false;
                FileLog.e(t);
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController init threw generation="
                        + requestGeneration, t);
                reportFailure(t);
            }
        }
    }

    public void setCameraEffect(@EffectFacing int effect) {
        if (selectedEffect == effect) return;
        selectedEffect = effect;
        bindUseCasesAndReport();
    }

    public int getCameraEffect() {
        return selectedEffect;
    }

    public void setUseConfiguredUltraWide(boolean useConfiguredUltraWide) {
        this.useConfiguredUltraWide = useConfiguredUltraWide;
    }

    public void switchCamera() {
        isFrontface = !isFrontface;
        if (provider != null && !bindUseCases()) {
            reportFailure(new IllegalStateException("CameraX camera switch bind failed"));
        }
    }

    public void closeCamera() {
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController close front=" + isFrontface
                + " initiated=" + initiated + " camera=" + cameraId(boundCamera));
        synchronized (initializationLock) {
            closed = true;
            ++initializationGeneration;
            enableTorch(false);
            detachConcurrentPeer();
            CameraXProviderCoordinator.release(
                    provider, this, () -> {
                        unbindOwnUseCases();
                        return true;
                    });
            invalidateBoundCameraControls();
            boundPreview = null;
            boundImageCapture = null;
            boundCamera = null;
            boundSessionConfig = null;
            appliedTargetFpsRange = null;
            initiated = false;
            lifecycle.stop();
        }
    }

    @SuppressLint("RestrictedApi")
    public boolean hasFrontFaceCamera() {
        if (provider == null) return false;
        try {
            return provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressLint("RestrictedApi")
    public static boolean hasGoodCamera(Context context) {
        return context != null && CameraXUtils.isCameraXSupported()
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    public int setNextFlashMode() {
        if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
            setFlashMode(ImageCapture.FLASH_MODE_ON);
        } else if (flashMode == ImageCapture.FLASH_MODE_ON) {
            setFlashMode(ImageCapture.FLASH_MODE_OFF);
        } else {
            setFlashMode(ImageCapture.FLASH_MODE_AUTO);
        }
        return flashMode;
    }

    public int getCurrentFlashMode() {
        return flashMode;
    }

    public void setFlashMode(int mode) {
        if (mode != ImageCapture.FLASH_MODE_AUTO && mode != ImageCapture.FLASH_MODE_ON
                && mode != ImageCapture.FLASH_MODE_OFF) {
            mode = ImageCapture.FLASH_MODE_AUTO;
        }
        flashMode = mode;
        if (boundImageCapture != null) {
            try {
                boundImageCapture.setFlashMode(mode);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    public boolean isFlashAvailable() {
        try {
            return boundCamera != null && boundCamera.getCameraInfo().hasFlashUnit();
        } catch (Throwable t) {
            return false;
        }
    }

    public void enableTorch(boolean enabled) {
        torchRequested = enabled;
        applyRequestedTorch();
    }

    private void applyRequestedTorch() {
        Camera camera = boundCamera;
        final int generation = boundCameraGeneration;
        if (camera == null || !boundCameraReady) return;
        try {
            if (camera.getCameraInfo().hasFlashUnit()) {
                ListenableFuture<Void> result = camera.getCameraControl().enableTorch(torchRequested);
                trackControlFuture(
                        result, camera, generation, "CameraX torch request", null);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public boolean isAvailableHdrMode() {
        return isExtensionAvailable(currentSelector(), EXTENSION_MODE_HDR);
    }

    public boolean isAvailableNightMode() {
        return isExtensionAvailable(currentSelector(), EXTENSION_MODE_NIGHT);
    }

    public boolean isAvailableWideMode() {
        if (provider == null) return false;
        try {
            return CameraXUtils.isWideAngleAvailable(provider);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isAvailableAutoMode() {
        return isExtensionAvailable(currentSelector(), EXTENSION_MODE_AUTO);
    }

    public boolean isAvailableSlowMoMode() {
        if (boundCamera == null) return false;
        try {
            
            android.util.Range<Integer>[] ranges = androidx.camera.camera2.interop.Camera2CameraInfo
                    .from(boundCamera.getCameraInfo())
                    .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null) return false;
            for (android.util.Range<Integer> r : ranges) {
                if (r != null && r.getUpper() != null && r.getUpper() >= 120) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressLint({"RestrictedApi", "UnsafeExperimentalUsageError", "UnsafeOptInUsageError"})
    public boolean bindUseCases() {
        if (closed || provider == null || surfaceProvider == null) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController bind skipped closed=" + closed
                    + " provider=" + (provider != null)
                    + " surfaceProvider=" + (surfaceProvider != null));
            return false;
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController bind begin front=" + isFrontface
                + " effect=" + selectedEffect + " target=" + targetResolution
                + " imageCapture=" + enableImageCapture);
        try {
            boolean result = CameraXProviderCoordinator.withSingleOwner(
                    provider, this, this::bindUseCasesOwned);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController bind end result=" + result
                    + " camera=" + cameraId(boundCamera)
                    + " fps=" + appliedTargetFpsRange);
            return result;
        } catch (Throwable error) {
            FileLog.e("CameraX ownership bind failed", error);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController ownership bind FAILED", error);
            clearPreparedUseCases();
            return false;
        }
    }

    private boolean bindUseCasesOwned() {
        initiated = false;
        if (closed || provider == null || surfaceProvider == null) return false;
        lifecycle.start();
        boolean requestedFpsWasApplied = false;
        try {
            detachConcurrentPeer();
            unbindOwnUseCases();
            if (prepareUseCases(true)) {
                requestedFpsWasApplied = appliedTargetFpsRange != null;
                if (bindPreparedUseCases()) return true;
            }
        } catch (Throwable t) {
            FileLog.e("CameraX optimized bind failed", t);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController optimized bind FAILED", t);
        }

        if (requestedFpsWasApplied) {
            try {
                unbindOwnUseCases();
                if (prepareUseCases(true, false, false, true)
                        && bindPreparedUseCases()) {
                    return true;
                }
            } catch (Throwable retryError) {
                FileLog.e("CameraX bind retry with platform FPS failed", retryError);
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController no-FPS retry FAILED", retryError);
            }
        }

        try {
            unbindOwnUseCases();
            if (prepareUseCases(true, false, false, false)
                    && bindPreparedUseCases()) {
                return true;
            }
        } catch (Throwable retryError) {
            FileLog.e("CameraX safe bind fallback failed", retryError);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController safe fallback FAILED", retryError);
        }

        clearPreparedUseCases();
        return false;
    }

    private boolean bindPreparedUseCases() {
        if (boundSessionConfig == null) return false;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController bindPrepared selector="
                + describeSelector(boundSelector) + " fps=" + appliedTargetFpsRange);
        boundCamera = provider.bindToLifecycle(
                lifecycle, boundSelector, boundSessionConfig);
        initiated = boundCamera != null;
        attachBoundCamera(boundCamera);
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController bindPrepared result=" + initiated
                + " camera=" + cameraId(boundCamera));
        return initiated;
    }

    private void clearPreparedUseCases() {
        boundCamera = null;
        boundPreview = null;
        boundImageCapture = null;
        boundSelector = null;
        boundSessionConfig = null;
        appliedTargetFpsRange = null;
        initiated = false;
    }

    private void bindUseCasesAndReport() {
        if (bindUseCases()) {
            notifyReady();
        } else {
            reportFailure(new IllegalStateException("CameraX preview bind failed"));
        }
    }

    @SuppressLint({"RestrictedApi", "UnsafeExperimentalUsageError", "UnsafeOptInUsageError"})
    private boolean prepareUseCases(boolean rebuild) {
        return prepareUseCases(rebuild, false, true, true);
    }

    private boolean prepareUseCases(boolean rebuild, boolean concurrentPreview,
                                    boolean applyRequestedFps, boolean applyEnhancements) {
        return prepareUseCases(rebuild, concurrentPreview, applyRequestedFps,
                applyEnhancements, null, null);
    }

    private boolean prepareUseCases(boolean rebuild, boolean concurrentPreview,
                                    boolean applyRequestedFps, boolean applyEnhancements,
                                    @Nullable CameraSelector selectorOverride) {
        return prepareUseCases(rebuild, concurrentPreview, applyRequestedFps,
                applyEnhancements, selectorOverride, null);
    }

    private boolean prepareUseCases(boolean rebuild, boolean concurrentPreview,
                                    boolean applyRequestedFps, boolean applyEnhancements,
                                    @Nullable CameraSelector selectorOverride,
                                    @Nullable Range<Integer> concurrentFpsRange) {
        if (closed || provider == null || surfaceProvider == null) return false;
        if (!rebuild && boundPreview != null && boundSelector != null) return true;
        try {
            CameraSelector selector;
            if (selectorOverride != null) {
                selector = selectorOverride;
            } else if (!isFrontface && selectedEffect == CAMERA_WIDE) {
                selector = CameraXUtils.buildUltraWideSelector(provider);
                if (selector == null) {
                    selector = CameraXUtils.buildIntendedCameraSelector(
                            provider, false, useConfiguredUltraWide);
                }
            } else {
                selector = CameraXUtils.buildIntendedCameraSelector(
                        provider, isFrontface, useConfiguredUltraWide);
            }
            boundSelector = selectorOverride != null ? selector : applyExtensionMode(selector);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController prepare rebuild=" + rebuild
                    + " concurrent=" + concurrentPreview
                    + " requestedFps=" + applyRequestedFps
                    + " enhancements=" + applyEnhancements
                    + " selector=" + describeSelector(boundSelector)
                    + " target=" + targetResolution);
            Preview.Builder previewBuilder = new Preview.Builder().setTargetRotation(targetRotation);
            
            if (targetResolution != null) {
                boolean roundVideoPreview = !enableImageCapture;
                float targetRatio = Math.max(targetResolution.getWidth(),
                        targetResolution.getHeight()) / (float) Math.max(1,
                        Math.min(targetResolution.getWidth(), targetResolution.getHeight()));
                int aspectRatio = Math.abs(targetRatio - 4f / 3f)
                        < Math.abs(targetRatio - 16f / 9f)
                        ? AspectRatio.RATIO_4_3 : AspectRatio.RATIO_16_9;
                boolean preferCaptureRate = !roundVideoPreview
                        && Math.min(
                        targetResolution.getWidth(),
                        targetResolution.getHeight()) <= 1080;
                previewBuilder.setResolutionSelector(concurrentPreview
                        ? CameraXUtils.buildConcurrentPreviewResolutionSelector(
                                targetResolution, aspectRatio)
                        : CameraXUtils.buildResolutionSelector(
                                targetResolution, aspectRatio, preferCaptureRate));
            }
            appliedTargetFpsRange = null;
            if (concurrentPreview && applyRequestedFps && concurrentFpsRange != null) {
                
                previewBuilder.setTargetFrameRate(concurrentFpsRange);
                appliedTargetFpsRange = concurrentFpsRange;
            }
            boolean configuredStartFromUltraWide = !isFrontface
                    && (useConfiguredUltraWide && NimarkoConfig.startFromUltraWideCam
                    || selectedEffect == CAMERA_WIDE);
            
            boolean startFromUltraWide = configuredStartFromUltraWide
                    && CameraXUtils.supportsSubOneZoom(provider, boundSelector)
                    && (!concurrentPreview
                    || !CameraXUtils.isOppoCph2791ConcurrentQuirk());
            activePhysicalCameraId = null;
            expectedInitialPhysicalCameraId = null;
            if (configuredStartFromUltraWide && concurrentPreview
                    && !startFromUltraWide) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log(
                        "CXController concurrent wide supplied by selected physical lens"
                                + " or blocked by device policy selector="
                                + describeSelector(boundSelector));
            }
            Camera2Interop.Extender<Preview> previewExtender =
                    applyEnhancements || startFromUltraWide || concurrentPreview
                            ? new Camera2Interop.Extender<>(previewBuilder) : null;
            if (concurrentPreview && previewExtender != null) {
                installConcurrentCamera2Diagnostics(previewExtender,
                        describeSelector(boundSelector)
                                + " ownerFront=" + isFrontface
                                + " controller=" + objectId(this)
                                + " previewBuilder=" + objectId(previewBuilder));
            }
            if (applyEnhancements) {
                CameraXUtils.applyCamera2Controls(provider, boundSelector,
                        previewExtender, false);
            }
            if (startFromUltraWide && previewExtender != null
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    androidx.camera.core.CameraInfo info =
                            provider.getCameraInfo(boundSelector);
                    ZoomState zoomState = info == null ? null
                            : info.getZoomState().getValue();
                    float initialRatio = CameraXUtils.getBaseZoomRatio(zoomState, true);
                    if (initialRatio < 0.999f) {
                        
                        baseZoomRatio = initialRatio;
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController initial wide control ratio="
                                + initialRatio + " selector="
                                + describeSelector(boundSelector)
                                + " concurrent=" + concurrentPreview);
                    }
                } catch (Throwable error) {
                    if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log(
                            "CXController initial wide request unavailable", error);
                }
            }
            if (startFromUltraWide && previewExtender != null
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                expectedInitialPhysicalCameraId =
                        CameraXUtils.findBackUltraWideCameraId(provider);
                if (expectedInitialPhysicalCameraId != null) {
                    final String expectedPhysicalId = expectedInitialPhysicalCameraId;
                    previewExtender.setSessionCaptureCallback(
                            new CameraCaptureSession.CaptureCallback() {
                                @Override
                                public void onCaptureCompleted(
                                        @NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull TotalCaptureResult result) {
                                    String physicalId;
                                    try {
                                        physicalId = result.get(CaptureResult
                                                .LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                                    } catch (Throwable ignored) {
                                        physicalId = null;
                                    }
                                    if (physicalId == null
                                            || physicalId.equals(activePhysicalCameraId)) {
                                        return;
                                    }
                                    activePhysicalCameraId = physicalId;
                                    if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log(
                                            "CXController active physical lens=" + physicalId
                                                    + " expectedWide=" + expectedPhysicalId
                                                    + " frame=" + result.getFrameNumber());
                                }
                            });
                    if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController waiting for physical wide="
                            + expectedInitialPhysicalCameraId);
                }
            }
            if (applyEnhancements && !concurrentPreview
                    && CameraXUtils.shouldEnablePreviewStabilization(provider, boundSelector)) {
                previewBuilder.setPreviewStabilizationEnabled(true);
            }
            boundPreview = previewBuilder.build();
            boundPreview.setSurfaceProvider(surfaceProvider);
            if (enableImageCapture && !concurrentPreview) {
                ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100)
                        .setTargetRotation(imageCaptureTargetRotation);
                if (targetResolution != null) {
                    captureBuilder.setResolutionSelector(CameraXUtils.buildResolutionSelector(
                            targetResolution, AspectRatio.RATIO_16_9, false));
                }
                if (applyEnhancements) {
                    CameraXUtils.applyCamera2Controls(provider, boundSelector,
                            new Camera2Interop.Extender<>(captureBuilder), true);
                }
                boundImageCapture = captureBuilder.build();
                boundImageCapture.setFlashMode(flashMode);
            } else {
                boundImageCapture = null;
            }
            if (concurrentPreview) {
                
                boundSessionConfig = null;
            } else {
                ArrayList<UseCase> useCases = new ArrayList<>(2);
                useCases.add(boundPreview);
                if (boundImageCapture != null) useCases.add(boundImageCapture);
                if (applyRequestedFps) {
                    appliedTargetFpsRange =
                            CameraXUtils.getSupportedTargetFpsRange(
                                    provider, boundSelector, useCases);
                }
                SessionConfig.Builder sessionBuilder =
                        new SessionConfig.Builder(useCases);
                if (appliedTargetFpsRange != null) {
                    sessionBuilder.setFrameRateRange(appliedTargetFpsRange);
                }
                boundSessionConfig = sessionBuilder.build();
            }
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController prepare FAILED front=" + isFrontface
                    + " concurrent=" + concurrentPreview, t);
            boundPreview = null;
            boundImageCapture = null;
            boundSelector = null;
            boundSessionConfig = null;
            appliedTargetFpsRange = null;
            return false;
        }
    }

    private static void installConcurrentCamera2Diagnostics(
            @NonNull Camera2Interop.Extender<Preview> extender,
            @NonNull String selectorDescription) {
        final String label = "selector=" + selectorDescription;
        extender.setDeviceStateCallback(new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXDevice OPENED id=" + camera.getId()
                        + " " + label);
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXDevice DISCONNECTED id=" + camera.getId()
                        + " " + label);
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXDevice ERROR id=" + camera.getId()
                        + " code=" + error + " " + label);
            }

            @Override
            public void onClosed(@NonNull CameraDevice camera) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXDevice CLOSED id=" + camera.getId()
                        + " " + label);
            }
        });
        extender.setSessionStateCallback(new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSession CONFIGURED id="
                        + session.getDevice().getId() + " " + label);
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSession CONFIGURE_FAILED id="
                        + session.getDevice().getId() + " " + label);
            }

            @Override
            public void onActive(@NonNull CameraCaptureSession session) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSession ACTIVE id="
                        + session.getDevice().getId() + " " + label);
            }

            @Override
            public void onReady(@NonNull CameraCaptureSession session) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSession READY id="
                        + session.getDevice().getId() + " " + label);
            }

            @Override
            public void onClosed(@NonNull CameraCaptureSession session) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXSession CLOSED id="
                        + session.getDevice().getId() + " " + label);
            }
        });
    }

    private void unbindOwnUseCases() {
        if (provider == null) return;
        invalidateBoundCameraControls();
        try {
            if (boundSessionConfig != null && provider.isBound(boundSessionConfig)) {
                provider.unbind(boundSessionConfig);
                return;
            }
            if (boundPreview == null) return;
            if (boundImageCapture != null && provider.isBound(boundImageCapture)) {
                provider.unbind(boundPreview, boundImageCapture);
            } else if (provider.isBound(boundPreview)) {
                provider.unbind(boundPreview);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public void unbindForSurfaceRebind() {
        if (closed) return;
        boolean unboundSingle = CameraXProviderCoordinator.runIfSingleOwner(
                provider, this, () -> {
                    unbindOwnUseCases();
                    return true;
                });
        if (!unboundSingle) {
            CameraXProviderCoordinator.release(
                    provider, this, () -> true);
        }
        invalidateBoundCameraControls();
        boundCamera = null;
        initiated = false;
    }

    private void detachConcurrentPeer() {
        NimarkoCameraXController peer = concurrentPeer;
        concurrentPeer = null;
        if (peer != null && peer.concurrentPeer == this) {
            peer.concurrentPeer = null;
            peer.invalidateBoundCameraControls();
            peer.initiated = false;
            peer.boundCamera = null;
        }
    }

    public boolean isPrepared() {
        return !closed && provider != null && boundPreview != null && boundSelector != null;
    }

    public boolean isConcurrentWith(@Nullable NimarkoCameraXController other) {
        return other != null && concurrentPeer == other && other.concurrentPeer == this
                && CameraXProviderCoordinator.isConcurrentPair(this, other)
                && isInitiated() && other.isInitiated();
    }

    public boolean bindConcurrentWith(@Nullable NimarkoCameraXController other) {
        return bindConcurrentWith(other, false);
    }

    public boolean bindConcurrentWith(@Nullable NimarkoCameraXController other,
                                      boolean compatibilityProfile) {
        lastConcurrentBindBusy = false;
        if (other == null || other == this || closed || other.closed) return false;
        if (isConcurrentWith(other)) return true;
        if (provider == null || provider != other.provider) {
            return false;
        }
        final NimarkoCameraXController backController = isFrontface ? other : this;
        final NimarkoCameraXController frontController = isFrontface ? this : other;
        if (backController.isFrontface || !frontController.isFrontface) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent rejected: pair is not front/back");
            return false;
        }
        
        boolean preferUltraWide = backController.wantsInitialUltraWide();
        CameraSelector[] selectors = CameraXUtils.buildConcurrentCameraSelectors(
                provider, false, true, preferUltraWide);
        if (selectors == null) {
            FileLog.e("CameraX concurrent bind failed: no advertised front/back pair");
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent unavailable backFront="
                    + backController.isFrontface + " frontFront=" + frontController.isFrontface);
            return false;
        }

        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent begin compatibility="
                + compatibilityProfile + " order=back-front back="
                + backController.describeSelector(selectors[0]) + " front="
                + frontController.describeSelector(selectors[1])
                + " selectedFront=" + isFrontface
                + " preferWide=" + preferUltraWide);

        try {
            return CameraXProviderCoordinator.withConcurrentOwners(
                    provider, backController, frontController, () -> {
            
            backController.lifecycle.stop();
            frontController.lifecycle.stop();
            backController.boundCamera = null;
            frontController.boundCamera = null;
            backController.initiated = false;
            frontController.initiated = false;

            Range<Integer> commonFps = compatibilityProfile ? null
                    : CameraXUtils.getCommonSupportedTargetFpsRange(
                            provider, selectors[0], selectors[1]);
            
            if (!backController.prepareUseCases(true, true, !compatibilityProfile, false,
                    selectors[0], commonFps)
                    || !frontController.prepareUseCases(true, true, !compatibilityProfile,
                    false, selectors[1], commonFps)) {
                throw new IllegalArgumentException(
                        "Concurrent CameraX use-case preparation failed");
            }

            UseCaseGroup firstGroup =
                    new UseCaseGroup.Builder().addUseCase(backController.boundPreview).build();
            UseCaseGroup secondGroup =
                    new UseCaseGroup.Builder().addUseCase(frontController.boundPreview).build();
            List<ConcurrentCamera.SingleCameraConfig> configs = Arrays.asList(
                    new ConcurrentCamera.SingleCameraConfig(
                            selectors[0], firstGroup, backController.lifecycle),
                    new ConcurrentCamera.SingleCameraConfig(
                            selectors[1], secondGroup, backController.lifecycle));
            
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent configs commonLifecycle=true"
                    + " strictVga=" + compatibilityProfile
                    + " activationDeferred=true"
                    + " backController=" + objectId(backController)
                    + " frontController=" + objectId(frontController)
                    + " backPreview=" + objectId(backController.boundPreview)
                    + " frontPreview=" + objectId(frontController.boundPreview)
                    + " backGroup=" + objectId(firstGroup)
                    + " frontGroup=" + objectId(secondGroup)
                    + " sharedLifecycle=" + objectId(backController.lifecycle)
                    + " lifecycleState="
                    + backController.lifecycle.getLifecycle().getCurrentState());
            ConcurrentCamera concurrentCamera = provider.bindToLifecycle(configs);
            List<Camera> cameras = concurrentCamera.getCameras();
            if (cameras == null || cameras.size() != 2) {
                throw new IllegalStateException(
                        "CameraX returned an incomplete concurrent camera pair");
            }
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent returned order=["
                    + cameraId(cameras.get(0)) + '@' + objectId(cameras.get(0))
                    + ", " + cameraId(cameras.get(1)) + '@' + objectId(cameras.get(1))
                    + "] expectedSelectors=[" + describeSelector(selectors[0])
                    + ", " + describeSelector(selectors[1]) + "]");
            backController.boundCamera = cameras.get(0);
            frontController.boundCamera = cameras.get(1);
            backController.initiated = true;
            frontController.initiated = true;
            concurrentPeer = other;
            other.concurrentPeer = this;
            backController.attachBoundCamera(backController.boundCamera);
            frontController.attachBoundCamera(frontController.boundCamera);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent providerMode="
                    + provider.isConcurrentCameraModeOn());
            
            backController.lifecycle.start();
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent lifecycle activated state="
                    + backController.lifecycle.getLifecycle().getCurrentState());
            
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent bound back="
                    + cameraId(backController.boundCamera) + " front="
                    + cameraId(frontController.boundCamera)
                    + " commonFps=" + commonFps);
            return true;
                    });
        } catch (Throwable error) {
            lastConcurrentBindBusy = isCameraAlreadyRunningFailure(error);
            other.lastConcurrentBindBusy = lastConcurrentBindBusy;
            FileLog.e("CameraX concurrent bind failed", error);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent FAILED compatibility="
                    + compatibilityProfile + " providerBusy="
                    + lastConcurrentBindBusy, error);
        }
        invalidateBoundCameraControls();
        other.invalidateBoundCameraControls();
        boundCamera = null;
        other.boundCamera = null;
        boundSessionConfig = null;
        other.boundSessionConfig = null;
        appliedTargetFpsRange = null;
        other.appliedTargetFpsRange = null;
        initiated = false;
        other.initiated = false;
        concurrentPeer = null;
        other.concurrentPeer = null;
        return false;
    }

    public boolean wasLastConcurrentBindBusy() {
        return lastConcurrentBindBusy;
    }

    private static boolean isCameraAlreadyRunningFailure(@Nullable Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UnsupportedOperationException) {
                String message = current.getMessage();
                if (message != null && message.toLowerCase(java.util.Locale.US)
                        .contains("already running")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public void onCameraXOwnershipInvalidated(long generation) {
        concurrentPeer = null;
        invalidateBoundCameraControls();
        clearPreparedUseCases();
        lifecycle.stop();
    }

    public boolean rebindSingle() {
        if (closed) return false;
        boolean result = bindUseCases();
        if (result) notifyReady();
        return result;
    }

    private void notifyReady() {
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController notifyReady front=" + isFrontface
                + " initiated=" + initiated + " camera=" + cameraId(boundCamera));
        if (readyCallback == null) return;
        try {
            readyCallback.run();
        } catch (Throwable callbackError) {
            FileLog.e("CameraX ready callback failed", callbackError);
        }
    }

    private void reportFailure(Throwable throwable) {
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController reportFailure front=" + isFrontface, throwable);
        if (failureCallback == null) return;
        try {
            failureCallback.run(throwable);
        } catch (Throwable callbackError) {
            FileLog.e("CameraX failure callback failed", callbackError);
        }
    }

    public void setZoom(float value) {
        Camera camera = boundCamera;
        if (camera == null || !boundCameraReady) return;
        try {
            ZoomState state = camera.getCameraInfo().getZoomState().getValue();
            zoomCoordinator.requestZoomRatio(
                    CameraXUtils.normalizedZoomToRatio(
                            state, baseZoomRatio, value));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public void setZoomRatio(float ratio) {
        Camera camera = boundCamera;
        if (camera == null || !boundCameraReady) return;
        zoomCoordinator.requestZoomRatio(ratio);
    }

    public float getZoomRatio() {
        if (boundCamera == null) return baseZoomRatio;
        try {
            ZoomState state = boundCamera.getCameraInfo().getZoomState().getValue();
            float observed = state != null ? state.getZoomRatio() : baseZoomRatio;
            return zoomCoordinator.getRequestedOr(observed);
        } catch (Throwable t) {
            return baseZoomRatio;
        }
    }

    public float getObservedZoomRatio() {
        Camera camera = boundCamera;
        if (camera == null) return 1f;
        try {
            ZoomState state = camera.getCameraInfo().getZoomState().getValue();
            return state == null ? 1f : state.getZoomRatio();
        } catch (Throwable error) {
            return 1f;
        }
    }

    public boolean isInitialLensReady() {
        if (isFrontface) return true;
        boolean wantsUltraWide = wantsInitialUltraWide();
        if (!wantsUltraWide) return true;
        
        if (concurrentPeer != null
                && CameraXUtils.isOppoCph2791ConcurrentQuirk()) return true;
        Camera camera = boundCamera;
        if (camera == null || !boundCameraReady) return false;
        try {
            String expectedPhysical = expectedInitialPhysicalCameraId;
            if (expectedPhysical != null) {
                String activePhysical = activePhysicalCameraId;
                if (activePhysical != null) {
                    return expectedPhysical.equals(activePhysical);
                }
                
                return false;
            }
            ZoomState state = camera.getCameraInfo().getZoomState().getValue();
            if (state == null || state.getMinZoomRatio() >= 0.999f) return true;
            float target = state.getMinZoomRatio();
            float tolerance = Math.max(0.025f, target * 0.06f);
            return Math.abs(state.getZoomRatio() - target) <= tolerance;
        } catch (Throwable error) {
            
            return false;
        }
    }

    @Nullable
    public String getActivePhysicalCameraId() {
        return activePhysicalCameraId;
    }

    @Nullable
    public String getExpectedInitialPhysicalCameraId() {
        return expectedInitialPhysicalCameraId;
    }

    public float getMinZoomRatio() {
        if (boundCamera == null) return Math.min(1f, baseZoomRatio);
        try {
            ZoomState state = boundCamera.getCameraInfo().getZoomState().getValue();
            return state != null ? state.getMinZoomRatio() : Math.min(1f, baseZoomRatio);
        } catch (Throwable t) {
            return Math.min(1f, baseZoomRatio);
        }
    }

    public float getMaxZoomRatio() {
        if (boundCamera == null) return Math.max(1f, baseZoomRatio);
        try {
            ZoomState state = boundCamera.getCameraInfo().getZoomState().getValue();
            return state != null ? state.getMaxZoomRatio() : Math.max(1f, baseZoomRatio);
        } catch (Throwable t) {
            return Math.max(1f, baseZoomRatio);
        }
    }

    public float resetZoom() {
        Camera camera = boundCamera;
        if (camera == null || !boundCameraReady) return 0f;
        zoomCoordinator.requestZoomRatio(baseZoomRatio);
        return 0f;
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    public boolean isExposureCompensationSupported() {
        if (boundCamera == null) return false;
        try {
            ExposureState es = boundCamera.getCameraInfo().getExposureState();
            return es != null && es.isExposureCompensationSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    public void setExposureCompensation(float value0to1) {
        Camera camera = boundCamera;
        final int generation = boundCameraGeneration;
        if (camera == null || !boundCameraReady) return;
        try {
            ExposureState es = camera.getCameraInfo().getExposureState();
            if (es == null || !es.isExposureCompensationSupported()) return;
            int lo = es.getExposureCompensationRange().getLower();
            int hi = es.getExposureCompensationRange().getUpper();
            int idx = CameraXUtils.normalizedExposureToIndex(value0to1, lo, hi);
            ListenableFuture<Integer> result =
                    camera.getCameraControl().setExposureCompensationIndex(idx);
            trackControlFuture(result, camera, generation,
                    "CameraX exposure compensation", null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public int setExposureCompensationIndex(int index) {
        Camera camera = boundCamera;
        final int generation = boundCameraGeneration;
        if (camera == null || !boundCameraReady) return Integer.MIN_VALUE;
        try {
            ExposureState es = camera.getCameraInfo().getExposureState();
            if (es == null || !es.isExposureCompensationSupported()) return Integer.MIN_VALUE;
            int lo = es.getExposureCompensationRange().getLower();
            int hi = es.getExposureCompensationRange().getUpper();
            int clamped = Math.max(lo, Math.min(hi, index));
            ListenableFuture<Integer> result =
                    camera.getCameraControl().setExposureCompensationIndex(clamped);
            int configured =
                    CameraXUtils.exposureIndexToConfigured(clamped, lo, hi);
            trackControlFuture(result, camera, generation,
                    "CameraX exposure compensation",
                    () -> NimarkoConfig.setCameraExposureIndex(configured));
            return clamped;
        } catch (Throwable t) {
            FileLog.e(t);
            return Integer.MIN_VALUE;
        }
    }

    public int getExposureCompensationIndex() {
        if (boundCamera == null) return 0;
        try {
            ExposureState es = boundCamera.getCameraInfo().getExposureState();
            if (es == null) return 0;
            return es.getExposureCompensationIndex();
        } catch (Throwable t) {
            return 0;
        }
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public void setTargetOrientation(int rotation) {
        targetRotation = CameraXUtils.toSurfaceRotation(rotation);
        imageCaptureTargetRotation = targetRotation;
        if (boundPreview != null) {
            try { boundPreview.setTargetRotation(targetRotation); } catch (Throwable t) { FileLog.e(t); }
        }
        if (boundImageCapture != null) {
            try { boundImageCapture.setTargetRotation(targetRotation); } catch (Throwable t) { FileLog.e(t); }
        }
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public void setImageCaptureTargetOrientation(int rotation) {
        imageCaptureTargetRotation = CameraXUtils.toSurfaceRotation(rotation);
        if (boundImageCapture != null) {
            try {
                boundImageCapture.setTargetRotation(imageCaptureTargetRotation);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public void setWorldCaptureOrientation(int rotation) {
        setImageCaptureTargetOrientation(rotation);
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public void focusToPoint(int x, int y ) {
        focusAndLock(x, y, false, false);
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public boolean focusAndLock(int x, int y, boolean lockAE, boolean lockAF) {
        if (boundCamera == null || meteringPointFactory == null) return false;
        try {
            MeteringPoint point = meteringPointFactory.createPoint(x, y);
            return focusAndLock(point, lockAE, lockAF);
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public boolean focusAndLock(@NonNull MeteringPoint point, boolean lockAE, boolean lockAF) {
        if (boundCamera == null) return false;
        try {
            int flags = 0;
            if (lockAF) flags |= FocusMeteringAction.FLAG_AF;
            if (lockAE) flags |= FocusMeteringAction.FLAG_AE;
            if (flags == 0) flags = FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE;
            FocusMeteringAction.Builder b = new FocusMeteringAction.Builder(point, flags);
            if (lockAE || lockAF) {
                b.disableAutoCancel();
            } else {
                b.setAutoCancelDuration(3, TimeUnit.SECONDS);
            }
            boundCamera.getCameraControl().startFocusAndMetering(b.build());
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public void cancelFocusAndLock() {
        if (boundCamera == null) return;
        try {
            boundCamera.getCameraControl().cancelFocusAndMetering();
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint({"RestrictedApi", "MissingPermission"})
    public void recordVideo(final File path, boolean mirror, BaseCameraView.VideoSavedCallback onStop) {
        if (onStop != null) onStop.onFinishVideoRecording(null, 0L);
    }

    @SuppressLint("RestrictedApi")
    public void stopVideoRecording(final boolean abandon) {

    }

    public void takePicture(final File file, Runnable onTake) {
        takePicture(file, onTake, null);
    }

    public boolean takePicture(final File file, @Nullable Runnable onTake,
                               @Nullable org.telegram.messenger.Utilities.Callback<Throwable> onError) {
        ImageCapture capture = boundImageCapture;
        if (capture == null || file == null) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController photo rejected capture=" + (capture != null)
                    + " file=" + file);
            return false;
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController photo start file=" + file.getAbsolutePath()
                + " front=" + isFrontface + " rotation=" + imageCaptureTargetRotation);
        try {
            ImageCapture.Metadata metadata = new ImageCapture.Metadata();
            
            metadata.setReversedHorizontal(isFrontface);
            ImageCapture.OutputFileOptions options =
                    new ImageCapture.OutputFileOptions.Builder(file)
                            .setMetadata(metadata)
                            .build();
            capture.takePicture(options,
                    androidx.core.content.ContextCompat.getMainExecutor(ApplicationLoader.applicationContext),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController photo saved file="
                                    + file.getAbsolutePath() + " uri="
                                    + outputFileResults.getSavedUri());
                            if (onTake != null) onTake.run();
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            FileLog.e(exception);
                            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController photo FAILED file="
                                    + file.getAbsolutePath(), exception);
                            if (onError != null) onError.run(exception);
                        }
                    });
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController photo dispatch FAILED file=" + file, t);
            if (onError != null) onError.run(t);
            return false;
        }
    }

    public boolean canTakePicture() {
        return !closed && boundImageCapture != null;
    }

    public int getImageCaptureRotationDegrees() {
        ImageCapture capture = boundImageCapture;
        if (capture == null) return 0;
        try {
            androidx.camera.core.ResolutionInfo info = capture.getResolutionInfo();
            return info == null ? 0 : ((info.getRotationDegrees() % 360) + 360) % 360;
        } catch (Throwable t) {
            return 0;
        }
    }

    @SuppressLint("RestrictedApi")
    public Size getPreviewSize() {
        return targetResolution == null ? new Size(0, 0)
                : new Size(targetResolution.getWidth(), targetResolution.getHeight());
    }

    public int getDisplayOrientation() {
        WindowManager mgr = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
        return mgr.getDefaultDisplay().getRotation();
    }

    @SuppressLint("UnsafeOptInUsageError")
    public int getCameraId() {
        if (boundCamera == null) return 0;
        try {
            return Camera2CameraInfo.from(boundCamera.getCameraInfo()).getCameraId().hashCode();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public void attachBoundCamera(@Nullable Camera camera) {
        invalidateBoundCameraControls();
        this.boundCamera = camera;
        baseZoomRatio = 1f;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController attach camera=" + cameraId(camera)
                + " generation=" + boundCameraGeneration);
        if (camera == null) return;

        final int generation = boundCameraGeneration;
        zoomCoordinator.attach(camera, generation);
        try {
            boundCameraState = camera.getCameraInfo().getCameraState();
            boundCameraStateObserver = state -> {
                if (state == null || camera != boundCamera
                        || generation != boundCameraGeneration) {
                    return;
                }
                CameraState.StateError stateError = state.getError();
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController state camera=" + cameraId(camera)
                        + " type=" + state.getType()
                        + " error=" + (stateError == null ? "none" : stateError.getCode()));
                if (stateError != null) {
                    boundCameraReady = false;
                    zoomCoordinator.setReady(camera, generation, false);
                    Throwable cause = stateError.getCause();
                    FileLog.e("CameraX camera state error " + stateError.getCode(),
                            cause != null ? cause : new IllegalStateException(
                                    "CameraX state error " + stateError.getCode()));
                    if ((stateError.getCode() == CameraState.ERROR_CAMERA_IN_USE
                            || stateError.getCode() == CameraState.ERROR_MAX_CAMERAS_IN_USE)
                            && concurrentPeer != null) {
                        scheduleConcurrentCameraInUseFailure(
                                camera, generation, stateError.getCode(), cause);
                    } else if (stateError.getCode() == CameraState.ERROR_STREAM_CONFIG
                            || stateError.getCode() == CameraState.ERROR_CAMERA_FATAL_ERROR) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (camera == boundCamera
                                    && generation == boundCameraGeneration) {
                                reportFailure(cause != null ? cause
                                        : new IllegalStateException(
                                                "CameraX state error "
                                                        + stateError.getCode()));
                            }
                        });
                    }
                    return;
                }
                if (state.getType() == CameraState.Type.OPEN) {
                    cancelConcurrentCameraInUseFailure();
                    
                    boundCameraReady = true;
                    final boolean initialZoomPrepared =
                            prepareInitialZoomIfGraphReady(camera, generation);
                    zoomCoordinator.setReady(camera, generation, true);
                    prepareConcurrentPeerInitialZoomIfReady();

                    AndroidUtilities.runOnUIThread(() -> {
                        if (camera != boundCamera
                                || generation != boundCameraGeneration
                                || closed) {
                            return;
                        }
                        if (!initialZoomPrepared) {
                            
                            prepareInitialZoomIfGraphReady(camera, generation);
                            prepareConcurrentPeerInitialZoomIfReady();
                        }
                        applyDeferredInitialCameraControls(camera, generation);
                    }, 120L);
                } else if (state.getType() == CameraState.Type.CLOSING
                        || state.getType() == CameraState.Type.CLOSED) {
                    boundCameraReady = false;
                    zoomCoordinator.setReady(camera, generation, false);
                }
            };
            boundCameraState.observe(lifecycle, boundCameraStateObserver);
        } catch (Throwable t) {
            FileLog.e(t);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController camera-state observer FAILED", t);
        }
    }

    private String describeSelector(@Nullable CameraSelector selector) {
        if (selector == null || provider == null) return "null";
        try {
            List<androidx.camera.core.CameraInfo> infos =
                    selector.filter(provider.getAvailableCameraInfos());
            ArrayList<String> ids = new ArrayList<>(infos.size());
            for (androidx.camera.core.CameraInfo info : infos) {
                ids.add(Camera2CameraInfo.from(info).getCameraId());
            }
            return ids.toString();
        } catch (Throwable error) {
            return "error:" + error.getClass().getSimpleName();
        }
    }

    private static String cameraId(@Nullable Camera camera) {
        if (camera == null) return "null";
        try {
            return Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId();
        } catch (Throwable error) {
            return "unknown:" + error.getClass().getSimpleName();
        }
    }

    private static String objectId(@Nullable Object object) {
        return object == null ? "null"
                : object.getClass().getSimpleName() + '@'
                + Integer.toHexString(System.identityHashCode(object));
    }

    private synchronized boolean prepareInitialZoom(Camera camera, int generation) {
        if (camera != boundCamera || generation != boundCameraGeneration
                || !boundCameraReady) {
            return false;
        }
        if (initialZoomPreparedGeneration == generation) {
            return true;
        }
        try {
            ZoomState state = camera.getCameraInfo().getZoomState().getValue();
            if (state == null) {
                return false;
            }
            boolean startFromUltraWide = wantsInitialUltraWide()
                    && CameraXUtils.supportsSubOneZoom(provider, boundSelector)
                    && (concurrentPeer == null
                    || !CameraXUtils.isOppoCph2791ConcurrentQuirk());
            baseZoomRatio = CameraXUtils.getBaseZoomRatio(
                    state, startFromUltraWide);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController initial zoom camera=" + cameraId(camera)
                    + " front=" + isFrontface
                    + " concurrent=" + (concurrentPeer != null)
                    + " configuredWide=" + NimarkoConfig.startFromUltraWideCam
                    + " base=" + baseZoomRatio
                    + " range=" + (state == null ? "null"
                    : state.getMinZoomRatio() + ".." + state.getMaxZoomRatio()));
            zoomCoordinator.requestZoomRatio(baseZoomRatio);
            initialZoomPreparedGeneration = generation;
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private boolean prepareInitialZoomIfGraphReady(Camera camera, int generation) {
        
        return prepareInitialZoom(camera, generation);
    }

    private void prepareConcurrentPeerInitialZoomIfReady() {
        NimarkoCameraXController peer = concurrentPeer;
        if (peer == null || !boundCameraReady || !peer.boundCameraReady) return;
        Camera peerCamera = peer.boundCamera;
        if (peerCamera != null) {
            peer.prepareInitialZoom(peerCamera, peer.boundCameraGeneration);
        }
    }

    private boolean wantsInitialUltraWide() {
        return !isFrontface
                && ((useConfiguredUltraWide && NimarkoConfig.startFromUltraWideCam)
                || selectedEffect == CAMERA_WIDE);
    }

    private void applyDeferredInitialCameraControls(Camera camera, int generation) {
        if (camera != boundCamera || generation != boundCameraGeneration
                || !boundCameraReady) {
            return;
        }
        try {
            ExposureState exposure = camera.getCameraInfo().getExposureState();
            if (exposure != null && exposure.isExposureCompensationSupported()) {
                int lower = exposure.getExposureCompensationRange().getLower();
                int upper = exposure.getExposureCompensationRange().getUpper();
                int index = CameraXUtils.configuredExposureToIndex(
                        NimarkoConfig.cameraExposureIndex, lower, upper);
                ListenableFuture<Integer> exposureResult =
                        camera.getCameraControl().setExposureCompensationIndex(index);
                trackControlFuture(exposureResult, camera, generation,
                        "CameraX initial exposure", null);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        applyRequestedTorch();
    }

    private void invalidateBoundCameraControls() {
        cancelConcurrentCameraInUseFailure();
        boundCameraReady = false;
        zoomCoordinator.detach();
        ++boundCameraGeneration;
        try {
            if (boundCameraState != null && boundCameraStateObserver != null) {
                boundCameraState.removeObserver(boundCameraStateObserver);
            }
        } catch (Throwable error) {
            FileLog.e(error);
        }
        boundCameraState = null;
        boundCameraStateObserver = null;
    }

    private void scheduleConcurrentCameraInUseFailure(
            Camera camera, int generation, int errorCode, @Nullable Throwable cause) {
        if (concurrentCameraInUseFailureRunnable != null) return;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (concurrentCameraInUseFailureRunnable != this) return;
                concurrentCameraInUseFailureRunnable = null;
                if (camera != boundCamera || generation != boundCameraGeneration
                        || concurrentPeer == null || boundCameraReady || closed) {
                    return;
                }
                ConcurrentCameraResourceException persistentError =
                        new ConcurrentCameraResourceException(
                        "Concurrent CameraX camera remained unavailable, error=" + errorCode,
                        cause);
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXController concurrent camera-in-use persisted camera="
                        + cameraId(camera) + " error=" + errorCode, persistentError);
                reportFailure(persistentError);
            }
        };
        concurrentCameraInUseFailureRunnable = task;
        
        AndroidUtilities.runOnUIThread(task, 1400L);
    }

    private void cancelConcurrentCameraInUseFailure() {
        Runnable task = concurrentCameraInUseFailureRunnable;
        concurrentCameraInUseFailureRunnable = null;
        if (task != null) {
            AndroidUtilities.cancelRunOnUIThread(task);
        }
    }

    private void trackControlFuture(ListenableFuture<?> future, Camera camera,
                                    int generation, String operation,
                                    @Nullable Runnable onSuccess) {
        if (future == null) return;
        future.addListener(() -> {
            try {
                future.get();
                if (camera == boundCamera && generation == boundCameraGeneration
                        && onSuccess != null) {
                    onSuccess.run();
                }
            } catch (Throwable error) {
                Throwable cause = error instanceof ExecutionException
                        && error.getCause() != null ? error.getCause() : error;
                boolean stale = camera != boundCamera
                        || generation != boundCameraGeneration;
                if (!stale && !(cause instanceof CancellationException)
                        && !(cause instanceof CameraControl.OperationCanceledException)) {
                    FileLog.e(operation + " failed", cause);
                }
            }
        }, ContextCompat.getMainExecutor(ApplicationLoader.applicationContext));
    }

    public void attachProvider(@Nullable ProcessCameraProvider provider) {
        this.provider = provider;
    }

    @Nullable
    public Camera getBoundCamera() {
        return boundCamera;
    }

    @NonNull
    public CameraSelector applyExtensionMode(@NonNull CameraSelector base) {
        int mode = EXTENSION_MODE_NONE;
        if (selectedEffect == CAMERA_HDR) {
            mode = EXTENSION_MODE_HDR;
        } else if (selectedEffect == CAMERA_NIGHT) {
            mode = EXTENSION_MODE_NIGHT;
        } else if (selectedEffect == CAMERA_AUTO) {
            mode = EXTENSION_MODE_AUTO;
        }
        if (mode == EXTENSION_MODE_NONE) return base;
        CameraSelector wrapped = wrapWithExtensionMode(base, mode);
        return wrapped != null ? wrapped : base;
    }

    private static final int EXTENSION_MODE_NONE = 0;
    private static final int EXTENSION_MODE_BOKEH = 1;
    private static final int EXTENSION_MODE_HDR = 2;
    private static final int EXTENSION_MODE_NIGHT = 3;
    private static final int EXTENSION_MODE_FACE_RETOUCH = 4;
    private static final int EXTENSION_MODE_AUTO = 5;

    @Nullable private static volatile Boolean sExtensionsClassAvailable;

    private static boolean extensionsClassAvailable() {
        Boolean cached = sExtensionsClassAvailable;
        if (cached != null) return cached;
        try {
            Class.forName("androidx.camera.extensions.ExtensionsManager");
            sExtensionsClassAvailable = Boolean.TRUE;
            return true;
        } catch (Throwable t) {
            sExtensionsClassAvailable = Boolean.FALSE;
            return false;
        }
    }

    @NonNull
    private CameraSelector currentSelector() {
        return provider != null ? CameraXUtils.buildIntendedCameraSelector(
                provider, isFrontface, useConfiguredUltraWide)
                : (isFrontface ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA);
    }

    private boolean isExtensionAvailable(@NonNull CameraSelector selector, int mode) {
        if (provider == null) return false;
        if (!extensionsClassAvailable()) return false;
        try {
            Object manager = getExtensionsManagerBlocking();
            if (manager == null) return false;
            Method isAvail = manager.getClass().getMethod("isExtensionAvailable", CameraSelector.class, int.class);
            Object result = isAvail.invoke(manager, selector, mode);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private CameraSelector wrapWithExtensionMode(@NonNull CameraSelector base, int mode) {
        if (provider == null) return null;
        if (!extensionsClassAvailable()) return null;
        try {
            Object manager = getExtensionsManagerBlocking();
            if (manager == null) return null;
            Method isAvail = manager.getClass().getMethod("isExtensionAvailable", CameraSelector.class, int.class);
            if (!Boolean.TRUE.equals(isAvail.invoke(manager, base, mode))) return null;
            Method wrap = manager.getClass().getMethod("getExtensionEnabledCameraSelector", CameraSelector.class, int.class);
            Object wrapped = wrap.invoke(manager, base, mode);
            return wrapped instanceof CameraSelector ? (CameraSelector) wrapped : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private Object getExtensionsManagerBlocking() {
        try {
            Class<?> mgrClass = Class.forName("androidx.camera.extensions.ExtensionsManager");
            Method async = mgrClass.getMethod("getInstanceAsync", Context.class, ProcessCameraProvider.class);
            Object future = async.invoke(null, ApplicationLoader.applicationContext, provider);
            if (future instanceof ListenableFuture) {
                return ((ListenableFuture<?>) future).get(2, TimeUnit.SECONDS);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @IntDef({CAMERA_NONE, CAMERA_AUTO, CAMERA_HDR, CAMERA_NIGHT})
    @Retention(RetentionPolicy.SOURCE)
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public @interface EffectFacing {
    }

}
