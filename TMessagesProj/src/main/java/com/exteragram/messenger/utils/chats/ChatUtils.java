package com.exteragram.messenger.utils.chats;

import android.app.Activity;
import android.net.Uri;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

public final class ChatUtils {
    private final app.nimarkogram.messenger.utils.chats.ChatUtils real;
    private final int account;

    private ChatUtils(app.nimarkogram.messenger.utils.chats.ChatUtils real, int account) {
        this.real = real;
        this.account = account;
    }

    public static ChatUtils getInstance() {
        return getInstance(UserConfig.selectedAccount);
    }

    public static ChatUtils getInstance(int account) {
        return new ChatUtils(
                app.nimarkogram.messenger.utils.chats.ChatUtils.getInstance(account), account);
    }

    public String getPathToMessage(MessageObject messageObject) {
        return real.getPathToMessage(messageObject);
    }

    public void saveStickerToGallery(Activity activity, TLRPC.Document document,
                                     Utilities.Callback<Uri> callback) {
        app.nimarkogram.messenger.utils.chats.NimarkoChatHelper
                .getInstance(account).saveStickerToGallery(activity, document, callback);
    }
}
