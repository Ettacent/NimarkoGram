/* Modifications Copyright (C) 2026 Ettacent; GPL v2 or later. */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class MessageMetaIconReplacements {
    private MessageMetaIconReplacements() { }

    static void apply(BaseIconReplace pack) {
        if (pack instanceof SolarIconReplace) {
            replace(pack, R.drawable.msg_views, R.drawable.ng_meta_views_solar);
            replace(pack, R.drawable.msg_views_solar, R.drawable.ng_meta_views_solar);
            replace(pack, R.drawable.msg_edited, R.drawable.ng_meta_edited_solar);
            replace(pack, R.drawable.forwards_solar, R.drawable.ng_meta_forwards_solar);
            replace(pack, R.drawable.msg_pin_mini, R.drawable.ng_meta_pinned_solar);
            replace(pack, R.drawable.msg_pin_mini_solar, R.drawable.ng_meta_pinned_solar);
            replace(pack, R.drawable.msg_reply_small, R.drawable.ng_meta_replies_solar);
            replace(pack, R.drawable.msg_reply_small_solar, R.drawable.ng_meta_replies_solar);
        } else if (pack instanceof LiquidGlassFullReplace) {
            replace(pack, R.drawable.msg_views, R.drawable.ng_meta_views_liquid);
            replace(pack, R.drawable.msg_views_solar, R.drawable.ng_meta_views_liquid);
            replace(pack, R.drawable.msg_edited, R.drawable.ng_meta_edited_liquid);
            replace(pack, R.drawable.forwards_solar, R.drawable.ng_meta_forwards_liquid);
            replace(pack, R.drawable.msg_pin_mini, R.drawable.ng_meta_pinned_liquid);
            replace(pack, R.drawable.msg_pin_mini_solar, R.drawable.ng_meta_pinned_liquid);
            replace(pack, R.drawable.msg_reply_small, R.drawable.ng_meta_replies_liquid);
            replace(pack, R.drawable.msg_reply_small_solar, R.drawable.ng_meta_replies_liquid);
        } else if (pack instanceof PlumpyFullReplace) {
            replace(pack, R.drawable.msg_views, R.drawable.ng_meta_views_plumpy);
            replace(pack, R.drawable.msg_views_solar, R.drawable.ng_meta_views_plumpy);
            replace(pack, R.drawable.msg_edited, R.drawable.ng_meta_edited_plumpy);
            replace(pack, R.drawable.forwards_solar, R.drawable.ng_meta_forwards_plumpy);
            replace(pack, R.drawable.msg_pin_mini, R.drawable.ng_meta_pinned_plumpy);
            replace(pack, R.drawable.msg_pin_mini_solar, R.drawable.ng_meta_pinned_plumpy);
            replace(pack, R.drawable.msg_reply_small, R.drawable.ng_meta_replies_plumpy);
            replace(pack, R.drawable.msg_reply_small_solar, R.drawable.ng_meta_replies_plumpy);
        }
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
