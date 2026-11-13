/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AnimationNotificationsLocker;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_forum;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.Forum.ForumBubbleDrawable;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LetterDrawable;
import org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet;
import org.telegram.ui.Components.ReplaceableIconDrawable;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import app.nimarkogram.messenger.notifications.InAppNotifications;
import java.util.ArrayList;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
public class TopicCreateFragment extends BaseFragment {

    private final static int CREATE_ID = 1;
    private final static int EDIT_ID = 2;
    long dialogId;
    long selectedEmojiDocumentId;
    long topicId;

    TextCheckCell2 checkBoxCell;
    EditTextBoldCursor editTextBoldCursor;
    SelectAnimatedEmojiDialog selectAnimatedEmojiDialog;
    BackupImageView[] backupImageView = new BackupImageView[2];

    String firstSymbol = "";
    boolean created;
    Drawable defaultIconDrawable;
    ReplaceableIconDrawable replaceableIconDrawable;
    TLRPC.TL_forumTopic topicForEdit;
    ForumBubbleDrawable forumBubbleDrawable;

    private ChatActivity openInChatActivity;
    private CreateTopicOperation createTopicOperation;
    private CreateTopicOperation completedCreateTopicOperation;
    private TL_forum.TL_messages_editForumTopic editTopicRequest;
    private AlertDialog editTopicProgressDialog;
    public TopicCreateFragment setOpenInChatActivity(ChatActivity chatActivity) {
        CreateTopicOperation operation = openInChatActivity == chatActivity ? null : createTopicOperation;
        openInChatActivity = chatActivity;
        if (chatActivity != null && fragmentView == null) {
            setCurrentAccount(chatActivity.getCurrentAccount());
        }
        retireCreateTopicUi(operation);
        return this;
    }

    int iconColor;

    public static TopicCreateFragment create(long chatId, long topicId) {
        Bundle bundle = new Bundle();
        bundle.putLong("chat_id", chatId);
        bundle.putLong("topic_id", topicId);
        return new TopicCreateFragment(bundle);
    }

    private TopicCreateFragment(Bundle bundle) {
        super(bundle);
    }

    private static final class CreateTopicOperation {
        final int account;
        final long userId;
        final long session;
        final long dialogId;
        final String title;
        final long emojiId;
        final int iconColor;
        final long randomId;
        final View editorView;
        final BooleanSupplier navigationCurrent;
        final ChatActivity target;
        final View targetView;
        final Object targetLayout;
        final AlertDialog progressDialog;
        final AtomicBoolean responseHandled = new AtomicBoolean();
        int targetGuid;
        long targetTopicId;
        boolean uiActive = true;

        CreateTopicOperation(TopicCreateFragment fragment, String title) {
            account = fragment.currentAccount;
            userId = UserConfig.getInstance(account).getClientUserId();
            session = InAppNotifications.session(account);
            dialogId = fragment.dialogId;
            this.title = title;
            emojiId = fragment.selectedEmojiDocumentId;
            iconColor = fragment.iconColor;
            randomId = Utilities.random.nextLong();
            editorView = fragment.fragmentView;
            navigationCurrent = fragment.captureNavigationRequest();
            target = fragment.openInChatActivity;
            targetView = target == null ? null : target.getFragmentView();
            targetLayout = target == null ? null : target.getParentLayout();
            targetGuid = target == null ? 0 : target.getClassGuid();
            targetTopicId = target == null ? 0 : target.getTopicId();
            progressDialog = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        }

        boolean isCurrentAccount() {
            return InAppNotifications.isCurrent(account, userId, session);
        }
    }

    private boolean isCurrentCreateTopicUi(CreateTopicOperation operation) {
        return operation != null && createTopicOperation == operation && operation.uiActive
                && isCurrentCreateTopicContext(operation);
    }

    private boolean isCurrentCreateTopicContext(CreateTopicOperation operation) {
        if (isFinished || isPaused() || currentAccount != operation.account
                || dialogId != operation.dialogId || fragmentView != operation.editorView
                || !operation.isCurrentAccount() || !operation.navigationCurrent.getAsBoolean()
                || openInChatActivity != operation.target) {
            return false;
        }
        ChatActivity target = operation.target;
        return target == null || !target.isFinished && target.getCurrentAccount() == operation.account
                && target.getDialogId() == operation.dialogId && target.getTopicId() == operation.targetTopicId
                && target.getClassGuid() == operation.targetGuid && target.getFragmentView() == operation.targetView
                && target.getParentLayout() == operation.targetLayout;
    }

