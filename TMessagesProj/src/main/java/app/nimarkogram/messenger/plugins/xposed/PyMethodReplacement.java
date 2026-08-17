package app.nimarkogram.messenger.plugins.xposed;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import app.nimarkogram.messenger.plugins.PluginsConstants;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.bridge.PythonBoundarySanitizer;

public class PyMethodReplacement extends XC_MethodReplacement {
    private final String pluginId;
    private final PyObject pythonCallback;
    private final PluginsController.PluginRuntimeToken runtimeToken;
    
    private final PyObject boundReplace;

    public PyMethodReplacement(String str, PyObject pyObject) {
        this(str, pyObject, PluginsController.getInstance().captureCurrentPluginRuntime());
    }

    public PyMethodReplacement(String str, PyObject pyObject,
                               PluginsController.PluginRuntimeToken runtimeToken) {
        PluginsController.PluginRuntimeToken exactRuntime =
                requireExactRuntime(str, runtimeToken);
        if (pyObject == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        if (!pyObject.containsKey(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD)) {
            throw new IllegalArgumentException("Python callback object must contain a method named 'replaceHookedMethod'");
        }
        this.pluginId = str;
        this.pythonCallback = pyObject;
        this.runtimeToken = exactRuntime;
        this.boundReplace = resolveBound(pyObject);
    }

    public PyMethodReplacement(String str, PyObject pyObject, int i) {
        this(str, pyObject, i, PluginsController.getInstance().captureCurrentPluginRuntime());
    }

    public PyMethodReplacement(String str, PyObject pyObject, int i,
                               PluginsController.PluginRuntimeToken runtimeToken) {
        super(i);
        PluginsController.PluginRuntimeToken exactRuntime =
                requireExactRuntime(str, runtimeToken);
        if (pyObject == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        if (!pyObject.containsKey(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD)) {
            throw new IllegalArgumentException("Python callback object must contain a method named 'replaceHookedMethod'");
        }
        this.pluginId = str;
        this.pythonCallback = pyObject;
        this.runtimeToken = exactRuntime;
        this.boundReplace = resolveBound(pyObject);
    }

    private static PluginsController.PluginRuntimeToken requireExactRuntime(
            String pluginId,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Replacement hook requires an exact plugin runtime");
        }
        if (pluginId == null
                || !pluginId.equals(runtimeToken.getPluginId())) {
            throw new IllegalArgumentException(
                    "Replacement hook owner does not match plugin id");
        }
        return runtimeToken;
    }

    private static PyObject resolveBound(PyObject pyObject) {
        try {
            return pyObject.get(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD);
        } catch (Throwable t) {
            FileLog.w("nimarko: PyMethodReplacement pre-resolve failed: " + t);
            return null;
        }
    }

    @Override 
    protected Object replaceHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
        PluginsController controller = PluginsController.getInstance();
        boolean entered =
                controller.getPluginRuntimeTaskDecision(this.runtimeToken)
                        == PluginsController.RUNTIME_TASK_RUN
                        && controller.enterPluginRuntime(this.runtimeToken);
        if (!entered) {
            return invokeOriginal(methodHookParam);
        }

        if (controller.getPluginRuntimeTaskDecision(this.runtimeToken)
                != PluginsController.RUNTIME_TASK_RUN) {
            Throwable closeFailure =
                    closeExecutionScope(controller, false, null);
            if (closeFailure != null) {
                rethrowIfFatal(closeFailure);
            }
            return invokeOriginalWithSuppressed(
                    methodHookParam, closeFailure);
        }

        Object replacementResult = null;
        Throwable replacementFailure = null;
        boolean watchdogStarted = false;
        try {
            PyObject pyObject = this.pythonCallback;
            if (pyObject == null) {
                throw new IllegalStateException(
                        "Python replacement callback is unavailable");
            }

            controller.getWatchdog().onPluginExecutionStarted(this.pluginId);
            watchdogStarted = true;
            PyObject pyObjectCallAttr;
            if (this.boundReplace != null) {
                pyObjectCallAttr = this.boundReplace.call(methodHookParam);
            } else {
                pyObjectCallAttr = pyObject.callAttr(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD, methodHookParam);
            }
            if (pyObjectCallAttr == null) {
                replacementResult = null;
            } else {
                Class<?> returnType = returnTypeOf(methodHookParam);
                if (returnType == null) {
                    throw new IllegalStateException(
                            "Replacement result has no unambiguous declared type");
                }
                replacementResult =
                        PythonBoundarySanitizer.convertPythonResult(
                                pyObjectCallAttr, returnType,
                                this.runtimeToken);
                if (replacementResult
                        == PythonBoundarySanitizer.UNSAFE_VALUE) {
                    throw new IllegalStateException(
                            "Blocked unsafe Python proxy replacement result");
                }
            }
        } catch (Throwable failure) {
            replacementFailure =
                    reportReplacementFailure(controller, failure);
        }

        replacementFailure = closeExecutionScope(
                controller, watchdogStarted, replacementFailure);
        if (replacementFailure == null) {
            return replacementResult;
        }
        rethrowIfFatal(replacementFailure);
        return invokeOriginalWithSuppressed(
                methodHookParam, replacementFailure);
    }

