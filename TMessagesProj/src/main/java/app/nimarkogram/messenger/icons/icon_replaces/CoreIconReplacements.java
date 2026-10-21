/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class CoreIconReplacements {
    private CoreIconReplacements() {}

    static void apply(BaseIconReplace pack) {
        if (pack instanceof SolarIconReplace) {
            pack.replaces.put(R.drawable.iv_math, R.drawable.ng_editor_math_solar);
            pack.replaces.put(R.drawable.iv_super, R.drawable.ng_editor_super_solar);
            pack.replaces.put(R.drawable.iv_sub, R.drawable.ng_editor_sub_solar);
        } else if (pack instanceof LiquidGlassFullReplace) {
            pack.replaces.put(R.drawable.iv_math, R.drawable.ng_editor_math_liquid);
            pack.replaces.put(R.drawable.iv_super, R.drawable.ng_editor_super_liquid);
            pack.replaces.put(R.drawable.iv_sub, R.drawable.ng_editor_sub_liquid);
        } else if (pack instanceof PlumpyFullReplace) {
            pack.replaces.put(R.drawable.iv_math, R.drawable.ng_editor_math_plumpy);
            pack.replaces.put(R.drawable.iv_super, R.drawable.ng_editor_super_plumpy);
            pack.replaces.put(R.drawable.iv_sub, R.drawable.ng_editor_sub_plumpy);
        }
        pack.noTint.remove(R.drawable.iv_math);
        pack.noTint.remove(R.drawable.iv_super);
        pack.noTint.remove(R.drawable.iv_sub);
        pack.addAlias(R.drawable.settings_account, R.drawable.msg_contacts);
        pack.addAlias(R.drawable.settings_chat, R.drawable.msg_discussion);
        pack.addAlias(R.drawable.settings_privacy, R.drawable.msg_secret);
        pack.addAlias(R.drawable.settings_sounds, R.drawable.msg_notifications);
        pack.addAlias(R.drawable.settings_data, R.drawable.msg_filled_datausage);
        pack.addAlias(R.drawable.settings_folders, R.drawable.msg_folders);
        pack.addAlias(R.drawable.settings_devices, R.drawable.msg2_devices);
        pack.addAlias(R.drawable.settings_power, R.drawable.msg2_battery);
        pack.addAlias(R.drawable.settings_language, R.drawable.msg_language);
        pack.addAlias(R.drawable.settings_business, R.drawable.msg_work);
        pack.addAlias(R.drawable.settings_gift, R.drawable.msg_gift_premium);
        pack.addAlias(R.drawable.settings_ask, R.drawable.msg2_ask_question);
        pack.addAlias(R.drawable.settings_faq, R.drawable.msg_help);
        pack.addAlias(R.drawable.settings_features, R.drawable.filter_light_solar);
        pack.addAlias(R.drawable.settings_policy, R.drawable.msg2_policy);
        pack.addAlias(R.drawable.settings_premium, R.drawable.emoji_tabs_faves);
        pack.addAlias(R.drawable.settings_stars, R.drawable.emoji_tabs_faves);
        pack.addAlias(R.drawable.settings_calls, R.drawable.msg_calls);
        pack.addAlias(R.drawable.settings_channel, R.drawable.msg_channel);
        pack.addAlias(R.drawable.settings_group, R.drawable.msg_groups);
        pack.addAlias(R.drawable.settings_invite, R.drawable.msg_addcontact);
        pack.addAlias(R.drawable.filled_premium_camera, R.drawable.camera);
        pack.addAlias(R.drawable.outline_profile_settings, R.drawable.msg_settings);
        pack.addAlias(R.drawable.filled_profile_settings, R.drawable.msg_settings);
    }
}
