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
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProxyListActivity;

import app.nimarkogram.messenger.infocards.preferences.InfoCardsPreferencesActivity;

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
    }

    @Override
    public int getCardId() {
        return InfoCardType.PROXY.id;
    }

    @Override
    public long getRefreshInterval() {
        
        return 30000; 
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
        if (id == NotificationCenter.proxySettingsChanged
                || id == NotificationCenter.proxyCheckDone
                || id == NotificationCenter.didUpdateConnectionState) {
            
            AndroidUtilities.cancelRunOnUIThread(coalescedUpdate);
            AndroidUtilities.runOnUIThread(coalescedUpdate, COALESCE_MS);
        }
    }

    @Override
    public void onUpdateData(boolean force) {
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        bindAccountObserver();
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        boolean enabled = SharedConfig.isProxyEnabled() && proxy != null;
        final int account = observedAccount;
        int connectionState = ConnectionsManager.getInstance(account).getConnectionState();
        boolean isConnected = connectionState == STATE_CONNECTED || connectionState == STATE_UPDATING;

        if (enabled && isConnected) {
            
            setIcon(R.drawable.pill_proxy);
            
            kickProxyCheck(proxy);
            if (proxy.ping > 0) {
                int ping = Math.max(0, Math.min(9999, (int) proxy.ping));
                setText(ping + " ms", true);
            } else {
                setText(LocaleController.getString(R.string.MenuProxyConnected), true);
            }
            stopLoading();
            connected = true;
        } else if (enabled) {
            
            setIcon(R.drawable.pill_proxy_off);
            setText(LocaleController.getString(R.string.MenuProxyConnecting), true);
            startLoading();
            connected = false;
        } else {
            
            setIcon(R.drawable.pill_proxy_off);
            setText(LocaleController.getString(R.string.Proxy), true);
            stopLoading();
            connected = false;
        }
        
        applyColorMode();
        markDataUpdated();
    }

    public static CharSequence liveValueText() {
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        boolean enabled = SharedConfig.isProxyEnabled() && proxy != null;
        int connectionState = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        boolean isConnected = connectionState == STATE_CONNECTED || connectionState == STATE_UPDATING;
        if (enabled && isConnected) {
            if (proxy.ping > 0) {
                int ping = Math.max(0, Math.min(9999, (int) proxy.ping));
                return ping + " ms";
            }
            return LocaleController.getString(R.string.MenuProxyConnected);
        } else if (enabled) {
            return LocaleController.getString(R.string.MenuProxyConnecting);
        }
        
        return null;
    }

    private void kickProxyCheck(SharedConfig.ProxyInfo proxy) {
        if (proxy == null || proxy.checking
                || SystemClock.elapsedRealtime() - proxy.availableCheckTime < 2 * 60 * 1000) {
            return;
        }
        final int acc = observedAccount;
        proxy.checking = true;
        proxy.proxyCheckPingId = ConnectionsManager.getInstance(acc).checkProxy(
                proxy.address, proxy.port, proxy.username, proxy.password, proxy.secret,
                time -> AndroidUtilities.runOnUIThread(() -> {
                    proxy.availableCheckTime = SystemClock.elapsedRealtime();
                    proxy.checking = false;
                    if (acc != UserConfig.selectedAccount || proxy != SharedConfig.currentProxy) {
                        return;
                    }
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
    protected int fillColorOverride() {
        
        return connected ? Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider) : 0;
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
        
        final ItemOptions options = ItemOptions.makeOptions(fragment, this);
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