    private void retireCreateTopicUi(CreateTopicOperation operation) {
        if (operation == null) {
            return;
        }
        operation.uiActive = false;
        if (createTopicOperation == operation) {
            createTopicOperation = null;
            created = false;
        }
        operation.progressDialog.dismiss();
    }

    private void createTopic(String title) {
        if (created || isFinished || isPaused() || getParentActivity() == null) {
            return;
        }
        final CreateTopicOperation operation = new CreateTopicOperation(this, title);
        createTopicOperation = operation;
        created = true;
        if (!isCurrentCreateTopicUi(operation)) {
            retireCreateTopicUi(operation);
            return;
        }
        operation.progressDialog.setOnCancelListener(dialog -> retireCreateTopicUi(operation));
        operation.progressDialog.showDelayed(500);
        try {
            TL_forum.TL_messages_createForumTopic request = new TL_forum.TL_messages_createForumTopic();
            request.peer = MessagesController.getInstance(operation.account).getInputPeer(operation.dialogId);
            request.title = operation.title;
            request.icon_color = operation.iconColor;
            request.flags |= 1;
            if (operation.emojiId != 0) {
                request.icon_emoji_id = operation.emojiId;
                request.flags |= 8;
            }
            request.random_id = operation.randomId;
            if (!Utilities.stageQueue.postRunnable(() -> {
                if (!operation.isCurrentAccount()) {
                    AndroidUtilities.runOnUIThread(() -> completeCreateTopic(operation, 0, false, null));
                    return;
                }
                try {
                    int token = ConnectionsManager.getInstance(operation.account).sendRequestSync(request,
                            (response, error) -> onCreateTopicResponse(operation, response, error), null, null,
                            0, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric, true);
                    if (token <= 0) {
                        AndroidUtilities.runOnUIThread(() -> completeCreateTopic(operation, 0, false, null));
                    }
                } catch (Exception failure) {
                    FileLog.e(failure);
                    AndroidUtilities.runOnUIThread(() -> completeCreateTopic(operation, 0, false, null));
                }
            })) {
                completeCreateTopic(operation, 0, false, null);
            }
        } catch (Exception error) {
            FileLog.e(error);
            completeCreateTopic(operation, 0, false, null);
        }
    }

    private void retireEditTopicUi() {
        editTopicRequest = null;
        AlertDialog progress = editTopicProgressDialog;
        editTopicProgressDialog = null;
        if (progress != null) progress.dismiss();
    }

