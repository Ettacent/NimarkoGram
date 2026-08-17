package app.nimarkogram.messenger.plugins.utils;

import com.chaquo.python.PyObject;

import android.os.Handler;
import android.os.Looper;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.concurrent.atomic.AtomicReference;

public final class PythonRunnable implements
        Runnable, PluginsController.RuntimeCallbackHolder {

    private interface ActivationDispatcher {
        boolean post(Runnable runnable);
    }

    private final OneShotCallbackState<PyObject> callbackState;
    private final PluginsController.PluginRuntimeToken runtimeToken;
    private final AtomicReference<ActivationDispatcher>
            activationDispatcher = new AtomicReference<>();

    public PythonRunnable(PyObject callback) {
        this(callback, PluginsController.getInstance().captureCurrentPluginRuntime());
    }

    public PythonRunnable(PyObject callback, PluginsController.PluginRuntimeToken runtimeToken) {
        if (runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Python Runnable requires an exact plugin runtime");
        }
        this.callbackState = new OneShotCallbackState<>(callback);
        this.runtimeToken = runtimeToken;
        if (!PluginsController.getInstance()
                .registerRuntimeCallbackHolder(runtimeToken, this)) {
            callbackState.drop();
            throw new IllegalArgumentException(
                    "Python Runnable runtime is not current");
        }
    }

    @Override
    public void run() {
        if (!callbackState.beginInitialInvocation()) {
            return;
        }
        dispatch();
    }

    private void runAfterActivation() {
        if (!callbackState.beginOnlyRetry()) {
            return;
        }
        dispatch();
    }

    private void dispatch() {
        PluginsController controller = PluginsController.getInstance();
        int decision = controller.getPluginRuntimeTaskDecision(runtimeToken);
        if (decision == PluginsController.RUNTIME_TASK_DROP) {
            revokeAndUnregister();
            return;
        }
        if (decision == PluginsController.RUNTIME_TASK_WAIT) {
            if (!callbackState.deferInitialInvocation()) {
                revokeAndUnregister();
                return;
            }
            activationDispatcher.set(captureDispatcher());
            int deferred = controller.deferRuntimeCallback(
                    runtimeToken, this);
            if (deferred == PluginsController.RUNTIME_TASK_RUN) {
                onPluginRuntimeActive();
            } else if (deferred == PluginsController.RUNTIME_TASK_DROP) {
                revokeAndUnregister();
            }
            return;
        }
        if (decision != PluginsController.RUNTIME_TASK_RUN) {
            revokeAndUnregister();
            return;
        }

        final PyObject callable = callbackState.takeForExecution();
        if (callable == null
                || !controller.enterPluginRuntime(runtimeToken)) {
            revokeAndUnregister();
            return;
        }
        String pluginId = runtimeToken.getPluginId();
        PluginUiRegistry.DecorChildrenSnapshot decorSnapshot =
                Looper.myLooper() == Looper.getMainLooper()
                        ? PluginUiRegistry.captureDecorChildren() : null;
        controller.getWatchdog().onPluginExecutionStarted(pluginId);
        try {
            callable.call();
        } catch (Throwable error) {
            
            controller.getWatchdog().onPluginExecutionFailed(
                    pluginId, error);
            FileLog.e("NimarkoGram: queued Python callback failed", error);
            rethrowIfFatal(error);
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(pluginId);
            if (decorSnapshot != null) {
                try {
                    PluginUiRegistry.adoptNewDecorChildren(
                            runtimeToken, decorSnapshot);
                } catch (Throwable failure) {
                    FileLog.e(
                            "NimarkoGram: unable to isolate plugin overlay",
                            failure);
                }
            }
            controller.exitPluginRuntime(runtimeToken);
            callbackState.complete();
            controller.unregisterRuntimeCallbackHolder(
                    runtimeToken, this);
        }
    }

    @Override
    public void onPluginRuntimeActive() {
        ActivationDispatcher dispatcher =
                activationDispatcher.getAndSet(null);
        if (dispatcher == null) {
            return;
        }
        try {
            if (!dispatcher.post(this::runAfterActivation)) {
                revokeAndUnregister();
            }
        } catch (Throwable failure) {
            revokeAndUnregister();
            rethrowIfFatal(failure);
            FileLog.e(
                    "NimarkoGram: unable to resume Python callback",
                    failure);
        }
    }

    @Override
    public void revokePluginRuntime() {
        callbackState.drop();
        activationDispatcher.set(null);
    }

    private static ActivationDispatcher captureDispatcher() {
        Thread current = Thread.currentThread();
        if (current instanceof DispatchQueue) {
            DispatchQueue queue = (DispatchQueue) current;
            return queue::postRunnable;
        }
        Looper looper = Looper.myLooper();
        if (looper != null) {
            Handler handler = new Handler(looper);
            return handler::post;
        }
        return Utilities.pluginsQueue::postRunnable;
    }

    private void revokeAndUnregister() {
        revokePluginRuntime();
        PluginsController.getInstance().unregisterRuntimeCallbackHolder(
                runtimeToken, this);
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
