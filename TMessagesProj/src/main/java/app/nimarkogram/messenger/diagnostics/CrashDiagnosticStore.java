/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.diagnostics;

import android.content.Context;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CrashDiagnosticStore {
    public static final int MAX_REPORTS = 3;
    public static final int MAX_EXPORTS = 3;
    public static final long MAX_PACKET_BYTES = 512L * 1024 * 1024;
    public static final long MAX_EXPORT_BYTES = 512L * 1024 * 1024;
    public static final long MAX_RETAINED_BYTES = MAX_PACKET_BYTES + MAX_EXPORT_BYTES;
    public static final long DISK_RESERVE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 65536;
    private static final int MAX_METADATA_BYTES = 4096;
    private static final long WRITE_RESERVE = 1024 * 1024;
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Set<String> PINNED = new HashSet<>();
    private static final AtomicBoolean EXPORTING = new AtomicBoolean();
    private static volatile File activeExport;
    private static final Comparator<Report> REPORT_ORDER = Comparator.comparingLong((Report r) -> r.creationOrder)
            .thenComparing(r -> r.id);
    private final File root;
    private final File exports;
    private final SpaceProbe space;

    public interface SpaceProbe {
        long usableBytes(File directory);
    }

    static final class HeapDeniedException extends IOException {
        final String status;

        HeapDeniedException(String status) {
            super("diagnostic_heap_denied");
            this.status = status;
        }
    }

    public static final class Report {
        public final String id, sessionId, mappingId, version, type, state, heapStatus, failureType;
        public final long createdWallMillis, creationOrder, heapBytes, heapDurationMillis, textBytes;
        public final boolean metadataPersisted;
        private final File directory;

        private Report(File directory, String id, String session, String mapping, String version,
                       String type, String state, String heapStatus, String failureType,
                       long created, long order, long heapBytes, long duration, long textBytes) {
            this(directory, id, session, mapping, version, type, state, heapStatus, failureType,
                    created, order, heapBytes, duration, textBytes, true);
        }

        private Report(File directory, String id, String session, String mapping, String version,
                       String type, String state, String heapStatus, String failureType,
                       long created, long order, long heapBytes, long duration, long textBytes, boolean persisted) {
            this.directory = directory;
            this.id = id;
            this.sessionId = session;
            this.mappingId = mapping;
            this.version = version;
            this.type = type;
            this.state = state;
            this.heapStatus = heapStatus;
            this.failureType = failureType;
            this.createdWallMillis = created;
            this.creationOrder = order;
            this.heapBytes = heapBytes;
            this.heapDurationMillis = duration;
            this.textBytes = textBytes;
            this.metadataPersisted = persisted;
        }

        private Report changed(String state, String heap, String failure, long bytes, long duration, long text) {
            return new Report(directory, id, sessionId, mappingId, version, type, state, heap,
                    failure, createdWallMillis, creationOrder, bytes, duration, text);
        }

        private Report unpersisted() {
            return new Report(directory, id, sessionId, mappingId, version, type, state, heapStatus,
                    failureType, createdWallMillis, creationOrder, heapBytes, heapDurationMillis, textBytes, false);
        }
    }

    static final class HeapTicket {
        final Report report;
        final File partial;
        final long inode, device;

        HeapTicket(Report report, File partial, StructStat stat) {
            this.report = report;
            this.partial = partial;
            inode = stat.st_ino;
            device = stat.st_dev;
        }
    }

    public CrashDiagnosticStore(Context context) throws IOException {
        this(workerContext(context).getNoBackupFilesDir(), context.getCacheDir());
    }

    public CrashDiagnosticStore(File noBackupDirectory, File cacheDirectory) throws IOException {
        this(noBackupDirectory, cacheDirectory, File::getUsableSpace);
    }

    public CrashDiagnosticStore(File noBackupDirectory, File cacheDirectory, SpaceProbe space) throws IOException {
        if (space == null) throw new IOException("diagnostic_space_probe");
        this.space = space;
        root = new File(anchor(noBackupDirectory), "crash-diagnostics-v1");
        exports = new File(anchor(cacheDirectory), "crash-diagnostics-exports");
        acquire();
        try {
            directory(root, true);
            directory(exports, true);
            recoverLocked();
            trim(null, 0, false);
        } finally {
            LOCK.unlock();
        }
    }

    public Report createReport(String session, String mapping, String version, String type, String text)
            throws IOException {
        acquire();
        try {
            directory(root, false);
            long order = nextCreationOrder();
            trim(null, WRITE_RESERVE, true);
            File slot = null;
            for (int i = 0; i < MAX_REPORTS; i++) {
                File candidate = slot(i);
                if (stat(candidate) == null) {
                    slot = candidate;
                    break;
                }
            }
            if (slot == null) throw new IOException("diagnostic_capacity");
            directory(slot, true);
            Report report = new Report(slot, UUID.randomUUID().toString(), token(session), token(mapping),
                    token(version), type(type), "started", "not_requested", "", System.currentTimeMillis(), order, 0, 0, 0);
            PINNED.add(slot.getPath());
            try {
                save(report);
                String safeText = text == null ? "" : text.substring(0, Math.min(text.length(), MAX_TEXT_CHARS));
                byte[] bytes = ("report=" + report.id + "\nsession=" + report.sessionId + "\nmapping="
                        + report.mappingId + "\nversion=" + report.version + "\ntype=" + report.type
                        + "\n\n" + safeText).getBytes(StandardCharsets.UTF_8);
                atomicWrite(artifact(report, ".txt"), bytes);
                report = report.changed("completed", "not_requested", "", 0, 0, bytes.length);
                save(report);
                return report;
            } catch (Throwable failure) {
                try {
                    save(report.changed("failed", "not_requested", failureType(failure), 0, 0, 0));
                } catch (Throwable ignored) {
                }
                throw new IOException("diagnostic_write_failed");
            } finally {
                PINNED.remove(slot.getPath());
            }
        } finally {
            LOCK.unlock();
        }
    }

    public Report tryCreateReport(String session, String mapping, String version, String type, String text) {
        try {
            return createReport(session, mapping, version, type, text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public Report getReport(String id) throws IOException {
        acquire();
        try {
            return find(id);
        } finally {
            LOCK.unlock();
        }
    }

    public List<Report> listReports() throws IOException {
        requireWorker();
        acquire();
        try {
            List<Report> result = reports();
            result.sort(REPORT_ORDER.reversed());
            return Collections.unmodifiableList(result);
        } finally {
            LOCK.unlock();
        }
    }

    public void recoverInterruptedReports() throws IOException {
        acquire();
        try {
            recoverLocked();
            trim(null, 0, false);
        } finally {
            LOCK.unlock();
        }
    }

    public File exportReport(String id, boolean includeHeap) throws IOException {
        requireWorker();
        if (!EXPORTING.compareAndSet(false, true)) throw new IOException("diagnostic_export_busy");
        Report report = null;
        File partial = null;
        boolean pinned = false;
        try {
            acquire();
            try {
                report = find(id);
                if (report == null || "started".equals(report.state) || PINNED.contains(report.directory.getPath())) {
                    throw new IOException("diagnostic_report_unavailable");
                }
                directory(exports, false);
                long expected = report.textBytes + metadata(report).length;
                if (includeHeap && "completed".equals(report.heapStatus)) expected += report.heapBytes;
                expected += WRITE_RESERVE;
                if (space.usableBytes(exports) < expected + DISK_RESERVE_BYTES) {
                    throw new IOException("diagnostic_export_space");
                }
                File destination = prepareExport(report, includeHeap, Math.min(MAX_EXPORT_BYTES, expected));
                PINNED.add(report.directory.getPath());
                pinned = true;
                partial = new File(exports, destination.getName() + ".partial");
                activeExport = destination;
            } finally {
                LOCK.unlock();
            }
            byte[] buffer = new byte[16384];
            try (FileOutputStream output = create(partial);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.setLevel(1);
                zip.putNextEntry(new ZipEntry(report.id + "/metadata.properties"));
                zip.write(metadata(report));
                zip.closeEntry();
                zipFile(zip, artifact(report, ".txt"), report.id + "/" + report.id + ".txt", buffer, MAX_TEXT_CHARS * 4L + 2048);
                if (includeHeap && "completed".equals(report.heapStatus)) {
                    zipFile(zip, artifact(report, ".hprof"), report.id + "/" + report.id + ".hprof", buffer, report.heapBytes);
                }
                zip.finish();
                zip.flush();
                output.getFD().sync();
            }
            if (length(partial) > MAX_EXPORT_BYTES) throw new IOException("diagnostic_export_oversize");
            File result = new File(exports, partial.getName().replace(".partial", ""));
            LOCK.lock();
            try {
                trim(report.id, WRITE_RESERVE, false);
                if (stat(result) != null) throw new IOException("diagnostic_export_exists");
                move(partial, result);
            } finally {
                LOCK.unlock();
            }
            return result;
        } finally {
            if (partial != null) {
                try {
                    removeFile(partial);
                } catch (Throwable ignored) {
                }
            }
            if (pinned) {
                LOCK.lock();
                try {
                    PINNED.remove(report.directory.getPath());
                } finally {
                    LOCK.unlock();
                }
            }
            activeExport = null;
            EXPORTING.set(false);
        }
    }

    HeapTicket beginHeap(Report supplied, long estimatedBytes) throws IOException {
        acquire();
        try {
            Report report = owned(supplied);
            if (!"completed".equals(report.state) || !"not_requested".equals(report.heapStatus)
                    || PINNED.contains(report.directory.getPath())) {
                throw new IOException("diagnostic_heap_unavailable");
            }
            if (estimatedBytes > Long.MAX_VALUE - DISK_RESERVE_BYTES
                    || space.usableBytes(root) < estimatedBytes + DISK_RESERVE_BYTES) {
                throw new HeapDeniedException("low_disk");
            }
            long ownBytes = reportBytes(report);
            long reserve = Math.min(Math.max(0, MAX_PACKET_BYTES - WRITE_RESERVE - ownBytes), estimatedBytes);
            trim(report.id, reserve + WRITE_RESERVE, false);
            Report started = report.changed("started", "started", "", 0, 0, report.textBytes);
            save(started);
            File partial = artifact(report, ".hprof.partial");
            try {
                try (FileOutputStream ignored = create(partial)) {
                }
                HeapTicket ticket = new HeapTicket(started, partial, requiredFile(partial));
                PINNED.add(report.directory.getPath());
                return ticket;
            } catch (Throwable failure) {
                try {
                    removeFile(partial);
                    save(started.changed("failed", "failed", failureType(failure), 0, 0, report.textBytes));
                } catch (Throwable ignored) {
                }
                throw new IOException("diagnostic_heap_create");
            }
        } finally {
            LOCK.unlock();
        }
    }

    Report finishHeap(HeapTicket ticket, String status, String failure, long duration) throws IOException {
        LOCK.lock();
        try {
            Report report = owned(ticket.report);
            long bytes = 0;
            try {
                StructStat current = requiredFile(ticket.partial);
                bytes = current.st_size;
                if (current.st_ino != ticket.inode || current.st_dev != ticket.device) {
                    status = "failed";
                    failure = "file_changed";
                } else if ("completed".equals(status) && bytes == 0) {
                    status = "empty";
                } else if ("completed".equals(status) && (packetBytes() > MAX_PACKET_BYTES - WRITE_RESERVE
                        || exportBytes() > MAX_EXPORT_BYTES || totalBytes() > MAX_RETAINED_BYTES - WRITE_RESERVE)) {
                    try {
                        trim(report.id, WRITE_RESERVE, false);
                    } catch (IOException capacity) {
                        status = bytes > MAX_PACKET_BYTES - WRITE_RESERVE ? "oversize" : "capacity";
                    }
                }
                if ("completed".equals(status)) {
                    try (FileInputStream input = read(ticket.partial)) {
                        input.getFD().sync();
                    }
                    move(ticket.partial, artifact(report, ".hprof"));
                } else {
                    removeFile(ticket.partial);
                }
                Report result = report.changed("completed".equals(status) ? "completed" : "failed",
                        status, failure, bytes, Math.max(0, duration), report.textBytes);
                save(result);
                return result;
            } catch (IOException failureDuringFinish) {
                Report failed = report.changed("failed", "storage_failure", "storage", bytes, Math.max(0, duration), report.textBytes);
                try {
                    removeFile(ticket.partial);
                    save(failed);
                    return failed;
                } catch (Throwable ignored) {
                    return failed.unpersisted();
                }
            }
        } finally {
            PINNED.remove(ticket.report.directory.getPath());
            LOCK.unlock();
        }
    }

    Report rejectHeap(Report supplied, String status) throws IOException {
        acquire();
        try {
            Report report = owned(supplied);
            if (PINNED.contains(report.directory.getPath())) return report;
            if ("started".equals(report.heapStatus)) status = "storage_failure";
            else if (!"not_requested".equals(report.heapStatus)) return report;
            Report result = report.changed("failed", status, "", 0, 0, report.textBytes);
            try {
                save(result);
                return result;
            } catch (IOException failure) {
                return result.unpersisted();
            }
        } finally {
            LOCK.unlock();
        }
    }

    private Report owned(Report supplied) throws IOException {
        if (supplied == null || !root.equals(supplied.directory.getParentFile())) throw new IOException("diagnostic_owner");
        Report current = load(supplied.directory);
        if (current == null || !current.id.equals(supplied.id)) throw new IOException("diagnostic_stale_report");
        return current;
    }

    Report unpersistedHeapFailure(Report report, long duration) {
        if (report == null || !root.equals(report.directory.getParentFile())) return null;
        return report.changed("failed", "storage_failure", "storage", report.heapBytes,
                Math.max(0, duration), report.textBytes).unpersisted();
    }

    private void recoverLocked() throws IOException {
        directory(root, false);
        for (int i = 0; i < MAX_REPORTS; i++) {
            File slot = slot(i);
            if (stat(slot) == null || PINNED.contains(slot.getPath())) continue;
            directory(slot, false);
            File meta = new File(slot, "metadata.properties");
            File pending = new File(slot, "metadata.properties.partial");
            if (stat(meta) == null) {
                if (stat(pending) != null) move(pending, meta);
                else if (slot.delete()) continue;
                else throw new IOException("diagnostic_incomplete_directory");
            }
            Report report = load(slot);
            removeFile(pending);
            if ("started".equals(report.state)) {
                boolean heap = "started".equals(report.heapStatus);
                long bytes = length(artifact(report, ".hprof.partial"));
                if (heap && bytes == 0) bytes = length(artifact(report, ".hprof"));
                removeFile(artifact(report, ".hprof.partial"));
                if (heap) removeFile(artifact(report, ".hprof"));
                removeFile(artifact(report, ".txt.partial"));
                save(report.changed("interrupted", heap ? "interrupted" : report.heapStatus,
                        "", bytes, report.heapDurationMillis, length(artifact(report, ".txt"))));
            }
        }
    }

    private List<Report> reports() throws IOException {
        directory(root, false);
        List<Report> result = new ArrayList<>(MAX_REPORTS);
        for (int i = 0; i < MAX_REPORTS; i++) {
            Report report = load(slot(i));
            if (report != null) result.add(report);
        }
        return result;
    }

    private Report find(String id) throws IOException {
        if (!validId(id)) return null;
        for (Report report : reports()) if (report.id.equals(id)) return report;
        return null;
    }

    private long nextCreationOrder() throws IOException {
        long maximum = 0;
        for (Report report : reports()) maximum = Math.max(maximum, report.creationOrder);
        if (maximum == Long.MAX_VALUE) throw new IOException("diagnostic_order_exhausted");
        return maximum + 1;
    }

    private void trim(String protectedId, long reserve, boolean needSlot) throws IOException {
        List<Report> reports = reports();
        reports.sort(REPORT_ORDER);
        long packets = packetBytes();
        long exported = exportBytes();
        int count = reports.size();
        for (int i = 0; i < MAX_EXPORTS && (exported > MAX_EXPORT_BYTES
                || packets > MAX_RETAINED_BYTES - reserve - exported); i++) {
            File index = new File(exports, "export-" + i + ".index");
            if (stat(index) == null) continue;
            File file = new File(exports, exportName(index));
            if (file.equals(activeExport)) continue;
            removeFile(file);
            removeFile(new File(exports, file.getName() + ".partial"));
            removeFile(index);
            exported = exportBytes();
        }
        for (Report report : reports) {
            if (packets <= MAX_PACKET_BYTES - reserve && packets <= MAX_RETAINED_BYTES - reserve - exported
                    && (!needSlot || count < MAX_REPORTS)) break;
            if (report.id.equals(protectedId) || PINNED.contains(report.directory.getPath())) continue;
            long bytes = reportBytes(report);
            delete(report);
            packets -= bytes;
            count--;
        }
        if (packets > MAX_PACKET_BYTES - reserve || exported > MAX_EXPORT_BYTES
                || packets > MAX_RETAINED_BYTES - reserve - exported || (needSlot && count >= MAX_REPORTS)) {
            throw new IOException("diagnostic_capacity");
        }
    }

    private long totalBytes() throws IOException {
        long packets = packetBytes();
        long exported = exportBytes();
        return exported > Long.MAX_VALUE - packets ? Long.MAX_VALUE : packets + exported;
    }

    private long packetBytes() throws IOException {
        long total = 0;
        for (Report report : reports()) {
            long bytes = reportBytes(report);
            if (bytes > Long.MAX_VALUE - total) return Long.MAX_VALUE;
            total += bytes;
        }
        return total;
    }

    private long exportBytes() throws IOException {
        long total = 0;
        for (int i = 0; i < MAX_EXPORTS; i++) {
            File index = new File(exports, "export-" + i + ".index");
            if (stat(index) == null) continue;
            File zip = new File(exports, exportName(index));
            File[] files = {index, new File(exports, index.getName() + ".partial"), zip,
                    new File(exports, zip.getName() + ".partial")};
            for (File file : files) {
                long bytes = length(file);
                if (bytes > Long.MAX_VALUE - total) return Long.MAX_VALUE;
                total += bytes;
            }
        }
        return total;
    }

    private long reportBytes(Report report) throws IOException {
        long bytes = 0;
        for (File file : files(report)) {
            long size = length(file);
            if (size > Long.MAX_VALUE - bytes) return Long.MAX_VALUE;
            bytes += size;
        }
        return bytes;
    }

    private File[] files(Report report) {
        return new File[]{new File(report.directory, "metadata.properties"),
                new File(report.directory, "metadata.properties.partial"), artifact(report, ".txt"),
                artifact(report, ".txt.partial"), artifact(report, ".hprof"), artifact(report, ".hprof.partial")};
    }

    private void delete(Report report) throws IOException {
        directory(report.directory, false);
        File[] files = files(report);
        for (File file : files) length(file);
        for (int i = files.length - 1; i >= 0; i--) removeFile(files[i]);
        if (!report.directory.delete()) throw new IOException("diagnostic_directory_not_empty");
    }

    private File slot(int i) {
        return new File(root, "slot-" + i);
    }

    private File prepareExport(Report report, boolean includeHeap, long reserve) throws IOException {
        File[] existing = new File[MAX_EXPORTS];
        long total = 0;
        int slot = -1;
        for (int i = 0; i < MAX_EXPORTS; i++) {
            File index = new File(exports, "export-" + i + ".index");
            File pending = new File(exports, index.getName() + ".partial");
            if (stat(index) == null && stat(pending) != null) move(pending, index);
            if (stat(index) != null) {
                String name = exportName(index);
                existing[i] = new File(exports, name);
                removeFile(new File(exports, name + ".partial"));
                total += Math.min(MAX_EXPORT_BYTES, length(existing[i]));
            }
            removeFile(pending);
            if (slot < 0 && (existing[i] == null || stat(existing[i]) == null)) slot = i;
        }
        for (int pass = 0; pass < MAX_EXPORTS && (slot < 0 || total > MAX_EXPORT_BYTES - reserve); pass++) {
            int oldest = -1;
            for (int i = 0; i < MAX_EXPORTS; i++) {
                if (existing[i] != null && (oldest < 0 || existing[i].lastModified() < existing[oldest].lastModified())) oldest = i;
            }
            if (oldest < 0) break;
            total -= Math.min(MAX_EXPORT_BYTES, length(existing[oldest]));
            removeFile(existing[oldest]);
            removeFile(new File(exports, "export-" + oldest + ".index"));
            existing[oldest] = null;
            if (slot < 0) slot = oldest;
        }
        if (slot < 0 || total > MAX_EXPORT_BYTES - reserve) throw new IOException("diagnostic_export_capacity");
        String name = "packet-" + report.id + (includeHeap ? "-heap-" : "-text-") + UUID.randomUUID() + ".zip";
        File result = new File(exports, name);
        if (stat(result) != null || stat(new File(exports, name + ".partial")) != null) throw new IOException("diagnostic_export_exists");
        atomicWrite(new File(exports, "export-" + slot + ".index"), name.getBytes(StandardCharsets.US_ASCII));
        return result;
    }

    private static String exportName(File index) throws IOException {
        long size = length(index);
        if (size <= 0 || size > 128) throw new IOException("diagnostic_export_index");
        byte[] bytes = new byte[(int) size];
        try (FileInputStream input = read(index)) {
            int position = 0;
            while (position < bytes.length) {
                int count = input.read(bytes, position, bytes.length - position);
                if (count < 0) throw new IOException("diagnostic_export_index");
                position += count;
            }
            if (input.read() != -1) throw new IOException("diagnostic_export_index");
        }
        String name = new String(bytes, StandardCharsets.US_ASCII);
        String uuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
        if (!name.matches("packet-" + uuid + "-(text|heap)-" + uuid + "\\.zip")) throw new IOException("diagnostic_export_index");
        return name;
    }

    private static File artifact(Report report, String suffix) {
        return new File(report.directory, report.id + suffix);
    }

    private static void save(Report report) throws IOException {
        atomicWrite(new File(report.directory, "metadata.properties"), metadata(report));
    }

    private static byte[] metadata(Report report) {
        return ("schema=1\nid=" + report.id + "\nsession=" + report.sessionId + "\nmapping=" + report.mappingId
                + "\nversion=" + report.version + "\ntype=" + report.type + "\nstate=" + report.state
                + "\nheapStatus=" + report.heapStatus + "\nfailureType=" + report.failureType
                + "\ncreated=" + report.createdWallMillis + "\ncreationOrder=" + report.creationOrder + "\nheapBytes=" + report.heapBytes
                + "\nheapDurationMillis=" + report.heapDurationMillis + "\ntextBytes=" + report.textBytes + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Report load(File directory) throws IOException {
        if (stat(directory) == null) return null;
        directory(directory, false);
        File file = new File(directory, "metadata.properties");
        long length = length(file);
        if (length <= 0 || length > MAX_METADATA_BYTES) throw new IOException("diagnostic_metadata");
        byte[] data = new byte[(int) length];
        try (FileInputStream input = read(file)) {
            int position = 0;
            while (position < data.length) {
                int count = input.read(data, position, data.length - position);
                if (count < 0) throw new IOException("diagnostic_metadata_short");
                position += count;
            }
            if (input.read() != -1) throw new IOException("diagnostic_metadata_long");
        }
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(data));
        try {
            String id = p.getProperty("id");
            String state = p.getProperty("state");
            String heap = p.getProperty("heapStatus");
            if (!"1".equals(p.getProperty("schema")) || !validId(id)
                    || !("started".equals(state) || "completed".equals(state) || "failed".equals(state) || "interrupted".equals(state))
                    || heap == null || !heap.equals(token(heap))) throw new IOException("diagnostic_metadata_values");
            long created = number(p, "created"), bytes = number(p, "heapBytes");
            long order = p.containsKey("creationOrder") ? number(p, "creationOrder") : 0;
            long duration = number(p, "heapDurationMillis"), text = number(p, "textBytes");
            return new Report(directory, id, token(p.getProperty("session")), token(p.getProperty("mapping")),
                    token(p.getProperty("version")), type(p.getProperty("type")), state, heap,
                    token(p.getProperty("failureType")), created, order, bytes, duration, text);
        } catch (RuntimeException failure) {
            throw new IOException("diagnostic_metadata_values");
        }
    }

    private static long number(Properties p, String name) {
        long value = Long.parseLong(p.getProperty(name));
        if (value < 0) throw new IllegalArgumentException();
        return value;
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static String token(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() > 128) return "unknown";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')
                    && c != '_' && c != '-' && c != '.' && c != '/' && c != '(' && c != ')' && c != ' ') return "unknown";
        }
        return value;
    }

    private static String type(String value) {
        return "manual".equals(value) || "fatal".equals(value) || "oom".equals(value)
                || "oom_observed".equals(value) || "early".equals(value) || "stall".equals(value) ? value : "unknown";
    }

    static String failureType(Throwable failure) {
        if (failure instanceof OutOfMemoryError) return "out_of_memory";
        if (failure instanceof IOException) return "io";
        if (failure instanceof SecurityException) return "security";
        if (failure instanceof UnsupportedOperationException) return "unsupported";
        return "other";
    }

    private static Context workerContext(Context context) throws IOException {
        requireWorker();
        if (context == null) throw new IOException("diagnostic_context");
        return context;
    }

    static void requireWorker() throws IOException {
        Looper main = Looper.getMainLooper();
        if (main != null && main == Looper.myLooper()) throw new IOException("diagnostic_worker_required");
    }

    private static void acquire() throws IOException {
        if (!LOCK.tryLock()) throw new IOException("diagnostic_store_busy");
    }

    private static File anchor(File file) throws IOException {
        if (file == null) throw new IOException("diagnostic_directory");
        StructStat original = rawStat(file);
        if (original == null || !OsConstants.S_ISDIR(original.st_mode)) throw new IOException("diagnostic_directory");
        File canonical = file.getCanonicalFile();
        directory(canonical, false);
        return canonical;
    }

    private static void parent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || !parent.equals(parent.getCanonicalFile())) throw new IOException("diagnostic_path");
        StructStat s = rawStat(parent);
        if (s == null || !OsConstants.S_ISDIR(s.st_mode)) throw new IOException("diagnostic_parent");
    }

    private static StructStat rawStat(File file) throws IOException {
        try {
            return Os.lstat(file.getPath());
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return null;
            throw new IOException("diagnostic_stat");
        }
    }

    private static StructStat stat(File file) throws IOException {
        parent(file);
        StructStat s = rawStat(file);
        if (s != null && !(OsConstants.S_ISDIR(s.st_mode) || OsConstants.S_ISREG(s.st_mode) && s.st_nlink == 1)) {
            throw new IOException("diagnostic_file_type");
        }
        return s;
    }

    private static StructStat requiredFile(File file) throws IOException {
        StructStat s = stat(file);
        if (s == null || !OsConstants.S_ISREG(s.st_mode)) throw new IOException("diagnostic_file");
        return s;
    }

    private static long length(File file) throws IOException {
        StructStat s = stat(file);
        if (s == null) return 0;
        if (!OsConstants.S_ISREG(s.st_mode) || s.st_size < 0) throw new IOException("diagnostic_file");
        return s.st_size;
    }

    private static void directory(File file, boolean create) throws IOException {
        StructStat s = stat(file);
        if (s == null && create) {
            try {
                Os.mkdir(file.getPath(), 0700);
            } catch (ErrnoException e) {
                throw new IOException("diagnostic_mkdir");
            }
            s = stat(file);
        }
        if (s == null || !OsConstants.S_ISDIR(s.st_mode) || !file.equals(file.getCanonicalFile())) {
            throw new IOException("diagnostic_directory");
        }
    }

    private static FileOutputStream create(File file) throws IOException {
        parent(file);
        FileDescriptor fd = null;
        try {
            fd = Os.open(file.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                    | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            return new OwnedOutputStream(fd);
        } catch (ErrnoException e) {
            throw new IOException("diagnostic_create");
        } catch (RuntimeException | Error failure) {
            try {
                closeDescriptor(fd);
            } catch (Throwable ignored) {
            }
            throw failure;
        }
    }

    private static FileInputStream read(File file) throws IOException {
        StructStat before = requiredFile(file);
        FileInputStream result = null;
        FileDescriptor fd = null;
        try {
            fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
            result = new OwnedInputStream(fd);
            StructStat after = Os.fstat(fd);
            if (!OsConstants.S_ISREG(after.st_mode) || after.st_nlink != 1
                    || before.st_dev != after.st_dev || before.st_ino != after.st_ino) throw new IOException("diagnostic_file_changed");
            return result;
        } catch (ErrnoException | IOException failure) {
            if (result != null) result.close();
            else closeDescriptor(fd);
            throw new IOException("diagnostic_read");
        } catch (RuntimeException | Error failure) {
            try {
                if (result != null) result.close();
                else closeDescriptor(fd);
            } catch (Throwable ignored) {
            }
            throw failure;
        }
    }

    private static void closeDescriptor(FileDescriptor fd) throws IOException {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.EBADF) throw new IOException("diagnostic_close");
        }
    }

    private static final class OwnedInputStream extends FileInputStream {
        private final FileDescriptor descriptor;
        private boolean closed;

        OwnedInputStream(FileDescriptor descriptor) {
            super(descriptor);
            this.descriptor = descriptor;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                super.close();
            } finally {
                closeDescriptor(descriptor);
            }
        }
    }

    private static final class OwnedOutputStream extends FileOutputStream {
        private final FileDescriptor descriptor;
        private boolean closed;

        OwnedOutputStream(FileDescriptor descriptor) {
            super(descriptor);
            this.descriptor = descriptor;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                super.close();
            } finally {
                closeDescriptor(descriptor);
            }
        }
    }

    private static void atomicWrite(File target, byte[] data) throws IOException {
        File partial = new File(target.getParentFile(), target.getName() + ".partial");
        length(target);
        removeFile(partial);
        try (FileOutputStream output = create(partial)) {
            output.write(data);
            output.getFD().sync();
        }
        move(partial, target);
    }

    private static void move(File source, File target) throws IOException {
        requiredFile(source);
        length(target);
        try {
            Os.rename(source.getPath(), target.getPath());
        } catch (ErrnoException e) {
            throw new IOException("diagnostic_rename");
        }
    }

    private static void removeFile(File file) throws IOException {
        if (stat(file) == null) return;
        requiredFile(file);
        if (!file.delete()) throw new IOException("diagnostic_delete");
    }

    private static void zipFile(ZipOutputStream zip, File file, String name, byte[] buffer, long maximum) throws IOException {
        long expected = length(file);
        if (expected <= 0 || expected > maximum) throw new IOException("diagnostic_export_file");
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream input = read(file)) {
            long copied = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > expected) throw new IOException("diagnostic_export_changed");
                zip.write(buffer, 0, count);
            }
            if (copied != expected) throw new IOException("diagnostic_export_changed");
        }
        zip.closeEntry();
    }
}
