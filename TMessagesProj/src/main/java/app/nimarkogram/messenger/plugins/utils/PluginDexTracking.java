package app.nimarkogram.messenger.plugins.utils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import dalvik.system.BaseDexClassLoader;
import org.telegram.messenger.FileLog;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
public final class PluginDexTracking {
    private static final PluginDexRegistry registry = new PluginDexRegistry();
    private static boolean attempted;
    private PluginDexTracking() {}
    public static synchronized void install() {
        if (attempted) return;
        attempted = true;
        List<MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            MethodHook constructorHook = new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame frame) {
                    if (frame.hasThrowable() || !(frame.thisObject instanceof ClassLoader)) return;
                    registry.registerLoader((ClassLoader) frame.thisObject,
                            PluginsWatchdog.currentExecutingPluginId());
                }
            };
            for (Constructor<?> constructor : BaseDexClassLoader.class.getDeclaredConstructors()) {
                hooks.add(Pine.hook(constructor, constructorHook));
            }
            MethodHook classHook = new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame frame) {
                    if (!frame.hasThrowable() && frame.getResult() instanceof Class<?>) {
                        registry.registerClass((ClassLoader) frame.thisObject,
                                (Class<?>) frame.getResult());
                    }
                }
            };
            for (Method method : BaseDexClassLoader.class.getDeclaredMethods()) {
                if (method.getName().equals("findClass") && method.getReturnType() == Class.class) {
                    hooks.add(Pine.hook(method, classHook));
                }
            }
        } catch (Throwable error) {
            for (MethodHook.Unhook hook : hooks) {
                try {
                    hook.unhook();
                } catch (Throwable ignored) {}
            }
            FileLog.e("Plugin DEX attribution unavailable", error);
        }
    }
    public static String findOwner(Throwable error) {
        return registry.findOwner(error);
    }
}
