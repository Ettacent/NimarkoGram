package app.nimarkogram.messenger.plugins.utils;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
final class PluginDexRegistry {
    private static final int MAX_CLASSES = 32768;
    private final Map<ClassLoader, String> loaders = new WeakHashMap<>();
    private final Map<String, String> classes = new HashMap<>();
    private boolean overflow;
    synchronized void registerLoader(ClassLoader loader, String pluginId) {
        if (loader == null || pluginId == null || pluginId.isEmpty()) return;
        String previous = loaders.get(loader);
        loaders.put(loader, previous == null || previous.equals(pluginId) ? pluginId : "");
    }
    synchronized void registerClass(ClassLoader loader, Class<?> type) {
        if (overflow) return;
        String owner = loaders.get(loader);
        if (owner == null) owner = "";
        if (type == null || type.getClassLoader() != loader) return;
        String name = type.getName();
        String previous = classes.get(name);
        if (previous != null) {
            if (!previous.equals(owner)) classes.put(name, "");
        } else if (classes.size() < MAX_CLASSES) {
            classes.put(name, owner);
        } else {
            overflow = true;
            classes.clear();
        }
    }
    synchronized String findOwner(Throwable error) {
        if (overflow) return null;
        String owner = null;
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
            StackTraceElement[] stack = error.getStackTrace();
            if (stack.length == 0) continue;
            String candidate = classes.get(stack[0].getClassName());
            if (candidate == null) continue;
            if (candidate.isEmpty()) continue;
            if (owner != null && !owner.equals(candidate)) return null;
            owner = candidate;
        }
        return owner;
    }
}
