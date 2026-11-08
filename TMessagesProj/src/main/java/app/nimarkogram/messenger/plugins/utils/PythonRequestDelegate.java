/* Modifications Copyright (C) 2026 Ettacent */

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

import app.nimarkogram.messenger.CrashContext;
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

        final ManagedResponseLease lease;

        Invocation(TLObject response, TLRPC.TL_error error, ManagedResponseLease lease) {
            this.response = response;
            this.error = error;
            this.lease = lease;
        }

        void release() {
            release(null);
        }

        void release(Throwable bodyFailure) {
            if (lease != null) lease.release(bodyFailure);
        }
    }

    private final OneShotCallbackState<CallbackTarget> callbackState;
    private final PluginsController.PluginRuntimeToken runtimeToken;
    private final String diagnosticOwner;
    private final AtomicReference<ActivationDispatcher>
            activationDispatcher = new AtomicReference<>();
    private final AtomicReference<Invocation>
            activationInvocation = new AtomicReference<>();

    private final Object activationLeaseLock = new Object();
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
        this.diagnosticOwner = runtimeToken.getGeneration() + ":" + runtimeToken.getInstanceId();
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
        this.diagnosticOwner = runtimeToken.getGeneration() + ":" + runtimeToken.getInstanceId();
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
        runWithResponseLease(response, error, null);
    }

    public void runWithResponseLease(TLObject response, TLRPC.TL_error error,
            ManagedResponseLease producer) {
        if (!callbackState.beginInitialInvocation()) {
            return;
        }
        ManagedResponseLease retained = null;
        final Invocation invocation;
        try {
            retained = producer == null ? null : producer.retain();
            invocation = new Invocation(response, error, retained);
        } catch (Throwable failure) {
            revokeAfterFailure(failure);
            if (retained != null) retained.release(failure);
            throw failure;
        }
        dispatch(invocation);
    }

    private void runAfterActivation() {
        final Invocation invocation;
        synchronized (activationLeaseLock) {
            invocation = activationInvocation.getAndSet(null);
        }
        if (invocation == null) return;
        if (!callbackState.beginOnlyRetry()) {
            invocation.release();
            return;
        }
        dispatch(invocation);
    }

    private void dispatch(Invocation invocation) {
        boolean parked = false;
        Throwable bodyFailure = null;
        try {
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
            synchronized (activationLeaseLock) {
                    if (!callbackState.isWaiting()) return;
                    activationDispatcher.set(captureDispatcher());
            activationInvocation.set(invocation);
                    parked = true;
            }
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
        boolean watchdogStarted = false;
        long diagnosticToken = 0;
        long diagnosticStart = 0;
        try {
            controller.getWatchdog().onPluginExecutionStarted(pluginId);
            watchdogStarted = true;
            diagnosticToken = CrashContext.beginInvocation();
            diagnosticStart = CrashContext.invocationStartedMs(diagnosticToken);
            CrashContext.pythonPhase(diagnosticToken, diagnosticStart,
                    "request_enter", "PythonRequestDelegate.run", pluginId, diagnosticOwner);
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
            try {
                try {
                    CrashContext.pythonPhase(diagnosticToken, diagnosticStart,
                            "request_exit", "PythonRequestDelegate.run", pluginId, diagnosticOwner);
                } finally {
                    if (watchdogStarted) {
                        controller.getWatchdog().onPluginExecutionFinished(pluginId);
                    }
                }
            } finally {
                try {
                    controller.exitPluginRuntime(runtimeToken);
                } finally {
                    try {
                        callbackState.complete();
                        controller.unregisterRuntimeCallbackHolder(
                                runtimeToken, this);
                    } finally {
                        CrashContext.endInvocation(diagnosticToken);
                    }
                }
            }
        }
        } catch (Throwable failure) {
            bodyFailure = failure;
            revokeAfterFailure(failure);
            throw failure;
        } finally {
            if (!parked) invocation.release(bodyFailure);
        }
    }

    @Override
    public void onPluginRuntimeActive() {
        final ActivationDispatcher dispatcher;
        final boolean hasInvocation;
        synchronized (activationLeaseLock) {
            dispatcher = activationDispatcher.getAndSet(null);
            hasInvocation = activationInvocation.get() != null;
        }
        if (dispatcher == null || !hasInvocation) {
            if (dispatcher != null) {
                revokeAndUnregister();
            }
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
                    "NimarkoGram: unable to resume plugin request callback",
                    failure);
        }
    }

    public void cancel() {
        revokeAndUnregister();
    }
    @Override
    public void revokePluginRuntime() {
        callbackState.drop();
        final Invocation cancelled;
        synchronized (activationLeaseLock) {
        activationDispatcher.set(null);
            cancelled = activationInvocation.getAndSet(null);
        }
        if (cancelled != null) cancelled.release();
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

    private void revokeAfterFailure(Throwable primary) {
        try {
            revokeAndUnregister();
        } catch (Throwable secondary) {
            if (primary != secondary) {
                try {
                    primary.addSuppressed(secondary);
                } catch (Throwable ignored) {
                }
            }
        }
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
