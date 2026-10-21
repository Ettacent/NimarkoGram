/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class SemanticIconReplacements {
    private SemanticIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        replace(pack, R.drawable.msg_sticker, R.drawable.ng_core_msg_sticker_liquid);
        replace(pack, R.drawable.msg_sticker_solar, R.drawable.ng_core_msg_sticker_liquid);
        replace(pack, R.drawable.msg2_sticker, R.drawable.ng_core_msg_sticker_liquid);
        replace(pack, R.drawable.msg_photo_sticker, R.drawable.ng_core_msg_sticker_liquid);
        replace(pack, R.drawable.msg_emoji_stickers, R.drawable.ng_core_msg_sticker_liquid);
        replace(pack, R.drawable.smiles_tab_stickers, R.drawable.ng_core_msg_sticker_liquid);
        pack.replaces.put(R.drawable.smiles_tab_gif, pack.wrap(R.drawable.msg_gif));
        replace(pack, R.drawable.media_sticker_stroke, R.drawable.ng_core_msg_sticker_liquid);
        replace(pack, R.drawable.menu_link_above, R.drawable.ng_core_menu_link_above_liquid);
        replace(pack, R.drawable.menu_link_below, R.drawable.ng_core_menu_link_below_liquid);
        replace(pack, R.drawable.menu_quote_specific, R.drawable.ng_core_menu_quote_specific_liquid);
        replace(pack, R.drawable.menu_quote_delete, R.drawable.ng_core_menu_quote_delete_liquid);
        replace(pack, R.drawable.menu_select_quote, R.drawable.ng_core_menu_select_quote_liquid);
        replace(pack, R.drawable.mini_quote, R.drawable.ng_core_mini_quote_liquid);
        replace(pack, R.drawable.menu_sticker_select, R.drawable.ng_core_menu_sticker_select_liquid);
        pack.addAlias(R.drawable.msg_premium_stickers, R.drawable.msg_sticker);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
