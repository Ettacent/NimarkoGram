package app.nimarkogram.messenger.utils;

import com.google.gson.Gson;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.lang.reflect.Field;

public final class AppUtils {
    private static final Gson GSON = new Gson();

    private AppUtils() {}

    public static void log(String message) { FileLog.d("nimarko-plugin: " + message); }
    public static void log(Throwable t) { FileLog.e(t); }
    public static void log(String message, Throwable t) { FileLog.e("nimarko-plugin: " + message, t); }

    public static Gson getGson() { return GSON; }

    public static void ensureRunningOnUi(Runnable r) {
        if (r == null) return;
        AndroidUtilities.runOnUIThread(r);
    }

    public static String getVersionText() { return BuildVars.BUILD_VERSION_STRING; }

    public static boolean isAppModified() { return false; }
    public static boolean isWinter() { return false; }

    public static String stackTraceToString(Throwable t) {
        if (t == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    public static boolean compareVersions(String op, String v1, String v2) {
        int cmp;
        if (SharedConfig.versionBiggerOrEqual(v1, v2)) {
            cmp = SharedConfig.versionBiggerOrEqual(v2, v1) ? 0 : 1; 
        } else {
            cmp = -1;
        }
        return applyOp(op, cmp);
    }

    public static boolean compareVersions(String op, int v1, int v2) {
        return applyOp(op, Integer.compare(v1, v2));
    }

    private static boolean applyOp(String op, int cmp) {
        if (op == null) return cmp == 0;
        switch (op) {
            case "==": return cmp == 0;
            case "!=": return cmp != 0;
            case ">":  return cmp > 0;
            case ">=": return cmp >= 0;
            case "<":  return cmp < 0;
            case "<=": return cmp <= 0;
            default:   return cmp == 0;
        }
    }

    public static Object getPrivateField(Object obj, String fieldName) {
        if (obj == null || fieldName == null) return null;
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) { FileLog.e(t); return null; }
    }

    public static void setPrivateField(Object obj, String fieldName, Object value) {
        if (obj == null || fieldName == null) return;
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable t) { FileLog.e(t); }
    }

    public static Object getPrivateStaticField(Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null) return null;
        try {
            Field f = findField(clazz, fieldName);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) { FileLog.e(t); return null; }
    }

    public static void setPrivateStaticField(Class<?> clazz, String fieldName, Object value) {
        if (clazz == null || fieldName == null) return;
        try {
            Field f = findField(clazz, fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable t) { FileLog.e(t); }
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
