package app.nimarkogram.messenger;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.List;

import app.nimarkogram.messenger.preferences.helpers.PopupHelper;

public final class NimarkoMessageMenuInjector {

    public static final int OPTION_COPY_PHOTO = 200;
    public static final int OPTION_COPY_PHOTO_AS_STICKER = 201;
    public static final int OPTION_CLEAR_FROM_CACHE = 202;
    public static final int OPTION_FORWARD_WO_AUTHOR = 203;
    public static final int OPTION_VIEW_HISTORY = 204;
    public static final int OPTION_SAVE_MESSAGE_CHAT = 205;
    public static final int OPTION_CREATE_QUOTE = 207;
    @Deprecated
    public static final int OPTION_DETAILS_JSON = 206;

    private NimarkoMessageMenuInjector() {}

    public static void injectCreateQuote(
        ChatActivity chatActivity,
        MessageObject selectedObject,
        MessageObject.GroupedMessages selectedObjectGroup,
        boolean noforwardsOrPaidMedia,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (noforwardsOrPaidMedia) return;
        if (!app.nimarkogram.messenger.quotes.NimarkoQuoteCreator.canCreate(chatActivity, selectedObject, selectedObjectGroup)) return;


        items.add(LocaleController.getString(R.string.NM_QC_Create));
        options.add(OPTION_CREATE_QUOTE);
        icons.add(R.drawable.menu_select_quote);
    }

    public static void injectCopyPhoto(
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (NimarkoConfig.showCopyPhoto) {
            items.add(LocaleController.getString(R.string.NM_MI_CopyPhoto));
            options.add(OPTION_COPY_PHOTO);
            icons.add(R.drawable.msg_copy);
        }
        if (NimarkoConfig.showCopyPhotoAsSticker) {
            items.add(LocaleController.getString(R.string.NM_MI_CopyPhotoAsSticker));
            options.add(OPTION_COPY_PHOTO_AS_STICKER);
            icons.add(R.drawable.msg_sticker);
        }
    }

    public static void injectClearFromCache(
        MessageObject selectedObject,
        int currentAccount,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (!NimarkoConfig.showClearFromCache) return;
        if (selectedObject == null || selectedObject.messageOwner == null) return;
        if (!hasCachedFile(selectedObject, currentAccount)) return;

        items.add(LocaleController.getString(R.string.NM_MI_ClearFromCache));
        options.add(OPTION_CLEAR_FROM_CACHE);
        icons.add(R.drawable.msg_clear);
    }

    private static boolean hasCachedFile(MessageObject m, int currentAccount) {
        final boolean hasMedia = m.getDocument() != null
                || m.type == MessageObject.TYPE_PHOTO
                || m.type == MessageObject.TYPE_VIDEO
                || m.type == MessageObject.TYPE_GIF
                || m.type == MessageObject.TYPE_VOICE
                || m.type == MessageObject.TYPE_ROUND_VIDEO
                || m.type == MessageObject.TYPE_MUSIC
                || m.type == MessageObject.TYPE_FILE
                || m.type == MessageObject.TYPE_ANIMATED_STICKER
                || m.type == MessageObject.TYPE_STICKER;
        if (!hasMedia) return false;

        if (m.mediaExists || m.attachPathExists) return true;

        String attachPath = m.messageOwner.attachPath;
        if (attachPath != null && !attachPath.isEmpty()) {
            try {
                if (new java.io.File(attachPath).exists()) return true;
            } catch (Exception ignore) { /* fall through to FileLoader */ }
        }

        try {
            java.io.File f = FileLoader.getInstance(currentAccount).getPathToMessage(m.messageOwner);
            return f != null && f.exists();
        } catch (Exception ignore) {
            return false;
        }
    }

    public static void injectForwardWoAuthorship(
        MessageObject selectedObject,
        int chatMode,
        boolean noforwardsOrPaidMedia,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (!NimarkoConfig.showForwardWoAuthorship) return;
        if (selectedObject == null) return;
        if (noforwardsOrPaidMedia) return;
        if (selectedObject.isSponsored()) return;
        if (chatMode == ChatActivity.MODE_QUICK_REPLIES || chatMode == ChatActivity.MODE_SCHEDULED) return;
        if (selectedObject.needDrawBluredPreview() && !selectedObject.hasExtendedMediaPreview()) return;
        if (selectedObject.isLiveLocation()) return;
        if (selectedObject.type == MessageObject.TYPE_PHONE_CALL
            || selectedObject.type == MessageObject.TYPE_GIFT_PREMIUM
            || selectedObject.type == MessageObject.TYPE_GIFT_PREMIUM_CHANNEL
            || selectedObject.type == MessageObject.TYPE_SUGGEST_PHOTO
            || selectedObject.type == MessageObject.TYPE_STORY_MENTION
            || selectedObject.type == MessageObject.TYPE_GIFT_STARS) return;
        if (selectedObject.isWallpaperAction()) return;
        if (selectedObject.isExpiredStory()) return;

        items.add(LocaleController.getString(R.string.NM_MI_ForwardWoAuthorship));
        options.add(OPTION_FORWARD_WO_AUTHOR);
        icons.add(R.drawable.msg_forward);
    }

