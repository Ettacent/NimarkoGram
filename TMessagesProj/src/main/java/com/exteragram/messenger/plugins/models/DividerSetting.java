package com.exteragram.messenger.plugins.models;

public class DividerSetting extends app.nimarkogram.messenger.plugins.models.DividerSetting {
    public DividerSetting(String text) {
        super(text);
    }

    public String getText() { return text; }
    public void setText(String v) { text = v; }
}
