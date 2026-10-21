/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class SolarMediaSemanticIconReplacements {
    private SolarMediaSemanticIconReplacements() { }

    static void apply(SolarIconReplace pack) {
        replace(pack, R.drawable.ic_playinline, R.drawable.ng_navigation_play_solar);
        replace(pack, R.drawable.ic_pauseinline, R.drawable.ng_navigation_pause_solar);
        replace(pack, R.drawable.ic_againinline, R.drawable.msg_reset_solar);
        replace(pack, R.drawable.msg_photo_switch2, R.drawable.camera_revert2_solar);
        replace(pack, R.drawable.msg_filled_lockedrecord, R.drawable.msg_secret_solar);
        replace(pack, R.drawable.msg_filled_unlockedrecord, R.drawable.ng_animated_menu_unlock_solar);
        replace(pack, R.drawable.filled_sound_on, R.drawable.ng_animated_media_mute_solar);
        replace(pack, R.drawable.msg_add, R.drawable.ng_navigation_add_solar);
    }

    private static void replace(SolarIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
