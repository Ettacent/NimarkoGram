/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.content.Context;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.WidgetSandboxPlayer;

public final class WidgetSandboxMessage implements WidgetSandboxSession.Listener {
    public interface Events {
        void onDiagnostics(WidgetSandboxMessage message, JSONObject diagnostics);

        void onAction(WidgetSandboxMessage message, JSONObject action);

        void onFailure(WidgetSandboxMessage message, String error);

        default void onResized(WidgetSandboxMessage message) {}

        default void onLoadedFrame(WidgetSandboxMessage message) {}

        default void onStorageCommit(WidgetSandboxMessage message, String area, JSONObject values) {}

        default void onRequest(WidgetSandboxMessage message, long id, String data, boolean wallet) {}

        default void onWallet(WidgetSandboxMessage message, long id, JSONObject call) {}

        default void onCopyText(WidgetSandboxMessage message, String text) {}

        default void onWatchPublicData(WidgetSandboxMessage message) {}
    }

    public static final int PLACEHOLDER_DP = 160;

    public final int index;
    public final String file;
    public final String title;
    private final String html;
    private final Context context;
    private final int cpuMultiplier;
    private Events events;
    private WidgetSandboxSession session;
    private WidgetSandboxSession.Frame snapshot;
    private boolean snapshotLoaded;
    private long contentRevealStart = -1;
    private static final long CONTENT_REVEAL_MS = 180;
    private SentPreview sentPreview;

    private static final class SentPreview {
        final WidgetSandboxSession.Frame frame;
        final String theme, locale, data, contextJson, safeArea;
        final int width;
        final boolean dark;
        final double time;

        SentPreview(WidgetSandboxMessage source, WidgetSandboxSession.Frame frame) {
            this.frame = frame;
            theme = source.theme;
            locale = source.locale;
            data = source.data;
            contextJson = source.contextJson;
            safeArea = source.safeArea;
            width = source.widthPx;
            dark = source.dark;
            time = System.nanoTime() / 1e9 + frame.clockOffset;
        }
    }
    private WidgetSandboxPlayer player;
    private int heightPx, widthPx;
    private int frameWidthPx;
    private boolean anchorBottom;
    private boolean dark, visible, active, paused;
    private int sessions;
    private int loadedFrameSession;
    private String theme, locale, storage;
    private String data = "{}", contextJson = "{}";
    private volatile String safeArea;
    private boolean safeAreaExact, safeAreaOver;
    private boolean watchesPublicData;
    private volatile boolean retired;
    private volatile java.util.function.BooleanSupplier owner = () -> true;

    void setOwner(java.util.function.BooleanSupplier owner) {
        this.owner = owner;
    }

    private boolean isCurrent() {
        return !retired && owner.getAsBoolean();
    }

    public WidgetSandboxMessage(
            Context context, int index, String file, String title, String html, boolean dark,
            int cpuMultiplier) {
        this.context = context.getApplicationContext();
        this.index = index;
        this.file = file;
        this.title = title;
        this.html = html;
        this.dark = dark;
        this.cpuMultiplier = cpuMultiplier;
    }

    private WidgetSandboxSession newSession() {
        WidgetSandboxSession created = theme == null && locale == null && storage == null
                ? new WidgetSandboxSession(context, html, dark, cpuMultiplier)
                : new WidgetSandboxSession(context, html, theme != null ? theme : WidgetSandboxTheme.scheme(dark), cpuMultiplier, locale, storage);
        created.setOwner(this::isCurrent);
        created.setSdk("{\"data\":" + data + ",\"context\":" + contextJson + "}");
        created.setSafeArea(safeArea);
        return created;
    }

    public void setContext(String context) {
        if (!isCurrent()) return;
        if (context != null) contextJson = context;
    }

    public void setData(String data) {
        if (!isCurrent()) return;
        if (data == null || data.equals(this.data)) return;
        this.data = data;
        if (session != null) session.setData(data);
    }

    public synchronized void setSafeArea(float bottom, float corner, boolean exact) {
        if (!isCurrent()) return;
        boolean over = bottom > 0;
        if (!exact && safeAreaExact && over == safeAreaOver) return;
        safeAreaExact = exact;
        safeAreaOver = over;
        String area = safeAreaJson(bottom, corner);
        if (area.equals(safeArea)) return;
        safeArea = area;
        AndroidUtilities.runOnUIThread(() -> {
            if (isCurrent() && session != null) session.setSafeArea(safeArea);
        });
    }

