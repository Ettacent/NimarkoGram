package app.nimarkogram.messenger.plugins.hooks;

import java.util.Objects;

import app.nimarkogram.messenger.plugins.PluginsController;

public class EventHookRecord implements HookRecord {
    private final String hookName;
    private final boolean matchSubstring;
    private final String pluginId;
    private final int priority;
    private final PluginsController.PluginRuntimeToken runtimeToken;

    public EventHookRecord(String pluginId, String hookName, boolean matchSubstring, int priority) {
        this(pluginId, hookName, matchSubstring, priority,
                PluginsController.getInstance().captureCurrentPluginRuntime());
    }

    public EventHookRecord(String pluginId, String hookName, boolean matchSubstring, int priority,
                           PluginsController.PluginRuntimeToken runtimeToken) {
        this.pluginId = pluginId;
        this.hookName = hookName;
        this.matchSubstring = matchSubstring;
        this.priority = priority;
        this.runtimeToken = runtimeToken;
    }

    public String getPluginId() {
        return this.pluginId;
    }

    public String getHookName() {
        return this.hookName;
    }

    public int getPriority() {
        return this.priority;
    }

    public boolean isMatchSubstring() {
        return this.matchSubstring;
    }

    @Override
    public PluginsController.PluginRuntimeToken getRuntimeToken() {
        return runtimeToken;
    }

    @Override
    public void cleanup() {}

    @Override
    public boolean matches(Object obj) {
        if (obj instanceof String) {
            String str = (String) obj;
            if (this.hookName != null) {
                if (this.matchSubstring) {
                    return !this.hookName.isEmpty() && str.contains(this.hookName);
                }
                return this.hookName.equals(str);
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj != null && getClass() == obj.getClass()) {
            EventHookRecord other = (EventHookRecord) obj;
            return this.matchSubstring == other.matchSubstring &&
                    Objects.equals(this.pluginId, other.pluginId) &&
                    Objects.equals(this.hookName, other.hookName) &&
                    Objects.equals(this.runtimeToken, other.runtimeToken);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.pluginId, this.hookName, this.matchSubstring,
                this.runtimeToken);
    }
}
