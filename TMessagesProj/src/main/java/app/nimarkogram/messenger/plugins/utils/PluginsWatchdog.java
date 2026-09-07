package app.nimarkogram.messenger.plugins.utils;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import app.nimarkogram.messenger.plugins.Plugin;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

public final class PluginsWatchdog {

    public static final PluginsWatchdog INSTANCE_HOLDER = null; 

    private static volatile PluginsWatchdog activeWatchdog;
    private final PluginsController controller;
    private final Map<Thread, ExecutionInfo> executingPlugins =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Thread, CrashSnapshot> callbackFailures =
            Collections.synchronizedMap(new WeakHashMap<>());
    private volatile String lastReportedFrozenPluginId;
    private volatile PluginsController.PluginRuntimeToken lastReportedFrozenRuntime;
    private volatile ScheduledExecutorService scheduler;
    private final Runnable watchdogRunnable;
     
    private volatile boolean wasBackgrounded;
     
    private volatile long foregroundResumedAtMs;
    private volatile WeakReference<AlertDialog> visibleAlert = new WeakReference<>(null);
    private volatile String visibleAlertPluginId;
    private volatile PluginsController.PluginRuntimeToken visibleAlertRuntime;

    public PluginsWatchdog(PluginsController controller) {
        this.controller = controller;
        this.watchdogRunnable = this::tick;
        activeWatchdog = this;
    }

    public static String findCrashingPlugin(Thread thread, Throwable error) {
        PluginsWatchdog active = activeWatchdog;
        return active != null ? active.getCrashingPluginId(thread, error) : null;
    }

    public static Plugin getKnownPlugin(String id) {
        PluginsWatchdog active = activeWatchdog;
        return active != null && active.controller != null && id != null
                ? active.controller.plugins.get(id) : null;
    }

    private void tick() {
        try {
            
            if (org.telegram.messenger.ApplicationLoader.mainInterfacePaused) {
                wasBackgrounded = true;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (wasBackgrounded) {
                wasBackgrounded = false;
                foregroundResumedAtMs = now;
            }
            long worst = 0L;
            ExecutionInfo frozenInfo = null;
            Map<Thread, ExecutionInfo> executions;
            synchronized (executingPlugins) {
                executions = new HashMap<>(executingPlugins);
            }
            for (Map.Entry<Thread, ExecutionInfo> entry : executions.entrySet()) {
                Thread thread = entry.getKey();
                ExecutionInfo info = entry.getValue();
                if (info.runtimeToken != null
                        && !controller.isPluginRuntimeExecuting(
                                info.runtimeToken)) {
                    executingPlugins.remove(thread, info);
                    continue;
                }
                long elapsed = now - Math.max(info.getStartTime(), foregroundResumedAtMs);
                if (elapsed > 5000L && elapsed > worst) {
                    frozenInfo = info;
                    worst = elapsed;
                }
            }
            String prev = lastReportedFrozenPluginId;
            PluginsController.PluginRuntimeToken prevRuntime =
                    lastReportedFrozenRuntime;
            if (frozenInfo != null) {
                String frozen = frozenInfo.getPluginId();
                if (Objects.equals(frozen, prev)
                        && Objects.equals(frozenInfo.runtimeToken, prevRuntime)) {
                    return;
                }
                lastReportedFrozenPluginId = frozen;
                lastReportedFrozenRuntime = frozenInfo.runtimeToken;
                Plugin plugin = controller.plugins.get(frozen);
                if (plugin != null) plugin.setNotResponding(true);
                FileLog.w("nimarko: PluginsWatchdog detected stuck plugin id=" + frozen + " elapsedMs=" + worst);
                NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginIsNotResponding);
                
                if (plugin != null) {
                    final Plugin frozenPlugin = plugin;
                    final PluginsController.PluginRuntimeToken frozenRuntime =
                            frozenInfo.runtimeToken;
                    AndroidUtilities.runOnUIThread(() ->
                            showNotRespondingAlertInternal(
                                    frozenPlugin, frozenRuntime));
                }
                return;
            }
            if (prev != null) {
                Plugin plugin = controller.plugins.get(prev);
                if (plugin != null) plugin.setNotResponding(false);
                lastReportedFrozenPluginId = null;
                lastReportedFrozenRuntime = null;
                NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginIsNotResponding);
                dismissStaleAlert(prev);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static final class ExecutionInfo {
        private final String pluginId;
        private final PluginsController.PluginRuntimeToken runtimeToken;
        private final long startTime;
         
        final ExecutionInfo prev;

        public ExecutionInfo(String pluginId, long startTime) {
            this(pluginId, null, startTime, null);
        }

        public ExecutionInfo(
                String pluginId,
                PluginsController.PluginRuntimeToken runtimeToken,
                long startTime, ExecutionInfo prev) {
            this.pluginId = pluginId;
            this.runtimeToken = runtimeToken;
            this.startTime = startTime;
            this.prev = prev;
        }

        public String getPluginId() { return pluginId; }
        public long getStartTime() { return startTime; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ExecutionInfo)) return false;
            ExecutionInfo other = (ExecutionInfo) o;
            return Objects.equals(pluginId, other.pluginId)
                    && Objects.equals(runtimeToken, other.runtimeToken)
                    && startTime == other.startTime;
        }
        @Override public int hashCode() {
            return Objects.hash(pluginId, runtimeToken, startTime);
        }
        @Override public String toString() {
            return "ExecutionInfo(pluginId=" + pluginId + ", startTime=" + startTime + ')';
        }
    }

