/**
 * This file is part of NimarkoGram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * NimarkoGram modifications:
 * Copyright Ettacent, 2026.
 *
 * Portions derived from Cherrygram:
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.helpers.NimarkoAlertDialogSwitchers;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class MessagesPreferencesActivity extends NimarkoUniversalPreferencesActivity {

    private final int messageMenuRow = 1, messageSizeRow = 2, directShareRow = 3,
            showForwardDateRow = 5, pencilIconForEditedRow = 6;

    private final int messageFilterRow = 9, leftBottomBtnRow = 10, doubleTapRow = 11, slideActionRow = 12, deleteForAllRow = 13;

    private final int reactionsOverlayRow = 14, reactionAnimationRow = 15, tapsOnPremiumStickersRow = 16, premiumStickersAutoplayRow = 17;

    private final int gifSpoilersRow = 18;

    @Override
    protected CharSequence getTitle() {
         
        return getString(R.string.MessagesSettings);
    }

    @Override
    public View createView(Context context) {
        setMD3(true);
        return super.createView(context);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AP_Header_Appearance)));
        items.add(UItem.asButton(messageMenuRow, R.drawable.msg_list, getString(R.string.CP_MessageMenu)));
        items.add(UItem.asButton(messageSizeRow, R.drawable.msg_photo_settings, getString(R.string.CP_Messages_Size)));
        items.add(UItem.asButton(directShareRow, R.drawable.msg_share, getString(R.string.DirectShare)));
        items.add(SettingsHelper.asSwitchCG(showForwardDateRow, getString(R.string.CP_ForwardMsgDate))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.msgForwardDate)
        );
        items.add(SettingsHelper.asSwitchCG(pencilIconForEditedRow, getString(R.string.AP_ShowPencilIcon))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.showPencilIcon)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.ActionsChartTitle)));
        items.add(UItem.asButton(messageFilterRow, R.drawable.msg_notspam, getString(R.string.CP_Message_Filtering)));
        items.add(UItem.asButton(leftBottomBtnRow, getString(R.string.CP_LeftBottomButtonAction), getLeftBottomButtonValue()));
        items.add(UItem.asButton(doubleTapRow, getString(R.string.CP_DoubleTapAction), getDoubleTapActionValue()));
        items.add(UItem.asButton(slideActionRow, getString(R.string.NM_MsgSlideAction), getSlideActionValue()));
        items.add(SettingsHelper.asSwitchCG(deleteForAllRow, getString(R.string.CP_DeleteForAll), getString(R.string.CP_DeleteForAll_Desc))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.deleteForAll)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.TelegramPremium)));
        items.add(SettingsHelper.asSwitchCG(reactionsOverlayRow, getString(R.string.CP_DisableReactionsOverlay), getString(R.string.CP_DisableReactionsOverlay_Desc))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.disableReactionsOverlay)
        );
        items.add(SettingsHelper.asSwitchCG(reactionAnimationRow, getString(R.string.CP_DisableReactionAnim), getString(R.string.CP_DisableReactionAnim_Desc))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.disableReactionAnim)
        );
        items.add(SettingsHelper.asSwitchCG(tapsOnPremiumStickersRow, getString(R.string.CP_DisablePremStickAnim), getString(R.string.CP_DisablePremStickAnim_Desc))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.disablePremStickAnim)
        );
        items.add(SettingsHelper.asSwitchCG(premiumStickersAutoplayRow, getString(R.string.CP_DisablePremStickAutoPlay), getString(R.string.CP_DisablePremStickAutoPlay_Desc))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.disablePremStickAutoPlay)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_MSG_Header_Sending)));
        items.add(SettingsHelper.asSwitchCG(gifSpoilersRow, getString(R.string.NM_MSG_GifSpoilers))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.gifSpoilers)
        );
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == messageMenuRow) {
            presentFragment(new MessageMenuPreferencesActivity());
        } else if (item.id == messageSizeRow) {
            NimarkoAlertDialogSwitchers.showMessageSize(this);
        } else if (item.id == directShareRow) {
            showDirectShareConfigurator(this);
        } else if (item.id == showForwardDateRow) {
            NimarkoConfig.toggleMsgForwardDate();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.msgForwardDate);
        } else if (item.id == pencilIconForEditedRow) {
            NimarkoConfig.toggleShowPencilIcon();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.showPencilIcon);
        } else if (item.id == messageFilterRow) {
            presentFragment(new MessageFiltersPreferencesActivity());
        } else if (item.id == leftBottomBtnRow) {
            showLeftBottomButtonSelector(() -> SettingsHelper.updateButtonValue(view, getLeftBottomButtonValue()));
        } else if (item.id == doubleTapRow) {
            showDoubleTapSelector(() -> SettingsHelper.updateButtonValue(view, getDoubleTapActionValue()));
        } else if (item.id == slideActionRow) {
            showSlideActionSelector(() -> SettingsHelper.updateButtonValue(view, getSlideActionValue()));
        } else if (item.id == deleteForAllRow) {
            NimarkoConfig.toggleDeleteForAll();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.deleteForAll);
        } else if (item.id == reactionsOverlayRow) {
            NimarkoConfig.toggleDisableReactionsOverlay();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.disableReactionsOverlay);

            showRestartBulletin();
        } else if (item.id == reactionAnimationRow) {
            NimarkoConfig.toggleDisableReactionAnim();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.disableReactionAnim);

            showRestartBulletin();
        } else if (item.id == tapsOnPremiumStickersRow) {
            NimarkoConfig.toggleDisablePremStickAnim();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.disablePremStickAnim);

            showRestartBulletin();
        } else if (item.id == premiumStickersAutoplayRow) {
            NimarkoConfig.toggleDisablePremStickAutoPlay();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.disablePremStickAutoPlay);

            showRestartBulletin();
        } else if (item.id == gifSpoilersRow) {
            NimarkoConfig.toggleGifSpoilers();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.gifSpoilers);
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        
        return false;
    }

    private void showDirectShareConfigurator(BaseFragment fragment) {
        List<ChatsPreferencesActivity.MenuItemConfig> menuItems = Arrays.asList(
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.RepostToStory),
                        R.drawable.large_repost_story,
                        () -> NimarkoConfig.shareDrawStoryButton,
                        () -> NimarkoConfig.toggleShareDrawStoryButton(),
                        true,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.FilterChats),
                        0,
                        () -> NimarkoConfig.usersDrawShareButton,
                        () -> NimarkoConfig.toggleUsersDrawShareButton(),
                        false,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.FilterGroups),
                        0,
                        () -> NimarkoConfig.supergroupsDrawShareButton,
                        () -> NimarkoConfig.toggleSupergroupsDrawShareButton(),
                        false,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.FilterChannels),
                        0,
                        () -> NimarkoConfig.channelsDrawShareButton,
                        () -> NimarkoConfig.toggleChannelsDrawShareButton(),
                        false,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.FilterBots),
                        0,
                        () -> NimarkoConfig.botsDrawShareButton,
                        () -> NimarkoConfig.toggleBotsDrawShareButton(),
                        false,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.StickersName),
                        0,
                        () -> NimarkoConfig.stickersDrawShareButton,
                        () -> NimarkoConfig.toggleStickersDrawShareButton(),
                        false,
                        false
                )
        );

        handleMenuAlert(getString(R.string.DirectShare), menuItems, fragment);
    }

    private String getLeftBottomButtonValue() {
        return switch (app.nimarkogram.messenger.NimarkoConfig.actionsBarLeftButton) {
            case NimarkoConfig.ACTIONS_LEFT_SAVE_MESSAGE -> getString(R.string.NM_ToSaved);
            case NimarkoConfig.ACTIONS_LEFT_DIRECT_SHARE -> getString(R.string.DirectShare);
            case NimarkoConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP -> getString(R.string.Forward) + " " + getString(R.string.NM_Without_Authorship);
            case NimarkoConfig.ACTIONS_LEFT_REPLY -> getString(R.string.Reply);
            default -> getString(R.string.Reply);
        };
    }

    private void showLeftBottomButtonSelector(Runnable runnable) {
        ArrayList<String> configStringKeys = new ArrayList<>();
        ArrayList<Integer> configValues = new ArrayList<>();

        configStringKeys.add(getString(R.string.Forward) + " " + getString(R.string.NM_Without_Authorship));
        configValues.add(NimarkoConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP);

        configStringKeys.add(getString(R.string.Reply));
        configValues.add(NimarkoConfig.ACTIONS_LEFT_REPLY);

        configStringKeys.add(getString(R.string.NM_ToSaved));
        configValues.add(NimarkoConfig.ACTIONS_LEFT_SAVE_MESSAGE);

        configStringKeys.add(getString(R.string.DirectShare));
        configValues.add(NimarkoConfig.ACTIONS_LEFT_DIRECT_SHARE);

        PopupHelper.show(configStringKeys, getString(R.string.CP_LeftBottomButtonAction), configValues.indexOf(app.nimarkogram.messenger.NimarkoConfig.actionsBarLeftButton), getContext(), i -> {
            NimarkoConfig.setActionsBarLeftButton(configValues.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getDoubleTapActionValue() {
        return switch (app.nimarkogram.messenger.NimarkoConfig.doubletapaction) {
            case NimarkoConfig.DOUBLE_TAP_ACTION_REACTION -> getString(R.string.Reactions);
            case NimarkoConfig.DOUBLE_TAP_ACTION_REPLY -> getString(R.string.Reply);
            case NimarkoConfig.DOUBLE_TAP_ACTION_SAVE -> getString(R.string.NM_ToSaved);
            case NimarkoConfig.DOUBLE_TAP_ACTION_EDIT -> getString(R.string.Edit);
            case NimarkoConfig.DOUBLE_TAP_ACTION_EDIT_OR_REACTION -> getString(R.string.NM_DoubleTap_EditOrReact);
            case NimarkoConfig.DOUBLE_TAP_ACTION_TRANSLATE -> getString(R.string.TranslateMessage);
            default -> getString(R.string.Disable);
        };
    }

    private void showDoubleTapSelector(Runnable runnable) {
        ArrayList<String> configStringKeys = new ArrayList<>();
        ArrayList<Integer> configValues = new ArrayList<>();

        configStringKeys.add(getString(R.string.Disable));
        configValues.add(NimarkoConfig.DOUBLE_TAP_ACTION_NONE);

        configStringKeys.add(getString(R.string.Reactions));
        configValues.add(NimarkoConfig.DOUBLE_TAP_ACTION_REACTION);

        configStringKeys.add(getString(R.string.Reply));
        configValues.add(NimarkoConfig.DOUBLE_TAP_ACTION_REPLY);

        configStringKeys.add(getString(R.string.NM_ToSaved));
        configValues.add(NimarkoConfig.DOUBLE_TAP_ACTION_SAVE);

        configStringKeys.add(getString(R.string.Edit));
        configValues.add(NimarkoConfig.DOUBLE_TAP_ACTION_EDIT);

        configStringKeys.add(getString(R.string.TranslateMessage));
        configValues.add(NimarkoConfig.DOUBLE_TAP_ACTION_TRANSLATE);

        configStringKeys.add(getString(R.string.NM_DoubleTap_EditOrReact));
        configValues.add(NimarkoConfig.DOUBLE_TAP_ACTION_EDIT_OR_REACTION);

        PopupHelper.show(configStringKeys, getString(R.string.CP_DoubleTapAction), configValues.indexOf(app.nimarkogram.messenger.NimarkoConfig.doubletapaction), getContext(), i -> {
            NimarkoConfig.setDoubleTapAction(configValues.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getSlideActionValue() {
        return switch (app.nimarkogram.messenger.NimarkoConfig.messageslideaction) {
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_SAVE -> getString(R.string.NM_ToSaved);
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_TRANSLATE -> getString(R.string.TranslateMessage);
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE -> getString(R.string.DirectShare);
            default -> getString(R.string.Reply);
        };
    }

    private void showSlideActionSelector(Runnable runnable) {
        ArrayList<String> configStringKeys = new ArrayList<>();
        ArrayList<Integer> configValues = new ArrayList<>();

        configStringKeys.add(getString(R.string.Reply));
        configValues.add(NimarkoConfig.MESSAGE_SLIDE_ACTION_REPLY);

        configStringKeys.add(getString(R.string.NM_ToSaved));
        configValues.add(NimarkoConfig.MESSAGE_SLIDE_ACTION_SAVE);

        configStringKeys.add(getString(R.string.TranslateMessage));
        configValues.add(NimarkoConfig.MESSAGE_SLIDE_ACTION_TRANSLATE);

        configStringKeys.add(getString(R.string.DirectShare));
        configValues.add(NimarkoConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE);

        PopupHelper.show(configStringKeys, getString(R.string.NM_MsgSlideAction), configValues.indexOf(app.nimarkogram.messenger.NimarkoConfig.messageslideaction), getContext(), i -> {
            NimarkoConfig.setMessageSlideAction(configValues.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private static void handleMenuAlert(String title, List<ChatsPreferencesActivity.MenuItemConfig> items, BaseFragment fragment) {
        ArrayList<String> prefTitle = new ArrayList<>();
        ArrayList<Integer> prefIcon = new ArrayList<>();
        ArrayList<Boolean> prefCheck = new ArrayList<>();
        ArrayList<Boolean> prefCheckInvisible = new ArrayList<>();
        ArrayList<Boolean> prefDivider = new ArrayList<>();
        ArrayList<Runnable> clickListener = new ArrayList<>();

        for (ChatsPreferencesActivity.MenuItemConfig item : items) {
            prefTitle.add(item.title);
            prefIcon.add(item.iconRes);
            prefCheck.add(item.isChecked.get());
            prefCheckInvisible.add(item.isCheckInvisible);
            prefDivider.add(item.divider);
            clickListener.add(item.toggle);
        }

        PopupHelper.showSwitchAlert(
                title,
                fragment,
                prefTitle,
                prefIcon,
                prefCheck,
                prefCheckInvisible,
                null,
                prefDivider,
                clickListener,
                null
        );
    }

}
