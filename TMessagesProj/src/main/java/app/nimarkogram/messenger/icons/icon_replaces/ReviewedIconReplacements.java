/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class ReviewedIconReplacements {
    private ReviewedIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        pack.addAlias(R.drawable.ic_ab_close, R.drawable.msg_close);
        pack.addAlias(R.drawable.tabs_link_24, R.drawable.msg_link);
        pack.addAlias(R.drawable.mini_inline_copy_16, R.drawable.msg_copy);
        pack.addAlias(R.drawable.outline_poll_attach_24, R.drawable.input_attach);
        if (liquid) {
            pack.addAlias(R.drawable.filled_premium_bots, R.drawable.msg_bot);
            pack.addAlias(R.drawable.filled_premium_business, R.drawable.menu_shop);
            pack.addAlias(R.drawable.filled_premium_hours, R.drawable.menu_premium_clock);
            pack.addAlias(R.drawable.filled_premium_intro, R.drawable.msg_info);
            replace(pack, R.drawable.ic_ab_new, R.drawable.ng_slot_ic_ab_new_liquid);
            pack.addAlias(R.drawable.list_warning_sign, R.drawable.warning_sign);
            pack.addAlias(R.drawable.media_flip, R.drawable.msg_photo_flip);
            pack.addAlias(R.drawable.menu_feature_wallpaper, R.drawable.msg_background);
            pack.addAlias(R.drawable.mini_reply_user, R.drawable.msg_openprofile);
            pack.addAlias(R.drawable.msg_media_gallery, R.drawable.msg_photos);
            pack.addAlias(R.drawable.msg_premium_reactions, R.drawable.msg_reactions_filled);
            pack.addAlias(R.drawable.msg_premium_speed, R.drawable.msg_speed);
            pack.addAlias(R.drawable.msg_premium_translate, R.drawable.msg_translate);
            pack.addAlias(R.drawable.msg_premium_uploads, R.drawable.msg_filled_data_files);
            pack.addAlias(R.drawable.msg_premium_voice, R.drawable.input_mic);
            replace(pack, R.drawable.msg_warning, R.drawable.ng_slot_msg_warning_liquid);
            pack.addAlias(R.drawable.play_mini_video, R.drawable.ic_action_play);
            pack.addAlias(R.drawable.premium_wallpaper, R.drawable.msg_background);
            pack.addAlias(R.drawable.profile_newmsg, R.drawable.msg_discussion);
            replace(pack, R.drawable.pagedown, R.drawable.ng_slot_pagedown_liquid);
            replace(pack, R.drawable.ic_video, R.drawable.ng_slot_ic_video_liquid);
            replace(pack, R.drawable.preview_arrow, R.drawable.ng_slot_preview_arrow_liquid);
            replace(pack, R.drawable.photo_star, R.drawable.ng_slot_photo_star_liquid);
            replace(pack, R.drawable.story_camera, R.drawable.ng_slot_story_camera_liquid);
            replace(pack, R.drawable.settings_arrow, R.drawable.ng_slot_settings_arrow_liquid);
        } else {
            replace(pack, R.drawable.ic_ab_new, R.drawable.ng_slot_ic_ab_new_plumpy);
            pack.addAlias(R.drawable.ic_chevron_right_black_18dp, R.drawable.msg_arrowright);
            pack.addAlias(R.drawable.mentionbutton, R.drawable.filled_username);
            pack.addAlias(R.drawable.mini_like_filled, R.drawable.msg_reactions_filled);
            pack.addAlias(R.drawable.msg_mention, R.drawable.filled_username);
            pack.addAlias(R.drawable.reactionbutton, R.drawable.msg_reactions);
            replace(pack, R.drawable.story_camera, R.drawable.ng_slot_story_camera_plumpy);
        }
        pack.replaces.remove(R.drawable.tooltip_arrow);
        pack.replaces.remove(R.drawable.tooltip_arrow_up);
        pack.noTint.remove(R.drawable.tooltip_arrow);
        pack.noTint.remove(R.drawable.tooltip_arrow_up);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
