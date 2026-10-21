/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class ConsumerSemanticIconReplacements {
    private ConsumerSemanticIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        if (liquid) {
            replace(pack, R.drawable.outline_add_account, pack.wrap(R.drawable.filled_new_contact_24));
            replace(pack, R.drawable.outline_profile_call_24, pack.wrap(R.drawable.filled_profile_call_24));
            replace(pack, R.drawable.outline_profile_edit_24, pack.wrap(R.drawable.filled_profile_edit_24));
            replace(pack, R.drawable.outline_profile_member_24, pack.wrap(R.drawable.filled_profile_member_24));
            replace(pack, R.drawable.outline_profile_message_24, pack.wrap(R.drawable.filled_profile_message_24));
            replace(pack, R.drawable.outline_profile_mute_24, pack.wrap(R.drawable.filled_profile_mute_24));
            replace(pack, R.drawable.outline_profile_photo, pack.wrap(R.drawable.filled_profile_photo));
            replace(pack, R.drawable.outline_profile_stop_24, pack.wrap(R.drawable.filled_profile_stop_24));
            replace(pack, R.drawable.outline_profile_unmute_24, pack.wrap(R.drawable.filled_profile_unmute_24));
            replace(pack, R.drawable.outline_profile_video_24, pack.wrap(R.drawable.filled_profile_video_24));
            replace(pack, R.drawable.outline_header_camera_plus_24, pack.wrap(R.drawable.filled_profile_photo));
            replace(pack, R.drawable.outline_header_lock_24, pack.wrap(R.drawable.msg_secret));
            replace(pack, R.drawable.outline_attach_camera_24, pack.wrap(R.drawable.instant_camera_attach_solar));
            replace(pack, R.drawable.menu_call_create_2_24, pack.wrap(R.drawable.filled_profile_call_24));
            replace(pack, R.drawable.mini_more_dots, R.drawable.ng_slot_mini_more_dots_liquid);
            replace(pack, R.drawable.video_actions, R.drawable.ng_slot_video_actions_liquid);
            replace(pack, R.drawable.send_plane_24, R.drawable.ng_composer_send_plane_liquid);
            replace(pack, R.drawable.photos_arrow, R.drawable.ng_slot_photos_arrow_liquid);
            replace(pack, R.drawable.filled_reply_quote, R.drawable.ng_semantic_filled_reply_quote_liquid);
            replace(pack, R.drawable.filled_reply_settings, R.drawable.ng_semantic_filled_reply_settings_liquid);
            replace(pack, R.drawable.chats_unpin, R.drawable.ng_semantic_chats_unpin_liquid);
            replace(pack, R.drawable.msg_unpin, R.drawable.ng_semantic_msg_unpin_liquid);
            replace(pack, R.drawable.msg_addfolder, R.drawable.ng_semantic_msg_addfolder_liquid);
            replace(pack, R.drawable.msg_addfolder_solar, R.drawable.ng_semantic_msg_addfolder_liquid);
            replace(pack, R.drawable.msg_removefolder, R.drawable.ng_semantic_msg_removefolder_liquid);
            replace(pack, R.drawable.msg_removefolder_solar, R.drawable.ng_semantic_msg_removefolder_liquid);
            replace(pack, R.drawable.msg_spoiler, R.drawable.ng_semantic_msg_spoiler_liquid);
            replace(pack, R.drawable.msg_spoiler_off, R.drawable.ng_semantic_msg_spoiler_off_liquid);
            replace(pack, R.drawable.msg_chats_remove, R.drawable.ng_semantic_msg_chats_remove_liquid);
            replace(pack, R.drawable.filled_folder_new, R.drawable.ng_semantic_filled_folder_new_liquid);
            replace(pack, R.drawable.menu_sticker_add, R.drawable.ng_semantic_menu_sticker_add_liquid);
            replace(pack, R.drawable.msg2_archived_stickers, R.drawable.ng_semantic_msg2_archived_stickers_liquid);
        } else {
            replace(pack, R.drawable.msg_addfolder, R.drawable.ng_semantic_msg_addfolder_plumpy);
            replace(pack, R.drawable.msg_addfolder_solar, R.drawable.ng_semantic_msg_addfolder_plumpy);
            replace(pack, R.drawable.msg_removefolder, R.drawable.ng_semantic_msg_removefolder_plumpy);
            replace(pack, R.drawable.msg_removefolder_solar, R.drawable.ng_semantic_msg_removefolder_plumpy);
            replace(pack, R.drawable.menu_sticker_add, R.drawable.ng_semantic_menu_sticker_add_plumpy);
            replace(pack, R.drawable.msg_gif_add, R.drawable.ng_semantic_msg_gif_add_plumpy);
            replace(pack, R.drawable.msg_premium_lock2, R.drawable.ng_semantic_msg_premium_lock2_plumpy);
        }
    }

    private static void add(BaseIconReplace pack, int source, int target) {
        if (pack.replaces.putIfAbsent(source, target) == null) {
            pack.noTint.remove(source);
        }
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
