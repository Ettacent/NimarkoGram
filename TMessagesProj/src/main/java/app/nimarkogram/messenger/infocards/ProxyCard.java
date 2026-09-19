package app.nimarkogram.messenger.infocards;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.SystemClock;
import android.view.Gravity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.proxy.ProxySettings;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProxyListActivity;

import app.nimarkogram.messenger.infocards.preferences.InfoCardsPreferencesActivity;
import app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig;
import app.nimarkogram.messenger.wsbypass.WsBypassCore;

public class ProxyCard extends BaseInfoCard implements NotificationCenter.NotificationCenterDelegate {

    private static final int STATE_CONNECTED = ConnectionsManager.ConnectionStateConnected; 
    private static final int STATE_UPDATING = ConnectionsManager.ConnectionStateUpdating;   

    private final int iconRes;
    private boolean connected;
    private boolean lifecycleAttached;
    private int observedAccount = -1;

    private static final long COALESCE_MS = 250;
    private final Runnable coalescedUpdate = () -> {
        if (lifecycleAttached && isAttachedToWindow()) onUpdateData(true);
    };

    public ProxyCard(Context context, Theme.ResourcesProvider resourcesProvider, int iconRes) {
        super(context, resourcesProvider);
        this.iconRes = iconRes;
        setIcon(iconRes);
        renderState(false);
    }

    @Override
    public int getCardId() {
        return InfoCardType.PROXY.id;
    }

    @Override
    public long getRefreshInterval() {
        
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        return connected && SharedConfig.isProxyEnabled() && proxy != null && displayPing(proxy, UserConfig.selectedAccount) <= 0
                ? 500 : 5000;
    }

