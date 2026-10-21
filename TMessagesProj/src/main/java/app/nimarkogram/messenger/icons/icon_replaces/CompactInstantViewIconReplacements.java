/* Copyright (C) 2026 Ettacent; GPL v2 or later. */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class CompactInstantViewIconReplacements {
    private CompactInstantViewIconReplacements() { }

    static void apply(BaseIconReplace pack) {
        final int target;
        if (pack instanceof SolarIconReplace) {
            target = R.drawable.ng_compact_instant_solar;
        } else if (pack instanceof LiquidGlassFullReplace) {
            target = R.drawable.ng_compact_instant_liquid;
        } else if (pack instanceof PlumpyFullReplace) {
            target = R.drawable.ng_compact_instant_plumpy;
        } else {
            return;
        }
        pack.replaces.put(R.drawable.msg_instant, target);
        pack.replaces.put(R.drawable.msg_instant_solar, target);
        pack.noTint.remove(R.drawable.msg_instant);
        pack.noTint.remove(R.drawable.msg_instant_solar);
    }
}
