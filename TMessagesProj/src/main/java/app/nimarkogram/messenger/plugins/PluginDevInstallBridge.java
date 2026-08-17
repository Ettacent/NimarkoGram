package app.nimarkogram.messenger.plugins;

import com.chaquo.python.PyObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

public final class PluginDevInstallBridge {
    private final PythonPluginsEngine engine;
    private final long generation;
    private final String authenticationToken;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<Thread> serverThread =
            new AtomicReference<>();
    private final AtomicLong commandGeneration = new AtomicLong();

    PluginDevInstallBridge(
            PythonPluginsEngine engine, long generation) {
        if (engine == null) {
            throw new IllegalArgumentException(
                    "Development install bridge requires an engine");
        }
        this.engine = engine;
        this.generation = generation;
        this.authenticationToken =
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "");
    }

    public boolean isActive() {
        return active.get()
                && engine.isCurrentDevInstallBridge(this, generation);
    }

    boolean startServer(PyObject serverClass) {
        if (serverClass == null || !isActive()) {
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                if (!isActive()
                        || serverThread.get() != Thread.currentThread()) {
                    return;
                }
                serverClass.callAttrThrows("start_server", this);
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                if (isActive()) {
                    FileLog.e("Development server thread failed", failure);
                }
            } finally {
                if (serverThread.get() == Thread.currentThread()) {
                    engine.onDevServerTerminated(this, generation);
                    serverThread.compareAndSet(
                            Thread.currentThread(), null);
                }
            }
        }, "nimarko-dev-server-" + generation);
        thread.setDaemon(true);
        if (!serverThread.compareAndSet(null, thread)) {
            return false;
        }
        try {
            thread.start();
            return true;
        } catch (Throwable failure) {
            serverThread.compareAndSet(thread, null);
            rethrowIfFatal(failure);
            FileLog.e("Could not start development server thread", failure);
            return false;
        }
    }

    public CommandAuthority authorize(String suppliedToken) {
        if (!isAuthenticatedServerCall(suppliedToken)) {
            return null;
        }
        return new CommandAuthority(
                this, generation, commandGeneration.incrementAndGet());
    }

    String getAuthenticationTokenForHostUi() {
        return authenticationToken;
    }

    void revokeFromHost() {
        active.set(false);
    }

    boolean isServerThreadAlive() {
        Thread thread = serverThread.get();
        return thread != null && thread.isAlive();
    }

    boolean awaitServerTermination(long timeoutMs) {
        Thread thread = serverThread.get();
        if (thread == null || !thread.isAlive()) {
            return true;
        }
        if (thread == Thread.currentThread()) {
            return false;
        }
        long boundedTimeout = Math.max(1L, timeoutMs);
        try {
            thread.join(boundedTimeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !thread.isAlive();
    }

    boolean belongsTo(
            PythonPluginsEngine expectedEngine,
            long expectedGeneration) {
        return engine == expectedEngine
                && generation == expectedGeneration;
    }

    boolean isMarkedActive() {
        return active.get();
    }

    boolean accepts(
            CommandAuthority authority,
            long expectedGeneration) {
        return authority != null
                && authority.owner == this
                && authority.bridgeGeneration == generation
                && generation == expectedGeneration
                && active.get()
                && engine.isCurrentDevInstallBridge(this, generation);
    }

    private boolean isAuthenticatedServerCall(String suppliedToken) {
        return active.get()
                && serverThread.get() == Thread.currentThread()
                && tokenMatches(suppliedToken)
                && engine.isCurrentDevInstallBridge(this, generation);
    }

    private boolean tokenMatches(String suppliedToken) {
        if (suppliedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                authenticationToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean postAuthenticatedHostAction(
            CommandAuthority authority, PyObject action) {
        if (action == null
                || serverThread.get() != Thread.currentThread()
                || !accepts(authority, generation)
                || !authority.consumed.compareAndSet(false, true)) {
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!accepts(authority, generation)
                    || !authority.belongsTo(
                            this, generation,
                            authority.commandGeneration)) {
                return;
            }
            try {
                action.call();
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                FileLog.e("Development server host UI action failed",
                        failure);
            }
        });
        return true;
    }

    public static final class CommandAuthority {
        private final PluginDevInstallBridge owner;
        private final long bridgeGeneration;
        private final long commandGeneration;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private CommandAuthority(
                PluginDevInstallBridge owner,
                long bridgeGeneration,
                long commandGeneration) {
            this.owner = owner;
            this.bridgeGeneration = bridgeGeneration;
            this.commandGeneration = commandGeneration;
        }

        public boolean consume() {
            return owner.serverThread.get() == Thread.currentThread()
                    && owner.accepts(this, bridgeGeneration)
                    && consumed.compareAndSet(false, true);
        }

        public boolean postToMain(PyObject action) {
            return owner.postAuthenticatedHostAction(this, action);
        }

        public boolean installCandidate(
                String path,
                String expectedPluginId,
                PyObject completion) {
            if (owner.serverThread.get() != Thread.currentThread()
                    || !owner.accepts(this, bridgeGeneration)
                    || !consumed.compareAndSet(false, true)) {
                return false;
            }
            DevCompletion oneShot =
                    owner.new DevCompletion(this, completion);
            try {
                return owner.engine.installFromDevAuthority(
                        owner, this, bridgeGeneration, commandGeneration,
                        path, expectedPluginId, oneShot::deliver);
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                FileLog.e("Development candidate transfer failed",
                        failure);
                oneShot.deliver(
                        "Could not take ownership of plugin candidate");
                return false;
            }
        }

        boolean belongsTo(
                PluginDevInstallBridge expectedOwner,
                long expectedBridgeGeneration,
                long expectedCommandGeneration) {
            return owner == expectedOwner
                    && bridgeGeneration == expectedBridgeGeneration
                    && commandGeneration == expectedCommandGeneration
                    && consumed.get();
        }
    }

    private final class DevCompletion {
        private final CommandAuthority authority;
        private final AtomicReference<PyObject> callback;

        DevCompletion(
                CommandAuthority authority, PyObject callback) {
            this.authority = authority;
            this.callback = new AtomicReference<>(callback);
        }

        void deliver(String error) {
            PyObject action = callback.getAndSet(null);
            if (action == null) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!accepts(authority, generation)
                        || !authority.belongsTo(
                                PluginDevInstallBridge.this,
                                generation,
                                authority.commandGeneration)) {
                    return;
                }
                try {
                    action.call(error);
                } catch (Throwable failure) {
                    rethrowIfFatal(failure);
                    FileLog.e(
                            "Development install completion failed",
                            failure);
                }
            });
        }
    }

    private static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
        if (failure instanceof LinkageError) {
            throw (LinkageError) failure;
        }
    }
}
