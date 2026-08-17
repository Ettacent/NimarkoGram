package com.exteragram.messenger.plugins;

public class PluginsController {

    public final java.util.LinkedHashMap<String, app.nimarkogram.messenger.plugins.Plugin> plugins;
     
    public final android.content.SharedPreferences preferences;
     
    public final java.io.File pluginsDir;
     
    public final java.util.LinkedHashMap<String, java.util.List<app.nimarkogram.messenger.plugins.models.SettingItem>> settings;
     
    public static final java.util.concurrent.ConcurrentHashMap<String, app.nimarkogram.messenger.plugins.PluginsController.PluginsEngine> engines =
            app.nimarkogram.messenger.plugins.PluginsController.engines;

    private final app.nimarkogram.messenger.plugins.PluginsController real;

    private PluginsController() {
        this.real = app.nimarkogram.messenger.plugins.PluginsController.getInstance();
        
        this.plugins = new java.util.LinkedHashMap<>(this.real.plugins);
        this.preferences = this.real.preferences;
        this.pluginsDir = this.real.pluginsDir;
        this.settings = new java.util.LinkedHashMap<>(this.real.settings);
    }

    public static PluginsController getInstance() {
        return new PluginsController();
    }

    public java.util.HashMap<String, app.nimarkogram.messenger.plugins.Plugin> getPluginsSnapshot() {
        return new java.util.HashMap<>(this.real.plugins);
    }

    public String getPluginPath(String pluginId) {
        String result = real.getPluginPath(pluginId);
        boolean exists;
        try { exists = result != null && new java.io.File(result).exists(); }
        catch (Throwable t) { exists = false; }
        org.telegram.messenger.FileLog.d("nimarko: getPluginPath(" + pluginId + ") -> " + result + " exists=" + exists);
        return result;
    }

