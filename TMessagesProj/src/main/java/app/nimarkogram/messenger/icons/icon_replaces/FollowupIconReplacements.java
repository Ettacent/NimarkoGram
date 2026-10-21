/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class FollowupIconReplacements {
    private FollowupIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        if (liquid) {
            add(pack, R.drawable.large_ads_info, R.drawable.ng_followup_large_ads_info_liquid);
            add(pack, R.drawable.large_away, R.drawable.ng_followup_large_away_liquid);
            add(pack, R.drawable.large_greeting, R.drawable.ng_followup_large_greeting_liquid);
            add(pack, R.drawable.large_quickreplies, R.drawable.ng_followup_large_quickreplies_liquid);
            add(pack, R.drawable.menu_feature_withdrawals, R.drawable.ng_followup_menu_feature_withdrawals_liquid);
            add(pack, R.drawable.tooltip_sound, R.drawable.ng_followup_tooltip_sound_liquid);
        } else {
            add(pack, R.drawable.large_ads_info, R.drawable.ng_followup_large_ads_info_plumpy);
            add(pack, R.drawable.large_away, R.drawable.ng_followup_large_away_plumpy);
            add(pack, R.drawable.large_greeting, R.drawable.ng_followup_large_greeting_plumpy);
            add(pack, R.drawable.large_quickreplies, R.drawable.ng_followup_large_quickreplies_plumpy);
            add(pack, R.drawable.tooltip_sound, R.drawable.ng_followup_tooltip_sound_plumpy);
        }
    }

    private static void add(BaseIconReplace pack, int source, int target) {
        if (pack.replaces.putIfAbsent(source, target) == null) {
            pack.noTint.remove(source);
        }
    }
}
