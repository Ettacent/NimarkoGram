/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.diagnostics;

import android.os.Process;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

import app.nimarkogram.messenger.CrashContext;

public final class RawProxyDiagnostics {
    public enum Phase {
        ADD, DEFER_ADD, DRAIN_ADD, REMOVE, DEFER_REMOVE, DRAIN_REMOVE, DELIVERY,
        POSTPONE_ADD, POSTPONE_REMOVE, POSTPONE_DELIVERY
    }

    private static final int OBJECT_LIMIT = 128;
    private static final int CLASS_LIMIT = 32;
    private static final int ACTIVE_LIMIT = 32;
    private static final int HISTORY_LIMIT = 64;
    private static final int DETAIL_LIMIT = 512;
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final ThreadLocal<Boolean> recording = new ThreadLocal<>();
    private static final ObjectSlot[] objects = new ObjectSlot[OBJECT_LIMIT];
    private static final ClassSlot[] classes = new ClassSlot[CLASS_LIMIT];
    private static final AtomicReferenceArray<Entry> active = new AtomicReferenceArray<>(ACTIVE_LIMIT);
    private static final Entry[] history = new Entry[HISTORY_LIMIT];
    private static final long fingerprintSalt = System.nanoTime();
    private static long nextObject;
    private static long nextClass;
    private static long nextInvocation;
    private static int objectCursor;
    private static int classCursor;
    private static int historyCursor;
    private static final AtomicLong dropped = new AtomicLong();
    private static final AtomicLong nextSnapshot = new AtomicLong();
    private static final AtomicBoolean snapshotBusy = new AtomicBoolean();
    private static volatile String cachedSnapshot = "raw_proxy not_observed";
    private static volatile String lastFailure = "";

    private RawProxyDiagnostics() {
    }

    public static long begin(Object candidate, Phase phase) {
        Entry entry = null;
        boolean entered = false;
        try {
            if (Boolean.TRUE.equals(recording.get())) return 0;
            if (candidate == null || phase == null || !Proxy.isProxyClass(candidate.getClass())
                    || !"com.chaquo.python.PyInvocationHandler".equals(
                    Proxy.getInvocationHandler(candidate).getClass().getName())) {
                return 0;
            }
            recording.set(true);
            entered = true;
            boolean registration = phase == Phase.ADD || phase == Phase.DEFER_ADD
                    || phase == Phase.POSTPONE_ADD;
            long site = registration ? registrationSite() : 0;
            long now = SystemClock.elapsedRealtime();
            long thread = Thread.currentThread().getId();
            int tid = Process.myTid();
            Class<?> candidateType = candidate.getClass();
            ClassSlot prepared = null;
            boolean known;
            if (!LOCK.tryLock()) {
                drop();
                return 0;
            }
            try {
                ObjectSlot object = findObject(candidate);
                known = object != null;
                if (known) entry = admit(object, phase, site, now, thread, tid);
                else prepared = findType(candidateType);
            } finally {
                LOCK.unlock();
            }
            if (!known) {
                if (prepared == null) prepared = describeType(candidateType);
                if (!LOCK.tryLock()) {
                    drop();
                    return 0;
                }
                try {
                    ObjectSlot object = object(candidate, candidateType, prepared);
                    if (object != null) entry = admit(object, phase, site, now, thread, tid);
                } finally {
                    LOCK.unlock();
                }
            }
            if (entry == null) return 0;
            entry.journal = CrashContext.beginInvocation();
            CrashContext.pythonPhase(entry.journal, now, "interface_enter",
                    entry.target(), "", "");
            CrashDiagnostics.recordEvent("raw_proxy", phase.name(), entry.detail("active", now));
            return entry.token;
        } catch (RuntimeException | Error ignored) {
            return entry == null ? 0 : entry.token;
        } finally {
            if (entered) {
                refreshSnapshot(false);
                recording.remove();
            }
        }
    }

