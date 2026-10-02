/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.ui;

import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import app.nimarkogram.messenger.utils.AppRestartHelper;

public final class RestartBulletin {
    private RestartBulletin() { }

    public static void show(BaseFragment fragment) {
        Bulletin bulletin = BulletinFactory.of(fragment).createSimpleBulletin(
                R.raw.info,
                LocaleController.getString(R.string.NM_RestartRequired),
                LocaleController.getString(R.string.NM_Restart),
                () -> AppRestartHelper.triggerRebirth(fragment.getParentActivity() != null
                        ? fragment.getParentActivity() : fragment.getContext()));
        ViewGroup wrapper = (ViewGroup) bulletin.getLayout().getParent();
        wrapper.setPadding(wrapper.getPaddingLeft(), wrapper.getPaddingTop(),
                wrapper.getPaddingRight(), wrapper.getPaddingBottom() + AndroidUtilities.dp(12));
        bulletin.show();
    }
}
