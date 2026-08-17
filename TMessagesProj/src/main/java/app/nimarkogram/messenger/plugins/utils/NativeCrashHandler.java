package app.nimarkogram.messenger.plugins.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import app.nimarkogram.messenger.NimarkoCrashHandler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public final class NativeCrashHandler {
    private static final String CRASH_FLAG_FILENAME = "native_crash.flag";
    private static final String DIAGNOSTICS_PREFS = "nimarko_crash_diagnostics";
    private static final String LAST_RECORDED_EXIT_TIMESTAMP = "last_recorded_exit_timestamp";
    private static final AtomicBoolean DIAGNOSTICS_SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean DIAGNOSTICS_HANDLED = new AtomicBoolean();
    private static final Object EXIT_INFO_LOCK = new Object();
    private static volatile boolean exitInfoLoaded;
    private static volatile ApplicationExitInfo cachedExitInfo;

    public static void schedulePreviousExitDiagnostics() {
        if (!isSupportedMainProcess()
                || !DIAGNOSTICS_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread worker = new Thread(() -> {
                try {
                    checkAndHandleNativeCrash();
                } catch (Throwable t) {
                    FileLog.e("nimarko: previous-exit diagnostics failed", t);
                }
            }, "ng-exit-diagnostics");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            DIAGNOSTICS_SCHEDULED.set(false);
            FileLog.e("nimarko: unable to schedule previous-exit diagnostics", t);
        }
    }

    public static void checkAndHandleNativeCrash() {
        if (!isSupportedMainProcess()
                || !DIAGNOSTICS_HANDLED.compareAndSet(false, true)) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;

        File file = new File(ApplicationLoader.getFilesDirFixed(), CRASH_FLAG_FILENAME);
        boolean legacyFlagSeen = file.exists();
        if (legacyFlagSeen && !file.delete()) {
            FileLog.w("nimarko: unable to remove stale native crash flag");
        }

        ApplicationExitInfo exit = lastExitInfo();
        if (!isDiagnosticExit(exit)) return;

        long timestamp = exit.getTimestamp();
        SharedPreferences diagnostics = context.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE);
        if (timestamp <= diagnostics.getLong(LAST_RECORDED_EXIT_TIMESTAMP, 0L)) return;

        diagnostics.edit()
                .putLong(LAST_RECORDED_EXIT_TIMESTAMP, timestamp)
                .putInt("last_exit_reason", exit.getReason())
                .putInt("last_exit_status", exit.getStatus())
                .putInt("last_exit_importance", exit.getImportance())
                .putLong("last_exit_pss", exit.getPss())
                .putLong("last_exit_rss", exit.getRss())
                .putString("last_exit_process", valueOrEmpty(exit.getProcessName()))
                .putString("last_exit_description", valueOrEmpty(exit.getDescription()))
                .putBoolean("legacy_native_flag_seen", legacyFlagSeen)
                .commit();

        writeExitReport(exit, legacyFlagSeen);
        FileLog.e("nimarko: captured previous process exit: "
                + reasonName(exit.getReason()) + " status=" + exit.getStatus());
    }

    private static ApplicationExitInfo lastExitInfo() {
        if (exitInfoLoaded) {
            return cachedExitInfo;
        }
        synchronized (EXIT_INFO_LOCK) {
            if (exitInfoLoaded) {
                return cachedExitInfo;
            }
            ApplicationExitInfo result = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Context ctx = ApplicationLoader.applicationContext;
                    if (ctx != null) {
                        ActivityManager am = (ActivityManager) ctx.getSystemService(
                                Context.ACTIVITY_SERVICE);
                        if (am != null) {
                            List<ApplicationExitInfo> infos =
                                    am.getHistoricalProcessExitReasons(
                                            ctx.getPackageName(), 0, 8);
                            if (infos != null && !infos.isEmpty()) {
                                String mainProcess = ctx.getPackageName();
                                for (ApplicationExitInfo info : infos) {
                                    if (info != null
                                            && mainProcess.equals(info.getProcessName())) {
                                        result = info;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            cachedExitInfo = result;
            exitInfoLoaded = true;
            return result;
        }
    }

    private static boolean isSupportedMainProcess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        String currentProcess = android.app.Application.getProcessName();
        return currentProcess == null
                || context.getPackageName().equals(currentProcess);
    }

    private static boolean isDiagnosticExit(ApplicationExitInfo info) {
        if (info == null) return false;
        int reason = info.getReason();
        return isCrashExit(info)
                || reason == ApplicationExitInfo.REASON_SIGNALED
                || reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_LOW_MEMORY
                || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE;
    }

    private static boolean isCrashExit(ApplicationExitInfo info) {
        if (info == null) return false;
        int reason = info.getReason();
        if (reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE) return true;
        if (reason != ApplicationExitInfo.REASON_SIGNALED) return false;
        
        int signal = info.getStatus();
        return signal == 4   
                || signal == 6   
                || signal == 7   
                || signal == 8   
                || signal == 11  
                || signal == 31; 
    }

    private static boolean isNativeCrashExit(ApplicationExitInfo info) {
        if (info == null) return false;
        if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) return true;
        if (info.getReason() != ApplicationExitInfo.REASON_SIGNALED) return false;
        int signal = info.getStatus();
        return signal == 4 || signal == 6 || signal == 7
                || signal == 8 || signal == 11 || signal == 31;
    }

    public static boolean lastExitWasLoadCrashAfter(long loadStartedAtMs) {
        if (loadStartedAtMs <= 0L) return false;
        ApplicationExitInfo info = lastExitInfo();
        
        if (!isNativeCrashExit(info)) return false;
        long exitAt = info.getTimestamp();
        return exitAt >= loadStartedAtMs && exitAt - loadStartedAtMs <= 10 * 60_000L;
    }

    public static boolean conservativePre30LoadCrash(long loadStartedAtMs) {
        
        return false;
    }

    public static boolean lastExitWasBenignKill() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false; 
            return lastExitWasBenignKillR();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean lastExitWasBenignKillR() {
        ApplicationExitInfo info = lastExitInfo();
        if (info == null) return false;
        int reason = info.getReason();
        return reason == ApplicationExitInfo.REASON_LOW_MEMORY
                || reason == ApplicationExitInfo.REASON_USER_REQUESTED
                
                || (reason == ApplicationExitInfo.REASON_SIGNALED && info.getStatus() == 9);
    }

    private static void writeExitReport(ApplicationExitInfo info, boolean legacyFlagSeen) {
        try {
            File dir = NimarkoCrashHandler.getLogDir();
            if (dir == null) return;
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                    .format(new Date(info.getTimestamp()));
            File report = new File(dir, "previous-exit-" + timestamp + ".txt");
            try (PrintWriter writer = new PrintWriter(
                    new BufferedWriter(new FileWriter(report)))) {
                writer.println("=== NimarkoGram previous process exit ===");
                writer.println("Time: " + new Date(info.getTimestamp()));
                writer.println("Process: " + valueOrEmpty(info.getProcessName()));
                writer.println("Reason: " + reasonName(info.getReason())
                        + " (" + info.getReason() + ")");
                writer.println("Status: " + info.getStatus());
                writer.println("Importance: " + info.getImportance());
                writer.println("PSS: " + info.getPss() + " KB");
                writer.println("RSS: " + info.getRss() + " KB");
                writer.println("Description: " + valueOrEmpty(info.getDescription()));
                writer.println("Legacy native flag seen: " + legacyFlagSeen);
                writer.println("Plugin attribution: none (diagnostics only)");
                writer.println("=== End ===");
            }
        } catch (Throwable t) {
            try {
                android.util.Log.e("nimarko-crash", "failed to write previous-exit report", t);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH:
                return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER:
            default:
                return "OTHER";
        }
    }

    private NativeCrashHandler() {
    }
}
