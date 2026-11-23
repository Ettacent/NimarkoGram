/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.distribution;

import android.content.Intent;
import java.io.File;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.hooks.MenuItemRecord;
import app.nimarkogram.messenger.plugins.hooks.PluginsHooks;
import app.nimarkogram.messenger.plugins.intents.IntentsController;
import app.nimarkogram.messenger.plugins.ui.PluginsActivity;
import app.nimarkogram.messenger.plugins.ui.PluginsInfoActivity;
import app.nimarkogram.messenger.plugins.utils.*;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;

public final class ClientPlugins {
    private ClientPlugins() {}

    public static final class Post {
        public final TLObject response;
        public final TLRPC.TL_error error;
        private Post(TLObject response, TLRPC.TL_error error) { this.response = response; this.error = error; }
    }
    public static final class MenuItem {
        private final MenuItemRecord record;
        public final String text, pluginId, itemId;
        public final int iconResId;
        private MenuItem(MenuItemRecord record) {
            this.record = record; text = record.text; pluginId = record.pluginId;
            itemId = record.itemId; iconResId = record.iconResId;
        }
        public void click(Map<String,Object> context) { record.onClickCallback.call(context); }
    }
    public static final class Lease {
        private final ManagedResponseLease actual;
        public Lease(TLObject response) { actual = new ManagedResponseLease(response); }
        public void release(Throwable bodyFailure) { actual.release(bodyFailure); }
    }

    public static boolean supported() { return PluginsController.isPluginEngineSupported(); }
    public static BaseFragment settings(boolean details) { return details ? new PluginsInfoActivity() : new PluginsActivity(); }
    public static boolean infoScreen(Object owner) { return owner instanceof PluginsInfoActivity; }
    public static int compactId() { return PluginsInfoActivity.PreferenceItem.COMPACT_VIEW.getId(); }
    public static int safeModeId() { return PluginsInfoActivity.PreferenceItem.SAFE_MODE.getId(); }
    public static int artId() { return PluginsInfoActivity.PreferenceItem.PLUGINS_DISABLE_ART_OPTS.getId(); }
    public static void init() { PluginsController engine = PluginsController.getInstance(); engine.init(() -> { if (engine.isInitialized()) engine.executeOnAppEvent("app_start"); }); }
    public static void event(String name) { PluginsController.getInstance().executeOnAppEvent(name); }
    public static boolean dispatchIntent(Intent intent) { return IntentsController.getInstance().dispatchIntent(intent); }
    public static long dispatchSequence() { return IntentsController.getInstance().getDispatchSequence(); }
    public static void exitDiagnostics() { NativeCrashHandler.schedulePreviousExitDiagnostics(); }
    public static String crashingPlugin(Thread thread, Throwable error) { return PluginsWatchdog.findCrashingPlugin(thread, error); }
    public static void writeIdentity(PrintWriter writer, String pluginId) { PluginCrashReports.writeIdentity(writer, pluginId); }
    public static void rememberFatalReport(String pluginId, File report) { PluginCrashReports.rememberFatalReport(pluginId, report); }
    public static void setSafeModeReason(String reason) { PluginCrashReports.setSafeModeReason(reason); }
    public static boolean isPlugin(MessageObject message) { return PluginsController.isPlugin(message); }
    public static void showInstallDialog(BaseFragment fragment, MessageObject message) { PluginsController.getInstance().showInstallDialog(fragment, message); }
    public static boolean maybeShowInstallDialog(MessageObject message, BaseFragment fragment) { return PluginsController.maybeShowInstallDialog(message, fragment); }
    public static boolean openFile(File file, String name, String mime) {
        if (file == null || !Python.isStarted()) return false;
        PyObject value = Python.getInstance().getModule("file_utils").callAttr("_dispatch_open_from_java", file.getAbsolutePath(), name, mime);
        return value != null && value.toBoolean();
    }
    public static DialogsActivity.DialogsActivityDelegate adaptDialogsDelegate(DialogsActivity.DialogsActivityDelegate delegate) { return app.nimarkogram.messenger.plugins.bridge.PythonInterfaceProxy.adaptDialogsDelegate(delegate); }
    public static <T> T withPortablePluginSettingsTransaction(Callable<T> transaction) throws Exception { return PluginsController.withPortablePluginSettingsTransaction(transaction::call); }
    public static boolean reloadPortablePluginSettings() { return PluginsController.reloadPortablePluginSettings(); }
    public static TLObject executePreRequestHook(String name, int account, TLObject request) { return PluginsController.getInstance().executePreRequestHook(name, account, request); }
    public static Post executePostRequestHook(String name, int account, TLObject response, TLRPC.TL_error error) {
        PluginsHooks.PostRequestResult result = PluginsController.getInstance().executePostRequestHook(name, account, response, error);
        return result == null ? null : new Post(result.response, result.error);
    }
    public static TLRPC.Updates executeUpdatesHook(String name, int account, TLRPC.Updates updates) { return PluginsController.getInstance().executeUpdatesHook(name, account, updates); }
    public static TLRPC.Update executeUpdateHook(String name, int account, TLRPC.Update update) { return PluginsController.getInstance().executeUpdateHook(name, account, update); }
    public static SendMessagesHelper.SendMessageParams executeSendMessageHook(int account, SendMessagesHelper.SendMessageParams message) { return PluginsController.getInstance().executeSendMessageHook(account, message); }
    public static boolean isPluginActive(String id) { return PluginsController.getInstance().isPluginActive(id); }
    public static List<MenuItem> getMenuItemsForLocation(String location, Map<String,Object> context) {
        List<MenuItemRecord> entries = PluginsController.getInstance().getMenuItemsForLocation(location, context);
        if (entries == null || entries.isEmpty()) return java.util.Collections.emptyList();
        java.util.ArrayList<MenuItem> items = new java.util.ArrayList<>(entries.size());
        for (MenuItemRecord record : entries) if (record != null) items.add(new MenuItem(record));
        return items;
    }
    public static RequestDelegate requestOwner(RequestDelegate delegate) { return delegate instanceof PythonRequestDelegate ? delegate : null; }
    public static void cancelOwner(RequestDelegate owner) { ((PythonRequestDelegate) owner).cancel(); }
    public static boolean dispatchWithLease(RequestDelegate delegate, TLObject response, TLRPC.TL_error error, Lease producer) {
        if (!(delegate instanceof PythonRequestDelegate)) return false;
        ((PythonRequestDelegate) delegate).runWithResponseLease(response, error, producer == null ? null : producer.actual);
        return true;
    }
}
