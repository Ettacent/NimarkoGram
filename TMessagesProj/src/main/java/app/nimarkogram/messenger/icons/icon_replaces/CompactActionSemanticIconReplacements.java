/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class CompactActionSemanticIconReplacements {
    private CompactActionSemanticIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        if (!(pack instanceof LiquidGlassFullReplace)) {
            return;
        }
        replace(pack, R.drawable.mini_call_join, R.drawable.ng_compact_call_join_liquid);
        replace(pack, R.drawable.mini_boost_remove, R.drawable.ng_compact_boost_remove_liquid);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
