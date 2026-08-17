package app.nimarkogram.messenger.plugins.xposed;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import app.nimarkogram.messenger.plugins.PluginsConstants;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.hooks.HookFilter;

public class PyMethodHook extends XC_MethodHook {
    private ArrayList<HookFilter> afterHookedFilters;
    private ArrayList<HookFilter> beforeHookedFilters;
    private final boolean hasAfterHook;
    private final boolean hasBeforeHook;
    private final String pluginId;
    private final PyObject pythonCallback;
    private final PluginsController.PluginRuntimeToken runtimeToken;
    
    private final PyObject boundBefore;
    private final PyObject boundAfter;

    public PyMethodHook(String str, PyObject pyObject) {
        this(str, pyObject, PluginsController.getInstance().captureCurrentPluginRuntime());
    }

    public PyMethodHook(String str, PyObject pyObject,
                        PluginsController.PluginRuntimeToken runtimeToken) {
        this.beforeHookedFilters = new ArrayList<>();
        this.afterHookedFilters = new ArrayList<>();
        PluginsController.PluginRuntimeToken exactRuntime =
                requireExactRuntime(str, runtimeToken);
        if (pyObject == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        this.pluginId = str;
        this.pythonCallback = pyObject;
        this.runtimeToken = exactRuntime;
        this.hasBeforeHook = pyObject.containsKey(PluginsConstants.Xposed.BEFORE_HOOKED_METHOD);
        this.hasAfterHook = pyObject.containsKey(PluginsConstants.Xposed.AFTER_HOOKED_METHOD);
        this.boundBefore = resolveBound(pyObject, PluginsConstants.Xposed.BEFORE_HOOKED_METHOD, this.hasBeforeHook);
        this.boundAfter = resolveBound(pyObject, PluginsConstants.Xposed.AFTER_HOOKED_METHOD, this.hasAfterHook);
    }

    public PyMethodHook(String str, PyObject pyObject, int i) {
        this(str, pyObject, i, PluginsController.getInstance().captureCurrentPluginRuntime());
    }

    public PyMethodHook(String str, PyObject pyObject, int i,
                        PluginsController.PluginRuntimeToken runtimeToken) {
        super(i);
        this.beforeHookedFilters = new ArrayList<>();
        this.afterHookedFilters = new ArrayList<>();
        PluginsController.PluginRuntimeToken exactRuntime =
                requireExactRuntime(str, runtimeToken);
        if (pyObject == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        this.pluginId = str;
        this.pythonCallback = pyObject;
        this.runtimeToken = exactRuntime;
        this.hasBeforeHook = pyObject.containsKey(PluginsConstants.Xposed.BEFORE_HOOKED_METHOD);
        this.hasAfterHook = pyObject.containsKey(PluginsConstants.Xposed.AFTER_HOOKED_METHOD);
        this.boundBefore = resolveBound(pyObject, PluginsConstants.Xposed.BEFORE_HOOKED_METHOD, this.hasBeforeHook);
        this.boundAfter = resolveBound(pyObject, PluginsConstants.Xposed.AFTER_HOOKED_METHOD, this.hasAfterHook);
    }

    private static PluginsController.PluginRuntimeToken requireExactRuntime(
            String pluginId,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Method hook requires an exact plugin runtime");
        }
        if (pluginId == null
                || !pluginId.equals(runtimeToken.getPluginId())) {
            throw new IllegalArgumentException(
                    "Method hook owner does not match plugin id");
        }
        return runtimeToken;
    }

    private boolean enterRuntime(PluginsController controller) {
        return controller.getPluginRuntimeTaskDecision(this.runtimeToken)
                == PluginsController.RUNTIME_TASK_RUN
                && controller.enterPluginRuntime(this.runtimeToken);
    }

    private void exitRuntime(PluginsController controller) {
        controller.exitPluginRuntime(this.runtimeToken);
    }

    private static PyObject resolveBound(PyObject pyObject, String attr, boolean has) {
        if (!has) return null;
        try {
            return pyObject.get(attr);
        } catch (Throwable t) {
            FileLog.w("nimarko: PyMethodHook pre-resolve failed for " + attr + ": " + t);
            return null;
        }
    }

    public void setBeforeHookedFilters(ArrayList<HookFilter> arrayList) {
        this.beforeHookedFilters = arrayList;
    }

    public void setAfterHookedFilters(ArrayList<HookFilter> arrayList) {
        this.afterHookedFilters = arrayList;
    }

    public ArrayList<HookFilter> getBeforeHookedFilters() {
        return this.beforeHookedFilters;
    }

    public ArrayList<HookFilter> getAfterHookedFilters() {
        return this.afterHookedFilters;
    }

    @Override 
    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
        if (this.hasBeforeHook) {
            PluginsController controller = PluginsController.getInstance();
            if (!enterRuntime(controller)) {
                return;
            }
            boolean watchdogStarted = false;
            try {
                if (!this.beforeHookedFilters.isEmpty()) {
                    ArrayList<HookFilter> arrayList = this.beforeHookedFilters;
                    int size = arrayList.size();
                    int i = 0;
                    while (i < size) {
                        HookFilter hookFilter = arrayList.get(i);
                        i++;
                        if (!hookFilter.execute(methodHookParam, true)) {
                            return;
                        }
                    }
                }
                
                controller.getWatchdog().onPluginExecutionStarted(this.pluginId);
                watchdogStarted = true;
                try {
                    
                    if (this.boundBefore != null) {
                        this.boundBefore.call(methodHookParam);
                    } else {
                        this.pythonCallback.callAttr(PluginsConstants.Xposed.BEFORE_HOOKED_METHOD, methodHookParam);
                    }
                } catch (PyException pythonFailure) {
                    controller.getWatchdog().onPluginExecutionFailed(
                            this.pluginId, pythonFailure);
                    throw pythonFailure;
                } finally {
                    controller.getWatchdog().onPluginExecutionFinished(this.pluginId);
                    watchdogStarted = false;
                }
            } catch (Throwable th) {
                handleHookError("beforeHookedMethod", th);
                
                throw th;
            } finally {
                if (watchdogStarted) {
                    controller.getWatchdog().onPluginExecutionFinished(this.pluginId);
                }
                exitRuntime(controller);
            }
        }
    }

