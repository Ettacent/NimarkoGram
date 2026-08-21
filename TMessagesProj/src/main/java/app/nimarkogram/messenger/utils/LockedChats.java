package app.nimarkogram.messenger.utils;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public final class LockedChats {

    private static final String PREF_FILE = "nimarko_locked_chats";
    private static final String LEGACY_PREF_KEY = "ids";
    private static final String LEGACY_ACCOUNT_KEY_PREFIX = "ids_";
    private static final String PREF_KEY_PREFIX = "ids_a";
    private static final String MIGRATION_CLEANED_KEY = "uid_binding_migration_complete";
    private static final java.util.HashMap<String, HashSet<String>> caches = new java.util.HashMap<>();
    private static final HashSet<Integer> loggingOutAccounts = new HashSet<>();

    private LockedChats() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_FILE, 0);
    }

    private static long currentUid(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0L;
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static String keyForIdentity(int account, long uid) {
        return PREF_KEY_PREFIX + account + "_u" + uid;
    }

    private static void cleanUnboundLegacyStorage(SharedPreferences preferences) {
        if (preferences.getBoolean(MIGRATION_CLEANED_KEY, false)) return;
        SharedPreferences.Editor editor = preferences.edit().remove(LEGACY_PREF_KEY);
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            editor.remove(LEGACY_ACCOUNT_KEY_PREFIX + account);
        }
        editor.putBoolean(MIGRATION_CLEANED_KEY, true).commit();
    }

    private static void cleanOtherUidsForSlot(SharedPreferences preferences, int account, String keepKey) {
        String prefix = PREF_KEY_PREFIX + account + "_u";
        SharedPreferences.Editor editor = null;
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix) && !key.equals(keepKey)) {
                if (editor == null) editor = preferences.edit();
                editor.remove(key);
                caches.remove(key);
            }
        }
        if (editor != null) editor.commit();
    }

    private static HashSet<String> getCacheLocked(int account, long expectedUid) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || expectedUid <= 0 || loggingOutAccounts.contains(account)
                || currentUid(account) != expectedUid) return null;
        String identityKey = keyForIdentity(account, expectedUid);
        HashSet<String> cached = caches.get(identityKey);
        if (cached != null) return cached;

        SharedPreferences preferences = prefs();
        cleanUnboundLegacyStorage(preferences);
        cleanOtherUidsForSlot(preferences, account, identityKey);
        String raw = preferences.getString(identityKey, null);
        if (raw == null || raw.isEmpty()) {
            cached = new HashSet<>();
        } else {
            try {
                List<String> list = new Gson().fromJson(raw, new TypeToken<List<String>>() {}.getType());
                cached = list != null ? new HashSet<>(list) : new HashSet<>();
            } catch (Throwable t) {
                cached = new HashSet<>();
            }
        }
        caches.put(identityKey, cached);
        return cached;
    }

    public static boolean isLocked(int account, long dialogId) {
        if (dialogId == 0L) return false;
        synchronized (LockedChats.class) {
            HashSet<String> cache = getCacheLocked(account, currentUid(account));
            return cache != null && cache.contains(String.valueOf(dialogId));
        }
    }

    public static boolean isLocked(long dialogId) {
        return isLocked(UserConfig.selectedAccount, dialogId);
    }

    public static void setLocked(int account, long dialogId, boolean locked) {
        setLocked(account, currentUid(account), dialogId, locked);
    }

    public static boolean setLocked(int account, long expectedUid, long dialogId, boolean locked) {
        if (dialogId == 0L) return false;
        synchronized (LockedChats.class) {
            HashSet<String> c = getCacheLocked(account, expectedUid);
            if (c == null || expectedUid != currentUid(account)) return false;
            String key = String.valueOf(dialogId);
            boolean wasLocked = c.contains(key);
            if (locked) c.add(key); else c.remove(key);
            if (expectedUid != currentUid(account)
                    || !prefs().edit().putString(keyForIdentity(account, expectedUid),
                    new Gson().toJson(new ArrayList<>(c))).commit()) {
                if (wasLocked) c.add(key); else c.remove(key);
                return false;
            }
            return true;
        }
    }

    public static void setLocked(long dialogId, boolean locked) {
        setLocked(UserConfig.selectedAccount, dialogId, locked);
    }

    public static boolean replaceAll(int account, long expectedUid, Collection<Long> dialogIds) {
        synchronized (LockedChats.class) {
            HashSet<String> cache = getCacheLocked(account, expectedUid);
            if (cache == null || expectedUid != currentUid(account)) return false;
            HashSet<String> replacement = new HashSet<>();
            if (dialogIds != null) {
                for (Long dialogId : dialogIds) {
                    if (dialogId != null && dialogId != 0L) {
                        replacement.add(String.valueOf(dialogId));
                    }
                }
            }
            HashSet<String> previous = new HashSet<>(cache);
            cache.clear();
            cache.addAll(replacement);
            if (expectedUid != currentUid(account)
                    || !prefs().edit().putString(keyForIdentity(account, expectedUid),
                    new Gson().toJson(new ArrayList<>(cache))).commit()) {
                cache.clear();
                cache.addAll(previous);
                return false;
            }
            return true;
        }
    }

    public static ArrayList<String> getAll(int account) {
        synchronized (LockedChats.class) {
            HashSet<String> cache = getCacheLocked(account, currentUid(account));
            return cache == null ? new ArrayList<>() : new ArrayList<>(cache);
        }
    }

    public static ArrayList<String> getAll() {
        return getAll(UserConfig.selectedAccount);
    }

    public static int count(int account) {
        synchronized (LockedChats.class) {
            HashSet<String> cache = getCacheLocked(account, currentUid(account));
            return cache == null ? 0 : cache.size();
        }
    }

    public static int count() {
        return count(UserConfig.selectedAccount);
    }

    public static void onAccountLoggedOut(int account, long uid) {
        synchronized (LockedChats.class) {
            loggingOutAccounts.add(account);
            SharedPreferences preferences = prefs();
            String prefix = PREF_KEY_PREFIX + account + "_u";
            SharedPreferences.Editor editor = null;
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(prefix)) {
                    if (editor == null) editor = preferences.edit();
                    editor.remove(key);
                    caches.remove(key);
                }
            }
            if (uid > 0) caches.remove(keyForIdentity(account, uid));
            if (editor != null) editor.commit();
        }
    }

    public static void onAccountOwnerCleared(int account) {
        synchronized (LockedChats.class) {
            loggingOutAccounts.remove(account);
        }
    }
}