    public static void failure(long token, Throwable failure) {
        if (token == 0) return;
        boolean entered = false;
        try {
            if (Boolean.TRUE.equals(recording.get())) return;
            recording.set(true);
            entered = true;
            Entry entry = find(token);
            if (entry == null || entry.thread != Thread.currentThread().getId()) return;
            entry.failed = true;
            if (entry.chain.failure != null && entry.chain.failure.get() == failure) return;
            long now = SystemClock.elapsedRealtime();
            StringBuilder related = new StringBuilder(160);
            for (int i = 0; i < active.length(); i++) {
                Entry current = active.get(i);
                if (current != null && current != entry && current.classToken == entry.classToken) {
                    related.append(";a=").append(current.related(now, true));
                    break;
                }
            }
            Entry first = null;
            Entry second = null;
            if (LOCK.tryLock()) {
                try {
                    for (int i = 1; i <= history.length && second == null; i++) {
                        Entry previous = history[(historyCursor - i + history.length) % history.length];
                        if (previous != null && previous.classToken == entry.classToken) {
                            if (first == null) first = previous;
                            else second = previous;
                        }
                    }
                } finally {
                    LOCK.unlock();
                }
            } else {
                drop();
            }
            if (first != null) related.append(";h=").append(first.related(now, false));
            if (second != null) related.append(";h=").append(second.related(now, false));
            String detail = entry.detail("failed", now) + related;
            if (detail.length() > DETAIL_LIMIT) detail = detail.substring(0, DETAIL_LIMIT);
            lastFailure = detail;
            entry.chain.failure = new WeakReference<>(failure);
            refreshSnapshot(true);
            CrashDiagnostics.recordFailure("raw_proxy", entry.phase.name(), detail, failure);
        } catch (RuntimeException | Error ignored) {
        } finally {
            if (entered) recording.remove();
        }
    }

    public static void end(long token) {
        if (token == 0) return;
        Entry entry = null;
        boolean entered = false;
        try {
            if (Boolean.TRUE.equals(recording.get())) return;
            recording.set(true);
            entered = true;
            Entry found = find(token);
            if (found == null || found.thread != Thread.currentThread().getId()
                    || !active.compareAndSet(found.slot, found, null)) return;
            entry = found;
            entry.ended = SystemClock.elapsedRealtime();
            entry.closed = true;
            if (LOCK.tryLock()) {
                try {
                    history[historyCursor] = entry;
                    historyCursor = (historyCursor + 1) % history.length;
                } finally {
                    LOCK.unlock();
                }
            } else {
                drop();
            }
            CrashContext.pythonPhase(entry.journal, entry.started, "interface_exit",
                    entry.target(), "", "");
            CrashDiagnostics.recordEvent("raw_proxy", entry.phase.name(),
                    entry.detail(entry.failed ? "failed" : "recent", entry.ended));
        } catch (RuntimeException | Error ignored) {
        } finally {
            if (entry != null) {
                try {
                    CrashContext.endInvocation(entry.journal);
                } catch (RuntimeException | Error ignored) {
                }
            }
            if (entered) {
                refreshSnapshot(false);
                recording.remove();
            }
        }
    }

    public static String getLastFailure() {
        return lastFailure;
    }

    public static String getCachedSnapshot() {
        return cachedSnapshot;
    }

    public static void sample() {
        boolean entered = false;
        try {
            if (Boolean.TRUE.equals(recording.get())) return;
            recording.set(true);
            entered = true;
            refreshSnapshot(false);
        } catch (RuntimeException | Error ignored) {
        } finally {
            if (entered) recording.remove();
        }
    }

    private static void refreshSnapshot(boolean force) {
        boolean acquired = false;
        try {
            long now = SystemClock.elapsedRealtime();
            long next = nextSnapshot.get();
            long deadline = now > Long.MAX_VALUE - 500 ? Long.MAX_VALUE : now + 500;
            if (!force && (now < next || !nextSnapshot.compareAndSet(next, deadline))) return;
            if (!snapshotBusy.compareAndSet(false, true)) return;
            acquired = true;
            if (force) nextSnapshot.set(deadline);
            Entry[] entries = new Entry[16];
            int live = 0;
            int recent = 0;
            int liveTotal = 0;
            int recentTotal = 0;
            if (!LOCK.tryLock()) return;
            try {
                for (int i = 0; i < active.length(); i++) {
                    Entry entry = active.get(i);
                    if (entry != null) {
                        liveTotal++;
                        if (live < 8) entries[live++] = entry;
                    }
                }
                for (int i = 1; i <= history.length; i++) {
                    Entry entry = history[(historyCursor - i + history.length) % history.length];
                    if (entry != null) {
                        if (recent < 8) entries[8 + recent++] = entry;
                        recentTotal++;
                    }
                }
            } finally {
                LOCK.unlock();
            }
            StringBuilder text = new StringBuilder(8192);
            text.append("raw_proxy sample_elapsed_ms=").append(now).append(" clock=elapsedRealtime")
                    .append(" active_observed=").append(liveTotal).append(" recent_observed=").append(recentTotal)
                    .append(" dropped=").append(dropped.get()).append("; bounded_observations_not_current_state\n");
            for (int i = 0; i < entries.length; i++) {
                Entry entry = entries[i];
                if (entry == null) continue;
                boolean closed = entry.closed;
                text.append(i < 8 ? "active_sample " : "recent_sample ")
                        .append(entry.detail(closed ? entry.failed ? "failed" : "recent" : "active",
                                closed ? entry.ended : now)).append('\n');
            }
            if (text.length() > 8192) text.setLength(8192);
            cachedSnapshot = text.toString();
        } catch (RuntimeException | Error ignored) {
        } finally {
            if (acquired) snapshotBusy.set(false);
        }
    }

