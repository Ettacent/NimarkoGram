package app.nimarkogram.messenger;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public final class NimarkoConfig {
    public static final String APP_NAME = "NimarkoGram";
    public static final String VERSION_NAME = org.telegram.messenger.BuildVars.BUILD_VERSION_STRING;
    public static final int VERSION_CODE = 1;

    public static final long[] TRUSTED_AUTHOR_IDS = new long[0];

    public static final long[] VERIFIED_CHANNEL_IDS = new long[0];

    private static final String PREFS_NAME = "nimarkoconfig";

    private static final Gson GSON = new Gson();
    private static final ReentrantLock SETTINGS_WRITE_LOCK = new ReentrantLock(true);

    private NimarkoConfig() {}

    public static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    public static SharedPreferences.Editor getEditor() {
        return new LockedEditor(getPreferences().edit());
    }

    @FunctionalInterface
    public interface SettingsTransaction<T> {
        T run() throws Exception;
    }

    public static <T> T withSettingsTransaction(SettingsTransaction<T> transaction) throws Exception {
        SETTINGS_WRITE_LOCK.lock();
        try {
            return transaction.run();
        } finally {
            SETTINGS_WRITE_LOCK.unlock();
        }
    }

    private static final class LockedEditor implements SharedPreferences.Editor {
        private final SharedPreferences.Editor delegate;

        LockedEditor(SharedPreferences.Editor delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized SharedPreferences.Editor putString(String key, String value) {
            delegate.putString(key, value);
            return this;
        }

        @Override
        public synchronized SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            delegate.putStringSet(key, values);
            return this;
        }

        @Override
        public synchronized SharedPreferences.Editor putInt(String key, int value) {
            delegate.putInt(key, value);
            return this;
        }

        @Override
        public synchronized SharedPreferences.Editor putLong(String key, long value) {
            delegate.putLong(key, value);
            return this;
        }

        @Override
        public synchronized SharedPreferences.Editor putFloat(String key, float value) {
            delegate.putFloat(key, value);
            return this;
        }

        @Override
        public synchronized SharedPreferences.Editor putBoolean(String key, boolean value) {
            delegate.putBoolean(key, value);
            return this;
        }

        @Override
        public synchronized SharedPreferences.Editor remove(String key) {
            delegate.remove(key);
            return this;
        }

        @Override
        public synchronized SharedPreferences.Editor clear() {
            delegate.clear();
            return this;
        }

        @Override
        public synchronized boolean commit() {
            SETTINGS_WRITE_LOCK.lock();
            try {
                return delegate.commit();
            } finally {
                SETTINGS_WRITE_LOCK.unlock();
            }
        }

        @Override
        public synchronized void apply() {
            SETTINGS_WRITE_LOCK.lock();
            try {
                delegate.apply();
            } finally {
                SETTINGS_WRITE_LOCK.unlock();
            }
        }
    }

    private static int getIntSafe(String key, int def) {
        try {
            return getPreferences().getInt(key, def);
        } catch (ClassCastException oldType) {
            int migrated = def;
            try {
                boolean legacy = getPreferences().getBoolean(key, false);
                migrated = legacy ? def : 0;
            } catch (Throwable ignored) {}
            try {
                getEditor().remove(key).putInt(key, migrated).apply();
            } catch (Throwable ignored) {}
            return migrated;
        }
    }

    public static String fullVersionString() {
        return APP_NAME + " " + VERSION_NAME;
    }

    public static boolean pluginsEngine = getPreferences().getBoolean("pluginsEngine", true);
    public static void togglePluginsEngine() {
        pluginsEngine = !pluginsEngine;
        getEditor().putBoolean("pluginsEngine", pluginsEngine).apply();
    }

    public static boolean pluginsDevMode = getPreferences().getBoolean("pluginsDevMode", false);
    public static void togglePluginsDevMode() {
        pluginsDevMode = !pluginsDevMode;
        getEditor().putBoolean("pluginsDevMode", pluginsDevMode).apply();
    }

    public static boolean pluginsSafeMode = getPreferences().getBoolean("pluginsSafeMode", false);
    public static void togglePluginsSafeMode() {
        pluginsSafeMode = !pluginsSafeMode;
        getEditor().putBoolean("pluginsSafeMode", pluginsSafeMode).apply();
        try {
            org.telegram.messenger.FileLog.d("nimarko: togglePluginsSafeMode -> " + pluginsSafeMode);
        } catch (Throwable ignored) {}
    }
    public static void setPluginsSafeMode(boolean v) {
        pluginsSafeMode = v;
        getEditor().putBoolean("pluginsSafeMode", v).apply();
        try {
            org.telegram.messenger.FileLog.d("nimarko: setPluginsSafeMode = " + v);
        } catch (Throwable ignored) {}
    }

    public static boolean pluginsCompactView = getPreferences().getBoolean("pluginsCompactView", false);
    public static void togglePluginsCompactView() {
        pluginsCompactView = !pluginsCompactView;
        getEditor().putBoolean("pluginsCompactView", pluginsCompactView).apply();
    }

    public static boolean pluginsDisableArtOpts = getPreferences().getBoolean("pluginsDisableArtOpts", false);
    public static void setPluginsDisableArtOpts(boolean v) {
        pluginsDisableArtOpts = v;
        getEditor().putBoolean("pluginsDisableArtOpts", v).apply();
    }

    public static boolean pluginsPySdkAutoUpdate = getPreferences().getBoolean("pluginsPySdkAutoUpdate", true);
    public static void setPluginsPySdkAutoUpdate(boolean v) {
        pluginsPySdkAutoUpdate = v;
        getEditor().putBoolean("pluginsPySdkAutoUpdate", v).apply();
    }

    public static boolean pluginsPySdkBetaVersions = getPreferences().getBoolean("pluginsPySdkBetaVersions", false);
    public static void setPluginsPySdkBetaVersions(boolean v) {
        pluginsPySdkBetaVersions = v;
        getEditor().putBoolean("pluginsPySdkBetaVersions", v).apply();
    }


    public static boolean hideProxySponsor = getPreferences().getBoolean("hideProxySponsor", true);
    public static void toggleHideProxySponsor() {
        hideProxySponsor = !hideProxySponsor;
        getEditor().putBoolean("hideProxySponsor", hideProxySponsor).apply();
    }

    public static volatile boolean silenceNonContacts = getPreferences().getBoolean("silenceNonContacts", false);
    public static void toggleSilenceNonContacts() {
        silenceNonContacts = !silenceNonContacts;
        getEditor().putBoolean("silenceNonContacts", silenceNonContacts).apply();
    }

    private static boolean loadDeleteAuthenticationSetting() {
        SharedPreferences preferences = getPreferences();
        boolean hasCanonical = preferences.contains("askPasscodeBeforeDelete");
        boolean enabled = hasCanonical
                ? preferences.getBoolean("askPasscodeBeforeDelete", false)
                : preferences.getBoolean("askBiometricsBeforeDelete", false);
        if (!hasCanonical
                || preferences.getBoolean("askBiometricsBeforeDelete", !enabled) != enabled) {
            getEditor()
                    .putBoolean("askPasscodeBeforeDelete", enabled)
                    .putBoolean("askBiometricsBeforeDelete", enabled)
                    .apply();
        }
        return enabled;
    }

    public static boolean askPasscodeBeforeDelete = loadDeleteAuthenticationSetting();
    @Deprecated
    public static boolean askBiometricsBeforeDelete = askPasscodeBeforeDelete;

    public static void setAskPasscodeBeforeDelete(boolean enabled) {
        askPasscodeBeforeDelete = enabled;
        askBiometricsBeforeDelete = enabled;
        getEditor()
                .putBoolean("askPasscodeBeforeDelete", enabled)
                .putBoolean("askBiometricsBeforeDelete", enabled)
                .apply();
        onPrivacyProtectionChanged();
    }

    public static void toggleAskPasscodeBeforeDelete() {
        setAskPasscodeBeforeDelete(!askPasscodeBeforeDelete);
    }

    @Deprecated
    public static void toggleAskBiometricsBeforeDelete() {
        toggleAskPasscodeBeforeDelete();
    }

    public static boolean askBiometricsToOpenChat = getPreferences().getBoolean("askBiometricsToOpenChat", false);
    public static void setAskBiometricsToOpenChat(boolean enabled) {
        askBiometricsToOpenChat = enabled;
        getEditor().putBoolean("askBiometricsToOpenChat", enabled).apply();
        onPrivacyProtectionChanged();
    }
    public static void toggleAskBiometricsToOpenChat() {
        setAskBiometricsToOpenChat(!askBiometricsToOpenChat);
    }

    public static boolean askBiometricsToOpenSavedMessages = getPreferences().getBoolean("askBiometricsToOpenSavedMessages", false);
    public static void setAskBiometricsToOpenSavedMessages(boolean enabled) {
        askBiometricsToOpenSavedMessages = enabled;
        getEditor().putBoolean("askBiometricsToOpenSavedMessages", enabled).apply();
        onPrivacyProtectionChanged();
    }
    public static void toggleAskBiometricsToOpenSavedMessages() {
        setAskBiometricsToOpenSavedMessages(!askBiometricsToOpenSavedMessages);
    }

    public static boolean chatShortcutJumpToBegin = getPreferences().getBoolean("chatShortcutJumpToBegin", true);
    public static void toggleChatShortcutJumpToBegin() {
        chatShortcutJumpToBegin = !chatShortcutJumpToBegin;
        getEditor().putBoolean("chatShortcutJumpToBegin", chatShortcutJumpToBegin).apply();
    }
    public static boolean chatShortcutSavedMessages = getPreferences().getBoolean("chatShortcutSavedMessages", false);
    public static void toggleChatShortcutSavedMessages() {
        chatShortcutSavedMessages = !chatShortcutSavedMessages;
        getEditor().putBoolean("chatShortcutSavedMessages", chatShortcutSavedMessages).apply();
    }


    public static final int LOCKED_CHATS_TTL_ALWAYS = 0;
    public static final int LOCKED_CHATS_TTL_1_MIN = 60;
    public static final int LOCKED_CHATS_TTL_5_MIN = 300;
    public static final int LOCKED_CHATS_TTL_15_MIN = 900;
    public static final int LOCKED_CHATS_TTL_UNTIL_RESTART = -1;
    private static int normalizeLockedChatsTtl(int seconds) {
        return seconds == LOCKED_CHATS_TTL_ALWAYS
                || seconds == LOCKED_CHATS_TTL_1_MIN
                || seconds == LOCKED_CHATS_TTL_5_MIN
                || seconds == LOCKED_CHATS_TTL_15_MIN
                || seconds == LOCKED_CHATS_TTL_UNTIL_RESTART
                ? seconds : LOCKED_CHATS_TTL_5_MIN;
    }
    public static int lockedChatsBiometricTtlSec = normalizeLockedChatsTtl(
            getIntSafe("lockedChatsBiometricTtlSec", LOCKED_CHATS_TTL_5_MIN));
    public static void setLockedChatsBiometricTtl(int seconds) {
        lockedChatsBiometricTtlSec = normalizeLockedChatsTtl(seconds);
        getEditor().putInt("lockedChatsBiometricTtlSec", lockedChatsBiometricTtlSec).apply();
        onPrivacyProtectionChanged();
    }

    public static boolean glareOnElements = getPreferences().getBoolean("glareOnElements", true);
    public static void toggleGlareOnElements() {
        glareOnElements = !glareOnElements;
        getEditor().putBoolean("glareOnElements", glareOnElements).apply();
    }

    public static boolean mediaGlow = getPreferences().getBoolean("mediaGlow", false);
    public static void toggleMediaGlow() {
        mediaGlow = !mediaGlow;
        getEditor().putBoolean("mediaGlow", mediaGlow).apply();
        org.telegram.ui.PhotoViewer.onMediaGlowSettingChanged(mediaGlow);
    }

    public static boolean sortByUnread = getPreferences().getBoolean("sortByUnread", false);
    public static void toggleSortByUnread() {
        sortByUnread = !sortByUnread;
        getEditor().putBoolean("sortByUnread", sortByUnread).apply();
    }
    public static void setSortByUnread(boolean v) {
        sortByUnread = v;
        getEditor().putBoolean("sortByUnread", v).apply();
    }

    public static boolean unarchiveOnSwipe = getPreferences().getBoolean("unarchiveOnSwipe", false);
    public static void toggleUnarchiveOnSwipe() {
        unarchiveOnSwipe = !unarchiveOnSwipe;
        getEditor().putBoolean("unarchiveOnSwipe", unarchiveOnSwipe).apply();
    }

    public static boolean forwardWithoutAuthor = getPreferences().getBoolean("forwardWithoutAuthor", false);
    public static void toggleForwardWithoutAuthor() {
        forwardWithoutAuthor = !forwardWithoutAuthor;
        getEditor().putBoolean("forwardWithoutAuthor", forwardWithoutAuthor).apply();
    }

    public static boolean gifSpoilers = getPreferences().getBoolean("gifSpoilers", false);
    public static void toggleGifSpoilers() {
        gifSpoilers = !gifSpoilers;
        getEditor().putBoolean("gifSpoilers", gifSpoilers).apply();
    }

    public static boolean nimarkoMediaAuto = getPreferences().getBoolean("nimarkoMediaAuto", true);
    public static void toggleNimarkoMediaAuto() {
        nimarkoMediaAuto = !nimarkoMediaAuto;
        getEditor().putBoolean("nimarkoMediaAuto", nimarkoMediaAuto).apply();
    }
    public static int nimarkoMediaYtFmt = getPreferences().getInt("nimarkoMediaYtFmt", 0);
    public static void setNimarkoMediaYtFmt(int v) {
        nimarkoMediaYtFmt = v;
        getEditor().putInt("nimarkoMediaYtFmt", v).apply();
    }
    public static boolean nimarkoMediaYtAsk = getPreferences().getBoolean("nimarkoMediaYtAsk", true);
    public static void toggleNimarkoMediaYtAsk() {
        nimarkoMediaYtAsk = !nimarkoMediaYtAsk;
        getEditor().putBoolean("nimarkoMediaYtAsk", nimarkoMediaYtAsk).apply();
    }
    public static String getVoipRelayTokenForUid(long uid) {
        String key = "voipRelayAuthToken_" + uid;
        String token = getPreferences().getString(key, "");
        long selectedUid = org.telegram.messenger.UserConfig.getInstance(
                org.telegram.messenger.UserConfig.selectedAccount).getClientUserId();
        if (token.isEmpty() && uid == selectedUid && getPreferences().contains("voipRelayAuthToken")) {
            token = getPreferences().getString("voipRelayAuthToken", "");
            getEditor().putString(key, token).remove("voipRelayAuthToken").apply();
        }
        return token;
    }
    public static void setVoipRelayTokenForUid(long uid, String token) {
        getEditor().putString("voipRelayAuthToken_" + uid, token == null ? "" : token).apply();
    }

    public static boolean wsAutoSubscribed = getPreferences().getBoolean("wsAutoSubscribed", false);
    public static void setWsAutoSubscribed(boolean v) {
        wsAutoSubscribed = v;
        getEditor().putBoolean("wsAutoSubscribed", v).apply();
    }

    public static String getWsRelayTokenForUid(long uid) {
        String key = "wsRelayAuthToken_" + uid;
        String token = getPreferences().getString(key, "");
        long selectedUid = org.telegram.messenger.UserConfig.getInstance(
                org.telegram.messenger.UserConfig.selectedAccount).getClientUserId();
        if (token.isEmpty() && uid == selectedUid && getPreferences().contains("wsRelayAuthToken")) {
            token = getPreferences().getString("wsRelayAuthToken", "");
            getEditor().putString(key, token).remove("wsRelayAuthToken").apply();
        }
        return token;
    }
    public static void setWsRelayTokenForUid(long uid, String t) {
        getEditor().putString("wsRelayAuthToken_" + uid, t == null ? "" : t).apply();
    }

    public static String wsInstallId = getPreferences().getString("wsInstallId", "");
    public static synchronized String ensureWsInstallId() {
        if (wsInstallId == null || wsInstallId.isEmpty()) {
            wsInstallId = java.util.UUID.randomUUID().toString().replace("-", "");
            getEditor().putString("wsInstallId", wsInstallId).apply();
        }
        return wsInstallId;
    }

    public static boolean localPremiumEmojis = getPreferences().getBoolean("localPremiumEmojis", true);
    public static void toggleLocalPremiumEmojis() {
        localPremiumEmojis = !localPremiumEmojis;
        getEditor().putBoolean("localPremiumEmojis", localPremiumEmojis).apply();
    }

    public static boolean deletedGiftsInject = getPreferences().getBoolean("deletedGiftsInject", true);
    public static void toggleDeletedGiftsInject() {
        deletedGiftsInject = !deletedGiftsInject;
        getEditor().putBoolean("deletedGiftsInject", deletedGiftsInject).apply();
    }

    public static boolean showDetails = getPreferences().getBoolean("showDetails", false);
    public static void toggleShowDetails() {
        showDetails = !showDetails;
        getEditor().putBoolean("showDetails", showDetails).apply();
    }


    public static boolean centerTitle = getPreferences().getBoolean("centerTitle", true);
    public static void toggleCenterTitle() { centerTitle = !centerTitle; getEditor().putBoolean("centerTitle", centerTitle).apply(); }

    public static boolean hideSearchBar = getPreferences().getBoolean("hideSearchBar", true);
    public static void toggleHideSearchBar() { hideSearchBar = !hideSearchBar; getEditor().putBoolean("hideSearchBar", hideSearchBar).apply(); }

    public static boolean drawSnowInActionBar = getPreferences().getBoolean("drawSnowInActionBar", false);
    public static void toggleDrawSnowInActionBar() { drawSnowInActionBar = !drawSnowInActionBar; getEditor().putBoolean("drawSnowInActionBar", drawSnowInActionBar).apply(); }

    public static boolean iosStyleComposer = getPreferences().getBoolean("iosStyleComposer", true);
    public static void toggleIosStyleComposer() {
        iosStyleComposer = !iosStyleComposer;
        getEditor().putBoolean("iosStyleComposer", iosStyleComposer).apply();
    }

    public static final int SWITCH_STYLE_DEFAULT = 0;
    public static final int SWITCH_STYLE_ONEUI = 1;
    public static final int SWITCH_STYLE_MD3 = 2;
    public static int switchStyle = nmMigrateSwitchStyle();
    private static int nmMigrateSwitchStyle() {
        try {
            SharedPreferences prefs = getPreferences();
            if (!prefs.getBoolean("switchStyleMigrated", false) && prefs.contains("oneUI_SwitchStyle")) {
                int mapped = prefs.getBoolean("oneUI_SwitchStyle", true) ? SWITCH_STYLE_ONEUI : SWITCH_STYLE_DEFAULT;
                getEditor().putInt("switchStyle", mapped).putBoolean("switchStyleMigrated", true)
                        .remove("oneUI_SwitchStyle").apply();
                return mapped;
            } else if (prefs.contains("oneUI_SwitchStyle")) {
                getEditor().remove("oneUI_SwitchStyle").apply();
            }
        } catch (Throwable ignored) {}
        return getPreferences().getInt("switchStyle", SWITCH_STYLE_MD3);
    }
    public static void setSwitchStyle(int v) {
        if (v < 0 || v > 2) v = SWITCH_STYLE_DEFAULT;
        switchStyle = v;
        getEditor().putInt("switchStyle", v).apply();
    }

    public static boolean disableDividers = getPreferences().getBoolean("disableDividers", true);
    public static void toggleDisableDividers() { disableDividers = !disableDividers; getEditor().putBoolean("disableDividers", disableDividers).apply(); }

    public static final int ICON_REPLACE_NONE = 0;
    public static final int ICON_REPLACE_SOLAR = 1;
    public static final int ICON_REPLACE_MD3 = 2;
    public static final int ICON_REPLACE_LIQUID_GLASS = 3;
    public static final int ICON_REPLACE_PLUMPY = 4;
    public static volatile int iconReplacement = nmForcePlumpyOnce();
    public static void setIconReplacement(int v) { iconReplacement = v; getEditor().putInt("iconReplacement", v).apply(); }

    private static final int PLUMPY_DEFAULT_VERSION = 1;

    private static int nmForcePlumpyOnce() {
        try {
            if (getPreferences().getInt("plumpyDefaultVersion", 0) < PLUMPY_DEFAULT_VERSION) {
                getEditor().putInt("iconReplacement", ICON_REPLACE_PLUMPY)
                        .putInt("plumpyDefaultVersion", PLUMPY_DEFAULT_VERSION).apply();
                return ICON_REPLACE_PLUMPY;
            }
        } catch (Throwable ignored) {}
        return getIntSafe("iconReplacement", ICON_REPLACE_PLUMPY);
    }

    public static boolean tabsHideAllChats = getPreferences().getBoolean("tabsHideAllChats", false);
    public static void toggleTabsHideAllChats() { tabsHideAllChats = !tabsHideAllChats; getEditor().putBoolean("tabsHideAllChats", tabsHideAllChats).apply(); }

    public static boolean tabsNoUnread = getPreferences().getBoolean("tabsNoUnread", false);
    public static void toggleTabsNoUnread() { tabsNoUnread = !tabsNoUnread; getEditor().putBoolean("tabsNoUnread", tabsNoUnread).apply(); }

    public static final int TAB_TYPE_MIX = 0;
    public static final int TAB_TYPE_TEXT = 1;
    public static final int TAB_TYPE_ICON = 2;
    public static int tabMode = getIntSafe("tabMode", TAB_TYPE_MIX);
    public static void setTabMode(int v) { tabMode = v; getEditor().putInt("tabMode", v).apply(); }

    public static boolean tabStyleStroke = getPreferences().getBoolean("tabStyleStroke", false);
    public static void toggleTabStyleStroke() { tabStyleStroke = !tabStyleStroke; getEditor().putBoolean("tabStyleStroke", tabStyleStroke).apply(); }

    public static boolean folderNameInHeader = getPreferences().getBoolean("folderNameInHeader", false);
    public static void toggleFolderNameInHeader() { folderNameInHeader = !folderNameInHeader; getEditor().putBoolean("folderNameInHeader", folderNameInHeader).apply(); }

    public static boolean foldersAtBottom = getPreferences().getBoolean("foldersAtBottom", false);
    public static void toggleFoldersAtBottom() { foldersAtBottom = !foldersAtBottom; getEditor().putBoolean("foldersAtBottom", foldersAtBottom).apply(); }


    public static final int FOLDER_BADGE_NUMBER = 0;
    public static final int FOLDER_BADGE_DOT    = 1;
    public static final int FOLDER_BADGE_HIDDEN = 2;

    public static boolean folderSwipeEnabled = getPreferences().getBoolean("folderSwipeEnabled", false);
    public static void toggleFolderSwipeEnabled() {
        folderSwipeEnabled = !folderSwipeEnabled;
        getEditor().putBoolean("folderSwipeEnabled", folderSwipeEnabled).apply();
    }
    public static void setFolderSwipeEnabled(boolean v) {
        folderSwipeEnabled = v;
        getEditor().putBoolean("folderSwipeEnabled", v).apply();
    }

    public static volatile Map<Integer, Integer> folderColors = loadFolderColors();
    private static Map<Integer, Integer> loadFolderColors() {
        String raw = getPreferences().getString("folderColors", null);
        if (raw == null || raw.isEmpty()) return Collections.synchronizedMap(new HashMap<>());
        try {
            Map<Integer, Integer> m = GSON.fromJson(raw,
                    new TypeToken<Map<Integer, Integer>>() {}.getType());
            return Collections.synchronizedMap(m != null ? new HashMap<>(m) : new HashMap<>());
        } catch (Throwable ignored) {
            return Collections.synchronizedMap(new HashMap<>());
        }
    }
    public static void saveFolderColors() {
        Map<Integer, Integer> colors = folderColors;
        Map<Integer, Integer> snapshot;
        if (colors == null) {
            snapshot = Collections.emptyMap();
        } else {
            synchronized (colors) {
                snapshot = new HashMap<>(colors);
            }
        }
        getEditor().putString("folderColors", GSON.toJson(snapshot)).apply();
    }

    public static volatile Map<Integer, Integer> folderBadgeMode = loadFolderBadgeMode();
    private static Map<Integer, Integer> loadFolderBadgeMode() {
        String raw = getPreferences().getString("folderBadgeMode", null);
        if (raw == null || raw.isEmpty()) return Collections.synchronizedMap(new HashMap<>());
        try {
            Map<Integer, Integer> m = GSON.fromJson(raw,
                    new TypeToken<Map<Integer, Integer>>() {}.getType());
            return Collections.synchronizedMap(m != null ? new HashMap<>(m) : new HashMap<>());
        } catch (Throwable ignored) {
            return Collections.synchronizedMap(new HashMap<>());
        }
    }
    public static void saveFolderBadgeMode() {
        Map<Integer, Integer> modes = folderBadgeMode;
        Map<Integer, Integer> snapshot;
        if (modes == null) {
            snapshot = Collections.emptyMap();
        } else {
            synchronized (modes) {
                snapshot = new HashMap<>(modes);
            }
        }
        getEditor().putString("folderBadgeMode", GSON.toJson(snapshot)).apply();
    }

    public static String folderGroupsJson = getPreferences().getString("folderGroupsJson", "");
    public static void setFolderGroupsJson(String v) {
        folderGroupsJson = v == null ? "" : v;
        getEditor().putString("folderGroupsJson", folderGroupsJson).apply();
    }

    public static boolean showMainTabs = getPreferences().getBoolean("showMainTabs", true);
    public static void toggleShowMainTabs() { showMainTabs = !showMainTabs; getEditor().putBoolean("showMainTabs", showMainTabs).apply(); }

    public static boolean showMainTabsTitle = getPreferences().getBoolean("showMainTabsTitle", true);
    public static void toggleShowMainTabsTitle() { showMainTabsTitle = !showMainTabsTitle; getEditor().putBoolean("showMainTabsTitle", showMainTabsTitle).apply(); }

    public static boolean openSettingsBySwipe = getPreferences().getBoolean("openSettingsBySwipe", false);
    public static void toggleOpenSettingsBySwipe() { openSettingsBySwipe = !openSettingsBySwipe; getEditor().putBoolean("openSettingsBySwipe", openSettingsBySwipe).apply(); }

    public static boolean mainTabsForceOpenChats = getPreferences().getBoolean("mainTabsForceOpenChats", false);
    public static void toggleMainTabsForceOpenChats() { mainTabsForceOpenChats = !mainTabsForceOpenChats; getEditor().putBoolean("mainTabsForceOpenChats", mainTabsForceOpenChats).apply(); }

    public static volatile String mainTabsOrder = getPreferences().getString("mainTabsOrder", null);
    public static synchronized void setMainTabsOrder(String v) {
        mainTabsOrder = v;
        if (v == null) {
            getEditor().remove("mainTabsOrder").apply();
        } else {
            getEditor().putString("mainTabsOrder", v).apply();
        }
    }

    public static String mainTabsCustomTitles = getPreferences().getString("mainTabsCustomTitles", null);
    public static void setMainTabsCustomTitles(String v) { mainTabsCustomTitles = v; getEditor().putString("mainTabsCustomTitles", v).apply(); }

    public static boolean showSeconds = getPreferences().getBoolean("showSeconds", false);
    public static void toggleShowSeconds() { showSeconds = !showSeconds; getEditor().putBoolean("showSeconds", showSeconds).apply(); }

    public static boolean disablePremiumStatuses = getPreferences().getBoolean("disablePremiumStatuses", false);
    public static void toggleDisablePremiumStatuses() { disablePremiumStatuses = !disablePremiumStatuses; getEditor().putBoolean("disablePremiumStatuses", disablePremiumStatuses).apply(); }

    public static boolean replyBackground = getPreferences().getBoolean("replyBackground",
            org.telegram.messenger.SharedConfig.getDevicePerformanceClass() >= org.telegram.messenger.SharedConfig.PERFORMANCE_CLASS_AVERAGE);
    public static void toggleReplyBackground() { replyBackground = !replyBackground; getEditor().putBoolean("replyBackground", replyBackground).apply(); }

    public static boolean replyCustomColors = getPreferences().getBoolean("replyCustomColors",
            org.telegram.messenger.SharedConfig.getDevicePerformanceClass() >= org.telegram.messenger.SharedConfig.PERFORMANCE_CLASS_AVERAGE);
    public static void toggleReplyCustomColors() { replyCustomColors = !replyCustomColors; getEditor().putBoolean("replyCustomColors", replyCustomColors).apply(); }

    public static boolean replyBackgroundEmoji = getPreferences().getBoolean("replyBackgroundEmoji",
            org.telegram.messenger.SharedConfig.getDevicePerformanceClass() >= org.telegram.messenger.SharedConfig.PERFORMANCE_CLASS_AVERAGE);
    public static void toggleReplyBackgroundEmoji() { replyBackgroundEmoji = !replyBackgroundEmoji; getEditor().putBoolean("replyBackgroundEmoji", replyBackgroundEmoji).apply(); }

    public static boolean profileChannelPreview = getPreferences().getBoolean("profileChannelPreview", true);
    public static void toggleProfileChannelPreview() { profileChannelPreview = !profileChannelPreview; getEditor().putBoolean("profileChannelPreview", profileChannelPreview).apply(); }

    public static boolean showIDDC = getPreferences().getBoolean("showIDDC", false);
    public static void toggleShowIDDC() { showIDDC = !showIDDC; getEditor().putBoolean("showIDDC", showIDDC).apply(); }

    public static boolean profileBirthDatePreview = getPreferences().getBoolean("profileBirthDatePreview", true);
    public static void toggleProfileBirthDatePreview() { profileBirthDatePreview = !profileBirthDatePreview; getEditor().putBoolean("profileBirthDatePreview", profileBirthDatePreview).apply(); }

    public static boolean profileBusinessPreview = getPreferences().getBoolean("profileBusinessPreview", true);
    public static void toggleProfileBusinessPreview() { profileBusinessPreview = !profileBusinessPreview; getEditor().putBoolean("profileBusinessPreview", profileBusinessPreview).apply(); }

    public static boolean profileBackgroundColor = getPreferences().getBoolean("profileBackgroundColor", true);
    public static void toggleProfileBackgroundColor() { profileBackgroundColor = !profileBackgroundColor; getEditor().putBoolean("profileBackgroundColor", profileBackgroundColor).apply(); }

    public static boolean profileBackgroundEmoji = getPreferences().getBoolean("profileBackgroundEmoji", true);
    public static void toggleProfileBackgroundEmoji() { profileBackgroundEmoji = !profileBackgroundEmoji; getEditor().putBoolean("profileBackgroundEmoji", profileBackgroundEmoji).apply(); }

    public static boolean centerChatTitle = getPreferences().getBoolean("centerChatTitle", true);
    public static void toggleCenterChatTitle() { centerChatTitle = !centerChatTitle; getEditor().putBoolean("centerChatTitle", centerChatTitle).apply(); }

    public static volatile boolean latexRenderingEnabled = getPreferences().getBoolean("latexRenderingEnabled", true);
    public static void toggleLatexRendering() { latexRenderingEnabled = !latexRenderingEnabled; getEditor().putBoolean("latexRenderingEnabled", latexRenderingEnabled).apply(); }

    public static boolean unreadBadgeOnBackButton = getPreferences().getBoolean("unreadBadgeOnBackButton", false);
    public static void toggleUnreadBadgeOnBackButton() { unreadBadgeOnBackButton = !unreadBadgeOnBackButton; getEditor().putBoolean("unreadBadgeOnBackButton", unreadBadgeOnBackButton).apply(); }

    public static boolean drawSnowInChat = getPreferences().getBoolean("drawSnowInChat", false);
    public static void toggleDrawSnowInChat() { drawSnowInChat = !drawSnowInChat; getEditor().putBoolean("drawSnowInChat", drawSnowInChat).apply(); }

    public static boolean disableSwipeToNext = getPreferences().getBoolean("disableSwipeToNext", false);
    public static void toggleDisableSwipeToNext() { disableSwipeToNext = !disableSwipeToNext; getEditor().putBoolean("disableSwipeToNext", disableSwipeToNext).apply(); }

    public static boolean disableVibration = getPreferences().getBoolean("disableVibration", false);
    public static void toggleDisableVibration() { disableVibration = !disableVibration; getEditor().putBoolean("disableVibration", disableVibration).apply(); }

    public static final int VIBRATE_DISABLE = 0;
    public static final int VIBRATE_CLICK = 1;
    public static final int VIBRATE_WAVE = 2;
    public static final int VIBRATE_KEYBOARD = 3;
    public static final int VIBRATE_LONG = 4;
    public static final int VIBRATION_DISABLE = VIBRATE_DISABLE;
    public static final int VIBRATION_CLICK = VIBRATE_CLICK;
    public static final int VIBRATION_WAVE_FORM = VIBRATE_WAVE;
    public static final int VIBRATION_KEYBOARD_TAP = VIBRATE_KEYBOARD;
    public static final int VIBRATION_LONG = VIBRATE_LONG;
    public static int vibrateInChats = getIntSafe("vibrateInChats", VIBRATE_DISABLE);
    public static void setVibrateInChats(int v) { vibrateInChats = v; getEditor().putInt("vibrateInChats", v).apply(); }

    public static boolean hideMuteUnmuteButton = getPreferences().getBoolean("hideMuteUnmuteButton", false);
    public static void toggleHideMuteUnmuteButton() { hideMuteUnmuteButton = !hideMuteUnmuteButton; getEditor().putBoolean("hideMuteUnmuteButton", hideMuteUnmuteButton).apply(); }
    public static void setHideMuteUnmuteButton(boolean v) { hideMuteUnmuteButton = v; getEditor().putBoolean("hideMuteUnmuteButton", v).apply(); }

    public static boolean hideSendAsChannel = getPreferences().getBoolean("hideSendAsChannel", false);
    public static void toggleHideSendAsChannel() { hideSendAsChannel = !hideSendAsChannel; getEditor().putBoolean("hideSendAsChannel", hideSendAsChannel).apply(); }

    public static boolean largePhotos = getPreferences().getBoolean("largePhotos",
            org.telegram.messenger.SharedConfig.getDevicePerformanceClass() >= org.telegram.messenger.SharedConfig.PERFORMANCE_CLASS_AVERAGE);
    public static void toggleLargePhotos() { largePhotos = !largePhotos; getEditor().putBoolean("largePhotos", largePhotos).apply(); }
    public static void setLargePhotos(boolean value) { largePhotos = value; getEditor().putBoolean("largePhotos", value).apply(); }

    public static boolean customChatForSavedMessages = getPreferences().getBoolean("customChatForSavedMessages", false);
    public static void toggleCustomChatForSavedMessages() { customChatForSavedMessages = !customChatForSavedMessages; getEditor().putBoolean("customChatForSavedMessages", customChatForSavedMessages).apply(); }
    public static long customSavedMessagesDialogId = getPreferences().getLong("customSavedMessagesDialogId", 0L);
    public static void setCustomSavedMessagesDialogId(long v) {
        setCustomSavedMessagesDialogId(org.telegram.messenger.UserConfig.selectedAccount, v);
    }
    public static void setCustomSavedMessagesDialogId(int account, long v) {
        customSavedMessagesDialogId = v;
        getEditor().putLong("customSavedMessagesDialogId_a" + account, v).apply();
    }
    public static long getCustomSavedMessagesDialogId(int account) {
        String key = "customSavedMessagesDialogId_a" + account;
        if (getPreferences().contains(key)) {
            return getPreferences().getLong(key, 0L);
        }
        if (account == org.telegram.messenger.UserConfig.selectedAccount
                && getPreferences().contains("customSavedMessagesDialogId")) {
            long legacy = getPreferences().getLong("customSavedMessagesDialogId", 0L);
            getEditor().putLong(key, legacy).remove("customSavedMessagesDialogId").apply();
            return legacy;
        }
        return 0L;
    }
    public static long getEffectiveSavedMessagesDialogId(long selfId) {
        return getEffectiveSavedMessagesDialogId(org.telegram.messenger.UserConfig.selectedAccount, selfId);
    }
    public static long getEffectiveSavedMessagesDialogId(int account, long selfId) {
        long customId = getCustomSavedMessagesDialogId(account);
        if (customChatForSavedMessages && customId != 0L) {
            return customId;
        }
        return selfId;
    }

    public static boolean customWallpapers = getPreferences().getBoolean("customWallpapers", true);
    public static void toggleCustomWallpapers() { customWallpapers = !customWallpapers; getEditor().putBoolean("customWallpapers", customWallpapers).apply(); }

    public static boolean autoPauseVideo = getPreferences().getBoolean("autoPauseVideo", false);
    public static void toggleAutoPauseVideo() { autoPauseVideo = !autoPauseVideo; getEditor().putBoolean("autoPauseVideo", autoPauseVideo).apply(); }

    public static boolean autoQuoteReplies = getPreferences().getBoolean("autoQuoteReplies", false);
    public static void toggleAutoQuoteReplies() { autoQuoteReplies = !autoQuoteReplies; getEditor().putBoolean("autoQuoteReplies", autoQuoteReplies).apply(); }

    public static boolean playVideoOnVolume = getPreferences().getBoolean("playVideoOnVolume", false);
    public static void togglePlayVideoOnVolume() { playVideoOnVolume = !playVideoOnVolume; getEditor().putBoolean("playVideoOnVolume", playVideoOnVolume).apply(); }

    public static int videoSeekDuration = getPreferences().getInt("videoSeekDuration", 10);
    public static void setVideoSeekDuration(int v) { videoSeekDuration = v; getEditor().putInt("videoSeekDuration", v).apply(); }

    public static int hideKeyboardOnScrollIntensity = getPreferences().getInt("hideKeyboardOnScrollIntensity", 5);
    public static void setHideKeyboardOnScrollIntensity(int v) { hideKeyboardOnScrollIntensity = v; getEditor().putInt("hideKeyboardOnScrollIntensity", v).apply(); }

    public static int recentEmojisAmplifier = getPreferences().getInt("recentEmojisAmplifier", 45);
    public static void setRecentEmojisAmplifier(int v) { recentEmojisAmplifier = v; getEditor().putInt("recentEmojisAmplifier", v).apply(); }

    public static int recentStickersAmplifier = getPreferences().getInt("recentStickersAmplifier", 20);
    public static void setRecentStickersAmplifier(int v) { recentStickersAmplifier = v; getEditor().putInt("recentStickersAmplifier", v).apply(); }

    public static final int NOTIF_SOUND_DISABLE = 0;
    public static final int NOTIF_SOUND_DEFAULT = 1;
    public static final int NOTIF_SOUND_IOS = 2;
    public static int notificationSound = getPreferences().getInt("notificationSound", NOTIF_SOUND_DEFAULT);
    public static void setNotificationSound(int v) { notificationSound = v; getEditor().putInt("notificationSound", v).apply(); }

    public static boolean shortcutBrowser = getPreferences().getBoolean("shortcutBrowser", false);
    public static void toggleShortcutBrowser() { shortcutBrowser = !shortcutBrowser; getEditor().putBoolean("shortcutBrowser", shortcutBrowser).apply(); }

    public static boolean shortcutDeleteAll = getPreferences().getBoolean("shortcutDeleteAll", true);
    public static void toggleShortcutDeleteAll() { shortcutDeleteAll = !shortcutDeleteAll; getEditor().putBoolean("shortcutDeleteAll", shortcutDeleteAll).apply(); }

    public static boolean adminsAdministrators = getPreferences().getBoolean("adminsAdministrators", true);
    public static void toggleAdminsAdministrators() { adminsAdministrators = !adminsAdministrators; getEditor().putBoolean("adminsAdministrators", adminsAdministrators).apply(); }

    public static boolean adminsMembers = getPreferences().getBoolean("adminsMembers", true);
    public static void toggleAdminsMembers() { adminsMembers = !adminsMembers; getEditor().putBoolean("adminsMembers", adminsMembers).apply(); }

    public static boolean adminsPermissions = getPreferences().getBoolean("adminsPermissions", true);
    public static void toggleAdminsPermissions() { adminsPermissions = !adminsPermissions; getEditor().putBoolean("adminsPermissions", adminsPermissions).apply(); }

    public static boolean adminsReactions = getPreferences().getBoolean("adminsReactions", true);
    public static void toggleAdminsReactions() { adminsReactions = !adminsReactions; getEditor().putBoolean("adminsReactions", adminsReactions).apply(); }

    public static boolean adminsRecentActions = getPreferences().getBoolean("adminsRecentActions", true);
    public static void toggleAdminsRecentActions() { adminsRecentActions = !adminsRecentActions; getEditor().putBoolean("adminsRecentActions", adminsRecentActions).apply(); }

    public static boolean adminsStatistics = getPreferences().getBoolean("adminsStatistics", true);
    public static void toggleAdminsStatistics() { adminsStatistics = !adminsStatistics; getEditor().putBoolean("adminsStatistics", adminsStatistics).apply(); }

    static {
        if (!getPreferences().getBoolean("adminsDefaultsResetV56", false)) {
            SharedPreferences.Editor ed = getEditor();
            ed.putBoolean("adminsAdministrators", true)
              .putBoolean("adminsMembers", true)
              .putBoolean("adminsPermissions", true)
              .putBoolean("adminsReactions", true)
              .putBoolean("adminsRecentActions", true)
              .putBoolean("adminsStatistics", true);
            adminsAdministrators = true;
            adminsMembers = true;
            adminsPermissions = true;
            adminsReactions = true;
            adminsRecentActions = true;
            adminsStatistics = true;
            ed.putBoolean("adminsDefaultsResetV56", true).apply();
        }
    }

    public static final int TELEGRAM_CAMERA = 0;
    public static final int CAMERA_X = 1;
    public static final int CAMERA_2 = 2;
    public static final int CAMERA_SYSTEM = 3;
    public static final int SYSTEM_CAMERA = CAMERA_SYSTEM;
    public static final int CAMERA_1 = TELEGRAM_CAMERA;
    public static int cameraType = initCameraType();
    public static void setCameraType(int v) { cameraType = v; getEditor().putInt("cameraType", v).apply(); }

    private static int initCameraType() {
        SharedPreferences prefs = getPreferences();
        boolean defaultCameraX = app.nimarkogram.messenger.camera.CameraXUtils.isCameraXSupported();
        int stored = prefs.getInt("cameraType", defaultCameraX ? CAMERA_X : TELEGRAM_CAMERA);
        boolean migrated = prefs.getBoolean("cameraTypeMigratedToV20", false);
        if (!migrated) {
            SharedPreferences.Editor ed = prefs.edit();
            if (stored == TELEGRAM_CAMERA && defaultCameraX) {
                stored = CAMERA_X;
                ed.putInt("cameraType", stored);
            }
            ed.putBoolean("cameraTypeMigratedToV20", true).apply();
        }
        return stored;
    }

    public static final int CAMERA_RESOLUTION_720P = 720;
    public static final int CAMERA_RESOLUTION_1080P = 1080;
    public static final int CAMERA_RESOLUTION_2K = 1440;

    private static int normalizeCameraResolution(int value) {
        if (value == CAMERA_RESOLUTION_720P
                || value == CAMERA_RESOLUTION_1080P
                || value == CAMERA_RESOLUTION_2K) {
            return value;
        }
        return CAMERA_RESOLUTION_2K;
    }

    private static int initCameraResolution() {
        SharedPreferences preferences = getPreferences();
        int stored = preferences.getInt("cameraResolution", CAMERA_RESOLUTION_2K);
        int normalized = normalizeCameraResolution(stored);
        if (stored != normalized) {
            getEditor().putInt("cameraResolution", normalized).apply();
        }
        return normalized;
    }

    public static int cameraResolution = initCameraResolution();
    public static void setCameraResolution(int v) {
        cameraResolution = normalizeCameraResolution(v);
        getEditor().putInt("cameraResolution", cameraResolution).apply();
    }

    public static final int CameraXFpsRangeDefault = 0;
    public static final int CameraXFpsRange25to30 = 1;
    public static final int CameraXFpsRange30to30 = 2;
    public static final int CameraXFpsRange30to60 = 3;
    public static final int CameraXFpsRange60to60 = 4;

    private static int normalizeCameraXFpsRange(int value) {
        if (value == CameraXFpsRange60to60) {
            return CameraXFpsRange30to60;
        }
        return value >= CameraXFpsRangeDefault
                && value <= CameraXFpsRange30to60
                ? value : CameraXFpsRangeDefault;
    }

    private static int initCameraXFpsRange() {
        SharedPreferences preferences = getPreferences();
        int fallback = org.telegram.messenger.SharedConfig
                .getDevicePerformanceClass()
                >= org.telegram.messenger.SharedConfig
                        .PERFORMANCE_CLASS_AVERAGE
                ? CameraXFpsRange25to30
                : CameraXFpsRangeDefault;
        int stored = preferences.getInt("cameraXFpsRange", fallback);
        int normalized = normalizeCameraXFpsRange(stored);
        if (stored != normalized) {
            getEditor()
                    .putInt("cameraXFpsRange", normalized)
                    .apply();
        }
        return normalized;
    }

    public static int cameraXFpsRange = initCameraXFpsRange();
    public static void setCameraXFpsRange(int value) {
        cameraXFpsRange = normalizeCameraXFpsRange(value);
        getEditor().putInt("cameraXFpsRange", cameraXFpsRange).apply();
    }

    public static boolean cameraStabilisation = getPreferences().getBoolean("cameraStabilisation", false);
    public static void toggleCameraStabilisation() { cameraStabilisation = !cameraStabilisation; getEditor().putBoolean("cameraStabilisation", cameraStabilisation).apply(); }

    public static boolean cameraSlowMo = getPreferences().getBoolean("cameraSlowMo", false);
    public static void toggleCameraSlowMo() { cameraSlowMo = !cameraSlowMo; getEditor().putBoolean("cameraSlowMo", cameraSlowMo).apply(); }

    public static int cameraExposureIndex = getIntSafe("cameraExposureIndex", 0);
    public static void setCameraExposureIndex(int v) {
        cameraExposureIndex = Math.max(-100, Math.min(100, v));
        getEditor().putInt("cameraExposureIndex", cameraExposureIndex).apply();
    }

    public static boolean cameraOpticalStabilization = getPreferences().getBoolean("cameraOpticalStabilization", true);
    public static void toggleCameraOpticalStabilization() { cameraOpticalStabilization = !cameraOpticalStabilization; getEditor().putBoolean("cameraOpticalStabilization", cameraOpticalStabilization).apply(); }

    public static boolean cameraContinuousFocus = getPreferences().getBoolean("cameraContinuousFocus", true);
    public static void toggleCameraContinuousFocus() { cameraContinuousFocus = !cameraContinuousFocus; getEditor().putBoolean("cameraContinuousFocus", cameraContinuousFocus).apply(); }

    public static boolean cameraNoiseReduction = getPreferences().getBoolean("cameraNoiseReduction", true);
    public static void toggleCameraNoiseReduction() { cameraNoiseReduction = !cameraNoiseReduction; getEditor().putBoolean("cameraNoiseReduction", cameraNoiseReduction).apply(); }

    public static boolean cameraFaceDetection = getPreferences().getBoolean("cameraFaceDetection", false);
    public static void toggleCameraFaceDetection() { cameraFaceDetection = !cameraFaceDetection; getEditor().putBoolean("cameraFaceDetection", cameraFaceDetection).apply(); }

    public static boolean cameraXUseHighRange = getPreferences().getBoolean("cameraXUseHighRange", true);
    public static void toggleCameraXUseHighRange() { cameraXUseHighRange = !cameraXUseHighRange; getEditor().putBoolean("cameraXUseHighRange", cameraXUseHighRange).apply(); }

    public static boolean rearCam = getPreferences().getBoolean("rearCam", false);
    public static void toggleRearCam() { rearCam = !rearCam; getEditor().putBoolean("rearCam", rearCam).apply(); }
    public static int videoMessagesCamera = getIntSafe("videoMessagesCamera", rearCam ? 1 : 0);
    public static void setVideoMessagesCamera(int v) { videoMessagesCamera = v; getEditor().putInt("videoMessagesCamera", v).apply(); }
    public static boolean pendingRoundFront = true;

    public static boolean startFromUltraWideCam = getPreferences().getBoolean("startFromUltraWideCam", true);
    public static void toggleStartFromUltraWideCam() { startFromUltraWideCam = !startFromUltraWideCam; getEditor().putBoolean("startFromUltraWideCam", startFromUltraWideCam).apply(); }

    public static boolean useDualCamera = getPreferences().getBoolean("useDualCamera", false);
    public static void toggleUseDualCamera() { useDualCamera = !useDualCamera; getEditor().putBoolean("useDualCamera", useDualCamera).apply(); }

    public static boolean disableAttachCamera = getPreferences().getBoolean("disableAttachCamera", true);
    public static void toggleDisableAttachCamera() { disableAttachCamera = !disableAttachCamera; getEditor().putBoolean("disableAttachCamera", disableAttachCamera).apply(); }

    public static boolean centerCameraControlButtons = getPreferences().getBoolean("centerCameraControlButtons", true);
    public static void toggleCenterCameraControlButtons() { centerCameraControlButtons = !centerCameraControlButtons; getEditor().putBoolean("centerCameraControlButtons", centerCameraControlButtons).apply(); }

    public static boolean hideStories = getPreferences().getBoolean("hideStories", false);
    public static void toggleHideStories() { hideStories = !hideStories; getEditor().putBoolean("hideStories", hideStories).apply(); }

    public static boolean archiveStoriesFromUsers = getPreferences().getBoolean("archiveStoriesFromUsers", false);
    public static void toggleArchiveStoriesFromUsers() { archiveStoriesFromUsers = !archiveStoriesFromUsers; getEditor().putBoolean("archiveStoriesFromUsers", archiveStoriesFromUsers).apply(); }

    public static boolean archiveStoriesFromChannels = getPreferences().getBoolean("archiveStoriesFromChannels", false);
    public static void toggleArchiveStoriesFromChannels() { archiveStoriesFromChannels = !archiveStoriesFromChannels; getEditor().putBoolean("archiveStoriesFromChannels", archiveStoriesFromChannels).apply(); }

    public static boolean systemEmoji = getPreferences().getBoolean("systemEmoji", false);
    public static void toggleSystemEmoji() { systemEmoji = !systemEmoji; getEditor().putBoolean("systemEmoji", systemEmoji).apply(); }

    public static boolean systemFonts = getPreferences().getBoolean("systemFonts", true);
    public static void toggleSystemFonts() { systemFonts = !systemFonts; getEditor().putBoolean("systemFonts", systemFonts).apply(); }

    public static final int SPRING_SPRING = 0;
    public static final int SPRING_CLASSIC = 1;
    public static final int ANIMATION_SPRING = SPRING_SPRING;
    public static final int ANIMATION_CLASSIC = SPRING_CLASSIC;
    public static int springAnimation = getIntSafe("springAnimation", SPRING_SPRING);
    public static void setSpringAnimation(int v) { springAnimation = v; getEditor().putInt("springAnimation", v).apply(); }
    public static boolean isSpringAnimationEnabled() { return springAnimation == SPRING_SPRING; }

    public static boolean predictiveBack = getPreferences().getBoolean("predictiveBack", true);
    public static void togglePredictiveBack() { predictiveBack = !predictiveBack; getEditor().putBoolean("predictiveBack", predictiveBack).apply(); }

    public static final int TABLET_AUTO = 0;
    public static final int TABLET_ENABLE = 1;
    public static final int TABLET_DISABLE = 2;
    public static final int TABLET_MODE_ENABLE = TABLET_ENABLE;
    public static final int TABLET_MODE_DISABLE = TABLET_DISABLE;
    public static final int TABLET_MODE_AUTO = TABLET_AUTO;
    public static int tabletMode = getIntSafe("tabletMode", TABLET_AUTO);
    public static void setTabletMode(int v) { tabletMode = v; getEditor().putInt("tabletMode", v).apply(); }

    public static boolean residentNotification = getPreferences().getBoolean("residentNotification", false);
    public static void toggleResidentNotification() { residentNotification = !residentNotification; getEditor().putBoolean("residentNotification", residentNotification).apply(); }


    public static boolean slowNetworkMode = getPreferences().getBoolean("slowNetworkMode", false);
    public static void toggleSlowNetworkMode() { slowNetworkMode = !slowNetworkMode; getEditor().putBoolean("slowNetworkMode", slowNetworkMode).apply(); }

    public static final int DL_BOOST_NONE = DownloadSpeedPolicy.BOOST_NONE;
    public static final int DL_BOOST_AVERAGE = DownloadSpeedPolicy.BOOST_AVERAGE;
    public static final int DL_BOOST_EXTREME = DownloadSpeedPolicy.BOOST_EXTREME;
    public static final int BOOST_NONE = DL_BOOST_NONE;
    public static final int BOOST_AVERAGE = DL_BOOST_AVERAGE;
    public static final int BOOST_EXTREME = DL_BOOST_EXTREME;
    public static int downloadSpeedBoost =
            DownloadSpeedPolicy.normalizeBoost(getIntSafe("downloadSpeedBoost", DL_BOOST_NONE));
    public static void setDownloadSpeedBoost(int v) {
        downloadSpeedBoost = DownloadSpeedPolicy.normalizeBoost(v);
        getEditor().putInt("downloadSpeedBoost", downloadSpeedBoost).apply();
    }

    public static boolean uploadSpeedBoost = getPreferences().getBoolean("uploadSpeedBoost", false);
    public static void toggleUploadSpeedBoost() { uploadSpeedBoost = !uploadSpeedBoost; getEditor().putBoolean("uploadSpeedBoost", uploadSpeedBoost).apply(); }

    public static boolean allowSystemPasscode = getPreferences().getBoolean("allowSystemPasscode", false);
    public static void setAllowSystemPasscode(boolean enabled) {
        allowSystemPasscode = enabled;
        getEditor().putBoolean("allowSystemPasscode", enabled).apply();
        onPrivacyProtectionChanged();
    }
    public static void toggleAllowSystemPasscode() { setAllowSystemPasscode(!allowSystemPasscode); }

    public static boolean askBiometricsToOpenArchive = getPreferences().getBoolean("askBiometricsToOpenArchive", false);
    public static void setAskBiometricsToOpenArchive(boolean enabled) {
        askBiometricsToOpenArchive = enabled;
        getEditor().putBoolean("askBiometricsToOpenArchive", enabled).apply();
        onPrivacyProtectionChanged();
    }
    public static void toggleAskBiometricsToOpenArchive() { setAskBiometricsToOpenArchive(!askBiometricsToOpenArchive); }

    public static boolean askBiometricsToOpenEncrypted = getPreferences().getBoolean("askBiometricsToOpenEncrypted", false);
    public static void setAskBiometricsToOpenEncrypted(boolean enabled) {
        askBiometricsToOpenEncrypted = enabled;
        getEditor().putBoolean("askBiometricsToOpenEncrypted", enabled).apply();
        onPrivacyProtectionChanged();
    }
    public static void toggleAskBiometricsToOpenEncrypted() { setAskBiometricsToOpenEncrypted(!askBiometricsToOpenEncrypted); }

    public static boolean hideArchiveFromChatsList = getPreferences().getBoolean("hideArchiveFromChatsList", false);
    public static void toggleHideArchiveFromChatsList() { hideArchiveFromChatsList = !hideArchiveFromChatsList; getEditor().putBoolean("hideArchiveFromChatsList", hideArchiveFromChatsList).apply(); }

    public static boolean hideArchivedStories = getPreferences().getBoolean("hideArchivedStories", false);
    public static void toggleHideArchivedStories() { hideArchivedStories = !hideArchivedStories; getEditor().putBoolean("hideArchivedStories", hideArchivedStories).apply(); }

    private static void onPrivacyProtectionChanged() {
        try {
            app.nimarkogram.messenger.security.NimarkoBiometricPrompt.clearVerified();
            org.telegram.messenger.AndroidUtilities.runOnUIThread(
                    org.telegram.ui.LaunchActivity::invalidateNimarkoSecureFlag);
        } catch (Throwable ignored) {
        }
    }

    public static boolean deleteForAll = getPreferences().getBoolean("deleteForAll", false);
    public static void toggleDeleteForAll() { deleteForAll = !deleteForAll; getEditor().putBoolean("deleteForAll", deleteForAll).apply(); }

    public static final int DTAP_NONE = 0;
    public static final int DTAP_REACTION = 1;
    public static final int DTAP_REPLY = 2;
    public static final int DTAP_SAVE = 3;
    public static final int DTAP_EDIT = 4;
    public static final int DTAP_TRANSLATE = 5;
    public static final int DTAP_EDIT_OR_REACTION = 6;
    public static final int DOUBLE_TAP_ACTION_NONE = DTAP_NONE;
    public static final int DOUBLE_TAP_ACTION_REACTION = DTAP_REACTION;
    public static final int DOUBLE_TAP_ACTION_REPLY = DTAP_REPLY;
    public static final int DOUBLE_TAP_ACTION_SAVE = DTAP_SAVE;
    public static final int DOUBLE_TAP_ACTION_EDIT = DTAP_EDIT;
    public static final int DOUBLE_TAP_ACTION_TRANSLATE = DTAP_TRANSLATE;
    public static final int DOUBLE_TAP_ACTION_EDIT_OR_REACTION = DTAP_EDIT_OR_REACTION;
    public static int doubleTapAction = getPreferences().getInt("doubleTapAction", DTAP_REACTION);
    public static int doubletapaction = doubleTapAction;
    public static void setDoubleTapAction(int v) {
        doubleTapAction = v;
        doubletapaction = v;
        getEditor().putInt("doubleTapAction", v).apply();
    }

    public static final int SLIDE_REPLY = 0;
    public static final int SLIDE_SAVE = 1;
    public static final int SLIDE_TRANSLATE = 2;
    public static final int SLIDE_DIRECT_SHARE = 3;
    public static final int MESSAGE_SLIDE_ACTION_REPLY = SLIDE_REPLY;
    public static final int MESSAGE_SLIDE_ACTION_SAVE = SLIDE_SAVE;
    public static final int MESSAGE_SLIDE_ACTION_TRANSLATE = SLIDE_TRANSLATE;
    public static final int MESSAGE_SLIDE_ACTION_DIRECT_SHARE = SLIDE_DIRECT_SHARE;
    public static int messageSlideAction = getPreferences().getInt("messageSlideAction", SLIDE_REPLY);
    public static int messageslideaction = messageSlideAction;
    public static void setMessageSlideAction(int v) {
        messageSlideAction = v;
        messageslideaction = v;
        getEditor().putInt("messageSlideAction", v).apply();
    }

    public static final int FILTER_NONE = 0;
    public static final int FILTER_PHOTOS = 1;
    public static final int FILTER_VIDEOS = 2;
    public static final int FILTER_VOICE_MESSAGES = 3;
    public static final int FILTER_VIDEO_MESSAGES = 4;
    public static final int FILTER_FILES = 5;
    public static final int FILTER_MUSIC = 6;
    public static final int FILTER_GIFS = 7;
    public static final int FILTER_GEO = 8;
    public static final int FILTER_CONTACTS = 9;
    public static final int FILTER_MENTIONS = 10;
    public static int messagesSearchFilter = getPreferences().getInt("messagesSearchFilter", FILTER_NONE);
    public static void setMessagesSearchFilter(int v) {
        if (messagesSearchFilter == v) {
            return;
        }
        messagesSearchFilter = v;
        getEditor().putInt("messagesSearchFilter", v).apply();
    }

    public static String translationTarget = getPreferences().getString("translationTarget", "app");
    public static void setTranslationTarget(String v) {
        translationTarget = v;
        getEditor().putString("translationTarget", v).apply();
    }
    public static String translationKeyboardTarget = getPreferences().getString("translationKeyboardTarget", "app");
    public static void setTranslationKeyboardTarget(String v) {
        translationKeyboardTarget = v;
        getEditor().putString("translationKeyboardTarget", v).apply();
    }

    public static final int ACTIONS_LEFT_REPLY = 0;
    public static final int ACTIONS_LEFT_SAVE_MESSAGE = 1;
    public static final int ACTIONS_LEFT_DIRECT_SHARE = 2;
    public static final int ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP = 3;
    public static final int ACTIONS_LEFT_FORWARD_WO_CAPTION = 4;
    public static int actionsBarLeftButton = getIntSafe("actionsBarLeftButton", ACTIONS_LEFT_REPLY);
    public static void setActionsBarLeftButton(int v) { actionsBarLeftButton = v; getEditor().putInt("actionsBarLeftButton", v).apply(); }

    public static final int TRX_TELEGRAM = 0;
    public static final int TRX_GOOGLE = 1;
    public static final int TRX_SYSTEM = 2;
    public static final int TRANSCRIPTION_PROVIDER_TELEGRAM = TRX_TELEGRAM;
    public static int voiceTranscriptionProvider = getPreferences().getInt("voiceTranscriptionProvider", TRX_TELEGRAM);
    public static void setVoiceTranscriptionProvider(int v) { voiceTranscriptionProvider = v; getEditor().putInt("voiceTranscriptionProvider", v).apply(); }

    public static boolean disablePremStickAnim = getPreferences().getBoolean("disablePremStickAnim", false);
    public static void toggleDisablePremStickAnim() { disablePremStickAnim = !disablePremStickAnim; getEditor().putBoolean("disablePremStickAnim", disablePremStickAnim).apply(); }

    public static boolean disablePremStickAutoPlay = getPreferences().getBoolean("disablePremStickAutoPlay", false);
    public static void toggleDisablePremStickAutoPlay() { disablePremStickAutoPlay = !disablePremStickAutoPlay; getEditor().putBoolean("disablePremStickAutoPlay", disablePremStickAutoPlay).apply(); }

    public static boolean disableReactionAnim = getPreferences().getBoolean("disableReactionAnim", false);
    public static void toggleDisableReactionAnim() { disableReactionAnim = !disableReactionAnim; getEditor().putBoolean("disableReactionAnim", disableReactionAnim).apply(); }

    public static boolean disableReactionsOverlay = getPreferences().getBoolean("disableReactionsOverlay", false);
    public static void toggleDisableReactionsOverlay() { disableReactionsOverlay = !disableReactionsOverlay; getEditor().putBoolean("disableReactionsOverlay", disableReactionsOverlay).apply(); }

    public static boolean hideStickerTime = getPreferences().getBoolean("hideStickerTime", false);
    public static void toggleHideStickerTime() { hideStickerTime = !hideStickerTime; getEditor().putBoolean("hideStickerTime", hideStickerTime).apply(); }

    public static boolean msgForwardDate = getPreferences().getBoolean("msgForwardDate", true);
    public static void toggleMsgForwardDate() { msgForwardDate = !msgForwardDate; getEditor().putBoolean("msgForwardDate", msgForwardDate).apply(); }

    public static boolean weekdayNearDate = getPreferences().getBoolean("weekdayNearDate", false);
    public static void toggleWeekdayNearDate() { weekdayNearDate = !weekdayNearDate; getEditor().putBoolean("weekdayNearDate", weekdayNearDate).apply(); }

    public static boolean showPencilIcon = getPreferences().getBoolean("showPencilIcon", true);
    public static void toggleShowPencilIcon() { showPencilIcon = !showPencilIcon; getEditor().putBoolean("showPencilIcon", showPencilIcon).apply(); }

    public static boolean preReformRussian = getPreferences().getBoolean("preReformRussian", false);
    public static void togglePreReformRussian() { preReformRussian = !preReformRussian; getEditor().putBoolean("preReformRussian", preReformRussian).apply(); }

    public static boolean botsDrawShareButton = getPreferences().getBoolean("botsDrawShareButton", true);
    public static void toggleBotsDrawShareButton() { botsDrawShareButton = !botsDrawShareButton; getEditor().putBoolean("botsDrawShareButton", botsDrawShareButton).apply(); }

    public static boolean channelsDrawShareButton = getPreferences().getBoolean("channelsDrawShareButton", true);
    public static void toggleChannelsDrawShareButton() { channelsDrawShareButton = !channelsDrawShareButton; getEditor().putBoolean("channelsDrawShareButton", channelsDrawShareButton).apply(); }

    public static boolean supergroupsDrawShareButton = getPreferences().getBoolean("supergroupsDrawShareButton", false);
    public static void toggleSupergroupsDrawShareButton() { supergroupsDrawShareButton = !supergroupsDrawShareButton; getEditor().putBoolean("supergroupsDrawShareButton", supergroupsDrawShareButton).apply(); }

    public static boolean usersDrawShareButton = getPreferences().getBoolean("usersDrawShareButton", false);
    public static void toggleUsersDrawShareButton() { usersDrawShareButton = !usersDrawShareButton; getEditor().putBoolean("usersDrawShareButton", usersDrawShareButton).apply(); }

    public static boolean stickersDrawShareButton = getPreferences().getBoolean("stickersDrawShareButton", false);
    public static void toggleStickersDrawShareButton() { stickersDrawShareButton = !stickersDrawShareButton; getEditor().putBoolean("stickersDrawShareButton", stickersDrawShareButton).apply(); }

    public static boolean shareDrawStoryButton = getPreferences().getBoolean("shareDrawStoryButton", true);
    public static void toggleShareDrawStoryButton() { shareDrawStoryButton = !shareDrawStoryButton; getEditor().putBoolean("shareDrawStoryButton", shareDrawStoryButton).apply(); }

    public static boolean msgMenuItemsCompactView = getPreferences().getBoolean("msgMenuItemsCompactView", false);
    public static void toggleMsgMenuItemsCompactView() { msgMenuItemsCompactView = !msgMenuItemsCompactView; getEditor().putBoolean("msgMenuItemsCompactView", msgMenuItemsCompactView).apply(); }

    public static boolean telegramPlusMessageMenu = getPreferences().getBoolean("telegramPlusMessageMenu", false);
    public static void toggleTelegramPlusMessageMenu() {
        telegramPlusMessageMenu = !telegramPlusMessageMenu;
        getEditor().putBoolean("telegramPlusMessageMenu", telegramPlusMessageMenu).apply();
    }

    public static volatile List<Integer> messageMenuOrder = loadMessageMenuOrder();

    private static List<Integer> loadMessageMenuOrder() {
        String raw = getPreferences().getString("messageMenuOrder", null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            List<Integer> list = GSON.fromJson(raw, new TypeToken<List<Integer>>() {}.getType());
            return list != null && !list.isEmpty()
                    ? Collections.unmodifiableList(new ArrayList<>(list))
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static synchronized void setMessageMenuOrder(List<Integer> order) {
        if (order == null || order.isEmpty()) {
            messageMenuOrder = null;
            getEditor().remove("messageMenuOrder").apply();
        } else {
            List<Integer> snapshot = Collections.unmodifiableList(new ArrayList<>(order));
            messageMenuOrder = snapshot;
            getEditor().putString("messageMenuOrder", GSON.toJson(snapshot)).apply();
        }
    }

    public static void resetMessageMenuOrder() { setMessageMenuOrder(null); }

    public static boolean messageMenuHaptic = getPreferences().getBoolean("messageMenuHaptic", true);
    public static void toggleMessageMenuHaptic() {
        messageMenuHaptic = !messageMenuHaptic;
        getEditor().putBoolean("messageMenuHaptic", messageMenuHaptic).apply();
    }

    private static Set<Long> loadLongSet(String key) {
        String raw = getPreferences().getString(key, null);
        if (raw == null || raw.isEmpty()) return new HashSet<>();
        try {
            List<Long> list = GSON.fromJson(raw, new TypeToken<List<Long>>() {}.getType());
            return list != null ? new HashSet<>(list) : new HashSet<>();
        } catch (Throwable ignored) {
            return new HashSet<>();
        }
    }

    private static final class CompactOverrideState {
        final Set<Long> on;
        final Set<Long> off;

        CompactOverrideState(Set<Long> on, Set<Long> off) {
            this.on = Collections.unmodifiableSet(new HashSet<>(on));
            this.off = Collections.unmodifiableSet(new HashSet<>(off));
        }
    }

    private static volatile CompactOverrideState compactOverrideState =
            new CompactOverrideState(
                    loadLongSet("chatCompactOverrideOn"),
                    loadLongSet("chatCompactOverrideOff"));

    private static final class CompactOverrideView extends AbstractSet<Long> {
        private final boolean enabled;

        CompactOverrideView(boolean enabled) {
            this.enabled = enabled;
        }

        private Set<Long> snapshot() {
            CompactOverrideState state = compactOverrideState;
            return enabled ? state.on : state.off;
        }

        @Override
        public Iterator<Long> iterator() {
            return snapshot().iterator();
        }

        @Override
        public int size() {
            return snapshot().size();
        }

        @Override
        public boolean contains(Object value) {
            return snapshot().contains(value);
        }
    }

    public static final Set<Long> chatCompactOverrideOn = new CompactOverrideView(true);
    public static final Set<Long> chatCompactOverrideOff = new CompactOverrideView(false);

    private static void putLongSet(SharedPreferences.Editor editor, String key, Set<Long> set) {
        if (set == null || set.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, GSON.toJson(new ArrayList<>(set)));
        }
    }

    public static boolean isCompactForChat(long dialogId) {
        CompactOverrideState state = compactOverrideState;
        if (state.on.contains(dialogId)) return true;
        if (state.off.contains(dialogId)) return false;
        return msgMenuItemsCompactView;
    }

    public static synchronized boolean cycleChatCompactOverride(long dialogId) {
        CompactOverrideState current = compactOverrideState;
        Set<Long> on = new HashSet<>(current.on);
        Set<Long> off = new HashSet<>(current.off);
        boolean wasOn = on.contains(dialogId);
        boolean wasOff = off.contains(dialogId);
        if (!wasOn && !wasOff) {
            on.add(dialogId);
        } else if (wasOn) {
            on.remove(dialogId);
            off.add(dialogId);
        } else {
            off.remove(dialogId);
        }
        SharedPreferences.Editor editor = getEditor();
        putLongSet(editor, "chatCompactOverrideOn", on);
        putLongSet(editor, "chatCompactOverrideOff", off);
        editor.apply();
        CompactOverrideState updated = new CompactOverrideState(on, off);
        compactOverrideState = updated;
        return updated.on.contains(dialogId)
                || !updated.off.contains(dialogId) && msgMenuItemsCompactView;
    }

    public static boolean largerVoiceMessagesLayout = getPreferences().getBoolean("largerVoiceMessagesLayout", true);
    public static void toggleLargerVoiceMessagesLayout() { largerVoiceMessagesLayout = !largerVoiceMessagesLayout; getEditor().putBoolean("largerVoiceMessagesLayout", largerVoiceMessagesLayout).apply(); }

    public static int slider_mediaAmplifier = getPreferences().getInt("slider_mediaAmplifier", 100);
    public static void setSlider_mediaAmplifier(int v) { slider_mediaAmplifier = v; getEditor().putInt("slider_mediaAmplifier", v).apply(); }

    public static int slider_stickerAmplifier = getPreferences().getInt("slider_stickerAmplifier", 100);
    public static void setSlider_stickerAmplifier(int v) { slider_stickerAmplifier = v; getEditor().putInt("slider_stickerAmplifier", v).apply(); }

    public static int slider_gifsAmplifier = getPreferences().getInt("slider_gifsAmplifier", 100);
    public static void setSlider_gifsAmplifier(int v) { slider_gifsAmplifier = v; getEditor().putInt("slider_gifsAmplifier", v).apply(); }

    public static boolean showSaveForNotifications = getPreferences().getBoolean("showSaveForNotifications", true);
    public static void toggleShowSaveForNotifications() { showSaveForNotifications = !showSaveForNotifications; getEditor().putBoolean("showSaveForNotifications", showSaveForNotifications).apply(); }

    public static boolean showReply = getPreferences().getBoolean("showReply", true);
    public static void toggleShowReply() { showReply = !showReply; getEditor().putBoolean("showReply", showReply).apply(); }

    public static boolean showSaveToGallery = getPreferences().getBoolean("showSaveToGallery", true);
    public static void toggleShowSaveToGallery() { showSaveToGallery = !showSaveToGallery; getEditor().putBoolean("showSaveToGallery", showSaveToGallery).apply(); }

    public static boolean showCopyPhoto = getPreferences().getBoolean("showCopyPhoto", true);
    public static void toggleShowCopyPhoto() { showCopyPhoto = !showCopyPhoto; getEditor().putBoolean("showCopyPhoto", showCopyPhoto).apply(); }

    public static boolean showCopyPhotoAsSticker = getPreferences().getBoolean("showCopyPhotoAsSticker", true);
    public static void toggleShowCopyPhotoAsSticker() { showCopyPhotoAsSticker = !showCopyPhotoAsSticker; getEditor().putBoolean("showCopyPhotoAsSticker", showCopyPhotoAsSticker).apply(); }

    public static boolean showSaveToDownloads = getPreferences().getBoolean("showSaveToDownloads", true);
    public static void toggleShowSaveToDownloads() { showSaveToDownloads = !showSaveToDownloads; getEditor().putBoolean("showSaveToDownloads", showSaveToDownloads).apply(); }

    public static boolean showShare = getPreferences().getBoolean("showShare", true);
    public static void toggleShowShare() { showShare = !showShare; getEditor().putBoolean("showShare", showShare).apply(); }

    public static boolean showClearFromCache = getPreferences().getBoolean("showClearFromCache", true);
    public static void toggleShowClearFromCache() { showClearFromCache = !showClearFromCache; getEditor().putBoolean("showClearFromCache", showClearFromCache).apply(); }

    public static boolean showForward = getPreferences().getBoolean("showForward", false);
    public static void toggleShowForward() { showForward = !showForward; getEditor().putBoolean("showForward", showForward).apply(); }

    public static boolean showForwardWoAuthorship = getPreferences().getBoolean("showForwardWoAuthorship", false);
    public static void toggleShowForwardWoAuthorship() { showForwardWoAuthorship = !showForwardWoAuthorship; getEditor().putBoolean("showForwardWoAuthorship", showForwardWoAuthorship).apply(); }

    public static boolean showViewHistory = getPreferences().getBoolean("showViewHistory", true);
    public static void toggleShowViewHistory() { showViewHistory = !showViewHistory; getEditor().putBoolean("showViewHistory", showViewHistory).apply(); }

    public static boolean showSaveMessage = getPreferences().getBoolean("showSaveMessage", false);
    public static void toggleShowSaveMessage() { showSaveMessage = !showSaveMessage; getEditor().putBoolean("showSaveMessage", showSaveMessage).apply(); }

    public static boolean showReport = getPreferences().getBoolean("showReport", true);
    public static void toggleShowReport() { showReport = !showReport; getEditor().putBoolean("showReport", showReport).apply(); }

    public static boolean showJSON = getPreferences().getBoolean("showJSON", false);
    public static void toggleShowJSON() { showJSON = !showJSON; getEditor().putBoolean("showJSON", showJSON).apply(); }


    public static boolean showForwardWoCaption = getPreferences().getBoolean("showForwardWoCaption", false);
    public static void toggleShowForwardWoCaption() { showForwardWoCaption = !showForwardWoCaption; getEditor().putBoolean("showForwardWoCaption", showForwardWoCaption).apply(); }

    public static boolean showDownloadSticker = getPreferences().getBoolean("showDownloadSticker", false);
    public static void toggleShowDownloadSticker() { showDownloadSticker = !showDownloadSticker; getEditor().putBoolean("showDownloadSticker", showDownloadSticker).apply(); }

    public static boolean showGetCustomReactions = getPreferences().getBoolean("showGetCustomReactions", false);
    public static void toggleShowGetCustomReactions() { showGetCustomReactions = !showGetCustomReactions; getEditor().putBoolean("showGetCustomReactions", showGetCustomReactions).apply(); }


    public static boolean forwardAuthorship = getPreferences().getBoolean("forwardAuthorship", true);
    public static void toggleForwardAuthorship() { forwardAuthorship = !forwardAuthorship; getEditor().putBoolean("forwardAuthorship", forwardAuthorship).apply(); }
    public static void setForwardAuthorship(boolean v) { forwardAuthorship = v; getEditor().putBoolean("forwardAuthorship", v).apply(); }

    public static boolean forwardCaptions = getPreferences().getBoolean("forwardCaptions", true);
    public static void toggleForwardCaptions() { forwardCaptions = !forwardCaptions; getEditor().putBoolean("forwardCaptions", forwardCaptions).apply(); }
    public static void setForwardCaptions(boolean v) { forwardCaptions = v; getEditor().putBoolean("forwardCaptions", v).apply(); }

    public static boolean forwardNotify = getPreferences().getBoolean("forwardNotify", true);
    public static void toggleForwardNotify() { forwardNotify = !forwardNotify; getEditor().putBoolean("forwardNotify", forwardNotify).apply(); }
    public static void setForwardNotify(boolean v) { forwardNotify = v; getEditor().putBoolean("forwardNotify", v).apply(); }

    public static boolean noAuthorship = getPreferences().getBoolean("noAuthorship", false);
    public static void toggleNoAuthorship() { noAuthorship = !noAuthorship; getEditor().putBoolean("noAuthorship", noAuthorship).apply(); }
    public static void setNoAuthorship(boolean v) { noAuthorship = v; getEditor().putBoolean("noAuthorship", v).apply(); }

    public static boolean noCaptions = getPreferences().getBoolean("noCaptions", false);
    public static void toggleNoCaptions() { noCaptions = !noCaptions; getEditor().putBoolean("noCaptions", noCaptions).apply(); }
    public static void setNoCaptions(boolean v) { noCaptions = v; getEditor().putBoolean("noCaptions", v).apply(); }

    public static boolean allowSafeStars = getPreferences().getBoolean("allowSafeStars", false);

    public static boolean sleepTimer = getPreferences().getBoolean("sleepTimer", false);
    public static void toggleSleepTimer() { sleepTimer = !sleepTimer; getEditor().putBoolean("sleepTimer", sleepTimer).apply(); }
    public static void setSleepTimer(boolean v) { sleepTimer = v; getEditor().putBoolean("sleepTimer", v).apply(); }


    public static boolean nimarkoTextAnim = getPreferences().getBoolean("nimarkoTextAnim", true);
    public static void toggleNimarkoTextAnim() {
        nimarkoTextAnim = !nimarkoTextAnim;
        getEditor().putBoolean("nimarkoTextAnim", nimarkoTextAnim).apply();
        app.nimarkogram.messenger.textanim.NimarkoTextAnim.applySettings();
    }

    public static boolean nimarkoTextAnimAppear = getPreferences().getBoolean("nimarkoTextAnimAppear", true);
    public static void toggleNimarkoTextAnimAppear() {
        nimarkoTextAnimAppear = !nimarkoTextAnimAppear;
        getEditor().putBoolean("nimarkoTextAnimAppear", nimarkoTextAnimAppear).apply();
        app.nimarkogram.messenger.textanim.NimarkoTextAnim.applySettings();
    }

    public static boolean nimarkoTextAnimCursor = getPreferences().getBoolean("nimarkoTextAnimCursor", true);
    public static void toggleNimarkoTextAnimCursor() {
        nimarkoTextAnimCursor = !nimarkoTextAnimCursor;
        getEditor().putBoolean("nimarkoTextAnimCursor", nimarkoTextAnimCursor).apply();
        app.nimarkogram.messenger.textanim.NimarkoTextAnim.applySettings();
    }

    public static boolean nimarkoTextAnimDelete = getPreferences().getBoolean("nimarkoTextAnimDelete", true);
    public static void toggleNimarkoTextAnimDelete() {
        nimarkoTextAnimDelete = !nimarkoTextAnimDelete;
        getEditor().putBoolean("nimarkoTextAnimDelete", nimarkoTextAnimDelete).apply();
        app.nimarkogram.messenger.textanim.NimarkoTextAnim.applySettings();
    }

    public static boolean nimarkoTextAnimSpoiler = getPreferences().getBoolean("nimarkoTextAnimSpoiler", false);
    public static void toggleNimarkoTextAnimSpoiler() {
        nimarkoTextAnimSpoiler = !nimarkoTextAnimSpoiler;
        getEditor().putBoolean("nimarkoTextAnimSpoiler", nimarkoTextAnimSpoiler).apply();
        app.nimarkogram.messenger.textanim.NimarkoTextAnim.applySettings();
    }

    public static boolean showRPCErrors = getPreferences().getBoolean("showRPCErrors", false);
    public static void toggleShowRPCErrors() { showRPCErrors = !showRPCErrors; getEditor().putBoolean("showRPCErrors", showRPCErrors).apply(); }

    public static final int AUDIO_SOURCE_DEFAULT = 0;
    public static final int AUDIO_SOURCE_MIC = 1;
    public static final int AUDIO_SOURCE_VOICE_UPLINK = 2;
    public static final int AUDIO_SOURCE_VOICE_DOWNLINK = 3;
    public static final int AUDIO_SOURCE_VOICE_CALL = 4;
    public static final int AUDIO_SOURCE_CAMCORDER = 5;
    public static final int AUDIO_SOURCE_VOICE_RECOGNITION = 6;
    public static final int AUDIO_SOURCE_VOICE_COMMUNICATION = 7;
    public static final int AUDIO_SOURCE_REMOTE_SUBMIX = 8;
    public static final int AUDIO_SOURCE_UNPROCESSED = 9;
    public static final int AUDIO_SOURCE_VOICE_PERFORMANCE = 10;
    public static int audioSource = sanitizeAudioSource(getIntSafe("audioSource", AUDIO_SOURCE_DEFAULT));
    public static void setAudioSource(int v) {
        audioSource = sanitizeAudioSource(v);
        getEditor().putInt("audioSource", audioSource).apply();
    }

    public static int sanitizeAudioSource(int value) {
        switch (value) {
            case AUDIO_SOURCE_DEFAULT:
            case AUDIO_SOURCE_MIC:
            case AUDIO_SOURCE_CAMCORDER:
            case AUDIO_SOURCE_VOICE_RECOGNITION:
            case AUDIO_SOURCE_VOICE_COMMUNICATION:
                return value;
            case AUDIO_SOURCE_UNPROCESSED:
                return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
                        ? value : AUDIO_SOURCE_DEFAULT;
            case AUDIO_SOURCE_VOICE_PERFORMANCE:
                return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                        ? value : AUDIO_SOURCE_DEFAULT;
            default:
                return AUDIO_SOURCE_DEFAULT;
        }
    }

    public static int getMediaRecorderAudioSource() {
        switch (sanitizeAudioSource(audioSource)) {
            case AUDIO_SOURCE_MIC: return android.media.MediaRecorder.AudioSource.MIC;
            case AUDIO_SOURCE_CAMCORDER: return android.media.MediaRecorder.AudioSource.CAMCORDER;
            case AUDIO_SOURCE_VOICE_RECOGNITION: return android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case AUDIO_SOURCE_VOICE_COMMUNICATION: return android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            case AUDIO_SOURCE_UNPROCESSED: return android.media.MediaRecorder.AudioSource.UNPROCESSED;
            case AUDIO_SOURCE_VOICE_PERFORMANCE: return android.media.MediaRecorder.AudioSource.VOICE_PERFORMANCE;
            case AUDIO_SOURCE_DEFAULT:
            default: return android.media.MediaRecorder.AudioSource.DEFAULT;
        }
    }

    public static boolean jacksonJSON_Provider = getPreferences().getBoolean("jacksonJSON_Provider",
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O);
    public static void toggleJacksonJSON_Provider() { jacksonJSON_Provider = !jacksonJSON_Provider; getEditor().putBoolean("jacksonJSON_Provider", jacksonJSON_Provider).apply(); }
    public static void setJacksonJSON_Provider(boolean value) { jacksonJSON_Provider = value; getEditor().putBoolean("jacksonJSON_Provider", value).apply(); }

    public static boolean hideVideoTimestamp = getPreferences().getBoolean("hideVideoTimestamp", true);
    public static void toggleHideVideoTimestamp() { hideVideoTimestamp = !hideVideoTimestamp; getEditor().putBoolean("hideVideoTimestamp", hideVideoTimestamp).apply(); }

    public static boolean oldTimeStyle = getPreferences().getBoolean("oldTimeStyle", false);
    public static void toggleOldTimeStyle() { oldTimeStyle = !oldTimeStyle; getEditor().putBoolean("oldTimeStyle", oldTimeStyle).apply(); }

    public static boolean replacePunctuationMarks = getPreferences().getBoolean("replacePunctuationMarks", true);
    public static void toggleReplacePunctuationMarks() { replacePunctuationMarks = !replacePunctuationMarks; getEditor().putBoolean("replacePunctuationMarks", replacePunctuationMarks).apply(); }

    public static boolean editTextSuggestionsFix = getPreferences().getBoolean("editTextSuggestionsFix", false);
    public static void toggleEditTextSuggestionsFix() { editTextSuggestionsFix = !editTextSuggestionsFix; getEditor().putBoolean("editTextSuggestionsFix", editTextSuggestionsFix).apply(); }

    public static boolean showAccounts = getPreferences().getBoolean("showAccounts", true);
    public static void toggleShowAccounts() { showAccounts = !showAccounts; getEditor().putBoolean("showAccounts", showAccounts).apply(); }

    public static boolean edgeToEdgeMode = getPreferences().getBoolean("edgeToEdgeMode", false);
    public static void toggleEdgeToEdgeMode() { edgeToEdgeMode = !edgeToEdgeMode; getEditor().putBoolean("edgeToEdgeMode", edgeToEdgeMode).apply(); }

    public static boolean noRounding = getPreferences().getBoolean("noRounding", true);
    public static void toggleNoRounding() { noRounding = !noRounding; getEditor().putBoolean("noRounding", noRounding).apply(); }

    public static boolean discussInsteadOfMute = getPreferences().getBoolean("discussInsteadOfMute", true);
    public static void toggleDiscussInsteadOfMute() { discussInsteadOfMute = !discussInsteadOfMute; getEditor().putBoolean("discussInsteadOfMute", discussInsteadOfMute).apply(); }


    public static boolean showSearchInTabs = getPreferences().getBoolean("showSearchInTabs", false);
    public static void toggleShowSearchInTabs() { showSearchInTabs = !showSearchInTabs; getEditor().putBoolean("showSearchInTabs", showSearchInTabs).apply(); }

    public static final int ROUND_AUTO = 0;
    public static final int ROUND_SD = 1;
    public static final int ROUND_HD = 2;
    public static final int ROUND_FHD = 3;
    public static final int ROUND_STD = 4;
    public static int videoMessagesResolution = getIntSafe("videoMessagesResolution", ROUND_STD);
    public static void setVideoMessagesResolution(int v) { videoMessagesResolution = v; getEditor().putInt("videoMessagesResolution", v).apply(); }
    public static int getVideoMessagesResolutionPx(int defaultPx) {
        switch (videoMessagesResolution) {
            case ROUND_AUTO: return 384;
            case ROUND_SD: return 240;
            case ROUND_STD: return 384;
            case ROUND_FHD: return 720;
            case ROUND_HD:
            default: return 512;
        }
    }

    public static int videoMessagesBitrateKbps = getIntSafe("videoMessagesBitrateKbps", 1000);
    public static int videoMessagesAudioBitrateKbps = getIntSafe("videoMessagesAudioBitrateKbps", 64);
    public static void setVideoMessagesBitrateKbps(int v) { videoMessagesBitrateKbps = v; getEditor().putInt("videoMessagesBitrateKbps", v).apply(); }
    public static void setVideoMessagesAudioBitrateKbps(int v) { videoMessagesAudioBitrateKbps = v; getEditor().putInt("videoMessagesAudioBitrateKbps", v).apply(); }
    static {
        try {
            if (!getPreferences().getBoolean("ngRoundDefaultsClamped", false)) {
                android.content.SharedPreferences.Editor e = getEditor();
                if (videoMessagesBitrateKbps > 1000) { videoMessagesBitrateKbps = 1000; e.putInt("videoMessagesBitrateKbps", 1000); }
                if (videoMessagesAudioBitrateKbps > 64) { videoMessagesAudioBitrateKbps = 64; e.putInt("videoMessagesAudioBitrateKbps", 64); }
                if (videoMessagesResolution == ROUND_HD || videoMessagesResolution == ROUND_FHD) { videoMessagesResolution = ROUND_STD; e.putInt("videoMessagesResolution", ROUND_STD); }
                e.putBoolean("ngRoundDefaultsClamped", true).apply();
            }
        } catch (Throwable ignored) {}
    }

    public static boolean sendVideosAtMaxQuality = getPreferences().getBoolean("sendVideosAtMaxQuality", true);
    public static void toggleSendVideosAtMaxQuality() { sendVideosAtMaxQuality = !sendVideosAtMaxQuality; getEditor().putBoolean("sendVideosAtMaxQuality", sendVideosAtMaxQuality).apply(); }

    public static int videoMessagesHintCount = getPreferences().getInt("videoMessagesHintCount", 0);
    public static void setVideoMessagesHintCount(int v) { videoMessagesHintCount = v; getEditor().putInt("videoMessagesHintCount", v).apply(); }
    public static void decrementVideoMessagesHintCount() {
        if (videoMessagesHintCount > 0) {
            videoMessagesHintCount--;
            getEditor().putInt("videoMessagesHintCount", videoMessagesHintCount).apply();
        }
    }

    private static volatile List<String> pinnedPlugins = loadPinnedPlugins();

    private static List<String> loadPinnedPlugins() {
        String raw = getPreferences().getString("pinnedPlugins", null);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<String> list = GSON.fromJson(raw, new TypeToken<List<String>>() {}.getType());
            return immutablePinnedPlugins(list);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static List<String> immutablePinnedPlugins(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<String> ordered = new ArrayList<>(source.size());
        HashSet<String> seen = new HashSet<>();
        for (String pluginId : source) {
            if (pluginId != null && !pluginId.isEmpty() && seen.add(pluginId)) {
                ordered.add(pluginId);
            }
        }
        return ordered.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(ordered);
    }

    public static List<String> getPinnedPluginsSnapshot() {
        return pinnedPlugins;
    }

    public static boolean isPluginPinned(String pluginId) {
        return pluginId != null && !pluginId.isEmpty() && pinnedPlugins.contains(pluginId);
    }

    public static synchronized void setPluginPinned(String pluginId, boolean pinned) {
        if (pluginId == null || pluginId.isEmpty()) {
            return;
        }
        List<String> current = pinnedPlugins;
        boolean alreadyPinned = current.contains(pluginId);
        if (alreadyPinned == pinned) {
            return;
        }
        ArrayList<String> updated = new ArrayList<>(current);
        if (pinned) {
            updated.add(pluginId);
        } else {
            updated.remove(pluginId);
        }
        List<String> snapshot = immutablePinnedPlugins(updated);
        pinnedPlugins = snapshot;
        savePinnedPluginsSnapshot(snapshot);
    }

    public static synchronized void savePinnedPlugins() {
        savePinnedPluginsSnapshot(pinnedPlugins);
    }

    public static synchronized boolean removePluginPinnedDurably(
            String pluginId) {
        if (pluginId == null || pluginId.isEmpty()
                || !pinnedPlugins.contains(pluginId)) {
            return true;
        }
        List<String> previous = pinnedPlugins;
        ArrayList<String> updated = new ArrayList<>(previous);
        updated.remove(pluginId);
        List<String> snapshot = immutablePinnedPlugins(updated);
        pinnedPlugins = snapshot;
        boolean saved = getEditor()
                .putString("pinnedPlugins", GSON.toJson(snapshot))
                .commit();
        if (!saved) {
            pinnedPlugins = previous;
        }
        return saved;
    }

    private static void savePinnedPluginsSnapshot(List<String> snapshot) {
        getEditor().putString("pinnedPlugins", GSON.toJson(snapshot)).apply();
    }

    public static volatile boolean enableMsgFilters = getPreferences().getBoolean("enableMsgFilters", false);
    public static void setEnableMsgFilters(boolean v) { enableMsgFilters = v; getEditor().putBoolean("enableMsgFilters", v).apply(); notifyMessageFiltersChanged(); }
    public static void toggleEnableMsgFilters() { setEnableMsgFilters(!enableMsgFilters); }

    public static volatile String msgFiltersElements = getPreferences().getString("msgFiltersElements", "");
    public static void setMsgFiltersElements(String v) { msgFiltersElements = v == null ? "" : v; getEditor().putString("msgFiltersElements", msgFiltersElements).apply(); notifyMessageFiltersChanged(); }

    public static volatile boolean msgFiltersDetectTranslit = getPreferences().getBoolean("msgFiltersDetectTranslit", false);
    public static void setMsgFiltersDetectTranslit(boolean v) { msgFiltersDetectTranslit = v; getEditor().putBoolean("msgFiltersDetectTranslit", v).apply(); notifyMessageFiltersChanged(); }
    public static void toggleMsgFiltersDetectTranslit() { setMsgFiltersDetectTranslit(!msgFiltersDetectTranslit); }

    public static volatile boolean msgFiltersMatchExactWord = getPreferences().getBoolean("msgFiltersMatchExactWord", false);
    public static void setMsgFiltersMatchExactWord(boolean v) { msgFiltersMatchExactWord = v; getEditor().putBoolean("msgFiltersMatchExactWord", v).apply(); notifyMessageFiltersChanged(); }
    public static void toggleMsgFiltersMatchExactWord() { setMsgFiltersMatchExactWord(!msgFiltersMatchExactWord); }

    public static volatile boolean msgFiltersDetectEntities = getPreferences().getBoolean("msgFiltersDetectEntities", false);
    public static void setMsgFiltersDetectEntities(boolean v) { msgFiltersDetectEntities = v; getEditor().putBoolean("msgFiltersDetectEntities", v).apply(); notifyMessageFiltersChanged(); }
    public static void toggleMsgFiltersDetectEntities() { setMsgFiltersDetectEntities(!msgFiltersDetectEntities); }

    public static volatile boolean msgFiltersHideFromBlocked = getPreferences().getBoolean("msgFiltersHideFromBlocked", false);
    public static void setMsgFiltersHideFromBlocked(boolean v) { msgFiltersHideFromBlocked = v; getEditor().putBoolean("msgFiltersHideFromBlocked", v).apply(); notifyMessageFiltersChanged(); }
    public static void toggleMsgFiltersHideFromBlocked() { setMsgFiltersHideFromBlocked(!msgFiltersHideFromBlocked); }

    public static volatile boolean msgFiltersHideAll = getPreferences().getBoolean("msgFiltersHideAll", false);
    public static void setMsgFiltersHideAll(boolean v) { msgFiltersHideAll = v; getEditor().putBoolean("msgFiltersHideAll", v).apply(); notifyMessageFiltersChanged(); }
    public static void toggleMsgFiltersHideAll() { setMsgFiltersHideAll(!msgFiltersHideAll); }

    public static volatile boolean msgFiltersCollapseAutomatically = getPreferences().getBoolean("msgFiltersCollapseAutomatically", false);
    public static void setMsgFiltersCollapseAutomatically(boolean v) {
        msgFiltersCollapseAutomatically = v;
        getEditor().putBoolean("msgFiltersCollapseAutomatically", v).apply();
        app.nimarkogram.messenger.chats.filters.MessagesFilterHelper.INSTANCE.clearRevealedMessages();
        notifyMessageFiltersChanged();
    }
    public static void toggleMsgFiltersCollapseAutomatically() { setMsgFiltersCollapseAutomatically(!msgFiltersCollapseAutomatically); }

    public static volatile boolean msgFilterTransparentMsg = getPreferences().getBoolean("msgFilterTransparentMsg", false);
    public static void setMsgFilterTransparentMsg(boolean v) { msgFilterTransparentMsg = v; getEditor().putBoolean("msgFilterTransparentMsg", v).apply(); notifyMessageFiltersChanged(); }
    public static void toggleMsgFilterTransparentMsg() { setMsgFilterTransparentMsg(!msgFilterTransparentMsg); }

    public static volatile String msgFiltersExcludedChats = "";
    public static void setMsgFiltersExcludedChats(String v) {
        setMsgFiltersExcludedChats(org.telegram.messenger.UserConfig.selectedAccount, v);
    }
    public static void setMsgFiltersExcludedChats(int account, String v) {
        setMsgFiltersExcludedChats(account, messageFiltersOwnerUid(account), v);
    }
    public static boolean setMsgFiltersExcludedChats(int account, long ownerUid, String v) {
        if (!putIdentityChatList("msgFiltersExcludedChats", account, ownerUid, v)) return false;
        if (account == org.telegram.messenger.UserConfig.selectedAccount) {
            msgFiltersExcludedChats = v == null ? "" : v;
        }
        notifyMessageFiltersChanged();
        return true;
    }


    public static volatile boolean msgFiltersUseRegex = getPreferences().getBoolean("msgFiltersUseRegex", false);
    public static void setMsgFiltersUseRegex(boolean v) { msgFiltersUseRegex = v; getEditor().putBoolean("msgFiltersUseRegex", v).apply(); notifyMessageFiltersChanged(); }
    public static void toggleMsgFiltersUseRegex() { setMsgFiltersUseRegex(!msgFiltersUseRegex); }

    public static volatile String msgFiltersRegexPatterns = getPreferences().getString("msgFiltersRegexPatterns", "");
    public static void setMsgFiltersRegexPatterns(String v) {
        msgFiltersRegexPatterns = v == null ? "" : v;
        getEditor().putString("msgFiltersRegexPatterns", msgFiltersRegexPatterns).apply();
        notifyMessageFiltersChanged();
    }

    public static volatile String msgFiltersChatWhitelist = "";
    public static void setMsgFiltersChatWhitelist(String v) {
        setMsgFiltersChatWhitelist(org.telegram.messenger.UserConfig.selectedAccount, v);
    }
    public static void setMsgFiltersChatWhitelist(int account, String v) {
        setMsgFiltersChatWhitelist(account, messageFiltersOwnerUid(account), v);
    }
    public static boolean setMsgFiltersChatWhitelist(int account, long ownerUid, String v) {
        if (!putIdentityChatList("msgFiltersChatWhitelist", account, ownerUid, v)) return false;
        if (account == org.telegram.messenger.UserConfig.selectedAccount) {
            msgFiltersChatWhitelist = v == null ? "" : v;
        }
        notifyMessageFiltersChanged();
        return true;
    }

    public static volatile String msgFiltersChatBlacklist = "";
    public static void setMsgFiltersChatBlacklist(String v) {
        setMsgFiltersChatBlacklist(org.telegram.messenger.UserConfig.selectedAccount, v);
    }
    public static void setMsgFiltersChatBlacklist(int account, String v) {
        setMsgFiltersChatBlacklist(account, messageFiltersOwnerUid(account), v);
    }
    public static boolean setMsgFiltersChatBlacklist(int account, long ownerUid, String v) {
        if (!putIdentityChatList("msgFiltersChatBlacklist", account, ownerUid, v)) return false;
        if (account == org.telegram.messenger.UserConfig.selectedAccount) {
            msgFiltersChatBlacklist = v == null ? "" : v;
        }
        notifyMessageFiltersChanged();
        return true;
    }

    public static final int MSG_FILTERS_LOGIC_OR  = 0;
    public static final int MSG_FILTERS_LOGIC_AND = 1;
    public static volatile int msgFiltersLogic = getIntSafe("msgFiltersLogic", MSG_FILTERS_LOGIC_OR);
    public static void setMsgFiltersLogic(int v) { msgFiltersLogic = v; getEditor().putInt("msgFiltersLogic", v).apply(); notifyMessageFiltersChanged(); }

    public static SharedPreferences prefs() { return getPreferences(); }
    public static SharedPreferences.Editor editor() { return getEditor(); }

    public static void putBoolean(String key, boolean value) {
        getEditor().putBoolean(key, value).apply();
        reloadMsgFilters();
        notifyMessageFiltersChanged();
    }
    public static void putString(String key, String value) {
        getEditor().putString(key, value).apply();
        reloadMsgFilters();
        notifyMessageFiltersChanged();
    }
    public static void putInt(String key, int value) {
        getEditor().putInt(key, value).apply();
        reloadMsgFilters();
        notifyMessageFiltersChanged();
    }

    private static long messageFiltersOwnerUid(int account) {
        if (account < 0 || account >= org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT) return 0L;
        return org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
    }

    private static String identityChatListKey(String baseKey, int account, long ownerUid) {
        return baseKey + "_a" + account + "_u" + ownerUid;
    }

    private static synchronized String getIdentityChatList(String baseKey, int account) {
        long ownerUid = messageFiltersOwnerUid(account);
        if (ownerUid <= 0) return "";
        SharedPreferences preferences = getPreferences();
        String scopedKey = identityChatListKey(baseKey, account, ownerUid);
        if (preferences.contains(scopedKey)) {
            return preferences.getString(scopedKey, "");
        }
        String migrationKey = baseKey + "_identity_migrated";
        if (!preferences.getBoolean(migrationKey, false)
                && account == org.telegram.messenger.UserConfig.selectedAccount
                && preferences.contains(baseKey)) {
            String legacy = preferences.getString(baseKey, "");
            getEditor()
                    .putString(scopedKey, legacy == null ? "" : legacy)
                    .remove(baseKey)
                    .putBoolean(migrationKey, true)
                    .commit();
            return legacy == null ? "" : legacy;
        }
        return "";
    }

    private static synchronized boolean putIdentityChatList(
            String baseKey, int account, long ownerUid, String value) {
        String normalized = value == null ? "" : value;
        if (ownerUid <= 0 || messageFiltersOwnerUid(account) != ownerUid) return false;
        getEditor()
                .putString(identityChatListKey(baseKey, account, ownerUid), normalized)
                .remove(baseKey)
                .putBoolean(baseKey + "_identity_migrated", true)
                .apply();
        return true;
    }

    private static void notifyMessageFiltersChanged() {
        for (int account = 0; account < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; account++) {
            org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(
                    org.telegram.messenger.NotificationCenter.updateInterfaces,
                    org.telegram.messenger.MessagesController.UPDATE_MASK_MESSAGE_FILTERS);
        }
    }

    private static void reloadMsgFilters() {
        SharedPreferences p = getPreferences();
        enableMsgFilters = p.getBoolean("enableMsgFilters", false);
        msgFiltersElements = p.getString("msgFiltersElements", "");
        msgFiltersDetectTranslit = p.getBoolean("msgFiltersDetectTranslit", false);
        msgFiltersMatchExactWord = p.getBoolean("msgFiltersMatchExactWord", false);
        msgFiltersDetectEntities = p.getBoolean("msgFiltersDetectEntities", false);
        msgFiltersHideFromBlocked = p.getBoolean("msgFiltersHideFromBlocked", false);
        msgFiltersHideAll = p.getBoolean("msgFiltersHideAll", false);
        msgFiltersCollapseAutomatically = p.getBoolean("msgFiltersCollapseAutomatically", false);
        msgFilterTransparentMsg = p.getBoolean("msgFilterTransparentMsg", false);
        msgFiltersExcludedChats = getMsgFiltersExcludedChats();
        msgFiltersUseRegex = p.getBoolean("msgFiltersUseRegex", false);
        msgFiltersRegexPatterns = p.getString("msgFiltersRegexPatterns", "");
        msgFiltersChatWhitelist = getMsgFiltersChatWhitelist();
        msgFiltersChatBlacklist = getMsgFiltersChatBlacklist();
        msgFiltersLogic = p.getInt("msgFiltersLogic", MSG_FILTERS_LOGIC_OR);
    }

    public static boolean isEnableMsgFilters()                { return enableMsgFilters; }
    public static String  getMsgFiltersElements()             { return msgFiltersElements; }
    public static boolean isMsgFiltersDetectTranslit()        { return msgFiltersDetectTranslit; }
    public static boolean isMsgFiltersMatchExactWord()        { return msgFiltersMatchExactWord; }
    public static boolean isMsgFiltersDetectEntities()        { return msgFiltersDetectEntities; }
    public static boolean isMsgFiltersHideFromBlocked()       { return msgFiltersHideFromBlocked; }
    public static boolean isMsgFiltersHideAll()               { return msgFiltersHideAll; }
    public static boolean isMsgFiltersCollapseAutomatically() { return msgFiltersCollapseAutomatically; }
    public static boolean isMsgFilterTransparentMsg()         { return msgFilterTransparentMsg; }
    public static String  getMsgFiltersExcludedChats() {
        return getMsgFiltersExcludedChats(org.telegram.messenger.UserConfig.selectedAccount);
    }
    public static String getMsgFiltersExcludedChats(int account) {
        String value = getIdentityChatList("msgFiltersExcludedChats", account);
        if (account == org.telegram.messenger.UserConfig.selectedAccount) msgFiltersExcludedChats = value;
        return value;
    }

    public static boolean isMsgFiltersUseRegex()              { return msgFiltersUseRegex; }
    public static String  getMsgFiltersRegexPatterns()        { return msgFiltersRegexPatterns; }
    public static String  getMsgFiltersChatWhitelist() {
        return getMsgFiltersChatWhitelist(org.telegram.messenger.UserConfig.selectedAccount);
    }
    public static String getMsgFiltersChatWhitelist(int account) {
        String value = getIdentityChatList("msgFiltersChatWhitelist", account);
        if (account == org.telegram.messenger.UserConfig.selectedAccount) msgFiltersChatWhitelist = value;
        return value;
    }
    public static String  getMsgFiltersChatBlacklist() {
        return getMsgFiltersChatBlacklist(org.telegram.messenger.UserConfig.selectedAccount);
    }
    public static String getMsgFiltersChatBlacklist(int account) {
        String value = getIdentityChatList("msgFiltersChatBlacklist", account);
        if (account == org.telegram.messenger.UserConfig.selectedAccount) msgFiltersChatBlacklist = value;
        return value;
    }
    public static int     getMsgFiltersLogic()                { return msgFiltersLogic; }

    public static boolean hideBubbleTail = getPreferences().getBoolean("hideBubbleTail", false);
    public static int bubbleShapeGeneration = 0;
    public static void toggleHideBubbleTail() {
        hideBubbleTail = !hideBubbleTail;
        bubbleShapeGeneration++;
        getEditor().putBoolean("hideBubbleTail", hideBubbleTail).apply();
    }

    public static boolean onlineIndicatorInGroups = getPreferences().getBoolean("onlineIndicatorInGroups", true);
    public static void toggleOnlineIndicatorInGroups() {
        onlineIndicatorInGroups = !onlineIndicatorInGroups;
        getEditor().putBoolean("onlineIndicatorInGroups", onlineIndicatorInGroups).apply();
    }

    public static final float AVATAR_CORNERS_MIN = 0f;
    public static final float AVATAR_CORNERS_MAX = 30f;

    public static float avatarCorners = getPreferences().getFloat("avatarCorners", AVATAR_CORNERS_MAX);
    public static void setAvatarCorners(float v) {
        if (v < AVATAR_CORNERS_MIN) v = AVATAR_CORNERS_MIN;
        if (v > AVATAR_CORNERS_MAX) v = AVATAR_CORNERS_MAX;
        avatarCorners = v;
        getEditor().putFloat("avatarCorners", v).apply();
    }

    public static final float STICKER_SIZE_MIN = 4f;
    public static final float STICKER_SIZE_MAX = 20f;
    public static final float STICKER_SIZE_DEFAULT = 14f;

    public static float stickerSize = getPreferences().getFloat("stickerSize", STICKER_SIZE_DEFAULT);
    public static void setStickerSize(float v) {
        if (v < STICKER_SIZE_MIN) v = STICKER_SIZE_MIN;
        if (v > STICKER_SIZE_MAX) v = STICKER_SIZE_MAX;
        stickerSize = v;
        getEditor().putFloat("stickerSize", v).apply();
    }

    public static boolean forumAvatarsLikeChats = getPreferences().getBoolean("forumAvatarsLikeChats", false);
    public static void toggleForumAvatarsLikeChats() {
        forumAvatarsLikeChats = !forumAvatarsLikeChats;
        getEditor().putBoolean("forumAvatarsLikeChats", forumAvatarsLikeChats).apply();
    }

    public static int getAvatarCorners(float size) {
        return getAvatarCorners(size, false);
    }

    public static int getAvatarCorners(float size, boolean toPx) {
        if (avatarCorners == 0) {
            return 0;
        }
        return (int) (avatarCorners * (size / 56.0f)
                * (toPx ? 1 : org.telegram.messenger.AndroidUtilities.density));
    }

    public static int getAvatarCornersForChat(float size, boolean forum) {
        return getAvatarCorners(forum && !forumAvatarsLikeChats ? size * 0.65f : size);
    }


    public static boolean forceBlur = getPreferences().getBoolean("forceBlur", false);
    public static void toggleForceBlur() {
        forceBlur = !forceBlur;
        getEditor().putBoolean("forceBlur", forceBlur).apply();
    }

    public static boolean inappBrowser = getPreferences().getBoolean("inappBrowser", true);
    public static void setInappBrowser(boolean v) {
        inappBrowser = v;
        getEditor().putBoolean("inappBrowser", v).apply();
    }

    public static boolean roundCamLogicalDisabled = getPreferences().getBoolean("roundCamLogicalDisabled", false);
    public static void setRoundCamLogicalDisabled(boolean v) {
        roundCamLogicalDisabled = v;
        getEditor().putBoolean("roundCamLogicalDisabled", v).apply();
    }

    public static boolean hideActionBarStatus = getPreferences().getBoolean("hideActionBarStatus", false);
    public static void toggleHideActionBarStatus() {
        hideActionBarStatus = !hideActionBarStatus;
        getEditor().putBoolean("hideActionBarStatus", hideActionBarStatus).apply();
    }

    public static boolean disableSendHints = getPreferences().getBoolean("disableSendHints", false);
    public static void toggleDisableSendHints() {
        disableSendHints = !disableSendHints;
        getEditor().putBoolean("disableSendHints", disableSendHints).apply();
    }

    public static boolean customTitleEnabled = getPreferences().getBoolean("customTitleEnabled", false);
    public static String customTitleText = getPreferences().getString("customTitleText", "");
    public static void setCustomTitle(boolean enabled, String text) {
        customTitleEnabled = enabled;
        customTitleText = text == null ? "" : text;
        getEditor().putBoolean("customTitleEnabled", customTitleEnabled)
                .putString("customTitleText", customTitleText).apply();
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                org.telegram.messenger.NotificationCenter.getGlobalInstance()
                        .postNotificationName(org.telegram.messenger.NotificationCenter.customTitleUpdated));
    }

    public static CharSequence resolveMainTitle(CharSequence appName) {
        if (customTitleEnabled && customTitleText != null && !customTitleText.trim().isEmpty()) {
            return customTitleText;
        }
        return appName;
    }

    public static boolean notificationReactions = getPreferences().getBoolean("notificationReactions", true);
    public static void toggleNotificationReactions() {
        notificationReactions = !notificationReactions;
        getEditor().putBoolean("notificationReactions", notificationReactions).apply();
    }

    public static String getNotificationReaction(int account) {
        return getPreferences().getString("notificationReaction_" + account, null);
    }
    public static void setNotificationReaction(int account, String reaction) {
        getEditor().putString("notificationReaction_" + account, reaction).apply();
    }

    public static String getNotificationReactionEmoji(int account) {
        return getPreferences().getString("notificationReactionEmoji_" + account, null);
    }
    public static void setNotificationReactionEmoji(int account, String emoji) {
        getEditor().putString("notificationReactionEmoji_" + account, emoji).apply();
    }
}
