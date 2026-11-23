/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.messenger;

import static org.telegram.messenger.ApplicationLoader.applicationContext;
import org.telegram.messenger.ApplicationLoader.PineRuntimeProbe;
import static app.nimarkogram.messenger.CrashContext.initializationPhase;
import app.nimarkogram.messenger.CrashContext.PineInitPhase;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

final class PluginRuntimeBootstrap {
    private PluginRuntimeBootstrap() {}
    private static final String NG_PINE_RUNTIME_PREFS = "nimarko_pine_runtime";
    private static final String NG_PINE_INIT_SIGNATURE = "init_signature";
    private static final String NG_PINE_INIT_STARTED_AT = "init_started_at";
    private static final String NG_PINE_INIT_PID = "init_pid";
    private static final String NG_PINE_BLOCKED_SIGNATURE = "blocked_signature";
    private static boolean ngPineRecoveryPending;
    private static final long NG_PINE_HOOK_WAIT_BUDGET_MS = 30_000L;
    private static volatile boolean ngPineRuntimeGuardInstalled = false;
    private static volatile boolean ngPineRecoveryChecked = false;
    private static volatile boolean ngPineBlockedByRecovery = false;
    private static volatile boolean ngPineInitAttempted = false;
    private static volatile boolean ngPineInited = false;
    private static volatile long ngPineHookWaitDeadline;
    private static volatile String ngPineUnavailableReason;
    public static final java.util.concurrent.CountDownLatch ngPineReady = new java.util.concurrent.CountDownLatch(1);

