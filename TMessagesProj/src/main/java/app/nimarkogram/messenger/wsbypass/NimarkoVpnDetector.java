package app.nimarkogram.messenger.wsbypass;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

public final class NimarkoVpnDetector {

    private static volatile boolean started;
    private static volatile boolean callbackRegistered;
    private static volatile boolean vpnActive;
    private static final long VPN_LOSS_CONFIRM_DELAY_MS = 1200L;
    private static final Object VPN_LOSS_LOCK = new Object();
    private static volatile Runnable pendingVpnLossCheck;
    private static long vpnLossGeneration;

    public static boolean isVpnActive() {
        if (callbackRegistered) return vpnActive;
        Boolean probed = probeVpnState();
        if (Boolean.TRUE.equals(probed)) {
            setVpnActive(true);
        } else if (Boolean.FALSE.equals(probed) && vpnActive) {
            
            scheduleVpnLossConfirmation();
        }
        
        return vpnActive;
    }

    private static ConnectivityManager cm() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return null;
            return (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean probeVpnActive() {
        Boolean state = probeVpnState();
        return state != null ? state : vpnActive;
    }

    public static boolean isVpnActiveFresh() {
        Boolean state = probeVpnState();
        if (state == null) return vpnActive;
        if (state) {
            setVpnActive(true);
            return true;
        }
        if (vpnActive) {
            scheduleVpnLossConfirmation();
            
            return true;
        }
        return false;
    }

    private static Boolean probeVpnState() {
        try {
            ConnectivityManager cm = cm();
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return Boolean.TRUE;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return Boolean.FALSE;
    }

    public static void start() {
        if (started) return;
        synchronized (NimarkoVpnDetector.class) {
            if (started) return;
            try {
                ConnectivityManager cm = cm();
                if (cm == null) return;
                
                Boolean initial = probeVpnState();
                if (initial != null) {
                    updateVpnState(initial, 0L, false, false);
                }
                
                NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build();
                cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { setVpnActive(true); }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            setVpnActive(true);
                        }
                    }
                    @Override public void onLost(Network network) { recheck(); }
                });
                callbackRegistered = true;
                started = true;
            } catch (Throwable ignored) {
                
            }
        }
    }

    public static void recheckNow() {
        if (!started) start();
        recheck();
    }

    private static void recheck() {
        try {
            Boolean now = probeVpnState();
            if (now != null) setVpnActive(now);
        } catch (Throwable ignored) {
        }
    }

    private static void setVpnActive(boolean now) {
        if (!now) {
            scheduleVpnLossConfirmation();
            return;
        }
        Runnable pending;
        synchronized (VPN_LOSS_LOCK) {
            vpnLossGeneration++;
            pending = pendingVpnLossCheck;
            pendingVpnLossCheck = null;
        }
        if (pending != null) AndroidUtilities.cancelRunOnUIThread(pending);
        publishVpnState(true);
    }

    private static void scheduleVpnLossConfirmation() {
        final Runnable check;
        final long generation;
        synchronized (VPN_LOSS_LOCK) {
            if (!vpnActive || pendingVpnLossCheck != null) return;
            generation = ++vpnLossGeneration;
            check = () -> {
                synchronized (VPN_LOSS_LOCK) {
                    if (generation != vpnLossGeneration) return;
                    pendingVpnLossCheck = null;
                }
                Boolean confirmed = probeVpnState();
                if (Boolean.TRUE.equals(confirmed)) {
                    setVpnActive(true);
                    return;
                }
                if (Boolean.FALSE.equals(confirmed)) {
                    
                    updateVpnState(false, generation, true, true);
                }
            };
            pendingVpnLossCheck = check;
        }
        AndroidUtilities.runOnUIThread(check, VPN_LOSS_CONFIRM_DELAY_MS);
    }

    private static void publishVpnState(boolean now) {
        updateVpnState(now, 0L, false, true);
    }

    private static boolean updateVpnState(boolean now, long expectedLossGeneration,
                                          boolean requireGeneration, boolean notifyController) {
        return app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig
                .mutateRelayState(() -> {
                    synchronized (VPN_LOSS_LOCK) {
                        if (requireGeneration && expectedLossGeneration != vpnLossGeneration) {
                            return false;
                        }
                        if (now == vpnActive) return false;
                        vpnActive = now;
                        if (notifyController) {
                            
                            AndroidUtilities.runOnUIThread(() ->
                                    NimarkoWsBypassController.getInstance()
                                            .onVpnStateChanged(vpnActive));
                        }
                        return true;
                    }
                });
    }

    private NimarkoVpnDetector() {}
}
