package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public class SelectorSetting extends app.nimarkogram.messenger.plugins.models.SelectorSetting {
    public SelectorSetting(String key, String text, int defaultValue, String[] items, String icon,
                           PyObject onChangeCallback, PyObject onLongClickCallback, String linkAlias) {
        super(key, text, defaultValue, items, icon, onChangeCallback, onLongClickCallback, linkAlias);
    }

    public String getKey() { return key; }
    public void setKey(String v) { key = v; }

    public String getText() { return text; }
    public void setText(String v) { text = v; }

    public int getDefaultValue() { return defaultValue; }
    public void setDefaultValue(int v) { defaultValue = v; }

    public String[] getItems() { return items; }
    public void setItems(String[] v) { items = v; }

    public PyObject getOnChangeCallback() { return onChangeCallback; }
    public void setOnChangeCallback(PyObject v) { onChangeCallback = v; }
}
