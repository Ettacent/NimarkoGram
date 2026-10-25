/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class SolarActionIconReplacements {
    private SolarActionIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        replace(pack, R.drawable.ng_media_cancel, R.drawable.ng_media_cancel_solar);
        replace(pack, R.drawable.ng_media_stop, R.drawable.ng_media_stop_solar);
        replace(pack, R.drawable.msg_select_between_solar, R.drawable.ng_action_select_between_solar);
        replace(pack, R.drawable.filled_profile_photo, R.drawable.ng_action_profile_photo_solar);
        replace(pack, R.drawable.outline_profile_photo, R.drawable.ng_action_profile_photo_solar);
        replace(pack, R.drawable.filled_profile_photo_20, R.drawable.ng_action_profile_photo_20_solar);
        replace(pack, R.drawable.profile_qr_scan_24, R.drawable.ng_action_qr_scan_solar);
        replace(pack, R.drawable.scan_qr, R.drawable.ng_action_qr_scan_solar);
        replace(pack, R.drawable.outline_header_qr_24, R.drawable.msg_qrcode_solar);
        replace(pack, R.drawable.wallet_card_qr, R.drawable.msg_qrcode_solar);
        replace(pack, R.drawable.filled_profile_story, R.drawable.ng_action_story_add_solar);
        replace(pack, R.drawable.menu_add_stories, R.drawable.ng_action_story_add_solar);
        replace(pack, R.drawable.outline_profile_story, R.drawable.ng_action_story_add_72_solar);
        replace(pack, R.drawable.menu_premium_effects, R.drawable.ng_action_message_effects_solar);
        replace(pack, R.drawable.menu_premium_tools, R.drawable.ng_action_chat_tools_solar);
        replace(pack, R.drawable.msg_plugins, R.drawable.ng_action_plugins_solar);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
