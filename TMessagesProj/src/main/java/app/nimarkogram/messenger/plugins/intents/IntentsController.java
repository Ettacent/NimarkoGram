package app.nimarkogram.messenger.plugins.intents;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.telegram.messenger.FileLog;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.PythonPluginsEngine;

public class IntentsController {
    private static volatile IntentsController instance;

    public static IntentsController getInstance() {
        IntentsController local = instance;
        if (local == null) {
            synchronized (IntentsController.class) {
                local = instance;
                if (local == null) {
                    local = instance = new IntentsController();
                }
            }
        }
        return local;
    }

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<IntentHookRecord>> hooksByPlugin =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IntentHookRecord> byHandlerId = new ConcurrentHashMap<>();
     
    private final ConcurrentHashMap<
            PluginsController.PluginRuntimeToken, AtomicInteger>
            globalCountsByRuntime = new ConcurrentHashMap<>();
     
    private final AtomicInteger legacyGlobalCount = new AtomicInteger(0);

    private IntentsController() {}

    public String addIntentHook(PyObject filterDict) {
        if (filterDict == null) return null;
        IntentHookRecord rec = new IntentHookRecord(filterDict);
        if (TextUtils.isEmpty(rec.pluginId)) return null;
        PluginsController controller = PluginsController.getInstance();
        final String[] result = new String[1];
        boolean committed = controller.runPluginRuntimeMutation(rec.pluginId, (Runnable) () -> {
            hooksByPlugin.computeIfAbsent(rec.pluginId, k -> new CopyOnWriteArrayList<>()).add(rec);
            byHandlerId.put(rec.handlerId, rec);
            result[0] = rec.handlerId;
        });
        if (!committed) {
            FileLog.w("nimarko intents: rejected late handler for inactive plugin " + rec.pluginId);
            return null;
        }
        FileLog.d("nimarko intents: registered handler " + rec.handlerId + " for plugin " + rec.pluginId);
        return result[0];
    }

    public void removeIntentHook(String handlerId) {
        if (TextUtils.isEmpty(handlerId)) return;
        IntentHookRecord rec = byHandlerId.remove(handlerId);
        if (rec == null) return;
        CopyOnWriteArrayList<IntentHookRecord> list = hooksByPlugin.get(rec.pluginId);
        if (list != null) {
            list.remove(rec);
            if (list.isEmpty()) {
                hooksByPlugin.remove(rec.pluginId, list);
            }
        }
    }

    public void removeIntentHooksByPluginId(String pluginId) {
        removeIntentHooksByPluginId(pluginId, null);
    }

    public void removeIntentHooksByPluginId(
            String pluginId, PluginsController.PluginRuntimeToken runtimeToken) {
        if (TextUtils.isEmpty(pluginId)) return;
        CopyOnWriteArrayList<IntentHookRecord> list = hooksByPlugin.get(pluginId);
        int removed = 0;
        if (list != null) {
            for (IntentHookRecord rec : list) {
                boolean owned;
                if (runtimeToken != null) {
                    owned = runtimeToken.equals(rec.runtimeToken);
                } else if (rec.runtimeToken == null) {
                    owned = true;
                } else {
                    
                    owned = !PluginsController.getInstance()
                            .isPluginRuntimeCurrent(rec.runtimeToken);
                }
                if (owned && list.remove(rec)) {
                    byHandlerId.remove(rec.handlerId, rec);
                    removed++;
                }
            }
            if (list.isEmpty()) hooksByPlugin.remove(pluginId, list);
        }
        if (runtimeToken != null) {
            globalCountsByRuntime.remove(runtimeToken);
        } else {
            for (PluginsController.PluginRuntimeToken owner
                    : new ArrayList<>(globalCountsByRuntime.keySet())) {
                if (pluginId.equals(owner.getPluginId())
                        && !PluginsController.getInstance()
                                .isPluginRuntimeCurrent(owner)) {
                    globalCountsByRuntime.remove(owner);
                }
            }
        }
        if (removed > 0) {
            FileLog.d("nimarko intents: removed " + removed
                    + " intent hooks for plugin " + pluginId);
        }
    }

    public void incrementGlobals() {
        incrementGlobals(
                PluginsController.getInstance()
                        .captureCurrentPluginRuntime());
    }

