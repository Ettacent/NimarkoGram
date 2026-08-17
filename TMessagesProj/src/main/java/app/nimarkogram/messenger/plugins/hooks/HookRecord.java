package app.nimarkogram.messenger.plugins.hooks;

import app.nimarkogram.messenger.plugins.PluginsController;

public interface HookRecord {
    void cleanup();

    boolean matches(Object obj);

    default PluginsController.PluginRuntimeToken getRuntimeToken() {
        return null;
    }
}