    @Override 
    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
        if (this.hasAfterHook) {
            PluginsController controller = PluginsController.getInstance();
            if (!enterRuntime(controller)) {
                return;
            }
            boolean watchdogStarted = false;
            try {
                if (!this.afterHookedFilters.isEmpty()) {
                    ArrayList<HookFilter> arrayList = this.afterHookedFilters;
                    int size = arrayList.size();
                    int i = 0;
                    while (i < size) {
                        HookFilter hookFilter = arrayList.get(i);
                        i++;
                        if (!hookFilter.execute(methodHookParam, false)) {
                            return;
                        }
                    }
                }
                controller.getWatchdog().onPluginExecutionStarted(this.pluginId);
                watchdogStarted = true;
                try {
                    
                    if (this.boundAfter != null) {
                        this.boundAfter.call(methodHookParam);
                    } else {
                        this.pythonCallback.callAttr(PluginsConstants.Xposed.AFTER_HOOKED_METHOD, methodHookParam);
                    }
                } catch (PyException pythonFailure) {
                    controller.getWatchdog().onPluginExecutionFailed(
                            this.pluginId, pythonFailure);
                    throw pythonFailure;
                } finally {
                    controller.getWatchdog().onPluginExecutionFinished(this.pluginId);
                    watchdogStarted = false;
                }
            } catch (Throwable th) {
                handleHookError("afterHookedMethod", th);
                throw th;
            } finally {
                if (watchdogStarted) {
                    controller.getWatchdog().onPluginExecutionFinished(this.pluginId);
                }
                exitRuntime(controller);
            }
        }
    }

    private void handleHookError(String str, Throwable th) {
        if ((th instanceof PyException) && th.getMessage() != null && th.getMessage().contains("closed")) {
            FileLog.e("Attempted to call a closed PyObject callback in " + this.pluginId);
            return;
        }
        FileLog.e("Plugin '" + this.pluginId + "' crashed in " + str + ": " + th.getMessage(), th);
    }
}