    public static void injectViewHistory(
        TLRPC.Chat currentChat,
        int chatMode,
        MessageObject message,
        ArrayList<MessageObject> threadMessageObjects,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (!NimarkoConfig.showViewHistory) return;
        if (currentChat == null) return;
        if (chatMode != 0) return;
        if (currentChat.broadcast) return;
        if (threadMessageObjects != null && message != null && threadMessageObjects.contains(message)) return;
        if (message != null && message.isOutOwner()) return;
        if (message != null && (message.messageOwner == null
            || message.messageOwner.action != null
            || message.isSponsored()
            || message.scheduled)) return;

        items.add(LocaleController.getString(R.string.AvatarPreviewSearchMessages));
        options.add(OPTION_VIEW_HISTORY);
        icons.add(R.drawable.msg_search);
    }

    public static void injectSaveMessage(
        MessageObject message,
        int chatMode,
        boolean noforwardsOrPaidMedia,
        TLRPC.User currentUser,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (!NimarkoConfig.showSaveMessage) return;
        if (noforwardsOrPaidMedia) return;
        if (chatMode == ChatActivity.MODE_SCHEDULED) return;
        if (UserObject.isUserSelf(currentUser)) return;
        if (message == null || message.isSponsored()) return;

        items.add(LocaleController.getString(R.string.NM_MI_SaveToSaved));
        options.add(OPTION_SAVE_MESSAGE_CHAT);
        icons.add(R.drawable.msg_saved);
    }

    public static void injectViewStatistics(
        ChatActivity chatActivity,
        MessageObject message,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (message == null || message.messageOwner == null) return;
        if (message.messageOwner.forwards <= 0) return;
        if (message.isForwarded()) return;
        if (chatActivity == null) return;
        if (!ChatObject.hasAdminRights(chatActivity.getCurrentChat())) return;

        items.add(LocaleController.getString(R.string.ViewStatistics));
        options.add(ChatActivity.OPTION_STATISTICS);
        icons.add(R.drawable.msg_stats);
    }

    public static void injectJSON(
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (NimarkoConfig.showJSON && !NimarkoConfig.showDetails) {
            items.add("JSON");
            options.add(app.nimarkogram.messenger.utils.chats.NimarkoChatActivityHelper.OPTION_DETAILS);
            icons.add(R.drawable.msg_info);
        }
    }

    public static void injectDetails(
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (NimarkoConfig.showDetails) {
            items.add(LocaleController.getString(R.string.NM_MI_Details));
            options.add(app.nimarkogram.messenger.utils.chats.NimarkoChatActivityHelper.OPTION_DETAILS);
            icons.add(R.drawable.msg_info);
        }
    }

    public static void injectReport(
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (NimarkoConfig.showReport) {
            items.add(LocaleController.getString(R.string.ReportChat));
            options.add(ChatActivity.OPTION_REPORT_CHAT);
            icons.add(R.drawable.msg_report);
        }
    }


    public static void injectForwardWoCaption(
        MessageObject selectedObject,
        MessageObject.GroupedMessages selectedObjectGroup,
        int chatMode,
        boolean noforwardsOrPaidMedia,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (!NimarkoConfig.showForwardWoCaption) return;
        if (selectedObject == null) return;
        if (noforwardsOrPaidMedia) return;
        if (selectedObject.isSponsored()) return;
        if (chatMode == ChatActivity.MODE_QUICK_REPLIES || chatMode == ChatActivity.MODE_SCHEDULED) return;
        if (selectedObject.needDrawBluredPreview() && !selectedObject.hasExtendedMediaPreview()) return;
        if (selectedObject.isLiveLocation()) return;
        if (selectedObject.type == MessageObject.TYPE_PHONE_CALL
                || selectedObject.type == MessageObject.TYPE_GIFT_PREMIUM
                || selectedObject.type == MessageObject.TYPE_GIFT_PREMIUM_CHANNEL
                || selectedObject.type == MessageObject.TYPE_SUGGEST_PHOTO
                || selectedObject.type == MessageObject.TYPE_STORY_MENTION
                || selectedObject.type == MessageObject.TYPE_GIFT_STARS) return;
        if (selectedObject.isWallpaperAction()) return;
        if (selectedObject.isExpiredStory()) return;
        if (!hasAnyCaption(selectedObject, selectedObjectGroup)) return;

        items.add(LocaleController.getString(R.string.NM_MI_ForwardWoCaption));
        options.add(app.nimarkogram.messenger.utils.chats.NimarkoChatActivityHelper.OPTION_FORWARD_WO_CAPTION);
        icons.add(R.drawable.msg_forward);
    }

