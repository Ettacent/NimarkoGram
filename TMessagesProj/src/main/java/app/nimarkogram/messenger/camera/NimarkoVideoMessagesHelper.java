package app.nimarkogram.messenger.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.media.CamcorderProfile;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.InstantCameraView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.NimarkoCameraLog;

public class NimarkoVideoMessagesHelper {

    private static final String TAG = "NimarkoVideoMessagesHelper";

    public NimarkoCameraXController cameraXController;
    private volatile NimarkoCameraXSurfaceSession cameraXSession;
    private volatile NimarkoCameraXSurfaceSession cameraXSecondarySession;
    private SurfaceTexture surfaceTexture;
    private SurfaceTexture secondarySurfaceTexture;
    private boolean frontFacing;
    private boolean cameraXDualMode;
    private boolean cameraXDualCompatibilityMode;
    private boolean cameraXDualTransitionPending;
    private boolean cameraXDualBindPending;
    private boolean cameraXDualBusyRetryPending;
    private int cameraXDualBusyRetryCount;
    private volatile int generation;
    private final Object cameraXCloseLock = new Object();
    private final ArrayList<NimarkoCameraXSurfaceSession> closingCameraXSessions =
            new ArrayList<>(2);
    private final ArrayList<Runnable> cameraXCloseWaiters = new ArrayList<>(2);

