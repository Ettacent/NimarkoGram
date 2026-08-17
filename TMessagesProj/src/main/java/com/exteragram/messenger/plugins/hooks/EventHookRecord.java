package com.exteragram.messenger.plugins.hooks;

public class EventHookRecord extends app.nimarkogram.messenger.plugins.hooks.EventHookRecord {
    public EventHookRecord(String pluginId, String hookName, boolean matchSubstring, int priority) {
        super(pluginId, hookName, matchSubstring, priority);
    }

    public boolean getMatchSubstring() {
        return isMatchSubstring();
    }
}
