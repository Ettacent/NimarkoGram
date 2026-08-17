package app.nimarkogram.messenger.plugins.hooks;

import de.robv.android.xposed.XC_MethodHook;
import org.telegram.messenger.FileLog;

import app.nimarkogram.messenger.plugins.PluginsController;

public class XposedHookRecord implements HookRecord {
    final XC_MethodHook.Unhook unhookObject;
    private final PluginsController.PluginRuntimeToken runtimeToken;

    public XposedHookRecord(XC_MethodHook.Unhook unhook) {
        this(unhook, PluginsController.getInstance().captureCurrentPluginRuntime());
    }

    public XposedHookRecord(XC_MethodHook.Unhook unhook,
                            PluginsController.PluginRuntimeToken runtimeToken) {
        this.unhookObject = unhook;
        this.runtimeToken = runtimeToken;
    }

    public java.lang.reflect.Member getHookedMember() {
        try {
            return unhookObject != null ? unhookObject.getHookedMethod() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public PluginsController.PluginRuntimeToken getRuntimeToken() {
        return runtimeToken;
    }

    @Override
    public void cleanup() {
        XC_MethodHook.Unhook unhook = this.unhookObject;
        if (unhook != null) {
            try {
                unhook.unhook();
            } catch (Throwable th) {
                FileLog.e("Error during Xposed unhook cleanup", th);
            }
        }
    }

    @Override
    public boolean matches(Object obj) {
        return (obj instanceof XC_MethodHook.Unhook) && this.unhookObject == obj;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        return obj != null && getClass() == obj.getClass() && this.unhookObject == ((XposedHookRecord) obj).unhookObject;
    }

    @Override
    public int hashCode() {
        return this.unhookObject != null ? this.unhookObject.hashCode() : 0;
    }
}
