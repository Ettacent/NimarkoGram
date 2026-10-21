/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class PlumpyActionIconReplacements {
    private PlumpyActionIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        replace(pack, R.drawable.ng_media_cancel, R.drawable.ng_media_cancel_plumpy);
        replace(pack, R.drawable.ng_media_stop, R.drawable.ng_media_stop_plumpy);
        replace(pack, R.drawable.msg_select_between_solar, R.drawable.ng_action_select_between_plumpy);
        replace(pack, R.drawable.outline_profile_photo, R.drawable.ng_core_filled_profile_photo_plumpy);
        replace(pack, R.drawable.msg_addphoto, R.drawable.ng_core_filled_profile_photo_plumpy);
        replace(pack, R.drawable.msg_addphoto_solar, R.drawable.ng_core_filled_profile_photo_plumpy);
        replace(pack, R.drawable.filled_profile_photo_20, R.drawable.ng_action_profile_photo_20_plumpy);
        replace(pack, R.drawable.profile_qr_scan_24, R.drawable.ng_action_qr_scan_plumpy);
        replace(pack, R.drawable.filled_profile_story, R.drawable.ng_action_story_add_plumpy);
        replace(pack, R.drawable.menu_add_stories, R.drawable.ng_action_story_add_plumpy);
        replace(pack, R.drawable.outline_profile_story, R.drawable.ng_action_story_add_72_plumpy);
        replace(pack, R.drawable.menu_premium_effects, R.drawable.ng_action_message_effects_plumpy);
        replace(pack, R.drawable.menu_premium_tools, R.drawable.ng_action_chat_tools_plumpy);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
