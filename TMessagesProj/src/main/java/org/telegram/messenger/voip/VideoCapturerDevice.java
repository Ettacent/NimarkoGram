/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger.voip;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.WindowManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.CapturerObserver;
import org.webrtc.EglBase;
import org.webrtc.Logging;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.voiceengine.WebRtcAudioRecord;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;
public class VideoCapturerDevice {

    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;
    private static final int CAPTURE_FPS = 30;

    public static EglBase eglBase;

    public static volatile Intent mediaProjectionPermissionResultData;

    private static final AtomicReferenceArray<VideoCapturerDevice> instance = new AtomicReferenceArray<>(2);
    private static final AtomicLong nextDeviceId = new AtomicLong();
    private static final ThreadLocal<VoIPService> creationOwner = new ThreadLocal<>();
    private static final Object deviceAudioLock = new Object();
    private static CaptureResources deviceAudioOwner;

    private final Object lifecycleLock = new Object();
    private final long deviceId = nextDeviceId.incrementAndGet();
    private final HashMap<Long, Binding> bindings = new HashMap<>();
    private final boolean screencast;
    private final Intent projectionPermissionData;
    private final WeakReference<VoIPService> serviceOwner;
    private volatile boolean destroyed;
    private volatile Binding currentBinding;

    private HandlerThread thread;
    private Handler handler;
    private boolean queueFailureReported;
    private volatile CaptureResources resources;

    private static final class Binding {
        final long id;
        final String deviceName;
        final CapturerObserver observer;
        volatile boolean retired;
        volatile boolean failed;
        volatile int state = Instance.VIDEO_STATE_ACTIVE;
        volatile CaptureResources resources;
        boolean installing;
        boolean acknowledged;

        Binding(long id, String deviceName, CapturerObserver observer) {
            this.id = id;
            this.deviceName = deviceName;
            this.observer = observer;
        }
    }

    private final class ObserverGate implements CapturerObserver {
        private Binding binding;
        private boolean started;

        synchronized void bind(Binding next) {
            if (binding != null && binding.observer != next.observer) {
                throw new IllegalStateException("Capture observer changed without a stop barrier");
            }
            binding = next;
        }

        synchronized void close() {
            Binding previous = binding;
            binding = null;
            boolean wasStarted = started;
            started = false;
            if (previous != null && wasStarted) {
                previous.observer.onCapturerStopped();
            }
        }

        @Override
        public synchronized void onCapturerStarted(boolean success) {
            Binding current = binding;
            if (current == null || current.retired || current.failed || destroyed) return;
            current.observer.onCapturerStarted(success);
            started = success;
        }

        @Override
        public synchronized void onCapturerStopped() {
            Binding current = binding;
            if (current == null || current.retired || current.failed || destroyed) return;
            current.observer.onCapturerStopped();
            started = false;
        }

        @Override
        public synchronized void onFrameCaptured(VideoFrame frame) {
            Binding current = binding;
            if (current == null || current.retired || current.failed || destroyed) return;
            current.observer.onFrameCaptured(frame);
        }
    }

    private final class CaptureResources {
        final boolean screen;
        final ObserverGate gate = new ObserverGate();
        volatile Binding binding;
        VideoCapturer capturer;
        SurfaceTextureHelper helper;
        volatile String cameraName;
        volatile boolean frontCamera;
        volatile boolean initialized;
        volatile boolean failed;
        volatile boolean stopping;
        volatile boolean cleanupFailed;
        volatile boolean cleaned;
        int appliedState = Instance.VIDEO_STATE_INACTIVE;
        volatile int width;
        volatile int height;
        volatile MediaProjection projection;
        WebRtcAudioRecord audioRecord;

        CaptureResources(Binding binding) {
            this.binding = binding;
            screen = "screen".equals(binding.deviceName);
        }
    }

    private static <T> T withServiceOwner(VoIPService owner, Supplier<T> action) {
        VoIPService previous = creationOwner.get();
        creationOwner.set(owner);
        try {
            return action.get();
        } finally {
            if (previous == null) creationOwner.remove();
            else creationOwner.set(previous);
        }
    }

    public static long createWithServiceOwner(VoIPService owner, VideoSink sink, int type) {
        return withServiceOwner(owner, () -> NativeInstance.createVideoCapturer(sink, type));
    }

