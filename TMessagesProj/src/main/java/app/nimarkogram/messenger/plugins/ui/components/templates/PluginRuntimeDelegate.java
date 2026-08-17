package app.nimarkogram.messenger.plugins.ui.components.templates;

import android.os.Handler;
import android.os.Looper;

import app.nimarkogram.messenger.plugins.PluginsController;
import org.telegram.messenger.FileLog;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PluginRuntimeDelegate {
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    public interface Callback<T> {
        T run();
    }

    interface ScopedRunnable {
        void run(SuperCallScope superCallScope);
    }

    interface ScopedCallback<T> {
        T run(SuperCallScope superCallScope);
    }

    public static final class FrameCallbackQueue
            implements PluginsController.RuntimeCallbackHolder {
        static final int MAX_PENDING_CALLBACKS = 24;

        private final PluginsController.PluginRuntimeToken runtimeToken;
        private final LinkedHashMap<Object, ScopedRunnable> pendingCallbacks =
                new LinkedHashMap<>();
        private final Runnable drainRunnable = this::drainOne;
        private boolean registered;
        private boolean closed;
        private boolean drainPosted;

        private FrameCallbackQueue(
                PluginsController.PluginRuntimeToken runtimeToken) {
            this.runtimeToken = runtimeToken;
            this.registered = runtimeToken != null
                    && PluginsController.getInstance()
                            .registerRuntimeCallbackHolder(runtimeToken, this);
            this.closed = !registered;
        }

        boolean enqueue(Object key, ScopedRunnable callback) {
            requireMainThread();
            if (key == null || callback == null) {
                return false;
            }
            int decision = PluginsController.getInstance()
                    .getPluginRuntimeTaskDecision(runtimeToken);
            if (decision == PluginsController.RUNTIME_TASK_DROP) {
                clear(runtimeToken);
                return false;
            }
            boolean shouldPost = false;
            synchronized (this) {
                if (closed) {
                    return false;
                }
                pendingCallbacks.remove(key);
                if (pendingCallbacks.size() >= MAX_PENDING_CALLBACKS) {
                    Iterator<Map.Entry<Object, ScopedRunnable>> iterator =
                            pendingCallbacks.entrySet().iterator();
                    if (iterator.hasNext()) {
                        iterator.next();
                        iterator.remove();
                    }
                }
                pendingCallbacks.put(key, callback);
                if (decision == PluginsController.RUNTIME_TASK_RUN
                        && !drainPosted) {
                    drainPosted = true;
                    shouldPost = true;
                }
            }
            if (shouldPost) {
                MAIN_HANDLER.post(drainRunnable);
            }
            return true;
        }

        public boolean isOpenFor(
                PluginsController.PluginRuntimeToken expectedRuntime) {
            synchronized (this) {
                if (closed || runtimeToken == null
                        || !runtimeToken.equals(expectedRuntime)) {
                    return false;
                }
            }
            return PluginsController.getInstance()
                    .getPluginRuntimeTaskDecision(runtimeToken)
                    == PluginsController.RUNTIME_TASK_RUN;
        }

        public void clear(
                PluginsController.PluginRuntimeToken expectedRuntime) {
            if (expectedRuntime == null
                    || !expectedRuntime.equals(runtimeToken)) {
                return;
            }
            boolean shouldUnregister;
            synchronized (this) {
                shouldUnregister = registered;
                registered = false;
                closeLocked();
            }
            if (shouldUnregister) {
                PluginsController.getInstance()
                        .unregisterRuntimeCallbackHolder(runtimeToken, this);
            }
        }

        @Override
        public void onPluginRuntimeActive() {
            boolean shouldPost = false;
            synchronized (this) {
                if (!closed && !pendingCallbacks.isEmpty()
                        && !drainPosted) {
                    drainPosted = true;
                    shouldPost = true;
                }
            }
            if (shouldPost) {
                MAIN_HANDLER.post(drainRunnable);
            }
        }

        @Override
        public void revokePluginRuntime() {
            synchronized (this) {
                
                registered = false;
                closeLocked();
            }
        }

        private void drainOne() {
            requireMainThread();
            int decision = PluginsController.getInstance()
                    .getPluginRuntimeTaskDecision(runtimeToken);
            if (decision == PluginsController.RUNTIME_TASK_DROP) {
                clear(runtimeToken);
                return;
            }
            ScopedRunnable callback = null;
            boolean shouldPost = false;
            synchronized (this) {
                drainPosted = false;
                if (closed) {
                    return;
                }
                if (decision == PluginsController.RUNTIME_TASK_WAIT) {
                    return;
                }
                Iterator<Map.Entry<Object, ScopedRunnable>> iterator =
                        pendingCallbacks.entrySet().iterator();
                if (iterator.hasNext()) {
                    callback = iterator.next().getValue();
                    iterator.remove();
                }
            }

            if (callback != null) {
                runScoped(runtimeToken, callback);
            }

            int nextDecision = PluginsController.getInstance()
                    .getPluginRuntimeTaskDecision(runtimeToken);
            if (nextDecision == PluginsController.RUNTIME_TASK_DROP) {
                clear(runtimeToken);
                return;
            }
            synchronized (this) {
                if (!closed && !pendingCallbacks.isEmpty()
                        && nextDecision
                                == PluginsController.RUNTIME_TASK_RUN
                        && !drainPosted) {
                    drainPosted = true;
                    shouldPost = true;
                }
            }
            if (shouldPost) {
                
                MAIN_HANDLER.post(drainRunnable);
            }
        }

        private void closeLocked() {
            closed = true;
            drainPosted = false;
            pendingCallbacks.clear();
        }
    }

    static final class SuperCallScope {
        private final PluginsController.PluginRuntimeToken runtimeToken;
        private boolean active = true;
        private boolean claimed;
        private boolean claimedResultAvailable;
        private Object claimedResult;

        SuperCallScope(PluginsController.PluginRuntimeToken runtimeToken) {
            this.runtimeToken = runtimeToken;
        }

        boolean run(Runnable callback) {
            if (callback == null || !claim()) {
                return false;
            }
            callback.run();
            return true;
        }

        <T> T call(Callback<T> callback, T rejectedResult) {
            if (callback == null || !claim()) {
                return rejectedResult;
            }
            T result = callback.run();
            claimedResult = result;
            claimedResultAvailable = true;
            return result;
        }

        private synchronized boolean claim() {
            if (!active || claimed
                    || Looper.myLooper() != Looper.getMainLooper()) {
                return false;
            }
            PluginsController.PluginRuntimeToken scopedToken =
                    PluginsController.getInstance()
                            .captureCurrentPluginRuntime();
            if (runtimeToken == null || !runtimeToken.equals(scopedToken)) {
                return false;
            }
            claimed = true;
            return true;
        }

        synchronized void close() {
            active = false;
        }

        synchronized boolean wasClaimed() {
            return claimed;
        }

        synchronized boolean hasClaimedResult() {
            return claimedResultAvailable;
        }

        @SuppressWarnings("unchecked")
        synchronized <T> T getClaimedResult(T rejectedResult) {
            return claimedResultAvailable ? (T) claimedResult : rejectedResult;
        }
    }

    private PluginRuntimeDelegate() {
    }

    public static FrameCallbackQueue newFrameCallbackQueue(
            PluginsController.PluginRuntimeToken runtimeToken) {
        requireMainThread();
        return new FrameCallbackQueue(runtimeToken);
    }

    public static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Plugin UI must be accessed on the main thread");
        }
    }

    public static PluginsController.PluginRuntimeToken capture(Object delegate) {
        requireMainThread();
        if (delegate == null) {
            return null;
        }
        PluginsController controller = PluginsController.getInstance();
        PluginsController.PluginRuntimeToken token =
                controller.captureCurrentPluginRuntime();
        if (token == null || !controller.isPluginRuntimeCurrent(token)) {
            throw new IllegalStateException(
                    "Plugin UI delegate requires a current plugin runtime");
        }
        return token;
    }

    public static boolean run(
            PluginsController.PluginRuntimeToken token, Runnable callback) {
        requireMainThread();
        if (token == null || callback == null) {
            return false;
        }
        PluginsController controller = PluginsController.getInstance();
        
        boolean enteredHere = false;
        if (!token.equals(controller.captureCurrentPluginRuntime())) {
            if (!controller.isPluginRuntimeCurrent(token)
                    || !controller.enterPluginRuntime(token)) {
                return false;
            }
            enteredHere = true;
        }
        String pluginId = token.getPluginId();
        controller.getWatchdog().onPluginExecutionStarted(pluginId);
        try {
            callback.run();
            return true;
        } catch (Throwable error) {
            rethrowIfFatal(error);
            controller.getWatchdog().onPluginExecutionFailed(
                    pluginId, error);
            FileLog.e("NimarkoGram: plugin UI callback failed for "
                    + pluginId, error);
            return false;
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(pluginId);
            if (enteredHere) {
                controller.exitPluginRuntime(token);
            }
        }
    }

    public static <T> T call(
            PluginsController.PluginRuntimeToken token,
            Callback<T> callback,
            T staleResult) {
        requireMainThread();
        if (token == null || callback == null) {
            return staleResult;
        }
        PluginsController controller = PluginsController.getInstance();
        boolean enteredHere = false;
        if (!token.equals(controller.captureCurrentPluginRuntime())) {
            if (!controller.isPluginRuntimeCurrent(token)
                    || !controller.enterPluginRuntime(token)) {
                return staleResult;
            }
            enteredHere = true;
        }
        String pluginId = token.getPluginId();
        controller.getWatchdog().onPluginExecutionStarted(pluginId);
        try {
            return callback.run();
        } catch (Throwable error) {
            rethrowIfFatal(error);
            controller.getWatchdog().onPluginExecutionFailed(
                    pluginId, error);
            FileLog.e("NimarkoGram: plugin UI callback failed for "
                    + pluginId, error);
            return staleResult;
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(pluginId);
            if (enteredHere) {
                controller.exitPluginRuntime(token);
            }
        }
    }

    static boolean runScoped(
            PluginsController.PluginRuntimeToken token,
            ScopedRunnable callback) {
        SuperCallScope superCallScope = new SuperCallScope(token);
        final boolean[] failed = new boolean[1];
        boolean invoked = run(token, () -> {
            try {
                callback.run(superCallScope);
            } catch (Throwable error) {
                rethrowIfFatal(error);
                failed[0] = true;
                PluginsController.getInstance().getWatchdog()
                        .onPluginExecutionFailed(
                                token.getPluginId(), error);
                FileLog.e("NimarkoGram: plugin traversal callback failed for "
                        + token.getPluginId(), error);
            } finally {
                superCallScope.close();
            }
        });
        
        return invoked && (!failed[0] || superCallScope.wasClaimed());
    }

    static <T> T callScoped(
            PluginsController.PluginRuntimeToken token,
            ScopedCallback<T> callback,
            T staleResult) {
        return call(token, () -> {
            SuperCallScope superCallScope = new SuperCallScope(token);
            try {
                return callback.run(superCallScope);
            } catch (Throwable error) {
                rethrowIfFatal(error);
                PluginsController.getInstance().getWatchdog()
                        .onPluginExecutionFailed(
                                token.getPluginId(), error);
                FileLog.e("NimarkoGram: plugin traversal callback failed for "
                        + token.getPluginId(), error);
                
                return superCallScope.hasClaimedResult()
                        ? superCallScope.getClaimedResult(staleResult)
                        : staleResult;
            } finally {
                superCallScope.close();
            }
        }, staleResult);
    }

    private static void rethrowIfFatal(Throwable error) {
        if (error instanceof VirtualMachineError) {
            throw (VirtualMachineError) error;
        }
        if (error instanceof ThreadDeath) {
            throw (ThreadDeath) error;
        }
        if (error instanceof LinkageError) {
            throw (LinkageError) error;
        }
    }
}
