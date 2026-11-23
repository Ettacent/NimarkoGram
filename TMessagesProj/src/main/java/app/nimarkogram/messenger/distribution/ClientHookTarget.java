/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.distribution;

public interface ClientHookTarget extends app.nimarkogram.messenger.plugins.xposed.PluginHookBypassTarget {
    boolean shouldBypassPluginHooks();
}
