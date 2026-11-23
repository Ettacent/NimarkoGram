/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.distribution;

import android.content.Context;
import app.nimarkogram.messenger.updater.AppUpdater;
import app.nimarkogram.messenger.updater.UpdaterSheet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

public final class ClientUpdates {
    private ClientUpdates() {}

    public static void open(BaseFragment fragment) {
        if (fragment != null) UpdaterSheet.showAlert(fragment, false, null);
    }

    public static void openFromContext(Context context) {
        if (context == null) return;
        if (ApplicationLoader.isStandaloneBuild()) {
            open(LaunchActivity.getLastFragment());
        } else {
            Browser.openUrl(context, BuildVars.isHuaweiStoreApp() ? BuildVars.HUAWEI_STORE_URL : BuildVars.PLAYSTORE_APP_URL);
        }
    }

    public static void onResume() {
        AndroidUtilities.runOnUIThread(() -> AppUpdater.checkOnLaunch(LaunchActivity.getLastFragment()), 2500);
    }

    public static void openNotification(BaseFragment fragment) {
        if (fragment == null) return;
        AppUpdater.Update saved = AppUpdater.getOrRestoreLastUpdate();
        if (saved != null) UpdaterSheet.showAlert(fragment, true, saved);
    }
}
