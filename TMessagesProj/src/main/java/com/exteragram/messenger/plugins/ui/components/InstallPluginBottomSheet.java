package com.exteragram.messenger.plugins.ui.components;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;

import com.exteragram.messenger.plugins.PluginsController;

public final class InstallPluginBottomSheet
        extends app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet {

    public InstallPluginBottomSheet(BaseFragment fragment,
                                    PluginsController.PluginValidationResult validationResult,
                                    PluginInstallParams params) {
        super(fragment, adaptResult(validationResult), adaptParams(params));
    }

    private static app.nimarkogram.messenger.plugins.PluginsController.PluginValidationResult
            adaptResult(PluginsController.PluginValidationResult r) {
        if (r == null) {
            return new app.nimarkogram.messenger.plugins.PluginsController.PluginValidationResult(null, null);
        }
        return new app.nimarkogram.messenger.plugins.PluginsController.PluginValidationResult(
                r.getPlugin(), r.getError());
    }

    private static app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams
            adaptParams(PluginInstallParams p) {
        
        String path = p != null ? p.getFilePath() : null;
        return new app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams(
                path, false);
    }

    public static final class PluginInstallParams {
        public String filePath;
        public boolean trusted;

        public PluginInstallParams(String filePath, boolean trusted) {
            this.filePath = filePath;
            this.trusted = trusted;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public boolean getTrusted() {
            return trusted;
        }

        public void setTrusted(boolean trusted) {
            this.trusted = trusted;
        }

        public static PluginInstallParams of(MessageObject message) {
            app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams real =
                    app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams.of(message);
            
            return new PluginInstallParams(real != null ? real.filePath : null,
                    real == null || !real.incompatible);
        }

        public static final Companion Companion = new Companion();
        public static final Companion INSTANCE = Companion;

        public static final class Companion {
            Companion() {}

            public PluginInstallParams of(MessageObject message) {
                return PluginInstallParams.of(message);
            }
        }
    }
}
