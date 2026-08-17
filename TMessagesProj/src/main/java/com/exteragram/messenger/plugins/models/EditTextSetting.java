package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public class EditTextSetting extends app.nimarkogram.messenger.plugins.models.EditTextSetting {
    public EditTextSetting(String key, String hint, String defaultValue, boolean multiline,
                           int maxLength, String mask, PyObject onChangeCallback) {
        super(key, hint, defaultValue, multiline, maxLength, mask, onChangeCallback);
    }

    public String getKey() { return key; }
    public void setKey(String v) { key = v; }

    public String getHint() { return hint; }
    public void setHint(String v) { hint = v; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String v) { defaultValue = v; }

    public boolean getMultiline() { return multiline; }
    public void setMultiline(boolean v) { multiline = v; }

    public int getMaxLength() { return maxLength; }
    public void setMaxLength(int v) { maxLength = v; }

    public String getMask() { return mask; }
    public void setMask(String v) { mask = v; }

    public PyObject getOnChangeCallback() { return onChangeCallback; }
    public void setOnChangeCallback(PyObject v) { onChangeCallback = v; }
}
