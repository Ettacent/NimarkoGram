/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.wsbypass.voip;

import android.os.SystemClock;
import app.nimarkogram.messenger.wsbypass.VpnDetector;
import app.nimarkogram.messenger.wsbypass.WsBypassConfig;
import app.nimarkogram.messenger.wsbypass.WlAccess;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import org.telegram.messenger.UserConfig;

public final class VoipBypassCore {

    public static final String TAG = "NimarkoVoIP";

    public static final boolean DEBUG = false;
    public static void dlog(String msg) {}

    private static final int STATUS_OK = 0;
    private static final int STATUS_AUTH_FAIL = 1;

    private static final int ALLOC_TIMEOUT_MS = 3000;

    private static final int PRIMARY_ATTEMPT_MAX_MS = 350;

    private static final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "voip-relay-alloc");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService dnsExecutor = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> {
        Thread t = new Thread(r, "voip-relay-dns");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final long DNS_TTL_MS = 60_000L;
    private static final ConcurrentHashMap<String, ResolvedRelay> dnsCache =
            new ConcurrentHashMap<>();

    private static final class ResolvedRelay {
        final InetAddress address;
        final long resolvedAtMs;
        ResolvedRelay(InetAddress address, long resolvedAtMs) {
            this.address = address;
            this.resolvedAtMs = resolvedAtMs;
        }
    }

    private static volatile VoipBypassCore instance;

    public static VoipBypassCore getInstance() {
        VoipBypassCore local = instance;
        if (local == null) {
            synchronized (VoipBypassCore.class) {
                local = instance;
                if (local == null) {
                    local = new VoipBypassCore();
                    instance = local;
                }
            }
        }
        return local;
    }

    private VoipBypassCore() {}

    public static final class RelayEndpoint {
        public final String host;
        public final int port;

        public RelayEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public RelayEndpoint allocateRelay(String reflectorIp, int reflectorPort) {
        return allocateRelay(reflectorIp, reflectorPort, UserConfig.selectedAccount, ALLOC_TIMEOUT_MS + 500);
    }

    public RelayEndpoint allocateRelay(String reflectorIp, int reflectorPort, int budgetMs) {
        return allocateRelay(reflectorIp, reflectorPort, UserConfig.selectedAccount, budgetMs);
    }

    public RelayEndpoint allocateRelay(String reflectorIp, int reflectorPort, int account, int budgetMs) {
        if (budgetMs <= 0) return null;
        if (!VoipRelayAuth.isAuthAllowed()) return null;
        String protocolTarget = VoipBypassConfig.relayProtocolTarget(reflectorIp);
        if (protocolTarget == null) return null;

        if (WsBypassConfig.suspendOnVpn
                && VpnDetector.isVpnActiveFresh()) {
            return null;
        }
        if (WlAccess.enabled()) {
            Future<RelayEndpoint> pending = executor.submit(() ->
                    WlAccess.allocateCall(protocolTarget, reflectorPort, budgetMs));
            try {
                return pending.get(budgetMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        }

        VoipRelayAuth.Credential cred = VoipRelayAuth.getCached(account);
        if (cred == null) {
            Log.e(TAG, "allocateRelay: no relay credential (auth unavailable)");
            return null;
        }

        final int controlPort = VoipBypassConfig.RELAY_CONTROL_PORT;
        final byte[] request = VoipRelayProof.request(protocolTarget, reflectorPort, cred.expiry, cred.uid,
                cred.hmac, VoipRelayAuth.nowSeconds(account) * 1000L);
        if (request == null) {
            Log.e(TAG, "allocateRelay: failed to build request");
            return null;
        }

        final String[] relayHosts = VoipBypassConfig.relayHosts(account);
        final long deadline = SystemClock.elapsedRealtime() + budgetMs;
        final AtomicBoolean authRejected =
                new AtomicBoolean(false);
        for (int i = 0; i < relayHosts.length; i++) {
            int remaining = (int) (deadline - SystemClock.elapsedRealtime());
            if (remaining <= 0) break;
            int attemptBudget = i + 1 < relayHosts.length
                    ? Math.min(PRIMARY_ATTEMPT_MAX_MS, remaining) : remaining;
            RelayEndpoint endpoint = allocateOnHost(relayHosts[i], controlPort, request, cred, protocolTarget,
                    reflectorPort, account, attemptBudget, authRejected);
            if (endpoint != null) return endpoint;
            if (authRejected.get()) break;
        }
        VoipRelayAuth.refreshAfterAllocationFailure(account);
        return null;
    }

    private RelayEndpoint allocateOnHost(final String relayHost, final int controlPort, final byte[] request,
                                         final VoipRelayAuth.Credential credential,
                                         final String reflectorIp, final int reflectorPort, final int account,
                                         final int budgetMs,
                                         final AtomicBoolean authRejected) {

        final AtomicReference<DatagramSocket> sockRef = new AtomicReference<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final long deadline = SystemClock.elapsedRealtime() + budgetMs;
        Callable<RelayEndpoint> task = () -> {
            DatagramSocket sock = null;
            try {
                InetAddress addr = resolveRelay(relayHost, deadline);
                if (addr == null || cancelled.get() || Thread.currentThread().isInterrupted()) return null;
                int remaining = (int) (deadline - SystemClock.elapsedRealtime());
                if (remaining <= 0) return null;
                sock = new DatagramSocket();
                sockRef.set(sock);
                if (cancelled.get() || Thread.currentThread().isInterrupted()) return null;
                sock.connect(addr, controlPort);
                sock.setSoTimeout(Math.max(1, Math.min(ALLOC_TIMEOUT_MS, remaining)));
                DatagramPacket pkt = new DatagramPacket(request, request.length);
                sock.send(pkt);

                byte[] buf = new byte[64];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                sock.receive(resp);

                if (!addr.equals(resp.getAddress()) || resp.getPort() != controlPort) {
                    Log.e(TAG, "allocateRelay: response source mismatch");
                    return null;
                }

                int[] verified = VoipRelayProof.reply(buf, resp.getLength(), request, credential.hmac);
                if (verified == null) {
                    Log.e(TAG, "allocateRelay: unauthenticated response rejected");
                    return null;
                }
                int status = verified[0];
                int allocatedPort = verified[1];
                if (status != STATUS_OK) {
                    Log.e(TAG, "allocateRelay: server error status=" + status);
                    if (status == STATUS_AUTH_FAIL) {
                        authRejected.set(true);
                        VoipRelayAuth.invalidateCredential(account, credential);
                    }
                    return null;
                }
                if (allocatedPort == 0) {
                    Log.e(TAG, "allocateRelay: allocated port is 0");
                    return null;
                }

                String exactRelayIp = addr.getHostAddress();
                return new RelayEndpoint(exactRelayIp, allocatedPort);
            } finally {
                if (sock != null) sock.close();
            }
        };

        Future<RelayEndpoint> future = executor.submit(task);
        try {
            return future.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.e(TAG, "allocateRelay error: " + e);
            return null;
        } finally {

            cancelled.set(true);
            future.cancel(true);
            DatagramSocket s = sockRef.get();
            if (s != null && !s.isClosed()) {
                try { s.close(); } catch (Throwable ignore) {}
            }
        }
    }

    private static InetAddress resolveRelay(String host, long deadlineMs) throws Exception {
        long now = SystemClock.elapsedRealtime();
        ResolvedRelay cached = dnsCache.get(host);
        if (cached != null && now - cached.resolvedAtMs < DNS_TTL_MS) return cached.address;
        int remaining = (int) (deadlineMs - now);
        if (remaining <= 0) return null;
        final Future<InetAddress[]> future;
        try {
            future = dnsExecutor.submit(() -> InetAddress.getAllByName(host));
        } catch (RejectedExecutionException saturated) {
            return null;
        }
        try {
            InetAddress[] addresses = future.get(remaining, TimeUnit.MILLISECONDS);
            if (addresses == null || addresses.length == 0) return null;

            InetAddress selected = addresses[0];
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address) { selected = address; break; }
            }
            dnsCache.put(host, new ResolvedRelay(selected, SystemClock.elapsedRealtime()));
            return selected;
        } finally {
            future.cancel(true);
        }
    }


    @Deprecated
    public int prepareReflector(String reflectorIp, int reflectorPort) {
        RelayEndpoint ep = allocateRelay(reflectorIp, reflectorPort);
        return ep != null ? ep.port : 0;
    }

    public void stopAll() {
        Log.d(TAG, "stopAll: no-op (server handles session GC)");
    }

    public int activeReflectorCount() {
        return 0;
    }
}
