/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import java.util.Map;
import java.util.Set;
public class ProxyRotationController implements NotificationCenter.NotificationCenterDelegate {
    private final static ProxyRotationController INSTANCE = new ProxyRotationController();

    public final static int DEFAULT_TIMEOUT_INDEX = 1;
    public final static List<Integer> ROTATION_TIMEOUTS = Arrays.asList(
            5, 10, 15, 30, 60
    );

    public static boolean isCheckRecent(long checkedAt, long now) {
        return checkedAt > 0 && checkedAt <= now && now - checkedAt < 120_000;
    }

    private RotationAttempt rotationAttempt;
    private final Map<SharedConfig.ProxyInfo, Object> ownChecks = new HashMap<>();

    private final class RotationAttempt implements Runnable {
        final int account;
        final SharedConfig.ProxyInfo proxy;
        final ProxySettings settings;
        final int timeoutIndex = SharedConfig.proxyRotationTimeout;
        final Set<SharedConfig.ProxyInfo> previousChecks = new HashSet<>(ownChecks.keySet());
        Map<SharedConfig.ProxyInfo, ProxySettings> candidates;
        long checkedAt;

        RotationAttempt(int account, SharedConfig.ProxyInfo proxy) {
            this.account = account;
            this.proxy = proxy;
            settings = proxy.getSettings();
        }

        @Override
        public void run() {
            if (!isCurrentAttempt(this) || candidates != null) {
            return;
        }
            candidates = new HashMap<>();
            List<SharedConfig.ProxyInfo> proxies;
            synchronized (SharedConfig.getProxyListSync()) {
                proxies = new ArrayList<>(SharedConfig.proxyList);
            }
            final long now = checkedAt = SystemClock.elapsedRealtime();
            for (SharedConfig.ProxyInfo info : proxies) {
                ProxySettings settings = info.getSettings();
                if (!settings.isValid() || ownChecks.containsKey(info)) {
                continue;
            }
                boolean needsCheck = !info.checking && !isCheckRecent(info.availableCheckTime, now);
                if (info != proxy && (needsCheck || info.checking
                        || (info.available && !previousChecks.contains(info)))) {
                    candidates.put(info, settings);
                }
                if (needsCheck) {
                    checkProxy(account, info, settings);
                }
            }
            previousChecks.clear();
            switchToAvailable(this);
        }
    }

    private void checkProxy(int account, SharedConfig.ProxyInfo info, ProxySettings settings) {
        Object check = new Object();
        ownChecks.put(info, check);
        info.checking = true;
        ConnectionsManager.getInstance(account).checkProxy(settings, time -> AndroidUtilities.runOnUIThread(() -> {
            if (!ownChecks.remove(info, check)) {
                return;
            }
            info.checking = false;
            boolean settingsMatch = settings.equals(info.getSettings());
            info.availableCheckTime = settingsMatch ? SystemClock.elapsedRealtime() : 0;
            info.available = settingsMatch && time != -1;
            info.ping = info.available ? time : 0;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info);
            }));
        }


    public static void init() {
        INSTANCE.initInternal();
    }
    private static boolean canRotate(SharedConfig.ProxyInfo current) {
        return !app.nimarkogram.messenger.distribution.ClientRelays.enabled()
                && SharedConfig.isProxyEnabled() && SharedConfig.proxyRotationEnabled
                && SharedConfig.proxyList.size() > 1 && current != null
                && current.getSettings().getType() != ProxySettings.Type.WEB;
    }

    private void cancelAttempt() {
        if (rotationAttempt != null) {
            AndroidUtilities.cancelRunOnUIThread(rotationAttempt);
            rotationAttempt = null;
        }
    }

    private boolean isCurrentAttempt(RotationAttempt attempt) {
        if (attempt != rotationAttempt) {
            return false;
        }
        if (attempt.account == UserConfig.selectedAccount && attempt.proxy == SharedConfig.currentProxy
                && attempt.timeoutIndex == SharedConfig.proxyRotationTimeout && canRotate(attempt.proxy)
                && attempt.settings.equals(attempt.proxy.getSettings()) && isConnectingToProxy(attempt.account)) {
            return true;
        }
        cancelAttempt();
        return false;
    }

    private static boolean isConnectingToProxy(int account) {
        return ConnectionsManager.getInstance(account).getConnectionState()
                == ConnectionsManager.ConnectionStateConnectingToProxy;
    }

    private void switchToAvailable(RotationAttempt attempt) {
        synchronized (SharedConfig.getProxyListSync()) {
            SharedConfig.ProxyInfo best = null;
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                ProxySettings settings = attempt.candidates.get(info);
                if (settings != null && !info.checking && info.available && settings.equals(info.getSettings())
                        && (best == null || info.ping < best.ping)) {
                    best = info;
                }
            }
            if (!isCurrentAttempt(attempt) || best == null) {
            return;
        }

            cancelAttempt();
            ProxySettings settings = attempt.candidates.get(best);

            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", true);
            settings.toSharedPreferences(editor);
            if (settings.getType() != ProxySettings.Type.SOCKS5) {
                editor.putBoolean("proxy_enabled_calls", false);
            }
            editor.apply();

            SharedConfig.currentProxy = best;
            ConnectionsManager.setProxySettings(true, settings);
        }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyCheckDone) {
            RotationAttempt attempt = rotationAttempt;
            if (attempt == null || !isCurrentAttempt(attempt) || attempt.candidates == null
                    || args.length == 0 || !(args[0] instanceof SharedConfig.ProxyInfo)) {
                return;
            }

            SharedConfig.ProxyInfo info = (SharedConfig.ProxyInfo) args[0];
            if (!info.checking && attempt.candidates.containsKey(info)) {
                if (info.available) {
                    switchToAvailable(attempt);
                } else {
                    attempt.candidates.remove(info);
                }
            }
        } else if (id == NotificationCenter.proxySettingsChanged || id == NotificationCenter.activeAccountChanged) {
            cancelAttempt();
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            if (rotationAttempt != null && isCurrentAttempt(rotationAttempt)) {
                if (rotationAttempt.candidates == null || isCheckRecent(rotationAttempt.checkedAt, SystemClock.elapsedRealtime())) {
                return;
            }

                cancelAttempt();
                }
            SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
            if (canRotate(current) && isConnectingToProxy(account)) {
                rotationAttempt = new RotationAttempt(account, current);
                int index = rotationAttempt.timeoutIndex;
                if (index < 0 || index >= ROTATION_TIMEOUTS.size()) {
                    index = DEFAULT_TIMEOUT_INDEX;
                }
                AndroidUtilities.runOnUIThread(rotationAttempt, ROTATION_TIMEOUTS.get(index) * 1000L);
            }
        }
    }
}
