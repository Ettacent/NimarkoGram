/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class FinalIconReplacements {
    private FinalIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        pack.replaces.putIfAbsent(R.drawable.filled_gram_earnings, liquid ? R.drawable.ng_core_filled_gram_earnings_liquid : R.drawable.ng_core_filled_gram_earnings_plumpy);
        pack.replaces.putIfAbsent(R.drawable.pill_usd, liquid ? R.drawable.ng_core_pill_usd_liquid : R.drawable.ng_core_pill_usd_plumpy);
    }
}
