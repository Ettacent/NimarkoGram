/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.diagnostics;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

public final class NativeInvocationJournal {
    private static final long MAGIC = 0x4e47444941473431L;
    private static final int VERSION = 4;
    private static final int HEADER = 4096;
    private static final int META_COUNT = 512;
    private static final int META_SIZE = 512;
    private static final int THREAD_COUNT = 64;
    private static final int DEPTH = 32;
    private static final int HISTORY = 64;
    private static final int RECORD_SIZE = 128;
    private static final int THREAD_SIZE = 16384;
    private static final int THREAD_BASE = HEADER + META_COUNT * META_SIZE;
    public static final int FILE_SIZE = THREAD_BASE + THREAD_COUNT * THREAD_SIZE;
    private static final int BEGIN = 1, PHASE = 2, END = 3, ABANDONED = 4;
    private static final String[] PHASES = {
            "begin", "adapter_before_enter", "callback_before_enter", "callback_before_exit",
            "before_bridge_return", "before_bridge_throw", "callback_after_enter", "callback_after_exit",
            "after_bridge_return", "adapter_scope_closed", "after_bridge_return_skipped",
            "after_bridge_return_replacement", "runnable_enter", "runnable_exit",
            "interface_enter", "interface_exit", "engine_enter", "engine_exit", "request_enter",
            "request_exit", "end", "abandoned", "unknown",
            "before_proxy_check", "before_result_check", "before_args_check", "before_receiver_check",
            "after_receiver_check", "after_args_check", "after_result_check",
            "adapter_claim", "before_scan_gate", "before_bypass_check", "before_baseline_receiver_scan",
            "before_baseline_scan", "before_bridge_return_skipped", "after_enter",
            "after_skipped_result_scan", "after_capture", "after_baseline_result_scan", "after_baseline_scan"
    };
    private final MappedByteBuffer storage;
    private final ThreadLocal<State> local = new ThreadLocal<>();
    private final State[] owners = new State[THREAD_COUNT];
    private final ReentrantLock ownerLock = new ReentrantLock();
    private final ConcurrentHashMap<Key, Metadata> metadata = new ConcurrentHashMap<>();
    private final AtomicInteger nextMetadata = new AtomicInteger();
    private volatile boolean disabled;

    public NativeInvocationJournal(File file, String session, String mapping, int pid,
                                   long wallMillis, long elapsedNanos) throws IOException {
        this(file, session, mapping, pid, wallMillis, elapsedNanos, 0);
    }

