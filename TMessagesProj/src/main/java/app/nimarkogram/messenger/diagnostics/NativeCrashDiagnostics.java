/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.diagnostics;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;

import androidx.annotation.DoNotInline;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class NativeCrashDiagnostics {
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final Pattern NAME = Pattern.compile("session-[a-f0-9]{32}\\.ngd");
    private static final int MAX_FILES = 4;
    private static final int MAX_SUBMISSIONS = 2;
    private static final int MAX_EVENT_KEYS = 23;
    private static volatile Thread worker;

    private NativeCrashDiagnostics() {
    }

    public static boolean isCollectorThread() {
        return worker == Thread.currentThread();
    }

    public static void start(Context context, Consumer<NativeInvocationJournal> ready) {
        if (context == null || !started.compareAndSet(false, true)) return;
        try {
            Context app = context.getApplicationContext();
            Thread thread = new Thread(() -> {
                Object reporter = null;
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    if (!mainProcess(app)) return;
                    Class<?> firebase = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics");
                    reporter = firebase.getMethod("getInstance").invoke(null);
                    if (!Boolean.TRUE.equals(firebase.getMethod("isCrashlyticsCollectionEnabled").invoke(reporter))) return;
                    setKey(reporter, "ng_diag_journal_state", "initializing");
                    File directory = new File(app.getNoBackupFilesDir(), "native-evidence-v4").getCanonicalFile();
                    File base = app.getNoBackupFilesDir().getCanonicalFile();
                    if (!base.equals(directory.getParentFile())) throw new IOException("Invalid journal directory");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Journal directory unavailable");
                    Os.chmod(directory.getPath(), 0700);
                    List<File> previous = files(directory);
                    long order = 1;
                    for (File prior : previous) {
                        try {
                            long previousOrder = NativeInvocationJournal.readHeader(prior).startupOrder;
                            if (previousOrder == Long.MAX_VALUE) throw new IllegalStateException("Journal order exhausted");
                            order = Math.max(order, previousOrder + 1);
                        } catch (IOException ignored) {
                        }
                    }
                    int evicted = 0;
                    while (previous.size() >= MAX_FILES) {
                        File old = previous.get(previous.size() - 1);
                        if (!old.delete()) throw new IOException("Journal retention unavailable");
                        previous.remove(previous.size() - 1);
                        evicted++;
                    }
                    String session = UUID.randomUUID().toString().replace("-", "");
                    String mapping = mappingId(app);
                    long wall = System.currentTimeMillis();
                    File file = new File(directory, "session-" + session + ".ngd");
                    NativeInvocationJournal journal = new NativeInvocationJournal(file, session, mapping,
                            Process.myPid(), wall, SystemClock.elapsedRealtimeNanos(), order);
                    Os.chmod(file.getPath(), 0600);
                    NativeExitInfoReader.markSession(app, session);
                    setKey(reporter, "ng_diag_schema", "5");
                    setKey(reporter, "ng_diag_session", session);
                    setKey(reporter, "ng_diag_mapping_id", mapping);
                    setKey(reporter, "ng_diag_journal_evicted", String.valueOf(evicted));
                    ready.accept(journal);
                    setKey(reporter, "ng_diag_journal_state", "ready_unsampled_managed_boundaries");
                    recover(app, reporter, previous, directory, wall);
                } catch (Throwable failure) {
                    try {
                        setKey(reporter, "ng_diag_journal_error", failure.getClass().getName());
                    } catch (Throwable ignored) {
                    }
                } finally {
                    worker = null;
                }
            }, "native-evidence-init");
            worker = thread;
            thread.start();
        } catch (Throwable ignored) {
            worker = null;
        }
    }

    private static boolean mainProcess(Context context) {
        String expected = context.getApplicationInfo().processName;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return expected.equals(Api28.processName());
            } catch (LinkageError ignored) {
            }
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == Process.myPid()) return expected.equals(process.processName);
        }
        return false;
    }

    private static List<File> files(File directory) throws IOException {
        File[] values = directory.listFiles(file -> NAME.matcher(file.getName()).matches());
        ArrayList<File> files = new ArrayList<>();
        Map<File, Long> order = new LinkedHashMap<>();
        if (values != null) for (File file : values) {
            if (file.isFile() && file.getCanonicalFile().equals(new File(directory, file.getName()))) {
                files.add(file);
                try {
                    order.put(file, NativeInvocationJournal.readHeader(file).startupOrder);
                } catch (IOException invalid) {
                    order.put(file, Long.MIN_VALUE);
                }
            }
        }
        files.sort(Comparator.comparingLong((File file) -> order.get(file)).reversed());
        return files;
    }

    private static void recover(Context context, Object reporter, List<File> files, File directory, long now) {
        String status = "no_previous_journal";
        int sent = 0;
        Set<String> matchedExits = new HashSet<>();
        boolean legacyCrash = false;
        if (Build.VERSION.SDK_INT < 30) {
            try {
                legacyCrash = Boolean.TRUE.equals(reporter.getClass().getMethod("didCrashOnPreviousExecution").invoke(reporter));
            } catch (Throwable ignored) {
            }
        }
        for (int index = 0; index < files.size() && sent < 2; index++) {
            File file = files.get(index);
            try {
                int attempts = attempts(file);
                NativeInvocationJournal.Snapshot header = NativeInvocationJournal.readHeader(file);
                if (!file.getName().equals("session-" + header.session + ".ngd")) throw new IOException("Journal name mismatch");
                if (header.reportedExitMillis > 0) matchedExits.add(header.pid + "@" + header.reportedExitMillis);
                if (attempts >= MAX_SUBMISSIONS) continue;
                NativeExitInfoReader.Result exit = NativeExitInfoReader.read(context, header.pid, header.session, header.wallMillis, now);
                status = exit.status;
                if (exit.exactMatch) matchedExits.add(exit.pid + "@" + exit.timestamp);
                if (exit.exactMatch) {
                    attempts = Math.max(attempts, unpairedAttempts(stateFile(directory), exit.pid, exit.timestamp));
                    if (attempts >= MAX_SUBMISSIONS) continue;
                }
                boolean unverified = Build.VERSION.SDK_INT < 30 && legacyCrash && index == 0;
                if (!exit.exactMatch && !unverified) continue;
                NativeTombstone.Summary tombstone = exit.tombstone;
                int tid = tombstone == null ? 0 : tombstone.tid;
                NativeInvocationJournal.Snapshot journal;
                String journalFailure = null;
                try {
                    journal = NativeInvocationJournal.read(file, tid);
                } catch (IOException unreadable) {
                    journal = header;
                    journalFailure = "journal_read_failed; identity_header_only; system evidence retained";
                }
                LinkedHashMap<String, String> keys = evidence(journal, exit, unverified, attempts + 1);
                if (journalFailure != null) keys.put("ng_previous_coverage", journalFailure);
                Throwable evidence = unverified ? new PreviousProcessEvidence() : new NativeExitEvidence();
                evidence.setStackTrace(stack(tombstone == null ? null : tombstone.frames));
                sent++;
                markAttempt(file, attempts + 1, exit.exactMatch ? exit.timestamp : 0);
                if (exit.exactMatch) markUnpaired(stateFile(directory), exit.pid, exit.timestamp, attempts + 1);
                submit(reporter, evidence, keys);
                status = "submission_attempted_not_server_acknowledged";
            } catch (Throwable failure) {
                status = "recovery_" + failure.getClass().getSimpleName();
            }
        }
        if (sent == 0 && Build.VERSION.SDK_INT >= 30) {
            try {
                NativeExitInfoReader.Result exit = NativeExitInfoReader.readLatestUnpaired(context, now);
                if (exit.pid > 0 && exit.timestamp > 0 && !matchedExits.contains(exit.pid + "@" + exit.timestamp)) {
                    File state = stateFile(directory);
                    int attempts = unpairedAttempts(state, exit.pid, exit.timestamp);
                    if (attempts < MAX_SUBMISSIONS) {
                        NativeInvocationJournal.Snapshot absent = new NativeInvocationJournal.Snapshot();
                        absent.pid = exit.pid;
                        absent.session = "unavailable";
                        absent.mapping = "unavailable";
                        LinkedHashMap<String, String> keys = evidence(absent, exit, false, attempts + 1);
                        keys.put("ng_previous_kind", "system_native_exit_unpaired_no_journal");
                        keys.put("ng_previous_coverage", "system_tombstone_only; no invocation journal or verified APK mapping");
                        NativeExitEvidence recovered = new NativeExitEvidence();
                        recovered.setStackTrace(stack(exit.tombstone == null ? null : exit.tombstone.frames));
                        sent++;
                        markUnpaired(state, exit.pid, exit.timestamp, attempts + 1);
                        submit(reporter, recovered, keys);
                        status = "unpaired_submission_attempted_not_server_acknowledged";
                    }
                }
            } catch (Throwable failure) {
                status = "unpaired_" + failure.getClass().getSimpleName();
            }
        }
        setKey(reporter, "ng_diag_recovery_state", status);
        setKey(reporter, "ng_diag_recovery_attempts", String.valueOf(sent));
    }

    private static LinkedHashMap<String, String> evidence(NativeInvocationJournal.Snapshot journal,
                                                          NativeExitInfoReader.Result exit, boolean unverified, int attempt) {
        LinkedHashMap<String, String> keys = new LinkedHashMap<>();
        keys.put("ng_previous_schema", String.valueOf(journal.diagnosticRevision));
        keys.put("ng_previous_kind", unverified ? "unverified_previous_journal_not_native_attribution" : "system_native_exit");
        keys.put("ng_previous_session", journal.session);
        keys.put("ng_previous_mapping", journal.mapping);
        keys.put("ng_previous_process", "pid=" + journal.pid + " started_wall_ms=" + journal.wallMillis
                + " started_elapsed_ns=" + journal.elapsedNanos + " exit_wall_ms=" + exit.timestamp + " attempt=" + attempt);
        keys.put("ng_previous_exit", "status=" + exit.status + " exact=" + exit.exactMatch + " reason=" + exit.reason
                + " code=" + exit.statusCode + " pss_kb=" + exit.pss + " rss_kb=" + exit.rss + " importance=" + exit.importance
                + " timestamp_outside_window=" + exit.timestampOutsideWindow);
        keys.put("ng_previous_coverage", "managed_boundaries_only threads=" + journal.threads + " invalid=" + journal.invalid
                + " truncated=" + journal.truncated + " stale_generation=" + journal.stale + " flags=" + journal.flags
                + " flag_bits=1:no_slot,2:depth,4:metadata,8:end_mismatch,16:disabled,32:metadata_pending,64:metadata_length,128:metadata_full"
                + " active_total=" + journal.active.size() + " recent_total=" + journal.recent.size()
                + " export_limit=4active/8recent");
        NativeTombstone.Summary trace = exit.tombstone;
        if (trace != null) {
            keys.put("ng_previous_native", "tid=" + trace.tid + " arch=" + trace.arch + " signal=" + trace.signal
                    + " code=" + trace.signalCode + " fault=" + (trace.hasFaultAddress ? hex(trace.faultAddress) : "unavailable")
                    + " status=" + trace.status + " process_uptime_s=" + trace.processUptime + " abort_category=" + trace.abortCategory);
            StringBuilder registers = new StringBuilder();
            for (Map.Entry<String, Long> entry : trace.registers.entrySet()) registers.append(entry.getKey()).append('=').append(hex(entry.getValue())).append(' ');
            keys.put("ng_previous_registers", registers.toString());
            StringBuilder mappings = new StringBuilder();
            for (NativeTombstone.Mapping map : trace.mappings) mappings.append(map.module).append(' ')
                    .append(hex(map.begin)).append('-').append(hex(map.end)).append(" off=").append(hex(map.offset))
                    .append(" bias=").append(hex(map.loadBias)).append(" rwx=").append(map.read ? 'r' : '-')
                    .append(map.write ? 'w' : '-').append(map.execute ? 'x' : '-').append(" build=").append(map.buildId).append('\n');
            keys.put("ng_previous_mappings", mappings.toString());
            LinkedHashMap<String, String> builds = new LinkedHashMap<>();
            for (NativeTombstone.Frame frame : trace.frames) if (!frame.buildId.isEmpty()) builds.put(frame.module, frame.buildId);
            keys.put("ng_previous_build_ids", builds.toString());
            if (trace.memoryError != null) {
                NativeTombstone.HeapObject heap = trace.memoryError.heap;
                keys.put("ng_previous_memory_error", "tool=" + trace.memoryError.tool + " type=" + trace.memoryError.type
                        + (heap == null ? "" : " heap=" + hex(heap.address) + " size=" + heap.size
                        + " allocated_tid=" + heap.allocationTid + " freed_tid=" + heap.deallocationTid));
                if (heap != null) {
                    keys.put("ng_previous_allocation", frames(heap.allocationFrames));
                    keys.put("ng_previous_deallocation", frames(heap.deallocationFrames));
                }
            }
        } else {
            keys.put("ng_previous_native", "crashing_tid_unavailable; journal includes multiple threads, not crash attribution");
        }
        appendJournalEvidence(keys, journal);
        return keys;
    }

    private static void appendJournalEvidence(LinkedHashMap<String, String> keys, NativeInvocationJournal.Snapshot journal) {
        int active = 0, recent = 0;
        while (active < Math.min(4, journal.active.size()) && keys.size() < MAX_EVENT_KEYS) {
            keys.put("ng_previous_active_" + active, entryText(journal.active.get(active)));
            active++;
        }
        while (recent < Math.min(8, journal.recent.size()) && keys.size() < MAX_EVENT_KEYS) {
            keys.put("ng_previous_recent_" + recent, entryText(journal.recent.get(recent)));
            recent++;
        }
        String coverage = keys.get("ng_previous_coverage");
        if (coverage != null) {
            keys.put("ng_previous_coverage", coverage + " exported_active=" + active
                    + " exported_recent=" + recent + " omitted_active=" + (journal.active.size() - active)
                    + " omitted_recent=" + (journal.recent.size() - recent));
        }
    }

    private static String entryText(NativeInvocationJournal.Entry entry) {
        String description = entry.description;
        boolean safe = description != null && description.length() <= 480;
        if (safe) for (int i = 0; i < description.length(); i++) {
            char c = description.charAt(i);
            if (Character.isISOControl(c) || !(Character.isJavaIdentifierPart(c) || " .-$:=[](),<>?".indexOf(c) >= 0)) {
                safe = false;
                break;
            }
        }
        if (safe) return entry.describe();
        return "tid=" + entry.nativeTid + " seq=" + entry.sequence + " t_ns=" + entry.timeNanos
                + " token=" + entry.token + " parent=" + entry.parent + " depth=" + entry.depth
                + " kind=" + entry.kind + " phase=" + entry.stage + " metadata_redacted";
    }

    private static String frames(List<NativeTombstone.Frame> frames) {
        StringBuilder result = new StringBuilder();
        for (NativeTombstone.Frame frame : frames) result.append(frame.module).append('+').append(hex(frame.relPc))
                .append(' ').append(frame.function).append(" build=").append(frame.buildId).append('\n');
        return result.toString();
    }

    private static StackTraceElement[] stack(List<NativeTombstone.Frame> frames) {
        if (frames == null || frames.isEmpty()) return new StackTraceElement[]{
                new StackTraceElement("native.evidence", "original_stack_unavailable", "system-tombstone", -2)};
        StackTraceElement[] stack = new StackTraceElement[Math.min(32, frames.size())];
        for (int i = 0; i < stack.length; i++) {
            NativeTombstone.Frame frame = frames.get(i);
            stack[i] = new StackTraceElement("native." + frame.module,
                    (frame.function.isEmpty() ? "pc" : frame.function) + "+" + hex(frame.relPc), frame.module, -2);
        }
        return stack;
    }

    private static void submit(Object reporter, Throwable failure, LinkedHashMap<String, String> keys) throws Exception {
        Class<?> builderClass = Class.forName("com.google.firebase.crashlytics.CustomKeysAndValues$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        Method put = builderClass.getMethod("putString", String.class, String.class);
        int count = 0;
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            if (++count > MAX_EVENT_KEYS) break;
            put.invoke(builder, entry.getKey(), bounded(entry.getValue()));
        }
        Object values = builderClass.getMethod("build").invoke(builder);
        Class<?> valuesClass = Class.forName("com.google.firebase.crashlytics.CustomKeysAndValues");
        reporter.getClass().getMethod("recordException", Throwable.class, valuesClass).invoke(reporter, failure, values);
    }

    private static int attempts(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() != NativeInvocationJournal.FILE_SIZE) throw new IOException("Invalid journal size");
            input.seek(256);
            int count = Integer.reverseBytes(input.readInt());
            return count >= 0 && count <= MAX_SUBMISSIONS ? count : 0;
        }
    }

    private static void markAttempt(File file, int attempt, long timestamp) throws IOException {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.seek(256);
            output.writeInt(Integer.reverseBytes(attempt));
            output.seek(280);
            output.writeLong(Long.reverseBytes(timestamp));
            output.getFD().sync();
        }
    }

    private static int unpairedAttempts(File file, int pid, long timestamp) throws IOException {
        if (!file.exists()) return 0;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() != 32 || input.readLong() != 0x4e47554e50414952L) return 0;
            int savedPid = input.readInt(), attempt = input.readInt();
            long savedTime = input.readLong(), checksum = input.readLong();
            return savedPid == pid && savedTime == timestamp && checksum == (timestamp ^ pid ^ attempt)
                    && attempt >= 0 ? attempt : 0;
        }
    }

    private static File stateFile(File directory) throws IOException {
        File state = new File(directory, "unpaired-state");
        if (!state.getCanonicalFile().equals(new File(directory.getCanonicalFile(), state.getName()))) throw new IOException("Invalid recovery state");
        return state;
    }

    private static void markUnpaired(File file, int pid, long timestamp, int attempt) throws IOException {
        if (file.exists()) {
            try (RandomAccessFile previous = new RandomAccessFile(file, "r")) {
                if (previous.length() == 32 && previous.readLong() == 0x4e47554e50414952L) {
                    int savedPid = previous.readInt(), savedAttempt = previous.readInt();
                    long savedTime = previous.readLong(), checksum = previous.readLong();
                    if (savedAttempt >= 0 && checksum == (savedTime ^ savedPid ^ savedAttempt)) {
                        if (savedTime == timestamp && savedPid == pid) attempt = Math.max(attempt, savedAttempt);
                    }
                }
            }
        }
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(32);
            output.writeLong(0);
            output.writeInt(pid);
            output.writeInt(attempt);
            output.writeLong(timestamp);
            output.writeLong(timestamp ^ pid ^ attempt);
            output.seek(0);
            output.writeLong(0x4e47554e50414952L);
            output.getFD().sync();
        }
    }

    private static String mappingId(Context context) {
        int id = context.getResources().getIdentifier("com.google.firebase.crashlytics.mapping_file_id", "string", context.getPackageName());
        return id == 0 ? "unavailable" : context.getResources().getString(id);
    }

    private static String hex(long value) { return "0x" + Long.toHexString(value); }

    private static String bounded(String value) {
        if (value == null) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= 900 ? value : new String(bytes, 0, 880, StandardCharsets.UTF_8) + " [truncated]";
    }

    private static void setKey(Object reporter, String key, String value) {
        if (reporter == null) return;
        try {
            reporter.getClass().getMethod("setCustomKey", String.class, String.class).invoke(reporter, key, bounded(value));
        } catch (Throwable ignored) {
        }
    }

    private static final class NativeExitEvidence extends RuntimeException {
        NativeExitEvidence() { super("Recovered native crash evidence; original fatal report is separate"); }
    }

    private static final class PreviousProcessEvidence extends RuntimeException {
        PreviousProcessEvidence() { super("Previous process journal; crash/thread correlation unavailable"); }
    }

    private static final class Api28 {
        @DoNotInline static String processName() { return Application.getProcessName(); }
    }
}
