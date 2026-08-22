package app.nimarkogram.messenger.preferences;

import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
            this.summaryRes = summaryRes;
            this.iconRes = iconRes;
            this.pathFirstRes = pathFirstRes;
            this.pathSecondRes = pathSecondRes;
            this.featured = featured;
        }

        String title() {
            return LocaleController.getString(titleRes);
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
                3, R.string.CP_Snowflakes_Header,
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
            if (entry.matches(query)) {
                result.add(entry);
            }
        }
        return result;
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