    static String safeAreaJson(float bottom, float corner) {
        return String.format(java.util.Locale.US,
                "{\"top\":0,\"right\":0,\"bottom\":%.2f,\"left\":0,\"cornerLeft\":0,\"cornerRight\":%.2f}", bottom, corner);
    }

    public void answerRequest(long id, int failure, String result) {
        if (!isCurrent()) return;
        if (session != null) session.answerRequest(id, failure, result);
    }

    public boolean watchesPublicData() {
        return session != null && watchesPublicData;
    }

    public void setSdk(String locale, String storage) {
        if (!isCurrent()) return;
        this.locale = locale;
        this.storage = storage;
    }

    public void setEvents(Events events) {
        if (!isCurrent()) return;
        this.events = events;
    }

    public void activate() {
        if (!isCurrent()) return;
        if (active) return;
        if (WidgetSandboxSession.disabledByConfig()) return;
        active = true;
        if (session != null) {
            session.releaseScripts();
            return;
        }
        WidgetSandboxSession created = newSession();
        created.setListener(this);
        created.setVisible(visible && !paused);
        session = created;
        sessions++;
        int width = widthPx;
        WidgetSandboxResources.IO.execute(
                () -> {
                    try {
                        created.start(width, false);
                    } catch (Exception ignored) {
                    }
                });
    }

    public void unload() {
        active = false;
        if (session == null) return;
        WidgetSandboxSession.Frame frame = frame();
        if (frame != null && sentPreview == null) {
            snapshotLoaded = frame.loaded || session.hasLoadedFrame();
            snapshot = frame;
        }
        session.close();
        session = null;
        if (watchesPublicData) {
            watchesPublicData = false;
            if (events != null) events.onWatchPublicData(this);
        }
    }

    public void dropSnapshot() {
        if (session == null) {
            snapshot = null;
            sentPreview = null;
            contentRevealStart = -1;
        }
    }

    public void reload() {
        unload();
        snapshot = null;
        sentPreview = null;
        contentRevealStart = -1;
    }

    public void forget() {
        reload();
        heightPx = 0;
        frameWidthPx = 0;
        anchorBottom = false;
    }

    public void release() {
        unload();
        snapshot = null;
        sentPreview = null;
        contentRevealStart = -1;
        player = null;
        events = null;
    }

    void retire() {
        retired = true;
        events = null;
        if (session != null) session.retire();
        session = null;
        active = visible = watchesPublicData = false;
        snapshot = null;
        sentPreview = null;
        player = null;
        storage = theme = locale = null;
        data = contextJson = "{}";
        heightPx = frameWidthPx = 0;
        contentRevealStart = -1;
    }

    public WidgetSandboxSession.Frame frame() {
        if (!isCurrent()) return null;
        WidgetSandboxSession.Frame live = session == null ? null : session.frame();
        if (sentPreview != null) {
            if (!java.util.Objects.equals(sentPreview.data, data) || !java.util.Objects.equals(sentPreview.contextJson, contextJson)
                    || !java.util.Objects.equals(sentPreview.locale, locale) || !sentPreview.frame.recorded()) {
                sentPreview = null;
            } else if (widthPx <= 0 || theme == null || safeArea == null) {
                return live;
            } else if (!matchesSentPreview()
                    || live != null && live.width > 0 && live.height > 0 && session.hasLoadedFrame()
                            && live.recorded() && Math.abs(live.width - widthPx) <= 1) {
                sentPreview = null;
            } else {
                return sentPreview.frame;
            }
        }
        if (live != null && (snapshot == null || session.loaded())) return live;
        if (snapshot != null && Math.abs(snapshot.width - widthPx) <= 1) return snapshot;
        return live != null ? live : snapshot;
    }