    @Override
    protected void onAttachedToWindow() {
        lifecycleAttached = true;
        bindAccountObserver();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        lifecycleAttached = false;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyCheckDone);
        if (observedAccount >= 0) {
            NotificationCenter.getInstance(observedAccount)
                    .removeObserver(this, NotificationCenter.didUpdateConnectionState);
            observedAccount = -1;
        }
        AndroidUtilities.cancelRunOnUIThread(coalescedUpdate);
        super.onDetachedFromWindow();
    }

    private void bindAccountObserver() {
        int selected = UserConfig.selectedAccount;
        if (observedAccount == selected) return;
        if (observedAccount >= 0) {
            NotificationCenter.getInstance(observedAccount)
                    .removeObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        observedAccount = selected;
        if (lifecycleAttached) {
            NotificationCenter.getInstance(observedAccount)
                    .addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        if (id == NotificationCenter.didUpdateConnectionState && account != observedAccount) return;
        if (id == NotificationCenter.proxyCheckDone) {
            if (args.length > 0 && args[0] == SharedConfig.currentProxy) {
                AndroidUtilities.cancelRunOnUIThread(coalescedUpdate);
                onUpdateData(false);
            }
            return;
        }
        if (id == NotificationCenter.proxySettingsChanged
                || id == NotificationCenter.didUpdateConnectionState) {
            
            AndroidUtilities.cancelRunOnUIThread(coalescedUpdate);
            AndroidUtilities.runOnUIThread(coalescedUpdate, COALESCE_MS);
        }
    }

    @Override
    public void onUpdateData(boolean force) {
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        bindAccountObserver();
        renderState(true);
        markDataUpdated();
    }
    private void renderState(boolean animated) {
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        boolean enabled = SharedConfig.isProxyEnabled() && proxy != null;
        final int account = lifecycleAttached ? observedAccount : UserConfig.selectedAccount;
        int connectionState = ConnectionsManager.getInstance(account).getConnectionState();
        boolean isConnected = connectionState == STATE_CONNECTED || connectionState == STATE_UPDATING;

        if (enabled && isConnected) {
            
            setIcon(R.drawable.pill_proxy);
            
            if (lifecycleAttached) kickProxyCheck(proxy, !connected);
            long measuredPing = displayPing(proxy, account);
            if (measuredPing > 0) {
                setText(measuredPing + " ms", animated);
            } else {
                setText(LocaleController.getString(R.string.MenuProxyConnected), animated);
            }
            stopLoading();
            connected = true;
        } else if (enabled) {
            
            setIcon(R.drawable.pill_proxy_off);
            setText(LocaleController.getString(R.string.MenuProxyConnecting), animated);
            startLoading();
            connected = false;
        } else {
            
            setIcon(R.drawable.pill_proxy_off);
            setText(LocaleController.getString(R.string.Proxy), animated);
            stopLoading();
            connected = false;
        }
        
        applyColorMode();
    }

    public static CharSequence liveValueText() {
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        boolean enabled = SharedConfig.isProxyEnabled() && proxy != null;
        int connectionState = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        boolean isConnected = connectionState == STATE_CONNECTED || connectionState == STATE_UPDATING;
        if (enabled && isConnected) {
            long measuredPing = displayPing(proxy, UserConfig.selectedAccount);
            if (measuredPing > 0) {
                return measuredPing + " ms";
            }
            return LocaleController.getString(R.string.MenuProxyConnected);
        } else if (enabled) {
            return LocaleController.getString(R.string.MenuProxyConnecting);
        }
        
        return null;
    }

    private static boolean isOwnBypass(SharedConfig.ProxyInfo proxy) {
        return proxy != null && proxy.settings != null
                && WsBypassCore.LOCAL_PROXY_HOST.equals(proxy.settings.getAddress())
                && proxy.settings.getPort() == NimarkoWsBypassConfig.localPort;
    }
    private static long displayPing(SharedConfig.ProxyInfo proxy, int account) {
        return isOwnBypass(proxy) ? ConnectionsManager.native_getCurrentMainPingTime(account) : proxy.ping;
    }
    private void kickProxyCheck(SharedConfig.ProxyInfo proxy, boolean justConnected) {
        if (proxy == null) return;
        final ProxySettings checkedSettings = proxy.settings;
        if (checkedSettings == null || !checkedSettings.isValid()) return;
        final int acc = observedAccount;
        if (isOwnBypass(proxy)) {
            return;
        }
        long retryInterval = proxy.ping > 0 ? 120000L : 15000L;
        if (proxy.checking || (!justConnected && proxy.availableCheckTime > 0
                && SystemClock.elapsedRealtime() - proxy.availableCheckTime < retryInterval)) {
            return;
        }
        proxy.checking = true;
        ConnectionsManager.getInstance(acc).checkProxy(
                checkedSettings,
                time -> AndroidUtilities.runOnUIThread(() -> {
                    proxy.checking = false;
                    if (!checkedSettings.equals(proxy.settings)) return;
                    proxy.availableCheckTime = SystemClock.elapsedRealtime();
                    if (time == -1) {
                        proxy.available = false;
                        proxy.ping = 0;
                    } else {
                        proxy.ping = time;
                        proxy.available = true;
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxy);
                }));
    }

    @Override
    protected int contentColorOverride() {
        return connected ? Theme.getColor(Theme.key_windowBackgroundWhiteGreenText, resourcesProvider) : 0;
    }

    @Override
    public void onCardClicked() {
        try {
            LaunchActivity la = LaunchActivity.instance;
            if (la != null) {
                INavigationLayout layout = la.getActionBarLayout();
                if (layout != null) {
                    layout.presentFragment(new ProxyListActivity());
                    return;
                }
            }
        } catch (Throwable ignore) {
        }
        
        onUpdateData(true);
    }

    @Override
    public boolean onCardLongClicked() {
        BaseFragment fragment = getCurrentFragment();
        if (fragment == null) {
            return false;
        }
        
        final ItemOptions options = ItemOptions.makeOptions(fragment, this).setDrawScrim(false);
        options.add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                () -> fragment.presentFragment(new InfoCardsPreferencesActivity()));
        
        options.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)
                .show();
        return true;
    }

    private static BaseFragment getCurrentFragment() {
        try {
            LaunchActivity la = LaunchActivity.instance;
            if (la != null) {
                INavigationLayout layout = la.getActionBarLayout();
                if (layout != null) {
                    return layout.getLastFragment();
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    @Override
    public void updateColors() {
        
        applyColorMode();
    }
}
