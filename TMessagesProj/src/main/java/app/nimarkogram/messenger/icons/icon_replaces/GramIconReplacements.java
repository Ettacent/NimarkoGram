/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class GramIconReplacements {
    private GramIconReplacements() {}

    static void solar(BaseIconReplace pack) {
        apply(pack, R.drawable.ng_gram_outline_solar, R.drawable.ng_gram_menu_solar, R.drawable.ng_gram_settings_solar);
        replace(pack, R.drawable.filled_gram_earnings, R.drawable.msg_stats_solar);
    }

    static void liquid(BaseIconReplace pack) {
        apply(pack, R.drawable.ng_gram_outline_liquid, R.drawable.ng_gram_menu_liquid, R.drawable.ng_gram_settings_liquid);
    }

    static void plumpy(BaseIconReplace pack) {
        apply(pack, R.drawable.ng_gram_outline_plumpy, R.drawable.ng_gram_menu_plumpy, R.drawable.ng_gram_settings_plumpy);
    }

    private static void apply(BaseIconReplace pack, int outline, int menu, int settings) {
        replace(pack, R.drawable.outline_gram_24, outline);
        replace(pack, R.drawable.ic_gram, outline);
        replace(pack, R.drawable.menu_gram_24, menu);
        replace(pack, R.drawable.settings_gram_24, settings);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