    private void editTopic(String title) {
        if (editTopicRequest != null || topicForEdit == null || isFinished || isPaused() || getParentActivity() == null) return;
        final TLRPC.TL_forumTopic topic = topicForEdit;
        final int account = currentAccount;
        final long userId = UserConfig.getInstance(account).getClientUserId();
        final long session = InAppNotifications.session(account);
        final BooleanSupplier accountCurrent = () -> InAppNotifications.isCurrent(account, userId, session);
        if (!accountCurrent.getAsBoolean()) return;
        final long editDialogId = dialogId;
        final View editorView = fragmentView;
        final TL_forum.TL_messages_editForumTopic request = new TL_forum.TL_messages_editForumTopic();
        request.peer = MessagesController.getInstance(account).getInputPeer(editDialogId);
        request.topic_id = topic.id;
        if (!TextUtils.equals(topic.title, title)) {
            request.title = title;
            request.flags |= 1;
        }
        if (topic.icon_emoji_id != selectedEmojiDocumentId) {
            request.icon_emoji_id = selectedEmojiDocumentId;
            request.flags |= 2;
        }
        if (checkBoxCell != null && topic.id == 1 && !checkBoxCell.isChecked() != topic.hidden) {
            request.hidden = !checkBoxCell.isChecked();
            request.flags |= 8;
        }
        if (request.flags == 0) {
            finishFragment();
            return;
        }
        final BooleanSupplier navigationCurrent = captureNavigationRequest();
        final BooleanSupplier uiCurrent = () -> !isFinished && !isPaused() && currentAccount == account
                && dialogId == editDialogId && topicForEdit == topic && fragmentView == editorView
                && accountCurrent.getAsBoolean() && navigationCurrent.getAsBoolean();
        if (!uiCurrent.getAsBoolean()) return;
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        final AtomicBoolean responseHandled = new AtomicBoolean();
        final RequestDelegate callback = (response, error) -> {
            if (!responseHandled.compareAndSet(false, true)) return;
            boolean success = false;
            if (response instanceof TLRPC.Updates && accountCurrent.getAsBoolean()) {
                try {
                    MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                    success = error == null;
                } catch (Exception failure) {
                    FileLog.e(failure);
                }
            }
            final boolean successful = success;
            AndroidUtilities.runOnUIThread(() -> {
                if (editTopicRequest != request) return;
                retireEditTopicUi();
                if (editTopicRequest != null || !uiCurrent.getAsBoolean()) return;
                if (successful) finishFragment();
                else BulletinFactory.of(this).showForError(error);
            });
        };
        editTopicRequest = request;
        editTopicProgressDialog = progress;
        progress.setOnCancelListener(dialog -> {
            if (editTopicRequest == request) retireEditTopicUi();
        });
        try {
            progress.showDelayed(500);
            if (!Utilities.stageQueue.postRunnable(() -> {
                if (!accountCurrent.getAsBoolean()) {
                    callback.run(null, null);
                    return;
                }
                try {
                    int token = ConnectionsManager.getInstance(account).sendRequestSync(request, callback, null, null,
                            0, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric, true);
                    if (token <= 0) callback.run(null, null);
                } catch (Exception failure) {
                    FileLog.e(failure);
                    callback.run(null, null);
                }
            })) callback.run(null, null);
        } catch (Exception failure) {
            FileLog.e(failure);
            callback.run(null, null);
        }
    }

    private void onCreateTopicResponse(CreateTopicOperation operation, TLObject response, TLRPC.TL_error error) {
        if (!operation.responseHandled.compareAndSet(false, true)) {
            return;
        }
        int topicId = 0;
        TLRPC.Message createdMessage = null;
        boolean success = false;
        if (response instanceof TLRPC.Updates && operation.isCurrentAccount()) {
            TLRPC.Updates updates = (TLRPC.Updates) response;
            for (TLRPC.Update update : updates.updates) {
                if (update instanceof TL_update.TL_updateMessageID
                        && ((TL_update.TL_updateMessageID) update).random_id == operation.randomId) {
                    topicId = ((TL_update.TL_updateMessageID) update).id;
                    break;
                }
            }
            for (TLRPC.Update update : updates.updates) {
                TLRPC.Message message = update instanceof TL_update.TL_updateNewChannelMessage
                        ? ((TL_update.TL_updateNewChannelMessage) update).message
                        : update instanceof TL_update.TL_updateNewMessage ? ((TL_update.TL_updateNewMessage) update).message : null;
                if (message != null && message.id == topicId && message.action instanceof TLRPC.TL_messageActionTopicCreate
                        && MessageObject.getDialogId(message) == operation.dialogId) {
                    createdMessage = message;
                    break;
                }
            }
            try {
                MessagesController.getInstance(operation.account).processUpdates(updates, false);
                success = error == null;
            } catch (Exception failure) {
                FileLog.e(failure);
            }
        }
        final int createdTopicId = topicId;
        final TLRPC.Message serverMessage = createdMessage;
        final boolean successful = success;
        AndroidUtilities.runOnUIThread(() -> completeCreateTopic(operation, createdTopicId, successful, error, serverMessage));
    }

