package app.nimarkogram.messenger.banners;

import android.text.TextUtils;
import android.util.AtomicFile;

import app.nimarkogram.messenger.utils.NimarkoInlineAuth;

import androidx.annotation.StringRes;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class NimarkoBannerController {

    private static final long FAIL_CD       = 30_000L;   
    private static final long VER_CHECK_INT = 60_000L;   
    private static final long STATUS_INT    = 300_000L;  
    private static final long MIN_VID       = 10_000L;   
    private static final long MIN_IMG       = 1_000L;    

    private static final String[] ALLOWED_EXT = {".mp4", ".jpg", ".jpeg", ".png"};

    private static volatile NimarkoBannerController instance;

    public static NimarkoBannerController getInstance() {
        NimarkoBannerController local = instance;
        if (local == null) {
            synchronized (NimarkoBannerController.class) {
                local = instance;
                if (local == null) {
                    local = new NimarkoBannerController();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static final class Meta {
        String version = "";
        String type = "jpg";
        boolean hasSound;
        boolean hideAvatar;
    }

    private static final class Scope {
        final int account;
        final long uid;
        Scope(int account, long uid) { this.account = account; this.uid = uid; }
        String fileTag() { return account + "_" + uid; }
        @Override public boolean equals(Object o) {
            return o instanceof Scope && ((Scope) o).account == account && ((Scope) o).uid == uid;
        }
        @Override public int hashCode() { return 31 * account + Long.hashCode(uid); }
    }

    private static final class CacheKey {
        final Scope scope;
        final long eid;
        CacheKey(Scope scope, long eid) { this.scope = scope; this.eid = eid; }
        @Override public boolean equals(Object o) {
            return o instanceof CacheKey && ((CacheKey) o).eid == eid && ((CacheKey) o).scope.equals(scope);
        }
        @Override public int hashCode() { return 31 * scope.hashCode() + Long.hashCode(eid); }
    }

    private volatile String myStatus = "none";
    private volatile boolean myHideAvatar;
    private volatile boolean myHasSound;
    private volatile String myStatusRaw; 
    private volatile boolean statusEverFetched;

    private final Map<CacheKey, String> cachedBanners = new ConcurrentHashMap<>();
    private final Map<String, CacheKey> bannerByPath = new ConcurrentHashMap<>();
    private final Map<CacheKey, Meta> bannersMeta = new ConcurrentHashMap<>();
    private final Set<CacheKey> loading = ConcurrentHashMap.newKeySet();
    private final Set<CacheKey> usersNoBanner = ConcurrentHashMap.newKeySet();
    private final Set<CacheKey> verChecking = ConcurrentHashMap.newKeySet();
    private final Map<CacheKey, Long> failTimes = new ConcurrentHashMap<>();
    private final Map<CacheKey, Long> checkTimes = new ConcurrentHashMap<>();
    private final Map<CacheKey, Long> existsTimes = new ConcurrentHashMap<>();
    private final Set<Scope> statusFetching = ConcurrentHashMap.newKeySet();

    private final Object cacheLock = new Object();
    private final Object authLock = new Object();
    private final Object indexPersistenceLock = new Object();
    private final Object statusStateLock = new Object();
    private final Object localBannerLock = new Object();
    private final Map<Scope, Long> indexRevisions = new HashMap<>();
    private long statusRevision;
    private long statusActivityRevision;
    private volatile long localBannerGeneration;
    private final Gson gson = new Gson();

    private final ExecutorService executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "nimarko-banner");
            t.setDaemon(true);
            return t;
        }
    });

    private String storageDir = "";
    private String cacheFolder = "";
    private File placeholderFile;
    private volatile boolean phReady;
    private volatile boolean phDownloading;
    private volatile boolean started;
    private volatile Thread statusLoopThread;
    private volatile Scope currentScope;

    private volatile Runnable settingsReloader;

    private NimarkoBannerController() {}


    public void ensureStarted() {
        if (started) {
            if (NimarkoBannerConfig.enabled) startStatusLoop();
            return;
        }
        synchronized (this) {
            if (started) return;
            try {
                File folder = new File(ApplicationLoader.getFilesDirFixed(), "nimarkobanner");
                if (!folder.exists()) //noinspection ResultOfMethodCallIgnored
                    folder.mkdirs();
                storageDir = folder.getAbsolutePath();
                File cache = new File(folder, "cache");
                if (!cache.exists()) //noinspection ResultOfMethodCallIgnored
                    cache.mkdirs();
                cacheFolder = cache.getAbsolutePath();
                File shared = new File(ApplicationLoader.getFilesDirFixed().getParentFile(), "cache/ettacent_shared");
                if (!shared.exists()) //noinspection ResultOfMethodCallIgnored
                    shared.mkdirs();
                placeholderFile = new File(shared, "zaglus.mp4");
            } catch (Throwable t) {
                FileLog.e("nimarko-banner: dir setup failed", t);
            }
            started = true;
            myId();
            if (NimarkoBannerConfig.enabled) startStatusLoop();
        }
    }

    public synchronized void setPollingEnabled(boolean enabled) {
        if (enabled) {
            ensureStarted();
            startStatusLoop();
        } else {
            Thread thread = statusLoopThread;
            statusLoopThread = null;
            if (thread != null) thread.interrupt();
        }
    }

    public void setSettingsReloader(Runnable r) { settingsReloader = r; }

    private void reloadSettings() {
        Runnable r = settingsReloader;
        if (r != null) AndroidUtilities.runOnUIThread(r);
    }


    public long myId() {
        int acc = UserConfig.selectedAccount;
        long uid;
        try {
            uid = UserConfig.getInstance(acc).getClientUserId();
        } catch (Throwable t) {
            uid = 0L;
        }
        Scope scope = currentScope;
        if (scope == null || scope.account != acc || scope.uid != uid) {
            switchScope(new Scope(acc, uid));
        }
        return uid;
    }

    private void switchScope(Scope next) {
        Scope previous;
        synchronized (this) {
            previous = currentScope;
            if (next.equals(previous)) return;
            synchronized (statusStateLock) {
                statusRevision++;
                statusActivityRevision++;
                myStatus = "none";
                myStatusRaw = null;
                statusEverFetched = false;
                myHideAvatar = false;
                myHasSound = false;
            }
            synchronized (localBannerLock) {
                localBannerGeneration++;
            }
            clearMemoryCaches();
            long displacedUid = NimarkoBannerConfig.activateScope(next.account, next.uid);
            NimarkoBannerConfig.reloadAccount();
            synchronized (statusStateLock) {
                currentScope = next;
            }
            if (started && next.uid != 0L) {
                final long indexStartRevision;
                synchronized (indexPersistenceLock) {
                    indexStartRevision = indexRevisions.getOrDefault(next, 0L);
                }
                final Scope displacedScope = displacedUid != 0L
                        ? new Scope(next.account, displacedUid) : null;
                executor.submit(() -> {
                    if (displacedScope != null) {
                        cleanupScopeFiles(displacedScope);
                    }
                    if (!isCurrentScope(next)) return;
                    migrateLegacyScopeFiles(next);
                    readStatusCache(next);
                    readIndex(next, indexStartRevision);
                    if (isCurrentScope(next)) {
                        reloadSettings();
                        AndroidUtilities.runOnUIThread(this::invalidate);
                    }
                });
            }
        }
        if (previous != null) invalidate();
    }

    private void clearMemoryCaches() {
        synchronized (cacheLock) {
            cachedBanners.clear();
            bannerByPath.clear();
            bannersMeta.clear();
            loading.clear();
            usersNoBanner.clear();
            verChecking.clear();
            failTimes.clear();
            checkTimes.clear();
            existsTimes.clear();
        }
    }

    private boolean isCurrentScope(Scope scope) {
        try {
            return scope != null && scope.equals(currentScope)
                    && scope.account == UserConfig.selectedAccount
                    && scope.uid == UserConfig.getInstance(scope.account).getClientUserId();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isScopeOwner(Scope scope) {
        try {
            return scope != null && scope.uid != 0L
                    && scope.uid == UserConfig.getInstance(scope.account).getClientUserId();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Scope scope() {
        myId();
        return currentScope;
    }

    private CacheKey key(long eid) { return new CacheKey(scope(), eid); }
    private static CacheKey key(Scope scope, long eid) { return new CacheKey(scope, eid); }

    private void migrateLegacyScopeFiles(Scope scope) {
        File scopedStatus = statusFile(scope);
        File legacyStatus = new File(storageDir, "server_status_" + scope.account + ".json");
        if (!scopedStatus.exists() && legacyStatus.exists()) {
            //noinspection ResultOfMethodCallIgnored
            legacyStatus.renameTo(scopedStatus);
        }
        File scopedIndex = indexFile(scope);
        File legacyIndex = new File(cacheFolder, "cache_index.json");
        if (!scopedIndex.exists() && legacyIndex.exists()) {
            //noinspection ResultOfMethodCallIgnored
            legacyIndex.renameTo(scopedIndex);
        }
    }

    private void cleanupScopeFiles(Scope scope) {
        safeRemove(statusFile(scope));
        safeRemove(indexFile(scope));
        synchronized (indexPersistenceLock) {
            indexRevisions.remove(scope);
        }
        File[] files = new File(cacheFolder).listFiles();
        if (files != null) {
            String prefix = scope.fileTag() + "_";
            for (File file : files) {
                if (file.getName().startsWith(prefix)) safeRemove(file);
            }
        }
        for (String ext : ALLOWED_EXT) {
            safeRemove(new File(storageDir, "local_banner_" + scope.fileTag() + ext));
        }
    }


    public static final class Resolved {
        public final String path;
        public final boolean isVideo;
        public final boolean loading;
        Resolved(String path, boolean isVideo, boolean loading) {
            this.path = path; this.isVideo = isVideo; this.loading = loading;
        }
    }

    public Resolved resolve(long eid) {
        long my = myId();
        CacheKey k = key(eid);
        String bf = null;
        boolean isLoading = false;
        if (eid == my) {
            if ("approved".equals(myStatus)) {
                bf = findCachedBanner(k);
                if (NimarkoBannerRenderer.DBG) NimarkoBannerRenderer.dbg("resolve OWN approved findCachedBanner=" + bf
                        + " inNoBanner=" + usersNoBanner.contains(k) + " loading=" + loading.contains(k));
                if (bf == null && !usersNoBanner.contains(k)) {
                    loadBannerAsync(k);
                    isLoading = true;
                }
            } else {
                String lp = NimarkoBannerConfig.localBannerPath;
                if (NimarkoBannerRenderer.DBG) NimarkoBannerRenderer.dbg("resolve OWN NOT-approved myStatus=" + myStatus
                        + " localBannerPath=" + lp);
                if (!TextUtils.isEmpty(lp)) bf = lp;
            }
        } else {
            bf = findCachedBanner(k);
            if (bf == null && !usersNoBanner.contains(k)) {
                loadBannerAsync(k);
                isLoading = true;
            }
        }
        boolean iv = bf != null && isVideo(bf);
        return new Resolved(bf, iv, isLoading);
    }

    public void maybeKickLoad(long eid) {
        CacheKey k = key(eid);
        if (!cachedBanners.containsKey(k) && !loading.contains(k)
                && !usersNoBanner.contains(k)
                && now() - getOr(failTimes, k) > FAIL_CD) {
            loadBannerAsync(k);
        }
    }

    public boolean shouldHideAvatar(long eid) {
        long my = myId();
        if (eid == my && !"approved".equals(myStatus)) return false;
        CacheKey k = key(eid);
        String p = cachedBanners.get(k);
        if (p == null) return false;
        Meta m = bannersMeta.get(k);
        return m != null && m.hideAvatar;
    }

    public boolean hasNoRealBanner(long eid) {
        long my = myId();
        if (eid == my) {
            if ("approved".equals(myStatus)) return false; 
            if (!statusEverFetched) return false;
            return TextUtils.isEmpty(NimarkoBannerConfig.localBannerPath);
        }
        return usersNoBanner.contains(key(eid));
    }

    public boolean hasSound(long eid) {
        long my = myId();
        if (eid == my) return myHasSound;
        Meta m = bannersMeta.get(key(eid));
        return m != null && m.hasSound;
    }

    public long eidForPath(String path) {
        CacheKey e = bannerByPath.get(path);
        return e == null || !e.scope.equals(scope()) ? 0 : e.eid;
    }


    public boolean placeholderReady() { return phReady && placeholderFile != null; }
    public String placeholderPath() { return placeholderFile != null ? placeholderFile.getAbsolutePath() : ""; }
    public boolean hasMeta(long eid) { return bannersMeta.containsKey(key(eid)); }

    private void dlPlaceholder() {
        if (phDownloading || placeholderFile == null) return;
        phDownloading = true;
        try {
            if (placeholderFile.exists() && placeholderFile.length() > MIN_VID) {
                phReady = true;
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            placeholderFile.delete();
            if (NimarkoBannerHttp.download(NimarkoBannerHttp.PLACEHOLDER_URL, placeholderFile)
                    && placeholderFile.length() > MIN_VID) {
                phReady = true;
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-banner: placeholder dl failed", t);
        } finally {
            phDownloading = false;
        }
    }


    private String findCachedBanner(CacheKey k) {
        String p = cachedBanners.get(k);
        if (p == null) return null;
        long now = now();
        if (now - getOr(existsTimes, k) > FAIL_CD) {
            existsTimes.put(k, now);
            final String expectedPath = p;
            executor.submit(() -> verifyCachedBanner(k, expectedPath));
        }
        if (now - getOr(checkTimes, k) > VER_CHECK_INT && verChecking.add(k)) {
            checkTimes.put(k, now);
            executor.submit(() -> syncBanner(k, false, true));
        }
        return p;
    }

    private void verifyCachedBanner(CacheKey key, String expectedPath) {
        if (new File(expectedPath).exists()) {
            return;
        }
        synchronized (cacheLock) {
            if (!expectedPath.equals(cachedBanners.get(key))) {
                return;
            }
            cachedBanners.remove(key);
            bannerByPath.remove(expectedPath);
            bannersMeta.remove(key);
            checkTimes.remove(key);
            existsTimes.remove(key);
        }
        writeIndexAsync(key.scope);
        loadBannerAsync(key);
        invalidate();
    }

    private void loadBannerAsync(CacheKey k) {
        if (k == null || !isCurrentScope(k.scope)) return;
        if (!loading.add(k)) return;
        executor.submit(() -> syncBanner(k, true, false));
    }

    private void syncBanner(CacheKey k, boolean download, boolean checkingClaimed) {
        final long eid = k.eid;
        boolean checking = !download;
        if (checking && !checkingClaimed && !verChecking.add(k)) return;
        try {
            if (!isCurrentScope(k.scope)) return;
            NimarkoBannerHttp.BannerInfo info = NimarkoBannerHttp.getBanner(eid);
            if (!isCurrentScope(k.scope)) return;
            NimarkoBannerRenderer.dbg("syncBanner eid=" + eid + " download=" + download
                    + " httpCode=" + info.httpCode + " hasBanner=" + info.hasBanner
                    + " type=" + info.type + " version=" + info.version
                    + " url=" + info.url);
            if (info.httpCode == 404) {
                NimarkoBannerRenderer.dbg("syncBanner 404 → usersNoBanner.add " + eid);
                rmCached(k); usersNoBanner.add(k); invalidate(); return;
            }
            if (info.httpCode != 200) {
                NimarkoBannerRenderer.dbg("syncBanner httpCode!=200 (" + info.httpCode + ") → fail " + eid);
                if (download) failTimes.put(k, now());
                return;
            }
            if (!info.hasBanner) {
                NimarkoBannerRenderer.dbg("syncBanner hasBanner=false → usersNoBanner.add " + eid);
                rmCached(k); usersNoBanner.add(k); invalidate(); return;
            }
            boolean needDl = false, upd = false, hasCached;
            synchronized (cacheLock) {
                Meta lm = bannersMeta.get(k);
                boolean fileCached = cachedBanners.containsKey(k)
                        && cachedBanners.get(k) != null && new File(cachedBanners.get(k)).exists();
                if (!TextUtils.isEmpty(info.version)
                        && (lm == null || !info.version.equals(lm.version) || !fileCached)) {
                    rmCachedUnlocked(k);
                    usersNoBanner.remove(k);
                    needDl = true;
                } else {
                    if (lm == null) lm = new Meta();
                    if (lm.hasSound != info.hasSound) { lm.hasSound = info.hasSound; upd = true; }
                    if (lm.hideAvatar != info.hideAvatar) { lm.hideAvatar = info.hideAvatar; upd = true; }
                    if (upd) bannersMeta.put(k, lm);
                }
                hasCached = cachedBanners.containsKey(k);
            }
            NimarkoBannerRenderer.dbg("syncBanner needDl=" + needDl + " hasCached=" + hasCached + " upd=" + upd + " " + eid);
            if (!needDl) {
                if (upd) writeIndexAsync(k.scope);
                if (hasCached && !checking) invalidate();
                return;
            }
            if (checking) {
                NimarkoBannerRenderer.dbg("syncBanner version changed → re-download " + eid);
                writeIndexAsync(k.scope);
                loading.remove(k);
                loadBannerAsync(k);
                return;
            }
            if (TextUtils.isEmpty(info.url)) { NimarkoBannerRenderer.dbg("syncBanner EMPTY url → fail " + eid); failTimes.put(k, now()); return; }
            String filePrefix = k.scope.fileTag() + "_" + eid;
            for (String oe : ALLOWED_EXT) safeRemove(new File(cacheFolder, filePrefix + oe));
            File[] priorFiles = new File(cacheFolder).listFiles();
            if (priorFiles != null) {
                String verPrefix = filePrefix + "_";
                for (File pf : priorFiles) {
                    if (pf.getName().startsWith(verPrefix)) safeRemove(pf);
                }
            }
            synchronized (cacheLock) {
                String old = cachedBanners.remove(k);
                if (old != null) bannerByPath.remove(old);
            }
            String ext = "mp4".equals(info.type) ? ".mp4" : "." + info.type;
            String vtag = TextUtils.isEmpty(info.version) ? "" : "_" + info.version.replaceAll("[^A-Za-z0-9]", "");
            File cp = new File(cacheFolder, filePrefix + vtag + ext);
            NimarkoBannerRenderer.dbg("syncBanner DOWNLOAD start url=" + info.url + " → " + cp.getAbsolutePath());
            boolean dlok = NimarkoBannerHttp.download(info.url, cp);
            NimarkoBannerRenderer.dbg("syncBanner DOWNLOAD result=" + dlok + " exists=" + cp.exists() + " size=" + (cp.exists() ? cp.length() : -1));
            if (!dlok || !validDownload(cp, info.type)) {
                safeRemove(cp);
                failTimes.put(k, now());
                return;
            }
            if (!isCurrentScope(k.scope)) { safeRemove(cp); return; }
            synchronized (cacheLock) {
                cachedBanners.put(k, cp.getAbsolutePath());
                bannerByPath.put(cp.getAbsolutePath(), k);
                Meta m = new Meta();
                m.version = info.version; m.type = info.type;
                m.hasSound = info.hasSound; m.hideAvatar = info.hideAvatar;
                bannersMeta.put(k, m);
                long n = now();
                checkTimes.put(k, n); existsTimes.put(k, n);
                usersNoBanner.remove(k);
            }
            NimarkoBannerRenderer.dbg("syncBanner CACHED OK " + eid + " → " + cp.getAbsolutePath());
            writeIndexAsync(k.scope);
            invalidate();
        } catch (Throwable t) {
            NimarkoBannerRenderer.dbg("syncBanner EXCEPTION " + eid + " : " + t);
            if (download) failTimes.put(k, now());
        } finally {
            if (download) loading.remove(k);
            if (checking) verChecking.remove(k);
        }
    }

    private void rmCached(CacheKey k) {
        synchronized (cacheLock) { rmCachedUnlocked(k); }
        writeIndexAsync(k.scope);
    }

    private void rmCachedUnlocked(CacheKey k) {
        String old = cachedBanners.remove(k);
        if (old != null) {
            bannerByPath.remove(old);
            safeRemove(new File(old));
        }
        bannersMeta.remove(k);
        checkTimes.remove(k);
        existsTimes.remove(k);
    }


    private File indexFile(Scope scope) { return new File(cacheFolder, "cache_index_" + scope.fileTag() + ".json"); }

    private void readIndex(Scope scope, long startRevision) {
        try {
            if (!isCurrentScope(scope)) return;
            JsonObject d = readJson(indexFile(scope));
            if (d == null || !isCurrentScope(scope)) return;
            long diskRevision = 0L;
            if (d.has("revision") && d.get("revision").isJsonPrimitive()) {
                try { diskRevision = Math.max(0L, d.get("revision").getAsLong()); } catch (Throwable ignored) {}
            }
            JsonObject paths = d.has("paths") && d.get("paths").isJsonObject() ? d.getAsJsonObject("paths") : new JsonObject();
            JsonObject meta = d.has("meta") && d.get("meta").isJsonObject() ? d.getAsJsonObject("meta") : new JsonObject();
            long now = now();
            ArrayList<CacheKey> redl = new ArrayList<>();
            synchronized (indexPersistenceLock) {
                if (indexRevisions.getOrDefault(scope, 0L) != startRevision
                        || !isCurrentScope(scope)) {
                    return;
                }
                synchronized (cacheLock) {
                    if (!isCurrentScope(scope)) return;
                    for (Map.Entry<String, com.google.gson.JsonElement> e : paths.entrySet()) {
                        long eid;
                        try { eid = Long.parseLong(e.getKey()); } catch (NumberFormatException nf) { continue; }
                        if (!e.getValue().isJsonPrimitive()) continue;
                        String v = e.getValue().getAsString();
                        CacheKey k = new CacheKey(scope, eid);
                        File f = new File(v);
                        if (f.exists()) {
                            if (isVideo(v) && f.length() < MIN_VID) { safeRemove(f); redl.add(k); }
                            else {
                                cachedBanners.put(k, v);
                                bannerByPath.put(v, k);
                                existsTimes.put(k, now);
                            }
                        } else redl.add(k);
                    }
                    for (Map.Entry<String, com.google.gson.JsonElement> e : meta.entrySet()) {
                        long eid;
                        try { eid = Long.parseLong(e.getKey()); } catch (NumberFormatException nf) { continue; }
                        if (!e.getValue().isJsonObject()) continue;
                        JsonObject mo = e.getValue().getAsJsonObject();
                        Meta m = new Meta();
                        if (mo.has("version") && mo.get("version").isJsonPrimitive()) m.version = mo.get("version").getAsString();
                        if (mo.has("type") && mo.get("type").isJsonPrimitive()) m.type = mo.get("type").getAsString();
                        if (mo.has("has_sound") && mo.get("has_sound").isJsonPrimitive()) m.hasSound = mo.get("has_sound").getAsBoolean();
                        if (mo.has("hide_avatar") && mo.get("hide_avatar").isJsonPrimitive()) m.hideAvatar = mo.get("hide_avatar").getAsBoolean();
                        bannersMeta.put(new CacheKey(scope, eid), m);
                    }
                    indexRevisions.put(scope, Math.max(startRevision, diskRevision));
                }
            }
            if (!isCurrentScope(scope)) return;
            for (CacheKey k : redl) {
                if (!usersNoBanner.contains(k)) loadBannerAsync(k);
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-banner: readIndex failed", t);
        }
    }

    private void writeIndexAsync(Scope scope) {
        if (scope == null || scope.uid == 0L) return;
        final long revision;
        synchronized (indexPersistenceLock) {
            revision = indexRevisions.getOrDefault(scope, 0L) + 1L;
            indexRevisions.put(scope, revision);
        }
        executor.submit(() -> writeIndex(scope, revision));
    }

    private void writeIndex(Scope scope, long revision) {
        try {
            if (!isCurrentScope(scope)) return;
            JsonObject paths = new JsonObject();
            JsonObject meta = new JsonObject();
            synchronized (cacheLock) {
                for (Map.Entry<CacheKey, String> e : cachedBanners.entrySet()) {
                    if (!scope.equals(e.getKey().scope)) continue;
                    paths.addProperty(String.valueOf(e.getKey().eid), e.getValue());
                }
                for (Map.Entry<CacheKey, Meta> e : bannersMeta.entrySet()) {
                    if (!scope.equals(e.getKey().scope)) continue;
                    Meta m = e.getValue();
                    JsonObject mo = new JsonObject();
                    mo.addProperty("version", m.version);
                    mo.addProperty("type", m.type);
                    mo.addProperty("has_sound", m.hasSound);
                    mo.addProperty("hide_avatar", m.hideAvatar);
                    meta.add(String.valueOf(e.getKey().eid), mo);
                }
            }
            JsonObject root = new JsonObject();
            root.addProperty("revision", revision);
            root.add("paths", paths);
            root.add("meta", meta);
            synchronized (indexPersistenceLock) {
                if (!isCurrentScope(scope)
                        || indexRevisions.getOrDefault(scope, 0L) != revision) {
                    return;
                }
                writeJsonAtomic(indexFile(scope), root);
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-banner: writeIndex failed", t);
        }
    }


    public String statusString() { return myStatus; }
    public boolean hideAvatarFlag() { return myHideAvatar; }

    private File statusFile(Scope scope) { return new File(storageDir, "server_status_" + scope.fileTag() + ".json"); }

    private void readStatusCache(Scope scope) {
        try {
            final long startRevision;
            final long startActivityRevision;
            synchronized (statusStateLock) {
                if (!isCurrentScope(scope) || statusFetching.contains(scope)) return;
                startRevision = statusRevision;
                startActivityRevision = statusActivityRevision;
            }
            JsonObject d = readJson(statusFile(scope));
            if (d == null || !isCurrentScope(scope)) return;
            synchronized (statusStateLock) {
                if (!isCurrentScope(scope)
                        || statusRevision != startRevision
                        || statusActivityRevision != startActivityRevision) {
                    return;
                }
                if (d.has("_client_revision") && d.get("_client_revision").isJsonPrimitive()) {
                    try { statusRevision = Math.max(statusRevision, d.get("_client_revision").getAsLong()); }
                    catch (Throwable ignored) {}
                }
                if (d.has("status") && d.get("status").isJsonPrimitive()) { myStatus = d.get("status").getAsString(); statusEverFetched = true; }
                if (d.has("hide_avatar") && d.get("hide_avatar").isJsonPrimitive()) myHideAvatar = d.get("hide_avatar").getAsBoolean();
                if (d.has("has_sound") && d.get("has_sound").isJsonPrimitive()) myHasSound = d.get("has_sound").getAsBoolean();
                myStatusRaw = d.toString();
            }
        } catch (Throwable ignored) {}
    }

    private void writeStatusCacheLocked(Scope scope, String rawJson, long revision) {
        try {
            if (rawJson != null && isCurrentScope(scope) && statusRevision == revision) {
                JsonObject d = gson.fromJson(rawJson, JsonObject.class);
                if (d != null) {
                    d.addProperty("_client_revision", revision);
                    writeJsonAtomic(statusFile(scope), d);
                    myStatusRaw = d.toString();
                }
            }
        } catch (Throwable ignored) {}
    }

    private long beginStatusMutation(Scope scope) {
        synchronized (statusStateLock) {
            if (!isCurrentScope(scope)) return -1L;
            statusActivityRevision++;
            return ++statusRevision;
        }
    }

    private boolean statusRevisionMatches(Scope scope, long revision) {
        return revision >= 0L && isCurrentScope(scope) && statusRevision == revision;
    }

    private long finishStatusMutationLocked(Scope scope, long revision) {
        if (!statusRevisionMatches(scope, revision)) return -1L;
        return ++statusRevision;
    }

    private void persistCurrentStatusLocked(Scope scope, long revision) {
        JsonObject d = null;
        try {
            if (!TextUtils.isEmpty(myStatusRaw)) d = gson.fromJson(myStatusRaw, JsonObject.class);
        } catch (Throwable ignored) {}
        if (d == null) d = new JsonObject();
        d.addProperty("status", myStatus);
        d.addProperty("hide_avatar", myHideAvatar);
        d.addProperty("has_sound", myHasSound);
        writeStatusCacheLocked(scope, d.toString(), revision);
    }

    public boolean fetchStatus() {
        return fetchStatus(scope());
    }

    private boolean fetchStatus(Scope scope) {
        if (scope == null || scope.uid == 0L || !statusFetching.add(scope)) return false;
        final long requestRevision;
        synchronized (statusStateLock) {
            if (!isCurrentScope(scope)) {
                statusFetching.remove(scope);
                return false;
            }
            statusActivityRevision++;
            requestRevision = statusRevision;
        }
        try {
            NimarkoBannerHttp.Status s = NimarkoBannerHttp.fetchStatus(scope.uid);
            if (!s.ok) return false;
            final String old;
            synchronized (statusStateLock) {
                if (!statusRevisionMatches(scope, requestRevision)) return false;
                statusEverFetched = true;
                old = myStatus;
                myStatus = s.status;
                myHideAvatar = s.hideAvatar;
                myHasSound = s.hasSound;
                myStatusRaw = s.rawJson;
                writeStatusCacheLocked(scope, s.rawJson, requestRevision);
            }
            if ("pending".equals(old) && "approved".equals(s.status)) {
                CacheKey ownKey = key(scope, scope.uid);
                clearMyCache(ownKey);
                loadBannerAsync(ownKey);
            }
            if (!old.equals(myStatus)) invalidate();
            return true;
        } finally {
            statusFetching.remove(scope);
        }
    }

    private synchronized void startStatusLoop() {
        if (!NimarkoBannerConfig.enabled || statusLoopThread != null) return;
        Thread t = new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            while (Thread.currentThread() == statusLoopThread && NimarkoBannerConfig.enabled) {
                fetchStatus();
                reloadSettings();
                try { Thread.sleep(STATUS_INT); } catch (InterruptedException ie) { return; }
            }
        }, "nimarko-banner-status");
        t.setDaemon(true);
        statusLoopThread = t;
        t.start();
    }

    private void clearMyCache(CacheKey k) {
        synchronized (cacheLock) {
            String old = cachedBanners.remove(k);
            if (old != null) { bannerByPath.remove(old); safeRemove(new File(old)); }
            bannersMeta.remove(k);
            checkTimes.remove(k);
            existsTimes.remove(k);
            usersNoBanner.remove(k);
        }
        writeIndexAsync(k.scope);
    }


    private String ensureToken(final Scope scope) {
        if (!isScopeOwner(scope)) return null;
        return NimarkoInlineAuth.ensureToken(scope.account, authLock, new NimarkoInlineAuth.Backend() {
            @Override public String cachedToken() {
                return NimarkoBannerConfig.getAuthToken(scope.account, scope.uid);
            }
            @Override public void cacheToken(String t) {
                NimarkoBannerConfig.setAuthToken(scope.account, scope.uid, t);
            }
            @Override public NimarkoInlineAuth.Reg register(long uid) {
                NimarkoBannerHttp.AuthRegister r = NimarkoBannerHttp.authRegister(uid);
                NimarkoInlineAuth.Reg reg = new NimarkoInlineAuth.Reg();
                reg.code = r.code;
                reg.botUsername = r.botUsername;
                reg.ok = r.ok;
                return reg;
            }
            @Override public String poll(long uid, String code) {
                String t = NimarkoBannerHttp.authPoll(uid, code);
                return NimarkoBannerHttp.AUTH_GIVE_UP.equals(t) ? NimarkoInlineAuth.GIVE_UP : t;
            }
        }, () -> isScopeOwner(scope));
    }


    public void submitModeration(File tmp, String ext, long size) {
        final Scope operationScope = scope();
        final long operationRevision = beginStatusMutation(operationScope);
        executor.submit(() -> {
            try {
                long my = operationScope.uid;
                if (my == 0 || operationRevision < 0L) { uiErr(R.string.NM_BAN_IdError); safeRemove(tmp); return; }
                uiInfo(R.string.NM_BAN_Sending);
                String token = ensureToken(operationScope);
                if (TextUtils.isEmpty(token)) { safeRemove(tmp); uiErr(R.string.NM_BAN_IdError); return; }
                NimarkoBannerHttp.SubmitResult r = NimarkoBannerHttp.submit(tmp, ext, my, size, token);
                if (r.httpCode == 401) {
                    NimarkoBannerConfig.setAuthToken(operationScope.account, operationScope.uid, "");
                    token = ensureToken(operationScope);
                    if (!TextUtils.isEmpty(token)) r = NimarkoBannerHttp.submit(tmp, ext, my, size, token);
                }
                safeRemove(tmp);
                if (!isCurrentScope(operationScope)) return;
                if (r.httpCode == 200) {
                    if (r.success) {
                        synchronized (statusStateLock) {
                            long committedRevision = finishStatusMutationLocked(operationScope, operationRevision);
                            if (committedRevision < 0L) return;
                            myStatus = "pending";
                            statusEverFetched = true;
                            persistCurrentStatusLocked(operationScope, committedRevision);
                        }
                        clearMyCache(key(operationScope, operationScope.uid));
                        uiOk(R.string.NM_BAN_SentOk);
                    } else {
                        uiErrText(r.error != null ? r.error : LocaleController.getString(R.string.NM_BAN_StatusUnknown));
                    }
                } else if (r.httpCode == 401) {
                    uiErr(R.string.NM_BAN_IdError);
                } else if (r.httpCode == 413) {
                    uiErr(R.string.NM_BAN_FileTooBig);
                } else if (r.httpCode == 403) {
                    synchronized (statusStateLock) {
                        long committedRevision = finishStatusMutationLocked(operationScope, operationRevision);
                        if (committedRevision < 0L) return;
                        myStatus = "blocked";
                        statusEverFetched = true;
                        persistCurrentStatusLocked(operationScope, committedRevision);
                    }
                    uiErr(R.string.NM_BAN_BlockedError);
                } else if (r.httpCode == 409) {
                    synchronized (statusStateLock) {
                        long committedRevision = finishStatusMutationLocked(operationScope, operationRevision);
                        if (committedRevision < 0L) return;
                        myStatus = "pending";
                        statusEverFetched = true;
                        persistCurrentStatusLocked(operationScope, committedRevision);
                    }
                    uiErr(R.string.NM_BAN_PendingError);
                } else {
                    uiErrText(LocaleController.getString(R.string.NM_BAN_StatusUnknown) + ": " + r.httpCode);
                }
            } catch (Throwable t) {
                safeRemove(tmp);
                FileLog.e("nimarko-banner: submitModeration failed", t);
            } finally {
                reloadSettings();
            }
        });
    }


    public void setHideAvatarRemote(boolean v) {
        final Scope operationScope = scope();
        final long operationRevision = beginStatusMutation(operationScope);
        executor.submit(() -> {
            long my = operationScope.uid;
            if (my == 0 || operationRevision < 0L) { uiErr(R.string.NM_BAN_IdError); return; }
            if (NimarkoBannerHttp.setHideAvatar(my, v)) {
                synchronized (statusStateLock) {
                    long committedRevision = finishStatusMutationLocked(operationScope, operationRevision);
                    if (committedRevision < 0L) return;
                    myHideAvatar = v;
                    statusEverFetched = true;
                    persistCurrentStatusLocked(operationScope, committedRevision);
                }
                uiOk(R.string.NM_BAN_HaUpdated);
            } else {
                uiErr(R.string.NM_BAN_HaError);
            }
            reloadSettings();
        });
    }

    public void refreshStatus() {
        Scope operationScope = scope();
        CacheKey ownKey = key(operationScope, operationScope.uid);
        usersNoBanner.remove(ownKey);
        executor.submit(() -> {
            boolean refreshed = fetchStatus(operationScope);
            reloadSettings();
            if (refreshed) uiOk(R.string.NM_BAN_StatusUpdated);
            else uiErr(R.string.NM_BAN_StatusRefreshFailed);
        });
    }

    public void onAccountSwitched() {
        if (!started) {
            NimarkoBannerConfig.reloadAccount();
            return;
        }
        myId();                         
        Scope operationScope = scope();
        CacheKey ownKey = key(operationScope, operationScope.uid);
        usersNoBanner.remove(ownKey);
        reloadSettings();
        invalidate();
        if (!NimarkoBannerConfig.enabled) return;
        executor.submit(() -> {
            fetchStatus(operationScope);
            reloadSettings();
            if (isCurrentScope(operationScope) && "approved".equals(myStatus)
                    && findCachedBanner(ownKey) == null) {
                loadBannerAsync(ownKey);
            }
            invalidate();
        });
    }

    public void setLocalBanner(File tmp, String ext) {
        final Scope operationScope = scope();
        final long operationGeneration;
        synchronized (localBannerLock) {
            operationGeneration = ++localBannerGeneration;
        }
        executor.submit(() -> {
            String detectedExtension = detectBannerExtension(tmp);
            if (tmp == null || !tmp.isFile() || tmp.length() <= 0 || tmp.length() > NimarkoBannerHttp.MAX_SIZE
                    || detectedExtension == null || !detectedExtension.equals(ext)) {
                safeRemove(tmp);
                uiErr(R.string.NM_BAN_InvalidFormat);
                return;
            }
            File dest = new File(storageDir, "local_banner_" + operationScope.fileTag() + ext);
            boolean saved = false;
            synchronized (localBannerLock) {
                if (operationGeneration != localBannerGeneration
                        || !isCurrentScope(operationScope)) {
                    safeRemove(tmp);
                    return;
                }
                AtomicFile atomic = new AtomicFile(dest);
                FileOutputStream out = null;
                try (FileInputStream in = new FileInputStream(tmp)) {
                    out = atomic.startWrite();
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > NimarkoBannerHttp.MAX_SIZE) throw new java.io.IOException("banner too large");
                        out.write(buffer, 0, n);
                    }
                    atomic.finishWrite(out);
                    out = null;
                    saved = true;
                } catch (Throwable t) {
                    if (out != null) atomic.failWrite(out);
                    FileLog.e("nimarko-banner: local save failed", t);
                } finally {
                    safeRemove(tmp);
                }
                if (saved && operationGeneration == localBannerGeneration
                        && isCurrentScope(operationScope)) {
                    for (String oe : ALLOWED_EXT) {
                        File old = new File(storageDir, "local_banner_" + operationScope.fileTag() + oe);
                        if (!old.equals(dest)) safeRemove(old);
                    }
                    NimarkoBannerConfig.setLocalBannerPath(
                            operationScope.account, operationScope.uid, dest.getAbsolutePath());
                } else if (saved) {
                    safeRemove(dest);
                }
            }
            if (saved && isCurrentScope(operationScope)
                    && operationGeneration == localBannerGeneration) {
                uiOk(R.string.NM_BAN_LocalSet);
                reloadSettings();
                invalidate();
            } else if (!saved) {
                uiErr(R.string.NM_BAN_NoAccess);
            }
        });
    }

    public void removeLocalBanner() {
        Scope operationScope = scope();
        synchronized (localBannerLock) {
            localBannerGeneration++;
            String path = NimarkoBannerConfig.getLocalBannerPath(operationScope.account, operationScope.uid);
            if (!TextUtils.isEmpty(path)) safeRemove(new File(path));
            for (String ext : ALLOWED_EXT) {
                safeRemove(new File(storageDir, "local_banner_" + operationScope.fileTag() + ext));
            }
            NimarkoBannerConfig.setLocalBannerPath(operationScope.account, operationScope.uid, "");
        }
        uiOk(R.string.NM_BAN_LocalDeleted);
        reloadSettings();
    }

    public String storageDir() { return storageDir; }

    public void handleVideoFail(String path) {
        try {
            if (placeholderFile != null && path != null && path.equals(placeholderFile.getAbsolutePath())) {
                phReady = false;
                safeRemove(placeholderFile);
                phDownloading = false;
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                dlPlaceholder();
                return;
            }
            CacheKey k = path == null ? null : bannerByPath.get(path);
            if (k != null && isCurrentScope(k.scope)) {
                rmCached(k);
                loading.remove(k);
                usersNoBanner.remove(k);
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                loadBannerAsync(k);
            }
        } catch (Throwable ignored) {}
    }


    public static boolean isBannerPluginFile(File file) {
        if (file == null) return false;
        String n = file.getName().toLowerCase();
        return n.contains("nimarkobanner") && n.endsWith(".plugin");
    }


    private void invalidate() {
        NimarkoBannerRenderer r = NimarkoBannerRenderer.peek();
        if (r != null) r.invalidateTopView();
    }

    private static boolean isVideo(String p) {
        if (p == null) return false;
        return p.toLowerCase().endsWith(".mp4");
    }

    private static boolean validDownload(File f, String type) {
        try {
            if (f == null || !f.exists()) return false;
            long len = f.length();
            if ("mp4".equals(type)) return len >= MIN_VID && ".mp4".equals(detectBannerExtension(f));
            if (len < MIN_IMG) return false;
            String actual = detectBannerExtension(f);
            return ("jpg".equals(type) || "jpeg".equals(type)) ? ".jpg".equals(actual)
                    : "png".equals(type) && ".png".equals(actual);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String detectBannerExtension(File file) {
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > NimarkoBannerHttp.MAX_SIZE) {
            return null;
        }
        byte[] header = new byte[16];
        int headerSize = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            headerSize = in.read(header);
        } catch (Throwable ignored) {}

        boolean png = headerSize >= 8
                && (header[0] & 0xff) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0d && header[5] == 0x0a && header[6] == 0x1a && header[7] == 0x0a;
        boolean jpeg = headerSize >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff;
        if (png || jpeg) {
            try {
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                android.graphics.BitmapFactory.Options decode = new android.graphics.BitmapFactory.Options();
                decode.inSampleSize = 1;
                int max = Math.max(bounds.outWidth, bounds.outHeight);
                while (max / decode.inSampleSize > 2048) decode.inSampleSize <<= 1;
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
                if (bitmap == null) return null;
                bitmap.recycle();
                return png ? ".png" : ".jpg";
            } catch (Throwable ignored) {
                return null;
            }
        }

        boolean mp4 = headerSize >= 12 && header[4] == 'f' && header[5] == 't'
                && header[6] == 'y' && header[7] == 'p';
        if (!mp4) return null;
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) return ".mp4";
            }
        } catch (Throwable ignored) {
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static long now() { return System.currentTimeMillis(); }

    private static long getOr(Map<CacheKey, Long> m, CacheKey k) {
        Long v = m.get(k);
        return v == null ? 0 : v;
    }

    private static void safeRemove(File f) {
        try { if (f != null && f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete(); } catch (Throwable ignored) {}
    }

    private JsonObject readJson(File f) {
        try {
            if (f == null || !f.exists()) return null;
            if (f.length() <= 0 || f.length() > 256 * 1024L) return null;
            byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            }
            return gson.fromJson(new String(data, "UTF-8"), JsonObject.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeJsonAtomic(File f, JsonObject obj) {
        AtomicFile atomic = new AtomicFile(f);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(obj.toString().getBytes("UTF-8"));
            atomic.finishWrite(out);
            out = null;
        } catch (Throwable t) {
            if (out != null) atomic.failWrite(out);
        }
    }


    private void uiOk(@StringRes int res) { uiBulletin(LocaleController.getString(res)); }
    private void uiInfo(@StringRes int res) { uiBulletin(LocaleController.getString(res)); }
    private void uiErr(@StringRes int res) { uiBulletin(LocaleController.getString(res)); }
    private void uiErrText(String text) { uiBulletin(text); }

    private void uiBulletin(String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                BaseFragment f = LaunchActivity.getLastFragment();
                if (f != null) {
                    BulletinFactory.of(f).createSimpleBulletin(R.raw.info, text).show();
                } else {
                    BulletinFactory.global().createSimpleBulletin(R.raw.info, text).show();
                }
            } catch (Throwable ignored) {}
        });
    }
}
