package app.nimarkogram.messenger.plugins.ui;

import android.view.View;

import com.chaquo.python.PyObject;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.components.templates.PluginRuntimeDelegate;

public final class PluginViewListener implements
        View.OnClickListener,
        View.OnLongClickListener,
        PluginUiRegistry.RuntimeOwnedUi {

    public static final int TYPE_CLICK = 1;
    public static final int TYPE_LONG_CLICK = 2;

    private final int type;
    private final PluginsController.PluginRuntimeToken runtimeToken;
    private PyObject callback;

    public PluginViewListener(
            int type,
            PyObject callback,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (type != TYPE_CLICK && type != TYPE_LONG_CLICK) {
            throw new IllegalArgumentException("Unknown plugin View listener type");
        }
        if (callback == null || runtimeToken == null) {
            throw new IllegalArgumentException(
                    "Plugin View listener requires a callback and exact runtime");
        }
        this.type = type;
        this.callback = callback;
        this.runtimeToken = runtimeToken;
        PluginUiRegistry.trackRuntimeOwnedUi(runtimeToken, this);
    }

    @Override
    public void onClick(View view) {
        if (type != TYPE_CLICK) {
            return;
        }
        PyObject localCallback = callback;
        if (localCallback != null) {
            PluginRuntimeDelegate.run(
                    runtimeToken, () -> localCallback.call(view));
        }
    }

    @Override
    public boolean onLongClick(View view) {
        if (type != TYPE_LONG_CLICK) {
            return false;
        }
        PyObject localCallback = callback;
        if (localCallback == null) {
            return false;
        }
        Boolean result = PluginRuntimeDelegate.call(
                runtimeToken, () -> {
                    PyObject pythonResult = localCallback.call(view);
                    if (pythonResult == null) {
                        return false;
                    }
                    
                    if (pythonResult.toJava(Object.class) == null) {
                        return true;
                    }
                    return pythonResult.toBoolean();
                }, null);
        return result != null && result;
    }

    @Override
    public void clearPluginUiReferences(
            PluginsController.PluginRuntimeToken expectedRuntime) {
        if (!PluginUiRegistry.isMainThread()) {
            throw new IllegalStateException(
                    "Plugin View listener cleanup must run on main");
        }
        if (expectedRuntime != null && !runtimeToken.equals(expectedRuntime)) {
            return;
        }
        callback = null;
        PluginUiRegistry.unregisterRuntimeOwnedUi(runtimeToken, this);
    }
}
