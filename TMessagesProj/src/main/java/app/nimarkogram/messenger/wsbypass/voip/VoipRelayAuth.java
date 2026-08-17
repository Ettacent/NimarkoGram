package app.nimarkogram.messenger.wsbypass.voip;

import android.util.Log;

import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.NimarkoInlineAuth;

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

    private static final java.util.concurrent.ConcurrentHashMap<Integer, Credential> cached =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object lock = new Object();

    private static final long ACCEPT_SKEW_S = 15;
    private static final long REFRESH_AHEAD_S = 60 * 60;
    private static final long AUTH_FLOW_BUDGET_MS = 45_000L;
    private static final int HTTP_STAGE_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;
    private static final ThreadLocal<Long> authDeadlineMs = new ThreadLocal<>();

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
        return VoipBypassConfig.isVoipRelayRequiredFresh();
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
                String t = NimarkoConfig.getVoipRelayTokenForUid(uid);
                return (t == null || t.isEmpty()) ? null : t;
            }
            @Override public void cacheToken(String t) { NimarkoConfig.setVoipRelayTokenForUid(uid, t); }
            @Override public NimarkoInlineAuth.Reg register(long ignored) { return httpRegister(uid); }
            @Override public String poll(long ignored, String code) { return httpPoll(uid, code); }
        };
    }

    public static void prefetchAsync(final int account) {
        if (!isAuthAllowed()) return;
        
        app.nimarkogram.messenger.wsbypass.RelayRegion.invalidate();
        long now = nowSeconds(account);
        Credential c = memoryOrDisk(account);
        long uid = uidOf(account);
        if (uid <= 0) return;
        
        if (c != null && c.uid == uid && isFresh(c, now)) return;
        
        final PrefetchOwner owner = new PrefetchOwner(authGeneration.get(), uidOf(account));
        if (owner.uid <= 0 || !claimPrefetchOwner(account, owner)) return;
        Thread worker = new Thread(() -> {
            try {
                if (owner.generation == authGeneration.get()
                        && owner.uid == uidOf(account) && isAuthAllowed()) {
                    get(account, true);
                }
            }
            catch (Throwable ignore) {}
            finally { prefetchOwners.remove(account, owner); }
        }, "voip-relay-auth");
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
                if (!forceRefresh && isUsable(old, now)
                        && uidOf(account) == ownerUid) {
                    return old;
                }
                long uid = uidOf(account);
                if (uid <= 0 || uid != ownerUid) return null;
                NimarkoInlineAuth.Backend backend = backendFor(uid);
                if (!isAuthAllowed()) {
                    return uidOf(account) == ownerUid && isUsable(old, now) ? old : null;
                }
                String token = NimarkoInlineAuth.ensureToken(
                        account, uid, lock, backend, permit);
                if (token == null || !permit.isEnabled()) {
                    return uidOf(account) == ownerUid && isUsable(old, now) ? old : null;
                }
                int[] status = new int[1];
                Credential fresh = fetchCredential(token, status);
                if (fresh == null && (status[0] == 401 || status[0] == 403)) {
                    
                    final String rejectedToken = token;
                    final java.util.concurrent.atomic.AtomicBoolean tokenInvalidated =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    if (!permit.runIfEnabled(() -> {
                        
                        String currentToken = backend.cachedToken();
                        if (UserConfig.getInstance(account).getClientUserId() == uid
                                && rejectedToken.equals(currentToken)) {
                            NimarkoConfig.setVoipRelayTokenForUid(uid, null);
                            tokenInvalidated.set(true);
                        }
                    }) || !tokenInvalidated.get()) {
                        return uidOf(account) == ownerUid
                                && isUsable(old, now) && permit.isEnabled() ? old : null;
                    }
                    token = authRemainingMs() > 0 && permit.isEnabled()
                            ? NimarkoInlineAuth.ensureToken(
                                    account, uid, lock, backend, permit) : null;
                    if (token == null) {
                        return uidOf(account) == ownerUid && isUsable(old, now) ? old : null;
                    }
                    fresh = fetchCredential(token, status);
                }
                if (fresh != null && fresh.uid == uid) {
                    final Credential credential = fresh;
                    final java.util.concurrent.atomic.AtomicBoolean committed =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    if (!permit.runIfEnabled(() -> {
                        
                        if (UserConfig.getInstance(account).getClientUserId() == credential.uid) {
                            cached.put(account, credential);
                            saveCredentialToDisk(account, credential);
                            committed.set(true);
                        }
                    }) || !committed.get()
                            || UserConfig.getInstance(account).getClientUserId() != credential.uid) {
                        cached.remove(account, credential);
                        fresh = null;
                    }
                } else if (fresh != null) {
                    fresh = null;
                }
                return fresh != null ? fresh
                        : (permit.isEnabled() && uidOf(account) == ownerUid
                        && isUsable(old, now) ? old : null);
            } finally {
                if (previousDeadline == null) authDeadlineMs.remove();
                else authDeadlineMs.set(previousDeadline);
            }
        }
    }

    public static void invalidateCredential(int account) {
        boolean invalidated;
        synchronized (authGenerationLock) {
            invalidated = uidOf(account) > 0;
            cached.remove(account);
            saveCredentialToDiskLocked(account, null);
        }
        if (invalidated) prefetchAsync(account);
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
        return c != null && c.hmac != null && c.hmac.length == 32 && c.expiry - ACCEPT_SKEW_S > now;
    }

    private static boolean isFresh(Credential c, long now) {
        return isUsable(c, now) && c.expiry - REFRESH_AHEAD_S > now;
    }

    public static long nowSeconds(int account) {
        try {
            int now = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime();
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

    private static NimarkoInlineAuth.Reg httpRegister(long uid) {
        NimarkoInlineAuth.Reg reg = new NimarkoInlineAuth.Reg();
        long generation = authGeneration.get();
        if (!isAuthAllowed()) return reg;
        HttpURLConnection con = null;
        try {
            URL url = new URL(API_URL + "/api/v1/voip/register?user_id=" + uid);
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
            URL url = new URL(API_URL + "/api/v1/voip/poll?user_id=" + uid
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
            URL url = new URL(API_URL + "/api/v1/voip/credential");
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
            if (rc != 200) {
                Log.e(TAG, "fetchCredential http " + rc);
                return null;
            }
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

    private static final String PREF_CRED = "voip_relay_cred";   
    private static final String PREF_SLOT_UID = "voip_relay_slot_uid_";

    private static String credentialKey(int account) {
        long uid = UserConfig.getInstance(account).getClientUserId();
        return PREF_CRED + "_" + uid;
    }

    private static long reconcileAccountLocked(int account) {
        try {
            android.content.SharedPreferences prefs = app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig.prefs();
            long currentUid = UserConfig.getInstance(account).getClientUserId();
            long priorUid = prefs.getLong(PREF_SLOT_UID + account, 0L);
            if (priorUid != currentUid) {
                android.content.SharedPreferences.Editor editor = prefs.edit();
                if (priorUid > 0) {
                    editor.remove(PREF_CRED + "_" + priorUid);
                    NimarkoConfig.setVoipRelayTokenForUid(priorUid, null);
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
                android.content.SharedPreferences.Editor editor =
                        app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig.prefs().edit()
                                .remove(PREF_SLOT_UID + account).remove(PREF_CRED);
                if (uid > 0) {
                    editor.remove(PREF_CRED + "_" + uid);
                    NimarkoConfig.setVoipRelayTokenForUid(uid, null);
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
            android.content.SharedPreferences p = app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig.prefs();
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
            android.content.SharedPreferences prefs = app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig.prefs();
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
