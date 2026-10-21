/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class CompactTransferIconReplacements {
    private CompactTransferIconReplacements() { }

    static void apply(BaseIconReplace pack) {
        if (pack instanceof SolarIconReplace) {
            pack.replaces.put(R.drawable.msg_mini_download, R.drawable.ng_compact_transfer_download_solar);
            pack.replaces.put(R.drawable.msg_mini_upload, R.drawable.ng_compact_transfer_upload_solar);
        } else if (pack instanceof LiquidGlassFullReplace) {
            pack.replaces.put(R.drawable.msg_mini_download, R.drawable.ng_compact_transfer_download_liquid);
            pack.replaces.put(R.drawable.msg_mini_upload, R.drawable.ng_compact_transfer_upload_liquid);
        } else if (pack instanceof PlumpyFullReplace) {
            pack.replaces.put(R.drawable.msg_mini_download, R.drawable.ng_compact_transfer_download_plumpy);
            pack.replaces.put(R.drawable.msg_mini_upload, R.drawable.ng_compact_transfer_upload_plumpy);
        } else {
            return;
        }
        pack.noTint.remove(R.drawable.msg_mini_download);
        pack.noTint.remove(R.drawable.msg_mini_upload);
    }
}
