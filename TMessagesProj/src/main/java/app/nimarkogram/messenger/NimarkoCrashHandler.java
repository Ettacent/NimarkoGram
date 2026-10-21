/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageInfo;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import app.nimarkogram.messenger.diagnostics.CrashDiagnostics;
import android.content.Context;
import android.os.Build;
import android.os.Environment;

import app.nimarkogram.messenger.plugins.utils.PluginCrashReports;
import app.nimarkogram.messenger.plugins.utils.PluginsWatchdog;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class NimarkoCrashHandler {
    private static volatile boolean installed = false;
    private static volatile Context appContext;
    private static final AtomicLong reportSequence = new AtomicLong();

    public static synchronized void install(Context ctx) {
        if (ctx != null) {
            try {
                Context application = ctx.getApplicationContext();
                appContext = application != null ? application : ctx;
            } catch (Throwable ignored) {
                appContext = ctx;
            }
        }
        if (installed || appContext == null) return;
        final Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            try { CrashDiagnostics.beginFatal(); } catch (Throwable ignored) {}
            String pluginId = null;
            try {
                if (!isOom(throwable)) {
                    pluginId = PluginsWatchdog.findCrashingPlugin(thread, throwable);
                }
                if (pluginId != null) {

                    appContext.getSharedPreferences("plugin_settings", Context.MODE_PRIVATE).edit()
                            .putString("pending_plugin_fatal_id", pluginId)
                            .putLong("pending_plugin_fatal_at", System.currentTimeMillis()).commit();
                }
            } catch (Throwable ignored) {}
            try {
                dump(thread, throwable, pluginId);
            } catch (Throwable ignored) {}
            if (prior != null) {
                prior.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        installed = true;
    }

    public static void dump(Thread thread, Throwable t) {
        String pluginId = null;
        try {
            if (!isOom(t)) pluginId = PluginsWatchdog.findCrashingPlugin(thread, t);
        } catch (Throwable ignored) {}
        dump(thread, t, pluginId);
    }

    private static void dump(Thread thread, Throwable t, String pluginId) {
        final boolean oom = isOom(t);
        String legacyReportId = "unavailable";
        File writtenReport = null;
        try {
            File dir = getLogDir();
            if (dir == null) return;
            if (pluginId != null) {
                dir = ensureLogDir(new File(dir, "plugin-crashes"));
                if (dir == null) return;
            }
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
            long threadId = thread != null ? thread.getId() : Thread.currentThread().getId();
            String reportId = ts + "-p" + android.os.Process.myPid()
                    + "-t" + threadId + "-" + reportSequence.incrementAndGet();
            legacyReportId = reportId;
            File f = new File(dir, (pluginId == null ? "crash-" : "plugin-crash-") + reportId + ".txt");

            try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
                if (pluginId != null) {
                    pw.println("Plugin error report — share with the plugin developer");
                    pw.println();
                }
                pw.println(pluginId == null ? "=== NimarkoGram crash ===" : "=== NimarkoGram plugin crash ===");
                if (pluginId != null) {
                    PluginCrashReports.writeIdentity(pw, pluginId);
                    pw.println("Attribution: tracked callback, matching throwable or registered DEX origin");
                    pw.println("Client process terminated: yes");
                }
                pw.println("Time: " + new Date());
                pw.println("Thread: " + (thread != null ? thread.getName() : "(null)"));
                pw.println("Build: " + Build.MODEL + " / Android " + Build.VERSION.SDK_INT);

                try {
                    pw.println("App: " + NimarkoConfig.APP_NAME + " " + NimarkoConfig.VERSION_NAME
                            + " (code " + versionCode() + ")");
                } catch (Throwable ignored) {
                    pw.println("App: (unavailable)");
                }
                try {
                    Runtime rt = Runtime.getRuntime();
                    long mb = 1048576L;
                    long total = rt.totalMemory() / mb;
                    long free = rt.freeMemory() / mb;
                    pw.println("Heap: used " + (total - free) + "M / " + total + "M (max "
                            + (rt.maxMemory() / mb) + "M), native "
                            + (Debug.getNativeHeapAllocatedSize() / mb) + "M");
                } catch (Throwable ignored) {
                }
                if (oom) {
                    try {
                        pw.println();
                        pw.println("=== OOM diagnostics ===");
                        pw.println("Uptime: " + (SystemClock.uptimeMillis() / 60000) + " min");
                        pw.println("GC: count=" + Debug.getRuntimeStat("art.gc.gc-count")
                                + ", freed=" + Debug.getRuntimeStat("art.gc.bytes-freed")
                                + ", blocking=" + Debug.getRuntimeStat("art.gc.blocking-gc-count"));
                        pw.println("Heap dump: private diagnostic package; completion status follows below.");
                    } catch (Throwable ignored) {
                    }
                }
                pw.println();
                if (t != null) {
                    t.printStackTrace(pw);
                    pw.println();
                    pw.println("=== Caused-by chain ===");
                    Throwable cause = t.getCause();
                    int depth = 0;
                    while (cause != null && cause != t && depth++ < 16) {
                        cause.printStackTrace(pw);
                        pw.println();
                        cause = cause.getCause();
                    }
                    if (cause != null) {
                        pw.println("(cause chain truncated)");
                    }
                } else {
                    pw.println("(throwable was null)");
                }
                CrashDiagnostics.writeContext(pw);
                pw.println("=== End ===");
                pw.flush();
                if (pw.checkError()) {
                    throw new IllegalStateException("Unable to write crash report");
                }
            }
            writtenReport = f;
            if (pluginId != null) PluginCrashReports.rememberFatalReport(pluginId, f);

            Log.e("nimarko-crash", "report written to " + f.getAbsolutePath());

            if (!oom) {
                String preview = readReportPreview(f, 64 * 1024);
                if (preview != null) {
                    Log.e("nimarko-crash", preview);
                    copyToClipboard(preview);
                }
            }
        } catch (Throwable ignored) {
            try {
                Log.e("nimarko-crash", "failed to dump", ignored);
            } catch (Throwable ignored2) {}
        } finally {
            try {
                String result = CrashDiagnostics.captureFatal(legacyReportId, t, oom);
                if (writtenReport != null) {
                    try (PrintWriter output = new PrintWriter(new FileWriter(writtenReport, true))) {
                        output.println("Diagnostic package: " + result);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static String readReportPreview(File file, int maxChars) {
        if (file == null || maxChars <= 0) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder result = new StringBuilder(Math.min(maxChars, 8192));
            char[] buffer = new char[2048];
            int remaining = maxChars;
            while (remaining > 0) {
                int count = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) break;
                result.append(buffer, 0, count);
                remaining -= count;
            }
            if (reader.read() >= 0) {
                result.append("\n[report preview truncated; full report is in nimarko-logs]");
            }
            return result.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void copyToClipboard(String text) {
        try {
            Context context = appContext;
            if (context == null) return;
            ClipboardManager cm =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        text.contains("=== NimarkoGram plugin crash ===") ? "NimarkoGram plugin crash" : "NimarkoGram crash", text));
            }
        } catch (Throwable ignored) {}
    }

    public static File getLogDir() {
        Context context = appContext;
        if (context != null) {
            try {
                File ext = context.getExternalFilesDir(null);
                if (ext != null) {
                    File dir = ensureLogDir(new File(ext, "nimarko-logs"));
                    if (dir != null) return dir;
                }
            } catch (Throwable ignored) {
            }

            try {
                File files = context.getFilesDir();
                if (files != null) {
                    File dir = ensureLogDir(new File(files, "nimarko-logs"));
                    if (dir != null) return dir;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            File legacy = ensureLogDir(
                    new File(Environment.getExternalStorageDirectory(), "NimarkoGram/logs"));
            if (legacy != null) return legacy;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File ensureLogDir(File dir) {
        if (dir == null) return null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return null;
            }
            return dir.isDirectory() && dir.canWrite() ? dir : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long versionCode() {
        try {
            Context context = appContext;
            if (context == null) return -1;
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isOom(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 64) {
            if (cur instanceof OutOfMemoryError) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private NimarkoCrashHandler() {}
}
