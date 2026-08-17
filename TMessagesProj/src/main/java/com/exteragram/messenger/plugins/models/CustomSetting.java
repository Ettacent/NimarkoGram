package com.exteragram.messenger.plugins.models;

import android.view.View;

import com.chaquo.python.PyObject;

import app.nimarkogram.messenger.plugins.Plugin;

import org.telegram.ui.Components.UItem;

public class CustomSetting extends app.nimarkogram.messenger.plugins.models.SettingItem {
    private PyObject createSubFragmentCallback;
    private Factory<?> factory;
    private PyObject factoryArgs;
    private UItem item;
    private PyObject onClickCallback;

    private CustomSetting(PyObject onClickCallback, PyObject createSubFragmentCallback,
                          PyObject onLongClickCallback, String linkAlias) {
        super("custom", null, onLongClickCallback, linkAlias);
        this.onClickCallback = onClickCallback;
        this.createSubFragmentCallback = createSubFragmentCallback;
    }

    public CustomSetting(UItem item, PyObject onClickCallback, PyObject createSubFragmentCallback,
                         PyObject onLongClickCallback, String linkAlias) {
        this(onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.item = item;
        if (item != null) {
            item.settingItem = this;
        }
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

    public static abstract class Factory<V extends View>
            extends app.nimarkogram.messenger.plugins.models.CustomSetting.Factory<V> {

        public UItem create(Plugin plugin, CustomSetting setting, PyObject args) {
            return null;
        }
    }
}