    void inheritSentPreview(WidgetSandboxMessage source) {
        if (!isCurrent() || source == this || !source.isCurrent() || sentPreview != null || snapshot != null
                || !java.util.Objects.equals(data, source.data)
                || !java.util.Objects.equals(contextJson, source.contextJson)
                || !java.util.Objects.equals(locale, source.locale)
                || widthPx > 0 && Math.abs(widthPx - source.widthPx) > 1
                || theme != null && !java.util.Objects.equals(theme, source.theme)
                || safeArea != null && !java.util.Objects.equals(safeArea, source.safeArea)) return;
        WidgetSandboxSession.Frame current = session == null ? null : session.frame();
        if (current != null && current.width > 0 && current.height > 0 && hasLoadedFrame()
                && current.recorded() && Math.abs(current.width - widthPx) <= 1) return;
        WidgetSandboxSession.Frame frame = source.frame();
        if (frame == null || frame.width <= 0 || frame.height <= 0 || !frame.recorded() || !(frame.loaded || source.hasLoadedFrame())
                || source.theme == null || source.widthPx <= 0 || Math.abs(frame.width - source.widthPx) > 1) return;
        sentPreview = new SentPreview(source, frame);
        contentRevealStart = source.contentRevealStart;
        frameWidthPx = frame.width;
        heightPx = Math.max(1, frame.height);
        anchorBottom = frame.anchorBottom;
        if (player != null) player.onMessageFrame(true);
    }

    private boolean matchesSentPreview() {
        return Math.abs(sentPreview.width - widthPx) <= 1 && sentPreview.dark == dark
                && java.util.Objects.equals(sentPreview.theme, theme) && java.util.Objects.equals(sentPreview.locale, locale)
                && java.util.Objects.equals(sentPreview.data, data) && java.util.Objects.equals(sentPreview.contextJson, contextJson)
                && java.util.Objects.equals(sentPreview.safeArea, safeArea);
    }

    public double previewTime(WidgetSandboxSession.Frame frame) {
        return isCurrent() && sentPreview != null && sentPreview.frame == frame ? sentPreview.time : Double.NaN;
    }

    public boolean isDisplayReady(WidgetSandboxSession.Frame frame) {
        return isCurrent() && frame != null && frame.width > 0 && frame.height > 0 && frame.recorded()
                && (sentPreview != null && sentPreview.frame == frame
                    || frame == snapshot && snapshotLoaded
                    || session != null && frame == session.frame() && session.hasLoadedFrame());
    }

    public float contentAlpha(long now, boolean start, boolean animated) {
        if (!isCurrent()) return 0;
        if (contentRevealStart < 0) {
            if (!start) return 1;
            contentRevealStart = now;
        }
        if (!animated) contentRevealStart = now - CONTENT_REVEAL_MS;
        float progress = Math.max(0f, Math.min(1f, (now - contentRevealStart) / (float) CONTENT_REVEAL_MS));
        float remaining = 1f - progress;
        return 1f - remaining * remaining * remaining;
    }

    public void contentUnavailable() {
        if (isCurrent()) contentRevealStart = -1;
    }

    public boolean dark() {
        return dark;
    }

    public int heightPx() {
        return heightPx;
    }

    public int frameWidthPx() {
        return frameWidthPx;
    }

    public boolean anchorBottom() {
        return anchorBottom;
    }

    public boolean hasLoadedFrame() {
        return session != null && session.hasLoadedFrame();
    }

    public boolean hasTheme() {
        return theme != null;
    }

    public boolean isActive() {
        return active;
    }

    public boolean hasSession() {
        return session != null;
    }

    public boolean scriptsRunning() {
        return session != null && session.scriptsReleased();
    }

    public boolean hasSnapshot() {
        return snapshot != null;
    }

    public int sessions() {
        return sessions;
    }

    public WidgetSandboxSession session() {
        return session;
    }

    public void setWidthPx(int width) {
        if (!isCurrent()) return;
        if (width <= 0 || width == widthPx) return;
        widthPx = width;
        if (session != null) session.setWidthPx(width);
    }

    public void setDarkTheme(boolean dark) {
        if (!isCurrent()) return;
        this.dark = dark;
        if (session != null) session.setDarkTheme(dark);
    }

    public void setTheme(String theme) {
        if (!isCurrent()) return;
        if (theme == null || theme.equals(this.theme)) return;
        this.theme = theme;
        dark = theme.contains("\"dark\"");
        if (session != null) session.setTheme(theme);
    }

    void setPaused(boolean paused) {
        if (!isCurrent()) return;
        if (this.paused == paused) return;
        this.paused = paused;
        if (session != null) session.setVisible(visible && !paused);
    }

