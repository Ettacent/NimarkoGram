package app.nimarkogram.messenger.wsbypass;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.proxy.ProxySettings;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyApplier {

    private ProxyApplier() {}

    private static final Object PROXY_LIST_LOCK = SharedConfig.getProxyListSync();

    private static final AtomicBoolean NOTIFY_IN_FLIGHT = new AtomicBoolean(false);

    private static final long NOTIFY_DELAY_MS = 0L;

    private static volatile ProxySnapshot snapshot;

    private static final String SNAP_PRESENT = "tgws_proxy_snap_present";
    private static final String SNAP_ENABLED = "tgws_proxy_snap_enabled";
    private static final String SNAP_HOST = "tgws_proxy_snap_host";
    private static final String SNAP_PORT = "tgws_proxy_snap_port";
    private static final String SNAP_USER = "tgws_proxy_snap_user";
    private static final String SNAP_PASS = "tgws_proxy_snap_pass";
    private static final String SNAP_SECRET = "tgws_proxy_snap_secret";
    private static final String SNAP_TYPE = "tgws_proxy_snap_type";
    private static final String SNAP_CALLS = "tgws_proxy_snap_calls";
    private static final String SNAP_VPN_SUSPENDED = "tgws_proxy_snap_vpn_suspended";

    private static volatile boolean vpnSuspended;

    public static boolean isVpnSuspended() { return vpnSuspended; }

    private static final class ProxySnapshot {
        final boolean enabled;
        final ProxySettings settings;
        final boolean callsEnabled;
        ProxySnapshot(boolean enabled, ProxySettings settings, boolean callsEnabled) {
            this.enabled = enabled;
            this.settings = settings;
            this.callsEnabled = callsEnabled;
        }
    }

    private static synchronized void captureSnapshotIfMissing(String localHost) {
        if (snapshot != null) return;
        
        if (loadPersistedSnapshot()) return;
        try {
            synchronized (PROXY_LIST_LOCK) {
                
                try {
                    SharedConfig.loadProxyList();
                } catch (Throwable loadFailure) {
                    
                    FileLog.e("ProxyApplier.loadProxyList before snapshot", loadFailure);
                }
                SharedPreferences settings = MessagesController.getGlobalMainSettings();
                SharedConfig.ProxyInfo curr = SharedConfig.currentProxy;
                if (curr == null && settings.getBoolean("proxy_enabled", false)) {
                    ProxySettings configured = ProxySettings.fromSharedPreferences(settings);
                    if (configured.isValid()) {
                        curr = new SharedConfig.ProxyInfo(configured);
                        SharedConfig.currentProxy = curr;
                    }
                }
                String host = curr == null ? "" : curr.getSettings().getAddress();
                if (curr != null && localHost != null && localHost.equals(host) && curr.getSettings().getPort() == NimarkoWsBypassConfig.localPort) {
                    snapshot = new ProxySnapshot(false, ProxySettings.EMPTY, false);
                    persistSnapshot(snapshot);
                    return;
                }
                boolean enabled = settings.getBoolean("proxy_enabled", false);
                boolean callsEnabled = settings.getBoolean("proxy_enabled_calls", false);
                if (curr != null) {
                    snapshot = new ProxySnapshot(
                            enabled,
                            curr.getSettings(),
                            callsEnabled);
                } else {
                    
                    snapshot = new ProxySnapshot(enabled,
                            ProxySettings.fromSharedPreferences(settings), callsEnabled);
                }
                persistSnapshot(snapshot);
            }
        } catch (Throwable t) {
            FileLog.e("ProxyApplier.captureSnapshot", t);
        }
    }

    private static boolean loadPersistedSnapshot() {
        try {
            SharedPreferences p = MessagesController.getGlobalMainSettings();
            ProxySnapshot saved = readSnapshot(p);
            if (saved == null) return false;
            snapshot = saved;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private static ProxySnapshot readSnapshot(SharedPreferences p) {
        if (!p.getBoolean(SNAP_PRESENT, false)) return null;
        String secret = p.getString(SNAP_SECRET, "");
        ProxySettings.Type type = p.contains(SNAP_TYPE)
                ? ProxySettings.intToType(p.getInt(SNAP_TYPE, 0))
                : (secret == null || secret.isEmpty() ? ProxySettings.Type.SOCKS5 : ProxySettings.Type.MTPROTO);
        return new ProxySnapshot(
                p.getBoolean(SNAP_ENABLED, false),
                ProxySettings.builder().setType(type)
                        .setAddress(p.getString(SNAP_HOST, ""))
                        .setPort(p.getInt(SNAP_PORT, 0))
                        .setUser(p.getString(SNAP_USER, ""))
                        .setPassword(p.getString(SNAP_PASS, ""))
                        .setSecret(secret).build(),
                p.getBoolean(SNAP_CALLS, false));
    }

    private static void persistSnapshot(ProxySnapshot snap) {
        try {
            SharedPreferences.Editor ed = MessagesController.getGlobalMainSettings().edit();
            writeSnapshot(ed, snap);
            ed.apply();
        } catch (Throwable ignored) {
        }
    }
    private static void writeSnapshot(SharedPreferences.Editor ed, ProxySnapshot snap) {
        if (snap == null) {
            ed.remove(SNAP_PRESENT)
                    .remove(SNAP_ENABLED)
                    .remove(SNAP_HOST)
                    .remove(SNAP_PORT)
                    .remove(SNAP_USER)
                    .remove(SNAP_PASS)
                    .remove(SNAP_SECRET)
                    .remove(SNAP_TYPE)
                    .remove(SNAP_CALLS);
        } else {
            ed.putBoolean(SNAP_PRESENT, true)
                    .putBoolean(SNAP_ENABLED, snap.enabled)
                    .putString(SNAP_HOST, snap.settings.getAddress())
                    .putInt(SNAP_PORT, snap.settings.getPort())
                    .putString(SNAP_USER, snap.settings.getUser())
                    .putString(SNAP_PASS, snap.settings.getPassword())
                    .putString(SNAP_SECRET, snap.settings.getSecret())
                    .putInt(SNAP_TYPE, ProxySettings.typeToInt(snap.settings.getType()))
                    .putBoolean(SNAP_CALLS, snap.callsEnabled);
        }
    }

    private static boolean isSystemVpnActive() {
        try {
            return NimarkoVpnDetector.isVpnActiveFresh();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized boolean restoreSnapshot() {
        ProxySnapshot snap = snapshot;
        if (snap == null) {
            
            loadPersistedSnapshot();
            snap = snapshot;
        }
        if (snap == null) return false;

        if (NimarkoWsBypassConfig.suspendOnVpn && isSystemVpnActive()) {
            setVpnSuspended(true);
            return false;
        }

        try {
            SharedPreferences.Editor ed = MessagesController.getGlobalMainSettings().edit();
            snap.settings.toSharedPreferences(ed);
            ed.putBoolean("proxy_enabled", snap.enabled);
            ed.putBoolean("proxy_enabled_calls", snap.callsEnabled);
            ed.putBoolean("proxy_calls_enabled", snap.callsEnabled);
            ed.putBoolean("calls_use_proxy", snap.callsEnabled);
            try { if (!ed.commit()) ed.apply(); } catch (Throwable ignored) { ed.apply(); }

            boolean accountsApplied;
            synchronized (PROXY_LIST_LOCK) {
                if (snap.settings.isValid()) {
                    try {
                        SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(
                                snap.settings);
                        SharedConfig.ProxyInfo added = SharedConfig.addProxy(info);
                        SharedConfig.currentProxy = added != null ? added : info;
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                    accountsApplied = applyToAllAccounts(
                            snap.enabled, snap.settings);
                } else {
                    SharedConfig.currentProxy = null;
                    accountsApplied = applyToAllAccounts(false, ProxySettings.EMPTY);
                }
                try { SharedConfig.saveProxyList(); } catch (Throwable ignored) {}
            }
            try { SharedConfig.saveConfig(); } catch (Throwable ignored) {}
            boolean restored = accountsApplied
                    && isApplyVerified(snap.enabled, snap.settings);
            if (restored) {
                snapshot = null;
                persistSnapshot(null);
            }
            return restored;
        } catch (Throwable t) {
            FileLog.e("ProxyApplier.restoreSnapshot", t);
            return false;
        }
    }

    public static synchronized void suspendForVpn(String localHost) {
        try {
            final String host = localHost == null ? "" : localHost;
            
            synchronized (PROXY_LIST_LOCK) {
                SharedConfig.ProxyInfo curr = SharedConfig.currentProxy;
                boolean currentIsOurs = curr != null
                        && host.equals(curr.getSettings().getAddress())
                        && curr.getSettings().getPort() == NimarkoWsBypassConfig.localPort;
                SharedPreferences settings = MessagesController.getGlobalMainSettings();
                boolean persistedIsOurs = settings.getBoolean("proxy_enabled", false)
                        && host.equals(settings.getString("proxy_ip", ""))
                        && settings.getInt("proxy_port", 0) == NimarkoWsBypassConfig.localPort;
                
                boolean ours = currentIsOurs || curr == null && persistedIsOurs;
                if (!ours) {
                    return; 
                }
                captureSnapshotIfMissing(host);
                setVpnSuspended(true);
                SharedConfig.currentProxy = null;
                
                settings.edit()
                        .putBoolean("proxy_enabled", false)
                        .putBoolean("proxy_enabled_calls", false)
                        .putBoolean("proxy_calls_enabled", false)
                        .putBoolean("calls_use_proxy", false)
                        .apply();
                applyToAllAccounts(false, ProxySettings.EMPTY);
            }
            AndroidUtilities.runOnUIThread(NOTIFY_RUNNABLE, NOTIFY_DELAY_MS);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void restoreForVpn() {
        if (!vpnSuspended) {
            try {
                vpnSuspended = MessagesController.getGlobalMainSettings()
                        .getBoolean(SNAP_VPN_SUSPENDED, false);
            } catch (Throwable ignored) {}
        }
        if (!vpnSuspended) return;
        try {
            if (restoreSnapshot()) setVpnSuspended(false);
            AndroidUtilities.runOnUIThread(NOTIFY_RUNNABLE, NOTIFY_DELAY_MS);
        } catch (Throwable ignored) {
        }
    }

    private static void setVpnSuspended(boolean value) {
        vpnSuspended = value;
        try {
            MessagesController.getGlobalMainSettings().edit()
                    .putBoolean(SNAP_VPN_SUSPENDED, value).apply();
        } catch (Throwable ignored) {}
    }

    private static boolean applyToAllAccounts(boolean enable, ProxySettings settings) {
        
        boolean applied = true;
        try {
            ConnectionsManager.setProxySettings(enable, settings);
        } catch (Throwable t) {
            FileLog.e(t);
            applied = false;
        }
        for (int ac = 0; ac < UserConfig.MAX_ACCOUNT_COUNT; ac++) {
            try {
                UserConfig uc = UserConfig.getInstance(ac);
                if (uc == null || !uc.isClientActivated()) continue;
                ConnectionsManager cm = ConnectionsManager.getInstance(ac);
                if (cm == null) continue;
                try {
                    cm.checkConnection();
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
        return applied;
    }

    public static synchronized boolean apply(int port, boolean enable, String secret, String localHost) {
        try {
            final String host = localHost == null ? "" : localHost;
            
            final int ownPort = port > 0 ? port : NimarkoWsBypassConfig.localPort;
            final String sec = secret == null ? "" : secret.trim();
            
            final ProxySettings localSettings = localSettings(host, port, sec);

            if (enable && NimarkoWsBypassConfig.suspendOnVpn && isSystemVpnActive()) {
                return false;
            }

            if (enable) {
                captureSnapshotIfMissing(host);
                
                setVpnSuspended(false);
            } else {
                
                if (restoreSnapshot()) {
                    AndroidUtilities.runOnUIThread(NOTIFY_RUNNABLE, NOTIFY_DELAY_MS);
                    return true;
                }
                if (snapshot != null && !(NimarkoWsBypassConfig.suspendOnVpn && isSystemVpnActive())) return false;
            }

            try {
                SharedConfig.loadProxyList();
            } catch (Throwable ignored) {}

            final long proxyRevision;
            boolean preferencesApplied = true;
            boolean accountsApplied;
            synchronized (PROXY_LIST_LOCK) {
                SharedConfig.ProxyInfo localProxy = null;
                ArrayList<SharedConfig.ProxyInfo> snapshot =
                        SharedConfig.proxyList != null
                                ? new ArrayList<>(SharedConfig.proxyList)
                                : new ArrayList<SharedConfig.ProxyInfo>();
                ArrayList<SharedConfig.ProxyInfo> duplicates = new ArrayList<>();
                for (int i = 0; i < snapshot.size(); i++) {
                    SharedConfig.ProxyInfo p = snapshot.get(i);
                    if (p == null) continue;
                    if (host.equals(p.getSettings().getAddress()) && p.getSettings().getPort() == ownPort) {
                        if (localProxy == null) {
                            localProxy = p;
                        } else {
                            duplicates.add(p);
                        }
                    }
                }
                for (int i = 0; i < duplicates.size(); i++) {
                    try {
                        SharedConfig.deleteProxy(duplicates.get(i));
                    } catch (Throwable ignored) {}
                }

                SharedPreferences.Editor ed =
                        MessagesController.getGlobalMainSettings().edit();

                if (!enable && localProxy != null) {
                    try {
                        SharedConfig.deleteProxy(localProxy);
                    } catch (Throwable ignored) {}
                    localProxy = null;
                }

                try {
                    SharedConfig.ProxyInfo curr = SharedConfig.currentProxy;
                    if (curr != null && host.equals(curr.getSettings().getAddress()) && curr.getSettings().getPort() == ownPort) {
                        SharedConfig.currentProxy = null;
                    }
                } catch (Throwable ignored) {}

                SharedConfig.ProxyInfo proxyObj = null;
                if (enable) {
                    if (localProxy != null) {
                        try {
                            localProxy.setSettings(localSettings);
                        } catch (Throwable ignored) {}
                        proxyObj = localProxy;
                    } else {
                        try {
                            SharedConfig.ProxyInfo info =
                                    new SharedConfig.ProxyInfo(localSettings);
                            proxyObj = SharedConfig.addProxy(info);
                            if (proxyObj == null) proxyObj = info;
                        } catch (Throwable t) {
                            FileLog.e(t);
                        }
                    }

                    if (proxyObj != null) {
                        SharedConfig.currentProxy = proxyObj;
                    }

                    localSettings.toSharedPreferences(ed);
                    ed.putBoolean("proxy_enabled", true);

                    boolean callsEnabled = localSettings.getType() == ProxySettings.Type.SOCKS5;
                    ed.putBoolean("proxy_enabled_calls", callsEnabled);
                    ed.putBoolean("proxy_calls_enabled", callsEnabled);
                    ed.putBoolean("calls_use_proxy", callsEnabled);

                    if (callsEnabled) {
                        for (int ac = 0; ac < UserConfig.MAX_ACCOUNT_COUNT; ac++) {
                            try {
                                UserConfig uc = UserConfig.getInstance(ac);
                                if (uc == null || !uc.isClientActivated()) continue;
                                SharedPreferences prefs = MessagesController.getMainSettings(ac);
                                if (prefs == null) continue;
                                String key = "tgws_proxy_p2p_backup_" + ac;
                                int currentP2P = prefs.getInt("calls_p2p", -1);
                                if (currentP2P != 2) {
                                    prefs.edit()
                                            .putInt(key, currentP2P)
                                            .putInt("calls_p2p", 2)
                                            .apply();
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } else {
                    ed.putBoolean("proxy_enabled", false);

                    for (int ac = 0; ac < UserConfig.MAX_ACCOUNT_COUNT; ac++) {
                        try {
                            UserConfig uc = UserConfig.getInstance(ac);
                            if (uc == null || !uc.isClientActivated()) continue;
                            SharedPreferences prefs = MessagesController.getMainSettings(ac);
                            if (prefs == null) continue;
                            String key = "tgws_proxy_p2p_backup_" + ac;
                            int savedP2P = prefs.getInt(key, -1);
                            if (savedP2P >= 0) {
                                prefs.edit()
                                        .putInt("calls_p2p", savedP2P)
                                        .remove(key)
                                        .apply();
                            }
                        } catch (Throwable ignored) {}
                    }
                }

                try {
                    ed.apply();
                } catch (Throwable ignored) {
                    preferencesApplied = false;
                }

                proxyRevision = SharedConfig.markProxyListChanged();
                accountsApplied = enable
                        ? applyToAllAccounts(true, localSettings)
                        : applyToAllAccounts(false, ProxySettings.EMPTY);
            }

            Utilities.globalQueue.postRunnable(() -> {
                try {
                    SharedConfig.saveProxyList(proxyRevision);
                } catch (Throwable ignored) {}
                try {
                    SharedConfig.saveConfig();
                } catch (Throwable ignored) {}
            });

            AndroidUtilities.runOnUIThread(NOTIFY_RUNNABLE, NOTIFY_DELAY_MS);
            return preferencesApplied && accountsApplied && (enable
                    ? isApplyVerified(true, localSettings)
                    : !MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false));
        } catch (Throwable e) {
            FileLog.e("ProxyApplier.apply error", e);
            return false;
        }
    }

    private static ProxySettings localSettings(String host, int port, String secret) {
        return ProxySettings.builder().setType(ProxySettings.Type.MTPROTO)
                .setAddress(host).setPort(port).setSecret(secret).build();
    }
    private static boolean isApplyVerified(boolean enable, ProxySettings expected) {
        try {
            synchronized (PROXY_LIST_LOCK) {
                return isApplyVerified(enable, expected, MessagesController.getGlobalMainSettings(),
                        SharedConfig.currentProxy, SharedConfig.proxyList);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }
    private static boolean isApplyVerified(boolean enable, ProxySettings expected,
                                           SharedPreferences settings, SharedConfig.ProxyInfo current,
                                           ArrayList<SharedConfig.ProxyInfo> proxies) {
        if (settings.getBoolean("proxy_enabled", false) != enable) return false;
        if (!expected.equals(ProxySettings.fromSharedPreferences(settings))) return false;
        if (!enable && !expected.isValid()) return true;
        if (current == null || !expected.equals(current.getSettings())) {
            return false;
        }
        if (proxies != null) {
            for (SharedConfig.ProxyInfo info : proxies) {
                if (info != null && expected.equals(info.getSettings())) return true;
            }
        }
        return false;
    }

    public static boolean isLocalEntryPresent(String localHost, int port) {
        try {
            final String host = localHost == null ? "" : localHost;
            synchronized (PROXY_LIST_LOCK) {
                if (SharedConfig.proxyList == null || SharedConfig.proxyList.isEmpty()) {
                    return false;
                }
                ArrayList<SharedConfig.ProxyInfo> snap = new ArrayList<>(SharedConfig.proxyList);
                for (int i = 0; i < snap.size(); i++) {
                    SharedConfig.ProxyInfo p = snap.get(i);
                    if (p == null) continue;
                    if (host.equals(p.getSettings().getAddress()) && p.getSettings().getPort() == port) return true;
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return false;
    }

    public static boolean isLocalProxyActive(String localHost, int port, String secret) {
        String host = localHost == null ? "" : localHost;
        String sec = secret == null ? "" : secret;
        return isApplyVerified(true, localSettings(host, port, sec));
    }

    public static synchronized void forceClearCurrent(String localHost) {
        try {
            final String host = localHost == null ? "" : localHost;
            synchronized (PROXY_LIST_LOCK) {
                SharedConfig.ProxyInfo curr = SharedConfig.currentProxy;
                if (curr == null) return;
                String addr = curr.getSettings().getAddress();
                if (host.equals(addr) && curr.getSettings().getPort() == NimarkoWsBypassConfig.localPort) {
                    SharedConfig.currentProxy = null;
                    SharedConfig.markProxyListChanged();
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static synchronized void removeLocalFromList(String localHost) {
        try {
            final String host = localHost == null ? "" : localHost;
            try {
                SharedConfig.loadProxyList();
            } catch (Throwable ignored) {}

            ArrayList<SharedConfig.ProxyInfo> toRemove = new ArrayList<>();
            synchronized (PROXY_LIST_LOCK) {
                if (SharedConfig.proxyList == null || SharedConfig.proxyList.isEmpty()) {
                    return;
                }
                ArrayList<SharedConfig.ProxyInfo> snap =
                        new ArrayList<>(SharedConfig.proxyList);
                for (int i = 0; i < snap.size(); i++) {
                    SharedConfig.ProxyInfo p = snap.get(i);
                    if (p == null) continue;
                    if (host.equals(p.getSettings().getAddress()) && p.getSettings().getPort() == NimarkoWsBypassConfig.localPort) {
                        toRemove.add(p);
                    }
                }
                if (toRemove.isEmpty()) return;
                for (int i = 0; i < toRemove.size(); i++) {
                    try {
                        SharedConfig.deleteProxy(toRemove.get(i));
                    } catch (Throwable ignored) {}
                }
            }
            try {
                SharedConfig.saveProxyList();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static final Runnable NOTIFY_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            if (!NOTIFY_IN_FLIGHT.compareAndSet(false, true)) {
                return;
            }
            try {
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.proxySettingsChanged);
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                NOTIFY_IN_FLIGHT.set(false);
            }
        }
    };
}
