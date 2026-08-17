 
package app.nimarkogram.messenger.updater;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

public final class NimarkoUpdateConfig {

    private NimarkoUpdateConfig() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nimarko_update", android.content.Context.MODE_PRIVATE);
    }

    public static boolean getAutoOTA() { return prefs().getBoolean("autoOTA", true); }
    public static void setAutoOTA(boolean v) { prefs().edit().putBoolean("autoOTA", v).apply(); }

    public static boolean getUpdateAvailable() { return prefs().getBoolean("updateAvailable", false); }
    public static void setUpdateAvailable(boolean v) { prefs().edit().putBoolean("updateAvailable", v).apply(); }

    public static String getUpdateVersionName() { return prefs().getString("updateVersionName", ""); }
    public static void setUpdateVersionName(String v) { prefs().edit().putString("updateVersionName", v == null ? "" : v).apply(); }

    public static String getUpdateSize() { return prefs().getString("updateSize", ""); }
    public static void setUpdateSize(String v) { prefs().edit().putString("updateSize", v == null ? "" : v).apply(); }

    public static boolean getUpdateIsDownloading() { return prefs().getBoolean("updateIsDownloading", false); }
    public static void setUpdateIsDownloading(boolean v) { prefs().edit().putBoolean("updateIsDownloading", v).apply(); }

    public static float getUpdateDownloadingProgress() { return prefs().getFloat("updateDownloadingProgress", 0f); }
    public static void setUpdateDownloadingProgress(float v) { prefs().edit().putFloat("updateDownloadingProgress", v).apply(); }

    public static long getUpdateScheduleTimestamp() { return prefs().getLong("updateScheduleTimestamp", 0L); }
    public static void setUpdateScheduleTimestamp(long v) { prefs().edit().putLong("updateScheduleTimestamp", v).apply(); }

    public static long getLastUpdateCheckTime() { return prefs().getLong("lastUpdateCheckTime", 0L); }
    public static void setLastUpdateCheckTime(long v) { prefs().edit().putLong("lastUpdateCheckTime", v).apply(); }

    public static String getApkSha256() { return prefs().getString("apkSha256", ""); }
    public static void setApkSha256(String v) {
        prefs().edit().putString("apkSha256", v == null ? "" : v).apply();
    }

    public static String getPausedDownloadLink() { return prefs().getString("pausedDownloadLink", ""); }
    public static void setPausedDownloadLink(String v) { prefs().edit().putString("pausedDownloadLink", v == null ? "" : v).apply(); }

    public static long getPausedDownloadOffset() { return prefs().getLong("pausedDownloadOffset", 0L); }
    public static void setPausedDownloadOffset(long v) { prefs().edit().putLong("pausedDownloadOffset", v).apply(); }

    public static String getPausedDownloadVersion() { return prefs().getString("pausedDownloadVersion", ""); }
    public static void setPausedDownloadVersion(String v) { prefs().edit().putString("pausedDownloadVersion", v == null ? "" : v).apply(); }

    public static long getPausedDownloadSize() { return prefs().getLong("pausedDownloadSize", 0L); }
    public static void setPausedDownloadSize(long v) { prefs().edit().putLong("pausedDownloadSize", v).apply(); }

    public static String getPausedDownloadSha256() { return prefs().getString("pausedDownloadSha256", ""); }
    public static void setPausedDownloadSha256(String v) { prefs().edit().putString("pausedDownloadSha256", v == null ? "" : v).apply(); }

    public static void clearPausedDownload() {
        prefs().edit()
                .remove("pausedDownloadLink")
                .remove("pausedDownloadOffset")
                .remove("pausedDownloadVersion")
                .remove("pausedDownloadSize")
                .remove("pausedDownloadSha256")
                .apply();
    }

    public static void setLastUpdate(String version, int versionCode, String url, String changelog, String size, String uploadDate) {
        prefs().edit()
                .putString("lastUpdateVersion", version == null ? "" : version)
                .putInt("lastUpdateVersionCode", versionCode)
                .putString("lastUpdateUrl", url == null ? "" : url)
                .putString("lastUpdateChangelog", changelog == null ? "" : changelog)
                .putString("lastUpdateSize", size == null ? "" : size)
                .putString("lastUpdateDate", uploadDate == null ? "" : uploadDate)
                .apply();
    }
    public static String getLastUpdateVersion() { return prefs().getString("lastUpdateVersion", ""); }
    public static int getLastUpdateVersionCode() { return prefs().getInt("lastUpdateVersionCode", 0); }
    public static String getLastUpdateUrl() { return prefs().getString("lastUpdateUrl", ""); }
    public static String getLastUpdateChangelog() { return prefs().getString("lastUpdateChangelog", ""); }
    public static String getLastUpdateSize() { return prefs().getString("lastUpdateSize", ""); }
    public static String getLastUpdateDate() { return prefs().getString("lastUpdateDate", ""); }

    public static boolean isStandalonePremiumBuild() { return false; }
    public static boolean isStandaloneBetaBuild() { return false; }
    public static boolean isDevBuild() { return false; }
}
