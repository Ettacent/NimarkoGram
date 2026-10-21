/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.diagnostics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

final class CrashDiagnosticBuffer {
    private static final class Entry {
        final long sequence;
        final long uptime;
        final String text;

        Entry(long sequence, long uptime, String text) {
            this.sequence = sequence;
            this.uptime = uptime;
            this.text = text;
        }
    }

    private final AtomicReferenceArray<Entry> events = new AtomicReferenceArray<>(64);
    private final AtomicReferenceArray<Entry> failures = new AtomicReferenceArray<>(8);
    private final AtomicReferenceArray<Entry> memory = new AtomicReferenceArray<>(16);
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicLong failureSequence = new AtomicLong();
    private final AtomicLong memorySequence = new AtomicLong();
    private final AtomicLong nextEvent = new AtomicLong();
    private final AtomicLong nextFailure = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile String lastFailure = "none";
    private long highSince = -1;
    private long highUsed;
    private long lastMemoryTime = -1;

    boolean event(long now, String text, boolean failure) {
        if (!admit(now, failure)) return false;
        appendAdmitted(now, text, failure);
        return true;
    }

    boolean admit(long now, boolean failure) {
        AtomicLong deadline = failure ? nextFailure : nextEvent;
        long next = deadline.get();
        if (now < next || !deadline.compareAndSet(next, now + (failure ? 100 : 250))) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    void appendAdmitted(long now, String text, boolean failure) {
        String bounded = bounded(text, failure ? 768 : 512);
        append(failure ? failures : events, failure ? failureSequence : eventSequence, now, bounded);
        if (failure) lastFailure = bounded;
    }

    void memory(long now, String text) {
        append(memory, memorySequence, now, bounded(text, 768));
    }

    private static void append(AtomicReferenceArray<Entry> entries, AtomicLong sequence, long now, String text) {
        long value = sequence.getAndIncrement();
        int slot = (int) (value & (entries.length() - 1));
        Entry entry = new Entry(value, now, text);
        Entry previous = entries.get(slot);
        while (previous == null || previous.sequence < value) {
            if (entries.compareAndSet(slot, previous, entry)) return;
            previous = entries.get(slot);
        }
    }

    String lastFailure() {
        return lastFailure;
    }

    String snapshot(long now) {
        StringBuilder result = new StringBuilder(8192);
        result.append("sampled_history_uptime_ms=").append(now).append(" dropped_rate_limited=").append(dropped.get())
                .append("; observations_not_causality\n");
        appendSnapshot(result, "failures", failures, failureSequence.get(), now);
        appendSnapshot(result, "memory", memory, memorySequence.get(), now);
        appendSnapshot(result, "events", events, eventSequence.get(), now);
        return result.toString();
    }

    private static void appendSnapshot(StringBuilder out, String title, AtomicReferenceArray<Entry> entries, long end, long now) {
        out.append('[').append(title).append("]\n");
        for (long i = Math.max(0, end - entries.length()); i < end; i++) {
            Entry entry = entries.get((int) (i & (entries.length() - 1)));
            if (entry == null || entry.sequence != i) continue;
            if (out.length() + entry.text.length() + 100 > 60 * 1024) {
                out.append("[snapshot budget reached]\n");
                break;
            }
            out.append("seq=").append(i).append(" age_ms=").append(Math.max(0, now - entry.uptime))
                    .append(' ').append(entry.text).append('\n');
        }
    }

    boolean earlyCaptureDue(long now, long used, long maximum, boolean enabled, boolean foreground) {
        if (now < lastMemoryTime) highSince = -1;
        lastMemoryTime = now;
        if (!enabled || !foreground || maximum <= 0 || used < 0 || used > maximum
                || maximum - used < 32L * 1024 * 1024 || used < maximum / 100 * 80) {
            highSince = -1;
            return false;
        }
        if (highSince < 0) {
            highSince = now;
            highUsed = used;
        }
        return now - highSince >= 20000 && (used - highUsed >= 8L * 1024 * 1024 || used >= maximum / 100 * 90);
    }

    static String bounded(String value, int maximum) {
        if (value == null) return "unavailable";
        int length = Math.min(value.length(), maximum);
        boolean safe = length == value.length();
        if (safe) for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) { safe = false; break; }
        }
        if (safe) return value;
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            out[i] = c >= 32 && c <= 126 ? c : '?';
        }
        return new String(out);
    }
}