    private static boolean isPineRecoveryMainProcess(Context context) {
        if (context == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return context.getPackageName().equals(Application.getProcessName());
            }
            android.app.ActivityManager manager = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                    manager.getRunningAppProcesses();
            if (processes != null) {
                int pid = android.os.Process.myPid();
                for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process != null && process.pid == pid) {
                        return context.getPackageName().equals(process.processName);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static SharedPreferences pineRuntimePreferences() {
        Context context = applicationContext;
        if (!isPineRecoveryMainProcess(context)) return null;
        try {
            return context.getSharedPreferences(NG_PINE_RUNTIME_PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pineArtModuleIdentity(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "platform";
        }

        String[] packageNames = {
                "com.google.android.art",
                "com.android.art",
                "com.android.runtime"
        };
        for (String packageName : packageNames) {
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(
                        packageName, android.content.pm.PackageManager.MATCH_APEX);
                if (info != null) {
                    long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? info.getLongVersionCode() : info.versionCode;
                    return packageName + ":" + versionCode + ":"
                            + (info.versionName == null ? "" : info.versionName);
                }
            } catch (Throwable ignored) {
            }
        }
        return "unknown";
    }

    private static String pineRuntimeSignature() {
        Context context = applicationContext;
        long lastUpdateTime = 0L;
        if (context != null) {
            try {
                PackageInfo info = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0);
                if (info != null) lastUpdateTime = info.lastUpdateTime;
            } catch (Throwable ignored) {
            }
        }
        String fingerprint;
        try {
            fingerprint = Build.FINGERPRINT;
        } catch (Throwable ignored) {
            fingerprint = Build.VERSION.INCREMENTAL;
        }

        return Build.VERSION.SDK_INT + ":" + fingerprint + ":"
                + pineArtModuleIdentity(context) + ":" + lastUpdateTime;
    }

    private static synchronized void preparePineRecoveryGuard() {
        if (ngPineRecoveryChecked) return;
        SharedPreferences preferences = pineRuntimePreferences();
        if (preferences == null) {

            return;
        }
        String signature = pineRuntimeSignature();
        String blockedSignature = preferences.getString(NG_PINE_BLOCKED_SIGNATURE, null);
        SharedPreferences.Editor editor = preferences.edit()
                .remove(NG_PINE_INIT_SIGNATURE)
                .remove(NG_PINE_INIT_STARTED_AT)
                .remove(NG_PINE_INIT_PID);
        if (signature.equals(blockedSignature)) {
            ngPineBlockedByRecovery = true;
            ngPineUnavailableReason =
                    "disabled after a native Pine initialization crash in this build";
        } else {
            String initSignature = preferences.getString(NG_PINE_INIT_SIGNATURE, null);
            long initStartedAt = preferences.getLong(NG_PINE_INIT_STARTED_AT, 0L);
            int initPid = preferences.getInt(NG_PINE_INIT_PID, 0);
            app.nimarkogram.messenger.plugins.utils.NativeCrashHandler.LoadCrashEvidence evidence =
                    app.nimarkogram.messenger.plugins.utils.NativeCrashHandler.LoadCrashEvidence.NO_MATCH;
            if (signature.equals(initSignature) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                evidence = app.nimarkogram.messenger.plugins.utils.NativeCrashHandler
                        .loadCrashEvidence(initStartedAt, initPid);
            }
            if (evidence == app.nimarkogram.messenger.plugins.utils.NativeCrashHandler.LoadCrashEvidence.UNKNOWN) {
                ngPineRecoveryPending = true;
                return;
            }
            boolean confirmedNativeInitCrash = evidence
                    == app.nimarkogram.messenger.plugins.utils.NativeCrashHandler.LoadCrashEvidence.MATCH;
            if (confirmedNativeInitCrash) {
                ngPineBlockedByRecovery = true;
                ngPineUnavailableReason =
                        "disabled after a native Pine initialization crash in this build";
                editor.putString(NG_PINE_BLOCKED_SIGNATURE, signature);
                FileLog.e("nimarko: Pine recovery guard blocked a confirmed native init crash loop");
            } else {

                editor.remove(NG_PINE_BLOCKED_SIGNATURE);
            }
        }
        if (!editor.commit()) {
            ngPineRecoveryPending = true;
            return;
        }
        ngPineRecoveryPending = false;
        ngPineRecoveryChecked = true;
    }

    private static boolean markPineInitializationStarted() {
        SharedPreferences preferences = pineRuntimePreferences();
        if (preferences == null) return false;
        try {
            return preferences.edit()
                    .putString(NG_PINE_INIT_SIGNATURE, pineRuntimeSignature())
                    .putLong(NG_PINE_INIT_STARTED_AT, System.currentTimeMillis())
                    .putInt(NG_PINE_INIT_PID, android.os.Process.myPid())
                    .commit();
        } catch (RuntimeException failure) {
            FileLog.e(failure);
            return false;
        }
    }

    private static void clearPineInitializationMarker(boolean initialized) {
        SharedPreferences preferences = pineRuntimePreferences();
        if (preferences == null) return;
        if (preferences.getInt(NG_PINE_INIT_PID, 0) != android.os.Process.myPid()) return;
        SharedPreferences.Editor editor = preferences.edit()
                .remove(NG_PINE_INIT_SIGNATURE)
                .remove(NG_PINE_INIT_STARTED_AT)
                .remove(NG_PINE_INIT_PID);
        if (initialized) editor.remove(NG_PINE_BLOCKED_SIGNATURE);
        editor.commit();
    }

    static synchronized void installPineRuntimeGuardIfNeeded() {
        installPineRuntimeGuardIfNeeded(false);
    }

    private static synchronized void installPineRuntimeGuardIfNeeded(boolean finalAttempt) {
        preparePineRecoveryGuard();
        if (ngPineRecoveryPending && finalAttempt) preparePineRecoveryGuard();
        boolean unavailableHistory = ngPineRecoveryPending && finalAttempt;
        if ((!ngPineBlockedByRecovery && !unavailableHistory)
                || ngPineRuntimeGuardInstalled) {
            return;
        }
        final String reason = ngPineBlockedByRecovery
                ? "Pine recovery guard is active for this build"
                : "Pine initialization deferred: previous exit could not be verified";
        try {
            top.canyie.pine.PineConfig.sdkLevel = Build.VERSION.SDK_INT;
            top.canyie.pine.PineConfig.disableHooks = true;
            top.canyie.pine.PineConfig.libLoader = () -> {
                throw new UnsupportedOperationException(reason);
            };
        } catch (Throwable t) {
            FileLog.e("nimarko: unable to install Pine recovery guard", t);
        } finally {
            ngPineRuntimeGuardInstalled = true;
            ngPineInitAttempted = true;
            ngPineInited = false;
            ngPineUnavailableReason = reason;
            ngPineReady.countDown();
        }
    }

    public static synchronized void ensurePineInited() {
        installPineRuntimeGuardIfNeeded(true);
        if (ngPineInited || ngPineInitAttempted) return;
        ngPineInitAttempted = true;
        ngPineHookWaitDeadline =
                SystemClock.elapsedRealtime() + NG_PINE_HOOK_WAIT_BUDGET_MS;
        boolean pineReady = false;
        if (!markPineInitializationStarted()) {
            ngPineUnavailableReason = "Pine initialization deferred: recovery marker could not be saved";
            top.canyie.pine.PineConfig.disableHooks = true;
            top.canyie.pine.PineConfig.libLoader = () -> {
                throw new IllegalStateException("Pine recovery marker could not be saved");
            };
            ngPineReady.countDown();
            return;
        }
        app.nimarkogram.messenger.CrashContext.initialization(true, false);
        try {

            boolean hiddenApiBypassReady = false;
            initializationPhase(PineInitPhase.HIDDEN_API_BEGIN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    hiddenApiBypassReady =
                            org.lsposed.hiddenapibypass.HiddenApiBypass
                                    .addHiddenApiExemptions("L");
                } catch (Throwable bypassError) {
                    FileLog.w("nimarko: HiddenApiBypass preflight failed: " + bypassError);
                }
                if (!hiddenApiBypassReady) {
                    FileLog.w("nimarko: hidden APIs remain enforced on this runtime");
                }
            }

            initializationPhase(PineInitPhase.HIDDEN_API_END);
            initializationPhase(PineInitPhase.CONFIG_BEGIN);
            top.canyie.pine.PineConfig.sdkLevel = android.os.Build.VERSION.SDK_INT;
            top.canyie.pine.PineConfig.debug = false;
            try {
                top.canyie.pine.PineConfig.debuggable = applicationContext != null
                        && (applicationContext.getApplicationInfo().flags
                            & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            } catch (Throwable ignore) { top.canyie.pine.PineConfig.debuggable = false; }
            top.canyie.pine.PineConfig.disableHooks = false;

            top.canyie.pine.PineConfig.disableHiddenApiPolicy = false;
            top.canyie.pine.PineConfig.disableHiddenApiPolicyForPlatformDomain = false;
            app.nimarkogram.messenger.plugins.bridge.ManagedHookBridge.install(applicationContext);
            initializationPhase(PineInitPhase.CONFIG_END);
            initializationPhase(PineInitPhase.ENGINE_BEGIN);
            top.canyie.pine.Pine.ensureInitialized();
            initializationPhase(PineInitPhase.ENGINE_END);
            if (!top.canyie.pine.Pine.isInitialized()) {
                ngPineUnavailableReason = "Pine native initialization did not complete";
                FileLog.w("nimarko: Pine native initialisation did not complete");
                return;
            }

            initializationPhase(PineInitPhase.HOOK_MODE_BEGIN);
            try {
                top.canyie.pine.Pine.setHookMode(top.canyie.pine.Pine.HookMode.REPLACEMENT);
            } catch (Throwable hm) {
                throw new IllegalStateException(
                        "Pine replacement hook mode is unavailable", hm);
            }
            if (top.canyie.pine.Pine.getHookMode()
                    != top.canyie.pine.Pine.HookMode.REPLACEMENT) {
                throw new IllegalStateException(
                        "Pine did not enter replacement hook mode");
            }
            initializationPhase(PineInitPhase.HOOK_MODE_END);

            if (BuildConfig.DEBUG) {
                if (!verifyPineRuntimeHook()) {
                    throw new IllegalStateException("Pine runtime hook smoke test failed");
                }
            } else {
                initializationPhase(PineInitPhase.BACKEND_READY_HOOKS_UNVERIFIED);
                FileLog.w("nimarko: Pine startup diagnostic probe skipped; "
                        + "backend initialized, runtime hook compatibility unverified");
            }
            pineReady = top.canyie.pine.Pine.isInitialized();
            ngPineUnavailableReason = pineReady
                    ? null : "Pine reported an uninitialized backend";
            org.telegram.messenger.FileLog.d("nimarko: Pine initialised (lazy), sdkLevel="
                    + top.canyie.pine.PineConfig.sdkLevel
                    + ", initialised=" + pineReady
                    + ", hookMode=" + top.canyie.pine.Pine.getHookMode());
        } catch (Throwable t) {
            app.nimarkogram.messenger.CrashContext.failure(
                    "pine", null, "ApplicationLoader.ensurePineInited", t);
            ngPineUnavailableReason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            org.telegram.messenger.FileLog.e("nimarko: Pine init failed", t);
        } finally {

            ngPineInited = pineReady;
            app.nimarkogram.messenger.CrashContext.initialization(false, pineReady);
            if (!pineReady) {
                try {
                    top.canyie.pine.PineConfig.disableHooks = true;
                } catch (Throwable ignored) {
                }
            }
            clearPineInitializationMarker(pineReady);
            ngPineReady.countDown();
        }
    }

    private static boolean verifyPineRuntimeHook() {
        top.canyie.pine.callback.MethodHook.Unhook staticUnhook = null;
        top.canyie.pine.callback.MethodHook.Unhook mixedUnhook = null;
        top.canyie.pine.callback.MethodHook.Unhook constructorUnhook = null;
        boolean passed = false;
        try {
            initializationPhase(PineInitPhase.STATIC_PREPARE);
            final int staticInput = 0x4E47;
            java.lang.reflect.Method staticProbe = ApplicationLoader.class
                    .getDeclaredMethod("pineRuntimeStaticProbe", int.class);
            staticProbe.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean staticBefore =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean staticAfter =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            top.canyie.pine.callback.MethodHook staticCallback =
                    new top.canyie.pine.callback.MethodHook() {
                        @Override
                        public void beforeCall(top.canyie.pine.Pine.CallFrame frame) {
                            boolean valid = frame != null
                                    && frame.thisObject == null
                                    && frame.args != null
                                    && frame.args.length == 1
                                    && frame.args[0] instanceof Integer;
                            staticBefore.set(valid);
                            if (valid) {
                                frame.args[0] = staticInput;
                            }
                        }

                        @Override
                        public void afterCall(top.canyie.pine.Pine.CallFrame frame) {
                            boolean valid = frame != null
                                    && frame.getResult() instanceof Integer
                                    && ((Integer) frame.getResult())
                                    == staticInput + 1;
                            staticAfter.set(valid);
                            if (valid) {
                                frame.setResult(staticInput + 2);
                            }
                        }
                    };
            initializationPhase(PineInitPhase.STATIC_HOOK_BEGIN);
            staticUnhook = top.canyie.pine.Pine.hook(
                    staticProbe, staticCallback);
            initializationPhase(PineInitPhase.STATIC_HOOK_END);
            initializationPhase(PineInitPhase.STATIC_CALL_BEGIN);
            Object staticResult = staticProbe.invoke(null, 1);
            initializationPhase(PineInitPhase.STATIC_CALL_END);

            initializationPhase(PineInitPhase.MIXED_PREPARE);
            Object marker = new Object();
            ApplicationLoader.PineRuntimeProbe receiver = new ApplicationLoader.PineRuntimeProbe();
            java.lang.reflect.Method mixedProbe = ApplicationLoader.PineRuntimeProbe.class
                    .getDeclaredMethod(
                            "mixedProbe",
                            Object.class, int.class, long.class,
                            Object.class, int.class, long.class,
                            Object.class, int.class, long.class,
                            Object.class, int.class, long.class,
                            double.class);
            mixedProbe.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean mixedBefore =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean mixedAfter =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            top.canyie.pine.callback.MethodHook mixedCallback =
                    new top.canyie.pine.callback.MethodHook() {
                        @Override
                        public void beforeCall(top.canyie.pine.Pine.CallFrame frame) {
                            boolean valid = frame != null
                                    && frame.thisObject == receiver
                                    && frame.args != null
                                    && frame.args.length == 13
                                    && frame.args[0] == marker
                                    && frame.args[3] == marker
                                    && frame.args[6] == marker
                                    && frame.args[9] == marker;
                            mixedBefore.set(valid);
                            if (valid) {
                                frame.args[1] = 7;
                            }
                        }

                        @Override
                        public void afterCall(top.canyie.pine.Pine.CallFrame frame) {
                            boolean valid = frame != null
                                    && frame.thisObject == receiver
                                    && frame.getResult() instanceof Long
                                    && ((Long) frame.getResult()) == 51L;
                            mixedAfter.set(valid);
                            if (valid) {
                                frame.setResult(52L);
                            }
                        }
                    };
            initializationPhase(PineInitPhase.MIXED_HOOK_BEGIN);
            mixedUnhook = top.canyie.pine.Pine.hook(
                    mixedProbe, mixedCallback);
            initializationPhase(PineInitPhase.MIXED_HOOK_END);
            initializationPhase(PineInitPhase.MIXED_CALL_BEGIN);
            Object mixedResult = mixedProbe.invoke(
                    receiver,
                    marker, 1, 2L,
                    marker, 3, 4L,
                    marker, 5, 6L,
                    marker, 7, 8L,
                    9.0d);
            initializationPhase(PineInitPhase.MIXED_CALL_END);

            initializationPhase(PineInitPhase.CONSTRUCTOR_PREPARE);
            java.lang.reflect.Constructor<ApplicationLoader.PineRuntimeProbe> constructor =
                    ApplicationLoader.PineRuntimeProbe.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean constructorBefore =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean constructorAfter =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            top.canyie.pine.callback.MethodHook constructorCallback =
                    new top.canyie.pine.callback.MethodHook() {
                        @Override
                        public void beforeCall(top.canyie.pine.Pine.CallFrame frame) {
                            boolean valid = frame != null
                                    && frame.thisObject instanceof PineRuntimeProbe
                                    && frame.args != null
                                    && frame.args.length == 1;
                            constructorBefore.set(valid);
                            if (valid) {
                                frame.args[0] = 73;
                            }
                        }

                        @Override
                        public void afterCall(top.canyie.pine.Pine.CallFrame frame) {
                            boolean valid = frame != null
                                    && frame.thisObject instanceof PineRuntimeProbe
                                    && ((PineRuntimeProbe) frame.thisObject)
                                    .constructorValue == 73;
                            constructorAfter.set(valid);
                        }
                    };
            initializationPhase(PineInitPhase.CONSTRUCTOR_HOOK_BEGIN);
            constructorUnhook = top.canyie.pine.Pine.hook(
                    constructor, constructorCallback);
            initializationPhase(PineInitPhase.CONSTRUCTOR_HOOK_END);
            initializationPhase(PineInitPhase.CONSTRUCTOR_CALL_BEGIN);
            PineRuntimeProbe constructed = constructor.newInstance(1);
            initializationPhase(PineInitPhase.CONSTRUCTOR_CALL_END);

            passed = staticBefore.get()
                    && staticAfter.get()
                    && staticResult instanceof Integer
                    && ((Integer) staticResult) == staticInput + 2
                    && mixedBefore.get()
                    && mixedAfter.get()
                    && mixedResult instanceof Long
                    && ((Long) mixedResult) == 52L
                    && constructorBefore.get()
                    && constructorAfter.get()
                    && constructed.constructorValue == 73;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            FileLog.e("nimarko: Pine runtime hook smoke test failed", cause);
        } finally {
            passed &= unhookPineRuntimeProbe(
                    constructorUnhook, "constructor", PineInitPhase.CONSTRUCTOR_UNHOOK_BEGIN,
                    PineInitPhase.CONSTRUCTOR_UNHOOK_END);
            passed &= unhookPineRuntimeProbe(mixedUnhook, "mixed", PineInitPhase.MIXED_UNHOOK_BEGIN,
                    PineInitPhase.MIXED_UNHOOK_END);
            passed &= unhookPineRuntimeProbe(staticUnhook, "static", PineInitPhase.STATIC_UNHOOK_BEGIN,
                    PineInitPhase.STATIC_UNHOOK_END);
        }
        return passed;
    }

    private static boolean unhookPineRuntimeProbe(
            top.canyie.pine.callback.MethodHook.Unhook unhook,
            String name, PineInitPhase begin, PineInitPhase end) {
        if (unhook == null) return true;
        try {
            initializationPhase(begin);
            unhook.unhook();
            initializationPhase(end);
            return true;
        } catch (Throwable t) {
            FileLog.e("nimarko: Pine " + name
                    + " smoke-test unhook failed", t);
            return false;
        }
    }

    public static boolean isPineAvailable() {
        return ngPineInited;
    }

    public static boolean wasPineInitializationAttempted() {
        return ngPineInitAttempted;
    }

    public static String getPineUnavailableReason() {
        return ngPineUnavailableReason;
    }

    public static boolean awaitPineInitializationForHooks() {
        if (ngPineInited || ngPineReady.getCount() == 0L) {
            return ngPineInited;
        }
        long remaining = ngPineHookWaitDeadline - SystemClock.elapsedRealtime();
        if (remaining <= 0L) {
            ngPineUnavailableReason = "Pine initialization exceeded 30 seconds";
            return false;
        }
        try {
            ngPineReady.await(
                    remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ngPineUnavailableReason = "interrupted while waiting for Pine initialization";
        }
        return ngPineInited;
    }

    public static void ngAwaitPine() { ensurePineInited(); }
}
