package app.nimarkogram.messenger.wsbypass;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NimarkoWsBypassController {

    public static final String STATE_OFF = "off";
    public static final String STATE_STARTING = "starting";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_VPN = "vpn"; 

    private static final long WATCHDOG_INTERVAL_SEC = 30L;

    private static volatile NimarkoWsBypassController instance;

    public static NimarkoWsBypassController getInstance() {
        NimarkoWsBypassController local = instance;
        if (local == null) {
            synchronized (NimarkoWsBypassController.class) {
                local = instance;
                if (local == null) {
                    local = new NimarkoWsBypassController();
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Object lifecycleLock = new Object();
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean resumeCheckPending = new AtomicBoolean(false);
    
    private long nextStartToken;
    private long activeStartToken;
    private long watchdogGeneration;
    private volatile boolean running;
    private volatile boolean lastStartFailed;
    private volatile String lastError = "";
    private volatile int currentPort;
    private volatile String currentSecret = "";
    private volatile Runnable settingsReloader;
    private volatile ScheduledExecutorService watchdogPool;
    private volatile ScheduledFuture<?> watchdogTask;

    private NimarkoWsBypassController() {
    }

    public void setSettingsReloader(Runnable reloader) {
        this.settingsReloader = reloader;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isStarting() {
        return starting.get();
    }

    public String getLastError() {
        return lastError == null ? "" : lastError;
    }

    public boolean blockedByVpn() {
        return app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isSuspendOnVpnEnabled() && NimarkoVpnDetector.isVpnActive();
    }

    private boolean blockedByVpnFresh() {
        return app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isSuspendOnVpnEnabled() && NimarkoVpnDetector.isVpnActiveFresh();
    }

    private boolean enforceVpnSuspensionFresh() {
        if (!blockedByVpnFresh()) return false;
        onVpnStateChanged(true);
        return true;
    }

    public void reevaluateForVpnToggle() {
        onVpnStateChanged(NimarkoVpnDetector.isVpnActiveFresh());
    }

    public String getConnectionState() {
        if (!app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isDataBypassEnabled()) return STATE_OFF;
        if (blockedByVpn()) return STATE_VPN;
        if (running) {
            
            try {
                WsBypassCore core = WsBypassCore.getInstance();
                if (!core.isRunning() || !core.isAcceptThreadAlive()) return STATE_FAILED;
                if (!core.hasActiveBridge() || core.getLastBridgeOkAtMs() == 0L) return STATE_STARTING;
            } catch (Throwable ignored) {}
            return STATE_RUNNING;
        }
        if (starting.get()) return STATE_STARTING;
        if (lastStartFailed) return STATE_FAILED;
        return STATE_OFF;
    }

    public void ensureStarted() {
        if (!app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isDataBypassEnabled() || enforceVpnSuspensionFresh()) return;
        if (running || starting.get()) return;
        startAsync();
    }

    public void ensureStartedSync() {
        if (!app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isDataBypassEnabled() || enforceVpnSuspensionFresh()) return;
        long token = claimStart();
        if (token == 0L) return;
        try {
            startSync(token);
        } catch (Throwable t) {
            FileLog.e("NimarkoWsBypassController.ensureStartedSync", t);
            releaseStart(token);
        }
    }

    public void setEnabled(boolean v) {
        long token = 0L;
        synchronized (lifecycleLock) {
            
            NimarkoWsBypassConfig.setEnabled(v);
            if (v) {
                if (blockedByVpnFresh()) {
                    suspendForVpn();
                } else {
                    token = claimStartLocked();
                }
            } else {
                stop();
            }
            notifyReloader();
        }
        if (token != 0L) launchStartThread(token);
    }

    public void onVpnStateChanged(boolean vpnActive) {
        
        boolean suspend = vpnActive
                && app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isSuspendOnVpnEnabled();
        if (suspend) {
            suspendForVpn();
        } else {
            resumeAfterVpn();
        }
    }

    private void suspendForVpn() {
        synchronized (lifecycleLock) {
            
            if (!blockedByVpn()) return;
            invalidateStartLocked();
            cancelRelayAuthConnections();
            cancelWatchdogLocked();
            try { WsBypassCore.getInstance().stop(); } catch (Throwable ignored) {}
            
            try { ProxyApplier.suspendForVpn(WsBypassCore.LOCAL_PROXY_HOST); } catch (Throwable ignored) {}
            try { ProxyApplier.removeLocalFromList(WsBypassCore.LOCAL_PROXY_HOST); } catch (Throwable ignored) {}
            running = false;
            currentPort = 0;
            currentSecret = "";
            notifyReloader();
        }
    }

    private void resumeAfterVpn() {
        long token = 0L;
        synchronized (lifecycleLock) {
            
            if (blockedByVpnFresh()) return;
            try { ProxyApplier.restoreForVpn(); } catch (Throwable ignored) {}
            if (app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                    .isDataBypassEnabled()) {
                token = claimStartLocked();
            }
            notifyReloader();
        }
        if (token != 0L) launchStartThread(token);
    }

    public void stop() {
        synchronized (lifecycleLock) {
            invalidateStartLocked();
            cancelRelayAuthConnections();
            cancelWatchdogLocked();
            try {
                WsBypassCore.getInstance().stop();
            } catch (Throwable t) {
                FileLog.e("NimarkoWsBypassController.stop core error", t);
            }
            try {
                ProxyApplier.apply(0, false, "", WsBypassCore.LOCAL_PROXY_HOST);
            } catch (Throwable t) {
                FileLog.e("NimarkoWsBypassController.stop proxy error", t);
            }
            try {
                ProxyApplier.removeLocalFromList(WsBypassCore.LOCAL_PROXY_HOST);
            } catch (Throwable ignored) {}
            running = false;
            currentPort = 0;
            currentSecret = "";
            notifyReloader();
        }
    }

    public void onAppResume() {
        final boolean dataEnabled = app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isDataBypassEnabled();
        final boolean suspendOnVpn = app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isSuspendOnVpnEnabled();
        if (!dataEnabled && !suspendOnVpn) {
            return;
        }
        if (!resumeCheckPending.compareAndSet(false, true)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                
                NimarkoVpnDetector.recheckNow();
                WsBypassCore.getInstance().resetResilienceState();
                if (app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                        .isDataBypassEnabled() && !enforceVpnSuspensionFresh()
                        && !running && !starting.get()) {
                    startAsync();
                }
            } catch (Throwable ignored) {
            } finally {
                resumeCheckPending.set(false);
                notifyReloader();
            }
        });
    }

    private void startAsync() {
        long token = claimStart();
        if (token == 0L) return;
        launchStartThread(token);
    }

    private long claimStart() {
        synchronized (lifecycleLock) {
            return claimStartLocked();
        }
    }

    private long claimStartLocked() {
        if (running || activeStartToken != 0L) return 0L;
        long token = ++nextStartToken;
        if (token == 0L) token = ++nextStartToken;
        activeStartToken = token;
        starting.set(true);
        return token;
    }

    private void invalidateStartLocked() {
        activeStartToken = 0L;
        starting.set(false);
    }

    private void releaseStart(long token) {
        synchronized (lifecycleLock) {
            releaseStartLocked(token);
        }
    }

    private void releaseStartLocked(long token) {
        if (activeStartToken != token) return;
        activeStartToken = 0L;
        starting.set(false);
    }

    private void launchStartThread(final long token) {
        notifyReloader();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                startSync(token);
            }
        }, "wsbypass-start");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable startFailure) {
            synchronized (lifecycleLock) {
                releaseStartLocked(token);
                lastError = String.valueOf(startFailure.getMessage());
                lastStartFailed = true;
                if (app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                        .isDataBypassEnabled() && !blockedByVpn()) {
                    ensureWatchdogLocked();
                }
            }
            FileLog.e("NimarkoWsBypassController start thread failed", startFailure);
            notifyReloader();
        }
    }

    private void startSync(long token) {
        synchronized (lifecycleLock) {
            if (activeStartToken != token) return;
            try {
                
                if (!app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                        .isDataBypassEnabled()) {
                    return; 
                }
                if (blockedByVpnFresh()) {
                    suspendForVpn();
                    return; 
                }
                int desiredPort = NimarkoWsBypassConfig.localPort;
                String secret = NimarkoWsBypassConfig.mtprotoSecret;
                if (secret == null || secret.isEmpty()) {
                    secret = MtprotoHandshake.generateSecretHex();
                    NimarkoWsBypassConfig.setMtprotoSecret(secret);
                }

                WsBypassCore core = WsBypassCore.getInstance();
                String err = core.start(desiredPort, secret);
                if (err != null && !err.isEmpty()) {
                    lastError = err;
                    lastStartFailed = true;
                    running = false;
                    ensureWatchdogLocked();
                    return;
                }

                int boundPort = core.getPort();
                currentPort = boundPort;
                currentSecret = core.getSecretHex();
                if (boundPort > 0 && boundPort != desiredPort) {
                    NimarkoWsBypassConfig.setLocalPort(boundPort);
                }

                boolean proxyApplied;
                try {
                    proxyApplied = ProxyApplier.apply(
                            boundPort, true, currentSecret, WsBypassCore.LOCAL_PROXY_HOST);
                } catch (Throwable t) {
                    FileLog.e("NimarkoWsBypassController.startSync proxy apply error", t);
                    lastError = String.valueOf(t.getMessage());
                    lastStartFailed = true;
                    running = false;
                    try { core.stop(); } catch (Throwable ignored) {}
                    currentPort = 0;
                    currentSecret = "";
                    ensureWatchdogLocked();
                    return;
                }
                if (!proxyApplied) {
                    if (blockedByVpnFresh()) {
                        
                        lastStartFailed = false;
                        lastError = "";
                        suspendForVpn();
                        return;
                    }
                    lastError = "proxy apply failed";
                    lastStartFailed = true;
                    running = false;
                    try { core.stop(); } catch (Throwable ignored) {}
                    try { ProxyApplier.apply(0, false, "", WsBypassCore.LOCAL_PROXY_HOST); } catch (Throwable ignored) {}
                    try { ProxyApplier.removeLocalFromList(WsBypassCore.LOCAL_PROXY_HOST); } catch (Throwable ignored) {}
                    currentPort = 0;
                    currentSecret = "";
                    ensureWatchdogLocked();
                    return;
                }

                running = true;
                lastStartFailed = false;
                lastError = "";
                try {
                    int account = org.telegram.messenger.UserConfig.selectedAccount;
                    WsRelayAuth.prefetchAsync(account);
                    if (app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig.isVoipBypassEnabled()) {
                        app.nimarkogram.messenger.wsbypass.voip.VoipRelayAuth.prefetchAsync(account);
                    }
                } catch (Throwable ignored) {}
                ensureWatchdogLocked();
                FileLog.d("NimarkoWsBypassController: started on 127.0.0.1:" + boundPort);
            } catch (Throwable t) {
                FileLog.e("NimarkoWsBypassController.startSync error", t);
                if (blockedByVpnFresh()) {
                    lastStartFailed = false;
                    lastError = "";
                    suspendForVpn();
                } else {
                    lastError = String.valueOf(t.getMessage());
                    lastStartFailed = true;
                    running = false;
                    try { WsBypassCore.getInstance().stop(); } catch (Throwable ignored) {}
                    try {
                        ProxyApplier.apply(0, false, "", WsBypassCore.LOCAL_PROXY_HOST);
                    } catch (Throwable ignored) {}
                    try {
                        ProxyApplier.removeLocalFromList(WsBypassCore.LOCAL_PROXY_HOST);
                    } catch (Throwable ignored) {}
                    currentPort = 0;
                    currentSecret = "";
                    ensureWatchdogLocked();
                }
            } finally {
                releaseStartLocked(token);
                notifyReloader();
            }
        }
    }

    private void startWatchdogLocked() {
        cancelWatchdogLocked();
        final long generation = ++watchdogGeneration;
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "wsbypass-watchdog");
                t.setDaemon(true);
                return t;
            }
        });
        watchdogPool = pool;
        try {
            watchdogTask = pool.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        watchdogTick(generation);
                    } catch (Throwable t) {
                        FileLog.e("NimarkoWsBypassController watchdog tick error", t);
                    }
                }
            }, WATCHDOG_INTERVAL_SEC, WATCHDOG_INTERVAL_SEC, TimeUnit.SECONDS);
        } catch (Throwable scheduleFailure) {
            watchdogTask = null;
            watchdogPool = null;
            pool.shutdownNow();
            FileLog.e("NimarkoWsBypassController watchdog start failed", scheduleFailure);
        }
    }

    private void watchdogTick(long generation) {
        synchronized (lifecycleLock) {
            if (generation != watchdogGeneration || watchdogTask == null) return;
            if (!app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                    .isDataBypassEnabled()) return;
            
            if (blockedByVpnFresh()) {
                
                suspendForVpn();
                return;
            }
            if (activeStartToken != 0L) return;

            if (!running) {
                FileLog.d("NimarkoWsBypassController watchdog: enabled but not running, restarting");
                restartCoreLocked();
                return;
            }

            boolean coreAlive;
            try {
                WsBypassCore core = WsBypassCore.getInstance();
                coreAlive = core.isRunning() && core.isAcceptThreadAlive();
            } catch (Throwable t) {
                coreAlive = false;
            }
            if (!coreAlive) {
                FileLog.d("NimarkoWsBypassController watchdog: core dead while running, restarting");
                restartCoreLocked();
                return;
            }

            int p = currentPort;
            if (p <= 0) return;
            if (!ProxyApplier.isLocalProxyActive(
                    WsBypassCore.LOCAL_PROXY_HOST, p, currentSecret)) {
                FileLog.d("NimarkoWsBypassController watchdog: proxy state diverged, re-applying");
                if (!ProxyApplier.apply(
                        p, true, currentSecret, WsBypassCore.LOCAL_PROXY_HOST)) {
                    restartCoreLocked();
                }
            }
        }
    }

    private void restartCoreLocked() {
        if (!app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .isDataBypassEnabled() || activeStartToken != 0L) {
            return;
        }
        if (blockedByVpnFresh()) {
            suspendForVpn();
            return;
        }
        try {
            running = false;
            try { WsBypassCore.getInstance().stop(); } catch (Throwable ignored) {}
            long token = claimStartLocked();
            if (token != 0L) startSync(token);
        } catch (Throwable t) {
            FileLog.e("NimarkoWsBypassController.restartCore error", t);
        }
    }

    private void cancelWatchdogLocked() {
        watchdogGeneration++;
        ScheduledFuture<?> task = watchdogTask;
        watchdogTask = null;
        if (task != null) task.cancel(false);
        ScheduledExecutorService pool = watchdogPool;
        watchdogPool = null;
        if (pool != null) pool.shutdownNow();
    }

    private static void cancelRelayAuthConnections() {
        WsRelayAuth.cancelPendingAuth();
        app.nimarkogram.messenger.wsbypass.voip.VoipRelayAuth.cancelPendingAuth();
    }

    private void ensureWatchdogLocked() {
        ScheduledFuture<?> task = watchdogTask;
        if (task == null || task.isCancelled() || task.isDone()) {
            startWatchdogLocked();
        }
    }

    private void notifyReloader() {
        final Runnable r = settingsReloader;
        if (r == null) return;
        try {
            AndroidUtilities.runOnUIThread(r);
        } catch (Throwable ignored) {}
    }
}
