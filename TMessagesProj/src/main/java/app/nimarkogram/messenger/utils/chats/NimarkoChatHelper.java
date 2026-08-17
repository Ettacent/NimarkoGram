/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils.chats;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.TranscribeButton;
import org.telegram.ui.PeerColorActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

import app.nimarkogram.messenger.NimarkoConfig;

public class NimarkoChatHelper extends BaseController {

    private static final AtomicReferenceArray<NimarkoChatHelper> Instance =
            new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);

    public NimarkoChatHelper(int num) {
        super(num);
    }

    public static NimarkoChatHelper getInstance(int num) {
        NimarkoChatHelper localInstance = Instance.get(num);
        if (localInstance == null) {
            synchronized (NimarkoChatHelper.class) {
                localInstance = Instance.get(num);
                if (localInstance == null) {
                    localInstance = new NimarkoChatHelper(num);
                    Instance.set(num, localInstance);
                }
            }
        }
        return localInstance;
    }

    public ChatActivity.ThemeDelegate themeDelegate;

    public static SpannableStringBuilder forwardsSpan;
    public static Drawable forwardsDrawable;

    public static SpannableStringBuilder editedSpan;
    public static Drawable editedDrawable;

    public boolean checkDeepLink(String url, long userID) {
        if (url == null) {
            return false;
        }
        if (!BuildVars.DEBUG_PRIVATE_VERSION) {
            return false;
        }
        
        if (userID != 0 && getUserConfig().clientUserId == userID) {
            return false;
        }

        if (url.contains("restart") || url.contains("reboot") || url.contains("nimarko_restart")) {
            app.nimarkogram.messenger.utils.AppRestartHelper.restartApp(ApplicationLoader.applicationContext);
            return true;
        }

        if (url.contains("luck") || url.contains("nimarko_luck")) {
            app.nimarkogram.messenger.utils.AppRestartHelper.killApp();
            return true;
        }

        return false;
    }

    public static CharSequence createForwardedString(MessageObject messageObject) {
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();

        if (forwardsDrawable == null) {
            forwardsDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.forwards_solar)).mutate();
        }
        if (forwardsSpan == null) {
            forwardsSpan = new SpannableStringBuilder("​");
            
            forwardsSpan.setSpan(new ColoredImageSpan(forwardsDrawable, 0), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        spannableStringBuilder
                .append(' ')
                .append(forwardsSpan)
                .append(' ')
                .append(String.format("%d", messageObject.messageOwner.forwards))
                .append(" • ")
                .append(LocaleController.getInstance().getFormatterDay().format((long) (messageObject.messageOwner.date) * 1000));
        return spannableStringBuilder;
    }

    public static SpannableStringBuilder getEditedSpan() {
        if (editedDrawable == null) {
            
            editedDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_edited)).mutate();
        }
        if (editedSpan == null) {
            editedSpan = new SpannableStringBuilder("​");
            
            editedSpan.setSpan(new ColoredImageSpan(editedDrawable, 0), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return editedSpan;
    }

    public static CharSequence createEditedString(MessageObject messageObject) {
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        boolean hasForwards = messageObject.messageOwner.forwards > 0;
        boolean isMusic = messageObject.isMusic();

        if (editedDrawable == null) {
            
            editedDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_edited)).mutate();
        }
        if (editedSpan == null) {
            editedSpan = new SpannableStringBuilder("​");
            editedSpan.setSpan(new ColoredImageSpan(editedDrawable, 0), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (forwardsDrawable == null) {
            forwardsDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.forwards_solar)).mutate();
        }
        if (forwardsSpan == null) {
            forwardsSpan = new SpannableStringBuilder("​");
            forwardsSpan.setSpan(new ColoredImageSpan(forwardsDrawable, 0), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        spannableStringBuilder
                .append(isMusic ? "" : " ")
                .append(hasForwards && !isMusic ? forwardsSpan : "")
                .append(hasForwards && !isMusic ? " " : "")
                .append(hasForwards && !isMusic ? String.format("%d", messageObject.messageOwner.forwards) : "")
                .append(isMusic ? "" : " ")
                .append(hasForwards && !isMusic ? "• " : "")
                .append(NimarkoConfig.showPencilIcon ? editedSpan : getString(R.string.EditedMessage))
                .append(hasForwards && !isMusic ? " • " : " ")
                .append(LocaleController.getInstance().getFormatterDay().format((long) (messageObject.messageOwner.date) * 1000));
        return spannableStringBuilder;
    }

    public void addMessageToClipboard(MessageObject selectedObject, Runnable callback) {
        String path = getPathToMessage(selectedObject);
        if (!TextUtils.isEmpty(path)) {
            addFileToClipboard(new File(path), callback);
        }
    }

    public String getPathToMessage(MessageObject messageObject) {
        String path = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(path)) {
            File temp = new File(path);
            if (!temp.exists()) {
                path = null;
            }
        }
        if (TextUtils.isEmpty(path)) {
            path = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner).toString();
            File temp = new File(path);
            if (!temp.exists()) {
                path = null;
            }
        }
        if (TextUtils.isEmpty(path)) {
            path = FileLoader.getInstance(messageObject.currentAccount).getPathToAttach(messageObject.getDocument(), true).toString();
            File temp = new File(path);
            if (!temp.exists()) {
                return null;
            }
        }
        return path;
    }

    public void addFileToClipboard(File file, Runnable callback) {
        try {
            Context context = ApplicationLoader.applicationContext;
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
            ClipData clip = ClipData.newUri(context.getContentResolver(), "label", uri);
            clipboard.setPrimaryClip(clip);
            callback.run();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void saveStickerToGallery(Activity activity, TLRPC.Document document, Utilities.Callback<Uri> callback) {
        String path = FileLoader.getInstance(currentAccount).getPathToAttach(document, true).toString();
        File temp = new File(path);
        if (!temp.exists()) {
            return;
        }
        saveStickerToGallery(activity, path, MessageObject.isVideoSticker(document), callback);
    }

    private void saveStickerToGallery(Activity activity, String path, boolean video, Utilities.Callback<Uri> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (video) {
                    MediaController.saveFile(path, activity, 1, null, null, callback);
                } else {
                    Bitmap image = BitmapFactory.decodeFile(path);
                    if (image != null) {
                        File file = new File(path.endsWith(".webp") ? path.replace(".webp", ".png") : path + ".png");
                        FileOutputStream stream = new FileOutputStream(file);
                        image.compress(Bitmap.CompressFormat.PNG, 100, stream);
                        stream.close();
                        MediaController.saveFile(file.toString(), activity, 0, null, null, callback);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public long getEmojiIdFromReply(MessageObject messageObject, TLRPC.User currentUser) {
        if (messageObject != null && messageObject.messageOwner != null && messageObject.replyMessageObject != null && messageObject.replyMessageObject.messageOwner != null && messageObject.replyMessageObject.messageOwner.from_id != null) {
            if (DialogObject.isEncryptedDialog(messageObject.replyMessageObject.getDialogId())) {
                TLRPC.User user = messageObject.replyMessageObject.isOutOwner() ? UserConfig.getInstance(messageObject.replyMessageObject.currentAccount).getCurrentUser() : currentUser;
                if (user != null) {
                    return UserObject.getEmojiId(user);
                }
            } else if (messageObject.replyMessageObject.isFromUser()) {
                TLRPC.User user = MessagesController.getInstance(messageObject.currentAccount).getUser(messageObject.replyMessageObject.messageOwner.from_id.user_id);
                if (user != null) {
                    return UserObject.getEmojiId(user);
                }
            } else if (messageObject.replyMessageObject.isFromChannel()) {
                TLRPC.Chat chat = MessagesController.getInstance(messageObject.currentAccount).getChat(messageObject.replyMessageObject.messageOwner.from_id.channel_id);
                if (chat != null) {
                    return ChatObject.getEmojiId(chat);
                }
            }
        }
        return 0;
    }

    private int getEmojiBackgroundFromReply(MessageObject messageObject, TLRPC.User currentUser) {
        if (messageObject != null && messageObject.messageOwner != null && messageObject.replyMessageObject != null && messageObject.replyMessageObject.messageOwner != null && messageObject.replyMessageObject.messageOwner.from_id != null) {
            if (DialogObject.isEncryptedDialog(messageObject.replyMessageObject.getDialogId())) {
                TLRPC.User user = messageObject.replyMessageObject.isOutOwner() ? UserConfig.getInstance(messageObject.replyMessageObject.currentAccount).getCurrentUser() : currentUser;
                if (user != null) {
                    return UserObject.getColorId(user);
                }
            } else if (messageObject.replyMessageObject.isFromUser()) {
                TLRPC.User user = MessagesController.getInstance(messageObject.currentAccount).getUser(messageObject.replyMessageObject.messageOwner.from_id.user_id);
                if (user != null) {
                    return UserObject.getColorId(user);
                }
            } else if (messageObject.replyMessageObject.isFromChannel()) {
                TLRPC.Chat chat = MessagesController.getInstance(messageObject.currentAccount).getChat(messageObject.replyMessageObject.messageOwner.from_id.channel_id);
                if (chat != null) {
                    return ChatObject.getColorId(chat);
                }
            }
        }
        return 0;
    }

    public void applyReplyBackground(MessageObject selectedObject, BaseFragment fragment) {
        long emojiDocumentId = getEmojiIdFromReply(selectedObject, MessagesController.getInstance(currentAccount).getUser(selectedObject.replyMessageObject.messageOwner.from_id.user_id));
        int colorId = getEmojiBackgroundFromReply(selectedObject, MessagesController.getInstance(currentAccount).getUser(selectedObject.replyMessageObject.messageOwner.from_id.user_id));
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();

        final TL_account.updateColor req = new TL_account.updateColor();
        if (me.color == null) {
            me.color = new TLRPC.PeerColor();
            me.flags2 |= 256;
            me.color.flags |= 1;
        }
        req.flags |= 4;
        req.color = new TLRPC.TL_peerColor();
        req.color.flags |= 1;
        req.color.color = me.color.color = colorId;

        if (emojiDocumentId != 0) {
            me.color.flags |= 2;
            req.color.flags |= 2;
            req.color.background_emoji_id = me.color.background_emoji_id = emojiDocumentId;
        } else {
            me.color.flags &= ~2;
            me.color.background_emoji_id = 0;
            req.color.flags &= ~2;
            req.color.background_emoji_id = 0;
        }

        getConnectionsManager().sendRequest(req, (res, err) -> {
            if (res != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    BulletinFactory.of(fragment).createSimpleBulletin(
                            PeerColorActivity.PeerColorDrawable.from(currentAccount, colorId),
                            getString(R.string.UserColorApplied)
                    ).setDuration(Bulletin.DURATION_PROLONG).show();
                });
            }
        });
    }

    public void openEmojiPack(MessageObject selectedObject, BaseFragment fragment) {
        long emojiDocumentId = getEmojiIdFromReply(selectedObject, MessagesController.getInstance(currentAccount).getUser(selectedObject.replyMessageObject.messageOwner.from_id.user_id));

        AnimatedEmojiDrawable.getDocumentFetcher(currentAccount).fetchDocument(emojiDocumentId, document -> AndroidUtilities.runOnUIThread(() -> {
            ArrayList<TLRPC.InputStickerSet> inputSets = new ArrayList<>(1);
            inputSets.add(MessageObject.getInputStickerSet(document));
            EmojiPacksAlert alert = new EmojiPacksAlert(fragment, fragment.getParentActivity(), themeDelegate, inputSets);
            alert.setDimBehindAlpha(100);
            alert.show();
        }));
    }

    public CharSequence getMessageText(MessageObject selectedObject, MessageObject.GroupedMessages selectedObjectGroup) {
        CharSequence messageTextToTranslate = null;
        if (selectedObject != null && selectedObject.type != MessageObject.TYPE_EMOJIS && selectedObject.type != MessageObject.TYPE_ANIMATED_STICKER && selectedObject.type != MessageObject.TYPE_STICKER) {
            messageTextToTranslate = getMessageCaption(selectedObject, selectedObjectGroup);
            if (messageTextToTranslate == null && selectedObject.isPoll()) {
                try {
                    TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) selectedObject.messageOwner.media).poll;
                    StringBuilder pollText = new StringBuilder(poll.question.text).append("\n");
                    for (TLRPC.PollAnswer answer : poll.answers)
                        pollText.append("\n🔘 ").append(answer.text == null ? "" : answer.text.text);
                    messageTextToTranslate = pollText.toString();
                } catch (Exception e) {
                }
            }
            if (messageTextToTranslate == null && MessageObject.isMediaEmpty(selectedObject.messageOwner)) {
                messageTextToTranslate = getMessageContent(selectedObject);
            }
            if (messageTextToTranslate != null && Emoji.fullyConsistsOfEmojis(messageTextToTranslate)) {
                messageTextToTranslate = null;
            }
        }
        return messageTextToTranslate == null ? " " : messageTextToTranslate;
    }

    public static ArrayList<TLRPC.MessageEntity> getTranslationEntities(
            MessageObject selectedObject,
            MessageObject.GroupedMessages group,
            int sourceMessageId,
            CharSequence text) {
        if (selectedObject == null || text == null) return null;
        MessageObject source = getTranslationSource(selectedObject, group, sourceMessageId);
        if (source == null) return null;
        if (source.messageOwner == null || source.messageOwner.entities == null) return null;
        CharSequence sourceText = source.caption != null ? source.caption : source.messageOwner.message;
        if (!TextUtils.equals(sourceText, text)) return null;
        return new ArrayList<>(source.messageOwner.entities);
    }

    public static MessageObject getTranslationSource(
            MessageObject selectedObject, MessageObject.GroupedMessages group, int sourceMessageId) {
        if (selectedObject == null) return null;
        MessageObject source = selectedObject;
        if (group != null && sourceMessageId != 0 && source.getId() != sourceMessageId) {
            for (MessageObject candidate : group.messages) {
                if (candidate != null && candidate.getId() == sourceMessageId) {
                    source = candidate;
                    break;
                }
            }
        }
        return source;
    }

    public CharSequence getMessageCaption(MessageObject messageObject, MessageObject.GroupedMessages group) {
        String restrictionReason = getMessagesController().getRestrictionReason(messageObject.messageOwner.restriction_reason);
        if (!TextUtils.isEmpty(restrictionReason)) {
            return restrictionReason;
        }
        if (messageObject.isVoiceTranscriptionOpen() && !TranscribeButton.isTranscribing(messageObject)) {
            return messageObject.getVoiceTranscription();
        }
        if (messageObject.caption != null) {
            return messageObject.caption;
        }
        if (group == null) {
            return null;
        }
        CharSequence caption = null;
        for (int a = 0, N = group.messages.size(); a < N; a++) {
            MessageObject message = group.messages.get(a);
            if (message.caption != null) {
                if (caption != null) {
                    return null;
                }
                caption = message.caption;
            }
        }
        return caption;
    }

    public CharSequence getMessageContent(MessageObject messageObject) {
        SpannableStringBuilder str = new SpannableStringBuilder();
        String restrictionReason = getMessagesController().getRestrictionReason(messageObject.messageOwner.restriction_reason);
        if (!TextUtils.isEmpty(restrictionReason)) {
            str.append(restrictionReason);
        } else if (messageObject.caption != null) {
            str.append(messageObject.caption);
        } else {
            str.append(messageObject.messageText);
        }
        return str;
    }

    public boolean isTopic(MessageObject messageObject) {
        TLRPC.TL_forumTopic topic = MessagesController.getInstance(currentAccount).getTopicsController().findTopic(
                -messageObject.getDialogId(), MessageObject.getTopicId(currentAccount, messageObject.messageOwner, true)
        );
        return topic != null;
    }

}
