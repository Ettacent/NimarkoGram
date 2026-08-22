package app.nimarkogram.messenger.preferences;

import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.plugins.PluginsController;

final class NimarkoSettingsSearchIndex {

    static final int SCREEN_GENERAL = 1;
    static final int SCREEN_APPEARANCE = 2;
    static final int SCREEN_CHATS = 3;
    static final int SCREEN_CAMERA = 4;
    static final int SCREEN_PRIVACY = 5;
    static final int SCREEN_PLUGINS = 6;
    static final int SCREEN_MEDIA = 7;
    static final int SCREEN_BANNERS = 8;
    static final int SCREEN_BYPASS = 9;
    static final int SCREEN_TEXT_ANIMATION = 10;
    static final int SCREEN_INFO_CARDS = 11;
    static final int SCREEN_ADVANCED = 12;
    static final int SCREEN_FOLDERS = 13;
    static final int SCREEN_BOTTOM_TABS = 14;
    static final int SCREEN_MESSAGES_PROFILES = 15;
    static final int SCREEN_MESSAGE_MENU = 16;
    static final int SCREEN_MESSAGE_MENU_ITEMS = 17;
    static final int SCREEN_MESSAGE_FILTERS = 18;
    static final int SCREEN_RECENT = 19;
    static final int SCREEN_MESSAGE_MENU_ORDER = 20;
    static final int ACTION_UPDATES = 100;
    static final int ACTION_SOURCE = 101;
    static final int ACTION_RESTART = 102;

    static final class Entry {
        final int guid;
        final int screen;
        final int itemId;
        final int titleRes;
        final int summaryRes;
        final int iconRes;
        final int pathFirstRes;
        final int pathSecondRes;
        final boolean featured;

        Entry(int guid, int screen, int itemId, int titleRes, int summaryRes, int iconRes,
              int pathFirstRes, int pathSecondRes, boolean featured) {
            this.guid = guid;
            this.screen = screen;
            this.itemId = itemId;
            this.titleRes = titleRes;
            this.summaryRes = summaryRes != 0 ? summaryRes : fallbackDescriptionRes(titleRes);
            this.iconRes = iconRes;
            this.pathFirstRes = pathFirstRes;
            this.pathSecondRes = pathSecondRes;
            this.featured = featured;
        }

        String title() {
            return LocaleController.getString(titleRes);
        }

        String summary() {
            return summaryRes == 0 ? "" : LocaleController.getString(summaryRes);
        }

        String[] path() {
            if (pathSecondRes != 0) {
                return new String[]{LocaleController.getString(pathFirstRes), LocaleController.getString(pathSecondRes)};
            }
            return pathFirstRes == 0 ? null : new String[]{LocaleController.getString(pathFirstRes)};
        }

