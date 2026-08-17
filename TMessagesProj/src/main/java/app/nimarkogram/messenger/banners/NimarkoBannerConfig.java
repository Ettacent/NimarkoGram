 
package app.nimarkogram.messenger.banners;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.io.File;

public final class NimarkoBannerConfig {

    private static final String PREFS = "nimarko_banners";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    private static SharedPreferences.Editor editor() {
        return prefs().edit();
    }

    public static volatile boolean enabled = prefs().getBoolean("enabled", false);
    public static void setEnabled(boolean v) {
        enabled = v;
        editor().putBoolean("enabled", v).apply();
    }
    public static void toggleEnabled() {
        setEnabled(!enabled);
    }

    public static volatile boolean useAvatar = prefs().getBoolean("use_avatar", false);
    public static void setUseAvatar(boolean v) {
        useAvatar = v;
        editor().putBoolean("use_avatar", v).apply();
    }

    public static volatile boolean liteMode = prefs().getBoolean("lite_mode", false);
    public static void setLiteMode(boolean v) {
        liteMode = v;
        editor().putBoolean("lite_mode", v).apply();
    }

    private static int currentAccount() {
        try { return UserConfig.selectedAccount; } catch (Throwable ignored) { return 0; }
    }

    private static long currentUid(int account) {
        try { return UserConfig.getInstance(account).getClientUserId(); } catch (Throwable ignored) { return 0L; }
    }

    private static String scopeKey(String key, int account, long uid) {
        return key + "_account_" + account + "_uid_" + uid;
    }

    private static String legacyAccountKey(String key, int account) {
        return key + "_account_" + account;
    }

    public static synchronized long activateScope(int account, long uid) {
        if (uid == 0L) return 0L;
        SharedPreferences p = prefs();
        String ownerKey = "slot_owner_uid_" + account;
        long previousUid = p.getLong(ownerKey, 0L);
        SharedPreferences.Editor e = p.edit();
        if (previousUid != 0L && previousUid != uid) {
            String oldPath = p.getString(scopeKey("local_banner_path", account, previousUid), "");
            if (oldPath != null && !oldPath.isEmpty()) {
                try { new File(oldPath).delete(); } catch (Throwable ignored) {}
            }
            e.remove(scopeKey("local_banner_path", account, previousUid));
            e.remove(scopeKey("auth_token", account, previousUid));
        }

        for (String key : new String[]{"local_banner_path", "auth_token"}) {
            String scoped = scopeKey(key, account, uid);
            String legacy = legacyAccountKey(key, account);
            if (!p.contains(scoped) && p.contains(legacy)) {
                e.putString(scoped, p.getString(legacy, ""));
            }
            e.remove(legacy);
        }
        e.putLong(ownerKey, uid).apply();
        return previousUid != uid ? previousUid : 0L;
    }

    public static volatile String localBannerPath = getLocalBannerPath();
    public static String getLocalBannerPath() {
        int account = currentAccount();
        return getLocalBannerPath(account, currentUid(account));
    }
    public static String getLocalBannerPath(int account, long uid) {
        if (uid == 0L) return "";
        return prefs().getString(scopeKey("local_banner_path", account, uid), "");
    }
    public static void setLocalBannerPath(String v) {
        int account = currentAccount();
        setLocalBannerPath(account, currentUid(account), v);
    }
    public static void setLocalBannerPath(int account, long uid, String v) {
        if (uid == 0L) return;
        localBannerPath = v == null ? "" : v;
        editor().putString(scopeKey("local_banner_path", account, uid), localBannerPath).apply();
    }

    public static volatile String authToken = getAuthToken();
    public static String getAuthToken() {
        int account = currentAccount();
        return getAuthToken(account, currentUid(account));
    }
    public static String getAuthToken(int account, long uid) {
        if (uid == 0L) return "";
        return prefs().getString(scopeKey("auth_token", account, uid), "");
    }
    public static void setAuthToken(String v) {
        int account = currentAccount();
        setAuthToken(account, currentUid(account), v);
    }
    public static void setAuthToken(int account, long uid, String v) {
        if (uid == 0L) return;
        authToken = v == null ? "" : v;
        editor().putString(scopeKey("auth_token", account, uid), authToken).apply();
    }

    public static void reloadAccount() {
        int account = currentAccount();
        long uid = currentUid(account);
        activateScope(account, uid);
        localBannerPath = getLocalBannerPath();
        authToken = getAuthToken();
    }

    private NimarkoBannerConfig() {}
}
