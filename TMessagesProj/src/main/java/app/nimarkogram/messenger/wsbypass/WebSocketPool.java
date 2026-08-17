package app.nimarkogram.messenger.wsbypass;

import org.telegram.messenger.FileLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WebSocketPool {

    private static final long MAX_AGE_MS = 120_000L;
    private static final int MAX_PER_KEY = 4;

    private static final class Entry {
        final RawWebSocket ws;
        final long acquiredAtMs;
        boolean healthCheckInProgress;

        Entry(RawWebSocket ws, long acquiredAtMs) {
            this.ws = ws;
            this.acquiredAtMs = acquiredAtMs;
        }
    }

    private final Object lock = new Object();
    private final HashMap<Long, ArrayDeque<Entry>> idle = new HashMap<>();

    private static long keyOf(int dc, boolean isMedia) {
        
        return ((long) dc << 1) | (isMedia ? 1L : 0L);
    }

    public RawWebSocket get(int dc, boolean isMedia) {
        long now = System.currentTimeMillis();
        long key = keyOf(dc, isMedia);
        List<RawWebSocket> toClose = null;
        RawWebSocket result = null;
        synchronized (lock) {
            ArrayDeque<Entry> q = idle.get(key);
            if (q == null || q.isEmpty()) {
                return null;
            }
            
            int candidates = q.size();
            while (candidates-- > 0 && !q.isEmpty()) {
                Entry e = q.pollFirst();
                if (e == null || e.ws == null) {
                    continue;
                }
                if (e.healthCheckInProgress) {
                    q.addLast(e);
                    continue;
                }
                boolean closed;
                try {
                    closed = e.ws.isClosed();
                } catch (Throwable t) {
                    if (toClose == null) toClose = new ArrayList<>(2);
                    toClose.add(e.ws);
                    continue;
                }
                if (closed) {
                    if (toClose == null) toClose = new ArrayList<>(2);
                    toClose.add(e.ws);
                    continue;
                }
                if ((now - e.acquiredAtMs) > MAX_AGE_MS) {
                    if (toClose == null) toClose = new ArrayList<>(2);
                    toClose.add(e.ws);
                    continue;
                }
                result = e.ws;
                break;
            }
            if (q.isEmpty()) {
                idle.remove(key);
            }
        }
        if (toClose != null) {
            for (RawWebSocket ws : toClose) {
                safeClose(ws);
            }
        }
        return result;
    }

    public void put(int dc, boolean isMedia, RawWebSocket ws) {
        if (ws == null) {
            return;
        }
        try {
            if (ws.isClosed()) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        long key = keyOf(dc, isMedia);
        RawWebSocket evicted = null;
        boolean accepted = true;
        synchronized (lock) {
            ArrayDeque<Entry> q = idle.get(key);
            if (q == null) {
                q = new ArrayDeque<>(MAX_PER_KEY);
                idle.put(key, q);
            }
            if (q.size() >= MAX_PER_KEY) {
                
                Iterator<Entry> it = q.iterator();
                while (it.hasNext()) {
                    Entry old = it.next();
                    if (old != null && !old.healthCheckInProgress) {
                        it.remove();
                        evicted = old.ws;
                        break;
                    }
                }
                if (evicted == null) {
                    accepted = false;
                }
            }
            if (accepted) {
                q.addLast(new Entry(ws, System.currentTimeMillis()));
            } else if (q.isEmpty()) {
                idle.remove(key);
            }
        }
        if (evicted != null) {
            safeClose(evicted);
        }
        if (!accepted) {
            safeClose(ws);
        }
    }

    public void clear() {
        ArrayList<RawWebSocket> all = new ArrayList<>();
        synchronized (lock) {
            for (ArrayDeque<Entry> q : idle.values()) {
                for (Entry e : q) {
                    if (e != null && e.ws != null) {
                        all.add(e.ws);
                    }
                }
            }
            idle.clear();
        }
        for (RawWebSocket ws : all) {
            safeClose(ws);
        }
    }

    public void healthScan() {
        
        Set<Entry> claimedEntries = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Entry> failedEntries = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayList<Entry> snapshot = new ArrayList<>();
        synchronized (lock) {
            for (ArrayDeque<Entry> queue : idle.values()) {
                for (Entry e : queue) {
                    if (e != null && e.ws != null && !e.healthCheckInProgress) {
                        e.healthCheckInProgress = true;
                        claimedEntries.add(e);
                        snapshot.add(e);
                    }
                }
            }
        }
        for (Entry e : snapshot) {
            boolean ok;
            try {
                ok = !e.ws.isClosed() && e.ws.ping(new byte[0]);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                failedEntries.add(e);
            }
        }
        ArrayList<RawWebSocket> toClose = new ArrayList<>(failedEntries.size());
        synchronized (lock) {
            Iterator<Map.Entry<Long, ArrayDeque<Entry>>> it = idle.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, ArrayDeque<Entry>> kv = it.next();
                ArrayDeque<Entry> q = kv.getValue();
                Iterator<Entry> qi = q.iterator();
                while (qi.hasNext()) {
                    Entry e = qi.next();
                    if (e == null) {
                        qi.remove();
                    } else if (failedEntries.contains(e)) {
                        qi.remove();
                        toClose.add(e.ws);
                    } else if (claimedEntries.contains(e)) {
                        e.healthCheckInProgress = false;
                    }
                }
                if (q.isEmpty()) {
                    it.remove();
                }
            }
        }
        for (RawWebSocket ws : toClose) {
            safeClose(ws);
        }
        if (!toClose.isEmpty()) {
            FileLog.d("WebSocketPool healthScan dropped=" + toClose.size());
        }
    }

    private static void safeClose(RawWebSocket ws) {
        if (ws == null) return;
        try {
            ws.close();
        } catch (Throwable ignore) {
        }
    }
}
