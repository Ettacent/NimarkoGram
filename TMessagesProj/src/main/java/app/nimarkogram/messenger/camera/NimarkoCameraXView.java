package app.nimarkogram.messenger.camera;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Range;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SessionConfig;
import androidx.camera.core.UseCase;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.NimarkoCameraLog;

public class NimarkoCameraXView extends BaseCameraView {

    private static final long FLOOD_GUARD_MS = 1250L;

    private final PreviewView previewView;
    private final ImageView blurredStubView;
     
    private final ImageView placeholderView;
    private final InternalLifecycle lifecycle;

    private boolean isStreaming;
     
    @Nullable private ValueAnimator textureViewAnimator;

    private int displayOrientation = 0;
     
    private int worldOrientation = 0;

    @Nullable private ProcessCameraProvider provider;
    @Nullable private Camera camera;
    @Nullable private ImageCapture imageCapture;
    @Nullable private Preview preview;
    @Nullable private SessionConfig boundSessionConfig;
    @Nullable private Range<Integer> appliedSessionFpsRange;
    private float baseZoomRatio = 1f;
    private int cameraGeneration;
    private boolean cameraControlsReady;
    private boolean cameraSwitchInProgress;
    private final CameraXZoomCoordinator zoomCoordinator =
            new CameraXZoomCoordinator("CameraX view zoom");

    @Nullable private androidx.lifecycle.LiveData<PreviewView.StreamState> streamStateLD;
    @Nullable private androidx.lifecycle.Observer<PreviewView.StreamState> streamStateObserver;

    @Nullable private VideoCapture<Recorder> videoCapture;
    @Nullable private RecordingSession recordingSession;
    private boolean destroyAfterRecordingFinalizes;
    private boolean streamingEnabled = true;

    private static final class RecordingSession {
        final File file;
        @Nullable final VideoSavedCallback callback;
        @Nullable Recording recording;
        boolean abandoned;
        boolean stopRequested;
        boolean finalizing;

        RecordingSession(File file, @Nullable VideoSavedCallback callback) {
            this.file = file;
            this.callback = callback;
        }
    }

    private boolean frontFacing;
    private boolean initied;
    @Nullable private CameraReadyCallback readyCallback;
    @Nullable private Runnable cameraFailureCallback;
    private boolean cameraFailureDelivered;

    private int flashMode = ImageCapture.FLASH_MODE_AUTO;

    private long lastShutterClickMs;

    @Nullable private Drawable thumbDrawable;
    private final Rect thumbBounds = new Rect();

    private int targetRotation = -1;

    @Nullable private ValueAnimator flipAnimator;
    private boolean flipHalfReached;
    private boolean firstFrameRendered;

    private final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DecelerateInterpolator focusInterpolator = new DecelerateInterpolator();
    private float focusProgress = 1.0f;
    private float innerAlpha;
    private float outerAlpha;
    private long focusLastDrawTime;
    private int focusCx;
    private int focusCy;

