/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.wsbypass.voip;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.Looper;
import app.nimarkogram.messenger.wsbypass.WsBypassConfig;
import app.nimarkogram.messenger.wsbypass.RelayRegion;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.telegram.tgnet.ConnectionsManager;
import android.util.Log;

import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.utils.InlineAuth;

public final class VoipRelayAuth {

    private static final String TAG = "NimarkoVoIP";
    private static final String API_URL = VoipBypassConfig.VOIP_AUTH_URL;

    public static final class Credential {
        public final long expiry;
        public final long uid;
        public final byte[] hmac;

        Credential(long expiry, long uid, byte[] hmac) {
            this.expiry = expiry;
            this.uid = uid;
            this.hmac = hmac;
        }
    }

    private static final ConcurrentHashMap<Integer, Credential> cached =
            new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    private static final long ACCEPT_SKEW_S = 15;
    private static final long REFRESH_AHEAD_S = 60 * 60;
    private static final long AUTH_FLOW_BUDGET_MS = 45_000L;
    private static final int HTTP_STAGE_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;
    private static final ThreadLocal<Long> authDeadlineMs = new ThreadLocal<>();

    private static final class PrefetchOwner {
        final long generation;
        volatile long uid;
        boolean running, waiting, retired, forceRefresh, authRunning, resumeRequested, retryForced;
        Runnable listener;
        PrefetchOwner(long generation) {
            this.generation = generation;
        }
    }
    private static final ConcurrentHashMap<Integer, PrefetchOwner>
            prefetchOwners = new ConcurrentHashMap<>();
    private static final class PrefetchReady implements Runnable {
        final int account;
        final PrefetchOwner owner;
        PrefetchReady(int account, PrefetchOwner owner) {
            this.account = account;
            this.owner = owner;
        }
        @Override public void run() {
            synchronized (owner) {
                if (owner.retired || owner.listener != this || !currentPrefetch(account, owner)) return;
                owner.waiting = false;
                owner.listener = null;
                owner.resumeRequested = true;
                if (owner.running) return;
                owner.running = true;
                owner.resumeRequested = false;
            }
            startPrefetch(account, owner);
        }
    }
    private static final ThreadPoolExecutor PREFETCH_WORKER = new ThreadPoolExecutor(
            UserConfig.MAX_ACCOUNT_COUNT, UserConfig.MAX_ACCOUNT_COUNT, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(UserConfig.MAX_ACCOUNT_COUNT), runnable -> new Thread(runnable, "voip-relay-auth"));
    static { PREFETCH_WORKER.allowCoreThreadTimeOut(true); }
    private static final Set<HttpURLConnection> activeConnections =
            ConcurrentHashMap.newKeySet();
    private static final AtomicLong authGeneration =
            new AtomicLong();
    private static final Object authGenerationLock = new Object();

    private static final ConcurrentHashMap<Integer, Long> lastAllocationRefresh = new ConcurrentHashMap<>();
    public static boolean isAuthAllowed() {
        return VoipBypassConfig.isVoipRelayRequiredFresh();
    }

    public static void cancelPendingAuth() {
        synchronized (authGenerationLock) {
            cancelPendingAuthLocked();
        }
    }

    private static void cancelPendingAuthLocked() {
        authGeneration.incrementAndGet();
        for (java.util.Map.Entry<Integer, PrefetchOwner> entry : prefetchOwners.entrySet()) {
            retirePrefetch(entry.getKey(), entry.getValue());
        }
        for (HttpURLConnection connection : activeConnections) {
            try { connection.disconnect(); } catch (Throwable ignore) {}
        }
        activeConnections.clear();
    }

    private static InlineAuth.Backend backendFor(final long uid) {
        return new InlineAuth.Backend() {
            @Override public String cachedToken() {
                String t = AppConfig.getVoipRelayTokenForUid(uid);
                return (t == null || t.isEmpty()) ? null : t;
            }
            @Override public void cacheToken(String t) { AppConfig.setVoipRelayTokenForUid(uid, t); }
            @Override public InlineAuth.Reg register(long ignored) { return httpRegister(uid); }
            @Override public String poll(long ignored, String code) { return httpPoll(uid, code); }
        };
    }

