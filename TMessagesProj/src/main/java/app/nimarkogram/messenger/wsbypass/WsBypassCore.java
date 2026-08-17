package app.nimarkogram.messenger.wsbypass;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WsBypassCore {

    public static final String LOCAL_PROXY_HOST = "127.0.0.1";

    private volatile long lastBridgeOkAtMs = 0L;
    public long getLastBridgeOkAtMs() { return lastBridgeOkAtMs; }
    private final Object bridgeStateLock = new Object();
    private int activeBridges;
    private long bridgeGeneration;
    public boolean hasActiveBridge() {
        synchronized (bridgeStateLock) {
            return activeBridges > 0;
        }
    }
    private long getBridgeGeneration() {
        synchronized (bridgeStateLock) {
            return bridgeGeneration;
        }
    }
    private boolean isBridgeGenerationCurrent(long generation) {
        synchronized (bridgeStateLock) {
            return generation == bridgeGeneration;
        }
    }
    private void markBridgeOk(long generation) {
        synchronized (bridgeStateLock) {
            if (generation == bridgeGeneration && activeBridges > 0) {
                lastBridgeOkAtMs = System.currentTimeMillis();
            }
        }
    }
    private boolean markBridgeStarted(long generation) {
        synchronized (bridgeStateLock) {
            if (generation != bridgeGeneration) {
                return false;
            }
            if (activeBridges++ == 0) {
                lastBridgeOkAtMs = 0L;
            }
            return true;
        }
    }
    private void markBridgeStopped(long generation) {
        synchronized (bridgeStateLock) {
            if (generation != bridgeGeneration) {
                return;
            }
            if (activeBridges > 0) {
                activeBridges--;
            }
            if (activeBridges == 0) {
                lastBridgeOkAtMs = 0L;
            }
        }
    }
    private void invalidateBridgeGeneration() {
        synchronized (bridgeStateLock) {
            bridgeGeneration++;
            activeBridges = 0;
            lastBridgeOkAtMs = 0L;
        }
    }

    static volatile boolean DEBUG = false;   
    static final java.util.concurrent.atomic.AtomicInteger CONN_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    static void decodeMtproto(int connId, String dir, byte[] plain) {
        if (!DEBUG || plain == null || plain.length < 4) return;
        try {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(40, plain.length); i++) hex.append(String.format("%02x", plain[i] & 0xFF));
            
            long flen = (plain[0] & 0xFFL) | ((plain[1] & 0xFFL) << 8) | ((plain[2] & 0xFFL) << 16) | ((plain[3] & 0xFFL) << 24);
            String interp;
            if (flen == 4 && plain.length >= 8) {
                int err = (plain[4] & 0xFF) | ((plain[5] & 0xFF) << 8) | ((plain[6] & 0xFF) << 16) | ((plain[7] & 0xFF) << 24);
                interp = "TRANSPORT ERROR code=" + err;
            } else if (plain.length >= 28) {
                boolean authZero = true;
                for (int i = 4; i < 12; i++) if (plain[i] != 0) { authZero = false; break; }
                if (authZero) {
                    long cons = (plain[24] & 0xFFL) | ((plain[25] & 0xFFL) << 8) | ((plain[26] & 0xFFL) << 16) | ((plain[27] & 0xFFL) << 24);
                    String name = cons == 0x05162463L ? "resPQ" : cons == 0xd0e8075cL ? "server_DH_params_ok"
                            : cons == 0x79cb045dL ? "server_DH_params_fail" : cons == 0x3bcbf734L ? "dh_gen_ok"
                            : cons == 0xbe7e8ef1L ? "req_pq_multi" : cons == 0xd712e4beL ? "req_DH_params"
                            : cons == 0xf5045f1fL ? "set_client_DH_params" : String.format("0x%08x", cons);
                    interp = "UNENCRYPTED auth msg: " + name + " (flen=" + flen + ")";
                } else {
                    interp = "ENCRYPTED (auth_key set, flen=" + flen + ") — session active";
                }
            } else {
                interp = "flen=" + flen + " short";
            }
            dbg("conn#" + connId + " " + dir + " mtproto: " + interp + " | hex=" + hex);
        } catch (Throwable ignored) {}
    }

    static volatile boolean SPLIT_UP = false;
    static void dbg(String msg) {
        if (!DEBUG) return;
        try { android.util.Log.i("NMWSBYPASS", msg); } catch (Throwable ignored) {}
        try { FileLog.d("wsbypass: " + msg); } catch (Throwable ignored) {}
    }

    private static final double WS_FAIL_COOLDOWN_SEC = 30.0;
    private static final double WS_FAIL_COOLDOWN_MAX_SEC = 300.0;
    private static final double WS_BLACKLIST_TTL_SEC = 420.0;
    private static final long WS_ROUTE_DEADLINE_MS = 9_000L;
    private static final long WS_RELAY_BUDGET_MS = 3_500L;
    private static final long WS_DIRECT_BUDGET_MS = 2_500L;
    private static final int MAX_DIRECT_ATTEMPTS = 4;
    private static final long WS_POOL_KEEPER_INTERVAL_SEC = 30L;

    private static final int SOCK_RCVBUF = 256 * 1024;
    private static final int SOCK_SNDBUF = 512 * 1024;
    private static final int RECV_CHUNK = 256 * 1024;
    private static final int TCP_FALLBACK_TIMEOUT_MS = 10_000;
    private static final int ACCEPT_TIMEOUT_MS = 1_000;
    private static final int HANDSHAKE_READ_TIMEOUT_MS = 10_000;
    private static final int LISTEN_BACKLOG = 64;

    private static final int MAX_HANDLER_THREADS = 512;

    private static final Map<Integer, String> DEFAULT_DC_IP;
    private static final Map<Integer, Integer> DC_OVERRIDES;
    private static final Map<Integer, String> DIRECT_DC_IP;

    static {
        Map<Integer, String> m = new HashMap<>();
        m.put(2, "149.154.167.220");
        m.put(4, "149.154.167.220");
        DEFAULT_DC_IP = Collections.unmodifiableMap(m);

        Map<Integer, Integer> o = new HashMap<>();
        o.put(203, 2);
        DC_OVERRIDES = Collections.unmodifiableMap(o);

        Map<Integer, String> d = new HashMap<>();
        d.put(1, "149.154.175.50");
        d.put(2, "149.154.167.51");
        d.put(3, "149.154.175.100");
        d.put(4, "149.154.167.91");
        d.put(5, "149.154.171.5");
        d.put(203, "91.105.192.100");
        DIRECT_DC_IP = Collections.unmodifiableMap(d);
    }

    private static volatile WsBypassCore instance;

    public static WsBypassCore getInstance() {
        WsBypassCore local = instance;
        if (local == null) {
            synchronized (WsBypassCore.class) {
                local = instance;
                if (local == null) {
                    local = new WsBypassCore();
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    private volatile int port;
    private volatile ServerSocket listener;
    private volatile Thread acceptThread;
    private volatile ExecutorService handlerPool;
    private volatile ScheduledExecutorService keeperPool;
    private volatile ScheduledFuture<?> keeperTask;
    private volatile byte[] secretBytes = new byte[0];
    private volatile String secretHex = "";
    private final Map<Integer, String> dcIp = new HashMap<>(DEFAULT_DC_IP);

    private final Object cfgLock = new Object();
    private final Map<Long, Long> failUntilMs = new HashMap<>();
    private final Map<Long, Integer> failCount = new HashMap<>();
    private final Map<Long, Long> blacklistUntilMs = new HashMap<>();
    private final Set<Long> blacklist = new HashSet<>();
    private final Map<Long, String> wsDomainPref = new HashMap<>();
    private final Map<String, Long> relayFailUntilMs = new HashMap<>();
    private final Map<String, Integer> relayFailCount = new HashMap<>();

    private final Object trackedLock = new Object();
    private final Set<Object> tracked = new HashSet<>();

    private final WebSocketPool wsPool = new WebSocketPool();

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public boolean isAcceptThreadAlive() {
        if (!running) return false;
        if (listener == null) return false;
        Thread at = acceptThread;
        return at != null && at.isAlive();
    }

    public String getSecretHex() {
        return secretHex;
    }

    public synchronized String start(int desiredPort, String secretHexIn) {
        synchronized (lifecycleLock) {
            try {
                stopLocked();

                String sec = MtprotoHandshake.validSecretHex(secretHexIn);
                if (sec == null || sec.isEmpty()) {
                    sec = MtprotoHandshake.generateSecretHex();
                }
                byte[] secBytes = MtprotoHandshake.toBytes16(sec);
                if (secBytes == null) {
                    return "invalid secret";
                }

                int bindPort = desiredPort;
                if (bindPort <= 0 || bindPort > 65535) {
                    bindPort = 0;
                }

                ServerSocket srv = new ServerSocket();
                try {
                    srv.setReuseAddress(true);
                } catch (Throwable ignored) {}
                try {
                    srv.setReceiveBufferSize(SOCK_RCVBUF);
                } catch (Throwable ignored) {}
                try {
                    srv.bind(new InetSocketAddress(InetAddress.getByName(LOCAL_PROXY_HOST), bindPort), LISTEN_BACKLOG);
                } catch (IOException ex) {
                    
                    if (bindPort != 0) {
                        try { srv.close(); } catch (Throwable ignored) {}
                        srv = new ServerSocket();
                        try { srv.setReuseAddress(true); } catch (Throwable ignored2) {}
                        try { srv.setReceiveBufferSize(SOCK_RCVBUF); } catch (Throwable ignored2) {}
                        try {
                            srv.bind(new InetSocketAddress(InetAddress.getByName(LOCAL_PROXY_HOST), 0), LISTEN_BACKLOG);
                        } catch (IOException ex2) {
                            try { srv.close(); } catch (Throwable ignored2) {}
                            return ex2.getMessage() == null ? "bind failed" : ex2.getMessage();
                        }
                    } else {
                        try { srv.close(); } catch (Throwable ignored) {}
                        return ex.getMessage() == null ? "bind failed" : ex.getMessage();
                    }
                }
                srv.setSoTimeout(ACCEPT_TIMEOUT_MS);

                this.listener = srv;
                this.port = srv.getLocalPort();
                this.secretHex = sec;
                this.secretBytes = secBytes;

                synchronized (cfgLock) {
                    failUntilMs.clear();
                    failCount.clear();
                    blacklist.clear();
                    blacklistUntilMs.clear();
                }

                ThreadPoolExecutor pool = new ThreadPoolExecutor(
                        0, MAX_HANDLER_THREADS,
                        60L, TimeUnit.SECONDS,
                        new java.util.concurrent.SynchronousQueue<Runnable>(),
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "wsbypass-handler");
                                t.setDaemon(true);
                                return t;
                            }
                        },
                        new ThreadPoolExecutor.AbortPolicy());
                this.handlerPool = pool;

                this.keeperPool = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "wsbypass-keeper");
                        t.setDaemon(true);
                        return t;
                    }
                });
                this.keeperTask = keeperPool.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            wsPool.healthScan();
                        } catch (Throwable t) {
                            FileLog.e(t);
                        }
                    }
                }, WS_POOL_KEEPER_INTERVAL_SEC, WS_POOL_KEEPER_INTERVAL_SEC, TimeUnit.SECONDS);

                this.running = true;
                final long generation = getBridgeGeneration();
                Thread accept = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        acceptLoop(generation);
                    }
                }, "wsbypass-accept");
                accept.setDaemon(true);
                this.acceptThread = accept;
                accept.start();

                FileLog.d("WsBypassCore started on " + LOCAL_PROXY_HOST + ":" + this.port);
                return "";
            } catch (Throwable t) {
                FileLog.e("WsBypassCore.start failed", t);
                try { stopLocked(); } catch (Throwable ignored) {}
                return t.getMessage() == null ? "start failed" : t.getMessage();
            }
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            stopLocked();
        }
    }

    public void resetResilienceState() {
        synchronized (cfgLock) {
            failUntilMs.clear();
            failCount.clear();
            blacklist.clear();
            blacklistUntilMs.clear();
            relayFailUntilMs.clear();
            relayFailCount.clear();
        }
    }

    private void stopLocked() {
        running = false;
        invalidateBridgeGeneration();
        ServerSocket s = listener;
        listener = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
        try { wsPool.clear(); } catch (Throwable ignored) {}
        closeAllTracked();

        ScheduledFuture<?> kt = keeperTask;
        keeperTask = null;
        if (kt != null) kt.cancel(false);

        ScheduledExecutorService ksp = keeperPool;
        keeperPool = null;
        if (ksp != null) ksp.shutdownNow();

        ExecutorService hp = handlerPool;
        handlerPool = null;
        if (hp != null) hp.shutdownNow();

        Thread at = acceptThread;
        acceptThread = null;
        if (at != null) {
            try { at.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private void acceptLoop(long generation) {
        try {
            while (running && isBridgeGenerationCurrent(generation)) {
                ServerSocket srv = listener;
                if (srv == null) break;
                
                try {
                    Socket conn;
                    try {
                        conn = srv.accept();
                    } catch (SocketTimeoutException to) {
                        continue;
                    } catch (IOException ex) {
                        if (!running) break;
                        continue;
                    }
                    try {
                        try { conn.setTcpNoDelay(true); } catch (Throwable ignored) {}
                        try {
                            conn.setReceiveBufferSize(SOCK_RCVBUF);
                            conn.setSendBufferSize(SOCK_SNDBUF);
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}

                    dbg("accept: tgnet connected to local proxy from " + conn.getRemoteSocketAddress());
                    if (!trackIfGenerationCurrent(conn, generation)) {
                        closeQuietly(conn);
                        continue;
                    }
                    final Socket fc = conn;
                    ExecutorService pool = handlerPool;
                    if (pool == null) {
                        untrack(fc);
                        closeQuietly(fc);
                        break;
                    }
                    try {
                        pool.submit(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(fc, generation);
                            }
                        });
                    } catch (Throwable t) {
                        untrack(fc);
                        closeQuietly(fc);
                    }
                } catch (Throwable iter) {
                    FileLog.e("WsBypassCore.acceptLoop iteration error", iter);
                    
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally {
            
            if (Thread.currentThread() == acceptThread) {
                running = false;
                FileLog.d("WsBypassCore.acceptLoop exited; running cleared");
            }
        }
    }

    private void handleClient(Socket conn, long generation) {
        try {
            if (!isBridgeGenerationCurrent(generation)) {
                return;
            }
            final byte[] connectionSecret = secretBytes;
            conn.setSoTimeout(HANDSHAKE_READ_TIMEOUT_MS);
            byte[] initPacket = recvExact(conn, MtprotoHandshake.HANDSHAKE_LEN);
            conn.setSoTimeout(0);
            if (!isBridgeGenerationCurrent(generation)) {
                return;
            }

            MtprotoHandshake.HandshakeResult hr = MtprotoHandshake.tryHandshake(initPacket, connectionSecret);
            if (hr == null) { dbg("handshake: FAILED to parse tgnet init (" + initPacket.length + "B) — secret mismatch?"); return; }

            int dc = hr.dcId;
            boolean isMedia = hr.isMedia;
            dbg("handshake OK: dc=" + dc + " media=" + isMedia + " protoTag=" + hr.protoTag);
            int relayDcIdx = isMedia ? -dc : dc;
            byte[] relayInit = MtprotoHandshake.generateRelayInit(hr.protoTag, relayDcIdx);
            CryptoCtx ctx = CryptoCtx.build(hr.decPrekeyIv, connectionSecret, relayInit);
            if (ctx == null) return;
            if (!isBridgeGenerationCurrent(generation)) return;

            String targetIp = dcIp.get(dc);
            if (targetIp == null) targetIp = "";
            targetIp = targetIp.trim();

            long dcKey = poolKey(dc, isMedia);
            final long routeDeadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(WS_ROUTE_DEADLINE_MS);

            RawWebSocket ws = null;
            try {
                ws = connectWsCf(dc, isMedia, Math.min(routeDeadline,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WS_RELAY_BUDGET_MS)),
                        generation);
                dbg("route: CF relay OK (dc=" + dc + ")");
            } catch (Throwable ex) {
                dbg("route: CF relay FAILED (dc=" + dc + "): " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                ws = null;
            }

            if (ws == null) {
                
                dbg("route: relay unavailable; suppressing direct route (dc=" + dc + ")");
                return;
            }

            if (!trackIfGenerationCurrent(ws, generation)) {
                try { ws.close(); } catch (Throwable ignored) {}
                return;
            }
            if (!runIfBridgeGenerationCurrent(generation, () -> failClear(dcKey))) {
                try { ws.close(); } catch (Throwable ignored) {}
                untrack(ws);
                return;
            }
            try {
                if (!isBridgeGenerationCurrent(generation)) {
                    try { ws.close(); } catch (Throwable ignored) {}
                    untrack(ws);
                    return;
                }
                ws.send(relayInit);
                MsgSplitter splitter = null;
                if (SPLIT_UP) {
                    try {
                        splitter = new MsgSplitter(relayInit, hr.protoInt);
                    } catch (Throwable t) {
                        splitter = null;
                    }
                }
                dbg("bridge: started (dc=" + dc + "), relayInit " + relayInit.length + "B sent, splitter=" + (splitter != null));
                bridgeWs(conn, ws, ctx, splitter, generation);
            } catch (Throwable t) {
                try { ws.close(); } catch (Throwable ignored) {}
                untrack(ws);
            }
        } catch (Throwable t) {
            
        } finally {
            closeQuietly(conn);
            untrack(conn);
        }
    }

    private RawWebSocket connectWsCf(int dc, boolean isMedia, long deadlineNanos,
                                     long generation) throws IOException {
        IOException last = null;
        
        final String path = DomainPool.relayPathForDc(dc);
        
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        final int authAccount = org.telegram.messenger.UserConfig.selectedAccount;
        WsRelayAuth.Credential authCredential = null;
        try { headers.put("X-Install", DomainPool.installId()); } catch (Throwable ignored) {}
        try {
            authCredential = WsRelayAuth.getCached(authAccount);
            if (authCredential != null) headers.put("X-Cred", authCredential.header());
        } catch (Throwable ignored) {}
        int attempted = 0;
        for (String host : DomainPool.relayHostsForDc(dc)) {
            if (!isBridgeGenerationCurrent(generation)) {
                throw new IOException("relay connect cancelled");
            }
            if (System.nanoTime() >= deadlineNanos) break;
            if (isRelayInBackoff(host)) continue;
            long hostBudgetMs = DomainPool.isAsiaRelayHost(host) ? 2_500L : 1_000L;
            long attemptDeadline = Math.min(deadlineNanos,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(hostBudgetMs));
            attempted++;
            dbg("connectWsCf: dc=" + dc + " -> " + host + path + " cred=" + headers.containsKey("X-Cred"));
            try {
                RawWebSocket ws = RawWebSocket.connectUntil(host, host, path, headers,
                        attemptDeadline, () -> isBridgeGenerationCurrent(generation));
                if (!isBridgeGenerationCurrent(generation)) {
                    try { ws.close(); } catch (Throwable ignored) {}
                    throw new IOException("relay connect cancelled");
                }
                if (!runIfBridgeGenerationCurrent(generation, () -> relayFailClear(host))) {
                    try { ws.close(); } catch (Throwable ignored) {}
                    throw new IOException("relay connect cancelled");
                }
                dbg("connectWsCf: 101 OK via " + host + path);
                return ws;
            } catch (IOException ex) {
                if (!isBridgeGenerationCurrent(generation)) {
                    throw new IOException("relay connect cancelled", ex);
                }
                dbg("connectWsCf: " + host + " -> " + ex.getMessage());
                int status = ex instanceof RawWebSocket.HandshakeException
                        ? ((RawWebSocket.HandshakeException) ex).statusCode : 0;
                if (!runIfBridgeGenerationCurrent(generation, () -> {
                    if (status == 401 || status == 403) relayFailClear(host);
                    else relayFailRecord(host);
                })) {
                    throw new IOException("relay connect cancelled", ex);
                }
                if (headers.containsKey("X-Cred") && (status == 401 || status == 403)) {
                    final WsRelayAuth.Credential rejectedCredential = authCredential;
                    runIfBridgeGenerationCurrent(generation,
                            () -> WsRelayAuth.invalidateCredential(authAccount, rejectedCredential));
                    headers.remove("X-Cred");
                    authCredential = null;
                }
                last = ex;
            } catch (Throwable t) {
                if (!isBridgeGenerationCurrent(generation)) {
                    throw new IOException("relay connect cancelled", t);
                }
                dbg("connectWsCf: " + host + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
                last = new IOException(t);
            }
        }
        if (attempted == 0) throw new IOException("relay hosts are in backoff");
        if (last != null) throw last;
        throw new IOException("cf websocket unavailable");
    }

    private RawWebSocket connectWsDirect(int dc, boolean isMedia, String targetIp,
                                         long deadlineNanos) throws IOException {
        
        RawWebSocket pooled = wsPool.get(dc, isMedia);
        if (pooled != null) return pooled;

        long dcKey = poolKey(dc, isMedia);
        List<String> domains = DomainPool.wsDomainsForDc(dc, isMedia);
        String pref;
        synchronized (cfgLock) {
            pref = wsDomainPref.get(dcKey);
        }
        if (pref != null && !pref.isEmpty()) {
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            ordered.add(pref);
            for (String d : domains) ordered.add(d);
            domains = new ArrayList<>(ordered);
        }

        IOException last = null;
        Set<String> redirectedDomains = new HashSet<>();
        int attempts = 0;
        for (String domain : domains) {
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            candidates.add(domain);
            if (targetIp != null && !targetIp.isEmpty()) candidates.add(targetIp);
            for (String host : candidates) {
                if (attempts >= MAX_DIRECT_ATTEMPTS || System.nanoTime() >= deadlineNanos) break;
                attempts++;
                try {
                    long attemptDeadline = Math.min(deadlineNanos,
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(900L));
                    RawWebSocket ws = RawWebSocket.connectUntil(host, domain, "/apiws", null,
                            attemptDeadline);
                    synchronized (cfgLock) {
                        wsDomainPref.put(dcKey, domain);
                    }
                    return ws;
                } catch (IOException ex) {
                    last = ex;
                    if (ex instanceof RawWebSocket.HandshakeException
                            && ((RawWebSocket.HandshakeException) ex).isRedirect()) {
                        redirectedDomains.add(domain);
                    }
                } catch (Throwable t) {
                    last = new IOException(t);
                }
            }
            if (attempts >= MAX_DIRECT_ATTEMPTS || System.nanoTime() >= deadlineNanos) break;
        }
        if (!domains.isEmpty() && redirectedDomains.size() == domains.size()) {
            blacklistAdd(dcKey);
        }
        if (last != null) throw last;
        throw new IOException("websocket unavailable");
    }

    private boolean tcpFallback(Socket client, int dc, byte[] relayInit, CryptoCtx ctx,
                                long deadlineNanos, long generation) {
        Integer ov = DC_OVERRIDES.get(dc);
        int dcEff = ov == null ? dc : ov;
        String dst = DIRECT_DC_IP.get(dc);
        if (dst == null) dst = DIRECT_DC_IP.get(dcEff);
        if (dst == null) {
            dst = dcIp.get(dc);
            if (dst == null) dst = dcIp.get(dcEff);
        }
        if (dst == null || dst.trim().isEmpty()) return false;

        Socket remote = null;
        try {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) return false;
            remote = new Socket();
            try { remote.setTcpNoDelay(true); } catch (Throwable ignored) {}
            try {
                remote.setReceiveBufferSize(SOCK_RCVBUF);
                remote.setSendBufferSize(SOCK_SNDBUF);
            } catch (Throwable ignored) {}
            remote.connect(new InetSocketAddress(dst.trim(), 443),
                    (int) Math.max(1L, Math.min(TCP_FALLBACK_TIMEOUT_MS, remainingMs)));
            if (!trackIfGenerationCurrent(remote, generation)) {
                closeQuietly(remote);
                return false;
            }
            OutputStream rout = remote.getOutputStream();
            rout.write(relayInit);
            rout.flush();
            bridgeTcp(client, remote, ctx, generation);
            return true;
        } catch (Throwable t) {
            closeQuietly(remote);
            untrack(remote);
            closeQuietly(client);
            return false;
        }
    }

    private void bridgeTcp(final Socket client, final Socket remote, final CryptoCtx ctx,
                           final long generation) {
        if (!markBridgeStarted(generation)) {
            closeQuietly(client);
            closeQuietly(remote);
            untrack(remote);
            return;
        }
        try {
        final AtomicBoolean done = new AtomicBoolean(false);
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                lowerPriority();
                try {
                    InputStream in = client.getInputStream();
                    OutputStream out = remote.getOutputStream();
                    byte[] buf = new byte[RECV_CHUNK];
                    while (!done.get()) {
                        int n = in.read(buf);
                        if (n <= 0) break;
                        byte[] chunk = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                        byte[] plain = cipherUpdate(ctx.cltDec, chunk);
                        byte[] outBuf = cipherUpdate(ctx.tgEnc, plain);
                        if (outBuf != null && outBuf.length > 0) {
                            out.write(outBuf);
                            out.flush();
                        }
                    }
                } catch (Throwable ignored) {}
                done.set(true);
                shutdownWrite(remote);
            }
        }, "wsbypass-tcp-up");
        t1.setDaemon(true);

        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                lowerPriority();
                try {
                    InputStream in = remote.getInputStream();
                    OutputStream out = client.getOutputStream();
                    byte[] buf = new byte[RECV_CHUNK];
                    while (!done.get()) {
                        int n = in.read(buf);
                        if (n <= 0) break;
                        byte[] chunk = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                        byte[] plain = cipherUpdate(ctx.tgDec, chunk);
                        byte[] outBuf = cipherUpdate(ctx.cltEnc, plain);
                        if (outBuf != null && outBuf.length > 0) {
                            out.write(outBuf);
                            out.flush();
                            markBridgeOk(generation);
                        }
                    }
                } catch (Throwable ignored) {}
                done.set(true);
                shutdownWrite(client);
            }
        }, "wsbypass-tcp-down");
        t2.setDaemon(true);

        t1.start();
        t2.start();
        
        try {
            while (!done.get() && (t1.isAlive() || t2.isAlive())) {
                Thread.sleep(50);
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        done.set(true);
        closeQuietly(client);
        closeQuietly(remote);
        try { t1.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { t2.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        untrack(remote);
        } finally {
            markBridgeStopped(generation);
        }
    }

    private void bridgeWs(final Socket client, final RawWebSocket ws, final CryptoCtx ctx,
                          final MsgSplitter splitter, final long generation) {
        if (!markBridgeStarted(generation)) {
            try { ws.close(); } catch (Throwable ignored) {}
            closeQuietly(client);
            untrack(ws);
            return;
        }
        try {
        final AtomicBoolean done = new AtomicBoolean(false);
        final long[] upBytes = {0};   
        final long[] upSent = {0};    
        final long[] downBytes = {0};
        final int connId = CONN_SEQ.incrementAndGet();
        final boolean[] loggedUp = {false};
        final boolean[] loggedDown = {false};
        Thread up = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = client.getInputStream();
                    byte[] buf = new byte[RECV_CHUNK];
                    while (!done.get()) {
                        int n = in.read(buf);
                        if (n <= 0) {
                            dbg("bridge up: client(tgnet) EOF n=" + n + " after up=" + upBytes[0] + "B down=" + downBytes[0] + "B");
                            if (splitter != null) {
                                try {
                                    List<byte[]> tail = splitter.flush();
                                    for (byte[] p : tail) {
                                        try { ws.send(p); } catch (Throwable t) { break; }
                                    }
                                } catch (Throwable ignored) {}
                            }
                            break;
                        }
                        upBytes[0] += n;
                        byte[] chunk = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                        byte[] plain = cipherUpdate(ctx.cltDec, chunk);
                        if (!loggedUp[0]) { loggedUp[0] = true; decodeMtproto(connId, "UP(client->DC)", plain); }
                        byte[] data = cipherUpdate(ctx.tgEnc, plain);
                        if (data == null || data.length == 0) continue;
                        if (splitter != null) {
                            List<byte[]> parts = splitter.split(data);
                            if (parts == null || parts.isEmpty()) continue;
                            if (parts.size() > 1) {
                                
                                ws.sendBatch(parts);
                            } else {
                                ws.send(parts.get(0));
                            }
                            for (byte[] p : parts) upSent[0] += p.length;
                        } else {
                            ws.send(data);
                            upSent[0] += data.length;
                        }
                    }
                } catch (Throwable t) { dbg("bridge up-thread end: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
                done.set(true);
            }
        }, "wsbypass-ws-up");
        up.setDaemon(true);

        Thread down = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream out = client.getOutputStream();
                    byte[] plainBuffer = new byte[64 * 1024 + 32];
                    byte[] clientBuffer = new byte[64 * 1024 + 32];
                    while (!done.get()) {
                        byte[] payload = ws.recv();
                        if (payload == null) { dbg("bridge down: ws.recv returned null (CLOSE)"); break; }
                        if (payload.length == 0) continue;
                        downBytes[0] += payload.length;
                        plainBuffer = ensureCipherBuffer(ctx.tgDec, payload.length, plainBuffer);
                        int plainLength = cipherUpdateInto(
                                ctx.tgDec, payload, payload.length, plainBuffer);
                        if (!loggedDown[0]) {
                            loggedDown[0] = true;
                            if (DEBUG) {
                                decodeMtproto(connId, "DOWN(DC->client)",
                                        Arrays.copyOf(plainBuffer, plainLength));
                            }
                        }
                        clientBuffer = ensureCipherBuffer(ctx.cltEnc, plainLength, clientBuffer);
                        int outputLength = cipherUpdateInto(
                                ctx.cltEnc, plainBuffer, plainLength, clientBuffer);
                        if (outputLength > 0) {
                            out.write(clientBuffer, 0, outputLength);
                            out.flush();
                            markBridgeOk(generation);
                        }
                    }
                } catch (Throwable t) { dbg("bridge down-thread end: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
                done.set(true);
            }
        }, "wsbypass-ws-down");
        down.setDaemon(true);

        up.start();
        down.start();
        
        final long startMs = System.currentTimeMillis();
        long nextLog = startMs + 2000;
        try {
            while (!done.get() && (up.isAlive() || down.isAlive())) {
                Thread.sleep(50);
                if (DEBUG && System.currentTimeMillis() >= nextLog) {
                    int pend = splitter == null ? 0 : splitter.pendingBytes();
                    dbg("bridge ALIVE dur=" + ((System.currentTimeMillis() - startMs) / 1000.0)
                            + "s upRead=" + upBytes[0] + "B upSent=" + upSent[0] + "B splitPend=" + pend
                            + "B down=" + downBytes[0] + "B"
                            + (upBytes[0] - upSent[0] > 1024 ? "  <-- UP STUCK (read>>sent)" : ""));
                    nextLog += 2000;
                }
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        done.set(true);
        dbg("bridge CLOSED dur=" + ((System.currentTimeMillis() - startMs) / 1000.0)
                + "s upRead=" + upBytes[0] + "B upSent=" + upSent[0] + "B down=" + downBytes[0] + "B"
                + (downBytes[0] == 0 ? "  <-- NO DATA FROM DC" : "")
                + (upBytes[0] - upSent[0] > 1024 ? "  <-- UP UNSENT (splitter held bytes)" : ""));
        try { ws.close(); } catch (Throwable ignored) {}
        closeQuietly(client);
        try { up.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { down.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        untrack(ws);
        untrack(client);
        } finally {
            markBridgeStopped(generation);
        }
    }

    private static long poolKey(int dc, boolean isMedia) {
        return ((long) dc << 1) | (isMedia ? 1L : 0L);
    }

    private boolean isInFailBackoff(long dcKey) {
        long now = nowElapsedMs();
        long until;
        synchronized (cfgLock) {
            Long v = failUntilMs.get(dcKey);
            until = v == null ? 0L : v;
        }
        return now < until;
    }

    private void failRecord(long dcKey) {
        synchronized (cfgLock) {
            Integer v = failCount.get(dcKey);
            int next = (v == null ? 0 : v) + 1;
            int expCap = Math.max(0, Math.min(next - 1, 4));
            double backoff = WS_FAIL_COOLDOWN_SEC * (1L << expCap);
            if (backoff > WS_FAIL_COOLDOWN_MAX_SEC) backoff = WS_FAIL_COOLDOWN_MAX_SEC;
            backoff *= java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.85, 1.16);
            long deadline = nowElapsedMs() + (long) (backoff * 1000.0);
            failCount.put(dcKey, next);
            failUntilMs.put(dcKey, deadline);
        }
    }

    private void failClear(long dcKey) {
        synchronized (cfgLock) {
            failUntilMs.remove(dcKey);
            failCount.remove(dcKey);
        }
    }

    private boolean isBlacklisted(long dcKey) {
        long now = nowElapsedMs();
        synchronized (cfgLock) {
            Long until = blacklistUntilMs.get(dcKey);
            if (until == null) return false;
            if (now >= until) {
                blacklistUntilMs.remove(dcKey);
                blacklist.remove(dcKey);
                return false;
            }
            return true;
        }
    }

    private void blacklistAdd(long dcKey) {
        synchronized (cfgLock) {
            blacklistUntilMs.put(dcKey, nowElapsedMs() + (long) (WS_BLACKLIST_TTL_SEC * 1000.0));
            blacklist.add(dcKey);
        }
    }

    private boolean isRelayInBackoff(String host) {
        synchronized (cfgLock) {
            Long until = relayFailUntilMs.get(host);
            if (until == null) return false;
            if (nowElapsedMs() >= until) {
                relayFailUntilMs.remove(host);
                return false;
            }
            return true;
        }
    }

    private void relayFailRecord(String host) {
        synchronized (cfgLock) {
            int failures = relayFailCount.containsKey(host) ? relayFailCount.get(host) + 1 : 1;
            relayFailCount.put(host, failures);
            int exp = Math.min(4, Math.max(0, failures - 1));
            long base = Math.min(300_000L, 15_000L * (1L << exp));
            long jittered = (long) (base
                    * java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.85, 1.16));
            relayFailUntilMs.put(host, nowElapsedMs() + jittered);
        }
    }

    private void relayFailClear(String host) {
        synchronized (cfgLock) {
            relayFailUntilMs.remove(host);
            relayFailCount.remove(host);
        }
    }

    private static long nowElapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private void track(Object closer) {
        if (closer == null) return;
        synchronized (trackedLock) {
            tracked.add(closer);
        }
    }

    private boolean trackIfGenerationCurrent(Object closer, long generation) {
        if (closer == null) return false;
        synchronized (bridgeStateLock) {
            if (generation != bridgeGeneration) return false;
            synchronized (trackedLock) {
                tracked.add(closer);
            }
            return true;
        }
    }

    private boolean runIfBridgeGenerationCurrent(long generation, Runnable action) {
        synchronized (bridgeStateLock) {
            if (generation != bridgeGeneration) return false;
            action.run();
            return true;
        }
    }

    private void untrack(Object closer) {
        if (closer == null) return;
        synchronized (trackedLock) {
            tracked.remove(closer);
        }
    }

    private void closeAllTracked() {
        List<Object> snapshot;
        synchronized (trackedLock) {
            snapshot = new ArrayList<>(tracked);
            tracked.clear();
        }
        for (Object o : snapshot) {
            try {
                if (o instanceof Socket) {
                    ((Socket) o).close();
                } else if (o instanceof RawWebSocket) {
                    ((RawWebSocket) o).close();
                } else if (o instanceof java.io.Closeable) {
                    ((java.io.Closeable) o).close();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static byte[] recvExact(Socket conn, int size) throws IOException {
        InputStream in = conn.getInputStream();
        byte[] out = new byte[size];
        int off = 0;
        while (off < size) {
            int r = in.read(out, off, size - off);
            if (r < 0) throw new IOException("unexpected eof");
            off += r;
        }
        return out;
    }

    private static byte[] cipherUpdate(javax.crypto.Cipher cipher, byte[] data) {
        if (cipher == null || data == null || data.length == 0) return new byte[0];
        try {
            byte[] out = cipher.update(data);
            return out == null ? new byte[0] : out;
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static byte[] ensureCipherBuffer(javax.crypto.Cipher cipher, int inputLength,
                                             byte[] current) {
        if (cipher == null) {
            return current;
        }
        int required = Math.max(inputLength + 32, cipher.getOutputSize(inputLength));
        return current.length >= required ? current : new byte[required];
    }

    private static int cipherUpdateInto(javax.crypto.Cipher cipher, byte[] input, int inputLength,
                                        byte[] output) throws javax.crypto.ShortBufferException {
        if (cipher == null || input == null || inputLength <= 0) {
            return 0;
        }
        return cipher.update(input, 0, inputLength, output, 0);
    }

    private static void shutdownWrite(Socket s) {
        if (s == null) return;
        try { s.shutdownOutput(); } catch (Throwable ignored) {}
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Throwable ignored) {}
    }

    private static void lowerPriority() {
        try {
            int target = Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2);
            Thread.currentThread().setPriority(target);
        } catch (Throwable ignored) {}
    }

    public static boolean isWsBypassPluginFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return false;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null && lines < 30) {
                lines++;
                String trimmed = line.trim();
                if (trimmed.startsWith("__id__")) {
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) continue;
                    String rhs = trimmed.substring(eq + 1).trim();
                    if (rhs.length() < 2) continue;
                    char q = rhs.charAt(0);
                    if (q != '"' && q != '\'') continue;
                    int end = rhs.indexOf(q, 1);
                    if (end < 0) continue;
                    String id = rhs.substring(1, end);
                    return "wsbypass".equals(id);
                }
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-wsbypass: isWsBypassPluginFile failed", t);
        }
        return false;
    }
}
