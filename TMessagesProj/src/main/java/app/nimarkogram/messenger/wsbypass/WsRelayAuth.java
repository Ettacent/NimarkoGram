package app.nimarkogram.messenger.wsbypass;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.NimarkoInlineAuth;

public final class WsRelayAuth {

    private static final String TAG = "NimarkoKWS";
    private static final String API_URL = "https://calls.nimarko.org";

    public static final class Credential {
        public final long expiry;   
        public final long uid;      
        public final byte[] hmac;   

        Credential(long expiry, long uid, byte[] hmac) {
            this.expiry = expiry;
            this.uid = uid;
            this.hmac = hmac;
        }

        public String header() {
            return expiry + ":" + uid + ":" + bytesToHex(hmac);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, Credential> cached =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object lock = new Object();

    private static final long ACCEPT_SKEW_S = 15;
    
    private static final long REFRESH_AHEAD_S = 60 * 60;
    private static final long AUTH_FLOW_BUDGET_MS = 45_000L;
    private static final int HTTP_STAGE_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;
    private static final ThreadLocal<Long> authDeadlineMs = new ThreadLocal<>();

    private static boolean usable(Credential c, long now) {
        return c != null && c.hmac != null && c.hmac.length == 32 && c.expiry - ACCEPT_SKEW_S > now;
    }

    private static boolean fresh(Credential c, long now) {
        return usable(c, now) && c.expiry - REFRESH_AHEAD_S > now;
    }

    private static final class PrefetchOwner {
        final long generation;
        final long uid;
        PrefetchOwner(long generation, long uid) {
            this.generation = generation;
            this.uid = uid;
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<Integer, PrefetchOwner>
            prefetchOwners = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<HttpURLConnection> activeConnections =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong authGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private static final Object authGenerationLock = new Object();

    public static boolean isAuthAllowed() {
        return app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isDataBypassRequiredFresh();
    }

    public static void cancelPendingAuth() {
        synchronized (authGenerationLock) {
            cancelPendingAuthLocked();
        }
    }

    private static void cancelPendingAuthLocked() {
        authGeneration.incrementAndGet();
        for (HttpURLConnection connection : activeConnections) {
            try { connection.disconnect(); } catch (Throwable ignore) {}
        }
        activeConnections.clear();
    }

    private static NimarkoInlineAuth.Backend backendFor(final long uid) {
        return new NimarkoInlineAuth.Backend() {
            @Override public String cachedToken() {
                String t = NimarkoConfig.getWsRelayTokenForUid(uid);
                return (t == null || t.isEmpty()) ? null : t;
            }
            @Override public void cacheToken(String t) { NimarkoConfig.setWsRelayTokenForUid(uid, t); }
            @Override public NimarkoInlineAuth.Reg register(long u) { return httpRegister(u); }
            @Override public String poll(long u, String code) { return httpPoll(u, code); }
        };
    }

    private static int[] candidateAccounts(int preferred) {
        try {
            if (org.telegram.messenger.UserConfig.getInstance(preferred).isClientActivated()
                    && uidOf(preferred) > 0) return new int[]{preferred};
        } catch (Throwable ignore) {}
        return new int[0];
    }

    private static long uidOf(int account) {
        try { return org.telegram.messenger.UserConfig.getInstance(account).getClientUserId(); }
        catch (Throwable t) { return 0; }
    }

    public static void prefetchAsync(final int account) {
        if (!isAuthAllowed()) return;
        
        RelayRegion.invalidate();
        Credential c = memoryOrDisk(account);
        long uid = uidOf(account);
        if (uid <= 0) return;
        if (c != null && c.uid == uid && fresh(c, nowSeconds(account))) return;
        final PrefetchOwner owner = new PrefetchOwner(authGeneration.get(), uid);
        if (owner.uid <= 0 || !claimPrefetchOwner(account, owner)) return;
        Thread worker = new Thread(() -> {
            try {
                if (owner.generation == authGeneration.get()
                        && owner.uid == uidOf(account) && isAuthAllowed()) {
                    get(account, true);
                }
            }
            catch (Throwable ignore) {}
            finally {
                prefetchOwners.remove(account, owner);
            }
        }, "ws-relay-auth");
        try {
            worker.start();
        } catch (Throwable t) {
            prefetchOwners.remove(account, owner);
            Log.e(TAG, "prefetch start failed", t);
        }
    }

    private static boolean claimPrefetchOwner(int account, PrefetchOwner owner) {
        while (true) {
            PrefetchOwner current = prefetchOwners.putIfAbsent(account, owner);
            if (current == null) return true;
            if (current.generation == owner.generation && current.uid == owner.uid) return false;
            if (prefetchOwners.replace(account, current, owner)) return true;
        }
    }

    public static Credential getCached(int account) {
        if (!isAuthAllowed()) return null;
        long ownerUid = uidOf(account);
        if (ownerUid <= 0) return null;
        long now = nowSeconds(account);
        Credential c = memoryOrDisk(account);
        if (uidOf(account) != ownerUid || c != null && c.uid != ownerUid) return null;
        if (usable(c, now)) {
            if (!fresh(c, now)) prefetchAsync(account);
            return c;
        }
        prefetchAsync(account);
        return null;
    }

    public static Credential get(int account) {
        return get(account, false);
    }

    private static Credential get(int account, boolean forceRefresh) {
        if (!isAuthAllowed()) return null;
        final long generation = authGeneration.get();
        final NimarkoInlineAuth.EnablePredicate permit = new NimarkoInlineAuth.EnablePredicate() {
            @Override public boolean isEnabled() {
                return generation == authGeneration.get() && isAuthAllowed();
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
            if (ownerUid <= 0) return null;
            Long previousDeadline = authDeadlineMs.get();
            authDeadlineMs.set(android.os.SystemClock.elapsedRealtime() + AUTH_FLOW_BUDGET_MS);
            try {
                long now = nowSeconds(account);
                Credential old = memoryOrDisk(account);
                if (old != null && old.uid != ownerUid) old = null;
                if (!forceRefresh && usable(old, now)
                        && uidOf(account) == ownerUid) {
                    return old;
                }
                int[] status = new int[1];
                for (int acc : candidateAccounts(account)) {
                    if (!isAuthAllowed() || authRemainingMs() <= 0) break;
                    long uid = uidOf(acc);
                    if (uid == 0 || acc == account && uid != ownerUid) continue;
                    NimarkoInlineAuth.Backend backend = backendFor(uid);
                    if (!isAuthAllowed()) break;
                    String token = NimarkoInlineAuth.ensureToken(
                            acc, uid, lock, backend, permit);
                    if (token == null || !permit.isEnabled()) continue;
                    Credential fresh = fetchCredential(token, status);
                    if (fresh == null && status[0] == 401) {
                        
                        final String rejectedToken = token;
                        final java.util.concurrent.atomic.AtomicBoolean tokenInvalidated =
                                new java.util.concurrent.atomic.AtomicBoolean(false);
                        if (!permit.runIfEnabled(() -> {
                            
                            String currentToken = backend.cachedToken();
                            if (uidOf(acc) == uid && rejectedToken.equals(currentToken)) {
                                backend.cacheToken(null);
                                tokenInvalidated.set(true);
                            }
                        }) || !tokenInvalidated.get()) {
                            continue;
                        }
                        token = authRemainingMs() > 0 && permit.isEnabled()
                                ? NimarkoInlineAuth.ensureToken(
                                        acc, uid, lock, backend, permit) : null;
                        if (token != null) fresh = fetchCredential(token, status);
                    }
                    if (fresh != null && fresh.uid == uid) {
                        final Credential credential = fresh;
                        final java.util.concurrent.atomic.AtomicBoolean committed =
                                new java.util.concurrent.atomic.AtomicBoolean(false);
                        if (permit.runIfEnabled(() -> {
                            
                            if (uidOf(acc) == credential.uid) {
                                cached.put(acc, credential);
                                saveCredentialToDisk(acc, credential);
                                committed.set(true);
                            }
                        }) && committed.get() && uidOf(acc) == credential.uid) return credential;
                        cached.remove(acc, credential);
                    }
                }
                return permit.isEnabled() && uidOf(account) == ownerUid
                        && usable(old, now) ? old : null;
            } finally {
                if (previousDeadline == null) authDeadlineMs.remove();
                else authDeadlineMs.set(previousDeadline);
            }
        }
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
        Credential disk = loadCredentialFromDisk(account, uid);
        if (disk != null) cached.put(account, disk);
        return disk;
    }

    private static long nowSeconds(int account) {
        try {
            int now = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime();
            if (now > 0) return now;
        } catch (Throwable ignore) {}
        return System.currentTimeMillis() / 1000L;
    }

    public static void invalidateCredential(int account) {
        invalidateCredential(account, null);
    }

    public static void invalidateCredential(int account, Credential rejected) {
        boolean invalidated = false;
        synchronized (authGenerationLock) {
            long uid = uidOf(account);
            if (uid <= 0 || (rejected != null && rejected.uid != uid)) return;
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
                && java.util.Arrays.equals(a.hmac, b.hmac);
    }

    private static NimarkoInlineAuth.Reg httpRegister(long uid) {
        NimarkoInlineAuth.Reg reg = new NimarkoInlineAuth.Reg();
        long generation = authGeneration.get();
        if (!isAuthAllowed()) return reg;
        HttpURLConnection con = null;
        try {
            URL url = new URL(API_URL + "/api/v1/kws/register?user_id=" + uid);
            con = (HttpURLConnection) url.openConnection();
            if (!registerConnection(con, generation)) return reg;
            con.setRequestMethod("POST");
            configureTimeouts(con);
            con.setDoOutput(true);
            con.getOutputStream().close();
            if (con.getResponseCode() != 200) return reg;
            JSONObject o = new JSONObject(readBody(con));
            reg.code = o.getString("code");
            reg.botUsername = o.getString("bot_username");
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
        if (!isAuthAllowed() || authRemainingMs() <= 0) return NimarkoInlineAuth.GIVE_UP;
        HttpURLConnection con = null;
        try {
            URL url = new URL(API_URL + "/api/v1/kws/poll?user_id=" + uid
                    + "&code=" + URLEncoder.encode(code, "UTF-8"));
            con = (HttpURLConnection) url.openConnection();
            if (!registerConnection(con, generation)) return NimarkoInlineAuth.GIVE_UP;
            con.setRequestMethod("POST");
            configureTimeouts(con);
            con.setDoOutput(true);
            con.getOutputStream().close();
            int rc = con.getResponseCode();
            if (rc == 200) {
                JSONObject o = new JSONObject(readBody(con));
                String token = o.optString("token", "");
                return token.isEmpty() ? null : token;
            }
            if (rc == 202) return null;        
            return NimarkoInlineAuth.GIVE_UP;  
        } catch (Throwable t) {
            Log.e(TAG, "poll failed: " + t);
            return null;
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
            URL url = new URL(API_URL + "/api/v1/kws/credential");
            con = (HttpURLConnection) url.openConnection();
            if (!registerConnection(con, generation)) return null;
            con.setRequestMethod("POST");
            con.setRequestProperty("X-Auth-Token", token);
            con.setRequestProperty("Content-Type", "application/json");
            configureTimeouts(con);
            con.setDoOutput(true);
            con.getOutputStream().close();
            int rc = con.getResponseCode();
            if (outStatus != null) outStatus[0] = rc;
            if (rc != 200) { Log.e(TAG, "fetchCredential http " + rc); return null; }
            JSONObject o = new JSONObject(readBody(con));
            long expiry = o.getLong("expiry");
            long uid = o.getLong("uid");
            byte[] hmac = hexToBytes(o.getString("hmac"));
            if (uid <= 0 || expiry <= 0
                    || hmac == null || hmac.length != 32) return null;
            return new Credential(expiry, uid, hmac);
        } catch (Throwable t) {
            Log.e(TAG, "fetchCredential failed: " + t);
            return null;
        } finally {
            if (con != null) { activeConnections.remove(con); con.disconnect(); }
        }
    }

    private static String readBody(HttpURLConnection con) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() + line.length() > MAX_RESPONSE_CHARS) {
                    throw new java.io.IOException("auth response too large");
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static int authRemainingMs() {
        Long deadline = authDeadlineMs.get();
        if (deadline == null) return HTTP_STAGE_TIMEOUT_MS;
        long remaining = deadline - android.os.SystemClock.elapsedRealtime();
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

    private static final String PREF_CRED = "ws_relay_cred";   
    private static final String PREF_SLOT_UID = "ws_relay_slot_uid_";

    private static String credentialKey(long uid) { return PREF_CRED + "_" + uid; }

    private static long reconcileAccountLocked(int account) {
        try {
            android.content.SharedPreferences prefs = NimarkoWsBypassConfig.prefs();
            long currentUid = uidOf(account);
            long priorUid = prefs.getLong(PREF_SLOT_UID + account, 0L);
            if (priorUid != currentUid) {
                android.content.SharedPreferences.Editor editor = prefs.edit();
                if (priorUid > 0) {
                    editor.remove(credentialKey(priorUid));
                    NimarkoConfig.setWsRelayTokenForUid(priorUid, null);
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
                android.content.SharedPreferences.Editor editor = NimarkoWsBypassConfig.prefs().edit()
                        .remove(PREF_SLOT_UID + account).remove(PREF_CRED);
                if (uid > 0) {
                    editor.remove(credentialKey(uid));
                    NimarkoConfig.setWsRelayTokenForUid(uid, null);
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
            android.content.SharedPreferences p = NimarkoWsBypassConfig.prefs();
            long uid = reconcileAccountLocked(account);
            if (c == null || c.hmac == null || uid <= 0 || c.uid != uid) {
                if (uid > 0) p.edit().remove(credentialKey(uid)).apply();
                return;
            }
            p.edit().putString(credentialKey(uid),
                    c.expiry + ":" + c.uid + ":" + bytesToHex(c.hmac)).apply();
        } catch (Throwable ignore) {}
    }

    private static Credential loadCredentialFromDisk(int account, long uid) {
        try {
            android.content.SharedPreferences prefs = NimarkoWsBypassConfig.prefs();
            String s = prefs.getString(credentialKey(uid), null);
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
            if (result.uid != uid) {
                prefs.edit().remove(PREF_CRED).remove(credentialKey(uid)).apply();
                return null;
            }
            if (legacy) prefs.edit().putString(credentialKey(uid), s).remove(PREF_CRED).apply();
            return result;
        } catch (Throwable t) { return null; }
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

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private WsRelayAuth() {}
}
