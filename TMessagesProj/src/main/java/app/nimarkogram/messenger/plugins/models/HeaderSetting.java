package app.nimarkogram.messenger.plugins.models;

import app.nimarkogram.messenger.plugins.PluginsConstants;

public class HeaderSetting extends SettingItem {
    public String text;

    public HeaderSetting(String str) {
        super(PluginsConstants.Settings.TYPE_HEADER);
        this.text = str;
    }
}