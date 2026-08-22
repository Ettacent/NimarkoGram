package app.nimarkogram.messenger.wsbypass.preferences;

import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ProxyListActivity;

import app.nimarkogram.messenger.preferences.BasePreferencesActivity;
import app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig;
import app.nimarkogram.messenger.wsbypass.NimarkoWsBypassController;
import app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig;

public class WsBypassPreferencesActivity extends BasePreferencesActivity
        implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ENABLED         = 200;
    private static final int ID_STATUS          = 201;
    private static final int ID_OPEN_PROXY      = 202;
    private static final int ID_VOIP_BYPASS     = 204;
    private static final int ID_SUSPEND_VPN     = 205;

    private final NimarkoWsBypassController ctrl = NimarkoWsBypassController.getInstance();

    private boolean statusPollScheduled;
    private final Runnable statusPoll = () -> {
        statusPollScheduled = false;
        if (NimarkoWsBypassConfig.enabled) reload();
    };
    private void scheduleStatusPoll() {
        if (statusPollScheduled) return;
        statusPollScheduled = true;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(statusPoll, 1000);
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_WSB_Title);
    }

    @Override
    public boolean onFragmentCreate() {
        ctrl.setSettingsReloader(this::reload);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        ctrl.setSettingsReloader(this::reload);
        if (NimarkoWsBypassConfig.enabled) {
            app.nimarkogram.messenger.wsbypass.WsRelayAuth.prefetchAsync(
                    org.telegram.messenger.UserConfig.selectedAccount);
            if (VoipBypassConfig.isVoipBypassEnabled()) {
                app.nimarkogram.messenger.wsbypass.voip.VoipRelayAuth.prefetchAsync(
                        org.telegram.messenger.UserConfig.selectedAccount);
            }
        }
        reload();
    }

    @Override
    public void onFragmentDestroy() {
        ctrl.setSettingsReloader(null);
        org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(statusPoll);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged) {
            reload();
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = NimarkoWsBypassConfig.enabled;

        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionConnection)));
        items.add(UItem.asCheck(ID_ENABLED, LocaleController.getString(R.string.NM_WSB_Enable))
                .setChecked(enabled));

        String state = enabled ? ctrl.getConnectionState() : NimarkoWsBypassController.STATE_OFF;
        String dot;
        String label;
        switch (state) {
            case NimarkoWsBypassController.STATE_RUNNING:
                dot = "🟢";
                label = LocaleController.getString(R.string.NM_WSB_Status_Connected);
                break;
            case NimarkoWsBypassController.STATE_STARTING:
                dot = "🟡";
                label = LocaleController.getString(R.string.NM_WSB_Status_Starting);
                break;
            case NimarkoWsBypassController.STATE_FAILED:
                dot = "🔴";
                label = LocaleController.getString(R.string.NM_WSB_Status_Failed);
                break;
            case NimarkoWsBypassController.STATE_VPN:
                dot = "🔵";
                label = LocaleController.getString(R.string.NM_WSB_Status_VpnSuspended);
                break;
            case NimarkoWsBypassController.STATE_OFF:
            default:
                dot = "⚪";
                label = LocaleController.getString(R.string.NM_WSB_Status_Off);
                break;
        }
        CharSequence statusDetails = dot + "  " + label;
        items.add(asSettingsLink(ID_STATUS, IconBackgroundColors.BLUE,
                R.drawable.msg_info,
                LocaleController.getString(R.string.NM_WSB_StatusTitle), statusDetails));
        if (enabled) {
            boolean terminal = NimarkoWsBypassController.STATE_RUNNING.equals(state)
                    || NimarkoWsBypassController.STATE_FAILED.equals(state)
                    || NimarkoWsBypassController.STATE_VPN.equals(state);
            if (!terminal) scheduleStatusPoll();
        }
        items.add(asSettingsLink(ID_OPEN_PROXY, IconBackgroundColors.PURPLE,
                R.drawable.msg_link_2,
                LocaleController.getString(R.string.NM_WSB_OpenProxySettings)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_WSB_About)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_WSB_MiscHeader)));
        items.add(UItem.asCheck(ID_VOIP_BYPASS, LocaleController.getString(R.string.NM_WSB_VoIP_Enable))
                .setChecked(VoipBypassConfig.isVoipBypassEnabled()));
        items.add(UItem.asCheck(ID_SUSPEND_VPN,
                LocaleController.getString(R.string.NM_WSB_SuspendOnVpn))
                .setChecked(NimarkoWsBypassConfig.suspendOnVpn));

        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_WSB_SuspendOnVpn_Desc)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null) return;
        switch (item.id) {
            case ID_ENABLED:
                NimarkoWsBypassConfig.setEnabled(!NimarkoWsBypassConfig.enabled);
                updateCheckState(view, NimarkoWsBypassConfig.enabled);
                if (NimarkoWsBypassConfig.enabled) {
                    ctrl.ensureStarted();
                } else {
                    ctrl.stop();
                }
                reload();
                break;
            case ID_OPEN_PROXY:
                presentFragment(new ProxyListActivity());
                break;
            case ID_SUSPEND_VPN:
                NimarkoWsBypassConfig.setSuspendOnVpn(!NimarkoWsBypassConfig.suspendOnVpn);
                updateCheckState(view, NimarkoWsBypassConfig.suspendOnVpn);
                ctrl.reevaluateForVpnToggle();
                reload();
                break;
            case ID_STATUS:
                if (NimarkoWsBypassConfig.enabled) {
                    ctrl.ensureStarted();
                    reload();
                }
                break;
            case ID_VOIP_BYPASS:
                boolean newVal = !VoipBypassConfig.isVoipBypassEnabled();
                VoipBypassConfig.setVoipBypassEnabled(newVal);
                updateCheckState(view, newVal);
                if (newVal) {
                    app.nimarkogram.messenger.wsbypass.voip.VoipRelayAuth.prefetchAsync(
                            org.telegram.messenger.UserConfig.selectedAccount);
                }
                break;
        }
    }

    private void reload() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