    public void incrementGlobals(
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (runtimeToken == null) {
            legacyGlobalCount.incrementAndGet();
            return;
        }
        if (!PluginsController.getInstance()
                .isPluginRuntimeCurrent(runtimeToken)) {
            return;
        }
        globalCountsByRuntime.compute(runtimeToken, (ignored, count) -> {
            if (count == null) return new AtomicInteger(1);
            count.incrementAndGet();
            return count;
        });
    }

    public void decrementGlobals() {
        decrementGlobals(
                PluginsController.getInstance()
                        .captureCurrentPluginRuntime());
    }

    public void decrementGlobals(
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (runtimeToken == null) {
            legacyGlobalCount.updateAndGet(
                    current -> current > 0 ? current - 1 : 0);
            return;
        }
        globalCountsByRuntime.computeIfPresent(
                runtimeToken, (ignored, count) ->
                        count.decrementAndGet() <= 0 ? null : count);
    }

    private boolean hasWork() {
        return !byHandlerId.isEmpty() || getGlobalCount() > 0;
    }

    private int getGlobalCount() {
        int count = legacyGlobalCount.get();
        for (AtomicInteger owned : globalCountsByRuntime.values()) {
            count += Math.max(0, owned.get());
        }
        return count;
    }

    public boolean dispatchIntent(Intent intent) {
        if (intent == null || !hasWork() || !PluginsController.isPluginEngineAvailable()) return false;
        try {
            return doDispatch(intent);
        } catch (Throwable t) {
            FileLog.e("nimarko intents: dispatch failed", t);
            return false;
        }
    }

    private boolean doDispatch(Intent intent) {
        final String action = intent.getAction();
        final Uri data = intent.getData();
        final String scheme = data != null ? data.getScheme() : null;
        final String host = data != null ? data.getHost() : null;
        final String path = data != null ? data.getPath() : null;
        final String mime = intent.getType();
        final int flags = intent.getFlags();
        final Set<String> cats = intent.getCategories();

        final Map<String, String> queryArgs = new HashMap<>();
        if (data != null && data.isHierarchical()) {
            try {
                for (String q : data.getQueryParameterNames()) {
                    queryArgs.put(q, data.getQueryParameter(q));
                }
            } catch (Exception ignored) {}
        }

        final List<IntentHookRecord> matchedRecords = new ArrayList<>();
        final Map<String, Map<String, String>> pathArgsByHandler = new HashMap<>();
        for (CopyOnWriteArrayList<IntentHookRecord> list : hooksByPlugin.values()) {
            for (IntentHookRecord rec : list) {
                PluginsController controller = PluginsController.getInstance();
                if (rec.runtimeToken != null) {
                    if (controller.getPluginRuntimeTaskDecision(rec.runtimeToken)
                            != PluginsController.RUNTIME_TASK_RUN) {
                        continue;
                    }
                } else if (!isPluginActive(rec.pluginId)) {
                    continue;
                }
                Map<String, String> pa = rec.match(action, scheme, host, path, queryArgs, cats, flags, mime);
                if (pa != null) {
                    matchedRecords.add(rec);
                    pathArgsByHandler.put(rec.handlerId, pa);
                }
            }
        }

        if (matchedRecords.isEmpty() && getGlobalCount() == 0) return false;
        if (!Python.isStarted()
                || PythonPluginsEngine
                        .isProcessPythonRuntimeAbandoned()) {
            return false;
        }

        Collections.sort(matchedRecords, new Comparator<IntentHookRecord>() {
            @Override public int compare(IntentHookRecord a, IntentHookRecord b) {
                return Integer.compare(b.priority, a.priority);
            }
        });

        final List<Map<String, Object>> matched = new ArrayList<>(matchedRecords.size());
        for (IntentHookRecord rec : matchedRecords) {
            Map<String, Object> m = new HashMap<>();
            m.put("handler_id", rec.handlerId);
            m.put("path_args", pathArgsByHandler.get(rec.handlerId));
            matched.add(m);
        }

        PyObject intentsModule = Python.getInstance().getModule("intents");
        PyObject result = intentsModule.callAttr("_dispatch_from_java", intent, matched);
        
        return result != null && result.toBoolean();
    }

    private boolean isPluginActive(String pluginId) {
        try {
            app.nimarkogram.messenger.plugins.Plugin p =
                    PluginsController.getInstance().plugins.get(pluginId);
            return p != null && p.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }
}
