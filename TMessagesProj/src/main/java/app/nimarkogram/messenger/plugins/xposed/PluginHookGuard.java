/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.plugins.xposed;

import java.lang.reflect.Member;

import org.telegram.messenger.FileLog;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.utils.PluginsWatchdog;

final class PluginHookGuard {
    private static final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    private PluginHookGuard() {}

    static boolean isActive() {
        return depth.get()[0] != 0;
    }

    static boolean isSupportMethod(Member member) {
        if (member == null) return false;
        for (Class<?> owner = member.getDeclaringClass(); owner != null;
                owner = owner.getEnclosingClass()) {
            if (owner == PluginsController.class || owner == PluginsWatchdog.class
                    || owner == FileLog.class) {
                return true;
            }
        }
        return false;
    }

    static void begin() {
        depth.get()[0]++;
    }

    static void end() {
        int[] state = depth.get();
        if (state[0] <= 0) throw new IllegalStateException("Unbalanced plugin support scope");
        state[0]--;
    }

    static PluginsController controller() {
        begin();
        try { return PluginsController.getInstance(); }
        finally { end(); }
    }

    static PluginsController.PluginRuntimeToken captureRuntime() {
        begin();
        try { return PluginsController.captureInitializedPluginRuntime(); }
        catch (Throwable ignored) { return null; }
        finally { end(); }
    }

    static boolean enterRuntime(PluginsController controller, PluginsController.PluginRuntimeToken token) {
        begin();
        try {
            return controller.getPluginRuntimeTaskDecision(token) == PluginsController.RUNTIME_TASK_RUN
                    && controller.enterPluginRuntime(token);
        } finally { end(); }
    }

    static int decision(PluginsController controller, PluginsController.PluginRuntimeToken token) {
        begin();
        try { return controller.getPluginRuntimeTaskDecision(token); }
        finally { end(); }
    }

    static void exitRuntime(PluginsController controller, PluginsController.PluginRuntimeToken token) {
        begin();
        try { controller.exitPluginRuntime(token); }
        finally { end(); }
    }

    static void started(PluginsController controller, String pluginId) {
        begin();
        try { controller.getWatchdog().onPluginExecutionStarted(pluginId); }
        finally { end(); }
    }

    static void finished(PluginsController controller, String pluginId) {
        begin();
        try { controller.getWatchdog().onPluginExecutionFinished(pluginId); }
        finally { end(); }
    }

    static void failed(PluginsController controller, String pluginId, Throwable failure) {
        begin();
        try { controller.getWatchdog().onPluginExecutionFailed(pluginId, failure); }
        finally { end(); }
    }

    static void error(String message, Throwable failure) {
        begin();
        try { FileLog.e(message, failure); }
        finally { end(); }
    }

    static void error(String message) {
        begin();
        try { FileLog.e(message); }
        finally { end(); }
    }
}
