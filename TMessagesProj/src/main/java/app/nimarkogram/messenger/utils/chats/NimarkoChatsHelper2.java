/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils.chats;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatJoined;
import static org.telegram.messenger.LocaleController.getString;

import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatRightsEditActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.UndoView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.ui.NimarkoJsonBottomSheet;
import app.nimarkogram.messenger.utils.NimarkoProfileActivityHelper;
import app.nimarkogram.messenger.utils.ResourcesUtils;

public final class NimarkoChatsHelper2 {

    private NimarkoChatsHelper2() {}

    public static void injectChatActivityAvatarOnClickNew(
            ChatActivity chatActivity, ChatActivity.ChatMessageCellDelegate chatMessageCellDelegate, ChatMessageCell cell, TLRPC.User user,
            boolean enableMention, boolean enableSearchMessages
    ) {
        if (chatActivity.getContext() == null) return;

        ArrayList<TLRPC.ChatParticipant> participants = new ArrayList<>();
        TLRPC.ChatFull chatInfo = chatActivity.getCurrentChatInfo();
        if (chatInfo != null && chatInfo.participants != null && chatInfo.participants.participants != null) {
            participants.addAll(chatInfo.participants.participants);
        }

        TLRPC.ChatParticipant participant = null;
        for (int i = 0; i < participants.size(); i++) {
            if (participants.get(i).user_id == user.id) {
                participant = participants.get(i);
                break;
            }
        }
        final boolean isChatParticipant = participant != null;
        final TLRPC.ChatParticipant fParticipant = participant;

        ItemOptions options = ItemOptions.makeOptions(chatActivity, cell)
                .add(R.drawable.msg_discussion, getString(R.string.SendMessage), () -> {
                    chatMessageCellDelegate.openDialog(cell, user);
                })
                .addIf(enableMention, R.drawable.msg_mention, getString(R.string.Mention), () -> {
                    chatMessageCellDelegate.appendMention(user);
                })
                .addIf(enableSearchMessages, R.drawable.msg_search, getString(R.string.AvatarPreviewSearchMessages), () -> {
                    chatActivity.openSearchWithUser(user);
                })
                .addIf(ChatObject.canBlockUsers(chatActivity.getCurrentChat()) && isChatParticipant, R.drawable.msg_remove, getString(R.string.KickFromGroup), () -> {
                    
                    chatActivity.getMessagesController().deleteParticipantFromChat(
                            chatActivity.getCurrentChat().id,
                            chatActivity.getMessagesController().getUser(user.id)
                    );
                })
                .addIf(ChatObject.hasAdminRights(chatActivity.getCurrentChat()) && isChatParticipant, R.drawable.msg_permissions, getString(R.string.ChangePermissions), () -> {
                    final int action = 1; 

                    TLRPC.ChatParticipant chatParticipant = null;
                    for (int i = 0; i < chatActivity.getCurrentChatInfo().participants.participants.size(); i++) {
                        if (chatActivity.getCurrentChatInfo().participants.participants.get(i).user_id == user.id) {
                            chatParticipant = chatActivity.getCurrentChatInfo().participants.participants.get(i);
                            break;
                        }
                    }

                    TLRPC.ChannelParticipant channelParticipant = null;

                    if (ChatObject.isChannel(chatActivity.getCurrentChat())) {
                        channelParticipant = ((TLRPC.TL_chatChannelParticipant) chatParticipant).channelParticipant;
                    }
                    
                    ChatRightsEditActivity frag = new ChatRightsEditActivity(
                            user.id,
                            chatActivity.getCurrentChatInfo().id,
                            channelParticipant != null ? channelParticipant.admin_rights : null,
                            chatActivity.getCurrentChat().default_banned_rights,
                            channelParticipant != null ? channelParticipant.banned_rights : null,
                            channelParticipant != null ? channelParticipant.rank : null,
                            action,
                            true,
                            false,
                            null
                    );
                    chatActivity.presentFragment(frag);
                })
                .addIf(ChatObject.canAddAdmins(chatActivity.getCurrentChat()) && isChatParticipant, R.drawable.msg_admins, getString(R.string.EditAdminRights), () -> {
                    final int action = 0; 

                    TLRPC.ChatParticipant chatParticipant = null;
                    for (int i = 0; i < chatActivity.getCurrentChatInfo().participants.participants.size(); i++) {
                        if (chatActivity.getCurrentChatInfo().participants.participants.get(i).user_id == user.id) {
                            chatParticipant = chatActivity.getCurrentChatInfo().participants.participants.get(i);
                            break;
                        }
                    }

                    TLRPC.ChannelParticipant channelParticipant = null;

                    if (ChatObject.isChannel(chatActivity.getCurrentChat())) {
                        channelParticipant = ((TLRPC.TL_chatChannelParticipant) chatParticipant).channelParticipant;
                    }

                    ChatRightsEditActivity frag = new ChatRightsEditActivity(
                            user.id,
                            chatActivity.getCurrentChatInfo().id,
                            channelParticipant != null ? channelParticipant.admin_rights : null,
                            chatActivity.getCurrentChat().default_banned_rights,
                            channelParticipant != null ? channelParticipant.banned_rights : null,
                            channelParticipant != null ? channelParticipant.rank : null,
                            action,
                            true,
                            false,
                            null
                    );
                    chatActivity.presentFragment(frag);
                })
                .addGapIf(fParticipant != null && fParticipant.date != 0);

        if (fParticipant != null && fParticipant.date != 0) {
            options.addText(ResourcesUtils.capitalize(formatJoined((long) fParticipant.date)), 13);
        }

        options.addGap()
                .addProfile(user, getString(R.string.ViewProfile), () -> {
                    chatMessageCellDelegate.openProfile(user);
                });

        options.setGravity(Gravity.LEFT)
                .forceBottom(true)
                .translate(0f, -AndroidUtilities.dp(48f))
                .setDrawScrim(false)
                .setBlur(true)
                .show();
    }

