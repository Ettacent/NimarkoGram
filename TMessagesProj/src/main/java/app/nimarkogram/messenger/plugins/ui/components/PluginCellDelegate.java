package app.nimarkogram.messenger.plugins.ui.components;

import android.view.View;

import app.nimarkogram.messenger.plugins.Plugin;

public interface PluginCellDelegate {
    boolean canOpenInExternalApp();

    void deletePlugin();

    void openInExternalApp();

    void openPluginSettings();

    default void onSettingsClicked(Plugin plugin) {
        openPluginSettings();
    }

    void pinPlugin(View view);

    void sharePlugin();

    void togglePlugin(View view);

    default void showKebabMenu(View anchor) {
        
    }
}
