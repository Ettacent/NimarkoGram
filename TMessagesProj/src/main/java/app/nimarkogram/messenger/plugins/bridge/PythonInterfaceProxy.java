package app.nimarkogram.messenger.plugins.bridge;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;

public final class PythonInterfaceProxy implements
        InvocationHandler, PluginUiRegistry.RuntimeOwnedUi {

    private final PluginsController.PluginRuntimeToken runtimeToken;
    private final AtomicReference<PyObject> target;
    private final AtomicReference<Object> proxy = new AtomicReference<>();

    private PythonInterfaceProxy(
            PyObject target,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (target == null || runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Python interface proxy requires an exact plugin runtime");
        }
        PluginsController controller = PluginsController.getInstance();
        if (!controller.isPluginRuntimeCurrent(runtimeToken)) {
            throw new IllegalStateException(
                    "Python interface proxy runtime is no longer current");
        }
        this.target = new AtomicReference<>(target);
        this.runtimeToken = runtimeToken;
    }

    public static Object create(
            PyObject target,
            PluginsController.PluginRuntimeToken runtimeToken,
            Class<?>[] interfaces) {
        if (interfaces == null || interfaces.length == 0) {
            throw new IllegalArgumentException(
                    "At least one Java interface is required");
        }
        Class<?>[] checked = Arrays.copyOf(interfaces, interfaces.length);
        ClassLoader loader = null;
        for (Class<?> type : checked) {
            if (type == null || !type.isInterface()) {
                throw new IllegalArgumentException(
                        "Python callbacks can implement Java interfaces only");
            }
            if (loader == null && type.getClassLoader() != null) {
                loader = type.getClassLoader();
            }
        }
        if (loader == null) {
            loader = PythonInterfaceProxy.class.getClassLoader();
        }

        PythonInterfaceProxy handler =
                new PythonInterfaceProxy(target, runtimeToken);
        Object result = Proxy.newProxyInstance(loader, checked, handler);
        handler.proxy.set(result);
        PluginUiRegistry.trackRuntimeOwnedUi(runtimeToken, handler);
        
        if (!PluginsController.getInstance()
                .isPluginRuntimeCurrent(runtimeToken)) {
            handler.clearPythonReference();
        }
        return result;
    }

    public static void release(Object candidate) {
        if (candidate == null || !Proxy.isProxyClass(candidate.getClass())) {
            return;
        }
        try {
            InvocationHandler handler = Proxy.getInvocationHandler(candidate);
            if (handler instanceof PythonInterfaceProxy) {
                ((PythonInterfaceProxy) handler).clearPythonReference();
            }
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            FileLog.e("Could not release Python interface proxy", failure);
        }
    }

    @Override
    public Object invoke(Object invokedProxy, Method method, Object[] args) {
        if (method == null) {
            return null;
        }
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(invokedProxy, method, args);
        }

        PyObject localTarget = target.get();
        if (localTarget == null) {
            return defaultValue(method.getReturnType());
        }
        PluginsController controller = PluginsController.getInstance();
        if (controller.getPluginRuntimeTaskDecision(runtimeToken)
                        != PluginsController.RUNTIME_TASK_RUN
                || !controller.enterPluginRuntime(runtimeToken)) {
            return defaultValue(method.getReturnType());
        }

        if (controller.getPluginRuntimeTaskDecision(runtimeToken)
                        != PluginsController.RUNTIME_TASK_RUN
                || target.get() != localTarget) {
            controller.exitPluginRuntime(runtimeToken);
            return defaultValue(method.getReturnType());
        }

        String pluginId = runtimeToken.getPluginId();
        controller.getWatchdog().onPluginExecutionStarted(pluginId);
        try {
            PyObject result = localTarget.callAttr(
                    method.getName(), args != null ? args : new Object[0]);
            Class<?> returnType = method.getReturnType();
            if (returnType == Void.TYPE || result == null) {
                return defaultValue(returnType);
            }
            Object converted =
                    PythonBoundarySanitizer.convertPythonResult(
                            result, returnType, runtimeToken);
            if (converted == PythonBoundarySanitizer.UNSAFE_VALUE) {
                FileLog.e("Blocked unsafe Python proxy result for "
                        + pluginId + ": " + method.getName());
                return defaultValue(returnType);
            }
            return converted != null || !returnType.isPrimitive()
                    ? converted : defaultValue(returnType);
        } catch (PyException failure) {
            controller.getWatchdog().onPluginExecutionFailed(
                    pluginId, failure);
            FileLog.e("Python interface callback failed for "
                    + pluginId + ": " + method.getName(), failure);
            return defaultValue(method.getReturnType());
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            controller.getWatchdog().onPluginExecutionFailed(
                    pluginId, failure);
            FileLog.e("Python interface bridge failed for "
                    + pluginId + ": " + method.getName(), failure);
            return defaultValue(method.getReturnType());
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(pluginId);
            controller.exitPluginRuntime(runtimeToken);
        }
    }

    @Override
    public void clearPluginUiReferences(
            PluginsController.PluginRuntimeToken expectedRuntime) {
        if (expectedRuntime != null
                && !runtimeToken.equals(expectedRuntime)) {
            return;
        }
        clearPythonReference();
    }

    private void clearPythonReference() {
        target.set(null);
        PluginUiRegistry.unregisterRuntimeOwnedUi(
                runtimeToken, this);
    }

    private Object invokeObjectMethod(
            Object invokedProxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return args != null && args.length == 1
                        && invokedProxy == args[0];
            case "hashCode":
                return System.identityHashCode(invokedProxy);
            case "toString":
                return "PythonInterfaceProxy{"
                        + runtimeToken + ", active="
                        + (target.get() != null) + '}';
            default:
                return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == null || !type.isPrimitive() || type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) return false;
        if (type == Character.TYPE) return '\0';
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0.0f;
        if (type == Double.TYPE) return 0.0d;
        return null;
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
}