    private static boolean hasAnyCaption(MessageObject m, MessageObject.GroupedMessages group) {
        if (m != null && m.caption != null && m.caption.length() > 0) return true;
        if (group == null) return false;
        for (int a = 0, n = group.messages.size(); a < n; a++) {
            MessageObject sib = group.messages.get(a);
            if (sib != null && sib.caption != null && sib.caption.length() > 0) return true;
        }
        return false;
    }

    public static void injectDownloadSticker(
        MessageObject selectedObject,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (!NimarkoConfig.showDownloadSticker) return;
        if (selectedObject == null) return;
        if (selectedObject.isAnimatedSticker()) return;

        items.add(LocaleController.getString(R.string.NM_MI_DownloadSticker));
        options.add(app.nimarkogram.messenger.utils.chats.NimarkoChatActivityHelper.OPTION_DOWNLOAD_STICKER);
        icons.add(R.drawable.msg_gallery);
    }

    public static void injectGetCustomReactions(
        MessageObject selectedObject,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (!NimarkoConfig.showGetCustomReactions) return;
        if (selectedObject == null) return;
        if (selectedObject.messageOwner == null || selectedObject.messageOwner.reactions == null
                || selectedObject.messageOwner.reactions.results == null) return;
        boolean hasCustom = false;
        for (int i = 0; i < selectedObject.messageOwner.reactions.results.size(); i++) {
            org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction vr =
                    org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction.fromTL(
                            selectedObject.messageOwner.reactions.results.get(i).reaction);
            if (vr.documentId != 0) { hasCustom = true; break; }
        }
        if (!hasCustom) return;

        items.add(LocaleController.getString(R.string.AccDescrCustomEmoji));
        options.add(app.nimarkogram.messenger.utils.chats.NimarkoChatActivityHelper.OPTION_GET_CUSTOM_REACTIONS);
        icons.add(R.drawable.msg_emoji_smiles);
    }

    public static void injectNimarkoMediaDownload(
        MessageObject selectedObject,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (selectedObject == null) return;
        CharSequence text = selectedObject.messageText;
        if (text == null || text.length() == 0) return;
        if (!app.nimarkogram.messenger.media.NimarkoMediaDownloader.messageHasSupportedUrl(text)) return;

        items.add(LocaleController.getString(R.string.NM_DownloadMedia));
        options.add(app.nimarkogram.messenger.utils.chats.NimarkoChatActivityHelper.OPTION_NIMARKO_MEDIA_DOWNLOAD);
        icons.add(R.drawable.msg_download);
    }