    public void createCameraX(InstantCameraView instantCameraView, final SurfaceTexture... surfaceTextures) {
        if (instantCameraView == null || surfaceTextures == null || surfaceTextures.length == 0
                || surfaceTextures[0] == null) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX create rejected view=" + (instantCameraView != null)
                    + " surfaces=" + (surfaceTextures == null ? "null" : surfaceTextures.length));
            return;
        }
        SurfaceTexture requestedSecondary = surfaceTextures.length > 1
                ? surfaceTextures[1] : null;
        
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX create surfaces=" + surfaceTextures.length
                + " effectiveDual=" + (requestedSecondary != null)
                + " front=" + instantCameraView.isCameraXFrontFacing());
        cameraXDualBusyRetryCount = 0;
        cameraXDualBusyRetryPending = false;
        createCameraXAttempt(instantCameraView, false, surfaceTextures[0],
                requestedSecondary);
    }

    private void createCameraXAttempt(InstantCameraView instantCameraView,
                                      boolean compatibilityMode,
                                      SurfaceTexture requestedSurface,
                                      SurfaceTexture requestedSecondary) {
        final int currentGeneration = ++generation;
        final boolean requestedFrontFacing = instantCameraView.isCameraXFrontFacing();
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX attempt generation=" + currentGeneration
                + " compatibility=" + compatibilityMode
                + " front=" + requestedFrontFacing
                + " dual=" + (requestedSecondary != null));
        closeActiveCameraXSessions(() -> {
            if (currentGeneration != generation) {
                return;
            }
            startCameraX(instantCameraView, requestedSurface, requestedSecondary,
                    requestedFrontFacing, currentGeneration, compatibilityMode);
        });
    }

    private void startCameraX(InstantCameraView instantCameraView,
                              SurfaceTexture requestedSurface,
                              SurfaceTexture requestedSecondary,
                              boolean requestedFrontFacing,
                              int currentGeneration,
                              boolean compatibilityMode) {
        if (currentGeneration != generation || requestedSurface == null) {
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX start stale/rejected generation=" + currentGeneration
                    + " active=" + generation + " surface=" + (requestedSurface != null));
            return;
        }
        surfaceTexture = requestedSurface;
        secondarySurfaceTexture = requestedSecondary;
        frontFacing = requestedFrontFacing;
        int size = NimarkoConfig.getVideoMessagesResolutionPx(512);
        int capture = Math.min(1200, Math.max(size, size * 2));
        final boolean dual = requestedSecondary != null;
        
        final int targetWidth = dual ? (compatibilityMode ? 480 : 720) : capture;
        final int targetHeight = dual ? (compatibilityMode ? 640 : 1280) : capture;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX start generation=" + currentGeneration
                + " front=" + requestedFrontFacing + " dual=" + dual
                + " compatibility=" + compatibilityMode
                + " startWide=" + NimarkoConfig.startFromUltraWideCam
                + " configuredSize=" + size + " capture=" + capture
                + " target=" + targetWidth + "x" + targetHeight);
        cameraXDualMode = dual;
        cameraXDualCompatibilityMode = dual && compatibilityMode;
        cameraXDualTransitionPending = false;
        cameraXDualBindPending = false;
        instantCameraView.onCameraXAttemptStarting(dual);
        cameraXSession = new NimarkoCameraXSurfaceSession(
                instantCameraView.getContext(), surfaceTexture, frontFacing,
                targetWidth, targetHeight,
                false, dual, false, true,
                new NimarkoCameraXSurfaceSession.Callback() {
                    @Override
                    public void onReady(int width, int height) {
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX primary ready generation="
                                + currentGeneration + " size=" + width + "x" + height
                                + " dual=" + cameraXDualMode);
                        if (currentGeneration == generation && cameraXSession != null) {
                            if (!cameraXDualMode
                                    || tryBindConcurrentRoundCamera(instantCameraView, width, height)) {
                                cameraXController = getCurrentSession().getController();
                                
                                instantCameraView.onCameraXSessionReady(cameraXSession, width, height);
                            }
                        }
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        if (currentGeneration != generation || cameraXSession == null) return;
                        FileLog.e("Round video CameraX bind failed", error);
                            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX primary FAILED generation="
                                    + currentGeneration, error);
                            if (cameraXDualMode) {
                                handleCameraXDualFailure(
                                        instantCameraView, currentGeneration,
                                        isConcurrentResourceFailure(error));
                            }
                    }
                });
        if (dual) {
            cameraXSecondarySession = new NimarkoCameraXSurfaceSession(
                    instantCameraView.getContext(), requestedSecondary, !frontFacing,
                    targetWidth, targetHeight,
                    false, true, false, true,
                    new NimarkoCameraXSurfaceSession.Callback() {
                        @Override
                        public void onReady(int width, int height) {
                            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX secondary ready generation="
                                    + currentGeneration + " size=" + width + "x" + height);
                            if (currentGeneration == generation && cameraXSecondarySession != null
                                    && tryBindConcurrentRoundCamera(instantCameraView, width, height)) {
                                cameraXController = getCurrentSession().getController();
                                instantCameraView.onCameraXSessionReady(cameraXSecondarySession, width, height);
                            }
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            if (currentGeneration != generation || cameraXSecondarySession == null) return;
                            FileLog.e("Round video secondary CameraX bind failed", error);
                            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX secondary FAILED generation="
                                    + currentGeneration, error);
                            handleCameraXDualFailure(
                                    instantCameraView, currentGeneration,
                                    isConcurrentResourceFailure(error));
                        }
                    });
        }
    }

    private boolean tryBindConcurrentRoundCamera(InstantCameraView view, int width, int height) {
        boolean primaryPrepared = cameraXSession != null && cameraXSession.isPrepared();
        boolean secondaryPrepared = cameraXSecondarySession != null && cameraXSecondarySession.isPrepared();
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX concurrent probe generation=" + generation
                + " primaryPrepared=" + primaryPrepared
                + " secondaryPrepared=" + secondaryPrepared
                + " callbackSize=" + width + "x" + height
                + " compatibility=" + cameraXDualCompatibilityMode);
        if (!primaryPrepared || !secondaryPrepared) return false;
        if (!cameraXSession.isConcurrentWith(cameraXSecondarySession)) {
            if (cameraXDualBindPending) {
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX concurrent probe ignored while bind pending generation="
                        + generation);
                return false;
            }
            cameraXDualBindPending = true;
            boolean bound;
            try {
                bound = cameraXSession.bindConcurrentWith(
                        cameraXSecondarySession, cameraXDualCompatibilityMode);
            } finally {
                cameraXDualBindPending = false;
            }
            if (!bound) {
                if ((cameraXSession.wasLastConcurrentBindBusy()
                        || cameraXSecondarySession.wasLastConcurrentBindBusy())
                        && scheduleCameraXDualBusyRetry(view)) {
                    return false;
                }
                handleCameraXDualFailure(view, generation, false);
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX concurrent bind returned false generation="
                        + generation);
                return false;
            }
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX concurrent ready generation=" + generation);
        return true;
    }

    private boolean scheduleCameraXDualBusyRetry(InstantCameraView view) {
        if (cameraXDualBusyRetryPending || cameraXDualBusyRetryCount >= 2
                || surfaceTexture == null || secondarySurfaceTexture == null) {
            return false;
        }
        final int expectedGeneration = generation;
        final boolean compatibility = cameraXDualCompatibilityMode;
        final SurfaceTexture primarySurface = surfaceTexture;
        final SurfaceTexture secondarySurface = secondarySurfaceTexture;
        final int retry = ++cameraXDualBusyRetryCount;
        cameraXDualBusyRetryPending = true;
        cameraXDualTransitionPending = true;
        view.onCameraXTransitionStarting();
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX provider-busy retry scheduled generation="
                + expectedGeneration + " retry=" + retry
                + " compatibility=" + compatibility);
        AndroidUtilities.runOnUIThread(() -> {
            if (expectedGeneration != generation || !cameraXDualMode) {
                cameraXDualBusyRetryPending = false;
                cameraXDualTransitionPending = false;
                return;
            }
            cameraXDualBusyRetryPending = false;
            cameraXDualTransitionPending = false;
            if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX provider-busy retry executing generation="
                    + expectedGeneration + " retry=" + retry);
            createCameraXAttempt(view, compatibility, primarySurface, secondarySurface);
        }, 220L * retry);
        return true;
    }

    private void handleCameraXDualFailure(InstantCameraView view,
                                          int expectedGeneration,
                                          boolean resourceFailure) {
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX dual failure generation=" + expectedGeneration
                + " compatibility=" + cameraXDualCompatibilityMode
                + " resourceFailure=" + resourceFailure);
        AndroidUtilities.runOnUIThread(() -> {
            if (expectedGeneration != generation || !cameraXDualMode) return;
            
            if (!resourceFailure && !cameraXDualCompatibilityMode
                    && retryCameraXDual(view)) {
                return;
            }
            collapseCameraXDualToSingle(view, expectedGeneration);
        });
    }

    private static boolean isConcurrentResourceFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof
                    NimarkoCameraXController.ConcurrentCameraResourceException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public boolean retryCameraXDual(InstantCameraView view) {
        if (view == null || !cameraXDualMode || cameraXDualCompatibilityMode
                || cameraXDualTransitionPending
                || surfaceTexture == null || secondarySurfaceTexture == null) {
            return false;
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX dual compatibility retry generation=" + generation);
        SurfaceTexture primarySurface = surfaceTexture;
        SurfaceTexture secondarySurface = secondarySurfaceTexture;
        cameraXDualTransitionPending = true;
        view.onCameraXTransitionStarting();
        createCameraXAttempt(
                view, true, primarySurface, secondarySurface);
        return true;
    }

    public void fallbackCameraXDualToSingle(InstantCameraView view) {
        if (view == null) return;
        AndroidUtilities.runOnUIThread(
                () -> collapseCameraXDualToSingle(view, generation));
    }

    private void collapseCameraXDualToSingle(InstantCameraView view,
                                             int expectedGeneration) {
        if (expectedGeneration != generation || cameraXSession == null
                || cameraXDualTransitionPending) {
            return;
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX dual collapse-to-single generation="
                + expectedGeneration);
        cameraXDualTransitionPending = true;
        cameraXDualMode = false;
        cameraXDualCompatibilityMode = false;
        final NimarkoCameraXSurfaceSession primary = cameraXSession;
        NimarkoCameraXSurfaceSession secondary = cameraXSecondarySession;
        cameraXSecondarySession = null;
        view.onCameraXDualUnavailable();

        AtomicInteger releases = new AtomicInteger(secondary == null ? 1 : 2);
        Runnable afterRelease = () -> {
            if (releases.decrementAndGet() != 0) return;
            AndroidUtilities.runOnUIThread(() -> {
                if (expectedGeneration != generation
                        || primary != cameraXSession || cameraXDualMode) {
                    return;
                }
                Runnable rebindSingle = () -> {
                    if (expectedGeneration != generation
                            || primary != cameraXSession || cameraXDualMode) {
                        return;
                    }
                    cameraXDualTransitionPending = false;
                    if (primary.rebindSingle()) {
                        cameraXController = primary.getController();
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX single fallback rebound generation="
                                + expectedGeneration);
                    } else {
                        FileLog.e("Round video CameraX single fallback bind failed");
                        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX single fallback FAILED generation="
                                + expectedGeneration);
                    }
                };
                
                rebindSingle.run();
            });
        };
        primary.releaseSurfaceForRebind(afterRelease);
        if (secondary != null) {
            retireCameraXSession(secondary, afterRelease);
        }
    }

    public NimarkoCameraXSurfaceSession getCurrentSession() {
        if (cameraXSecondarySession != null
                && cameraXSecondarySession.isFrontFacing() == frontFacing) {
            return cameraXSecondarySession;
        }
        return cameraXSession;
    }

    public NimarkoCameraXSurfaceSession getRearSession() {
        if (cameraXSession != null && !cameraXSession.isFrontFacing()) {
            return cameraXSession;
        }
        if (cameraXSecondarySession != null
                && !cameraXSecondarySession.isFrontFacing()) {
            return cameraXSecondarySession;
        }
        return null;
    }

    public int getSessionIndex(NimarkoCameraXSurfaceSession session) {
        if (session == null) return -1;
        if (session == cameraXSession) return 0;
        if (session == cameraXSecondarySession) return 1;
        return -1;
    }

    public boolean isConcurrentDualReady() {
        return cameraXSession != null
                && cameraXSecondarySession != null
                && cameraXSession.isConcurrentWith(cameraXSecondarySession)
                && cameraXSession.isInitiated()
                && cameraXSecondarySession.isInitiated();
    }

    public void switchCameraX(InstantCameraView instantCameraView) {
        if (instantCameraView == null) return;
        frontFacing = instantCameraView.isCameraXFrontFacing();
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX switch requested generation=" + generation
                + " front=" + frontFacing + " dualSessions="
                + (cameraXSecondarySession != null));
        if (cameraXSecondarySession != null && cameraXSession != null) {
            if (cameraXSession.isConcurrentWith(cameraXSecondarySession)) {
                NimarkoCameraXSurfaceSession current = getCurrentSession();
                cameraXController = current == null ? null : current.getController();
            } else {
                
                FileLog.e("Round video CameraX dual switch requested before concurrent bind completed");
                if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX dual switch ignored: pair not ready generation="
                        + generation);
            }
            return;
        }
        if (cameraXSession != null) {
            
            final SurfaceTexture targetSurface = surfaceTexture;
            final NimarkoCameraXSurfaceSession switchingSession = cameraXSession;
            switchingSession.releaseSurfaceForRebind(() -> {
                if (switchingSession != cameraXSession || targetSurface == null
                        || targetSurface != surfaceTexture) {
                    return;
                }
                switchingSession.switchCamera(frontFacing);
                cameraXController = switchingSession.getController();
            });
        } else if (surfaceTexture != null) {
            
            if (secondarySurfaceTexture != null) {
                createCameraX(instantCameraView, surfaceTexture, secondarySurfaceTexture);
            } else {
                createCameraX(instantCameraView, surfaceTexture);
            }
        }
    }

    public void destroyCameraX(InstantCameraView instantCameraView) {
        destroyCameraX(instantCameraView, null);
    }

    public void destroyCameraX(InstantCameraView instantCameraView, Runnable onClosed) {
        int oldGeneration = generation;
        generation++;
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX destroy generation=" + oldGeneration
                + " next=" + generation);
        cameraXDualTransitionPending = false;
        cameraXDualCompatibilityMode = false;
        cameraXDualBindPending = false;
        cameraXDualBusyRetryPending = false;
        cameraXDualBusyRetryCount = 0;
        closeActiveCameraXSessions(onClosed);
    }

    private void closeActiveCameraXSessions(Runnable onClosed) {
        ArrayList<NimarkoCameraXSurfaceSession> sessions = new ArrayList<>(2);
        boolean runImmediately = false;
        synchronized (cameraXCloseLock) {
            addClosingSessionLocked(sessions, cameraXSession);
            addClosingSessionLocked(sessions, cameraXSecondarySession);
            cameraXSession = null;
            cameraXSecondarySession = null;
            cameraXDualMode = false;
            cameraXController = null;
            surfaceTexture = null;
            secondarySurfaceTexture = null;
            if (onClosed != null) {
                if (closingCameraXSessions.isEmpty()) {
                    runImmediately = true;
                } else {
                    cameraXCloseWaiters.add(onClosed);
                }
            }
        }
        if (NimarkoCameraLog.DEBUG) NimarkoCameraLog.log("RoundCX close sessions count=" + sessions.size()
                + " pending=" + closingCameraXSessions.size());
        for (NimarkoCameraXSurfaceSession session : sessions) {
            try {
                session.enableTorch(false);
            } catch (Throwable error) {
                FileLog.e(error);
            }
            session.close(() -> onCameraXSessionClosed(session));
        }
        if (runImmediately) {
            onClosed.run();
        }
    }

    private void retireCameraXSession(NimarkoCameraXSurfaceSession session) {
        retireCameraXSession(session, null);
    }

    private void retireCameraXSession(NimarkoCameraXSurfaceSession session,
                                      Runnable onRetired) {
        if (session == null) {
            if (onRetired != null) onRetired.run();
            return;
        }
        boolean added;
        synchronized (cameraXCloseLock) {
            added = !closingCameraXSessions.contains(session);
            if (added) {
                closingCameraXSessions.add(session);
            }
        }
        if (added) {
            try {
                session.enableTorch(false);
            } catch (Throwable error) {
                FileLog.e(error);
            }
            session.close(() -> {
                onCameraXSessionClosed(session);
                if (onRetired != null) onRetired.run();
            });
        } else if (onRetired != null) {
            
            boolean runImmediately;
            synchronized (cameraXCloseLock) {
                runImmediately = closingCameraXSessions.isEmpty();
                if (!runImmediately) {
                    cameraXCloseWaiters.add(onRetired);
                }
            }
            if (runImmediately) onRetired.run();
        }
    }

    private void addClosingSessionLocked(
            ArrayList<NimarkoCameraXSurfaceSession> sessions,
            NimarkoCameraXSurfaceSession session) {
        if (session == null || closingCameraXSessions.contains(session)) {
            return;
        }
        closingCameraXSessions.add(session);
        sessions.add(session);
    }

    private void onCameraXSessionClosed(NimarkoCameraXSurfaceSession session) {
        ArrayList<Runnable> waiters = null;
        synchronized (cameraXCloseLock) {
            if (!closingCameraXSessions.remove(session)
                    || !closingCameraXSessions.isEmpty()
                    || cameraXCloseWaiters.isEmpty()) {
                return;
            }
            waiters = new ArrayList<>(cameraXCloseWaiters);
            cameraXCloseWaiters.clear();
        }
        for (Runnable waiter : waiters) {
            try {
                waiter.run();
            } catch (Throwable error) {
                FileLog.e(error);
            }
        }
    }

    public void setZoom(float zoom) {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        if (current != null) current.setZoom(zoom);
    }

    public void setZoomRatio(float ratio) {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        if (current != null) current.setZoomRatio(ratio);
    }

    public float getZoomRatio() {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        return current != null ? current.getZoomRatio() : 1f;
    }

    public float getMinZoomRatio() {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        return current != null ? current.getMinZoomRatio() : 1f;
    }

    public float getMaxZoomRatio() {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        return current != null ? current.getMaxZoomRatio() : 1f;
    }

    public boolean createFlashConfigurator(InstantCameraView instantCameraView) {
        return false;
    }

    public void checkFlash(InstantCameraView instantCameraView) {

    }

    public void updateCameraXFlash(InstantCameraView instantCameraView) {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        boolean enableRear = instantCameraView.shouldEnableCameraXRearTorch();
        applyCameraXFlash(cameraXSession, current, enableRear);
        if (cameraXSecondarySession != cameraXSession) {
            applyCameraXFlash(cameraXSecondarySession, current, enableRear);
        }
    }

    private static void applyCameraXFlash(NimarkoCameraXSurfaceSession session,
                                          NimarkoCameraXSurfaceSession current,
                                          boolean enableRear) {
        if (session != null) {
            session.enableTorch(enableRear && session == current && !session.isFrontFacing());
        }
    }

    public void showExposureControls(InstantCameraView instantCameraView, boolean show) {
        
    }

    public int getSliderW() {
        return 0;
    }

    public int getSliderH() {
        return 0;
    }

    public int getSliderBM() {
        return 0;
    }

    public boolean isInitiated() {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        return current != null && current.isInitiated();
    }

    public boolean isExposureCompensationSupported() {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        return current != null && current.isExposureCompensationSupported();
    }

    public void setExposureCompensation(float value) {
        NimarkoCameraXSurfaceSession current = getCurrentSession();
        if (current != null) current.setExposureCompensation(value);
    }

    public static boolean isHDRAvailable() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    for (String t : info.getSupportedTypes()) {
                        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(t)) {
                            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(t);
                            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                                
                                if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                                        || pl.profile == 4096 || pl.profile == 8192) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.d(TAG + ": isHDRAvailable probe failed: " + t.getMessage());
        }
        return false;
    }

    public static boolean applyHDRProfile(MediaRecorder rec) {
        return false;
    }

    public static int applyStabilization(CameraCharacteristics chars) {
        if (chars == null) {
            return android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
        try {
            int[] modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (modes == null || modes.length == 0) {
                return android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
            }
            int best = android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
            for (int m : modes) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && m == android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) {
                    return m;
                }
                if (m == android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                    best = m;
                }
            }
            return best;
        } catch (Throwable t) {
            FileLog.d(TAG + ": applyStabilization failed: " + t.getMessage());
            return android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
    }

    public static LoudnessEnhancer applyAudioGain(int audioSessionId, int gainDb) {
        if (audioSessionId == 0) return null;
        int clamped = Math.max(-20, Math.min(20, gainDb));
        if (clamped == 0) return null;
        try {
            LoudnessEnhancer enhancer = new LoudnessEnhancer(audioSessionId);
            
            enhancer.setTargetGain(clamped * 100);
            enhancer.setEnabled(true);
            return enhancer;
        } catch (Throwable t) {
            FileLog.d(TAG + ": applyAudioGain(" + clamped + "dB) failed: " + t.getMessage());
            return null;
        }
    }

    public static LoudnessEnhancer applyAudioGain(int audioSessionId) {
        return applyAudioGain(audioSessionId, 0);
    }

    public static boolean isPreviewBeforeSendEnabled() {
        return false;
    }

    public static int getPreviewLoopCount() {
        return 3;
    }

    public interface ShakeListener {
        void onShake();
    }

    public static class ShakeDetector implements SensorEventListener {

        private static final float SHAKE_THRESHOLD_GFORCE = 2.7f;
        private static final long  MIN_INTERVAL_MS        = 500L;

        private final SensorManager sensorManager;
        private final Sensor accelerometer;
        private final WeakReference<ShakeListener> listenerRef;
        private long lastShakeTime;
        private boolean started;

        public ShakeDetector(Context ctx, ShakeListener listener) {
            this.listenerRef = new WeakReference<>(listener);
            SensorManager sm = null;
            Sensor accel = null;
            try {
                sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
                if (sm != null) {
                    accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                }
            } catch (Throwable ignored) {}
            this.sensorManager = sm;
            this.accelerometer = accel;
        }

        public void start() {
            
        }

        public void stop() {
            if (!started) return;
            try {
                sensorManager.unregisterListener(this);
            } catch (Throwable ignored) {}
            started = false;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length < 3) return;
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            float gX = x / SensorManager.GRAVITY_EARTH;
            float gY = y / SensorManager.GRAVITY_EARTH;
            float gZ = z / SensorManager.GRAVITY_EARTH;
            float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);
            if (gForce > SHAKE_THRESHOLD_GFORCE) {
                long now = System.currentTimeMillis();
                if (now - lastShakeTime < MIN_INTERVAL_MS) return;
                lastShakeTime = now;
                ShakeListener l = listenerRef.get();
                if (l != null) {
                    l.onShake();
                }
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {   }
    }
}
