package app.nimarkogram.messenger.plugins.utils;

import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import app.nimarkogram.messenger.plugins.PluginsController;

public final class PythonUtilitiesCallback
        implements Utilities.Callback<Object> {

    private final OneShotCallbackState<PyObject> callbackState;

    public PythonUtilitiesCallback(PyObject callback) {
        if (PluginsController.getInstance()
                .captureCurrentPluginRuntime() != null) {
            throw new SecurityException(
                    "Engine callback bridge is unavailable to plugins");
        }
        this.callbackState = new OneShotCallbackState<>(callback);
    }

    @Override
    public void run(Object argument) {
        if (!callbackState.beginInitialInvocation()) {
            return;
        }
        PyObject localCallback = callbackState.takeForExecution();
        if (localCallback == null) {
            callbackState.drop();
            return;
        }
        try {
            localCallback.call(argument);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError) {
                throw (VirtualMachineError) failure;
            }
            if (failure instanceof ThreadDeath) {
                throw (ThreadDeath) failure;
            }
            if (failure instanceof LinkageError) {
                throw (LinkageError) failure;
            }
            FileLog.e("NimarkoGram: Python engine callback failed",
                    failure);
        } finally {
            callbackState.complete();
        }
    }
}
