package app.nimarkogram.messenger.plugins;

import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import com.chaquo.python.PyObject;
import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.plugins.hooks.EventHookRecord;
import app.nimarkogram.messenger.plugins.hooks.HookRecord;
import app.nimarkogram.messenger.plugins.hooks.MenuItemRecord;
import app.nimarkogram.messenger.plugins.hooks.PluginsHooks;
import app.nimarkogram.messenger.plugins.hooks.XposedHookRecord;
import app.nimarkogram.messenger.plugins.models.SettingItem;
import app.nimarkogram.messenger.plugins.ui.PluginsActivity;
import app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet;
import app.nimarkogram.messenger.plugins.ui.components.SafeModeBottomSheet;
import app.nimarkogram.messenger.plugins.utils.ClassProxy;
import app.nimarkogram.messenger.plugins.utils.MenuContextBuilder;
import app.nimarkogram.messenger.plugins.utils.NativeCrashHandler;
import app.nimarkogram.messenger.utils.chats.ChatUtils;
import de.robv.android.xposed.XC_MethodHook;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

public class PluginsController implements PluginsHooks {
    public static final String PREF_PLUGIN_ENABLED_KEY_PREFIX = "plugin_enabled_";
    private static final long ENGINE_INIT_TIMEOUT_MS = 90_000L;
    private static final long ENGINE_SHUTDOWN_TIMEOUT_MS = 30_000L;
    public static final ConcurrentHashMap<String, PluginsEngine> engines = new ConcurrentHashMap<>();
    private static final DispatchQueue pluginRegistryCleanupQueue =
            new DispatchQueue("pluginRegistryCleanupQueue");
    private final app.nimarkogram.messenger.plugins.utils.PluginsWatchdog watchdog =
            new app.nimarkogram.messenger.plugins.utils.PluginsWatchdog(this);
    private final Object controllerLifecycleLock = new Object();
    private final ArrayList<Runnable> shutdownCompletionCallbacks =
            new ArrayList<>();
    private final ArrayList<Runnable> initializationCompletionCallbacks =
            new ArrayList<>();
    private boolean shutdownInProgress;
    private boolean initializationInProgress;
    private long initializationAttempt;
    private boolean shutdownRequiresProcessRestart;
    private final AtomicLong controllerLifecycleEpoch =
            new AtomicLong(1L);

    public app.nimarkogram.messenger.plugins.utils.PluginsWatchdog getWatchdog() { return watchdog; }

    static {
        
        engines.put(PluginsConstants.PYTHON, new com.exteragram.messenger.plugins.PythonPluginsEngine());
    }

    private volatile Map<String, List<EventHookRecord>> exactMatchEventHooksCache;
    public File pluginsDir;
    private volatile List<EventHookRecord> substringMatchEventHooksCache;
    public final ConcurrentHashMap<String, Plugin> plugins = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<SettingItem>> settings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PluginRuntimeToken> settingsRuntimeTokens =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MenuItemRecord> menuItemsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MenuItemRecord>> menuItemsByMenuType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<HookRecord>> hooks = new ConcurrentHashMap<>();
    private static final class InterestedPlugin {
        final String pluginId;
        final PluginRuntimeToken runtimeToken;

