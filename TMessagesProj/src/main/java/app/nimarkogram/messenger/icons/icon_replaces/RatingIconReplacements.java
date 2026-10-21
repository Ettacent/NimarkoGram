/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class RatingIconReplacements {
    private RatingIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        pack.replaces.put(R.drawable.ic_rating_star, liquid ? R.drawable.ng_core_ic_rating_star_liquid : R.drawable.ng_core_ic_rating_star_plumpy);
        pack.replaces.put(R.drawable.ic_rating_star_filled, liquid ? R.drawable.ng_core_ic_rating_star_filled_liquid : R.drawable.ng_core_ic_rating_star_filled_plumpy);
        pack.noTint.remove(R.drawable.ic_rating_star);
        pack.noTint.remove(R.drawable.ic_rating_star_filled);
    }
}
