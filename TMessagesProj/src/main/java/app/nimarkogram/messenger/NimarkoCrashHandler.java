package app.nimarkogram.messenger;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import app.nimarkogram.messenger.plugins.PluginsController;
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
            try {
                dump(thread, throwable);
            } catch (Throwable ignored) {}
            
            try {
                Context context = appContext;
                if (context != null) {
                    android.content.SharedPreferences pp = context.getSharedPreferences("plugin_settings", 0);
                    android.content.SharedPreferences.Editor ed = pp.edit();

                    String attributedId = null;
                    try {
                        attributedId = PluginsController.getInstance().attributePluginFromCrashStack(thread, throwable);
                    } catch (Throwable ignored) {}

                    if (isLikelyPluginCrash(throwable) || attributedId != null) {
                        ed.putBoolean("had_crash", true);
                    }

                    if (attributedId != null) {
                        
                        ed.putString("crashed_plugin_id", attributedId)
                                .putBoolean("crashed_plugin_attribution_exact", true);
                    } else {
                        ed.remove("crashed_plugin_attribution_exact");
                        
                        String tn = thread != null ? thread.getName() : null;
                        boolean onPluginThread = tn != null && tn.contains("pluginsQueue");
                        if (!onPluginThread) {
                            ed.remove("crashed_plugin_id");
                        }
                    }
                    ed.commit();
                }
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

    private static boolean isLikelyPluginCrash(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            StackTraceElement[] trace = cur.getStackTrace();
            if (trace != null) {
                for (StackTraceElement el : trace) {
                    String cls = el.getClassName();
                    if (cls == null) continue;
                    
                    if (cls.startsWith("com.chaquo.python.")
                            || cls.startsWith("app.nimarkogram.messenger.plugins.xposed.")
                            || cls.startsWith("app.nimarkogram.messenger.plugins.hooks.")
                            || cls.startsWith("app.nimarkogram.messenger.plugins.intents.")) {
                        return true;
                    }
                    
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    public static void dump(Thread thread, Throwable t) {
        final boolean oom = isOom(t);
        try {
            File dir = getLogDir();
            if (dir == null) return;
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
            long threadId = thread != null ? thread.getId() : Thread.currentThread().getId();
            String reportId = ts + "-p" + android.os.Process.myPid()
                    + "-t" + threadId + "-" + reportSequence.incrementAndGet();
            File f = new File(dir, "crash-" + reportId + ".txt");
            
            try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
                
                pw.println("Report this crash through the project issue tracker");
                pw.println();
                pw.println("=== NimarkoGram crash ===");
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
                            + (android.os.Debug.getNativeHeapAllocatedSize() / mb) + "M");
                } catch (Throwable ignored) {
                }
                if (oom) {
                    try {
                        pw.println();
                        pw.println("=== OOM diagnostics ===");
                        pw.println("Uptime: " + (android.os.SystemClock.uptimeMillis() / 60000) + " min");
                        pw.println("GC: count=" + android.os.Debug.getRuntimeStat("art.gc.gc-count")
                                + ", freed=" + android.os.Debug.getRuntimeStat("art.gc.bytes-freed")
                                + ", blocking=" + android.os.Debug.getRuntimeStat("art.gc.blocking-gc-count"));
                        try {
                            Class<?> fr = Class.forName("java.lang.ref.FinalizerReference");
                            java.lang.reflect.Field head = fr.getDeclaredField("head");
                            head.setAccessible(true);
                            java.lang.reflect.Field next = fr.getDeclaredField("next");
                            next.setAccessible(true);
                            int n = 0;
                            Object cur = head.get(null);
                            while (cur != null && n < 200000) {
                                n++;
                                cur = next.get(cur);
                            }
                            pw.println("Finalizer queue: "
                                    + (n >= 200000 ? ">=200000" : String.valueOf(n)) + " pending");
                        } catch (Throwable ignored) {
                        }
                        try {
                            StringBuilder sb = new StringBuilder(128);
                            java.util.Map<String, ?> all = appContext
                                    .getSharedPreferences("plugin_settings", 0).getAll();
                            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                                String key = e.getKey();
                                if (key.startsWith("plugin_enabled_")
                                        && Boolean.TRUE.equals(e.getValue())) {
                                    if (sb.length() > 0) sb.append(", ");
                                    if (sb.length() < 512) {
                                        sb.append(key.substring("plugin_enabled_".length()));
                                    }
                                }
                            }
                            pw.println("Enabled plugins: " + (sb.length() > 0 ? sb : "(none)"));
                        } catch (Throwable ignored) {
                        }
                        pw.println("Heap dump: nimarko-logs/oom-" + reportId
                                + ".hprof (пришлите и его, если создался)");
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
                pw.println("=== End ===");
                pw.flush();
                if (pw.checkError()) {
                    throw new IllegalStateException("Unable to write crash report");
                }
            }
            
            if (oom) {
                try {
                    File hp = new File(dir, "oom-" + reportId + ".hprof");
                    android.os.Debug.dumpHprofData(hp.getAbsolutePath());
                    android.util.Log.e("nimarko-crash", "hprof dumped: " + hp.getAbsolutePath());
                } catch (Throwable ignored) {}
            }
            android.util.Log.e("nimarko-crash", "report written to " + f.getAbsolutePath());
            
            if (!oom) {
                String preview = readReportPreview(f, 64 * 1024);
                if (preview != null) {
                    android.util.Log.e("nimarko-crash", preview);
                    copyToClipboard(preview);
                }
            }
        } catch (Throwable ignored) {
            try {
                android.util.Log.e("nimarko-crash", "failed to dump", ignored);
            } catch (Throwable ignored2) {}
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
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("NimarkoGram crash", text));
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
            android.content.pm.PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isOom(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            if (cur instanceof OutOfMemoryError) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private NimarkoCrashHandler() {}
}