    private TLRPC.TL_forumTopic cacheCreatedTopic(CreateTopicOperation operation, int topicId, TLRPC.Message serverMessage) {
        if (topicId <= 0 || !operation.isCurrentAccount()) {
            return null;
        }
        MessagesController controller = MessagesController.getInstance(operation.account);
        TLRPC.TL_forumTopic topic = controller.getTopicsController().findTopic(-operation.dialogId, topicId);
        if (topic != null) {
            return topic;
        }
        TLRPC.TL_messageActionTopicCreate action = new TLRPC.TL_messageActionTopicCreate();
        action.title = operation.title;
        action.icon_color = operation.iconColor;
        action.icon_emoji_id = operation.emojiId;
        if (operation.emojiId != 0) {
            action.flags |= 1;
        }
        TLRPC.Message message = serverMessage;
        if (message != null && message.id == topicId && message.action instanceof TLRPC.TL_messageActionTopicCreate
                && MessageObject.getDialogId(message) == operation.dialogId) {
            action = (TLRPC.TL_messageActionTopicCreate) message.action;
        } else {
            message = new TLRPC.TL_messageService();
            message.action = action;
            message.peer_id = controller.getPeer(operation.dialogId);
            message.from_id = controller.getPeer(operation.userId);
            message.dialog_id = operation.dialogId;
            message.id = topicId;
            message.date = ConnectionsManager.getInstance(operation.account).getCurrentTime();
            message.out = true;
        }
        topic = new TLRPC.TL_forumTopic();
        topic.id = topicId;
        topic.peer = message.peer_id;
        topic.date = message.date;
        topic.icon_emoji_id = action.icon_emoji_id;
        topic.flags = 2 | (action.icon_emoji_id == 0 ? 0 : 1);
        topic.my = true;
        topic.topicStartMessage = message;
        topic.title = action.title;
        topic.top_message = topicId;
        topic.topMessage = message;
        topic.from_id = message.from_id != null ? message.from_id : controller.getPeer(operation.userId);
        topic.notify_settings = new TLRPC.TL_peerNotifySettings();
        topic.icon_color = action.icon_color;
        if (!operation.isCurrentAccount()) {
            return null;
        }
        controller.getTopicsController().onTopicCreated(operation.dialogId, topic, true);
        return operation.isCurrentAccount()
                ? controller.getTopicsController().findTopic(-operation.dialogId, topicId) : null;
    }

    private void completeCreateTopic(CreateTopicOperation operation, int topicId, boolean success, TLRPC.TL_error error) {
        completeCreateTopic(operation, topicId, success, error, null);
    }