    private static void drop() {
        dropped.getAndUpdate(value -> value == Long.MAX_VALUE ? value : value + 1);
    }

    private static Entry find(long token) {
        for (int i = 0; i < active.length(); i++) {
            Entry entry = active.get(i);
            if (entry != null && entry.token == token) return entry;
        }
        return null;
    }

    private static Entry admit(ObjectSlot object, Phase phase, long site, long now, long thread, int tid) {
        int available = -1;
        FailureChain chain = null;
        for (int i = 0; i < active.length(); i++) {
            Entry entry = active.get(i);
            if (entry == null && available < 0) available = i;
            if (entry != null && entry.thread == thread) chain = entry.chain;
        }
        if (available < 0 || nextInvocation == Long.MAX_VALUE) {
            drop();
            return null;
        }
        if (site != 0) {
            if (object.firstSite == 0) {
                object.firstSite = site;
                object.firstTime = now;
                object.firstThread = thread;
            }
            object.lastSite = site;
            object.lastTime = now;
            object.lastThread = thread;
        }
        Entry entry = new Entry(++nextInvocation, object, phase, now, thread, tid,
                available, chain == null ? new FailureChain() : chain);
        active.set(available, entry);
        return entry;
    }

    private static ObjectSlot findObject(Object candidate) {
        for (ObjectSlot slot : objects) {
            Object value = slot == null ? null : slot.reference.get();
            if (value == candidate) return slot;
        }
        return null;
    }

    private static ObjectSlot object(Object candidate, Class<?> candidateType, ClassSlot prepared) {
        int available = -1;
        for (int i = 0; i < objects.length; i++) {
            ObjectSlot slot = objects[i];
            Object value = slot == null ? null : slot.reference.get();
            if (value == candidate) return slot;
            if (value == null && available < 0) available = i;
        }
        if (nextObject == Long.MAX_VALUE) return null;
        if (available < 0) {
            drop();
            available = objectCursor;
            objectCursor = (objectCursor + 1) % objects.length;
        }
        ClassSlot type = type(candidateType, prepared);
        if (type == null) return null;
        ObjectSlot slot = new ObjectSlot(candidate, ++nextObject, type);
        objects[available] = slot;
        return slot;
    }

    private static ClassSlot findType(Class<?> type) {
        for (ClassSlot slot : classes) {
            if (slot != null && slot.reference.get() == type) return slot;
        }
        return null;
    }

    private static ClassSlot type(Class<?> type, ClassSlot prepared) {
        int available = -1;
        for (int i = 0; i < classes.length; i++) {
            ClassSlot slot = classes[i];
            Class<?> value = slot == null ? null : slot.reference.get();
            if (value == type) return slot;
            if (value == null && available < 0) available = i;
        }
        if (nextClass == Long.MAX_VALUE) return null;
        if (available < 0) {
            drop();
            available = classCursor;
            classCursor = (classCursor + 1) % classes.length;
        }
        ClassSlot slot = new ClassSlot(type, ++nextClass, prepared.fingerprint, prepared.interfaces);
        classes[available] = slot;
        return slot;
    }

    private static ClassSlot describeType(Class<?> type) {
        Class<?>[] interfaces = type.getInterfaces();
        long fingerprint = fingerprintSalt;
        for (int i = 0; i < Math.min(interfaces.length, 16); i++) {
            fingerprint = hash(fingerprint, interfaces[i].getName());
        }
        fingerprint = fingerprint * 1099511628211L ^ interfaces.length;
        return new ClassSlot(type, 0, hash(fingerprint, type.getName()), fingerprint);
    }

