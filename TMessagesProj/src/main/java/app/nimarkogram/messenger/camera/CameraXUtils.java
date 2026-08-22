package app.nimarkogram.messenger.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.SessionConfig;
import androidx.camera.core.UseCase;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.NimarkoCameraLog;

public final class CameraXUtils {

    public static final int CAMERA_TELEGRAM = 0;
    public static final int CAMERA_X = 1;
    public static final int CAMERA_2 = 2;
    public static final int CAMERA_SYSTEM = 3;

    private CameraXUtils() {}

    private static volatile Map<Quality, Size> qualityToSize;
    private static final Map<String, Map<Quality, Size>> QUALITY_TO_SIZE_BY_CAMERA =
            new ConcurrentHashMap<>();
    private static volatile int suggestedCameraResolution = -1;
    private static final AtomicBoolean QUALITY_LOAD_STARTED = new AtomicBoolean();
    private static ExecutorService newQualityExecutor() {
        return Executors.newSingleThreadExecutor(command ->
            new Thread(() -> {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                command.run();
            }, "cameraCapabilities"));
    }
    private static final Object PROVIDER_LOCK = new Object();
    @Nullable private static volatile ListenableFuture<ProcessCameraProvider> sharedProviderFuture;
    private static final Map<String, CameraCapabilities> CAMERA_CAPABILITIES =
            new ConcurrentHashMap<>();

    private static final class CameraCapabilities {
        final CameraInfo cameraInfo;
        final Range<Integer>[] fpsRanges;
        final int[] opticalStabilizationModes;
        final int[] videoStabilizationModes;
        final int[] autofocusModes;
        final int[] noiseReductionModes;
        final int[] faceDetectionModes;
        final int[] distortionCorrectionModes;

        @SuppressWarnings("unchecked")
        CameraCapabilities(CameraInfo cameraInfo) {
            this.cameraInfo = cameraInfo;
            Camera2CameraInfo info = Camera2CameraInfo.from(cameraInfo);
            fpsRanges = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            opticalStabilizationModes = info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            videoStabilizationModes = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            autofocusModes = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            noiseReductionModes = info.getCameraCharacteristic(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            faceDetectionModes = info.getCameraCharacteristic(
                    CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
            distortionCorrectionModes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getCameraCharacteristic(
                            CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                    : null;
        }
    }

    public static ListenableFuture<ProcessCameraProvider> getProviderFuture(Context context) {
        ListenableFuture<ProcessCameraProvider> future = sharedProviderFuture;
        if (future != null) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils provider future reused done=" + future.isDone());
            return future;
        }
        synchronized (PROVIDER_LOCK) {
            future = sharedProviderFuture;
            if (future == null) {
                Context appContext = context.getApplicationContext();
                future = ProcessCameraProvider.getInstance(appContext != null ? appContext : context);
                sharedProviderFuture = future;
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils provider future created");
                final ListenableFuture<ProcessCameraProvider> createdFuture = future;
                createdFuture.addListener(() -> {
                    try {
                        ProcessCameraProvider created = createdFuture.get();
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils provider initialized cameras="
                                + created.getAvailableCameraInfos().size()
                                + " concurrentPairs="
                                + created.getAvailableConcurrentCameraInfos().size());
                    } catch (Throwable error) {
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils provider initialization FAILED", error);
                        synchronized (PROVIDER_LOCK) {
                            if (sharedProviderFuture == createdFuture) {
                                sharedProviderFuture = null;
                            }
                        }
                    }
                }, ContextCompat.getMainExecutor(appContext != null ? appContext : context));
            }
        }
        return future;
    }

    public static Map<Quality, Size> getAvailableVideoSizes() {
        Map<Quality, Size> sizes = qualityToSize;
        return sizes != null ? sizes : Collections.emptyMap();
    }

    public static void loadCameraXSizes() {
        if (qualityToSize != null) return;
        if (!isCameraXSupported()) return;
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return;
        if (!QUALITY_LOAD_STARTED.compareAndSet(false, true)) return;
        final ExecutorService executor;
        try {
            executor = newQualityExecutor();
        } catch (Throwable ignored) {
            QUALITY_LOAD_STARTED.set(false);
            return;
        }
        try {
            ListenableFuture<ProcessCameraProvider> providerFuture = getProviderFuture(context);
            providerFuture.addListener(() -> {
                try {
                    ProcessCameraProvider provider = providerFuture.get();
                    for (CameraInfo cameraInfo : provider.getAvailableCameraInfos()) {
                        cacheAvailableVideoSizes(cameraInfo);
                    }
                    CameraSelector selector = buildIntendedBackCameraSelector(provider);
                    CameraInfo selectedCamera = resolveSelectedCameraInfo(provider, selector);
                    Map<Quality, Size> loadedSizes = cacheAvailableVideoSizes(selectedCamera);
                    if (!loadedSizes.isEmpty()) {
                        qualityToSize = loadedSizes;
                        loadSuggestedResolution();
                    }
                } catch (Throwable ignored) {
                } finally {
                    QUALITY_LOAD_STARTED.set(false);
                    executor.shutdown();
                }
            }, executor);
        } catch (Throwable ignored) {
            QUALITY_LOAD_STARTED.set(false);
            executor.shutdown();
        }
    }

