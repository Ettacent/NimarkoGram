package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public class SettingItem extends app.nimarkogram.messenger.plugins.models.SettingItem {
    public SettingItem(String type, String icon, PyObject onLongClickCallback, String linkAlias) {
        super(type, icon, onLongClickCallback, linkAlias);
    }

    public String getType() { return this.type; }
    public void setType(String v) { this.type = v; }

    public String getIcon() { return this.icon; }
    public void setIcon(String v) { this.icon = v; }

    public PyObject getOnLongClickCallback() { return this.onLongClickCallback; }
    public void setOnLongClickCallback(PyObject v) { this.onLongClickCallback = v; }

    public String getLinkAlias() { return this.linkAlias; }
    public void setLinkAlias(String v) { this.linkAlias = v; }
}
