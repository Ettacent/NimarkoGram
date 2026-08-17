package app.nimarkogram.messenger.plugins.ui;

import android.app.Dialog;
import android.content.DialogInterface;

import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.AlertDialog;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.components.templates.PluginRuntimeDelegate;

public final class PluginDialogCallback implements
        AlertDialog.OnButtonClickListener,
        DialogInterface.OnClickListener,
        DialogInterface.OnDismissListener,
        DialogInterface.OnCancelListener,
        DialogInterface.OnShowListener,
        PluginUiRegistry.RuntimeOwnedUi {

    public static final int TYPE_BUTTON = 1;
    public static final int TYPE_ITEMS = 2;
    public static final int TYPE_DISMISS = 3;
    public static final int TYPE_CANCEL = 4;
    public static final int TYPE_SHOW = 5;

    private final int type;
    private final PluginsController.PluginRuntimeToken runtimeToken;
    private PyObject callback;
    private PyObject owner;

    public PluginDialogCallback(
            int type,
            PyObject callback,
            PyObject owner,
            PluginsController.PluginRuntimeToken runtimeToken) {
        PluginRuntimeDelegate.requireMainThread();
        if (type < TYPE_BUTTON || type > TYPE_SHOW) {
            throw new IllegalArgumentException("Unknown plugin dialog callback type");
        }
        if (owner == null || runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Plugin dialog callback requires an owner and exact runtime");
        }
        this.type = type;
        this.callback = callback;
        this.owner = owner;
        this.runtimeToken = runtimeToken;
        if (!PluginUiRegistry.registerRuntimeOwnedUi(runtimeToken, this)) {
            this.callback = null;
            this.owner = null;
        }
    }

    @Override
    public void onClick(AlertDialog dialog, int which) {
        if (type == TYPE_BUTTON) {
            invokeWithIndex(which);
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (type == TYPE_ITEMS) {
            invokeWithIndex(which);
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (type != TYPE_DISMISS) {
            return;
        }
        if (dialog instanceof Dialog) {
            PluginUiRegistry.unregisterDialog(runtimeToken, (Dialog) dialog);
        }
        PyObject localOwner = owner;
        PyObject localCallback = callback;
        try {
            PluginRuntimeDelegate.run(runtimeToken, () -> {
                if (localOwner == null) {
                    return;
                }
                try {
                    localOwner.callAttr("_on_dialog_dismissed", dialog);
                } catch (Throwable error) {
                    PluginsController.getInstance().getWatchdog()
                            .onPluginExecutionFailed(
                                    runtimeToken.getPluginId(), error);
                    FileLog.e("Unable to update plugin dialog dismissal state", error);
                }
                if (localCallback != null) {
                    localCallback.call(localOwner);
                }
            });
        } finally {
            clearPluginUiReferences(runtimeToken);
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        if (type == TYPE_CANCEL) {
            invokeWithoutIndex();
        }
    }

    @Override
    public void onShow(DialogInterface dialog) {
        if (type != TYPE_SHOW) {
            return;
        }
        PyObject localOwner = owner;
        if (localOwner == null) {
            return;
        }
        PluginRuntimeDelegate.run(runtimeToken, () ->
                localOwner.callAttr("_on_dialog_shown", dialog));
    }

    private void invokeWithIndex(int which) {
        PyObject localOwner = owner;
        PyObject localCallback = callback;
        if (localOwner == null || localCallback == null) {
            return;
        }
        PluginRuntimeDelegate.run(
                runtimeToken, () -> localCallback.call(localOwner, which));
    }

    private void invokeWithoutIndex() {
        PyObject localOwner = owner;
        PyObject localCallback = callback;
        if (localOwner == null || localCallback == null) {
            return;
        }
        PluginRuntimeDelegate.run(
                runtimeToken, () -> localCallback.call(localOwner));
    }

    @Override
    public void clearPluginUiReferences(
            PluginsController.PluginRuntimeToken expectedRuntime) {
        PluginRuntimeDelegate.requireMainThread();
        if (expectedRuntime != null && !runtimeToken.equals(expectedRuntime)) {
            return;
        }
        callback = null;
        owner = null;
        PluginUiRegistry.unregisterRuntimeOwnedUi(runtimeToken, this);
    }
}
