package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public class InputSetting extends app.nimarkogram.messenger.plugins.models.InputSetting {
    public InputSetting(String key, String text, String defaultValue, String subtext, String icon,
                        PyObject onChangeCallback, PyObject onLongClickCallback, String linkAlias) {
        super(key, text, defaultValue, subtext, icon, onChangeCallback, onLongClickCallback, linkAlias);
    }

    public String getKey() { return key; }
    public void setKey(String v) { key = v; }

    public String getText() { return text; }
    public void setText(String v) { text = v; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String v) { defaultValue = v; }

    public String getSubtext() { return subtext; }
    public void setSubtext(String v) { subtext = v; }

    public PyObject getOnChangeCallback() { return onChangeCallback; }
    public void setOnChangeCallback(PyObject v) { onChangeCallback = v; }
}
