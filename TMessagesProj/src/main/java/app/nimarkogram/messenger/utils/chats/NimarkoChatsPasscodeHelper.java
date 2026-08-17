/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils.chats;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.BaseController;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FingerprintController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReferenceArray;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.security.NimarkoBiometricPrompt;

public class NimarkoChatsPasscodeHelper extends BaseController {

    private static final AtomicReferenceArray<NimarkoChatsPasscodeHelper> instances =
            new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);

    public static NimarkoChatsPasscodeHelper getInstance(int num) {
        NimarkoChatsPasscodeHelper localInstance = instances.get(num);
        if (localInstance == null) {
            synchronized (NimarkoChatsPasscodeHelper.class) {
                localInstance = instances.get(num);
                if (localInstance == null) {
                    localInstance = new NimarkoChatsPasscodeHelper(num);
                    instances.set(num, localInstance);
                }
            }
        }
        return localInstance;
    }

    private NimarkoChatsPasscodeHelper(int num) {
        super(num);
    }

    private HashSet<String> lockedChatsCache;

    public String getPasscodeArray() {
        return "locked_chats_list";
    }

    public void saveArrayList(ArrayList<String> list, String key) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил saveArrayList");

        if (key.equals(getPasscodeArray())) {
            lockedChatsCache = new HashSet<>(list);
        }

        getMessagesController().getMainSettings()
                .edit()
                .putString(key, new Gson().toJson(list))
                .apply();
    }

    public ArrayList<String> getArrayList(String key) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил кешированный getArrayList для паролей");

        if (key.equals(getPasscodeArray()) && lockedChatsCache != null) {
            return new ArrayList<>(lockedChatsCache);
        }

        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил getArrayList для паролей");

        String json = getMessagesController().getMainSettings().getString(key, null);
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("getArrayList: " + json);
        ArrayList<String> list = null;
        try {
            list = new Gson().fromJson(json, new TypeToken<ArrayList<String>>() {}.getType());
        } catch (Throwable ignore) {}
        if (list == null) {
            list = new ArrayList<>();
            list.add(String.valueOf(getUserConfig().clientUserId));
        }

        if (key.equals(getPasscodeArray())) {
            lockedChatsCache = new HashSet<>(list);
        }

        return list;
    }

    public boolean isChatLocked(long chatId) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил isChatLocked");
        if (chatId == 0L || !NimarkoConfig.askBiometricsToOpenChat) return false;

        if (lockedChatsCache == null) {
            getArrayList(getPasscodeArray());
        }

        String idStr = String.valueOf(chatId);
        return (lockedChatsCache != null && lockedChatsCache.contains(idStr))
                || (lockedChatsCache != null && lockedChatsCache.contains("-" + idStr));
    }

    public boolean isChatLocked(MessageObject messageObject) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил isChatLocked2");
        return NimarkoConfig.askBiometricsToOpenChat && messageObject.messageOwner.message != null
                && !messageObject.isStoryReactionPush && !messageObject.isStoryPush
                && !messageObject.isStoryMentionPush && !messageObject.isStoryPushHidden
                && isChatLocked(messageObject.getChatId());
    }

    public boolean isEncryptedChat(long chatId) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил isEncryptedChat");
        if (NimarkoConfig.askBiometricsToOpenEncrypted) {
            int encID;
            if (DialogObject.isEncryptedDialog(chatId)) {
                encID = DialogObject.getEncryptedChatId(chatId);
            } else if (chatId > 0 && chatId <= Integer.MAX_VALUE) {
                encID = (int) chatId;
            } else {
                return false;
            }
            TLRPC.EncryptedChat encryptedChat = getMessagesController().getEncryptedChat(encID);
            return encryptedChat != null;
        } else {
            return false;
        }
    }

    public boolean isEncryptedChat(MessageObject messageObject) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил isEncryptedChat2");
        if (NimarkoConfig.askBiometricsToOpenEncrypted) {
            int encID = DialogObject.getEncryptedChatId(messageObject.getDialogId());
            TLRPC.EncryptedChat encryptedChat = getMessagesController().getEncryptedChat(encID);
            return messageObject.messageOwner.message != null
                    && !messageObject.isStoryReactionPush && !messageObject.isStoryPush
                    && !messageObject.isStoryMentionPush && !messageObject.isStoryPushHidden
                    && encryptedChat != null;
        } else {
            return false;
        }
    }

    public ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(MessageObject messageObject) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил checkLockedChatsEntities");
        return checkLockedChatsEntities(messageObject, messageObject.messageOwner.entities);
    }

    public ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(MessageObject messageObject, ArrayList<TLRPC.MessageEntity> original) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил checkLockedChatsEntities2");
        if (isChatLocked(messageObject) || isEncryptedChat(messageObject)) {
            ArrayList<TLRPC.MessageEntity> entities = original != null ? new ArrayList<>(original) : null;
            TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
            spoiler.offset = 0;
            spoiler.length = messageObject.messageOwner.message.length();
            if (entities != null) entities.add(spoiler);
            return entities;
        }
        return original;
    }

    private final char[] spoilerChars = new char[] {
            '⠌', '⡢', '⢑', '⠨', '⠥', '⠮', '⡑'
    };

    public String replaceStringToSpoilers(String originalText, boolean force) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил replaceStringToSpoilers");
        if (originalText == null) {
            return null;
        }
        if (NimarkoConfig.askBiometricsToOpenArchive || force) {
            StringBuilder stringBuilder = new StringBuilder(originalText);
            for (int i = 0; i < originalText.length(); i++) {
                stringBuilder.setCharAt(i, spoilerChars[i % spoilerChars.length]);
            }
            return stringBuilder.toString();
        }
        return originalText;
    }

    public int getLockedChatsCount() {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил getLockedChatsCount");
        return getArrayList(getPasscodeArray()).size();
    }

    public boolean shouldRequireBiometrics(long userID, long chatID, long encID) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил shouldRequireBiometrics");
        boolean lockedChat = (userID != 0L && isChatLocked(userID)) || (chatID != 0L && isChatLocked(chatID));

        boolean encryptedChat = encID != 0L && isEncryptedChat(encID);

        return (lockedChat && shouldRequireBiometricsToOpenChats()) || (encryptedChat && shouldRequireBiometricsToOpenEncryptedChats());
    }

    public boolean shouldRequireBiometricsToOpenChats() {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил shouldRequireBiometricsToOpenChats");
        return NimarkoConfig.askBiometricsToOpenChat;
    }

    public boolean shouldRequireBiometricsToOpenEncryptedChats() {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил shouldRequireBiometricsToOpenEncryptedChats");
        return NimarkoConfig.askBiometricsToOpenEncrypted;
    }

    public boolean askPasscodeBeforeDelete() {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил askPasscodeBeforeDelete");
        return NimarkoConfig.askPasscodeBeforeDelete;
    }

    public boolean checkBiometricAvailable() {
        if (BuildVars.DEBUG_PRIVATE_VERSION) FileLog.d("запросил checkBiometricAvailable");

        boolean hasBiometrics = NimarkoBiometricPrompt.hasBiometricEnrolled();
        if (!hasBiometrics) return false;

        boolean hasFingerprints = NimarkoBiometricPrompt.hasEnrolledFingerprints();
        if (hasFingerprints) {
            return FingerprintController.isKeyReady() && !FingerprintController.checkDeviceFingerprintsChanged();
        } else {
            return true;
        }
    }
}