    @SuppressLint("RestrictedApi")
    private static Map<Quality, Size> fetchAvailableVideoSizes(@Nullable CameraInfo cameraInfo) {
        Map<Quality, Size> result = new HashMap<>();
        if (cameraInfo == null) return result;
        for (Quality quality : QualitySelector.getSupportedQualities(cameraInfo)) {
            Size resolution = QualitySelector.getResolution(cameraInfo, quality);
            result.put(quality, resolution != null ? resolution : new Size(0, 0));
        }
        return result;
    }

    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    private static Map<Quality, Size> cacheAvailableVideoSizes(@Nullable CameraInfo cameraInfo) {
        if (cameraInfo == null) return Collections.emptyMap();
        final String cameraId;
        try {
            cameraId = Camera2CameraInfo.from(cameraInfo).getCameraId();
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
        Map<Quality, Size> cached = QUALITY_TO_SIZE_BY_CAMERA.get(cameraId);
        if (cached != null) return cached;
        Map<Quality, Size> loaded = fetchAvailableVideoSizes(cameraInfo);
        if (loaded.isEmpty()) return Collections.emptyMap();
        loaded = Collections.unmodifiableMap(loaded);
        Map<Quality, Size> raced = QUALITY_TO_SIZE_BY_CAMERA.putIfAbsent(cameraId, loaded);
        return raced != null ? raced : loaded;
    }

    public static Quality getVideoQuality(CameraSelector selector, ProcessCameraProvider provider) {
        final int configuredHeight = getEffectiveConfiguredHeight();
        CameraInfo selectedCamera = resolveSelectedCameraInfo(provider, selector);
        Map<Quality, Size> sizes = null;
        if (selectedCamera != null) {
            try {
                sizes = QUALITY_TO_SIZE_BY_CAMERA.get(
                        Camera2CameraInfo.from(selectedCamera).getCameraId());
            } catch (Throwable ignored) {
            }
        }
        if (sizes == null && Looper.myLooper() != Looper.getMainLooper()) {
            sizes = cacheAvailableVideoSizes(selectedCamera);
        } else if (sizes == null) {
            loadCameraXSizes();
            sizes = Collections.emptyMap();
        }
        for (Map.Entry<Quality, Size> entry : sizes.entrySet()) {
            if (entry.getValue().getHeight() == configuredHeight) return entry.getKey();
        }
        return qualityForConfiguredHeight(configuredHeight);
    }

    public static void loadSuggestedResolution() {
        int suggestedRes = getSuggestedResolution(false);
        getAvailableVideoSizes().values().stream()
                .mapToInt(Size::getHeight)
                .filter(height -> height <= suggestedRes)
                .max()
                .ifPresent(height -> suggestedCameraResolution = height);
    }

    public static Quality getVideoQuality() {
        final int configuredHeight = getEffectiveConfiguredHeight();
        return getAvailableVideoSizes().entrySet().stream()
                .filter(entry -> entry.getValue().getHeight() == configuredHeight)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> qualityForConfiguredHeight(configuredHeight));
    }

    private static int getEffectiveConfiguredHeight() {
        if (NimarkoConfig.cameraResolution > 0) {
            return NimarkoConfig.cameraResolution;
        }
        return suggestedCameraResolution > 0
                ? suggestedCameraResolution : getSuggestedResolution(false);
    }

    private static Quality qualityForConfiguredHeight(int configuredHeight) {
        if (configuredHeight >= 2160) return Quality.UHD;
        if (configuredHeight >= 1440) return Quality.FHD;
        if (configuredHeight >= 1080) return Quality.FHD;
        if (configuredHeight >= 720) return Quality.HD;
        if (configuredHeight > 0) return Quality.SD;
        return Quality.HIGHEST;
    }

    private static int getSuggestedResolution(boolean isPreview) {
        int perfClass = SharedConfig.getDevicePerformanceClass();
        if (perfClass == SharedConfig.PERFORMANCE_CLASS_LOW) return 720;
        return 1080;
    }

    public static boolean isCameraXSupported() {
        return SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE;
    }

    public static boolean isCurrentCameraCameraX() {
        return isCameraXSupported() && NimarkoConfig.cameraType == CAMERA_X;
    }

