/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui;

import android.content.Context;
import android.content.res.Resources;

import org.telegram.messenger.web.R;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.icons.NimarkoIconResources;

final class SmsIconPackResources {
    private SmsIconPackResources() {}

    static int resolve(Context context, int source) {
        if (context == null) {
            return source;
        }
        Resources resources = context.getResources();
        if (!(resources instanceof NimarkoIconResources)) {
            return source;
        }
        int selection = ((NimarkoIconResources) resources).getAppliedSelection();
        boolean liquid = selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS;
        if (!liquid && selection != NimarkoConfig.ICON_REPLACE_PLUMPY) {
            return source;
        }
        if (source == R.drawable.large_sms_code) {
            return liquid ? R.drawable.ng_followup_large_sms_code_liquid : R.drawable.ng_followup_large_sms_code_plumpy;
        }
        if (source == R.drawable.menu_feature_gift) {
            return liquid ? org.telegram.messenger.R.drawable.ng_raster_menu_feature_gift_liquid : org.telegram.messenger.R.drawable.ng_raster_menu_feature_gift_plumpy;
        }
        if (source == R.drawable.menu_feature_sms) {
            return liquid ? org.telegram.messenger.R.drawable.ng_raster_menu_feature_sms_liquid : org.telegram.messenger.R.drawable.ng_raster_menu_feature_sms_plumpy;
        }
        if (source == R.drawable.menu_sms_history) {
            return liquid ? org.telegram.messenger.R.drawable.ng_raster_menu_sms_history_liquid : org.telegram.messenger.R.drawable.ng_raster_menu_sms_history_plumpy;
        }
        return source;
    }
}
