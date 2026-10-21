/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class QualityIconReplacements {
    private QualityIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        int sd = liquid ? R.drawable.ng_quality_sd_liquid : R.drawable.ng_quality_sd_plumpy;
        int hd = liquid ? R.drawable.ng_quality_hd_liquid : R.drawable.ng_quality_hd_plumpy;
        replace(pack, R.drawable.menu_quality_sd, sd);
        replace(pack, R.drawable.menu_quality_sd_filled, sd);
        replace(pack, R.drawable.menu_quality_hd, hd);
        replace(pack, R.drawable.menu_quality_hd_filled, hd);
        replace(pack, R.drawable.menu_quality_hd2,
                liquid ? R.drawable.ng_quality_hd_locked_liquid : R.drawable.ng_quality_hd_locked_plumpy);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
