/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class SolarAnimatedIconReplacements {
    private SolarAnimatedIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        if (!(pack instanceof SolarIconReplace)) {
            return;
        }
        replace(pack, R.drawable.ic_action_previous, R.drawable.ng_animated_ic_action_previous_solar);
        replace(pack, R.drawable.filled_new_contact_24, R.drawable.ng_animated_filled_new_contact_24_solar);
        replace(pack, R.drawable.menu_unlock, R.drawable.ng_animated_menu_unlock_solar);
        replace(pack, R.drawable.ic_lock_white, R.drawable.ng_animated_ic_lock_white_solar);
        replace(pack, R.drawable.menu_night_mode_24, R.drawable.ng_animated_menu_night_mode_24_solar);
        replace(pack, R.drawable.menu_day_mode_24, R.drawable.ng_animated_menu_day_mode_24_solar);
        replace(pack, R.drawable.media_unmute, R.drawable.ng_animated_media_unmute_solar);
        replace(pack, R.drawable.media_mute, R.drawable.ng_animated_media_mute_solar);
        replace(pack, R.drawable.bot_webview, R.drawable.ng_animated_bot_webview_solar);
        replace(pack, R.drawable.msg_stories_saved, R.drawable.ng_animated_msg_stories_saved_solar);
        replace(pack, R.drawable.msg_stories_archive, R.drawable.ng_animated_msg_stories_archive_solar);
        replace(pack, R.drawable.outline_header_camera_plus_24, R.drawable.ng_animated_outline_header_camera_plus_24_solar);
        replace(pack, R.drawable.filled_add_photo, R.drawable.ng_animated_filled_add_photo_solar);
        replace(pack, R.drawable.msg_archive_hide, R.drawable.ng_animated_msg_archive_hide_solar);
        replace(pack, R.drawable.msg_archive_show, R.drawable.ng_animated_msg_archive_show_solar);
        replace(pack, R.drawable.msg_go_up, R.drawable.ng_animated_msg_go_up_solar);
        replace(pack, R.drawable.iv_align_horiz_middle, R.drawable.ng_animated_iv_align_horiz_middle_solar);
        replace(pack, R.drawable.iv_align_horiz_left, R.drawable.ng_animated_iv_align_horiz_left_solar);
        replace(pack, R.drawable.iv_align_horiz_right, R.drawable.ng_animated_iv_align_horiz_right_solar);
        replace(pack, R.drawable.menu_link_above, R.drawable.ng_animated_menu_link_above_solar);
        replace(pack, R.drawable.menu_link_below, R.drawable.ng_animated_menu_link_below_solar);
        replace(pack, R.drawable.msg_stories_caption, R.drawable.ng_animated_msg_stories_caption_solar);
        replace(pack, R.drawable.msg_spoiler, R.drawable.ng_animated_msg_spoiler_solar);
        replace(pack, R.drawable.msg_warning, R.drawable.ng_animated_msg_warning_solar);
        replace(pack, R.drawable.menu_link_revoke, R.drawable.ng_animated_menu_link_revoke_solar);
        replace(pack, R.drawable.msg_halfcheck, R.drawable.ng_animated_msg_halfcheck_solar);
        replace(pack, R.drawable.msg_mini_checks, R.drawable.ng_animated_msg_mini_checks_solar);
        replace(pack, R.drawable.media_repost, R.drawable.ng_animated_media_repost_solar);
        replace(pack, R.drawable.voice_minimize, R.drawable.ng_animated_voice_minimize_solar);
        replace(pack, R.drawable.voice_expand, R.drawable.ng_animated_voice_expand_solar);
        replace(pack, R.drawable.large_boosts, R.drawable.ng_animated_large_boosts_solar);
        replace(pack, R.drawable.msg_disable, R.drawable.ng_animated_msg_disable_solar);
        replace(pack, R.drawable.msg_handdown, R.drawable.ng_animated_msg_handdown_solar);
        replace(pack, R.drawable.ic_rating_star, R.drawable.ng_animated_ic_rating_star_solar);
        replace(pack, R.drawable.ic_rating_star_filled, R.drawable.ng_animated_ic_rating_star_filled_solar);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
