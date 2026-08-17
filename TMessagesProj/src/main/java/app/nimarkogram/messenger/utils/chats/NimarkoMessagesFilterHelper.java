/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils.chats;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public final class NimarkoMessagesFilterHelper {

    private NimarkoMessagesFilterHelper() {}

    public static boolean isFiltered(MessageObject messageObject) {
        return false;
    }

    public static ArrayList<TLRPC.MessageEntity> addSpoilerEntities(MessageObject messageObject) {
        return addSpoilerEntities(messageObject, messageObject.messageOwner.entities);
    }

    public static ArrayList<TLRPC.MessageEntity> addSpoilerEntities(
            MessageObject messageObject,
            ArrayList<TLRPC.MessageEntity> original
    ) {
        return original;
    }

    public static String addSpoilerEntities(String originalText) {
        return originalText;
    }

    public static boolean shouldBlockMessage(MessageObject messageObject) {
        return false;
    }

    public static String getExcludedList() {
        return "excluded_for_filters";
    }

    public static int getExcludedChatsCount() {
        return 0;
    }

    public static void saveArrayList(ArrayList<String> list, String key) {
    }

    public static ArrayList<String> getArrayList(String key) {
        return new ArrayList<>();
    }
     
}
