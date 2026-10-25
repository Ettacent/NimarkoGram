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

import org.telegram.messenger.NotificationCenter;
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

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.utils.chats.ChatHelper2;
import app.nimarkogram.messenger.utils.VibrateUtils;
import app.nimarkogram.messenger.preferences.helpers.AlertDialogSwitchers;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class ChatsPreferencesActivity extends UniversalPreferencesActivity {

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
            gifSpoilersRow = 53, videoSeekSliderRow = 54, multiMicrophoneRow = 55, disableVoiceAutoNextRow = 56;

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
                    gifSpoilersRow, videoSeekSliderRow, multiMicrophoneRow, disableVoiceAutoNextRow -> PAGE_MEDIA;
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
                items.add(UItem.asShadow(null));
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
                R.drawable.msg_recent, getString(R.string.NM_Slider_RecentEmojisAndStickers),
                getString(R.string.NM_CH_RecentEmojisStickers_Desc)));
        items.add(SettingsHelper.asSwitchCG(sendAsChannelButtonRow,
                        getString(R.string.NM_HideSendAsChannel),
                        getString(R.string.NM_HideSendAsChannelDesc))
                .setChecked(AppConfig.hideSendAsChannel));
        items.add(SettingsHelper.asSwitchCG(disableSendHintsRow,
                        getString(R.string.NM_DisableSendHints),
                        getString(R.string.NM_DisableSendHints_Desc))
                .setChecked(AppConfig.disableSendHints));
        items.add(UItem.asHeader(getString(R.string.NM_HideKbdOnScroll)));
        items.add(UItem.asIntSlideView(1, 0, AppConfig.hideKeyboardOnScrollIntensity, 10,
                val -> val == 0 ? getString(R.string.VibrationDisabled) : String.valueOf(val),
                AppConfig::setHideKeyboardOnScrollIntensity).setId(hideKbdSliderRow));
        items.add(UItem.asShadow(getString(R.string.NM_SettingsDesc_HideKeyboardOnScroll)));

        items.add(UItem.asHeader(getString(R.string.MessagesSettings)));
        items.add(SettingsHelper.asSwitchCG(pencilIconRow, getString(R.string.AP_ShowPencilIcon))
                .setChecked(AppConfig.showPencilIcon));
        items.add(SettingsHelper.asSwitchCG(forwardDateRow, getString(R.string.NM_ForwardMsgDate))
                .setChecked(AppConfig.msgForwardDate));
        items.add(SettingsHelper.asSwitchCG(autoQuoteRow,
                        getString(R.string.NM_AutoQuoteReplies),
                        getString(R.string.NM_AutoQuoteReplies_Desc))
                .setChecked(AppConfig.autoQuoteReplies));
        items.add(SettingsHelper.asSwitchCG(preReformRussianRow,
                        getString(R.string.NM_PreReformRussian),
                        getString(R.string.NM_PreReformRussian_Desc))
                .setChecked(AppConfig.preReformRussian));
        items.add(SettingsHelper.asSwitchCG(latexRenderingRow,
                        getString(R.string.NM_LatexRendering),
                        getString(R.string.NM_LatexRendering_Desc))
                .setChecked(AppConfig.latexRenderingEnabled));
        items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryInputText)));
    }

    private void fillGesturesPage(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionGesturesActions)));
        items.add(asSettingsValue(doubleTapRow, IconBackgroundColors.BLUE,
                R.drawable.msg_actions, getString(R.string.NM_DoubleTapAction), getDoubleTapActionValue()));
        items.add(asSettingsValue(slideActionRow, IconBackgroundColors.CYAN,
                R.drawable.msg_forward, getString(R.string.NM_MsgSlideAction), getSlideActionValue()));
        items.add(asSettingsValue(leftBottomBtnRow, IconBackgroundColors.ORANGE,
                R.drawable.msg_replace, getString(R.string.NM_LeftBottomButtonAction), getLeftBottomButtonValue()));
        items.add(SettingsHelper.asSwitchCG(disableSwipeToNextRow,
                        getString(R.string.NM_DisableSwipeToNext),
                        getString(R.string.NM_DisableSwipeToNext_Desc))
                .setChecked(AppConfig.disableSwipeToNext));
        if (VibrateUtils.hasVibrator()) {
            items.add(SettingsHelper.asSwitchCG(disableVibrationRow, getString(R.string.NM_DisableVibration))
                    .setChecked(AppConfig.disableVibration));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionActions)));
        items.add(SettingsHelper.asSwitchCG(forwardWithoutAuthorRow, getString(R.string.ForwardWithoutAuthor))
                .setChecked(AppConfig.forwardWithoutAuthor));
        items.add(SettingsHelper.asSwitchCG(deleteForAllRow,
                        getString(R.string.NM_DeleteForAll),
                        getString(R.string.NM_DeleteForAll_Desc))
                .setChecked(AppConfig.deleteForAll));
        items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryGesturesActions)));
    }

    private void fillChatList(ArrayList<UItem> items) {
        items.add(SettingsHelper.asSwitchCG(sortByUnreadRow, getString(R.string.NM_SortByUnread))
                .setChecked(AppConfig.sortByUnread));
        items.add(SettingsHelper.asSwitchCG(unarchiveOnSwipeRow, getString(R.string.NM_UnarchiveOnSwipe))
                .setChecked(AppConfig.unarchiveOnSwipe));
        items.add(SettingsHelper.asSwitchCG(customChatRow,
                        getString(R.string.EP_CustomChat),
                        getString(R.string.EP_CustomChat_Desc))
                .setChecked(AppConfig.customChatForSavedMessages));
        if (AppConfig.customChatForSavedMessages) {
            items.add(SettingsHelper.asCustomWithBackground(createUserCell()));
        }
    }

    private void fillMedia(ArrayList<UItem> items) {
        items.add(SettingsHelper.asSwitchCG(disableVoiceAutoNextRow,
                        getString(R.string.NimarkoDisableVMAutoplay),
                        getString(R.string.NM_DisableVoiceAutoNext_Desc))
                .setChecked(AppConfig.disableVoiceMessagesAutoNext));
        items.add(SettingsHelper.asSwitchCG(multiMicrophoneRow,
                        getString(R.string.NM_CAM_MultiMicrophone), getString(R.string.NM_CAM_MultiMicrophoneDesc))
                .setChecked(AppConfig.cameraXMultiMicrophone));
        items.add(SettingsHelper.asSwitchCG(largePhotosRow, getString(R.string.EP_PhotosSize))
                .setChecked(AppConfig.largePhotos));
        items.add(SettingsHelper.asSwitchCG(playVideoOnVolumeBtnRow,
                        getString(R.string.NM_PlayVideo),
                        getString(R.string.NM_PlayVideo_Desc))
                .setChecked(AppConfig.playVideoOnVolume));
        items.add(SettingsHelper.asSwitchCG(autoPauseVideoRow,
                        getString(R.string.NM_AutoPauseVideo),
                        getString(R.string.NM_AutoPauseVideo_Desc))
                .setChecked(AppConfig.autoPauseVideo));
        items.add(SettingsHelper.asSwitchCG(gifSpoilersRow, getString(R.string.NM_MSG_GifSpoilers))
                .setChecked(AppConfig.gifSpoilers));
        items.add(UItem.asHeader(getString(R.string.NM_VideoSeekDuration)));
        items.add(UItem.asIntSlideView(1, 0, AppConfig.videoSeekDuration, 25,
                val -> val == 0 ? getString(R.string.NM_WSB_Status_Off) : String.valueOf(val),
                AppConfig::setVideoSeekDuration).setId(videoSeekSliderRow));
        items.add(UItem.asShadow(getString(R.string.NM_SettingsDesc_VideoSeek)));
    }

    private void fillReactions(ArrayList<UItem> items) {
        items.add(SettingsHelper.asSwitchCG(reactionsOverlayRow,
                        getString(R.string.NM_DisableReactionsOverlay),
                        getString(R.string.NM_DisableReactionsOverlay_Desc))
                .setChecked(AppConfig.disableReactionsOverlay));
        items.add(SettingsHelper.asSwitchCG(reactionAnimationRow,
                        getString(R.string.NM_DisableReactionAnim),
                        getString(R.string.NM_DisableReactionAnim_Desc))
                .setChecked(AppConfig.disableReactionAnim));
        items.add(SettingsHelper.asSwitchCG(premStickAnimRow,
                        getString(R.string.NM_DisablePremStickAnim),
                        getString(R.string.NM_DisablePremStickAnim_Desc))
                .setChecked(AppConfig.disablePremStickAnim));
        items.add(SettingsHelper.asSwitchCG(premStickAutoplayRow,
                        getString(R.string.NM_DisablePremStickAutoPlay),
                        getString(R.string.NM_DisablePremStickAutoPlay_Desc))
                .setChecked(AppConfig.disablePremStickAutoPlay));
    }

    private void fillNotifications(ArrayList<UItem> items) {
        items.add(asSettingsValue(notificationSoundRow, IconBackgroundColors.BLUE,
                R.drawable.msg_notifications, getString(R.string.NotificationsSound), getNotificationSoundValue()));
        if (VibrateUtils.hasVibrator()) {
            items.add(asSettingsValue(vibrateInChatsRow, IconBackgroundColors.PURPLE,
                    R.drawable.msg_noise_on, getString(R.string.NM_VibrateInChats), getVibrationValue()));
        }
    }

    private void fillTools(ArrayList<UItem> items) {
        items.add(asSettingsLink(chatMenuShortcutsRow, IconBackgroundColors.ORANGE,
                R.drawable.msg_work, getString(R.string.NM_ChatMenuShortcuts)));
        items.add(asSettingsLink(directShareRow, IconBackgroundColors.GREEN,
                R.drawable.msg_share, getString(R.string.DirectShare), getString(R.string.DirectShareInfo)));
        items.add(asSettingsLink(messageMenuRow, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_settings, getString(R.string.NM_MessageMenu),
                getString(R.string.NM_SettingsSummaryMessageMenu)));
        items.add(asSettingsLink(messageFilterRow, IconBackgroundColors.RED,
                R.drawable.msg_search, getString(R.string.NM_Message_Filtering),
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
            AppConfig.toggleSortByUnread();
            updateCheckState(view, AppConfig.sortByUnread);
            MessagesController.getInstance(currentAccount).sortDialogs(null);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        } else if (id == unarchiveOnSwipeRow) {
            AppConfig.toggleUnarchiveOnSwipe();
            updateCheckState(view, AppConfig.unarchiveOnSwipe);
        } else if (id == forwardWithoutAuthorRow) {
            AppConfig.toggleForwardWithoutAuthor();
            updateCheckState(view, AppConfig.forwardWithoutAuthor);
        } else if (id == customChatRow) {
            AppConfig.toggleCustomChatForSavedMessages();
            item.checked = AppConfig.customChatForSavedMessages;
            updateCheckState(view, AppConfig.customChatForSavedMessages);
            updateItemsAfterToggle();
        } else if (id == recentEmojisStickersRow) {
            AlertDialogSwitchers.showRecentEmojisAndStickers(this);
        } else if (id == disableSendHintsRow) {
            AppConfig.toggleDisableSendHints();
            updateCheckState(view, AppConfig.disableSendHints);

        } else if (id == pencilIconRow) {
            AppConfig.toggleShowPencilIcon();
            updateCheckState(view, AppConfig.showPencilIcon);
        } else if (id == forwardDateRow) {
            AppConfig.toggleMsgForwardDate();
            updateCheckState(view, AppConfig.msgForwardDate);
        } else if (id == sendAsChannelButtonRow) {
            AppConfig.toggleHideSendAsChannel();
            updateCheckState(view, AppConfig.hideSendAsChannel);
        } else if (id == chatMenuShortcutsRow) {
            showChatMenuItemsConfigurator(this);

        } else if (id == doubleTapRow) {
            showDoubleTapSelector(() -> SettingsHelper.updateButtonValue(view, getDoubleTapActionValue()));
        } else if (id == slideActionRow) {
            showSlideActionSelector(() -> SettingsHelper.updateButtonValue(view, getSlideActionValue()));
        } else if (id == leftBottomBtnRow) {
            showLeftBottomButtonSelector(() -> SettingsHelper.updateButtonValue(view, getLeftBottomButtonValue()));
        } else if (id == autoQuoteRow) {
            AppConfig.toggleAutoQuoteReplies();
            updateCheckState(view, AppConfig.autoQuoteReplies);
        } else if (id == preReformRussianRow) {
            AppConfig.togglePreReformRussian();
            updateCheckState(view, AppConfig.preReformRussian);
        } else if (id == latexRenderingRow) {
            AppConfig.toggleLatexRendering();
            updateCheckState(view, AppConfig.latexRenderingEnabled);
            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
        } else if (id == disableSwipeToNextRow) {
            AppConfig.toggleDisableSwipeToNext();
            updateCheckState(view, AppConfig.disableSwipeToNext);
        } else if (id == deleteForAllRow) {
            AppConfig.toggleDeleteForAll();
            updateCheckState(view, AppConfig.deleteForAll);
        } else if (id == directShareRow) {
            showDirectShareConfigurator(this);
        } else if (id == disableVibrationRow) {
            AppConfig.toggleDisableVibration();
            updateCheckState(view, AppConfig.disableVibration);
            showRestartBulletin();

        } else if (id == disableVoiceAutoNextRow) {
            AppConfig.toggleDisableVoiceMessagesAutoNext();
            item.checked = AppConfig.disableVoiceMessagesAutoNext;
            updateCheckState(view, item.checked);
        } else if (id == multiMicrophoneRow) {
            AppConfig.toggleCameraXMultiMicrophone();
            item.checked = AppConfig.cameraXMultiMicrophone;
            updateCheckState(view, item.checked);
        } else if (id == largePhotosRow) {
            AppConfig.toggleLargePhotos();
            updateCheckState(view, AppConfig.largePhotos);
            showRestartBulletin();
        } else if (id == playVideoOnVolumeBtnRow) {
            AppConfig.togglePlayVideoOnVolume();
            updateCheckState(view, AppConfig.playVideoOnVolume);
        } else if (id == autoPauseVideoRow) {
            AppConfig.toggleAutoPauseVideo();
            updateCheckState(view, AppConfig.autoPauseVideo);
        } else if (id == gifSpoilersRow) {
            AppConfig.toggleGifSpoilers();
            updateCheckState(view, AppConfig.gifSpoilers);

        } else if (id == reactionsOverlayRow) {
            AppConfig.toggleDisableReactionsOverlay();
            updateCheckState(view, AppConfig.disableReactionsOverlay);
            showRestartBulletin();
        } else if (id == reactionAnimationRow) {
            AppConfig.toggleDisableReactionAnim();
            updateCheckState(view, AppConfig.disableReactionAnim);
            showRestartBulletin();
        } else if (id == premStickAnimRow) {
            AppConfig.toggleDisablePremStickAnim();
            updateCheckState(view, AppConfig.disablePremStickAnim);
            showRestartBulletin();
        } else if (id == premStickAutoplayRow) {
            AppConfig.toggleDisablePremStickAutoPlay();
            updateCheckState(view, AppConfig.disablePremStickAutoPlay);
            showRestartBulletin();

        } else if (id == notificationSoundRow) {
            showNotificationSoundSelector(() -> {
                SettingsHelper.updateButtonValue(view, getNotificationSoundValue());
                int sel = AppConfig.notificationSound;
                if (sel != AppConfig.NOTIF_SOUND_DISABLE) {
                    int tone = (sel == AppConfig.NOTIF_SOUND_IOS) ? R.raw.sound_in_ios : R.raw.sound_in;
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
                    VibrateUtils.vibrateForChatMode(AppConfig.vibrateInChats);
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
                AppConfig.setCustomSavedMessagesDialogId(currentAccount, did);
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

        long chatId = AppConfig.getCustomSavedMessagesDialogId(currentAccount);
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
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.Disable)); values.add(AppConfig.DOUBLE_TAP_ACTION_NONE);
        keys.add(getString(R.string.Reactions)); values.add(AppConfig.DOUBLE_TAP_ACTION_REACTION);
        keys.add(getString(R.string.Reply)); values.add(AppConfig.DOUBLE_TAP_ACTION_REPLY);
        keys.add(getString(R.string.NM_ToSaved)); values.add(AppConfig.DOUBLE_TAP_ACTION_SAVE);
        keys.add(getString(R.string.Edit)); values.add(AppConfig.DOUBLE_TAP_ACTION_EDIT);
        keys.add(getString(R.string.TranslateMessage)); values.add(AppConfig.DOUBLE_TAP_ACTION_TRANSLATE);
        keys.add(getString(R.string.NM_DoubleTap_EditOrReact)); values.add(AppConfig.DOUBLE_TAP_ACTION_EDIT_OR_REACTION);
        PopupHelper.show(keys, getString(R.string.NM_DoubleTapAction), values.indexOf(AppConfig.doubletapaction), getContext(), i -> {
            AppConfig.setDoubleTapAction(values.get(i));
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
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.Reply)); values.add(AppConfig.MESSAGE_SLIDE_ACTION_REPLY);
        keys.add(getString(R.string.NM_ToSaved)); values.add(AppConfig.MESSAGE_SLIDE_ACTION_SAVE);
        keys.add(getString(R.string.TranslateMessage)); values.add(AppConfig.MESSAGE_SLIDE_ACTION_TRANSLATE);
        keys.add(getString(R.string.DirectShare)); values.add(AppConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE);
        PopupHelper.show(keys, getString(R.string.NM_MsgSlideAction), values.indexOf(AppConfig.messageslideaction), getContext(), i -> {
            AppConfig.setMessageSlideAction(values.get(i));
            if (runnable != null) runnable.run();
        });
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
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.Forward) + " " + getString(R.string.NM_Without_Authorship)); values.add(AppConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP);
        keys.add(getString(R.string.Reply)); values.add(AppConfig.ACTIONS_LEFT_REPLY);
        keys.add(getString(R.string.NM_ToSaved)); values.add(AppConfig.ACTIONS_LEFT_SAVE_MESSAGE);
        keys.add(getString(R.string.DirectShare)); values.add(AppConfig.ACTIONS_LEFT_DIRECT_SHARE);
        PopupHelper.show(keys, getString(R.string.NM_LeftBottomButtonAction), values.indexOf(AppConfig.actionsBarLeftButton), getContext(), i -> {
            AppConfig.setActionsBarLeftButton(values.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private void showDirectShareConfigurator(BaseFragment fragment) {
        List<MenuItemConfig> menuItems = Arrays.asList(
                new MenuItemConfig(getString(R.string.RepostToStory), R.drawable.large_repost_story,
                        () -> AppConfig.shareDrawStoryButton, () -> AppConfig.toggleShareDrawStoryButton(), true, false),
                new MenuItemConfig(getString(R.string.FilterChats), 0,
                        () -> AppConfig.usersDrawShareButton, () -> AppConfig.toggleUsersDrawShareButton(), false, false),
                new MenuItemConfig(getString(R.string.FilterGroups), 0,
                        () -> AppConfig.supergroupsDrawShareButton, () -> AppConfig.toggleSupergroupsDrawShareButton(), false, false),
                new MenuItemConfig(getString(R.string.FilterChannels), 0,
                        () -> AppConfig.channelsDrawShareButton, () -> AppConfig.toggleChannelsDrawShareButton(), false, false),
                new MenuItemConfig(getString(R.string.FilterBots), 0,
                        () -> AppConfig.botsDrawShareButton, () -> AppConfig.toggleBotsDrawShareButton(), false, false),
                new MenuItemConfig(getString(R.string.StickersName), 0,
                        () -> AppConfig.stickersDrawShareButton, () -> AppConfig.toggleStickersDrawShareButton(), false, false)
        );
        handleMenuAlert(getString(R.string.DirectShare), menuItems, fragment);
    }

    public static void showChatMenuItemsConfigurator(BaseFragment fragment) {
        List<MenuItemConfig> menuItems = Arrays.asList(
                new MenuItemConfig(getString(R.string.NM_JumpToBeginning), R.drawable.msg_go_up,
                        () -> AppConfig.chatShortcutJumpToBegin, () -> AppConfig.toggleChatShortcutJumpToBegin(), false, false),
                new MenuItemConfig(getString(R.string.NM_DeleteAllFromSelf), R.drawable.msg_delete,
                        () -> AppConfig.shortcutDeleteAll, () -> AppConfig.toggleShortcutDeleteAll(), false, false),
                new MenuItemConfig(getString(R.string.SavedMessages), R.drawable.msg_saved,
                        () -> AppConfig.chatShortcutSavedMessages, () -> AppConfig.toggleChatShortcutSavedMessages(), false, false),
                new MenuItemConfig(getString(R.string.NM_CMS_TelegramBrowser), R.drawable.msg_language,
                        () -> AppConfig.shortcutBrowser, () -> AppConfig.toggleShortcutBrowser(), true, false),
                new MenuItemConfig(getString(R.string.NM_AdminActions), R.drawable.msg_admins,
                        () -> false, () -> showChatAdminItemsConfigurator(fragment), false, true)
        );
        handleMenuAlert(getString(R.string.NM_ChatMenuShortcuts), menuItems, fragment);
    }

    private static void showChatAdminItemsConfigurator(BaseFragment fragment) {
        List<MenuItemConfig> menuItems = Arrays.asList(
                new MenuItemConfig(getString(R.string.Reactions), R.drawable.msg_reactions2,
                        () -> AppConfig.adminsReactions, () -> AppConfig.toggleAdminsReactions(), false, false),
                new MenuItemConfig(getString(R.string.ChannelPermissions), R.drawable.msg_permissions,
                        () -> AppConfig.adminsPermissions, () -> AppConfig.toggleAdminsPermissions(), false, false),
                new MenuItemConfig(getString(R.string.ChannelAdministrators), R.drawable.msg_admins,
                        () -> AppConfig.adminsAdministrators, () -> AppConfig.toggleAdminsAdministrators(), false, false),
                new MenuItemConfig(getString(R.string.ChannelMembers), R.drawable.msg_groups,
                        () -> AppConfig.adminsMembers, () -> AppConfig.toggleAdminsMembers(), false, false),
                new MenuItemConfig(getString(R.string.StatisticsAndBoosts), R.drawable.msg_stats,
                        () -> AppConfig.adminsStatistics, () -> AppConfig.toggleAdminsStatistics(), false, false),
                new MenuItemConfig(getString(R.string.EventLog), R.drawable.msg_log,
                        () -> AppConfig.adminsRecentActions, () -> AppConfig.toggleAdminsRecentActions(), false, false)
        );
        handleMenuAlert(getString(R.string.NM_AdminActions), menuItems, fragment);
    }

    private String getNotificationSoundValue() {
        return switch (AppConfig.notificationSound) {
            case AppConfig.NOTIF_SOUND_DEFAULT -> getString(R.string.Default);
            case AppConfig.NOTIF_SOUND_IOS -> "iOS";
            default -> getString(R.string.PopupDisabled);
        };
    }

    private void showNotificationSoundSelector(Runnable runnable) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.PopupDisabled)); values.add(AppConfig.NOTIF_SOUND_DISABLE);
        keys.add(getString(R.string.Default)); values.add(AppConfig.NOTIF_SOUND_DEFAULT);
        keys.add("iOS"); values.add(AppConfig.NOTIF_SOUND_IOS);
        PopupHelper.show(keys, getString(R.string.NotificationsSound), values.indexOf(AppConfig.notificationSound), getContext(), i -> {
            AppConfig.setNotificationSound(values.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getVibrationValue() {
        return switch (AppConfig.vibrateInChats) {
            case AppConfig.VIBRATE_CLICK -> getString(R.string.NM_Vibrate_Click);
            case AppConfig.VIBRATE_WAVE -> getString(R.string.NM_Vibrate_Wave);
            case AppConfig.VIBRATE_KEYBOARD -> getString(R.string.NM_Vibrate_Keyboard);
            case AppConfig.VIBRATE_LONG -> getString(R.string.NM_Vibrate_Long);
            default -> getString(R.string.NM_Vibrate_Off);
        };
    }

    private void showVibrationSelector(Runnable runnable) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        keys.add(getString(R.string.NM_Vibrate_Off)); values.add(AppConfig.VIBRATE_DISABLE);
        keys.add(getString(R.string.NM_Vibrate_Click)); values.add(AppConfig.VIBRATE_CLICK);
        keys.add(getString(R.string.NM_Vibrate_Wave)); values.add(AppConfig.VIBRATE_WAVE);
        keys.add(getString(R.string.NM_Vibrate_Keyboard)); values.add(AppConfig.VIBRATE_KEYBOARD);
        keys.add(getString(R.string.NM_Vibrate_Long)); values.add(AppConfig.VIBRATE_LONG);
        PopupHelper.show(keys, getString(R.string.NM_VibrateInChats), values.indexOf(AppConfig.vibrateInChats), getContext(), i -> {
            AppConfig.setVibrateInChats(values.get(i));
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
