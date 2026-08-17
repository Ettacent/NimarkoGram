package com.exteragram.messenger.preferences.utils;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;

import java.util.Map;

public class SettingsRegistry {

    public static class Entry extends app.nimarkogram.messenger.preferences.utils.SettingsRegistry.Entry {}

    public static final Map<Class<?>, Boolean> ayuCategories =
            app.nimarkogram.messenger.preferences.utils.SettingsRegistry.ayuCategories;

    private SettingsRegistry() {}

    public static app.nimarkogram.messenger.preferences.utils.SettingsRegistry getInstance() {
        return app.nimarkogram.messenger.preferences.utils.SettingsRegistry.getInstance();
    }

    public static boolean isValidForLinkAliases(UItem item) {
        return app.nimarkogram.messenger.preferences.utils.SettingsRegistry.isValidForLinkAliases(item);
    }

    public static boolean isValidForSearch(UItem item) {
        return app.nimarkogram.messenger.preferences.utils.SettingsRegistry.isValidForSearch(item);
    }
}
