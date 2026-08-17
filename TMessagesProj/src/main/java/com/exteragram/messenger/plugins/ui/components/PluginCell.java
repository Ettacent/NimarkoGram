package com.exteragram.messenger.plugins.ui.components;

import android.content.Context;

import org.telegram.ui.ActionBar.Theme;

public class PluginCell
        extends app.nimarkogram.messenger.plugins.ui.components.PluginCell {

    public PluginCell(Context context) {
        super(context);
    }

    public PluginCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
    }

    public void set(com.exteragram.messenger.plugins.Plugin plugin,
                    PluginCellDelegate delegate) {
        super.set(plugin, delegate);
    }

    public void set(com.exteragram.messenger.plugins.Plugin plugin,
                    PluginCellDelegate delegate, long operationEpoch) {
        super.set(plugin, delegate, operationEpoch);
    }
}