    public static String getActiveUsername(long userId) {
        TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(userId);
        if (user == null) return "";
        String username = null;
        ArrayList<TLRPC.TL_username> usernames = new ArrayList<>();
        if (user.usernames != null) {
            usernames.addAll(user.usernames);
        }
        if (!TextUtils.isEmpty(user.username)) {
            username = user.username;
        }
        if (user.usernames != null) {
            usernames = new ArrayList<>(user.usernames);
        }
        if (TextUtils.isEmpty(username)) {
            for (int i = 0; i < usernames.size(); i++) {
                TLRPC.TL_username u = usernames.get(i);
                if (u != null && u.active && !TextUtils.isEmpty(u.username)) {
                    username = u.username;
                    break;
                }
            }
        }
        return username != null ? username : "";
    }
     
    public static TLRPC.MessagesFilter getSearchFilterType() {
        TLRPC.MessagesFilter filter;
        switch (NimarkoConfig.messagesSearchFilter) {
            case NimarkoConfig.FILTER_PHOTOS:
                filter = new TLRPC.TL_inputMessagesFilterPhotos();
                break;
            case NimarkoConfig.FILTER_VIDEOS:
                filter = new TLRPC.TL_inputMessagesFilterVideo();
                break;
            case NimarkoConfig.FILTER_VOICE_MESSAGES:
                filter = new TLRPC.TL_inputMessagesFilterVoice();
                break;
            case NimarkoConfig.FILTER_VIDEO_MESSAGES:
                filter = new TLRPC.TL_inputMessagesFilterRoundVideo();
                break;
            case NimarkoConfig.FILTER_FILES:
                filter = new TLRPC.TL_inputMessagesFilterDocument();
                break;
            case NimarkoConfig.FILTER_MUSIC:
                filter = new TLRPC.TL_inputMessagesFilterMusic();
                break;
            case NimarkoConfig.FILTER_GIFS:
                filter = new TLRPC.TL_inputMessagesFilterGif();
                break;
            case NimarkoConfig.FILTER_GEO:
                filter = new TLRPC.TL_inputMessagesFilterGeo();
                break;
            case NimarkoConfig.FILTER_CONTACTS:
                filter = new TLRPC.TL_inputMessagesFilterContacts();
                break;
            case NimarkoConfig.FILTER_MENTIONS:
                filter = new TLRPC.TL_inputMessagesFilterMyMentions();
                break;
            default:
                filter = new TLRPC.TL_inputMessagesFilterEmpty();
                break;
        }
        return filter;
    }
     
    public static long getCustomChatID() {
        return getCustomChatID(UserConfig.selectedAccount);
    }

