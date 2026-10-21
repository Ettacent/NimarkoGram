/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class CompactSecurityIconReplacements {
    private CompactSecurityIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        if (pack instanceof SolarIconReplace) {
            return;
        } else if (pack instanceof LiquidGlassFullReplace) {
            replace(pack, R.drawable.msg_mini_lock2, R.drawable.ng_compact_security_lock2_liquid);
            replace(pack, R.drawable.msg_mini_lock3, R.drawable.ng_compact_security_lock3_liquid);
            replace(pack, R.drawable.msg_mini_premiumlock, R.drawable.ng_compact_security_premiumlock_liquid);
            replace(pack, R.drawable.msg_mini_lockedemoji, R.drawable.ng_compact_security_lockedemoji_liquid);
            replace(pack, R.drawable.msg_mini_qr, R.drawable.ng_compact_security_qr_liquid);
        } else if (pack instanceof PlumpyFullReplace) {
            replace(pack, R.drawable.msg_mini_lock2, R.drawable.ng_compact_security_lock2_plumpy);
            replace(pack, R.drawable.msg_mini_lock3, R.drawable.ng_compact_security_lock3_plumpy);
            replace(pack, R.drawable.msg_mini_premiumlock, R.drawable.ng_compact_security_premiumlock_plumpy);
            replace(pack, R.drawable.msg_mini_lockedemoji, R.drawable.ng_compact_security_lockedemoji_plumpy);
            replace(pack, R.drawable.msg_mini_qr, R.drawable.ng_compact_security_qr_plumpy);
        } else {
            return;
        }
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