    public static boolean isCurrentCameraNotCameraX() {
        return !isCurrentCameraCameraX();
    }

    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    public static String findBackUltraWideCameraId(ProcessCameraProvider provider) {
        if (provider == null) return null;
        try {
            String defaultBackId = null;
            CameraInfo defaultBack = null;
            try {
                defaultBack = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA);
                if (defaultBack != null) {
                    defaultBackId = Camera2CameraInfo.from(defaultBack).getCameraId();
                }
            } catch (Throwable ignored) {
            }

            String logicalPhysicalId = findLogicalUltraWidePhysicalId(defaultBack);
            if (logicalPhysicalId != null) {
                return logicalPhysicalId;
            }

            List<CameraInfo> all = provider.getAvailableCameraInfos();
            int backCount = 0;
            float minFocal = Float.MAX_VALUE;
            float bestIntrinsicRatio = 1f;
            String bestId = null;
            for (CameraInfo info : all) {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
                Integer facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                backCount++;

                String cameraId = c2.getCameraId();
                if (cameraId == null || cameraId.equals(defaultBackId)) continue;

                int[] capabilities = c2.getCameraCharacteristic(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean logicalMultiCamera = false;
                if (capabilities != null) {
                    for (int capability : capabilities) {
                        if (capability == CameraCharacteristics
                                .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                            logicalMultiCamera = true;
                            break;
                        }
                    }
                }
                if (logicalMultiCamera) continue;

                float intrinsicRatio = safeIntrinsicZoomRatio(info);
                if (intrinsicRatio > 0f && intrinsicRatio < 0.95f
                        && intrinsicRatio < bestIntrinsicRatio) {
                    bestIntrinsicRatio = intrinsicRatio;
                    bestId = cameraId;
                    continue;
                }

                float[] focals = c2.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focals == null) continue;
                for (float focal : focals) {
                    if (focal > 0f && focal < 3.0f && focal < minFocal) {
                        minFocal = focal;
                        bestId = cameraId;
                    }
                }
            }
            if (backCount < 2) return null;
            return bestId;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    private static String findLogicalUltraWidePhysicalId(@Nullable CameraInfo logicalCamera) {
        if (logicalCamera == null) return null;
        try {
            if (!logicalCamera.isLogicalMultiCameraSupported()) return null;
            Set<CameraInfo> physicalInfos = logicalCamera.getPhysicalCameraInfos();
            if (physicalInfos == null || physicalInfos.size() < 2) return null;

            CameraInfo best = null;
            float bestIntrinsicRatio = 1f;
            for (CameraInfo physicalInfo : physicalInfos) {
                float intrinsicRatio = safeIntrinsicZoomRatio(physicalInfo);
                if (intrinsicRatio > 0f && intrinsicRatio < 0.95f
                        && intrinsicRatio < bestIntrinsicRatio) {
                    best = physicalInfo;
                    bestIntrinsicRatio = intrinsicRatio;
                }
            }

            if (best == null) {
                CameraInfo shortest = null;
                float shortestFocal = Float.MAX_VALUE;
                float secondShortestFocal = Float.MAX_VALUE;
                for (CameraInfo physicalInfo : physicalInfos) {
                    float focal = getShortestFocalLength(physicalInfo);
                    if (!(focal > 0f)) continue;
                    if (focal < shortestFocal) {
                        secondShortestFocal = shortestFocal;
                        shortestFocal = focal;
                        shortest = physicalInfo;
                    } else if (focal < secondShortestFocal) {
                        secondShortestFocal = focal;
                    }
                }
                if (shortest != null && secondShortestFocal < Float.MAX_VALUE
                        && shortestFocal <= secondShortestFocal * 0.82f) {
                    best = shortest;
                }
            }

            return best == null ? null
                    : Camera2CameraInfo.from(best).getCameraId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float safeIntrinsicZoomRatio(@Nullable CameraInfo info) {
        if (info == null) return 1f;
        try {
            float ratio = info.getIntrinsicZoomRatio();
            return Float.isNaN(ratio) || Float.isInfinite(ratio) || ratio <= 0f
                    ? 1f : ratio;
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private static float getShortestFocalLength(@Nullable CameraInfo info) {
        if (info == null) return Float.MAX_VALUE;
        try {
            float[] focals = Camera2CameraInfo.from(info).getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focals == null) return Float.MAX_VALUE;
            float shortest = Float.MAX_VALUE;
            for (float focal : focals) {
                if (focal > 0f && focal < shortest) shortest = focal;
            }
            return shortest;
        } catch (Throwable ignored) {
            return Float.MAX_VALUE;
        }
    }

    public static boolean hasLogicalUltraWide(ProcessCameraProvider provider) {
        if (provider == null) return false;
        try {
            CameraInfo info = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA);
            return supportsSubOneZoom(info);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    static boolean supportsSubOneZoom(ProcessCameraProvider provider,
                                      @Nullable CameraSelector selector) {
        if (provider == null || selector == null) return false;
        try {
            return supportsSubOneZoom(provider.getCameraInfo(selector));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private static boolean supportsSubOneZoom(@Nullable CameraInfo info) {
        if (info == null) return false;
        try {
            ZoomState state = info.getZoomState().getValue();
            if (state != null && state.getMinZoomRatio() < 0.999f) return true;
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Range<Float> range = Camera2CameraInfo.from(info).getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                return range != null && range.getLower() < 0.999f;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @SuppressLint("UnsafeOptInUsageError")
    public static CameraSelector buildUltraWideSelector(ProcessCameraProvider provider) {
        if (provider == null) return null;
        try {
            CameraInfo logicalBack = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA);
            String physicalId = findLogicalUltraWidePhysicalId(logicalBack);
            if (logicalBack != null && physicalId != null) {
                String logicalId = Camera2CameraInfo.from(logicalBack).getCameraId();
                CameraSelector logicalSelector = buildCameraIdSelector(logicalId, false);
                return CameraSelector.Builder.fromSelector(logicalSelector)
                        .setPhysicalCameraId(physicalId)
                        .build();
            }
        } catch (Throwable ignored) {
        }

        final String standaloneId = findBackUltraWideCameraId(provider);
        if (standaloneId == null) return null;
        return buildCameraIdSelector(standaloneId, false);
    }

    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    public static CameraSelector buildIntendedBackCameraSelector(ProcessCameraProvider provider) {
        return buildIntendedCameraSelector(provider, false);
    }

    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    public static CameraSelector buildIntendedCameraSelector(ProcessCameraProvider provider, boolean frontFacing) {
        return buildIntendedCameraSelector(provider, frontFacing, true);
    }

    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    public static CameraSelector buildIntendedCameraSelector(ProcessCameraProvider provider,
                                                             boolean frontFacing,
                                                             boolean useConfiguredUltraWide) {
        if (provider == null) return frontFacing
                ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        if (!frontFacing && useConfiguredUltraWide && NimarkoConfig.startFromUltraWideCam
                && !hasLogicalUltraWide(provider)) {
            CameraSelector wide = buildUltraWideSelector(provider);
            if (wide != null) return wide;
        }
        CameraSelector defaultSelector = frontFacing
                ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            CameraInfo selected = provider.getCameraInfo(defaultSelector);
            if (selected != null) {
                return buildCameraIdSelector(
                        Camera2CameraInfo.from(selected).getCameraId(), frontFacing);
            }
        } catch (Throwable ignored) {
        }
        return defaultSelector;
    }

    @SuppressLint("UnsafeOptInUsageError")
    static CameraSelector buildCameraIdSelector(String cameraId, boolean frontFacing) {
        return new CameraSelector.Builder()
                .requireLensFacing(frontFacing
                        ? CameraSelector.LENS_FACING_FRONT
                        : CameraSelector.LENS_FACING_BACK)
                .addCameraFilter(cameras -> {
            java.util.ArrayList<CameraInfo> result = new java.util.ArrayList<>(1);
            for (CameraInfo info : cameras) {
                try {
                    if (cameraId.equals(Camera2CameraInfo.from(info).getCameraId())) result.add(info);
                } catch (Throwable ignored) {}
            }
            return result;
        }).build();
    }

    @Nullable
    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    static CameraSelector[] buildConcurrentCameraSelectors(ProcessCameraProvider provider,
                                                            boolean firstFront,
                                                            boolean secondFront) {
        return buildConcurrentCameraSelectors(provider, firstFront, secondFront, false);
    }

    @Nullable
    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    static CameraSelector[] buildConcurrentCameraSelectors(ProcessCameraProvider provider,
                                                            boolean firstFront,
                                                            boolean secondFront,
                                                            boolean preferUltraWide) {
        if (provider == null || firstFront == secondFront) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils concurrent selector rejected provider="
                    + (provider != null) + " firstFront=" + firstFront
                    + " secondFront=" + secondFront);
            return null;
        }
        try {
            String preferredFrontId = null;
            String preferredBackId = null;
            try {
                preferredFrontId = Camera2CameraInfo.from(
                        provider.getCameraInfo(CameraSelector.DEFAULT_FRONT_CAMERA))
                        .getCameraId();
            } catch (Throwable ignored) {}
            try {
                preferredBackId = Camera2CameraInfo.from(
                        provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA))
                        .getCameraId();
            } catch (Throwable ignored) {}
            String preferredFirstId = firstFront
                    ? preferredFrontId : preferredBackId;
            String preferredSecondId = secondFront
                    ? preferredFrontId : preferredBackId;
            String preferredUltraWideId = preferUltraWide
                    ? findBackUltraWideCameraId(provider) : null;

            if (isOppoCph2791ConcurrentQuirk()) {
                String independentWideId = findBackUltraWideCameraId(provider);
                CameraSelector independentWide = findAvailableCameraSelector(
                        provider, independentWideId, false);
                CameraSelector exactFront = findAvailableCameraSelector(
                        provider, preferredFrontId, true);
                if (independentWide != null && exactFront != null
                        && independentWideId != null
                        && !independentWideId.equals(preferredBackId)) {
                    if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils concurrent OPPO physical override back="
                            + independentWideId + " front=" + preferredFrontId
                            + " advertisedBack=" + preferredBackId);
                    return firstFront
                            ? new CameraSelector[] { exactFront, independentWide }
                            : new CameraSelector[] { independentWide, exactFront };
                }
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils concurrent OPPO physical override unavailable"
                        + " wide=" + independentWideId + " front=" + preferredFrontId
                        + " advertisedBack=" + preferredBackId);
            }

            CameraSelector[] best = null;
            int bestScore = Integer.MIN_VALUE;
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils concurrent advertisedPairs="
                    + provider.getAvailableConcurrentCameraInfos().size()
                    + " preferredFront=" + preferredFrontId
                    + " preferredBack=" + preferredBackId);
            for (List<CameraInfo> pair : provider.getAvailableConcurrentCameraInfos()) {
                ArrayList<CameraInfo> firstCandidates = new ArrayList<>();
                ArrayList<CameraInfo> secondCandidates = new ArrayList<>();
                for (CameraInfo info : pair) {
                    Integer facing = Camera2CameraInfo.from(info).getCameraCharacteristic(
                            CameraCharacteristics.LENS_FACING);
                    if (facing == null) continue;
                    if (facing != CameraCharacteristics.LENS_FACING_FRONT
                            && facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                    boolean front = facing == CameraCharacteristics.LENS_FACING_FRONT;
                    if (front == firstFront) {
                        firstCandidates.add(info);
                    }
                    if (front == secondFront) {
                        secondCandidates.add(info);
                    }
                }
                for (CameraInfo first : firstCandidates) {
                    for (CameraInfo second : secondCandidates) {
                        if (first == second) continue;
                        String firstId =
                                Camera2CameraInfo.from(first).getCameraId();
                        String secondId =
                                Camera2CameraInfo.from(second).getCameraId();
                        CameraSelector firstSelector = first.getCameraSelector();
                        CameraSelector secondSelector = second.getCameraSelector();
                        int score = 0;
                        if (preferredFirstId != null
                                && preferredFirstId.equals(firstId)) score += 4;
                        if (preferredSecondId != null
                                && preferredSecondId.equals(secondId)) score += 4;
                        if (preferUltraWide) {
                            CameraInfo backInfo = firstFront ? second : first;
                            String backId = firstFront ? secondId : firstId;
                            if (preferredUltraWideId != null
                                    && preferredUltraWideId.equals(backId)) {
                                score += 12;
                            }
                            if (supportsSubOneZoom(backInfo)) {
                                score += 8;
                            }
                            if (safeIntrinsicZoomRatio(backInfo) < 0.95f) {
                                score += 10;
                            }
                        }
                        if (pair.size() == 2) score += 1;
                        if (best == null || score > bestScore) {
                            best = new CameraSelector[] {
                                    firstSelector, secondSelector
                            };
                            bestScore = score;
                            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils concurrent candidate first="
                                    + firstId + " second=" + secondId + " score=" + score
                                    + " pairSize=" + pair.size()
                                    + " advertisedOrder=" + describeCameraInfoOrder(pair));
                        }
                    }
                }
            }
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils concurrent selection result="
                    + (best == null ? "none" : "score=" + bestScore)
                    + " preferWide=" + preferUltraWide
                    + " wideId=" + preferredUltraWideId);
            return best;
        } catch (Throwable error) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils concurrent selection FAILED", error);
        }
        return null;
    }