    public static long getCustomChatID(int account) {
        long selfId = UserConfig.getInstance(account).clientUserId;
        return NimarkoConfig.getEffectiveSavedMessagesDialogId(account, selfId);
    }
     
    private static final Map<ShareAlert, ForwardMenuState> forwardMenuStates = new WeakHashMap<>();

    private static final class ForwardMenuState {
        private boolean authorship = true;
        private boolean captions = true;
        private boolean notify = true;

        synchronized boolean hasAuthorship() {
            return authorship;
        }

        synchronized boolean hasCaptions() {
            return captions;
        }

        synchronized boolean shouldNotify() {
            return notify;
        }

        synchronized void toggleAuthorship() {
            authorship = !authorship;
        }

        synchronized void toggleCaptions() {
            captions = !captions;
        }

        synchronized void toggleNotify() {
            notify = !notify;
        }
    }

    public static void showForwardMenu(ShareAlert sa, View scrimView) {
        final ForwardMenuState state;
        synchronized (forwardMenuStates) {
            ForwardMenuState existing = forwardMenuStates.get(sa);
            if (existing == null) {
                existing = new ForwardMenuState();
                forwardMenuStates.put(sa, existing);
            }
            state = existing;
        }
        
        ItemOptions.makeOptions(sa.container, null, scrimView)
                .addChecked(
                        state.hasAuthorship(),
                        getString(R.string.CG_FwdMenu_Authorship),
                        state::toggleAuthorship
                )
                .addChecked(
                        state.hasCaptions(),
                        getString(R.string.CG_FwdMenu_Captions),
                        state::toggleCaptions
                )
                .addChecked(
                        state.shouldNotify(),
                        getString(R.string.CG_FwdMenu_Notify),
                        state::toggleNotify
                )
                .setDimAlpha(100)
                .translate(-dp(10f), dp(5f))
                .show();
    }
     
