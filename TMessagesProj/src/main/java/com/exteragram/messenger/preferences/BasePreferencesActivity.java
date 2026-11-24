/* Modifications Copyright (C) 2026 Ettacent */

package com.exteragram.messenger.preferences;

import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.ui.Components.UItem;
public abstract class BasePreferencesActivity
        extends app.nimarkogram.messenger.preferences.BasePreferencesActivity {
    protected void showListDialog(UItem item, CharSequence[] choices, String title, int selected,
                                  PopupUtils.OnItemClickListener listener) {
        super.showListDialog(item, choices, title, selected, listener == null ? null : listener::onClick);
}
}
