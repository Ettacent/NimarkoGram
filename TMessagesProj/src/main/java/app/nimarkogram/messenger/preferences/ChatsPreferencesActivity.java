/*
 * This file is part of NimarkoGram for Android.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 * Copyright Ettacent, 2026.
 */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.chats.NimarkoChatHelper2;
import app.nimarkogram.messenger.utils.VibrateUtils;
import app.nimarkogram.messenger.preferences.helpers.NimarkoAlertDialogSwitchers;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class ChatsPreferencesActivity extends NimarkoUniversalPreferencesActivity {

    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_CHAT_LIST = 1;
    private static final int PAGE_INPUT = 2;
    private static final int PAGE_GESTURES = 3;
    private static final int PAGE_MEDIA = 4;
    private static final int PAGE_REACTIONS = 5;
    private static final int PAGE_NOTIFICATIONS = 6;
    private static final int PAGE_TOOLS = 7;

    private static final int openChatListRow = 1_001;
    private static final int openInputRow = 1_002;
    private static final int openGesturesRow = 1_003;
    private static final int openMediaRow = 1_004;
    private static final int openReactionsRow = 1_005;
    private static final int openNotificationsRow = 1_006;
    private static final int openToolsRow = 1_007;

    private final int page;

    private static final int sortByUnreadRow = 1, unarchiveOnSwipeRow = 2, forwardWithoutAuthorRow = 3,
            customChatRow = 4, recentEmojisStickersRow = 5;

    private static final int pencilIconRow = 14, forwardDateRow = 15,
            sendAsChannelButtonRow = 17, chatMenuShortcutsRow = 19;

    private static final int doubleTapRow = 30, slideActionRow = 31, leftBottomBtnRow = 32, autoQuoteRow = 33,
            disableSwipeToNextRow = 34, deleteForAllRow = 35, directShareRow = 36, disableVibrationRow = 37,
            hideKbdSliderRow = 38, preReformRussianRow = 39, latexRenderingRow = 40,
            disableSendHintsRow = 41;

    private static final int largePhotosRow = 50, playVideoOnVolumeBtnRow = 51, autoPauseVideoRow = 52,
            gifSpoilersRow = 53, videoSeekSliderRow = 54;

    private static final int reactionsOverlayRow = 60, reactionAnimationRow = 61, premStickAnimRow = 62,
            premStickAutoplayRow = 63;

    private static final int notificationSoundRow = 70, vibrateInChatsRow = 71;

    private static final int messageMenuRow = 80, messageFilterRow = 81;

    public ChatsPreferencesActivity() {
        this(PAGE_OVERVIEW);
    }

    private ChatsPreferencesActivity(int page) {
        this.page = page;
    }

    public static ChatsPreferencesActivity forSetting(int itemId) {
        int targetPage = switch (itemId) {
            case sortByUnreadRow, unarchiveOnSwipeRow, customChatRow -> PAGE_CHAT_LIST;
            case recentEmojisStickersRow, pencilIconRow, forwardDateRow, sendAsChannelButtonRow,
                    autoQuoteRow, hideKbdSliderRow, preReformRussianRow, latexRenderingRow,
                    disableSendHintsRow -> PAGE_INPUT;
            case doubleTapRow, slideActionRow, leftBottomBtnRow, forwardWithoutAuthorRow,
                    disableSwipeToNextRow, deleteForAllRow, disableVibrationRow -> PAGE_GESTURES;
            case largePhotosRow, playVideoOnVolumeBtnRow, autoPauseVideoRow,
                    gifSpoilersRow, videoSeekSliderRow -> PAGE_MEDIA;
            case reactionsOverlayRow, reactionAnimationRow, premStickAnimRow,
                    premStickAutoplayRow -> PAGE_REACTIONS;
            case notificationSoundRow, vibrateInChatsRow -> PAGE_NOTIFICATIONS;
            case chatMenuShortcutsRow, directShareRow, messageMenuRow, messageFilterRow -> PAGE_TOOLS;
            default -> PAGE_OVERVIEW;
        };
        return new ChatsPreferencesActivity(targetPage);
    }

    @Override
    protected CharSequence getTitle() {
        return switch (page) {
            case PAGE_CHAT_LIST -> getString(R.string.NM_SettingsSectionChatList);
            case PAGE_INPUT -> getString(R.string.NM_SettingsSectionInputText);
            case PAGE_GESTURES -> getString(R.string.NM_SettingsSectionGesturesActions);
            case PAGE_MEDIA -> getString(R.string.NM_SettingsSectionMediaPlayback);
            case PAGE_REACTIONS -> getString(R.string.NM_SettingsSectionReactionsEffects);
            case PAGE_NOTIFICATIONS -> getString(R.string.Notifications);
            case PAGE_TOOLS -> getString(R.string.NM_SettingsSectionTools);
            default -> getString(R.string.FilterChats);
        };
    }

    @Override
    public View createView(Context context) {
        setMD3(true);
        return super.createView(context);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        switch (page) {
            case PAGE_CHAT_LIST -> {
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionDisplay)));
                fillChatList(items);
                items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryChatList)));
            }
            case PAGE_INPUT -> fillInputPage(items);
            case PAGE_GESTURES -> fillGesturesPage(items);
            case PAGE_MEDIA -> {
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionMediaPlayback)));
                fillMedia(items);
                items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryMediaPlayback)));
            }
            case PAGE_REACTIONS -> {
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionReactionsEffects)));
                fillReactions(items);
                items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryReactionsEffects)));
            }
            case PAGE_NOTIFICATIONS -> {
                items.add(UItem.asHeader(getString(R.string.Notifications)));
                fillNotifications(items);
                items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryChatNotifications)));
            }
            case PAGE_TOOLS -> {
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionTools)));
                fillTools(items);
            }
            default -> fillOverview(items);
        }
    }

    private void fillOverview(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionInterface)));
        items.add(asSettingsLink(openChatListRow, IconBackgroundColors.BLUE,
                R.drawable.msg_folders, getString(R.string.NM_SettingsSectionChatList),
                getString(R.string.NM_SettingsSummaryChatList)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.MessagesSettings)));
        items.add(asSettingsLink(openInputRow, IconBackgroundColors.PURPLE,
                R.drawable.msg_edit, getString(R.string.NM_SettingsSectionInputText),
                getString(R.string.NM_SettingsSummaryInputText)));
        items.add(asSettingsLink(openGesturesRow, IconBackgroundColors.CYAN,
                R.drawable.msg_actions, getString(R.string.NM_SettingsSectionGesturesActions),
                getString(R.string.NM_SettingsSummaryGesturesActions)));
        items.add(asSettingsLink(openMediaRow, IconBackgroundColors.ORANGE,
                R.drawable.msg_video, getString(R.string.NM_SettingsSectionMediaPlayback),
                getString(R.string.NM_SettingsSummaryMediaPlayback)));
        items.add(asSettingsLink(openReactionsRow, IconBackgroundColors.GREEN,
                R.drawable.msg_reactions, getString(R.string.NM_SettingsSectionReactionsEffects),
                getString(R.string.NM_SettingsSummaryReactionsEffects)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionNotificationsTools)));
        items.add(asSettingsLink(openNotificationsRow, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_notifications, getString(R.string.Notifications),
                getString(R.string.NM_SettingsSummaryChatNotifications)));
        items.add(asSettingsLink(openToolsRow, IconBackgroundColors.ORANGE_DEEP,
                R.drawable.msg_settings, getString(R.string.NM_SettingsSectionTools),
                getString(R.string.NM_SettingsSummaryChatTools)));
        items.add(UItem.asShadow(null));
    }

    private void fillInputPage(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionInterfaceInput)));
        items.add(asSettingsLink(recentEmojisStickersRow, IconBackgroundColors.PURPLE,
                R.drawable.msg_recent, getString(R.string.CP_Slider_RecentEmojisAndStickers),
                getString(R.string.NM_CH_RecentEmojisStickers_Desc)));
        items.add(SettingsHelper.asSwitchCG(sendAsChannelButtonRow,
                        getString(R.string.CP_HideSendAsChannel),
                        getString(R.string.CP_HideSendAsChannelDesc))
                .setChecked(NimarkoConfig.hideSendAsChannel));
        items.add(SettingsHelper.asSwitchCG(disableSendHintsRow,
                        getString(R.string.NM_DisableSendHints),
                        getString(R.string.NM_DisableSendHints_Desc))
                .setChecked(NimarkoConfig.disableSendHints));
        items.add(UItem.asHeader(getString(R.string.CP_HideKbdOnScroll)));
        items.add(UItem.asIntSlideView(1, 0, NimarkoConfig.hideKeyboardOnScrollIntensity, 10,
                val -> val == 0 ? getString(R.string.VibrationDisabled) : String.valueOf(val),
                NimarkoConfig::setHideKeyboardOnScrollIntensity).setId(hideKbdSliderRow));
        items.add(UItem.asShadow(getString(R.string.NM_SettingsDesc_HideKeyboardOnScroll)));

        items.add(UItem.asHeader(getString(R.string.MessagesSettings)));
        items.add(SettingsHelper.asSwitchCG(pencilIconRow, getString(R.string.AP_ShowPencilIcon))
                .setChecked(NimarkoConfig.showPencilIcon));
        items.add(SettingsHelper.asSwitchCG(forwardDateRow, getString(R.string.CP_ForwardMsgDate))
                .setChecked(NimarkoConfig.msgForwardDate));
        items.add(SettingsHelper.asSwitchCG(autoQuoteRow,
                        getString(R.string.CP_AutoQuoteReplies),
                        getString(R.string.CP_AutoQuoteReplies_Desc))
                .setChecked(NimarkoConfig.autoQuoteReplies));
        items.add(SettingsHelper.asSwitchCG(preReformRussianRow,
                        getString(R.string.NM_CP_PreReformRussian),
                        getString(R.string.NM_CP_PreReformRussian_Desc))
                .setChecked(NimarkoConfig.preReformRussian));
        items.add(SettingsHelper.asSwitchCG(latexRenderingRow,
                        getString(R.string.NM_CP_LatexRendering),
                        getString(R.string.NM_CP_LatexRendering_Desc))
                .setChecked(NimarkoConfig.latexRenderingEnabled));
        items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryInputText)));
    }

    private void fillGesturesPage(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionGesturesActions)));
        items.add(asSettingsValue(doubleTapRow, IconBackgroundColors.BLUE,
                R.drawable.msg_actions, getString(R.string.CP_DoubleTapAction), getDoubleTapActionValue()));
        items.add(asSettingsValue(slideActionRow, IconBackgroundColors.CYAN,
                R.drawable.msg_forward, getString(R.string.NM_MsgSlideAction), getSlideActionValue()));
        items.add(asSettingsValue(leftBottomBtnRow, IconBackgroundColors.ORANGE,
                R.drawable.msg_replace, getString(R.string.CP_LeftBottomButtonAction), getLeftBottomButtonValue()));
        items.add(SettingsHelper.asSwitchCG(disableSwipeToNextRow,
                        getString(R.string.CP_DisableSwipeToNext),
                        getString(R.string.CP_DisableSwipeToNext_Desc))
                .setChecked(NimarkoConfig.disableSwipeToNext));
        if (VibrateUtils.hasVibrator()) {
            items.add(SettingsHelper.asSwitchCG(disableVibrationRow, getString(R.string.CP_DisableVibration))
                    .setChecked(NimarkoConfig.disableVibration));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionActions)));
        items.add(SettingsHelper.asSwitchCG(forwardWithoutAuthorRow, getString(R.string.ForwardWithoutAuthor))
                .setChecked(NimarkoConfig.forwardWithoutAuthor));
        items.add(SettingsHelper.asSwitchCG(deleteForAllRow,
                        getString(R.string.CP_DeleteForAll),
                        getString(R.string.CP_DeleteForAll_Desc))
                .setChecked(NimarkoConfig.deleteForAll));
        items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryGesturesActions)));
    }

    private void fillChatList(ArrayList<UItem> items) {
        items.add(SettingsHelper.asSwitchCG(sortByUnreadRow, getString(R.string.CP_SortByUnread))
                .setChecked(NimarkoConfig.sortByUnread));
        items.add(SettingsHelper.asSwitchCG(unarchiveOnSwipeRow, getString(R.string.CP_UnarchiveOnSwipe))
                .setChecked(NimarkoConfig.unarchiveOnSwipe));
        items.add(SettingsHelper.asSwitchCG(customChatRow,
                        getString(R.string.EP_CustomChat),
                        getString(R.string.EP_CustomChat_Desc))
                .setChecked(NimarkoConfig.customChatForSavedMessages));
        if (NimarkoConfig.customChatForSavedMessages) {
            items.add(SettingsHelper.asCustomWithBackground(createUserCell()));
        }
    }

    private void fillMedia(ArrayList<UItem> items) {
        items.add(SettingsHelper.asSwitchCG(largePhotosRow, getString(R.string.EP_PhotosSize))
                .setChecked(NimarkoConfig.largePhotos));
        items.add(SettingsHelper.asSwitchCG(playVideoOnVolumeBtnRow,
                        getString(R.string.CP_PlayVideo),
                        getString(R.string.CP_PlayVideo_Desc))
                .setChecked(NimarkoConfig.playVideoOnVolume));
        items.add(SettingsHelper.asSwitchCG(autoPauseVideoRow,
                        getString(R.string.CP_AutoPauseVideo),
                        getString(R.string.CP_AutoPauseVideo_Desc))
                .setChecked(NimarkoConfig.autoPauseVideo));
        items.add(SettingsHelper.asSwitchCG(gifSpoilersRow, getString(R.string.NM_MSG_GifSpoilers))
                .setChecked(NimarkoConfig.gifSpoilers));
        items.add(UItem.asHeader(getString(R.string.CP_VideoSeekDuration)));
        items.add(UItem.asIntSlideView(1, 0, NimarkoConfig.videoSeekDuration, 25,
                val -> val == 0 ? getString(R.string.NM_WSB_Status_Off) : String.valueOf(val),
                NimarkoConfig::setVideoSeekDuration).setId(videoSeekSliderRow));
        items.add(UItem.asShadow(getString(R.string.NM_SettingsDesc_VideoSeek)));
    }

    private void fillReactions(ArrayList<UItem> items) {
        items.add(SettingsHelper.asSwitchCG(reactionsOverlayRow,
                        getString(R.string.CP_DisableReactionsOverlay),
                        getString(R.string.CP_DisableReactionsOverlay_Desc))
                .setChecked(NimarkoConfig.disableReactionsOverlay));
        items.add(SettingsHelper.asSwitchCG(reactionAnimationRow,
                        getString(R.string.CP_DisableReactionAnim),
                        getString(R.string.CP_DisableReactionAnim_Desc))
                .setChecked(NimarkoConfig.disableReactionAnim));
        items.add(SettingsHelper.asSwitchCG(premStickAnimRow,
                        getString(R.string.CP_DisablePremStickAnim),
                        getString(R.string.CP_DisablePremStickAnim_Desc))
                .setChecked(NimarkoConfig.disablePremStickAnim));
        items.add(SettingsHelper.asSwitchCG(premStickAutoplayRow,
                        getString(R.string.CP_DisablePremStickAutoPlay),
                        getString(R.string.CP_DisablePremStickAutoPlay_Desc))
                .setChecked(NimarkoConfig.disablePremStickAutoPlay));
    }

    private void fillNotifications(ArrayList<UItem> items) {
        items.add(asSettingsValue(notificationSoundRow, IconBackgroundColors.BLUE,
                R.drawable.msg_notifications, getString(R.string.NotificationsSound), getNotificationSoundValue()));
        if (VibrateUtils.hasVibrator()) {
            items.add(asSettingsValue(vibrateInChatsRow, IconBackgroundColors.PURPLE,
                    R.drawable.msg_noise_on, getString(R.string.CP_VibrateInChats), getVibrationValue()));
        }
    }

    private void fillTools(ArrayList<UItem> items) {
        items.add(asSettingsLink(chatMenuShortcutsRow, IconBackgroundColors.ORANGE,
                R.drawable.msg_work, getString(R.string.CP_ChatMenuShortcuts)));
        items.add(asSettingsLink(directShareRow, IconBackgroundColors.GREEN,
                R.drawable.msg_share, getString(R.string.DirectShare), getString(R.string.DirectShareInfo)));
        items.add(asSettingsLink(messageMenuRow, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_settings, getString(R.string.CP_MessageMenu),
                getString(R.string.NM_SettingsSummaryMessageMenu)));
        items.add(asSettingsLink(messageFilterRow, IconBackgroundColors.RED,
                R.drawable.msg_search, getString(R.string.CP_Message_Filtering),
                getString(R.string.NM_SettingsSummaryMessageFilters)));
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        final int id = item.id;
        if (id == openChatListRow) {
            presentFragment(new ChatsPreferencesActivity(PAGE_CHAT_LIST));
            return;
        } else if (id == openInputRow) {
            presentFragment(new ChatsPreferencesActivity(PAGE_INPUT));
            return;
        } else if (id == openGesturesRow) {
            presentFragment(new ChatsPreferencesActivity(PAGE_GESTURES));
            return;
        } else if (id == openMediaRow) {
            presentFragment(new ChatsPreferencesActivity(PAGE_MEDIA));
            return;
        } else if (id == openReactionsRow) {
            presentFragment(new ChatsPreferencesActivity(PAGE_REACTIONS));
            return;
        } else if (id == openNotificationsRow) {
            presentFragment(new ChatsPreferencesActivity(PAGE_NOTIFICATIONS));
            return;
        } else if (id == openToolsRow) {
            presentFragment(new ChatsPreferencesActivity(PAGE_TOOLS));
            return;
        }
        if (id == sortByUnreadRow) {
            NimarkoConfig.toggleSortByUnread();
            updateCheckState(view, NimarkoConfig.sortByUnread);
            MessagesController.getInstance(currentAccount).sortDialogs(null);
            getNotificationCenter().postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload);
        } else if (id == unarchiveOnSwipeRow) {
            NimarkoConfig.toggleUnarchiveOnSwipe();
            updateCheckState(view, NimarkoConfig.unarchiveOnSwipe);
        } else if (id == forwardWithoutAuthorRow) {
            NimarkoConfig.toggleForwardWithoutAuthor();
            updateCheckState(view, NimarkoConfig.forwardWithoutAuthor);
        } else if (id == customChatRow) {
            NimarkoConfig.toggleCustomChatForSavedMessages();
            updateCheckState(view, NimarkoConfig.customChatForSavedMessages);
            listView.adapter.update(true);
        } else if (id == recentEmojisStickersRow) {
            NimarkoAlertDialogSwitchers.showRecentEmojisAndStickers(this);
        } else if (id == disableSendHintsRow) {
            NimarkoConfig.toggleDisableSendHints();
            updateCheckState(view, NimarkoConfig.disableSendHints);

        } else if (id == pencilIconRow) {
            NimarkoConfig.toggleShowPencilIcon();
            updateCheckState(view, NimarkoConfig.showPencilIcon);
        } else if (id == forwardDateRow) {
            NimarkoConfig.toggleMsgForwardDate();
            updateCheckState(view, NimarkoConfig.msgForwardDate);
        } else if (id == sendAsChannelButtonRow) {
            NimarkoConfig.toggleHideSendAsChannel();
            updateCheckState(view, NimarkoConfig.hideSendAsChannel);
        } else if (id == chatMenuShortcutsRow) {
            showChatMenuItemsConfigurator(this);

        } else if (id == doubleTapRow) {
            showDoubleTapSelector(() -> SettingsHelper.updateButtonValue(view, getDoubleTapActionValue()));
        } else if (id == slideActionRow) {
            showSlideActionSelector(() -> SettingsHelper.updateButtonValue(view, getSlideActionValue()));
        } else if (id == leftBottomBtnRow) {
            showLeftBottomButtonSelector(() -> SettingsHelper.updateButtonValue(view, getLeftBottomButtonValue()));
        } else if (id == autoQuoteRow) {
            NimarkoConfig.toggleAutoQuoteReplies();
            updateCheckState(view, NimarkoConfig.autoQuoteReplies);
        } else if (id == preReformRussianRow) {
            NimarkoConfig.togglePreReformRussian();
            updateCheckState(view, NimarkoConfig.preReformRussian);
        } else if (id == latexRenderingRow) {
            NimarkoConfig.toggleLatexRendering();
            updateCheckState(view, NimarkoConfig.latexRenderingEnabled);
            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
        } else if (id == disableSwipeToNextRow) {
            NimarkoConfig.toggleDisableSwipeToNext();
            updateCheckState(view, NimarkoConfig.disableSwipeToNext);
        } else if (id == deleteForAllRow) {
            NimarkoConfig.toggleDeleteForAll();
            updateCheckState(view, NimarkoConfig.deleteForAll);
        } else if (id == directShareRow) {
            showDirectShareConfigurator(this);
        } else if (id == disableVibrationRow) {
            NimarkoConfig.toggleDisableVibration();
            updateCheckState(view, NimarkoConfig.disableVibration);
            showRestartBulletin();

        } else if (id == largePhotosRow) {
            NimarkoConfig.toggleLargePhotos();
            updateCheckState(view, NimarkoConfig.largePhotos);
            showRestartBulletin();
        } else if (id == playVideoOnVolumeBtnRow) {
            NimarkoConfig.togglePlayVideoOnVolume();
            updateCheckState(view, NimarkoConfig.playVideoOnVolume);
        } else if (id == autoPauseVideoRow) {
            NimarkoConfig.toggleAutoPauseVideo();
            updateCheckState(view, NimarkoConfig.autoPauseVideo);
        } else if (id == gifSpoilersRow) {
            NimarkoConfig.toggleGifSpoilers();
            updateCheckState(view, NimarkoConfig.gifSpoilers);

        } else if (id == reactionsOverlayRow) {
            NimarkoConfig.toggleDisableReactionsOverlay();
            updateCheckState(view, NimarkoConfig.disableReactionsOverlay);
            showRestartBulletin();
        } else if (id == reactionAnimationRow) {
            NimarkoConfig.toggleDisableReactionAnim();
            updateCheckState(view, NimarkoConfig.disableReactionAnim);
            showRestartBulletin();
        } else if (id == premStickAnimRow) {
            NimarkoConfig.toggleDisablePremStickAnim();
            updateCheckState(view, NimarkoConfig.disablePremStickAnim);
            showRestartBulletin();
        } else if (id == premStickAutoplayRow) {
            NimarkoConfig.toggleDisablePremStickAutoPlay();
            updateCheckState(view, NimarkoConfig.disablePremStickAutoPlay);
            showRestartBulletin();

        } else if (id == notificationSoundRow) {
            showNotificationSoundSelector(() -> {
                SettingsHelper.updateButtonValue(view, getNotificationSoundValue());
                int sel = NimarkoConfig.notificationSound;
                if (sel != NimarkoConfig.NOTIF_SOUND_DISABLE) {
                    int tone = (sel == NimarkoConfig.NOTIF_SOUND_IOS) ? R.raw.sound_in_ios : R.raw.sound_in;
                    try {
                        MediaPlayer mp = MediaPlayer.create(getContext(), tone);
                        if (mp != null) {
                            mp.setOnCompletionListener(MediaPlayer::release);
                            mp.start();
                        }
                    } catch (Exception ignored) {}
                }
                showRestartBulletin();
            });
        } else if (id == vibrateInChatsRow) {
            showVibrationSelector(() -> {
                try {
                    VibrateUtils.vibrateForChatMode(NimarkoConfig.vibrateInChats);
                } catch (Exception ignored) {}
                SettingsHelper.updateButtonValue(view, getVibrationValue());
            });

        } else if (id == messageMenuRow) {
            presentFragment(new MessageMenuPreferencesActivity());
        } else if (id == messageFilterRow) {
            presentFragment(new MessageFiltersPreferencesActivity());
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private UserCell createUserCell() {
        UserCell userCell = new UserCell(getContext(), 14, 0, false, true, getResourceProvider());

        userCell.addButton.setText(getString(R.string.Edit));
        userCell.addButton.setOnClickListener(view1 -> {
            if (getUserConfig().getCurrentUser() == null) {
                return;
            }
            Bundle args = new Bundle();
            args.putBoolean("onlySelect", true);
            args.putBoolean("cgPrefs", true);
            args.putBoolean("allowGlobalSearch", false);
            args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
            args.putBoolean("resetDelegate", false);
            args.putBoolean("closeFragment", true);
            DialogsActivity fragment = new DialogsActivity(args);
            fragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                long did = dids.get(0).dialogId;
                NimarkoConfig.setCustomSavedMessagesDialogId(currentAccount, did);
                fragment.finishFragment(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    View avatar = userCell.avatarImageView;
                    int[] loc = new int[2];
                    avatar.getLocationOnScreen(loc);
                    float cx = loc[0] + avatar.getWidth() / 2f;
                    float cy = loc[1] + avatar.getHeight() / 2f;
                    LaunchActivity.makeRipple(cx, cy, 5f);
                }
                listView.adapter.update(false);
                return true;
            });
            presentFragment(fragment);
        });

        long chatId = NimarkoConfig.getCustomSavedMessagesDialogId(currentAccount);
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-chatId);
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(chatId);

        StringBuilder status = new StringBuilder();
        status.append(getString(R.string.EP_CustomChat_Selected_Title));
        status.append(' ').append("\"").append(getString(R.string.SavedMessages)).append("\".");

        if (chatId == getUserConfig().clientUserId) {
            userCell.setData("saved_cg", getString(R.string.SavedMessages), "", 0);
        } else if (chat != null) {
            userCell.setData(chat, chat.title, status, 0);
        } else {
            userCell.setData(user, UserObject.getUserName(user), status, 0);
        }
        return userCell;
    }


    private String getDoubleTapActionValue() {
        return switch (NimarkoConfig.doubletapaction) {
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
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.Disable)); values.add(NimarkoConfig.DOUBLE_TAP_ACTION_NONE);
        keys.add(getString(R.string.Reactions)); values.add(NimarkoConfig.DOUBLE_TAP_ACTION_REACTION);
        keys.add(getString(R.string.Reply)); values.add(NimarkoConfig.DOUBLE_TAP_ACTION_REPLY);
        keys.add(getString(R.string.NM_ToSaved)); values.add(NimarkoConfig.DOUBLE_TAP_ACTION_SAVE);
        keys.add(getString(R.string.Edit)); values.add(NimarkoConfig.DOUBLE_TAP_ACTION_EDIT);
        keys.add(getString(R.string.TranslateMessage)); values.add(NimarkoConfig.DOUBLE_TAP_ACTION_TRANSLATE);
        keys.add(getString(R.string.NM_DoubleTap_EditOrReact)); values.add(NimarkoConfig.DOUBLE_TAP_ACTION_EDIT_OR_REACTION);
        PopupHelper.show(keys, getString(R.string.CP_DoubleTapAction), values.indexOf(NimarkoConfig.doubletapaction), getContext(), i -> {
            NimarkoConfig.setDoubleTapAction(values.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getSlideActionValue() {
        return switch (NimarkoConfig.messageslideaction) {
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_SAVE -> getString(R.string.NM_ToSaved);
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_TRANSLATE -> getString(R.string.TranslateMessage);
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE -> getString(R.string.DirectShare);
            default -> getString(R.string.Reply);
        };
    }

    private void showSlideActionSelector(Runnable runnable) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.Reply)); values.add(NimarkoConfig.MESSAGE_SLIDE_ACTION_REPLY);
        keys.add(getString(R.string.NM_ToSaved)); values.add(NimarkoConfig.MESSAGE_SLIDE_ACTION_SAVE);
        keys.add(getString(R.string.TranslateMessage)); values.add(NimarkoConfig.MESSAGE_SLIDE_ACTION_TRANSLATE);
        keys.add(getString(R.string.DirectShare)); values.add(NimarkoConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE);
        PopupHelper.show(keys, getString(R.string.NM_MsgSlideAction), values.indexOf(NimarkoConfig.messageslideaction), getContext(), i -> {
            NimarkoConfig.setMessageSlideAction(values.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getLeftBottomButtonValue() {
        return switch (NimarkoConfig.actionsBarLeftButton) {
            case NimarkoConfig.ACTIONS_LEFT_SAVE_MESSAGE -> getString(R.string.NM_ToSaved);
            case NimarkoConfig.ACTIONS_LEFT_DIRECT_SHARE -> getString(R.string.DirectShare);
            case NimarkoConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP -> getString(R.string.Forward) + " " + getString(R.string.NM_Without_Authorship);
            case NimarkoConfig.ACTIONS_LEFT_REPLY -> getString(R.string.Reply);
            default -> getString(R.string.Reply);
        };
    }

    private void showLeftBottomButtonSelector(Runnable runnable) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.Forward) + " " + getString(R.string.NM_Without_Authorship)); values.add(NimarkoConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP);
        keys.add(getString(R.string.Reply)); values.add(NimarkoConfig.ACTIONS_LEFT_REPLY);
        keys.add(getString(R.string.NM_ToSaved)); values.add(NimarkoConfig.ACTIONS_LEFT_SAVE_MESSAGE);
        keys.add(getString(R.string.DirectShare)); values.add(NimarkoConfig.ACTIONS_LEFT_DIRECT_SHARE);
        PopupHelper.show(keys, getString(R.string.CP_LeftBottomButtonAction), values.indexOf(NimarkoConfig.actionsBarLeftButton), getContext(), i -> {
            NimarkoConfig.setActionsBarLeftButton(values.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private void showDirectShareConfigurator(BaseFragment fragment) {
        List<MenuItemConfig> menuItems = Arrays.asList(
                new MenuItemConfig(getString(R.string.RepostToStory), R.drawable.large_repost_story,
                        () -> NimarkoConfig.shareDrawStoryButton, () -> NimarkoConfig.toggleShareDrawStoryButton(), true, false),
                new MenuItemConfig(getString(R.string.FilterChats), 0,
                        () -> NimarkoConfig.usersDrawShareButton, () -> NimarkoConfig.toggleUsersDrawShareButton(), false, false),
                new MenuItemConfig(getString(R.string.FilterGroups), 0,
                        () -> NimarkoConfig.supergroupsDrawShareButton, () -> NimarkoConfig.toggleSupergroupsDrawShareButton(), false, false),
                new MenuItemConfig(getString(R.string.FilterChannels), 0,
                        () -> NimarkoConfig.channelsDrawShareButton, () -> NimarkoConfig.toggleChannelsDrawShareButton(), false, false),
                new MenuItemConfig(getString(R.string.FilterBots), 0,
                        () -> NimarkoConfig.botsDrawShareButton, () -> NimarkoConfig.toggleBotsDrawShareButton(), false, false),
                new MenuItemConfig(getString(R.string.StickersName), 0,
                        () -> NimarkoConfig.stickersDrawShareButton, () -> NimarkoConfig.toggleStickersDrawShareButton(), false, false)
        );
        handleMenuAlert(getString(R.string.DirectShare), menuItems, fragment);
    }


    public static void showChatMenuItemsConfigurator(BaseFragment fragment) {
        List<MenuItemConfig> menuItems = Arrays.asList(
                new MenuItemConfig(getString(R.string.NM_JumpToBeginning), R.drawable.ic_upward_solar,
                        () -> NimarkoConfig.chatShortcutJumpToBegin, () -> NimarkoConfig.toggleChatShortcutJumpToBegin(), false, false),
                new MenuItemConfig(getString(R.string.NM_DeleteAllFromSelf), R.drawable.msg_delete,
                        () -> NimarkoConfig.shortcutDeleteAll, () -> NimarkoConfig.toggleShortcutDeleteAll(), false, false),
                new MenuItemConfig(getString(R.string.SavedMessages), R.drawable.msg_saved,
                        () -> NimarkoConfig.chatShortcutSavedMessages, () -> NimarkoConfig.toggleChatShortcutSavedMessages(), false, false),
                new MenuItemConfig("Telegram Browser", R.drawable.msg_language,
                        () -> NimarkoConfig.shortcutBrowser, () -> NimarkoConfig.toggleShortcutBrowser(), true, false),
                new MenuItemConfig(getString(R.string.CP_AdminActions), R.drawable.msg_admins,
                        () -> false, () -> showChatAdminItemsConfigurator(fragment), false, true)
        );
        handleMenuAlert(getString(R.string.CP_ChatMenuShortcuts), menuItems, fragment);
    }

    private static void showChatAdminItemsConfigurator(BaseFragment fragment) {
        List<MenuItemConfig> menuItems = Arrays.asList(
                new MenuItemConfig(getString(R.string.Reactions), R.drawable.msg_reactions2,
                        () -> NimarkoConfig.adminsReactions, () -> NimarkoConfig.toggleAdminsReactions(), false, false),
                new MenuItemConfig(getString(R.string.ChannelPermissions), R.drawable.msg_permissions,
                        () -> NimarkoConfig.adminsPermissions, () -> NimarkoConfig.toggleAdminsPermissions(), false, false),
                new MenuItemConfig(getString(R.string.ChannelAdministrators), R.drawable.msg_admins,
                        () -> NimarkoConfig.adminsAdministrators, () -> NimarkoConfig.toggleAdminsAdministrators(), false, false),
                new MenuItemConfig(getString(R.string.ChannelMembers), R.drawable.msg_groups,
                        () -> NimarkoConfig.adminsMembers, () -> NimarkoConfig.toggleAdminsMembers(), false, false),
                new MenuItemConfig(getString(R.string.StatisticsAndBoosts), R.drawable.msg_stats,
                        () -> NimarkoConfig.adminsStatistics, () -> NimarkoConfig.toggleAdminsStatistics(), false, false),
                new MenuItemConfig(getString(R.string.EventLog), R.drawable.msg_log,
                        () -> NimarkoConfig.adminsRecentActions, () -> NimarkoConfig.toggleAdminsRecentActions(), false, false)
        );
        handleMenuAlert(getString(R.string.CP_AdminActions), menuItems, fragment);
    }


    private String getNotificationSoundValue() {
        return switch (NimarkoConfig.notificationSound) {
            case NimarkoConfig.NOTIF_SOUND_DEFAULT -> getString(R.string.Default);
            case NimarkoConfig.NOTIF_SOUND_IOS -> "iOS";
            default -> getString(R.string.PopupDisabled);
        };
    }

    private void showNotificationSoundSelector(Runnable runnable) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.PopupDisabled)); values.add(NimarkoConfig.NOTIF_SOUND_DISABLE);
        keys.add(getString(R.string.Default)); values.add(NimarkoConfig.NOTIF_SOUND_DEFAULT);
        keys.add("iOS"); values.add(NimarkoConfig.NOTIF_SOUND_IOS);
        PopupHelper.show(keys, getString(R.string.NotificationsSound), values.indexOf(NimarkoConfig.notificationSound), getContext(), i -> {
            NimarkoConfig.setNotificationSound(values.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getVibrationValue() {
        return switch (NimarkoConfig.vibrateInChats) {
            case NimarkoConfig.VIBRATE_CLICK -> getString(R.string.NM_Vibrate_Click);
            case NimarkoConfig.VIBRATE_WAVE -> getString(R.string.NM_Vibrate_Wave);
            case NimarkoConfig.VIBRATE_KEYBOARD -> getString(R.string.NM_Vibrate_Keyboard);
            case NimarkoConfig.VIBRATE_LONG -> getString(R.string.NM_Vibrate_Long);
            default -> getString(R.string.NM_Vibrate_Off);
        };
    }

    private void showVibrationSelector(Runnable runnable) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.NM_Vibrate_Off)); values.add(NimarkoConfig.VIBRATE_DISABLE);
        keys.add(getString(R.string.NM_Vibrate_Click)); values.add(NimarkoConfig.VIBRATE_CLICK);
        keys.add(getString(R.string.NM_Vibrate_Wave)); values.add(NimarkoConfig.VIBRATE_WAVE);
        keys.add(getString(R.string.NM_Vibrate_Keyboard)); values.add(NimarkoConfig.VIBRATE_KEYBOARD);
        keys.add(getString(R.string.NM_Vibrate_Long)); values.add(NimarkoConfig.VIBRATE_LONG);
        PopupHelper.show(keys, getString(R.string.CP_VibrateInChats), values.indexOf(NimarkoConfig.vibrateInChats), getContext(), i -> {
            NimarkoConfig.setVibrateInChats(values.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private static void handleMenuAlert(String title, List<MenuItemConfig> items, BaseFragment fragment) {
        ArrayList<String> prefTitle = new ArrayList<>();
        ArrayList<Integer> prefIcon = new ArrayList<>();
        ArrayList<Boolean> prefCheck = new ArrayList<>();
        ArrayList<Boolean> prefCheckInvisible = new ArrayList<>();
        ArrayList<Boolean> prefDivider = new ArrayList<>();
        ArrayList<Runnable> clickListener = new ArrayList<>();

        for (MenuItemConfig item : items) {
            prefTitle.add(item.title);
            prefIcon.add(item.iconRes);
            prefCheck.add(item.isChecked.get());
            prefCheckInvisible.add(item.isCheckInvisible);
            prefDivider.add(item.divider);
            clickListener.add(item.toggle);
        }

        PopupHelper.showSwitchAlert(title, fragment, prefTitle, prefIcon, prefCheck, prefCheckInvisible,
                null, prefDivider, clickListener, null);
    }

    public static class MenuItemConfig {
        String title;
        int iconRes;
        Supplier<Boolean> isChecked;
        Runnable toggle;
        boolean divider;
        boolean isCheckInvisible;

        MenuItemConfig(String title, int iconRes, Supplier<Boolean> isChecked, Runnable toggle, boolean divider, boolean isCheckInvisible) {
            this.title = title;
            this.iconRes = iconRes;
            this.isChecked = isChecked;
            this.toggle = toggle;
            this.divider = divider;
            this.isCheckInvisible = isCheckInvisible;
        }
    }
}
