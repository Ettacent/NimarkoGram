/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class ControlSemanticIconReplacements {
    private ControlSemanticIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        replace(pack, R.drawable.msg_contacts_name, R.drawable.ng_control_semantic_sort_name_liquid);
        replace(pack, R.drawable.msg_contacts_name_solar, R.drawable.ng_control_semantic_sort_name_liquid);
        replace(pack, R.drawable.msg_contacts_time, R.drawable.ng_control_semantic_sort_time_liquid);
        replace(pack, R.drawable.msg_contacts_time_solar, R.drawable.ng_control_semantic_sort_time_liquid);
        replace(pack, R.drawable.player_new_repeat_shuffle, R.drawable.ng_control_semantic_repeat_shuffle_liquid);
        replace(pack, R.drawable.player_new_repeat_reverse, R.drawable.ng_control_semantic_repeat_reverse_liquid);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
