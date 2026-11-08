/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.badges;

public final class BadgePluginOwners {
    private BadgePluginOwners() {}
    public static final class Owner {
        public final String pluginId;
        public final Object runtime;
        public Owner(String pluginId, Object runtime) {
            this.pluginId = pluginId;
            this.runtime = runtime;
        }
    }
    public interface Resolver {
        Owner capture();
        boolean isActive(Owner owner);
    }
    private static volatile Resolver resolver;
    public static void install(Resolver value) { resolver = value; }
    static Owner capture() {
        Resolver value = resolver;
        return value == null ? null : value.capture();
    }
    static boolean isActive(Owner owner) {
        Resolver value = resolver;
        return owner != null && value != null && value.isActive(owner);
    }
}