        boolean matches(String query) {
            if (TextUtils.isEmpty(query)) {
                return featured;
            }
            StringBuilder text = new StringBuilder(title());
            if (summaryRes != 0) {
                text.append(' ').append(LocaleController.getString(summaryRes));
            }
            if (pathFirstRes != 0) {
                text.append(' ').append(LocaleController.getString(pathFirstRes));
            }
            if (pathSecondRes != 0) {
                text.append(' ').append(LocaleController.getString(pathSecondRes));
            }
            String normalized = normalize(text.toString());
            String transliterated = normalize(LocaleController.getInstance().getTranslitString(text.toString()));
            for (String token : normalize(query).split("\\s+")) {
                if (!token.isEmpty() && !normalized.contains(token) && !transliterated.contains(token)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final List<Entry> ENTRIES = createEntries();

    private static List<Entry> createEntries() {
        ArrayList<Entry> entries = new ArrayList<>();
        int[] guid = {1};

        page(entries, guid, SCREEN_GENERAL, R.string.NM_Cat_General, R.string.NM_SettingsSummaryGeneral,
                R.drawable.msg_settings_solar);
        page(entries, guid, SCREEN_APPEARANCE, R.string.NM_Cat_Appearance, R.string.NM_SettingsSummaryAppearance,
                R.drawable.msg_theme_solar);
        page(entries, guid, SCREEN_CHATS, R.string.NM_Cat_Chats, R.string.NM_SettingsSummaryChats,
                R.drawable.msg_msgbubble3_solar);
        page(entries, guid, SCREEN_CAMERA, R.string.NM_Cat_Camera, R.string.NM_SettingsSummaryCamera,
                R.drawable.camera_solar);
        page(entries, guid, SCREEN_PRIVACY, R.string.NM_Cat_Privacy, R.string.NM_SettingsSummaryPrivacy,
                R.drawable.msg_secret_solar);
        page(entries, guid, SCREEN_PLUGINS, R.string.Plugins, R.string.NM_SettingsSummaryPlugins,
                R.drawable.msg_plugins);
        page(entries, guid, SCREEN_MEDIA, R.string.NM_DownloadMedia, R.string.NM_SettingsSummaryMedia,
                R.drawable.msg_download_solar);
        page(entries, guid, SCREEN_BANNERS, R.string.NM_BAN_Title, R.string.NM_SettingsSummaryBanners,
                R.drawable.msg_photos_solar);
        page(entries, guid, SCREEN_BYPASS, R.string.NM_WSB_Title, R.string.NM_SettingsSummaryBypass,
                R.drawable.msg_satellite_solar);
        page(entries, guid, SCREEN_TEXT_ANIMATION, R.string.NM_TA_Title, R.string.NM_SettingsSummaryTextAnimation,
                R.drawable.msg_edit_solar);
        page(entries, guid, SCREEN_INFO_CARDS, R.string.NM_CARDS_Title, R.string.NM_SettingsSummaryInfoCards,
                R.drawable.msg_search_solar);
        page(entries, guid, SCREEN_ADVANCED, R.string.NM_SettingsAdvanced, R.string.NM_SettingsSummaryAdvanced,
                R.drawable.msg_log_solar);
        page(entries, guid, ACTION_UPDATES, R.string.UP_CheckForUpdates, R.string.NM_SettingsSummaryUpdates,
                R.drawable.msg_info_solar);
        page(entries, guid, ACTION_SOURCE, R.string.NM_HUB_SourceCode, R.string.NM_SettingsSummarySource,
                R.drawable.msg_link_2_solar);
        page(entries, guid, ACTION_RESTART, R.string.NM_HUB_Restart, R.string.NM_SettingsSummaryRestart,
                R.drawable.msg_retry_solar);

        rows(entries, guid, SCREEN_GENERAL, R.drawable.msg_settings_solar,
                R.string.NM_Cat_General, R.string.NM_SettingsSectionSystemAnimations,
                1, R.string.EP_NavigationAnimation,
                2, R.string.EP_NavigationAnimationCrossfading,
                3, R.string.NM_PredictiveBackAnimation,
                9, R.string.AP_SystemEmoji,
                10, R.string.AP_SystemFonts,
                11, R.string.AP_Tablet_Mode);
        row(entries, guid, SCREEN_GENERAL, 4, R.string.CP_SilenceNonContacts, R.string.CP_SilenceNonContacts_Desc,
                R.drawable.msg_settings_solar, R.string.NM_Cat_General, R.string.NM_SettingsSectionNotificationsStories);
        row(entries, guid, SCREEN_GENERAL, 6, R.string.NM_ResidentNotification, R.string.NotificationsService,
                R.drawable.msg_settings_solar, R.string.NM_Cat_General, R.string.NM_SettingsSectionNotificationsStories);
        row(entries, guid, SCREEN_GENERAL, 17, R.string.NM_NotificationReactions, R.string.NM_NotificationReactions_Desc,
                R.drawable.msg_settings_solar, R.string.NM_Cat_General, R.string.NM_SettingsSectionNotificationsStories);
        row(entries, guid, SCREEN_GENERAL, 18, R.string.NM_NotificationReactionEmoji, 0,
                R.drawable.msg_settings_solar, R.string.NM_Cat_General, R.string.NM_SettingsSectionNotificationsStories);
        rows(entries, guid, SCREEN_GENERAL, R.drawable.msg_settings_solar,
                R.string.NM_Cat_General, R.string.NM_SettingsSectionNotificationsStories,
                7, R.string.CP_HideStories,
                8, R.string.CP_ArchiveStories);
        rows(entries, guid, SCREEN_GENERAL, R.drawable.msg_settings_solar,
                R.string.NM_Cat_General, R.string.NM_SettingsSectionConnection,
                12, R.string.EP_DownloadSpeedBoost,
                13, R.string.NM_GE_UploadSpeedBoost,
                14, R.string.EP_SlowNetworkMode);
        rows(entries, guid, SCREEN_GENERAL, R.drawable.msg_settings_solar,
                R.string.NM_Cat_General, R.string.NM_SettingsSectionGiftsEmoji,
                15, R.string.NM_GEN_DeletedGifts,
                16, R.string.NM_GEN_LocalPremiumEmoji);
        rows(entries, guid, SCREEN_GENERAL, R.drawable.msg_settings_solar,
                R.string.NM_Cat_General, R.string.NM_SettingsSectionDataBackup,
                19, R.string.NM_Config_Export,
                20, R.string.NM_Config_Import);

        rows(entries, guid, SCREEN_APPEARANCE, R.drawable.msg_theme_solar,
                R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionInterfaceEffects,
                4, R.string.AP_IconReplacements,
                5, R.string.NM_SwitchStyle,
                6, R.string.AP_DisableDividers);
        rows(entries, guid, SCREEN_APPEARANCE, R.drawable.msg_theme_solar,
                R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionNavigationHeader,
                1, R.string.AP_CenterTitle,
                2, R.string.AP_HideSearchBar,
                14, R.string.NM_HideActionBarStatus,
                15, R.string.NM_CustomTitle);
        row(entries, guid, SCREEN_APPEARANCE, 21, R.string.NM_IOSStyleComposer, R.string.NM_IOSStyleComposer_Desc,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionChatAppearance);
        row(entries, guid, SCREEN_APPEARANCE, 18, R.string.NM_HideBubbleTail, R.string.NM_HideBubbleTail_Desc,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionChatAppearance);
        row(entries, guid, SCREEN_APPEARANCE, 19, R.string.NM_OnlineIndicatorInGroups, R.string.NM_OnlineIndicatorInGroups_Desc,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionAvatarsStickers);
        rows(entries, guid, SCREEN_APPEARANCE, R.drawable.msg_theme_solar,
                R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionInterfaceEffects,
                13, R.string.NM_ForceBlur,
                10, R.string.AP_GlareOnElements,
                16, R.string.NM_MediaGlow,
                3, R.string.NM_SnowInHeader,
                28, R.string.NM_CP_SnowflakesInChat);
        row(entries, guid, SCREEN_APPEARANCE, 12, R.string.NM_ForumAvatarsLikeChats,
                R.string.NM_ForumAvatarsLikeChats_Desc, R.drawable.msg_theme_solar,
                R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionAvatarsStickers);
        row(entries, guid, SCREEN_APPEARANCE, 22, R.string.NM_AvatarCorners, 0,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionAvatarsStickers);
        row(entries, guid, SCREEN_APPEARANCE, 23, R.string.NM_StickerSize, 0,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionAvatarsStickers);
        row(entries, guid, SCREEN_APPEARANCE, 20, R.string.CP_TimeOnStick, 0,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionAvatarsStickers);
        rows(entries, guid, SCREEN_APPEARANCE, R.drawable.msg_theme_solar,
                R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionChatAppearance,
                24, R.string.CP_Messages_Size,
                25, R.string.NM_CP_CenterTitleInChat,
                26, R.string.CP_UnreadBadgeOnBackButton,
                27, R.string.CP_CustomWallpapers,
                29, R.string.CP_HideMuteUnmuteButton,
                30, R.string.NM_CP_WeekdayNearDate);
        pageRow(entries, guid, SCREEN_FOLDERS, R.string.CP_Filters_Header, R.drawable.msg_folders,
                R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionNavigationHeader);
        pageRow(entries, guid, SCREEN_BOTTOM_TABS, R.string.CP_MainTabs_Header, R.drawable.tabs_reorder,
                R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionNavigationHeader);
        pageRow(entries, guid, SCREEN_MESSAGES_PROFILES, R.string.CP_ProfileReplyBackground, R.drawable.msg_customize,
                R.string.NM_Cat_Appearance, R.string.NM_SettingsSectionChatAppearance);

        rows(entries, guid, SCREEN_CHATS, R.drawable.msg_msgbubble3_solar,
                R.string.NM_Cat_Chats, R.string.NM_SettingsSectionChatList,
                1, R.string.CP_SortByUnread,
                2, R.string.CP_UnarchiveOnSwipe,
                4, R.string.EP_CustomChat);
        rows(entries, guid, SCREEN_CHATS, R.drawable.msg_msgbubble3_solar,
                R.string.NM_Cat_Chats, R.string.NM_SettingsSectionInputText,
                5, R.string.CP_Slider_RecentEmojisAndStickers,
                14, R.string.AP_ShowPencilIcon,
                15, R.string.CP_ForwardMsgDate,
                17, R.string.CP_HideSendAsChannel,
                33, R.string.CP_AutoQuoteReplies,
                39, R.string.NM_CP_PreReformRussian,
                40, R.string.NM_CP_LatexRendering,
                41, R.string.NM_DisableSendHints);
        rows(entries, guid, SCREEN_CHATS, R.drawable.msg_msgbubble3_solar,
                R.string.NM_Cat_Chats, R.string.NM_SettingsSectionGesturesActions,
                30, R.string.CP_DoubleTapAction,
                31, R.string.NM_MsgSlideAction,
                32, R.string.CP_LeftBottomButtonAction,
                3, R.string.ForwardWithoutAuthor,
                34, R.string.CP_DisableSwipeToNext,
                35, R.string.CP_DeleteForAll,
                37, R.string.CP_DisableVibration);
        rows(entries, guid, SCREEN_CHATS, R.drawable.msg_msgbubble3_solar,
                R.string.NM_Cat_Chats, R.string.NM_SettingsSectionMediaPlayback,
                50, R.string.EP_PhotosSize,
                51, R.string.CP_PlayVideo,
                52, R.string.CP_AutoPauseVideo,
                53, R.string.NM_MSG_GifSpoilers,
                54, R.string.CP_VideoSeekDuration);
        rows(entries, guid, SCREEN_CHATS, R.drawable.msg_msgbubble3_solar,
                R.string.NM_Cat_Chats, R.string.NM_SettingsSectionReactionsEffects,
                60, R.string.CP_DisableReactionsOverlay,
                61, R.string.CP_DisableReactionAnim,
                62, R.string.CP_DisablePremStickAnim,
                63, R.string.CP_DisablePremStickAutoPlay);
        rows(entries, guid, SCREEN_CHATS, R.drawable.msg_msgbubble3_solar,
                R.string.NM_Cat_Chats, R.string.Notifications,
                70, R.string.NotificationsSound,
                71, R.string.CP_VibrateInChats);
        row(entries, guid, SCREEN_CHATS, 36, R.string.DirectShare, R.string.DirectShareInfo,
                R.drawable.msg_msgbubble3_solar, R.string.NM_Cat_Chats, R.string.NM_SettingsSectionTools);
        row(entries, guid, SCREEN_CHATS, 19, R.string.CP_ChatMenuShortcuts, 0,
                R.drawable.msg_msgbubble3_solar, R.string.NM_Cat_Chats, R.string.NM_SettingsSectionTools);
        row(entries, guid, SCREEN_CHATS, 38, R.string.CP_HideKbdOnScroll, 0,
                R.drawable.msg_msgbubble3_solar, R.string.NM_Cat_Chats, R.string.NM_SettingsSectionInputText);
        pageRow(entries, guid, SCREEN_MESSAGE_MENU, R.string.CP_MessageMenu, R.drawable.msg_list,
                R.string.NM_Cat_Chats, R.string.NM_SettingsSectionTools);
        pageRow(entries, guid, SCREEN_MESSAGE_FILTERS, R.string.CP_Message_Filtering, R.drawable.msg_notspam,
                R.string.NM_Cat_Chats, R.string.NM_SettingsSectionTools);

        row(entries, guid, SCREEN_CAMERA, 2, R.string.CP_CameraType, 0,
                R.drawable.camera_solar, R.string.NM_Cat_Camera, R.string.CP_CameraType);
        row(entries, guid, SCREEN_CAMERA, 10, R.string.CP_CenterCameraControlButtons,
                R.string.CP_CenterCameraControlButtons_Desc, R.drawable.camera_solar,
                R.string.NM_Cat_Camera, R.string.CP_Category_Camera);
        row(entries, guid, SCREEN_CAMERA, 4, R.string.NM_CAM_RoundCamera, 0,
                R.drawable.camera_solar, R.string.NM_Cat_Camera, R.string.CP_Header_Videomessages);
        row(entries, guid, SCREEN_CAMERA, 3, R.string.CP_CameraDualCamera, R.string.CP_CameraDualCamera_Desc,
                R.drawable.camera_solar, R.string.NM_Cat_Camera, R.string.CP_Header_Videomessages);
        row(entries, guid, SCREEN_CAMERA, 5, R.string.CP_CameraUW, R.string.CP_CameraUW_Desc,
                R.drawable.camera_solar, R.string.NM_Cat_Camera, R.string.CP_Header_Videomessages);
        rows(entries, guid, SCREEN_CAMERA, R.drawable.camera_solar,
                R.string.NM_Cat_Camera, R.string.NM_CAM_VideoQuality,
                17, R.string.NM_CAM_RoundVideoSize,
                18, R.string.NM_CAM_RoundVideoBitrate,
                7, R.string.CP_CameraQuality,
                8, R.string.NM_CAM_FpsRange,
                6, R.string.CP_CameraStabilisation);
        row(entries, guid, SCREEN_CAMERA, 11, R.string.NM_CAM_Improvements, 0,
                R.drawable.camera_solar, R.string.NM_Cat_Camera, R.string.NM_CAM_VideoQuality);
        row(entries, guid, SCREEN_CAMERA, 12, R.string.NM_CAM_OpticalStabilization,
                R.string.NM_CAM_OpticalStabilization_Desc, R.drawable.camera_solar,
                R.string.NM_Cat_Camera, R.string.NM_CAM_Improvements);
        row(entries, guid, SCREEN_CAMERA, 13, R.string.NM_CAM_ContinuousFocus,
                R.string.NM_CAM_ContinuousFocus_Desc, R.drawable.camera_solar,
                R.string.NM_Cat_Camera, R.string.NM_CAM_Improvements);
        row(entries, guid, SCREEN_CAMERA, 14, R.string.NM_Camera_NoiseReduction,
                R.string.NM_Camera_NoiseReduction_Desc, R.drawable.camera_solar,
                R.string.NM_Cat_Camera, R.string.NM_CAM_Improvements);
        row(entries, guid, SCREEN_CAMERA, 15, R.string.NM_Camera_FaceDetection,
                R.string.NM_Camera_FaceDetection_Desc, R.drawable.camera_solar,
                R.string.NM_Cat_Camera, R.string.NM_CAM_Improvements);
        row(entries, guid, SCREEN_CAMERA, 16, R.string.NM_Camera_UseHighRange,
                R.string.NM_Camera_UseHighRange_Desc, R.drawable.camera_solar,
                R.string.NM_Cat_Camera, R.string.NM_CAM_Improvements);

        row(entries, guid, SCREEN_PRIVACY, 1, R.string.NM_PR_HideProxy, 0,
                R.drawable.msg_secret_solar, R.string.NM_Cat_Privacy, R.string.NM_SettingsSectionHiddenItems);
        row(entries, guid, SCREEN_PRIVACY, 2, R.string.NM_PR_DeleteAccount, 0,
                R.drawable.msg_secret_solar, R.string.NM_Cat_Privacy, R.string.NM_SettingsSectionAccount);
        rows(entries, guid, SCREEN_PRIVACY, R.drawable.msg_secret_solar,
                R.string.NM_Cat_Privacy, R.string.NM_SettingsSectionHiddenItems,
                3, R.string.NM_PR_HideArchivedStories,
                4, R.string.NM_PR_HideArchiveList,
                14, R.string.NM_PR_OpenArchive);
        rows(entries, guid, SCREEN_PRIVACY, R.drawable.msg_secret_solar,
                R.string.NM_Cat_Privacy, R.string.NM_PR_Header_ChatProtection,
                5, R.string.NM_PR_AskBioOpenChats,
                6, R.string.NM_PR_LockedChats,
                11, R.string.NM_PR_LockedChatsTtl,
                12, R.string.NM_PR_AskBioOpenEncrypted,
                13, R.string.NM_PR_AskBioOpenArchive,
                7, R.string.NM_PR_RequireBiometricsToDelete);
        rows(entries, guid, SCREEN_PRIVACY, R.drawable.msg_secret_solar,
                R.string.NM_Cat_Privacy, R.string.NM_SettingsSectionAuthentication,
                8, R.string.NM_PR_AllowSystemPasscode,
                9, R.string.NM_PR_TestFingerprint);

        rows(entries, guid, SCREEN_FOLDERS, R.drawable.msg_folders,
                R.string.NM_Cat_Appearance, R.string.CP_Filters_Header,
                1, R.string.NM_FO_TabsHideAllChats,
                2, R.string.NM_FO_TabsNoCounter,
                3, R.string.NM_FO_TabStyle,
                4, R.string.NM_FO_TabStyleStroke,
                5, R.string.NM_FO_FolderNameInHeader,
                6, R.string.NM_FO_FoldersAtBottom);
        rows(entries, guid, SCREEN_BOTTOM_TABS, R.drawable.tabs_reorder,
                R.string.NM_Cat_Appearance, R.string.CP_MainTabs_Header,
                1, R.string.NM_BT_ShowTabs,
                2, R.string.NM_BT_ShowTabsTitle,
                4, R.string.NM_BT_ForceOpenChats,
                5, R.string.NM_BT_ShowSearchInTabs,
                6, R.string.Reset);
        rows(entries, guid, SCREEN_RECENT, R.drawable.msg_reactions2,
                R.string.NM_Cat_Chats, R.string.CP_Slider_RecentEmojisAndStickers,
                1, R.string.Emoji,
                2, R.string.AccDescrStickers);

        row(entries, guid, SCREEN_PLUGINS, 2, R.string.PluginsEngine, 0,
                R.drawable.msg_plugins, R.string.Plugins, 0);

        rows(entries, guid, SCREEN_MESSAGES_PROFILES, R.drawable.msg_customize,
                R.string.CP_ProfileReplyBackground, R.string.NM_MP_CustomizeMessage,
                MessagesAndProfilesPreferencesActivity.SETTING_SHOW_SECONDS, R.string.NM_MP_ShowSeconds,
                MessagesAndProfilesPreferencesActivity.SETTING_PREMIUM_STATUSES, R.string.NM_MP_DisablePremiumStatuses,
                MessagesAndProfilesPreferencesActivity.SETTING_REPLY_BACKGROUND, R.string.NM_MP_ReplyBackground,
                MessagesAndProfilesPreferencesActivity.SETTING_REPLY_COLORS, R.string.NM_MP_ReplyCustomColors,
                MessagesAndProfilesPreferencesActivity.SETTING_REPLY_EMOJI, R.string.NM_MP_ReplyBackgroundEmoji);
        rows(entries, guid, SCREEN_MESSAGES_PROFILES, R.drawable.msg_customize,
                R.string.CP_ProfileReplyBackground, R.string.NM_MP_CustomizeProfile,
                MessagesAndProfilesPreferencesActivity.SETTING_PROFILE_CHANNEL, R.string.NM_MP_ProfileChannelPreview,
                MessagesAndProfilesPreferencesActivity.SETTING_PROFILE_ID_DC, R.string.NM_MP_ShowIdDc,
                MessagesAndProfilesPreferencesActivity.SETTING_PROFILE_BIRTHDAY, R.string.NM_MP_ProfileBirthDatePreview,
                MessagesAndProfilesPreferencesActivity.SETTING_PROFILE_BUSINESS, R.string.NM_MP_ProfileBusinessPreview,
                MessagesAndProfilesPreferencesActivity.SETTING_PROFILE_COLOR, R.string.NM_MP_ProfileBackgroundColor,
                MessagesAndProfilesPreferencesActivity.SETTING_PROFILE_EMOJI, R.string.NM_MP_ProfileBackgroundEmoji);

        rows(entries, guid, SCREEN_MEDIA, R.drawable.msg_download_solar,
                R.string.NM_DownloadMedia, 0,
                100, R.string.NM_NM_AutoDownload,
                101, R.string.NM_NM_YtAsk,
                102, R.string.NM_NM_FormatVideo,
                103, R.string.NM_NM_FormatAudio);
        row(entries, guid, SCREEN_TEXT_ANIMATION, 200, R.string.NM_TA_Master, R.string.NM_TA_Master_Desc,
                R.drawable.msg_edit_solar, R.string.NM_TA_Title, R.string.NM_TA_HeaderMain);
        rows(entries, guid, SCREEN_TEXT_ANIMATION, R.drawable.msg_edit_solar,
                R.string.NM_TA_Title, R.string.NM_TA_HeaderEffects,
                201, R.string.NM_TA_Appear,
                202, R.string.NM_TA_Cursor,
                203, R.string.NM_TA_Delete,
                204, R.string.NM_TA_Spoiler);
        row(entries, guid, SCREEN_BANNERS, 100, R.string.NM_BAN_Enable, R.string.NM_BAN_EnableHint,
                R.drawable.msg_photos_solar, R.string.NM_BAN_Title, 0);
        rows(entries, guid, SCREEN_BANNERS, R.drawable.msg_photos_solar,
                R.string.NM_BAN_Title, R.string.NM_BAN_GlobalHeader,
                101, R.string.NM_BAN_StatusLabel,
                102, R.string.NM_BAN_ChangeGlobal,
                103, R.string.NM_BAN_SubmitModeration,
                105, R.string.NM_BAN_RefreshStatus);
        row(entries, guid, SCREEN_BANNERS, 104, R.string.NM_BAN_HideAvatar, R.string.NM_BAN_HideAvatarHint,
                R.drawable.msg_photos_solar, R.string.NM_BAN_Title, R.string.NM_SettingsSectionDisplay);
        rows(entries, guid, SCREEN_BANNERS, R.drawable.msg_photos_solar,
                R.string.NM_BAN_Title, R.string.NM_BAN_LocalHeader,
                106, R.string.NM_BAN_PickLocal,
                107, R.string.NM_BAN_DeleteLocal,
                108, R.string.NM_BAN_AvatarBanner);
        row(entries, guid, SCREEN_BANNERS, 109, R.string.NM_BAN_LiteMode, R.string.NM_BAN_LiteModeHint,
                R.drawable.msg_photos_solar, R.string.NM_BAN_Title, R.string.NM_SettingsSectionDisplay);
        rows(entries, guid, SCREEN_BYPASS, R.drawable.msg_satellite_solar,
                R.string.NM_WSB_Title, R.string.NM_SettingsSectionConnection,
                200, R.string.NM_WSB_Enable,
                201, R.string.NM_WSB_StatusTitle,
                202, R.string.NM_WSB_OpenProxySettings);
        rows(entries, guid, SCREEN_BYPASS, R.drawable.msg_satellite_solar,
                R.string.NM_WSB_Title, R.string.NM_WSB_MiscHeader,
                204, R.string.NM_WSB_VoIP_Enable,
                205, R.string.NM_WSB_SuspendOnVpn);
        rows(entries, guid, SCREEN_INFO_CARDS, R.drawable.msg_search_solar,
                R.string.NM_CARDS_Title, R.string.NM_CARDS_GeneralHeader,
                100, R.string.NM_CARDS_Enable,
                101, R.string.NM_CARDS_InfiniteScroll,
                102, R.string.NM_CARDS_AutoScroll);
        rows(entries, guid, SCREEN_INFO_CARDS, R.drawable.msg_search_solar,
                R.string.NM_CARDS_Title, R.string.NM_SettingsSectionContent,
                1001, R.string.NM_CARDS_NameWeather,
                1002, R.string.NM_CARDS_NameGram,
                1003, R.string.NM_CARDS_NameBitcoin,
                1004, R.string.NM_CARDS_NameUsd,
                1005, R.string.NM_CARDS_NameStorage,
                1006, R.string.NM_CARDS_NameProxy);
        rows(entries, guid, SCREEN_ADVANCED, R.drawable.msg_log_solar,
                R.string.NM_SettingsAdvanced, R.string.NM_SettingsSectionInterfaceInput,
                9, R.string.NM_DBG_ShowAccounts,
                6, R.string.NM_DBG_OldTimeStyle,
                7, R.string.NM_DBG_ReplacePunctuation,
                8, R.string.NM_DBG_EditTextFix);
        rows(entries, guid, SCREEN_ADVANCED, R.drawable.msg_log_solar,
                R.string.NM_SettingsAdvanced, R.string.NM_SettingsSectionMediaRecording,
                2, R.string.NM_DBG_AudioSource,
                5, R.string.NM_DBG_HideVideoTimestamp,
                11, R.string.NM_DBG_SendMaxQuality);
        rows(entries, guid, SCREEN_ADVANCED, R.drawable.msg_log_solar,
                R.string.NM_SettingsAdvanced, R.string.NM_SettingsSectionDiagnosticsCompatibility,
                1, R.string.NM_DBG_ShowRPCErrors,
                3, R.string.NM_DBG_JacksonJSONProvider);

        pageRow(entries, guid, SCREEN_MESSAGE_MENU_ITEMS, R.string.CP_MessageMenuItems, R.drawable.msg_list,
                R.string.NM_Cat_Chats, R.string.CP_MessageMenu);
        pageRow(entries, guid, SCREEN_MESSAGE_MENU_ORDER, R.string.NM_Menu_Reorder, R.drawable.msg_reorder,
                R.string.NM_Cat_Chats, R.string.CP_MessageMenu);
        row(entries, guid, SCREEN_MESSAGE_MENU, MessageMenuPreferencesActivity.SETTING_MODERN_MENU,
                R.string.NM_Menu_TelegramPlus, R.string.NM_Menu_TelegramPlus_Desc,
                R.drawable.msg_list, R.string.NM_Cat_Chats, R.string.CP_MessageMenu);
        row(entries, guid, SCREEN_MESSAGE_MENU, MessageMenuPreferencesActivity.SETTING_COMPACT_LAYOUT,
                R.string.CP_MessageMenuCompactLayout, R.string.CP_MessageMenuCompactLayout_Desc,
                R.drawable.msg_list, R.string.NM_Cat_Chats, R.string.CP_MessageMenu);
        row(entries, guid, SCREEN_MESSAGE_MENU, MessageMenuPreferencesActivity.SETTING_HAPTIC,
                R.string.NM_Menu_Haptic, R.string.NM_Menu_Haptic_Desc,
                R.drawable.msg_list, R.string.NM_Cat_Chats, R.string.CP_MessageMenu);
        rows(entries, guid, SCREEN_MESSAGE_FILTERS, R.drawable.msg_notspam,
                R.string.NM_Cat_Chats, R.string.CP_Message_Filtering,
                0, R.string.NM_MF_Filter,
                0, R.string.NM_MF_Field,
                0, R.string.NM_MF_Translit,
                0, R.string.NM_MF_Exact_Words,
                0, R.string.NM_MF_Exclusions,
                0, R.string.NM_MF_Entities,
                0, R.string.NM_MF_HideAll,
                0, R.string.NM_MF_Collapse,
                0, R.string.NM_MF_Transparent,
                0, R.string.NM_MF_UseRegex,
                0, R.string.NM_MF_RegexPatterns,
                0, R.string.NM_MF_LogicMode,
                0, R.string.NM_MF_ChatWhitelist,
                0, R.string.NM_MF_ChatBlacklist);
        rows(entries, guid, SCREEN_MESSAGE_MENU_ITEMS, R.drawable.msg_list,
                R.string.CP_MessageMenu, R.string.CP_MessageMenuItems,
                1, R.string.SaveForNotifications,
                2, R.string.Reply,
                3, R.string.SaveToGallery,
                4, R.string.NM_MI_CopyPhoto,
                5, R.string.NM_MI_CopyPhotoAsSticker,
                6, R.string.SaveToDownloads,
                7, R.string.ShareFile,
                8, R.string.NM_MI_ClearFromCache,
                9, R.string.Forward,
                10, R.string.NM_MI_ForwardWoAuthorship,
                11, R.string.AvatarPreviewSearchMessages,
                12, R.string.NM_MI_SaveToSaved,
                13, R.string.ReportChat,
                14, R.string.NM_MI_JSON,
                15, R.string.NM_MI_ForwardWoCaption,
                16, R.string.NM_MI_DownloadSticker,
                17, R.string.AccDescrCustomEmoji,
                18, R.string.NM_MI_Details);

        return Collections.unmodifiableList(entries);
    }

    static List<Entry> search(String query) {
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.screen == SCREEN_PLUGINS && !PluginsController.isPluginEngineSupported()) {
                continue;
            }
            if (entry.screen == SCREEN_GENERAL && entry.itemId == 3
                    && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                continue;
            }
            if (entry.screen == SCREEN_PRIVACY
                    && ((entry.itemId == 6 || entry.itemId == 11) && !NimarkoConfig.askBiometricsToOpenChat
                    || entry.itemId == 14 && !NimarkoConfig.hideArchiveFromChatsList)) {
                continue;
            }
            if (entry.matches(query)) {
                result.add(entry);
            }
        }
        return result;
    }

    static void applyDescriptions(Object owner, ArrayList<UItem> items) {
        int screen = screenFor(owner);
        if (screen == 0 || items == null) {
            return;
        }
        for (UItem item : items) {
            if (item == null || TextUtils.isEmpty(item.text) || !TextUtils.isEmpty(item.subtext)) {
                continue;
            }
            Entry entry = findEntry(screen, item);
            if (entry == null || entry.summaryRes == 0) {
                continue;
            }
            CharSequence description = entry.summary();
            if (TextUtils.isEmpty(description)) {
                continue;
            }
            if (item.instanceOf(SettingsActivity.SettingCell.Factory.class)) {
                item.subtext = description;
            } else if (item.viewType == UniversalAdapter.VIEW_TYPE_CHECK) {
                item.viewType = UniversalAdapter.VIEW_TYPE_TEXT_CHECK;
                item.subtext = description;
            } else if (item.viewType == UniversalAdapter.VIEW_TYPE_TEXT_CHECK) {
                item.subtext = description;
            } else if (item.viewType == UniversalAdapter.VIEW_TYPE_RADIO && TextUtils.isEmpty(item.textValue)) {
                item.textValue = description;
            }
        }
    }

    private static Entry findEntry(int screen, UItem item) {
        Entry titleMatch = null;
        for (Entry entry : ENTRIES) {
            if (entry.screen != screen || entry.itemId == 0) {
                continue;
            }
            boolean sameTitle = TextUtils.equals(entry.title(), item.text);
            if (entry.itemId == item.id && sameTitle) {
                return entry;
            }
            if (sameTitle) {
                titleMatch = entry;
            }
        }
        return titleMatch;
    }

    private static int screenFor(Object owner) {
        if (owner instanceof GeneralPreferencesActivity) return SCREEN_GENERAL;
        if (owner instanceof AppearancePreferencesActivity) return SCREEN_APPEARANCE;
        if (owner instanceof ChatsPreferencesActivity || owner instanceof MessagesPreferencesActivity) return SCREEN_CHATS;
        if (owner instanceof PrivacyPreferencesActivity) return SCREEN_PRIVACY;
        if (owner instanceof FoldersPreferencesActivity) return SCREEN_FOLDERS;
        if (owner instanceof BottomTabsPreferencesActivity) return SCREEN_BOTTOM_TABS;
        if (owner instanceof MessageMenuPreferencesActivity) return SCREEN_MESSAGE_MENU;
        if (owner instanceof MessageMenuItemsPreferencesActivity) return SCREEN_MESSAGE_MENU_ITEMS;
        if (owner instanceof NimarkoMediaPreferencesActivity) return SCREEN_MEDIA;
        if (owner instanceof NimarkoTextAnimPreferencesActivity) return SCREEN_TEXT_ANIMATION;
        if (owner instanceof BannerPreferencesActivity) return SCREEN_BANNERS;
        if (owner instanceof DebugPreferencesActivity) return SCREEN_ADVANCED;
        if (owner instanceof RecentEmojisStickersPreferencesActivity) return SCREEN_RECENT;
        if (owner instanceof app.nimarkogram.messenger.wsbypass.preferences.WsBypassPreferencesActivity) return SCREEN_BYPASS;
        if (owner instanceof app.nimarkogram.messenger.infocards.preferences.InfoCardsPreferencesActivity) return SCREEN_INFO_CARDS;
        return 0;
    }

    private static int fallbackDescriptionRes(int titleRes) {
        if (titleRes == R.string.EP_NavigationAnimation) return R.string.NM_SettingsDesc_NavigationAnimation;
        if (titleRes == R.string.EP_NavigationAnimationCrossfading) return R.string.NM_SettingsDesc_NavigationCrossfade;
        if (titleRes == R.string.NM_PredictiveBackAnimation) return R.string.NM_SettingsDesc_PredictiveBack;
        if (titleRes == R.string.AP_SystemEmoji) return R.string.NM_SettingsDesc_SystemEmoji;
        if (titleRes == R.string.AP_SystemFonts) return R.string.NM_SettingsDesc_SystemFonts;
        if (titleRes == R.string.AP_Tablet_Mode) return R.string.NM_SettingsDesc_TabletMode;
        if (titleRes == R.string.NM_ResidentNotification) return R.string.NM_SettingsDesc_ResidentNotification;
        if (titleRes == R.string.NM_NotificationReactionEmoji) return R.string.NM_SettingsDesc_ReactionEmoji;
        if (titleRes == R.string.CP_HideStories) return R.string.CP_HideStories_Desc;
        if (titleRes == R.string.CP_ArchiveStories) return R.string.CP_ArchiveStories_Desc;
        if (titleRes == R.string.EP_DownloadSpeedBoost) return R.string.NM_SettingsDesc_DownloadBoost;
        if (titleRes == R.string.NM_GE_UploadSpeedBoost) return R.string.NM_SettingsDesc_UploadBoost;
        if (titleRes == R.string.EP_SlowNetworkMode) return R.string.NM_SettingsDesc_SlowNetwork;
        if (titleRes == R.string.NM_GEN_DeletedGifts) return R.string.NM_GEN_DeletedGifts_Desc;
        if (titleRes == R.string.NM_GEN_LocalPremiumEmoji) return R.string.NM_GEN_LocalPremiumEmoji_Desc;
        if (titleRes == R.string.NM_Config_Export) return R.string.NM_Config_Export_Desc;
        if (titleRes == R.string.NM_Config_Import) return R.string.NM_Config_Import_Desc;
        if (titleRes == R.string.AP_IconReplacements) return R.string.NM_SettingsDesc_IconPack;
        if (titleRes == R.string.NM_SwitchStyle) return R.string.NM_SettingsDesc_SwitchStyle;
        if (titleRes == R.string.AP_DisableDividers) return R.string.NM_SettingsDesc_DisableDividers;
        if (titleRes == R.string.AP_CenterTitle) return R.string.NM_SettingsDesc_CenterTitle;
        if (titleRes == R.string.AP_HideSearchBar) return R.string.NM_SettingsDesc_HideSearchBar;
        if (titleRes == R.string.NM_HideActionBarStatus) return R.string.NM_SettingsDesc_HideHeaderStatus;
        if (titleRes == R.string.NM_CustomTitle) return R.string.NM_SettingsDesc_CustomTitle;
        if (titleRes == R.string.NM_ForceBlur) return R.string.NM_SettingsDesc_ForceBlur;
        if (titleRes == R.string.AP_GlareOnElements) return R.string.AP_GlareOnElementsInfo;
        if (titleRes == R.string.NM_MediaGlow) return R.string.NM_MediaGlow_Desc;
        if (titleRes == R.string.NM_SnowInHeader) return R.string.NM_SnowInHeader_Desc;
        if (titleRes == R.string.NM_CP_SnowflakesInChat) return R.string.NM_CP_SnowflakesInChat_Desc;
        if (titleRes == R.string.CP_TimeOnStick) return R.string.NM_SettingsDesc_StickerTime;
        if (titleRes == R.string.CP_Messages_Size) return R.string.NM_SettingsDesc_MessageSize;
        if (titleRes == R.string.NM_CP_CenterTitleInChat) return R.string.NM_SettingsDesc_CenterChatTitle;
        if (titleRes == R.string.NM_IOSStyleComposer) return R.string.NM_IOSStyleComposer_Desc;
        if (titleRes == R.string.NM_HideBubbleTail) return R.string.NM_HideBubbleTail_Desc;
        if (titleRes == R.string.NM_ForumAvatarsLikeChats) return R.string.NM_ForumAvatarsLikeChats_Desc;
        if (titleRes == R.string.NM_OnlineIndicatorInGroups) return R.string.NM_OnlineIndicatorInGroups_Desc;
        if (titleRes == R.string.CP_UnreadBadgeOnBackButton) return R.string.CP_UnreadBadgeOnBackButton_Desc;
        if (titleRes == R.string.CP_CustomWallpapers) return R.string.CP_CustomWallpapers_Desc;
        if (titleRes == R.string.NM_CP_WeekdayNearDate) return R.string.NM_CP_WeekdayNearDate_Desc;
        if (titleRes == R.string.CP_HideMuteUnmuteButton) return R.string.NM_SettingsDesc_HideMuteButton;
        if (titleRes == R.string.CP_SortByUnread) return R.string.NM_SettingsDesc_SortUnread;
        if (titleRes == R.string.CP_UnarchiveOnSwipe) return R.string.NM_SettingsDesc_UnarchiveSwipe;
        if (titleRes == R.string.EP_CustomChat) return R.string.EP_CustomChat_Desc;
        if (titleRes == R.string.CP_Slider_RecentEmojisAndStickers) return R.string.NM_CH_RecentEmojisStickers_Desc;
        if (titleRes == R.string.AP_ShowPencilIcon) return R.string.NM_SettingsDesc_PencilIcon;
        if (titleRes == R.string.CP_ForwardMsgDate) return R.string.NM_SettingsDesc_ForwardDate;
        if (titleRes == R.string.CP_HideSendAsChannel) return R.string.CP_HideSendAsChannelDesc;
        if (titleRes == R.string.CP_AutoQuoteReplies) return R.string.CP_AutoQuoteReplies_Desc;
        if (titleRes == R.string.NM_CP_PreReformRussian) return R.string.NM_CP_PreReformRussian_Desc;
        if (titleRes == R.string.NM_CP_LatexRendering) return R.string.NM_CP_LatexRendering_Desc;
        if (titleRes == R.string.NM_DisableSendHints) return R.string.NM_DisableSendHints_Desc;
        if (titleRes == R.string.CP_DoubleTapAction) return R.string.NM_SettingsDesc_DoubleTap;
        if (titleRes == R.string.NM_MsgSlideAction) return R.string.NM_SettingsDesc_MessageSwipe;
        if (titleRes == R.string.CP_LeftBottomButtonAction) return R.string.NM_SettingsDesc_LeftButton;
        if (titleRes == R.string.ForwardWithoutAuthor) return R.string.NM_SettingsDesc_ForwardWithoutAuthor;
        if (titleRes == R.string.CP_DisableSwipeToNext) return R.string.CP_DisableSwipeToNext_Desc;
        if (titleRes == R.string.CP_DeleteForAll) return R.string.CP_DeleteForAll_Desc;
        if (titleRes == R.string.CP_DisableVibration) return R.string.NM_SettingsDesc_MenuVibration;
        if (titleRes == R.string.EP_PhotosSize) return R.string.NM_SettingsDesc_LargePhotos;
        if (titleRes == R.string.CP_PlayVideo) return R.string.CP_PlayVideo_Desc;
        if (titleRes == R.string.CP_AutoPauseVideo) return R.string.CP_AutoPauseVideo_Desc;
        if (titleRes == R.string.NM_MSG_GifSpoilers) return R.string.NM_SettingsDesc_GifSpoilers;
        if (titleRes == R.string.CP_HideKbdOnScroll) return R.string.NM_SettingsDesc_HideKeyboardOnScroll;
        if (titleRes == R.string.CP_VideoSeekDuration) return R.string.NM_SettingsDesc_VideoSeek;
        if (titleRes == R.string.CP_DisableReactionsOverlay) return R.string.CP_DisableReactionsOverlay_Desc;
        if (titleRes == R.string.CP_DisableReactionAnim) return R.string.CP_DisableReactionAnim_Desc;
        if (titleRes == R.string.CP_DisablePremStickAnim) return R.string.CP_DisablePremStickAnim_Desc;
        if (titleRes == R.string.CP_DisablePremStickAutoPlay) return R.string.CP_DisablePremStickAutoPlay_Desc;
        if (titleRes == R.string.NotificationsSound) return R.string.NM_SettingsDesc_NotificationSound;
        if (titleRes == R.string.CP_VibrateInChats) return R.string.NM_SettingsDesc_ChatVibration;
        if (titleRes == R.string.CP_ChatMenuShortcuts) return R.string.NM_SettingsDesc_ChatShortcuts;
        if (titleRes == R.string.NM_PR_HideProxy) return R.string.NM_SettingsDesc_HideProxy;
        if (titleRes == R.string.NM_PR_DeleteAccount) return R.string.NM_SettingsDesc_DeleteAccount;
        if (titleRes == R.string.NM_PR_OpenArchive) return R.string.NM_SettingsDesc_OpenArchive;
        if (titleRes == R.string.NM_PR_LockedChats) return R.string.NM_SettingsDesc_LockedChats;
        if (titleRes == R.string.NM_PR_LockedChatsTtl) return R.string.NM_SettingsDesc_LockedChatsTtl;
        if (titleRes == R.string.NM_PR_HideArchivedStories) return R.string.NM_PR_HideArchivedStories_Desc;
        if (titleRes == R.string.NM_PR_HideArchiveList) return R.string.NM_PR_HideArchiveList_Desc;
        if (titleRes == R.string.NM_PR_AskBioOpenChats) return R.string.NM_PR_AskBioOpenChats_Desc;
        if (titleRes == R.string.NM_PR_AskBioOpenEncrypted) return R.string.NM_PR_AskBioOpenEncrypted_Desc;
        if (titleRes == R.string.NM_PR_AskBioOpenArchive) return R.string.NM_PR_AskBioOpenArchive_Desc;
        if (titleRes == R.string.NM_PR_RequireBiometricsToDelete) return R.string.NM_PR_RequireBiometricsToDelete_Desc;
        if (titleRes == R.string.NM_PR_AllowSystemPasscode) return R.string.NM_PR_AllowSystemPasscode_Desc;
        if (titleRes == R.string.NM_PR_TestFingerprint) return R.string.NM_PR_TestFingerprint_Desc;
        if (titleRes == R.string.NM_FO_TabsHideAllChats) return R.string.NM_SettingsDesc_HideAllChatsTab;
        if (titleRes == R.string.NM_FO_TabsNoCounter) return R.string.NM_SettingsDesc_FolderCounter;
        if (titleRes == R.string.NM_FO_TabStyle) return R.string.NM_SettingsDesc_FolderTabStyle;
        if (titleRes == R.string.NM_FO_TabStyleStroke) return R.string.NM_FO_TabStyleStroke_Desc;
        if (titleRes == R.string.NM_FO_FolderNameInHeader) return R.string.NM_SettingsDesc_FolderNameHeader;
        if (titleRes == R.string.NM_FO_FoldersAtBottom) return R.string.NM_FO_FoldersAtBottom_Desc;
        if (titleRes == R.string.NM_BT_ShowTabs) return R.string.NM_SettingsDesc_BottomTabs;
        if (titleRes == R.string.NM_BT_ShowTabsTitle) return R.string.NM_SettingsDesc_BottomTabsTitle;
        if (titleRes == R.string.NM_BT_ForceOpenChats) return R.string.CP_MainTabs_ForceOpenChats_Desc;
        if (titleRes == R.string.NM_BT_ShowSearchInTabs) return R.string.NM_BT_ShowSearchInTabs_Desc;
        if (titleRes == R.string.Reset) return R.string.NM_SettingsDesc_ResetTabs;
        if (titleRes == R.string.Emoji) return R.string.NM_SettingsDesc_RecentEmoji;
        if (titleRes == R.string.AccDescrStickers) return R.string.NM_SettingsDesc_RecentStickers;
        if (titleRes == R.string.NM_MP_ShowSeconds) return R.string.NM_MP_ShowSeconds_Desc;
        if (titleRes == R.string.NM_MP_DisablePremiumStatuses) return R.string.NM_MP_DisablePremiumStatuses_Desc;
        if (titleRes == R.string.NM_MP_ReplyBackground) return R.string.NM_MP_ReplyBackground_Desc;
        if (titleRes == R.string.NM_MP_ReplyCustomColors) return R.string.NM_MP_ReplyCustomColors_Desc;
        if (titleRes == R.string.NM_MP_ReplyBackgroundEmoji) return R.string.NM_MP_ReplyBackgroundEmoji_Desc;
        if (titleRes == R.string.NM_MP_ProfileChannelPreview) return R.string.NM_MP_ProfileChannelPreview_Desc;
        if (titleRes == R.string.NM_MP_ShowIdDc) return R.string.NM_MP_ShowIdDc_Desc;
        if (titleRes == R.string.NM_MP_ProfileBirthDatePreview) return R.string.NM_MP_ProfileBirthDatePreview_Desc;
        if (titleRes == R.string.NM_MP_ProfileBusinessPreview) return R.string.NM_MP_ProfileBusinessPreview_Desc;
        if (titleRes == R.string.NM_MP_ProfileBackgroundColor) return R.string.NM_MP_ProfileBackgroundColor_Desc;
        if (titleRes == R.string.NM_MP_ProfileBackgroundEmoji) return R.string.NM_MP_ProfileBackgroundEmoji_Desc;
        if (titleRes == R.string.NM_NM_AutoDownload) return R.string.NM_NM_AutoDownload_Desc;
        if (titleRes == R.string.NM_TA_Appear) return R.string.NM_TA_Appear_Desc;
        if (titleRes == R.string.NM_TA_Cursor) return R.string.NM_TA_Cursor_Desc;
        if (titleRes == R.string.NM_TA_Delete) return R.string.NM_TA_Delete_Desc;
        if (titleRes == R.string.NM_TA_Spoiler) return R.string.NM_TA_Spoiler_Desc;
        if (titleRes == R.string.NM_BAN_StatusLabel) return R.string.NM_SettingsDesc_BannerStatus;
        if (titleRes == R.string.NM_BAN_ChangeGlobal) return R.string.NM_SettingsDesc_BannerGlobal;
        if (titleRes == R.string.NM_BAN_SubmitModeration) return R.string.NM_SettingsDesc_BannerSubmit;
        if (titleRes == R.string.NM_BAN_RefreshStatus) return R.string.NM_SettingsDesc_BannerRefresh;
        if (titleRes == R.string.NM_BAN_PickLocal) return R.string.NM_SettingsDesc_BannerLocal;
        if (titleRes == R.string.NM_BAN_DeleteLocal) return R.string.NM_SettingsDesc_BannerDelete;
        if (titleRes == R.string.NM_BAN_AvatarBanner) return R.string.NM_SettingsDesc_BannerAvatar;
        if (titleRes == R.string.NM_WSB_Enable) return R.string.NM_SettingsDesc_BypassEnable;
        if (titleRes == R.string.NM_WSB_OpenProxySettings) return R.string.NM_SettingsDesc_BypassProxySettings;
        if (titleRes == R.string.NM_WSB_VoIP_Enable) return R.string.NM_SettingsDesc_BypassCalls;
        if (titleRes == R.string.NM_WSB_SuspendOnVpn) return R.string.NM_WSB_SuspendOnVpn_Desc;
        if (titleRes == R.string.NM_CARDS_Enable) return R.string.NM_SettingsDesc_CardsEnable;
        if (titleRes == R.string.NM_CARDS_InfiniteScroll) return R.string.NM_SettingsDesc_CardsInfinite;
        if (titleRes == R.string.NM_CARDS_AutoScroll) return R.string.NM_SettingsDesc_CardsAutoScroll;
        if (titleRes == R.string.NM_DBG_ShowAccounts) return R.string.NM_SettingsDesc_ShowAccounts;
        if (titleRes == R.string.NM_DBG_OldTimeStyle) return R.string.NM_SettingsDesc_OldTime;
        if (titleRes == R.string.NM_DBG_ReplacePunctuation) return R.string.NM_SettingsDesc_ReplacePunctuation;
        if (titleRes == R.string.NM_DBG_EditTextFix) return R.string.NM_SettingsDesc_EditTextFix;
        if (titleRes == R.string.NM_DBG_AudioSource) return R.string.NM_SettingsDesc_AudioSource;
        if (titleRes == R.string.NM_DBG_HideVideoTimestamp) return R.string.NM_SettingsDesc_HideVideoTimestamp;
        if (titleRes == R.string.NM_DBG_SendMaxQuality) return R.string.NM_SettingsDesc_MaxVideoQuality;
        if (titleRes == R.string.NM_DBG_ShowRPCErrors) return R.string.NM_SettingsDesc_RpcErrors;
        if (titleRes == R.string.NM_DBG_JacksonJSONProvider) return R.string.NM_SettingsDesc_JsonProvider;
        if (titleRes == R.string.CP_MessageMenuItems) return R.string.NM_SettingsDesc_MenuItems;
        if (titleRes == R.string.NM_Menu_Reorder) return R.string.NM_SettingsDesc_MenuOrder;
        if (titleRes == R.string.CP_MessageMenu) return R.string.NM_SettingsSummaryMessageMenu;
        if (titleRes == R.string.CP_Message_Filtering) return R.string.NM_SettingsSummaryMessageFilters;
        if (titleRes == R.string.SaveForNotifications || titleRes == R.string.Reply
                || titleRes == R.string.SaveToGallery || titleRes == R.string.NM_MI_CopyPhoto
                || titleRes == R.string.NM_MI_CopyPhotoAsSticker || titleRes == R.string.SaveToDownloads
                || titleRes == R.string.ShareFile || titleRes == R.string.NM_MI_ClearFromCache
                || titleRes == R.string.Forward || titleRes == R.string.NM_MI_ForwardWoAuthorship
                || titleRes == R.string.AvatarPreviewSearchMessages || titleRes == R.string.NM_MI_SaveToSaved
                || titleRes == R.string.ReportChat || titleRes == R.string.NM_MI_JSON
                || titleRes == R.string.NM_MI_ForwardWoCaption || titleRes == R.string.NM_MI_DownloadSticker
                || titleRes == R.string.AccDescrCustomEmoji || titleRes == R.string.NM_MI_Details) {
            return R.string.NM_SettingsDesc_MenuItemVisibility;
        }
        return 0;
    }

    private static void page(List<Entry> entries, int[] guid, int screen, int titleRes, int summaryRes, int iconRes) {
        entries.add(new Entry(guid[0]++, screen, 0, titleRes, summaryRes, iconRes,
                R.string.Settings, 0, true));
    }

    private static void pageRow(List<Entry> entries, int[] guid, int screen, int titleRes, int iconRes,
                                int pathFirstRes, int pathSecondRes) {
        entries.add(new Entry(guid[0]++, screen, 0, titleRes, 0, iconRes,
                pathFirstRes, pathSecondRes, false));
    }

    private static void rows(List<Entry> entries, int[] guid, int screen, int iconRes,
                             int pathFirstRes, int pathSecondRes, int... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row(entries, guid, screen, pairs[i], pairs[i + 1], 0, iconRes, pathFirstRes, pathSecondRes);
        }
    }

    private static void row(List<Entry> entries, int[] guid, int screen, int itemId, int titleRes,
                            int summaryRes, int iconRes, int pathFirstRes, int pathSecondRes) {
        entries.add(new Entry(guid[0]++, screen, itemId, titleRes, summaryRes, iconRes,
                pathFirstRes, pathSecondRes, false));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }

    private NimarkoSettingsSearchIndex() {
    }
}
