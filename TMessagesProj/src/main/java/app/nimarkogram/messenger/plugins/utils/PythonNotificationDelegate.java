package app.nimarkogram.messenger.plugins.utils;

import android.os.Looper;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;

public final class PythonNotificationDelegate implements
        NotificationCenter.NotificationCenterDelegate,
        PluginUiRegistry.RuntimeOwnedUi {

    private enum State {
        ACTIVE,
        CLOSED
    }

    private final PluginsController.PluginRuntimeToken runtimeToken;
    private final AtomicReference<PyObject> callback;
    private final AtomicReference<State> state =
            new AtomicReference<>(State.ACTIVE);
    private NotificationCenter notificationCenter;
    private int[] notificationIds;

    public PythonNotificationDelegate(
            PyObject callback,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (callback == null || runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Python notification callback requires an exact plugin runtime");
        }
        this.callback = new AtomicReference<>(callback);
        this.runtimeToken = runtimeToken;
        PluginUiRegistry.trackRuntimeOwnedUi(runtimeToken, this);
    }

    public boolean register(
            NotificationCenter center, int[] ids) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "NotificationCenter observers must be registered on main");
        }
        if (center == null || ids == null || ids.length == 0
                || state.get() != State.ACTIVE
                || callback.get() == null
                || !PluginsController.getInstance()
                        .isPluginRuntimeCurrent(runtimeToken)) {
            return false;
        }
        unregisterOnMain(false);
        notificationCenter = center;
        notificationIds = Arrays.copyOf(ids, ids.length);
        int registered = 0;
        try {
            for (int id : notificationIds) {
                center.addObserver(this, id);
                registered++;
            }
            return true;
        } catch (Throwable failure) {
            for (int index = 0; index < registered; index++) {
                try {
                    center.removeObserver(this, notificationIds[index]);
                } catch (Throwable ignored) {
                }
            }
            notificationCenter = null;
            notificationIds = null;
            rethrowIfFatal(failure);
            FileLog.e("Unable to register plugin NotificationCenter observer",
                    failure);
            return false;
        }
    }

    public void unregister() {
        state.set(State.CLOSED);
        callback.getAndSet(null);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            unregisterOnMain(true);
        } else {
            AndroidUtilities.runOnUIThread(
                    () -> unregisterOnMain(true));
        }
    }

    @Override
    public void didReceivedNotification(
            int id, int account, Object... args) {
        if (state.get() != State.ACTIVE) {
            return;
        }
        PyObject localCallback = callback.get();
        if (localCallback == null || state.get() != State.ACTIVE) {
            return;
        }
        PluginsController controller = PluginsController.getInstance();
        if (controller.getPluginRuntimeTaskDecision(runtimeToken)
                != PluginsController.RUNTIME_TASK_RUN
                || !controller.enterPluginRuntime(runtimeToken)) {
            return;
        }
        if (state.get() != State.ACTIVE
                || callback.get() != localCallback) {
            controller.exitPluginRuntime(runtimeToken);
            return;
        }
        String pluginId = runtimeToken.getPluginId();
        controller.getWatchdog().onPluginExecutionStarted(pluginId);
        try {
            int payloadLength = args != null ? args.length : 0;
            Object[] callbackArgs = new Object[payloadLength + 2];
            callbackArgs[0] = id;
            callbackArgs[1] = account;
            if (payloadLength > 0) {
                System.arraycopy(
                        args, 0, callbackArgs, 2, payloadLength);
            }
            localCallback.call(callbackArgs);
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            controller.getWatchdog().onPluginExecutionFailed(
                    pluginId, failure);
            FileLog.e("NimarkoGram: plugin notification callback failed for "
                    + pluginId, failure);
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(pluginId);
            controller.exitPluginRuntime(runtimeToken);
        }
    }

    @Override
    public void clearPluginUiReferences(
            PluginsController.PluginRuntimeToken expectedRuntime) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "Plugin notification cleanup must run on main");
        }
        if (expectedRuntime != null
                && !runtimeToken.equals(expectedRuntime)) {
            return;
        }
        state.set(State.CLOSED);
        callback.getAndSet(null);
        unregisterOnMain(true);
    }

    private void unregisterOnMain(boolean stopTracking) {
        NotificationCenter center = notificationCenter;
        int[] ids = notificationIds;
        notificationCenter = null;
        notificationIds = null;
        if (center != null && ids != null) {
            for (int id : ids) {
                try {
                    center.removeObserver(this, id);
                } catch (Throwable failure) {
                    rethrowIfFatal(failure);
                    FileLog.e("Unable to unregister plugin NotificationCenter observer",
                            failure);
                }
            }
        }
        if (stopTracking) {
            PluginUiRegistry.unregisterRuntimeOwnedUi(
                    runtimeToken, this);
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