    public static void removeItems(
        MessageObject selectedObject,
        MessageObject.GroupedMessages selectedObjectGroup,
        boolean noforwardsOrPaidMedia,
        boolean allowEdit,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options,
        ArrayList<Integer> icons
    ) {
        if (options == null || items == null || icons == null) return;
        java.util.ArrayList<Integer> toRemove = new java.util.ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            Integer opt = options.get(i);
            if (opt == null) continue;
            boolean remove = false;
            int o = opt;
            if (o == ChatActivity.OPTION_SAVE_TO_GALLERY || o == ChatActivity.OPTION_SAVE_TO_GALLERY2) {
                remove = noforwardsOrPaidMedia || !NimarkoConfig.showSaveToGallery;
            } else if (o == ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC) {
                remove = noforwardsOrPaidMedia || !NimarkoConfig.showSaveToDownloads;
            } else if (o == ChatActivity.OPTION_SHARE) {
                remove = noforwardsOrPaidMedia || !NimarkoConfig.showShare;
            } else if (o == ChatActivity.OPTION_COPY) {
                remove = noforwardsOrPaidMedia;
            } else if (o == OPTION_COPY_PHOTO) {
                remove = noforwardsOrPaidMedia || !NimarkoConfig.showCopyPhoto;
            } else if (o == OPTION_COPY_PHOTO_AS_STICKER) {
                remove = noforwardsOrPaidMedia || !NimarkoConfig.showCopyPhotoAsSticker;
            } else if (o == ChatActivity.OPTION_FORWARD) {
                remove = noforwardsOrPaidMedia || !NimarkoConfig.showForward;
            } else if (o == OPTION_FORWARD_WO_AUTHOR) {
                remove = noforwardsOrPaidMedia || !NimarkoConfig.showForwardWoAuthorship;
            }
            if (remove) toRemove.add(i);
        }
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            int idx = toRemove.get(i);
            if (idx < options.size()) options.remove(idx);
            if (idx < items.size()) items.remove(idx);
            if (idx < icons.size()) icons.remove(idx);
        }
    }

    public static void showMessageMenuItemsConfigurator(BaseFragment fragment) {
        if (fragment == null) return;

        List<MenuItemConfig> menuItems = new ArrayList<>();

        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.SaveForNotifications),
                R.drawable.msg_tone_add,
                () -> NimarkoConfig.showSaveForNotifications,
                NimarkoConfig::toggleShowSaveForNotifications,
                true
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.Reply),
                R.drawable.menu_reply,
                () -> NimarkoConfig.showReply,
                NimarkoConfig::toggleShowReply,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.SaveToGallery),
                R.drawable.msg_gallery,
                () -> NimarkoConfig.showSaveToGallery,
                NimarkoConfig::toggleShowSaveToGallery,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.NM_MI_CopyPhoto),
                R.drawable.msg_copy,
                () -> NimarkoConfig.showCopyPhoto,
                NimarkoConfig::toggleShowCopyPhoto,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.NM_MI_CopyPhotoAsSticker),
                R.drawable.msg_copy,
                () -> NimarkoConfig.showCopyPhotoAsSticker,
                NimarkoConfig::toggleShowCopyPhotoAsSticker,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.SaveToDownloads),
                R.drawable.msg_download,
                () -> NimarkoConfig.showSaveToDownloads,
                NimarkoConfig::toggleShowSaveToDownloads,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.ShareFile),
                R.drawable.msg_shareout,
                () -> NimarkoConfig.showShare,
                NimarkoConfig::toggleShowShare,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.NM_MI_ClearFromCache),
                R.drawable.msg_clear,
                () -> NimarkoConfig.showClearFromCache,
                NimarkoConfig::toggleShowClearFromCache,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.Forward),
                R.drawable.msg_forward,
                () -> NimarkoConfig.showForward,
                NimarkoConfig::toggleShowForward,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.NM_MI_ForwardWoAuthorship),
                R.drawable.msg_forward,
                () -> NimarkoConfig.showForwardWoAuthorship,
                NimarkoConfig::toggleShowForwardWoAuthorship,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.AvatarPreviewSearchMessages),
                R.drawable.msg_search,
                () -> NimarkoConfig.showViewHistory,
                NimarkoConfig::toggleShowViewHistory,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.NM_MI_SaveToSaved),
                R.drawable.msg_saved,
                () -> NimarkoConfig.showSaveMessage,
                NimarkoConfig::toggleShowSaveMessage,
                false
        ));
        menuItems.add(new MenuItemConfig(
                LocaleController.getString(R.string.ReportChat),
                R.drawable.msg_report,
                () -> NimarkoConfig.showReport,
                NimarkoConfig::toggleShowReport,
                false
        ));
        menuItems.add(new MenuItemConfig(
                "JSON",
                R.drawable.msg_info,
                () -> NimarkoConfig.showJSON,
                NimarkoConfig::toggleShowJSON,
                false
        ));

        List<String> prefTitle = new ArrayList<>();
        List<Integer> prefIcon = new ArrayList<>();
        List<Boolean> prefCheck = new ArrayList<>();
        List<Boolean> prefDivider = new ArrayList<>();
        List<Runnable> clickListener = new ArrayList<>();

        for (MenuItemConfig item : menuItems) {
            prefTitle.add(item.title);
            prefIcon.add(item.icon);
            prefCheck.add(item.isChecked.get());
            prefDivider.add(item.divider);
            clickListener.add(item.toggle);
        }

        PopupHelper.showSwitchAlert(
                LocaleController.getString(R.string.CP_MessageMenuItems),
                fragment,
                prefTitle,
                prefIcon,
                prefCheck,
                null,
                null,
                prefDivider,
                clickListener,
                null
        );
    }

    private static final class MenuItemConfig {
        final String title;
        final int icon;
        final java.util.function.Supplier<Boolean> isChecked;
        final Runnable toggle;
        final boolean divider;

        MenuItemConfig(String title, int icon, java.util.function.Supplier<Boolean> isChecked,
                       Runnable toggle, boolean divider) {
            this.title = title;
            this.icon = icon;
            this.isChecked = isChecked;
            this.toggle = toggle;
            this.divider = divider;
        }
    }
}