    public String exportPluginsToZip(boolean includeSettings, boolean includeDisabled, String excludeId) {
        try {
            java.io.File cacheDir = new java.io.File(
                    org.telegram.messenger.ApplicationLoader.getFilesDirFixed(), "plugin_exports");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            java.io.File outFile = new java.io.File(cacheDir,
                    "plugins_export_" + (System.currentTimeMillis() / 1000L) + ".zip");

            org.json.JSONObject manifest = new org.json.JSONObject();
            manifest.put("format_version", 1);
            manifest.put("exported_at",
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date()));
            org.json.JSONObject pluginsJson = new org.json.JSONObject();

            int exported = 0;
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                    new java.io.FileOutputStream(outFile))) {
                for (java.util.Map.Entry<String, app.nimarkogram.messenger.plugins.Plugin> e
                        : real.plugins.entrySet()) {
                    String pid = e.getKey();
                    if (pid == null) continue;
                    if (excludeId != null && excludeId.equals(pid)) continue;
                    app.nimarkogram.messenger.plugins.Plugin plugin = e.getValue();
                    if (plugin == null) continue;
                    if (!includeDisabled && !plugin.isEnabled()) continue;

                    String path = real.getPluginPath(pid);
                    if (path == null) continue;
                    java.io.File pluginFile = new java.io.File(path);
                    if (!pluginFile.exists()) continue;

                    zos.putNextEntry(new java.util.zip.ZipEntry("plugins/" + pid + ".py"));
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(pluginFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();

                    org.json.JSONObject info = new org.json.JSONObject();
                    info.put("enabled", plugin.isEnabled());
                    try { info.put("version", String.valueOf(plugin.getVersion())); } catch (Throwable ignored) {}
                    try { info.put("name", String.valueOf(plugin.getName())); } catch (Throwable ignored) {}
                    try { info.put("author", String.valueOf(plugin.getAuthor())); } catch (Throwable ignored) {}

                    if (includeSettings) {
                        try {
                            java.util.Map<String, ?> prefs = real.getPluginSettingsPreferences(pid);
                            if (prefs != null && !prefs.isEmpty()) {
                                org.json.JSONObject settingsJson = new org.json.JSONObject(prefs);
                                info.put("settings", settingsJson);
                            }
                        } catch (Throwable ignored) {}
                    }

                    pluginsJson.put(pid, info);
                    exported++;
                }
                manifest.put("plugins", pluginsJson);
                zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
                zos.write(manifest.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            org.telegram.messenger.FileLog.d(
                    "nimarko: exportPluginsToZip wrote " + exported + " plugins to " + outFile);
            if (exported == 0) {
                outFile.delete();
                return null;
            }
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e("nimarko: exportPluginsToZip failed", t);
            return null;
        }
    }

    public java.util.Map<String, ?> getPluginSettingsPreferences(String pluginId) {
        return real.getPluginSettingsPreferences(pluginId);
    }

    public boolean getPluginSettingBoolean(String pluginId, String key, boolean defaultValue) {
        return real.getPluginSettingBoolean(pluginId, key, defaultValue);
    }

    public String getPluginSettingString(String pluginId, String key, String defaultValue) {
        return real.getPluginSettingString(pluginId, key, defaultValue);
    }

    public int getPluginSettingInt(String pluginId, String key, int defaultValue) {
        return real.getPluginSettingInt(pluginId, key, defaultValue);
    }

    public void setPluginSetting(String pluginId, String key, Object value) {
        real.setPluginSetting(pluginId, key, value);
    }

    public void loadPluginSettings(String pluginId) {
        real.loadPluginSettings(pluginId);
    }

    public app.nimarkogram.messenger.plugins.PluginsController.PluginsEngine getPluginEngine(String pluginId) {
        return real.getPluginEngine(pluginId);
    }

    public void setPluginEnabled(String pluginId, boolean enabled,
                                 org.telegram.messenger.Utilities.Callback<String> callback) {
        real.setPluginEnabled(pluginId, enabled, callback);
    }

    public void deletePlugin(String pluginId, org.telegram.messenger.Utilities.Callback<String> callback) {
        real.deletePlugin(pluginId, callback);
    }

    public boolean hasPluginSettings(String pluginId) {
        return real.hasPluginSettings(pluginId);
    }

    public boolean isPluginEnabled(String id) {
        if (id == null) return false;
        app.nimarkogram.messenger.plugins.Plugin p = plugins.get(id);
        return p != null && p.isEnabled();
    }

    public java.util.List<app.nimarkogram.messenger.plugins.models.SettingItem> getPluginSettingsList(String pluginId) {
        return real.getPluginSettingsList(pluginId);
    }

    public static final class PluginValidationResult {
        private app.nimarkogram.messenger.plugins.Plugin plugin;
        private String error;

        public PluginValidationResult(app.nimarkogram.messenger.plugins.Plugin plugin, String error) {
            this.plugin = plugin;
            this.error = error;
        }

        public app.nimarkogram.messenger.plugins.Plugin getPlugin() { return plugin; }
        public String getError() { return error; }
        public void setPlugin(app.nimarkogram.messenger.plugins.Plugin plugin) { this.plugin = plugin; }
        public void setError(String error) { this.error = error; }
    }

    public app.nimarkogram.messenger.plugins.PluginsController getReal() {
        return real;
    }

    public static java.util.concurrent.ConcurrentHashMap<String, app.nimarkogram.messenger.plugins.PluginsController.PluginsEngine> getEngines() {
        return engines;
    }

    public static boolean isPluginEngineSupported() {
        try { return app.nimarkogram.messenger.plugins.PluginsController.isPluginEngineSupported(); }
        catch (Throwable t) { return false; }
    }

    public static boolean isPluginEngineAvailable() {
        try { return app.nimarkogram.messenger.plugins.PluginsController.isPluginEngineAvailable(); }
        catch (Throwable t) { return false; }
    }

    public static boolean isPlugin(org.telegram.messenger.MessageObject messageObject) {
        try { return app.nimarkogram.messenger.plugins.PluginsController.isPlugin(messageObject); }
        catch (Throwable t) { return false; }
    }

    public static boolean isPlugin(java.io.File file, org.telegram.messenger.MessageObject messageObject) {
        try { return app.nimarkogram.messenger.plugins.PluginsController.isPlugin(file); }
        catch (Throwable t) { return false; }
    }

    public static boolean isPluginPinned(String pluginId) {
        try { return app.nimarkogram.messenger.plugins.PluginsController.isPluginPinned(pluginId); }
        catch (Throwable t) { return false; }
    }

    public static void setPluginPinned(String pluginId, boolean isPinned) {
        try { app.nimarkogram.messenger.plugins.PluginsController.setPluginPinned(pluginId, isPinned); }
        catch (Throwable ignored) {}
    }

    public static app.nimarkogram.messenger.plugins.PluginsController.PluginsEngine getPluginEngine(java.io.File file) {
        try { return app.nimarkogram.messenger.plugins.PluginsController.getPluginEngine(file); }
        catch (Throwable t) { return null; }
    }

    public static void openPluginSettings(String pluginId) {
        try { app.nimarkogram.messenger.plugins.PluginsController.openPluginSetting(pluginId, null); }
        catch (Throwable ignored) {}
    }

    public static void openPluginSettings(String pluginId, String linkAlias) {
        try { app.nimarkogram.messenger.plugins.PluginsController.openPluginSetting(pluginId, linkAlias); }
        catch (Throwable ignored) {}
    }

    public java.util.concurrent.ConcurrentHashMap<String, app.nimarkogram.messenger.plugins.Plugin> getPlugins() {
        return real.plugins;
    }

    public android.content.SharedPreferences getPreferences() {
        return real.preferences;
    }

    public java.io.File getPluginsDir() {
        try { return real.getPluginsDir(); }
        catch (Throwable t) { return real.pluginsDir; }
    }

    public java.util.concurrent.ConcurrentHashMap<String, java.util.List<app.nimarkogram.messenger.plugins.models.SettingItem>> getSettings() {
        return real.settings;
    }

    public app.nimarkogram.messenger.plugins.utils.PluginsWatchdog getWatchdog() {
        try { return real.getWatchdog(); }
        catch (Throwable t) { return null; }
    }

    public void setPluginsDir(java.io.File file) {
        try { real.pluginsDir = file; } catch (Throwable ignored) {}
    }

    public void setPreferences(android.content.SharedPreferences sharedPreferences) {
        try { real.preferences = sharedPreferences; } catch (Throwable ignored) {}
    }

    public void init() {
        try { real.init(); } catch (Throwable ignored) {}
    }

    public void init(Runnable onDone) {
        try { real.init(onDone); } catch (Throwable ignored) {}
    }

    public void init(boolean startWithSafeMode) {
        try { real.init(startWithSafeMode, null); } catch (Throwable ignored) {}
    }

    public void init(boolean startWithSafeMode, Runnable onDone) {
        try { real.init(startWithSafeMode, onDone); } catch (Throwable ignored) {}
    }

    public void restart() {
        try { real.restart(); } catch (Throwable ignored) {}
    }

    public void restart(boolean startWithSafeMode) {
        try { real.restart(startWithSafeMode); } catch (Throwable ignored) {}
    }

    public void shutdown(Runnable onDone) {
        try { real.shutdown(onDone); } catch (Throwable ignored) {}
    }

    public void checkDevServers() {
        try { real.checkDevServers(); } catch (Throwable ignored) {}
    }

    public boolean getInitialized() {
        try { return real.isInitialized(); } catch (Throwable t) { return false; }
    }

    public boolean isInitialized() {
        try { return real.isInitialized(); } catch (Throwable t) { return false; }
    }

    public void addEventHook(String pluginId, String hookName, boolean matchSubstring, int priority) {
        try { real.addEventHook(pluginId, hookName, matchSubstring, priority); } catch (Throwable ignored) {}
    }

    public void removeEventHook(String pluginId, String hookName) {
        try { real.removeEventHook(pluginId, hookName); } catch (Throwable ignored) {}
    }

    public void addXposedHook(String pluginId, de.robv.android.xposed.XC_MethodHook.Unhook unhook) {
        try { real.addXposedHook(pluginId, unhook); } catch (Throwable ignored) {}
    }

    public void addXposedHooks(String pluginId, java.util.ArrayList<de.robv.android.xposed.XC_MethodHook.Unhook> unhooks) {
        try { real.addXposedHooks(pluginId, unhooks); } catch (Throwable ignored) {}
    }

    public void removeXposedHook(String pluginId, de.robv.android.xposed.XC_MethodHook.Unhook unhook) {
        try { real.removeXposedHook(pluginId, unhook); } catch (Throwable ignored) {}
    }

    public void removeHooksByPluginId(String pluginId) {
        try { real.removeHooksByPluginId(pluginId); } catch (Throwable ignored) {}
    }

    public String addMenuItem(String pluginId, com.chaquo.python.PyObject pyMenuItemData) {
        try { return real.addMenuItem(pluginId, pyMenuItemData); } catch (Throwable t) { return null; }
    }

    public boolean removeMenuItem(String pluginId, String itemId) {
        try { return real.removeMenuItem(pluginId, itemId); } catch (Throwable t) { return false; }
    }

    public void removeMenuItemsByPluginId(String pluginId) {
        try { real.removeMenuItemsByPluginId(pluginId); } catch (Throwable ignored) {}
    }

    public java.util.List<app.nimarkogram.messenger.plugins.hooks.MenuItemRecord> getMenuItemsForLocation(
            String menuType, java.util.Map<String, Object> contextData) {
        try {
            return real.getMenuItemsForLocation(menuType,
                    contextData == null ? new java.util.HashMap<String, Object>() : contextData);
        } catch (Throwable t) {
            return new java.util.ArrayList<>();
        }
    }

    public java.util.List<app.nimarkogram.messenger.plugins.hooks.MenuItemRecord> getMenuItemsForLocation(
            String menuType, app.nimarkogram.messenger.plugins.utils.MenuContextBuilder builder) {
        try { return real.getMenuItemsForLocation(menuType, builder); }
        catch (Throwable t) { return new java.util.ArrayList<>(); }
    }

    public void executeOnAppEvent(String eventType) {
        try { real.executeOnAppEvent(eventType); } catch (Throwable ignored) {}
    }

    public boolean hasPluginSettingsPreferences(String pluginId) {
        try { return real.hasPluginSettingsPreferences(pluginId); } catch (Throwable t) { return false; }
    }

    public void invalidatePluginSettings(String pluginId) {
        try { real.invalidatePluginSettings(pluginId); } catch (Throwable ignored) {}
    }

    public void loadPluginSettings() {
        try { real.loadPluginSettings(); } catch (Throwable ignored) {}
    }

    public void clearPluginSettingsPreferences(String pluginId) {
        try { real.clearPluginSettingsPreferences(pluginId); } catch (Throwable ignored) {}
    }

    public void clearPluginSettingsPreferences(String pluginId, boolean clearEnabledState) {
        try { real.clearPluginSettingsPreferences(pluginId, clearEnabledState); } catch (Throwable ignored) {}
    }

    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment, String filePath, boolean trusted) {
        try { real.showInstallDialog(fragment, filePath, trusted); } catch (Throwable ignored) {}
    }

    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment, org.telegram.messenger.MessageObject messageObject) {
        try { real.showInstallDialog(fragment, messageObject); } catch (Throwable ignored) {}
    }

    public org.telegram.tgnet.TLObject executePreRequestHook(String requestName, int account, org.telegram.tgnet.TLObject request) {
        try { return real.executePreRequestHook(requestName, account, request); }
        catch (Throwable t) { return request; }
    }

    public app.nimarkogram.messenger.plugins.hooks.PluginsHooks.PostRequestResult executePostRequestHook(
            String requestName, int account, org.telegram.tgnet.TLObject response, org.telegram.tgnet.TLRPC.TL_error error) {
        try { return real.executePostRequestHook(requestName, account, response, error); }
        catch (Throwable t) { return null; }
    }

    public org.telegram.tgnet.TLRPC.Update executeUpdateHook(String updateName, int account, org.telegram.tgnet.TLRPC.Update update) {
        try { return real.executeUpdateHook(updateName, account, update); }
        catch (Throwable t) { return update; }
    }

    public org.telegram.tgnet.TLRPC.Updates executeUpdatesHook(String containerName, int account, org.telegram.tgnet.TLRPC.Updates updates) {
        try { return real.executeUpdatesHook(containerName, account, updates); }
        catch (Throwable t) { return updates; }
    }

    public org.telegram.messenger.SendMessagesHelper.SendMessageParams executeSendMessageHook(
            int account, org.telegram.messenger.SendMessagesHelper.SendMessageParams params) {
        try { return real.executeSendMessageHook(account, params); }
        catch (Throwable t) { return params; }
    }

    public void notifyPluginsChanged() {
        try {
            java.lang.reflect.Method m = real.getClass().getDeclaredMethod("notifyPluginsChanged");
            m.setAccessible(true);
            m.invoke(real);
        } catch (Throwable ignored) {
            
        }
    }

    public void cleanupPlugin(String pluginId) {
        try { real.removeHooksByPluginId(pluginId); } catch (Throwable ignored) {}
        try { real.removeMenuItemsByPluginId(pluginId); } catch (Throwable ignored) {}
    }
}
