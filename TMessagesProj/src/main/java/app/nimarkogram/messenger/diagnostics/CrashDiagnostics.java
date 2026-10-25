/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.diagnostics;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import app.nimarkogram.messenger.CrashContext;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

public final class CrashDiagnostics {
    public static final class ReportInfo {
        public final String id, type, state, heapStatus;
        public final long createdWallMillis, heapBytes;

        private ReportInfo(CrashDiagnosticStore.Report report) {
            id = report.id;
            type = report.type;
            state = report.state;
            heapStatus = report.heapStatus;
            createdWallMillis = report.createdWallMillis;
            heapBytes = report.heapBytes;
        }
    }

    public interface ReportCallback { void onResult(ReportInfo report, String errorCode); }
    public interface ReportsCallback { void onResult(List<ReportInfo> reports); }
    public interface ExportCallback { void onResult(File file, String errorCode); }

    private static final CrashDiagnosticBuffer buffer = new CrashDiagnosticBuffer();
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean fatal = new AtomicBoolean();
    private static final AtomicBoolean oomAttempted = new AtomicBoolean();
    private static final AtomicBoolean oomQueued = new AtomicBoolean();
    private static final AtomicBoolean earlyAttempted = new AtomicBoolean();
    private static final AtomicBoolean pingPending = new AtomicBoolean();
    private static final AtomicBoolean manualPending = new AtomicBoolean();
    private static final AtomicInteger modeGeneration = new AtomicInteger();
    private static final Object modeLock = new Object();
    private static final AtomicInteger resumed = new AtomicInteger();
    private static final AtomicLong failedQueueOffers = new AtomicLong();
    private static final AtomicReferenceArray<String> bridgeSnapshots = new AtomicReferenceArray<>(5);
    private static final ReentrantLock fragmentLock = new ReentrantLock();
    private static final WeakReference<?>[] fragments = new WeakReference<?>[128];
    private static final boolean[] destroyed = new boolean[128];
    private static final ThreadLocal<Boolean> recording = new ThreadLocal<>();
    private static volatile Handler main;
    private static volatile ThreadPoolExecutor io;
    private static volatile CrashDiagnosticStore store;
    private static volatile CrashHeapDumper heapDumper;
    private static volatile SharedPreferences preferences;
    private static volatile boolean primaryProcess;
    private static volatile boolean rawProxyObserved;
    private static volatile boolean preferencesLoading;
    private static volatile byte[] emergencyReserve;
    private static volatile boolean extended;
    private static volatile String session = "unavailable";
    private static volatile String mapping = "unavailable";
    private static volatile String version = "unavailable";
    private static volatile String nativeSession = "unavailable";
    private static volatile String screen = "unavailable";
    private static volatile String memoryState = "not_sampled";
    private static volatile String stallState = "not_observed";
    private static volatile String cachedSnapshot = "Diagnostics not initialized\n";
    private static volatile String offeredId = "";
    private static volatile long pingAt;
    private static volatile long lastAck;
    private static volatile int lastTrim = -1;
    private static volatile boolean startupComplete;
    private static volatile boolean hasResumed;
    private static long installedAt;
    private static long lastMemory;
    private static long lastDetailedMemory;
    private static long lastStall = -60000;
    private static long lastStallPackage = -120000;
    private static long rssKb = -1;
    private static long pssKb = -1;
    private static int fragmentOverflow;

    private CrashDiagnostics() {}

