/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class PermissionSemanticIconReplacements {
    private PermissionSemanticIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        int camera = liquid ? R.drawable.ng_permission_camera_liquid : R.drawable.ng_permission_camera_plumpy;
        int gallery = liquid ? R.drawable.ng_permission_gallery_liquid : R.drawable.ng_permission_gallery_plumpy;
        replace(pack, R.drawable.permissions_camera1, camera);
        replace(pack, R.drawable.permissions_camera1_solar, camera);
        replace(pack, R.drawable.permissions_gallery1, gallery);
        replace(pack, R.drawable.permissions_gallery1_solar, gallery);
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
