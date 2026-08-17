package app.nimarkogram.messenger.plugins.models;

import app.nimarkogram.messenger.plugins.PluginsConstants;

public class DividerSetting extends SettingItem {
    public String text;

    public DividerSetting(String str) {
        super(PluginsConstants.Settings.TYPE_DIVIDER);
        this.text = str;
    }
}