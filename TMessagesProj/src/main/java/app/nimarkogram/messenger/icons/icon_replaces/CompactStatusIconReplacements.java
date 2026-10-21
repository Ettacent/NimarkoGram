/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class CompactStatusIconReplacements {
    private CompactStatusIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        if (pack instanceof SolarIconReplace) {
            return;
        } else if (pack instanceof LiquidGlassFullReplace) {
            replace(pack, R.drawable.msg_mini_forumarrow, R.drawable.ng_compact_status_forumarrow_liquid);
            replace(pack, R.drawable.msg_mini_topicarrow, R.drawable.ng_compact_status_topicarrow_liquid);
            replace(pack, R.drawable.msg_mini_replystory2, R.drawable.ng_compact_status_replystory_liquid);
            replace(pack, R.drawable.mini_external_link, R.drawable.ng_compact_status_external_link_liquid);
        } else if (pack instanceof PlumpyFullReplace) {
            replace(pack, R.drawable.msg_mini_forumarrow, R.drawable.ng_compact_status_forumarrow_plumpy);
            replace(pack, R.drawable.msg_mini_topicarrow, R.drawable.ng_compact_status_topicarrow_plumpy);
            replace(pack, R.drawable.msg_mini_replystory2, R.drawable.ng_compact_status_replystory_plumpy);
            replace(pack, R.drawable.mini_external_link, R.drawable.ng_compact_status_external_link_plumpy);
        } else {
            return;
        }
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
