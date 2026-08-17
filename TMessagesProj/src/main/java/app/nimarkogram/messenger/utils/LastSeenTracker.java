 
package app.nimarkogram.messenger.utils;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public final class LastSeenTracker {

    private static final String PREF_FILE = "nimarko_last_seen";
    private static final int WINDOW_SIZE = 20;       
    private static final long DEDUP_GAP_MS = 30L * 60L * 1000L; 
    private static final int MIN_SAMPLES_FOR_HINT = 5;
    private static final double DOMINANCE_RATIO = 0.6; 
    private static final int MAX_TRACKED_USERS = 500;  
    private static final long RETENTION_SEC = 90L * 24L * 60L * 60L;
    private static final String KEY_PREFIX = "u_";
    private static final String IDENTITY_MIGRATION_PREFIX = "identity_migrated_a";
    private static final String GLOBAL_LEGACY_CLEANED = "global_legacy_cleaned";
    private static final Object LOCK = new Object();

    private LastSeenTracker() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_FILE, 0);
    }

    public static void recordStatus(int account, long userId, TLRPC.UserStatus status) {
        if (userId == 0 || status == null) return;
        long ownerUid = ownerUid(account);
        if (ownerUid <= 0) return;

        long stampSec;
        if (status instanceof TLRPC.TL_userStatusOnline) {
            stampSec = System.currentTimeMillis() / 1000L;
        } else if (status instanceof TLRPC.TL_userStatusOffline) {
            
            int expires = status.expires;
            if (expires <= 0) return;
            stampSec = expires;
            long nowSec = System.currentTimeMillis() / 1000L;
            
            if (nowSec - stampSec > 24L * 60L * 60L) return;
        } else {
            return; 
        }
        long validationNowSec = System.currentTimeMillis() / 1000L;
        if (stampSec <= 0 || stampSec > validationNowSec + 5L * 60L
                || stampSec < validationNowSec - RETENTION_SEC) return;

        synchronized (LOCK) {
            if (ownerUid(account) != ownerUid) return;
            SharedPreferences sp = prefs();
            migrateIdentityLocked(sp, account, ownerUid);
            String identityPrefix = identityPrefix(account, ownerUid);
            String key = keyFor(account, ownerUid, userId);
            
            pruneIfNeededLocked(sp, identityPrefix, key);
            String existing = sp.getString(key, "");
            Deque<Long> window = parse(existing);
            long nowSec = System.currentTimeMillis() / 1000L;
            boolean pruned = pruneExpired(window, nowSec);

            if (!window.isEmpty()) {
                long last = window.peekLast();
                if (Math.abs(stampSec - last) * 1000L < DEDUP_GAP_MS) {
                    if (pruned && ownerUid(account) == ownerUid) {
                        sp.edit().putString(key, serialize(window)).apply();
                    }
                    return; 
                }
            }
            window.addLast(stampSec);
            while (window.size() > WINDOW_SIZE) {
                window.pollFirst();
            }
            if (ownerUid(account) == ownerUid) {
                sp.edit().putString(key, serialize(window)).apply();
            }
        }
    }

    public static String getPatternHint(int account, long userId) {
        if (userId == 0) return null;
        long ownerUid = ownerUid(account);
        if (ownerUid <= 0) return null;
        Deque<Long> window;
        synchronized (LOCK) {
            if (ownerUid(account) != ownerUid) return null;
            SharedPreferences preferences = prefs();
            migrateIdentityLocked(preferences, account, ownerUid);
            String key = keyFor(account, ownerUid, userId);
            String raw = preferences.getString(key, "");
            window = parse(raw);
            if (pruneExpired(window, System.currentTimeMillis() / 1000L)
                    && ownerUid(account) == ownerUid) {
                if (window.isEmpty()) preferences.edit().remove(key).apply();
                else preferences.edit().putString(key, serialize(window)).apply();
            }
        }
        if (window.isEmpty()) return null;
        if (window.size() < MIN_SAMPLES_FOR_HINT) return null;

        int[] buckets = new int[4];
        Calendar cal = Calendar.getInstance(TimeZone.getDefault());
        for (long secs : window) {
            cal.setTimeInMillis(secs * 1000L);
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            buckets[bucketOf(hour)]++;
        }

        int total = window.size();
        int bestIdx = 0;
        int bestVal = buckets[0];
        for (int i = 1; i < buckets.length; i++) {
            if (buckets[i] > bestVal) {
                bestVal = buckets[i];
                bestIdx = i;
            }
        }
        if (bestVal < (int) Math.ceil(total * DOMINANCE_RATIO)) {
            return null;
        }
        int resId;
        switch (bestIdx) {
            case 0: resId = R.string.NM_Extra_LastSeenPattern_Morning; break;
            case 1: resId = R.string.NM_Extra_LastSeenPattern_Afternoon; break;
            case 2: resId = R.string.NM_Extra_LastSeenPattern_Evening; break;
            default: resId = R.string.NM_Extra_LastSeenPattern_Night; break;
        }
        if (ownerUid(account) != ownerUid) return null;
        return LocaleController.getString(resId);
    }

    public static void forget(int account, long userId) {
        if (userId == 0) return;
        long ownerUid = ownerUid(account);
        if (ownerUid <= 0) return;
        synchronized (LOCK) {
            if (ownerUid(account) != ownerUid) return;
            SharedPreferences preferences = prefs();
            migrateIdentityLocked(preferences, account, ownerUid);
            preferences.edit().remove(keyFor(account, ownerUid, userId)).apply();
        }
    }

    private static int bucketOf(int hour) {
        if (hour >= 6 && hour < 12)  return 0; 
        if (hour >= 12 && hour < 18) return 1; 
        if (hour >= 18 && hour < 23) return 2; 
        return 3; 
    }

    private static long ownerUid(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0L;
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static String identityPrefix(int account, long ownerUid) {
        return "a_" + account + "_o_" + ownerUid + "_" + KEY_PREFIX;
    }

    private static String keyFor(int account, long ownerUid, long userId) {
        return identityPrefix(account, ownerUid) + userId;
    }

    private static void migrateIdentityLocked(SharedPreferences sp, int account, long ownerUid) {
        String migrationKey = IDENTITY_MIGRATION_PREFIX + account + "_o" + ownerUid;
        boolean identityDone = sp.getBoolean(migrationKey, false);
        boolean globalDone = sp.getBoolean(GLOBAL_LEGACY_CLEANED, false);
        if (identityDone && globalDone) return;

        SharedPreferences.Editor editor = sp.edit();
        if (!globalDone) {
            for (String key : sp.getAll().keySet()) {
                if (key != null && key.startsWith(KEY_PREFIX)) editor.remove(key);
            }
            editor.putBoolean(GLOBAL_LEGACY_CLEANED, true);
        }
        if (!identityDone) {
            String legacyAccountPrefix = "a_" + account + "_" + KEY_PREFIX;
            for (String key : sp.getAll().keySet()) {
                if (key != null && key.startsWith(legacyAccountPrefix)) editor.remove(key);
            }
            editor.putBoolean(migrationKey, true);
        }
        editor.commit();
    }

    @Deprecated public static void recordStatus(long userId, TLRPC.UserStatus status) { recordStatus(UserConfig.selectedAccount, userId, status); }
    @Deprecated public static String getPatternHint(long userId) { return getPatternHint(UserConfig.selectedAccount, userId); }
    @Deprecated public static void forget(long userId) { forget(UserConfig.selectedAccount, userId); }

    private static void pruneIfNeededLocked(SharedPreferences sp, String identityPrefix, String incomingKey) {
        try {
            Map<String, ?> all = sp.getAll();
            if (all == null) return;
            
            int distinct = 0;
            for (String k : all.keySet()) {
                if (k != null && k.startsWith(identityPrefix)) distinct++;
            }
            if (all.containsKey(incomingKey)) return; 
            if (distinct < MAX_TRACKED_USERS) return;

            List<String[]> entries = new ArrayList<>(distinct);
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                if (k == null || !k.startsWith(identityPrefix)) continue;
                long last = 0L;
                Object v = e.getValue();
                if (v instanceof String) {
                    String csv = (String) v;
                    int comma = csv.lastIndexOf(',');
                    String tail = comma >= 0 ? csv.substring(comma + 1) : csv;
                    try { last = Long.parseLong(tail.trim()); } catch (NumberFormatException ignored) {}
                }
                entries.add(new String[]{k, Long.toString(last)});
            }
            Collections.sort(entries, new Comparator<String[]>() {
                @Override public int compare(String[] a, String[] b) {
                    long la = Long.parseLong(a[1]);
                    long lb = Long.parseLong(b[1]);
                    return Long.compare(la, lb); 
                }
            });

            int target = (int) (MAX_TRACKED_USERS * 0.9);
            int toRemove = entries.size() - target;
            if (toRemove <= 0) return;
            SharedPreferences.Editor ed = sp.edit();
            for (int i = 0; i < toRemove && i < entries.size(); i++) {
                ed.remove(entries.get(i)[0]);
            }
            ed.apply();
        } catch (Throwable ignored) {}
    }

    private static Deque<Long> parse(String csv) {
        Deque<Long> out = new ArrayDeque<>(WINDOW_SIZE);
        if (csv == null || csv.isEmpty()) return out;
        String[] parts = csv.split(",");
        for (String p : parts) {
            try {
                out.addLast(Long.parseLong(p));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static boolean pruneExpired(Deque<Long> window, long nowSec) {
        boolean changed = false;
        java.util.Iterator<Long> iterator = window.iterator();
        while (iterator.hasNext()) {
            long sample = iterator.next();
            if (sample <= 0 || sample < nowSec - RETENTION_SEC || sample > nowSec + 5L * 60L) {
                iterator.remove();
                changed = true;
            }
        }
        while (window.size() > WINDOW_SIZE) {
            window.pollFirst();
            changed = true;
        }
        return changed;
    }

    private static String serialize(Deque<Long> window) {
        StringBuilder sb = new StringBuilder(window.size() * 12);
        boolean first = true;
        for (long v : window) {
            if (!first) sb.append(',');
            sb.append(v);
            first = false;
        }
        return sb.toString();
    }
}
