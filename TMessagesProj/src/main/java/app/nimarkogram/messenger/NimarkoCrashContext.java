/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger;
import android.os.Build;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
public final class NimarkoCrashContext {
    private static final int MAX_VALUE_LENGTH = 160;
    private static final long MIN_UPDATE_INTERVAL_MS = 250L;
    private static final long INIT_RETRY_INTERVAL_MS = 5000L;
    private static final int MAX_INIT_ATTEMPTS = 5;
    private static final long FAILURE_WINDOW_MS = 10000L;
    private static final int MAX_FAILURES = 4;
    private static volatile Object crashlytics;
    private static volatile Method setCustomKey;
    private static volatile Method log;
    private static volatile boolean initialized;
    private static long nextUpdateMs;
    private static long nextInitAttemptMs;
    private static int initAttempts;
    private static boolean emitting;
    private static final String[] failureKeys = new String[MAX_FAILURES];
    private static final long[] failureTimes = new long[MAX_FAILURES];
    private NimarkoCrashContext() {
    }
    public static synchronized void pine(String stage, String target) {
        if (!admitNormal()) return;
        mark("pine", null, stage, target, null);
    }
    public static synchronized void python(String pluginId, String callback, int argumentCount) {
        if (!admitNormal()) return;
        mark("python", pluginId, "callback", callback, "argc=" + argumentCount);
    }
    public static synchronized void failure(String domain, String pluginId, String target, Throwable failure) {
        if (emitting) return;
        long now = android.os.SystemClock.elapsedRealtime();
        int slot = -1;
        for (int i = 0; i < MAX_FAILURES; i++) {
            if (failureKeys[i] == null || now - failureTimes[i] >= FAILURE_WINDOW_MS) {
                slot = i;
            }
        }
        if (slot < 0 || !ensure()) return;
        String safeDomain = safe(domain);
        String safePlugin = safe(pluginId);
        String safeTarget = safe(target);
        String detail = failure == null ? "" : safe(failure.getClass().getName());
        String key = safeDomain.length() + ":" + safeDomain
                + safePlugin.length() + ":" + safePlugin
                + safeTarget.length() + ":" + safeTarget + detail;
        for (int i = 0; i < MAX_FAILURES; i++) {
            if (now - failureTimes[i] < FAILURE_WINDOW_MS && key.equals(failureKeys[i])) return;
        }
        failureKeys[slot] = key;
        failureTimes[slot] = now;
        mark(safeDomain, safePlugin, "failure", safeTarget, detail);
    }
    private static boolean admitNormal() {
        if (emitting) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < nextUpdateMs) return false;
        nextUpdateMs = now + MIN_UPDATE_INTERVAL_MS;
        return ensure();
    }
    private static void mark(String domain, String pluginId, String stage,
            String target, String detail) {
        String safeDomain = safe(domain);
        String safePlugin = safe(pluginId);
        String safeStage = safe(stage);
        String safeTarget = safe(target);
        String safeDetail = safe(detail);
        emitting = true;
        try {
            setCustomKey.invoke(crashlytics, "ng_diag_domain", safeDomain);
            setCustomKey.invoke(crashlytics, "ng_diag_plugin", safePlugin);
            setCustomKey.invoke(crashlytics, "ng_diag_stage", safeStage);
            setCustomKey.invoke(crashlytics, "ng_diag_target", safeTarget);
            setCustomKey.invoke(crashlytics, "ng_diag_detail", safeDetail);
            setCustomKey.invoke(crashlytics, "ng_diag_sdk", String.valueOf(Build.VERSION.SDK_INT));
            log.invoke(crashlytics, "ng_diag domain=" + safeDomain
                    + " stage=" + safeStage + " target=" + safeTarget
                    + (safePlugin.isEmpty() ? "" : " plugin=" + safePlugin)
                    + (safeDetail.isEmpty() ? "" : " detail=" + safeDetail));
        } catch (Throwable ignored) {
        } finally {
            emitting = false;
        }
    }
    private static boolean ensure() {
        if (initialized) return crashlytics != null;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < nextInitAttemptMs) return false;
        nextInitAttemptMs = now + INIT_RETRY_INTERVAL_MS;
        initAttempts++;
        emitting = true;
        try {
            Class<?> type = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics");
            Method keyMethod = type.getMethod("setCustomKey", String.class, String.class);
            Method logMethod = type.getMethod("log", String.class);
            Object instance = type.getMethod("getInstance").invoke(null);
            setCustomKey = keyMethod;
            log = logMethod;
            crashlytics = instance;
            initialized = true;
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : null;
            boolean notReady = cause instanceof IllegalStateException
                    && cause.getMessage() != null
                    && cause.getMessage().startsWith("Default FirebaseApp is not initialized in this process");
            initialized = !notReady || initAttempts >= MAX_INIT_ATTEMPTS;
        } finally {
            emitting = false;
        }
        return crashlytics != null;
    }
    private static String safe(String value) {
        if (value == null) return "";
        String result = value.replace('\n', ' ').replace('\r', ' ');
        return result.length() <= MAX_VALUE_LENGTH
                ? result : result.substring(0, MAX_VALUE_LENGTH);
    }
}
