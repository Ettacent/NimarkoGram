package com.exteragram.messenger.utils;

import com.google.gson.Gson;

public class AppUtils {

    public AppUtils() {}

    public static void log(String message) { logInternal(message, null, 5); }
    public static void log(Throwable t) { logInternal("", t, 5); }
    public static void log(String message, Throwable t) { logInternal(message, t, 5); }

    private static void logInternal(String message, Throwable throwable, int callerDepth) {
        if (throwable != null) {
            app.nimarkogram.messenger.utils.AppUtils.log(message, throwable);
        } else {
            app.nimarkogram.messenger.utils.AppUtils.log(message);
        }
    }

    public static Gson getGson() { return app.nimarkogram.messenger.utils.AppUtils.getGson(); }

    public static void ensureRunningOnUi(Runnable r) { app.nimarkogram.messenger.utils.AppUtils.ensureRunningOnUi(r); }

    public static String getVersionText() { return app.nimarkogram.messenger.utils.AppUtils.getVersionText(); }

    public static boolean isAppModified() { return app.nimarkogram.messenger.utils.AppUtils.isAppModified(); }
    public static boolean isWinter() { return app.nimarkogram.messenger.utils.AppUtils.isWinter(); }

    public static int compareVersionValues(String first, String second) {
        String[] left = first != null ? first.split("\\.") : new String[0];
        String[] right = second != null ? second.split("\\.") : new String[0];
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? parseVersionPart(left[i]) : 0;
            int b = i < right.length ? parseVersionPart(right[i]) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    private static int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9].*$", ""));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int getNotificationColor() {
        int color = org.telegram.ui.ActionBar.Theme.getColor(
                org.telegram.ui.ActionBar.Theme.key_actionBarDefault) | 0xff000000;
        float brightness = org.telegram.messenger.AndroidUtilities.computePerceivedBrightness(color);
        if (brightness >= 0.721f || brightness <= 0.279f) {
            return org.telegram.ui.ActionBar.Theme.getColor(
                    org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueHeader)
                    | 0xff000000;
        }
        return color;
    }

    public static int getNotificationIconColor() {
        return getNotificationColor();
    }

    public static int getSwipeVelocity() {
        android.graphics.Point size = org.telegram.messenger.AndroidUtilities.displaySize;
        return size.x > size.y ? 1250 : 850;
    }

    public static void printObjectDetails(Object value) {
        if (value == null) return;
        try {
            logInternal(value.getClass().getName() + ": " + getGson().toJson(value), null, 6);
        } catch (Throwable error) {
            logInternal(value.getClass().getName(), error, 6);
        }
    }

    public static String stackTraceToString(Throwable t) { return app.nimarkogram.messenger.utils.AppUtils.stackTraceToString(t); }

    public static boolean compareVersions(String op, String v1, String v2) {
        return app.nimarkogram.messenger.utils.AppUtils.compareVersions(op, v1, v2);
    }

    public static boolean compareVersions(String op, int v1, int v2) {
        return app.nimarkogram.messenger.utils.AppUtils.compareVersions(op, v1, v2);
    }

    public static Object getPrivateField(Object obj, String fieldName) {
        return app.nimarkogram.messenger.utils.AppUtils.getPrivateField(obj, fieldName);
    }

    public static void setPrivateField(Object obj, String fieldName, Object value) {
        app.nimarkogram.messenger.utils.AppUtils.setPrivateField(obj, fieldName, value);
    }

    public static Object getPrivateStaticField(Class<?> clazz, String fieldName) {
        return app.nimarkogram.messenger.utils.AppUtils.getPrivateStaticField(clazz, fieldName);
    }

    public static void setPrivateStaticField(Class<?> clazz, String fieldName, Object value) {
        app.nimarkogram.messenger.utils.AppUtils.setPrivateStaticField(clazz, fieldName, value);
    }
}
