/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class DurationSemanticIconReplacements {
    private DurationSemanticIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        if (liquid) {
            replace(pack, R.drawable.msg_autodelete_1d, R.drawable.ng_duration_semantic_retention_1d_liquid);
            replace(pack, R.drawable.msg_autodelete_1d_solar, R.drawable.ng_duration_semantic_retention_1d_liquid);
            replace(pack, R.drawable.msg_autodelete_2d, R.drawable.ng_duration_semantic_retention_2d_liquid);
            replace(pack, R.drawable.msg_autodelete_1w, R.drawable.ng_duration_semantic_retention_1w_liquid);
            replace(pack, R.drawable.msg_autodelete_1w_solar, R.drawable.ng_duration_semantic_retention_1w_liquid);
            replace(pack, R.drawable.msg_autodelete_1m, R.drawable.ng_duration_semantic_retention_1m_liquid);
            replace(pack, R.drawable.msg_autodelete_1m_solar, R.drawable.ng_duration_semantic_retention_1m_liquid);
        } else {
            replace(pack, R.drawable.notifications_mute1h, R.drawable.ng_duration_semantic_mute_1h_plumpy);
            replace(pack, R.drawable.notifications_mute1h_solar, R.drawable.ng_duration_semantic_mute_1h_plumpy);
            replace(pack, R.drawable.notifications_mute2d, R.drawable.ng_duration_semantic_mute_2d_plumpy);
            replace(pack, R.drawable.notifications_mute2d_solar, R.drawable.ng_duration_semantic_mute_2d_plumpy);
        }
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
