package app.nimarkogram.messenger.utils;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoLocalEmoji {

    private NimarkoLocalEmoji() {}

    private static final String PREFIX = "tg://emoji?id=";

    public static boolean canUse(int account) {
        try {
            return NimarkoConfig.localPremiumEmojis
                    && !UserConfig.getInstance(account).isPremium();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void replaceCustomEmojis(int account, long dialogId,
                                           ArrayList<TLRPC.MessageEntity> entities) {
        replaceCustomEmojis(account, dialogId, entities, false);
    }

    public static void replaceCustomEmojis(int account, long dialogId,
                                           ArrayList<TLRPC.MessageEntity> entities, boolean force) {
        if ((!canUse(account) && !force) || entities == null || entities.isEmpty()) {
            return;
        }
        
        try {
            if (!force && dialogId > 0 && dialogId == UserConfig.getInstance(account).getClientUserId()) {
                return;
            }
        } catch (Throwable ignored) {}

        HashSet<Long> groupSetIds = new HashSet<>();
        try {
            if (!force && dialogId < 0) {
                TLRPC.ChatFull chatFull = MessagesController.getInstance(account).getChatFull(-dialogId);
                if (chatFull != null && chatFull.emojiset != null) {
                    TLRPC.TL_messages_stickerSet set =
                            MediaDataController.getInstance(account).getGroupStickerSetById(chatFull.emojiset);
                    if (set != null && set.documents != null) {
                        for (TLRPC.Document d : set.documents) {
                            if (d != null) groupSetIds.add(d.id);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity e = entities.get(i);
            if (e instanceof TLRPC.TL_messageEntityCustomEmoji) {
                TLRPC.TL_messageEntityCustomEmoji ce = (TLRPC.TL_messageEntityCustomEmoji) e;
                if (force && !ce.local) continue;
                if (groupSetIds.contains(ce.document_id)) continue;
                TLRPC.TL_messageEntityTextUrl url = new TLRPC.TL_messageEntityTextUrl();
                url.offset = ce.offset;
                url.length = ce.length;
                url.url = PREFIX + ce.document_id;
                entities.set(i, url);
            }
        }
    }

    public static boolean parseCustomEmojis(CharSequence text,
                                            ArrayList<TLRPC.MessageEntity> entities, long msgId) {
        if (text == null || entities == null || entities.isEmpty()) {
            return false;
        }
        boolean changed = false;
        int inserted = 0;
        ArrayList<TLRPC.MessageEntity> snapshot = new ArrayList<>(entities);
        for (int i = 0; i < snapshot.size(); i++) {
            TLRPC.MessageEntity e = snapshot.get(i);
            if (!(e instanceof TLRPC.TL_messageEntityTextUrl)) continue;
            TLRPC.TL_messageEntityTextUrl url = (TLRPC.TL_messageEntityTextUrl) e;
            if (url.url == null || !url.url.startsWith(PREFIX)) continue;
            try {
                long docId = Long.parseLong(url.url.substring(PREFIX.length()));
                int off = url.offset;
                int len = url.length;
                if (off < 0 || len <= 0 || off + len > text.length()) continue;
                int[] emojiOnly = new int[1];
                ArrayList<Emoji.EmojiSpanRange> emojis =
                        Emoji.parseEmojis(text.subSequence(off, off + len).toString(), emojiOnly);
                if (emojiOnly[0] > 0 && emojis.size() == 1) {
                    TLRPC.TL_messageEntityCustomEmoji ce = new TLRPC.TL_messageEntityCustomEmoji();
                    ce.document_id = docId;
                    ce.offset = off;
                    ce.length = len;
                    ce.local = true;
                    if (msgId > 0) {
                        entities.set(i, ce);
                    } else {
                        entities.add(i + inserted, ce);
                        inserted++;
                    }
                    changed = true;
                }
            } catch (NumberFormatException ex) {
                FileLog.e("nimarko-local-emoji: bad id " + url.url, ex);
            }
        }
        return changed;
    }
}
