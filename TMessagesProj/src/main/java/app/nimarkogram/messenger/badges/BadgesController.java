/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.badges;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import app.nimarkogram.messenger.api.dto.BadgeDTO;
import app.nimarkogram.messenger.api.model.ProfileStatus;
import app.nimarkogram.messenger.api.network.ApiClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BadgesController {

    public static final BadgesController INSTANCE = new BadgesController();

    private static final class GsonHolder {
        static final Gson INSTANCE = new Gson();
    }
    public static BadgesController getInstance() {
        return INSTANCE;
    }

    public final ApiBadgeSource apiBadgeSource = new ApiBadgeSource();

    public static final BadgeDTO DEV_BADGE = null;
    public static final BadgeDTO SUPPORTER_BADGE = null;
    public static final BadgeDTO TRUSTED_BADGE = null;

    private static final String PREFS = "nimarko_badges";
    private static final String KEY_CACHE = "cache_json";
    private static final String KEY_SERVER_USER_CACHE = "server_user_cache_json";
    private static final String KEY_CHAT_CACHE = "chat_cache_json";
    private static final String KEY_API_USERS = "api_users";
    private static final String KEY_API_CHATS = "api_chats";
    private static final String KEY_STORE_VERSION = "store_version";
    private static final int STORE_VERSION_SEPARATE_OWNERS = 2;
    private static final String KEY_FILE_MIGRATED = "badge_cache_file_v1";
    private static final long REFRESH_INTERVAL_SECONDS = 60L;
    private static final long PERSIST_DEBOUNCE_MS = 350L;
    private static final long BOOTSTRAP_USER_ID = 0L;

    private static final long BADGE_POLL_ACTIVE_MS = 5_000L;
    private static final long BADGE_POLL_IDLE_MAX_MS = 60_000L;
    private volatile boolean initialized = false;
    private volatile long lastRefreshAtMs = 0L;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private static final class ServerBadgeSnapshot {
        final Map<Long, BadgeEntry> users;
        final Map<Long, BadgeEntry> chats;

        ServerBadgeSnapshot(Map<Long, BadgeEntry> users, Map<Long, BadgeEntry> chats) {
            this.users = users;
            this.chats = chats;
        }
    }
    private volatile ServerBadgeSnapshot serverBadges =
            new ServerBadgeSnapshot(Collections.emptyMap(), Collections.emptyMap());
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nimarko-badges-refresh");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService persistScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nimarko-badges-persist");
                t.setDaemon(true);
                return t;
            });
    private final Object persistLock = new Object();
    private long persistRevision;
    private long serverRevision;
    private boolean cacheLoadComplete;
    private boolean cacheReady;
    private boolean persistRequested;
    private Context cacheContext;
    private ScheduledFuture<?> pendingPersist;

    private final Object badgePollLock = new Object();
    private ScheduledFuture<?> pendingBadgePoll;
    private long badgePollRevision;
    private long badgePollDelayMs = BADGE_POLL_ACTIVE_MS;
    private volatile long lastBadgeHash;
    private boolean cacheMigrationPending;
    private BadgesController() {}

    public void init(Context context) {
        synchronized (persistLock) {
        if (initialized) return;
        initialized = true;

        }
        seedBootstrapEntry();
        final Context application = context.getApplicationContext();
        synchronized (persistLock) {
            cacheContext = application != null ? application : context;
        }
        persistScheduler.execute(() -> {
            lastBadgeHash = computeBadgeHash();
            loadFromDisk(cacheContext);
        });
        scheduler.scheduleWithFixedDelay(() -> {
            if (!ApplicationLoader.mainInterfacePaused) refresh();
        }, 0L, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);

        synchronized (badgePollLock) {
            scheduleBadgePollLocked(BADGE_POLL_ACTIVE_MS);
        }
    }

    private void scheduleBadgePollLocked(long delayMs) {
        final long revision = ++badgePollRevision;
        pendingBadgePoll = scheduler.schedule(
                () -> pollPluginBadges(revision), delayMs, TimeUnit.MILLISECONDS);
    }

    private void pollPluginBadges(long revision) {
        synchronized (badgePollLock) {
            if (revision != badgePollRevision) return;
            pendingBadgePoll = null;
        }
        try {
            synchronized (persistLock) {
                if (!cacheLoadComplete) return;
            }
            long hash = computeBadgeHash();
            boolean contentChanged = hash != lastBadgeHash;
            if (contentChanged) {
                lastBadgeHash = hash;
                        apiBadgeSource.forceNotify();

                if (contentChanged) persist();
            }
        } catch (Throwable ignored) {
        } finally {
            synchronized (badgePollLock) {
                if (revision == badgePollRevision) {
                    badgePollDelayMs = !ApplicationLoader.mainInterfacePaused ? BADGE_POLL_ACTIVE_MS
                            : Math.min(BADGE_POLL_IDLE_MAX_MS, badgePollDelayMs * 2L);
                    scheduleBadgePollLocked(badgePollDelayMs);
                }
            }
        }
    }

    private void noteBadgeConsumer() {
        if (ApplicationLoader.mainInterfacePaused) return;
        synchronized (badgePollLock) {
            if (pendingBadgePoll != null
                    && pendingBadgePoll.getDelay(TimeUnit.MILLISECONDS) > BADGE_POLL_ACTIVE_MS) {
                pendingBadgePoll.cancel(false);
                badgePollDelayMs = BADGE_POLL_ACTIVE_MS;
                scheduleBadgePollLocked(BADGE_POLL_ACTIVE_MS);
            }
        }
    }

    private long computeBadgeHash() {
        return computeBadgeHash(apiBadgeSource.cache);
    }

    private static final class BadgeFingerprint {
        final long key;
        final int entryHash;
        final BadgePluginOwners.Owner owner;

        BadgeFingerprint(long key, BadgeEntry entry) {
            this.key = key;
            if (entry == null) {
                entryHash = 0;
                owner = null;
            } else {
                BadgeDTO badge = entry.getBadge();
                int hash = badge == null ? 0 : copyBadge(badge).hashCode();
                hash = hash * 31 + entry.getStatus().hashCode();
                entryHash = hash * 31 + Boolean.hashCode(entry.getCanChangeBadge());
                owner = entry.getPluginOwner();
            }
        }
    }

    private long computeBadgeHash(Map<Long, BadgeEntry> source) {
        long h = 0xcbf29ce484222325L;
        try {
            ArrayList<BadgeFingerprint> entries = new ArrayList<>(source.size());
            for (Map.Entry<Long, BadgeEntry> row : source.entrySet()) {
                BadgeEntry entry = row.getValue() == null ? null : row.getValue().snapshot();
                entries.add(new BadgeFingerprint(row.getKey() == null ? 0L : row.getKey(), entry));
            }
            entries.sort(Comparator.comparingLong(e -> e.key));
            for (BadgeFingerprint entry : entries) {
                h ^= entry.key;
                h *= 0x100000001b3L;
                h ^= entry.entryHash;
                h *= 0x100000001b3L;
                BadgePluginOwners.Owner owner = entry.owner;
                h ^= owner == null ? 0 : owner.pluginId.hashCode();
                h *= 0x100000001b3L;
                h ^= BadgePluginOwners.isActive(owner) ? 1 : 0;
                h *= 0x100000001b3L;
            }
        } catch (Throwable ignored) {}
        return h;
    }

    public void putPluginBadge(long userId, long emojiDocumentId, String text) {
        if (userId == 0L || emojiDocumentId == 0L) return;
        BadgeDTO dto = new BadgeDTO(emojiDocumentId, text);
            apiBadgeSource.cache.put(userId, new BadgeEntry(dto, ProfileStatus.SUPPORTER, false));
        persist();
        apiBadgeSource.forceNotify();

    }

    public void putPluginChatBadge(long chatId, long emojiDocumentId, String text) {
        if (chatId == 0L || emojiDocumentId == 0L) return;
        BadgeDTO dto = new BadgeDTO(emojiDocumentId, text);
            apiBadgeSource.cache.put(pluginChatKey(chatId),
                    new BadgeEntry(dto, ProfileStatus.SUPPORTER, false));
        persist();
        apiBadgeSource.forceNotify();
    }

    public BadgeDTO i(TLObject obj) {
        if (obj == null) return null;
        try {
            long id;
            boolean isUser;
            if (obj instanceof TLRPC.User) {
                id = ((TLRPC.User) obj).id;
                isUser = true;
            } else if (obj instanceof TLRPC.Chat) {
                id = ((TLRPC.Chat) obj).id;
                isUser = false;
            } else {
                return null;
            }
            BadgeEntry e = isUser ? getUserEntry(id) : getChatEntry(id);
            if (e != null && e.getBadge() != null && e.getBadge().getDocumentId() != 0L) {
                return e.getBadge();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean r(TLObject obj) {
        return i(obj) != null;
    }

    public BadgeDTO o(TLRPC.User user) {
        BadgeDTO b = i(user);
        if (b != null && z(user, b)) return b;
        return null;
    }

    public BadgeDTO m(TLRPC.User user) {
        return null;
    }

    public BadgeDTO l() {
        return null;
    }

    public BadgeDTO h() {
        try {
            TLRPC.User cu = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
            return i(cu);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public BadgeDTO k() { return DEV_BADGE; }
    public BadgeDTO n() { return SUPPORTER_BADGE; }
    public BadgeDTO p() { return TRUSTED_BADGE; }

    public boolean e(TLRPC.User user) {
        if (user == null) return false;
        BadgeEntry e = getUserEntry(user.id);
        if (e != null && e.getCanChangeBadge()) return true;
        return w(user.id);
    }

    public boolean d() {
        try {
            TLRPC.User cu = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
            return e(cu);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean u(TLRPC.User user) {
        return user != null && w(user.id);
    }

    public boolean w(long id) {
        BadgeEntry e = getUserEntry(id);
        return e != null && e.getStatus() == ProfileStatus.DEVELOPER;
    }

    public boolean x(TLRPC.Chat chat) {
        if (chat == null) return false;
        BadgeEntry entry = getChatEntry(chat.id);
        return entry != null && entry.getStatus() == ProfileStatus.DEVELOPER;
    }

    public boolean y(long id) {
        return false;
    }

    public boolean z(TLRPC.User user, BadgeDTO badge) {
        if (user == null || badge == null) return false;
        if (!e(user)) return false;
        BadgeDTO statusBadge = m(user);
        return !badge.equals(statusBadge) || u(user);
    }

    public boolean q() {
        try {
            TLRPC.User cu = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
            return r(cu);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean t() {
        try {
            TLRPC.User cu = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
            return u(cu);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public BadgeDTO getBadgeForUser(long userId) {
        BadgeEntry e = getUserEntry(userId);
        return e != null ? e.getBadge() : null;
    }

    public boolean isBadgeAssigned(long userId) {
        return getUserEntry(userId) != null;
    }

    public Map<Long, BadgeDTO> getAllBadges() {
        noteBadgeConsumer();
        ServerBadgeSnapshot server = serverBadges;
        Map<Long, BadgeDTO> out = new HashMap<>(server.users.size() + apiBadgeSource.cache.size());
        for (Map.Entry<Long, BadgeEntry> e : server.users.entrySet()) {
            if (e.getValue() != null && e.getValue().getBadge() != null) {
                out.put(e.getKey(), e.getValue().getBadge());
            }
        }
        for (Map.Entry<Long, BadgeEntry> e : apiBadgeSource.cache.entrySet()) {
            BadgeEntry entry = e.getValue() == null ? null : e.getValue().snapshot();
            if (entry != null && BadgePluginOwners.isActive(entry.getPluginOwner())
                    && entry.getBadge() != null) {
                out.put(e.getKey(), entry.getBadge());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public void setBadgeForUser(long userId, BadgeDTO badge) {
        if (badge == null) {
            clearBadgeForUser(userId);
            return;
        }
            apiBadgeSource.cache.put(userId, new BadgeEntry(badge, ProfileStatus.SUPPORTER, false));
        persist();
        apiBadgeSource.forceNotify();
    }

    public void clearBadgeForUser(long userId) {
        boolean removed = apiBadgeSource.clearForHydration(userId);
        if (removed) {
            persist();
            apiBadgeSource.forceNotify();
        }
    }

    public void refresh() {
        noteBadgeConsumer();
        if (!initialized || !refreshPending.compareAndSet(false, true)) return;
        scheduler.execute(() -> {
            try {
                refreshSync();
            } finally {
                refreshPending.set(false);
            }
        });
    }

    private void seedBootstrapEntry() {
        if (apiBadgeSource.cache.isEmpty()) {
            apiBadgeSource.cache.put(BOOTSTRAP_USER_ID,
                    new BadgeEntry(new BadgeDTO(0L, ""), ProfileStatus.DEFAULT, false));
        }
    }

    private BadgeEntry getUserEntry(long userId) {
        noteBadgeConsumer();
        BadgeEntry plugin = visiblePluginEntry(userId);
        return plugin != null ? plugin : serverBadges.users.get(userId);
    }

    private static long pluginChatKey(long chatId) {
        return -Math.abs(chatId);
    }

    private BadgeEntry getChatEntry(long chatId) {
        noteBadgeConsumer();
        BadgeEntry plugin = visiblePluginEntry(pluginChatKey(chatId));
        return plugin != null ? plugin : serverBadges.chats.get(chatId);
    }

    private BadgeEntry visiblePluginEntry(long key) {
        BadgeEntry entry = apiBadgeSource.cache.get(key);
        if (entry != null) entry = entry.snapshot();
        return entry != null && BadgePluginOwners.isActive(entry.getPluginOwner()) ? entry : null;
    }

    private void refreshSync() {
        long now = SystemClock.elapsedRealtime();
        if (lastRefreshAtMs != 0L && now - lastRefreshAtMs < TimeUnit.SECONDS.toMillis(20)) {
            return;
        }
        lastRefreshAtMs = now;
        try {

            ApiClient.BadgeFetchResult fetch = ApiClient.fetchNimarkoBadges();
            if (!fetch.success) {

                return;
            }
            Map<Long, BadgeDTO> nimarko = fetch.badges != null ? fetch.badges : Collections.emptyMap();
            Map<Long, BadgeEntry> nextUsers = new HashMap<>();
            Map<Long, BadgeEntry> nextChats = new HashMap<>();
            for (Map.Entry<Long, BadgeDTO> e : nimarko.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                long id = e.getKey();
                BadgeEntry entry = new BadgeEntry(e.getValue(), ProfileStatus.SUPPORTER, false);
                if (id < -1_000_000_000_000L) {
                    long chatId = -id - 1_000_000_000_000L;
                    nextChats.put(chatId, entry);
                } else {
                    nextUsers.put(id, entry);
                }
            }

            ServerBadgeSnapshot nextServer = new ServerBadgeSnapshot(
                    Collections.unmodifiableMap(nextUsers),
                    Collections.unmodifiableMap(nextChats));
            final boolean changed;
            synchronized (persistLock) {
                ++serverRevision;
                ServerBadgeSnapshot current = serverBadges;
                changed = !current.users.equals(nextServer.users) || !current.chats.equals(nextServer.chats);
                if (changed) serverBadges = nextServer;
                if (changed || cacheMigrationPending || !cacheLoadComplete) schedulePersistLocked();
            }
            if (changed) apiBadgeSource.forceNotify();
        } catch (Throwable t) {
            FileLog.e("nimarko-badges: refresh failed", t);
        }
    }

    private void loadFromDisk(Context ctx) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean("extera_badges_cleared_v1", false)) {
                sp.edit().remove(KEY_CACHE).putBoolean("extera_badges_cleared_v1", true).apply();
            }
                Type t = new TypeToken<LinkedHashMap<Long, BadgeDTO>>(){}.getType();
            final boolean migrate = !sp.getBoolean(KEY_FILE_MIGRATED, false);
                JsonObject disk = null;
                    String json = new BadgeCacheFile(ctx).read();
                    if (json != null) {
                try {
                    disk = GsonHolder.INSTANCE.fromJson(json, JsonObject.class);
                        if (disk == null || !disk.has("version") || disk.get("version").getAsInt() != 1) {
                            throw new IllegalStateException("Unsupported badge cache");
                        }
                } catch (RuntimeException invalidCache) {
                    disk = null;
                    FileLog.e("nimarko-badges: invalid cache file", invalidCache);
                }
            }
            boolean legacy = disk == null && migrate;
                LinkedHashMap<Long, BadgeDTO> persisted = readBadgeMap(disk != null && disk.has("plugins")
                        ? disk.get("plugins").toString() : legacy ? sp.getString(KEY_CACHE, null) : null, t);
                LinkedHashMap<Long, BadgeDTO> persistedServerUsers =
                        readBadgeMap(disk != null && disk.has("users") ? disk.get("users").toString()
                                : legacy ? sp.getString(KEY_SERVER_USER_CACHE, null) : null, t);
                LinkedHashMap<Long, BadgeDTO> persistedServerChats =
                        readBadgeMap(disk != null && disk.has("chats") ? disk.get("chats").toString()
                                : legacy ? sp.getString(KEY_CHAT_CACHE, null) : null, t);
                Map<Long, String> persistedOwners = disk != null && disk.has("owners")
                    ? GsonHolder.INSTANCE.fromJson(disk.get("owners"), new TypeToken<LinkedHashMap<Long, String>>(){}.getType())
                        : Collections.emptyMap();
                if (persistedOwners == null) persistedOwners = Collections.emptyMap();

                if (legacy && sp.getInt(KEY_STORE_VERSION, 0) < STORE_VERSION_SEPARATE_OWNERS) {

                    Set<Long> legacyServerIds = parseLongSet(
                            sp.getStringSet(KEY_API_USERS, Collections.emptySet()));
                    Set<Long> legacyServerChatIds = parseLongSet(
                            sp.getStringSet(KEY_API_CHATS, Collections.emptySet()));
                    for (Long chatId : legacyServerChatIds) {
                        BadgeDTO dto = persisted.remove(chatId);
                        if (dto != null) persistedServerChats.put(chatId, dto);
                    }
                    if (!sp.contains(KEY_API_USERS)) {
                        legacyServerIds = new HashSet<>(persisted.keySet());
                    }
                    for (Long serverId : legacyServerIds) {
                        BadgeDTO dto = persisted.remove(serverId);
                        if (dto != null) persistedServerUsers.put(serverId, dto);
                    }
                }
            Map<Long, BadgeEntry> plugins = new LinkedHashMap<>();
                for (Map.Entry<Long, BadgeDTO> e : persisted.entrySet()) {
                    if (e.getKey() == null || e.getKey() == BOOTSTRAP_USER_ID || e.getValue() == null) continue;

                    BadgeEntry restored = new BadgeEntry(e.getValue(), ProfileStatus.SUPPORTER, false);
                    restored.restorePluginOwner(persistedOwners.get(e.getKey()));
                plugins.put(e.getKey(), restored);
            }
            ServerBadgeSnapshot restoredServer = new ServerBadgeSnapshot(
                        toEntryMap(persistedServerUsers),
                        toEntryMap(persistedServerChats));
            long projectedHash = computeBadgeHash(apiBadgeSource.projectHydration(plugins));
            synchronized (persistLock) {
                if (serverRevision == 0) serverBadges = restoredServer;
                cacheMigrationPending = migrate;
            }
            apiBadgeSource.hydrate(plugins);
            lastBadgeHash = projectedHash;
            boolean startupDirty = apiBadgeSource.finishHydration();
            synchronized (persistLock) {
                cacheLoadComplete = cacheReady = true;
                if (migrate || persistRequested || startupDirty) schedulePersistLocked();
            }
            apiBadgeSource.forceNotify();
        } catch (Exception failure) {
            apiBadgeSource.finishHydration();
            synchronized (persistLock) {
                cacheLoadComplete = true;
            }
            FileLog.e("nimarko-badges: load cache failed", failure);
        }
    }

    private void persist() {
        synchronized (persistLock) {
            schedulePersistLocked();
        }
    }

    private void schedulePersistLocked() {
        final long revision = ++persistRevision;
        persistRequested = true;
        if (!cacheReady) return;
        if (pendingPersist != null) {
            pendingPersist.cancel(false);
        }
        pendingPersist = persistScheduler.schedule(
                () -> persistRevision(revision), PERSIST_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void persistRevision(long revision) {
        synchronized (persistLock) {

            if (revision != persistRevision || !cacheReady) return;
            pendingPersist = null;
        }
        persistSnapshot(revision);
    }

    private void persistSnapshot(long revision) {
        try {
            Context ctx = cacheContext;
            if (ctx == null) return;
            LinkedHashMap<Long, BadgeDTO> pluginSnapshot = new LinkedHashMap<>(apiBadgeSource.cache.size());
            LinkedHashMap<Long, String> pluginOwners = new LinkedHashMap<>();
            for (Map.Entry<Long, BadgeEntry> e : apiBadgeSource.cache.entrySet()) {
                if (e.getKey() == null || e.getKey() == BOOTSTRAP_USER_ID) continue;
                BadgeEntry entry = e.getValue() == null ? null : e.getValue().snapshot();
                if (entry == null || entry.getBadge() == null) continue;
                BadgePluginOwners.Owner owner = entry.getPluginOwner();
                if (!BadgePluginOwners.isActive(owner)) continue;
                pluginSnapshot.put(e.getKey(), copyBadge(entry.getBadge()));
                pluginOwners.put(e.getKey(), owner.pluginId);
            }
            ServerBadgeSnapshot server = serverBadges;
            LinkedHashMap<Long, BadgeDTO> serverUserSnapshot = badgeSnapshot(server.users);
            LinkedHashMap<Long, BadgeDTO> chatSnapshot = badgeSnapshot(server.chats);
            JsonObject disk = new JsonObject();
            disk.addProperty("version", 1);
            disk.add("plugins", GsonHolder.INSTANCE.toJsonTree(pluginSnapshot));
            disk.add("owners", GsonHolder.INSTANCE.toJsonTree(pluginOwners));
            disk.add("users", GsonHolder.INSTANCE.toJsonTree(serverUserSnapshot));
            disk.add("chats", GsonHolder.INSTANCE.toJsonTree(chatSnapshot));
            String json = GsonHolder.INSTANCE.toJson(disk);
            synchronized (persistLock) {
                if (revision != persistRevision) return;
            }
            new BadgeCacheFile(ctx).write(json);
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean committed = sp.edit()
                    .putBoolean(KEY_FILE_MIGRATED, true)
                    .remove(KEY_CACHE)
                    .remove(KEY_SERVER_USER_CACHE)
                    .remove(KEY_CHAT_CACHE)
                    .remove(KEY_STORE_VERSION)
                    .remove(KEY_API_USERS)
                    .remove(KEY_API_CHATS)
                    .commit();
            synchronized (persistLock) {
            cacheMigrationPending = !committed;
                if (committed && revision == persistRevision) persistRequested = false;
            }
            if (!committed) {
                FileLog.e("nimarko-badges: persist commit failed");
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-badges: persist failed", t);
        }
    }

    private static Set<Long> parseLongSet(Set<String> values) {
        Set<Long> out = new HashSet<>();
        if (values != null) for (String value : values) {
            try { out.add(Long.parseLong(value)); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static LinkedHashMap<Long, BadgeDTO> readBadgeMap(String json, Type type) {
        if (TextUtils.isEmpty(json)) return new LinkedHashMap<>();
        if (json.length() > BadgeCacheFile.MAX_BYTES) throw new IllegalArgumentException("Badge cache too large");
        LinkedHashMap<Long, BadgeDTO> map = GsonHolder.INSTANCE.fromJson(json, type);
        return map != null ? map : new LinkedHashMap<>();
    }

    private static Map<Long, BadgeEntry> toEntryMap(Map<Long, BadgeDTO> badges) {
        Map<Long, BadgeEntry> entries = new HashMap<>();
        for (Map.Entry<Long, BadgeDTO> e : badges.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                entries.put(e.getKey(), new BadgeEntry(e.getValue(), ProfileStatus.SUPPORTER, false));
            }
        }
        return Collections.unmodifiableMap(entries);
    }

    private static LinkedHashMap<Long, BadgeDTO> badgeSnapshot(Map<Long, BadgeEntry> entries) {
        LinkedHashMap<Long, BadgeDTO> snapshot = new LinkedHashMap<>(entries.size());
        for (Map.Entry<Long, BadgeEntry> e : entries.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue().getBadge() != null) {
                snapshot.put(e.getKey(), copyBadge(e.getValue().getBadge()));
            }
        }
        return snapshot;
    }
    private static BadgeDTO copyBadge(BadgeDTO badge) {
        return new BadgeDTO(badge.getDocumentId(), badge.getText());
    }
}