    private static long registrationSite() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        long result = fingerprintSalt;
        int included = 0;
        for (int i = 0; i < Math.min(stack.length, 32) && included < 4; i++) {
            StackTraceElement frame = stack[i];
            String type = frame.getClassName();
            if (type.equals(RawProxyDiagnostics.class.getName())
                    || type.equals("org.telegram.messenger.NotificationCenter")
                    || type.startsWith("org.telegram.messenger.NotificationCenter$")) continue;
            result = hash(hash(result, type), frame.getMethodName());
            result = result * 1099511628211L ^ frame.getLineNumber();
            included++;
        }
        return result == 0 ? 1 : result;
    }

    private static long hash(long value, String text) {
        for (int i = 0; i < Math.min(text.length(), 256); i++) {
            value = (value ^ text.charAt(i)) * 1099511628211L;
        }
        return (value ^ text.length()) * 1099511628211L;
    }

    private static final class ClassSlot {
        final WeakReference<Class<?>> reference;
        final long token;
        final long fingerprint;
        final long interfaces;

        ClassSlot(Class<?> type, long token, long fingerprint, long interfaces) {
            reference = new WeakReference<>(type);
            this.token = token;
            this.fingerprint = fingerprint;
            this.interfaces = interfaces;
        }
    }

    private static final class ObjectSlot {
        final WeakReference<Object> reference;
        final long token;
        final long classToken;
        final long fingerprint;
        final long interfaces;
        long firstSite;
        long firstTime;
        long firstThread;
        long lastSite;
        long lastTime;
        long lastThread;

        ObjectSlot(Object candidate, long token, ClassSlot type) {
            reference = new WeakReference<>(candidate);
            this.token = token;
            classToken = type.token;
            fingerprint = type.fingerprint;
            interfaces = type.interfaces;
        }
    }

    private static final class FailureChain {
        WeakReference<Throwable> failure;
    }

    private static final class Entry {
        final long token;
        final long objectToken;
        final long classToken;
        final long fingerprint;
        final long interfaces;
        final long firstSite;
        final long firstTime;
        final long firstThread;
        final long lastSite;
        final long lastTime;
        final long lastThread;
        final Phase phase;
        final long started;
        final long thread;
        final int tid;
        final long losses;
        final int slot;
        final FailureChain chain;
        volatile long journal;
        volatile long ended;
        volatile boolean failed;
        volatile boolean closed;

        Entry(long token, ObjectSlot object, Phase phase, long started, long thread, int tid,
              int slot, FailureChain chain) {
            this.token = token;
            objectToken = object.token;
            classToken = object.classToken;
            fingerprint = object.fingerprint;
            interfaces = object.interfaces;
            firstSite = object.firstSite;
            firstTime = object.firstTime;
            firstThread = object.firstThread;
            lastSite = object.lastSite;
            lastTime = object.lastTime;
            lastThread = object.lastThread;
            this.phase = phase;
            this.started = started;
            this.thread = thread;
            this.tid = tid;
            losses = dropped.get();
            this.slot = slot;
            this.chain = chain;
        }

        String target() {
            return "raw_nc_" + phase.name() + "_c" + Long.toHexString(fingerprint)
                    + "_i" + Long.toHexString(interfaces) + "_r" + Long.toHexString(firstSite);
        }

        String related(long now, boolean running) {
            return Long.toHexString(token) + "/" + Long.toHexString(objectToken) + "/"
                    + Integer.toHexString(phase.ordinal()) + "/"
                    + Long.toHexString(Math.min(0xffffffffL, Math.max(0, now - (running ? started : ended))))
                    + "/" + (running ? "a" : failed ? "f" : "r");
        }

        String detail(String state, long now) {
            String result = "s=" + state + ";v=" + Long.toHexString(token)
                    + ";o=" + Long.toHexString(objectToken) + ";c=" + Long.toHexString(classToken)
                    + ";cf=" + Long.toHexString(fingerprint) + ";if=" + Long.toHexString(interfaces)
                    + ";r=" + Long.toHexString(firstSite) + ";rt=" + Long.toHexString(firstTime)
                    + ";rj=" + Long.toHexString(firstThread) + ";lr=" + Long.toHexString(lastSite)
                    + ";lt=" + Long.toHexString(lastTime) + ";lj=" + Long.toHexString(lastThread)
                    + ";u=" + Long.toHexString(started) + ";j=" + Long.toHexString(thread)
                    + ";n=" + Integer.toHexString(tid) + ";dt=" + Long.toHexString(Math.max(0, now - started))
                    + ";p=" + phase.ordinal() + ";x=" + Long.toHexString(losses);
            return result.length() <= DETAIL_LIMIT ? result : result.substring(0, DETAIL_LIMIT);
        }
    }
}
