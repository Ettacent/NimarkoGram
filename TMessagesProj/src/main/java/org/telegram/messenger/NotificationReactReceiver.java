package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

import org.telegram.tgnet.TLRPC;

public class NotificationReactReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        long dialogId = intent.getLongExtra("dialog_id", 0);
        int maxId = intent.getIntExtra("max_id", 0);
        int currentAccount = intent.getIntExtra("currentAccount", 0);
        String reaction = intent.getStringExtra("reaction");
        int notificationId = intent.getIntExtra("notification_id", 0);
        if (dialogId == 0 || maxId == 0 || reaction == null || reaction.isEmpty()
                || !UserConfig.isValidAccount(currentAccount)) {
            return;
        }

        final AccountInstance acc = AccountInstance.getInstance(currentAccount);

        TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        req.peer = acc.getMessagesController().getInputPeer(dialogId);
        req.msg_id = maxId;
        if (reaction.startsWith("animated_")) {
            try {
                TLRPC.TL_reactionCustomEmoji r = new TLRPC.TL_reactionCustomEmoji();
                r.document_id = Long.parseLong(reaction.substring("animated_".length()));
                req.reaction.add(r);
                req.flags |= 1;
            } catch (Exception e) {
                return;
            }
        } else {
            TLRPC.TL_reactionEmoji r = new TLRPC.TL_reactionEmoji();
            r.emoticon = reaction;
            req.reaction.add(r);
            req.flags |= 1;
        }

        acc.getConnectionsManager().sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                acc.getMessagesController().processUpdates((TLRPC.Updates) response, false);
            }
        });

        try {
            acc.getMessagesController().markDialogAsRead(dialogId, maxId, maxId, 0, false, 0, 0, true, 0);
        } catch (Throwable ignore) {}

        try {
            NotificationManagerCompat.from(context).cancel(notificationId);
        } catch (Throwable ignore) {}
    }
}
