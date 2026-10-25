/* Modifications Copyright (C) 2026 Ettacent */

/**
 * This is the source code of Nimarko for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
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

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.preferences.helpers.AlertDialogSwitchers;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class MessagesPreferencesActivity extends UniversalPreferencesActivity {

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
        items.add(UItem.asHeader(getString(R.string.NM_Header_ChatView)));
        items.add(asPlainSettingsRowWithSubtitle(messageMenuRow, getString(R.string.NM_MessageMenu),
                getString(R.string.NM_SettingsSummaryMessageMenu)));
        items.add(asPlainSettingsRow(messageSizeRow, getString(R.string.NM_Messages_Size)));
        items.add(asPlainSettingsRowWithSubtitle(directShareRow, getString(R.string.DirectShare),
                getString(R.string.DirectShareInfo)));
        items.add(SettingsHelper.asSwitchCG(showForwardDateRow, getString(R.string.NM_ForwardMsgDate))
                .setChecked(AppConfig.msgForwardDate)
        );
        items.add(SettingsHelper.asSwitchCG(pencilIconForEditedRow, getString(R.string.AP_ShowPencilIcon))
                .setChecked(AppConfig.showPencilIcon)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.ActionsChartTitle)));
        items.add(asPlainSettingsRowWithSubtitle(messageFilterRow, getString(R.string.NM_Message_Filtering),
                getString(R.string.NM_SettingsSummaryMessageFilters)));
        items.add(asPlainSettingsRow(leftBottomBtnRow,
                getString(R.string.NM_LeftBottomButtonAction), getLeftBottomButtonValue()));
        items.add(asPlainSettingsRow(doubleTapRow,
                getString(R.string.NM_DoubleTapAction), getDoubleTapActionValue()));
        items.add(asPlainSettingsRow(slideActionRow,
                getString(R.string.NM_MsgSlideAction), getSlideActionValue()));
        items.add(SettingsHelper.asSwitchCG(deleteForAllRow, getString(R.string.NM_DeleteForAll))
                .setChecked(AppConfig.deleteForAll)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.TelegramPremium)));
        items.add(SettingsHelper.asSwitchCG(reactionsOverlayRow, getString(R.string.NM_DisableReactionsOverlay))
                .setChecked(AppConfig.disableReactionsOverlay)
        );
        items.add(SettingsHelper.asSwitchCG(reactionAnimationRow, getString(R.string.NM_DisableReactionAnim))
                .setChecked(AppConfig.disableReactionAnim)
        );
        items.add(SettingsHelper.asSwitchCG(tapsOnPremiumStickersRow, getString(R.string.NM_DisablePremStickAnim))
                .setChecked(AppConfig.disablePremStickAnim)
        );
        items.add(SettingsHelper.asSwitchCG(premiumStickersAutoplayRow, getString(R.string.NM_DisablePremStickAutoPlay))
                .setChecked(AppConfig.disablePremStickAutoPlay)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_MSG_Header_Sending)));
        items.add(SettingsHelper.asSwitchCG(gifSpoilersRow, getString(R.string.NM_MSG_GifSpoilers))
                .setChecked(AppConfig.gifSpoilers)
        );
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == messageMenuRow) {
            presentFragment(new MessageMenuPreferencesActivity());
        } else if (item.id == messageSizeRow) {
            AlertDialogSwitchers.showMessageSize(this);
        } else if (item.id == directShareRow) {
            showDirectShareConfigurator(this);
        } else if (item.id == showForwardDateRow) {
            AppConfig.toggleMsgForwardDate();
            updateCheckState(view, AppConfig.msgForwardDate);
        } else if (item.id == pencilIconForEditedRow) {
            AppConfig.toggleShowPencilIcon();
            updateCheckState(view, AppConfig.showPencilIcon);
        } else if (item.id == messageFilterRow) {
            presentFragment(new MessageFiltersPreferencesActivity());
        } else if (item.id == leftBottomBtnRow) {
            showLeftBottomButtonSelector(() -> SettingsHelper.updateButtonValue(view, getLeftBottomButtonValue()));
        } else if (item.id == doubleTapRow) {
            showDoubleTapSelector(() -> SettingsHelper.updateButtonValue(view, getDoubleTapActionValue()));
        } else if (item.id == slideActionRow) {
            showSlideActionSelector(() -> SettingsHelper.updateButtonValue(view, getSlideActionValue()));
        } else if (item.id == deleteForAllRow) {
            AppConfig.toggleDeleteForAll();
            updateCheckState(view, AppConfig.deleteForAll);
        } else if (item.id == reactionsOverlayRow) {
            AppConfig.toggleDisableReactionsOverlay();
            updateCheckState(view, AppConfig.disableReactionsOverlay);

            showRestartBulletin();
        } else if (item.id == reactionAnimationRow) {
            AppConfig.toggleDisableReactionAnim();
            updateCheckState(view, AppConfig.disableReactionAnim);

            showRestartBulletin();
        } else if (item.id == tapsOnPremiumStickersRow) {
            AppConfig.toggleDisablePremStickAnim();
            updateCheckState(view, AppConfig.disablePremStickAnim);

            showRestartBulletin();
        } else if (item.id == premiumStickersAutoplayRow) {
            AppConfig.toggleDisablePremStickAutoPlay();
            updateCheckState(view, AppConfig.disablePremStickAutoPlay);

            showRestartBulletin();
        } else if (item.id == gifSpoilersRow) {
            AppConfig.toggleGifSpoilers();
            updateCheckState(view, AppConfig.gifSpoilers);
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
                        () -> AppConfig.shareDrawStoryButton,
                        () -> AppConfig.toggleShareDrawStoryButton(),
                        true,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.FilterChats),
                        0,
                        () -> AppConfig.usersDrawShareButton,
                        () -> AppConfig.toggleUsersDrawShareButton(),
                        false,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.FilterGroups),
                        0,
                        () -> AppConfig.supergroupsDrawShareButton,
                        () -> AppConfig.toggleSupergroupsDrawShareButton(),
                        false,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.FilterChannels),
                        0,
                        () -> AppConfig.channelsDrawShareButton,
                        () -> AppConfig.toggleChannelsDrawShareButton(),
                        false,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.FilterBots),
                        0,
                        () -> AppConfig.botsDrawShareButton,
                        () -> AppConfig.toggleBotsDrawShareButton(),
                        false,
                        false
                ),
                new ChatsPreferencesActivity.MenuItemConfig(
                        getString(R.string.StickersName),
                        0,
                        () -> AppConfig.stickersDrawShareButton,
                        () -> AppConfig.toggleStickersDrawShareButton(),
                        false,
                        false
                )
        );

        handleMenuAlert(getString(R.string.DirectShare), menuItems, fragment);
    }

    private String getLeftBottomButtonValue() {
        return switch (AppConfig.actionsBarLeftButton) {
            case AppConfig.ACTIONS_LEFT_SAVE_MESSAGE -> getString(R.string.NM_ToSaved);
            case AppConfig.ACTIONS_LEFT_DIRECT_SHARE -> getString(R.string.DirectShare);
            case AppConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP -> getString(R.string.Forward) + " " + getString(R.string.NM_Without_Authorship);
            case AppConfig.ACTIONS_LEFT_REPLY -> getString(R.string.Reply);
            default -> getString(R.string.Reply);
        };
    }

    private void showLeftBottomButtonSelector(Runnable runnable) {
        ArrayList<String> configStringKeys = new ArrayList<>();
        ArrayList<Integer> configValues = new ArrayList<>();

        configStringKeys.add(getString(R.string.Forward) + " " + getString(R.string.NM_Without_Authorship));
        configValues.add(AppConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP);

        configStringKeys.add(getString(R.string.Reply));
        configValues.add(AppConfig.ACTIONS_LEFT_REPLY);

        configStringKeys.add(getString(R.string.NM_ToSaved));
        configValues.add(AppConfig.ACTIONS_LEFT_SAVE_MESSAGE);

        configStringKeys.add(getString(R.string.DirectShare));
        configValues.add(AppConfig.ACTIONS_LEFT_DIRECT_SHARE);

        PopupHelper.show(configStringKeys, getString(R.string.NM_LeftBottomButtonAction), configValues.indexOf(AppConfig.actionsBarLeftButton), getContext(), i -> {
            AppConfig.setActionsBarLeftButton(configValues.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getDoubleTapActionValue() {
        return switch (AppConfig.doubletapaction) {
            case AppConfig.DOUBLE_TAP_ACTION_REACTION -> getString(R.string.Reactions);
            case AppConfig.DOUBLE_TAP_ACTION_REPLY -> getString(R.string.Reply);
            case AppConfig.DOUBLE_TAP_ACTION_SAVE -> getString(R.string.NM_ToSaved);
            case AppConfig.DOUBLE_TAP_ACTION_EDIT -> getString(R.string.Edit);
            case AppConfig.DOUBLE_TAP_ACTION_EDIT_OR_REACTION -> getString(R.string.NM_DoubleTap_EditOrReact);
            case AppConfig.DOUBLE_TAP_ACTION_TRANSLATE -> getString(R.string.TranslateMessage);
            default -> getString(R.string.Disable);
        };
    }

    private void showDoubleTapSelector(Runnable runnable) {
        ArrayList<String> configStringKeys = new ArrayList<>();
        ArrayList<Integer> configValues = new ArrayList<>();

        configStringKeys.add(getString(R.string.Disable));
        configValues.add(AppConfig.DOUBLE_TAP_ACTION_NONE);

        configStringKeys.add(getString(R.string.Reactions));
        configValues.add(AppConfig.DOUBLE_TAP_ACTION_REACTION);

        configStringKeys.add(getString(R.string.Reply));
        configValues.add(AppConfig.DOUBLE_TAP_ACTION_REPLY);

        configStringKeys.add(getString(R.string.NM_ToSaved));
        configValues.add(AppConfig.DOUBLE_TAP_ACTION_SAVE);

        configStringKeys.add(getString(R.string.Edit));
        configValues.add(AppConfig.DOUBLE_TAP_ACTION_EDIT);

        configStringKeys.add(getString(R.string.TranslateMessage));
        configValues.add(AppConfig.DOUBLE_TAP_ACTION_TRANSLATE);

        configStringKeys.add(getString(R.string.NM_DoubleTap_EditOrReact));
        configValues.add(AppConfig.DOUBLE_TAP_ACTION_EDIT_OR_REACTION);

        PopupHelper.show(configStringKeys, getString(R.string.NM_DoubleTapAction), configValues.indexOf(AppConfig.doubletapaction), getContext(), i -> {
            AppConfig.setDoubleTapAction(configValues.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getSlideActionValue() {
        return switch (AppConfig.messageslideaction) {
            case AppConfig.MESSAGE_SLIDE_ACTION_SAVE -> getString(R.string.NM_ToSaved);
            case AppConfig.MESSAGE_SLIDE_ACTION_TRANSLATE -> getString(R.string.TranslateMessage);
            case AppConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE -> getString(R.string.DirectShare);
            default -> getString(R.string.Reply);
        };
    }

    private void showSlideActionSelector(Runnable runnable) {
        ArrayList<String> configStringKeys = new ArrayList<>();
        ArrayList<Integer> configValues = new ArrayList<>();

        configStringKeys.add(getString(R.string.Reply));
        configValues.add(AppConfig.MESSAGE_SLIDE_ACTION_REPLY);

        configStringKeys.add(getString(R.string.NM_ToSaved));
        configValues.add(AppConfig.MESSAGE_SLIDE_ACTION_SAVE);

        configStringKeys.add(getString(R.string.TranslateMessage));
        configValues.add(AppConfig.MESSAGE_SLIDE_ACTION_TRANSLATE);

        configStringKeys.add(getString(R.string.DirectShare));
        configValues.add(AppConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE);

        PopupHelper.show(configStringKeys, getString(R.string.NM_MsgSlideAction), configValues.indexOf(AppConfig.messageslideaction), getContext(), i -> {
            AppConfig.setMessageSlideAction(configValues.get(i));
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