    private Throwable reportReplacementFailure(
            PluginsController controller, Throwable failure) {
        Throwable primary = failure;
        try {
            controller.getWatchdog().onPluginExecutionFailed(
                    this.pluginId, failure);
        } catch (Throwable reportFailure) {
            primary = mergeFailures(primary, reportFailure);
        }
        try {
            handleHookError(failure);
        } catch (Throwable loggingFailure) {
            primary = mergeFailures(primary, loggingFailure);
        }
        return primary;
    }

    private Throwable closeExecutionScope(
            PluginsController controller,
            boolean watchdogStarted,
            Throwable primary) {
        if (watchdogStarted) {
            try {
                controller.getWatchdog().onPluginExecutionFinished(
                        this.pluginId);
            } catch (Throwable finishFailure) {
                primary = mergeFailures(primary, finishFailure);
            }
        }
        try {
            controller.exitPluginRuntime(this.runtimeToken);
        } catch (Throwable exitFailure) {
            primary = mergeFailures(primary, exitFailure);
        }
        return primary;
    }

    private static Object invokeOriginal(
            XC_MethodHook.MethodHookParam methodHookParam)
            throws Throwable {
        return de.robv.android.xposed.XposedBridge.invokeOriginalMethod(
                methodHookParam.method,
                methodHookParam.thisObject,
                methodHookParam.args);
    }

    private static Object invokeOriginalWithSuppressed(
            XC_MethodHook.MethodHookParam methodHookParam,
            Throwable replacementFailure) throws Throwable {
        try {
            return invokeOriginal(methodHookParam);
        } catch (Throwable originalFailure) {
            addSuppressedSafely(
                    originalFailure, replacementFailure);
            throw originalFailure;
        }
    }

    private static Class<?> returnTypeOf(
            XC_MethodHook.MethodHookParam methodHookParam) {
        return methodHookParam != null
                && methodHookParam.method instanceof Method
                ? ((Method) methodHookParam.method).getReturnType()
                : null;
    }

    private static Throwable mergeFailures(
            Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary == null || secondary == primary) return primary;
        if (isFatal(secondary) && !isFatal(primary)) {
            addSuppressedSafely(secondary, primary);
            return secondary;
        }
        addSuppressedSafely(primary, secondary);
        return primary;
    }

    private static void addSuppressedSafely(
            Throwable primary, Throwable secondary) {
        if (primary == null || secondary == null
                || primary == secondary) {
            return;
        }
        try {
            primary.addSuppressed(secondary);
        } catch (Throwable suppressionFailure) {
            rethrowIfFatal(suppressionFailure);
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    private static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
        if (failure instanceof LinkageError) {
            throw (LinkageError) failure;
        }
    }

    private void handleHookError(Throwable th) {
        if ((th instanceof PyException) && th.getMessage() != null && th.getMessage().contains("closed")) {
            FileLog.e("Attempted to call a closed PyObject callback in " + this.pluginId);
            return;
        }
        FileLog.e("Plugin '" + this.pluginId + "' crashed in replaceHookedMethod: " + th.getMessage(), th);
    }
}
