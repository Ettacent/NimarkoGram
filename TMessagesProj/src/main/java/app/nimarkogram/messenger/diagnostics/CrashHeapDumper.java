/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.diagnostics;

import android.os.Debug;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CrashHeapDumper {
    public interface HeapWriter {
        void dump(String absolutePath) throws Exception;
    }

    public interface MemoryProbe {
        long usedBytes();
        long headroomBytes();
    }

    public static final long MIN_HEADROOM_BYTES = 32L * 1024 * 1024;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final AtomicBoolean EARLY_ATTEMPTED = new AtomicBoolean();
    private static final HeapWriter ANDROID_WRITER = Debug::dumpHprofData;
    private final CrashDiagnosticStore store;
    private final MemoryProbe memory;

    public CrashHeapDumper(CrashDiagnosticStore store) {
        this(store, new MemoryProbe() {
            public long usedBytes() {
                Runtime runtime = Runtime.getRuntime();
                return Math.max(0, runtime.totalMemory() - runtime.freeMemory());
            }

            public long headroomBytes() {
                return Math.max(0, Runtime.getRuntime().maxMemory() - usedBytes());
            }
        });
    }

    public CrashHeapDumper(CrashDiagnosticStore store, MemoryProbe memory) {
        if (store == null || memory == null) throw new IllegalArgumentException();
        this.store = store;
        this.memory = memory;
    }

    public static boolean isBusy() {
        return BUSY.get();
    }

    public CrashDiagnosticStore.Report capture(CrashDiagnosticStore.Report report, boolean oom) {
        return capture(report, oom, ANDROID_WRITER);
    }

    public CrashDiagnosticStore.Report capture(CrashDiagnosticStore.Report report, boolean oom, HeapWriter writer) {
        if (report == null) return null;
        if (!BUSY.compareAndSet(false, true)) return reject(report, "busy");
        CrashDiagnosticStore.HeapTicket ticket = null;
        long started = System.nanoTime();
        String status = "failed";
        String failure = "";
        try {
            boolean emergency = oom && ("oom".equals(report.type) || "oom_observed".equals(report.type) || "fatal".equals(report.type));
            if (!emergency) CrashDiagnosticStore.requireWorker();
            if ("early".equals(report.type) && !EARLY_ATTEMPTED.compareAndSet(false, true)) {
                return reject(report, "early_attempted");
            }
            if (writer == null) return reject(report, "unavailable");
            if (!emergency && memory.headroomBytes() < MIN_HEADROOM_BYTES) return reject(report, "low_memory");
            long used = Math.max(0, memory.usedBytes());
            long estimate = used > (Long.MAX_VALUE - 16L * 1024 * 1024) / 2 ? Long.MAX_VALUE / 2
                    : Math.max(16L * 1024 * 1024, used * 2 + 16L * 1024 * 1024);
            ticket = store.beginHeap(report, estimate);
            writer.dump(ticket.partial.getAbsolutePath());
            status = "completed";
        } catch (Throwable error) {
            failure = CrashDiagnosticStore.failureType(error);
            if (error instanceof CrashDiagnosticStore.HeapDeniedException) {
                status = ((CrashDiagnosticStore.HeapDeniedException) error).status;
            } else {
                status = "failed";
            }
        } finally {
            if (ticket == null) BUSY.set(false);
        }
        if (ticket == null) return reject(report, status);
        try {
            return store.finishHeap(ticket, status, failure, (System.nanoTime() - started) / 1000000);
        } catch (Throwable ignored) {
            return store.unpersistedHeapFailure(report, (System.nanoTime() - started) / 1000000);
        } finally {
            BUSY.set(false);
        }
    }

    private CrashDiagnosticStore.Report reject(CrashDiagnosticStore.Report report, String status) {
        try {
            return store.rejectHeap(report, status);
        } catch (Throwable ignored) {
            return current(report);
        }
    }

    private CrashDiagnosticStore.Report current(CrashDiagnosticStore.Report report) {
        try {
            return store.getReport(report.id);
        } catch (Throwable ignored) {
            return store.unpersistedHeapFailure(report, 0);
        }
    }
}