    private static final class CrashSnapshot {
        private static final int MAX_CAUSES = 16;
        final String pluginId;
        final List<WeakReference<Throwable>> throwables = new ArrayList<>();
        CrashSnapshot(String pluginId, Throwable throwable) {
            this.pluginId = pluginId;
            for (int depth = 0; throwable != null && depth < MAX_CAUSES;
                    depth++, throwable = throwable.getCause()) {
                throwables.add(new WeakReference<>(throwable));
            }
        }

        boolean matches(Throwable throwable) {
            for (int depth = 0; throwable != null && depth < MAX_CAUSES;
                    depth++, throwable = throwable.getCause()) {
                for (WeakReference<Throwable> reference : throwables) {
                    if (reference.get() == throwable) return true;
                }
            }
            return false;
        }
    }

    public void start() {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) {
            ScheduledExecutorService ns = Executors.newSingleThreadScheduledExecutor();
            ns.scheduleWithFixedDelay(watchdogRunnable, 1L, 1L, TimeUnit.SECONDS);
            scheduler = ns;
        }
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
        scheduler = null;
        lastReportedFrozenPluginId = null;
        lastReportedFrozenRuntime = null;
        wasBackgrounded = false;
        foregroundResumedAtMs = 0L;
        executingPlugins.clear();
        callbackFailures.clear();
        dismissStaleAlert(null);
    }

    public void onPluginExecutionStarted(String pluginId) {
        if (pluginId == null) return;
        Thread t = Thread.currentThread();
        
        ExecutionInfo previous = executingPlugins.get(t);
        if (previous == null) callbackFailures.remove(t);
        executingPlugins.put(t, new ExecutionInfo(
                pluginId, controller.captureCurrentPluginRuntime(),
                SystemClock.elapsedRealtime(), previous));
    }

    public void onPluginExecutionFailed(String pluginId, Throwable throwable) {
        onPluginExecutionFailed(pluginId, throwable, "callback");
    }

    public void onPluginExecutionFailed(String pluginId, Throwable throwable, String phase) {
        if (pluginId == null || throwable == null) return;
        Thread thread = Thread.currentThread();
        CrashSnapshot previous = callbackFailures.get(thread);

        if (previous != null && previous.matches(throwable)) return;
        callbackFailures.put(thread, new CrashSnapshot(pluginId, throwable));
        try {

            PluginCrashReports.recordFailure(pluginId, phase, throwable);
        } catch (Throwable ignored) {
        }
    }

    public void onPluginExecutionFinished(String pluginId) {
        Thread t = Thread.currentThread();
        ExecutionInfo info = executingPlugins.get(t);
        if (info == null || !Objects.equals(info.getPluginId(), pluginId)) return;
        if (info.prev != null) {
            executingPlugins.put(t, info.prev);
        } else {
            executingPlugins.remove(t);
        }
        if (Objects.equals(pluginId, lastReportedFrozenPluginId)
                && Objects.equals(info.runtimeToken, lastReportedFrozenRuntime)) {
            Plugin plugin = controller.plugins.get(pluginId);
            if (plugin != null) plugin.setNotResponding(false);
            lastReportedFrozenPluginId = null;
            lastReportedFrozenRuntime = null;
            NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginIsNotResponding);
            dismissStaleAlert(pluginId);
        }
    }

    public String getExecutingPluginId(Thread thread) {
        if (thread == null) return null;
        ExecutionInfo info = executingPlugins.get(thread);
        return info != null ? info.getPluginId() : null;
    }

    public String getCrashingPluginId(Thread thread, Throwable uncaught) {
        if (thread == null || uncaught == null) return null;
        CrashSnapshot snapshot = callbackFailures.get(thread);
        if (snapshot != null && snapshot.matches(uncaught)) return snapshot.pluginId;
        ExecutionInfo active = executingPlugins.get(thread);
        return active != null ? active.getPluginId() : null;
    }

    private void dismissStaleAlert(String pluginId) {
        if (pluginId != null && !Objects.equals(pluginId, visibleAlertPluginId)) return;
        AlertDialog dialog = visibleAlert.get();
        visibleAlert = new WeakReference<>(null);
        visibleAlertPluginId = null;
        visibleAlertRuntime = null;
        if (dialog != null) {
            
            PluginUiRegistry.dismissDialog(dialog);
        }
    }

    public void forceDisablePlugin(String pluginId, Activity activity) {
        if (controller.forceDisablePluginDurably(pluginId)) {
            restartApp(activity);
        } else {
            FileLog.e("Frozen plugin could not be disabled durably: "
                    + pluginId);
        }
    }

    public void forceDeletePlugin(String pluginId, Activity activity) {
        if (!controller.forceDeletePluginDurably(pluginId)) {
            FileLog.e("Frozen plugin deletion could not be made durable: "
                    + pluginId);
            return;
        }
        restartApp(activity);
    }

    private void restartApp(final Activity activity) {
        AndroidUtilities.runOnUIThread(() -> doRestart(activity), 200L);
    }

    private static void doRestart(Activity activity) {
        try {
            if (activity != null) {
                PackageManager pm = activity.getPackageManager();
                Intent launch = pm.getLaunchIntentForPackage(activity.getPackageName());
                activity.finishAffinity();
                if (launch != null) activity.startActivity(launch);
            }
        } catch (Throwable ignored) {}
        System.exit(0);
    }

    public static void showNotRespondingAlert(final Plugin plugin) {
        PluginsController controller = PluginsController.getInstance();
        PluginsWatchdog watchdog = controller.getWatchdog();
        PluginsController.PluginRuntimeToken runtimeToken = null;
        if (plugin != null) {
            runtimeToken = Objects.equals(
                    plugin.getId(), watchdog.lastReportedFrozenPluginId)
                    ? watchdog.lastReportedFrozenRuntime
                    : controller.getCurrentPluginRuntime(plugin.getId());
        }
        watchdog.showNotRespondingAlertInternal(plugin, runtimeToken);
    }

    private void showNotRespondingAlertInternal(
            final Plugin plugin,
            final PluginsController.PluginRuntimeToken runtimeToken) {
        if (plugin == null) return;
        if (!plugin.isNotResponding()
                || !Objects.equals(plugin.getId(), lastReportedFrozenPluginId)
                || !Objects.equals(runtimeToken, lastReportedFrozenRuntime)
                || !isRuntimeStillFrozenOwner(plugin.getId(), runtimeToken)) {
            return;
        }
        BaseFragment last = LaunchActivity.getLastFragment();
        if (last == null) return;
        final Activity parent = last.getParentActivity();
        if (parent == null || parent.isFinishing() || parent.isDestroyed()) return;
        dismissStaleAlert(null);
        AlertDialog dlg = new AlertDialog.Builder(parent, last.getResourceProvider())
                .setTitle(LocaleController.formatString(R.string.PluginIsNotRespondingAlert, plugin.getName()))
                .setItems(
                        new CharSequence[] {
                                LocaleController.getString(R.string.WaitMore),
                                LocaleController.getString(R.string.Disable),
                                LocaleController.getString(R.string.Delete)
                        },
                        new int[] { R.drawable.msg_recent, R.drawable.msg_block, R.drawable.msg_delete },
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (!isRuntimeStillFrozenOwner(
                                        plugin.getId(), runtimeToken)) {
                                    dialog.dismiss();
                                    return;
                                }
                                if (which == 1) {
                                    PluginsController.getInstance().getWatchdog().forceDisablePlugin(plugin.getId(), parent);
                                } else if (which == 2) {
                                    PluginsController.getInstance().getWatchdog().forceDeletePlugin(plugin.getId(), parent);
                                }
                            }
                        }
                ).create();
        visibleAlertPluginId = plugin.getId();
        visibleAlertRuntime = runtimeToken;
        visibleAlert = new WeakReference<>(dlg);
        dlg.setOnDismissListener(dialog -> {
            if (visibleAlert.get() == dlg) {
                visibleAlert = new WeakReference<>(null);
                visibleAlertPluginId = null;
                visibleAlertRuntime = null;
            }
        });
        if (!plugin.isNotResponding()
                || !Objects.equals(plugin.getId(), lastReportedFrozenPluginId)
                || !Objects.equals(runtimeToken, lastReportedFrozenRuntime)
                || parent.isFinishing() || parent.isDestroyed()
                || !isRuntimeStillFrozenOwner(plugin.getId(), runtimeToken)) {
            dismissStaleAlert(plugin.getId());
            return;
        }
        try {
            dlg.show();
        } catch (Throwable showFailure) {
            FileLog.e("nimarko: unable to show plugin watchdog dialog", showFailure);
            dismissStaleAlert(plugin.getId());
            return;
        }
        try {
            dlg.setItemColor(dlg.getItemsCount() - 1, Theme.getColor(Theme.key_text_RedBold), Theme.getColor(Theme.key_text_RedRegular));
        } catch (Throwable ignored) {}
    }

    private boolean isRuntimeStillFrozenOwner(
            String pluginId, PluginsController.PluginRuntimeToken runtimeToken) {
        for (ExecutionInfo info : executingPlugins.values()) {
            if (!Objects.equals(pluginId, info.pluginId)
                    || !Objects.equals(runtimeToken, info.runtimeToken)) {
                continue;
            }
            if (runtimeToken == null
                    || controller.isPluginRuntimeExecuting(runtimeToken)) {
                return true;
            }
        }
        return false;
    }
}
