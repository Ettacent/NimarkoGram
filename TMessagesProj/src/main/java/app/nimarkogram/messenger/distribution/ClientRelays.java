/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.distribution;

import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import java.io.File;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import app.nimarkogram.messenger.wsbypass.*;
import app.nimarkogram.messenger.wsbypass.preferences.WlPreferencesActivity;
import app.nimarkogram.messenger.wsbypass.preferences.WsBypassPreferencesActivity;
import app.nimarkogram.messenger.wsbypass.voip.*;

public final class ClientRelays {
    private ClientRelays() {}

    public static void start() {
        try { VpnDetector.start(); } catch (Throwable ignored) {}
        try { WsBypassController.getInstance().ensureStartedSync(); } catch (Throwable ignored) {}
        try {
            if (enabled() && VoipBypassConfig.isVoipBypassEnabled())
                AndroidUtilities.runOnUIThread(() -> VoipRelayAuth.prefetchAsync(UserConfig.selectedAccount), 4000);
        } catch (Throwable ignored) {}
        try {
            if (enabled()) AndroidUtilities.runOnUIThread(() -> WsRelayAuth.prefetchAsync(UserConfig.selectedAccount), 4500);
        } catch (Throwable ignored) {}
    }
    public static void suspendForVpn() {
        if (WsBypassController.getInstance().blockedByVpn()) ProxyApplier.suspendForVpn(WsBypassCore.LOCAL_PROXY_HOST);
    }
    public static void onResume() { WsBypassController.getInstance().onAppResume(); }
    public static void onAccountSwitch(int account) { RelayRegion.invalidate(); prefetch(account, 500, 1000); }
    public static void onLogin(int account) { prefetch(account, 3500, 5000); }
    private static void prefetch(int account, int dataDelay, int callDelay) {
        AndroidUtilities.runOnUIThread(() -> WsRelayAuth.prefetchAsync(account), dataDelay);
        AndroidUtilities.runOnUIThread(() -> VoipRelayAuth.prefetchAsync(account), callDelay);
    }
    public static void prefetchCall(int account) {
        if (enabled() && VoipBypassConfig.isVoipBypassEnabled()) VoipRelayAuth.prefetchAsync(account);
    }
    public static void onLogout(int account, long uid) {
        WsRelayAuth.onAccountLoggedOut(account, uid);
        WlAccess.onAccountLoggedOut(account, uid);
        VoipRelayAuth.onAccountLoggedOut(account, uid);
    }
    public static boolean enabled() { return WsBypassConfig.enabled; }
    public static boolean running() { return WsBypassController.getInstance().isRunning(); }
    public static boolean transportConnected() {
        return WsBypassController.STATE_RUNNING.equals(WsBypassController.getInstance().getConnectionState());
    }
    public static boolean localProxyAvailable() {
        WsBypassCore core = WsBypassCore.getInstance(); return core.isRunning() && core.isAcceptThreadAlive();
    }
    public static void disable() { WsBypassController.getInstance().setEnabled(false); }
    public static boolean owns(SharedConfig.ProxyInfo proxy) {
        return proxy != null && proxy.getSettings() != null && WsBypassCore.LOCAL_PROXY_HOST.equals(proxy.getSettings().getAddress())
                && proxy.getSettings().getPort() == WsBypassConfig.localPort;
    }
    public static boolean isLegacyPlugin(File file) { return WsBypassCore.isWsBypassPluginFile(file); }
    public static BaseFragment settings(boolean whitelist) { return whitelist ? new WlPreferencesActivity() : new WsBypassPreferencesActivity(); }
    public static boolean isSettings(Object owner, boolean whitelist) {
        return whitelist ? owner instanceof WlPreferencesActivity : owner instanceof WsBypassPreferencesActivity;
    }

    public static final class State {
        private final VoipBypassConfig.RelayState original;
        public final long generation;
        public final boolean required;
        private State(VoipBypassConfig.RelayState original) {
            this.original = original; this.generation = original.generation; this.required = original.required;
        }
    }
    public static State capture() { return new State(VoipBypassConfig.captureRelayState()); }
    public static boolean publish(State state, Runnable publication) {
        return VoipBypassConfig.publishIfRelayStateCurrent(state == null ? null : state.original, publication);
    }
    public static final class Endpoint {
        public final String host; public final int port;
        private Endpoint(VoipBypassCore.RelayEndpoint e) { host = e.host; port = e.port; }
    }
    public static String target(String ip) { return VoipBypassConfig.relayProtocolTarget(ip); }
    public static Endpoint allocate(String target, int port, int account, int budgetMs) {
        VoipBypassCore.RelayEndpoint e = VoipBypassCore.getInstance().allocateRelay(target, port, account, budgetMs);
        return e == null ? null : new Endpoint(e);
    }
    public static void log(String text) { VoipBypassCore.dlog(text); }
}
