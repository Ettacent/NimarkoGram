/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class RasterNativeIconReplacements {
    private RasterNativeIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        if (liquid) {
            add(pack, R.drawable.filled_chatlink_large, R.drawable.ng_raster_filled_chatlink_large_liquid);
            add(pack, R.drawable.filled_forward_settings, R.drawable.ng_raster_filled_forward_settings_liquid);
            add(pack, R.drawable.filled_open_message, R.drawable.ng_raster_filled_open_message_liquid);
            add(pack, R.drawable.filled_premium_away, R.drawable.ng_raster_filled_premium_away_liquid);
            add(pack, R.drawable.filled_premium_chatlink, R.drawable.ng_raster_filled_premium_chatlink_liquid);
            add(pack, R.drawable.filled_premium_status2, R.drawable.ng_raster_filled_premium_status2_liquid);
            add(pack, R.drawable.input_video_story_remove, R.drawable.ng_raster_input_video_story_remove_liquid);
            add(pack, R.drawable.large_log_actions, R.drawable.ng_raster_large_log_actions_liquid);
            add(pack, R.drawable.large_monetize, R.drawable.ng_raster_large_monetize_liquid);
            add(pack, R.drawable.media_button_erase, R.drawable.ng_raster_media_button_erase_liquid);
            add(pack, R.drawable.media_dual_camera2, R.drawable.ng_raster_media_dual_camera2_liquid);
            add(pack, R.drawable.menu_feature_cover, R.drawable.ng_raster_menu_feature_cover_liquid);
            add(pack, R.drawable.menu_feature_custombg, R.drawable.ng_raster_menu_feature_custombg_liquid);
            add(pack, R.drawable.menu_feature_intro, R.drawable.ng_raster_menu_feature_intro_liquid);
            add(pack, R.drawable.menu_feature_noads, R.drawable.ng_raster_menu_feature_noads_liquid);
            add(pack, R.drawable.menu_feature_status, R.drawable.ng_raster_menu_feature_status_liquid);
            add(pack, R.drawable.menu_feature_stories, R.drawable.ng_raster_menu_feature_stories_liquid);
            add(pack, R.drawable.menu_forward_stamp, R.drawable.ng_raster_menu_forward_stamp_liquid);
            add(pack, R.drawable.menu_premium_away, R.drawable.ng_raster_menu_premium_away_liquid);
            add(pack, R.drawable.menu_unsave_story, R.drawable.ng_raster_menu_unsave_story_liquid);
            add(pack, R.drawable.mini_giveaway, R.drawable.ng_raster_mini_giveaway_liquid);
            add(pack, R.drawable.msg_groups_create, R.drawable.ng_raster_msg_groups_create_liquid);
            add(pack, R.drawable.msg_handdown, R.drawable.ng_raster_msg_handdown_liquid);
            add(pack, R.drawable.msg_mini_checks, R.drawable.ng_raster_msg_mini_checks_liquid);
            add(pack, R.drawable.msg_premium_ads, R.drawable.ng_raster_msg_premium_ads_liquid);
            add(pack, R.drawable.msg_premium_avatar, R.drawable.ng_raster_msg_premium_avatar_liquid);
            add(pack, R.drawable.msg_premium_emoji, R.drawable.ng_raster_msg_premium_emoji_liquid);
            add(pack, R.drawable.msg_premium_icons, R.drawable.ng_raster_msg_premium_icons_liquid);
            add(pack, R.drawable.msg_premium_lock2, R.drawable.ng_raster_msg_premium_lock2_liquid);
            add(pack, R.drawable.msg_seen, R.drawable.ng_raster_msg_seen_liquid);
            add(pack, R.drawable.msg_stories_order, R.drawable.ng_raster_msg_stories_order_liquid);
            add(pack, R.drawable.msg_stories_save, R.drawable.ng_raster_msg_stories_save_liquid);
            add(pack, R.drawable.premium_status, R.drawable.ng_raster_premium_status_liquid);
        } else {
            add(pack, R.drawable.filled_chatlink_large, R.drawable.ng_raster_filled_chatlink_large_plumpy);
            add(pack, R.drawable.filled_forward_settings, R.drawable.ng_raster_filled_forward_settings_plumpy);
            add(pack, R.drawable.large_log_actions, R.drawable.ng_raster_large_log_actions_plumpy);
            add(pack, R.drawable.large_monetize, R.drawable.ng_raster_large_monetize_plumpy);
            add(pack, R.drawable.player_new_repeat_reverse, R.drawable.ng_raster_player_new_repeat_reverse_plumpy);
            add(pack, R.drawable.player_new_repeat_shuffle, R.drawable.ng_raster_player_new_repeat_shuffle_plumpy);
        }
    }

    private static void add(BaseIconReplace pack, int source, int target) {
        if (pack.replaces.putIfAbsent(source, target) == null) {
            pack.noTint.remove(source);
        }
    }
}