    public NativeInvocationJournal(File file, String session, String mapping, int pid,
                                   long wallMillis, long elapsedNanos, long startupOrder) throws IOException {
        if (!session.matches("[a-f0-9]{32}") || pid <= 0 || startupOrder < 0 || file.exists()) {
            throw new IOException("Invalid journal identity");
        }
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(FILE_SIZE);
            storage = output.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
            storage.order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset < FILE_SIZE; offset += 4096) storage.put(offset, (byte) 0);
            storage.putInt(8, VERSION);
            storage.putInt(12, FILE_SIZE);
            storage.putInt(16, pid);
            storage.putInt(20, 5);
            storage.putLong(24, wallMillis);
            storage.putLong(32, elapsedNanos);
            storage.putLong(264, startupOrder);
            putText(storage, 64, 64, session);
            putText(storage, 132, 96, mapping);
            storage.putLong(0, MAGIC);
            storage.force();
        }
    }

    public long begin(long nowNanos, int nativeTid) {
        if (disabled) return 0;
        try {
            State state = state(nativeTid, nowNanos);
            if (state == null) {
                storage.putInt(512, 1);
                return 0;
            }
            if (state.depth == DEPTH || state.counter == 0xffffffffL) {
                storage.putInt(516, 1);
                return 0;
            }
            long token = (state.generation << 40) | ((long) state.slot << 32) | ++state.counter;
            int depth = state.depth++;
            state.tokens[depth] = token;
            state.started[depth] = nowNanos;
            state.meta[depth] = 0;
            state.phases[depth] = 0;
            write(state, depth, BEGIN, nowNanos);
            return token;
        } catch (Throwable failure) {
            disable();
            return 0;
        }
    }

    public long startedMillis(long token) {
        State state = local.get();
        int depth = find(state, token);
        return depth < 0 ? 0 : state.started[depth] / 1000000L;
    }

    public static boolean ownsToken(long token) {
        return token > 0 && token >>> 40 != 0;
    }

    public void phase(long token, long nowNanos, String phase, String domain,
                      String target, String plugin, String runtime) {
        if (token == 0 || disabled) return;
        try {
            State state = local.get();
            int depth = find(state, token);
            if (depth < 0) {
                storage.putInt(524, 1);
                return;
            }
            if (state.meta[depth] == 0) state.meta[depth] = metadata(state, domain, target, plugin, runtime);
            state.phases[depth] = phaseCode(phase);
            write(state, depth, PHASE, nowNanos);
        } catch (Throwable failure) {
            disable();
        }
    }

    public void end(long token, long nowNanos) {
        if (token == 0 || disabled) return;
        try {
            State state = local.get();
            int depth = find(state, token);
            if (depth < 0) {
                storage.putInt(524, 1);
                return;
            }
            while (state.depth > depth + 1) {
                storage.putInt(524, 1);
                int abandoned = --state.depth;
                state.phases[abandoned] = 21;
                write(state, abandoned, ABANDONED, nowNanos);
                state.tokens[abandoned] = 0;
            }
            state.phases[depth] = 20;
            write(state, depth, END, nowNanos);
            state.tokens[depth] = 0;
            state.depth = depth;
        } catch (Throwable failure) {
            disable();
        }
    }

    private void disable() {
        disabled = true;
        try {
            storage.putInt(528, 1);
        } catch (Throwable ignored) {
        }
    }

    private State state(int nativeTid, long now) {
        State state = local.get();
        if (state != null && state.slot >= 0) return state;
        if (state != null && now < state.retryAfter) return null;
        if (state == null) {
            state = new State(null, -1, 0, nativeTid);
            local.set(state);
        }
        state.retryAfter = now + 1000000000L;
        if (!ownerLock.tryLock()) return null;
        try {
            for (int slot = 0; slot < THREAD_COUNT; slot++) {
                State old = owners[slot];
                Thread thread = old == null ? null : old.thread.get();
                if (thread != null && thread.isAlive()) continue;
                long generation = old == null ? 1 : old.generation + 1;
                if (generation >= 0x800000L) continue;
                state = new State(storage.duplicate().order(ByteOrder.LITTLE_ENDIAN), slot, generation, nativeTid);
                int base = THREAD_BASE + slot * THREAD_SIZE;
                state.buffer.putLong(base, 0);
                state.buffer.putInt(base + 8, nativeTid);
                state.buffer.putLong(base + 16, state.javaId);
                state.buffer.putLong(base, generation);
                owners[slot] = state;
                local.set(state);
                return state;
            }
            return null;
        } finally {
            ownerLock.unlock();
        }
    }

    private int metadata(State state, String domain, String target, String plugin, String runtime) {
        if (domain != null && domain.length() > 16 || target != null && target.length() > 256
                || plugin != null && plugin.length() > 64 || runtime != null && runtime.length() > 48) {
            storage.putInt(520, 1);
            storage.putInt(536, 1);
            return 0;
        }
        state.lookup.set(domain, target, plugin, runtime);
        Metadata found = metadata.get(state.lookup);
        if (found != null) return metadataId(found);
        if (nextMetadata.get() >= META_COUNT) {
            storage.putInt(520, 1);
            storage.putInt(540, 1);
            return 0;
        }
        Key key = new Key(domain, target, plugin, runtime);
        Metadata entry = new Metadata();
        Metadata previous = metadata.putIfAbsent(key, entry);
        if (previous != null) return metadataId(previous);
        int id = nextMetadata.incrementAndGet();
        if (id > META_COUNT) {
            entry.id = -1;
            storage.putInt(520, 1);
            storage.putInt(540, 1);
            return 0;
        }
        byte[] bytes = ("domain=" + utf8(key.domain, 24) + " target=" + utf8(key.target, 256)
                + " plugin=" + utf8(key.plugin, 96) + " runtime=" + utf8(key.runtime, 64)).getBytes(StandardCharsets.UTF_8);
        int length = Math.min(480, bytes.length);
        int offset = HEADER + (id - 1) * META_SIZE;
        ByteBuffer buffer = state.buffer;
        buffer.putInt(offset, 0);
        buffer.putInt(offset + 4, length);
        long checksum = 0xcbf29ce484222325L;
        for (int i = 0; i < length; i++) {
            buffer.put(offset + 16 + i, bytes[i]);
            checksum = (checksum ^ (bytes[i] & 255)) * 0x100000001b3L;
        }
        buffer.putLong(offset + 8, checksum);
        buffer.putInt(offset, id);
        entry.id = id;
        return id;
    }

    private int metadataId(Metadata metadata) {
        int id = metadata.id;
        if (id == 0) storage.putInt(532, 1);
        return Math.max(0, id);
    }

    private static String utf8(String value, int limit) {
        byte[] bytes = safe(value, 256).getBytes(StandardCharsets.UTF_8);
        return bytes.length <= limit ? new String(bytes, StandardCharsets.UTF_8)
                : new String(bytes, 0, limit - 16, StandardCharsets.UTF_8) + "[truncated]";
    }

    private static int find(State state, long token) {
        if (state == null || token == 0) return -1;
        for (int i = state.depth - 1; i >= 0; i--) if (state.tokens[i] == token) return i;
        return -1;
    }

    private static int phaseCode(String phase) {
        for (int i = 0; i < PHASES.length; i++) if (PHASES[i].equals(phase)) return i;
        return 22;
    }

    private static void write(State state, int depth, int kind, long now) {
        long sequence = ++state.sequence;
        long token = state.tokens[depth];
        long parent = depth == 0 ? 0 : state.tokens[depth - 1];
        int base = THREAD_BASE + state.slot * THREAD_SIZE + RECORD_SIZE;
        ByteBuffer b = state.payloadView;
        b.putLong(8, now);
        b.putLong(16, token);
        b.putLong(24, parent);
        b.putLong(32, state.javaId);
        b.putLong(40, state.generation);
        b.putInt(48, state.nativeTid);
        b.putInt(52, depth);
        b.putInt(56, kind);
        b.putInt(60, state.meta[depth]);
        b.putInt(64, state.phases[depth]);
        b.putLong(72, state.started[depth]);
        state.crc.reset();
        state.crc.update(state.payload, 8, 72);
        b.putLong(80, state.crc.getValue());
        b.putLong(88, sequence);
        copyRecord(state, base + depth * RECORD_SIZE, sequence);
        copyRecord(state, base + (DEPTH + (int) ((sequence - 1) % HISTORY)) * RECORD_SIZE, sequence);
    }

    private static void copyRecord(State state, int offset, long sequence) {
        state.buffer.putLong(offset, 0);
        state.buffer.putLong(offset + 88, 0);
        state.buffer.position(offset + 8);
        state.buffer.put(state.payload, 8, 88);
        state.buffer.putLong(offset, sequence);
    }

    private static void putText(ByteBuffer buffer, int offset, int limit, String value) {
        byte[] data = safe(value, limit).getBytes(StandardCharsets.UTF_8);
        int length = Math.min(limit, data.length);
        buffer.putInt(offset, length);
        for (int i = 0; i < length; i++) buffer.put(offset + 4 + i, data[i]);
    }

    private static String getText(ByteBuffer buffer, int offset, int limit) throws IOException {
        int length = buffer.getInt(offset);
        if (length < 0 || length > limit) throw new IOException("Invalid journal text");
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = buffer.get(offset + 4 + i);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String safe(String value, int limit) {
        if (value == null) return "";
        String result = value.replace('\n', ' ').replace('\r', ' ');
        return result.length() > limit ? result.substring(0, limit) : result;
    }

    public static Snapshot read(File file, int nativeTid) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() != FILE_SIZE) throw new IOException("Invalid journal size");
            ByteBuffer b = input.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            Snapshot result = header(b);
            result.selectedTid = nativeTid;
            CRC32 crc = new CRC32();
            byte[] payload = new byte[72];
            Map<Integer, String> descriptions = new HashMap<>();
            PriorityQueue<Entry> recent = new PriorityQueue<>(64, Comparator.comparingLong(e -> e.timeNanos));
            PriorityQueue<Entry> active = new PriorityQueue<>(64, Comparator.comparingLong(e -> e.timeNanos));
            for (int slot = 0; slot < THREAD_COUNT; slot++) {
                int base = THREAD_BASE + slot * THREAD_SIZE;
                long generation = b.getLong(base);
                if (generation <= 0) continue;
                int tid = b.getInt(base + 8);
                if (nativeTid != 0 && tid != nativeTid) continue;
                result.threads++;
                for (int i = 0; i < DEPTH + HISTORY; i++) {
                    int offset = base + RECORD_SIZE + i * RECORD_SIZE;
                    long sequence = b.getLong(offset);
                    if (sequence == 0) continue;
                    if (b.getLong(offset + 40) != generation) {
                        result.stale++;
                        continue;
                    }
                    b.position(offset + 8);
                    b.get(payload);
                    crc.reset();
                    crc.update(payload);
                    if (sequence < 0 || sequence != b.getLong(offset + 88)
                            || b.getLong(offset + 40) != generation || b.getInt(offset + 48) != tid
                            || b.getLong(offset + 80) != crc.getValue()) {
                        result.invalid++;
                        continue;
                    }
                    int kind = b.getInt(offset + 56), depth = b.getInt(offset + 52), phase = b.getInt(offset + 64);
                    long token = b.getLong(offset + 16), parent = b.getLong(offset + 24);
                    long time = b.getLong(offset + 8), started = b.getLong(offset + 72), javaId = b.getLong(offset + 32);
                    if (kind < BEGIN || kind > ABANDONED || depth < 0 || depth >= DEPTH
                            || phase < 0 || phase >= PHASES.length || !ownsToken(token)
                            || token >>> 40 != generation || (token >>> 32 & 255) != slot
                            || tid <= 0 || javaId <= 0 || javaId != b.getLong(base + 16)
                            || started < 0 || time < started || (i < DEPTH && depth != i)
                            || (depth == 0 ? parent != 0 : !ownsToken(parent) || parent == token
                            || parent >>> 40 != generation || (parent >>> 32 & 255) != slot)
                            || kind == BEGIN && phase != 0 || kind == END && phase != 20
                            || kind == ABANDONED && phase != 21) {
                        result.invalid++;
                        continue;
                    }
                    if (i < DEPTH && kind != BEGIN && kind != PHASE) continue;
                    Entry entry = new Entry();
                    entry.sequence = sequence;
                    entry.timeNanos = b.getLong(offset + 8);
                    entry.token = b.getLong(offset + 16);
                    entry.parent = b.getLong(offset + 24);
                    entry.javaId = b.getLong(offset + 32);
                    entry.nativeTid = tid;
                    entry.depth = depth;
                    entry.kind = kind;
                    entry.stage = PHASES[phase];
                    entry.startedNanos = b.getLong(offset + 72);
                    int meta = b.getInt(offset + 60);
                    String description = descriptions.get(meta);
                    if (description == null) {
                        description = readMetadata(b, meta);
                        descriptions.put(meta, description);
                    }
                    entry.description = description;
                    if (i < DEPTH) result.activeObserved++;
                    else result.recentObserved++;
                    if ("metadata_unavailable".equals(description) || "metadata_invalid".equals(description)) {
                        result.metadataMissing++;
                    }
                    PriorityQueue<Entry> queue = i < DEPTH ? active : recent;
                    queue.add(entry);
                    if (queue.size() > 64) {
                        queue.poll();
                        result.truncated++;
                    }
                }
            }
            result.active.addAll(active);
            result.recent.addAll(recent);
            Comparator<Entry> order = Comparator.comparingLong((Entry entry) -> entry.timeNanos).reversed();
            result.active.sort(order);
            result.recent.sort(order);
            result.recordsRead = true;
            return result;
        }
    }

    public static Snapshot readHeader(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() != FILE_SIZE) throw new IOException("Invalid journal size");
            byte[] bytes = new byte[HEADER];
            input.readFully(bytes);
            return header(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
        }
    }

    private static Snapshot header(ByteBuffer b) throws IOException {
        if (b.getLong(0) != MAGIC || b.getInt(8) != VERSION || b.getInt(12) != FILE_SIZE) throw new IOException("Invalid journal header");
        Snapshot result = new Snapshot();
        result.pid = b.getInt(16);
        result.diagnosticRevision = b.getInt(20) == 0 ? 4 : b.getInt(20);
        result.wallMillis = b.getLong(24);
        result.elapsedNanos = b.getLong(32);
        result.startupOrder = b.getLong(264);
        result.reportedExitMillis = b.getLong(280);
        result.session = getText(b, 64, 64);
        result.mapping = getText(b, 132, 96);
        if (result.pid <= 0 || result.startupOrder < 0 || !result.session.matches("[a-f0-9]{32}")) throw new IOException("Invalid journal identity");
        for (int i = 0; i < 8; i++) if (b.getInt(512 + 4 * i) != 0) result.flags |= 1 << i;
        return result;
    }

    private static String readMetadata(ByteBuffer b, int id) {
        if (id <= 0 || id > META_COUNT) return "metadata_unavailable";
        int offset = HEADER + (id - 1) * META_SIZE;
        int length = b.getInt(offset + 4);
        if (b.getInt(offset) != id || length < 0 || length > 480) return "metadata_invalid";
        byte[] data = new byte[length];
        long checksum = 0xcbf29ce484222325L;
        for (int i = 0; i < length; i++) {
            data[i] = b.get(offset + 16 + i);
            checksum = (checksum ^ (data[i] & 255)) * 0x100000001b3L;
        }
        return checksum == b.getLong(offset + 8) ? new String(data, StandardCharsets.UTF_8) : "metadata_invalid";
    }

    public static final class Snapshot {
        public String session, mapping;
        public int pid, flags, threads, invalid, truncated, stale, diagnosticRevision;
        public int selectedTid, activeObserved, recentObserved, metadataMissing;
        public boolean recordsRead;
        public long wallMillis, elapsedNanos, startupOrder, reportedExitMillis;
        public final List<Entry> active = new ArrayList<>();
        public final List<Entry> recent = new ArrayList<>();
    }

    public static final class Entry {
        public long sequence, timeNanos, startedNanos, token, parent, javaId;
        public int nativeTid, depth, kind;
        public String stage, description;

        public String describe() {
            return "tid=" + nativeTid + " jtid=" + javaId + " seq=" + sequence + " t_ns=" + timeNanos
                    + " start_ns=" + startedNanos + " token=" + token + " parent=" + parent
                    + " depth=" + depth + " kind=" + kind + " phase=" + stage + " " + description;
        }
    }

    private static final class State {
        final ByteBuffer buffer;
        final int slot, nativeTid;
        final long javaId, generation;
        final WeakReference<Thread> thread;
        final long[] tokens, started;
        final int[] meta, phases;
        final Key lookup = new Key(null, null, null, null);
        final byte[] payload = new byte[RECORD_SIZE];
        final ByteBuffer payloadView = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        final CRC32 crc = new CRC32();
        long counter, sequence, retryAfter;
        int depth;

        State(ByteBuffer buffer, int slot, long generation, int nativeTid) {
            this.buffer = buffer;
            this.slot = slot;
            this.generation = generation;
            this.nativeTid = nativeTid;
            int size = slot < 0 ? 0 : DEPTH;
            tokens = new long[size];
            started = new long[size];
            meta = new int[size];
            phases = new int[size];
            javaId = Thread.currentThread().getId();
            thread = new WeakReference<>(Thread.currentThread());
        }
    }

    private static final class Metadata {
        volatile int id;
    }

    private static final class Key {
        String domain, target, plugin, runtime;
        int hash;

        Key(String domain, String target, String plugin, String runtime) {
            set(domain, target, plugin, runtime);
        }

        void set(String domain, String target, String plugin, String runtime) {
            this.domain = domain == null ? "" : domain;
            this.target = target == null ? "" : target;
            this.plugin = plugin == null ? "" : plugin;
            this.runtime = runtime == null ? "" : runtime;
            hash = ((this.domain.hashCode() * 31 + this.target.hashCode()) * 31 + this.plugin.hashCode()) * 31 + this.runtime.hashCode();
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return domain.equals(key.domain) && target.equals(key.target) && plugin.equals(key.plugin) && runtime.equals(key.runtime);
        }
    }
}
