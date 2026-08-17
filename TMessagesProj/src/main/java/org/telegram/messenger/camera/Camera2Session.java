package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private volatile boolean isError;   
    private boolean isSuccess;
    private volatile boolean isClosed;   
    private volatile boolean deviceErrored;   
    private volatile int lastErrorCode = -1;

    private org.telegram.messenger.Utilities.Callback<Integer> errorCallback;
    public void whenError(org.telegram.messenger.Utilities.Callback<Integer> cb) {
        if (destroyed || isClosed) {
            return;
        }
        this.errorCallback = cb;
        if (cb != null && isError) nmFireError(lastErrorCode);
    }
    private void nmFireError(int code) {
        if (destroyed || isClosed) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {   
            if (destroyed || isClosed) {
                return;
            }
            org.telegram.messenger.Utilities.Callback<Integer> cb = errorCallback;
            if (cb != null) { errorCallback = null; cb.run(code); }
        });
    }

    private final CameraManager cameraManager;
    private final boolean isFront;
    public final String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private HandlerThread thread;
    private Handler handler;

    private volatile CameraDevice cameraDevice;
    private CameraDevice closingCameraDevice;
    private volatile boolean openPending;
    private SurfaceTexture surfaceTexture;
    private volatile CameraCaptureSession captureSession;
    private Surface surface;

    private final CameraDevice.StateCallback cameraStateCallback;
    private volatile int captureSessionGeneration;
    private CaptureRequest.Builder captureRequestBuilder;
    private Rect sensorSize;
    private float maxZoom = 1f;
    private float minZoom = 1f;
    private float currentZoom = 1f;
    
    private boolean zoomRatioSupported = false;

    private final Size previewSize;

    private ImageReader imageReader;

    private long lastTime;

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight) {
        return create(front, viewWidth, viewHeight, true);
    }

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight, boolean preferLogical) {
        
        return create(front, viewWidth, viewHeight, preferLogical, app.nimarkogram.messenger.NimarkoConfig.cameraResolution);
    }

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight, boolean preferLogical, int requestedHeight) {
        
        return create(front, viewWidth, viewHeight, preferLogical, requestedHeight, false);
    }

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight, boolean preferLogical, int requestedHeight, boolean noStillSurface) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
        boolean selectedLogical = false;
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            final int wantFacing = front ? CameraCharacteristics.LENS_FACING_FRONT
                                         : CameraCharacteristics.LENS_FACING_BACK;

            String logicalId = null;
            float logicalSpan = -1f;
            for (String id : preferLogical ? cameraIds : new String[0]) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                if (cc == null) continue;
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != wantFacing) continue;
                int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean logical = false;
                if (caps != null) {
                    for (int c : caps) {
                        if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                            logical = true;
                            break;
                        }
                    }
                }
                if (!logical) continue;
                float span = 1f;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        Range<Float> zr = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                        if (zr != null) span = zr.getUpper() - zr.getLower();
                    } catch (Throwable ignored) {}
                }
                if (span > logicalSpan) { logicalSpan = span; logicalId = id; }
            }

            String primaryPhysicalId = null;
            long primaryPhysicalArea = -1L;
            String anyPhysicalId = null;
            long anyPhysicalArea = -1L;
            if (logicalId == null) {
                for (String candidateId : cameraIds) {
                    CameraCharacteristics cc = cameraManager.getCameraCharacteristics(candidateId);
                    if (cc == null) continue;
                    if (cc.get(CameraCharacteristics.LENS_FACING) == null
                            || cc.get(CameraCharacteristics.LENS_FACING) != wantFacing) continue;
                    int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    boolean logical = false;
                    if (caps != null) for (int c : caps) if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) { logical = true; break; }
                    if (logical) continue;
                    Size pixels = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                    long area = pixels == null ? 0L : (long) pixels.getWidth() * pixels.getHeight();
                    float[] focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    float lensMin = Float.MAX_VALUE;
                    if (focals != null) for (float f : focals) if (f > 0f && f < lensMin) lensMin = f;
                    if (area > anyPhysicalArea) {
                        anyPhysicalArea = area;
                        anyPhysicalId = candidateId;
                    }
                    if (lensMin >= 3f && lensMin <= 10f && area > primaryPhysicalArea) {
                        primaryPhysicalArea = area;
                        primaryPhysicalId = candidateId;
                    }
                }
                if (primaryPhysicalId == null) primaryPhysicalId = anyPhysicalId;
            }
            for (int i = 0; i < cameraIds.length; ++i) {
                final String id = cameraIds[i];
                
                if (logicalId != null && !logicalId.equals(id)) continue;
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                if (characteristics == null) continue;
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == null
                        || characteristics.get(CameraCharacteristics.LENS_FACING) != wantFacing) {
                    continue;
                }
                
                if (!preferLogical) {
                    int[] mcaps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    boolean mLogical = false;
                    if (mcaps != null) {
                        for (int c : mcaps) {
                            if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) { mLogical = true; break; }
                        }
                    }
                    if (mLogical) continue;
                }
                if (logicalId == null && primaryPhysicalId != null && !primaryPhysicalId.equals(id)) continue;
                StreamConfigurationMap confMap = (StreamConfigurationMap) characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                float cameraAspectRatio = pixelSize == null ? 0 : (float) pixelSize.getWidth() / pixelSize.getHeight();
                if ((viewWidth / (float) viewHeight >= 1f) != (cameraAspectRatio >= 1f)) {
                    cameraAspectRatio = 1f / cameraAspectRatio;
                }
                if (bestAspectRatio <= 0 || Math.abs((float) viewWidth / viewHeight - bestAspectRatio) > Math.abs((float) viewWidth / viewHeight - cameraAspectRatio)) {
                    if (confMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Size[] outputs = confMap.getOutputSizes(SurfaceTexture.class);
                        Size size = chooseQualityAwareSize(outputs, viewWidth, viewHeight, requestedHeight);
                        if (size != null) {
                            bestAspectRatio = cameraAspectRatio;
                            cameraId = id;
                            bestSize = size;
                            selectedLogical = (logicalId != null && logicalId.equals(id));
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            return null;
        }
        return new Camera2Session(context, front, cameraId, bestSize, selectedLogical, noStillSurface);
    }

    private boolean nmIsLogical;
     
    public boolean isLogical() { return nmIsLogical; }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size, boolean logicalCamera, boolean noStillSurface) {
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                openPending = false;
                if (destroyed) {
                    closingCameraDevice = camera;
                    try { camera.close(); } catch (Throwable ignored) {}
                    return;
                }
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + cameraId + " opened");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                openPending = false;
                deviceErrored = true;
                lastErrorCode = -1;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
                closingCameraDevice = camera;
                try { camera.close(); } catch (Throwable ignored) {}
                Camera2Session.this.cameraDevice = null;
                if (!destroyed) {
                    AndroidUtilities.runOnUIThread(() -> {
                        isError = true;
                        nmFireError(lastErrorCode);
                    });
                }
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                openPending = false;
                closingCameraDevice = camera;
                Camera2Session.this.cameraDevice = null;
                deviceErrored = true;   
                lastErrorCode = error;
                FileLog.e("Camera2Session camera #" + cameraId + " received " + error + " error");
                if (!destroyed) {
                    AndroidUtilities.runOnUIThread(() -> {
                        isError = true;
                        nmFireError(error);
                    });
                }
                try { camera.close(); } catch (Throwable ignored) {}
            }

            @Override
            public void onClosed(@NonNull CameraDevice camera) {
                openPending = false;
                if (cameraDevice == camera) cameraDevice = null;
                if (closingCameraDevice == camera) closingCameraDevice = null;
                if (destroyed) completeDestroyOnHandler();
            }
        };

        this.isFront = isFront;
        this.cameraId = cameraId;
        this.nmIsLogical = logicalCamera;
        this.previewSize = size;
        this.lastTime = System.currentTimeMillis();
        
        this.imageReader = noStillSurface ? null : ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final Float value = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (value == null || value < 1f) ? 1f : value;
            minZoom = 1f;
            zoomRatioSupported = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Range<Float> zr = cameraCharacteristics.get(
                            CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    if (zr != null && zr.getUpper() > zr.getLower()) {
                        zoomRatioSupported = true;
                        minZoom = zr.getLower();
                        maxZoom = zr.getUpper();
                    }
                } catch (Throwable ignored) {}
            }
            openPending = true;
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (Exception e) {
            openPending = false;
            FileLog.e(e);
            deviceErrored = true;
            lastErrorCode = -1;
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
                nmFireError(lastErrorCode);
            });
        }
    }

    private Runnable doneCallback;
    public void whenDone(Runnable doneCallback) {
        if (destroyed || isClosed) {
            return;
        }
        if (isInitiated()) {
            doneCallback.run();
            this.doneCallback = null;
        } else {
            this.doneCallback = doneCallback;
        }
    }

    public void open(SurfaceTexture surfaceTexture) {
        if (destroyed || isClosed) {
            return;
        }
        handler.post(() -> {
            if (destroyed || isClosed) {
                return;
            }
            this.surfaceTexture = surfaceTexture;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            }
            checkOpen();
        });
    }

    private boolean opened = false;
    private void checkOpen() {
        if (opened || destroyed || isClosed) return;
        if (surfaceTexture == null || cameraDevice == null) return;
        
        if (isError || deviceErrored) {
            nmFireError(lastErrorCode); return;
        }
        opened = true;

        surface = new Surface(surfaceTexture);

        try {
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            if (imageReader != null) {   
                surfaces.add(imageReader.getSurface());
            }
            final CameraDevice expectedDevice = cameraDevice;
            final int generation = ++captureSessionGeneration;
            expectedDevice.createCaptureSession(
                    surfaces,
                    createCaptureStateCallback(expectedDevice, generation),
                    null);
        } catch (Exception e) {
            FileLog.e(e);
            deviceErrored = true;
            lastErrorCode = -1;
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
                nmFireError(lastErrorCode);
            });
        }
    }

    private CameraCaptureSession.StateCallback createCaptureStateCallback(
            final CameraDevice expectedDevice, final int generation) {
        return new CameraCaptureSession.StateCallback() {
            private boolean isCurrent(@NonNull CameraCaptureSession session) {
                return !destroyed
                        && !isClosed
                        && generation == captureSessionGeneration
                        && cameraDevice == expectedDevice
                        && (captureSession == null || captureSession == session);
            }

            private void closeStale(@NonNull CameraCaptureSession session) {
                try {
                    session.close();
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                if (!isCurrent(session)) {
                    closeStale(session);
                    return;
                }
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session configured");
                Camera2Session.this.lastTime = System.currentTimeMillis();
                try {
                    if (!updateCaptureRequest()) {
                        if (!isCurrent(session)) {
                            closeStale(session);
                            return;
                        }
                        deviceErrored = true;
                        lastErrorCode = -1;
                        AndroidUtilities.runOnUIThread(() -> {
                            if (isCurrent(session)) {
                                isError = true;
                                nmFireError(lastErrorCode);
                            }
                        });
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isCurrent(session)) {
                            return;
                        }
                        isSuccess = true;
                        if (doneCallback != null) {
                            doneCallback.run();
                            doneCallback = null;
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                if (!isCurrent(session)) {
                    closeStale(session);
                    return;
                }
                deviceErrored = true;
                lastErrorCode = -1;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session failed to configure");
                closeStale(session);
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation == captureSessionGeneration
                            && !destroyed && !isClosed && cameraDevice == expectedDevice) {
                        isError = true;
                        nmFireError(-1);
                    }
                });
            }
        };
    }

    public boolean isInitiated() {
        return !isError && isSuccess && !isClosed;
    }

    public int getDisplayOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayOrientation;
            if (isFront) {
                displayOrientation = (sensorOrientation + degrees) % 360;
                displayOrientation = (360 - displayOrientation) % 360; 
            } else { 
                displayOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return displayOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private int getJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int jpegOrientation;
            if (isFront) {
                jpegOrientation = (sensorOrientation + degrees) % 360;
                jpegOrientation = (360 - jpegOrientation) % 360; 
            } else { 
                jpegOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return jpegOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public int getWorldAngle() {
        int displayOrientation = getDisplayOrientation();
        int jpegOrientation = getJpegOrientation();
        int diffOrientation = jpegOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
        return diffOrientation;
    }

    public int getCurrentOrientation() {
        return getJpegOrientation();
    }

    private final Rect cropRegion = new Rect();
    public void setZoom(float value) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;

        float requestedZoom = Utilities.clamp(value, maxZoom, minZoom);
        if (Math.abs(requestedZoom - currentZoom) < 0.001f) return;
        currentZoom = requestedZoom;
        updateCaptureRequest();
    }

    private boolean flashing;
    
    private int flashIntensityPercent = 100;
    public void setFlash(boolean flash) {
        if (flashing != flash) {
            flashing = flash;
            updateCaptureRequest();
            applyTorchStrength();
        }
    }
    public void setFlash(boolean flash, int intensityPercent) {
        boolean changed = (flashing != flash) || (flashIntensityPercent != intensityPercent);
        flashing = flash;
        flashIntensityPercent = Utilities.clamp(intensityPercent, 100, 0);
        if (changed) {
            updateCaptureRequest();
            applyTorchStrength();
        }
    }
    public void setFlashIntensity(int intensityPercent) {
        int v = Utilities.clamp(intensityPercent, 100, 0);
        if (flashIntensityPercent != v) {
            flashIntensityPercent = v;
            applyTorchStrength();
        }
    }
    public boolean getFlash() {
        return flashing;
    }

    private void applyTorchStrength() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (cameraManager == null || cameraId == null || isFront) return;
        if (!flashing) return;
        if (flashIntensityPercent >= 100 || flashIntensityPercent <= 0) return;
        try {
            CameraCharacteristics cc = cameraCharacteristics;
            if (cc == null) cc = cameraManager.getCameraCharacteristics(cameraId);
            Integer maxLevel = cc.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            if (maxLevel == null || maxLevel <= 1) return; 
            int strength = Math.round((flashIntensityPercent / 100f) * maxLevel);
            if (strength < 1) strength = 1;
            if (strength > maxLevel) strength = maxLevel;
            cameraManager.turnOnTorchWithStrengthLevel(cameraId, strength);
        } catch (Throwable t) {
            FileLog.e("Camera2Session turnOnTorchWithStrengthLevel failed", t);
        }
    }

    public float getZoom() {
        return currentZoom;
    }

    private int currentEvIndex = 0;
    public boolean isExposureCompensationSupported() {
        if (cameraCharacteristics == null) return false;
        try {
            Range<Integer> r = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            return r != null && (r.getUpper() - r.getLower()) > 0;
        } catch (Throwable t) {
            return false;
        }
    }
    public void setExposureCompensation(float value0to1) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || captureSession == null) return;
        try {
            Range<Integer> r = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (r == null) return;
            int lo = r.getLower();
            int hi = r.getUpper();
            int span = hi - lo;
            if (span <= 0) return;
            float v = value0to1;
            if (v < 0f) v = 0f; else if (v > 1f) v = 1f;
            int idx = lo + Math.round(span * v);
            currentEvIndex = idx;
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
        } catch (Throwable t) {
            FileLog.e("Camera2Session setExposureCompensation failed", t);
        }
    }
    public int getExposureCompensationIndex() {
        return currentEvIndex;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getMinZoom() {
        
        return minZoom;
    }

    public int getPreviewWidth() {
        return previewSize.getWidth();
    }

    public int getPreviewHeight() {
        return previewSize.getHeight();
    }

    public void destroy(boolean async) {
        destroy(async, null);
    }

    private volatile boolean destroyed;
    private boolean destroyComplete;
    private boolean destroyCompletionStarted;
    private final ArrayList<Runnable> destroyCallbacks = new ArrayList<>();

    public void destroy(boolean async, Runnable afterCallback) {
        isClosed = true;
        synchronized (destroyCallbacks) {
            if (destroyComplete) {
                if (afterCallback != null) AndroidUtilities.runOnUIThread(afterCallback);
                return;
            }
            if (afterCallback != null) destroyCallbacks.add(afterCallback);
            if (destroyed) return;
            destroyed = true;
            captureSessionGeneration++;
            errorCallback = null;
            doneCallback = null;
        }
        
        if (Looper.myLooper() == thread.getLooper()) {
            closeResourcesForDestroy();
        } else {
            handler.post(this::closeResourcesForDestroy);
        }
    }

    private void closeResourcesForDestroy() {
        CameraCaptureSession session = captureSession;
        captureSession = null;
        if (session != null) {
            try {
                session.close();
            } catch (Throwable error) {
                FileLog.e(error);
            }
        }
        ImageReader reader = imageReader;
        imageReader = null;
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable error) {
                FileLog.e(error);
            }
        }
        Surface previewSurface = surface;
        surface = null;
        surfaceTexture = null;
        if (previewSurface != null) {
            try {
                previewSurface.release();
            } catch (Throwable error) {
                FileLog.e(error);
            }
        }
        CameraDevice device = cameraDevice != null ? cameraDevice : closingCameraDevice;
        cameraDevice = null;
        if (device != null) {
            closingCameraDevice = device;
            try { device.close(); } catch (Throwable ignored) {}
        } else if (openPending) {
            
        } else {
            completeDestroyOnHandler();
        }
    }

    private void completeDestroyOnHandler() {
        if (destroyCompletionStarted) return;
        destroyCompletionStarted = true;
        thread.quitSafely();
        joinCameraThread(this::dispatchDestroyCallbacks);
    }

    private void dispatchDestroyCallbacks() {
        ArrayList<Runnable> callbacks;
        synchronized (destroyCallbacks) {
            destroyComplete = true;
            callbacks = new ArrayList<>(destroyCallbacks);
            destroyCallbacks.clear();
        }
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private void joinCameraThread(Runnable afterCallback) {
        Runnable join = () -> {
            try {
                if (Thread.currentThread() != thread) thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (afterCallback != null) AndroidUtilities.runOnUIThread(afterCallback);
        };
        if (Looper.myLooper() == Looper.getMainLooper() || Thread.currentThread() == thread) {
            Utilities.globalQueue.postRunnable(join);
        } else {
            join.run();
        }
    }

    private boolean recordingVideo;
    public void setRecordingVideo(boolean recording) {
        if (recordingVideo != recording) {
            recordingVideo = recording;
            updateCaptureRequest();
        }
    }

    private boolean scanningBarcode;
    public void setScanningBarcode(boolean scanning) {
        if (scanningBarcode != scanning) {
            scanningBarcode = scanning;
            updateCaptureRequest();
        }
    }

    private boolean nightMode;
    public void setNightMode(boolean enable) {
        if (nightMode != enable) {
            nightMode = enable;
            updateCaptureRequest();
        }
    }

    private Range<Integer> nmValidateFpsRange(Range<Integer> requested) {
        if (requested == null) return null;
        try {
            CameraCharacteristics cc = cameraCharacteristics;
            if (cc == null) return null; 
            Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return null; 
            
            for (Range<Integer> r : ranges) {
                if (r != null && r.getLower().equals(requested.getLower()) && r.getUpper().equals(requested.getUpper())) {
                    return requested;
                }
            }
            
            Range<Integer> best = null;
            for (Range<Integer> r : ranges) {
                if (r == null) continue;
                if (r.getUpper().equals(requested.getUpper())
                        && r.getLower() <= requested.getLower()) {
                    if (best == null || r.getLower() < best.getLower()) best = r;
                }
            }
            return best; 
        } catch (Throwable t) {
            FileLog.e("Camera2Session nmValidateFpsRange failed", t);
            return null;
        }
    }

    private boolean updateCaptureRequest() {
        if (cameraDevice == null || surface == null || captureSession == null) return false;
        try {
            int template;
            if (recordingVideo) {
                template = CameraDevice.TEMPLATE_RECORD;
            } else if (scanningBarcode) {
                template = CameraDevice.TEMPLATE_STILL_CAPTURE;
            } else {
                template = CameraDevice.TEMPLATE_PREVIEW;
            }
            captureRequestBuilder = cameraDevice.createCaptureRequest(template);

            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            } else if (nightMode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
            }

            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flashing ? (recordingVideo ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_SINGLE) : CaptureRequest.FLASH_MODE_OFF);

            if (recordingVideo) {
                
                Range<Integer> aeFps;
                switch (app.nimarkogram.messenger.NimarkoConfig.cameraXFpsRange) {
                    case app.nimarkogram.messenger.NimarkoConfig.CameraXFpsRange25to30: aeFps = new Range<>(25, 30); break;
                    case app.nimarkogram.messenger.NimarkoConfig.CameraXFpsRange30to30: aeFps = new Range<>(30, 30); break;
                    case app.nimarkogram.messenger.NimarkoConfig.CameraXFpsRange30to60: aeFps = new Range<>(30, 60); break;
                    
                    case app.nimarkogram.messenger.NimarkoConfig.CameraXFpsRange60to60: aeFps = new Range<>(30, 60); break;
                    case app.nimarkogram.messenger.NimarkoConfig.CameraXFpsRangeDefault:
                    default:                                                            aeFps = new Range<>(30, 60); break;
                }
                
                Range<Integer> supportedFps = nmValidateFpsRange(aeFps);
                if (supportedFps != null) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, supportedFps);
                }
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
            }
            
            try {
                int mode = app.nimarkogram.messenger.NimarkoConfig.cameraStabilisation
                        ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
                captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode);
            } catch (Throwable ignored) {}

            try {
                if (app.nimarkogram.messenger.NimarkoConfig.cameraOpticalStabilization) {
                    captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                    captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                } else {
                    captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                }
            } catch (Throwable ignored) {}

            try {
                int af = app.nimarkogram.messenger.NimarkoConfig.cameraContinuousFocus
                        ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        : CaptureRequest.CONTROL_AF_MODE_AUTO;
                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, af);
            } catch (Throwable ignored) {}

            try {
                int nr;
                if (app.nimarkogram.messenger.NimarkoConfig.cameraNoiseReduction) {
                    nr = recordingVideo
                            ? CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                            : CaptureRequest.NOISE_REDUCTION_MODE_FAST;
                } else {
                    nr = CaptureRequest.NOISE_REDUCTION_MODE_OFF;
                }
                captureRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, nr);
            } catch (Throwable ignored) {}

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    captureRequestBuilder.set(CaptureRequest.DISTORTION_CORRECTION_MODE,
                            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY);
                }
            } catch (Throwable ignored) {}

            try {
                int fd = app.nimarkogram.messenger.NimarkoConfig.cameraFaceDetection
                        ? CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                        : CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
                captureRequestBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, fd);
            } catch (Throwable ignored) {}

            if (currentEvIndex != 0) {
                try {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex);
                } catch (Throwable ignored) {}
            }

            if (zoomRatioSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                
                try {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom);
                } catch (Throwable ignored) {}
            } else if (sensorSize != null && Math.abs(currentZoom - 1f) >= 0.01f) {
                final int centerX = sensorSize.width() / 2;
                final int centerY = sensorSize.height() / 2;
                final int deltaX = (int) ((0.5f * sensorSize.width()) / currentZoom);
                final int deltaY = (int) ((0.5f * sensorSize.height()) / currentZoom);
                cropRegion.set(
                        centerX - deltaX,
                        centerY - deltaY,
                        centerX + deltaX,
                        centerY + deltaY
                );
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
            }

            captureRequestBuilder.addTarget(surface);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions setRepeatingRequest error in updateCaptureRequest", e);
            return false;
        }
    }

    public boolean takePicture(final File file, Utilities.Callback<Integer> whenDone) {
        if (imageReader == null) return false;   
        if (cameraDevice == null || captureSession == null) return false;
        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            final int orientation = getJpegOrientation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    if (image == null) return;   
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                        if (null != output) {
                            try {
                                output.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        if (whenDone != null) {
                            whenDone.run(orientation);
                        }
                    });
                }
            }, null);
            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            }
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureSession.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {}, null);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions takePicture error", e);
            return false;
        }
    }

    public static Size chooseQualityAwareSize(Size[] choices, int viewWidth, int viewHeight, int requestedHeight) {
        if (choices == null || choices.length == 0) return null;
        if (requestedHeight <= 0) {
            return chooseOptimalSize(choices, viewWidth, viewHeight, false);
        }
        
        final int w = Math.max(viewWidth, viewHeight);
        final int h = Math.min(viewWidth, viewHeight);
        final float targetRatio = h == 0 ? 0f : (float) w / h;

        final long heightCap = (long) requestedHeight * 2;
        Size bestAspect = null;
        long bestAspectDelta = Long.MAX_VALUE;
        Size bestAnyCapped = null;
        long bestAnyCappedDelta = Long.MAX_VALUE;
        Size bestAnyUncapped = null;
        long bestAnyUncappedDelta = Long.MAX_VALUE;

        for (Size s : choices) {
            if (s == null) continue;
            long dHeight = Math.abs((long) s.getHeight() - requestedHeight);
            if (dHeight < bestAnyUncappedDelta) {
                bestAnyUncappedDelta = dHeight;
                bestAnyUncapped = s;
            }
            if (s.getHeight() <= heightCap && dHeight < bestAnyCappedDelta) {
                bestAnyCappedDelta = dHeight;
                bestAnyCapped = s;
            }
            if (targetRatio > 0 && s.getHeight() > 0) {
                float ratio = (float) s.getWidth() / s.getHeight();
                if (Math.abs(ratio - targetRatio) < 0.05f) {
                    
                    if (dHeight < bestAspectDelta && s.getHeight() <= heightCap) {
                        bestAspectDelta = dHeight;
                        bestAspect = s;
                    }
                }
            }
        }
        if (bestAspect != null) return bestAspect;
        if (bestAnyCapped != null) return bestAnyCapped;
        return bestAnyUncapped;
    }

    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        List<Size> bigEnoughWithAspectRatio = new ArrayList<>(choices.length);
        List<Size> bigEnough = new ArrayList<>(choices.length);
        int w = width;
        int h = height;
        for (int a = 0; a < choices.length; a++) {
            Size option = choices[a];
            if (notBigger && (option.getHeight() > height || option.getWidth() > width)) {
                continue;
            }
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnoughWithAspectRatio.add(option);
            } else if (option.getHeight() * option.getWidth() <= width * height * 4 && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnoughWithAspectRatio.size() > 0) {
            return Collections.min(bigEnoughWithAspectRatio, new CompareSizesByArea());
        } else if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
    }
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

}
