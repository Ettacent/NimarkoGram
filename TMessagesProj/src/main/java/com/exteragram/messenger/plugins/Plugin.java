package com.exteragram.messenger.plugins;

public class Plugin extends app.nimarkogram.messenger.plugins.Plugin {
    public Plugin(String id, String name) {
        super(id, name);
    }

    public void setRequirements(java.util.List<String> requirements) {
        if (requirements == null) {
            setRequirements((String) null);
            return;
        }
        setRequirements(String.join("\n", requirements));
    }

    public static Plugin fromReal(app.nimarkogram.messenger.plugins.Plugin real) {
        if (real == null) {
            return null;
        }
        if (real instanceof Plugin) {
            return (Plugin) real;
        }
        Plugin bridge = new Plugin(real.getId(), real.getName());
        bridge.setDescription(real.getDescription());
        bridge.setAuthor(real.getAuthor());
        bridge.setEngine(real.getEngine());
        bridge.setVersion(real.getVersion());
        bridge.setMinVersion(real.getMinVersion());
        bridge.setRequirements(real.getRequirementsRaw());
        bridge.setIcon(real.getIcon());
        bridge.setEnabled(real.isEnabled());
        bridge.setNotResponding(real.isNotResponding());
        bridge.setCachedEngine(real.getCachedEngine());
        if (real.getError() != null) {
            bridge.setError(real.getError());
        }
        return bridge;
    }
}