    private void completeCreateTopic(CreateTopicOperation operation, int topicId, boolean success, TLRPC.TL_error error, TLRPC.Message serverMessage) {
        try {
            TLRPC.TL_forumTopic topic = success ? cacheCreatedTopic(operation, topicId, serverMessage) : null;
            if (!isCurrentCreateTopicUi(operation)) {
                return;
            }
            operation.progressDialog.dismiss();
            if (!isCurrentCreateTopicUi(operation)) {
                return;
            }
            if (topic == null) {
                retireCreateTopicUi(operation);
                if (createTopicOperation == null && isCurrentCreateTopicContext(operation)) {
                    BulletinFactory.of(this).showForError(error);
                }
                return;
            }
            TLRPC.Message message = topic.topicStartMessage;
            if (message == null) {
                return;
            }
            ArrayList<MessageObject> messageObjects = new ArrayList<>();
            messageObjects.add(new MessageObject(operation.account, message, false, false));
            if (!isCurrentCreateTopicUi(operation)) {
                return;
            }
            TLRPC.Chat chat = MessagesController.getInstance(operation.account).getChat(-operation.dialogId);
            ChatActivity target = operation.target;
            if (target == null) {
                Bundle args = new Bundle();
                args.putLong("chat_id", -operation.dialogId);
                args.putInt("message_id", 1);
                args.putInt("unread_count", 0);
                args.putBoolean("historyPreloaded", false);
                ChatActivity activity = new ChatActivity(args);
                activity.setCurrentAccount(operation.account);
                if (!isCurrentCreateTopicUi(operation)) {
                    return;
                }
                activity.setThreadMessages(messageObjects, chat, topic.id, 1, 1, topic);
                activity.justCreatedTopic = true;
                if (!isCurrentCreateTopicUi(operation)) {
                    return;
                }
                completedCreateTopicOperation = operation;
                if (!presentFragment(activity) && completedCreateTopicOperation == operation) {
                    completedCreateTopicOperation = null;
                }
                return;
            }
            target.resetForReload();
            operation.targetGuid = target.getClassGuid();
            if (!isCurrentCreateTopicUi(operation)) return;
            target.saveDraft();
            if (!isCurrentCreateTopicUi(operation)) return;
            operation.targetTopicId = topic.id;
            target.setThreadMessages(messageObjects, chat, topic.id, 1, 1, topic);
            if (!isCurrentCreateTopicUi(operation)) return;
            target.justCreatedTopic = true;
            target.firstLoadMessages();
            if (!isCurrentCreateTopicUi(operation)) return;
            target.updateTitle(true);
            if (!isCurrentCreateTopicUi(operation)) return;
            if (target.getAvatarContainer() != null) target.getAvatarContainer().updateSubtitle(true);
            if (!isCurrentCreateTopicUi(operation)) return;
            target.updateTopicTitleIcon();
            if (!isCurrentCreateTopicUi(operation)) return;
            if (target.topicsTabs != null) target.topicsTabs.setCurrentTopic(target.getTopicId());
            if (!isCurrentCreateTopicUi(operation)) return;
            target.updateTopPanel(true);
            if (!isCurrentCreateTopicUi(operation)) return;
            target.updateBottomOverlay(true);
            if (!isCurrentCreateTopicUi(operation)) return;
            target.hideFieldPanel(true);
            if (!isCurrentCreateTopicUi(operation)) return;
            target.applyDraftMaybe(true, true);
            if (!isCurrentCreateTopicUi(operation)) return;
            target.reloadPinnedMessages();
            if (!isCurrentCreateTopicUi(operation)) return;
            completedCreateTopicOperation = operation;
            finishFragment();
        } finally {
            retireCreateTopicUi(operation);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        dialogId = -arguments.getLong("chat_id");
        topicId = arguments.getLong("topic_id", 0);
        if (topicId != 0) {
            topicForEdit = getMessagesController().getTopicsController().findTopic(-dialogId, topicId);
            if (topicForEdit == null) {
                return false;
            }
            iconColor = topicForEdit.icon_color;
        } else {
            iconColor = ForumBubbleDrawable.serverSupportedColor[Math.abs(Utilities.random.nextInt() % ForumBubbleDrawable.serverSupportedColor.length)];
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        retireCreateTopicUi(createTopicOperation);
        retireEditTopicUi();
        if (topicForEdit != null) {
            actionBar.setTitle(LocaleController.getString(R.string.EditTopic));
        } else {
            actionBar.setTitle(LocaleController.getString(R.string.NewTopic));
        }
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    retireCreateTopicUi(createTopicOperation);
                    retireEditTopicUi();
                    finishFragment();
                    return;
                }
                if (id == CREATE_ID) {
                    String topicName = editTextBoldCursor.getText() == null ? null : editTextBoldCursor.getText().toString();
                    if (TextUtils.isEmpty(topicName)) {
                        Vibrator v = (Vibrator) getParentActivity().getSystemService(Context.VIBRATOR_SERVICE);
                        if (v != null) {
                            v.vibrate(200);
                        }
                        AndroidUtilities.shakeView(editTextBoldCursor);
                        return;
                    }

                    if (created) {
                        return;
                    }

                    createTopic(topicName);
                } else if (id == EDIT_ID) {

                    String topicName = editTextBoldCursor.getText() == null ? null : editTextBoldCursor.getText().toString();
                    if (TextUtils.isEmpty(topicName)) {
                        Vibrator v = (Vibrator) getParentActivity().getSystemService(Context.VIBRATOR_SERVICE);
                        if (v != null) {
                            v.vibrate(200);
                        }
                        AndroidUtilities.shakeView(editTextBoldCursor);
                        return;
                    }
                    editTopic(topicName);
                }
            }
        });
        if (topicForEdit == null) {
            actionBar.createMenu().addItem(CREATE_ID, LocaleController.getString(R.string.Create));
        } else {
            actionBar.createMenu().addItem(EDIT_ID, R.drawable.ic_ab_done);
        }
        ThemeBind.backgroundColor(actionBar, Theme.key_windowBackgroundGray, getResourceProvider());
        actionBar.setCastShadows(false);

        FrameLayout contentView = new SizeNotifierFrameLayout(context) {
            boolean keyboardWasShown;
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                measureKeyboardHeight();
                if (getKeyboardHeight() == 0 && !keyboardWasShown) {
                    SharedPreferences sharedPreferences = MessagesController.getGlobalEmojiSettings();
                    keyboardHeight = sharedPreferences.getInt("kbd_height", dp(200));
                    setPadding(0, 0, 0, keyboardHeight);
                } else {
                    keyboardWasShown = true;
                    setPadding(0, 0, 0, 0);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        fragmentView = contentView;
        ThemeBind.backgroundColor(contentView, Theme.key_windowBackgroundGray, getResourceProvider());

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(linearLayout);

        HeaderCell headerCell = new HeaderCell(context);
        if (topicForEdit != null && topicForEdit.id == 1) {
            headerCell.setText(LocaleController.getString(R.string.CreateGeneralTopicTitle));
        } else {
            headerCell.setText(LocaleController.getString(R.string.CreateTopicTitle));
        }

        FrameLayout editTextContainer = new FrameLayout(context);

        editTextBoldCursor = new EditTextBoldCursor(context);
        editTextBoldCursor.setHintText(LocaleController.getString(R.string.EnterTopicName));
        ThemeBind.editHintColor(editTextBoldCursor, Theme.key_chat_messagePanelHint, getResourceProvider());
        ThemeBind.textColor(editTextBoldCursor, Theme.key_chat_messagePanelText, getResourceProvider());
        editTextBoldCursor.setPadding(dp(0), editTextBoldCursor.getPaddingTop(), dp(0), editTextBoldCursor.getPaddingBottom());
        editTextBoldCursor.setBackground(null);
        editTextBoldCursor.setSingleLine(true);
        editTextBoldCursor.setInputType(editTextBoldCursor.getInputType() | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editTextContainer.addView(editTextBoldCursor, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 51, 4, 21, 4));

        editTextBoldCursor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                String trimmedString = s.toString().trim();
                String oldFirstSymbol = firstSymbol;
                if (trimmedString.length() > 0) {
                    firstSymbol = trimmedString.substring(0, 1).toUpperCase();
                } else {
                    firstSymbol = "";
                }
                if (!oldFirstSymbol.equals(firstSymbol)) {
                    LetterDrawable letterDrawable = new LetterDrawable(null, LetterDrawable.STYLE_TOPIC_DRAWABLE);
                    letterDrawable.setTitle(firstSymbol);
                    if (replaceableIconDrawable != null) {
                        replaceableIconDrawable.setIcon(letterDrawable, true);
                    }
                }
            }
        });
        FrameLayout iconContainer = new FrameLayout(context) {

            ValueAnimator backAnimator;
            boolean pressed;
            float pressedProgress;

            @Override
            protected void dispatchDraw(Canvas canvas) {
                float s = 0.8f + 0.2f * (1f - pressedProgress);
                canvas.save();
                canvas.scale(s, s, getMeasuredHeight() / 2f, getMeasuredWidth() / 2f);
                super.dispatchDraw(canvas);
                canvas.restore();
                updatePressedProgress();
            }

            @Override
            public void setPressed(boolean pressed) {
                super.setPressed(pressed);
                if (this.pressed != pressed) {
                    this.pressed = pressed;
                    invalidate();
                    if (pressed) {
                        if (backAnimator != null) {
                            backAnimator.removeAllListeners();
                            backAnimator.cancel();
                        }
                    }
                    if (!pressed && pressedProgress != 0) {
                        backAnimator = ValueAnimator.ofFloat(pressedProgress, 0);
                        backAnimator.addUpdateListener(animation -> {
                            pressedProgress = (float) animation.getAnimatedValue();
                            invalidate();
                        });
                        backAnimator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                backAnimator = null;
                            }
                        });
                        backAnimator.setInterpolator(new OvershootInterpolator(5.0f));
                        backAnimator.setDuration(350);
                        backAnimator.start();
                    }
                }
            }

            public void updatePressedProgress() {
                if (isPressed() && pressedProgress != 1f) {
                    pressedProgress = Utilities.clamp(pressedProgress + 16f / 100f, 1f, 0);
                    invalidate();
                }
            }
        };
        iconContainer.setOnClickListener(v -> {
            if (selectedEmojiDocumentId == 0 && topicForEdit == null) {
                iconColor = forumBubbleDrawable.moveNexColor();
            }
        });
        for (int i = 0; i < 2; i++) {
            backupImageView[i] = new BackupImageView(context);
            iconContainer.addView(backupImageView[i], LayoutHelper.createFrame(28, 28, Gravity.CENTER));
        }
        editTextContainer.addView(iconContainer, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        LinearLayout topView = new LinearLayout(context);
        topView.setOrientation(LinearLayout.VERTICAL);
        topView.addView(headerCell);
        topView.addView(editTextContainer);
        ThemeBind.fillBackground(topView, Theme.createRoundRectDrawableShadowed(dp(16), getThemedColor(Theme.key_windowBackgroundWhite)), ThemeBind.of(Theme.key_windowBackgroundWhite), getResourceProvider());
        linearLayout.addView(topView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 9, 1, 9, 0));


        FrameLayout emojiContainer = new FrameLayout(context);
        emojiContainer.setClipChildren(false);

        if (topicForEdit == null || topicForEdit.id != 1) {
            selectAnimatedEmojiDialog = new SelectAnimatedEmojiDialog(this, getContext(), false, null, SelectAnimatedEmojiDialog.TYPE_TOPIC_ICON, null) {

                private boolean firstLayout = true;

                @Override
                protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                    super.onLayout(changed, left, top, right, bottom);
                    if (firstLayout) {
                        firstLayout = false;
                        selectAnimatedEmojiDialog.onShow(null);
                    }
                }

                protected void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                    boolean setIsFree = false;
                    if (!TextUtils.isEmpty(UserConfig.getInstance(currentAccount).defaultTopicIcons)) {
                        TLRPC.TL_messages_stickerSet stickerSet = getMediaDataController().getStickerSetByEmojiOrName(UserConfig.getInstance(currentAccount).defaultTopicIcons);
                        long stickerSetId = stickerSet == null ? 0 : stickerSet.set.id;
                        long documentSetId = MediaDataController.getStickerSetId(document);
                        setIsFree = stickerSetId == documentSetId;
                    }

                    selectEmoji(documentId, setIsFree);
                }
            };

            selectAnimatedEmojiDialog.setAnimationsEnabled(fragmentBeginToShow);
            selectAnimatedEmojiDialog.setClipChildren(false);
            emojiContainer.addView(selectAnimatedEmojiDialog, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 12, 12, 12, 12));

            Drawable drawable = ForumUtilities.createTopicDrawable("", iconColor, false);
            CombinedDrawable topicCombinedDrawable = (CombinedDrawable) drawable;
            forumBubbleDrawable = (ForumBubbleDrawable) topicCombinedDrawable.getBackgroundDrawable();

            replaceableIconDrawable = new ReplaceableIconDrawable(context);
            CombinedDrawable combinedDrawable2 = new CombinedDrawable(drawable, replaceableIconDrawable, 0, 0);
            combinedDrawable2.setFullsize(true);

            selectAnimatedEmojiDialog.setForumIconDrawable(combinedDrawable2);

            defaultIconDrawable = combinedDrawable2;

            replaceableIconDrawable.addView(backupImageView[0]);
            replaceableIconDrawable.addView(backupImageView[1]);

            backupImageView[0].setImageDrawable(defaultIconDrawable);
            AndroidUtilities.updateViewVisibilityAnimated(backupImageView[0], true, 1, false);
            AndroidUtilities.updateViewVisibilityAnimated(backupImageView[1], false, 1, false);

            forumBubbleDrawable.addParent(backupImageView[0]);
            forumBubbleDrawable.addParent(backupImageView[1]);
        } else {
            ImageView imageView = new ImageView(context);
            imageView.setImageResource(R.drawable.msg_filled_general);
            ThemeBind.colorFilter(imageView, Theme.key_chat_inMenu, getResourceProvider(), PorterDuff.Mode.MULTIPLY);
            iconContainer.addView(imageView, LayoutHelper.createFrame(22, 22, Gravity.CENTER));

            emojiContainer.addView(
                new View(context),
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 8)
            );

            FrameLayout checkBoxCellContainer = new FrameLayout(context);
            ThemeBind.fillBackground(checkBoxCellContainer, Theme.createRoundRectDrawableShadowed(dp(16), getThemedColor(Theme.key_windowBackgroundWhite)), ThemeBind.of(Theme.key_windowBackgroundWhite), getResourceProvider());

            checkBoxCell = new TextCheckCell2(context);
            checkBoxCell.getCheckBox().setDrawIconType(0);
            checkBoxCell.setTextAndCheck(LocaleController.getString(R.string.EditTopicHide), !topicForEdit.hidden, false);
            ThemeBind.selectorBackground(checkBoxCell, Theme.createRadSelectorDrawable(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector), 16, 16), ThemeBind.of(Theme.key_windowBackgroundWhite), ThemeBind.of(Theme.key_listSelector), getResourceProvider());
            checkBoxCell.setOnClickListener(e -> {
                checkBoxCell.setChecked(!checkBoxCell.isChecked());
            });
            checkBoxCellContainer.addView(checkBoxCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.FILL));
            emojiContainer.addView(checkBoxCellContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP, 9, 8, 9, 0));

            TextInfoPrivacyCell infoCell = new TextInfoPrivacyCell(context);
            infoCell.setText(LocaleController.getString(R.string.EditTopicHideInfo));
            emojiContainer.addView(infoCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 8 + 50, 0, 0));
        }
        linearLayout.addView(emojiContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (topicForEdit != null) {
            editTextBoldCursor.setText(topicForEdit.title);
            selectEmoji(topicForEdit.icon_emoji_id, true);
        } else {
            selectEmoji(0L, true);
        }

        return fragmentView;
    }

    private void selectEmoji(Long documentId, boolean free) {
        if (selectAnimatedEmojiDialog == null || replaceableIconDrawable == null) {
            return;
        }
        long docId = documentId == null ? 0L : documentId;
        selectAnimatedEmojiDialog.setSelected(docId);
        if (selectedEmojiDocumentId == docId) {
            return;
        }

        if (!free && docId != 0 && !getUserConfig().isPremium()) {
            TLRPC.Document emoji = AnimatedEmojiDrawable.findDocument(currentAccount, documentId);
            if (emoji != null) {
                BulletinFactory.of(this)
                        .createEmojiBulletin(
                                emoji,
                                AndroidUtilities.replaceTags(LocaleController.getString(R.string.UnlockPremiumEmojiHint)),
                                LocaleController.getString(R.string.PremiumMore),
                                () -> {
                                    new PremiumFeatureBottomSheet(this, PremiumPreviewFragment.PREMIUM_FEATURE_ANIMATED_EMOJI, false).show();
                                }
                        ).show();
            }
            return;
        }

        selectedEmojiDocumentId = docId;

        if (docId != 0) {
            AnimatedEmojiDrawable animatedEmojiDrawable = new AnimatedEmojiDrawable(AnimatedEmojiDrawable.CACHE_TYPE_FORUM_TOPIC, currentAccount, docId);
            animatedEmojiDrawable.setColorFilter(Theme.chat_animatedEmojiTextColorFilter);
            backupImageView[1].setAnimatedEmojiDrawable(animatedEmojiDrawable);
            backupImageView[1].setImageDrawable(null);
        } else {
            LetterDrawable letterDrawable = new LetterDrawable(null, LetterDrawable.STYLE_TOPIC_DRAWABLE);
            letterDrawable.setTitle(firstSymbol);
            replaceableIconDrawable.setIcon(letterDrawable, false);
            backupImageView[1].setImageDrawable(defaultIconDrawable);
            backupImageView[1].setAnimatedEmojiDrawable(null);
        }

        BackupImageView tmp = backupImageView[0];
        backupImageView[0] = backupImageView[1];
        backupImageView[1] = tmp;

        AndroidUtilities.updateViewVisibilityAnimated(backupImageView[0], true, 0.5f, true);
        AndroidUtilities.updateViewVisibilityAnimated(backupImageView[1], false, 0.5f, true);
    }

    AnimationNotificationsLocker notificationsLocker = new AnimationNotificationsLocker();

    @Override
    public void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        super.onTransitionAnimationStart(isOpen, backward);
        if (isOpen) {
            notificationsLocker.lock();
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (!isOpen && completedCreateTopicOperation != null
                && completedCreateTopicOperation.editorView == fragmentView) {
            completedCreateTopicOperation = null;
            removeSelfFromStack();
        }

        notificationsLocker.unlock();
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setAnimationsEnabled(fragmentBeginToShow);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        showKeyboard();
    }

    @Override
    public void onPause() {
        retireCreateTopicUi(createTopicOperation);
        retireEditTopicUi();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        retireCreateTopicUi(createTopicOperation);
        retireEditTopicUi();
        notificationsLocker.unlock();
        super.onFragmentDestroy();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        boolean close = super.onBackPressed(invoked);
        if (close && invoked) {
            retireCreateTopicUi(createTopicOperation);
            retireEditTopicUi();
        }
        return close;
    }
    public void showKeyboard() {
        editTextBoldCursor.requestFocus();
        AndroidUtilities.showKeyboard(editTextBoldCursor);
    }
}