    public static void prefetchAsync(final int account) {
        prefetchAsync(account, false);
    }

    public static void refreshAfterAllocationFailure(final int account) {
        synchronized (authGenerationLock) {
            if (!isAuthAllowed() || uidOf(account) <= 0) return;
            long now = SystemClock.elapsedRealtime();
            Long previous = lastAllocationRefresh.get(account);
            if (previous != null && now - previous < 60_000L) return;
            lastAllocationRefresh.put(account, now);
        }
        prefetchAsync(account, true);
    }

    private static boolean currentPrefetch(int account, PrefetchOwner owner) {
        return owner.generation == authGeneration.get() && prefetchOwners.get(account) == owner;
    }

    private static void retirePrefetch(int account, PrefetchOwner owner) {
        Runnable listener;
        synchronized (owner) {
            owner.retired = true;
            listener = owner.listener;
            owner.listener = null;
            owner.waiting = false;
        }
        prefetchOwners.remove(account, owner);
        if (listener != null) ConnectionsManager.clearInitializationListener(account, listener);
    }
    private static void prefetchAsync(final int account, boolean forceRefresh) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        final long generation = authGeneration.get();
        while (generation == authGeneration.get()) {
            PrefetchOwner owner = prefetchOwners.get(account);
            if (owner == null || owner.generation != generation) {
                PrefetchOwner fresh = new PrefetchOwner(generation);
                boolean installed = owner == null ? prefetchOwners.putIfAbsent(account, fresh) == null
                        : prefetchOwners.replace(account, owner, fresh);
                if (!installed) continue;
                if (owner != null) retirePrefetch(account, owner);
                owner = fresh;
            }
            synchronized (owner) {
                if (owner.retired || !currentPrefetch(account, owner)) continue;
                if (forceRefresh && !owner.authRunning) owner.forceRefresh = true;
                if (owner.running || owner.waiting) return;
                owner.running = true;
            }
            startPrefetch(account, owner);
            return;
        }
    }

    private static void startPrefetch(int account, PrefetchOwner owner) {
        try {
            PREFETCH_WORKER.execute(() -> {
                try {
                    runPrefetch(account, owner);
                } catch (Throwable ignored) {
                } finally {
                    finishPrefetch(account, owner);
                }
            });
        } catch (Throwable error) {
            retirePrefetch(account, owner);
            Log.e(TAG, "prefetch start failed", error);
        }
    }

    private static void deferPrefetch(int account, PrefetchOwner owner) {
        final Runnable listener = new PrefetchReady(account, owner);
        synchronized (authGenerationLock) {
            if (!currentPrefetch(account, owner)) return;
            synchronized (owner) {
                if (owner.retired) return;
                owner.waiting = true;
                owner.listener = listener;
            }
            ConnectionsManager.setInitializationListener(account, listener);
        }
        ConnectionsManager.dispatchInitializationListener(account);
    }

    private static void runPrefetch(int account, PrefetchOwner owner) {
        if (!currentPrefetch(account, owner) || !isAuthAllowed()) return;
        synchronized (owner) { owner.retryForced = false; }
        long uid = uidOf(account);
        if (owner.uid > 0 && owner.uid != uid) return;
        if (uid > 0) owner.uid = uid;
        long now = readyNowSeconds(account);
        if (now <= 0) {
            deferPrefetch(account, owner);
            return;
        }
        long loadedUid = uidOf(account);
        if (owner.uid > 0 && owner.uid != loadedUid) return;
        uid = loadedUid;
        if (uid > 0) owner.uid = uid;
        if (uid <= 0 || !currentPrefetch(account, owner) || !isAuthAllowed()) return;

        RelayRegion.invalidate();
        Credential credential = memoryOrDisk(account);
        if (!currentPrefetch(account, owner) || uidOf(account) != uid) return;
        now = readyNowSeconds(account);
        if (now <= 0) {
            deferPrefetch(account, owner);
            return;
        }
        boolean force;
        synchronized (owner) {
            force = owner.forceRefresh;
            owner.forceRefresh = false;
        }
        if (!force && credential != null && credential.uid == uid && isFresh(credential, now)) {
            synchronized (owner) { owner.retryForced = true; }
            return;
        }
        synchronized (owner) {
            if (owner.retired || !currentPrefetch(account, owner)) return;
            owner.authRunning = true;
            owner.forceRefresh = false;
        }
        get(account, true, owner);
        if (currentPrefetch(account, owner) && readyNowSeconds(account) <= 0) {
            synchronized (owner) { owner.forceRefresh = true; }
            deferPrefetch(account, owner);
        }
    }

    private static void finishPrefetch(int account, PrefetchOwner owner) {
        boolean restart = false;
        Runnable listener = null;
        synchronized (owner) {
            owner.running = false;
            owner.authRunning = false;
            if (!owner.retired && currentPrefetch(account, owner)) {
                if (owner.waiting) return;
                if (owner.resumeRequested || owner.retryForced && owner.forceRefresh) {
                    owner.resumeRequested = false;
                    owner.running = true;
                    restart = true;
                } else {
                    owner.retired = true;
                    listener = owner.listener;
                    owner.listener = null;
            prefetchOwners.remove(account, owner);
                }
            } else {
                owner.retired = true;
                listener = owner.listener;
                owner.listener = null;
                prefetchOwners.remove(account, owner);
            }
        }
        if (listener != null) ConnectionsManager.clearInitializationListener(account, listener);
        if (restart) startPrefetch(account, owner);
    }

    public static Credential getCached(int account) {
        long now = readyNowSeconds(account);
        if (now <= 0) {
            prefetchAsync(account);
            return null;
        }
        if (!isAuthAllowed()) return null;
        long ownerUid = uidOf(account);
        if (ownerUid <= 0) return null;
        Credential c = memoryOrDisk(account);
        if (uidOf(account) != ownerUid || c != null && c.uid != ownerUid) return null;
        now = readyNowSeconds(account);
        if (now <= 0) {
            prefetchAsync(account);
            return null;
        }
        if (uidOf(account) != ownerUid) return null;
        if (isUsable(c, now)) {

            if (!isFresh(c, now)) prefetchAsync(account);
            return c;
        }

        prefetchAsync(account);
        return null;
    }

    public static Credential get(int account) {
        return get(account, false);
    }

    private static Credential get(int account, boolean forceRefresh) {
        return get(account, forceRefresh, null);
    }

    private static Credential get(int account, boolean forceRefresh, PrefetchOwner expectedOwner) {
        if (expectedOwner != null && !currentPrefetch(account, expectedOwner)) return null;
        if (Looper.myLooper() == Looper.getMainLooper() || readyNowSeconds(account) <= 0) {
            if (expectedOwner == null) prefetchAsync(account, forceRefresh);
            return null;
        }
        if (!isAuthAllowed()) return null;
        final long generation = expectedOwner == null ? authGeneration.get() : expectedOwner.generation;
        final InlineAuth.EnablePredicate permit = new InlineAuth.EnablePredicate() {
            @Override public boolean isEnabled() {
                return generation == authGeneration.get()
                        && (expectedOwner == null || currentPrefetch(account, expectedOwner)) && isAuthAllowed();
            }
            @Override public boolean runIfEnabled(Runnable action) {
                synchronized (authGenerationLock) {
                    if (!isEnabled()) return false;
                    action.run();
                    return true;
                }
            }
        };
        synchronized (lock) {
            if (!permit.isEnabled()) return null;
            final long ownerUid = uidOf(account);
            if (ownerUid <= 0 || expectedOwner != null && ownerUid != expectedOwner.uid) return null;
            Long previousDeadline = authDeadlineMs.get();
            authDeadlineMs.set(SystemClock.elapsedRealtime() + AUTH_FLOW_BUDGET_MS);
            try {
                final InlineAuth.EnablePredicate freshPermit = freshWorkPermit(permit);
                long now = readyNowSeconds(account);
                if (now <= 0) return null;
                Credential old = memoryOrDisk(account);
                if (old != null && old.uid != ownerUid) old = null;
                now = readyNowSeconds(account);
                if (now <= 0) return null;
                if (!forceRefresh && isUsable(old, now)
                        && uidOf(account) == ownerUid) {
                    return old;
                }
                long uid = uidOf(account);
                if (uid <= 0 || uid != ownerUid) return null;
                InlineAuth.Backend backend = backendFor(uid);
                if (!isAuthAllowed()) {
                    return usableFallback(account, ownerUid, old, permit);
                }
                String token = InlineAuth.ensureToken(
                        account, uid, lock, backend, freshPermit);
                if (token == null || !freshPermit.isEnabled()) {
                    return usableFallback(account, ownerUid, old, permit);
                }
                int[] status = new int[1];
                Credential fresh = fetchCredential(token, status);
                if (fresh == null && status[0] == 401) {

                    final String rejectedToken = token;
                    final AtomicBoolean tokenInvalidated =
                            new AtomicBoolean(false);
                    if (!permit.runIfEnabled(() -> {

                        String currentToken = backend.cachedToken();
                        if (UserConfig.getInstance(account).getClientUserId() == uid
                                && rejectedToken.equals(currentToken)) {
                            AppConfig.setVoipRelayTokenForUid(uid, null);
                            tokenInvalidated.set(true);
                        }
                    }) || !tokenInvalidated.get()) {
                        return usableFallback(account, ownerUid, old, permit);
                    }
                    token = authRemainingMs() > 0 && freshPermit.isEnabled()
                            ? InlineAuth.ensureToken(
                                    account, uid, lock, backend, freshPermit) : null;
                    if (token == null) {
                        return usableFallback(account, ownerUid, old, permit);
                    }
                    fresh = fetchCredential(token, status);
                }
                if (fresh != null && fresh.uid == uid && isUsable(fresh, readyNowSeconds(account))) {
                    final Credential credential = fresh;
                    final AtomicBoolean committed =
                            new AtomicBoolean(false);
                    if (!freshPermit.runIfEnabled(() -> {
                        if (UserConfig.getInstance(account).getClientUserId() == credential.uid
                                && isUsable(credential, readyNowSeconds(account))) {
                            cached.put(account, credential);
                            saveCredentialToDisk(account, credential);
                            committed.set(true);
                        }
                    }) || !committed.get() || !freshPermit.isEnabled()
                            || UserConfig.getInstance(account).getClientUserId() != credential.uid
                            || !isUsable(credential, readyNowSeconds(account))) {
                        cached.remove(account, credential);
                        fresh = null;
                    }
                } else if (fresh != null) {
                    fresh = null;
                }
                return fresh != null ? fresh
                        : usableFallback(account, ownerUid, old, permit);
            } finally {
                if (previousDeadline == null) authDeadlineMs.remove();
                else authDeadlineMs.set(previousDeadline);
            }
        }
    }

    private static Credential usableFallback(int account, long ownerUid, Credential old,
                                             InlineAuth.EnablePredicate permit) {
        return permit.isEnabled() && uidOf(account) == ownerUid
                && isUsable(old, readyNowSeconds(account)) ? old : null;
    }
    public static void invalidateCredential(int account) {
        invalidateCredential(account, null);
    }

    public static void invalidateCredential(int account, Credential rejected) {
        boolean invalidated = false;
        synchronized (authGenerationLock) {
            long uid = uidOf(account);
            if (uid <= 0 || rejected != null && rejected.uid != uid) return;
            Credential current = memoryOrDiskLocked(account);
            if (rejected == null || current == null || sameCredential(current, rejected)) {
            cached.remove(account);
            saveCredentialToDiskLocked(account, null);
                invalidated = true;
            }
        }
        if (invalidated) prefetchAsync(account);
    }

    private static boolean sameCredential(Credential a, Credential b) {
        return a != null && b != null && a.expiry == b.expiry && a.uid == b.uid
                && Arrays.equals(a.hmac, b.hmac);
    }

    private static Credential memoryOrDisk(int account) {
        synchronized (authGenerationLock) {
            if (!isAuthAllowed()) return null;
            return memoryOrDiskLocked(account);
        }
    }

    private static Credential memoryOrDiskLocked(int account) {
        long uid = reconcileAccountLocked(account);
        if (uid <= 0) return null;
        Credential c = cached.get(account);
        if (c != null && c.uid == uid) return c;
        if (c != null) cached.remove(account, c);
        Credential disk = loadCredentialFromDisk(account);
        if (disk != null) cached.put(account, disk);
        return disk;
    }

    private static boolean isUsable(Credential c, long now) {
        return now > 0 && c != null && c.hmac != null && c.hmac.length == 32 && c.expiry - ACCEPT_SKEW_S > now;
    }

    private static boolean isFresh(Credential c, long now) {
        return isUsable(c, now) && c.expiry - REFRESH_AHEAD_S > now;
    }

    private static long readyNowSeconds(int account) {
        ConnectionsManager manager = ConnectionsManager.getInstanceIfInitialized(account);
        if (manager == null) return 0;
        try {
            int now = manager.getCurrentTime();
            return now > 0 ? now : 0;
        } catch (Throwable ignored) { return 0; }
    }

    public static long nowSeconds(int account) {
        try {
            int now = ConnectionsManager.getInstance(account).getCurrentTime();
            if (now > 0) return now;
        } catch (Throwable ignore) {}
        return System.currentTimeMillis() / 1000L;
    }

    private static long uidOf(int account) {
        try {
            return UserConfig.getInstance(account).getClientUserId();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static InlineAuth.Reg httpRegister(long uid) {
        InlineAuth.Reg reg = new InlineAuth.Reg();
        long generation = authGeneration.get();
        if (!isAuthAllowed()) return reg;
        HttpURLConnection con = null;
        try {
            checkAuthDeadline();
            URL url = new URL(API_URL + "/api/v1/voip/register?user_id=" + uid);
            con = (HttpURLConnection) url.openConnection();
            if (!registerConnection(con, generation)) return reg;
            con.setRequestMethod("POST");
            configureTimeouts(con);
            con.setDoOutput(true);
            checkAuthDeadline();
            con.getOutputStream().close();
            checkAuthDeadline();
            int rc = con.getResponseCode();
            checkAuthDeadline();
            if (rc != 200) return reg;
            JSONObject o = readJson(con);
            reg.code = o.getString("code");
            reg.botUsername = o.getString("bot_username");
            checkAuthDeadline();
            reg.ok = true;
        } catch (Throwable t) {
            Log.e(TAG, "register failed: " + t);
        } finally {
            if (con != null) { activeConnections.remove(con); con.disconnect(); }
        }
        return reg;
    }

    private static String httpPoll(long uid, String code) {
        long generation = authGeneration.get();
        if (!isAuthAllowed() || authRemainingMs() <= 0) return InlineAuth.GIVE_UP;
        HttpURLConnection con = null;
        try {
            checkAuthDeadline();
            URL url = new URL(API_URL + "/api/v1/voip/poll?user_id=" + uid
                    + "&code=" + URLEncoder.encode(code, "UTF-8"));
            con = (HttpURLConnection) url.openConnection();
            if (!registerConnection(con, generation)) return InlineAuth.GIVE_UP;
            con.setRequestMethod("POST");
            configureTimeouts(con);
            con.setDoOutput(true);
            checkAuthDeadline();
            con.getOutputStream().close();
            checkAuthDeadline();
            int rc = con.getResponseCode();
            checkAuthDeadline();
            if (rc == 200) {
                JSONObject o = readJson(con);
                String token = o.optString("token", "");
                checkAuthDeadline();
                return token.isEmpty() ? null : token;
            }
            if (rc == 202) return null;
            return InlineAuth.GIVE_UP;
        } catch (Throwable t) {
            Log.e(TAG, "poll failed: " + t);
            return authRemainingMs() <= 0 ? InlineAuth.GIVE_UP : null;
        } finally {
            if (con != null) { activeConnections.remove(con); con.disconnect(); }
        }
    }

    private static Credential fetchCredential(String token, int[] outStatus) {
        if (outStatus != null) outStatus[0] = 0;
        long generation = authGeneration.get();
        if (!isAuthAllowed()) return null;
        HttpURLConnection con = null;
        try {
            checkAuthDeadline();
            URL url = new URL(API_URL + "/api/v1/voip/credential");
            con = (HttpURLConnection) url.openConnection();
            if (!registerConnection(con, generation)) return null;
            con.setRequestMethod("POST");
            con.setRequestProperty("X-Auth-Token", token);
            con.setRequestProperty("Content-Type", "application/json");
            configureTimeouts(con);
            con.setDoOutput(true);
            checkAuthDeadline();
            con.getOutputStream().close();

            checkAuthDeadline();
            int rc = con.getResponseCode();
            if (outStatus != null) outStatus[0] = rc;
            checkAuthDeadline();
            if (rc != 200) {
                Log.e(TAG, "fetchCredential http " + rc);
                return null;
            }
            JSONObject o = readJson(con);
            long expiry = o.getLong("expiry");
            long uid = o.getLong("uid");
            byte[] hmac = hexToBytes(o.getString("hmac"));
            if (uid <= 0 || expiry <= 0
                    || hmac == null || hmac.length != 32) return null;
            checkAuthDeadline();
            return new Credential(expiry, uid, hmac);
        } catch (Throwable t) {
            Log.e(TAG, "fetchCredential failed: " + t);
            return null;
        } finally {
            if (con != null) { activeConnections.remove(con); con.disconnect(); }
        }
    }

    private static InlineAuth.EnablePredicate freshWorkPermit(
            final InlineAuth.EnablePredicate lifetimePermit) {
        final Long deadline = authDeadlineMs.get();
        return new InlineAuth.EnablePredicate() {
            @Override public boolean isEnabled() {
                return lifetimePermit.isEnabled()
                        && (deadline == null || SystemClock.elapsedRealtime() < deadline);
            }
            @Override public boolean runIfEnabled(Runnable action) {
                synchronized (authGenerationLock) {
                    if (!isEnabled()) return false;
                    action.run();
                    return true;
                }
            }
        };
    }

    private static void checkAuthDeadline() throws SocketTimeoutException {
        Long deadline = authDeadlineMs.get();
        if (deadline != null && SystemClock.elapsedRealtime() >= deadline) {
            throw new SocketTimeoutException("relay auth deadline exceeded");
        }
    }

    private static JSONObject readJson(HttpURLConnection con) throws Exception {
        String body = readBody(con);
        checkAuthDeadline();
        JSONObject result = new JSONObject(body);
        checkAuthDeadline();
        return result;
    }
    private static String readBody(HttpURLConnection con) throws Exception {
        checkAuthDeadline();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            while (true) {
                checkAuthDeadline();
                int c = r.read();
                checkAuthDeadline();
                if (c == -1) break;
                if (c == '\r' || c == '\n') continue;
                if (sb.length() >= MAX_RESPONSE_CHARS) {
                    throw new IOException("auth response too large");
                }
                sb.append((char) c);
            }
        }
        checkAuthDeadline();
        return sb.toString();
    }

    private static int authRemainingMs() {
        Long deadline = authDeadlineMs.get();
        if (deadline == null) return HTTP_STAGE_TIMEOUT_MS;
        long remaining = deadline - SystemClock.elapsedRealtime();
        return remaining <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private static void configureTimeouts(HttpURLConnection con) throws SocketTimeoutException {
        int remaining = authRemainingMs();
        if (remaining <= 0) throw new SocketTimeoutException("relay auth deadline exceeded");
        int timeout = Math.max(1, Math.min(HTTP_STAGE_TIMEOUT_MS, remaining));
        con.setConnectTimeout(timeout);
        con.setReadTimeout(timeout);
        con.setInstanceFollowRedirects(false);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static final String PREF_CRED = "voip_relay_cred";
    private static final String PREF_SLOT_UID = "voip_relay_slot_uid_";

    private static String credentialKey(int account) {
        long uid = UserConfig.getInstance(account).getClientUserId();
        return PREF_CRED + "_" + uid;
    }

    private static long reconcileAccountLocked(int account) {
        try {
            SharedPreferences prefs = WsBypassConfig.prefs();
            long currentUid = UserConfig.getInstance(account).getClientUserId();
            long priorUid = prefs.getLong(PREF_SLOT_UID + account, 0L);
            if (priorUid != currentUid) {
                SharedPreferences.Editor editor = prefs.edit();
                if (priorUid > 0) {
                    editor.remove(PREF_CRED + "_" + priorUid);
                    AppConfig.setVoipRelayTokenForUid(priorUid, null);
                }
                if (currentUid > 0) editor.putLong(PREF_SLOT_UID + account, currentUid);
                else editor.remove(PREF_SLOT_UID + account);
                editor.remove(PREF_CRED).apply();
                cached.remove(account);
            }
            return currentUid;
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void onAccountLoggedOut(int account, long uid) {
        synchronized (authGenerationLock) {
            cancelPendingAuthLocked();
            cached.remove(account);
            try {
                SharedPreferences.Editor editor =
                        WsBypassConfig.prefs().edit()
                                .remove(PREF_SLOT_UID + account).remove(PREF_CRED);
                if (uid > 0) {
                    editor.remove(PREF_CRED + "_" + uid);
                    AppConfig.setVoipRelayTokenForUid(uid, null);
                }
                editor.apply();
            } catch (Throwable ignore) {}
        }
    }

    private static void saveCredentialToDisk(int account, Credential c) {
        synchronized (authGenerationLock) {
            saveCredentialToDiskLocked(account, c);
        }
    }

    private static void saveCredentialToDiskLocked(int account, Credential c) {
        try {
            SharedPreferences p = WsBypassConfig.prefs();
            long uid = reconcileAccountLocked(account);
            String key = credentialKey(account);
            if (c == null || c.hmac == null || uid <= 0 || c.uid != uid) {
                p.edit().remove(key).apply(); return;
            }
            p.edit().putString(key, c.expiry + ":" + c.uid + ":" + bytesToHex(c.hmac)).apply();
        } catch (Throwable ignore) {}
    }

    private static Credential loadCredentialFromDisk(int account) {
        try {
            SharedPreferences prefs = WsBypassConfig.prefs();
            String key = credentialKey(account);
            String s = prefs.getString(key, null);
            boolean legacy = false;
            if (s == null) {
                s = prefs.getString(PREF_CRED, null);
                legacy = s != null;
            }
            if (s == null) return null;
            String[] parts = s.split(":");
            if (parts.length != 3) return null;
            byte[] hmac = hexToBytes(parts[2]);
            if (hmac == null || hmac.length != 32) return null;
            Credential result = new Credential(Long.parseLong(parts[0]), Long.parseLong(parts[1]), hmac);
            long uid = UserConfig.getInstance(account).getClientUserId();
            if (result.uid != uid || uid <= 0) {
                prefs.edit().remove(key).remove(PREF_CRED).apply();
                return null;
            }
            if (legacy) {
                prefs.edit().putString(key, s).remove(PREF_CRED).apply();
            }
            return result;
        } catch (Throwable t) { return null; }
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static boolean registerConnection(HttpURLConnection connection, long generation) {
        synchronized (authGenerationLock) {
            if (generation != authGeneration.get() || !isAuthAllowed()) {
                try { connection.disconnect(); } catch (Throwable ignore) {}
                return false;
            }
            activeConnections.add(connection);
            if (generation != authGeneration.get() || !isAuthAllowed()) {
                activeConnections.remove(connection);
                try { connection.disconnect(); } catch (Throwable ignore) {}
                return false;
            }
            return true;
        }
    }

    private VoipRelayAuth() {}
}
