/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class RecordDeleteIconReplacements {
    private RecordDeleteIconReplacements() { }

    static void apply(BaseIconReplace pack, int body, int lid) {
        pack.replaces.put(R.drawable.ng_record_delete_body, body);
        pack.replaces.put(R.drawable.ng_record_delete_lid, lid);
        pack.noTint.remove(R.drawable.ng_record_delete_body);
        pack.noTint.remove(R.drawable.ng_record_delete_lid);
    }
}
