/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */
package app.nimarkogram.messenger.utils.chats;

import androidx.annotation.Nullable;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FingerprintController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.security.NimarkoBiometricPrompt;
import app.nimarkogram.messenger.utils.CGCompat;
import app.nimarkogram.messenger.utils.LockedChats;

public final class NimarkoChatsPasswordHelper {

    private NimarkoChatsPasswordHelper() {}

    private static final char[] SPOILER_CHARS = new char[] {
            '⠌', '⡢', '⢑', '⠨', '⠥', '⠮', '⡑'
    };

    public static boolean isChatLocked(long chatId) {
        return isChatLocked(UserConfig.selectedAccount, chatId);
    }

    public static boolean isChatLocked(int currentAccount, long dialogId) {
        if (isSavedMessagesProtected(currentAccount, dialogId)) return true;
        return CGCompat.isChatLocked(currentAccount, dialogId);
    }

    public static boolean isChatLocked(@Nullable MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) return false;
        if (!NimarkoConfig.askBiometricsToOpenChat
                && !NimarkoConfig.askBiometricsToOpenSavedMessages) return false;
        if (messageObject.messageOwner.message == null) return false;
        if (messageObject.isStoryReactionPush || messageObject.isStoryPush
                || messageObject.isStoryMentionPush || messageObject.isStoryPushHidden) {
            return false;
        }
        return isChatLocked(messageObject.currentAccount, messageObject.getDialogId());
    }

    public static boolean isEncryptedChat(long chatId, int currentAccount) {
        if (!NimarkoConfig.askBiometricsToOpenEncrypted) return false;
        int encId;
        if (DialogObject.isEncryptedDialog(chatId)) {
            encId = DialogObject.getEncryptedChatId(chatId);
        } else if (chatId > 0 && chatId <= Integer.MAX_VALUE) {
            encId = (int) chatId; 
        } else {
            return false;
        }
        TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance(currentAccount).getEncryptedChat(encId);
        return encryptedChat != null;
    }

    public static boolean isEncryptedChat(@Nullable MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) return false;
        if (!NimarkoConfig.askBiometricsToOpenEncrypted) return false;
        if (messageObject.messageOwner.message == null) return false;
        if (messageObject.isStoryReactionPush || messageObject.isStoryPush
                || messageObject.isStoryMentionPush || messageObject.isStoryPushHidden) {
            return false;
        }
        int encId = DialogObject.getEncryptedChatId(messageObject.getDialogId());
        TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance(messageObject.currentAccount).getEncryptedChat(encId);
        return encryptedChat != null;
    }

    @Nullable
    public static ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(
            @Nullable CharSequence text,
            long dialogId,
            int currentAccount,
            @Nullable ArrayList<TLRPC.MessageEntity> original) {
        if (text == null || text.length() == 0) return original;
        boolean gated = isChatLocked(currentAccount, dialogId) || isEncryptedChat(dialogId, currentAccount);
        if (!gated) return original;
        ArrayList<TLRPC.MessageEntity> entities = original != null ? new ArrayList<>(original) : new ArrayList<>();
        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
        spoiler.offset = 0;
        spoiler.length = text.length();
        entities.add(spoiler);
        return entities;
    }

    @Nullable
    public static ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(@Nullable CharSequence text, long dialogId) {
        return checkLockedChatsEntities(text, dialogId, org.telegram.messenger.UserConfig.selectedAccount, null);
    }

    @Nullable
    public static ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(@Nullable MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) return null;
        return checkLockedChatsEntities(messageObject, messageObject.messageOwner.entities);
    }

    @Nullable
    public static ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(
            @Nullable MessageObject messageObject,
            @Nullable ArrayList<TLRPC.MessageEntity> original) {
        if (messageObject == null || messageObject.messageOwner == null) return original;
        if (!isChatLocked(messageObject) && !isEncryptedChat(messageObject)) return original;
        ArrayList<TLRPC.MessageEntity> entities = original != null ? new ArrayList<>(original) : new ArrayList<>();
        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
        spoiler.offset = 0;
        spoiler.length = messageObject.messageOwner.message != null ? messageObject.messageOwner.message.length() : 0;
        entities.add(spoiler);
        return entities;
    }

    @Nullable
    public static String replaceStringToSpoilers(@Nullable CharSequence text, boolean force) {
        if (text == null) return null;
        if (!force && !NimarkoConfig.askBiometricsToOpenArchive) return text.toString();
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0, n = text.length(); i < n; i++) {
            sb.append(SPOILER_CHARS[i % SPOILER_CHARS.length]);
        }
        return sb.toString();
    }

    @Nullable
    public static String replaceStringToSpoilers(@Nullable CharSequence text) {
        return replaceStringToSpoilers(text, false);
    }

    public static int getLockedChatsCount() {
        return getLockedChatsCount(org.telegram.messenger.UserConfig.selectedAccount);
    }

    public static int getLockedChatsCount(int currentAccount) {
        return LockedChats.count(currentAccount);
    }

    public static boolean shouldRequireBiometrics(long userId, long chatId, long encId, int currentAccount) {
        boolean savedMessages = userId != 0L
                && userId == UserConfig.getInstance(currentAccount).getClientUserId();
        boolean lockedChat = (userId != 0L && isChatLocked(currentAccount, userId))
                || (chatId != 0L && isChatLocked(currentAccount, -Math.abs(chatId)));
        boolean encryptedChat = false;
        if (encId != 0L && encId >= Integer.MIN_VALUE && encId <= Integer.MAX_VALUE) {
            encryptedChat = MessagesController.getInstance(currentAccount).getEncryptedChat((int) encId) != null;
        }
        return (savedMessages && shouldRequireBiometricsToOpenSavedMessages())
                || (lockedChat && shouldRequireBiometricsToOpenChats())
                || (encryptedChat && shouldRequireBiometricsToOpenEncryptedChats());
    }

    public static boolean isSavedMessagesProtected(int currentAccount, long dialogId) {
        return NimarkoConfig.askBiometricsToOpenSavedMessages
                && dialogId != 0L
                && dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
    }

    public static boolean shouldRequireBiometricsToOpenChats() {
        return NimarkoConfig.askBiometricsToOpenChat;
    }

    public static boolean shouldRequireBiometricsToOpenSavedMessages() {
        return NimarkoConfig.askBiometricsToOpenSavedMessages;
    }

    public static boolean shouldRequireBiometricsToOpenEncryptedChats() {
        return NimarkoConfig.askBiometricsToOpenEncrypted;
    }

    public static boolean shouldRequireBiometricsToOpenArchive() {
        return NimarkoConfig.askBiometricsToOpenArchive;
    }

    public static boolean askPasscodeBeforeDelete() {
        return NimarkoConfig.askPasscodeBeforeDelete;
    }

    public static boolean checkBiometricAvailable() {
        if (!NimarkoBiometricPrompt.canAuthenticateConfigured()) return false;
        if (NimarkoBiometricPrompt.hasEnrolledFingerprints()) {
            return FingerprintController.isKeyReady() && !FingerprintController.checkDeviceFingerprintsChanged();
        }
        return true;
    }
}
