/*
 * Original Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.preferences.helpers

import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.edit
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.TLRPC.TL_peerColorCollectible
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.ImageUpdater
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.LoginActivity
import org.telegram.ui.LogoutActivity
import org.telegram.ui.PhotoViewer
import org.telegram.ui.SettingsActivity
import app.nimarkogram.messenger.NimarkoConfig
import app.nimarkogram.messenger.preferences.MainPreferencesActivity
import app.nimarkogram.messenger.utils.AppRestartHelper
import app.nimarkogram.messenger.utils.NimarkoDeeplinkHelper
import app.nimarkogram.messenger.utils.chats.NimarkoChatHelper2
import app.nimarkogram.messenger.utils.chats.NimarkoChatMenuInjector
import app.nimarkogram.messenger.utils.ui.MainTabsManager

class TelegramSettingsHelper(
    private var fragment: BaseFragment
) {

    private lateinit var avatarContainer: View
    private lateinit var avatarView: BackupImageView
    private lateinit var cameraButton: View
    private lateinit var imageUpdater: ImageUpdater

    private lateinit var listView: UniversalRecyclerView

    fun bindViews(
        avatarContainer: FrameLayout,
        avatarView: BackupImageView,
        cameraButton: FrameLayout,
        imageUpdater: ImageUpdater,

        listView: UniversalRecyclerView
    ) {
        this.avatarContainer = avatarContainer
        this.avatarView = avatarView
        this.cameraButton = cameraButton
        this.imageUpdater = imageUpdater

        this.listView = listView
    }

    fun showMyProfile(): Boolean {
        return !NimarkoConfig.showMainTabs || !MainTabsManager.hasTab(MainTabsManager.TabType.PROFILE)
    }

    fun showItemOptions(button: View) {
        val o = ItemOptions.makeOptions(fragment, button)

        o.add(
            R.drawable.msg_leave,
            getString(R.string.LogOut)
        ) {
            fragment.presentFragment(LogoutActivity())
        }

        o.add(
            R.drawable.msg_retry,
            getString(R.string.NM_HUB_Restart)
        ) {
            AppRestartHelper.restartApp(fragment.context)
        }

        o.setBlur(false)
        o.setDrawScrim(false)
        o.translate(0F, -dp(48F).toFloat())
        o.show()
    }

    private val provider = object : PhotoViewer.EmptyPhotoViewerProvider() {

        override fun getPlaceForPhoto(
            messageObject: MessageObject?,
            fileLocation: TLRPC.FileLocation?,
            index: Int,
            needPreview: Boolean,
            closing: Boolean
        ): PhotoViewer.PlaceProviderObject? {

            if (fileLocation == null) return null
            if (avatarContainer.scaleX > 0.96f && closing) return null

            val user = fragment.userConfig.currentUser
            val photoBig = user?.photo?.photo_big

            if (photoBig != null &&
                photoBig.local_id == fileLocation.local_id &&
                photoBig.volume_id == fileLocation.volume_id &&
                photoBig.dc_id == fileLocation.dc_id
            ) {
                val coords = IntArray(2)
                avatarView.getLocationInWindow(coords)

                val obj = PhotoViewer.PlaceProviderObject()
                obj.viewX = coords[0]
                obj.viewY = coords[1]
                obj.parentView = avatarView
                obj.imageReceiver = avatarView.imageReceiver
                obj.dialogId = fragment.userConfig.clientUserId
                obj.thumb = obj.imageReceiver.bitmapSafe ?: return null
                obj.size = -1
                obj.radius = avatarView.imageReceiver.getRoundRadius(true)
                obj.scale = avatarContainer.scaleX
                obj.canEdit = true
                obj.fadeIn = avatarContainer.scaleX > 0.96f

                return obj
            }

            return null
        }

        override fun willHidePhotoViewer() {
            avatarView.imageReceiver.setVisible(true, true)
        }

        override fun openPhotoForEdit(file: String?, thumb: String?, isVideo: Boolean) {
            imageUpdater.openPhotoForEdit(file, thumb, 0, isVideo)
        }

    }

    fun openAvatar() {
        val user = fragment.userConfig.currentUser
        val photo = user?.photo?.photo_big ?: return

        PhotoViewer.getInstance().parentActivity = fragment.parentActivity

        if (user.photo.dc_id != 0) {
            photo.dc_id = user.photo.dc_id
        }

        PhotoViewer.getInstance().setParentActivity(fragment)
        PhotoViewer.getInstance().openPhoto(photo, provider)
    }

    fun checkAvatarActions() {
        avatarContainer.setOnClickListener {
            openAvatar()
        }

        cameraButton.setOnClickListener {
            val user = fragment.userConfig.currentUser
            imageUpdater.openMenu(
                user != null && user.photo?.photo_big != null && user.photo !is TLRPC.TL_userProfilePhotoEmpty,
                { fragment.messagesController.deleteUserPhoto(null) },
                {},
                0
            )
        }
    }
     
    fun injectChannelAdvice(items: ArrayList<UItem>) {
        
    }

    fun injectAccounts(items: MutableList<UItem>, accountNumbers: ArrayList<Int>, user: TLRPC.User?) {
        items.add(UItem.asHeader(getString(R.string.SettingsAccounts)))

        val addAccountItem = SettingsActivity.SettingCell.Factory.of(
            1392,
            0xFF1CA5ED.toInt(),
            0xFF1488E1.toInt(),
            R.drawable.filled_add_album,
            getString(R.string.AddAccount)
        )

        if (accountNumbers.size >= 1) {
            addAccountItem.`object` = Runnable {
                NimarkoConfig.toggleShowAccounts()
            }
        }

        items.add(addAccountItem)

        for (i in accountNumbers.indices) {
            if (NimarkoConfig.showAccounts) {
                items.add(SettingsActivity.AccountCell.Factory.of(i, accountNumbers[i]))
            }
        }

        var colorTop = 0xFF1CA5ED.toInt()
        var colorBottom = 0xFF1488E1.toInt()

        if (showMyProfile() && user != null) {
            if (user.color is TL_peerColorCollectible) {
                val p = user.color as TL_peerColorCollectible
                val dark = Theme.isCurrentThemeDark()
                val colors = if (dark && p.dark_colors != null) p.dark_colors else p.colors

                val color1 = colors[0]!! or -0x1000000
                val color2 = if (colors.size >= 2) colors[1]!! or -0x1000000 else color1
                
                colorTop = color1
                colorBottom = color2
            } else {
                val colorId = UserObject.getColorId(user)
                if (colorId < 7) {
                    val color = Theme.getColor(Theme.keys_avatar_nameInMessage[colorId])
                    val isWhite = isWhiteOrNearWhite(color)
                    if (isWhite) {
                        colorTop = 0xFF1CA5ED.toInt()
                        colorBottom = 0xFF1488E1.toInt()
                    } else {
                        colorTop = Theme.getColor(Theme.keys_avatar_nameInMessage[colorId])
                        colorBottom = Theme.getColor(Theme.keys_avatar_nameInMessage[colorId])
                    }
                } else {
                    val peerColors = MessagesController.getInstance(UserConfig.selectedAccount).peerColors
                    val peerColor = peerColors?.getColor(colorId)
                    if (peerColor != null) {
                        colorTop = peerColor.color1
                        colorBottom = peerColor.color2
                    }
                }
            }
        }

        items.add(
            SettingsActivity.SettingCell.Factory.of(
                1,
                colorTop,
                colorBottom,
                R.drawable.settings_account,
                if (showMyProfile()) getString(R.string.MyProfile) else getString(R.string.SettingsAccount),
                getString(R.string.SettingsAccountInfo)
            )
        )

        items.add(UItem.asShadow(null))
    }

    fun injectCherryItems(items: MutableList<UItem>) {
        if (!NimarkoConfig.hideArchiveFromChatsList && fragment.messagesController.getDialogs(1).isNotEmpty()) {
            val archiveItem = SettingsActivity.SettingCell.Factory.of(
                1395,
                0xFFF45255.toInt(),
                0xFFDF3955.toInt(),
                R.drawable.cg_settings_archive_solar,
                getString(R.string.ArchivedChats)
            )
            archiveItem.`object` = "archive"
            items.add(archiveItem)
        }

        items.add(
            SettingsActivity.SettingCell.Factory.of(
                1393,
                0xFF4F85F6.toInt(),
                0xFF3568E8.toInt(),
                R.drawable.cg_settings_saved_solar,
                getString(R.string.SavedMessages)
            )
        )

        items.add(UItem.asShadow(null))

        items.add(
            SettingsActivity.SettingCell.Factory.of(
                1390,
                0xFFE54C7F.toInt(),
                0xFFA33156.toInt(),
                
                R.drawable.msg_settings_solar,
                getString(R.string.NimarkoGramSettings)
            )
        )

        items.add(UItem.asShadow(null))
    }

    fun handleOnClick(item: UItem) {
        when (item.id) {
            1390 -> fragment.presentFragment(MainPreferencesActivity())
            1392 -> {
                var freeAccounts = 0
                var availableAccount: Int? = null
                for (a in UserConfig.MAX_ACCOUNT_COUNT - 1 downTo 0) {
                    val uc = UserConfig.getInstance(a)
                    if (!uc.isClientActivated) {
                        freeAccounts++
                        if (availableAccount == null) availableAccount = a
                    }
                }

                if (!UserConfig.hasPremiumOnAccounts()) {
                    freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT)
                }

                if (freeAccounts > 0 && availableAccount != null) {
                    fragment.presentFragment(LoginActivity(availableAccount))
                } else if (!UserConfig.hasPremiumOnAccounts()) {
                    fragment.showDialog(
                        LimitReachedBottomSheet(
                            fragment,
                            fragment.context,
                            TYPE_ACCOUNTS,
                            fragment.currentAccount,
                            fragment.resourceProvider
                        )
                    )
                }
            }
            1393 -> fragment.presentFragment(ChatActivity.of(NimarkoChatHelper2.getCustomChatID(fragment.currentAccount)))
            
            1395 -> NimarkoChatMenuInjector.openArchivedChats(fragment)
        }
    }

    fun handleOnLongClick(item: UItem): Boolean {
        when (item.id) {
            1390 -> AndroidUtilities.addToClipboard("tg://${NimarkoDeeplinkHelper.DeepLinksRepo.NG_Settings}")
            12 -> AndroidUtilities.addToClipboard("tg://${NimarkoDeeplinkHelper.DeepLinksRepo.NG_Stars}")
        }
        return true
    }
     
    private fun isWhiteOrNearWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r > 230 && g > 230 && b > 230
    }

}
