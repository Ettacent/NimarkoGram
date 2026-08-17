package com.exteragram.messenger;

public final class ExteraConfig {

    private ExteraConfig() {}

    public static final android.content.SharedPreferences.Editor editor =
            app.nimarkogram.messenger.NimarkoConfig.getEditor();

    public static android.content.SharedPreferences.Editor getEditor() {
        try {
            return app.nimarkogram.messenger.NimarkoConfig.getEditor();
        } catch (Throwable t) {
            return editor;
        }
    }

    public static android.content.SharedPreferences getPreferences() {
        try {
            return app.nimarkogram.messenger.NimarkoConfig.getPreferences();
        } catch (Throwable t) {
            return null;
        }
    }

    public static volatile boolean pluginsSafeMode = app.nimarkogram.messenger.NimarkoConfig.pluginsSafeMode;

    public static boolean getPluginsSafeMode() {
        try { return app.nimarkogram.messenger.NimarkoConfig.pluginsSafeMode; } catch (Throwable t) { return false; }
    }

    public static void setPluginsSafeMode(boolean enabled) {
        pluginsSafeMode = enabled;
        try { app.nimarkogram.messenger.NimarkoConfig.setPluginsSafeMode(enabled); } catch (Throwable t) {   }
    }

    public static boolean getPluginsDevMode() {
        try { return app.nimarkogram.messenger.NimarkoConfig.pluginsDevMode; } catch (Throwable t) { return false; }
    }

    public static boolean getPluginsCompactView() {
        try { return app.nimarkogram.messenger.NimarkoConfig.pluginsCompactView; } catch (Throwable t) { return false; }
    }

    public static boolean getPluginsDisableArtOpts() {
        try { return app.nimarkogram.messenger.NimarkoConfig.pluginsDisableArtOpts; } catch (Throwable t) { return false; }
    }

    public static boolean getPluginsPySdkAutoUpdate() {
        try { return app.nimarkogram.messenger.NimarkoConfig.pluginsPySdkAutoUpdate; } catch (Throwable t) { return false; }
    }

    public static boolean getPluginsPySdkBetaVersions() {
        try { return app.nimarkogram.messenger.NimarkoConfig.pluginsPySdkBetaVersions; } catch (Throwable t) { return false; }
    }

    public static boolean getPluginsEngine() {
        try { return app.nimarkogram.messenger.NimarkoConfig.pluginsEngine; } catch (Throwable t) { return true; }
    }

    public static float getAvatarCorners() {
        try { return app.nimarkogram.messenger.NimarkoConfig.avatarCorners; } catch (Throwable t) { return 0f; }
    }

    public static int getAvatarCorners(float size) {
        try { return app.nimarkogram.messenger.NimarkoConfig.getAvatarCorners(size); } catch (Throwable t) { return 0; }
    }

    public static int getAvatarCorners(float size, boolean toPx) {
        try { return app.nimarkogram.messenger.NimarkoConfig.getAvatarCorners(size, toPx); } catch (Throwable t) { return 0; }
    }

    public static int getAvatarCorners(float size, boolean toPx, boolean forum) {
        return getAvatarCorners(size, toPx, forum, false);
    }

    public static int getAvatarCorners(float size, boolean toPx, boolean forum, boolean withStory) {
        try {
            float corners = app.nimarkogram.messenger.NimarkoConfig.avatarCorners;
            if (corners == 0f) {
                return 0;
            }
            float value = (corners * size) / 56.0f;
            if (withStory) {
                value -= 2.5f;
            }
            if (!toPx) {
                value = org.telegram.messenger.AndroidUtilities.dp(value);
            }
            if (forum) {
                value = (((int) value) * 42) >> 6;
            }
            return (int) java.lang.Math.ceil(value);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static float getAvatarSquareness() {
        try {
            float v = 1.0f - (app.nimarkogram.messenger.NimarkoConfig.avatarCorners / 28.0f);
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        } catch (Throwable t) {
            return 0f;
        }
    }

    public static void setAvatarCorners(float v) {
        try { app.nimarkogram.messenger.NimarkoConfig.setAvatarCorners(v); } catch (Throwable t) {   }
    }

    public static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    public static com.google.gson.Gson getGSON() {
        return GSON;
    }

    public static boolean getDisableNumberRounding() {
        try { return app.nimarkogram.messenger.NimarkoConfig.noRounding; } catch (Throwable t) { return false; }
    }

    public static boolean getFormatTimeWithSeconds() {
        try { return app.nimarkogram.messenger.NimarkoConfig.showSeconds; } catch (Throwable t) { return false; }
    }

    public static boolean getInAppVibration() {
        try { return !app.nimarkogram.messenger.NimarkoConfig.disableVibration; } catch (Throwable t) { return false; }
    }

    public static int getDoubleTapSeekDuration() {
        try { return app.nimarkogram.messenger.NimarkoConfig.videoSeekDuration; } catch (Throwable t) { return 0; }
    }

    public static boolean getUseSystemFonts() {
        try { return app.nimarkogram.messenger.NimarkoConfig.systemFonts; } catch (Throwable t) { return false; }
    }

    public static boolean getHideStories() {
        try { return app.nimarkogram.messenger.NimarkoConfig.hideStories; } catch (Throwable t) { return false; }
    }

    public static String getTargetLang() {
        try { return app.nimarkogram.messenger.NimarkoConfig.translationTarget; } catch (Throwable t) { return "app"; }
    }

    public static void setTargetLang(String v) {
        try { app.nimarkogram.messenger.NimarkoConfig.setTranslationTarget(v); } catch (Throwable t) {   }
    }

    public static int getTranslationProvider() {
        try { return 0; } catch (Throwable t) { return 0; }
    }

    public static int getTitleText() {
        return 0;
    }

    public static boolean getRelativeLastSeen() {
        try { return false; } catch (Throwable t) { return false; }
    }

    public static boolean getFilterZalgo() {
        try { return false; } catch (Throwable t) { return false; }
    }
}
