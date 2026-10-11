/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NativeTombstone {
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_FIELDS = 262144;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_THREADS = 128;
    private static final int MAX_MAPPINGS = 4096;
    private static final String REGISTER_NAMES = "|pc|sp|lr|fp|ip|eip|esp|ebp|rip|rsp|rbp|r13|r14|r15|";
    private static final String ADDRESS_REGISTERS = "|pc|sp|lr|eip|esp|rip|rsp|r13|r14|r15|";

    private NativeTombstone() {
    }

    public static final class Summary {
        public final int pid, tid, arch, signal, signalCode;
        public final long faultAddress, processUptime;
        public final boolean hasFaultAddress, truncated;
        public final String status;
        public final String abortCategory;
        public final List<Frame> frames;
        public final Map<String, Long> registers;
        public final List<Mapping> mappings;
        public final MemoryError memoryError;

        private Summary(State s, String failure) {
            pid = s.pid;
            tid = s.tid;
            arch = s.arch;
            signal = s.signal;
            signalCode = s.signalCode;
            hasFaultAddress = s.hasFaultAddress;
            faultAddress = hasFaultAddress ? s.faultAddress : 0;
            processUptime = s.processUptime;
            memoryError = s.memoryError;
            abortCategory = s.abortCategory;
            ThreadData thread = s.threads.get(tid);
            if (thread != null && (!thread.hasValue || thread.hasId && thread.id != tid)) {
                thread = null;
            }
            if (tid <= 0) {
                thread = null;
            }
            frames = thread == null ? Collections.<Frame>emptyList() : frozen(thread.frames);
            registers = thread == null ? Collections.<String, Long>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(thread.registers));
            ArrayList<Long> addresses = new ArrayList<>();
            if (hasFaultAddress) {
                addresses.add(faultAddress);
            }
            for (Map.Entry<String, Long> register : registers.entrySet()) {
                if (ADDRESS_REGISTERS.contains("|" + register.getKey() + "|")) {
                    addresses.add(register.getValue());
                }
            }
            if (!frames.isEmpty()) {
                Frame frame = frames.get(0);
                if (frame.pc != 0) {
                    addresses.add(frame.pc);
                }
                if (frame.sp != 0) {
                    addresses.add(frame.sp);
                }
            }
            ArrayList<Mapping> matched = new ArrayList<>();
            for (Mapping mapping : s.mappings) {
                for (long address : addresses) {
                    if (unsignedCompare(address, mapping.begin) >= 0
                            && unsignedCompare(address, mapping.end) < 0) {
                        if (matched.size() < 8) {
                            matched.add(mapping);
                        } else {
                            s.limited = true;
                        }
                        break;
                    }
                }
            }
            mappings = frozen(matched);
            if (failure != null) {
                status = failure;
            } else if (!s.anyField) {
                status = "empty";
            } else if (pid <= 0 || signal <= 0) {
                status = "incomplete";
            } else if (thread == null) {
                status = s.threadsLimited ? "limited" : "missing_crashing_thread";
            } else {
                status = s.limited || thread.limited ? "limited" : "ok";
            }
            truncated = !"ok".equals(status) && !"unavailable".equals(status);
        }
    }

    public static final class Frame {
        public final long pc, relPc, sp, functionOffset, fileMapOffset;
        public final String function, module, buildId;

        private Frame(long pc, long relPc, long sp, long functionOffset, long fileMapOffset,
                      String function, String module, String buildId) {
            this.pc = pc;
            this.relPc = relPc;
            this.sp = sp;
            this.functionOffset = functionOffset;
            this.fileMapOffset = fileMapOffset;
            this.function = function;
            this.module = module;
            this.buildId = buildId;
        }
    }

    public static final class Mapping {
        public final long begin, end, offset, loadBias;
        public final boolean read, write, execute;
        public final String module, buildId;

        private Mapping(long begin, long end, long offset, long loadBias, boolean read,
                        boolean write, boolean execute, String module, String buildId) {
            this.begin = begin;
            this.end = end;
            this.offset = offset;
            this.loadBias = loadBias;
            this.read = read;
            this.write = write;
            this.execute = execute;
            this.module = module;
            this.buildId = buildId;
        }
    }

    public static final class MemoryError {
        public final int tool, type;
        public final HeapObject heap;

        private MemoryError(int tool, int type, HeapObject heap) {
            this.tool = tool;
            this.type = type;
            this.heap = heap;
        }
    }

    public static final class HeapObject {
        public final long address, size, allocationTid, deallocationTid;
        public final List<Frame> allocationFrames, deallocationFrames;

        private HeapObject(long address, long size, long allocationTid, long deallocationTid,
                           List<Frame> allocationFrames, List<Frame> deallocationFrames) {
            this.address = address;
            this.size = size;
            this.allocationTid = allocationTid;
            this.deallocationTid = deallocationTid;
            this.allocationFrames = frozen(allocationFrames);
            this.deallocationFrames = frozen(deallocationFrames);
        }
    }

    public static Summary parse(InputStream input) {
        State state = new State();
        if (input == null) {
            return new Summary(state, "unavailable");
        }
        String failure = null;
        try {
            Reader r = new Reader(new Source(input, state), Long.MAX_VALUE, 0);
            int tag;
            while ((tag = r.tag()) != 0) {
                state.anyField = true;
                switch (tag >>> 3) {
                    case 1: state.arch = r.int32(tag); break;
                    case 5: state.pid = (int) r.uint32(tag); break;
                    case 6: state.tid = (int) r.uint32(tag); break;
                    case 10: readSignal(r.message(tag), state); break;
                    case 14: state.abortCategory = r.abortCategory(tag); break;
                    case 15: readCause(r.message(tag), state); break;
                    case 16: readThreadEntry(r.message(tag), state); break;
                    case 17:
                        Mapping mapping = readMapping(r.message(tag));
                        if (state.mappings.size() < MAX_MAPPINGS) {
                            state.mappings.add(mapping);
                        } else {
                            state.limited = true;
                        }
                        break;
                    case 20: state.processUptime = r.uint32(tag); break;
                    case 2:
                    case 3:
                    case 4:
                    case 8:
                    case 9:
                    case 18:
                    case 19:
                    case 21:
                    case 25:
                    case 26: r.skipAs(tag, 2); break;
                    case 7:
                    case 22:
                    case 23:
                    case 24: r.skipAs(tag, 0); break;
                    default: r.skip(tag);
                }
            }
        } catch (ParseFailure e) {
            failure = e.status;
        } catch (IOException e) {
            failure = "io_error";
        }
        return new Summary(state, failure);
    }

    private static void readSignal(Reader r, State s) throws IOException {
        int number = s.signal, code = s.signalCode;
        boolean hasAddress = s.hasFaultAddress;
        long address = s.faultAddress;
        int tag;
        while ((tag = r.tag()) != 0) {
            switch (tag >>> 3) {
                case 1: number = r.int32(tag); break;
                case 3: code = r.int32(tag); break;
                case 8: hasAddress = r.bool(tag); break;
                case 9: address = r.number(tag); break;
                case 2:
                case 4:
                case 10: r.skipAs(tag, 2); break;
                case 5:
                case 6:
                case 7: r.skipAs(tag, 0); break;
                default: r.skip(tag);
            }
        }
        s.signal = number;
        s.signalCode = code;
        s.hasFaultAddress = hasAddress;
        s.faultAddress = address;
    }

    private static void readThreadEntry(Reader r, State s) throws IOException {
        int key = 0;
        ThreadData thread = new ThreadData();
        int tag;
        while ((tag = r.tag()) != 0) {
            switch (tag >>> 3) {
                case 1: key = (int) r.uint32(tag); break;
                case 2:
                    readThread(r.message(tag), thread);
                    thread.hasValue = true;
                    break;
                default: r.skip(tag);
            }
        }
        if (s.threads.size() < MAX_THREADS || s.threads.containsKey(key)) {
            s.threads.put(key, thread);
        } else {
            s.limited = true;
            s.threadsLimited = true;
            if (key == s.tid) {
                s.threads.remove(s.threads.keySet().iterator().next());
                s.threads.put(key, thread);
            }
        }
    }

    private static void readThread(Reader r, ThreadData thread) throws IOException {
        int tag;
        while ((tag = r.tag()) != 0) {
            switch (tag >>> 3) {
                case 1:
                    thread.id = r.int32(tag);
                    thread.hasId = true;
                    break;
                case 3: readRegister(r.message(tag), thread); break;
                case 4:
                    Frame frame = readFrame(r.message(tag));
                    if (thread.frames.size() < 32) {
                        thread.frames.add(frame);
                    } else {
                        thread.limited = true;
                    }
                    break;
                case 2:
                case 5:
                case 7:
                case 9: r.skipAs(tag, 2); break;
                case 6:
                case 8: r.skipAs(tag, 0); break;
                default: r.skip(tag);
            }
        }
    }

    private static void readRegister(Reader r, ThreadData thread) throws IOException {
        String name = "";
        long value = 0;
        int tag;
        while ((tag = r.tag()) != 0) {
            switch (tag >>> 3) {
                case 1: name = r.ascii(tag, 16, false); break;
                case 2: value = r.number(tag); break;
                default: r.skip(tag);
            }
        }
        if (!name.isEmpty() && name.indexOf('|') < 0 && REGISTER_NAMES.contains("|" + name + "|")) {
            thread.registers.put(name, value);
        }
    }

    private static Frame readFrame(Reader r) throws IOException {
        long pc = 0, relPc = 0, sp = 0, functionOffset = 0, fileMapOffset = 0;
        String function = "", module = "", buildId = "";
        int tag;
        while ((tag = r.tag()) != 0) {
            switch (tag >>> 3) {
                case 1: relPc = r.number(tag); break;
                case 2: pc = r.number(tag); break;
                case 3: sp = r.number(tag); break;
                case 4: function = function(r.ascii(tag, 512)); break;
                case 5: functionOffset = r.number(tag); break;
                case 6: module = module(r.ascii(tag, 4096)); break;
                case 7: fileMapOffset = r.number(tag); break;
                case 8: buildId = buildId(r.ascii(tag, 128)); break;
                default: r.skip(tag);
            }
        }
        return new Frame(pc, relPc, sp, functionOffset, fileMapOffset, function, module, buildId);
    }

    private static Mapping readMapping(Reader r) throws IOException {
        long begin = 0, end = 0, offset = 0, loadBias = 0;
        boolean read = false, write = false, execute = false;
        String module = "", buildId = "";
        int tag;
        while ((tag = r.tag()) != 0) {
            switch (tag >>> 3) {
                case 1: begin = r.number(tag); break;
                case 2: end = r.number(tag); break;
                case 3: offset = r.number(tag); break;
                case 4: read = r.bool(tag); break;
                case 5: write = r.bool(tag); break;
                case 6: execute = r.bool(tag); break;
                case 7: module = module(r.ascii(tag, 4096)); break;
                case 8: buildId = buildId(r.ascii(tag, 128)); break;
                case 9: loadBias = r.number(tag); break;
                default: r.skip(tag);
            }
        }
        if (unsignedCompare(begin, end) >= 0) {
            throw new ParseFailure("malformed");
        }
        return new Mapping(begin, end, offset, loadBias, read, write, execute, module, buildId);
    }

    private static void readCause(Reader r, State s) throws IOException {
        int tag;
        MemoryError error = null;
        while ((tag = r.tag()) != 0) {
            if (tag >>> 3 == 2) {
                error = readMemoryError(r.message(tag), error);
            } else if (tag >>> 3 == 1) {
                r.skipAs(tag, 2);
            } else {
                r.skip(tag);
            }
        }
        if (error != null) {
            if (s.memoryError == null) {
                s.memoryError = error;
            } else {
                s.limited = true;
            }
        }
    }

    private static MemoryError readMemoryError(Reader r, MemoryError previous) throws IOException {
        int tool = previous == null ? 0 : previous.tool;
        int type = previous == null ? 0 : previous.type;
        HeapObject heap = previous == null ? null : previous.heap;
        int tag;
        while ((tag = r.tag()) != 0) {
            switch (tag >>> 3) {
                case 1: tool = r.int32(tag); break;
                case 2: type = r.int32(tag); break;
                case 3: heap = readHeap(r.message(tag), heap); break;
                default: r.skip(tag);
            }
        }
        return new MemoryError(tool, type, heap);
    }

    private static HeapObject readHeap(Reader r, HeapObject previous) throws IOException {
        long address = previous == null ? 0 : previous.address;
        long size = previous == null ? 0 : previous.size;
        long allocationTid = previous == null ? 0 : previous.allocationTid;
        long deallocationTid = previous == null ? 0 : previous.deallocationTid;
        List<Frame> allocation = previous == null ? new ArrayList<Frame>() : new ArrayList<>(previous.allocationFrames);
        List<Frame> deallocation = previous == null ? new ArrayList<Frame>() : new ArrayList<>(previous.deallocationFrames);
        int tag;
        while ((tag = r.tag()) != 0) {
            switch (tag >>> 3) {
                case 1: address = r.number(tag); break;
                case 2: size = r.number(tag); break;
                case 3: allocationTid = r.number(tag); break;
                case 5: deallocationTid = r.number(tag); break;
                case 4:
                case 6:
                    Frame frame = readFrame(r.message(tag));
                    List<Frame> frames = tag >>> 3 == 4 ? allocation : deallocation;
                    if (frames.size() < 8) {
                        frames.add(frame);
                    } else {
                        r.source.state.limited = true;
                    }
                    break;
                default: r.skip(tag);
            }
        }
        return new HeapObject(address, size, allocationTid, deallocationTid, allocation, deallocation);
    }

    private static String classifyAbort(byte[] prefix) {
        int start = 0, end = prefix.length;
        while (start < end && (prefix[start] == ' ' || prefix[start] == '\t')) start++;
        for (int i = start; i < end; i++) {
            if (prefix[i] == '\n' || prefix[i] == '\r' || prefix[i] == 0) {
                end = i;
                break;
            }
        }
        String jni = "JNI DETECTED ERROR IN APPLICATION: ";
        String oldJni = "JNI ERROR (app bug): ";
        if (matches(prefix, start, end, jni) || matches(prefix, start, end, oldJni)) {
            start += matches(prefix, start, end, jni) ? jni.length() : oldJni.length();
            if (matches(prefix, start, end, "thread ")
                    && contains(prefix, start, end, " using JNIEnv* from thread ")) {
                return "jni_wrong_thread";
            }
            if (matches(prefix, start, end, "JNI ")
                    && contains(prefix, start, end, " called with pending exception ")) {
                return "jni_pending_exception";
            }
            if (matches(prefix, start, end, "use of invalid jobject")
                    || (matches(prefix, start, end, "use of deleted ")
                    || matches(prefix, start, end, "use of invalid ")
                    || matches(prefix, start, end, "accessed stale "))
                    && contains(prefix, start, end, " reference")) {
                return "jni_invalid_reference";
            }
            return "unclassified";
        }
        String python = "Fatal Python error: ";
        if (matches(prefix, start, end, python)) {
            start += python.length();
            if (matches(prefix, start, end, "PyThreadState_Get:")) return "python_thread_state_get";
            if (matches(prefix, start, end, "PyEval_SaveThread:")) return "python_eval_save_thread";
            if (matches(prefix, start, end, "PyGILState_Release:")) return "python_gil_state_release";
            return "python_fatal";
        }
        if (matches(prefix, start, end, "Scudo ERROR: ")) return "scudo";
        if (matches(prefix, start, end, "FORTIFY: ")) return "fortify";
        String libcxx = "libc++abi: ";
        if (matches(prefix, start, end, libcxx)) start += libcxx.length();
        if (matches(prefix, start, end, "terminating due to uncaught exception")
                || matches(prefix, start, end, "terminating due to uncaught foreign exception")) {
            return "cxx_uncaught_exception";
        }
        return "unclassified";
    }

    private static boolean matches(byte[] bytes, int start, int end, String literal) {
        if (literal.length() > end - start) return false;
        for (int i = 0; i < literal.length(); i++) {
            if (bytes[start + i] != literal.charAt(i)) return false;
        }
        return true;
    }

    private static boolean contains(byte[] bytes, int start, int end, String literal) {
        for (int i = start; i <= end - literal.length(); i++) {
            if (matches(bytes, i, end, literal)) return true;
        }
        return false;
    }

    private static String module(String name) {
        if (name.equals("ng-pine-code") || name.equals("[anon:ng-pine-code]")
                || name.equals("pine codes") || name.equals("[anon:pine codes]")) {
            return "ng-pine-code";
        }
        if (name.equals("[stack]") || name.equals("[anon:dalvik-jit-code-cache]")) {
            return name;
        }
        if (name.isEmpty() || name.startsWith("[")
                || name.startsWith("/memfd:") || name.startsWith("memfd:")
                || name.startsWith("/dev/ashmem/")) {
            return "anon";
        }
        if (name.endsWith(" (deleted)")) {
            name = name.substring(0, name.length() - 10);
        }
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 160 || name.isEmpty() || name.startsWith(".") || name.contains("..")) {
            return "";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!alphaNumeric(c) && c != '_' && c != '-' && c != '.' && c != '+') {
                return "";
            }
        }
        return name.endsWith(".so") || name.endsWith(".apk") || name.endsWith(".oat")
                || name.endsWith(".art") || name.endsWith(".vdex") ? name : "";
    }

    private static String function(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!alphaNumeric(c) && "_:$~.<>(),*& []+-=!".indexOf(c) < 0) {
                return "";
            }
        }
        return name;
    }

    private static String buildId(String name) {
        if ((name.length() & 1) != 0) {
            return "";
        }
        StringBuilder hex = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'F') {
                c += 'a' - 'A';
            }
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return "";
            }
            hex.append(c);
        }
        return hex.toString();
    }

    private static boolean alphaNumeric(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    private static int unsignedCompare(long a, long b) {
        return Long.compare(a ^ Long.MIN_VALUE, b ^ Long.MIN_VALUE);
    }

    private static <T> List<T> frozen(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static final class State {
        int pid, tid, arch, signal, signalCode;
        long faultAddress, processUptime;
        boolean hasFaultAddress, limited, threadsLimited, anyField;
        MemoryError memoryError;
        String abortCategory = "unclassified";
        final Map<Integer, ThreadData> threads = new LinkedHashMap<>();
        final List<Mapping> mappings = new ArrayList<>();
    }

    private static final class ThreadData {
        int id;
        boolean hasId, hasValue, limited;
        final List<Frame> frames = new ArrayList<>();
        final Map<String, Long> registers = new LinkedHashMap<>();
    }

    private static final class ParseFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final String status;

        ParseFailure(String status) {
            this.status = status;
        }
    }

    private static final class Source {
        final InputStream input;
        final State state;
        final byte[] scratch = new byte[4096];
        long position;
        int fields;

        Source(InputStream input, State state) {
            this.input = input;
            this.state = state;
        }

        int one(boolean eofAllowed) throws IOException {
            if (position >= MAX_BYTES) {
                throw new ParseFailure("budget_exceeded");
            }
            int b = input.read();
            if (b < 0) {
                if (eofAllowed) {
                    return -1;
                }
                throw new ParseFailure("truncated");
            }
            position++;
            return b;
        }

        void drain(long count) throws IOException {
            while (count > 0) {
                int wanted = (int) Math.min(count, scratch.length);
                int read = input.read(scratch, 0, wanted);
                if (read < 0) {
                    throw new ParseFailure("truncated");
                }
                if (read == 0) {
                    one(false);
                    count--;
                } else {
                    position += read;
                    count -= read;
                }
            }
        }
    }

    private static final class Reader {
        final Source source;
        final long end;
        final int depth;

        Reader(Source source, long end, int depth) {
            this.source = source;
            this.end = end;
            this.depth = depth;
        }

        int tag() throws IOException {
            if (source.position == end) {
                return 0;
            }
            int first = source.one(depth == 0);
            if (first < 0) {
                return 0;
            }
            long value = varint(first);
            if (value <= 0 || value > 0xffffffffL || value >>> 3 == 0) {
                throw new ParseFailure("malformed");
            }
            int wire = (int) value & 7;
            if (wire != 0 && wire != 1 && wire != 2 && wire != 5) {
                throw new ParseFailure("malformed");
            }
            if (++source.fields > MAX_FIELDS) {
                throw new ParseFailure("budget_exceeded");
            }
            return (int) value;
        }

        long varint(int first) throws IOException {
            long value = 0;
            int b = first;
            for (int i = 0; i < 10; i++) {
                if (i == 9 && b > 1) {
                    throw new ParseFailure("malformed");
                }
                value |= (long) (b & 127) << (7 * i);
                if ((b & 128) == 0) {
                    if (i > 0 && b == 0) {
                        throw new ParseFailure("malformed");
                    }
                    return value;
                }
                if (source.position == end) {
                    throw new ParseFailure("malformed");
                }
                b = source.one(false);
            }
            throw new ParseFailure("malformed");
        }

        long rawVarint() throws IOException {
            if (source.position == end) {
                throw new ParseFailure("malformed");
            }
            return varint(source.one(false));
        }

        void wire(int tag, int expected) throws IOException {
            if ((tag & 7) != expected) {
                throw new ParseFailure("malformed");
            }
        }

        long number(int tag) throws IOException {
            wire(tag, 0);
            return rawVarint();
        }

        long uint32(int tag) throws IOException {
            long value = number(tag);
            if (value < 0 || value > 0xffffffffL) {
                throw new ParseFailure("malformed");
            }
            return value;
        }

        int int32(int tag) throws IOException {
            long value = number(tag);
            if (value != (int) value && (value < 0 || value > 0xffffffffL)) {
                throw new ParseFailure("malformed");
            }
            return (int) value;
        }

        boolean bool(int tag) throws IOException {
            long value = number(tag);
            if (value != 0 && value != 1) {
                throw new ParseFailure("malformed");
            }
            return value != 0;
        }

        long length(int tag) throws IOException {
            wire(tag, 2);
            long value = rawVarint();
            if (value < 0 || value > Integer.MAX_VALUE || value > end - source.position) {
                throw new ParseFailure("malformed");
            }
            requireBudget(value);
            return value;
        }

        void requireBudget(long count) throws IOException {
            if (count > MAX_BYTES - source.position) {
                throw new ParseFailure("budget_exceeded");
            }
        }

        Reader message(int tag) throws IOException {
            long length = length(tag);
            if (depth >= MAX_DEPTH) {
                throw new ParseFailure("budget_exceeded");
            }
            return new Reader(source, source.position + length, depth + 1);
        }

        String abortCategory(int tag) throws IOException {
            long length = length(tag);
            byte[] prefix = new byte[(int) Math.min(length, 1024)];
            for (int i = 0; i < prefix.length; i++) prefix[i] = (byte) source.one(false);
            source.drain(length - prefix.length);
            return classifyAbort(prefix);
        }

        String ascii(int tag, int max) throws IOException {
            return ascii(tag, max, true);
        }

        String ascii(int tag, int max, boolean reportLimit) throws IOException {
            long length = length(tag);
            if (length > max) {
                source.state.limited |= reportLimit;
                source.drain(length);
                return "";
            }
            StringBuilder value = new StringBuilder((int) length);
            boolean safe = true;
            for (int i = 0; i < length; i++) {
                int c = source.one(false);
                if (c < 32 || c > 126) {
                    safe = false;
                }
                value.append((char) c);
            }
            return safe ? value.toString() : "";
        }

        void skipAs(int tag, int expected) throws IOException {
            wire(tag, expected);
            skip(tag);
        }

        void skip(int tag) throws IOException {
            long count;
            switch (tag & 7) {
                case 0: rawVarint(); return;
                case 1: count = 8; break;
                case 2: count = length(tag); break;
                case 5: count = 4; break;
                default: throw new ParseFailure("malformed");
            }
            if (count > end - source.position) {
                throw new ParseFailure("malformed");
            }
            requireBudget(count);
            source.drain(count);
        }
    }
}