    public static void install(Context context) {
        if (!(context instanceof Application) || !installed.compareAndSet(false, true)) return;
        try {
            Application application = (Application) context;
            main = new Handler(Looper.getMainLooper());
            installedAt = SystemClock.uptimeMillis();
            session = UUID.randomUUID().toString().replace("-", "");
            emergencyReserve = new byte[512 * 1024];
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "crash-diagnostics-io");
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            io = executor;
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) {
                    hasResumed = true;
                    resumed.incrementAndGet();
                    lastAck = SystemClock.uptimeMillis();
                    pingAt = lastAck;
                    recordEvent("lifecycle", "resume", activity.getClass().getName());
                }
                @Override public void onActivityPaused(Activity activity) {
                    resumed.updateAndGet(value -> Math.max(0, value - 1));
                    lastAck = SystemClock.uptimeMillis();
                    if (resumed.get() == 0) pingAt = lastAck;
                    recordEvent("lifecycle", "pause", activity.getClass().getName());
                }
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
            execute(() -> initializeStorage(application));
            ScheduledThreadPoolExecutor sampler = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "crash-diagnostics-sample");
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.setDaemon(true);
                return thread;
            });
            sampler.setRemoveOnCancelPolicy(true);
            sampler.scheduleWithFixedDelay(CrashDiagnostics::tick, 1, 1, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    private static void initializeStorage(Context context) {
        try {
            if (!isPrimaryProcess(context)) return;
            primaryProcess = true;
            preferencesLoading = true;
            preferences = context.getSharedPreferences("crash_diagnostics", Context.MODE_PRIVATE);
            boolean saved = preferences.getBoolean("extended_memory", false);
            synchronized (modeLock) {
                if (modeGeneration.get() == 0) extended = saved;
                else preferences.edit().putBoolean("extended_memory", extended).apply();
                preferencesLoading = false;
            }
            offeredId = preferences.getString("last_offered", "");
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = CrashDiagnosticBuffer.bounded(info.versionName, 32) + " ("
                    + (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode) + ")";
            android.content.res.Resources resources = context.getPackageManager().getResourcesForApplication(context.getApplicationInfo());
            int resource = resources.getIdentifier("com.google.firebase.crashlytics.mapping_file_id", "string", context.getPackageName());
            if (resource != 0) mapping = CrashDiagnosticBuffer.bounded(resources.getString(resource), 64);
            CrashDiagnosticStore ready = new CrashDiagnosticStore(context);
            ready.recoverInterruptedReports();
            heapDumper = new CrashHeapDumper(ready);
            store = ready;
            recordEvent("diagnostics", "ready", "schema=1");
            publish(true);
        } catch (Throwable failure) {
            recordFailure("diagnostics", "initialization", "local_storage_unavailable", failure);
        }
    }

    private static boolean isPrimaryProcess(Context context) {
        try {
            String expected = context.getApplicationInfo().processName;
            if (expected == null || expected.isEmpty()) expected = context.getPackageName();
            if (Build.VERSION.SDK_INT >= 28) {
                String process = Application.getProcessName();
                if (process != null) return expected.equals(process);
            }
            try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
                byte[] bytes = new byte[256];
                int length = 0;
                while (length < bytes.length) {
                    int value = input.read();
                    if (value == 0 || value < 0) return expected.equals(new String(bytes, 0, length, StandardCharsets.UTF_8));
                    bytes[length++] = (byte) value;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void setNativeSession(String value) {
        if (value != null && value.matches("[a-f0-9]{32}")) nativeSession = value;
    }

    public static boolean isExtendedMemoryDiagnosticsEnabled() { return extended; }

    public static void setExtendedMemoryDiagnosticsEnabled(boolean value) {
        synchronized (modeLock) {
            modeGeneration.incrementAndGet();
            extended = value;
            if (!preferencesLoading && preferences != null) {
                try { preferences.edit().putBoolean("extended_memory", value).apply(); }
                catch (Throwable ignored) {}
            }
        }
        recordEvent("diagnostics", "memory_mode", value ? "enabled" : "disabled");
    }

    public static void recordEvent(String domain, String stage, String detail) {
        record(domain, stage, detail, null);
    }

    public static void recordFailure(String domain, String stage, String detail, Throwable failure) {
        record(domain, stage, detail, failure);
    }

    private static void record(String domain, String stage, String detail, Throwable failure) {
        boolean entered = false;
        try {
            if ("raw_proxy".equals(domain)) rawProxyObserved = true;
            if (Boolean.TRUE.equals(recording.get())) return;
            recording.set(true);
            entered = true;
            long now = SystemClock.uptimeMillis();
            if (!buffer.admit(now, failure != null)) return;
            String text = "tid=" + android.os.Process.myTid() + " domain=" + CrashDiagnosticBuffer.bounded(domain, 40)
                    + " stage=" + CrashDiagnosticBuffer.bounded(stage, 64) + " " + CrashDiagnosticBuffer.bounded(detail, 512)
                    + (failure == null ? "" : " exception=" + failure.getClass().getName());
            buffer.appendAdmitted(now, text, failure != null);
            if (failure != null) publish(false);
        } catch (Throwable ignored) {
        } finally {
            if (entered) recording.remove();
        }
    }

    public static void recordBridgeSnapshot(String key, String snapshot) {
        try {
            int slot = "ng_diag_main".equals(key) ? 0 : "ng_diag_worker_0".equals(key) ? 1
                    : "ng_diag_worker_1".equals(key) ? 2 : "ng_diag_worker_2".equals(key) ? 3 : 4;
            bridgeSnapshots.set(slot, CrashDiagnosticBuffer.bounded(snapshot, 1000));
        } catch (Throwable ignored) {
        }
    }

    public static void screenResumed(Object fragment) {
        if (fragment == null) return;
        try {
            screen = CrashDiagnosticBuffer.bounded(fragment.getClass().getName(), 128);
            if (!fragmentLock.tryLock()) return;
            try {
                int vacant = -1;
                for (int i = 0; i < fragments.length; i++) {
                    Object previous = fragments[i] == null ? null : fragments[i].get();
                    if (previous == fragment) { destroyed[i] = false; return; }
                    if (previous == null && vacant < 0) vacant = i;
                }
                if (vacant >= 0) {
                    fragments[vacant] = new WeakReference<>(fragment);
                    destroyed[vacant] = false;
                } else fragmentOverflow++;
            } finally {
                fragmentLock.unlock();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void fragmentDestroyed(Object fragment) {
        try {
            if (fragment == null || !fragmentLock.tryLock()) return;
            try {
                for (int i = 0; i < fragments.length; i++) {
                    if (fragments[i] != null && fragments[i].get() == fragment) { destroyed[i] = true; break; }
                }
            } finally {
                fragmentLock.unlock();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onTrimMemory(int level) {
        lastTrim = level;
        recordEvent("memory", "trim", "level=" + level);
    }

    public static void startupCompleted() {
        startupComplete = true;
        recordEvent("startup", "on_create", "complete");
    }

    private static String fragmentState() {
        if (!fragmentLock.tryLock()) return "fragments=busy";
        try {
            int live = 0, retired = 0;
            for (int i = 0; i < fragments.length; i++) {
                if (fragments[i] != null && fragments[i].get() != null) {
                    live++;
                    if (destroyed[i]) retired++;
                }
            }
            return "tracked_live=" + live + " destroyed_not_collected=" + retired + " tracking_overflow=" + fragmentOverflow;
        } finally {
            fragmentLock.unlock();
        }
    }

    private static void tick() {
        if (fatal.get() || !primaryProcess) return;
        try {
            long now = SystemClock.uptimeMillis();
            boolean watching = resumed.get() > 0 || !hasResumed && now - installedAt < 60000;
            if (watching) {
                if (pingPending.get()) {
                    long delay = now - pingAt;
                    if (delay >= 5000 && now - lastStall >= 60000 && !Debug.isDebuggerConnected()) {
                        lastStall = now;
                        stallState = "sample_uptime=" + now + " pending_ping_ms=" + delay + " " + mainStack();
                        buffer.event(now, stallState, true);
                        publish(true);
                        if (now - lastStallPackage >= 120000) {
                            lastStallPackage = now;
                            String snapshot = snapshotNow("main_thread_stall", null);
                            execute(() -> createReport("stall", snapshot));
                        }
                    }
                } else if (pingPending.compareAndSet(false, true)) {
                    pingAt = now;
                    boolean posted = false;
                    try {
                        posted = main.post(() -> { lastAck = SystemClock.uptimeMillis(); pingPending.set(false); });
                    } finally {
                        if (!posted) pingPending.set(false);
                    }
                }
            }
            if (now - lastMemory >= 5000 && (watching || lastMemory == 0)) {
                lastMemory = now;
                sampleMemory(now);
                cachedSnapshot = snapshotNow("sample", null);
                publish(true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void sampleMemory(long now) {
        if (rawProxyObserved) RawProxyDiagnostics.sample();
        Runtime runtime = Runtime.getRuntime();
        long maximum = runtime.maxMemory(), used = runtime.totalMemory() - runtime.freeMemory();
        if (now - lastDetailedMemory >= 30000 && maximum - used >= 32L * 1024 * 1024) {
            try {
                Debug.MemoryInfo info = new Debug.MemoryInfo();
                Debug.getMemoryInfo(info);
                pssKb = info.getTotalPss();
                rssKb = readRssKb();
                lastDetailedMemory = now;
            } catch (Throwable ignored) {
            }
        }
        String cache = "cache=not_initialized";
        if (ApplicationLoader.applicationHandler != null) {
            long[] sizes = ImageLoader.diagnosticCacheSizesIfInitialized();
            if (sizes != null) cache = "cache_estimate_bytes=" + sizes[0] + ',' + sizes[1] + ',' + sizes[2] + ',' + sizes[3];
        }
        memoryState = "clock=uptimeMillis uptime_ms=" + now + " java_used=" + used + " java_committed=" + runtime.totalMemory()
                + " java_max=" + maximum + " native_allocated=" + Debug.getNativeHeapAllocatedSize()
                + " rss_kb=" + rssKb + " pss_kb=" + pssKb + " detail_age_ms=" + (lastDetailedMemory == 0 ? -1 : now - lastDetailedMemory)
                + " gc=" + runtimeStat("art.gc.gc-count") + " blocking_gc=" + runtimeStat("art.gc.blocking-gc-count")
                + " freed=" + runtimeStat("art.gc.bytes-freed") + " trim=" + lastTrim + " " + cache + " " + fragmentState();
        buffer.memory(now, memoryState);
        if (buffer.earlyCaptureDue(now, used, maximum, extended, resumed.get() > 0) && earlyAttempted.compareAndSet(false, true)) {
            if (!execute(() -> {
                if (extended && !fatal.get() && resumed.get() > 0) capture("early", false, null);
                else earlyAttempted.set(false);
            })) earlyAttempted.set(false);
        }
    }

    private static long runtimeStat(String key) {
        if (Build.VERSION.SDK_INT < 23) return -1;
        try { return Long.parseLong(Debug.getRuntimeStat(key)); } catch (Throwable ignored) { return -1; }
    }

    private static long readRssKb() {
        try (BufferedReader input = new BufferedReader(new FileReader("/proc/self/status"), 1024)) {
            for (int i = 0; i < 96; i++) {
                String line = input.readLine();
                if (line == null) break;
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.substring(6).trim().split("\\s+", 2)[0]);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String mainStack() {
        StringBuilder value = new StringBuilder(1024);
        StackTraceElement[] frames = Looper.getMainLooper().getThread().getStackTrace();
        for (int i = 0; i < Math.min(24, frames.length) && value.length() < 1800; i++) {
            value.append(CrashDiagnosticBuffer.bounded(frames[i].getClassName(), 100)).append('.')
                    .append(CrashDiagnosticBuffer.bounded(frames[i].getMethodName(), 60)).append(':').append(frames[i].getLineNumber()).append(';');
        }
        return value.toString();
    }

    private static String identity() {
        return "schema=1 clock=uptimeMillis session=" + session + " native_session=" + nativeSession + " mapping=" + mapping
                + " version=" + version + " sdk=" + Build.VERSION.SDK_INT + " last_resumed_fragment=" + screen;
    }

    private static void publish(boolean initialize) {
        if (!primaryProcess) return;
        try {
            CrashContext.publishRuntimeDiagnostics(identity(), memoryState, buffer.lastFailure(), stallState, initialize);
        } catch (Throwable ignored) {
        }
    }

    private static String snapshotNow(String reason, Throwable failure) {
        StringBuilder value = new StringBuilder(8192);
        value.append(identity()).append("\nreason=").append(CrashDiagnosticBuffer.bounded(reason, 64))
                .append("\nlast_resumed_fragment=").append(screen).append(" resumed_activities=").append(resumed.get())
                .append(" application_created=").append(startupComplete).append(" activity_ever_resumed=").append(hasResumed)
                .append(" main_ack_uptime=").append(lastAck).append(" queue_rejected=").append(failedQueueOffers.get()).append('\n');
        if (failure != null) {
            value.append("exception=").append(failure.getClass().getName()).append('\n');
            StackTraceElement[] frames = failure.getStackTrace();
            for (int i = 0; i < Math.min(48, frames.length); i++) {
                value.append(CrashDiagnosticBuffer.bounded(frames[i].getClassName(), 120)).append('.')
                        .append(CrashDiagnosticBuffer.bounded(frames[i].getMethodName(), 80)).append(':').append(frames[i].getLineNumber()).append('\n');
            }
        }
        value.append("last_memory=").append(memoryState).append("\nlast_stall=").append(stallState).append('\n');
        if (rawProxyObserved) {
            value.append("raw_proxy_failure=").append(RawProxyDiagnostics.getLastFailure()).append('\n')
                    .append(RawProxyDiagnostics.getCachedSnapshot()).append('\n');
        }
        for (int i = 0; i < bridgeSnapshots.length(); i++) {
            String entry = bridgeSnapshots.get(i);
            if (entry != null) value.append("sampled_bridge_").append(i).append(" clock=elapsedRealtime ").append(entry).append('\n');
        }
        value.append(buffer.snapshot(SystemClock.uptimeMillis()));
        if (value.length() > 64 * 1024) value.setLength(64 * 1024);
        return value.toString();
    }

    public static void beginFatal() {
        emergencyReserve = null;
        fatal.set(true);
    }

    public static void writeContext(PrintWriter writer) {
        try {
            writer.println("=== Bounded pre-crash context ===");
            writer.println(cachedSnapshot);
            writer.println("Latest failure: " + buffer.lastFailure());
            if (rawProxyObserved) {
                writer.println("Latest raw proxy failure: " + RawProxyDiagnostics.getLastFailure());
                writer.println(RawProxyDiagnostics.getCachedSnapshot());
            }
            writer.println("History is sampled; current exception remains authoritative.");
        } catch (Throwable ignored) {
        }
    }

    public static String captureFatal(String legacyReportId, Throwable failure, boolean oom) {
        beginFatal();
        try {
            if (oom) {
                return "deferred_oom_capture=disabled_for_stability";
            }
            String snapshot = oom ? oomSnapshot(legacyReportId, failure)
                    : snapshotNow("fatal", failure) + "\nlegacy_report=" + CrashDiagnosticBuffer.bounded(legacyReportId, 96);
            CrashDiagnosticStore ready = store;
            CrashDiagnosticStore.Report report = ready == null ? null
                    : ready.tryCreateReport(session, mapping, version, oom ? "oom" : "fatal", snapshot);
            if (report == null) return "unavailable";
            if (oom && heapDumper != null && oomAttempted.compareAndSet(false, true)) {
                CrashDiagnosticStore.Report captured = heapDumper.capture(report, true);
                if (captured != null) report = captured;
            }
            return "id=" + report.id + " heap_status=" + report.heapStatus + " heap_bytes=" + report.heapBytes;
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String oomSnapshot(String legacyReportId, Throwable failure) {
        StringBuilder result = new StringBuilder(4096);
        result.append("OOM bounded context; legacy_report=").append(CrashDiagnosticBuffer.bounded(legacyReportId, 96)).append('\n');
        if (failure != null) {
            result.append("exception=").append(failure.getClass().getName()).append('\n');
            StackTraceElement[] frames = failure.getStackTrace();
            for (int i = 0; i < Math.min(16, frames.length); i++) {
                result.append(CrashDiagnosticBuffer.bounded(frames[i].getClassName(), 120)).append('.')
                        .append(CrashDiagnosticBuffer.bounded(frames[i].getMethodName(), 80)).append(':').append(frames[i].getLineNumber()).append('\n');
            }
        }
        String snapshot = cachedSnapshot;
        int limit = rawProxyObserved ? 24576 : 32768;
        if (rawProxyObserved) {
            result.append("raw_proxy_failure=").append(RawProxyDiagnostics.getLastFailure()).append('\n')
                    .append(RawProxyDiagnostics.getCachedSnapshot()).append('\n');
        }
        result.append(snapshot, 0, Math.min(limit, snapshot.length()));
        if (snapshot.length() > limit) result.append("\n[OOM snapshot budget reached]");
        return result.toString();
    }

    private static CrashDiagnosticStore.Report createReport(String type, String text) {
        try {
            CrashDiagnosticStore ready = store;
            return ready == null ? null : ready.createReport(session, mapping, version, type, text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void capture(String type, boolean oom, ReportCallback callback) {
        CrashDiagnosticStore.Report result = null;
        String error = null;
        try {
            if (heapDumper == null || store == null) error = "not_ready";
            else {
                result = createReport(type, snapshotNow(type, null));
                if (result == null) error = "storage";
                else if ("oom_observed".equals(type) && (fatal.get() || !oomAttempted.compareAndSet(false, true))) error = "coalesced";
                else {
                    CrashDiagnosticStore.Report captured = heapDumper.capture(result, oom);
                    if (captured == null) error = "capture_failed";
                    else {
                        result = captured;
                        if (!"completed".equals(result.heapStatus)) error = result.heapStatus;
                    }
                }
            }
        } catch (Throwable failure) {
            error = "capture_failed";
        }
        ReportInfo info = result == null ? null : new ReportInfo(result);
        String code = error;
        if (callback != null) onMain(() -> callback.onResult(info, code));
    }

    public static void requestManualHeapDump(ReportCallback callback) {
        if (!manualPending.compareAndSet(false, true)) {
            if (callback != null) onMain(() -> callback.onResult(null, "busy"));
            return;
        }
        if (!execute(() -> {
            try { capture("manual", false, callback); }
            finally { manualPending.set(false); }
        })) {
            manualPending.set(false);
            if (callback != null) onMain(() -> callback.onResult(null, "busy"));
        }
    }

    public static void requestObservedOomDump() {
        emergencyReserve = null;
        if (oomQueued.compareAndSet(false, true)) {
            if (!execute(() -> {
                try {
                    if (!fatal.get() && !oomAttempted.get()) capture("oom_observed", true, null);
                } finally { oomQueued.set(false); }
            })) oomQueued.set(false);
        }
    }

    public static void getReportsAsync(ReportsCallback callback) {
        if (callback == null) return;
        if (!execute(() -> {
            List<ReportInfo> result = new ArrayList<>(3);
            try {
                CrashDiagnosticStore ready = store;
                if (ready != null) for (CrashDiagnosticStore.Report report : ready.listReports()) result.add(new ReportInfo(report));
            } catch (Throwable ignored) {
            }
            List<ReportInfo> immutable = Collections.unmodifiableList(result);
            onMain(() -> callback.onResult(immutable));
        })) onMain(() -> callback.onResult(Collections.emptyList()));
    }

    public static void getPendingReportAsync(ReportCallback callback) {
        if (callback == null) return;
        getReportsAsync(reports -> {
            ReportInfo latest = null;
            for (ReportInfo report : reports) {
                if ("fatal".equals(report.type) || "oom".equals(report.type) || "stall".equals(report.type)) {
                    if (!report.id.equals(offeredId)) latest = report;
                    break;
                }
            }
            callback.onResult(latest, null);
        });
    }

    public static void markReportOffered(String id) {
        if (id == null || id.length() > 128) return;
        offeredId = id;
        execute(() -> { if (preferences != null) preferences.edit().putString("last_offered", id).apply(); });
    }

    public static void exportReportAsync(String reportId, boolean includeHeap, ExportCallback callback) {
        if (callback == null) return;
        if (!execute(() -> {
            File file = null;
            String error = null;
            try {
                CrashDiagnosticStore ready = store;
                if (ready == null) error = "not_ready";
                else file = ready.exportReport(reportId, includeHeap);
            } catch (Throwable failure) {
                error = "export_failed";
            }
            File result = file;
            String code = error;
            onMain(() -> callback.onResult(result, code));
        })) onMain(() -> callback.onResult(null, "busy"));
    }

    private static boolean execute(Runnable runnable) {
        ThreadPoolExecutor executor = io;
        if (executor == null) return false;
        try { executor.execute(runnable); return true; }
        catch (Throwable ignored) { failedQueueOffers.incrementAndGet(); return false; }
    }

    private static void onMain(Runnable runnable) {
        Handler handler = main;
        if (handler != null) {
            try { if (!handler.post(runnable)) failedQueueOffers.incrementAndGet(); }
            catch (Throwable ignored) { failedQueueOffers.incrementAndGet(); }
        }
    }
}
