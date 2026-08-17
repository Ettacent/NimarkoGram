 
package app.nimarkogram.messenger.ui

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ContactsController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.BulletinFactory
import app.nimarkogram.messenger.utils.AppRestartHelper

object BulletinCreator {

    fun createRestartBulletin(fragment: BaseFragment) {
        BulletinFactory.of(fragment).createSimpleBulletin(
            R.raw.info,
            getString(R.string.NM_RestartRequired),
            getString(R.string.NM_Restart)
        ) {
            AppRestartHelper.restartApp(fragment.context)
        }.show()
    }

    fun createDebugSuccessBulletin(fragment: BaseFragment) {
        BulletinFactory.of(fragment)
            .createSuccessBulletin(getString(R.string.OK))
            .setDuration(Bulletin.DURATION_LONG)
            .show()
    }

    fun createSwitchAccountBulletin(account: Int) {
        val nextAcc: TLObject? = UserConfig.getInstance(account).currentUser

        if (nextAcc is TLRPC.User) {
            AndroidUtilities.runOnUIThread({
                val accs = ArrayList<TLObject?>()
                accs.add(nextAcc)

                val text: CharSequence = AndroidUtilities.replaceTags(
                    "Switched to **" +
                        ContactsController.formatName(nextAcc.first_name, nextAcc.last_name) +
                        "**"
                )

                BulletinFactory.global().createChatsBulletin(accs, text, null)
                    .setDuration(Bulletin.DURATION_LONG)
                    .show()

                accs.clear()
            }, 200)
        }
    }

}
