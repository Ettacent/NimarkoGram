/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class FolderSemanticIconReplacements {
    private FolderSemanticIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        replace(pack, R.drawable.filter_cat_solar, R.drawable.msg_emoji_cat_liquid);
        replace(pack, R.drawable.filter_book_solar, R.drawable.ng_folder_semantic_book_liquid);
        replace(pack, R.drawable.filter_game_solar, R.drawable.ng_folder_semantic_game_liquid);
        replace(pack, R.drawable.filter_travel_solar, R.drawable.ng_folder_semantic_travel_liquid);
        replace(pack, R.drawable.filter_sport_solar, R.drawable.ng_folder_semantic_sport_liquid);
        replace(pack, R.drawable.filter_study_solar, R.drawable.ng_folder_semantic_study_liquid);
        replace(pack, R.drawable.filter_groups, R.drawable.ng_folder_semantic_groups_liquid);
        replace(pack, R.drawable.filter_unread, R.drawable.ng_folder_semantic_unread_liquid);
        replace(pack, R.drawable.filter_flower_solar, R.drawable.ng_folder_semantic_flower_liquid);
        replace(pack, R.drawable.filter_home_solar, R.drawable.msg_home_liquid);
        replace(pack, R.drawable.filter_mask_solar, R.drawable.ng_folder_semantic_mask_liquid);
        replace(pack, R.drawable.filter_party_solar, R.drawable.ng_folder_semantic_party_liquid);
        replace(pack, R.drawable.filter_setup_solar, R.drawable.ng_folder_semantic_setup_liquid);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
