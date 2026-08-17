package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public class TextSetting extends app.nimarkogram.messenger.plugins.models.TextSetting {
    public String subtext;

    public TextSetting(String text, String subtext, String icon, boolean accent, boolean red,
                       PyObject onClickCallback, PyObject createSubFragmentCallback,
                       PyObject onLongClickCallback, String linkAlias) {
        super(text, icon, accent, red, onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.subtext = subtext;
    }

    public String getText() { return text; }
    public void setText(String v) { text = v; }

    public String getSubtext() { return subtext; }
    public void setSubtext(String v) { subtext = v; }

    public boolean getAccent() { return accent; }
    public void setAccent(boolean v) { accent = v; }

    public boolean getRed() { return red; }
    public void setRed(boolean v) { red = v; }

    public PyObject getOnClickCallback() { return onClickCallback; }
    public void setOnClickCallback(PyObject v) { onClickCallback = v; }

    public PyObject getCreateSubFragmentCallback() { return createSubFragmentCallback; }
    public void setCreateSubFragmentCallback(PyObject v) { createSubFragmentCallback = v; }
}
