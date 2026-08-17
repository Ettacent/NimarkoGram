package com.exteragram.messenger.plugins;

public class PythonPluginsEngine extends app.nimarkogram.messenger.plugins.PythonPluginsEngine {
    public PythonPluginsEngine() {
        super();
    }

    @Override
    public boolean isPlugin(java.io.File file) {
        return isPlugin(file, null);
    }

    public boolean isPlugin(java.io.File file, org.telegram.messenger.MessageObject messageObject) {
        return super.isPlugin(file);
    }

    @Override
    public void sharePlugin(String id) {
        super.sharePlugin(id);
    }

    @Override
    public void openPluginSettings(
            app.nimarkogram.messenger.plugins.Plugin plugin,
            org.telegram.ui.ActionBar.BaseFragment fragment) {
        openPluginSettings(
                com.exteragram.messenger.plugins.Plugin.fromReal(plugin),
                fragment);
    }

    public void openPluginSettings(
            com.exteragram.messenger.plugins.Plugin plugin,
            org.telegram.ui.ActionBar.BaseFragment fragment) {
        app.nimarkogram.messenger.plugins.Plugin live = null;
        if (plugin != null) {
            live = app.nimarkogram.messenger.plugins.PluginsController
                    .getInstance().plugins.get(plugin.getId());
        }
        super.openPluginSettings(live != null ? live : plugin, fragment);
    }

    @Override
    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment,
                                  app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams params) {
        showInstallDialog(fragment,
                new com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams(
                        params != null ? params.filePath : null,
                        params == null || !params.incompatible));
    }

    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment,
                                  com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams params) {
        super.showInstallDialog(fragment,
                new app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams(
                        params != null ? params.getFilePath() : null,
                        params == null || !params.getTrusted()));
    }
}
