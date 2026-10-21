/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class PlumpySemanticIconReplacements {
    private PlumpySemanticIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        pack.replaces.put(R.drawable.ic_send, R.drawable.ng_send_plane_plumpy);
        pack.replaces.put(R.drawable.ic_send_solar, R.drawable.ng_send_plane_plumpy);
        pack.replaces.put(R.drawable.msg_sticker, R.drawable.ng_composer_sticker_plumpy);
        pack.replaces.put(R.drawable.msg_sticker_solar, R.drawable.ng_composer_sticker_plumpy);
        pack.replaces.put(R.drawable.msg2_sticker, R.drawable.ng_composer_sticker_plumpy);
        pack.replaces.put(R.drawable.msg_photo_sticker, R.drawable.ng_composer_sticker_plumpy);
        pack.replaces.put(R.drawable.msg_emoji_stickers, R.drawable.ng_composer_sticker_plumpy);
        pack.replaces.put(R.drawable.smiles_tab_stickers, R.drawable.ng_composer_sticker_plumpy);
        pack.replaces.put(R.drawable.media_sticker_stroke, R.drawable.ng_composer_sticker_plumpy);
        pack.replaces.put(R.drawable.msg_premium_stickers, R.drawable.ng_composer_sticker_plumpy);
        pack.replaces.put(R.drawable.filled_birthday, R.drawable.ng_core_filled_birthday_plumpy);
        pack.replaces.put(R.drawable.filled_profile_photo, R.drawable.ng_core_filled_profile_photo_plumpy);
        pack.replaces.put(R.drawable.round_warn, R.drawable.ng_core_round_warn_plumpy);
        pack.replaces.put(R.drawable.menu_quote_specific, R.drawable.ng_core_menu_quote_specific_plumpy);
        pack.replaces.put(R.drawable.menu_quote_delete, R.drawable.ng_core_menu_quote_delete_plumpy);
    }
}
