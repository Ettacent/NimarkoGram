package app.nimarkogram.messenger.plugins.models;

import android.view.View;

import com.chaquo.python.PyObject;

import app.nimarkogram.messenger.plugins.Plugin;

import org.telegram.ui.Components.UItem;

public final class CustomSetting extends SettingItem {
    private volatile PyObject createSubFragmentCallback;
    private Factory<?> factory;
    private volatile PyObject factoryArgs;
    private UItem item;
    private volatile PyObject onClickCallback;
    private volatile PyObject createViewCallback;
    private volatile PyObject bindViewCallback;

    private CustomSetting(PyObject onClickCallback, PyObject createSubFragmentCallback,
                          PyObject onLongClickCallback, String linkAlias) {
        super("custom", null, onLongClickCallback, linkAlias);
        this.onClickCallback = onClickCallback;
        this.createSubFragmentCallback = createSubFragmentCallback;
    }

    public CustomSetting(PyObject createViewCallback, PyObject bindViewCallback,
                         PyObject onClickCallback, PyObject createSubFragmentCallback,
                         PyObject onLongClickCallback, String linkAlias) {
        this(onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.createViewCallback = createViewCallback;
        this.bindViewCallback = bindViewCallback;
    }

    public PyObject getCreateViewCallback() { return createViewCallback; }
    public PyObject getBindViewCallback() { return bindViewCallback; }

    public CustomSetting(UItem item, PyObject onClickCallback, PyObject createSubFragmentCallback,
                         PyObject onLongClickCallback, String linkAlias) {
        this(onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.item = item;
        if (item != null) item.settingItem = this;
    }

    public CustomSetting(Factory<?> factory, PyObject onClickCallback, PyObject createSubFragmentCallback,
                         PyObject onLongClickCallback, String linkAlias) {
        this(onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.factory = factory;
    }

    public CustomSetting(Factory<?> factory, PyObject factoryArgs, PyObject onClickCallback,
                         PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        this(factory, onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.factoryArgs = factoryArgs;
    }

    public CustomSetting(View view, PyObject onClickCallback, PyObject createSubFragmentCallback,
                         PyObject onLongClickCallback, String linkAlias) {
        this(UItem.asCustom(view), onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
    }

    public PyObject getOnClickCallback() { return onClickCallback; }
    public void setOnClickCallback(PyObject v) { this.onClickCallback = v; }

    public PyObject getCreateSubFragmentCallback() { return createSubFragmentCallback; }
    public void setCreateSubFragmentCallback(PyObject v) { this.createSubFragmentCallback = v; }

    public UItem getItem() { return item; }
    public void setItem(UItem v) { this.item = v; }

    public Factory<?> getFactory() { return factory; }
    public void setFactory(Factory<?> v) { this.factory = v; }

    public PyObject getFactoryArgs() { return factoryArgs; }
    public void setFactoryArgs(PyObject v) { this.factoryArgs = v; }

    @Override
    public void clearPythonReferences() {
        createSubFragmentCallback = null;
        onClickCallback = null;
        createViewCallback = null;
        bindViewCallback = null;
        factoryArgs = null;
        factory = null;
        if (item != null && item.settingItem == this) {
            item.settingItem = null;
        }
        item = null;
        super.clearPythonReferences();
    }

    public static abstract class Factory<V extends View> extends UItem.UItemFactory<V> {
        private boolean isClickableValue = true;
        private boolean isShadowValue;

        public UItem create(Plugin plugin, CustomSetting setting, PyObject args) {
            return null;
        }

        @Override
        public boolean isClickable() {
            return isClickableValue;
        }

        public final boolean isClickableValue() { return isClickableValue; }
        public final void setClickableValue(boolean v) { this.isClickableValue = v; }

        @Override
        public boolean isShadow() {
            return isShadowValue;
        }

        public final boolean isShadowValue() { return isShadowValue; }
        public final void setShadowValue(boolean v) { this.isShadowValue = v; }

        public void onClick(Plugin plugin, UItem item, View view) {}
        public void onLongClick(Plugin plugin, UItem item, View view) {}
    }
}