    private final DisplayManager.DisplayListener displayOrientationListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) {
            View root = getRootView();
            if (root != null && root.getDisplay() != null && root.getDisplay().getDisplayId() == displayId) {
                displayOrientation = root.getDisplay().getRotation();
                setOrientation(displayOrientation);
            }
        }
    };

    private final OrientationEventListener worldOrientationListener = new OrientationEventListener(getContext()) {
        @Override public void onOrientationChanged(int orientation) {
            if (orientation == ORIENTATION_UNKNOWN) return;
            int rotation;
            if (orientation >= 45 && orientation < 135) {
                rotation = Surface.ROTATION_270;
            } else if (orientation >= 135 && orientation < 225) {
                rotation = Surface.ROTATION_180;
            } else if (orientation >= 225 && orientation < 315) {
                rotation = Surface.ROTATION_90;
            } else {
                rotation = Surface.ROTATION_0;
            }
            worldOrientation = rotation;
            setOrientation(rotation);
        }
    };

    public static boolean hasGoodCamera(Context context) {
        return NimarkoCameraXController.hasGoodCamera(context);
    }

    public NimarkoCameraXView(Context context, boolean frontFacing) {
        super(context);
        this.frontFacing = frontFacing;
        this.lifecycle = new InternalLifecycle();
        
        setBackgroundColor(Color.BLACK);
        this.previewView = new PreviewView(context);
        this.previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        this.previewView.setBackgroundColor(Color.BLACK);
        this.previewView.setFocusableInTouchMode(false);
        this.previewView.setAlpha(0f);
        this.previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER));
        
        this.placeholderView = new ImageView(context);
        this.placeholderView.setVisibility(View.GONE);
        this.placeholderView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(placeholderView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        this.blurredStubView = new ImageView(context);
        this.blurredStubView.setVisibility(View.GONE);
        addView(blurredStubView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setWillNotDraw(false);

        outerPaint.setColor(0xffffffff);
        outerPaint.setStyle(Paint.Style.STROKE);
        outerPaint.setStrokeWidth(AndroidUtilities.dp(2));
        innerPaint.setColor(0x7fffffff);

        try {
            DisplayManager dm = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) dm.registerDisplayListener(displayOrientationListener, null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            worldOrientationListener.enable();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public void setCameraReadyCallback(@Nullable CameraReadyCallback cb) { this.readyCallback = cb; }
    public void setCameraFailureCallback(@Nullable Runnable cb) { this.cameraFailureCallback = cb; }

    private void notifyCameraFailure(Throwable error) {
        initied = false;
        cameraSwitchInProgress = false;
        cameraControlsReady = false;
        zoomCoordinator.detach();
        if (error != null) FileLog.e(error);
        if (!cameraFailureDelivered) {
            cameraFailureDelivered = true;
            Runnable callback = cameraFailureCallback;
            if (callback != null) callback.run();
        }
    }

    @Override @Nullable public View getPreviewView() { return previewView; }
    @Override public boolean isFrontface() { return frontFacing; }
    @Override public boolean isInited() { return initied; }

    @Override @Nullable public Bitmap getBitmap() {
        try { return previewView.getBitmap(); } catch (Throwable t) { return null; }
    }

    @Override public int getOrientation() { return targetRotation < 0 ? 0 : targetRotation; }

    @Override @Nullable
    public org.telegram.messenger.camera.CameraSessionWrapper getCameraSession() { return null; }
    @Override @Nullable public Object getCameraSessionObject() { return null; }

    @Override @Nullable public android.view.TextureView getTextureView() { return null; }

    @Override public boolean isSameTakePictureOrientation() {
        return displayOrientation == worldOrientation;
    }

    @Override
    public void initCamera() {
        final Context ctx = getContext().getApplicationContext();
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.dumpCameraInventory(ctx, "CameraX view init");
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView init front=" + frontFacing
                + " streaming=" + streamingEnabled);
        Executor exec = ContextCompat.getMainExecutor(ctx);
        try {
            ListenableFuture<ProcessCameraProvider> future = CameraXUtils.getProviderFuture(ctx);
            future.addListener(() -> {
                try {
                    provider = future.get();
                    if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView provider ready cameras="
                            + provider.getAvailableCameraInfos().size());
                    lifecycle.markState(Lifecycle.State.RESUMED);
                    if (streamingEnabled && bindUseCases()) {
                        initied = true;
                        if (readyCallback != null) readyCallback.onCameraReady();
                    } else if (streamingEnabled) {
                        notifyCameraFailure(new IllegalStateException("No CameraX use cases were bound"));
                    } else {
                        initied = false;
                    }
                } catch (Throwable t) {
                    if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView provider/init FAILED", t);
                    notifyCameraFailure(t);
                }
            }, exec);
        } catch (Throwable t) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView init dispatch FAILED", t);
            notifyCameraFailure(t);
        }
    }

    @Override
    public void destroyCamera() {
        RecordingSession session = recordingSession;
        if (session != null) {
            
            destroyAfterRecordingFinalizes = true;
            if (session != null) {
                session.stopRequested = true;
                try {
                    if (session.recording != null) session.recording.stop();
                } catch (Throwable t) { FileLog.e(t); }
            }
            return;
        }
        teardownCamera();
    }

    private void teardownCamera() {
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView teardown recording=" + (recordingSession != null)
                + " streaming=" + isStreaming);
        try {
            unbindOwnedUseCases();
        } catch (Throwable ignored) {}
        
        removeStreamStateObserver();
        camera = null;
        imageCapture = null;
        preview = null;
        videoCapture = null;
        boundSessionConfig = null;
        appliedSessionFpsRange = null;
        cameraSwitchInProgress = false;
        cameraControlsReady = false;
        zoomCoordinator.detach();
        lifecycle.markState(Lifecycle.State.DESTROYED);
        initied = false;
        placeholderView.animate().setListener(null).cancel();
        blurredStubView.animate().setListener(null).cancel();
        releaseImageBitmap(placeholderView);
        releaseBackgroundBitmap(blurredStubView);
        placeholderView.setVisibility(View.GONE);
        blurredStubView.setVisibility(View.GONE);
    }

    private void unbindOwnedUseCases() {
        if (provider == null) return;
        cameraControlsReady = false;
        ++cameraGeneration;
        zoomCoordinator.detach();
        if (boundSessionConfig != null) {
            try {
                if (provider.isBound(boundSessionConfig)) {
                    provider.unbind(boundSessionConfig);
                }
                boundSessionConfig = null;
                appliedSessionFpsRange = null;
                return;
            } catch (Throwable error) {
                FileLog.e("CameraX session unbind failed", error);
                boundSessionConfig = null;
                appliedSessionFpsRange = null;
            }
        }
        ArrayList<UseCase> owned = new ArrayList<>(3);
        if (preview != null) owned.add(preview);
        if (imageCapture != null) owned.add(imageCapture);
        if (videoCapture != null) owned.add(videoCapture);
        if (!owned.isEmpty()) {
            provider.unbind(owned.toArray(new UseCase[0]));
        }
    }

    public void setStreamingEnabled(boolean enabled) {
        if (streamingEnabled == enabled) return;
        streamingEnabled = enabled;
        if (!enabled) {
            isStreaming = false;
            cameraSwitchInProgress = false;
            removeStreamStateObserver();
            if (!isRecordingOrFinalizing()) {
                try { unbindOwnedUseCases(); } catch (Throwable t) { FileLog.e(t); }
                camera = null;
                initied = false;
            }
        } else if (!isRecordingOrFinalizing() && provider != null
                && lifecycle.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED) {
            initied = bindUseCases();
            if (initied && readyCallback != null) readyCallback.onCameraReady();
            else if (!initied) notifyCameraFailure(new IllegalStateException("CameraX re-enable bind failed"));
        }
    }

    public boolean isRecordingOrFinalizing() {
        return recordingSession != null;
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private Preview buildPreview(CameraSelector selector, Size targetSize, int aspectRatio,
                                 boolean applyEnhancements) {
        Preview.Builder previewBuilder = new Preview.Builder()
                .setResolutionSelector(CameraXUtils.buildResolutionSelector(
                        targetSize, aspectRatio, true));
        Camera2Interop.Extender<Preview> previewExtender = null;
        if (applyEnhancements) {
            previewExtender = new Camera2Interop.Extender<>(previewBuilder);
            CameraXUtils.applyCamera2Controls(provider, selector,
                    previewExtender, false);
        }
        if (!frontFacing && NimarkoConfig.startFromUltraWideCam
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (previewExtender == null) {
                    previewExtender = new Camera2Interop.Extender<>(previewBuilder);
                }
                androidx.camera.core.CameraInfo info = provider.getCameraInfo(selector);
                androidx.camera.core.ZoomState zoomState = info == null ? null
                        : info.getZoomState().getValue();
                float initialRatio = CameraXUtils.getBaseZoomRatio(zoomState, true);
                if (initialRatio < 0.999f) {
                    previewExtender.setCaptureRequestOption(
                            CaptureRequest.CONTROL_ZOOM_RATIO, initialRatio);
                    baseZoomRatio = initialRatio;
                    if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView initial wide request ratio="
                            + initialRatio);
                }
            } catch (Throwable error) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView initial wide request unavailable", error);
            }
        }
        if (applyEnhancements
                && CameraXUtils.shouldEnablePreviewStabilization(provider, selector)) {
            previewBuilder.setPreviewStabilizationEnabled(true);
        }
        return previewBuilder.build();
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private ImageCapture buildImageCapture(CameraSelector selector, @Nullable Size targetSize,
                                           int aspectRatio, int rotation,
                                           boolean applyEnhancements,
                                           boolean preferCaptureRate) {
        ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .setTargetRotation(rotation)
                .setResolutionSelector(CameraXUtils.buildResolutionSelector(
                        targetSize, aspectRatio, preferCaptureRate));
        if (applyEnhancements) {
            CameraXUtils.applyCamera2Controls(provider, selector,
                    new Camera2Interop.Extender<>(captureBuilder), true);
        }
        ImageCapture capture = captureBuilder.build();
        capture.setFlashMode(flashMode);
        return capture;
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private boolean bindUseCases() {
        if (provider == null || !streamingEnabled || isRecordingOrFinalizing()) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView bind skipped provider=" + (provider != null)
                    + " streamingEnabled=" + streamingEnabled
                    + " recording=" + isRecordingOrFinalizing());
            return false;
        }
        unbindOwnedUseCases();
        camera = null;
        isStreaming = false;
        
        removeStreamStateObserver();

        CameraSelector selector =
                CameraXUtils.buildIntendedCameraSelector(provider, frontFacing);

        int aspectRatio = AspectRatio.RATIO_16_9;

        final Size previewTargetSize = CameraXUtils.getAttachPreviewResolutionSize();
        final Size captureTargetSize = CameraXUtils.getTargetResolutionSize();
        
        final Range<Integer> targetFps = CameraXUtils.getTargetFpsRange();

        int effectiveRotation = targetRotation >= 0 ? targetRotation : worldOrientation;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView bind begin front=" + frontFacing
                + " previewTarget=" + previewTargetSize
                + " captureTarget=" + captureTargetSize
                + " targetFps=" + targetFps + " rotation=" + effectiveRotation);
        imageCapture = buildImageCapture(
                selector, captureTargetSize, aspectRatio, effectiveRotation,
                true, false);

        preview = buildPreview(
                selector, previewTargetSize, aspectRatio, true);
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        videoCapture = buildVideoCapture(
                selector, effectiveRotation, true);
        camera = bindWithFallback(
                selector, previewTargetSize, captureTargetSize, aspectRatio,
                effectiveRotation, targetFps);
        if (camera == null) {
            
            FileLog.e("NimarkoCameraXView.bindUseCases: all use-case binds failed");
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView bind FAILED all fallback profiles front="
                    + frontFacing);
            imageCapture = null;
            preview = null;
            videoCapture = null;
            camera = null;
            return false;
        }
        final Camera boundCamera = camera;
        final int generation = cameraGeneration;
        zoomCoordinator.attach(boundCamera, generation);
        
        try {
            
            streamStateObserver = state -> {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView stream state=" + state
                        + " front=" + frontFacing + " generation=" + generation);
                if (state == PreviewView.StreamState.STREAMING) {
                    
                    AndroidUtilities.runOnUIThread(() -> {
                        if (boundCamera != camera || generation != cameraGeneration) {
                            return;
                        }
                        isStreaming = true;
                        firstFrameRendered = true;
                        cameraSwitchInProgress = false;
                        cameraControlsReady = true;
                        zoomCoordinator.setReady(boundCamera, generation, true);
                        applyStableCameraControls(boundCamera, generation);
                        hideSwitchPlaceholder();
                        if (previewView.getAlpha() == 0f) {
                            showTexture(true, true);
                        }
                        onFirstFrameRendered();
                    }, 120L);
                } else if (state == PreviewView.StreamState.IDLE) {
                    cameraControlsReady = false;
                    zoomCoordinator.setReady(boundCamera, generation, false);
                }
                
                AndroidUtilities.runOnUIThread(() ->
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.cameraInitied));
            };
            streamStateLD = previewView.getPreviewStreamState();
            streamStateLD.observe(lifecycle, streamStateObserver);
        } catch (Throwable t) {
            FileLog.e(t);
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView stream observer FAILED", t);
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView bind success front=" + frontFacing
                + " generation=" + generation + " fps=" + appliedSessionFpsRange);
        return true;
    }

    private void removeStreamStateObserver() {
        try {
            if (streamStateLD != null && streamStateObserver != null) {
                streamStateLD.removeObserver(streamStateObserver);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        streamStateLD = null;
        streamStateObserver = null;
    }

    private void applyStableCameraControls(Camera boundCamera, int generation) {
        if (boundCamera != camera || generation != cameraGeneration
                || !cameraControlsReady) {
            return;
        }
        try {
            androidx.camera.core.ZoomState zoomState =
                    boundCamera.getCameraInfo().getZoomState().getValue();
            boolean ultraWide = !frontFacing
                    && NimarkoConfig.startFromUltraWideCam
                    && CameraXUtils.isWideAngleAvailable(provider);
            baseZoomRatio = CameraXUtils.getBaseZoomRatio(zoomState, ultraWide);
            zoomCoordinator.requestZoomRatio(baseZoomRatio);
        } catch (Throwable error) {
            FileLog.e(error);
        }
        try {
            ExposureState exposure = boundCamera.getCameraInfo().getExposureState();
            if (exposure != null && exposure.isExposureCompensationSupported()) {
                int lower = exposure.getExposureCompensationRange().getLower();
                int upper = exposure.getExposureCompensationRange().getUpper();
                int index = CameraXUtils.configuredExposureToIndex(
                        NimarkoConfig.cameraExposureIndex, lower, upper);
                ListenableFuture<Integer> exposureFuture =
                        boundCamera.getCameraControl().setExposureCompensationIndex(index);
                trackControlFuture(exposureFuture, boundCamera, generation,
                        "CameraX initial exposure");
            }
        } catch (Throwable error) {
            FileLog.e(error);
        }
    }

    private void trackControlFuture(ListenableFuture<?> future, Camera boundCamera,
                                    int generation, String operation) {
        if (future == null) return;
        future.addListener(() -> {
            try {
                future.get();
            } catch (Throwable error) {
                Throwable cause = error instanceof ExecutionException
                        && error.getCause() != null ? error.getCause() : error;
                boolean stale = boundCamera != camera || generation != cameraGeneration;
                if (!stale && !(cause instanceof CancellationException)
                        && !(cause instanceof CameraControl.OperationCanceledException)) {
                    FileLog.e(operation + " failed", cause);
                }
            }
        }, ContextCompat.getMainExecutor(getContext().getApplicationContext()));
    }

    private void hideSwitchPlaceholder() {
        if (placeholderView.getVisibility() != View.VISIBLE) return;
        final int generation = cameraGeneration;
        placeholderView.animate().setListener(null).cancel();
        placeholderView.animate()
                .alpha(0f)
                .setDuration(100L)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (generation != cameraGeneration) return;
                        releaseImageBitmap(placeholderView);
                        placeholderView.setVisibility(View.GONE);
                        placeholderView.setAlpha(1f);
                        placeholderView.animate().setListener(null);
                    }
                })
                .start();
    }

    private void showSwitchPlaceholder(Bitmap bitmap) {
        if (bitmap == null) return;
        placeholderView.animate().setListener(null).cancel();
        placeholderView.setAlpha(1f);
        releaseImageBitmap(placeholderView);
        placeholderView.setImageBitmap(bitmap);
        placeholderView.setVisibility(View.VISIBLE);
    }

    @Nullable
    private TextureView findPreviewTextureView(View view) {
        if (view instanceof TextureView) {
            return (TextureView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextureView texture = findPreviewTextureView(group.getChildAt(i));
                if (texture != null) return texture;
            }
        }
        return null;
    }

    @Nullable
    private Bitmap captureTransitionFrame(int maxDimension) {
        try {
            TextureView texture = findPreviewTextureView(previewView);
            if (texture != null && texture.isAvailable()
                    && texture.getWidth() > 0 && texture.getHeight() > 0) {
                float scale = Math.min(1f, maxDimension
                        / (float) Math.max(texture.getWidth(), texture.getHeight()));
                int width = Math.max(1, Math.round(texture.getWidth() * scale));
                int height = Math.max(1, Math.round(texture.getHeight() * scale));
                return texture.getBitmap(width, height);
            }
            Bitmap full = previewView.getBitmap();
            if (full == null || full.getWidth() <= 0 || full.getHeight() <= 0) {
                return full;
            }
            float scale = Math.min(1f, maxDimension
                    / (float) Math.max(full.getWidth(), full.getHeight()));
            if (scale >= 1f) return full;
            Bitmap scaled = Bitmap.createScaledBitmap(full,
                    Math.max(1, Math.round(full.getWidth() * scale)),
                    Math.max(1, Math.round(full.getHeight() * scale)), true);
            if (scaled != full && !full.isRecycled()) full.recycle();
            return scaled;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private void setBlurredStubBitmap(@Nullable Bitmap bitmap) {
        releaseBackgroundBitmap(blurredStubView);
        blurredStubView.setBackground(bitmap == null ? null : new BitmapDrawable(
                ApplicationLoader.applicationContext.getResources(), bitmap));
    }

    private static void releaseImageBitmap(ImageView view) {
        Drawable drawable = view.getDrawable();
        view.setImageDrawable(null);
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static void releaseBackgroundBitmap(View view) {
        Drawable drawable = view.getBackground();
        view.setBackground(null);
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void onFirstFrameRendered() {
        if (blurredStubView.getVisibility() == View.VISIBLE && flipAnimator == null) {
            blurredStubView.animate().alpha(0).setListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    blurredStubView.setVisibility(View.GONE);
                    releaseBackgroundBitmap(blurredStubView);
                }
            }).start();
        }
    }

    @Override
    public void switchCamera() {
        if (isRecordingOrFinalizing() || cameraSwitchInProgress) return;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView switch requested fromFront=" + frontFacing
                + " streaming=" + isStreaming);
        
        if (isStreaming) {
            try {
                Bitmap previewBitmap = captureTransitionFrame(320);
                if (previewBitmap != null) {
                    showSwitchPlaceholder(previewBitmap);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        cameraSwitchInProgress = true;
        frontFacing = !frontFacing;
        if (!bindUseCases()) {
            cameraSwitchInProgress = false;
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView switch FAILED toFront=" + frontFacing);
            notifyCameraFailure(new IllegalStateException("CameraX lens switch failed"));
        }
    }

    @Override
    public void setZoom(float zoom) {
        Camera boundCamera = camera;
        if (boundCamera == null || !cameraControlsReady) return;
        try {
            androidx.camera.core.ZoomState state =
                    boundCamera.getCameraInfo().getZoomState().getValue();
            zoomCoordinator.requestZoomRatio(
                    CameraXUtils.normalizedZoomToRatio(
                            state, baseZoomRatio, zoom));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    @Override
    public float getZoom() {
        if (camera == null) return 0f;
        try {
            androidx.camera.core.ZoomState state =
                    camera.getCameraInfo().getZoomState().getValue();
            if (state == null) return 0f;
            return CameraXUtils.zoomRatioToNormalized(
                    state, baseZoomRatio,
                    zoomCoordinator.getRequestedOr(state.getZoomRatio()));
        } catch (Throwable t) {
            return 0f;
        }
    }

    @Override
    public boolean isExposureCompensationSupported() {
        if (camera == null) return false;
        ExposureState es = camera.getCameraInfo().getExposureState();
        return es != null && es.isExposureCompensationSupported();
    }

    @Override
    public void setExposureCompensation(float value0to1) {
        Camera boundCamera = camera;
        final int generation = cameraGeneration;
        if (boundCamera == null || !cameraControlsReady) return;
        try {
            ExposureState es = boundCamera.getCameraInfo().getExposureState();
            if (es == null || !es.isExposureCompensationSupported()) return;
            int lo = es.getExposureCompensationRange().getLower();
            int hi = es.getExposureCompensationRange().getUpper();
            int idx = CameraXUtils.normalizedExposureToIndex(value0to1, lo, hi);
            CameraControl cc = boundCamera.getCameraControl();
            ListenableFuture<Integer> future =
                    cc.setExposureCompensationIndex(idx);
            trackControlFuture(
                    future, boundCamera, generation, "CameraX exposure compensation");
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    @Override
    public void takePicture(File output, @Nullable SavedCallback cb) {
        if (imageCapture == null) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView photo rejected imageCapture=null file=" + output);
            if (cb != null) cb.onSaved(false);
            return;
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView photo start front=" + frontFacing + " file=" + output);
        lastShutterClickMs = SystemClock.elapsedRealtime();
        runHaptic();
        Executor exec = ContextCompat.getMainExecutor(getContext().getApplicationContext());
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(output).build();
        imageCapture.takePicture(opts, exec, new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView photo saved file=" + output
                        + " uri=" + outputFileResults.getSavedUri());
                if (cb != null) cb.onSaved(true);
            }
            @Override public void onError(ImageCaptureException exception) {
                FileLog.e(exception);
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXView photo FAILED file=" + output, exception);
                if (cb != null) cb.onSaved(false);
            }
        });
    }

    @Override
    public void takePicture(File file, @Nullable Runnable onTake) {
        lastShutterClickMs = SystemClock.elapsedRealtime();
        takePicture(file, success -> { if (onTake != null) onTake.run(); });
    }

    @Override public boolean isFlooding() {
        return SystemClock.elapsedRealtime() - lastShutterClickMs < FLOOD_GUARD_MS;
    }

    public void setFlash(int mode) {
        switch (mode) {
            case ImageCapture.FLASH_MODE_ON:
            case ImageCapture.FLASH_MODE_OFF:
            case ImageCapture.FLASH_MODE_AUTO:
                flashMode = mode;
                break;
            default:
                flashMode = ImageCapture.FLASH_MODE_AUTO;
                break;
        }
        if (imageCapture != null) {
            try { imageCapture.setFlashMode(flashMode); } catch (Throwable t) { FileLog.e(t); }
        }
    }

    @Override
    public void setFlashMode(int mode) { setFlash(mode); }

    @Override
    public int getFlashMode() { return flashMode; }

    @Override public String getCurrentFlashMode() {
        return mapFlashMode(flashMode);
    }

    @Override public String setNextFlashMode() {
        int next;
        switch (flashMode) {
            case ImageCapture.FLASH_MODE_AUTO: next = ImageCapture.FLASH_MODE_ON;   break;
            case ImageCapture.FLASH_MODE_ON:   next = ImageCapture.FLASH_MODE_OFF;  break;
            case ImageCapture.FLASH_MODE_OFF:
            default:                            next = ImageCapture.FLASH_MODE_AUTO; break;
        }
        setFlash(next);
        return mapFlashMode(flashMode);
    }

    private static String mapFlashMode(int result) {
        switch (result) {
            case ImageCapture.FLASH_MODE_ON:  return "on";
            case ImageCapture.FLASH_MODE_OFF: return "off";
            default:                          return "auto";
        }
    }

    @Override public boolean isFlashAvailable() {
        if (camera == null) return false;
        try {
            return camera.getCameraInfo().hasFlashUnit();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void focusToPoint(int x, int y) {
        if (camera != null) {
            try {
                MeteringPointFactory factory = previewView.getMeteringPointFactory();
                MeteringPoint point = factory.createPoint(x, y);
                FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build();
                camera.getCameraControl().startFocusAndMetering(action);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        
        focusProgress = 0.0f;
        innerAlpha = 1.0f;
        outerAlpha = 1.0f;
        focusCx = x;
        focusCy = y;
        focusLastDrawTime = System.currentTimeMillis();
        invalidate();
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        boolean result = super.drawChild(canvas, child, drawingTime);
        if (focusProgress != 1.0f || innerAlpha != 0.0f || outerAlpha != 0.0f) {
            int baseRad = AndroidUtilities.dp(30);
            long newTime = System.currentTimeMillis();
            long dt = newTime - focusLastDrawTime;
            if (dt < 0 || dt > 17) {
                dt = 17;
            }
            focusLastDrawTime = newTime;
            outerPaint.setAlpha((int) (focusInterpolator.getInterpolation(outerAlpha) * 255));
            innerPaint.setAlpha((int) (focusInterpolator.getInterpolation(innerAlpha) * 127));
            float interpolated = focusInterpolator.getInterpolation(focusProgress);
            canvas.drawCircle(focusCx, focusCy, baseRad + baseRad * (1.0f - interpolated), outerPaint);
            canvas.drawCircle(focusCx, focusCy, baseRad * interpolated, innerPaint);

            if (focusProgress < 1) {
                focusProgress += dt / 200.0f;
                if (focusProgress > 1) {
                    focusProgress = 1;
                }
                invalidate();
            } else if (innerAlpha != 0) {
                innerAlpha -= dt / 150.0f;
                if (innerAlpha < 0) {
                    innerAlpha = 0;
                }
                invalidate();
            } else if (outerAlpha != 0) {
                outerAlpha -= dt / 150.0f;
                if (outerAlpha < 0) {
                    outerAlpha = 0;
                }
                invalidate();
            }
        }
        return result;
    }

    @SuppressLint("MissingPermission")
    @Override public void runHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator == null) return;
                VibrationEffect effect = VibrationEffect.createWaveform(new long[]{0, 1}, -1);
                vibrator.cancel();
                vibrator.vibrate(effect);
            } else {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        } catch (Throwable ignored) {}
    }

    @Override public void setOrientation(int rotation) {
        targetRotation = CameraXUtils.toSurfaceRotation(rotation);
        if (imageCapture != null) {
            try { imageCapture.setTargetRotation(targetRotation); } catch (Throwable t) { FileLog.e(t); }
        }
        if (videoCapture != null) {
            try { videoCapture.setTargetRotation(targetRotation); } catch (Throwable t) { FileLog.e(t); }
        }
    }

    @Nullable
    public android.util.Size getPreviewSize() {
        if (preview == null) return null;
        try {
            return preview.getResolutionInfo() != null
                    ? preview.getResolutionInfo().getResolution()
                    : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void showTexture(boolean show, boolean animated) {
        if (previewView == null) return;
        if (textureViewAnimator != null) {
            textureViewAnimator.cancel();
            textureViewAnimator = null;
        }
        if (animated) {
            textureViewAnimator = ValueAnimator.ofFloat(previewView.getAlpha(), show ? 1f : 0f);
            textureViewAnimator.addUpdateListener(anm -> previewView.setAlpha((float) anm.getAnimatedValue()));
            textureViewAnimator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    previewView.setAlpha(show ? 1f : 0f);
                    textureViewAnimator = null;
                }
            });
            textureViewAnimator.start();
        } else {
            previewView.setAlpha(show ? 1f : 0f);
        }
    }

    @Override
    public float getTextureHeight(float width, float height) {
        android.util.Size previewSize = getPreviewSize();
        if (previewSize == null) return height;
        int frameWidth, frameHeight;
        if (worldOrientation == Surface.ROTATION_90 || worldOrientation == Surface.ROTATION_270) {
            frameWidth = previewSize.getWidth();
            frameHeight = previewSize.getHeight();
        } else {
            frameWidth = previewSize.getHeight();
            frameHeight = previewSize.getWidth();
        }
        float s = Math.max(width / (float) frameWidth, height / (float) frameHeight);
        return (int) (s * frameHeight);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        android.util.Size previewSize = getPreviewSize();
        if (previewSize != null) {
            int frameWidth, frameHeight;
            if (worldOrientation == Surface.ROTATION_90 || worldOrientation == Surface.ROTATION_270) {
                frameWidth = previewSize.getWidth();
                frameHeight = previewSize.getHeight();
            } else {
                frameWidth = previewSize.getHeight();
                frameHeight = previewSize.getWidth();
            }
            float s = Math.min(
                    MeasureSpec.getSize(widthMeasureSpec) / (float) frameWidth,
                    MeasureSpec.getSize(heightMeasureSpec) / (float) frameHeight);
            blurredStubView.getLayoutParams().width = (int) (s * frameWidth);
            blurredStubView.getLayoutParams().height = (int) (s * frameHeight);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try {
            DisplayManager dm = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) dm.unregisterDisplayListener(displayOrientationListener);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            worldOrientationListener.disable();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    @Override public boolean isHdrModeSupported() { return false; }

    public boolean isNightModeSupported() { return false; }

    public boolean isAutoModeSupported() { return false; }

    @Override public boolean isWideModeSupported() {
        if (provider == null) return false;
        try {
            return CameraXUtils.findBackUltraWideCameraId(provider) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private int currentEffect = NimarkoCameraXController.CAMERA_NONE;

    public void changeEffect(@NimarkoCameraXController.EffectFacing int effect) {
        if (isRecordingOrFinalizing() || currentEffect == effect) return;
        if (isStreaming) {
            try {
                Bitmap previewBitmap = captureTransitionFrame(320);
                if (previewBitmap != null) {
                    showSwitchPlaceholder(previewBitmap);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        currentEffect = effect;
        
        if (!bindUseCases()) notifyCameraFailure(new IllegalStateException("CameraX effect bind failed"));
    }

    public int getCameraEffect() { return currentEffect; }

    public void startChangeEffectAnimation() {
        placeholderView.animate().setListener(null).cancel();
        releaseImageBitmap(placeholderView);
        placeholderView.setAlpha(1f);
        placeholderView.setVisibility(View.GONE);
        blurredStubView.animate().setListener(null).cancel();
        if (firstFrameRendered) {
            try {
                Bitmap bitmap = captureTransitionFrame(100);
                if (bitmap != null) {
                    Utilities.blurBitmap(bitmap, 3);
                    setBlurredStubBitmap(bitmap);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        blurredStubView.setAlpha(1f);
        blurredStubView.setVisibility(View.VISIBLE);
        firstFrameRendered = false;
        invalidate();
    }

    @Override public void setThumbDrawable(@Nullable Drawable drawable) {
        if (thumbDrawable != null) thumbDrawable.setCallback(null);
        thumbDrawable = drawable;
        if (thumbDrawable != null) thumbDrawable.setCallback(this);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (thumbDrawable != null) {
            thumbBounds.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
            int W = thumbDrawable.getIntrinsicWidth();
            int H = thumbDrawable.getIntrinsicHeight();
            if (W > 0 && H > 0) {
                float scale = 1f / Math.min(
                        W / (float) Math.max(1, thumbBounds.width()),
                        H / (float) Math.max(1, thumbBounds.height()));
                thumbDrawable.setBounds(
                        (int) (thumbBounds.centerX() - W * scale / 2f),
                        (int) (thumbBounds.centerY() - H * scale / 2f),
                        (int) (thumbBounds.centerX() + W * scale / 2f),
                        (int) (thumbBounds.centerY() + H * scale / 2f));
                thumbDrawable.draw(canvas);
            }
        }
        super.onDraw(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (flipAnimator != null) canvas.drawColor(Color.BLACK);
        super.dispatchDraw(canvas);
    }

    @Override public void startSwitchingAnimation() {
        if (flipAnimator != null) flipAnimator.cancel();
        blurredStubView.animate().setListener(null).cancel();

        if (firstFrameRendered) {
            try {
                Bitmap bitmap = captureTransitionFrame(100);
                if (bitmap != null) {
                    Utilities.blurBitmap(bitmap, 3);
                    setBlurredStubBitmap(bitmap);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            blurredStubView.setAlpha(0f);
        } else {
            blurredStubView.setAlpha(1f);
        }
        blurredStubView.setVisibility(View.VISIBLE);
        firstFrameRendered = false;
        flipHalfReached = false;

        flipAnimator = ValueAnimator.ofFloat(0f, 1f);
        flipAnimator.addUpdateListener(va -> {
            float v = (float) va.getAnimatedValue();
            float rotation;
            boolean halfReached = false;
            if (v < 0.5f) {
                rotation = v;
            } else {
                halfReached = true;
                rotation = v - 1f;
            }
            rotation *= 180f;
            previewView.setRotationY(rotation);
            blurredStubView.setRotationY(rotation);
            if (halfReached && !flipHalfReached) {
                blurredStubView.setAlpha(1f);
                flipHalfReached = true;
            }
        });
        flipAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                flipAnimator = null;
                previewView.setRotationY(0f);
                blurredStubView.setRotationY(0f);
                if (!flipHalfReached) {
                    blurredStubView.setAlpha(1f);
                    flipHalfReached = true;
                }
                invalidate();
            }
        });
        flipAnimator.setDuration(400);
        flipAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        flipAnimator.start();
        invalidate();
    }

    @Override
    public boolean hasFrontFaceCamera() {
        if (provider == null) return false;
        try {
            return provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public void rebind() {
        if (isRecordingOrFinalizing()) return;
        
        if (isStreaming) {
            try {
                Bitmap previewBitmap = captureTransitionFrame(320);
                if (previewBitmap != null) {
                    showSwitchPlaceholder(previewBitmap);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        if (!bindUseCases() && streamingEnabled) {
            notifyCameraFailure(new IllegalStateException("CameraX rebind failed"));
        }
    }

    @Override public void closeCamera() { destroyCamera(); }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private VideoCapture<Recorder> buildVideoCapture(CameraSelector selector, int rotation,
                                                     boolean applyEnhancements) {
        final Quality configured = CameraXUtils.getVideoQuality(selector, provider);
        QualitySelector qs = QualitySelector.from(configured,
                FallbackStrategy.lowerQualityOrHigherThan(configured));
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(qs)
                .build();
        VideoCapture.Builder<Recorder> builder = new VideoCapture.Builder<>(recorder)
                .setTargetRotation(rotation);
        if (applyEnhancements
                && CameraXUtils.shouldEnableVideoStabilization(provider, selector)) {
            builder.setVideoStabilizationEnabled(true);
        }
        return builder.build();
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    @Nullable
    private Camera bindWithFallback(CameraSelector selector,
                                    @Nullable Size previewTargetSize,
                                    @Nullable Size captureTargetSize,
                                    int aspectRatio, int rotation,
                                    @Nullable Range<Integer> targetFps) {
        boolean requestedFpsWasApplied = false;
        try {
            Camera result = bindSessionGraph(selector, targetFps);
            requestedFpsWasApplied = appliedSessionFpsRange != null;
            return result;
        } catch (Throwable e1) {
            requestedFpsWasApplied = appliedSessionFpsRange != null;
            FileLog.e(e1);
        }
        if (requestedFpsWasApplied) {
            try {
                unbindOwnedUseCases();
                return bindSessionGraph(selector, null);
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
        
        try {
            unbindOwnedUseCases();
            imageCapture = buildImageCapture(
                    selector, captureTargetSize, aspectRatio, rotation,
                    false, false);
            preview = buildPreview(
                    selector, previewTargetSize, aspectRatio, false);
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            videoCapture = buildVideoCapture(
                    selector, rotation, false);
            return bindSessionGraph(selector, null);
        } catch (Throwable e3) {
            FileLog.e(e3);
        }
        
        try {
            unbindOwnedUseCases();
            videoCapture = null;
            return bindSessionGraph(selector, null);
        } catch (Throwable e4) {
            FileLog.e(e4);
        }
        return null;
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private Camera bindSessionGraph(CameraSelector selector,
                                    @Nullable Range<Integer> requestedFps) {
        List<UseCase> useCases = new ArrayList<>(3);
        if (preview != null) useCases.add(preview);
        if (imageCapture != null) useCases.add(imageCapture);
        if (videoCapture != null) useCases.add(videoCapture);
        if (useCases.isEmpty()) {
            throw new IllegalStateException("CameraX session has no use cases");
        }
        appliedSessionFpsRange = requestedFps == null ? null
                : CameraXUtils.getSupportedTargetFpsRange(
                        provider, selector, useCases);
        SessionConfig.Builder builder = new SessionConfig.Builder(useCases);
        if (appliedSessionFpsRange != null) {
            builder.setFrameRateRange(appliedSessionFpsRange);
        }
        boundSessionConfig = builder.build();
        return provider.bindToLifecycle(
                lifecycle, selector, boundSessionConfig);
    }

    @Override
    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError", "MissingPermission"})
    public void recordVideo(File path, boolean mirrorThumb, @Nullable VideoSavedCallback onStop) {
        if (videoCapture == null || path == null) {
            FileLog.d("NimarkoCameraXView.recordVideo: VideoCapture unavailable on this device");
            if (onStop != null) onStop.onFinishVideoRecording(null, 0L);
            return;
        }
        if (recordingSession != null) {
            FileLog.d("NimarkoCameraXView.recordVideo: previous recording is still finalizing");
            if (onStop != null) onStop.onFinishVideoRecording(null, 0L);
            return;
        }
        final RecordingSession session = new RecordingSession(path, onStop);
        try {
            recordingSession = session;

            Recorder recorder = videoCapture.getOutput();
            FileOutputOptions out = new FileOutputOptions.Builder(path).build();
            PendingRecording pending = recorder.prepareRecording(getContext(), out);
            boolean audioGranted = ContextCompat.checkSelfPermission(getContext(),
                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (audioGranted) {
                pending = pending.withAudioEnabled();
            }
            final Recording started = pending.start(ContextCompat.getMainExecutor(getContext()), event -> {
                if (event instanceof VideoRecordEvent.Finalize) {
                    onRecordingFinalized(session, (VideoRecordEvent.Finalize) event);
                }
            });
            session.recording = started;
            
            if (session.stopRequested) {
                started.stop();
            }
        } catch (Throwable t) {
            FileLog.e(t);
            if (recordingSession == session) recordingSession = null;
            try { if (path.isFile()) path.delete(); } catch (Throwable ignored) {}
            if (session.callback != null) session.callback.onFinishVideoRecording(null, 0L);
        }
    }

    @Override
    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    public void stopVideoRecording(boolean abandon) {
        RecordingSession session = recordingSession;
        if (session == null) return;
        session.abandoned = abandon;
        session.stopRequested = true;
        try {
            if (session.recording != null) {
                session.recording.stop();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private void onRecordingFinalized(RecordingSession session, VideoRecordEvent.Finalize event) {
        if (recordingSession != session || session.finalizing) return;
        session.finalizing = true;
        session.recording = null;
        final File file = session.file;
        final VideoSavedCallback cb = session.callback;
        final boolean abandoned = session.abandoned;

        long durationMs = 0L;
        try {
            durationMs = event.getRecordingStats().getRecordedDurationNanos() / 1_000_000L;
        } catch (Throwable ignored) {}

        boolean finalizeReportedError = false;
        try {
            
            finalizeReportedError = event.hasError();
        } catch (Throwable ignored) {}

        if (durationMs <= 0 && file != null) durationMs = readVideoDuration(file);
        boolean validFile = file != null && file.isFile() && file.length() > 0 && durationMs > 0;
        if (finalizeReportedError && validFile) {
            FileLog.d("NimarkoCameraXView: preserving finalized video despite recoverable CameraX error");
        }
        if (abandoned || !validFile || cb == null) {
            if (file != null) { try { file.delete(); } catch (Throwable ignored) {} }
            
            try {
                if (cb != null && !abandoned) cb.onFinishVideoRecording(null, 0L);
            } finally {
                completeRecordingSession(session);
            }
            return;
        }

        final long fDurationMs = durationMs;
        Utilities.globalQueue.postRunnable(() -> {
            final String thumbPath = generateVideoThumb(file);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (cb != null) cb.onFinishVideoRecording(thumbPath, fDurationMs);
                } finally {
                    completeRecordingSession(session);
                }
            });
        });
    }

    private void completeRecordingSession(RecordingSession session) {
        if (recordingSession == session) recordingSession = null;
        finishDeferredDestroy();
    }

    private long readVideoDuration(File file) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            if (retriever != null) try { retriever.release(); } catch (Throwable ignored) {}
        }
    }

    private void finishDeferredDestroy() {
        if (destroyAfterRecordingFinalizes) {
            destroyAfterRecordingFinalizes = false;
            teardownCamera();
        } else if (!streamingEnabled) {
            try { unbindOwnedUseCases(); } catch (Throwable t) { FileLog.e(t); }
            camera = null;
            initied = false;
        }
    }

    @Nullable
    private String generateVideoThumb(File videoFile) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            File thumbFile = new File(videoFile.getParentFile(), videoFile.getName() + ".jpg");
            FileOutputStream fos = new FileOutputStream(thumbFile);
            try {
                frame.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
            }
            frame.recycle();
            return thumbFile.getAbsolutePath();
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * NG CG-port: parity with {@code CameraXView.setRecordFile}. CG declares
     * this method with an empty body — the stock-CameraView API requires
     * pre-allocating an output file before recordVideo, but the CameraX path
     * resolves its own output target inside {@link #recordVideo}, so there's
     * nothing to store. We keep the explicit override so callers that work
     * against {@link BaseCameraView} don't silently fall through to the
     * default no-op without a documented reason.
     */
    @Override
    public void setRecordFile(java.io.File generateVideoPath) {
        
    }

    @Override
    public void setFpsLimit(int fpsLimit) {
        
    }

    @Override
    public void setDelegate(@Nullable org.telegram.messenger.camera.CameraView.CameraViewDelegate delegate) {
        if (delegate == null) { this.readyCallback = null; return; }
        this.readyCallback = delegate::onCameraInit;
    }

    private static final class InternalLifecycle implements LifecycleOwner {
        private final LifecycleRegistry registry;
        InternalLifecycle() {
            this.registry = new LifecycleRegistry(this);
            this.registry.setCurrentState(Lifecycle.State.INITIALIZED);
        }
        void markState(Lifecycle.State state) {
            this.registry.setCurrentState(state);
        }
        @Override public Lifecycle getLifecycle() { return registry; }
    }
}
