/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class DeviceFolderIconReplacements {
    private DeviceFolderIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        if (!(pack instanceof LiquidGlassFullReplace)) {
            return;
        }
        pack.replaces.put(R.drawable.files_folder, R.drawable.msg2_folder_liquid);
        pack.replaces.put(R.drawable.files_folder_solar, R.drawable.msg2_folder_liquid);
        pack.replaces.put(R.drawable.device_tablet_ios, R.drawable.ng_polish_device_tablet_ios_liquid);
        pack.replaces.put(R.drawable.device_tablet_android, R.drawable.ng_polish_device_tablet_android_liquid);
    }
}
