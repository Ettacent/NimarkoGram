package app.nimarkogram.messenger.wsbypass;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
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
    private static final String SNAP_CALLS = "tgws_proxy_snap_calls";
    private static final String SNAP_VPN_SUSPENDED = "tgws_proxy_snap_vpn_suspended";

    private static volatile boolean vpnSuspended;

    public static boolean isVpnSuspended() { return vpnSuspended; }

    private static final class ProxySnapshot {
        final boolean enabled;
        final String host;
        final int port;
        final String user;
        final String password;
        final String secret;
        final boolean callsEnabled;
        ProxySnapshot(boolean enabled, String host, int port, String user, String password, String secret,
                      boolean callsEnabled) {
            this.enabled = enabled;
            this.host = host == null ? "" : host;
            this.port = port;
            this.user = user == null ? "" : user;
            this.password = password == null ? "" : password;
            this.secret = secret == null ? "" : secret;
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
                    String configuredHost = settings.getString("proxy_ip", "");
                    int configuredPort = settings.getInt("proxy_port", 0);
                    if (!configuredHost.isEmpty() && configuredPort > 0) {
                        curr = new SharedConfig.ProxyInfo(configuredHost, configuredPort,
                                settings.getString("proxy_user", ""),
                                settings.getString("proxy_pass", ""),
                                settings.getString("proxy_secret", ""));
                        SharedConfig.currentProxy = curr;
                    }
                }
                String host = curr == null ? "" : (curr.address == null ? "" : curr.address);
                if (curr != null && localHost != null && localHost.equals(host) && curr.port == NimarkoWsBypassConfig.localPort) {
                    
                    snapshot = new ProxySnapshot(false, "", 0, "", "", "", false);
                    persistSnapshot(snapshot);
                    return;
                }
                boolean enabled = settings.getBoolean("proxy_enabled", false);
                boolean callsEnabled = settings.getBoolean("proxy_enabled_calls", false);
                if (curr != null) {
                    snapshot = new ProxySnapshot(
                            enabled,
                            curr.address,
                            curr.port,
                            curr.username,
                            curr.password,
                            curr.secret,
                            callsEnabled);
                } else {
                    
                    snapshot = new ProxySnapshot(enabled,
                            settings.getString("proxy_ip", ""),
                            settings.getInt("proxy_port", 0),
                            settings.getString("proxy_user", ""),
                            settings.getString("proxy_pass", ""),
                            settings.getString("proxy_secret", ""), callsEnabled);
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
            if (!p.getBoolean(SNAP_PRESENT, false)) return false;
            snapshot = new ProxySnapshot(
                    p.getBoolean(SNAP_ENABLED, false),
                    p.getString(SNAP_HOST, ""),
                    p.getInt(SNAP_PORT, 0),
                    p.getString(SNAP_USER, ""),
                    p.getString(SNAP_PASS, ""),
                    p.getString(SNAP_SECRET, ""),
                    p.getBoolean(SNAP_CALLS, false));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void persistSnapshot(ProxySnapshot snap) {
        try {
            SharedPreferences.Editor ed = MessagesController.getGlobalMainSettings().edit();
            if (snap == null) {
                ed.remove(SNAP_PRESENT)
                        .remove(SNAP_ENABLED)
                        .remove(SNAP_HOST)
                        .remove(SNAP_PORT)
                        .remove(SNAP_USER)
                        .remove(SNAP_PASS)
                        .remove(SNAP_SECRET)
                        .remove(SNAP_CALLS);
            } else {
                ed.putBoolean(SNAP_PRESENT, true)
                        .putBoolean(SNAP_ENABLED, snap.enabled)
                        .putString(SNAP_HOST, snap.host)
                        .putInt(SNAP_PORT, snap.port)
                        .putString(SNAP_USER, snap.user)
                        .putString(SNAP_PASS, snap.password)
                        .putString(SNAP_SECRET, snap.secret)
                        .putBoolean(SNAP_CALLS, snap.callsEnabled);
            }
            ed.apply();
        } catch (Throwable ignored) {
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
            ed.putString("proxy_ip", snap.host);
            ed.putInt("proxy_port", snap.port);
            ed.putString("proxy_user", snap.user);
            ed.putString("proxy_pass", snap.password);
            ed.putString("proxy_secret", snap.secret);
            ed.putBoolean("proxy_enabled", snap.enabled);
            ed.putBoolean("proxy_enabled_calls", snap.callsEnabled);
            ed.putBoolean("proxy_calls_enabled", snap.callsEnabled);
            ed.putBoolean("calls_use_proxy", snap.callsEnabled);
            try { if (!ed.commit()) ed.apply(); } catch (Throwable ignored) { ed.apply(); }

            boolean accountsApplied;
            synchronized (PROXY_LIST_LOCK) {
                if (snap.enabled && !snap.host.isEmpty() && snap.port > 0) {
                    try {
                        SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(
                                snap.host, snap.port, snap.user, snap.password, snap.secret);
                        SharedConfig.ProxyInfo added = SharedConfig.addProxy(info);
                        SharedConfig.currentProxy = added != null ? added : info;
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                    accountsApplied = applyToAllAccounts(
                            true, snap.host, snap.port, snap.user, snap.password, snap.secret);
                } else {
                    SharedConfig.currentProxy = null;
                    accountsApplied = applyToAllAccounts(false, "", 0, "", "", "");
                }
                try { SharedConfig.saveProxyList(); } catch (Throwable ignored) {}
            }
            try { SharedConfig.saveConfig(); } catch (Throwable ignored) {}
            boolean restored = accountsApplied
                    && isApplyVerified(snap.enabled, snap.host, snap.port, snap.secret);
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
                        && host.equals(curr.address == null ? "" : curr.address)
                        && curr.port == NimarkoWsBypassConfig.localPort;
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
                applyToAllAccounts(false, "", 0, "", "", "");
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

    private static boolean applyToAllAccounts(boolean enable, String host, int port,
                                              String user, String pass, String secret) {
        
        boolean applied = true;
        try {
            ConnectionsManager.setProxySettings(enable, host, port, user, pass, secret);
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
            
            final String user = "";
            final String pass = "";

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
                    if (host.equals(p.address) && p.port == ownPort) {
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
                    if (curr != null && host.equals(curr.address) && curr.port == ownPort) {
                        SharedConfig.currentProxy = null;
                    }
                } catch (Throwable ignored) {}

                SharedConfig.ProxyInfo proxyObj = null;
                if (enable) {
                    if (localProxy != null) {
                        try {
                            localProxy.port = port;
                            localProxy.username = user;
                            localProxy.password = pass;
                            localProxy.secret = sec;
                        } catch (Throwable ignored) {}
                        proxyObj = localProxy;
                    } else {
                        try {
                            SharedConfig.ProxyInfo info =
                                    new SharedConfig.ProxyInfo(host, port, user, pass, sec);
                            proxyObj = SharedConfig.addProxy(info);
                            if (proxyObj == null) proxyObj = info;
                        } catch (Throwable t) {
                            FileLog.e(t);
                        }
                    }

                    if (proxyObj != null) {
                        SharedConfig.currentProxy = proxyObj;
                    }

                    ed.putString("proxy_ip", host);
                    ed.putInt("proxy_port", port);
                    ed.putString("proxy_user", user);
                    ed.putString("proxy_pass", pass);
                    ed.putString("proxy_secret", sec);
                    ed.putBoolean("proxy_enabled", true);

                    boolean callsEnabled = sec.length() == 0;
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
                        ? applyToAllAccounts(true, host, port, user, pass, sec)
                        : applyToAllAccounts(false, "", 0, "", "", "");
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
            return preferencesApplied && accountsApplied && isApplyVerified(enable, host, port, sec);
        } catch (Throwable e) {
            FileLog.e("ProxyApplier.apply error", e);
            return false;
        }
    }

    private static boolean isApplyVerified(boolean enable, String host, int port, String secret) {
        try {
            SharedPreferences settings = MessagesController.getGlobalMainSettings();
            if (settings.getBoolean("proxy_enabled", false) != enable) return false;
            if (!enable) return true;

            SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
            if (current == null
                    || !host.equals(current.address == null ? "" : current.address)
                    || current.port != port
                    || !secret.equals(current.secret == null ? "" : current.secret)) {
                return false;
            }
            return host.equals(settings.getString("proxy_ip", ""))
                    && port == settings.getInt("proxy_port", 0)
                    && secret.equals(settings.getString("proxy_secret", ""))
                    && isLocalEntryPresent(host, port);
        } catch (Throwable ignored) {
            return false;
        }
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
                    if (host.equals(p.address) && p.port == port) return true;
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
        return isApplyVerified(true, host, port, sec);
    }

    public static synchronized void forceClearCurrent(String localHost) {
        try {
            final String host = localHost == null ? "" : localHost;
            synchronized (PROXY_LIST_LOCK) {
                SharedConfig.ProxyInfo curr = SharedConfig.currentProxy;
                if (curr == null) return;
                String addr = curr.address == null ? "" : curr.address;
                if (host.equals(addr) && curr.port == NimarkoWsBypassConfig.localPort) {
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
                    if (host.equals(p.address) && p.port == NimarkoWsBypassConfig.localPort) {
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
