package app.nimarkogram.messenger.plugins.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.AtomicFile;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.plugins.Plugin;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PluginCrashReports {
    private static final int MAX_CHARS = 48 * 1024;
    private static final int MAX_REPORTS = 32;
    private static final Map<String, Long> lastFailures = new LinkedHashMap<>();
    private static final Map<String, String> reportKeys = new LinkedHashMap<>();
    private static final ThreadPoolExecutor reader = new ThreadPoolExecutor(1, 1,
            30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "plugin-report-reader");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1,
            30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "plugin-crash-reports");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private PluginCrashReports() {}

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plugin_crash_reports", Context.MODE_PRIVATE);
    }

    public static void setSafeModeReason(String reason) {
        try {
            preferences().edit().putString("safe_mode_reason", reason).commit();
        } catch (Exception ignored) {}
    }

    public static String safeModeExplanation() {
        String reason = null;
        try { reason = preferences().getString("safe_mode_reason", null); } catch (Exception ignored) {}
        if ("manual".equals(reason)) return LocaleController.getString(R.string.NM_PluginSafetyManual);
        if ("engine_timeout".equals(reason)) return LocaleController.getString(R.string.NM_PluginSafetyEngineTimeout);
        if ("engine_failure".equals(reason)) return LocaleController.getString(R.string.NM_PluginSafetyEngineFailure);
        if ("recovery_failed".equals(reason)) return LocaleController.getString(R.string.NM_PluginSafetyRecoveryFailed);
        return LocaleController.getString(R.string.NM_PluginSafetyUnknown);
    }

    public static boolean hasReport(String pluginId) {
        try { return preferences().getBoolean("report_" + key(pluginId), false); }
        catch (Exception ignored) { return false; }
    }

    public static void recordFailure(String pluginId, String phase, Throwable failure) {
        if (pluginId == null || failure == null || failure instanceof OutOfMemoryError) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (lastFailures) {
            Long last = lastFailures.get(pluginId);
            if (last != null && now - last < 10_000L) return;
            if (lastFailures.size() >= 64) lastFailures.remove(lastFailures.keySet().iterator().next());
            lastFailures.put(pluginId, now);
        }
        String threadName = Thread.currentThread().getName();
        long time = System.currentTimeMillis();
        try {
            worker.execute(() -> {
                try { save(pluginId, format(pluginId, phase, threadName, time, failure, null), time); }
                catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    public static void recordLoadInterruption(String pluginId, long time) {
        try {
            save(pluginId, format(pluginId, "native process exit during loading", "unknown", time, null,
                    "Load-time correlation only; the native faulting plugin/thread is not proven.\n"
                    + "This plugin was disabled as a precaution. Other plugins were left unchanged."), time);
        } catch (Throwable ignored) {}
    }

    public static void rememberFatalReport(String pluginId, File report) {
        try { save(pluginId, read(report), System.currentTimeMillis()); }
        catch (Throwable ignored) {}
    }

    public static void writeIdentity(PrintWriter writer, String pluginId) {
        writer.println("Plugin ID: " + clean(pluginId));
        Plugin plugin = PluginsWatchdog.getKnownPlugin(pluginId);
        if (plugin != null) {
            writer.println("Plugin name: " + clean(plugin.getName()));
            writer.println("Plugin version: " + clean(plugin.getVersion()));
            writer.println("Plugin author: " + clean(plugin.getAuthor()));
        }
    }

    private static String format(String pluginId, String phase, String thread, long time,
                                 Throwable failure, String note) {
        LimitedWriter buffer = new LimitedWriter();
        PrintWriter writer = new PrintWriter(buffer);
        writer.println("=== NimarkoGram plugin report ===");
        writeIdentity(writer, pluginId);
        writer.println("Time: " + new Date(time));
        writer.println("Phase: " + clean(phase));
        writer.println("Thread: " + clean(thread));
        writer.println("Device: " + Build.MODEL + " / Android " + Build.VERSION.SDK_INT);
        writer.println("App: " + NimarkoConfig.VERSION_NAME);
        writer.println("Client process terminated: "
                + ("native process exit during loading".equals(phase) ? "yes" : "no"));
        if (note != null) writer.println(note);
        if (failure != null) {
            writer.println();
            failure.printStackTrace(writer);
        }
        writer.flush();
        return buffer.toString();
    }

    private static String clean(String value) {
        if (value == null) return "unknown";
        return value.substring(0, Math.min(value.length(), 512)).replace('\n', ' ').replace('\r', ' ');
    }

    private static String key(String id) throws Exception {
        synchronized (reportKeys) {
            String cached = reportKeys.get(id);
            if (cached != null) return cached;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) {
                result.append(Character.forDigit((b & 255) >>> 4, 16));
                result.append(Character.forDigit(b & 15, 16));
            }
            if (reportKeys.size() >= 128) reportKeys.remove(reportKeys.keySet().iterator().next());
            String value = result.toString();
            reportKeys.put(id, value);
            return value;
        }
    }

    private static File directory() {
        File directory = new File(ApplicationLoader.getFilesDirFixed(), "plugin-crash-reports");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Plugin report storage unavailable");
        return directory;
    }

    private static synchronized void save(String id, String text, long time) throws Exception {
        File dir = directory();
        String name = key(id);
        File target = new File(dir, name + ".txt");
        if (target.exists() && target.lastModified() > time) return;
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(text.substring(0, Math.min(text.length(), MAX_CHARS)).getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
        } catch (Exception | Error error) {
            atomic.failWrite(output);
            throw error;
        }
        target.setLastModified(time);
        boolean firstReport = !preferences().getBoolean("report_" + name, false);
        preferences().edit().putBoolean("report_" + name, true).apply();
        if (firstReport) {
            NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginsUpdated);
        }
        File[] reports = dir.listFiles((parent, filename) -> filename.endsWith(".txt"));
        if (reports != null && reports.length > MAX_REPORTS) {
            Arrays.sort(reports, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < reports.length - MAX_REPORTS; i++) {
                if (reports[i].delete()) {
                    String old = reports[i].getName();
                    preferences().edit().remove("report_" + old.substring(0, old.length() - 4)).apply();
                }
            }
        }
    }

    private static synchronized String read(File file) throws Exception {
        try (Reader reader = new InputStreamReader(new AtomicFile(file).openRead(), StandardCharsets.UTF_8)) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[2048];
            while (result.length() < MAX_CHARS) {
                int count = reader.read(buffer, 0, Math.min(buffer.length, MAX_CHARS - result.length()));
                if (count < 0) break;
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    private static String report(Plugin plugin, Throwable fallback) {
        try {
            String saved = read(new File(directory(), key(plugin.getId()) + ".txt"));
            if (!saved.isEmpty()) return saved;
        } catch (Exception ignored) {}
        return format(plugin.getId(), "plugin status", "unknown", System.currentTimeMillis(), fallback,
                LocaleController.getString(R.string.NM_PluginReportMissing));
    }

    public static void copyReport(Plugin plugin, Throwable fallback, BaseFragment fragment) {
        if (plugin == null) return;
        submitUi(() -> {
            String report = report(plugin, fallback);
            AndroidUtilities.runOnUIThread(() -> {
                if (AndroidUtilities.addToClipboard(report) && usable(fragment)) {
                    BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                }
            });
        });
    }

    public static void showReport(Plugin plugin, BaseFragment fragment) {
        if (plugin == null || !usable(fragment)) return;
        submitUi(() -> {
            String report = report(plugin, plugin.getError());
            AndroidUtilities.runOnUIThread(() -> {
                if (!usable(fragment)) return;
                AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
                builder.setTitle(LocaleController.getString(R.string.NM_PluginReportTitle));
                builder.setMessage(report);
                builder.setPositiveButton(LocaleController.getString(R.string.Copy), (dialog, which) -> AndroidUtilities.addToClipboard(report));
                builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
                fragment.showDialog(builder.create());
            });
        });
    }

    private static boolean usable(BaseFragment fragment) {
        return fragment != null && fragment.getParentActivity() != null
                && !fragment.getParentActivity().isFinishing() && !fragment.getParentActivity().isDestroyed();
    }

    private static void submitUi(Runnable runnable) {
        try {
            reader.execute(() -> {
                try { runnable.run(); } catch (Throwable ignored) {}
            });
        } catch (RuntimeException ignored) {
            AndroidUtilities.runOnUIThread(() -> {
                BaseFragment fragment = org.telegram.ui.LaunchActivity.getLastFragment();
                if (usable(fragment)) {
                    BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.info,
                            LocaleController.getString(R.string.ErrorOccurred)).show();
                }
            });
        }
    }

    private static final class LimitedWriter extends Writer {
        final StringBuilder text = new StringBuilder();
        boolean truncated;
        @Override public void write(char[] chars, int offset, int length) {
            int count = Math.min(length, MAX_CHARS - text.length());
            if (count > 0) text.append(chars, offset, count);
            truncated |= count < length;
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() {
            return text + (truncated ? "\n[report truncated]" : "");
        }
    }
}
