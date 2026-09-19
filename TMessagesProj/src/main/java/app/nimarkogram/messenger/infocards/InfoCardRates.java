package app.nimarkogram.messenger.infocards;

import android.util.Log;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public final class InfoCardRates {

    private static final String TAG = "NimarkoCardRates";
    private static final String RATES_URL = "https://ettacent.dev/rates.json";
    private static final String PREFS_NAME = "nm_pillstack_rates";
    private static final String PREFS_DATA = "snapshot";
    private static final String PREFS_TIME = "snapshot_time";

    private static final long MIN_REFETCH_MS = 60_000L;

    private static final class RateSnapshot {
        final JSONObject data;
        final long fetchedAtMs;

        RateSnapshot(JSONObject data, long fetchedAtMs) {
            this.data = data;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private static volatile RateSnapshot snapshot;
    private static volatile boolean persistedLoaded;
    private static RateSnapshot currentSnapshot() {
        if (ApplicationLoader.applicationContext == null) {
            return snapshot;
        }
        if (!persistedLoaded) {
            synchronized (InfoCardRates.class) {
                if (!persistedLoaded) {
                    try {
                        android.content.SharedPreferences prefs = ApplicationLoader.applicationContext
                                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
                        String raw = prefs.getString(PREFS_DATA, null);
                        long time = prefs.getLong(PREFS_TIME, 0L);
                        if (raw != null && !raw.isEmpty() && time > 0L) {
                            snapshot = new RateSnapshot(new JSONObject(raw), time);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "restore snapshot failed", t);
                    }
                    persistedLoaded = true;
                }
            }
        }
        return snapshot;
    }
    private static void persistSnapshot(RateSnapshot value) {
        if (value == null || value.data == null || ApplicationLoader.applicationContext == null) return;
        try {
            ApplicationLoader.applicationContext.getSharedPreferences(
                            PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREFS_DATA, value.data.toString())
                    .putLong(PREFS_TIME, value.fetchedAtMs)
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "persist snapshot failed", t);
        }
    }

    private static final ArrayList<Runnable> pending = new ArrayList<>();
    private static final ArrayList<WeakReference<CallbackHandle>> weakPending = new ArrayList<>();
    private static boolean fetching = false;

    public static final class CallbackHandle {
        private Runnable callback;
        private boolean canceled;

        private CallbackHandle(Runnable callback) {
            this.callback = callback;
        }

        public void cancel() {
            synchronized (pending) {
                canceled = true;
                callback = null;
                for (int i = weakPending.size() - 1; i >= 0; i--) {
                    CallbackHandle handle = weakPending.get(i).get();
                    if (handle == null || handle == this) weakPending.remove(i);
                }
            }
        }

        private void dispatch() {
            Runnable runnable;
            synchronized (pending) {
                if (canceled) return;
                canceled = true;
                runnable = callback;
                callback = null;
            }
            if (runnable != null) runnable.run();
        }
    }

    public static void prefetch() {
        fetch(false, null);
    }

    public static void fetch(final boolean force, final Runnable onDone) {
        fetchInternal(force, onDone, null);
    }

    public static CallbackHandle fetchWeak(final boolean force, final Runnable onDone) {
        CallbackHandle handle = onDone == null ? null : new CallbackHandle(onDone);
        fetchInternal(force, null, handle);
        return handle;
    }

    private static void fetchInternal(final boolean force, final Runnable onDone,
                                      final CallbackHandle weakCallback) {
        final long now = System.currentTimeMillis();
        RateSnapshot current = currentSnapshot();
        if (!force && isFresh(current, now)) {
            if (onDone != null) AndroidUtilities.runOnUIThread(onDone);
            if (weakCallback != null) postWeak(weakCallback);
            return;
        }
        
        final boolean startFetch;
        final boolean becameFresh;
        synchronized (pending) {
            
            becameFresh = !force && isFresh(currentSnapshot(), System.currentTimeMillis());
            if (!becameFresh) {
                if (onDone != null) {
                    pending.add(onDone);
                }
                if (weakCallback != null) {
                    weakPending.add(new WeakReference<>(weakCallback));
                }
            }
            startFetch = !becameFresh && !fetching;
            if (startFetch) fetching = true;
        }
        if (becameFresh) {
            if (onDone != null) AndroidUtilities.runOnUIThread(onDone);
            if (weakCallback != null) postWeak(weakCallback);
            return;
        }
        if (!startFetch) {
            return; 
        }
        new Thread(() -> {
            try {
                JSONObject o = doFetch();
                if (o != null) {
                    RateSnapshot value = new RateSnapshot(o, System.currentTimeMillis());
                    snapshot = value;
                    persistSnapshot(value);
                }
            } catch (Throwable t) {
                Log.e(TAG, "fetch failed: " + t);
            } finally {
                final ArrayList<Runnable> toRun;
                final ArrayList<WeakReference<CallbackHandle>> weakToRun;
                synchronized (pending) {
                    toRun = new ArrayList<>(pending);
                    pending.clear();
                    weakToRun = new ArrayList<>(weakPending);
                    weakPending.clear();
                    fetching = false;
                }
                for (int i = 0; i < toRun.size(); i++) {
                    AndroidUtilities.runOnUIThread(toRun.get(i));
                }
                for (int i = 0; i < weakToRun.size(); i++) {
                    CallbackHandle handle = weakToRun.get(i).get();
                    if (handle != null) postWeak(handle);
                }
            }
        }, "pill-rates").start();
    }

    private static void postWeak(CallbackHandle handle) {
        final WeakReference<CallbackHandle> ref = new WeakReference<>(handle);
        AndroidUtilities.runOnUIThread(() -> {
            CallbackHandle callback = ref.get();
            if (callback != null) callback.dispatch();
        });
    }

    private static boolean isFresh(RateSnapshot value, long now) {
        return value != null && now - value.fetchedAtMs < MIN_REFETCH_MS;
    }

    private static JSONObject doFetch() {
        HttpURLConnection con = null;
        try {
            URL url = new URL(RATES_URL);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);
            if (con.getResponseCode() != 200) {
                Log.e(TAG, "http " + con.getResponseCode());
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Throwable t) {
            Log.e(TAG, "doFetch failed: " + t);
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public static boolean hasCached() {
        return currentSnapshot() != null;
    }

    public static JSONObject cached() {
        RateSnapshot value = currentSnapshot();
        if (value == null) return null;
        try {
            return new JSONObject(value.data.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static double coinUsd(String coin) {
        return coinUsd(currentSnapshot(), coin);
    }

    private static double coinUsd(RateSnapshot value, String coin) {
        JSONObject s = value != null ? value.data : null;
        if (s == null || coin == null) return 0;
        JSONObject coins = s.optJSONObject("coins");
        if (coins == null) return 0;
        JSONObject c = coins.optJSONObject(coin.toLowerCase());
        if (c == null) return 0;
        return c.optDouble("usd", 0);
    }

    public static double fiatRate(String ccy) {
        return fiatRate(currentSnapshot(), ccy);
    }

    private static double fiatRate(RateSnapshot value, String ccy) {
        if (ccy == null || ccy.equalsIgnoreCase("USD")) return 1.0;
        JSONObject s = value != null ? value.data : null;
        if (s == null) return 1.0;
        JSONObject fiat = s.optJSONObject("fiat");
        if (fiat == null) return 1.0;
        double r = fiat.optDouble(ccy.toUpperCase(), Double.NaN);
        return Double.isNaN(r) ? 1.0 : r;
    }

    public static double coinInFiat(String coin, String ccy) {
        RateSnapshot value = currentSnapshot();
        return coinUsd(value, coin) * fiatRate(value, ccy);
    }

    public static Double change24h(String coin) {
        return coinChange(coin, "change_24h");
    }

    public static Double change7d(String coin) {
        return coinChange(coin, "change_7d");
    }

    private static Double coinChange(String coin, String key) {
        RateSnapshot value = currentSnapshot();
        JSONObject s = value != null ? value.data : null;
        if (s == null || coin == null) return null;
        JSONObject coins = s.optJSONObject("coins");
        if (coins == null) return null;
        JSONObject c = coins.optJSONObject(coin.toLowerCase());
        if (c == null || !c.has(key)) return null;
        double v = c.optDouble(key, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    private InfoCardRates() {}
}