        InterestedPlugin(String pluginId, PluginRuntimeToken runtimeToken) {
            this.pluginId = pluginId;
            this.runtimeToken = runtimeToken;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof InterestedPlugin)) return false;
            InterestedPlugin that = (InterestedPlugin) other;
            return java.util.Objects.equals(pluginId, that.pluginId)
                    && java.util.Objects.equals(runtimeToken, that.runtimeToken);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(pluginId, runtimeToken);
        }
    }

    private final ConcurrentHashMap<String, List<InterestedPlugin>>
            interestedPluginsCache = new ConcurrentHashMap<>();
    private final AtomicLong interestedPluginsRevision = new AtomicLong();
    private final Object hooksCacheLock = new Object();
    private volatile boolean hooksCacheDirty = true;
    public SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0);
    
    private final java.util.Set<String> enablingInProgress =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
     
    private final java.util.Set<String> startupActivations =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    public static final int RUNTIME_TASK_DROP = 0;
    public static final int RUNTIME_TASK_WAIT = 1;
    public static final int RUNTIME_TASK_RUN = 2;

    public static final class PluginRuntimeToken {
        private final String pluginId;
        private final int generation;
        private final long instanceId;

        PluginRuntimeToken(String pluginId, int generation, long instanceId) {
            this.pluginId = pluginId;
            this.generation = generation;
            this.instanceId = instanceId;
        }

        public String getPluginId() {
            return pluginId;
        }

        public int getGeneration() {
            return generation;
        }

        public long getInstanceId() {
            return instanceId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PluginRuntimeToken)) return false;
            PluginRuntimeToken token = (PluginRuntimeToken) other;
            return generation == token.generation
                    && instanceId == token.instanceId
                    && java.util.Objects.equals(pluginId, token.pluginId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(pluginId, generation, instanceId);
        }

        @Override
        public String toString() {
            return pluginId + '@' + generation + '#' + instanceId;
        }
    }

    private enum RuntimeState {
        PREPARING,
        ACTIVE,
        REVOKED
    }

    private static final class RuntimeSlot {
        final PluginRuntimeToken token;
        volatile RuntimeState state;
        int activeCalls;
        final ArrayList<Runnable> quiescenceListeners = new ArrayList<>();
        final ArrayList<RuntimeCallbackHolder> callbackHolders =
                new ArrayList<>();
        final ArrayList<RuntimeCallbackHolder> preparingCallbacks =
                new ArrayList<>();

        RuntimeSlot(PluginRuntimeToken token) {
            this.token = token;
            this.state = RuntimeState.PREPARING;
        }
    }

    public interface RuntimeCallbackHolder {
        void onPluginRuntimeActive();
        void revokePluginRuntime();
    }

    private final AtomicLong nextRuntimeInstanceId = new AtomicLong();
    private final ConcurrentHashMap<String, RuntimeSlot> currentRuntimeByPlugin =
            new ConcurrentHashMap<>();
     
    private final ConcurrentHashMap<PluginRuntimeToken, RuntimeSlot> runtimeSlotsByToken =
            new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<PluginRuntimeToken>> runtimeScopes =
            ThreadLocal.withInitial(ArrayDeque::new);

    public static final class PluginInitializationToken {
        final String pluginId;
        final int generation;

        PluginInitializationToken(String pluginId, int generation) {
            this.pluginId = pluginId;
            this.generation = generation;
        }

        public String getPluginId() {
            return pluginId;
        }

        public int getGeneration() {
            return generation;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PluginInitializationToken)) return false;
            PluginInitializationToken token = (PluginInitializationToken) other;
            return generation == token.generation
                    && java.util.Objects.equals(pluginId, token.pluginId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(pluginId, generation);
        }
    }
     
    private final ThreadLocal<PluginInitializationToken> initializationPermit = new ThreadLocal<>();
     
    private final ThreadLocal<PluginRuntimeToken> pluginUnloadPermit =
            new ThreadLocal<>();
     
    private final ConcurrentHashMap<PluginInitializationToken, AtomicInteger>
            activePluginInitializations = new ConcurrentHashMap<>();
    
    private final ConcurrentHashMap<String, Boolean> pendingToggleState =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<PendingToggleCallback>>
            pendingToggleCallbacks =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> toggleGenerations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> toggleGenerationLocks = new ConcurrentHashMap<>();
     
    private final ConcurrentHashMap<String, Integer> runtimeEpochs = new ConcurrentHashMap<>();

    private static final class PendingToggleCallback {
        final boolean requestedState;
        final int generation;
        final long lifecycleEpoch;
        final Utilities.Callback<String> callback;

        PendingToggleCallback(
                boolean requestedState, int generation,
                long lifecycleEpoch,
                Utilities.Callback<String> callback) {
            this.requestedState = requestedState;
            this.generation = generation;
            this.lifecycleEpoch = lifecycleEpoch;
            this.callback = callback;
        }
    }

    private static final class PluginCleanup {
        final String pluginId;
         
        final PluginRuntimeToken runtimeToken;
         
        final PluginRuntimeToken quiescenceToken;
        final List<HookRecord> detachedHooks;
        final boolean settingsDetached;
        final boolean menuItemsDetached;
        final boolean cleanPythonRegistries;

        PluginCleanup(String pluginId, List<HookRecord> detachedHooks,
                      boolean settingsDetached, boolean menuItemsDetached,
                      boolean cleanPythonRegistries) {
            this(pluginId, null, null, detachedHooks, settingsDetached, menuItemsDetached,
                    cleanPythonRegistries);
        }

        PluginCleanup(String pluginId, PluginRuntimeToken runtimeToken,
                      List<HookRecord> detachedHooks, boolean settingsDetached,
                      boolean menuItemsDetached, boolean cleanPythonRegistries) {
            this(pluginId, runtimeToken, runtimeToken, detachedHooks,
                    settingsDetached, menuItemsDetached, cleanPythonRegistries);
        }

        PluginCleanup(String pluginId, PluginRuntimeToken runtimeToken,
                      PluginRuntimeToken quiescenceToken,
                      List<HookRecord> detachedHooks, boolean settingsDetached,
                      boolean menuItemsDetached, boolean cleanPythonRegistries) {
            this.pluginId = pluginId;
            this.runtimeToken = runtimeToken;
            this.quiescenceToken = quiescenceToken;
            this.detachedHooks = detachedHooks;
            this.settingsDetached = settingsDetached;
            this.menuItemsDetached = menuItemsDetached;
            this.cleanPythonRegistries = cleanPythonRegistries;
        }
    }

    public boolean isEnablingInProgress(String pluginId) {
        return pluginId != null
                && (enablingInProgress.contains(pluginId)
                || startupActivations.contains(pluginId));
    }

    void setPluginStartupActivationPending(
            String pluginId, boolean pending) {
        if (TextUtils.isEmpty(pluginId)) return;
        if (pending) {
            startupActivations.add(pluginId);
        } else {
            startupActivations.remove(pluginId);
        }
    }

    void clearPluginStartupActivations() {
        startupActivations.clear();
    }

    public boolean isTogglingInProgress(String pluginId) {
        return pluginId != null && pendingToggleState.containsKey(pluginId);
    }

    public long getControllerLifecycleEpoch() {
        return controllerLifecycleEpoch.get();
    }

    public boolean isControllerShuttingDown() {
        synchronized (controllerLifecycleLock) {
            return shutdownInProgress;
        }
    }

    private boolean isControllerLifecycleCurrent(long epoch) {
        synchronized (controllerLifecycleLock) {
            return !shutdownInProgress
                    && controllerLifecycleEpoch.get() == epoch;
        }
    }

    public int getPluginToggleGeneration(String pluginId) {
        return TextUtils.isEmpty(pluginId) ? 0 : toggleGenerations.getOrDefault(pluginId, 0);
    }

    private Object generationLock(String pluginId) {
        return toggleGenerationLocks.computeIfAbsent(pluginId, ignored -> new Object());
    }

    private int nextToggleGeneration(String pluginId) {
        synchronized (generationLock(pluginId)) {
            int next = toggleGenerations.getOrDefault(pluginId, 0) + 1;
            toggleGenerations.put(pluginId, next);
            return next;
        }
    }

    public PluginRuntimeToken preparePluginRuntime(String pluginId, int generation) {
        if (TextUtils.isEmpty(pluginId)) return null;
        synchronized (generationLock(pluginId)) {
            if (!isPluginEnableRequestedLocked(pluginId, generation)) {
                PluginDebugLog.log("RUNTIME prepare rejected plugin="
                        + pluginId + " generation=" + generation
                        + " currentGeneration="
                        + toggleGenerations.getOrDefault(pluginId, 0)
                        + " requested="
                        + pendingToggleState.get(pluginId));
                return null;
            }
            RuntimeSlot previous = currentRuntimeByPlugin.get(pluginId);
            if (previous != null) {
                PluginDebugLog.log("RUNTIME prepare revoking previous="
                        + previous.token + " state=" + previous.state
                        + " activeCalls=" + previous.activeCalls);
                revokeRuntimeSlotLocked(previous);
            }
            PluginRuntimeToken token = new PluginRuntimeToken(
                    pluginId, generation, nextRuntimeInstanceId.incrementAndGet());
            RuntimeSlot slot = new RuntimeSlot(token);
            currentRuntimeByPlugin.put(pluginId, slot);
            runtimeSlotsByToken.put(token, slot);
            PluginDebugLog.log("RUNTIME prepared token=" + token);
            return token;
        }
    }

    public PluginRuntimeToken getCurrentPluginRuntime(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) return null;
        RuntimeSlot slot = currentRuntimeByPlugin.get(pluginId);
        return slot != null && slot.state != RuntimeState.REVOKED ? slot.token : null;
    }

    public PluginRuntimeToken captureCurrentPluginRuntime() {
        Deque<PluginRuntimeToken> stack = runtimeScopes.get();
        PluginRuntimeToken scoped = stack.peek();
        if (scoped != null) return scoped;
        PluginRuntimeToken unloading = pluginUnloadPermit.get();
        if (unloading != null) return unloading;

        PluginInitializationToken initialization = initializationPermit.get();
        if (initialization == null) return null;
        RuntimeSlot slot = currentRuntimeByPlugin.get(initialization.pluginId);
        if (slot == null || slot.state == RuntimeState.REVOKED
                || slot.token.generation != initialization.generation) {
            return null;
        }
        return slot.token;
    }

    public boolean enterPluginRuntime(PluginRuntimeToken token) {
        if (token == null) return false;
        synchronized (generationLock(token.pluginId)) {
            boolean unloading = token.equals(pluginUnloadPermit.get());
            if (unloading) {
                RuntimeSlot oldSlot = runtimeSlotsByToken.get(token);
                if (oldSlot == null) {
                    return false;
                }
                oldSlot.activeCalls++;
                runtimeScopes.get().push(token);
                return true;
            }
            if (NimarkoConfig.pluginsSafeMode) return false;
            RuntimeSlot slot = currentRuntimeByPlugin.get(token.pluginId);
            if (slot == null || slot.state == RuntimeState.REVOKED
                    || !slot.token.equals(token)
                    || runtimeSlotsByToken.get(token) != slot
                    || toggleGenerations.getOrDefault(token.pluginId, 0) != token.generation) {
                return false;
            }
            if (slot.state == RuntimeState.PREPARING) {
                PluginInitializationToken permit = initializationPermit.get();
                if (permit == null
                        || permit.generation != token.generation
                        || !java.util.Objects.equals(
                                permit.pluginId, token.pluginId)
                        || !isPluginEnableRequestedLocked(
                                token.pluginId, token.generation)) {
                    return false;
                }
            }
            slot.activeCalls++;
            runtimeScopes.get().push(token);
            return true;
        }
    }

    public void exitPluginRuntime(PluginRuntimeToken token) {
        if (token == null) return;
        List<Runnable> listeners = Collections.emptyList();
        Deque<PluginRuntimeToken> stack = runtimeScopes.get();
        if (!stack.isEmpty() && token.equals(stack.peek())) {
            stack.pop();
        } else {
            stack.removeFirstOccurrence(token);
        }
        if (stack.isEmpty()) {
            runtimeScopes.remove();
        }
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = runtimeSlotsByToken.get(token);
            if (slot != null && slot.activeCalls > 0) {
                slot.activeCalls--;
                if (slot.activeCalls == 0 && !slot.quiescenceListeners.isEmpty()) {
                    listeners = new ArrayList<>(slot.quiescenceListeners);
                    slot.quiescenceListeners.clear();
                }
            }
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                FileLog.e("Error completing plugin runtime quiescence for " + token, t);
            }
        }
    }

    public void runWhenPluginRuntimeQuiescent(
            PluginRuntimeToken token, Runnable listener) {
        if (listener == null) return;
        if (token == null) {
            listener.run();
            return;
        }
        boolean runNow;
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = runtimeSlotsByToken.get(token);
            runNow = slot == null || slot.activeCalls == 0;
            if (!runNow) {
                slot.quiescenceListeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    public void releasePluginRuntime(PluginRuntimeToken token) {
        if (token == null) return;
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = runtimeSlotsByToken.get(token);
            if (slot == null) return;
            if (slot.activeCalls != 0) {
                PluginDebugLog.log("RUNTIME release rejected busy token="
                        + token + " activeCalls=" + slot.activeCalls);
                FileLog.w("nimarko: refusing to release busy runtime " + token
                        + " activeCalls=" + slot.activeCalls);
                return;
            }
            slot.quiescenceListeners.clear();
            runtimeSlotsByToken.remove(token, slot);
            PluginDebugLog.log("RUNTIME released token=" + token);
        }
    }

    public boolean isPluginRuntimeCurrent(PluginRuntimeToken token) {
        if (token == null || NimarkoConfig.pluginsSafeMode) return false;
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = currentRuntimeByPlugin.get(token.pluginId);
            return slot != null
                    && slot.state != RuntimeState.REVOKED
                    && slot.token.equals(token)
                    && toggleGenerations.getOrDefault(token.pluginId, 0) == token.generation;
        }
    }

    public boolean isPluginRuntimeExecuting(PluginRuntimeToken token) {
        if (token == null) return false;
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = runtimeSlotsByToken.get(token);
            return slot != null
                    && slot.token.equals(token)
                    && slot.activeCalls > 0;
        }
    }

    public boolean registerRuntimeCallbackHolder(
            PluginRuntimeToken token, RuntimeCallbackHolder holder) {
        if (token == null || holder == null || NimarkoConfig.pluginsSafeMode) {
            return false;
        }
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = currentRuntimeByPlugin.get(token.pluginId);
            if (slot == null || slot.state == RuntimeState.REVOKED
                    || !slot.token.equals(token)
                    || runtimeSlotsByToken.get(token) != slot
                    || toggleGenerations.getOrDefault(token.pluginId, 0)
                            != token.generation) {
                return false;
            }
            if (!slot.callbackHolders.contains(holder)) {
                slot.callbackHolders.add(holder);
            }
            return true;
        }
    }

    public int deferRuntimeCallback(
            PluginRuntimeToken token, RuntimeCallbackHolder holder) {
        if (token == null || holder == null || NimarkoConfig.pluginsSafeMode) {
            return RUNTIME_TASK_DROP;
        }
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = currentRuntimeByPlugin.get(token.pluginId);
            if (slot == null || slot.state == RuntimeState.REVOKED
                    || !slot.token.equals(token)
                    || runtimeSlotsByToken.get(token) != slot
                    || !slot.callbackHolders.contains(holder)
                    || toggleGenerations.getOrDefault(token.pluginId, 0)
                            != token.generation) {
                return RUNTIME_TASK_DROP;
            }
            if (slot.state == RuntimeState.ACTIVE) {
                return isPluginActive(token.pluginId)
                        ? RUNTIME_TASK_RUN : RUNTIME_TASK_DROP;
            }
            if (!isPluginEnableRequestedLocked(
                    token.pluginId, token.generation)) {
                return RUNTIME_TASK_DROP;
            }
            if (!slot.preparingCallbacks.contains(holder)) {
                slot.preparingCallbacks.add(holder);
            }
            return RUNTIME_TASK_WAIT;
        }
    }

    public void unregisterRuntimeCallbackHolder(
            PluginRuntimeToken token, RuntimeCallbackHolder holder) {
        if (token == null || holder == null) return;
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = runtimeSlotsByToken.get(token);
            if (slot == null) return;
            slot.preparingCallbacks.remove(holder);
            slot.callbackHolders.remove(holder);
        }
    }

    public int getPluginRuntimeTaskDecision(PluginRuntimeToken token) {
        if (token == null || NimarkoConfig.pluginsSafeMode) return RUNTIME_TASK_DROP;
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = currentRuntimeByPlugin.get(token.pluginId);
            if (slot == null || !slot.token.equals(token)
                    || slot.state == RuntimeState.REVOKED
                    || toggleGenerations.getOrDefault(token.pluginId, 0) != token.generation) {
                return RUNTIME_TASK_DROP;
            }
            if (slot.state == RuntimeState.PREPARING) {
                return isPluginEnableRequestedLocked(token.pluginId, token.generation)
                        ? RUNTIME_TASK_WAIT : RUNTIME_TASK_DROP;
            }
            return isPluginActive(token.pluginId)
                    ? RUNTIME_TASK_RUN : RUNTIME_TASK_DROP;
        }
    }

    public boolean isPluginRuntimeCallbackAllowed(PluginRuntimeToken token) {
        return getPluginRuntimeTaskDecision(token) == RUNTIME_TASK_RUN;
    }

    public void beginPluginUnload(PluginRuntimeToken token) {
        if (token == null) return;
        PluginRuntimeToken existing = pluginUnloadPermit.get();
        if (existing != null && !existing.equals(token)) {
            throw new IllegalStateException(
                    "Nested unload for a different plugin runtime");
        }
        pluginUnloadPermit.set(token);
    }

    public void endPluginUnload(PluginRuntimeToken token) {
        if (token != null && token.equals(pluginUnloadPermit.get())) {
            pluginUnloadPermit.remove();
        }
    }

    public boolean commitPluginRuntime(PluginRuntimeToken token, Plugin plugin) {
        if (token == null || plugin == null) return false;
        final ArrayList<RuntimeCallbackHolder> callbacksToActivate;
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = currentRuntimeByPlugin.get(token.pluginId);
            if (slot == null || !slot.token.equals(token)
                    || slot.state != RuntimeState.PREPARING
                    || !isPluginEnableRequestedLocked(token.pluginId, token.generation)
                    || plugins.get(token.pluginId) != plugin) {
                PluginDebugLog.log("RUNTIME commit rejected token=" + token
                        + " slot=" + (slot != null ? slot.token : null)
                        + " state=" + (slot != null ? slot.state : null)
                        + " requested="
                        + pendingToggleState.get(token.pluginId)
                        + " currentGeneration="
                        + toggleGenerations.getOrDefault(
                                token.pluginId, 0));
                return false;
            }
            plugin.setError(null);
            plugin.setEnabled(true);
            preferences.edit()
                    .putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + token.pluginId, true)
                    .commit();
            slot.state = RuntimeState.ACTIVE;
            callbacksToActivate =
                    new ArrayList<>(slot.preparingCallbacks);
            slot.preparingCallbacks.clear();
            PluginDebugLog.log("RUNTIME committed token=" + token
                    + " callbacksToActivate="
                    + callbacksToActivate.size());
        }
        for (RuntimeCallbackHolder holder : callbacksToActivate) {
            try {
                holder.onPluginRuntimeActive();
            } catch (Throwable failure) {
                FileLog.e("Unable to activate callback holder for " + token,
                        failure);
                try {
                    holder.revokePluginRuntime();
                } catch (Throwable ignored) {
                }
            }
        }
        return true;
    }

    public void revokePluginRuntime(PluginRuntimeToken token) {
        if (token == null) return;
        synchronized (generationLock(token.pluginId)) {
            RuntimeSlot slot = runtimeSlotsByToken.get(token);
            if (slot != null && slot.token.equals(token)) {
                PluginDebugLog.log("RUNTIME revoke token=" + token
                        + " state=" + slot.state
                        + " activeCalls=" + slot.activeCalls
                        + " holders=" + slot.callbackHolders.size());
                revokeRuntimeSlotLocked(slot);
                currentRuntimeByPlugin.remove(token.pluginId, slot);
            }
        }
    }

    private PluginRuntimeToken revokePluginRuntimeLocked(String pluginId) {
        RuntimeSlot slot = currentRuntimeByPlugin.remove(pluginId);
        if (slot == null) return null;
        revokeRuntimeSlotLocked(slot);
        return slot.token;
    }

    private void revokeRuntimeSlotLocked(RuntimeSlot slot) {
        if (slot == null) return;
        slot.state = RuntimeState.REVOKED;
        ArrayList<RuntimeCallbackHolder> holders =
                new ArrayList<>(slot.callbackHolders);
        slot.preparingCallbacks.clear();
        slot.callbackHolders.clear();
        for (RuntimeCallbackHolder holder : holders) {
            try {
                holder.revokePluginRuntime();
            } catch (Throwable failure) {
                FileLog.e("Unable to revoke callback holder for "
                        + slot.token, failure);
            }
        }
    }

    public boolean getRequestedPluginEnabled(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) return false;
        synchronized (generationLock(pluginId)) {
            Boolean pending = pendingToggleState.get(pluginId);
            if (pending != null) return pending;
            if (startupActivations.contains(pluginId)
                    && preferences != null) {
                return preferences.getBoolean(
                        PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId,
                        false);
            }
            Plugin plugin = plugins.get(pluginId);
            return plugin != null && plugin.isEnabled();
        }
    }

    public boolean isPluginEnableRequested(String pluginId, int generation) {
        if (TextUtils.isEmpty(pluginId) || NimarkoConfig.pluginsSafeMode) return false;
        synchronized (generationLock(pluginId)) {
            return isPluginEnableRequestedLocked(pluginId, generation);
        }
    }

    private boolean isPluginEnableRequestedLocked(String pluginId, int generation) {
        if (NimarkoConfig.pluginsSafeMode
                || toggleGenerations.getOrDefault(pluginId, 0) != generation) {
            return false;
        }
        Boolean pending = pendingToggleState.get(pluginId);
        return pending != null ? pending
                : preferences.getBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false);
    }

    public boolean claimPluginEnableCode(String pluginId, int generation) {
        synchronized (generationLock(pluginId)) {
            return isPluginEnableRequestedLocked(pluginId, generation);
        }
    }

    public boolean commitPluginEnable(String pluginId, int generation, Plugin plugin) {
        if (TextUtils.isEmpty(pluginId) || plugin == null) return false;
        PluginRuntimeToken token = getCurrentPluginRuntime(pluginId);
        if (token != null && token.generation == generation) {
            return commitPluginRuntime(token, plugin);
        }
        synchronized (generationLock(pluginId)) {
            if (!isPluginEnableRequestedLocked(pluginId, generation)
                    || plugins.get(pluginId) != plugin) {
                return false;
            }
            plugin.setError(null);
            plugin.setEnabled(true);
            preferences.edit()
                    .putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, true)
                    .commit();
            return true;
        }
    }

    public boolean failPluginEnable(String pluginId, int generation, Throwable failure) {
        return failPluginEnable(pluginId, generation, failure, null);
    }

    public boolean failPluginEnable(
            String pluginId, int generation, Throwable failure,
            PluginRuntimeToken runtimeToken) {
        if (TextUtils.isEmpty(pluginId)) return false;
        final PluginCleanup cleanup;
        synchronized (generationLock(pluginId)) {
            if (toggleGenerations.getOrDefault(pluginId, 0) != generation) {
                return false;
            }
            Plugin plugin = plugins.get(pluginId);
            if (plugin != null) {
                plugin.setEnabled(false);
                plugin.setError(failure);
            }
            cleanup = detachPluginRuntimeLocked(pluginId, runtimeToken);
        }
        finishPluginDeactivation(cleanup);
        return true;
    }

    public boolean isPluginToggleGenerationCurrent(String pluginId, int generation) {
        if (TextUtils.isEmpty(pluginId)) return false;
        synchronized (generationLock(pluginId)) {
            return toggleGenerations.getOrDefault(pluginId, 0) == generation;
        }
    }

    public boolean restorePluginEnabledPreference(
            String pluginId, int transactionGeneration,
            boolean transactionEnabled) {
        if (TextUtils.isEmpty(pluginId)) return false;
        synchronized (generationLock(pluginId)) {
            boolean effectiveEnabled;
            if (toggleGenerations.getOrDefault(pluginId, 0)
                    == transactionGeneration) {
                effectiveEnabled = transactionEnabled;
            } else {
                Boolean pending = pendingToggleState.get(pluginId);
                effectiveEnabled = pending != null
                        ? pending
                        : preferences.getBoolean(
                                PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId,
                                false);
            }
            preferences.edit()
                    .putBoolean(
                            PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId,
                            effectiveEnabled)
                    .commit();
            return effectiveEnabled;
        }
    }

    public boolean runForPluginToggleGeneration(String pluginId, int generation, Runnable action) {
        if (TextUtils.isEmpty(pluginId) || action == null) return false;
        synchronized (generationLock(pluginId)) {
            if (toggleGenerations.getOrDefault(pluginId, 0) != generation) return false;
        }
        action.run();
        return isPluginToggleGenerationCurrent(pluginId, generation);
    }

    public void beginPluginInitialization(String pluginId, int generation) {
        if (TextUtils.isEmpty(pluginId)) return;
        PluginInitializationToken current = initializationPermit.get();
        PluginInitializationToken token =
                new PluginInitializationToken(pluginId, generation);
        if (token.equals(current)) return;
        if (current != null) {
            finishPluginInitializationPermit(current);
        }
        initializationPermit.set(token);
        activePluginInitializations.compute(token, (ignored, count) -> {
            if (count == null) return new AtomicInteger(1);
            count.incrementAndGet();
            return count;
        });
    }

    public void endPluginInitialization(String pluginId, int generation) {
        PluginInitializationToken permit = initializationPermit.get();
        if (permit != null && permit.generation == generation
                && java.util.Objects.equals(permit.pluginId, pluginId)) {
            initializationPermit.remove();
            finishPluginInitializationPermit(permit);
        }
    }

    public void endPluginInitialization(String pluginId) {
        PluginInitializationToken permit = initializationPermit.get();
        if (permit != null && java.util.Objects.equals(permit.pluginId, pluginId)) {
            initializationPermit.remove();
            finishPluginInitializationPermit(permit);
        }
    }

    private void finishPluginInitializationPermit(PluginInitializationToken token) {
        activePluginInitializations.computeIfPresent(token, (ignored, count) ->
                count.decrementAndGet() <= 0 ? null : count);
    }

    public PluginInitializationToken captureCurrentPluginInitialization() {
        return initializationPermit.get();
    }

    public boolean isPluginInitializationActive(PluginInitializationToken token) {
        if (token == null) return false;
        AtomicInteger count = activePluginInitializations.get(token);
        return count != null && count.get() > 0;
    }

    public boolean canPluginRegisterRuntime(String pluginId) {
        if (TextUtils.isEmpty(pluginId) || NimarkoConfig.pluginsSafeMode) return false;
        synchronized (generationLock(pluginId)) {
            return canPluginRegisterRuntimeLocked(pluginId);
        }
    }

    private boolean canPluginRegisterRuntimeLocked(String pluginId) {
        if (NimarkoConfig.pluginsSafeMode) return false;
        Deque<PluginRuntimeToken> stack = runtimeScopes.get();
        PluginRuntimeToken scoped = stack.peek();
        if (scoped != null) {
            RuntimeSlot slot = currentRuntimeByPlugin.get(pluginId);
            return java.util.Objects.equals(scoped.pluginId, pluginId)
                    && slot != null
                    && slot.token.equals(scoped)
                    && slot.state != RuntimeState.REVOKED
                    && toggleGenerations.getOrDefault(pluginId, 0) == scoped.generation
                    && isPluginEnableRequestedLocked(pluginId, scoped.generation);
        }
        
        PluginInitializationToken permit = initializationPermit.get();
        if (permit != null && java.util.Objects.equals(permit.pluginId, pluginId)) {
            RuntimeSlot slot = currentRuntimeByPlugin.get(pluginId);
            return slot != null
                    && slot.state != RuntimeState.REVOKED
                    && slot.token.generation == permit.generation
                    && isPluginEnableRequestedLocked(pluginId, permit.generation);
        }
        
        if (currentRuntimeByPlugin.containsKey(pluginId)) {
            return false;
        }
        return isPluginActive(pluginId);
    }

    public boolean runPluginRuntimePythonMutation(String pluginId, PyObject mutation) {
        if (TextUtils.isEmpty(pluginId) || mutation == null) return false;
        final int runtimeEpoch;
        synchronized (generationLock(pluginId)) {
            if (!canPluginRegisterRuntimeLocked(pluginId)) return false;
            runtimeEpoch = runtimeEpochs.getOrDefault(pluginId, 0);
        }
        mutation.call();
        synchronized (generationLock(pluginId)) {
            return runtimeEpochs.getOrDefault(pluginId, 0) == runtimeEpoch
                    && canPluginRegisterRuntimeLocked(pluginId);
        }
    }

    public boolean runPluginRuntimeMutation(String pluginId, Runnable mutation) {
        if (TextUtils.isEmpty(pluginId) || mutation == null) return false;
        synchronized (generationLock(pluginId)) {
            if (!canPluginRegisterRuntimeLocked(pluginId)) return false;
            mutation.run();
            return canPluginRegisterRuntimeLocked(pluginId);
        }
    }

    public int cancelPluginInitialization(String pluginId, int generation, boolean persistDisabled) {
        if (TextUtils.isEmpty(pluginId)) return -1;
        final int cancellationGeneration;
        final PluginCleanup cleanup;
        synchronized (generationLock(pluginId)) {
            
            if (toggleGenerations.getOrDefault(pluginId, 0) != generation) return -1;
            if (persistDisabled) {
                cancellationGeneration = generation + 1;
                toggleGenerations.put(pluginId, cancellationGeneration);
                if (pendingToggleState.containsKey(pluginId)) {
                    pendingToggleState.put(pluginId, false);
                }
            } else {
                
                cancellationGeneration = generation;
            }
            enablingInProgress.remove(pluginId);
            Plugin plugin = plugins.get(pluginId);
            if (plugin != null) plugin.setEnabled(false);
            if (persistDisabled && preferences != null) {
                preferences.edit()
                        .putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false)
                        .commit();
            }
            cleanup = detachPluginRuntimeLocked(pluginId);
        }
        finishPluginDeactivation(cleanup);
        notifyPluginsChanged();
        return cancellationGeneration;
    }

    private final Runnable updateNotificationRunnable = () -> {
        PluginDebugLog.log("CTRL pluginsUpdated dispatch on UI"
                + " pending=" + pendingToggleState.size()
                + " enabling=" + enablingInProgress.size());
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginMenuItemsUpdated);
    };

    public interface PluginsEngine {
        boolean canOpenInExternalApp();
        void checkDevServer();
        boolean clearPluginSettings(String str);
        void deletePlugin(String str, Utilities.Callback<String> callback);
        void executeOnAppEvent(String str);
        HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String str, int i, TLObject tLObject, TLRPC.TL_error tL_error, String str2);
        HookResult<TLObject> executePreRequestHook(String str, int i, TLObject tLObject, String str2);
        HookResult<SendMessagesHelper.SendMessageParams> executeSendMessageHook(int i, SendMessagesHelper.SendMessageParams sendMessageParams, String str);
        HookResult<TLRPC.Update> executeUpdateHook(String str, int i, TLRPC.Update update, String str2);
        HookResult<TLRPC.Updates> executeUpdatesHook(String str, int i, TLRPC.Updates updates, String str2);
        default HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(
                String str, int i, TLObject response, TLRPC.TL_error error,
                String pluginId, PluginRuntimeToken expectedRuntime) {
            return executePostRequestHook(
                    str, i, response, error, pluginId);
        }
        default HookResult<TLObject> executePreRequestHook(
                String str, int i, TLObject request, String pluginId,
                PluginRuntimeToken expectedRuntime) {
            return executePreRequestHook(str, i, request, pluginId);
        }
        default HookResult<SendMessagesHelper.SendMessageParams>
                executeSendMessageHook(
                        int account,
                        SendMessagesHelper.SendMessageParams params,
                        String pluginId,
                        PluginRuntimeToken expectedRuntime) {
            return executeSendMessageHook(account, params, pluginId);
        }
        default HookResult<TLRPC.Update> executeUpdateHook(
                String str, int account, TLRPC.Update update,
                String pluginId, PluginRuntimeToken expectedRuntime) {
            return executeUpdateHook(str, account, update, pluginId);
        }
        default HookResult<TLRPC.Updates> executeUpdatesHook(
                String str, int account, TLRPC.Updates updates,
                String pluginId, PluginRuntimeToken expectedRuntime) {
            return executeUpdatesHook(str, account, updates, pluginId);
        }
        Map<String, ?> getAllPluginSettings(String str);
        String getPluginPath(String str);
        Object getPluginSetting(String str, String str2, Object obj);
        void init(Runnable runnable);
        boolean isEngineAvailable();
        boolean isPlugin(File file);
        List<SettingItem> loadPluginSettings(String str);
        void openInExternalApp(String str);
        void openPluginSetting(Plugin plugin, String str, BaseFragment baseFragment);
        void openPluginSetting(String str, String str2, BaseFragment baseFragment);
        void openPluginSettings(Plugin plugin, BaseFragment baseFragment);
        void openPluginSettings(String str, BaseFragment baseFragment);
        void setPluginEnabled(String str, boolean z, Utilities.Callback<String> callback);
        default void setPluginEnabled(String str, boolean z, int generation,
                                      Utilities.Callback<String> callback) {
            setPluginEnabled(str, z, callback);
        }
        void setPluginSetting(String str, String str2, Object obj);
        void sharePlugin(String str);
        void showInstallDialog(BaseFragment baseFragment, InstallPluginBottomSheet.PluginInstallParams pluginInstallParams);
        void shutdown(Runnable runnable);
    }

    @FunctionalInterface
    public interface PortablePluginSettingsTransaction<T> {
        T run() throws Exception;
    }

    public static PluginsController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public static boolean reloadPortablePluginSettings() {
        PluginsEngine engine = engines.get(PluginsConstants.PYTHON);
        if (engine instanceof PythonPluginsEngine) {
            return ((PythonPluginsEngine) engine).reloadPortablePluginSettings();
        }
        FileLog.e("Python plugin engine unavailable while reloading portable settings");
        return false;
    }

    public static <T> T withPortablePluginSettingsTransaction(
            PortablePluginSettingsTransaction<T> transaction) throws Exception {
        PluginsEngine engine = engines.get(PluginsConstants.PYTHON);
        if (!(engine instanceof PythonPluginsEngine)) {
            throw new IllegalStateException("Python plugin engine unavailable for settings transaction");
        }
        return ((PythonPluginsEngine) engine).withPortablePluginSettingsTransaction(transaction);
    }

    public String attributePluginFromCrashStack(Thread thread, Throwable t) {
        
        return watchdog.getCrashingPluginId(thread, t);
    }

    public static boolean isPluginEngineSupported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    public static boolean isPluginEngineAvailable() {
        if (isPluginEngineSupported() && NimarkoConfig.pluginsEngine && !NimarkoConfig.pluginsSafeMode) {
            for (PluginsEngine pluginsEngine : engines.values()) {
                if (pluginsEngine != null) {
                    try {
                        if (pluginsEngine.isEngineAvailable()) {
                            return true;
                        }
                    } catch (Throwable th) {
                        FileLog.e("Error checking engine availability.", th);
                    }
                }
            }
        }
        return false;
    }

    public static boolean isPlugin(MessageObject messageObject) {
        String pathToMessage = ChatUtils.getInstance().getPathToMessage(messageObject);
        return (messageObject == null || messageObject.getDocumentName() == null || TextUtils.isEmpty(pathToMessage) || !isPlugin(new File(pathToMessage)) || !isPluginEngineSupported()) ? false : true;
    }

    public static boolean isPlugin(File file) {
        if (file == null) {
            return false;
        }
        for (PluginsEngine engine : engines.values()) {
            if (engine.isPlugin(file)) {
                return true;
            }
        }
        return false;
    }

    public static PluginsEngine getPluginEngine(File file) {
        if (file == null) {
            return null;
        }
        for (PluginsEngine pluginsEngine : engines.values()) {
            if (pluginsEngine.isPlugin(file)) {
                return pluginsEngine;
            }
        }
        return null;
    }

    public static void openPluginSetting(String str, String str2) {
        final BaseFragment lastFragment;
        if (TextUtils.isEmpty(str) || (lastFragment = LaunchActivity.getLastFragment()) == null) {
            return;
        }
        if (!NimarkoConfig.pluginsEngine) {
            BulletinFactory.of(lastFragment).createSimpleBulletin(R.raw.error, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginEngineNotEnabled, str)), LocaleController.getString(R.string.Enable), 2750, () -> lastFragment.presentFragment(new PluginsActivity())).show();
            return;
        }
        Plugin plugin = getInstance().plugins.get(str);
        if (plugin == null) {
            BulletinFactory.of(lastFragment).createEmojiBulletin("🤷\u200d♂️", AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginNotFound, str)).toString()).show();
            return;
        }
        
        if (!plugin.isEnabled()) {
            BulletinFactory.of(lastFragment).createSimpleBulletin(R.raw.info,
                    AndroidUtilities.replaceTags(LocaleController.formatString(R.string.NM_PluginIsDisabled, plugin.getName()))).show();
            return;
        }
        if (!getInstance().hasPluginSettings(str)) {
            BulletinFactory.of(lastFragment).createEmojiBulletin("🤷\u200d♂️", AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginHasNoSettings, plugin.getName())).toString()).show();
            return;
        }
        PluginsEngine pluginEngine = getInstance().getPluginEngine(str);
        if (pluginEngine != null) {
            pluginEngine.openPluginSetting(str, str2, lastFragment);
        }
    }

    public File getPluginsDir() {
        if (pluginsDir == null) {
            File file = new File(ApplicationLoader.getFilesDirFixed(), PluginsConstants.PLUGINS);
            this.pluginsDir = file;
            if (!file.exists()) {
                this.pluginsDir.mkdirs();
            }
        }
        return pluginsDir;
    }

    public PluginsEngine getPluginEngine(String str) {
        PluginsEngine pluginsEngine = null;
        if (str != null && !TextUtils.isEmpty(str)) {
            Plugin plugin = this.plugins.get(str);
            if (plugin == null) {
                return null;
            }
            PluginsEngine pluginsEngine2 = plugin.cachedEngine;
            if (pluginsEngine2 != null) {
                return pluginsEngine2;
            }
            String engine = plugin.getEngine();
            if (engine == null) {
                return null;
            }
            pluginsEngine = engines.get(engine);
            if (pluginsEngine != null) {
                plugin.cachedEngine = pluginsEngine;
            }
        }
        return pluginsEngine;
    }

    public static boolean isPluginPinned(String str) {
        return !TextUtils.isEmpty(str) && NimarkoConfig.isPluginPinned(str);
    }

    public static void setPluginPinned(String str, boolean z) {
        if (TextUtils.isEmpty(str)) {
            return;
        }
        NimarkoConfig.setPluginPinned(str, z);
    }

    private DispatchQueue getOrCreatePluginsQueue() {
        synchronized (controllerLifecycleLock) {
            DispatchQueue queue = Utilities.pluginsQueue;
            if (queue == null || !queue.isAlive()) {
                queue = new DispatchQueue("pluginsQueue");
                Utilities.pluginsQueue = queue;
            }
            return queue;
        }
    }

    public void init() {
        init(false, null);
    }

    public void init(final Runnable runnable) {
        init(false, runnable);
    }

    public void init(final boolean startWithSafeMode, final Runnable runnable) {
        FileLog.d("nimarko: PluginsController.init() begin");
        if (!isPluginEngineSupported() || !NimarkoConfig.pluginsEngine) {
            if (runnable != null) {
                runnable.run();
            }
            return;
        }
        synchronized (controllerLifecycleLock) {
            if (shutdownInProgress) {
                shutdownCompletionCallbacks.add(
                        () -> init(startWithSafeMode, runnable));
                return;
            }
            if (shutdownRequiresProcessRestart) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (runnable != null) {
                        runnable.run();
                    }
                    FileLog.w("nimarko: refusing in-process plugin "
                            + "initialization after failed shutdown");
                    app.nimarkogram.messenger.utils.AppRestartHelper
                            .triggerRebirth(
                                    ApplicationLoader.applicationContext);
                });
                return;
            }
            if (initialized && !initializationInProgress) {
                if (runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                }
                return;
            }
            if (runnable != null) {
                initializationCompletionCallbacks.add(runnable);
            }
            if (initializationInProgress) {
                return;
            }
            initializationInProgress = true;
            initializationAttempt++;
        }
        final long attempt;
        synchronized (controllerLifecycleLock) {
            attempt = initializationAttempt;
        }
        AndroidUtilities.runOnUIThread(
                () -> timeoutControllerInitialization(attempt),
                ENGINE_INIT_TIMEOUT_MS);

        Runnable initializationWork =
                () -> startControllerInitialization(
                        startWithSafeMode, attempt);
        DispatchQueue initializationQueue = getOrCreatePluginsQueue();
        if (Thread.currentThread() == initializationQueue) {
            initializationWork.run();
            return;
        }
        if (!initializationQueue.postRunnable(initializationWork)) {
            FileLog.e("nimarko: could not enqueue plugin initialization");
            timeoutControllerInitialization(attempt);
        }
    }

    private void startControllerInitialization(
            final boolean startWithSafeMode, final long attempt) {
        synchronized (controllerLifecycleLock) {
            if (!initializationInProgress
                    || initializationAttempt != attempt
                    || shutdownInProgress) {
                return;
            }
        }
        try {
        NativeCrashHandler.schedulePreviousExitDiagnostics();
        
        try {
            ClassProxy.setMvelEvaluator((expression, context) -> {
                try {
                    return org.mvel2.MVEL.eval(expression, context);
                } catch (Throwable t) {
                    FileLog.e("nimarko: ClassProxy MVEL eval failed: " + expression, t);
                    return null;
                }
            });
        } catch (Throwable t) {
            FileLog.e("nimarko: ClassProxy.setMvelEvaluator wiring failed", t);
        }
        
        if (!Utilities.pluginsQueue.isAlive()) {
            Utilities.pluginsQueue = new DispatchQueue("pluginsQueue");
        }

        if (this.preferences == null) {
            this.preferences = ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0);
        }
        
        PythonPluginsEngine.recoverInterruptedPluginUpdates(this);

        try {
            if (!this.preferences.getBoolean("plugin_falsequarantine_recovered_1", false)) {
                android.content.SharedPreferences.Editor rec = this.preferences.edit();
                for (String key : new java.util.ArrayList<>(this.preferences.getAll().keySet())) {
                    if (key != null && key.startsWith("plugin_crashed_")) {
                        String id = key.substring("plugin_crashed_".length());
                        String restoreKey = "plugin_enabled_before_quarantine_" + id;
                        if (this.preferences.contains(restoreKey)) {
                            rec.remove(key);
                            if (this.preferences.getBoolean(restoreKey, false)) {
                                rec.putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + id, true);
                            }
                            rec.remove(restoreKey);
                        }
                    }
                }
                rec.putBoolean("plugin_falsequarantine_recovered_1", true);
                rec.apply();
            }
        } catch (Throwable ignore) {}

        try {
            boolean hadCrash = this.preferences.getBoolean("had_crash", false);
            String crashedPluginId = this.preferences.getString("crashed_plugin_id", null);
            long crashedPluginStartedAt = this.preferences.getLong("crashed_plugin_started_at", 0L);
            boolean exactJavaAttribution = this.preferences.getBoolean("crashed_plugin_attribution_exact", false);
            boolean isManualSafeMode = crashedPluginId != null && crashedPluginId.equals("manual!");
            
            final boolean benignKill = NativeCrashHandler.lastExitWasBenignKill();
            boolean reliableLoadCrash = exactJavaAttribution
                    || NativeCrashHandler.lastExitWasLoadCrashAfter(crashedPluginStartedAt)
                    || NativeCrashHandler.conservativePre30LoadCrash(crashedPluginStartedAt);
            final String attributedId = (crashedPluginId != null && !isManualSafeMode
                    && !benignKill && reliableLoadCrash) ? crashedPluginId : null;
            this.preferences.edit().remove("had_crash").remove("crashed_plugin_id")
                    .remove("crashed_plugin_started_at").remove("native_crash_flag_only")
                    .remove("crashed_plugin_attribution_exact").apply();

            if (attributedId != null && !startWithSafeMode) {
                boolean wasAlreadyQuarantined = this.preferences.getBoolean("plugin_crashed_" + attributedId, false);
                if (wasAlreadyQuarantined) {
                    
                    this.preferences.edit()
                            .putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + attributedId, false)
                            .putInt("unattributed_native_crashes", 0)
                            .apply();
                    NimarkoConfig.setPluginsSafeMode(true);
                } else {
                    
                    this.preferences.edit()
                            .putBoolean("plugin_enabled_before_quarantine_" + attributedId,
                                    this.preferences.getBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + attributedId, false))
                            .putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + attributedId, false)
                            .putBoolean("plugin_crashed_" + attributedId, true)
                            .putInt("unattributed_native_crashes", 0)
                            .apply();
                    AndroidUtilities.runOnUIThread(() -> {
                        BaseFragment lastFragment = LaunchActivity.getLastFragment();
                        if (lastFragment != null) {
                            BulletinFactory.of(lastFragment).createSimpleBulletin(R.raw.info,
                                    LocaleController.formatString(R.string.NM_PluginCrashDisabled, attributedId)).show();
                        }
                    }, 800L);
                }
            } else if (isManualSafeMode || startWithSafeMode) {
                NimarkoConfig.setPluginsSafeMode(true);
            } else if (hadCrash) {
                int streak = this.preferences.getInt("unattributed_native_crashes", 0) + 1;
                if (streak >= 2) {
                    this.preferences.edit().putInt("unattributed_native_crashes", 0).apply();
                    NimarkoConfig.setPluginsSafeMode(true);
                } else {
                    this.preferences.edit().putInt("unattributed_native_crashes", streak).apply();
                    NimarkoConfig.setPluginsSafeMode(NimarkoConfig.pluginsSafeMode);
                }
            } else {
                this.preferences.edit().putInt("unattributed_native_crashes", 0).apply();
                NimarkoConfig.setPluginsSafeMode(NimarkoConfig.pluginsSafeMode);
            }

            if (NimarkoConfig.pluginsSafeMode) {
                AndroidUtilities.runOnUIThread(() -> {
                    BaseFragment lastFragment = LaunchActivity.getLastFragment();
                    if (lastFragment != null) {
                        new SafeModeBottomSheet(lastFragment).show();
                    }
                }, 800L);
            }
        } catch (Exception unused) {}
        
        File file = new File(ApplicationLoader.getFilesDirFixed(), PluginsConstants.PLUGINS);
        this.pluginsDir = file;
        if (!file.exists()) {
            this.pluginsDir.mkdirs();
        }
        List<PluginsEngine> engineSnapshot =
                new ArrayList<>(engines.values());
        final AtomicInteger remaining =
                new AtomicInteger(engineSnapshot.size());
        final AtomicBoolean aggregateCompleted =
                new AtomicBoolean(false);
        Runnable finishInitialization = () -> {
            if (aggregateCompleted.compareAndSet(false, true)) {
                finishControllerInitialization(attempt, true);
            }
        };
        if (engineSnapshot.isEmpty()) {
            finishInitialization.run();
            return;
        }
        
        for (PluginsEngine engine : engineSnapshot) {
            AtomicBoolean engineCompleted = new AtomicBoolean(false);
            Runnable engineDone = () -> {
                if (!engineCompleted.compareAndSet(false, true)) {
                    return;
                }
                if (remaining.decrementAndGet() == 0) {
                    finishInitialization.run();
                }
            };
            try {
                engine.init(engineDone);
            } catch (Throwable th) {
                FileLog.e("nimarko: plugin engine init crashed, continuing", th);
                engineDone.run();
            }
        }
        } catch (Throwable failure) {
            FileLog.e("nimarko: plugin controller initialization failed",
                    failure);
            timeoutControllerInitialization(attempt);
        }
    }

    private void timeoutControllerInitialization(long attempt) {
        synchronized (controllerLifecycleLock) {
            if (!initializationInProgress
                    || initializationAttempt != attempt) {
                return;
            }
            shutdownRequiresProcessRestart = true;
        }
        FileLog.e("nimarko: plugin engine initialization timed out or failed");
        if (!finishControllerInitialization(attempt, false)) {
            return;
        }
        
        NimarkoConfig.setPluginsSafeMode(true);
        AndroidUtilities.runOnUIThread(() ->
                app.nimarkogram.messenger.utils.AppRestartHelper
                        .triggerRebirth(
                                ApplicationLoader.applicationContext));
    }

    private boolean finishControllerInitialization(
            long attempt, boolean success) {
        final ArrayList<Runnable> completionCallbacks;
        synchronized (controllerLifecycleLock) {
            if (!initializationInProgress
                    || initializationAttempt != attempt) {
                return false;
            }
            initialized = success;
            initializationInProgress = false;
            completionCallbacks =
                    new ArrayList<>(initializationCompletionCallbacks);
            initializationCompletionCallbacks.clear();
        }
        
        try {
            watchdog.start();
        } catch (Throwable th) {
            FileLog.e("nimarko: watchdog start failed", th);
        }
        FileLog.d("nimarko: PluginsController.init() end success=" + success);
        for (Runnable completion : completionCallbacks) {
            try {
                completion.run();
            } catch (Throwable completionFailure) {
                FileLog.e("Plugin initialization completion failed",
                        completionFailure);
            }
        }
        return true;
    }

    public volatile boolean initialized = false;
    public boolean isInitialized() { return initialized; }

    public void checkDevServers() {
        for (PluginsEngine engine : engines.values()) {
            engine.checkDevServer();
        }
    }

    public void shutdown(final Runnable runnable) {
        final boolean startShutdown;
        synchronized (controllerLifecycleLock) {
            if (initializationInProgress && !shutdownInProgress) {
                initializationCompletionCallbacks.add(
                        () -> shutdown(runnable));
                return;
            }
            if (runnable != null) {
                shutdownCompletionCallbacks.add(runnable);
            }
            if (shutdownInProgress) {
                return;
            }
            shutdownInProgress = true;
            controllerLifecycleEpoch.incrementAndGet();
            startShutdown = true;
        }
        if (startShutdown) {
            completeCancelledToggleCallbacks(
                    cancelPendingTogglesForLifecycleBoundary());
        }
        List<PluginsEngine> engineSnapshot =
                new ArrayList<>(engines.values());
        final AtomicBoolean aggregateCompleted =
                new AtomicBoolean(false);
        final Runnable timeout = () -> {
            if (!aggregateCompleted.compareAndSet(false, true)) {
                return;
            }
            FileLog.e("nimarko: plugin engine shutdown timed out");
            synchronized (controllerLifecycleLock) {
                
                shutdownRequiresProcessRestart = true;
            }
            finishControllerShutdown();
        };
        Runnable finishShutdown = () -> {
            if (!aggregateCompleted.compareAndSet(false, true)) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(timeout);
            AndroidUtilities.runOnUIThread(
                    this::finishControllerShutdown);
        };
        
        AndroidUtilities.runOnUIThread(
                timeout, ENGINE_SHUTDOWN_TIMEOUT_MS);
        if (engineSnapshot.isEmpty()) {
            finishShutdown.run();
            return;
        }
        boolean posted = Utilities.pluginsQueue.postRunnable(() -> {
            final AtomicInteger remaining =
                    new AtomicInteger(engineSnapshot.size());
            for (PluginsEngine engine : engineSnapshot) {
                final AtomicBoolean engineCompleted =
                        new AtomicBoolean(false);
                Runnable engineDone = () -> {
                    if (!engineCompleted.compareAndSet(false, true)) {
                        return;
                    }
                    if (remaining.decrementAndGet() == 0) {
                        finishShutdown.run();
                    }
                };
                try {
                    engine.shutdown(engineDone);
                } catch (Throwable shutdownFailure) {
                    FileLog.e("nimarko: plugin engine shutdown failed",
                            shutdownFailure);
                    synchronized (controllerLifecycleLock) {
                        shutdownRequiresProcessRestart = true;
                    }
                    engineDone.run();
                }
            }
        });
        if (!posted && aggregateCompleted.compareAndSet(false, true)) {
            AndroidUtilities.cancelRunOnUIThread(timeout);
            FileLog.e("nimarko: could not enqueue plugin engine shutdown");
            synchronized (controllerLifecycleLock) {
                shutdownRequiresProcessRestart = true;
            }
            AndroidUtilities.runOnUIThread(
                    this::finishControllerShutdown);
        }
    }

    private void finishControllerShutdown() {
        completeCancelledToggleCallbacks(
                cancelPendingTogglesForLifecycleBoundary());

        for (RuntimeSlot slot :
                new ArrayList<>(currentRuntimeByPlugin.values())) {
            if (slot == null) continue;
            revokePluginRuntime(slot.token);
            app.nimarkogram.messenger.plugins.intents.IntentsController
                    .getInstance()
                    .removeIntentHooksByPluginId(
                            slot.token.getPluginId(), slot.token);
            runWhenPluginRuntimeQuiescent(
                    slot.token, () -> releasePluginRuntime(slot.token));
        }

        this.plugins.clear();
        this.startupActivations.clear();
        for (List<SettingItem> definitions :
                new ArrayList<>(this.settings.values())) {
            clearSettingPythonReferences(definitions);
        }
        this.settings.clear();
        this.settingsRuntimeTokens.clear();
        for (MenuItemRecord record :
                new ArrayList<>(this.menuItemsById.values())) {
            record.releaseCallback(record.runtimeToken);
        }
        this.menuItemsById.clear();
        this.menuItemsByMenuType.clear();
        this.hooks.clear();
        this.activePluginInitializations.clear();
        invalidateHooksCache();
        this.initialized = false;
        try {
            watchdog.stop();
        } catch (Throwable shutdownFailure) {
            FileLog.e("nimarko: watchdog shutdown failed", shutdownFailure);
        }

        final ArrayList<Runnable> completionCallbacks;
        synchronized (controllerLifecycleLock) {
            shutdownInProgress = false;
            completionCallbacks =
                    new ArrayList<>(shutdownCompletionCallbacks);
            shutdownCompletionCallbacks.clear();
        }
        FileLog.d("nimarko: PluginsController shutdown complete");
        for (Runnable completion : completionCallbacks) {
            try {
                completion.run();
            } catch (Throwable completionFailure) {
                FileLog.e("Plugin shutdown completion failed",
                        completionFailure);
            }
        }
    }

    private ArrayList<PendingToggleCallback>
            cancelPendingTogglesForLifecycleBoundary() {
        ArrayList<PendingToggleCallback> cancelled =
                new ArrayList<>();
        HashSet<String> pendingIds = new HashSet<>();
        pendingIds.addAll(pendingToggleState.keySet());
        pendingIds.addAll(pendingToggleCallbacks.keySet());
        pendingIds.addAll(enablingInProgress);
        for (String pluginId : pendingIds) {
            synchronized (generationLock(pluginId)) {
                toggleGenerations.put(
                        pluginId,
                        toggleGenerations.getOrDefault(pluginId, 0) + 1);
                pendingToggleState.remove(pluginId);
                enablingInProgress.remove(pluginId);
                cancelled.addAll(
                        drainPendingToggleCallbacksLocked(pluginId));
            }
        }
        return cancelled;
    }

    private void completeCancelledToggleCallbacks(
            List<PendingToggleCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            for (PendingToggleCallback pending : callbacks) {
                if (pending == null || pending.callback == null) continue;
                try {
                    
                    pending.callback.run(null);
                } catch (Throwable callbackFailure) {
                    FileLog.e("Plugin lifecycle callback failed",
                            callbackFailure);
                }
            }
        });
    }

    public boolean clearPluginSettingsPreferences(String pluginId, boolean clearEnabledState) {
        if (TextUtils.isEmpty(pluginId)) return false;
        boolean cleared = true;
        PluginsEngine pluginEngine = getPluginEngine(pluginId);
        if (pluginEngine != null) {
            cleared = pluginEngine.clearPluginSettings(pluginId);
        }
        if (clearEnabledState && this.preferences != null) {
            String key = PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId;
            if (this.preferences.contains(key)) {
                cleared &= this.preferences.edit().remove(key).commit();
            }
        }
        return cleared;
    }

    public void restart() {
        restart(NimarkoConfig.pluginsSafeMode);
    }

    public void restart(final boolean startWithSafeMode) {
        FileLog.d("nimarko: PluginsController.restart(startWithSafeMode=" + startWithSafeMode + ")");
        PluginsEngine pythonEngine = engines.get(
                PluginsConstants.PYTHON);
        if (pythonEngine instanceof PythonPluginsEngine
                && ((PythonPluginsEngine) pythonEngine)
                        .requiresProcessRestart()) {
            FileLog.w("nimarko: Python lifecycle is wedged; "
                    + "performing a clean process restart");
            app.nimarkogram.messenger.utils.AppRestartHelper
                    .triggerRebirth(
                            ApplicationLoader.applicationContext);
            return;
        }
        shutdown(() -> {
            PluginsEngine latestPythonEngine =
                    engines.get(PluginsConstants.PYTHON);
            final boolean failedShutdown;
            synchronized (controllerLifecycleLock) {
                failedShutdown = shutdownRequiresProcessRestart;
            }
            if (failedShutdown
                    || (latestPythonEngine instanceof PythonPluginsEngine
                    && ((PythonPluginsEngine) latestPythonEngine)
                            .requiresProcessRestart())) {
                FileLog.w("nimarko: Python lifecycle wedged during shutdown; "
                        + "performing a clean process restart");
                app.nimarkogram.messenger.utils.AppRestartHelper
                        .triggerRebirth(
                                ApplicationLoader.applicationContext);
                return;
            }
            if (NimarkoConfig.pluginsEngine) {
                init(startWithSafeMode, () -> FileLog.d("nimarko: PluginsController.restart() complete"));
            }
        });
    }

    public List<SettingItem> getPluginSettingsList(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        return this.settings.get(str);
    }

    public void setPluginEnabled(final String str, final boolean z, final Utilities.Callback<String> callback) {
        PluginDebugLog.log("CTRL setPluginEnabled request plugin=" + str
                + " target=" + z
                + " callback=" + (callback != null)
                + " thread=" + Thread.currentThread().getName());
        final long requestEpoch;
        synchronized (controllerLifecycleLock) {
            if (shutdownInProgress) {
                PluginDebugLog.log("CTRL toggle rejected: shutdown plugin="
                        + str + " target=" + z
                        + " lifecycleEpoch="
                        + controllerLifecycleEpoch.get());
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(
                            () -> callback.run(null));
                }
                return;
            }
            requestEpoch = controllerLifecycleEpoch.get();
        }
        if (str == null) {
            Utilities.pluginsQueue.postRunnable(() -> {
                if (!isControllerLifecycleCurrent(requestEpoch)) {
                    if (callback != null) {
                        AndroidUtilities.runOnUIThread(
                                () -> callback.run(null));
                    }
                    return;
                }
                PluginsEngine pluginEngine = getPluginEngine((String) null);
                if (pluginEngine != null) pluginEngine.setPluginEnabled(null, z, callback);
                else if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.run(null));
            });
            return;
        }
        
        if (z && "NimarkoMedia".equals(str)) {
            final int specialGeneration;
            synchronized (generationLock(str)) {
                if (!isControllerLifecycleCurrent(requestEpoch)) {
                    if (callback != null) {
                        AndroidUtilities.runOnUIThread(
                                () -> callback.run(null));
                    }
                    return;
                }
                specialGeneration =
                        toggleGenerations.getOrDefault(str, 0) + 1;
                toggleGenerations.put(str, specialGeneration);
            }
            Plugin nm = this.plugins.get(str);
            if (nm != null) {
                nm.setEnabled(false);
            }
            
            if (this.preferences != null) {
                this.preferences.edit().putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + str, false).apply();
            }
            cleanupPlugin(str, getCurrentPluginRuntime(str));
            invalidateInterestedPluginsCache();
            
            enablingInProgress.remove(str);
            pendingToggleState.remove(str);
            final List<PendingToggleCallback> callbacks;
            synchronized (generationLock(str)) {
                callbacks = drainPendingToggleCallbacksLocked(str);
                if (callback != null) {
                    callbacks.add(new PendingToggleCallback(
                            z, specialGeneration, requestEpoch, callback));
                }
            }
            notifyPluginsChanged();
            AndroidUtilities.runOnUIThread(() -> {
                BaseFragment lastFragment = LaunchActivity.getLastFragment();
                if (lastFragment != null) {
                    BulletinFactory.of(lastFragment).createSimpleBulletin(R.raw.info,
                            AndroidUtilities.replaceTags(LocaleController.getString(R.string.NM_MediaBuiltIn))
                    ).show();
                } else {
                    BulletinFactory.global().createSimpleBulletin(R.raw.info,
                            AndroidUtilities.replaceTags(LocaleController.getString(R.string.NM_MediaBuiltIn))
                    ).show();
                }
            });
            
            deletePlugin(str, errStr -> {
                deliverToggleCallbacks(
                        callbacks, false, specialGeneration,
                        requestEpoch, errStr);
            });
            return;
        }
        final Boolean prior;
        final int generation;
        final PluginCleanup immediateCleanup;
        
        synchronized (generationLock(str)) {
            if (!isControllerLifecycleCurrent(requestEpoch)) {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(
                            () -> callback.run(null));
                }
                return;
            }
            generation = toggleGenerations.getOrDefault(str, 0) + 1;
            toggleGenerations.put(str, generation);
            prior = pendingToggleState.put(str, z);
            if (callback != null) {
                pendingToggleCallbacks
                        .computeIfAbsent(str, ignored -> new CopyOnWriteArrayList<>())
                        .add(new PendingToggleCallback(
                                z, generation, requestEpoch, callback));
            }
            if (!z) {
                enablingInProgress.remove(str);
                Plugin plugin = this.plugins.get(str);
                if (plugin != null) {
                    plugin.setEnabled(false);
                }
                immediateCleanup = detachPluginRuntimeLocked(str);
            } else {
                enablingInProgress.add(str);
                immediateCleanup = null;
            }
            PluginDebugLog.log("CTRL toggle published plugin=" + str
                    + " target=" + z
                    + " generation=" + generation
                    + " lifecycleEpoch=" + requestEpoch
                    + " priorPending=" + prior
                    + " runtime="
                    + (currentRuntimeByPlugin.get(str) != null
                            ? currentRuntimeByPlugin.get(str).token : null)
                    + " cleanup="
                    + (immediateCleanup != null));
        }
        
        if (!z) {
            if (this.preferences != null) {
                this.preferences.edit()
                        .putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + str, false)
                        .apply();
            }
            finishPluginDeactivation(immediateCleanup);
            invalidateInterestedPluginsCache();
            notifyPluginsChanged();
        }
        
        if (prior != null) {
            
            PluginDebugLog.log("CTRL toggle coalesced plugin=" + str
                    + " target=" + z
                    + " generation=" + generation
                    + " replacedPending=" + prior);
            FileLog.d("nimarko: coalesced toggle for " + str + " (target=" + z + ")");
            return;
        }
        boolean posted = Utilities.pluginsQueue.postRunnable(
                () -> runToggleLoop(str, generation, requestEpoch));
        PluginDebugLog.log("CTRL toggle queued plugin=" + str
                + " target=" + z
                + " generation=" + generation
                + " posted=" + posted
                + " queueAlive=" + Utilities.pluginsQueue.isAlive());
    }

    private void runToggleLoop(
            String str, int scheduledGeneration, long scheduledEpoch) {
        PluginDebugLog.log("CTRL toggle-loop enter plugin=" + str
                + " scheduledGeneration=" + scheduledGeneration
                + " scheduledEpoch=" + scheduledEpoch
                + " controllerEpoch="
                + controllerLifecycleEpoch.get());
        if (!isControllerLifecycleCurrent(scheduledEpoch)) {
            PluginDebugLog.log("CTRL toggle-loop stale lifecycle plugin="
                    + str + " scheduledEpoch=" + scheduledEpoch);
            return;
        }
        final boolean targetState;
        final int appliedGeneration;
        synchronized (generationLock(str)) {
            if (!isControllerLifecycleCurrent(scheduledEpoch)) {
                return;
            }
            Boolean target = pendingToggleState.get(str);
            if (target == null) {
                PluginDebugLog.log("CTRL toggle-loop no pending state plugin="
                        + str);
                return;
            }
            targetState = target;
            appliedGeneration = toggleGenerations.getOrDefault(
                    str, scheduledGeneration);
            RuntimeSlot slot = currentRuntimeByPlugin.get(str);
            PluginDebugLog.log("CTRL toggle-loop snapshot plugin=" + str
                    + " target=" + targetState
                    + " appliedGeneration=" + appliedGeneration
                    + " runtime=" + (slot != null ? slot.token : null)
                    + " runtimeState="
                    + (slot != null ? slot.state : null)
                    + " activeCalls="
                    + (slot != null ? slot.activeCalls : 0));
        }
        PluginsEngine pluginEngine = getPluginEngine(str);
        if (pluginEngine == null) {
            PluginDebugLog.log("CTRL toggle-loop engine missing plugin="
                    + str + " target=" + targetState
                    + " generation=" + appliedGeneration);
            List<PendingToggleCallback> callbacks = Collections.emptyList();
            boolean retry = false;
            int retryGeneration = appliedGeneration;
            synchronized (generationLock(str)) {
                if (toggleGenerations.getOrDefault(str, 0) == appliedGeneration
                        && pendingToggleState.remove(str, targetState)) {
                    callbacks = drainPendingToggleCallbacksLocked(str);
                    enablingInProgress.remove(str);
                } else if (pendingToggleState.containsKey(str)) {
                    retry = true;
                    retryGeneration = toggleGenerations.getOrDefault(
                            str, appliedGeneration);
                }
            }
            if (retry) {
                final int generationToRetry = retryGeneration;
                PluginDebugLog.log("CTRL toggle-loop retry without engine plugin="
                        + str + " generation=" + generationToRetry);
                Utilities.pluginsQueue.postRunnable(
                        () -> runToggleLoop(
                                str, generationToRetry, scheduledEpoch));
                return;
            }
            deliverToggleCallbacks(
                    callbacks, targetState, appliedGeneration,
                    scheduledEpoch,
                    "Plugin not found: " + str);
            return;
        }
        final AtomicBoolean completionOnce = new AtomicBoolean(false);
        Utilities.Callback<String> engineCompletion = errStr -> {
            PluginDebugLog.log("CTRL engine callback plugin=" + str
                    + " applied=" + targetState
                    + " generation=" + appliedGeneration
                    + " error=" + (errStr != null)
                    + " callbackThread="
                    + Thread.currentThread().getName());
            if (!completionOnce.compareAndSet(false, true)) {
                FileLog.w("nimarko: duplicate plugin toggle callback ignored for "
                        + str + "@" + appliedGeneration);
                return;
            }
            if (!isControllerLifecycleCurrent(scheduledEpoch)) {
                return;
            }
            
            final Boolean latest;
            final int latestGeneration;
            synchronized (generationLock(str)) {
                latest = pendingToggleState.get(str);
                latestGeneration = toggleGenerations.getOrDefault(
                        str, appliedGeneration);
            }
            if (latest != null && (latest != targetState || latestGeneration != appliedGeneration)) {
                
                FileLog.d("nimarko: reconciling toggle for " + str
                        + " (applied=" + targetState + ", latest=" + latest + ")");
                PluginDebugLog.log("CTRL toggle reconcile plugin=" + str
                        + " applied=" + targetState
                        + "@" + appliedGeneration
                        + " latest=" + latest
                        + "@" + latestGeneration);
                Utilities.pluginsQueue.postRunnable(() -> runToggleLoop(
                        str, latestGeneration, scheduledEpoch));
            } else {
                List<PendingToggleCallback> completionCallbacks;
                synchronized (generationLock(str)) {
                    if (!isControllerLifecycleCurrent(scheduledEpoch)) {
                        return;
                    }
                    if (toggleGenerations.getOrDefault(str, 0) != appliedGeneration
                            || !pendingToggleState.remove(str, targetState)) {
                        Utilities.pluginsQueue.postRunnable(() -> runToggleLoop(
                                str,
                                toggleGenerations.getOrDefault(
                                        str, appliedGeneration),
                                scheduledEpoch));
                        return;
                    }
                    completionCallbacks = drainPendingToggleCallbacksLocked(str);
                    enablingInProgress.remove(str);
                }
                deliverToggleCallbacks(
                        completionCallbacks, targetState,
                        appliedGeneration, scheduledEpoch, errStr);
            }
        };
        try {
            PluginDebugLog.log("CTRL engine invoke plugin=" + str
                    + " target=" + targetState
                    + " generation=" + appliedGeneration
                    + " engine="
                    + pluginEngine.getClass().getSimpleName());
            pluginEngine.setPluginEnabled(
                    str, targetState, appliedGeneration,
                    engineCompletion);
        } catch (Throwable toggleFailure) {
            FileLog.e("nimarko: plugin toggle call failed for " + str,
                    toggleFailure);
            engineCompletion.run(
                    "Plugin toggle failed: "
                            + String.valueOf(toggleFailure.getMessage()));
        }
        invalidateInterestedPluginsCache();
    }

    private List<PendingToggleCallback> drainPendingToggleCallbacksLocked(
            String pluginId) {
        CopyOnWriteArrayList<PendingToggleCallback> callbacks =
                pendingToggleCallbacks.remove(pluginId);
        return callbacks == null
                ? new ArrayList<>()
                : new ArrayList<>(callbacks);
    }

    private void deliverToggleCallbacks(
            List<PendingToggleCallback> callbacks,
            boolean finalState, int appliedGeneration,
            long appliedEpoch,
            String error) {
        if (callbacks == null || callbacks.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            for (PendingToggleCallback pending : callbacks) {
                if (pending == null || pending.callback == null) continue;
                try {
                    
                    pending.callback.run(
                            pending.requestedState == finalState
                                    && pending.generation
                                            == appliedGeneration
                                    && pending.lifecycleEpoch
                                            == appliedEpoch
                                    ? error : null);
                } catch (Throwable callbackFailure) {
                    FileLog.e("Plugin toggle callback failed", callbackFailure);
                }
            }
        });
    }

    private void deliverToggleCallbacks(
            List<PendingToggleCallback> callbacks,
            boolean finalState, String ignoredError) {
        deliverToggleCallbacks(
                callbacks, finalState, Integer.MIN_VALUE,
                Long.MIN_VALUE, null);
    }

    public void completePluginToggleForAbandonedRuntime(
            String pluginId, String error) {
        if (TextUtils.isEmpty(pluginId)) return;
        final List<PendingToggleCallback> callbacks;
        final boolean requestedState;
        final int generation;
        final long lifecycleEpoch = controllerLifecycleEpoch.get();
        synchronized (generationLock(pluginId)) {
            Boolean pending = pendingToggleState.remove(pluginId);
            requestedState = pending != null && pending;
            generation = toggleGenerations.getOrDefault(pluginId, 0);
            callbacks = drainPendingToggleCallbacksLocked(pluginId);
            enablingInProgress.remove(pluginId);
            Plugin plugin = plugins.get(pluginId);
            if (plugin != null) {
                plugin.setEnabled(false);
            }
        }
        invalidateInterestedPluginsCache();
        notifyPluginsChanged();
        deliverToggleCallbacks(
                callbacks, requestedState, generation,
                lifecycleEpoch, requestedState ? error : null);
    }

    public boolean forceDisablePluginDurably(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) return false;
        final PluginCleanup cleanup;
        final List<PendingToggleCallback> supersededCallbacks;
        final boolean preferenceSaved;
        synchronized (generationLock(pluginId)) {
            toggleGenerations.put(
                    pluginId,
                    toggleGenerations.getOrDefault(pluginId, 0) + 1);
            pendingToggleState.remove(pluginId);
            enablingInProgress.remove(pluginId);
            supersededCallbacks =
                    drainPendingToggleCallbacksLocked(pluginId);

            Plugin plugin = plugins.get(pluginId);
            if (plugin != null) {
                plugin.setEnabled(false);
            }

            boolean committed = false;
            if (preferences != null) {
                try {
                    committed = preferences.edit()
                            .putBoolean(
                                    PREF_PLUGIN_ENABLED_KEY_PREFIX
                                            + pluginId,
                                    false)
                            .commit();
                } catch (Throwable preferenceFailure) {
                    FileLog.e("Could not persist watchdog disable for "
                            + pluginId, preferenceFailure);
                }
            }
            preferenceSaved = committed;
            cleanup = detachPluginRuntimeLocked(pluginId);
        }

        finishPluginDeactivation(cleanup);
        deliverToggleCallbacks(
                supersededCallbacks, false, null);
        invalidateInterestedPluginsCache();
        notifyPluginsChanged();
        return preferenceSaved;
    }

    public boolean forceDeletePluginDurably(String pluginId) {
        if (TextUtils.isEmpty(pluginId)
                || !pluginId.matches("^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")) {
            return false;
        }
        final PluginCleanup cleanup;
        final List<PendingToggleCallback> supersededCallbacks;
        final boolean preferenceSaved;
        synchronized (generationLock(pluginId)) {
            try {
                if (!PythonPluginsEngine.prepareDurablePluginDeletion(
                        this, pluginId)) {
                    return false;
                }
            } catch (Throwable preparationFailure) {
                FileLog.e("Could not prepare watchdog deletion for "
                        + pluginId, preparationFailure);
                return false;
            }

            toggleGenerations.put(
                    pluginId,
                    toggleGenerations.getOrDefault(pluginId, 0) + 1);
            pendingToggleState.remove(pluginId);
            enablingInProgress.remove(pluginId);
            supersededCallbacks =
                    drainPendingToggleCallbacksLocked(pluginId);

            Plugin plugin = plugins.get(pluginId);
            if (plugin != null) {
                plugin.setEnabled(false);
            }

            boolean committed = false;
            if (preferences != null) {
                try {
                    committed = preferences.edit()
                            .putBoolean(
                                    PREF_PLUGIN_ENABLED_KEY_PREFIX
                                            + pluginId,
                                    false)
                            .commit();
                } catch (Throwable preferenceFailure) {
                    FileLog.e("Could not persist watchdog delete disable for "
                            + pluginId, preferenceFailure);
                }
            }
            preferenceSaved = committed;
            cleanup = detachPluginRuntimeLocked(pluginId);
        }

        finishPluginDeactivation(cleanup);
        deliverToggleCallbacks(
                supersededCallbacks, false, null);
        invalidateInterestedPluginsCache();
        notifyPluginsChanged();
        if (!preferenceSaved) {
            FileLog.w("Frozen plugin disable preference was not persisted; "
                    + "durable delete recovery will still remove "
                    + pluginId);
        }
        return true;
    }

    public void deletePlugin(final String str, final Utilities.Callback<String> callback) {
        if (TextUtils.isEmpty(str)
                || !str.matches("^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.run("Invalid plugin id"));
            }
            return;
        }
        DispatchQueue queue = getOrCreatePluginsQueue();
        if (Thread.currentThread() != queue) {
            if (!queue.postRunnable(() -> deletePlugin(str, callback))
                    && callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.run("Plugin queue is unavailable"));
            }
            return;
        }
        PluginCleanup immediateCleanup = null;
        List<PendingToggleCallback> supersededCallbacks =
                Collections.emptyList();
        boolean deletionPrepared;
        synchronized (generationLock(str)) {
            
            try {
                deletionPrepared =
                        PythonPluginsEngine.prepareDurablePluginDeletion(
                                this, str);
            } catch (Throwable preparationFailure) {
                deletionPrepared = false;
                FileLog.e("Could not prepare durable plugin deletion for "
                        + str, preparationFailure);
            }
            if (deletionPrepared) {
                toggleGenerations.put(
                        str, toggleGenerations.getOrDefault(str, 0) + 1);
                pendingToggleState.remove(str);
                enablingInProgress.remove(str);
                supersededCallbacks =
                        drainPendingToggleCallbacksLocked(str);
                Plugin plugin = plugins.get(str);
                if (plugin != null) {
                    plugin.setEnabled(false);
                }
                immediateCleanup = detachPluginRuntimeLocked(str);
            }
        }
        if (!deletionPrepared) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.run(
                                "Could not prepare plugin deletion: "
                                        + str));
            }
            return;
        }
        if (preferences != null) {
            preferences.edit()
                    .putBoolean(
                            PREF_PLUGIN_ENABLED_KEY_PREFIX + str,
                            false)
                    .commit();
        }
        finishPluginDeactivation(immediateCleanup);
        deliverToggleCallbacks(
                supersededCallbacks, false, null);
        invalidateInterestedPluginsCache();
        notifyPluginsChanged();
        PluginsEngine pluginEngine = getPluginEngine(str);
        if (pluginEngine != null) {
            pluginEngine.deletePlugin(str, callback);
        } else if (callback != null) {
            AndroidUtilities.runOnUIThread(() ->
                    callback.run("Plugin engine is unavailable"));
        }
    }

    public void cleanupPlugin(String str) {
        cleanupPlugin(str, captureCurrentPluginRuntime());
    }

    public void cleanupPlugin(String str, PluginRuntimeToken runtimeToken) {
        if (TextUtils.isEmpty(str)) return;
        final PluginCleanup cleanup;
        synchronized (generationLock(str)) {
            if (runtimeToken == null
                    && currentRuntimeByPlugin.get(str) != null) {
                
                FileLog.w("Skipped tokenless cleanup for exact runtime "
                        + currentRuntimeByPlugin.get(str).token);
                return;
            }
            if (runtimeToken != null
                    && !str.equals(runtimeToken.getPluginId())) {
                FileLog.w("Rejected cleanup token " + runtimeToken
                        + " for plugin " + str);
                return;
            }
            cleanup = detachPluginRuntimeLocked(str, runtimeToken);
        }
        finishPluginDeactivation(cleanup);
    }

    private PluginCleanup detachPluginRuntimeLocked(String str) {
        return detachPluginRuntimeLocked(str, null);
    }

    private PluginCleanup detachPluginRuntimeLocked(
            String str, PluginRuntimeToken runtimeToken) {
        
        RuntimeSlot currentSlot = currentRuntimeByPlugin.get(str);
        boolean ownsCurrent = runtimeToken == null
                || (currentSlot != null && currentSlot.token.equals(runtimeToken));
        PluginRuntimeToken quiescenceToken = runtimeToken;
        if (runtimeToken == null) {
            quiescenceToken = revokePluginRuntimeLocked(str);
        } else {
            revokePluginRuntime(runtimeToken);
        }
        PluginRuntimeToken detachToken =
                runtimeToken != null ? runtimeToken : quiescenceToken;
        if (ownsCurrent) {
            runtimeEpochs.put(str, runtimeEpochs.getOrDefault(str, 0) + 1);
        }

        Set<HookRecord> pluginHooks = this.hooks.get(str);
        List<HookRecord> detachedHooks = Collections.emptyList();
        if (pluginHooks != null && !pluginHooks.isEmpty()) {
            if (detachToken == null) {
                if (this.hooks.remove(str, pluginHooks)) {
                    detachedHooks = new ArrayList<>(pluginHooks);
                }
            } else {
                detachedHooks = pluginHooks.stream()
                        .filter(record -> detachToken.equals(record.getRuntimeToken()))
                        .collect(Collectors.toList());
                if (!detachedHooks.isEmpty()) {
                    pluginHooks.removeAll(detachedHooks);
                    if (pluginHooks.isEmpty()) {
                        this.hooks.remove(str, pluginHooks);
                    }
                }
            }
        }
        if (!detachedHooks.isEmpty()) {
            invalidateHooksCache();
        }

        boolean settingsDetached = false;
        List<SettingItem> detachedSettings = null;
        if (detachToken == null) {
            settingsRuntimeTokens.remove(str);
            detachedSettings = this.settings.remove(str);
            settingsDetached = detachedSettings != null;
        } else {
            PluginRuntimeToken settingsOwner = settingsRuntimeTokens.get(str);
            if (detachToken.equals(settingsOwner)
                    && settingsRuntimeTokens.remove(str, settingsOwner)) {
                detachedSettings = this.settings.remove(str);
                settingsDetached = detachedSettings != null;
            }
        }
        clearSettingPythonReferences(detachedSettings);
        boolean menuItemsDetached = false;
        for (MenuItemRecord record : new ArrayList<>(this.menuItemsById.values())) {
            if (!str.equals(record.pluginId)
                    || (detachToken != null && !detachToken.equals(record.runtimeToken))
                    || !this.menuItemsById.remove(record.itemId, record)) {
                continue;
            }
            CopyOnWriteArrayList<MenuItemRecord> items =
                    this.menuItemsByMenuType.get(record.menuType);
            if (items != null) {
                items.remove(record);
                if (items.isEmpty()) {
                    this.menuItemsByMenuType.remove(record.menuType, items);
                }
            }
            record.releaseCallback(record.runtimeToken);
            menuItemsDetached = true;
        }
        app.nimarkogram.messenger.plugins.intents.IntentsController.getInstance()
                .removeIntentHooksByPluginId(str, detachToken);
        
        PluginRuntimeToken delayedCleanupToken = detachToken;
        return new PluginCleanup(str, delayedCleanupToken, quiescenceToken,
                detachedHooks, settingsDetached, menuItemsDetached, true);
    }

    private static void clearSettingPythonReferences(
            List<SettingItem> definitions) {
        if (definitions == null) return;
        for (SettingItem item : definitions) {
            if (item == null) continue;
            try {
                item.clearPythonReferences();
            } catch (Throwable failure) {
                FileLog.e("Unable to clear plugin setting callbacks",
                        failure);
            }
        }
    }

    private void finishPluginDeactivation(PluginCleanup cleanup) {
        if (cleanup == null) return;
        PluginDebugLog.log("CTRL deactivation begin plugin="
                + cleanup.pluginId
                + " runtime=" + cleanup.runtimeToken
                + " quiescence=" + cleanup.quiescenceToken
                + " hooks=" + cleanup.detachedHooks.size()
                + " settings=" + cleanup.settingsDetached
                + " menuItems=" + cleanup.menuItemsDetached
                + " pythonRegistries="
                + cleanup.cleanPythonRegistries);
        if (cleanup.settingsDetached) {
            AndroidUtilities.runOnUIThread(() ->
                    NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(
                            NotificationCenter.pluginSettingsUnregistered,
                            cleanup.pluginId, cleanup.runtimeToken));
        }
        if (cleanup.menuItemsDetached) {
            AndroidUtilities.runOnUIThread(() ->
                    NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(
                            NotificationCenter.pluginMenuItemsUpdated));
        }
        if (cleanup.cleanPythonRegistries) {
            if (cleanup.runtimeToken != null) {
                app.nimarkogram.messenger.plugins.ui.PluginUiRegistry.cleanup(
                        cleanup.runtimeToken);
            } else {
                app.nimarkogram.messenger.plugins.ui.PluginUiRegistry.cleanupPlugin(
                        cleanup.pluginId);
            }
        }
        Runnable externalCleanup = () -> cleanupDetachedHooks(cleanup);
        DispatchQueue queue = Utilities.pluginsQueue;
        if (Thread.currentThread() == queue) {
            externalCleanup.run();
        } else {
            boolean posted = queue.postRunnable(externalCleanup);
            PluginDebugLog.log("CTRL deactivation hook cleanup queued plugin="
                    + cleanup.pluginId + " posted=" + posted
                    + " queueAlive=" + queue.isAlive());
        }
        if (cleanup.cleanPythonRegistries) {
            runWhenPluginRuntimeQuiescent(cleanup.quiescenceToken, () -> {
                
                pluginRegistryCleanupQueue.postRunnable(
                        () -> cleanupPythonRegistries(cleanup));
            });
        }
        PluginDebugLog.log("CTRL deactivation published plugin="
                + cleanup.pluginId + " runtime="
                + cleanup.runtimeToken);
    }

    private void cleanupDetachedHooks(PluginCleanup cleanup) {
        for (HookRecord hookRecord : cleanup.detachedHooks) {
            try {
                hookRecord.cleanup();
            } catch (Throwable th) {
                FileLog.e("Error cleaning runtime hook for plugin " + cleanup.pluginId, th);
            }
        }
        if (!cleanup.detachedHooks.isEmpty()) {
            FileLog.d("Removed all (" + cleanup.detachedHooks.size()
                    + ") hooks for plugin " + cleanup.pluginId);
        }
    }

    private void cleanupPythonRegistries(PluginCleanup cleanup) {
        try {
            
            if (cleanup.runtimeToken == null
                    && getCurrentPluginRuntime(cleanup.pluginId) != null) {
                FileLog.d("Skipped stale broad Python cleanup for active plugin "
                        + cleanup.pluginId);
                return;
            }
            if (com.chaquo.python.Python.isStarted()
                    && !PythonPluginsEngine
                            .isProcessPythonRuntimeAbandoned()) {
                com.chaquo.python.Python.getInstance().getModule("intents")
                        .callAttr("_remove_intent_hooks_for_plugin",
                                cleanup.pluginId, cleanup.runtimeToken);
                com.chaquo.python.Python.getInstance().getModule("file_utils")
                        .callAttr("_remove_file_hooks_for_plugin",
                                cleanup.pluginId, cleanup.runtimeToken);
            }
        } catch (Throwable th) {
            FileLog.e("Error cleaning Python registries for plugin " + cleanup.pluginId, th);
        }
    }

    public String getPluginPath(String str) {
        PluginsEngine pluginEngine;
        if (str == null || TextUtils.isEmpty(str) || (pluginEngine = getPluginEngine(str)) == null) {
            return null;
        }
        return pluginEngine.getPluginPath(str);
    }

    public void showInstallDialog(BaseFragment baseFragment, MessageObject messageObject) {
        showInstallDialog(baseFragment, InstallPluginBottomSheet.PluginInstallParams.of(messageObject));
    }

    public static boolean maybeShowInstallDialog(MessageObject messageObject, BaseFragment fragment) {
        try {
            if (fragment != null && isPlugin(messageObject)) {
                getInstance().showInstallDialog(fragment, messageObject);
                return true;
            }
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e("nimarko: maybeShowInstallDialog failed", t);
        }
        return false;
    }

    public void showInstallDialog(BaseFragment baseFragment, String str, boolean z) {
        showInstallDialog(baseFragment, new InstallPluginBottomSheet.PluginInstallParams(str, z));
    }

    private void showInstallDialog(final BaseFragment baseFragment, InstallPluginBottomSheet.PluginInstallParams pluginInstallParams) {
        if (baseFragment == null || !AndroidUtilities.isActivityRunning(baseFragment.getParentActivity()) || TextUtils.isEmpty(pluginInstallParams.filePath)) {
            return;
        }
        File file = new File(pluginInstallParams.filePath);
        
        if (app.nimarkogram.messenger.media.NimarkoMediaController.isNimarkoMediaPluginFile(file)) {
            BulletinFactory.of(baseFragment)
                    .createSimpleBulletin(R.raw.info,
                            AndroidUtilities.replaceTags(LocaleController.getString(R.string.NM_MediaBuiltIn)))
                    .show();
            return;
        }
        
        if (app.nimarkogram.messenger.banners.NimarkoBannerController.isBannerPluginFile(file)) {
            BulletinFactory.of(baseFragment)
                    .createSimpleBulletin(R.raw.info,
                            AndroidUtilities.replaceTags(LocaleController.getString(R.string.NM_BAN_PluginIntegrated)))
                    .show();
            return;
        }
        
        if (app.nimarkogram.messenger.wsbypass.WsBypassCore.isWsBypassPluginFile(file)) {
            BulletinFactory.of(baseFragment)
                    .createSimpleBulletin(R.raw.info,
                            AndroidUtilities.replaceTags(LocaleController.getString(R.string.NM_WSBypassBuiltIn)))
                    .show();
            return;
        }
        if (!NimarkoConfig.pluginsEngine) {
            BulletinFactory.of(baseFragment).createSimpleBulletin(R.raw.error, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginNotEnabled, file.getName())), LocaleController.getString(R.string.Enable), 2750, () -> baseFragment.presentFragment(new PluginsActivity())).show();
            return;
        }
        
        if (NimarkoConfig.pluginsSafeMode) {
            BulletinFactory.of(baseFragment).createSimpleBulletin(R.raw.error,
                    AndroidUtilities.replaceTags(
                            LocaleController.getString(R.string.PluginsSafeModeOn))).show();
            return;
        }
        PluginsEngine pluginEngine = getPluginEngine(file);
        if (pluginEngine == null) {
            
            BulletinFactory.of(baseFragment).createSimpleBulletin(R.raw.error,
                    AndroidUtilities.replaceTags(
                            LocaleController.getString(R.string.NM_AddPluginFailed))).show();
            return;
        }
        pluginEngine.showInstallDialog(baseFragment, pluginInstallParams);
    }

    public void loadPluginSettings() {
        loadPluginSettings(null);
    }

    public void loadPluginSettings(final String str) {
        if (TextUtils.isEmpty(str)) {
            for (String str2 : this.plugins.keySet()) {
                Plugin plugin = this.plugins.get(str2);
                if (plugin != null && plugin.isEnabled() && plugin.getError() == null) {
                    loadPluginSettings(str2);
                } else if (plugin != null) {
                    invalidatePluginSettings(str2);
                }
            }
            return;
        }
        loadPluginSettings(str, getPluginToggleGeneration(str));
    }

    public void loadPluginSettings(final String str, final int generation) {
        if (TextUtils.isEmpty(str)) return;
        Utilities.pluginsQueue.postRunnable(() -> {
            try {
                PluginsEngine pluginEngine = getPluginEngine(str);
                if (pluginEngine == null) {
                    return;
                }
                PluginRuntimeToken runtimeToken = getCurrentPluginRuntime(str);
                if (runtimeToken == null || runtimeToken.getGeneration() != generation) {
                    return;
                }
                List<SettingItem> listLoadPluginSettings = pluginEngine.loadPluginSettings(str);
                if (listLoadPluginSettings == null) {
                    invalidatePluginSettings(str, generation);
                    return;
                }
                List<SettingItem> replacedSettings = null;
                boolean published = false;
                synchronized (generationLock(str)) {
                    if (toggleGenerations.getOrDefault(str, 0) == generation
                            && isPluginActive(str)
                            && isPluginRuntimeCurrent(runtimeToken)) {
                        replacedSettings =
                                this.settings.put(
                                        str, listLoadPluginSettings);
                        this.settingsRuntimeTokens.put(str, runtimeToken);
                        published = true;
                    }
                }
                if (!published) {
                    clearSettingPythonReferences(
                            listLoadPluginSettings);
                    return;
                }
                if (replacedSettings != listLoadPluginSettings) {
                    clearSettingPythonReferences(replacedSettings);
                }
                FileLog.d("Registered settings for plugin " + str);
                AndroidUtilities.runOnUIThread(() ->
                        NotificationCenter.getGlobalInstance()
                                .postNotificationNameOnUIThread(
                                        NotificationCenter.pluginSettingsRegistered,
                                        str, runtimeToken));
            } catch (Throwable th) {
                FileLog.e(th);
                invalidatePluginSettings(str, generation);
            }
        });
    }

    public boolean hasPluginSettings(String str) {
        return !TextUtils.isEmpty(str) && this.settings.containsKey(str);
    }

    public void invalidatePluginSettings(final String str) {
        if (TextUtils.isEmpty(str)) {
            return;
        }
        PluginRuntimeToken runtimeToken =
                this.settingsRuntimeTokens.remove(str);
        List<SettingItem> removedSettings = this.settings.remove(str);
        clearSettingPythonReferences(removedSettings);
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance()
                        .postNotificationNameOnUIThread(
                                NotificationCenter.pluginSettingsUnregistered,
                                str, runtimeToken));
    }

    private void invalidatePluginSettings(final String str, final int generation) {
        boolean removed;
        PluginRuntimeToken runtimeToken;
        List<SettingItem> removedSettings;
        synchronized (generationLock(str)) {
            if (toggleGenerations.getOrDefault(str, 0) != generation) return;
            runtimeToken = this.settingsRuntimeTokens.remove(str);
            removedSettings = this.settings.remove(str);
            removed = removedSettings != null;
        }
        clearSettingPythonReferences(removedSettings);
        if (removed) {
            AndroidUtilities.runOnUIThread(() ->
                    NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(
                            NotificationCenter.pluginSettingsUnregistered,
                            str, runtimeToken));
        }
    }

    public boolean clearPluginSettingsPreferences(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        boolean cleared = true;
        PluginsEngine pluginEngine = getPluginEngine(str);
        if (pluginEngine != null) {
            cleared = pluginEngine.clearPluginSettings(str);
        }
        if (this.preferences == null) {
            return cleared;
        }
        String str2 = PREF_PLUGIN_ENABLED_KEY_PREFIX + str;
        if (this.preferences.contains(str2)) {
            cleared &= this.preferences.edit().remove(str2).commit();
        }
        return cleared;
    }

    public Map<String, ?> getPluginSettingsPreferences(String str) {
        PluginsEngine pluginEngine = getPluginEngine(str);
        if (pluginEngine != null) {
            return pluginEngine.getAllPluginSettings(str);
        }
        return null;
    }

    public boolean hasPluginSettingsPreferences(String str) {
        Map<String, ?> pluginSettingsPreferences = getPluginSettingsPreferences(str);
        return (pluginSettingsPreferences != null && !pluginSettingsPreferences.isEmpty());
    }

    public boolean getPluginSettingBoolean(String str, String str2, boolean z) {
        PluginsEngine pluginEngine = getPluginEngine(str);
        if (pluginEngine != null) {
            Object pluginSetting = pluginEngine.getPluginSetting(str, str2, Boolean.valueOf(z));
            if (pluginSetting instanceof Boolean) {
                return ((Boolean) pluginSetting).booleanValue();
            }
        }
        return z;
    }

    public String getPluginSettingString(String str, String str2, String str3) {
        Object pluginSetting;
        PluginsEngine pluginEngine = getPluginEngine(str);
        return (pluginEngine == null || (pluginSetting = pluginEngine.getPluginSetting(str, str2, str3)) == null) ? str3 : pluginSetting.toString();
    }

    public int getPluginSettingInt(String str, String str2, int i) {
        PluginsEngine pluginEngine = getPluginEngine(str);
        if (pluginEngine != null) {
            Object pluginSetting = pluginEngine.getPluginSetting(str, str2, Integer.valueOf(i));
            if (pluginSetting instanceof Number) {
                return ((Number) pluginSetting).intValue();
            }
        }
        return i;
    }

    public void setPluginSetting(String str, String str2, Object obj) {
        PluginsEngine pluginEngine = getPluginEngine(str);
        if (pluginEngine != null) {
            pluginEngine.setPluginSetting(str, str2, obj);
            loadPluginSettings(str);
        }
    }

    private void addHook(String str, HookRecord hookRecord, String str2) {
        if (TextUtils.isEmpty(str) || hookRecord == null) {
            return;
        }
        boolean rejected = false;
        synchronized (generationLock(str)) {
            if (!canPluginRegisterRuntimeLocked(str)) {
                rejected = true;
            } else if (this.hooks.computeIfAbsent(str, k -> new CopyOnWriteArraySet<>()).add(hookRecord)) {
                FileLog.d(str2);
                invalidateHooksCache();
            }
        }
        if (rejected) {
            FileLog.w("Rejected late runtime hook registration for inactive plugin " + str);
            finishPluginDeactivation(new PluginCleanup(
                    str, Collections.singletonList(hookRecord), false, false, false));
        }
    }

    public void addEventHook(String str, String str2, boolean z, int i) {
        addHook(str, new EventHookRecord(str, str2, z, i), "Added event hook '" + str2 + "' for plugin " + str);
    }

    private void removeHook(String str, java.util.function.Predicate<HookRecord> predicate, String str2) {
        if (TextUtils.isEmpty(str)) return;
        final List<HookRecord> toRemove;
        synchronized (generationLock(str)) {
            Set<HookRecord> set = this.hooks.get(str);
            if (set == null || set.isEmpty()) return;
            toRemove = set.stream().filter(predicate).collect(Collectors.toList());
            if (toRemove.isEmpty()) return;
            set.removeAll(toRemove);
            if (set.isEmpty()) {
                this.hooks.remove(str, set);
            }
            invalidateHooksCache();
        }

        FileLog.d(str2);
        finishPluginDeactivation(new PluginCleanup(str, toRemove, false, false, false));
    }

    public void removeEventHook(String str, final String str2) {
        removeHook(str, hookRecord -> (hookRecord instanceof EventHookRecord) && java.util.Objects.equals(((EventHookRecord) hookRecord).getHookName(), str2), "Removed event hook(s) matching name '" + str2 + "' for plugin " + str);
    }

    public void addXposedHook(String str, XC_MethodHook.Unhook unhook) {
        if (unhook == null) {
            FileLog.w("Ignored null Xposed hook registration for plugin " + str);
            return;
        }
        addHook(str, new XposedHookRecord(unhook), "Added Xposed hook for plugin " + str);
    }

    public void addXposedHooks(String str, ArrayList<XC_MethodHook.Unhook> arrayList) {
        if (TextUtils.isEmpty(str) || arrayList == null || arrayList.isEmpty()) return;
        List<HookRecord> records = new ArrayList<>(arrayList.size());
        for (XC_MethodHook.Unhook unhook : arrayList) {
            if (unhook != null) {
                records.add(new XposedHookRecord(unhook));
            }
        }
        if (records.isEmpty()) return;
        boolean rejected;
        synchronized (generationLock(str)) {
            rejected = !canPluginRegisterRuntimeLocked(str);
            if (!rejected) {
                Set<HookRecord> pluginHooks =
                        this.hooks.computeIfAbsent(str, k -> new CopyOnWriteArraySet<>());
                if (pluginHooks.addAll(records)) {
                    invalidateHooksCache();
                }
            }
        }
        if (rejected) {
            FileLog.w("Rejected late Xposed hook batch for inactive plugin " + str);
            finishPluginDeactivation(new PluginCleanup(
                    str, records, false, false, false));
        } else {
            FileLog.d("Added " + records.size() + " Xposed hooks for plugin " + str);
        }
    }

    public void removeXposedHook(String str, final XC_MethodHook.Unhook unhook) {
        removeHook(str, hookRecord -> (hookRecord instanceof XposedHookRecord) && hookRecord.matches(unhook), "Removed Xposed hook for plugin " + str);
    }

    public void removeHooksByPluginId(String str) {
        if (TextUtils.isEmpty(str)) return;
        final List<HookRecord> detachedHooks;
        synchronized (generationLock(str)) {
            Set<HookRecord> setRemove = this.hooks.remove(str);
            if (setRemove == null) return;
            detachedHooks = new ArrayList<>(setRemove);
            invalidateHooksCache();
        }
        finishPluginDeactivation(new PluginCleanup(str, detachedHooks, false, false, false));
    }

    public String addMenuItem(String str, PyObject pyObject) {
        if (TextUtils.isEmpty(str) || pyObject == null) return null;
        if (!isPluginEngineAvailable()) return null;
        try {
            
            final MenuItemRecord menuItemRecord = new MenuItemRecord(str, pyObject);
            if (menuItemRecord.menuType == null) {
                return null;
            }
            synchronized (generationLock(str)) {
                if (!canPluginRegisterRuntimeLocked(str)) {
                    menuItemRecord.releaseCallback(
                            menuItemRecord.runtimeToken);
                    return null;
                }
                MenuItemRecord menuItemRecord2 = this.menuItemsById.get(menuItemRecord.itemId);
                if (menuItemRecord2 != null && !menuItemRecord2.pluginId.equals(str)) {
                    FileLog.w(String.format("Plugin %s tried to add a menu item: %s, which is already used by plugin %s", str, menuItemRecord.itemId, menuItemRecord2.pluginId));
                    menuItemRecord.releaseCallback(
                            menuItemRecord.runtimeToken);
                    return null;
                }
                this.menuItemsById.put(
                        menuItemRecord.itemId, menuItemRecord);
                
                for (Map.Entry<String, CopyOnWriteArrayList<MenuItemRecord>>
                        entry : this.menuItemsByMenuType.entrySet()) {
                    CopyOnWriteArrayList<MenuItemRecord> oldItems =
                            entry.getValue();
                    for (MenuItemRecord stale :
                            new ArrayList<>(oldItems)) {
                        if (stale != menuItemRecord
                                && str.equals(stale.pluginId)
                                && menuItemRecord.itemId.equals(
                                        stale.itemId)
                                && oldItems.remove(stale)) {
                            stale.releaseCallback(stale.runtimeToken);
                        }
                    }
                    if (oldItems.isEmpty()) {
                        this.menuItemsByMenuType.remove(
                                entry.getKey(), oldItems);
                    }
                }
                
                this.menuItemsByMenuType.compute(menuItemRecord.menuType, (key, list) -> {
                     CopyOnWriteArrayList<MenuItemRecord> newList = list == null ? new CopyOnWriteArrayList<>() : new CopyOnWriteArrayList<>(list);
                     newList.removeIf(item -> item.itemId.equals(menuItemRecord.itemId));
                     newList.add(menuItemRecord);
                     Collections.sort(newList, (o1, o2) -> Integer.compare(o2.priority, o1.priority));
                     return newList;
                });
            }
            FileLog.d("Added menu item: " + menuItemRecord.itemId + " for plugin " + str + " in type " + menuItemRecord.menuType);
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginMenuItemsUpdated));
            return menuItemRecord.itemId;
        } catch (Exception e) {
            
            FileLog.d("nimarko: addMenuItem failed for plugin " + str + ": " + e);
        }
        return null;
    }

    public boolean removeMenuItem(String str, String str2) {
        if (TextUtils.isEmpty(str) || TextUtils.isEmpty(str2)) return false;
        final MenuItemRecord menuItemRecordRemove;
        synchronized (generationLock(str)) {
            MenuItemRecord current = this.menuItemsById.get(str2);
            if (current == null || current.menuType == null || !str.equals(current.pluginId)
                    || !this.menuItemsById.remove(str2, current)) {
                return false;
            }
            menuItemRecordRemove = current;
            CopyOnWriteArrayList<MenuItemRecord> items =
                    this.menuItemsByMenuType.get(menuItemRecordRemove.menuType);
            if (items != null) {
                items.remove(menuItemRecordRemove);
                if (items.isEmpty()) {
                    this.menuItemsByMenuType.remove(menuItemRecordRemove.menuType, items);
                }
            }
            menuItemRecordRemove.releaseCallback(
                    menuItemRecordRemove.runtimeToken);
        }
        FileLog.d("Removed menu item: " + str2 + " for plugin " + str);
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginMenuItemsUpdated));
        return true;
    }

    public void removeMenuItemsByPluginId(String str) {
        if (TextUtils.isEmpty(str)) return;
        boolean removed = false;
        synchronized (generationLock(str)) {
            for (MenuItemRecord record : new ArrayList<>(this.menuItemsById.values())) {
                if (!str.equals(record.pluginId)
                        || !this.menuItemsById.remove(record.itemId, record)) {
                    continue;
                }
                CopyOnWriteArrayList<MenuItemRecord> items =
                        this.menuItemsByMenuType.get(record.menuType);
                if (items != null) {
                    items.remove(record);
                    if (items.isEmpty()) {
                        this.menuItemsByMenuType.remove(record.menuType, items);
                    }
                }
                record.releaseCallback(record.runtimeToken);
                removed = true;
            }
        }
        FileLog.d("Removed all menu items for plugin: " + str);
        if (removed) {
            AndroidUtilities.runOnUIThread(() ->
                    NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(
                            NotificationCenter.pluginMenuItemsUpdated));
        }
    }

    public boolean isPluginActive(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) {
            return false;
        }
        Plugin plugin = this.plugins.get(pluginId);
        return plugin != null && plugin.isEnabled() && !plugin.hasError();
    }

    public java.util.List<MenuItemRecord> getMenuItemsForLocation(String str, MenuContextBuilder menuContextBuilder) {
        if (menuContextBuilder == null) {
            return getMenuItemsForLocation(str, new HashMap<>());
        }
        return getMenuItemsForLocation(str, menuContextBuilder.build());
    }

    public java.util.List<MenuItemRecord> getMenuItemsForLocation(String str, Map<String, Object> map) {
        if (!isPluginEngineAvailable() || TextUtils.isEmpty(str)) {
            return Collections.emptyList();
        }
        CopyOnWriteArrayList<MenuItemRecord> copyOnWriteArrayList = this.menuItemsByMenuType.get(str);
        if (copyOnWriteArrayList == null || copyOnWriteArrayList.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<MenuItemRecord> arrayList = new ArrayList<>();
        for (MenuItemRecord menuItemRecord : copyOnWriteArrayList) {
            boolean runtimeActive = menuItemRecord.runtimeToken != null
                    && getPluginRuntimeTaskDecision(menuItemRecord.runtimeToken)
                            == RUNTIME_TASK_RUN;
            if (runtimeActive && menuItemRecord.onClickCallback != null
                    && menuItemRecord.checkCondition(map)) {
                arrayList.add(menuItemRecord);
            }
        }
        return arrayList;
    }

    void notifyPluginsChanged() {
        PluginDebugLog.log("CTRL notifyPluginsChanged pending="
                + pendingToggleState.size()
                + " enabling=" + enablingInProgress.size()
                + " runtimes=" + currentRuntimeByPlugin.size());
        AndroidUtilities.cancelRunOnUIThread(this.updateNotificationRunnable);
        AndroidUtilities.runOnUIThread(this.updateNotificationRunnable, 150L);
    }

    public void executeOnAppEvent(final String str) {
        if (!NimarkoConfig.pluginsEngine || NimarkoConfig.pluginsSafeMode) {
            return;
        }
        final Runnable dispatch = () -> {
            
            if (!NimarkoConfig.pluginsEngine || NimarkoConfig.pluginsSafeMode
                    || !isPluginEngineAvailable()) {
                return;
            }
            FileLog.d("Execute scripts on app event " + str);
            engines.values().forEach(engine -> {
                try { engine.executeOnAppEvent(str); } catch (Throwable t) { FileLog.e(t); }
            });
        };
        final DispatchQueue queue = getOrCreatePluginsQueue();
        if (Thread.currentThread() == queue) {
            dispatch.run();
        } else if (!queue.postRunnable(dispatch)) {
            FileLog.e("Failed to enqueue plugin app event " + str);
        }
    }

    private void invalidateInterestedPluginsCache() {
        synchronized (hooksCacheLock) {
            interestedPluginsRevision.incrementAndGet();
            interestedPluginsCache.clear();
        }
    }

    private void invalidateHooksCache() {
        synchronized (hooksCacheLock) {
            hooksCacheDirty = true;
            interestedPluginsRevision.incrementAndGet();
            interestedPluginsCache.clear();
        }
    }

    private boolean isInterestedPluginKnown(InterestedPlugin owner) {
        if (owner == null || TextUtils.isEmpty(owner.pluginId)) return false;
        if (owner.runtimeToken != null) {
            return isPluginRuntimeCallbackAllowed(owner.runtimeToken);
        }
        Plugin plugin = plugins.get(owner.pluginId);
        return plugin != null && plugin.isEnabled() && !plugin.hasError();
    }

    private boolean isInterestedPluginActive(InterestedPlugin owner) {
        if (owner == null || TextUtils.isEmpty(owner.pluginId)) return false;
        if (owner.runtimeToken != null) {
            return getPluginRuntimeTaskDecision(owner.runtimeToken)
                    == RUNTIME_TASK_RUN;
        }
        Plugin plugin = plugins.get(owner.pluginId);
        return plugin != null && plugin.isEnabled() && !plugin.hasError();
    }

    private List<InterestedPlugin> getInterestedPlugins(String str) {
        if (TextUtils.isEmpty(str)) {
            return Collections.emptyList();
        }
        for (;;) {
            List<InterestedPlugin> cached =
                    this.interestedPluginsCache.get(str);
            if (cached != null) return cached;

            long revision = interestedPluginsRevision.get();
            rebuildHooksCacheIfNeeded();
            HashMap<InterestedPlugin, Integer> map = new HashMap<>();
            java.util.List<EventHookRecord> list2 = this.exactMatchEventHooksCache.get(str);
            if (list2 != null) {
                for (final EventHookRecord eventHookRecord : list2) {
                    map.merge(
                            new InterestedPlugin(
                                    eventHookRecord.getPluginId(),
                                    eventHookRecord.getRuntimeToken()),
                            eventHookRecord.getPriority(),
                            Integer::max);
                }
            }
            for (final EventHookRecord eventHookRecord2 : this.substringMatchEventHooksCache) {
                if (eventHookRecord2.matches(str)) {
                    map.merge(
                            new InterestedPlugin(
                                    eventHookRecord2.getPluginId(),
                                    eventHookRecord2.getRuntimeToken()),
                            eventHookRecord2.getPriority(),
                            Integer::max);
                }
            }
            final List<InterestedPlugin> computed;
            if (map.isEmpty()) {
                computed = Collections.emptyList();
            } else {
                computed = map.entrySet().stream()
                        .sorted(
                                Map.Entry
                                        .<InterestedPlugin, Integer>
                                                comparingByValue()
                                        .reversed()
                                        .thenComparing(
                                                entry ->
                                                        entry.getKey()
                                                                .pluginId))
                        .map(Map.Entry::getKey)
                        
                        .filter(this::isInterestedPluginKnown)
                        .collect(Collectors.toList());
            }
            synchronized (hooksCacheLock) {
                if (interestedPluginsRevision.get() != revision) {
                    continue;
                }
                List<InterestedPlugin> raced =
                        interestedPluginsCache.putIfAbsent(str, computed);
                return raced != null ? raced : computed;
            }
        }
    }

    java.util.List<String> getInterestedPluginIds(String str) {
        return getInterestedPlugins(str).stream()
                .map(owner -> owner.pluginId)
                .collect(Collectors.toList());
    }

    private void rebuildHooksCacheIfNeeded() {
        if (this.hooksCacheDirty) {
            synchronized (this.hooksCacheLock) {
                if (this.hooksCacheDirty) {
                    Map<String, List<EventHookRecord>> map = new HashMap<>();
                    List<EventHookRecord> arrayList = new ArrayList<>();
                    
                    for (Set<HookRecord> set : this.hooks.values()) {
                        for (HookRecord hookRecord : set) {
                            if (hookRecord instanceof EventHookRecord) {
                                EventHookRecord eventHookRecord = (EventHookRecord) hookRecord;
                                if (eventHookRecord.isMatchSubstring()) {
                                    arrayList.add(eventHookRecord);
                                } else {
                                    map.computeIfAbsent(eventHookRecord.getHookName(), k -> new ArrayList<>()).add(eventHookRecord);
                                }
                            }
                        }
                    }
                    this.exactMatchEventHooksCache = map;
                    this.substringMatchEventHooksCache = arrayList;
                    this.hooksCacheDirty = false;
                }
            }
        }
    }

    private boolean pluginImplementsHook(String pluginId, String pythonHookName) {
        Plugin p = this.plugins.get(pluginId);
        if (p == null) return false;
        java.util.Set<String> impl = p.implementedHooks;
        if (impl == null) return true;
        return impl.contains(pythonHookName);
    }

    @Override
    public TLObject executePreRequestHook(String str, int i, TLObject tLObject) {
        if (isPluginEngineAvailable()) {
            List<InterestedPlugin> interestedPlugins =
                    getInterestedPlugins(str);
            if (!interestedPlugins.isEmpty()) {
                for (InterestedPlugin owner : interestedPlugins) {
                    if (!isInterestedPluginActive(owner)) continue;
                    String str2 = owner.pluginId;
                    if (!pluginImplementsHook(str2, "pre_request_hook")) continue;
                    PluginsEngine pluginEngine = getPluginEngine(str2);
                    if (pluginEngine != null) {
                        HookResult<TLObject> hookResultExecutePreRequestHook =
                                pluginEngine.executePreRequestHook(
                                        str, i, tLObject, str2,
                                        owner.runtimeToken);
                        TLObject tLObject2 = hookResultExecutePreRequestHook.result;
                        if (hookResultExecutePreRequestHook.cancel) {
                            return null;
                        }
                        if (hookResultExecutePreRequestHook.isFinal) {
                            return tLObject2;
                        }
                        tLObject = tLObject2;
                    }
                }
                return tLObject;
            }
        }
        return tLObject;
    }

    @Override
    public PluginsHooks.PostRequestResult executePostRequestHook(String str, int i, TLObject tLObject, TLRPC.TL_error tL_error) {
        if (!isPluginEngineAvailable()) {
            return new PluginsHooks.PostRequestResult(tLObject, tL_error);
        }
        List<InterestedPlugin> interestedPlugins =
                getInterestedPlugins(str);
        if (interestedPlugins.isEmpty()) {
            return new PluginsHooks.PostRequestResult(tLObject, tL_error);
        }
        TLObject tLObject2 = tLObject;
        TLRPC.TL_error tL_error2 = tL_error;
        for (InterestedPlugin owner : interestedPlugins) {
            if (!isInterestedPluginActive(owner)) continue;
            String str2 = owner.pluginId;
            if (!pluginImplementsHook(str2, "post_request_hook")) continue;
            PluginsEngine pluginEngine = getPluginEngine(str2);
            if (pluginEngine != null) {
                HookResult<PluginsHooks.PostRequestResult>
                        hookResultExecutePostRequestHook =
                                pluginEngine.executePostRequestHook(
                                        str, i, tLObject2, tL_error2, str2,
                                        owner.runtimeToken);
                if (hookResultExecutePostRequestHook.cancel) {
                    return null;
                }
                PluginsHooks.PostRequestResult postRequestResult = hookResultExecutePostRequestHook.result;
                TLObject tLObject3 = postRequestResult.response;
                TLRPC.TL_error tL_error3 = postRequestResult.error;
                if (hookResultExecutePostRequestHook.isFinal) {
                    return new PluginsHooks.PostRequestResult(tLObject3, tL_error3);
                }
                tL_error2 = tL_error3;
                tLObject2 = tLObject3;
            }
        }
        return new PluginsHooks.PostRequestResult(tLObject2, tL_error2);
    }

    @Override
    public TLRPC.Update executeUpdateHook(String str, int i, TLRPC.Update update) {
        if (isPluginEngineAvailable()) {
            List<InterestedPlugin> interestedPlugins =
                    getInterestedPlugins(str);
            if (!interestedPlugins.isEmpty()) {
                for (InterestedPlugin owner : interestedPlugins) {
                    if (!isInterestedPluginActive(owner)) continue;
                    String str2 = owner.pluginId;
                    if (!pluginImplementsHook(str2, "on_update_hook")) continue;
                    PluginsEngine pluginEngine = getPluginEngine(str2);
                    if (pluginEngine != null) {
                        HookResult<TLRPC.Update> hookResultExecuteUpdateHook =
                                pluginEngine.executeUpdateHook(
                                        str, i, update, str2,
                                        owner.runtimeToken);
                        TLRPC.Update update2 = hookResultExecuteUpdateHook.result;
                        if (hookResultExecuteUpdateHook.cancel) {
                            return null;
                        }
                        if (hookResultExecuteUpdateHook.isFinal) {
                            return update2;
                        }
                        update = update2;
                    }
                }
                return update;
            }
        }
        return update;
    }

    @Override
    public TLRPC.Updates executeUpdatesHook(String str, int i, TLRPC.Updates updates) {
        if (isPluginEngineAvailable()) {
            List<InterestedPlugin> interestedPlugins =
                    getInterestedPlugins(str);
            if (!interestedPlugins.isEmpty()) {
                for (InterestedPlugin owner : interestedPlugins) {
                    if (!isInterestedPluginActive(owner)) continue;
                    String str2 = owner.pluginId;
                    if (!pluginImplementsHook(str2, "on_updates_hook")) continue;
                    PluginsEngine pluginEngine = getPluginEngine(str2);
                    if (pluginEngine != null) {
                        HookResult<TLRPC.Updates> hookResultExecuteUpdatesHook =
                                pluginEngine.executeUpdatesHook(
                                        str, i, updates, str2,
                                        owner.runtimeToken);
                        TLRPC.Updates updates2 = hookResultExecuteUpdatesHook.result;
                        if (hookResultExecuteUpdatesHook.cancel) {
                            return null;
                        }
                        if (hookResultExecuteUpdatesHook.isFinal) {
                            return updates2;
                        }
                        updates = updates2;
                    }
                }
                return updates;
            }
        }
        return updates;
    }

    @Override
    public SendMessagesHelper.SendMessageParams executeSendMessageHook(int i, SendMessagesHelper.SendMessageParams sendMessageParams) {
        if (isPluginEngineAvailable()) {
            List<InterestedPlugin> interestedPlugins =
                    getInterestedPlugins(
                            PluginsConstants.SEND_MESSAGE_HOOK);
            if (!interestedPlugins.isEmpty()) {
                for (InterestedPlugin owner : interestedPlugins) {
                    if (!isInterestedPluginActive(owner)) continue;
                    String str = owner.pluginId;
                    if (!pluginImplementsHook(str, "on_send_message_hook")) continue;
                    PluginsEngine pluginEngine = getPluginEngine(str);
                    if (pluginEngine != null) {
                        HookResult<SendMessagesHelper.SendMessageParams>
                                hookResultExecuteSendMessageHook =
                                        pluginEngine.executeSendMessageHook(
                                                i, sendMessageParams, str,
                                                owner.runtimeToken);
                        SendMessagesHelper.SendMessageParams sendMessageParams2 = hookResultExecuteSendMessageHook.result;
                        if (hookResultExecuteSendMessageHook.cancel) {
                            return null;
                        }
                        if (hookResultExecuteSendMessageHook.isFinal) {
                            return sendMessageParams2;
                        }
                        sendMessageParams = sendMessageParams2;
                    }
                }
                return sendMessageParams;
            }
        }
        return sendMessageParams;
    }

    private static class SingletonHolder {
        private static final PluginsController INSTANCE = new PluginsController();
    }

    public static class HookResult<T> {
        public boolean cancel;
        public boolean isFinal;
        public T result;

        public HookResult(T t, boolean z, boolean z2) {
            this.result = t;
            this.cancel = z;
            this.isFinal = z2;
        }
    }

    public static class PluginValidationResult {
        public String error;
        public Plugin plugin;

        public PluginValidationResult(Plugin plugin, String str) {
            this.plugin = plugin;
            this.error = str;
        }
    }
}
