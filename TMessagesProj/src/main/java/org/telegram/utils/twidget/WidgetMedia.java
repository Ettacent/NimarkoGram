/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.content.Context;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Wallet.GramWalletWidget;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RichWidgetBlock;

public final class WidgetMedia {

    public static final String URL_PREFIX = "tg-widget:";
    public static final int MIN_WIDTH = 200, MIN_HEIGHT = 50, MAX_HEIGHT = 600;
    private static final int KEEP_RUNNING = 4;
    private static final int KEEP_SNAPSHOTS = 12;
    private static final int MAX_CRASHES = 2;
    private static final int KEEP_WIDTHS = 4;
    private static final int MEASURE_AT_ONCE = 3;
    private static final long MEASURE_LIMIT_MS = 4000;
    private static final long STORAGE_DELAY_MS = 1000;

    static final class Entry {
        final String url;
        final int account;
        final MessagesStorage.WidgetStateToken token;
        volatile boolean retired;
        final ArrayList<Runnable> cancellations = new ArrayList<>();
        final long dialogId;
        final int messageId;
        final int width, height;
        final byte[] payload;
        final boolean noForwards;
        final boolean isCopy, canQuery, backgroundQuery, preview;
        final long widgetId;
        final String chatType;
        final long botId;
        String chatData, publicData;
        long publicDataHash;
        TLRPC.TL_messageMediaWidget media;
        String html;
        WidgetSandboxMessage message;
        RichWidgetBlock block;
        final ArrayList<RichWidgetBlock> attached = new ArrayList<>();
        RichWidgetBlock owner;
        int shown;
        long lastShown;
        boolean stateLoaded;
        boolean activateOnLoad;
        int crashes, crashedSession;
        boolean dead;
        int measureWidthPx;
        boolean measureOut;
        final Runnable measured = () -> onMeasured(this);

        Entry(String url, int account, MessagesStorage.WidgetStateToken token, long dialogId, int messageId, long botId, TLRPC.TL_messageMediaWidget media, boolean preview, boolean noForwards) {
            this.url = url;
            this.account = account;
            this.token = token;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.botId = botId;
            this.width = Math.max(0, media.width);
            this.height = Math.max(0, media.height);
            this.payload = media.payload == null ? null : media.payload.clone();
            this.noForwards = noForwards;
            this.preview = preview;
            this.isCopy = !preview && media.is_copy;
            this.canQuery = !preview && media.can_query && messageId > 0;
            this.backgroundQuery = !preview && media.background_query && messageId > 0;
            this.widgetId = media.widget_id;
            this.chatType = preview || isCopy ? null : chatTypeOf(account, dialogId);
            this.media = media;
            this.chatData = text(media.chat_data);
            this.publicData = text(media.public_data);
            this.publicDataHash = media.public_data_hash;
        }

        String storageKey() {
            return WidgetMedia.storageKey(token, dialogId, messageId);
        }

        boolean isCurrent() {
            return !retired && token.isCurrent();
        }
    }

    private static final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Heights> heights = new ConcurrentHashMap<>();

    static final class Heights {
        private final ArrayList<int[]> byWidth = new ArrayList<>();

        synchronized int[] nearest(int width) {
            int[] best = null;
            for (int[] item : byWidth) {
                if (best == null || Math.abs(item[0] - width) < Math.abs(best[0] - width)) best = item;
            }
            return best;
        }

        synchronized int near(int width) {
            int best = 0, distance = Integer.MAX_VALUE;
            for (int[] item : byWidth) {
                int d = Math.abs(item[0] - width);
                if (d < distance) {
                    distance = d;
                    best = item[1];
                }
            }
            return best;
        }

        synchronized boolean anchorBottom() {
            return !byWidth.isEmpty() && byWidth.get(0).length > 2 && byWidth.get(0)[2] != 0;
        }

        synchronized boolean put(int width, int height) {
            return put(width, height, false);
        }

        synchronized boolean put(int width, int height, boolean anchorBottom) {
            int anchor = anchorBottom ? 1 : 0;
            for (int i = 0; i < byWidth.size(); i++) {
                int[] item = byWidth.get(i);
                if (item[0] != width) continue;
                if (item[1] == height && item[2] == anchor && i == 0) return false;
                byWidth.remove(i);
                break;
            }
            byWidth.add(0, new int[]{width, height, anchor});
            while (byWidth.size() > KEEP_WIDTHS) byWidth.remove(byWidth.size() - 1);
            return true;
        }

        synchronized void load(ArrayList<int[]> rows) {
            if (!byWidth.isEmpty()) return;
            for (int i = 0; i < rows.size() && i < KEEP_WIDTHS; i++) byWidth.add(rows.get(i));
        }
    }

    private static Heights heightsOf(String key) {
        Heights h = heights.get(key);
        if (h == null) {
            Heights created = new Heights();
            h = heights.putIfAbsent(key, created);
            if (h == null) h = created;
        }
        return h;
    }
    private static final HashMap<String, JSONObject> storages = new HashMap<>();
    private static final HashMap<String, Entry> pendingWrites = new HashMap<>();
    private static final Runnable writePending = WidgetMedia::flushStorage;
    private static final ArrayList<Entry> toMeasure = new ArrayList<>(), measuring = new ArrayList<>();
    private static final ArrayList<Runnable> heightWaiters = new ArrayList<>();
    private static boolean linkDialogShown, appPaused;

    private WidgetMedia() {
    }

    public static boolean isWidget(TLRPC.MessageMedia media) {
        return media instanceof TLRPC.TL_messageMediaWidget;
    }

