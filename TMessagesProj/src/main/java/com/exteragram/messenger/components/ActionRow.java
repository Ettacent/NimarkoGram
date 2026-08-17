package com.exteragram.messenger.components;

import android.content.Context;

import org.telegram.ui.ActionBar.Theme;

import java.util.List;

public class ActionRow extends app.nimarkogram.messenger.components.ActionRow {
    public ActionRow(Context context, Theme.ResourcesProvider resourcesProvider,
                     List<app.nimarkogram.messenger.components.ActionRow.ActionItem> items) {
        super(context, resourcesProvider, items);
    }
}
