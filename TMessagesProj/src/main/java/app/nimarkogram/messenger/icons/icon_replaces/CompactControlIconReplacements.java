/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class CompactControlIconReplacements {
    private CompactControlIconReplacements() { }

    static void apply(BaseIconReplace pack) {
        if (pack instanceof SolarIconReplace) {
            replace(pack, R.drawable.msg_mini_customize, R.drawable.ng_compact_control_customize_solar);
        } else if (pack instanceof LiquidGlassFullReplace) {
            replace(pack, R.drawable.msg_mini_customize, R.drawable.ng_compact_control_customize_liquid);
            replace(pack, R.drawable.msg_mini_addstory, R.drawable.ng_compact_control_addstory_liquid);
        } else if (pack instanceof PlumpyFullReplace) {
            replace(pack, R.drawable.msg_mini_addstory, R.drawable.ng_compact_control_addstory_plumpy);
        } else {
            return;
        }
    }

    private static void replace(BaseIconReplace pack, int source, int target) {
        pack.replaces.put(source, target);
        pack.noTint.remove(source);
    }
}
