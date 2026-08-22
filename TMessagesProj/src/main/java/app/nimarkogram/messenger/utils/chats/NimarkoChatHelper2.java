/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils.chats;

import static org.telegram.messenger.LocaleController.getString;

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatRightsEditActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.UndoView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.NimarkoResourcesHelper;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.security.NimarkoBiometricPrompt;
import app.nimarkogram.messenger.ui.NimarkoJsonBottomSheet;
import app.nimarkogram.messenger.utils.CGCompat;
import app.nimarkogram.messenger.utils.NimarkoProfileActivityHelper;
import app.nimarkogram.messenger.utils.ResourcesUtils;

public final class NimarkoChatHelper2 {

    private NimarkoChatHelper2() {}

    public static void forwardWithPasscode(BaseFragment fragment, long targetDialogId, Runnable action) {
        if (action == null) return;
        boolean encrypted = DialogObject.isEncryptedDialog(targetDialogId);
        if (fragment == null) {
            if (!NimarkoConfig.askBiometricsToOpenChat
                    && !(NimarkoConfig.askBiometricsToOpenEncrypted && encrypted)) {
                action.run();
            }
            return;
        }
        boolean gateChat = NimarkoConfig.askBiometricsToOpenChat
                && CGCompat.isChatLocked(fragment.getCurrentAccount(), targetDialogId);
        boolean gateEnc = NimarkoConfig.askBiometricsToOpenEncrypted && encrypted;
        if (!gateChat && !gateEnc) {
            action.run();
            return;
        }
        if (fragment.getParentActivity() == null) return;
        if (!NimarkoBiometricPrompt.canAuthenticateConfigured()) return;
        try {
            NimarkoBiometricPrompt.prompt(fragment.getParentActivity(), fragment.getCurrentAccount(), action, null);
        } catch (Throwable t) {
            FileLog.e("Nimarko forwarding authentication prompt failed closed", t);
        }
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
        if (TextUtils.isEmpty(username)) {
            for (int i = 0; i < usernames.size(); i++) {
                TLRPC.TL_username u = usernames.get(i);
                if (u != null && u.active && !TextUtils.isEmpty(u.username)) {
                    username = u.username;
                    break;
                }
            }
        }
        return username == null ? "" : username;
    }

    public static long getCustomChatID() {
        return getCustomChatID(UserConfig.selectedAccount);
    }

