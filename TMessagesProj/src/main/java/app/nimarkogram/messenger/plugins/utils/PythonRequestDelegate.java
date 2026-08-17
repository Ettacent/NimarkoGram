package app.nimarkogram.messenger.plugins.utils;

import com.chaquo.python.PyObject;
import com.chaquo.python.PyProxy;

import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicReference;

import app.nimarkogram.messenger.plugins.PluginsController;

public final class PythonRequestDelegate implements
        RequestDelegate, PluginsController.RuntimeCallbackHolder {

    private interface ActivationDispatcher {
        boolean post(Runnable runnable);
    }

    private static final class CallbackTarget {
        final PyObject pythonCallback;
        final RequestDelegate delegateCallback;

        CallbackTarget(PyObject pythonCallback, RequestDelegate delegateCallback) {
            this.pythonCallback = pythonCallback;
            this.delegateCallback = delegateCallback;
        }
    }

    private static final class Invocation {
        final TLObject response;
        final TLRPC.TL_error error;

        Invocation(TLObject response, TLRPC.TL_error error) {
            this.response = response;
            this.error = error;
        }
    }

    private final OneShotCallbackState<CallbackTarget> callbackState;
    private final PluginsController.PluginRuntimeToken runtimeToken;
    private final AtomicReference<ActivationDispatcher>
            activationDispatcher = new AtomicReference<>();
    private final AtomicReference<Invocation>
            activationInvocation = new AtomicReference<>();

    public PythonRequestDelegate(
            PyObject callback,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (callback == null || runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Python request callback requires an exact plugin runtime");
        }
        this.callbackState = new OneShotCallbackState<>(
                new CallbackTarget(callback, null));
        this.runtimeToken = runtimeToken;
        registerExactHolder();
    }

    private PythonRequestDelegate(
            RequestDelegate callback,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (callback == null || runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Request delegate requires an exact plugin runtime");
        }
        if (callback instanceof PyProxy) {
            throw new IllegalArgumentException(
                    "Raw PyProxy RequestDelegate is not safe to retain");
        }
        this.callbackState = new OneShotCallbackState<>(
                new CallbackTarget(null, callback));
        this.runtimeToken = runtimeToken;
        registerExactHolder();
    }

    public static PythonRequestDelegate fromDelegate(
            RequestDelegate callback,
            PluginsController.PluginRuntimeToken runtimeToken) {
        return new PythonRequestDelegate(
                callback, runtimeToken);
    }

    @Override
    public void run(TLObject response, TLRPC.TL_error error) {
        if (!callbackState.beginInitialInvocation()) {
            return;
        }
        dispatch(new Invocation(response, error));
    }

    private void runAfterActivation(Invocation invocation) {
        if (!callbackState.beginOnlyRetry()) {
            return;
        }
        dispatch(invocation);
    }

    private void dispatch(Invocation invocation) {
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
            activationInvocation.set(invocation);
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

        CallbackTarget callback = callbackState.takeForExecution();
        if (callback == null
                || !controller.enterPluginRuntime(runtimeToken)) {
            revokeAndUnregister();
            return;
        }
        String pluginId = runtimeToken.getPluginId();
        controller.getWatchdog().onPluginExecutionStarted(pluginId);
        try {
            if (callback.delegateCallback != null) {
                callback.delegateCallback.run(
                        invocation.response, invocation.error);
            } else {
                callback.pythonCallback.call(
                        invocation.response, invocation.error);
            }
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            controller.getWatchdog().onPluginExecutionFailed(
                    pluginId, failure);
            FileLog.e("NimarkoGram: plugin request callback failed for "
                    + pluginId, failure);
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(pluginId);
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
        Invocation invocation = activationInvocation.getAndSet(null);
        if (dispatcher == null || invocation == null) {
            return;
        }
        try {
            if (!dispatcher.post(
                    () -> runAfterActivation(invocation))) {
                revokeAndUnregister();
            }
        } catch (Throwable failure) {
            revokeAndUnregister();
            rethrowIfFatal(failure);
            FileLog.e(
                    "NimarkoGram: unable to resume plugin request callback",
                    failure);
        }
    }

    @Override
    public void revokePluginRuntime() {
        callbackState.drop();
        activationDispatcher.set(null);
        activationInvocation.set(null);
    }

    private void registerExactHolder() {
        if (!PluginsController.getInstance()
                .registerRuntimeCallbackHolder(runtimeToken, this)) {
            callbackState.drop();
            throw new IllegalArgumentException(
                    "Request delegate runtime is not current");
        }
    }

    private static ActivationDispatcher captureDispatcher() {
        Thread thread = Thread.currentThread();
        if (thread instanceof DispatchQueue) {
            DispatchQueue queue = (DispatchQueue) thread;
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
