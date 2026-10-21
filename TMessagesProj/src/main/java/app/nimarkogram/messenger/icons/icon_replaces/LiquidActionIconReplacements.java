/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class LiquidActionIconReplacements {
    private LiquidActionIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        replace(pack, R.drawable.ng_media_cancel, R.drawable.ng_media_cancel_liquid);
        replace(pack, R.drawable.ng_media_stop, R.drawable.ng_media_stop_liquid);
        replace(pack, R.drawable.msg_select_between_solar, R.drawable.ng_action_select_between_liquid);
        replace(pack, R.drawable.msg_pollstop, R.drawable.ng_action_poll_stop_liquid);
        replace(pack, R.drawable.msg_gif_add, R.drawable.ng_action_gif_add_liquid);
        replace(pack, R.drawable.msg_ungroup, R.drawable.ng_action_ungroup_liquid);
        replace(pack, R.drawable.msg_newphone, R.drawable.ng_action_change_phone_liquid);
        replace(pack, R.drawable.msg_newphone_solar, R.drawable.ng_action_change_phone_liquid);
        replace(pack, R.drawable.msg_viewreplies, R.drawable.ng_action_view_replies_liquid);
        replace(pack, R.drawable.msg_viewreplies_solar, R.drawable.ng_action_view_replies_liquid);
        replace(pack, R.drawable.msg_unfave, R.drawable.ng_action_unfavorite_liquid);
        replace(pack, R.drawable.msg_unfave_solar, R.drawable.ng_action_unfavorite_liquid);
        replace(pack, R.drawable.menu_hide_gift, R.drawable.ng_action_hide_gift_liquid);
        replace(pack, R.drawable.msg_unpin_solar, R.drawable.ng_semantic_msg_unpin_liquid);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