    static boolean isOppoCph2791ConcurrentQuirk() {
        return "OPPO".equalsIgnoreCase(Build.MANUFACTURER)
                && "CPH2791".equalsIgnoreCase(Build.MODEL);
    }

    @Nullable
    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    private static CameraSelector findAvailableCameraSelector(
            @NonNull ProcessCameraProvider provider,
            @Nullable String cameraId,
            boolean frontFacing) {
        if (cameraId == null) return null;
        try {
            for (CameraInfo info : provider.getAvailableCameraInfos()) {
                if (cameraId.equals(Camera2CameraInfo.from(info).getCameraId())) {
                    Integer facing = Camera2CameraInfo.from(info).getCameraCharacteristic(
                            CameraCharacteristics.LENS_FACING);
                    if (facing == null || (facing == CameraCharacteristics.LENS_FACING_FRONT)
                            != frontFacing) {
                        continue;
                    }
                    CameraSelector selector = buildCameraIdSelector(
                            cameraId, frontFacing);
                    CameraInfo resolved = provider.getCameraInfo(selector);
                    if (resolved != null && cameraId.equals(
                            Camera2CameraInfo.from(resolved).getCameraId())) {
                        return selector;
                    }
                }
            }
        } catch (Throwable error) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils exact selector unavailable id=" + cameraId,
                    error);
        }
        return null;
    }

    @SuppressLint("UnsafeOptInUsageError")
    private static String describeCameraInfoOrder(List<CameraInfo> cameras) {
        ArrayList<String> ids = new ArrayList<>(cameras.size());
        for (CameraInfo camera : cameras) {
            try {
                ids.add(Camera2CameraInfo.from(camera).getCameraId());
            } catch (Throwable error) {
                ids.add("?");
            }
        }
        return ids.toString();
    }

    private static int compareCameraIds(String left, String right) {
        try {
            return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        } catch (Throwable ignored) {
            return left.compareTo(right);
        }
    }

    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    public static String getWideCameraId(ProcessCameraProvider provider) {
        return findBackUltraWideCameraId(provider);
    }

    public static boolean isWideAngleAvailable(ProcessCameraProvider provider) {
        return hasLogicalUltraWide(provider) || findBackUltraWideCameraId(provider) != null;
    }

    public static float getBaseZoomRatio(@Nullable ZoomState state, boolean startFromUltraWide) {
        if (state == null) return 1f;
        float min = state.getMinZoomRatio();
        float max = state.getMaxZoomRatio();
        float target = startFromUltraWide ? min : 1f;
        return Math.max(min, Math.min(max, target));
    }

    public static float normalizedZoomToLinear(@Nullable ZoomState state,
                                               float baseRatio,
                                               float normalizedZoom) {
        if (state == null) return Math.max(0f, Math.min(1f, normalizedZoom));
        float baseLinear = ratioToLinearZoom(state, baseRatio);
        float normalized = Math.max(0f, Math.min(1f, normalizedZoom));
        return baseLinear + normalized * (1f - baseLinear);
    }

    public static float normalizedZoomToRatio(@Nullable ZoomState state,
                                              float baseRatio,
                                              float normalizedZoom) {
        if (state == null) return Math.max(0.0001f, baseRatio);
        return linearZoomToRatio(state,
                normalizedZoomToLinear(state, baseRatio, normalizedZoom));
    }

    public static float zoomRatioToNormalized(@Nullable ZoomState state,
                                              float baseRatio,
                                              float ratio) {
        if (state == null) return 0f;
        return linearZoomToNormalized(state, baseRatio,
                ratioToLinearZoom(state, ratio));
    }

    public static float linearZoomToNormalized(@Nullable ZoomState state,
                                               float baseRatio,
                                               float linearZoom) {
        if (state == null) return 0f;
        float baseLinear = ratioToLinearZoom(state, baseRatio);
        float range = 1f - baseLinear;
        if (range <= 0.0001f) return 0f;
        return Math.max(0f, Math.min(1f, (linearZoom - baseLinear) / range));
    }

    private static float ratioToLinearZoom(ZoomState state, float ratio) {
        float min = Math.max(0.0001f, state.getMinZoomRatio());
        float max = Math.max(min, state.getMaxZoomRatio());
        float clamped = Math.max(min, Math.min(max, ratio));
        if (max - min <= 0.0001f) return 0f;
        float denominator = 1f / min - 1f / max;
        if (Math.abs(denominator) <= 0.000001f) return 0f;
        return Math.max(0f, Math.min(1f, (1f / min - 1f / clamped) / denominator));
    }

    private static float linearZoomToRatio(ZoomState state, float linearZoom) {
        float min = Math.max(0.0001f, state.getMinZoomRatio());
        float max = Math.max(min, state.getMaxZoomRatio());
        if (max - min <= 0.0001f) return min;
        float linear = Math.max(0f, Math.min(1f, linearZoom));
        float inverse = 1f / min - linear * (1f / min - 1f / max);
        if (inverse <= 0.000001f) return max;
        return Math.max(min, Math.min(max, 1f / inverse));
    }

    @SuppressLint("UnsafeOptInUsageError")
    public static CameraSelector getDefaultWideAngleCamera(ProcessCameraProvider provider) {
        CameraSelector selector = buildUltraWideSelector(provider);
        if (selector != null) return selector;
        throw new IllegalArgumentException("This device doesn't support wide camera! "
                + "isWideAngleAvailable should be checked first before calling "
                + "getDefaultWideAngleCamera.");
    }

    public static ProcessCameraProvider getProviderBlocking(Context ctx) {
        try {
            ListenableFuture<ProcessCameraProvider> f = getProviderFuture(ctx);
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    public static int toSurfaceRotation(int rotation) {
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90
                || rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270) {
            return rotation;
        }
        int degrees = ((rotation % 360) + 360) % 360;
        if (degrees >= 315 || degrees < 45) return Surface.ROTATION_0;
        if (degrees < 135) return Surface.ROTATION_90;
        if (degrees < 225) return Surface.ROTATION_180;
        return Surface.ROTATION_270;
    }

    @Nullable
    public static Size getTargetResolutionSize() {
        int shortSide = NimarkoConfig.cameraResolution;
        if (shortSide <= 0) return null;
        int longSide = Math.max(1, Math.round(shortSide * 16f / 9f));
        if ((shortSide & 1) != 0) shortSide++;
        if ((longSide & 1) != 0) longSide++;
        return new Size(shortSide, longSide);
    }

    @Nullable
    public static Size getAttachPreviewResolutionSize() {
        Size output = getTargetResolutionSize();
        int shortSide = output == null
                ? 1080 : Math.min(1080, Math.min(output.getWidth(), output.getHeight()));
        int longSide = Math.max(1, Math.round(shortSide * 16f / 9f));
        if ((shortSide & 1) != 0) shortSide++;
        if ((longSide & 1) != 0) longSide++;
        return new Size(shortSide, longSide);
    }

    public static ResolutionSelector buildResolutionSelector(@Nullable Size preferred,
                                                              int aspectRatio,
                                                              boolean preferCaptureRate) {
        ResolutionSelector.Builder builder = new ResolutionSelector.Builder()
                .setAllowedResolutionMode(preferCaptureRate
                        ? ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
                        : ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE);
        if (aspectRatio == AspectRatio.RATIO_16_9) {
            builder.setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY);
        } else {
            builder.setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY);
        }
        if (preferred != null) {
            int fallbackRule = preferCaptureRate
                    ? ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    : ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER;
            builder.setResolutionStrategy(new ResolutionStrategy(
                    preferred, fallbackRule));
            final float preferredRatio = normalizedRatio(preferred);
            final boolean customRatio = Math.abs(preferredRatio - 16f / 9f) > 0.03f
                    && Math.abs(preferredRatio - 4f / 3f) > 0.03f;
            if (customRatio) {
                final long preferredArea = (long) preferred.getWidth() * preferred.getHeight();
                builder.setResolutionFilter((supportedSizes, rotationDegrees) -> {
                    ArrayList<Size> sorted = new ArrayList<>(supportedSizes);
                    sorted.sort(Comparator
                            .comparingDouble((Size size) ->
                                    Math.abs(normalizedRatio(size) - preferredRatio))
                            .thenComparingInt(size -> {
                                long area = (long) size.getWidth() * size.getHeight();
                                return preferCaptureRate
                                        ? (area <= preferredArea ? 0 : 1)
                                        : (area >= preferredArea ? 0 : 1);
                            })
                            .thenComparingLong(size ->
                                    Math.abs((long) size.getWidth() * size.getHeight() - preferredArea)));
                    return sorted;
                });
            }
        }
        return builder.build();
    }

    public static ResolutionSelector buildConcurrentPreviewResolutionSelector(
            @NonNull Size preferred, int aspectRatio) {
        final int requestedWidth = Math.max(1, preferred.getWidth());
        final int requestedHeight = Math.max(1, preferred.getHeight());
        final long requestedArea = (long) requestedWidth * requestedHeight;
        final float requestedRatio = normalizedRatio(preferred);
        ResolutionSelector.Builder builder = new ResolutionSelector.Builder()
                .setAllowedResolutionMode(
                        ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .setAspectRatioStrategy(aspectRatio == AspectRatio.RATIO_16_9
                        ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionFilter((supportedSizes, rotationDegrees) -> {
                    ArrayList<Size> compatible = new ArrayList<>();
                    ArrayList<Size> all = new ArrayList<>(supportedSizes);
                    for (Size size : supportedSizes) {
                        int orientedWidth = rotationDegrees == 90 || rotationDegrees == 270
                                ? size.getHeight() : size.getWidth();
                        int orientedHeight = rotationDegrees == 90 || rotationDegrees == 270
                                ? size.getWidth() : size.getHeight();
                        long area = (long) orientedWidth * orientedHeight;
                        if (area <= requestedArea
                                && Math.abs(normalizedRatio(size) - requestedRatio) <= 0.035f) {
                            compatible.add(size);
                        }
                    }
                    ArrayList<Size> result = compatible.isEmpty() ? all : compatible;
                    result.sort((left, right) -> compareConcurrentPreviewSizes(
                            left, right, rotationDegrees,
                            requestedWidth, requestedHeight, requestedArea,
                            requestedRatio));
                    if (!result.isEmpty()) {
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("CXUtils concurrent resolutions requested="
                                + preferred + " rotation=" + rotationDegrees
                                + " supported=" + supportedSizes.size()
                                + " capped=" + !compatible.isEmpty()
                                + " first=" + result.subList(0, Math.min(4, result.size())));
                    }
                    return result;
                });
        return builder.build();
    }

    private static int compareConcurrentPreviewSizes(
            Size left, Size right, int rotationDegrees,
            int requestedWidth, int requestedHeight, long requestedArea,
            float requestedRatio) {
        long leftScore = concurrentPreviewScore(left, rotationDegrees,
                requestedWidth, requestedHeight, requestedArea, requestedRatio);
        long rightScore = concurrentPreviewScore(right, rotationDegrees,
                requestedWidth, requestedHeight, requestedArea, requestedRatio);
        return Long.compare(leftScore, rightScore);
    }

    private static long concurrentPreviewScore(
            Size size, int rotationDegrees,
            int requestedWidth, int requestedHeight, long requestedArea,
            float requestedRatio) {
        int width = rotationDegrees == 90 || rotationDegrees == 270
                ? size.getHeight() : size.getWidth();
        int height = rotationDegrees == 90 || rotationDegrees == 270
                ? size.getWidth() : size.getHeight();
        long area = (long) width * height;
        long exactPenalty = width == requestedWidth && height == requestedHeight
                ? 0L : 1_000_000_000_000L;
        long oversizePenalty = area > requestedArea ? 2_000_000_000_000L : 0L;
        long ratioPenalty = Math.round(
                Math.abs(normalizedRatio(size) - requestedRatio) * 100_000_000_000L);
        long dimensionPenalty = (long) Math.abs(width - requestedWidth) * 1_000_000L
                + (long) Math.abs(height - requestedHeight) * 1_000L;
        long areaPenalty = Math.min(999_999_999L, Math.abs(area - requestedArea));
        return exactPenalty + oversizePenalty + ratioPenalty
                + dimensionPenalty + areaPenalty;
    }

    private static float normalizedRatio(Size size) {
        int shortSide = Math.max(1, Math.min(size.getWidth(), size.getHeight()));
        int longSide = Math.max(size.getWidth(), size.getHeight());
        return longSide / (float) shortSide;
    }

    @Nullable
    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    private static CameraInfo resolveSelectedCameraInfo(ProcessCameraProvider provider,
                                                        CameraSelector selector) {
        if (provider == null || selector == null) return null;
        try {
            CameraInfo selected = provider.getCameraInfo(selector);
            String physicalId = selector.getPhysicalCameraId();
            if (physicalId == null || selected == null) return selected;
            try {
                if (physicalId.equals(
                        Camera2CameraInfo.from(selected).getCameraId())) {
                    return selected;
                }
            } catch (Throwable ignored) {
            }
            Set<CameraInfo> physicalInfos = selected.getPhysicalCameraInfos();
            if (physicalInfos != null) {
                for (CameraInfo physicalInfo : physicalInfos) {
                    try {
                        if (physicalId.equals(Camera2CameraInfo.from(
                                physicalInfo).getCameraId())) {
                            return physicalInfo;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    private static CameraCapabilities getCameraCapabilities(ProcessCameraProvider provider,
                                                            CameraSelector selector) {
        if (provider == null || selector == null) return null;
        try {
            CameraInfo cameraInfo = resolveSelectedCameraInfo(provider, selector);
            if (cameraInfo == null) return null;
            String cameraId = Camera2CameraInfo.from(cameraInfo).getCameraId();
            CameraCapabilities cached = CAMERA_CAPABILITIES.get(cameraId);
            if (cached != null) return cached;
            CameraCapabilities capabilities = new CameraCapabilities(cameraInfo);
            CameraCapabilities raced = CAMERA_CAPABILITIES.putIfAbsent(cameraId, capabilities);
            return raced != null ? raced : capabilities;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean containsMode(@Nullable int[] modes, int wanted) {
        if (modes == null) return false;
        for (int mode : modes) {
            if (mode == wanted) return true;
        }
        return false;
    }

    public static boolean shouldEnableOpticalStabilization(ProcessCameraProvider provider,
                                                            CameraSelector selector) {
        CameraCapabilities capabilities = getCameraCapabilities(provider, selector);
        return NimarkoConfig.cameraOpticalStabilization
                && capabilities != null
                && containsMode(capabilities.opticalStabilizationModes,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
    }

    public static boolean shouldEnablePreviewStabilization(ProcessCameraProvider provider,
                                                            CameraSelector selector) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || !NimarkoConfig.cameraStabilisation
                || shouldEnableOpticalStabilization(provider, selector)) {
            return false;
        }
        CameraCapabilities capabilities = getCameraCapabilities(provider, selector);
        return capabilities != null
                && containsMode(capabilities.videoStabilizationModes,
                        android.hardware.camera2.CameraMetadata
                                .CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
    }

    public static boolean shouldEnableVideoStabilization(ProcessCameraProvider provider,
                                                          CameraSelector selector) {
        if (!NimarkoConfig.cameraStabilisation
                || shouldEnableOpticalStabilization(provider, selector)) {
            return false;
        }
        CameraCapabilities capabilities = getCameraCapabilities(provider, selector);
        return capabilities != null
                && (containsMode(capabilities.videoStabilizationModes,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && containsMode(capabilities.videoStabilizationModes,
                        android.hardware.camera2.CameraMetadata
                                .CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION));
    }

    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    public static void applyCamera2Controls(ProcessCameraProvider provider,
                                            CameraSelector selector,
                                            Camera2Interop.Extender<?> extender,
                                            boolean stillCapture) {
        CameraCapabilities capabilities = getCameraCapabilities(provider, selector);
        if (capabilities == null || extender == null) return;

        final boolean useOis = shouldEnableOpticalStabilization(provider, selector);
        if (containsMode(capabilities.opticalStabilizationModes,
                useOis ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                        : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            extender.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    useOis ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                            : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        if ((useOis || !NimarkoConfig.cameraStabilisation)
                && containsMode(capabilities.videoStabilizationModes,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
            extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }

        int preferredAf = stillCapture
                ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
        int fallbackAf = CaptureRequest.CONTROL_AF_MODE_AUTO;
        int selectedAf = NimarkoConfig.cameraContinuousFocus
                && containsMode(capabilities.autofocusModes, preferredAf)
                ? preferredAf
                : containsMode(capabilities.autofocusModes, fallbackAf)
                        ? fallbackAf : CaptureRequest.CONTROL_AF_MODE_OFF;
        if (containsMode(capabilities.autofocusModes, selectedAf)) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, selectedAf);
        }

        int selectedNoiseReduction;
        if (!NimarkoConfig.cameraNoiseReduction) {
            selectedNoiseReduction = CaptureRequest.NOISE_REDUCTION_MODE_OFF;
        } else if (stillCapture && containsMode(capabilities.noiseReductionModes,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
            selectedNoiseReduction = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY;
        } else {
            selectedNoiseReduction = CaptureRequest.NOISE_REDUCTION_MODE_FAST;
        }
        if (containsMode(capabilities.noiseReductionModes, selectedNoiseReduction)) {
            extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE, selectedNoiseReduction);
        }

        int selectedFaceDetection = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
        if (NimarkoConfig.cameraFaceDetection) {
            if (containsMode(capabilities.faceDetectionModes,
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)) {
                selectedFaceDetection = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE;
            } else if (containsMode(capabilities.faceDetectionModes,
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL)) {
                selectedFaceDetection = CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL;
            }
        }
        if (containsMode(capabilities.faceDetectionModes, selectedFaceDetection)) {
            extender.setCaptureRequestOption(
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE, selectedFaceDetection);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int selectedDistortion = stillCapture
                    && containsMode(capabilities.distortionCorrectionModes,
                            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
                    ? CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                    : CaptureRequest.DISTORTION_CORRECTION_MODE_FAST;
            if (containsMode(capabilities.distortionCorrectionModes, selectedDistortion)) {
                extender.setCaptureRequestOption(
                        CaptureRequest.DISTORTION_CORRECTION_MODE, selectedDistortion);
            }
        }
    }

    @Nullable
    public static Range<Integer> getTargetFpsRange() {
        switch (NimarkoConfig.cameraXFpsRange) {
            case NimarkoConfig.CameraXFpsRange25to30: return new Range<>(25, 30);
            case NimarkoConfig.CameraXFpsRange30to30: return new Range<>(30, 30);
            case NimarkoConfig.CameraXFpsRange30to60: return new Range<>(30, 60);
            case NimarkoConfig.CameraXFpsRange60to60: return new Range<>(30, 60);
            case NimarkoConfig.CameraXFpsRangeDefault:
            default:                                  return null;
        }
    }

    @Nullable
    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    public static Range<Integer> getSupportedTargetFpsRange(ProcessCameraProvider provider,
                                                            CameraSelector selector) {
        Range<Integer> requested = getTargetFpsRange();
        if (requested == null || provider == null || selector == null) return requested;
        try {
            CameraCapabilities capabilities = getCameraCapabilities(provider, selector);
            Range<Integer>[] ranges = capabilities != null ? capabilities.fpsRanges : null;
            if (ranges == null) return null;
            for (Range<Integer> r : ranges) {
                if (r != null && r.getLower().equals(requested.getLower())
                        && r.getUpper().equals(requested.getUpper())) {
                    return requested;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Nullable
    public static Range<Integer> getCommonSupportedTargetFpsRange(
            ProcessCameraProvider provider,
            CameraSelector firstSelector,
            CameraSelector secondSelector) {
        Range<Integer> requested = getTargetFpsRange();
        if (requested == null || provider == null
                || firstSelector == null || secondSelector == null) {
            return null;
        }
        CameraCapabilities first = getCameraCapabilities(provider, firstSelector);
        CameraCapabilities second = getCameraCapabilities(provider, secondSelector);
        return containsRange(first == null ? null : first.fpsRanges, requested)
                && containsRange(second == null ? null : second.fpsRanges, requested)
                ? requested : null;
    }

    private static boolean containsRange(@Nullable Range<Integer>[] ranges,
                                         Range<Integer> wanted) {
        if (ranges == null || wanted == null) return false;
        for (Range<Integer> range : ranges) {
            if (range != null
                    && wanted.getLower().equals(range.getLower())
                    && wanted.getUpper().equals(range.getUpper())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    public static Range<Integer> getSupportedTargetFpsRange(
            ProcessCameraProvider provider, CameraSelector selector,
            List<? extends UseCase> useCases) {
        Range<Integer> requested = getTargetFpsRange();
        if (requested == null || provider == null || selector == null
                || useCases == null || useCases.isEmpty()) {
            return null;
        }
        try {
            CameraInfo cameraInfo = resolveSelectedCameraInfo(provider, selector);
            if (cameraInfo == null) return null;
            SessionConfig probe = new SessionConfig.Builder(useCases).build();
            Set<Range<Integer>> supported =
                    cameraInfo.getSupportedFrameRateRanges(probe);
            if (supported == null) return null;
            for (Range<Integer> range : supported) {
                if (range != null
                        && requested.getLower().equals(range.getLower())
                        && requested.getUpper().equals(range.getUpper())) {
                    return requested;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static int normalizedExposureToIndex(float value, int lower, int upper) {
        float signed = (Math.max(0f, Math.min(1f, value)) - 0.5f) * 2f;
        int index = signed < 0f
                ? Math.round(Math.max(0, -lower) * signed)
                : Math.round(Math.max(0, upper) * signed);
        return Math.max(lower, Math.min(upper, index));
    }

    public static int configuredExposureToIndex(int value, int lower, int upper) {
        float signed = Math.max(-100, Math.min(100, value)) / 100f;
        int index = signed < 0f
                ? Math.round(Math.max(0, -lower) * signed)
                : Math.round(Math.max(0, upper) * signed);
        return Math.max(lower, Math.min(upper, index));
    }

    public static int exposureIndexToConfigured(int index, int lower, int upper) {
        int clamped = Math.max(lower, Math.min(upper, index));
        float normalized;
        if (clamped < 0) {
            normalized = Math.max(0, -lower) == 0
                    ? 0f : clamped / (float) Math.max(1, -lower);
        } else {
            normalized = Math.max(0, upper) == 0
                    ? 0f : clamped / (float) Math.max(1, upper);
        }
        return Math.max(-100, Math.min(100, Math.round(normalized * 100f)));
    }

    public static void warmUpAsync(Context ctx) {
        if (ctx == null || !isCameraXSupported()) return;
        try {
            final ListenableFuture<ProcessCameraProvider> f = getProviderFuture(ctx);
            f.addListener(() -> {  }, ContextCompat.getMainExecutor(ctx));
        } catch (Throwable ignored) {}
    }
}
