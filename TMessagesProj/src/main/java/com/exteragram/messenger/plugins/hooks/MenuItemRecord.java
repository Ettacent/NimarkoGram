package com.exteragram.messenger.plugins.hooks;

public class MenuItemRecord extends app.nimarkogram.messenger.plugins.hooks.MenuItemRecord {

    public MenuItemRecord(String pluginId, com.chaquo.python.PyObject pyData) {
        super(pluginId, pyData);
    }

    public String getPluginId() {
        return this.pluginId;
    }

    public String getItemId() {
        return this.itemId;
    }

    public String getMenuType() {
        return this.menuType;
    }

    public String getText() {
        return this.text;
    }

    public com.chaquo.python.PyObject getOnClickCallback() {
        return this.onClickCallback;
    }

    public String getIconName() {
        return this.iconName;
    }

    public int getIconResId() {
        return this.iconResId;
    }

    public String getSubtext() {
        return this.subtext;
    }

    public String getConditionString() {
        return this.conditionString;
    }

    public int getPriority() {
        return this.priority;
    }
}