    public void setVisible(boolean visible) {
        if (!isCurrent()) return;
        this.visible = visible;
        if (session != null) {
            session.setVisible(visible && !paused);
            if (visible && !paused) session.ensureDisplayList();
        }
    }

    public void bind(WidgetSandboxPlayer player) {
        if (!isCurrent()) return;
        this.player = player;
    }

    public void unbind(WidgetSandboxPlayer player) {
        if (this.player == player) this.player = null;
    }

    public void ensureDisplayList() {
        if (!isCurrent()) return;
        if (session != null) session.ensureDisplayList();
    }

    public void touch(int action, float x, float y, int pointer, boolean primary, boolean mouse, int modifiers, int button) {
        if (!isCurrent()) return;
        if (session != null) session.touch(action, x, y, pointer, primary, mouse, modifiers, button);
    }

    public void hover(int action, float x, float y, int modifiers) {
        if (!isCurrent()) return;
        if (session != null) session.hover(action, x, y, modifiers);
    }

    public String failure() {
        return session == null ? null : session.failure();
    }

    @Override
    public void onFrame(WidgetSandboxSession from) {
        if (!isCurrent()) return;
        if (from != session) return;
        WidgetSandboxSession.Frame live = from.frame();
        if (live == null) return;
        if (sentPreview != null) {
            WidgetSandboxSession.Frame preview = sentPreview.frame;
            if (frame() == preview) return;
        }
        boolean shown = snapshot == null || from.loaded();
        if (!shown) return;
        snapshot = null;
        frameWidthPx = live.width;
        boolean anchored = live.anchorBottom != anchorBottom;
        anchorBottom = live.anchorBottom;
        int height = Math.max(1, live.height);
        boolean resized = height != heightPx;
        heightPx = height;
        if (player != null) player.onMessageFrame(resized);
        if ((resized || anchored) && events != null) events.onResized(this);
        if (loadedFrameNow() && !resized && player != null) player.onMessageFrame(true);
    }

    private boolean loadedFrameNow() {
        if (loadedFrameSession == sessions || session == null || !session.hasLoadedFrame()) return false;
        loadedFrameSession = sessions;
        if (events != null) events.onLoadedFrame(this);
        return true;
    }

    @Override
    public void onGesture(boolean x, boolean y, boolean hold) {
        if (!isCurrent()) return;
        if (player != null) player.onGesture(x, y, hold);
    }

    @Override
    public void onRequest(long id, String data, boolean wallet) {
        if (!isCurrent()) return;
        if (events != null) events.onRequest(this, id, data, wallet);
        else answerRequest(id, WidgetSandboxSession.REQUEST_FAILED, null);
    }

    @Override
    public void onWallet(long id, JSONObject call) {
        if (!isCurrent()) return;
        if (events != null) events.onWallet(this, id, call);
        else answerRequest(id, WidgetSandboxSession.REQUEST_FAILED, null);
    }

    @Override
    public void onCopyText(String text) {
        if (!isCurrent()) return;
        if (events != null) events.onCopyText(this, text);
    }

    @Override
    public void onWatchPublicData(boolean on) {
        if (!isCurrent()) return;
        if (watchesPublicData == on) return;
        watchesPublicData = on;
        if (events != null) events.onWatchPublicData(this);
    }

    @Override
    public void onCursor(String cursor) {
        if (!isCurrent()) return;
        if (player != null) player.onCursor(cursor);
    }

    @Override
    public void onDiagnostics(JSONObject diagnostics) {
        if (!isCurrent()) return;
        if (events != null) events.onDiagnostics(this, diagnostics);
        if (session != null && (diagnostics.optBoolean("loaded") && snapshot != null || sentPreview != null))
            onFrame(session);
        else if (loadedFrameNow() && player != null)
            player.onMessageFrame(true);
    }

    @Override
    public void onAction(JSONObject action) {
        if (!isCurrent()) return;
        if (events != null) events.onAction(this, action);
    }

    @Override
    public void onStorageCommit(String area, JSONObject values) {
        if (!isCurrent()) return;
        if (events != null) events.onStorageCommit(this, area, values);
    }

    @Override
    public void onFailure(String message) {
        if (!isCurrent()) return;
        if (events != null) events.onFailure(this, message);
        if (player != null) player.onMessageFailure();
    }
}
