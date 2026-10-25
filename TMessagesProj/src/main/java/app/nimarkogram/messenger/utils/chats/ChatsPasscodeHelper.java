/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This file is part of NimarkoGram for Android.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 * Copyright Ettacent, 2026.
 */

package app.nimarkogram.messenger.utils.chats;

import org.telegram.messenger.BaseController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReferenceArray;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.utils.LockedChats;

public class ChatsPasscodeHelper extends BaseController {

    private static final String LOCKED_CHATS_KEY = "locked_chats_list";
    private static final AtomicReferenceArray<ChatsPasscodeHelper> instances =
            new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);

    public static ChatsPasscodeHelper getInstance(int account) {
        ChatsPasscodeHelper instance = instances.get(account);
        if (instance == null) {
            synchronized (ChatsPasscodeHelper.class) {
                instance = instances.get(account);
                if (instance == null) {
                    instance = new ChatsPasscodeHelper(account);
                    instances.set(account, instance);
                }
            }
        }
        return instance;
    }

    private ChatsPasscodeHelper(int account) {
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
        return ChatsPasswordHelper.isChatLocked(currentAccount, chatId);
    }

    public boolean isChatLocked(MessageObject messageObject) {
        return ChatsPasswordHelper.isChatLocked(messageObject);
    }

    public boolean isEncryptedChat(long chatId) {
        return ChatsPasswordHelper.isEncryptedChat(chatId, currentAccount);
    }

    public boolean isEncryptedChat(MessageObject messageObject) {
        return ChatsPasswordHelper.isEncryptedChat(messageObject);
    }

    public ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(MessageObject messageObject) {
        return ChatsPasswordHelper.checkLockedChatsEntities(messageObject);
    }

    public ArrayList<TLRPC.MessageEntity> checkLockedChatsEntities(
            MessageObject messageObject, ArrayList<TLRPC.MessageEntity> original) {
        return ChatsPasswordHelper.checkLockedChatsEntities(messageObject, original);
    }

    public String replaceStringToSpoilers(String originalText, boolean force) {
        return ChatsPasswordHelper.replaceStringToSpoilers(originalText, force);
    }

    public int getLockedChatsCount() {
        return LockedChats.count(currentAccount);
    }

    public boolean shouldRequireBiometrics(long userId, long chatId, long encId) {
        return ChatsPasswordHelper.shouldRequireBiometrics(
                userId, chatId, encId, currentAccount);
    }

    public boolean shouldRequireBiometricsToOpenChats() {
        return AppConfig.askBiometricsToOpenChat;
    }

    public boolean shouldRequireBiometricsToOpenEncryptedChats() {
        return AppConfig.askBiometricsToOpenEncrypted;
    }

    public boolean askPasscodeBeforeDelete() {
        return AppConfig.askPasscodeBeforeDelete;
    }

    public boolean checkBiometricAvailable() {
        return ChatsPasswordHelper.checkBiometricAvailable();
    }
}
