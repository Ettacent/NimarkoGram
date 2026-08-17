package app.nimarkogram.messenger.badges;

import android.text.TextUtils;

import app.nimarkogram.messenger.api.dto.BadgeDTO;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BulletinFactory;

public final class BadgeUi {

    private BadgeUi() {}

    public static CharSequence accessibilityLabel(BadgeDTO badge) {
        if (badge == null) return null;
        CharSequence title = LocaleController.getString(R.string.NM_ProfileBadge);
        return TextUtils.isEmpty(badge.getText()) ? title : title + ": " + badge.getText();
    }

    public static void showBulletin(int account, BadgeDTO badge) {
        if (badge == null || badge.getDocumentId() == 0L) return;
        CharSequence text = TextUtils.isEmpty(badge.getText())
                ? LocaleController.getString(R.string.NM_ProfileBadge) : badge.getText();
        TLRPC.Document cached = AnimatedEmojiDrawable.findDocument(account, badge.getDocumentId());
        if (cached != null) {
            showBulletin(cached, text);
            return;
        }
        AnimatedEmojiDrawable.getDocumentFetcher(account).fetchDocument(badge.getDocumentId(), document -> {
            if (document != null) AndroidUtilities.runOnUIThread(() -> showBulletin(document, text));
        });
    }

    private static void showBulletin(TLRPC.Document document, CharSequence text) {
        if (document == null) return;
        BulletinFactory.global().createEmojiBulletin(document, text).show();
    }
}