    public static void showJsonMenu(NimarkoJsonBottomSheet sa, FrameLayout field, MessageObject messageObject) {
        String fwdDateLabel;
        if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.document != null) {
            fwdDateLabel = "Date: ➥ " + ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.media.document.date);
        } else if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.photo != null) {
            fwdDateLabel = "Date: ➥ " + ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.media.photo.date);
        } else {
            fwdDateLabel = "Message is not forwarded.";
        }
        String dcLabel;
        if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.document != null) {
            dcLabel = "DC: " + messageObject.messageOwner.media.document.dc_id;
        } else if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.photo != null) {
            dcLabel = "DC: " + messageObject.messageOwner.media.photo.dc_id;
        } else {
            dcLabel = "DC: Available only for media.";
        }
        ItemOptions options = ItemOptions.makeOptions(sa.container, sa.resourcesProvider, field)
                .addIf(
                        !(messageObject.messageOwner instanceof TLRPC.TL_messageService),
                        R.drawable.msg_info,
                        sa.isJacksonSupportedAndEnabled() ? "Switch to GSON" : "Switch to Jackson",
                        () -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                NimarkoConfig.toggleJacksonJSON_Provider();
                                sa.dismiss();
                                NimarkoJsonBottomSheet.showAlert(sa.getContext(), sa.resourcesProvider, sa.fragment, messageObject, null);
                            } else {
                                BulletinFactory.of(sa.container, sa.resourcesProvider)
                                        .createSimpleBulletin(R.raw.error, "Jackson library is supported on Android 8 and newer.")
                                        .show();
                            }
                        }
                )
                .add(
                        R.drawable.msg_calendar2,
                        "Date: " + ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.date),
                        () -> {
                            AndroidUtilities.addToClipboard(ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.date));
                            BulletinFactory.of(sa.container, sa.resourcesProvider)
                                    .createCopyBulletin(getString(R.string.TextCopied))
                                    .show();
                        }
                )
                .addIf(messageObject.messageOwner != null,
                        R.drawable.msg_calendar2,
                        fwdDateLabel,
                        () -> {
                            String textToCopy = "";
                            if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.document != null) {
                                textToCopy = ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.media.document.date);
                            } else if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.photo != null) {
                                textToCopy = ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.media.photo.date);
                            }
                            if (!textToCopy.isEmpty()) {
                                AndroidUtilities.addToClipboard(textToCopy);
                                BulletinFactory.of(sa.container, sa.resourcesProvider)
                                        .createCopyBulletin(getString(R.string.TextCopied))
                                        .show();
                            }
                        }
                )
                .addIf(messageObject.messageOwner != null,
                        R.drawable.msg_info,
                        dcLabel,
                        () -> {}
                )
                .addGapIf(messageObject.messageOwner != null
                        && messageObject.messageOwner.restriction_reason != null
                        && hasRestrictionReasons(messageObject));

        if (messageObject.messageOwner != null
                && messageObject.messageOwner.restriction_reason != null
                && hasRestrictionReasons(messageObject)) {
            options.addText(NimarkoProfileActivityHelper.getRestrictionReasons(messageObject.messageOwner.restriction_reason), 13);
        }

        options.setDimAlpha(100)
                .translate(-AndroidUtilities.dp(15f), 0f)
                .show();
    }

    private static boolean hasRestrictionReasons(MessageObject messageObject) {
        String s = NimarkoProfileActivityHelper.getRestrictionReasons(messageObject.messageOwner.restriction_reason);
        return s != null && !s.isEmpty();
    }
     
    public static void injectChatActivityMsgSlideAction(ChatActivity cf, MessageObject msg, boolean isChannel, int classGuid) {
        switch (NimarkoConfig.messageSlideAction) {
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_REPLY:
                cf.showFieldPanelForReply(msg);
                break;
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_SAVE: {
                long chatID = getCustomChatID(cf.getCurrentAccount());

                ArrayList<MessageObject> one = new ArrayList<>(Collections.singletonList(msg));
                cf.getSendMessagesHelper().sendMessage(one, chatID, false, false, true, 0, null, -1, 0);

                cf.createUndoView();
                if (cf.getUndoView() == null) {
                    return;
                }
                if (!BulletinFactory.of(cf).showForwardedBulletinWithTag(chatID, one.size())) {
                    cf.getUndoView().showWithAction(chatID, UndoView.ACTION_FWD_MESSAGES, one.size());
                }
                break;
            }
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_TRANSLATE: {
                
                String text = msg.messageOwner.message;
                String toLang = TranslateAlert2.getToLanguage();
                TranslateAlert2 alert = TranslateAlert2.showAlert(
                        cf.getContext(),
                        cf,
                        cf.getCurrentAccount(),
                        "und",
                        toLang,
                        text,
                        msg.messageOwner.entities,
                        false,
                        null,
                        () -> cf.dimBehindView(false)
                );
                if (alert != null) {
                    alert.setDimBehindAlpha(100);
                    alert.setDimBehind(true);
                }
                break;
            }
            
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE: {
                cf.showDialog(new ShareAlert(cf.getParentActivity(),
                        new ArrayList<>(Collections.singletonList(msg)),
                        null, isChannel, null, false) {
                    @Override
                    public void dismissInternal() {
                        super.dismissInternal();
                        AndroidUtilities.requestAdjustResize(cf.getParentActivity(), classGuid);
                        if (cf.getChatActivityEnterView() != null && cf.getChatActivityEnterView().getVisibility() == View.VISIBLE) {
                            cf.fragmentView.requestLayout();
                        }
                        cf.updatePinnedMessageView(true);
                    }
                });

                AndroidUtilities.setAdjustResizeToNothing(cf.getParentActivity(), classGuid);
                cf.fragmentView.requestLayout();
                break;
            }
        }
    }
     
    public static void updateStickerSetCache(BaseFragment fragment, TLRPC.TL_messages_stickerSet stickerSet, boolean emoji, boolean isKeyboardVisible) {
        TLRPC.TL_messages_getStickerSet req = new TLRPC.TL_messages_getStickerSet();
        TLRPC.TL_inputStickerSetShortName input = new TLRPC.TL_inputStickerSetShortName();
        input.short_name = stickerSet.set.short_name;
        req.stickerset = input;

        fragment.getConnectionsManager().sendRequest(req, (res, err) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (res instanceof TLRPC.TL_messages_stickerSet) {
                    fragment.getMediaDataController().putStickerSet((TLRPC.TL_messages_stickerSet) res, true);

                    if (fragment.getParentActivity() == null || fragment.getContext() == null) return;

                } else {
                    BulletinFactory.of(fragment)
                            .createSimpleBulletin(
                                    R.raw.error,
                                    getString(emoji ? R.string.AddEmojiNotFound : R.string.AddStickersNotFound)
                            )
                            .show(true);
                }
            });
        });
    }
     
}