    public static boolean isWidgetUrl(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    private static String storageKey(MessagesStorage.WidgetStateToken token, long dialogId, int messageId) {
        return token.cachePrefix() + dialogId + "/" + messageId;
    }

    public static TL_iv.RichMessage richMessage(MessageObject object, TLRPC.TL_messageMediaWidget media) {
        onAccountCleanup(object.currentAccount);
        MessagesStorage.WidgetStateToken token = MessagesStorage.getInstance(object.currentAccount).getWidgetStateToken();
        String url = URL_PREFIX + storageKey(token, object.getDialogId(), object.messageOwner.id) + "/"
                + Arrays.hashCode(media.payload);
        entry(object, url, media, token);
        return layout(object, url);
    }

    public static TL_iv.RichMessage richMessageForOwner(MessageObject object, TLRPC.TL_messageMediaWidget media, MessagesStorage.WidgetStateToken token) {
        if (!token.isCurrent()) return null;
        onAccountCleanup(object.currentAccount);
        String url = URL_PREFIX + storageKey(token, object.getDialogId(), object.messageOwner.id) + "/"
                + Arrays.hashCode(media.payload);
        if (entry(object, url, media, token) == null) return null;
        return layout(object, url);
    }

    public static void onMessageSent(int account, long dialogId, int oldId, int newId) {
        if (oldId == newId) return;
        MessagesStorage.WidgetStateToken token = MessagesStorage.getInstance(account).getWidgetStateToken();
        if (!token.isCurrent()) return;
        flushStorage();
        String from = storageKey(token, dialogId, oldId), to = storageKey(token, dialogId, newId);
        Heights known = heights.remove(from);
        if (known != null) heights.putIfAbsent(to, known);
        JSONObject storage = storages.remove(from);
        if (storage != null && !storages.containsKey(to)) storages.put(to, storage);
        MessagesStorage.getInstance(account).moveWidgetState(token, dialogId, oldId, newId);
        for (Entry entry : entries.values()) {
            if (!entry.isCurrent() || entry.account != account || entry.dialogId != dialogId || entry.messageId != oldId || entry.message == null || entry.height != 0) continue;
            int width = css(entry.message.frameWidthPx());
            int height = clamp(css(entry.message.heightPx()));
            boolean anchorBottom = entry.message.anchorBottom();
            if (width > 0 && entry.message.heightPx() > 0 && heightsOf(to).put(width, height, anchorBottom)) {
                MessagesStorage.getInstance(account).putWidgetHeight(token, dialogId, newId, width, height, anchorBottom ? 1 : 0, KEEP_WIDTHS);
            }
        }
    }

    public static void onMessageSent(MessageObject object, int oldId, MessagesStorage.WidgetStateToken token) {
        if (!token.isCurrent()) return;
        int account = object.currentAccount, newId = object.getId();
        long dialogId = object.getDialogId();
        onMessageSent(account, dialogId, oldId, newId);
        handoffSentPreview(object, oldId, token);
    }

    public static void handoffSentPreview(MessageObject object, int oldId, MessagesStorage.WidgetStateToken token) {
        int account = object.currentAccount, newId = object.getId();
        long dialogId = object.getDialogId();
        if (oldId >= 0 || newId <= 0 || !token.isCurrent()
                || !(MessageObject.getMedia(object.messageOwner) instanceof TLRPC.TL_messageMediaWidget)) return;
        TLRPC.TL_messageMediaWidget media = (TLRPC.TL_messageMediaWidget) MessageObject.getMedia(object.messageOwner);
        for (Entry source : entries.values()) {
            if (!source.isCurrent() || source.account != account || source.dialogId != dialogId || source.messageId != oldId
                    || !source.token.cachePrefix().equals(token.cachePrefix()) || source.preview || source.message == null
                    || source.botId != botOf(object) || !Arrays.equals(source.payload, media.payload)
                    || source.noForwards != object.messageOwner.noforwards
                    || source.width != Math.max(0, media.width) || source.height != Math.max(0, media.height)
                    || source.isCopy != media.is_copy || source.widgetId != media.widget_id
                    || !TextUtils.equals(source.chatData, text(media.chat_data))
                    || !TextUtils.equals(source.publicData, text(media.public_data)) || source.publicDataHash != media.public_data_hash) continue;
            WidgetSandboxSession.Frame frame = source.message.frame();
            if (frame == null || !frame.recorded()) continue;
            String url = URL_PREFIX + storageKey(token, dialogId, newId) + "/" + Arrays.hashCode(media.payload);
            Entry target = entry(object, url, media, token);
            if (target == null || target == source || !Arrays.equals(target.payload, media.payload)
                    || !TextUtils.equals(contextOf(source), contextOf(target))) return;
            WidgetSandboxMessage message = find(url);
            if (message != null && source.isCurrent() && target.isCurrent())
                message.inheritSentPreview(source.message);
            return;
        }
    }

    private static long botOf(MessageObject object) {
        TLRPC.Message message = object.messageOwner;
        if (message.via_bot_id != 0) return message.via_bot_id;
        if (message.fwd_from != null) {
            return message.fwd_from.from_id instanceof TLRPC.TL_peerUser ? message.fwd_from.from_id.user_id : 0;
        }
        long from = object.getFromChatId();
        return from > 0 ? from : 0;
    }

    private static Entry entry(MessageObject object, String url, TLRPC.TL_messageMediaWidget media, MessagesStorage.WidgetStateToken token) {
        if (!token.isCurrent()) return null;
        Entry entry = currentEntry(url);
        if (entry == null) {
            entry = new Entry(url, object.currentAccount, token, object.getDialogId(), object.messageOwner.id, botOf(object), media, false, object.messageOwner.noforwards);
            Entry raced = entries.putIfAbsent(url, entry);
            if (raced != null) {
                entry = raced;
            } else {
                loadState(entry);
            }
        } else if (entry.media != media) {
            Entry known = entry;
            AndroidUtilities.runOnUIThread(() -> {
                if (!known.isCurrent()) return;
                known.media = media;
                known.publicDataHash = media.public_data_hash;
                applyData(known, text(media.chat_data), text(media.public_data));
            });
        }
        if (!entry.isCurrent()) {
            retire(entry);
            return null;
        }
        return entry;
    }

    private static TL_iv.RichMessage layout(MessageObject object, String url) {
        TL_iv.pageBlockEmbed embed = new TL_iv.pageBlockEmbed();
        embed.url = url;
        embed.html = "";
        embed.caption = new TL_iv.PageCaption();
        embed.caption.text = new TL_iv.textEmpty();
        embed.caption.credit = new TL_iv.textEmpty();
        TL_iv.RichMessage rich = new TL_iv.RichMessage();
        rich.blocks.add(embed);
        return rich;
    }

    public static WidgetSandboxMessage find(String url) {
        Entry entry = isWidgetUrl(url) ? currentEntry(url) : null;
        if (entry == null) return null;
        if (WidgetSandboxSession.disabledByConfig()) return null;
        synchronized (entry) {
            if (!entry.isCurrent()) return null;
            if (entry.message == null) {
                if (entry.html == null) entry.html = inflate(entry.payload);
                if (entry.html == null) return null;
                Context context = ApplicationLoader.applicationContext;
                WidgetSandboxMessage message = new WidgetSandboxMessage(
                        context, entry.messageId, entry.url, "", entry.html, false, 1);
                message.setOwner(entry::isCurrent);
                message.setSdk(locale(), null);
                message.setContext(contextOf(entry));
                message.setData(dataOf(entry.chatData, entry.publicData));
                message.setEvents(events);
                entry.message = message;
            }
            return entry.message;
        }
    }

    public static int widgetWidthPx(String url, int bubbleWidthPx) {
        Entry entry = isWidgetUrl(url) ? currentEntry(url) : null;
        if (entry == null || entry.width == 0) return bubbleWidthPx;
        return Math.min(bubbleWidthPx, AndroidUtilities.dp(Math.max(MIN_WIDTH, entry.width)));
    }

    public static int contentHeightPx(String url, WidgetSandboxMessage message, int widthPx) {
        Entry entry = isWidgetUrl(url) ? currentEntry(url) : null;
        if (entry == null) return 0;
        if (entry.height > 0) return AndroidUtilities.dp(clamp(entry.height));
        int px = message != null && Math.abs(message.frameWidthPx() - widthPx) <= 1 ? message.heightPx() : 0;
        if (px > 0) return Math.min(AndroidUtilities.dp(MAX_HEIGHT), Math.max(AndroidUtilities.dp(MIN_HEIGHT), px));
        Heights known = heights.get(entry.storageKey());
        int css = known != null ? known.near(css(widthPx)) : 0;
        return AndroidUtilities.dp(clamp(css > 0 ? css : WidgetSandboxMessage.PLACEHOLDER_DP));
    }

    public static boolean anchorBottom(String url, WidgetSandboxMessage message) {
        if (message != null && message.heightPx() > 0) return message.anchorBottom();
        Entry entry = isWidgetUrl(url) ? currentEntry(url) : null;
        Heights known = entry != null ? heights.get(entry.storageKey()) : null;
        return known != null && known.anchorBottom();
    }

    public static String heightSource(String url, WidgetSandboxMessage message, int widthPx) {
        Entry entry = isWidgetUrl(url) ? currentEntry(url) : null;
        if (entry == null) return "none";
        if (entry.height > 0) return "anchor " + entry.height;
        if (message != null && Math.abs(message.frameWidthPx() - widthPx) <= 1 && message.heightPx() > 0) return "frame";
        Heights known = heights.get(entry.storageKey());
        int[] near = known != null ? known.nearest(css(widthPx)) : null;
        if (near == null || near[1] <= 0) return "placeholder";
        return near[0] == css(widthPx) ? "cache" : "cache of width " + near[0];
    }

    private static int css(int px) {
        return Math.round(px / AndroidUtilities.density);
    }

    private static int clamp(int dp) {
        return Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, dp));
    }

    @SuppressWarnings("unchecked")
    private static void loadState(Entry entry) {
        MessagesStorage storage = MessagesStorage.getInstance(entry.account);
        storage.getStorageQueue().postRunnable(() -> {
            if (!entry.isCurrent()) return;
            Object[] state = storage.getWidgetState(entry.token, entry.dialogId, entry.messageId);
            ArrayList<int[]> rows = (ArrayList<int[]>) state[1];
            AndroidUtilities.runOnUIThread(() -> {
                if (!entry.isCurrent()) return;
                if (!rows.isEmpty()) heightsOf(entry.storageKey()).load(rows);
                onStateLoaded(entry, (String) state[0]);
            });
        });
    }

    private static void onStateLoaded(Entry entry, String local) {
        if (!entry.isCurrent()) return;
        entry.stateLoaded = true;
        String key = entry.storageKey();
        if (local != null && !storages.containsKey(key)) {
            try {
                JSONObject storage = new JSONObject();
                storage.put("local", new JSONObject(local));
                storages.put(key, storage);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        WidgetSandboxMessage message = entry.message;
        if (message != null) {
            JSONObject storage = storages.get(key);
            message.setSdk(locale(), storage == null ? null : storage.toString());
        }
        if (entry.block != null && (message == null || message.heightPx() <= 0)) entry.block.onResizedAway("cached heights read");
        if (entry.activateOnLoad) {
            entry.activateOnLoad = false;
            if (entry.shown > 0 && message != null) start(entry);
        }
        measureNext();
    }

    public static void measure(String url, int widthPx, boolean out) {
        AndroidUtilities.runOnUIThread(() -> {
            Entry entry = currentEntry(url);
            if (entry == null || entry.message == null) return;
            if (measuring.contains(entry)) {
                entry.measureWidthPx = widthPx;
                entry.message.setWidthPx(widthPx);
                return;
            }
            entry.measureWidthPx = widthPx;
            entry.measureOut = out;
            toMeasure.remove(entry);
            toMeasure.add(entry);
            measureNext();
        });
    }

    private static void measureNext() {
        Entry entry;
        while (measuring.size() < MEASURE_AT_ONCE && !appPaused && (entry = nextToMeasure()) != null) {
            toMeasure.remove(entry);
            WidgetSandboxMessage message = entry.message;
            if (!entry.isCurrent() || message == null || entry.dead || entry.shown > 0) continue;
            String source = heightSource(entry.url, message, entry.measureWidthPx);
            if (!source.equals("placeholder") && !source.startsWith("cache of width")) continue;
            message.setWidthPx(entry.measureWidthPx);
            if (!message.hasTheme()) message.setTheme(WidgetSandboxTheme.build(null, entry.measureOut));
            if (message.isActive()) continue;
            measuring.add(entry);
            start(entry);
            AndroidUtilities.runOnUIThread(entry.measured, MEASURE_LIMIT_MS);
        }
    }

    private static Entry nextToMeasure() {
        Entry best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int i = toMeasure.size() - 1; i >= 0; i--) {
            Entry entry = toMeasure.get(i);
            if (!entry.isCurrent() || !entry.stateLoaded) continue;
            long distance = Long.MAX_VALUE - 1;
            for (Entry shown : entries.values()) {
                if (shown.shown == 0 || shown.account != entry.account || shown.dialogId != entry.dialogId) continue;
                distance = Math.min(distance, Math.abs((long) shown.messageId - entry.messageId));
            }
            if (distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static void whenShownHeightsKnown(Runnable then, long limitMs) {
        if (shownHeightsKnown()) {
            then.run();
            return;
        }
        Runnable once = new Runnable() {
            @Override
            public void run() {
                if (!heightWaiters.remove(this)) return;
                AndroidUtilities.cancelRunOnUIThread(this);
                then.run();
            }
        };
        heightWaiters.add(once);
        AndroidUtilities.runOnUIThread(once, limitMs);
    }

    private static boolean shownHeightsKnown() {
        for (Entry entry : entries.values()) {
            WidgetSandboxMessage message = entry.message;
            RichWidgetBlock block = entry.owner;
            if (entry.shown == 0 || message == null || block == null || entry.dead || message.hasLoadedFrame()) continue;
            String source = heightSource(entry.url, message, block.widthPx());
            if (source.equals("placeholder") || source.startsWith("cache of width")) return false;
        }
        return true;
    }

    private static void checkShownHeights() {
        if (heightWaiters.isEmpty() || !shownHeightsKnown()) return;
        for (Runnable waiter : new ArrayList<>(heightWaiters)) AndroidUtilities.runOnUIThread(waiter);
    }

    private static void onMeasured(Entry entry) {
        AndroidUtilities.cancelRunOnUIThread(entry.measured);
        if (!measuring.remove(entry)) return;
        trim();
        measureNext();
    }

    private static void flushStorage() {
        AndroidUtilities.cancelRunOnUIThread(writePending);
        if (pendingWrites.isEmpty()) return;
        for (Entry entry : pendingWrites.values()) {
            if (!entry.isCurrent()) continue;
            JSONObject storage = storages.get(entry.storageKey());
            JSONObject local = storage != null ? storage.optJSONObject("local") : null;
            String value = local == null || local.length() == 0 ? "" : local.toString();
            MessagesStorage.getInstance(entry.account).putWidgetStorage(entry.token, entry.dialogId, entry.messageId, value);
        }
        pendingWrites.clear();
    }

    public static void onKillSwitch() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!WidgetSandboxSession.disabledByConfig()) return;
            toMeasure.clear();
            for (Entry entry : entries.values()) {
                if (entry.message != null && entry.message.hasSession()) entry.message.unload();
            }
        });
    }

    public static void onStateCleared(int account) {
        MessagesStorage.getInstance(account).invalidateWidgetState();
    }

    public static void onAccountCleanup(int account) {
        for (Entry entry : entries.values()) {
            if (entry.account == account && !entry.isCurrent()) retire(entry);
        }
    }

    private static Entry currentEntry(String url) {
        Entry entry = entries.get(url);
        if (entry != null && !entry.isCurrent()) {
            retire(entry);
            return null;
        }
        return entry;
    }

    private static void retire(Entry entry) {
        synchronized (entry) {
            if (entry.retired) return;
            entry.retired = true;
            entries.remove(entry.url, entry);
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (entry.message != null) entry.message.retire();
            AndroidUtilities.cancelRunOnUIThread(entry.measured);
            toMeasure.remove(entry);
            measuring.remove(entry);
            for (Runnable cancel : new ArrayList<>(entry.cancellations)) cancel.run();
            entry.cancellations.clear();
            heights.remove(entry.storageKey());
            storages.remove(entry.storageKey());
            pendingWrites.remove(entry.storageKey());
            entry.attached.clear();
            entry.owner = entry.block = null;
            entry.shown = 0;
            updatePolls();
            checkShownHeights();
            measureNext();
        });
    }

    public static void onHeightsCleared(int account, MessagesStorage.WidgetStateToken token) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!token.isCurrent()) return;
            removeAccount(heights.keySet(), account + "/");
            for (Entry entry : entries.values()) {
                if (entry.account == account && entry.attached.isEmpty() && entry.message != null) entry.message.forget();
            }
        });
    }

    public static RichWidgetBlock registered(String url) {
        Entry entry = currentEntry(url);
        return entry == null ? null : entry.block;
    }

    public static void register(String url, RichWidgetBlock block) {
        Entry entry = currentEntry(url);
        if (entry != null) entry.block = block;
    }

    public static boolean attach(String url, RichWidgetBlock block) {
        Entry entry = currentEntry(url);
        if (entry == null) return true;
        if (!entry.attached.contains(block)) entry.attached.add(block);
        RichWidgetBlock newest = newest(entry.attached);
        if (newest != block) return false;
        RichWidgetBlock previous = entry.owner;
        entry.owner = block;
        if (previous != null && previous != block) previous.demote();
        return true;
    }

    public static void detach(String url, RichWidgetBlock block) {
        Entry entry = currentEntry(url);
        if (entry == null) return;
        entry.attached.remove(block);
        if (entry.owner != block) return;
        entry.owner = newest(entry.attached);
        if (entry.owner != null) entry.owner.promote();
    }

    private static RichWidgetBlock newest(ArrayList<RichWidgetBlock> blocks) {
        RichWidgetBlock newest = null;
        for (RichWidgetBlock b : blocks) {
            if (newest == null || b.serial > newest.serial) newest = b;
        }
        return newest;
    }

        public static void onShown(WidgetSandboxMessage message) {
        Entry entry = entryOf(message);
        if (entry == null) return;
        entry.shown++;
        entry.lastShown = System.currentTimeMillis();
        message.setPaused(appPaused);
        if (entry.stateLoaded) start(entry);
        else entry.activateOnLoad = true;
        trim();
        updatePolls();
    }

    private static void start(Entry entry) {
        if (!entry.isCurrent()) return;
        WidgetSandboxMessage message = entry.message;
        JSONObject storage = storages.get(entry.storageKey());
        message.setSdk(locale(), storage == null ? null : storage.toString());
        if (entry.dead) {
            if (entry.crashes >= MAX_CRASHES) return;
            entry.dead = false;
            message.unload();
        }
        message.activate();
    }

    public static void onHidden(WidgetSandboxMessage message) {
        Entry entry = entryOf(message);
        if (entry == null) return;
        entry.shown = Math.max(0, entry.shown - 1);
        entry.lastShown = System.currentTimeMillis();
        trim();
        flushStorage();
        updatePolls();
    }

    public static void onAppPaused(boolean paused) {
        if (appPaused == paused) return;
        appPaused = paused;
        for (Entry entry : entries.values()) {
            if (entry.message != null) entry.message.setPaused(paused);
        }
        if (paused) flushStorage();
        else measureNext();
        updatePolls();
    }

    private static void trim() {
        ArrayList<Entry> hidden = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.message != null && entry.shown == 0 && !measuring.contains(entry)) hidden.add(entry);
        }
        Collections.sort(hidden, (a, b) -> Long.compare(b.lastShown, a.lastShown));
        for (int i = 0; i < hidden.size(); i++) {
            WidgetSandboxMessage message = hidden.get(i).message;
            if (i >= KEEP_RUNNING) message.unload();
            if (i >= KEEP_SNAPSHOTS) message.dropSnapshot();
        }
    }

    private static void removeAccount(java.util.Set<String> keys, String prefix) {
        for (java.util.Iterator<String> i = keys.iterator(); i.hasNext(); ) {
            if (i.next().startsWith(prefix)) i.remove();
        }
    }

    private static Entry entryOf(WidgetSandboxMessage message) {
        Entry entry = message == null ? null : currentEntry(message.file);
        return entry != null && entry.message == message ? entry : null;
    }

    private static String locale() {
        try {
            return LocaleController.getInstance().getCurrentLocale().toLanguageTag();
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String chatTypeOf(int account, long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) return "private";
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat == null) return null;
        if (!ChatObject.isChannel(chat)) return "group";
        return chat.megagroup ? "supergroup" : "channel";
    }

    private static String dataOf(String chatData, String publicData) {
        try {
            JSONObject data = new JSONObject();
            data.put("chatData", chatData == null ? JSONObject.NULL : chatData);
            data.put("publicData", publicData == null ? JSONObject.NULL : publicData);
            return data.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String contextOf(Entry entry) {
        try {
            JSONObject context = new JSONObject();
            context.put("isCopy", entry.isCopy);
            context.put("canQuery", entry.canQuery);
            context.put("backgroundQuery", entry.backgroundQuery);
            context.put("preview", entry.preview);
            context.put("wallet", walletAvailable(entry));
            if (entry.chatType != null) context.put("chatType", entry.chatType);
            TLRPC.User me = entry.canQuery ? UserConfig.getInstance(entry.account).getCurrentUser() : null;
            if (me != null) {
                JSONObject viewer = new JSONObject();
                viewer.put("id", me.id);
                if (!TextUtils.isEmpty(me.first_name)) viewer.put("firstName", me.first_name);
                if (!TextUtils.isEmpty(me.last_name)) viewer.put("lastName", me.last_name);
                String username = UserObject.getPublicUsername(me);
                if (!TextUtils.isEmpty(username)) viewer.put("username", username);
                viewer.put("isPremium", me.premium);
                context.put("viewer", viewer);
            }
            return context.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void applyData(Entry entry, String chatData, String publicData) {
        if (!entry.isCurrent()) return;
        if (TextUtils.equals(entry.chatData, chatData) && TextUtils.equals(entry.publicData, publicData)) return;
        entry.chatData = chatData;
        entry.publicData = publicData;
        if (entry.media != null) {
            entry.media.chat_data = chatData;
            entry.media.public_data = publicData;
            entry.media.public_data_hash = entry.publicDataHash;
        }
        if (entry.message != null) entry.message.setData(dataOf(chatData, publicData));
    }

    public static void onDataEdited(int account, long dialogId, int messageId, String publicData, long publicDataHash, String chatData) {
        for (Entry entry : entries.values()) {
            if (!entry.isCurrent() || entry.preview || entry.account != account || entry.dialogId != dialogId || entry.messageId != messageId) continue;
            if (publicData != null) {
                entry.publicDataHash = publicDataHash;
                if (entry.media != null) entry.media.public_data_hash = publicDataHash;
            }
            applyData(entry, chatData != null ? text(chatData) : entry.chatData, publicData != null ? text(publicData) : entry.publicData);
        }
    }

    public static String previewUrl(int account, TLRPC.BotInlineResult result) {
        onAccountCleanup(account);
        MessagesStorage.WidgetStateToken token = MessagesStorage.getInstance(account).getWidgetStateToken();
        TLRPC.TL_botInlineMessageWidget widget = (TLRPC.TL_botInlineMessageWidget) result.send_message;
        String url = URL_PREFIX + "preview/" + token.cachePrefix() + result.query_id + "/" + result.id + "/" + Arrays.hashCode(widget.payload);
        if (token.isCurrent() && currentEntry(url) == null) {
            TLRPC.TL_messageMediaWidget media = new TLRPC.TL_messageMediaWidget();
            media.width = widget.width;
            media.height = widget.height;
            media.payload = widget.payload;
            media.public_data = widget.public_data;
            media.public_data_hash = widget.public_data_hash;
            media.chat_data = widget.chat_data;
            media.widget_id = widget.widget_id;
            Entry entry = new Entry(url, account, token, 0, 0, 0, media, true, false);
            entry.stateLoaded = true;
            entries.putIfAbsent(url, entry);
            if (!entry.isCurrent()) retire(entry);
        }
        return url;
    }

    public static boolean isPreview(String url) {
        Entry entry = isWidgetUrl(url) ? currentEntry(url) : null;
        return entry != null && entry.preview;
    }

    private static final long REQUEST_LIMIT_MS = 30_000;

    private static void request(Entry entry, WidgetSandboxMessage message, long id, String data, boolean wallet) {
        if (entry == null || !entry.canQuery || wallet && !walletAvailable(entry)) {
            message.answerRequest(id, WidgetSandboxSession.REQUEST_FAILED, null);
            return;
        }
        TLRPC.TL_widgetQueryTypeData type = new TLRPC.TL_widgetQueryTypeData();
        type.with_wallet = wallet;
        query(entry, message, id, type, data.getBytes(StandardCharsets.UTF_8));
    }

    private static void query(Entry entry, WidgetSandboxMessage message, long id, TLRPC.WidgetQueryType type, byte[] data) {
        if (entry == null || !entry.isCurrent() || entry.message != message) return;
        boolean[] answered = new boolean[1];
        ConnectionsManager connections = ConnectionsManager.getInstance(entry.account);
        int[] requestId = new int[1];
        Runnable[] cancel = new Runnable[1];
        Runnable limit = () -> {
            if (answered[0]) return;
            answered[0] = true;
            entry.cancellations.remove(cancel[0]);
            connections.cancelRequest(requestId[0], true);
            if (entry.isCurrent()) message.answerRequest(id, WidgetSandboxSession.REQUEST_FAILED, null);
        };
        cancel[0] = () -> {
            answered[0] = true;
            AndroidUtilities.cancelRunOnUIThread(limit);
            if (requestId[0] != 0) connections.cancelRequest(requestId[0], true);
        };
        entry.cancellations.add(cancel[0]);
        sendQuery(entry, type, data, requestId, (res, err) -> {
            if (answered[0] || !entry.isCurrent() || entry.message != message) return;
            answered[0] = true;
            entry.cancellations.remove(cancel[0]);
            AndroidUtilities.cancelRunOnUIThread(limit);
            if (res != null) {
                message.answerRequest(id, WidgetSandboxSession.REQUEST_ANSWERED, text(res.result));
            } else {
                String error = err != null ? err.text : "";
                int failure = "WIDGET_RESPONSE_TIMEOUT".equals(error) ? WidgetSandboxSession.REQUEST_TIMEOUT
                        : error != null && error.startsWith("FLOOD_WAIT") ? WidgetSandboxSession.REQUEST_RATE_LIMITED
                        : WidgetSandboxSession.REQUEST_FAILED;
                message.answerRequest(id, failure, null);
            }
        });
        AndroidUtilities.runOnUIThread(limit, REQUEST_LIMIT_MS);
    }

    private static final boolean[] legacyQuery = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private static void sendQuery(Entry entry, TLRPC.WidgetQueryType type, byte[] data, int[] requestId, Utilities.Callback2<TLRPC.TL_messages_botWidgetQueryAnswer, TLRPC.TL_error> done) {
        if (!entry.isCurrent()) return;
        final boolean plain = type instanceof TLRPC.TL_widgetQueryTypeData && !((TLRPC.TL_widgetQueryTypeData) type).with_wallet;
        final boolean legacy = legacyQuery[entry.account];
        if (legacy && !plain) {
            TLRPC.TL_error error = new TLRPC.TL_error();
            error.code = 400;
            error.text = "WIDGET_QUERY_TYPE_UNSUPPORTED";
            AndroidUtilities.runOnUIThread(() -> {
                if (entry.isCurrent()) done.run(null, error);
            });
            return;
        }
        TLRPC.TL_messages_getBotWidgetQueryAnswer req = legacy ? new TLRPC.TL_messages_getBotWidgetQueryAnswer_layer233() : new TLRPC.TL_messages_getBotWidgetQueryAnswer();
        req.peer = MessagesController.getInstance(entry.account).getInputPeer(entry.dialogId);
        req.msg_id = entry.messageId;
        req.query_type = type;
        req.data = data;
        requestId[0] = ConnectionsManager.getInstance(entry.account).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (!entry.isCurrent()) return;
            if (!legacy && err != null && err.text != null && (err.text.startsWith("INPUT_METHOD_INVALID") || err.text.startsWith("INPUT_CONSTRUCTOR_INVALID"))) {
                legacyQuery[entry.account] = true;
                sendQuery(entry, type, data, requestId, done);
                return;
            }
            done.run(res, err);
        });
    }

    private static boolean walletAvailable(Entry entry) {
        return entry.isCurrent() && entry.canQuery && !entry.preview && MessagesController.getInstance(entry.account).config.walletAvailable.get();
    }

    private static void wallet(Entry entry, WidgetSandboxMessage message, long id, JSONObject call) {
        TLRPC.User bot = entry == null || entry.botId == 0 ? null : MessagesController.getInstance(entry.account).getUser(entry.botId);
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (entry == null || !walletAvailable(entry) || bot == null || !bot.bot) {
            message.answerRequest(id, WidgetSandboxSession.REQUEST_BAD_REQUEST, null);
            return;
        }
        if (fragment == null || fragment.getContext() == null) {
            message.answerRequest(id, WidgetSandboxSession.REQUEST_FAILED, null);
            return;
        }
        byte[] data = call.optString("data").getBytes(StandardCharsets.UTF_8);
        GramWalletWidget.open(fragment.getContext(), entry.account, bot, call, fragment.getResourceProvider(), new GramWalletWidget.Delegate() {
            @Override
            public void sendOutcome(TLRPC.WidgetQueryType queryType) {
                query(entry, message, id, queryType, data);
            }

            @Override
            public void rejected() {
                if (entry.isCurrent()) message.answerRequest(id, WidgetSandboxSession.REQUEST_REJECTED, null);
            }

            @Override
            public void badRequest() {
                if (entry.isCurrent()) message.answerRequest(id, WidgetSandboxSession.REQUEST_BAD_REQUEST, null);
            }

            @Override
            public void failed() {
                if (entry.isCurrent()) message.answerRequest(id, WidgetSandboxSession.REQUEST_FAILED, null);
            }
        });
    }

    private static void copyText(String text) {
        if (TextUtils.isEmpty(text)) return;
        AndroidUtilities.addToClipboard(text);
        BaseFragment fragment = AndroidUtilities.shouldShowClipboardToast() ? LaunchActivity.getSafeLastFragment() : null;
        if (fragment == null) return;
        String shown = text.length() > 80 ? text.substring(0, 80) + "…" : text;
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.copy, LocaleController.formatString(R.string.WidgetTextCopied, shown)).show();
    }

    private static final int POLL_DEFAULT_S = 30, POLL_MIN_S = 5, POLL_MAX_S = 3600;

    private static final class Poll {
        final int account;
        final MessagesStorage.WidgetStateToken token;
        final String key;
        final long widgetId;
        long hash;
        int periodS = POLL_DEFAULT_S;
        int requestId;
        final Runnable run = () -> poll(this);

        Poll(int account, MessagesStorage.WidgetStateToken token, long widgetId) {
            this.account = account;
            this.token = token;
            this.widgetId = widgetId;
            this.key = token.cachePrefix() + widgetId;
        }
    }

    private static final HashMap<String, Poll> polls = new HashMap<>();

    private static void updatePolls() {
        HashMap<String, Entry> wanted = new HashMap<>();
        for (Entry entry : entries.values()) {
            if (!entry.isCurrent() || entry.widgetId == 0 || entry.preview || entry.shown == 0 || appPaused) continue;
            if (entry.message == null || !entry.message.watchesPublicData()) continue;
            wanted.put(entry.token.cachePrefix() + entry.widgetId, entry);
        }
        for (java.util.Iterator<java.util.Map.Entry<String, Poll>> i = polls.entrySet().iterator(); i.hasNext(); ) {
            java.util.Map.Entry<String, Poll> item = i.next();
            if (wanted.containsKey(item.getKey())) continue;
            Poll poll = item.getValue();
            AndroidUtilities.cancelRunOnUIThread(poll.run);
            if (poll.requestId != 0) ConnectionsManager.getInstance(poll.account).cancelRequest(poll.requestId, true);
            i.remove();
        }
        for (java.util.Map.Entry<String, Entry> item : wanted.entrySet()) {
            if (polls.containsKey(item.getKey())) continue;
            Entry entry = item.getValue();
            Poll poll = new Poll(entry.account, entry.token, entry.widgetId);
            poll.hash = entry.publicDataHash;
            polls.put(item.getKey(), poll);
            AndroidUtilities.runOnUIThread(poll.run, poll.periodS * 1000L);
        }
    }

    private static void poll(Poll poll) {
        if (!poll.token.isCurrent() || polls.get(poll.key) != poll) return;
        Entry carrier = null;
        for (Entry entry : entries.values()) {
            if (entry.isCurrent() && entry.token.cachePrefix().equals(poll.token.cachePrefix()) && entry.widgetId == poll.widgetId && !entry.preview && entry.shown > 0 && entry.messageId > 0) {
                carrier = entry;
                break;
            }
        }
        if (carrier == null) {
            updatePolls();
            return;
        }
        TLRPC.TL_messages_getWidgetPublicData req = new TLRPC.TL_messages_getWidgetPublicData();
        req.peer = MessagesController.getInstance(carrier.account).getInputPeer(carrier.dialogId);
        req.msg_id = carrier.messageId;
        req.hash = poll.hash;
        poll.requestId = ConnectionsManager.getInstance(poll.account).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            poll.requestId = 0;
            if (!poll.token.isCurrent() || polls.get(poll.key) != poll) return;
            if (res != null) {
                poll.periodS = res.period <= 0 ? POLL_DEFAULT_S : Math.max(POLL_MIN_S, Math.min(POLL_MAX_S, res.period));
                if (res instanceof TLRPC.TL_messages_widgetPublicData) {
                    TLRPC.TL_messages_widgetPublicData changed = (TLRPC.TL_messages_widgetPublicData) res;
                    poll.hash = changed.hash;
                    onPublicData(poll.account, poll.widgetId, changed.data, changed.hash);
                }
            }
            AndroidUtilities.runOnUIThread(poll.run, poll.periodS * 1000L);
        });
    }

    private static void onPublicData(int account, long widgetId, String data, long hash) {
        String publicData = text(data);
        for (Entry entry : entries.values()) {
            if (!entry.isCurrent() || entry.account != account || entry.widgetId != widgetId || entry.preview) continue;
            if (TextUtils.equals(entry.publicData, publicData) && entry.publicDataHash == hash) continue;
            entry.publicDataHash = hash;
            applyData(entry, entry.chatData, publicData);
            if (entry.media != null) entry.media.public_data_hash = hash;
            MessagesStorage.getInstance(account).updateMessageWidgetData(entry.token, entry.dialogId, entry.messageId, data == null ? "" : data, hash, null);
        }
    }

    private static String inflate(byte[] payload) {
        if (payload == null) return null;
        boolean gzip = payload.length >= 2 && (payload[0] & 0xFF) == 0x1F && (payload[1] & 0xFF) == 0x8B;
        try (InputStream input = gzip
                        ? new GZIPInputStream(new ByteArrayInputStream(payload))
                        : new InflaterInputStream(new ByteArrayInputStream(payload));
                ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length * 4)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            if (BuildVars.LOGS_ENABLED) FileLog.d("widget payload: " + (gzip ? "gzip" : "zlib") + ", " + payload.length + " -> " + output.size() + " bytes");
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static final WidgetSandboxMessage.Events events = new WidgetSandboxMessage.Events() {
        @Override
        public void onDiagnostics(WidgetSandboxMessage message, JSONObject diagnostics) {
            String dead = diagnostics.optString("dead");
            if (TextUtils.isEmpty(dead)) return;
            Entry entry = entryOf(message);
            if (entry == null || entry.crashedSession == message.sessions()) return;
            entry.crashedSession = message.sessions();
            entry.crashes++;
            entry.dead = true;
            FileLog.d("widget " + message.file + " stopped (" + entry.crashes + "): " + dead);
            onMeasured(entry);
            checkShownHeights();
        }

        @Override
        public void onLoadedFrame(WidgetSandboxMessage message) {
            checkShownHeights();
            Entry entry = entryOf(message);
            if (entry != null) onMeasured(entry);
        }

        @Override
        public void onAction(WidgetSandboxMessage message, JSONObject action) {
            Entry entry = entryOf(message);
            if (entry == null) return;
            String kind = action.optString("kind");
            if ("openLink".equals(kind) || "openTelegramLink".equals(kind)) {
                openLink(entry, action.optString("url"), "openTelegramLink".equals(kind));
            } else if ("haptic".equals(kind)) {
                View view = entry != null && entry.shown > 0 && entry.owner != null ? entry.owner.hostView() : null;
                if (view != null) {
                    String haptic = action.optString("haptic");
                    int feedback = "selection".equals(haptic) ? HapticFeedbackConstants.CLOCK_TICK
                            : "notification".equals(haptic) ? HapticFeedbackConstants.CONFIRM
                            : HapticFeedbackConstants.KEYBOARD_TAP;
                    view.performHapticFeedback(feedback);
                }
            }
        }

        @Override
        public void onRequest(WidgetSandboxMessage message, long id, String data, boolean wallet) {
            request(entryOf(message), message, id, data, wallet);
        }

        @Override
        public void onWallet(WidgetSandboxMessage message, long id, JSONObject call) {
            wallet(entryOf(message), message, id, call);
        }

        @Override
        public void onCopyText(WidgetSandboxMessage message, String text) {
            if (entryOf(message) != null) copyText(text);
        }

        @Override
        public void onWatchPublicData(WidgetSandboxMessage message) {
            updatePolls();
        }

        @Override
        public void onFailure(WidgetSandboxMessage message, String error) {
            FileLog.e("widget " + message.file + ": " + error);
        }

        @Override
        public void onResized(WidgetSandboxMessage message) {
            Entry entry = entryOf(message);
            if (entry == null || message.heightPx() <= 0) return;
            int width = css(message.frameWidthPx());
            int height = clamp(css(message.heightPx()));
            boolean anchorBottom = message.anchorBottom();
            if (width > 0 && entry.height == 0 && !entry.preview && heightsOf(entry.storageKey()).put(width, height, anchorBottom)) {
                MessagesStorage.getInstance(entry.account).putWidgetHeight(entry.token, entry.dialogId, entry.messageId, width, height, anchorBottom ? 1 : 0, KEEP_WIDTHS);
            }
            if (entry.block != null) entry.block.onResizedAway("frame off screen");
        }

        @Override
        public void onStorageCommit(WidgetSandboxMessage message, String area, JSONObject values) {
            Entry entry = entryOf(message);
            if (entry == null) return;
            String key = entry.storageKey();
            JSONObject storage = storages.get(key);
            if (storage == null) storage = new JSONObject();
            try {
                storage.put(area, values);
            } catch (Exception ignored) {
            }
            storages.put(key, storage);
            for (Entry other : entries.values()) {
                if (other.isCurrent() && other.message != null && key.equals(other.storageKey())) other.message.setSdk(locale(), storage.toString());
            }
            if ("local".equals(area)) {
                pendingWrites.put(key, entry);
                AndroidUtilities.cancelRunOnUIThread(writePending);
                AndroidUtilities.runOnUIThread(writePending, STORAGE_DELAY_MS);
            }
        }
    };

    private static void openLink(Entry entry, String url, boolean telegram) {
        if (!entry.isCurrent() || TextUtils.isEmpty(url) || linkDialogShown) return;
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) return;
        Context context = fragment.getParentActivity();
        if (telegram) {
            Browser.openUrl(context, url);
            return;
        }
        linkDialogShown = true;
        AlertDialog dialog = new AlertDialog.Builder(context, fragment.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.WidgetOpenLinkTitle))
                .setMessage(LocaleController.formatString(R.string.WidgetOpenLinkText, url))
                .setPositiveButton(LocaleController.getString(R.string.Open), (d, w) -> {
                    if (entry.isCurrent()) Browser.openUrl(context, url, true, false);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.setOnDismissListener(d -> linkDialogShown = false);
        if (fragment.showDialog(dialog) == null) linkDialogShown = false;
    }
}
