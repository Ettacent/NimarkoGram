package app.nimarkogram.messenger.utils.chats;

import org.telegram.messenger.BaseController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReferenceArray;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.LockedChats;

public class NimarkoChatsPasscodeHelper extends BaseController {

    private static final String LOCKED_CHATS_KEY = "locked_chats_list";
    private static final AtomicReferenceArray<NimarkoChatsPasscodeHelper> instances =
            new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);

    public static NimarkoChatsPasscodeHelper getInstance(int account) {
        NimarkoChatsPasscodeHelper instance = instances.get(account);
        if (instance == null) {
            synchronized (NimarkoChatsPasscodeHelper.class) {
                instance = instances.get(account);
                if (instance == null) {
                    instance = new NimarkoChatsPasscodeHelper(account);
                    instances.set(account, instance);
                }
            }
        }
        return instance;
    }

    private NimarkoChatsPasscodeHelper(int account) {
        super(account);
    }

    public String getPasscodeArray() {
        return LOCKED_CHATS_KEY;
    }

    public void saveArrayList(ArrayList<String> list, String key) {
        if (!LOCKED_CHATS_KEY.equals(key)) return;
        HashSet<Long> dialogIds = new HashSet<>();
        if (list != null) {
            for (String value : list) {
                try {
                    long dialogId = Long.parseLong(value);
                    if (dialogId != 0L) dialogIds.add(dialogId);
                } catch (Throwable ignored) {
                }
            }
        }
        LockedChats.replaceAll(currentAccount, getUserConfig().getClientUserId(), dialogIds);
    }

    public ArrayList<String> getArrayList(String key) {
        return LOCKED_CHATS_KEY.equals(key) ? LockedChats.getAll(currentAccount) : new ArrayList<>();
    }

    public boolean isChatLocked(long chatId) {
        return NimarkoChatsPasswordHelper.isChatLocked(currentAccount, chatId);
    }

    public boolean isChatLocked(MessageObject messageObject) {
        return NimarkoChatsPasswordHelper.isChatLocked(messageObject);
    }

    public boolean isEncryptedChat(long chatId) {
        return NimarkoChatsPasswordHelper.isEncryptedChat(chatId, currentAccount);
    }

    public boolean isEncryptedChat(MessageObject messageObject) {
        return NimarkoChatsPasswordHelper.isEncryptedChat(messageObject);
    }

    public ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(MessageObject messageObject) {
        return NimarkoChatsPasswordHelper.checkLockedChatsEntities(messageObject);
    }

    public ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(
            MessageObject messageObject, ArrayList<TLRPC.MessageEntity> original) {
        return NimarkoChatsPasswordHelper.checkLockedChatsEntities(messageObject, original);
    }

    public String replaceStringToSpoilers(String originalText, boolean force) {
        return NimarkoChatsPasswordHelper.replaceStringToSpoilers(originalText, force);
    }

    public int getLockedChatsCount() {
        return LockedChats.count(currentAccount);
    }

    public boolean shouldRequireBiometrics(long userId, long chatId, long encId) {
        return NimarkoChatsPasswordHelper.shouldRequireBiometrics(
                userId, chatId, encId, currentAccount);
    }

    public boolean shouldRequireBiometricsToOpenChats() {
        return NimarkoConfig.askBiometricsToOpenChat;
    }

    public boolean shouldRequireBiometricsToOpenEncryptedChats() {
        return NimarkoConfig.askBiometricsToOpenEncrypted;
    }

    public boolean askPasscodeBeforeDelete() {
        return NimarkoConfig.askPasscodeBeforeDelete;
    }

    public boolean checkBiometricAvailable() {
        return NimarkoChatsPasswordHelper.checkBiometricAvailable();
    }
}
