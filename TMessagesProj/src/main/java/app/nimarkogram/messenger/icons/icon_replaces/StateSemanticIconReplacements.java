/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class StateSemanticIconReplacements {
    private StateSemanticIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        replace(pack, R.drawable.list_unmute, R.drawable.ng_state_semantic_list_unmute_liquid);
        replace(pack, R.drawable.filled_location_forever, R.drawable.ng_state_semantic_location_forever_liquid);
        replace(pack, R.drawable.calls_decline, R.drawable.ng_state_semantic_calls_decline_liquid);
        replace(pack, R.drawable.calls_decline_solar, R.drawable.ng_state_semantic_call_end_liquid);
        replace(pack, R.drawable.ic_call_end_white_24dp, R.drawable.ng_state_semantic_call_end_liquid);
        replace(pack, R.drawable.msg_endcall, R.drawable.ng_state_semantic_call_end_liquid);
        replace(pack, R.drawable.msg_endcall_solar, R.drawable.ng_state_semantic_call_end_liquid);
        replace(pack, R.drawable.ic_call_notification_decline, R.drawable.ic_call_notification_decline);
        replace(pack, R.drawable.menu_devices, R.drawable.msg2_devices_liquid);
        replace(pack, R.drawable.msg_tone_off, R.drawable.ng_state_semantic_tone_off_liquid);
        replace(pack, R.drawable.msg_tone_off_solar, R.drawable.ng_state_semantic_tone_off_liquid);
        replace(pack, R.drawable.msg_mini_fireoff, R.drawable.ng_state_semantic_mini_fireoff_liquid);
        replace(pack, R.drawable.msg_mute_1h, R.drawable.ng_state_semantic_mute_recent_liquid);
        replace(pack, R.drawable.msg_mute_1h_solar, R.drawable.ng_state_semantic_mute_recent_liquid);
        replace(pack, R.drawable.msg_mute_period, R.drawable.ng_state_semantic_mute_period_liquid);
        replace(pack, R.drawable.msg_mute_period_solar, R.drawable.ng_state_semantic_mute_period_liquid);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