    public static long getCustomChatID(int account) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        return NimarkoConfig.getEffectiveSavedMessagesDialogId(account, selfId);
    }

    public static long getCustomChatID(long selfId) {
        return NimarkoConfig.getEffectiveSavedMessagesDialogId(UserConfig.selectedAccount, selfId);
    }

    public static void updateStickerSetCache(BaseFragment fragment, TLRPC.TL_messages_stickerSet stickerSet, boolean emoji, boolean isKeyboardVisible) {
        TLRPC.TL_messages_getStickerSet req = new TLRPC.TL_messages_getStickerSet();
        TLRPC.TL_inputStickerSetShortName input = new TLRPC.TL_inputStickerSetShortName();
        input.short_name = stickerSet.set.short_name;
        req.stickerset = input;

        fragment.getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
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
        }));
    }

    public static void makeReplyButtonClick(ChatActivity chatActivity, MessageObject selectedObject, boolean noForwards) {
        if (chatActivity == null) return;
        if (noForwards || chatActivity.isInScheduleMode()) {
            createReplyAction(chatActivity, selectedObject);
            return; 
        }
        switch (NimarkoConfig.actionsBarLeftButton) {
            case NimarkoConfig.ACTIONS_LEFT_REPLY:
                createReplyAction(chatActivity, selectedObject);
                break;
            case NimarkoConfig.ACTIONS_LEFT_SAVE_MESSAGE:
                createSaveMessagesSelected(chatActivity);
                break;
            case NimarkoConfig.ACTIONS_LEFT_DIRECT_SHARE:
                createShareAlertSelected(chatActivity);
                break;
            case NimarkoConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP:
                chatActivity.openForward(false, true, false);
                break;
        }
    }

    public static void makeReplyButtonLongClick(ChatActivity chatActivity, boolean noForwards, Theme.ResourcesProvider resourcesProvider) {
        if (chatActivity == null || chatActivity.getContext() == null) return;

        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Integer> values = new ArrayList<>();

        labels.add(getString(R.string.Forward));
        values.add(NimarkoConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP);

        labels.add(getString(R.string.Reply));
        values.add(NimarkoConfig.ACTIONS_LEFT_REPLY);

        labels.add(getString(R.string.NM_MI_SaveToSaved));
        values.add(NimarkoConfig.ACTIONS_LEFT_SAVE_MESSAGE);

        labels.add(getString(R.string.DirectShare));
        values.add(NimarkoConfig.ACTIONS_LEFT_DIRECT_SHARE);

        final int currentIndex = Math.max(0, values.indexOf(NimarkoConfig.actionsBarLeftButton));

        PopupHelper.showSimpleAlert(chatActivity.getContext(), resourcesProvider,
                getString(R.string.NM_MS_LeftBottomButton), 
                labels.toArray(new CharSequence[0]),
                currentIndex,
                i -> {
                    NimarkoConfig.setActionsBarLeftButton(values.get(i));

                    if (chatActivity.getActionsButtonsLayout() == null
                            || chatActivity.getActionsButtonsLayout().getReplyButton() == null) {
                        return;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        int[] location = new int[2];
                        chatActivity.getActionsButtonsLayout().getReplyButton().getLocationOnScreen(location);
                        float centerX = location[0] + chatActivity.getActionsButtonsLayout().getReplyButton().getWidth() / 2f;
                        float centerY = location[1] + chatActivity.getActionsButtonsLayout().getReplyButton().getHeight() / 2f;
                        LaunchActivity.makeRipple(centerX, centerY, 5);
                    }

                    chatActivity.getActionsButtonsLayout().updateReplyButtonUI(
                            NimarkoResourcesHelper.getLeftActionButtonText(noForwards),
                            NimarkoResourcesHelper.getLeftActionButtonDrawable(noForwards),
                            NimarkoConfig.actionsBarLeftButton != NimarkoConfig.ACTIONS_LEFT_REPLY
                    );
                    chatActivity.getActionsButtonsLayout().updateForwardButtonUI(
                            getString(R.string.Forward),
                            R.drawable.input_forward,
                            NimarkoConfig.actionsBarLeftButton == NimarkoConfig.ACTIONS_LEFT_REPLY
                    );
                });
    }

    public static void createReplyAction(ChatActivity chatActivity, MessageObject selectedObject) {
        if (chatActivity == null) return;
        if (selectedObject != null && selectedObject.messageOwner != null && selectedObject.messageOwner.noforwards) {
            return;
        }
        TLRPC.Chat currentChat = chatActivity.getCurrentChat();
        if (selectedObject != null && currentChat != null
                && (ChatObject.isNotInChat(currentChat) && !ChatObject.isMonoForum(currentChat) && !chatActivity.isThreadChat()
                    || ChatObject.isChannel(currentChat) && !ChatObject.canPost(currentChat) && !currentChat.megagroup
                    || !ChatObject.canSendMessages(currentChat))) {
            MessageObject messageObject = selectedObject;
            if (messageObject.getGroupId() != 0) {
                MessageObject.GroupedMessages group = chatActivity.getGroup(messageObject.getGroupId());
                if (group != null) {
                    messageObject = group.captionMessage;
                }
            }
            chatActivity.setReplyingMessageObject(messageObject);
            Bundle args = new Bundle();
            args.putBoolean("onlySelect", true);
            args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
            args.putBoolean("quote", true);
            args.putBoolean("reply_to", true);
            final long author = DialogObject.getPeerDialogId(selectedObject.getFromPeer());
            long clientId = UserConfig.getInstance(chatActivity.getCurrentAccount()).getClientUserId();
            if (author != 0 && author != chatActivity.getDialogId() && author != clientId && author > 0) {
                args.putLong("reply_to_author", author);
            }
            args.putInt("messagesCount", 1);
            args.putBoolean("canSelectTopics", true);
            DialogsActivity fragment = new DialogsActivity(args);
            fragment.setDelegate(chatActivity);
            chatActivity.presentFragment(fragment);
        } else {
            chatActivity.showFieldPanelForReply(selectedObject);
        }
        chatActivity.clearSelectionMode();
    }

    public static void createSaveMessagesSelected(ChatActivity chatActivity) {
        if (chatActivity == null) return;
        try {
            long chatID = getCustomChatID(chatActivity.getCurrentAccount());
            ArrayList<MessageObject> messages = collectSelectedAndClear(chatActivity);
            forwardMessages(chatActivity, messages, false, true, 0, chatID);
            chatActivity.createUndoView();
            if (chatActivity.getUndoView() == null) return;
            if (!BulletinFactory.of(chatActivity).showForwardedBulletinWithTag(chatID, messages.size())) {
                chatActivity.getUndoView().showWithAction(chatID, UndoView.ACTION_FWD_MESSAGES, messages.size());
            }
        } catch (Exception ignore) {
            chatActivity.clearSelectionMode();
        }
    }

    public static ArrayList<MessageObject> getSelectedMessages(ChatActivity chatActivity) {
        return collectSelectedAndClear(chatActivity);
    }

    private static ArrayList<MessageObject> collectSelectedAndClear(ChatActivity chatActivity) {
        ArrayList<MessageObject> fmessages = new ArrayList<>();
        if (chatActivity == null) return fmessages;

        for (int a = 1; a >= 0; a--) {
            ArrayList<Integer> ids = chatActivity.getSelectedMessagesIds(a);
            Collections.sort(ids);
            for (int b = 0; b < ids.size(); b++) {
                MessageObject mo = chatActivity.getSelectedMessage(a, ids.get(b));
                if (mo != null) {
                    fmessages.add(mo);
                }
            }
        }

        chatActivity.clearSelectionMode();
        chatActivity.updatePinnedMessageView(true);
        chatActivity.updateVisibleRows();

        return fmessages;
    }

    public static void forwardMessages(ChatActivity chatActivity, ArrayList<MessageObject> arrayList, boolean fromMyName, boolean notify, int scheduleDate, long did) {
        if (chatActivity == null || arrayList == null || arrayList.isEmpty()) return;
        if ((scheduleDate != 0) == (chatActivity.getChatMode() == ChatActivity.MODE_SCHEDULED)) {
            chatActivity.waitingForSendingMessageLoad = true;
        }
        SendMessagesHelper helper = SendMessagesHelper.getInstance(chatActivity.getCurrentAccount());
        AlertsCreator.showSendMediaAlert(
                helper.sendMessage(arrayList, did == 0 ? chatActivity.getDialogId() : did, fromMyName, false, notify, scheduleDate, null, -1, 0),
                chatActivity, chatActivity.getResourceProvider()
        );
    }

    public static void createShareAlertSelected(ChatActivity chatActivity) {
        if (chatActivity == null) return;

        final MessageObject fwd = chatActivity.getForwardingMessage();
        final MessageObject.GroupedMessages fwdGroup = chatActivity.getForwardingMessageGroup();
        final ArrayList<Integer> sel0 = chatActivity.getSelectedMessagesIds(0);
        final ArrayList<Integer> sel1 = chatActivity.getSelectedMessagesIds(1);

        if (fwd == null && sel0.isEmpty() && sel1.isEmpty()) return;

        final ArrayList<MessageObject> fmessages = new ArrayList<>();
        if (fwd != null) {
            if (fwdGroup != null) {
                fmessages.addAll(fwdGroup.messages);
            } else {
                fmessages.add(fwd);
            }
            
        } else {
            
            fmessages.addAll(collectSelectedAndClear(chatActivity));
        }

        chatActivity.hideActionMode();
        chatActivity.updatePinnedMessageView(true);
        chatActivity.updateVisibleRows();

        final ChatActivity cf = chatActivity;
        chatActivity.showDialog(new ShareAlert(
                chatActivity.getContext(), chatActivity, fmessages, null, null,
                ChatObject.isChannel(chatActivity.getCurrentChat()),
                null, null, false, false, false, null, chatActivity.getResourceProvider()
        ) {
            @Override
            public void dismissInternal() {
                super.dismissInternal();
                AndroidUtilities.requestAdjustResize(cf.getParentActivity(), cf.getClassGuid());
                if (cf.getChatActivityEnterView() != null && cf.getChatActivityEnterView().getVisibility() == View.VISIBLE) {
                    if (cf.getFragmentView() != null) cf.getFragmentView().requestLayout();
                }
            }

            @Override
            protected void onSend(LongSparseArray<TLRPC.Dialog> dids, int count, TLRPC.TL_forumTopic topic, boolean showToast) {
                cf.createUndoView();
                if (cf.getUndoView() == null || !showToast) return;
                if (dids.size() == 1) {
                    cf.getUndoView().showWithAction(dids.valueAt(0).id, UndoView.ACTION_FWD_MESSAGES, count, topic, null, null);
                } else {
                    cf.getUndoView().showWithAction(0L, UndoView.ACTION_FWD_MESSAGES, count, (Object) dids.size(), null, null);
                }
            }
        });
        AndroidUtilities.setAdjustResizeToNothing(chatActivity.getParentActivity(), chatActivity.getClassGuid());
        if (chatActivity.getFragmentView() != null) chatActivity.getFragmentView().requestLayout();
    }

    public static void injectChatActivityAvatarOnClickNew(
            ChatActivity chatActivity,
            ChatActivity.ChatMessageCellDelegate chatMessageCellDelegate,
            ChatMessageCell cell,
            TLRPC.User user,
            boolean enableMention,
            boolean enableSearchMessages
    ) {
        if (chatActivity == null || chatActivity.getContext() == null) return;

        final TLRPC.Chat currentChat = chatActivity.getCurrentChat();
        final TLRPC.ChatFull chatInfo = chatActivity.getCurrentChatInfo();

        final ArrayList<TLRPC.ChatParticipant> participants = new ArrayList<>();
        if (chatInfo != null && chatInfo.participants != null && chatInfo.participants.participants != null) {
            participants.addAll(chatInfo.participants.participants);
        }
        TLRPC.ChatParticipant participantMatch = null;
        for (int i = 0; i < participants.size(); i++) {
            if (participants.get(i).user_id == user.id) {
                participantMatch = participants.get(i);
                break;
            }
        }
        final TLRPC.ChatParticipant participant = participantMatch;
        final boolean isChatParticipant = participant != null;

        ItemOptions opts = ItemOptions.makeOptions(chatActivity, cell)
                .add(R.drawable.msg_discussion, getString(R.string.SendMessage), () -> chatMessageCellDelegate.openDialog(cell, user))
                .addIf(enableMention, R.drawable.msg_mention, getString(R.string.Mention), () -> chatMessageCellDelegate.appendMention(user))
                .addIf(enableSearchMessages, R.drawable.msg_search, getString(R.string.AvatarPreviewSearchMessages), () -> chatActivity.openSearchWithUser(user))
                .addIf(ChatObject.canBlockUsers(currentChat) && isChatParticipant, R.drawable.msg_remove, getString(R.string.KickFromGroup), () -> {
                    MessagesController mc = chatActivity.getMessagesController();
                    mc.deleteParticipantFromChat(currentChat.id, mc.getUser(user.id));
                })
                .addIf(ChatObject.hasAdminRights(currentChat) && isChatParticipant, R.drawable.msg_permissions, getString(R.string.ChangePermissions), () -> {
                    openRightsEditor(chatActivity, user, participant,   1);
                })
                .addIf(ChatObject.canAddAdmins(currentChat) && isChatParticipant, R.drawable.msg_admins, getString(R.string.EditAdminRights), () -> {
                    openRightsEditor(chatActivity, user, participant,   0);
                });

        if (participant != null && participant.date != 0) {
            opts = opts.addGap().addText(LocaleController.formatJoined((long) participant.date), 13);
        }

        opts.addGap()
                .addProfile(user, getString(R.string.ViewProfile), () -> chatMessageCellDelegate.openProfile(user))
                .setGravity(Gravity.LEFT)
                .forceBottom(true)
                .translate(0f, -AndroidUtilities.dp(48f))
                .setDrawScrim(false)
                .setBlur(true)
                .show();
    }

    private static void openRightsEditor(ChatActivity chatActivity, TLRPC.User user, TLRPC.ChatParticipant participant, int type) {
        TLRPC.Chat currentChat = chatActivity.getCurrentChat();
        TLRPC.ChatFull chatInfo = chatActivity.getCurrentChatInfo();

        TLRPC.ChannelParticipant channelParticipant = null;
        if (ChatObject.isChannel(currentChat) && participant instanceof TLRPC.TL_chatChannelParticipant) {
            channelParticipant = ((TLRPC.TL_chatChannelParticipant) participant).channelParticipant;
        }

        ChatRightsEditActivity frag = new ChatRightsEditActivity(
                user.id,
                chatInfo != null ? chatInfo.id : currentChat.id,
                channelParticipant != null ? channelParticipant.admin_rights : null,
                currentChat.default_banned_rights,
                channelParticipant != null ? channelParticipant.banned_rights : null,
                channelParticipant != null ? channelParticipant.rank : null,
                type,
                true,
                false,
                null
        );
        chatActivity.presentFragment(frag);
    }

    public static void injectChatActivityMsgSlideAction(ChatActivity chatActivity, MessageObject msg, boolean isChannel, int classGuid) {
        if (chatActivity == null || msg == null) return;
        switch (NimarkoConfig.messageSlideAction) {
            case NimarkoConfig.SLIDE_REPLY: {
                chatActivity.showFieldPanelForReply(msg);
                break;
            }
            case NimarkoConfig.SLIDE_SAVE: {
                long chatID = getCustomChatID(chatActivity.getCurrentAccount());
                ArrayList<MessageObject> one = new ArrayList<>();
                one.add(msg);
                SendMessagesHelper.getInstance(chatActivity.getCurrentAccount())
                        .sendMessage(one, chatID, false, false, true, 0, null, -1, 0);
                chatActivity.createUndoView();
                if (chatActivity.getUndoView() == null) return;
                if (!BulletinFactory.of(chatActivity).showForwardedBulletinWithTag(chatID, one.size())) {
                    chatActivity.getUndoView().showWithAction(chatID, UndoView.ACTION_FWD_MESSAGES, one.size());
                }
                break;
            }
            case NimarkoConfig.SLIDE_TRANSLATE: {
                if (msg.messageOwner == null || msg.messageOwner.message == null) return;
                String text = msg.messageOwner.message;
                String toLang = TranslateAlert2.getToLanguage();
                TranslateAlert2 alert = TranslateAlert2.showAlert(
                        chatActivity.getContext(), chatActivity, chatActivity.getCurrentAccount(),
                        "und", toLang, text, msg.messageOwner.entities, false,
                        null, () -> chatActivity.dimBehindView(false)
                );
                if (alert != null) {
                    alert.setDimBehindAlpha(100);
                    alert.setDimBehind(true);
                }
                break;
            }
            
            case NimarkoConfig.SLIDE_DIRECT_SHARE: {
                ArrayList<MessageObject> one = new ArrayList<>();
                one.add(msg);
                final ChatActivity cf = chatActivity;
                chatActivity.showDialog(new ShareAlert(chatActivity.getParentActivity(), one, null, isChannel, null, false) {
                    @Override
                    public void dismissInternal() {
                        super.dismissInternal();
                        AndroidUtilities.requestAdjustResize(cf.getParentActivity(), classGuid);
                        if (cf.getChatActivityEnterView() != null && cf.getChatActivityEnterView().getVisibility() == View.VISIBLE) {
                            if (cf.getFragmentView() != null) cf.getFragmentView().requestLayout();
                        }
                        cf.updatePinnedMessageView(true);
                    }
                });
                AndroidUtilities.setAdjustResizeToNothing(chatActivity.getParentActivity(), classGuid);
                if (chatActivity.getFragmentView() != null) chatActivity.getFragmentView().requestLayout();
                break;
            }
        }
    }

    public static void showSearchMessageFilterSelector(ChatActivity chatActivity) {
        if (chatActivity == null || chatActivity.getContext() == null) return;

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();

        labels.add(getString(R.string.NM_CH_SearchFilter_None));        values.add(NimarkoConfig.FILTER_NONE);
        labels.add(getString(R.string.NM_CH_SearchFilter_Photos));      values.add(NimarkoConfig.FILTER_PHOTOS);
        labels.add(getString(R.string.NM_CH_SearchFilter_Videos));      values.add(NimarkoConfig.FILTER_VIDEOS);
        labels.add(getString(R.string.NM_CH_SearchFilter_VoiceMessages)); values.add(NimarkoConfig.FILTER_VOICE_MESSAGES);
        labels.add(getString(R.string.NM_CH_SearchFilter_VideoMessages)); values.add(NimarkoConfig.FILTER_VIDEO_MESSAGES);
        labels.add(getString(R.string.NM_CH_SearchFilter_Files));       values.add(NimarkoConfig.FILTER_FILES);
        labels.add(getString(R.string.NM_CH_SearchFilter_Music));       values.add(NimarkoConfig.FILTER_MUSIC);
        labels.add(getString(R.string.NM_CH_SearchFilter_GIFs));        values.add(NimarkoConfig.FILTER_GIFS);
        labels.add(getString(R.string.NM_CH_SearchFilter_Geolocation)); values.add(NimarkoConfig.FILTER_GEO);
        labels.add(getString(R.string.NM_CH_SearchFilter_Contacts));    values.add(NimarkoConfig.FILTER_CONTACTS);
        labels.add(getString(R.string.NM_CH_SearchFilter_MyMentions));  values.add(NimarkoConfig.FILTER_MENTIONS);

        final int currentIndex = Math.max(0, values.indexOf(NimarkoConfig.messagesSearchFilter));

        PopupHelper.showSimpleAlert(
                chatActivity.getContext(),
                chatActivity.getResourceProvider(),
                getString(R.string.NM_CH_SearchFilter),
                labels.toArray(new CharSequence[0]),
                currentIndex,
                i -> {
                    NimarkoConfig.setMessagesSearchFilter(values.get(i));
                    chatActivity.openSearchWithText(null);
                }
        );
    }

    public static void showJsonMenu(NimarkoJsonBottomSheet sa, FrameLayout field, MessageObject messageObject) {
        if (sa == null || messageObject == null || messageObject.messageOwner == null) return;

        ItemOptions opts = ItemOptions.makeOptions(sa.getContainer(), sa.resourcesProvider, field)
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
                                BulletinFactory.of(sa.getContainer(), sa.resourcesProvider)
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
                            BulletinFactory.of(sa.getContainer(), sa.resourcesProvider)
                                    .createCopyBulletin(getString(R.string.TextCopied))
                                    .show();
                        }
                );

        final CharSequence fwdDateLabel;
        if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.document != null) {
            fwdDateLabel = "Date: ➥ " + ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.media.document.date);
        } else if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.photo != null) {
            fwdDateLabel = "Date: ➥ " + ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.media.photo.date);
        } else {
            fwdDateLabel = "Message is not forwarded.";
        }
        opts.add(R.drawable.msg_calendar2, fwdDateLabel, () -> {
            String textToCopy = "";
            if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.document != null) {
                textToCopy = ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.media.document.date);
            } else if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.photo != null) {
                textToCopy = ResourcesUtils.createDateAndTimeForJSON((long) messageObject.messageOwner.media.photo.date);
            }
            if (!textToCopy.isEmpty()) {
                AndroidUtilities.addToClipboard(textToCopy);
                BulletinFactory.of(sa.getContainer(), sa.resourcesProvider)
                        .createCopyBulletin(getString(R.string.TextCopied))
                        .show();
            }
        });

        final CharSequence dcLabel;
        if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.document != null) {
            dcLabel = "DC: " + messageObject.messageOwner.media.document.dc_id;
        } else if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.photo != null) {
            dcLabel = "DC: " + messageObject.messageOwner.media.photo.dc_id;
        } else {
            dcLabel = "DC: Available only for media.";
        }
        opts.add(R.drawable.msg_info, dcLabel, () -> {   });

        String restrictions = NimarkoProfileActivityHelper.getRestrictionReasons(messageObject.messageOwner.restriction_reason);
        if (restrictions != null && !restrictions.isEmpty()) {
            opts.addGap().addText(restrictions, 13);
        }

        opts.setDimAlpha(100)
                .translate(-AndroidUtilities.dp(15f), 0f)
                .show();
    }

    public static TLRPC.MessagesFilter getSearchFilterType() {
        switch (NimarkoConfig.messagesSearchFilter) {
            case NimarkoConfig.FILTER_PHOTOS:         return new TLRPC.TL_inputMessagesFilterPhotos();
            case NimarkoConfig.FILTER_VIDEOS:         return new TLRPC.TL_inputMessagesFilterVideo();
            case NimarkoConfig.FILTER_VOICE_MESSAGES: return new TLRPC.TL_inputMessagesFilterVoice();
            case NimarkoConfig.FILTER_VIDEO_MESSAGES: return new TLRPC.TL_inputMessagesFilterRoundVideo();
            case NimarkoConfig.FILTER_FILES:          return new TLRPC.TL_inputMessagesFilterDocument();
            case NimarkoConfig.FILTER_MUSIC:          return new TLRPC.TL_inputMessagesFilterMusic();
            case NimarkoConfig.FILTER_GIFS:           return new TLRPC.TL_inputMessagesFilterGif();
            case NimarkoConfig.FILTER_GEO:            return new TLRPC.TL_inputMessagesFilterGeo();
            case NimarkoConfig.FILTER_CONTACTS:       return new TLRPC.TL_inputMessagesFilterContacts();
            case NimarkoConfig.FILTER_MENTIONS:       return new TLRPC.TL_inputMessagesFilterMyMentions();
            default:                                  return new TLRPC.TL_inputMessagesFilterEmpty();
        }
    }
}
