/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.view.Choreographer;

import org.json.JSONObject;
import org.telegram.messenger.AppGlobalConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WidgetSandboxSession {
    public interface Listener {
        void onFrame(WidgetSandboxSession session);

        void onGesture(boolean x, boolean y, boolean hold);

        default void onCursor(String cursor) {}

        default void onRequest(long id, String data, boolean wallet) {}

        default void onWallet(long id, JSONObject call) {}

        default void onCopyText(String text) {}

        default void onWatchPublicData(boolean on) {}

        void onDiagnostics(JSONObject diagnostics);

        default void onAction(JSONObject action) {}

        default void onStorageCommit(String area, JSONObject values) {}

        void onFailure(String message);
        default void onEngineDied() {
            onFailure("Widget engine stopped");
        }
    }

    public static final int REQUEST_ANSWERED = 0, REQUEST_TIMEOUT = 1, REQUEST_RATE_LIMITED = 2, REQUEST_FAILED = 3,
            REQUEST_REJECTED = 4, REQUEST_BAD_REQUEST = 5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final WidgetSandboxBackend backend = new WidgetSandboxBackend(this);
    private final WidgetEngine engine = WidgetEngine.current();
    private volatile WidgetGate gate = new WidgetGate(null);
    private final Context context;
    private final String html;
    private final int cpuMultiplier;
    private final String locale, storage;
    private volatile String sdk = "{}";
    private volatile String safeArea;
    final float density;
    private final long clockOrigin = System.nanoTime();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean tickInFlight = new AtomicBoolean();
    private final AtomicBoolean frameWanted = new AtomicBoolean();
    private final AtomicBoolean animating = new AtomicBoolean();
    private final AtomicBoolean animationDue = new AtomicBoolean();
    private final Object publicationLock = new Object();
    private volatile int lastFrameHeight;
    private volatile boolean framed;
    private volatile long session;
    private volatile boolean closed;
    private volatile java.util.function.BooleanSupplier owner = () -> true;

    void setOwner(java.util.function.BooleanSupplier owner) {
        this.owner = owner;
    }

    private boolean isOwned() {
        return owner.getAsBoolean();
    }
    private volatile boolean loaded;
    private volatile boolean rendered, renderedHeight;
    private volatile boolean anchorBottom;
    private String theme;
    private boolean dark, scriptsReleased, frameScheduled, repaintPending;
    private volatile boolean visible;
    private int widthPx;
    private Frame current, pending;
    private JSONObject pendingDiagnostics;
    private boolean publicationScheduled;
    private Listener listener;
    private volatile Utilities.Callback<String> conformanceListener;
    private volatile Utilities.Callback2<Integer, String> stressListener;
    private volatile Utilities.Callback<float[]> workListener;
    private float[] nativeWork;
    private volatile Utilities.Callback<String> attackListener;
    private Listener storageListener;
    private String failure;

    volatile byte[] lastList, lastComposition;

    public float layoutMs, recordMs;
    public int layoutPasses, nativeBuilds, displayOps, records;
    public JSONObject diagnostics = new JSONObject();

    public String dump() {
        byte[] list = lastList, composition = lastComposition;
        if (list == null) return null;
        return "{\"list\":"
                + WidgetDisplayList.toJson(list)
                + ",\"composition\":"
                + (composition == null ? "null" : new String(composition, StandardCharsets.UTF_8))
                + "}";
    }

    private final Runnable publish = this::publishPending;
    private final Runnable wake = this::scheduleFrame;
    private final Runnable animationWake =
            () -> {
                animationDue.set(true);
                scheduleFrame();
            };
    private final Choreographer.FrameCallback frameCallback =
            nanos -> {
                frameScheduled = false;
                long id = session;
                if (closed || !isOwned() || id == 0 || !tickInFlight.compareAndSet(false, true)) return;
                boolean animate = visible && (animationDue.getAndSet(false) | animating.get());
                boolean frame = visible && frameWanted.getAndSet(false);
                if (frame) frameSentAt = nanos;
                engine.tick(
                        id, (nanos - clockOrigin) / 1e6f, frame, animate, dirty.getAndSet(false));
            };
    private long frameSentAt;
    private final Runnable frameRequested =
            () -> {
                long id = session;
                long now = System.nanoTime();
                long period = (long) (1e9 / Math.max(30, AndroidUtilities.screenRefreshRate));
                if (closed || !isOwned() || id == 0 || !visible || !frameWanted.get() || now - frameSentAt < period * 9 / 10
                        || !tickInFlight.compareAndSet(false, true)) {
                    scheduleFrame();
                    return;
                }
                frameWanted.set(false);
                frameSentAt = now;
                engine.tick(id, (now - clockOrigin) / 1e6f, true, false, false);
            };

    public WidgetSandboxSession(Context context, String html, boolean dark, int cpuMultiplier) {
        this(context, html, WidgetSandboxTheme.scheme(dark), cpuMultiplier, null, null);
    }

    public WidgetSandboxSession(Context context, String html, String theme, int cpuMultiplier, String locale, String storage) {
        if (cpuMultiplier < 1 || cpuMultiplier > 4)
            throw new IllegalArgumentException("CPU multiplier must be 1..4");
        this.context = context.getApplicationContext();
        this.html = html;
        this.theme = theme;
        this.dark = theme.contains("\"dark\"");
        this.cpuMultiplier = cpuMultiplier;
        this.locale = locale == null ? "en-US" : locale;
        this.storage = storage == null ? "{}" : storage;
        density = context.getResources().getDisplayMetrics().density;
    }

    public void setSdk(String json) {
        if (!isOwned() || json == null) return;
        sdk = json;
        try {
            gate = new WidgetGate(new JSONObject(json).optJSONObject("context"));
        } catch (Exception e) {
            gate = new WidgetGate(null);
        }
    }

    void setSafeArea(String area) {
        if (closed || !isOwned() || area == null || area.equals(safeArea)) return;
        safeArea = area;
        long id = session;
        if (id != 0) engine.safeArea(id, area.getBytes(StandardCharsets.UTF_8));
    }

    void setData(String data) {
        long id = session;
        if (!closed && isOwned() && id != 0) engine.data(id, data.getBytes(StandardCharsets.UTF_8));
    }

    void answerRequest(long request, int failure, String result) {
        gate.answered(failure == REQUEST_ANSWERED);
        long id = session;
        if (!closed && isOwned() && id != 0) engine.answerRequest(id, request, failure, result == null ? null : result.getBytes(StandardCharsets.UTF_8));
    }

    public static void setInProcessEngine(boolean inProcess) {
        WidgetEngine.isolated = !(inProcess && org.telegram.messenger.BuildVars.DEBUG_PRIVATE_VERSION);
    }

    public static void prewarmEngine(Context context) {
        if (!WidgetEngine.isolated || disabledByConfig()) return;
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null || activityManager.isLowRamDevice()) return;
        try {
            WidgetRemoteEngine.INSTANCE.prewarm(context);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        if (!foregroundWatched) {
            org.telegram.ui.Components.ForegroundDetector detector = org.telegram.ui.Components.ForegroundDetector.getInstance();
            if (detector != null) {
                foregroundWatched = true;
                detector.addListener(new org.telegram.ui.Components.ForegroundDetector.Listener() {
                    @Override
                    public void onBecameForeground() {
                        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> prewarmEngine(context));
                    }

                    @Override
                    public void onBecameBackground() {
                    }
                });
            }
        }
    }

    private static boolean foregroundWatched;

    public static boolean disabledByConfig() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && AppGlobalConfig.getInstance(a).widgetsDisabled.get()) {
                return true;
            }
        }
        return false;
    }

    public void start(int widthPx, boolean holdScripts) throws Exception {
        if (closed || !isOwned()) return;
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Start WidgetSandboxSession off UI");
        if (disabledByConfig()) throw new IllegalStateException("Widgets are disabled by the app config");
        if (this.widthPx == 0) this.widthPx = widthPx;
        Trace.beginSection("WidgetSandbox:Create");
        long id = 0;
        try {
            if (closed || !isOwned()) return;
            final String createdSafeArea = safeArea;
            String sdk = this.sdk;
            if (createdSafeArea != null) sdk = new JSONObject(sdk).put("contentSafeArea", new JSONObject(createdSafeArea)).toString();
            id =
                    engine.create(
                            context,
                            html,
                            theme,
                            widthPx / density,
                            density,
                            cpuMultiplier,
                            locale,
                            storage,
                            sdk,
                            LocaleController.getString(R.string.WidgetStopped),
                            visible,
                            backend);
            final String createdTheme = theme;
            final long created = id;
            main.post(
                    () -> {
                        if (closed || !isOwned()) {
                            destroyInBackground(created);
                            return;
                        }
                        session = created;
                        if (this.widthPx != widthPx && this.widthPx > 0)
                            engine.width(created, this.widthPx / density);
                        if (!this.theme.equals(createdTheme))
                            engine.theme(created, this.theme.getBytes(StandardCharsets.UTF_8));
                        if (safeArea != null && !safeArea.equals(createdSafeArea))
                            engine.safeArea(created, safeArea.getBytes(StandardCharsets.UTF_8));
                        scriptsReleased = true;
                        engine.visible(created, visible);
                        scheduleFrame();
                    });
        } catch (Exception error) {
            if (id != 0) destroyInBackground(id);
            main.post(
                    () -> {
                        failure = error.toString();
                        if (!closed && isOwned() && listener != null) listener.onFailure(failure);
                    });
            throw error;
        } finally {
            Trace.endSection();
        }
    }

    void setAttackListener(Utilities.Callback<String> listener) {
        attackListener = listener;
    }

    void setStressListener(Utilities.Callback2<Integer, String> listener) {
        stressListener = listener;
    }

    void setWorkListener(Utilities.Callback<float[]> listener) {
        workListener = listener;
    }

    void onRecorded(float parseMs, float canvasMs) {
        Utilities.Callback<float[]> listener = workListener;
        float[] work = nativeWork;
        nativeWork = null;
        if (listener == null || work == null) return;
        work[4] = parseMs;
        work[5] = canvasMs;
        listener.run(work);
    }

    void setConformanceListener(Utilities.Callback<String> listener) {
        conformanceListener = listener;
    }

    public void setListener(Listener listener) {
        if (!isOwned()) return;
        this.listener = listener;
        if (listener != null) storageListener = listener;
    }

    public void releaseScripts() {
        if (closed || !isOwned()) return;
        scriptsReleased = true;
    }

    public boolean scriptsReleased() {
        return scriptsReleased;
    }

    public boolean loaded() {
        return loaded;
    }

    public boolean hasLoadedFrame() {
        Frame frame = current;
        return frame != null && (frame.loaded || renderedHeight);
    }

    public void setWidthPx(int width) {
        if (width <= 0 || width == widthPx || closed || !isOwned()) return;
        widthPx = width;
        long id = session;
        if (id != 0) engine.width(id, width / density);
    }

    public int widthPx() {
        return widthPx;
    }

    public void setDarkTheme(boolean dark) {
        if (this.dark == dark || closed || !isOwned()) return;
        setTheme(WidgetSandboxTheme.scheme(dark));
    }

    public void setTheme(String theme) {
        if (closed || !isOwned() || theme == null || theme.equals(this.theme)) return;
        this.theme = theme;
        dark = theme.contains("\"dark\"");
        long id = session;
        if (id != 0) engine.theme(id, theme.getBytes(StandardCharsets.UTF_8));
    }

    public void setVisible(boolean visible) {
        if (closed || !isOwned() || this.visible == visible) return;
        this.visible = visible;
        long id = session;
        if (id != 0) engine.visible(id, visible);
        if (visible) scheduleFrame();
    }

    public String failure() {
        return failure;
    }

    Frame frame() {
        return current;
    }

    void ensureDisplayList() {
        if (closed || !isOwned() || session == 0 || repaintPending) return;
        Frame frame = current;
        if (frame != null && frameMatchesWidth(frame) && frame.recorded()) return;
        repaintPending = true;
        engine.repaint(session);
    }

    void touch(int action, float x, float y, int pointer, boolean primary) {
        touch(action, x, y, pointer, primary, false, 0, 0);
    }

    void touch(int action, float x, float y, int pointer, boolean primary, boolean mouse, int modifiers, int button) {
        if (action == 0 && visible) gate.press();
        long id = session;
        if (!closed && isOwned() && id != 0) engine.touch(id, action, x, y, pointer, primary, mouse, modifiers, button);
    }

    void hover(int action, float x, float y, int modifiers) {
        long id = session;
        if (!closed && isOwned() && id != 0) engine.hover(id, action, x, y, modifiers);
    }

    private boolean frameMatchesWidth(Frame frame) {
        return widthPx == 0 || Math.abs(frame.width - widthPx) <= 1;
    }

    private void scheduleFrame() {
        if (closed || !isOwned()
                || session == 0
                || frameScheduled
                || tickInFlight.get()
                || !(dirty.get()
                        || visible
                                && (frameWanted.get() || animating.get() || animationDue.get())))
            return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    void onWorkerSignal(int kind, String message) {
        if (kind == 8) {
            if (!WidgetGate.storageCommit(message)) return;
            main.post(
                    () -> {
                        if (!isOwned() || storageListener == null) return;
                        try {
                            JSONObject commit = new JSONObject(message);
                            JSONObject values = commit.optJSONObject("values");
                            storageListener.onStorageCommit(commit.optString("area"), values == null ? new JSONObject() : values);
                        } catch (Exception ignored) {
                        }
                    });
            return;
        }
        if (kind == 10) {
            Utilities.Callback<String> report = attackListener;
            if (report != null) report.run(message);
            return;
        }
        if (closed || !isOwned()) return;
        if (kind == 12 || kind == 19) {
            long id;
            String data = null;
            JSONObject call = null;
            boolean wallet = false;
            try {
                JSONObject request = new JSONObject(message);
                id = request.getLong("id");
                if (kind == 19) call = request.getJSONObject("call");
                else {
                    data = request.getString("data");
                    wallet = request.optBoolean("wallet");
                }
            } catch (Exception ignored) {
                return;
            }
            boolean allowed = kind == 19 ? gate.walletCall(call.toString(), visible) : gate.request(data, wallet, visible);
            if (!allowed) {
                long engineId = session;
                if (engineId != 0) engine.answerRequest(engineId, id, REQUEST_FAILED, null);
                return;
            }
            final String requestData = data;
            final JSONObject walletCall = call;
            final boolean withWallet = wallet;
            main.post(
                    () -> {
                        if (closed || !isOwned() || listener == null) return;
                        if (kind == 19) listener.onWallet(id, walletCall);
                        else listener.onRequest(id, requestData, withWallet);
                    });
            return;
        }
        if (kind == 13 || kind == 14) {
            if (kind == 13 && !(WidgetGate.copyText(message) && gate.privileged(visible))) return;
            main.post(
                    () -> {
                        if (closed || !isOwned() || listener == null) return;
                        if (kind == 13) listener.onCopyText(message);
                        else listener.onWatchPublicData("1".equals(message));
                    });
            return;
        }
        if (kind == 11) {
            main.post(
                    () -> {
                        if (!closed && isOwned() && listener != null) listener.onCursor(message);
                    });
            return;
        }
        if (kind == 18) {
            try {
                JSONObject work = new JSONObject(message);
                nativeWork = new float[] {
                        (float) work.optDouble("receive"), (float) work.optDouble("animate"),
                        (float) work.optDouble("paint"), (float) work.optDouble("serialize"), 0, 0,
                        (float) work.optDouble("ticks"), (float) work.optDouble("framesSent"),
                        (float) work.optDouble("opsLatency"), (float) work.optDouble("validate", -1),
                        (float) work.optDouble("hop", -1)};
            } catch (Exception ignored) {
            }
            return;
        }
        if (kind == 16 || kind == 17) {
            Utilities.Callback2<Integer, String> stress = stressListener;
            if (stress != null) stress.run(kind, message);
            return;
        }
        if (kind == 9) {
            Utilities.Callback<String> report = conformanceListener;
            if (report != null) main.post(() -> report.run(message));
            return;
        }
        if (kind == 6) {
            tickInFlight.set(false);
            main.post(wake);
        } else if (kind == 1) {
            frameWanted.set(true);
            main.post(frameRequested);
        } else if (kind == 2) {
            dirty.set(true);
            main.removeCallbacks(wake);
            main.post(wake);
        } else if (kind == 3) {
            boolean x, y, hold;
            try {
                JSONObject claim = new JSONObject(message);
                x = claim.optBoolean("x");
                y = claim.optBoolean("y");
                hold = claim.optBoolean("hold");
            } catch (Exception ignored) {
                return;
            }
            main.post(
                    () -> {
                        if (!closed && isOwned() && listener != null) listener.onGesture(x, y, hold);
                    });
        } else if (kind == 4) {
            try {
                JSONObject value = new JSONObject(message);
                if (value.optBoolean("loaded")) loaded = true;
                anchorBottom = "bottom".equals(value.optString("resizeAnchor"));
                if (value.optBoolean("rendered") && !rendered) {
                    double height = value.optDouble("height", -1);
                    double last = Math.min(600, Math.max(50, lastFrameHeight / density));
                    if (framed && height > 0 && Math.abs(last - height) <= 1) renderedHeight = true;
                    rendered = true;
                }
                synchronized (publicationLock) {
                    if (closed || !isOwned()) return;
                    pendingDiagnostics = value;
                    schedulePublicationLocked();
                }
            } catch (Exception ignored) {
            }
        } else if (kind == 5) {
            JSONObject action;
            try {
                action = new JSONObject(message);
                        } catch (Exception ignored) {
                return;
            }
            String actionKind = action.optString("kind");
            boolean allowed;
            if ("openLink".equals(actionKind) || "openTelegramLink".equals(actionKind)) {
                allowed = WidgetGate.link(action.optString("url"), "openTelegramLink".equals(actionKind)) && gate.privileged(visible);
            } else if ("haptic".equals(actionKind)) {
                allowed = gate.haptic(visible);
            } else {
                allowed = "ready".equals(actionKind);
            }
            if (!allowed) return;
            main.post(
                    () -> {
                        if (!closed && isOwned() && listener != null) listener.onAction(action);
                    });
        } else if (kind == 7) {
            float delay;
            try {
                delay = Float.parseFloat(message);
            } catch (NumberFormatException error) {
                return;
            }
            animating.set(delay == 0);
            main.post(
                    () -> {
                        if (closed || !isOwned()) return;
                        main.removeCallbacks(animationWake);
                        if (delay > 0) main.postDelayed(animationWake, (long) Math.ceil(delay));
                        else if (delay == 0) scheduleFrame();
                    });
        }
    }

    void onWorkerFrame(
            WidgetFrameNode node,
            Layer[] layers,
            double clockOffset,
            int width,
            int height,
            float layout,
            float record,
            int passes,
            int builds,
            int ops,
            WidgetText text) {
        boolean loaded = rendered;
        synchronized (publicationLock) {
            if (closed || !isOwned()) {
                node.discard();
                return;
            }
            if (pending != null) pending.node.discard();
            pending =
                    new Frame(
                            node, layers, clockOffset, width, height, layout, record, passes,
                            builds, ops, text, loaded, anchorBottom);
            schedulePublicationLocked();
        }
        lastFrameHeight = height;
        framed = true;
    }

    private void schedulePublicationLocked() {
        if (publicationScheduled) return;
        publicationScheduled = true;
        main.post(publish);
    }

    private void publishPending() {
        Frame frame;
        JSONObject snapshot;
        synchronized (publicationLock) {
            frame = pending;
            pending = null;
            snapshot = pendingDiagnostics;
            pendingDiagnostics = null;
            publicationScheduled = false;
        }
        if (closed || !isOwned()) {
            if (frame != null) frame.node.discard();
            return;
        }
        if (frame != null && !frameMatchesWidth(frame)) {
            frame.node.discard();
            frame = null;
            repaintPending = false;
        }
        if (frame != null) {
            current = frame;
            repaintPending = false;
            layoutMs = frame.layout;
            recordMs = frame.record;
            layoutPasses = frame.passes;
            nativeBuilds = frame.builds;
            displayOps = frame.ops;
            records++;
            if (listener != null) listener.onFrame(this);
        }
        if (snapshot != null) {
            diagnostics = snapshot;
            if (listener != null) listener.onDiagnostics(snapshot);
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        listener = null;
        main.removeCallbacks(wake);
        main.removeCallbacks(publish);
        main.removeCallbacks(animationWake);
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frameCallback);
        frameScheduled = false;
        synchronized (publicationLock) {
            if (pending != null) pending.node.discard();
            pending = null;
            pendingDiagnostics = null;
        }
        long id = session;
        session = 0;
        if (id != 0) destroyInBackground(id);
    }

    void retire() {
        storageListener = null;
        close();
    }

    public boolean closed() {
        return closed;
    }

    void onEngineDied() {
        main.post(
                () -> {
                    if (closed || !isOwned() || session == 0) return;
                    session = 0;
                    tickInFlight.set(false);
                    failure = "Widget engine stopped";
                    if (listener != null) listener.onEngineDied();
                });
    }

    private void destroyInBackground(long id) {
        WidgetSandboxResources.IO.execute(
                () -> {
                    Trace.beginSection("WidgetSandbox:Destroy");
                    try {
                        engine.destroy(id);
                    } finally {
                        Trace.endSection();
                    }
                });
    }

    public static final class Layer {
        public final WidgetFrameNode node;
        public final float[] clip, clipRadii;
        public final double start, duration;
        public final boolean repeat;
        public final float[] keys;

        Layer(
                WidgetFrameNode node,
                float[] clip,
                float[] clipRadii,
                double start,
                double duration,
                boolean repeat,
                float[] keys) {
            this.node = node;
            this.clip = clip;
            this.clipRadii = clipRadii;
            this.start = start;
            this.duration = duration;
            this.repeat = repeat;
            this.keys = keys;
        }
    }

    public static final class Frame {
        public final WidgetFrameNode node;
        public final Layer[] layers;
        public final double clockOffset;
        public final int width, height, passes, builds, ops;
        final float layout, record;
        public final WidgetText text;
        final boolean loaded;
        final boolean anchorBottom;

        Frame(
                WidgetFrameNode node,
                Layer[] layers,
                double clockOffset,
                int width,
                int height,
                float layout,
                float record,
                int passes,
                int builds,
                int ops,
                WidgetText text,
                boolean loaded,
                boolean anchorBottom) {
            this.text = text;
            this.loaded = loaded;
            this.anchorBottom = anchorBottom;
            this.node = node;
            this.layers = layers;
            this.clockOffset = clockOffset;
            this.width = width;
            this.height = height;
            this.layout = layout;
            this.record = record;
            this.passes = passes;
            this.builds = builds;
            this.ops = ops;
        }

        public boolean recorded() {
            if (!node.recorded()) return false;
            for (Layer layer : layers) if (!layer.node.recorded()) return false;
            return true;
        }
    }
}
