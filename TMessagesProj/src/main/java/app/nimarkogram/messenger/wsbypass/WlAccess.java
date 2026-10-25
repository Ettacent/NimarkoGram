/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.wsbypass;

import android.os.SystemClock;
import app.nimarkogram.messenger.wsbypass.voip.VoipBypassCore;
import java.util.concurrent.RejectedExecutionException;
import org.telegram.messenger.Utilities;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.nimarkogram.messenger.banners.BannerConfig;
import app.nimarkogram.messenger.utils.InlineAuth;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class WlAccess {
    public static final String HOST = "bypass.nimarko.org";
    public static final int PORT = 15435;
    public static final String ORIGIN = "https://" + HOST + ":" + PORT;
    private static final String PREFIX = "/api/v1/banners/wl";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final byte[] EMPTY = new byte[0];
    private static final ThreadPoolExecutor CALL_DNS = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "wl-call-dns");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final Object LOCK = new Object();
    private static final Object AUTH_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<Long, Grant> GRANTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Subscription> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> EPOCHS = new ConcurrentHashMap<>();
    private static final AtomicBoolean WARMING = new AtomicBoolean();
    private static final AtomicBoolean FOREGROUND_REQUESTED = new AtomicBoolean();
    private static final ThreadLocal<Boolean> BACKGROUND_WARM = new ThreadLocal<>();
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static final AtomicBoolean NOTIFY_PENDING = new AtomicBoolean();
    private static final long REQUEST_TIMEOUT_MS = 30_000;
    private static final ThreadLocal<DeadlineTask> REQUEST = new ThreadLocal<>();
    private static volatile long lastWarm;
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(12), task -> {
                Thread thread = new Thread(task, "wl-access");
                thread.setDaemon(true);
                return thread;
            });
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();

    public interface Callback { void done(String status); }

    public interface CheckoutCallback { void done(String status, Checkout checkout); }

    public static final class Checkout {
        public final String orderId, confirmationUrl, status, priceVersion;
        public final long expiresAt, amountCents;
        Checkout(String orderId, String confirmationUrl, String status, long expiresAt,
                 long amountCents, String priceVersion) {
            this.orderId = orderId; this.confirmationUrl = confirmationUrl;
            this.status = status; this.expiresAt = expiresAt;
            this.amountCents = amountCents; this.priceVersion = priceVersion;
        }
    }

    public static final class Subscription {
        public final String status, priceVersion;
        public final long expiresAt, priceCents;
        Subscription(String status, long expiresAt, long priceCents, String priceVersion) {
            this.status = status; this.expiresAt = expiresAt;
            this.priceCents = priceCents; this.priceVersion = priceVersion;
        }
    }

    private static long integerCents(JSONObject json, String field) {
        Object value = json.opt(field);
        if (!(value instanceof Integer) && !(value instanceof Long)) return -1;
        long cents = ((Number) value).longValue();
        return cents > 0 ? cents : -1;
    }

    private static String priceVersion(JSONObject json) {
        String version = json.optString("price_version", "").trim();
        return "null".equals(version) ? "" : version;
    }

    private static Subscription parseSubscription(JSONObject state, long now) {
        String status = state.optString("status", "payment_required");
        long paidExpires = state.optLong("expires_at", 0);
        if ("approved".equals(status) && paidExpires <= now) status = "expired";
        if (!"approved".equals(status) && !"payment_required".equals(status)
                && !"expired".equals(status) && !"revoked".equals(status)
                && !"blocked".equals(status)) status = "error";
        return new Subscription(status, paidExpires, integerCents(state, "price_cents"), priceVersion(state));
    }

    private static long orderAmountCents(JSONObject json) {
        if (!"RUB".equals(json.optString("currency", "RUB"))) return -1;
        Object amount = json.opt("amount");
        if (amount == null || amount == JSONObject.NULL) {
            long cents = integerCents(json, "amount_cents");
            return cents > 0 ? cents : integerCents(json, "cents");
        }
        if (amount instanceof JSONObject) {
            JSONObject quote = (JSONObject) amount;
            if (!"RUB".equals(quote.optString("currency", ""))) return -1;
            amount = quote.opt("value");
        } else if (!"RUB".equals(json.optString("currency", "RUB"))) {
            return -1;
        }
        if (!(amount instanceof String) && !(amount instanceof Number)) return -1;
        try {
            long cents = new BigDecimal(amount.toString()).movePointRight(2).longValueExact();
            return cents > 0 ? cents : -1;
        } catch (Exception ignored) { return -1; }
    }
    public static final class Grant {
        final long uid, expires, paidUntil;
        final String token;
        final PrivateKey key;
        Grant(long uid, long expires, String token, PrivateKey key) {
            this(uid, expires, token, key, 0);
        }
        Grant(long uid, long expires, String token, PrivateKey key, long paidUntil) {
            this.uid = uid; this.expires = expires; this.token = token; this.key = key;
            this.paidUntil = paidUntil;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nimarko_wl", Context.MODE_PRIVATE);
    }

    public static boolean enabled() { return prefs().getBoolean("enabled", false); }

    private static long uid(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                && UserConfig.getInstance(account).isClientActivated()
                ? UserConfig.getInstance(account).getClientUserId() : 0;
    }

    private static boolean current(int account, long owner, long epoch) {
        return owner > 0 && uid(account) == owner && EPOCHS.getOrDefault(owner, 0L) == epoch;
    }

    private static int accountFor(long owner) {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) if (uid(i) == owner) return i;
        return -1;
    }

    public static Grant cached() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            Grant grant = accountGrant(i);
            if (grant != null) return grant;
        }
        return null;
    }
    private static Grant accountGrant(int account) {
        long owner = uid(account);
        Grant grant = GRANTS.get(owner);
        return grant != null && Math.min(grant.expires, grant.paidUntil) > ConnectionsManager.getInstance(account).getCurrentTime() + 60L
                && uid(account) == owner ? grant : null;
    }
    public static boolean hasAccountGrant(int account) { return accountGrant(account) != null; }
    public static Subscription subscription(int account) { return SUBSCRIPTIONS.get(uid(account)); }

    public static long accountEpoch(int account) { return EPOCHS.getOrDefault(uid(account), 0L); }

    public static long accessExpiresAt(int account) {
        Grant grant = accountGrant(account);
        if (grant == null) grant = cached();
        return grant == null ? 0 : grant.paidUntil;
    }

    public static int sponsorAccount(int account) {
        Grant grant = accountGrant(account);
        if (grant == null) grant = cached();
        return grant == null ? account : accountFor(grant.uid);
    }

    public static boolean isCheckoutUrl(String value) {
        try {
            if (value == null || value.length() > 4096) return false;
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("yoomoney.ru") || host.endsWith(".yoomoney.ru")
                    || host.equals("yookassa.ru") || host.endsWith(".yookassa.ru");
        } catch (Exception ignored) { return false; }
    }

    public static void checkout(int account, CheckoutCallback callback) {
        requestOrder(account, null, callback);
    }

    public static void checkOrder(int account, String orderId, CheckoutCallback callback) {
        requestOrder(account, orderId, callback);
    }

    private static void finishCheckout(int account, long owner, long epoch, CheckoutCallback callback,
                                       String status, Checkout checkout) {
        if (callback == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            boolean valid = current(account, owner, epoch);
            callback.done(valid ? status : "account_changed", valid ? checkout : null);
        });
    }

    private static void requestOrder(int account, String orderId, CheckoutCallback resultCallback) {
        AtomicBoolean delivered = new AtomicBoolean();
        CheckoutCallback callback = (status, checkout) -> {
            if (resultCallback != null && delivered.compareAndSet(false, true)) resultCallback.done(status, checkout);
        };
        final long owner = uid(account), epoch = EPOCHS.getOrDefault(owner, 0L);
        Runnable job = () -> {
            String status = "error";
            Checkout checkout = null;
            try {
                if (!current(account, owner, epoch)) throw new IOException("account_changed");
                if (orderId != null && (orderId.isEmpty() || orderId.length() > 256))
                    throw new IOException("invalid_order");
                String path = orderId == null ? "/billing/orders" : "/billing/orders/" + Uri.encode(orderId);
                String method = orderId == null ? "POST" : "GET";
                byte[] body = orderId == null ? bytes(new JSONObject()) : null;
                String token = bannerToken(account, owner, epoch);
                JSONObject result;
                try {
                    if (!current(account, owner, epoch)) throw new IOException("account_changed");
                    result = call(path, method, token, body);
                } catch (ApiError error) {
                    if (error.code != 401 || !current(account, owner, epoch)) throw error;
                    BannerConfig.setAuthToken(account, owner, "");
                    token = bannerToken(account, owner, epoch);
                    if (!current(account, owner, epoch)) throw new IOException("account_changed");
                    result = call(path, method, token, body);
                }
                if (!current(account, owner, epoch)) throw new IOException("account_changed");
                String id = orderId == null ? result.getString("order_id") : orderId;
                if (id.isEmpty() || id.length() > 256) throw new IOException("invalid_order");
                String url = result.optString("confirmation_url", "");
                if ("null".equals(url)) url = "";
                if (!url.isEmpty() && !isCheckoutUrl(url)) throw new IOException("invalid_checkout_url");
                String paymentStatus = result.optString("status", "pending");
                if ("paid".equals(paymentStatus) || "succeeded".equals(paymentStatus)
                        || "completed".equals(paymentStatus) || "approved".equals(paymentStatus)) {
                    status = "completed";
                } else if ("pending".equals(paymentStatus) || "pending_payment".equals(paymentStatus)
                        || "creating".equals(paymentStatus) || "creation_unknown".equals(paymentStatus)
                        || "waiting_for_capture".equals(paymentStatus) || "processing".equals(paymentStatus)
                        || "payment_required".equals(paymentStatus)) {
                    status = "pending";
                } else {
                    status = "payment_failed";
                }
                checkout = new Checkout(id, url, status, result.optLong("expires_at", 0),
                        orderAmountCents(result), priceVersion(result));
            } catch (Exception error) { status = accessErrorStatus(account, owner, epoch, error); }
            finishCheckout(account, owner, epoch, callback, status, checkout);
        };
        try { execute(job, () -> finishCheckout(account, owner, epoch, callback, "error", null)); }
        catch (RejectedExecutionException error) {
            finishCheckout(account, owner, epoch, callback, "busy", null);
        }
    }
    public static void addListener(Runnable listener) { LISTENERS.add(listener); }
    public static void removeListener(Runnable listener) { LISTENERS.remove(listener); }
    private static void notifyChanged() {
        if (LISTENERS.isEmpty() || !NOTIFY_PENDING.compareAndSet(false, true)) return;
        AndroidUtilities.runOnUIThread(() -> {
            NOTIFY_PENDING.set(false);
            for (Runnable listener : LISTENERS) listener.run();
        });
    }

    public static boolean isCurrent(Grant grant) {
        int account = grant == null ? -1 : accountFor(grant.uid);
        return account >= 0 && accountGrant(account) == grant;
    }

    public static void setEnabled(boolean value) {
        if (value && cached() == null) return;
        if (enabled() == value) return;
        prefs().edit().putBoolean("enabled", value).apply();
        WsBypassController controller = WsBypassController.getInstance();
        controller.stop();
        if (value) WsBypassConfig.setEnabled(true);
        controller.ensureStarted();
    }

    private static void onGrantRemoved() {
        AndroidUtilities.runOnUIThread(() -> {
            if (enabled()) {
                if (cached() == null) {
                    setEnabled(false);
                } else {
                    WsBypassController controller = WsBypassController.getInstance();
                    controller.stop();
                    controller.ensureStarted();
                }
            }
            notifyChanged();
        });
    }
    private static void queue(Runnable job, Callback callback) {
        try { execute(job, () -> finish(callback, "error")); }
        catch (RejectedExecutionException e) { finish(callback, "busy"); }
    }

    private static final class DeadlineTask implements Runnable {
        final long deadline = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS;
        final Runnable job, expired;
        final AtomicBoolean completed = new AtomicBoolean();
        final Runnable timeout;

        DeadlineTask(Runnable job, Runnable expired) {
            this.job = job;
            this.expired = expired;
            this.timeout = () -> {
                if (completed.compareAndSet(false, true)) {
                    WORK.remove(this);
                    this.expired.run();
                }
            };
        }

        boolean active() {
            return !completed.get() && SystemClock.elapsedRealtime() < deadline;
        }

        @Override public void run() {
            if (!active()) {
                timeout.run();
                return;
            }
            REQUEST.set(this);
            try { job.run(); }
            finally {
                completed.set(true);
                REQUEST.remove();
                AndroidUtilities.cancelRunOnUIThread(timeout);
            }
        }
    }

    private static void execute(Runnable job, Runnable expired) {
        DeadlineTask task = new DeadlineTask(job, expired);
        AndroidUtilities.runOnUIThread(task.timeout, REQUEST_TIMEOUT_MS);
        try { WORK.execute(task); }
        catch (RejectedExecutionException error) {
            task.completed.set(true);
            AndroidUtilities.cancelRunOnUIThread(task.timeout);
            throw error;
        }
    }

    private static long remainingRequestMillis() throws IOException {
        DeadlineTask task = REQUEST.get();
        if (task == null) return REQUEST_TIMEOUT_MS;
        if (!task.active()) throw new IOException("request_timeout");
        return Math.max(1, task.deadline - SystemClock.elapsedRealtime());
    }

    private static void finish(Callback callback, String status) {
        if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.done(status));
    }

    private static void restoreGrants() {
        boolean changed = false;
        try {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                long owner = uid(account), epoch = EPOCHS.getOrDefault(owner, 0L);
                if (owner <= 0 || GRANTS.containsKey(owner)) continue;
                try {
                    String alias = prefs().getString("key_" + owner, "");
                    JSONObject stored = new JSONObject(prefs().getString("grant_" + owner, ""));
                    if (alias.isEmpty() || stored.getLong("uid") != owner
                            || Math.min(stored.getLong("expires"), stored.optLong("paid_expires_at", 0))
                            <= ConnectionsManager.getInstance(account).getCurrentTime() + 60L) continue;
                    PrivateKey key = (PrivateKey) keys.getKey(alias, null);
                    if (key == null) continue;
                    Grant grant = new Grant(owner, stored.getLong("expires"), stored.getString("token"), key,
                            stored.getLong("paid_expires_at"));
                    synchronized (LOCK) {
                        if (current(account, owner, epoch) && GRANTS.putIfAbsent(owner, grant) == null) changed = true;
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        if (changed) notifyChanged();
    }

    public static void warm() {
        if (!enabled() || lastWarm != 0 && SystemClock.elapsedRealtime() - lastWarm < 30_000
                || !WARMING.compareAndSet(false, true)) return;
        lastWarm = SystemClock.elapsedRealtime();
        try {
            execute(() -> {
                BACKGROUND_WARM.set(true);
                try {
                    restoreGrants();
                    for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                        if (FOREGROUND_REQUESTED.get()) break;
                        long owner = uid(i);
                        if (owner <= 0 || !enabled()) continue;
                        try { refreshNow(i, owner, EPOCHS.getOrDefault(owner, 0L), null); }
                        catch (Exception ignored) { }
                    }
                } finally {
                    BACKGROUND_WARM.remove();
                    WARMING.set(false);
                }
            }, () -> WARMING.set(false));
        } catch (RejectedExecutionException e) { WARMING.set(false); }
    }

    public static void refresh(int account, Callback callback) {
        refreshWithSubscription(account, null, callback);
    }

    public static void refreshWithSubscription(int account, Callback subscriptionCallback, Callback resultCallback) {
        final long owner = uid(account), epoch = EPOCHS.getOrDefault(owner, 0L);
        AtomicBoolean delivered = new AtomicBoolean();
        Callback callback = status -> {
            FOREGROUND_REQUESTED.set(false);
            if (resultCallback != null && delivered.compareAndSet(false, true))
                resultCallback.done(current(account, owner, epoch) ? status : "account_changed");
        };
        FOREGROUND_REQUESTED.set(true);
        queue(() -> {
            restoreGrants();
            String status = "error";
            try { status = refreshNow(account, owner, epoch, subscriptionCallback); }
            catch (Exception e) { status = errorStatus(e); }
            Grant available = cached();
            int checkedOther = -1;
            if (current(account, owner, epoch) && available != null && available.uid != owner) {
                int other = accountFor(available.uid);
                checkedOther = other;
                try { refreshNow(other, available.uid, EPOCHS.getOrDefault(available.uid, 0L), null); }
                catch (Exception ignored) { }
            }
            if (current(account, owner, epoch) && cached() == null) {
                for (int other = 0; other < UserConfig.MAX_ACCOUNT_COUNT; other++) {
                    long otherOwner = uid(other);
                    if (other == account || other == checkedOther || otherOwner <= 0) continue;
                    try { refreshNow(other, otherOwner, EPOCHS.getOrDefault(otherOwner, 0L), null); }
                    catch (Exception ignored) { }
                    if (cached() != null || !current(account, owner, epoch)) break;
                }
            }
            finish(callback, current(account, owner, epoch) ? status : "account_changed");
        }, callback);
    }

    private static String bannerToken(int account, long owner, long epoch) throws Exception {
        remainingRequestMillis();
        DeadlineTask task = REQUEST.get();
        final boolean backgroundWarm = Boolean.TRUE.equals(BACKGROUND_WARM.get());
        InlineAuth.EnablePredicate permit = new InlineAuth.EnablePredicate() {
            @Override public boolean isEnabled() {
                return (task == null || task.active()) && current(account, owner, epoch)
                        && (!backgroundWarm || !FOREGROUND_REQUESTED.get());
            }
            @Override public boolean runIfEnabled(Runnable action) {
                synchronized (LOCK) {
                    if (!isEnabled()) return false;
                    action.run();
                    return true;
                }
            }
        };
        String token = InlineAuth.ensureToken(account, owner, AUTH_LOCK, new InlineAuth.Backend() {
            @Override public String cachedToken() { return BannerConfig.getAuthToken(account, owner); }
            @Override public void cacheToken(String token) {
                if (current(account, owner, epoch)) BannerConfig.setAuthToken(account, owner, token);
            }
            @Override public InlineAuth.Reg register(long id) {
                InlineAuth.Reg result = new InlineAuth.Reg();
                try {
                    JSONObject json = call("/api/v1/banners/auth/register?user_id=" + owner, "POST", null, EMPTY);
                    result.code = json.getString("code");
                    result.botUsername = json.getString("bot_username"); result.ok = true;
                } catch (Exception ignored) { }
                return result;
            }
            @Override public String poll(long id, String code) {
                try { return call("/api/v1/banners/auth/poll?user_id=" + owner + "&code=" + Uri.encode(code),
                        "POST", null, EMPTY).optString("token", null); }
                catch (Exception e) { return null; }
            }
        }, permit);
        remainingRequestMillis();
        if (token == null || !current(account, owner, epoch)) throw new IOException("authentication_required");
        return token;
    }

    private static String refreshNow(int account, long owner, long epoch, Callback subscriptionCallback) throws Exception {
        try {
            return refreshAccount(account, owner, epoch, subscriptionCallback);
        } catch (ApiError error) {
            return accessErrorStatus(account, owner, epoch, error);
        }
    }

    private static String refreshAccount(int account, long owner, long epoch, Callback subscriptionCallback) throws Exception {
        remainingRequestMillis();
        if (!current(account, owner, epoch)) throw new IOException("account_changed");
        String auth = bannerToken(account, owner, epoch);
        JSONObject state;
        try { state = call(PREFIX + "/status", "GET", auth, null); }
        catch (ApiError e) {
            if (e.code != 401) throw e;
            if (!current(account, owner, epoch)) throw new IOException("account_changed");
            BannerConfig.setAuthToken(account, owner, "");
            auth = bannerToken(account, owner, epoch);
            state = call(PREFIX + "/status", "GET", auth, null);
        }
        if (!current(account, owner, epoch)) throw new IOException("account_changed");
        long now = state.optLong("server_time", ConnectionsManager.getInstance(account).getCurrentTime());
        Subscription subscription = parseSubscription(state, now);
        String status = subscription.status;
        long paidExpires = subscription.expiresAt;
        if ("error".equals(status)) return status;
        synchronized (LOCK) {
            if (!current(account, owner, epoch)) throw new IOException("account_changed");
            SUBSCRIPTIONS.put(owner, subscription);
        }
        if ("blocked".equals(status)) return recordBlocked(account, owner, epoch);
        if (!"approved".equals(status)) {
            boolean changed = false;
            synchronized (LOCK) {
                if (!current(account, owner, epoch)) throw new IOException("account_changed");
                changed = GRANTS.remove(owner) != null;
                prefs().edit().remove("grant_" + owner).apply();
            }
            if (changed || enabled() && cached() == null) onGrantRemoved();
            else notifyChanged();
            return status;
        }
        DeadlineTask task = REQUEST.get();
        if (subscriptionCallback != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if ((task == null || SystemClock.elapsedRealtime() < task.deadline)
                        && current(account, owner, epoch)) subscriptionCallback.done(status);
            });
        }
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        String alias = prefs().getString("key_" + owner, "");
        boolean newKey = alias.isEmpty() || !keys.containsAlias(alias);
        if (newKey) {
            byte[] suffix = new byte[12]; RANDOM.nextBytes(suffix);
            alias = "nimarko_wl_" + owner + "_" + b64(suffix);
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
            synchronized (LOCK) {
                if (!current(account, owner, epoch)) {
                    keys.deleteEntry(alias);
                    throw new IOException("account_changed");
                }
                prefs().edit().putString("key_" + owner, alias).apply();
            }
        }
        PrivateKey key = (PrivateKey) keys.getKey(alias, null);
        Grant grant = newKey ? null : GRANTS.get(owner);
        if (grant == null && !newKey) {
            try {
                JSONObject old = new JSONObject(prefs().getString("grant_" + owner, ""));
                if (old.getLong("uid") == owner && old.optLong("paid_expires_at", 0) > now)
                    grant = new Grant(owner, old.getLong("expires"), old.getString("token"), key, paidExpires);
            } catch (Exception ignored) { }
        }
        if (grant == null || grant.expires < now + 86400) {
            JSONObject request = new JSONObject().put("uid", owner)
                    .put("public_key", b64(keys.getCertificate(alias).getPublicKey().getEncoded()));
            JSONObject pending = call(PREFIX + "/register", "POST", auth, bytes(request));
            String code = pending.getString("code"), secret = pending.getString("poll_secret");
            JSONObject poll = new JSONObject().put("code", code).put("poll_secret", secret)
                    .put("signature", sign(key, ("nimarko-wl-enroll-v1\n" + code + "\n" + secret).getBytes(StandardCharsets.US_ASCII)));
            InlineAuth.sendInlineVerification(account, "NimarkoBanner_Bot", code,
                    () -> (task == null || task.active()) && current(account, owner, epoch));
            JSONObject issued = null;
            for (int attempt = 0; attempt < 15; attempt++) {
                if (!current(account, owner, epoch)) throw new IOException("account_changed");
                issued = call(PREFIX + "/poll", "POST", auth, bytes(poll));
                if (!"verifying".equals(issued.optString("status"))) break;
                Thread.sleep(Math.min(2000, remainingRequestMillis()));
            }
            if (issued == null || "verifying".equals(issued.optString("status"))) {
                throw new IOException("authentication_required");
            }
            if (!"approved".equals(issued.optString("status")) || issued.getLong("uid") != owner)
                throw new IOException("authentication_required");
            issued.put("paid_expires_at", paidExpires);
            grant = new Grant(owner, issued.getLong("expires"), issued.getString("token"), key, paidExpires);
            synchronized (LOCK) {
                remainingRequestMillis();
                if (!current(account, owner, epoch)) throw new IOException("account_changed");
                prefs().edit().putString("grant_" + owner, issued.toString()).apply();
            }
        }
        if (grant.paidUntil != paidExpires) {
            grant = new Grant(owner, grant.expires, grant.token, grant.key, paidExpires);
        }
        boolean changed;
        synchronized (LOCK) {
            remainingRequestMillis();
            if (!current(account, owner, epoch)) throw new IOException("account_changed");
            prefs().edit().putString("grant_" + owner, new JSONObject()
                    .put("uid", owner).put("expires", grant.expires).put("token", grant.token)
                    .put("paid_expires_at", grant.paidUntil).toString()).apply();
            changed = GRANTS.put(owner, grant) != grant;
        }
        if (changed) notifyChanged();
        return "approved";
    }

    public static Map<String, String> headers(Grant grant, String method, String path, byte[] body) throws IOException {
        try {
            int account = accountFor(grant.uid);
            if (account < 0 || !isCurrent(grant)) throw new IOException("account_changed");
            Map<String, String> out = signedHeaders(grant, ConnectionsManager.getInstance(account).getCurrentTime(), method, path, body);
            if (!isCurrent(grant)) throw new IOException("account_changed");
            return out;
        } catch (Exception e) { throw new IOException("access_proof_failed", e); }
    }

    private static Map<String, String> signedHeaders(Grant grant, long stamp, String method, String path, byte[] body) throws Exception {
            byte[] nonceBytes = new byte[24]; RANDOM.nextBytes(nonceBytes);
            String nonce = b64(nonceBytes);
            String timestamp = String.valueOf(stamp);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body == null ? EMPTY : body);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            String message = "nimarko-wl-request-v1\n" + grant.token + "\n" + timestamp + "\n" + nonce
                    + "\n" + method + "\n" + path + "\n" + hex;
            Map<String, String> out = new HashMap<>();
            out.put("X-WL-Token", grant.token); out.put("X-WL-Time", timestamp); out.put("X-WL-Nonce", nonce);
            out.put("X-WL-Signature", sign(grant.key, message.getBytes(StandardCharsets.US_ASCII)));
            return out;
    }

    public static void rejected(Grant grant) {
        synchronized (LOCK) {
            if (grant == null || !GRANTS.remove(grant.uid, grant)) return;
            prefs().edit().remove("grant_" + grant.uid).apply();
            lastWarm = 0;
        }
        notifyChanged();
        warm();
    }

    public static void onAccountLoggedOut(int account, long owner) {
        final String oldAlias, oldGrant;
        if (owner <= 0) return;
        final long clockOffset = ConnectionsManager.getInstance(account).getCurrentTime() - System.currentTimeMillis() / 1000L;
        synchronized (LOCK) {
            oldAlias = prefs().getString("key_" + owner, "");
            oldGrant = prefs().getString("grant_" + owner, "");
            EPOCHS.merge(owner, 1L, Long::sum);
            GRANTS.remove(owner);
            SUBSCRIPTIONS.remove(owner);
            prefs().edit().remove("grant_" + owner).remove("key_" + owner).apply();
        }
        notifyChanged();
        Runnable cleanup = () -> {
            try {
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
                Map<String, String> proof = null;
                try {
                    JSONObject stored = new JSONObject(oldGrant);
                    PrivateKey key = (PrivateKey) keys.getKey(oldAlias, null);
                    if (key != null && stored.getLong("uid") == owner) {
                        Grant grant = new Grant(owner, stored.getLong("expires"), stored.getString("token"), key);
                        proof = signedHeaders(grant, System.currentTimeMillis() / 1000L + clockOffset,
                                "POST", PREFIX + "/forget", EMPTY);
                    }
                } catch (Exception ignored) { }
                if (!oldAlias.isEmpty()) keys.deleteEntry(oldAlias);
                if (proof != null) {
                    Request.Builder request = new Request.Builder().url(ORIGIN + PREFIX + "/forget")
                            .post(RequestBody.create(JSON, EMPTY));
                    for (Map.Entry<String, String> header : proof.entrySet()) request.header(header.getKey(), header.getValue());
                    try (Response response = HTTP.newCall(request.build()).execute()) { }
                }
            } catch (Exception ignored) { }
        };
        try { WORK.execute(cleanup); }
        catch (RejectedExecutionException e) {
            Utilities.globalQueue.postRunnable(cleanup);
        }
        if (enabled()) AndroidUtilities.runOnUIThread(() -> {
            WsBypassController.getInstance().stop();
            WsBypassController.getInstance().ensureStarted();
        });
    }


    private static String recordBlocked(int account, long owner, long epoch) {
        boolean changed;
        synchronized (LOCK) {
            if (!current(account, owner, epoch)) return "account_changed";
            Subscription previous = SUBSCRIPTIONS.get(owner);
            SUBSCRIPTIONS.put(owner, new Subscription("blocked", previous == null ? 0 : previous.expiresAt,
                    previous == null ? -1 : previous.priceCents, previous == null ? "" : previous.priceVersion));
            changed = GRANTS.remove(owner) != null;
            prefs().edit().remove("grant_" + owner).apply();
        }
        if (changed || enabled() && cached() == null) onGrantRemoved();
        else notifyChanged();
        return "blocked";
    }

    private static String accessErrorStatus(int account, long owner, long epoch, Exception error) {
        String status = errorStatus(error);
        return "blocked".equals(status) ? recordBlocked(account, owner, epoch) : status;
    }
    private static String errorStatus(Exception error) {
        if (error instanceof ApiError) {
            ApiError api = (ApiError) error;
            if (api.code == 429) return "busy";
            if (api.code == 401) return "authentication_required";
            if (api.code >= 400 && api.code < 500 && api.detail.equals("blocked")) return "blocked";
            if (api.detail.equals("invalid_screenshot")) return "invalid_screenshot";
        }
        if ("file_too_large".equals(error.getMessage())) return "file_too_large";
        if ("account_changed".equals(error.getMessage())) return "account_changed";
        if ("authentication_required".equals(error.getMessage())) return "authentication_required";
        return "error";
    }

    public static VoipBypassCore.RelayEndpoint allocateCall(
            String ip, int port, int budgetMs) throws Exception {
        if (budgetMs <= 0 || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null;
        final long deadline = SystemClock.elapsedRealtime() + budgetMs;
        Grant grant = cached();
        if (grant == null || !enabled()) { warm(); return null; }
        byte[] body = bytes(new JSONObject().put("ip", ip).put("port", port));
        Request.Builder request = new Request.Builder().url(ORIGIN + "/calls/allocate")
                .post(RequestBody.create(JSON, body));
        for (Map.Entry<String, String> header : headers(grant, "POST", "/calls/allocate", body).entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        final java.util.concurrent.atomic.AtomicReference<java.net.InetAddress> peer =
                new java.util.concurrent.atomic.AtomicReference<>();
        OkHttpClient client = HTTP.newBuilder()
                .proxy(java.net.Proxy.NO_PROXY)
                .dns(hostname -> {
                    if (!HOST.equalsIgnoreCase(hostname)) throw new java.net.UnknownHostException("untrusted_host");
                    java.util.concurrent.Future<java.util.List<java.net.InetAddress>> lookup = null;
                    try {
                        long remaining = deadline - SystemClock.elapsedRealtime();
                        if (remaining <= 0) throw new java.net.UnknownHostException("dns_timeout");
                        lookup = CALL_DNS.submit(() -> okhttp3.Dns.SYSTEM.lookup(HOST));
                        return lookup.get(remaining, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new java.net.UnknownHostException("dns_interrupted");
                    } catch (Exception error) {
                        throw new java.net.UnknownHostException("dns_unavailable");
                    } finally {
                        if (lookup != null) lookup.cancel(true);
                        CALL_DNS.purge();
                    }
                })
                .eventListener(new okhttp3.EventListener() {
                    @Override
                    public void connectionAcquired(okhttp3.Call call, okhttp3.Connection connection) {
                        peer.set(connection.socket().getInetAddress());
                    }
                }).build();
        okhttp3.Call call = client.newCall(request.build());
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) return null;
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (response.code() == 401 || response.code() == 403) { rejected(grant); return null; }
            JSONObject result = read(response);
            String host = result.getString("host"); int allocatedPort = result.getInt("port");
            java.net.InetAddress connected = peer.get();
            if (response.handshake() == null || connected == null
                    || !(HOST.equalsIgnoreCase(host) || connected.getHostAddress().equalsIgnoreCase(host))
                    || allocatedPort < 24000 || allocatedPort >= 25000
                    || SystemClock.elapsedRealtime() >= deadline
                    || !isCurrent(grant) || !enabled()) return null;
            return new VoipBypassCore.RelayEndpoint(connected.getHostAddress(), allocatedPort);
        }
    }

    private static JSONObject call(String path, String method, String auth, byte[] body) throws Exception {
        long remaining = remainingRequestMillis();
        Request.Builder builder = new Request.Builder().url(ORIGIN + path);
        if (auth != null) builder.header("X-Auth-Token", auth);
        if (method.equals("POST")) builder.post(RequestBody.create(JSON, body == null ? EMPTY : body));
        okhttp3.Call call = HTTP.newCall(builder.build());
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            JSONObject result = read(response);
            remainingRequestMillis();
            return result;
        }
    }

    private static JSONObject read(Response response) throws Exception {
        if (response.body() == null) throw new IOException("empty_response");
        byte[] data;
        try (InputStream in = response.body().byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 65536) throw new IOException("response_too_large");
                out.write(buffer, 0, count);
            }
            data = out.toByteArray();
        }
        JSONObject result;
        try { result = new JSONObject(new String(data, StandardCharsets.UTF_8)); }
        catch (Exception e) { result = new JSONObject(); }
        if (response.code() < 200 || response.code() >= 300)
            throw new ApiError(response.code(), result.optString("detail", "error"));
        return result;
    }

    private static final class ApiError extends IOException {
        final int code; final String detail;
        ApiError(int code, String detail) { super("http_" + code); this.code = code; this.detail = detail; }
    }

    private static String sign(PrivateKey key, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key); signer.update(message); return b64(signer.sign());
    }
    private static String b64(byte[] data) { return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static byte[] bytes(JSONObject json) { return json.toString().getBytes(StandardCharsets.UTF_8); }
    private WlAccess() { }
}