    public static void setupWithServiceOwner(VoIPService owner, NativeInstance instance, VideoSink sink, int type) {
        withServiceOwner(owner, () -> {
            instance.setupOutgoingVideo(sink, type);
            return null;
        });
    }

    public VideoCapturerDevice(boolean screencast) {
        this.screencast = screencast;
        projectionPermissionData = mediaProjectionPermissionResultData;
        VoIPService owner = creationOwner.get();
        serviceOwner = new WeakReference<>(owner != null ? owner : VoIPService.getSharedInstance());
        if (Build.VERSION.SDK_INT < 18) {
            destroyed = true;
            return;
        }
        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);
        Logging.d("VideoCapturerDevice", "device model = " + Build.MANUFACTURER + Build.MODEL);
        if (!postUi(() -> {
            if (destroyed) {
                acknowledgeUninstalledBindings();
                return;
            }
            HandlerThread captureThread = null;
            try {
                if (eglBase == null) eglBase = EglBase.create(null, EglBase.CONFIG_PLAIN);
                captureThread = new HandlerThread("CallThread");
                captureThread.start();
                Handler captureHandler = new Handler(captureThread.getLooper());
                synchronized (lifecycleLock) {
                    thread = captureThread;
                    handler = captureHandler;
                }
                if (!destroyed) {
                    int index = screencast ? 1 : 0;
                    VideoCapturerDevice previous;
                    do {
                        previous = instance.get(index);
                        if (previous != null && previous.deviceId > deviceId) break;
                    } while (!instance.compareAndSet(index, previous, this));
                }
                scheduleWork();
            } catch (Throwable error) {
                FileLog.e(error);
                synchronized (lifecycleLock) {
                    if (captureThread != null && thread != captureThread) captureThread.quitSafely();
                }
                onDestroy();
            }
        })) {
            onDestroy();
        }
    }

    private static boolean postUi(Runnable runnable) {
        Handler ui = ApplicationLoader.applicationHandler;
        return ui != null && ui.post(runnable);
    }

    private boolean isCurrentLocked(Binding binding) {
        return binding != null && !destroyed && !binding.retired && !binding.acknowledged
                && currentBinding == binding && bindings.get(binding.id) == binding;
    }

    private boolean isCurrent(Binding binding) {
        synchronized (lifecycleLock) {
            return isCurrentLocked(binding);
        }
    }

    private boolean isActive(Binding binding, CaptureResources capture) {
        return isCurrent(binding) && !binding.failed && resources == capture && capture.binding == binding
                && capture.initialized && !capture.failed && !capture.stopping && !capture.cleaned && !capture.cleanupFailed;
    }

    private VoIPService currentService(Binding binding, CaptureResources capture) {
        VoIPService owner = serviceOwner.get();
        return owner != null && VoIPService.getSharedInstance() == owner
                && instance.get(screencast ? 1 : 0) == this && isActive(binding, capture) ? owner : null;
    }

    private boolean init(long bindingId, String deviceName, CapturerObserver observer) {
        if (bindingId == 0 || observer == null || Build.VERSION.SDK_INT < 18
                || "screen".equals(deviceName) && Build.VERSION.SDK_INT < 21) return false;
        Binding binding = new Binding(bindingId, deviceName, observer);
        synchronized (lifecycleLock) {
            if (destroyed || bindings.containsKey(bindingId)) return false;
            bindings.put(bindingId, binding);
            Binding previous = currentBinding;
            currentBinding = binding;
            if (previous != null) previous.retired = true;
        }
        scheduleWork();
        return true;
    }

    private void onCaptureRetired(long bindingId) {
        boolean absent;
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(bindingId);
            absent = binding == null;
            if (binding != null) {
                binding.retired = true;
                if (currentBinding == binding) currentBinding = null;
            }
        }
        if (absent) {
            nativeReleaseSourceLease(bindingId);
        } else {
            scheduleWork();
        }
    }

    private void scheduleWork() {
        if (!postUi(() -> {
            if (destroyed) instance.compareAndSet(screencast ? 1 : 0, this, null);
            Binding expected = currentBinding;
            if (!isCurrent(expected)) expected = null;
            Handler captureHandler;
            synchronized (lifecycleLock) {
                captureHandler = handler;
            }
            final Binding requested = expected;
            if (captureHandler == null) {
                if (destroyed) acknowledgeUninstalledBindings();
            } else if (!captureHandler.post(() -> reconcile(requested))) {
                handleRejectedQueue();
            }
        })) handleRejectedQueue();
    }

    private void handleRejectedQueue() {
        synchronized (lifecycleLock) {
            destroyed = true;
            currentBinding = null;
            for (Binding binding : bindings.values()) binding.retired = true;
            if (!queueFailureReported) {
                queueFailureReported = true;
                FileLog.e("Capture lifecycle queue rejected work; retaining any in-use source leases");
            }
        }
        instance.compareAndSet(screencast ? 1 : 0, this, null);
        acknowledgeUninstalledBindings();
    }

    private ArrayList<Binding> snapshotBindings() {
        synchronized (lifecycleLock) {
            return new ArrayList<>(bindings.values());
        }
    }

    private void acknowledgeUninstalledBindings() {
        for (Binding binding : snapshotBindings()) {
            synchronized (lifecycleLock) {
                if (binding.installing || binding.resources != null) continue;
            }
            acknowledge(binding);
        }
    }

    private void acknowledge(Binding binding) {
        synchronized (lifecycleLock) {
            if (binding.acknowledged || binding.installing || !binding.retired) return;
            CaptureResources capture = binding.resources;
            if (capture != null && !capture.cleaned && capture.binding == binding) return;
            binding.acknowledged = true;
            bindings.remove(binding.id);
        }
        nativeReleaseSourceLease(binding.id);
    }

    private void reconcile(Binding requested) {
        if (isCurrent(requested)) {
            synchronized (lifecycleLock) {
                if (!isCurrentLocked(requested)) return;
                requested.installing = true;
            }
            try {
                install(requested);
            } catch (Throwable error) {
                FileLog.e(error);
                CaptureResources capture = requested.resources;
                if (!"screen".equals(requested.deviceName)) {
                    failCamera(requested, capture);
                } else {
                    synchronized (lifecycleLock) {
                        requested.retired = true;
                        if (currentBinding == requested) currentBinding = null;
                    }
                    if (capture != null) cleanup(capture);
                }
            } finally {
                synchronized (lifecycleLock) {
                    requested.installing = false;
                }
            }
        }
        for (Binding binding : snapshotBindings()) {
            if (!binding.retired) continue;
            CaptureResources capture = binding.resources;
            if (capture == null || capture.cleaned || capture.binding != binding) {
                acknowledge(binding);
            } else if (!capture.stopping) {
                Binding replacement = currentBinding;
                if (isCurrent(replacement) && replacement != requested && !capture.cleanupFailed) continue;
                cleanup(capture);
            }
        }
        maybeQuit();
    }

    private String cameraName(String deviceName, CameraEnumerator enumerator) {
        boolean front = "front".equals(deviceName);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name) == front) return name;
        }
        throw new IllegalStateException("Requested camera is unavailable");
    }

    private void install(Binding binding) {
        if (!isCurrent(binding) || binding.failed) return;
        CaptureResources capture = resources;
        if (capture != null && capture.stopping) return;
        if (capture != null && (capture.failed || capture.cleanupFailed)) {
            cleanup(capture);
            return;
        }
        if (capture != null && capture.binding != binding) {
            boolean sameObserver = capture.initialized && !capture.binding.failed && capture.binding.observer == binding.observer
                    && !capture.screen && !"screen".equals(binding.deviceName);
            if (!sameObserver) {
                cleanup(capture);
                return;
            }
            Binding previous;
            synchronized (lifecycleLock) {
                if (!isCurrentLocked(binding) || binding.failed) return;
                previous = capture.failed ? null : capture.binding;
                if (previous != null) {
                    binding.resources = capture;
                    capture.binding = binding;
                }
            }
            if (previous == null) {
                cleanup(capture);
                return;
            }
            capture.gate.bind(binding);
            acknowledge(previous);
            if (!isCurrent(binding)) return;
            if (!capture.screen) {
                CameraEnumerator enumerator = Camera2Enumerator.isSupported(ApplicationLoader.applicationContext)
                        ? new Camera2Enumerator(ApplicationLoader.applicationContext) : new Camera1Enumerator();
                String name = cameraName(binding.deviceName, enumerator);
                if (!name.equals(capture.cameraName)) {
                    if (binding.state != Instance.VIDEO_STATE_ACTIVE) {
                        cleanup(capture);
                        return;
                    }
                    applyState(binding, capture);
                    if (!isCurrent(binding)) return;
                    if (binding.state != Instance.VIDEO_STATE_ACTIVE || capture.appliedState != Instance.VIDEO_STATE_ACTIVE) {
                        cleanup(capture);
                        return;
                    }
                    switchCamera(binding, capture, name);
                }
            }
        } else if (capture == null) {
            if ("screen".equals(binding.deviceName) && binding.state != Instance.VIDEO_STATE_ACTIVE) return;
            capture = new CaptureResources(binding);
            binding.resources = capture;
            resources = capture;
            capture.gate.bind(binding);
            createResources(binding, capture);
        }
        if (isActive(binding, capture)) applyState(binding, capture);
    }

    private void switchCamera(Binding binding, CaptureResources capture, String name) {
        if (!isActive(binding, capture)) return;
        ((CameraVideoCapturer) capture.capturer).switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean front) {
                if (resources != capture || capture.stopping || capture.cleaned) return;
                capture.cameraName = name;
                capture.frontCamera = front;
                postUi(() -> {
                    VoIPService owner = currentService(binding, capture);
                    if (owner != null) owner.setSwitchingCamera(false, front);
        });
    }

            @Override
            public void onCameraSwitchError(String errorDescription) {
                postUi(() -> {
                    VoIPService owner = currentService(binding, capture);
                    if (owner != null && owner.isSwitchingCamera()) {
                        owner.setSwitchingCamera(false, capture.frontCamera);
                    }
                });
            }
        }, name);
    }

    private void failCamera(Binding binding, CaptureResources capture) {
        final Binding failedBinding;
        synchronized (lifecycleLock) {
            if (capture != null) {
                if (resources != capture || capture.stopping || capture.cleaned || capture.failed) return;
                capture.failed = true;
            }
            failedBinding = capture == null ? binding : capture.binding;
            if (!isCurrentLocked(failedBinding) || failedBinding.failed || failedBinding.resources != capture) return;
            failedBinding.failed = true;
        }
        if (!postUi(() -> {
            if (!isCurrent(failedBinding) || failedBinding.resources != capture) return;
            try {
                VoIPService owner = serviceOwner.get();
                if (owner != null && VoIPService.getSharedInstance() == owner && instance.get(0) == this
                        && owner.isSwitchingCamera()) {
                    owner.setSwitchingCamera(false, owner.isFrontFaceCamera());
                }
            } finally {
                onCaptureRetired(failedBinding.id);
            }
        })) handleRejectedQueue();
    }

    private void createResources(Binding binding, CaptureResources capture) {
        if (!isCurrent(binding)) return;
        if (capture.screen) {
            capture.capturer = new ScreenCapturerAndroid(projectionPermissionData, new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    postUi(() -> {
                        VoIPService service = currentService(binding, capture);
                        if (service != null) service.stopScreenCapture();
                    });
                }
            });
            Point size = getScreenCaptureSize();
            capture.width = size.x;
            capture.height = size.y;
            capture.helper = SurfaceTextureHelper.create("ScreenCapturerThread", eglBase.getEglBaseContext());
        } else {
            CameraEnumerator enumerator = Camera2Enumerator.isSupported(ApplicationLoader.applicationContext)
                    ? new Camera2Enumerator(ApplicationLoader.applicationContext) : new Camera1Enumerator();
            capture.cameraName = cameraName(binding.deviceName, enumerator);
            capture.frontCamera = enumerator.isFrontFacing(capture.cameraName);
            capture.capturer = enumerator.createCapturer(capture.cameraName, new CameraVideoCapturer.CameraEventsHandler() {
                @Override public void onCameraError(String errorDescription) { failCamera(null, capture); }
                @Override public void onCameraDisconnected() { failCamera(null, capture); }
                @Override public void onCameraFreezed(String errorDescription) {}
                @Override public void onCameraOpening(String cameraName) {}
                @Override public void onCameraClosed() {}

                @Override
                public void onFirstFrameAvailable() {
                    Binding owner = capture.binding;
                    boolean front = capture.frontCamera;
                    postUi(() -> {
                        VoIPService service = currentService(owner, capture);
                        if (service != null) {
                            if (front == "front".equals(owner.deviceName)
                                    && (service.isSwitchingCamera() || service.isFrontFaceCamera() != front)) {
                                service.setSwitchingCamera(false, front);
                            }
                            if (currentService(owner, capture) == service) service.onCameraFirstFrameAvailable();
                        }
                    });
                }
            });
            capture.width = CAPTURE_WIDTH;
            capture.height = CAPTURE_HEIGHT;
            capture.helper = SurfaceTextureHelper.create("VideoCapturerThread", eglBase.getEglBaseContext());
        }
        if (capture.capturer == null || capture.helper == null) {
            throw new IllegalStateException("Capture resources could not be created");
        }
        capture.capturer.initialize(capture.helper, ApplicationLoader.applicationContext, capture.gate);
        capture.initialized = true;
    }

    private void applyState(Binding binding, CaptureResources capture) {
        final int state = binding.state;
        if (!isActive(binding, capture) || state == capture.appliedState) return;
        if (state == Instance.VIDEO_STATE_ACTIVE) {
            capture.capturer.startCapture(capture.width, capture.height, CAPTURE_FPS);
            if (capture.screen) {
                capture.projection = ((ScreenCapturerAndroid) capture.capturer).getMediaProjection();
                if (capture.projection != null && isCurrent(binding)) {
                    synchronized (deviceAudioLock) {
                        WebRtcAudioRecord audio = WebRtcAudioRecord.Instance;
                        if (audio != null) {
                            if (deviceAudioOwner != null && deviceAudioOwner.audioRecord != null) {
                                deviceAudioOwner.audioRecord.stopDeviceAudioRecord();
                            }
                            capture.audioRecord = audio;
                            deviceAudioOwner = capture;
                            audio.initDeviceAudioRecord(capture.projection);
                        }
                    }
                }
            }
        } else {
            if (capture.screen) {
                cleanup(capture);
            } else {
                try {
                    capture.capturer.stopCapture();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Capture stop was interrupted", error);
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Capture stop was interrupted");
                }
            }
            capture.projection = null;
            stopDeviceAudio(capture);
        }
        capture.appliedState = state;
    }

    private static void stopDeviceAudio(CaptureResources capture) {
        synchronized (deviceAudioLock) {
            if (deviceAudioOwner == capture) {
                if (capture.audioRecord != null) capture.audioRecord.stopDeviceAudioRecord();
                deviceAudioOwner = null;
            }
        }
    }

    private void cleanup(CaptureResources capture) {
        if (capture.stopping || capture.cleaned) return;
        capture.stopping = true;
        capture.cleanupFailed = false;
        try {
            capture.gate.close();
            stopDeviceAudio(capture);
            final VideoCapturer capturer = capture.capturer;
            final SurfaceTextureHelper helper = capture.helper;
            if (capture.initialized) {
                capturer.stopCapture();
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Capture cleanup stop was interrupted");
                }
            }
            capture.projection = null;
            if (helper == null) {
                if (capturer != null) capturer.dispose();
                finishCleanup(capture);
                return;
            }
            helper.stopListening();
            if (!helper.getHandler().post(() -> {
                try {
                    if (capturer != null) capturer.dispose();
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("Capture disposal was interrupted");
                    }
                    helper.dispose();
                    Handler captureHandler;
                    synchronized (lifecycleLock) {
                        captureHandler = handler;
                    }
                    if (captureHandler == null || !captureHandler.post(() -> finishCleanup(capture))) {
                        handleRejectedQueue();
                    }
                } catch (Throwable error) {
                    cleanupFailed(capture, error);
                }
            })) {
                cleanupFailed(capture, new IllegalStateException("Capture helper rejected its stop barrier"));
            }
        } catch (Throwable error) {
            cleanupFailed(capture, error);
        }
    }

    private void cleanupFailed(CaptureResources capture, Throwable error) {
        FileLog.e(error);
        capture.cleanupFailed = true;
        capture.stopping = false;
    }

    private void finishCleanup(CaptureResources capture) {
        capture.cleaned = true;
        capture.capturer = null;
        capture.helper = null;
        capture.audioRecord = null;
        if (resources == capture) resources = null;
        for (Binding binding : snapshotBindings()) {
            if (binding.resources == capture) {
                binding.resources = null;
                if (binding.retired) acknowledge(binding);
            }
        }
        maybeQuit();
        synchronized (lifecycleLock) {
            if (bindings.isEmpty()) return;
        }
        scheduleWork();
    }

    private void maybeQuit() {
        HandlerThread captureThread = null;
        synchronized (lifecycleLock) {
            if (destroyed && bindings.isEmpty() && resources == null && thread != null) {
                captureThread = thread;
                thread = null;
                handler = null;
            }
        }
        if (captureThread != null) captureThread.quitSafely();
    }

    private void onStateChanged(long bindingId, int state) {
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(bindingId);
            if (!isCurrentLocked(binding)) return;
            binding.state = state;
        }
        scheduleWork();
    }

    private void onAspectRatioRequested(float aspectRatio) {
    }

    private void onDestroy() {
        synchronized (lifecycleLock) {
            destroyed = true;
            currentBinding = null;
            for (Binding binding : bindings.values()) binding.retired = true;
        }
        scheduleWork();
    }
    public static void checkScreenCapturerSize() {
        VideoCapturerDevice device = instance.get(1);
        if (device == null) return;
        CaptureResources capture = device.resources;
        Binding binding = capture == null ? null : capture.binding;
        if (capture == null || !device.isActive(binding, capture)) return;
        Point size = getScreenCaptureSize();
        if (capture.width == size.x && capture.height == size.y) return;
        postUi(() -> {
            if (!device.isActive(binding, capture)) return;
            Handler captureHandler;
            synchronized (device.lifecycleLock) {
                captureHandler = device.handler;
            }
            if (captureHandler != null && !captureHandler.post(() -> {
                if (!device.isActive(binding, capture)) return;
                try {
                    capture.width = size.x;
                    capture.height = size.y;
                    capture.capturer.changeCaptureFormat(size.x, size.y, CAPTURE_FPS);
                } catch (Throwable error) {
                    FileLog.e(error);
                    synchronized (device.lifecycleLock) {
                        binding.retired = true;
                        if (device.currentBinding == binding) device.currentBinding = null;
                    }
                    device.cleanup(capture);
                    device.scheduleWork();
                }
            })) device.handleRejectedQueue();
            });
        }
    public static MediaProjection getMediaProjection() {
        VideoCapturerDevice device = instance.get(1);
        if (device == null) return null;
        CaptureResources capture = device.resources;
        return capture != null && device.isActive(capture.binding, capture) ? capture.projection : null;
    }

    private static Point getScreenCaptureSize() {
        return getScreenCaptureSize(/* 4 */ 16);
    }
    private static Point getScreenCaptureSize(int roundBy) {
        WindowManager wm = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);

        float aspect;
        if (size.x > size.y) {
            aspect = size.y / (float) size.x;
        } else {
            aspect = size.x / (float) size.y;
        }
        int dx = -1;
        int dy = -1;
        for (int a = 1; a <= 100; a++) {
            float val = a * aspect;
            if (val == (int) val) {
                if (size.x > size.y) {
                    dx = a;
                    dy = (int) (a * aspect);
                } else {
                    dy = a;
                    dx = (int) (a * aspect);
                }
                break;
            }
        }
        if (dx != -1 && aspect != 1) {
            while (size.x > 1000 || size.y > 1000 || size.x % roundBy != 0 || size.y % roundBy != 0) {
                size.x -= dx;
                size.y -= dy;
                if (size.x < 800 && size.y < 800) {
                    dx = -1;
                    break;
                }
            }
        }
        if (dx == -1 || aspect == 1) {
            float scale = Math.max(size.x / 970.0f, size.y / 970.0f);
            size.x = (int) Math.ceil((size.x / scale) / (float) roundBy) * roundBy;
            size.y = (int) Math.ceil((size.y / scale) / (float) roundBy) * roundBy;
        }
        return size;
    }


    private static EglBase.Context getSharedEGLContext() {
        if (eglBase == null) {
            eglBase = EglBase.create(null, EglBase.CONFIG_PLAIN);
        }
        return eglBase != null ? eglBase.getEglBaseContext() : null;
    }

    public static EglBase getEglBase() {
        if (eglBase == null) {
            eglBase = EglBase.create(null, EglBase.CONFIG_PLAIN);
        }
        return eglBase;
    }

    private static native void nativeReleaseSourceLease(long bindingId);
}
