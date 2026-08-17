/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils.chats;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.AccountFrozenAlert;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChannelAdminLogActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatReactionsEditActivity;
import org.telegram.ui.ChatUsersActivity;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.Reactions.ChatCustomReactionsEditActivity;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.StatisticActivity;
import org.telegram.ui.web.SearchEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReferenceArray;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.security.NimarkoBiometricPrompt;
import app.nimarkogram.messenger.ui.NimarkoJsonBottomSheet;
import app.nimarkogram.messenger.utils.LockedChats;

public class NimarkoChatActivityHelper extends BaseController {

    private static final AtomicReferenceArray<NimarkoChatActivityHelper> Instance =
            new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);

    public NimarkoChatActivityHelper(int num) {
        super(num);
    }

    public static NimarkoChatActivityHelper getInstance(int num) {
        NimarkoChatActivityHelper localInstance = Instance.get(num);
        if (localInstance == null) {
            synchronized (NimarkoChatActivityHelper.class) {
                localInstance = Instance.get(num);
                if (localInstance == null) {
                    localInstance = new NimarkoChatActivityHelper(num);
                    Instance.set(num, localInstance);
                }
            }
        }
        return localInstance;
    }

    public final static int OPTION_JUMP_TO_BEGINNING = 2000;
    public final static int OPTION_DELETE_ALL_FROM_SELF = 2001;
    public final static int OPTION_UPGRADE_GROUP = 2002;
    public final static int OPTION_TEXT_MENTION = 2003;
    public final static int OPTION_SELECT_BETWEEN = 2004;
    public final static int OPTION_FOR_ADMINS_REACTIONS = 2006;
    public final static int OPTION_FOR_ADMINS_PERMISSIONS = 2007;
    public final static int OPTION_FOR_ADMINS_ADMINISTRATORS = 2008;
    public final static int OPTION_FOR_ADMINS_MEMBERS = 2009;
    public final static int OPTION_FOR_ADMINS_STATISTICS = 2010;
    public final static int OPTION_FOR_ADMINS_RECENT_ACTIONS = 2011;
    public final static int OPTION_DOWNLOAD_STICKER = 2016;
    public final static int OPTION_FORWARD_WO_CAPTION = 2018;
    public final static int OPTION_GET_CUSTOM_REACTIONS = 2019;
    public final static int OPTION_DETAILS = 2021;
    public final static int OPTION_TRANSLATE_DOUBLE_TAP = 2022;
    public final static int OPTION_TEXT_CODE = 2023;
    public final static int OPTION_GO_TO_SAVED = 2024;
    public final static int OPTION_ASK_PASSCODE = 2025;
    public final static int OPTION_DO_NOT_ASK_PASSCODE = 2026;
    public final static int OPTION_OPEN_TELEGRAM_BROWSER = 2027;
    public final static int OPTION_ADVANCED_SEARCH = 2033;
     
    public final static int OPTION_NIMARKO_MEDIA_DOWNLOAD = 2050;

    public void checkActionBarOptions(
            int id,
            ChatActivity chatActivity, ActionBarMenuItem headerItem,
            ArrayList<MessageObject> messages,
            SparseArray<MessageObject>[] selectedMessagesIds,
            long mergeDialogId, int editTextStart, int editTextEnd,
            TLRPC.TL_forumTopic forumTopic, TLRPC.Chat currentChat
    ) {
        if (id == OPTION_ADVANCED_SEARCH) {
            createSearchWithIDAlert(chatActivity);
        } else if (id == OPTION_JUMP_TO_BEGINNING) {
            chatActivity.jumpToDate(2);
        } else if (id == OPTION_DELETE_ALL_FROM_SELF) {
            
            NimarkoMessageHelper.getInstance(currentAccount).createDeleteHistoryAlert(
                    chatActivity, currentChat, forumTopic, mergeDialogId, chatActivity.getResourceProvider());
        } else if (id == OPTION_UPGRADE_GROUP) {
            AlertDialog.Builder builder = new AlertDialog.Builder(chatActivity.getParentActivity());
            builder.setMessage(getString(R.string.ConvertGroupAlert));
            builder.setTitle(getString(R.string.Warning));
            builder.setPositiveButton(getString(R.string.OK), (dialogInterface, i) -> getMessagesController().convertToMegaGroup(chatActivity.getParentActivity(), currentChat.id, chatActivity, chatNew -> {
                if (chatNew != 0) {
                    getMessagesController().toggleChannelInvitesHistory(chatNew, false);
                }
            }));
            builder.setNegativeButton(getString(R.string.Cancel), null);
            chatActivity.showDialog(builder.create());
        } else if (id == OPTION_TEXT_MENTION) {
            if (chatActivity.getChatActivityEnterView() != null && chatActivity.getChatActivityEnterView().getEditField() != null) {
                chatActivity.getChatActivityEnterView().getEditField().setSelectionOverride(editTextStart, editTextEnd);
                
                chatActivity.getChatActivityEnterView().getEditField().makeSelectedMention();
            }
        } else if (id == OPTION_SELECT_BETWEEN) {
            ArrayList<Integer> ids = new ArrayList<>();
            for (int a = 1; a >= 0; a--) {
                for (int b = 0; b < selectedMessagesIds[a].size(); b++) {
                    ids.add(selectedMessagesIds[a].keyAt(b));
                }
            }
            Collections.sort(ids);
            if (ids.size() < 2) return;
            Integer begin = ids.get(0);
            Integer end = ids.get(ids.size() - 1);
            for (int i = 0; i < messages.size(); i++) {
                int msgId = messages.get(i).getId();
                if (msgId > begin && msgId < end && !(selectedMessagesIds[0].indexOfKey(msgId) >= 0)) {
                    chatActivity.addToSelectedMessages(messages.get(i), false);
                    chatActivity.updateActionModeTitle();
                    chatActivity.updateVisibleRows();
                }
            }
        } else if (id == OPTION_FOR_ADMINS_REACTIONS) {
            TLRPC.ChatFull info = MessagesStorage.getInstance(currentAccount).loadChatInfo(currentChat.id, ChatObject.isChannel(currentChat), new CountDownLatch(1), false, false);

            if (info == null) return;
            if (ChatObject.isChannelAndNotMegaGroup(currentChat)) {
                chatActivity.presentFragment(new ChatCustomReactionsEditActivity(currentChat.id, info));
            } else {
                Bundle args = new Bundle();
                args.putLong(ChatReactionsEditActivity.KEY_CHAT_ID, currentChat.id);
                ChatReactionsEditActivity reactionsEditActivity = new ChatReactionsEditActivity(args);
                reactionsEditActivity.setInfo(info);
                chatActivity.presentFragment(reactionsEditActivity);
            }
        } else if (id == OPTION_FOR_ADMINS_PERMISSIONS) {
            Bundle args = new Bundle();
            args.putLong("chat_id", currentChat.id);
            args.putInt("type", !(ChatObject.isChannel(currentChat) && !currentChat.megagroup) && !currentChat.gigagroup ? ChatUsersActivity.TYPE_KICKED : ChatUsersActivity.TYPE_BANNED);
            ChatUsersActivity fragment = new ChatUsersActivity(args);
            fragment.setInfo(getMessagesController().getChatFull(currentChat.id));
            chatActivity.presentFragment(fragment);
        } else if (id == OPTION_FOR_ADMINS_ADMINISTRATORS) {
            Bundle args = new Bundle();
            args.putLong("chat_id", currentChat.id);
            args.putInt("type", ChatUsersActivity.TYPE_ADMIN);
            ChatUsersActivity fragment = new ChatUsersActivity(args);
            fragment.setInfo(getMessagesController().getChatFull(currentChat.id));
            chatActivity.presentFragment(fragment);
        } else if (id == OPTION_FOR_ADMINS_MEMBERS) {
            Bundle args = new Bundle();
            args.putLong("chat_id", currentChat.id);
            args.putInt("type", ChatUsersActivity.TYPE_USERS);
            ChatUsersActivity fragment = new ChatUsersActivity(args);
            fragment.setInfo(getMessagesController().getChatFull(currentChat.id));
            chatActivity.presentFragment(fragment);
        } else if (id == OPTION_FOR_ADMINS_STATISTICS) {
            chatActivity.presentFragment(StatisticActivity.create(currentChat, false));
        } else if (id == OPTION_FOR_ADMINS_RECENT_ACTIONS) {
            chatActivity.presentFragment(new ChannelAdminLogActivity(currentChat));
        } else if (id == OPTION_TEXT_CODE) {
            if (chatActivity.getChatActivityEnterView() != null && chatActivity.getChatActivityEnterView().getEditField() != null) {
                chatActivity.getChatActivityEnterView().getEditField().setSelectionOverride(editTextStart, editTextEnd);
                
                chatActivity.getChatActivityEnterView().getEditField().makeSelectedCode();
            }
        } else if (id == OPTION_GO_TO_SAVED) {
            chatActivity.presentFragment(ChatActivity.of(NimarkoChatHelper2.getCustomChatID(currentAccount)));
        } else if (id == OPTION_ASK_PASSCODE) {
            
            final int account = currentAccount;
            final long ownerUid = UserConfig.getInstance(account).getClientUserId();
            final long dialogId = chatActivity.getDialogId();
            NimarkoBiometricPrompt.prompt(chatActivity.getParentActivity(), account, () -> {
                if (DialogObject.isUserDialog(dialogId) || DialogObject.isChatDialog(dialogId)) {
                    if (!LockedChats.isLocked(account, dialogId)
                            && LockedChats.setLocked(account, ownerUid, dialogId, true)) {
                        if (headerItem != null) headerItem.hideSubItem(OPTION_ASK_PASSCODE);
                        if (BuildVars.DEBUG_PRIVATE_VERSION) {
                            FileLog.d("NG locked chats now contains: " + dialogId);
                        }
                    }
                }
            }, null);
        } else if (id == OPTION_DO_NOT_ASK_PASSCODE) {
            
            final int account = currentAccount;
            final long ownerUid = UserConfig.getInstance(account).getClientUserId();
            final long dialogId = chatActivity.getDialogId();
            NimarkoBiometricPrompt.prompt(chatActivity.getParentActivity(), account, () -> {
                if (DialogObject.isUserDialog(dialogId) || DialogObject.isChatDialog(dialogId)) {
                    if (LockedChats.isLocked(account, dialogId)
                            && LockedChats.setLocked(account, ownerUid, dialogId, false)) {
                        if (headerItem != null) headerItem.hideSubItem(OPTION_DO_NOT_ASK_PASSCODE);
                        if (BuildVars.DEBUG_PRIVATE_VERSION) {
                            FileLog.d("NG locked chats removed: " + dialogId);
                        }
                    }
                }
            }, null);
        } else if (id == OPTION_OPEN_TELEGRAM_BROWSER) {
            Browser.openInTelegramBrowser(chatActivity.getParentActivity(), SearchEngine.getCurrent().getSearchURL(""), null);
        }
    }
     
    public void checkProcessSelectedOption(
            int option,
            ChatActivity chatActivity,
            MessageObject selectedObject, MessageObject.GroupedMessages selectedObjectGroup,
            long threadMessageId,
            TLRPC.Chat currentChat
    ) {
        switch (option) {
            
            case OPTION_FORWARD_WO_CAPTION: {
                if (getMessagesController().isFrozen()) {
                    AccountFrozenAlert.show(currentAccount);
                    return;
                }
                chatActivity.forwardingMessage = selectedObject;
                chatActivity.forwardingMessageGroup = selectedObjectGroup;
                Bundle args = new Bundle();
                args.putBoolean("onlySelect", true);
                args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
                args.putInt("messagesCount", 1);
                args.putInt("hasPoll", chatActivity.forwardingMessage.isPoll() ? (chatActivity.forwardingMessage.isPublicPoll() ? 2 : 1) : 0);
                args.putBoolean("hasInvoice", chatActivity.forwardingMessage.isInvoice());
                args.putBoolean("canSelectTopics", true);
                ChatActivity.putNimarkoForwardOptions(args, true, true);
                DialogsActivity fragment = new DialogsActivity(args);
                fragment.setDelegate(chatActivity);
                chatActivity.presentFragment(fragment);
                break;
            }
            
            case OPTION_DETAILS: {
                
                if (selectedObject != null && selectedObject.messageOwner != null) {
                    try {
                        NimarkoJsonBottomSheet.getMessageId(selectedObject);
                        NimarkoJsonBottomSheet.showAlert(
                                chatActivity.getContext(),
                                chatActivity.getResourceProvider(),
                                chatActivity,
                                selectedObject,
                                currentChat
                        );
                    } catch (Throwable t) {
                        FileLog.e(t);
                        try {
                            AndroidUtilities.addToClipboard(selectedObject.messageOwner.toString());
                            BulletinFactory.global()
                                    .createSuccessBulletin(getString(R.string.NM_MI_CopyPhoto), chatActivity.getResourceProvider())
                                    .setDuration(Bulletin.DURATION_SHORT)
                                    .show();
                        } catch (Throwable ignored) {}
                    }
                }
                break;
            }
            case OPTION_GET_CUSTOM_REACTIONS: {
                
                if (selectedObject.messageOwner == null
                        || selectedObject.messageOwner.reactions == null
                        || selectedObject.messageOwner.reactions.results == null) break;
                ArrayList<TLRPC.InputStickerSet> stickerSets = new ArrayList<>();
                HashSet<Long> setIds = new HashSet<>();
                for (int idx = 0; idx < selectedObject.messageOwner.reactions.results.size(); idx++) {
                    try {
                        ReactionsLayoutInBubble.VisibleReaction vr =
                                ReactionsLayoutInBubble.VisibleReaction.fromTL(
                                        selectedObject.messageOwner.reactions.results.get(idx).reaction);
                        if (vr.documentId == 0) continue;
                        TLRPC.Document doc = AnimatedEmojiDrawable.findDocument(currentAccount, vr.documentId);
                        if (doc == null) continue;
                        TLRPC.InputStickerSet stickerSet = MessageObject.getInputStickerSet(doc);
                        if (stickerSet == null) continue;
                        if (setIds.contains(stickerSet.id)) continue;
                        stickerSets.add(stickerSet);
                        setIds.add(stickerSet.id);
                    } catch (Throwable ignored) {}
                }
                if (stickerSets.isEmpty()) break;
                EmojiPacksAlert alert = new EmojiPacksAlert(chatActivity, chatActivity.getParentActivity(), chatActivity.getResourceProvider(), stickerSets) {
                    @Override
                    public void dismiss() {
                        super.dismiss();
                        chatActivity.dimBehindView(false);
                    }
                };
                alert.setCalcMandatoryInsets(chatActivity.isKeyboardVisible());
                alert.setDimBehind(false);
                chatActivity.closeMenu(false);
                chatActivity.showDialog(alert);
                break;
            }
            case OPTION_DOWNLOAD_STICKER: {
                
                if ((Build.VERSION.SDK_INT <= Build.VERSION_CODES.P || BuildVars.NO_SCOPED_STORAGE) && chatActivity.getParentActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    chatActivity.getParentActivity().requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
                    return;
                }
                com.exteragram.messenger.utils.chats.ChatUtils.getInstance(currentAccount)
                        .saveStickerToGallery(chatActivity.getParentActivity(), selectedObject.getDocument(), (uri) -> {
                    if (BulletinFactory.canShowBulletin(chatActivity)) {
                        BulletinFactory.of(chatActivity)
                                .createDownloadBulletin(BulletinFactory.FileType.STICKER, chatActivity.getResourceProvider())
                                .show();
                    }
                });
                break;
            }
            case OPTION_TRANSLATE_DOUBLE_TAP: {
                
                String toLang = TranslateAlert2.getToLanguage();

                boolean noforwards = getMessagesController().isChatNoForwards(currentChat) || selectedObject.messageOwner.noforwards || chatActivity.getDialogId() == UserObject.VERIFY;
                boolean noforwardsOrPaidMedia = noforwards || selectedObject.type == MessageObject.TYPE_PAID_MEDIA;

                int[] sourceMessageId = {selectedObject.getId()};
                CharSequence text = selectedObject.getMessageTextToTranslate(selectedObjectGroup, sourceMessageId);
                if (text == null) break;
                MessageObject translationSource = NimarkoChatHelper.getTranslationSource(
                        selectedObject, selectedObjectGroup, sourceMessageId[0]);
                String fromLang = translationSource != null && translationSource.messageOwner != null
                        ? translationSource.messageOwner.originalLanguage : null;
                ArrayList<TLRPC.MessageEntity> entities = NimarkoChatHelper.getTranslationEntities(
                        selectedObject, selectedObjectGroup, sourceMessageId[0], text);
                TranslateAlert2 alert = TranslateAlert2.showAlert(
                        chatActivity.getContext(),
                        chatActivity,
                        currentAccount,
                        fromLang == null ? "und" : fromLang,
                        toLang,
                        text,
                        entities,
                        noforwardsOrPaidMedia,
                        null,
                        () -> chatActivity.dimBehindView(false)
                );
                if (alert != null) chatActivity.dimBehindView(true);
                break;
            }
        }
    }
     
    private void searchWithID(ChatActivity chatActivity, String inputID) {
        if (inputID.length() > 20) {
            AlertDialog.Builder builder = new AlertDialog.Builder(chatActivity.getContext());
            builder.setTitle(getString(R.string.AvatarPreviewSearchMessages));
            builder.setMessage(LocaleController.getString(R.string.InvalidFormatError));
            builder.setPositiveButton(getString(R.string.Close), null);
            builder.show();
            return;
        }
        long chatID;
        try {
            chatID = Long.parseLong(inputID);
        } catch (NumberFormatException nfe) {
            return;
        }

        TLRPC.User user = getMessagesController().getUser(chatID);
        TLRPC.Chat chat = getMessagesController().getChat(chatID);

        if (chat == null && inputID.startsWith("100") && inputID.length() > 10) {
            chatID = Long.parseLong(inputID.substring(3));
            chat = getMessagesController().getChat(chatID);
        }

        if (user != null) {
            chatActivity.openSearchWithText("");
        } else if (chat != null) {
            chatActivity.openSearchWithText("");
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(chatActivity.getContext());
            builder.setTitle(getString(R.string.AvatarPreviewSearchMessages));
            builder.setMessage(LocaleController.formatString(R.string.NoResultFoundFor, chatID));
            builder.setPositiveButton(getString(R.string.Close), null);
            builder.show();
        }
    }

    private void createSearchWithIDAlert(ChatActivity chatActivity) {
        AlertDialog.Builder builder = new AlertDialog.Builder(chatActivity.getContext());
        builder.setTitle(getString(R.string.AvatarPreviewSearchMessages));

        final EditTextBoldCursor editText = new EditTextBoldCursor(chatActivity.getContext());
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setHintTextColor(chatActivity.getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setTextColor(chatActivity.getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setBackground(Theme.createEditTextDrawable(chatActivity.getContext(), true));
        editText.setPadding(0, 0, 0, 0);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setHint("ID");
        editText.setCursorColor(chatActivity.getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setFocusable(true);
        editText.requestFocus();
        builder.setView(editText);

        builder.setPositiveButton(getString(R.string.Search), (dialogInterface, i) -> {
            AndroidUtilities.hideKeyboard(editText);
            CharSequence editable = editText.getText();
            if (!TextUtils.isEmpty(editable)) {
                searchWithID(chatActivity, editable.toString().replaceAll("[^0-9-]", ""));
            }
        });

        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> AndroidUtilities.hideKeyboard(editText));

        builder.show().setOnShowListener(dialog -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });

        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) editText.getLayoutParams();
        if (layoutParams != null) {
            if (layoutParams instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) layoutParams).gravity = Gravity.CENTER_HORIZONTAL;
            }
            layoutParams.rightMargin = layoutParams.leftMargin = AndroidUtilities.dp(24);
            layoutParams.height = AndroidUtilities.dp(36);
            layoutParams.bottomMargin = AndroidUtilities.dp(15);
            editText.setLayoutParams(layoutParams);
        }
        editText.setSelection(0, editText.getText().length());
    }

    public static void updateMultipleSelection(ActionBarMenu actionMode, ChatActivity chatActivity) {
        if (actionMode == null || chatActivity == null) {
            return;
        }
        View item = actionMode.getItem(OPTION_SELECT_BETWEEN);
        if (item == null) {
            return;
        }
        final boolean show = chatActivity.getSelectedMessagesIds(0).size() > 1;
        item.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void checkDoubleTapOptions(ChatActivity chatActivity) {
        switch (NimarkoConfig.doubleTapAction) {
            case NimarkoConfig.DOUBLE_TAP_ACTION_TRANSLATE:
                chatActivity.processSelectedOption(OPTION_TRANSLATE_DOUBLE_TAP);
                break;
            case NimarkoConfig.DOUBLE_TAP_ACTION_REPLY:
                chatActivity.processSelectedOption(ChatActivity.OPTION_REPLY);
                break;
            case NimarkoConfig.DOUBLE_TAP_ACTION_SAVE:
                
                chatActivity.processSelectedOption(
                        app.nimarkogram.messenger.NimarkoMessageMenuInjector.OPTION_SAVE_MESSAGE_CHAT);
                break;
            case NimarkoConfig.DOUBLE_TAP_ACTION_EDIT:
                chatActivity.processSelectedOption(ChatActivity.OPTION_EDIT);
                break;
        }
    }
     
    public void forwardWithPasscode(
            DialogsActivity fragment,
            ChatActivity chatActivityOrg,
            ChatActivity chatActivityNew,
            ChatActivity.ReplyQuote replyingQuote,
            ChatActivityEnterView chatActivityEnterView,
            ArrayList<MessageObject> fmessages,
            MessageObject replyingMessageObject,
            MessageObject replyingTopMessage,
            MessageObject threadMessageObject,
            MessageObject.GroupedMessages replyingQuoteGroup
    ) {
        if (chatActivityOrg.presentFragment(chatActivityNew, true)) {
            if (fragment.isQuote && replyingMessageObject != null) {
                if (chatActivityEnterView != null && chatActivityNew.getChatActivityEnterView() != null) {
                    chatActivityNew.getChatActivityEnterView().setFieldText(
                            chatActivityEnterView.getFieldText()
                    );
                }
                if (replyingQuoteGroup != null) {
                    chatActivityNew.replyingQuoteGroup = replyingQuoteGroup;
                } else if (replyingMessageObject != null) {
                    chatActivityNew.replyingQuoteGroup = chatActivityOrg.getGroup(replyingMessageObject.getGroupId());
                }
                if (replyingTopMessage != null) {
                    chatActivityNew.replyingTopMessage = replyingTopMessage;
                } else if (threadMessageObject != null) {
                    chatActivityNew.replyingTopMessage = threadMessageObject;
                }
                chatActivityNew.onHideFieldPanelRunnable = () -> {
                    if (chatActivityEnterView != null) {
                        chatActivityEnterView.hideTopView(true);
                    }
                };
                chatActivityNew.showFieldPanelForReplyQuote(replyingMessageObject, replyingQuote);
            } else {
                chatActivityNew.showFieldPanelForForward(true, fmessages);
            }
            if (chatActivityNew.getDialogId() == chatActivityOrg.getDialogId() && !AndroidUtilities.isTablet()) {
                chatActivityOrg.removeSelfFromStack();
            }
        } else {
            fragment.finishFragment();
        }
    }

    public void openDiscussion(ChatActivity chatActivity) {
        if (!chatActivity.showDiscussInsteadOfMute()) {
            return;
        }
        Bundle args = new Bundle();
        args.putLong("chat_id", chatActivity.chatInfo.linked_chat_id);
        if (!getMessagesController().checkCanOpenChat(args, chatActivity)) {
            return;
        }
        chatActivity.presentFragment(new ChatActivity(args));
    }

    public static class KeyboardHiderOnFastScroll {

        public static void attachTo(@NonNull RecyclerView recyclerView, @NonNull View contentView, ChatActivityEnterView chatActivityEnterView) {
            if (recyclerView == null || contentView == null || chatActivityEnterView == null) return;
            final int VELOCITY_THRESHOLD = dp(NimarkoConfig.hideKeyboardOnScrollIntensity * 1000); 
            final int invertedSensitivity = dp(10000)   - VELOCITY_THRESHOLD + 1;

            recyclerView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
                private VelocityTracker velocityTracker = null;

                @Override
                public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            if (velocityTracker == null) {
                                velocityTracker = VelocityTracker.obtain();
                            } else {
                                velocityTracker.clear();
                            }
                            velocityTracker.addMovement(e);
                            break;

                        case MotionEvent.ACTION_MOVE:
                            if (velocityTracker != null) {
                                velocityTracker.addMovement(e);
                            }
                            break;

                        case MotionEvent.ACTION_UP:
                            if (velocityTracker != null) {
                                velocityTracker.addMovement(e);
                                velocityTracker.computeCurrentVelocity(1000);
                                float velocityY = velocityTracker.getYVelocity();

                                if (Math.abs(velocityY) > invertedSensitivity && NimarkoConfig.hideKeyboardOnScrollIntensity > 0) {
                                    chatActivityEnterView.hidePopup(true);
                                    AndroidUtilities.hideKeyboard(contentView);
                                }

                                velocityTracker.recycle();
                                velocityTracker = null;
                            }
                            break;

                        case MotionEvent.ACTION_CANCEL:
                            if (velocityTracker != null) {
                                velocityTracker.recycle();
                                velocityTracker = null;
                            }
                            break;
                    }

                    return false;
                }

                @Override
                public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {}

                @Override
                public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
            });
        }
    }
}
