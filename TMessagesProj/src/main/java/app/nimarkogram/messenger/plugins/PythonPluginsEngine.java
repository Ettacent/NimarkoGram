package app.nimarkogram.messenger.plugins;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.core.content.FileProvider;
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.plugins.hooks.PluginsHooks;
import app.nimarkogram.messenger.plugins.pip.PipController;
import app.nimarkogram.messenger.plugins.models.DividerSetting;
import app.nimarkogram.messenger.plugins.models.EditTextSetting;
import app.nimarkogram.messenger.plugins.models.HeaderSetting;
import app.nimarkogram.messenger.plugins.models.InputSetting;
import app.nimarkogram.messenger.plugins.models.SelectorSetting;
import app.nimarkogram.messenger.plugins.models.SettingItem;
import app.nimarkogram.messenger.plugins.models.SwitchSetting;
import app.nimarkogram.messenger.plugins.models.TextSetting;
import app.nimarkogram.messenger.plugins.ui.PluginSettingsActivity;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;
import app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet;
import app.nimarkogram.messenger.plugins.ui.components.PluginCell;
import app.nimarkogram.messenger.plugins.utils.PyObjectUtils;
import app.nimarkogram.messenger.utils.text.LocaleUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

public class PythonPluginsEngine implements PluginsController.PluginsEngine {
    private static final Object PYTHON_START_LOCK = new Object();
    private static final Object PINE_INIT_LOCK = new Object();
    private static final Pattern SAFE_MODE_METADATA_PATTERN = Pattern.compile(
            "(?ms)^\\s*__(version|min_version|id|icon|name|description|author|requirements)__\\s*=\\s*(?:[rRuUbBfF]{0,2})?(\"\"\"|'''|\"|')(.*?)\\2");
    private static final Pattern SAFE_MODE_REQUIREMENTS_LIST_PATTERN = Pattern.compile(
            "(?ms)^\\s*__requirements__\\s*=\\s*\\[(.*?)]");
    private static final Pattern SAFE_MODE_LIST_STRING_PATTERN = Pattern.compile(
            "(?:[rRuUbBfF]{0,2})?(\"|')(.*?)\\1");
    private static final Pattern PLUGIN_UPDATE_MARKER_PATTERN = Pattern.compile(
            "^\\.([a-zA-Z][a-zA-Z0-9_-]{1,31})\\.py\\.update$");
    private static final Pattern PLUGIN_DELETE_MARKER_PATTERN = Pattern.compile(
            "^\\.([a-zA-Z][a-zA-Z0-9_-]{1,31})\\.py\\.delete$");
    private static final Pattern HOST_INSTALL_STAGE_PATTERN = Pattern.compile(
            "^\\.plugin-install-[0-9a-f]{32}\\.stage$");
    private static final Pattern PLUGIN_UPDATE_DEPENDENCY_PATTERN =
            Pattern.compile(
                    "^\\.([a-zA-Z][a-zA-Z0-9_-]{1,31})"
                            + "\\.py\\.update\\.deps$");
    private static final String UPDATE_STATE_PREPARED = "PREPARED";
    private static final String UPDATE_STATE_ROLLING_BACK =
            "ROLLING_BACK";
    private static final String UPDATE_STATE_ROLLED_BACK =
            "ROLLED_BACK";
    private static final String UPDATE_STATE_COMMITTED = "COMMITTED";
    private static final String UPDATE_NO_BACKUP = "-";
    private static final String UPDATE_CLEANUP_PRUNE = "PRUNE";
    private static final String UPDATE_CLEANUP_UNINSTALL = "UNINSTALL";
    private static final String DELETE_STATE_PREPARED = "PREPARED";
    private static final String DELETE_STATE_COMMITTED = "COMMITTED";
    private static final int MAX_PLUGIN_SETTINGS_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PLUGIN_CANDIDATE_BYTES =
            4 * 1024 * 1024;
    private static final java.util.Set<String>
            AUTHORIZED_METADATA_KEYS =
                    Collections.unmodifiableSet(
                            new java.util.HashSet<>(Arrays.asList(
                                    "__version__", "__min_version__",
                                    "__id__", "__icon__", "__name__",
                                    "__description__", "__author__",
                                    "__requirements__")));
    private static final Pattern UPDATE_TRANSACTION_ID_PATTERN =
            Pattern.compile("^[0-9a-f]{8,40}$");
    private static final java.util.Set<String>
            RECOVERY_BLOCKED_PLUGIN_IDS =
                    ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String>
            ABANDONED_RUNTIME_PLUGIN_IDS =
                    ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean PYTHON_RUNTIME_ABANDONED =
            new AtomicBoolean();
    private static final long PINE_INIT_TIMEOUT_MS = 10_000L;
    private static final long PLUGIN_LOAD_TIMEOUT_MS = 30_000L;
    private static final long PLUGIN_UNLOAD_TIMEOUT_MS = 5_000L;
    private static final long PLUGIN_RETIREMENT_TIMEOUT_MS = 10_000L;
    private static final ScheduledExecutorService
            LIFECYCLE_DEADLINE_EXECUTOR =
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread thread = new Thread(
                                r, "nimarko-plugin-retirement-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final long HOST_INSTALL_TICKET_TTL_MS =
            TimeUnit.MINUTES.toMillis(10);

    public PyObject basePluginClass;
    public volatile PyObject debuggerListener;
    private volatile PyObject devServerClass;
    private volatile PluginDevInstallBridge devInstallBridge;
    private final AtomicLong devInstallBridgeGeneration = new AtomicLong();
    private final Object installPublicationLock = new Object();
    private final Object devServerLock = new Object();
    private volatile Python python;
    private volatile boolean pluginsPathAdded;
    public final ConcurrentHashMap<String, PyObject> pluginInstances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PluginsController.PluginRuntimeToken>
            pluginRuntimeTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PluginsController.PluginRuntimeToken,
            PluginUiRegistry.DecorChildrenSnapshot>
            legacyOverlayProbes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MetadataCacheEntry> metadataCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> settingsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> settingsCacheGenerations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HostInstallTicket>
            hostInstallTickets = new ConcurrentHashMap<>();
    private final java.util.Set<AuthorizedCandidate>
            activeAuthorizedCandidates =
                    ConcurrentHashMap.newKeySet();
    private final Object settingsReloadLock = new Object();
    private volatile ExecutorService pluginInitExecutor = newInitExecutor();
    private static boolean pineInitAttemptRunning;
    private final ConcurrentHashMap<String, LifecycleOperation> lifecycleOperations =
            new ConcurrentHashMap<>();

    private enum LifecyclePhase {
        LOAD,
        UNLOAD
    }

    private static final class LifecycleOperation {
        final Object monitor = new Object();
        final String pluginId;
        final PyObject instance;
        final PluginsController.PluginRuntimeToken runtimeToken;
        final int enableGeneration;
        final AtomicBoolean timedOut = new AtomicBoolean();
        final AtomicBoolean completionScheduled = new AtomicBoolean();
        final AtomicBoolean unloadStarted = new AtomicBoolean();
        final AtomicBoolean settled = new AtomicBoolean();
        final AtomicBoolean retirementDeadlineScheduled =
                new AtomicBoolean();
        final AtomicBoolean retirementExpired = new AtomicBoolean();
        boolean deferredAdmissionClosed;
        final AtomicInteger callState = new AtomicInteger();
        final ArrayList<Runnable> deferredActions = new ArrayList<>();
        volatile LifecyclePhase phase;
        volatile boolean actuallyReturned;
        volatile Throwable outcome;

        LifecycleOperation(
                String pluginId, PyObject instance,
                PluginsController.PluginRuntimeToken runtimeToken,
                int enableGeneration, LifecyclePhase phase) {
            this.pluginId = pluginId;
            this.instance = instance;
            this.runtimeToken = runtimeToken;
            this.enableGeneration = enableGeneration;
            this.phase = phase;
        }
    }

    private static final class LifecyclePendingException extends Exception {
        LifecyclePendingException(String pluginId) {
            super("Previous lifecycle call is still finishing for " + pluginId);
        }
    }

    private static final class MetadataCacheEntry {
        final long length;
        final long modified;
        final Map<String, String> metadata;

        MetadataCacheEntry(File file, Map<String, String> metadata) {
            this.length = file.length();
            this.modified = file.lastModified();
            this.metadata = Collections.unmodifiableMap(new HashMap<>(metadata));
        }

        boolean matches(File file) {
            return file.length() == length && file.lastModified() == modified;
        }
    }

    private static final int HOST_TICKET_ISSUED = 0;
    private static final int HOST_TICKET_BOUND = 1;
    private static final int HOST_TICKET_QUEUED = 2;
    private static final int HOST_TICKET_CONSUMED = 3;
    private static final int HOST_TICKET_REVOKED = 4;

    private final class HostInstallTicket
            implements InstallPluginBottomSheet.HostInstallAuthority {
        private final AuthorizedCandidate candidate;
        private final String nonce;
        private final long expiresAtElapsedRealtime;
        private final AtomicInteger state =
                new AtomicInteger(HOST_TICKET_ISSUED);

        private HostInstallTicket(
                AuthorizedCandidate candidate,
                String nonce, long expiresAtElapsedRealtime) {
            this.candidate = candidate;
            this.nonce = nonce;
            this.expiresAtElapsedRealtime = expiresAtElapsedRealtime;
        }

        boolean bind() {
            return state.compareAndSet(
                    HOST_TICKET_ISSUED, HOST_TICKET_BOUND);
        }

        @Override
        public boolean transfer(Utilities.Callback<String> callback) {
            if (callback == null
                    || Looper.myLooper() != Looper.getMainLooper()
                    || getPluginsController()
                            .captureCurrentPluginRuntime() != null) {
                return false;
            }
            if (expiresAtElapsedRealtime
                            <= SystemClock.elapsedRealtime()
                    || hostInstallTickets.get(nonce) != this) {
                revokeHostInstallTicket(this, false);
                return false;
            }
            if (!state.compareAndSet(
                    HOST_TICKET_BOUND,
                    HOST_TICKET_QUEUED)) {
                return false;
            }
            if (!hostInstallTickets.remove(nonce, this)) {
                forceRevokeHostInstallTicket(this);
                return false;
            }
            try {
                Utilities.pluginsQueue.postRunnable(
                        () -> consumeHostInstallTicket(this, callback));
                return true;
            } catch (Throwable queueFailure) {
                FileLog.e("Could not queue authorized plugin install",
                        queueFailure);
                forceRevokeHostInstallTicket(this);
                return false;
            }
        }

        @Override
        public void revoke() {
            revokeHostInstallTicket(this, false);
        }

        @Override
        public String toString() {
            return "HostInstallTicket{oneShot=true}";
        }
    }

    private final class AuthorizedCandidate {
        final File stagedFile;
        final String sha256;
        final Plugin plugin;
        private final AtomicBoolean published = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();

        AuthorizedCandidate(
                File stagedFile, String sha256, Plugin plugin) {
            this.stagedFile = stagedFile;
            this.sha256 = sha256;
            this.plugin = plugin;
        }

        void markPublished() {
            published.set(true);
            activeAuthorizedCandidates.remove(this);
        }

        void cleanup() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            if (published.get() || stagedFile == null
                    || !stagedFile.exists()) {
                activeAuthorizedCandidates.remove(this);
                return;
            }
            if (stagedFile.delete()) {
                activeAuthorizedCandidates.remove(this);
            } else {
                cleaned.set(false);
                FileLog.w("Could not remove staged plugin candidate "
                        + stagedFile.getAbsolutePath());
            }
        }
    }

    private static final class StagedCandidatePayload {
        final File file;
        final byte[] bytes;
        final String sha256;

        StagedCandidatePayload(
                File file, byte[] bytes, String sha256) {
            this.file = file;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    @FunctionalInterface
    private interface InstallContinuationGuard {
        boolean isAuthorized(String pluginId);

        default boolean canRetireExistingRuntime(String pluginId) {
            return isAuthorized(pluginId);
        }

        default void didRetireExistingRuntime(String pluginId) {
        }
    }

    private final class RuntimeSelfUpdateGuard
            implements InstallContinuationGuard {
        private final PluginsController.PluginRuntimeToken requester;
        private boolean exactRetirementPrepared;
        private boolean exactRetirementStarted;

        RuntimeSelfUpdateGuard(
                PluginsController.PluginRuntimeToken requester) {
            this.requester = requester;
        }

        @Override
        public boolean isAuthorized(String pluginId) {
            if (!hasMatchingRuntimeGeneration(pluginId)) {
                return false;
            }
            PluginsController.PluginRuntimeToken current =
                    getPluginsController()
                            .getCurrentPluginRuntime(pluginId);
            return requester.equals(current)
                    || (exactRetirementStarted && current == null);
        }

        @Override
        public boolean canRetireExistingRuntime(String pluginId) {
            if (exactRetirementStarted) {
                return isAuthorized(pluginId);
            }
            PluginsController.PluginRuntimeToken current =
                    getPluginsController()
                            .getCurrentPluginRuntime(pluginId);
            PluginsController.PluginRuntimeToken mapped =
                    pluginRuntimeTokens.get(pluginId);
            exactRetirementPrepared =
                    hasMatchingRuntimeGeneration(pluginId)
                            && requester.equals(current)
                            && requester.equals(mapped);
            return exactRetirementPrepared;
        }

        @Override
        public void didRetireExistingRuntime(String pluginId) {
            if (!exactRetirementStarted) {
                PluginsController.PluginRuntimeToken current =
                        getPluginsController()
                                .getCurrentPluginRuntime(pluginId);
                if (!exactRetirementPrepared
                        || !hasMatchingRuntimeGeneration(pluginId)
                        || current != null) {
                    throw new SecurityException(
                            "Exact self-update runtime was not retired");
                }
                exactRetirementStarted = true;
                exactRetirementPrepared = false;
            }
        }

        private boolean hasMatchingRuntimeGeneration(String pluginId) {
            return requester != null
                    && requester.getPluginId().equals(pluginId)
                    && getPluginsController()
                            .getPluginToggleGeneration(pluginId)
                            == requester.getGeneration()
                    && getPluginsController()
                            .isPluginEnableRequested(
                                    pluginId,
                                    requester.getGeneration());
        }
    }

    private static final class PluginUpdateMarkerData {
        final String state;
        final String backupName;
        final String cleanupMode;
        final String transactionId;
        final String sourceSha256;

        PluginUpdateMarkerData(
                String state, String backupName, String cleanupMode,
                String transactionId, String sourceSha256) {
            this.state = state;
            this.backupName = backupName;
            this.cleanupMode = cleanupMode;
            this.transactionId = transactionId;
            this.sourceSha256 = sourceSha256;
        }
    }

    private static final class PluginDeleteMarkerData {
        final String state;
        final String transactionId;

        PluginDeleteMarkerData(String state, String transactionId) {
            this.state = state;
            this.transactionId = transactionId;
        }
    }

    static void recoverInterruptedPluginDeletes(
            PluginsController controller) {
        if (controller == null) return;
        File directory = controller.getPluginsDir();
        if (directory == null) return;
        File[] markers = directory.listFiles((dir, name) ->
                PLUGIN_DELETE_MARKER_PATTERN.matcher(name).matches());
        if (markers == null) return;
        Arrays.sort(markers,
                java.util.Comparator.comparingLong(File::lastModified)
                        .reversed()
                        .thenComparing(File::getName));
        for (File marker : markers) {
            Matcher matcher =
                    PLUGIN_DELETE_MARKER_PATTERN.matcher(marker.getName());
            if (!matcher.matches()) continue;
            String pluginId = matcher.group(1);
            RECOVERY_BLOCKED_PLUGIN_IDS.add(pluginId);
            try {
                PluginDeleteMarkerData markerData =
                        readPluginDeleteMarker(marker);
                if (completePluginDeletion(
                        controller, pluginId, marker, markerData)) {
                    RECOVERY_BLOCKED_PLUGIN_IDS.remove(pluginId);
                    FileLog.w("Completed interrupted plugin deletion for "
                            + pluginId);
                }
            } catch (Throwable failure) {
                FileLog.e("Plugin deletion recovery failed for "
                        + marker.getAbsolutePath(), failure);
            }
        }
    }

    public static boolean prepareDurablePluginDeletion(
            PluginsController controller, String pluginId) {
        if (controller == null || pluginId == null
                || !pluginId.matches(
                        "^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")) {
            return false;
        }
        File directory = controller.getPluginsDir();
        File marker = pluginDeleteMarker(directory, pluginId);
        try {
            if (marker.exists()) {
                readPluginDeleteMarker(marker);
            } else {
                writePluginDeleteMarker(
                        directory, pluginId,
                        DELETE_STATE_PREPARED,
                        newPluginUpdateTransactionId(), false);
            }
            RECOVERY_BLOCKED_PLUGIN_IDS.add(pluginId);
            return true;
        } catch (Throwable failure) {
            FileLog.e("Could not prepare durable deletion for "
                    + pluginId, failure);
            return false;
        }
    }

    static void recoverInterruptedPluginUpdates(
            PluginsController controller) {
        if (controller == null) return;
        recoverInterruptedPluginDeletes(controller);
        File directory = controller.getPluginsDir();
        if (directory == null) return;
        cleanupOrphanDependencySnapshots(directory);
        java.util.Set<String> pendingArtifactPlugins =
                PipController.getInstance()
                        .getPendingDeferredArtifactPluginIds();
        RECOVERY_BLOCKED_PLUGIN_IDS.addAll(pendingArtifactPlugins);
        File[] markers = directory.listFiles((dir, name) ->
                PLUGIN_UPDATE_MARKER_PATTERN.matcher(name).matches());
        if (markers == null) markers = new File[0];
        Arrays.sort(markers,
                java.util.Comparator.comparingLong(File::lastModified)
                        .reversed()
                        .thenComparing(File::getName));
        java.util.Set<String> markerPluginIds = new java.util.HashSet<>();
        for (File marker : markers) {
            Matcher matcher =
                    PLUGIN_UPDATE_MARKER_PATTERN.matcher(marker.getName());
            if (matcher.matches()) {
                markerPluginIds.add(matcher.group(1));
            }
        }

        for (File marker : markers) {
            Matcher matcher =
                    PLUGIN_UPDATE_MARKER_PATTERN.matcher(marker.getName());
            if (!matcher.matches()) continue;
            String pluginId = matcher.group(1);
            RECOVERY_BLOCKED_PLUGIN_IDS.add(pluginId);
            if (pluginDeleteMarker(directory, pluginId).exists()) {
                continue;
            }
            try {
                PluginUpdateMarkerData markerData =
                        readPluginUpdateMarker(marker, pluginId);

                File backup =
                        UPDATE_NO_BACKUP.equals(markerData.backupName)
                                ? null
                                : new File(
                                        directory,
                                        markerData.backupName);
                File destination =
                        new File(directory, pluginId + ".py");
                File dependencySnapshot =
                        pluginUpdateDependencySnapshot(
                                directory, pluginId);

                if (UPDATE_STATE_COMMITTED.equals(markerData.state)) {
                    if (markerData.transactionId != null
                            && !PipController.getInstance()
                                    .commitDeferredArtifactTransaction(
                                            pluginId,
                                            markerData.transactionId)) {
                        FileLog.w("Committed plugin artifact cleanup "
                                + "deferred for " + pluginId);
                        continue;
                    }
                    boolean finalized =
                            UPDATE_CLEANUP_UNINSTALL.equals(
                                            markerData.cleanupMode)
                                    ? PipController.getInstance()
                                            .uninstallDependencies(pluginId)
                                    : PipController.getInstance()
                                            .cleanupAndReport();
                    if (!finalized) {
                        FileLog.w("Committed plugin dependency cleanup "
                                + "deferred for " + pluginId);
                        continue;
                    }
                    if (backup != null && backup.exists()
                            && !backup.delete()) {
                        FileLog.w("Committed plugin backup cleanup deferred: "
                                + backup.getAbsolutePath());
                        continue;
                    }
                    if (dependencySnapshot.exists()
                            && !dependencySnapshot.delete()) {
                        FileLog.w("Committed plugin dependency snapshot "
                                + "cleanup deferred: "
                                + dependencySnapshot.getAbsolutePath());
                        continue;
                    }
                    if (marker.exists() && !marker.delete()) {
                        FileLog.w("Committed plugin marker cleanup deferred: "
                                + marker.getAbsolutePath());
                        continue;
                    }
                    syncDirectory(directory);
                    RECOVERY_BLOCKED_PLUGIN_IDS.remove(pluginId);
                    continue;
                }

                boolean transactional =
                        markerData.transactionId != null;
                boolean journalExists =
                        pendingArtifactPlugins.contains(pluginId);
                PipController.DependencySnapshot dependencyState = null;
                if (transactional && dependencySnapshot.isFile()) {
                    dependencyState =
                            PipController.getInstance()
                                    .readDependencySnapshot(
                                            dependencySnapshot,
                                            pluginId,
                                            markerData.transactionId);
                } else if (transactional
                        && !UPDATE_STATE_ROLLED_BACK.equals(
                                markerData.state)) {
                    FileLog.e("Dependency rollback snapshot is missing for "
                            + pluginId + "; plugin remains blocked");
                    continue;
                }

                if (UPDATE_STATE_ROLLED_BACK.equals(
                        markerData.state)) {
                    if (!matchesSourceChecksum(
                            destination,
                            markerData.sourceSha256)) {
                        FileLog.e("Rolled-back plugin source checksum "
                                + "mismatch for " + pluginId);
                        continue;
                    }
                    if (dependencyState != null
                            && !PipController.getInstance()
                                    .restoreState(
                                            pluginId,
                                            dependencyState)) {
                        FileLog.e("Could not finish dependency rollback for "
                                + pluginId);
                        continue;
                    }
                    if (dependencyState != null
                            && !Python.isStarted()) {
                        FileLog.d("Plugin dependency path recovery deferred "
                                + "until Python starts for " + pluginId);
                        continue;
                    }
                    if (backup != null && backup.exists()
                            && !backup.delete()) {
                        FileLog.w("Rolled-back plugin backup cleanup "
                                + "deferred: " + backup);
                        continue;
                    }
                    if (dependencySnapshot.exists()
                            && !dependencySnapshot.delete()) {
                        FileLog.w("Rolled-back dependency snapshot cleanup "
                                + "deferred: " + dependencySnapshot);
                        continue;
                    }
                    if (marker.exists() && !marker.delete()) {
                        FileLog.w("Rolled-back plugin marker cleanup "
                                + "deferred: " + marker);
                        continue;
                    }
                    syncDirectoryStrict(directory);
                    RECOVERY_BLOCKED_PLUGIN_IDS.remove(pluginId);
                    continue;
                }

                if (transactional) {
                    if (UPDATE_STATE_PREPARED.equals(
                            markerData.state)) {
                        if ((journalExists
                                && !PipController.getInstance()
                                        .hasOuterArtifactTransaction(
                                                pluginId,
                                                markerData.transactionId))
                                || (!journalExists
                                        && markerData.sourceSha256
                                                != null)) {
                            FileLog.e("Dependency/source transaction "
                                    + "identity mismatch for " + pluginId);
                            continue;
                        }
                        writePluginUpdateMarker(
                                directory, pluginId, backup,
                                UPDATE_STATE_ROLLING_BACK,
                                markerData.cleanupMode,
                                markerData.transactionId,
                                resolveRollbackSourceChecksum(
                                        markerData, backup,
                                        destination),
                                true);
                        markerData = readPluginUpdateMarker(
                                marker, pluginId);
                    } else if (journalExists
                            && !PipController.getInstance()
                                    .hasOuterArtifactTransaction(
                                            pluginId,
                                            markerData.transactionId)) {
                        FileLog.e("Dependency/source rollback transaction "
                                + "identity mismatch for " + pluginId);
                        continue;
                    }
                    if (!PipController.getInstance()
                            .rollbackDeferredArtifactTransaction(
                                    pluginId,
                                    markerData.transactionId)) {
                        FileLog.e("Could not recover dependency artifacts "
                                + "for " + pluginId
                                + "; plugin remains blocked");
                        continue;
                    }
                } else if (journalExists) {
                    FileLog.e("Legacy source marker conflicts with a "
                            + "dependency transaction for " + pluginId);
                    continue;
                }

                boolean recovered;
                if (backup != null && backup.exists()) {
                    if (markerData.sourceSha256 != null
                            && !matchesSourceChecksum(
                                    backup,
                                    markerData.sourceSha256)) {
                        FileLog.e("Plugin rollback backup checksum mismatch "
                                + "for " + pluginId);
                        continue;
                    }
                    android.system.Os.rename(
                            backup.getAbsolutePath(),
                            destination.getAbsolutePath());
                    recovered = !backup.exists()
                            && matchesSourceChecksum(
                                    destination,
                                    markerData.sourceSha256);
                } else if (backup == null) {
                    recovered = !destination.exists()
                            || destination.delete();
                } else {
                    recovered = matchesSourceChecksum(
                            destination,
                            markerData.sourceSha256);
                }
                if (!recovered) {
                    FileLog.e("Could not recover interrupted update for "
                            + pluginId + "; marker retained");
                    continue;
                }
                syncDirectory(directory);

                clearUncommittedPluginWatermark(controller, pluginId);

                if (!transactional) {
                    FileLog.w("Legacy plugin update marker has no dependency "
                            + "snapshot for " + pluginId);
                } else {
                    if (!PipController.getInstance().restoreState(
                            pluginId, dependencyState)) {
                        FileLog.e("Could not recover dependency state for "
                                + pluginId + "; marker retained");
                        continue;
                    }
                    writePluginUpdateMarker(
                            directory, pluginId, backup,
                            UPDATE_STATE_ROLLED_BACK,
                            markerData.cleanupMode,
                            markerData.transactionId,
                            markerData.sourceSha256,
                            true);
                    if (!Python.isStarted()) {
                        FileLog.d("Plugin dependency path recovery deferred "
                                + "until Python starts for " + pluginId);
                        continue;
                    }
                    if (!dependencySnapshot.delete()) {
                        FileLog.w("Recovered plugin dependency snapshot "
                                + "cleanup deferred: "
                                + dependencySnapshot.getAbsolutePath());
                        continue;
                    }
                }
                if (backup != null && backup.exists()
                        && !backup.delete()) {
                    FileLog.w("Recovered plugin backup cleanup deferred: "
                            + backup);
                    continue;
                }
                if (marker.exists() && !marker.delete()) {
                    FileLog.w("Recovered plugin marker cleanup deferred: "
                            + marker.getAbsolutePath());
                    continue;
                }
                syncDirectory(directory);
                RECOVERY_BLOCKED_PLUGIN_IDS.remove(pluginId);
                FileLog.w("Recovered interrupted plugin update for "
                        + pluginId);
            } catch (Throwable failure) {
                FileLog.e("Plugin update recovery failed for "
                        + marker.getAbsolutePath(), failure);
            }
        }

        for (String pluginId : pendingArtifactPlugins) {
            if (markerPluginIds.contains(pluginId)) continue;
            if (PipController.getInstance()
                    .rollbackPendingDeferredArtifactTransaction(pluginId)) {
                RECOVERY_BLOCKED_PLUGIN_IDS.remove(pluginId);
                FileLog.w("Recovered dependency transaction prepared "
                        + "before its source marker for " + pluginId);
            }
        }
    }

    private static PluginUpdateMarkerData readPluginUpdateMarker(
            File marker, String pluginId) throws IOException {
        if (marker == null || !marker.isFile()
                || marker.length() < 1 || marker.length() > 4096) {
            throw new IOException(
                    "Plugin update marker has an invalid size");
        }
        String state;
        String backupName;
        String cleanupMode;
        String transactionId;
        String sourceSha256;
        String trailingData;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(marker),
                        StandardCharsets.UTF_8))) {
            state = reader.readLine();
            backupName = reader.readLine();
            cleanupMode = reader.readLine();
            transactionId = reader.readLine();
            sourceSha256 = reader.readLine();
            trailingData = reader.readLine();
        }
        if (cleanupMode == null || cleanupMode.isEmpty()) {
            cleanupMode = UPDATE_CLEANUP_PRUNE;
        }
        if (transactionId != null && transactionId.isEmpty()) {
            transactionId = null;
        }
        boolean validState =
                UPDATE_STATE_PREPARED.equals(state)
                        || UPDATE_STATE_ROLLING_BACK.equals(state)
                        || UPDATE_STATE_ROLLED_BACK.equals(state)
                        || UPDATE_STATE_COMMITTED.equals(state);
        boolean validBackup =
                backupName != null
                        && (UPDATE_NO_BACKUP.equals(backupName)
                                || (backupName.startsWith(
                                                pluginId + ".py.bak.")
                                        && !backupName.contains("/")
                                        && !backupName.contains("\\")));
        boolean validCleanup =
                UPDATE_CLEANUP_PRUNE.equals(cleanupMode)
                        || UPDATE_CLEANUP_UNINSTALL.equals(cleanupMode);
        boolean validTransaction =
                transactionId == null
                        || UPDATE_TRANSACTION_ID_PATTERN
                                .matcher(transactionId).matches();
        boolean validSourceChecksum =
                sourceSha256 == null
                        || UPDATE_NO_BACKUP.equals(sourceSha256)
                        || sourceSha256.matches("^[0-9a-f]{64}$");
        if (!validState || !validBackup || !validCleanup
                || !validTransaction || !validSourceChecksum) {
            throw new IOException(
                    "Invalid plugin update recovery marker "
                            + marker.getAbsolutePath());
        }
        if (trailingData != null) {
            throw new IOException(
                    "Plugin update marker has trailing data");
        }
        return new PluginUpdateMarkerData(
                state, backupName, cleanupMode, transactionId,
                sourceSha256);
    }

    private static File pluginDeleteMarker(
            File directory, String pluginId) {
        return new File(
                directory, "." + pluginId + ".py.delete");
    }

    private static PluginDeleteMarkerData readPluginDeleteMarker(
            File marker) throws IOException {
        if (marker == null || !marker.isFile()
                || marker.length() < 1 || marker.length() > 4096) {
            throw new IOException(
                    "Plugin delete marker has an invalid size");
        }
        String state;
        String transactionId;
        String trailingData;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(marker),
                        StandardCharsets.UTF_8))) {
            state = reader.readLine();
            transactionId = reader.readLine();
            trailingData = reader.readLine();
        }
        if ((!DELETE_STATE_PREPARED.equals(state)
                && !DELETE_STATE_COMMITTED.equals(state))
                || transactionId == null
                || !UPDATE_TRANSACTION_ID_PATTERN.matcher(
                        transactionId).matches()
                || trailingData != null) {
            throw new IOException(
                    "Invalid plugin delete marker "
                            + marker.getAbsolutePath());
        }
        return new PluginDeleteMarkerData(state, transactionId);
    }

    private static void writePluginDeleteMarker(
            File directory, String pluginId, String state,
            String transactionId, boolean replaceExisting)
            throws IOException {
        if ((!DELETE_STATE_PREPARED.equals(state)
                && !DELETE_STATE_COMMITTED.equals(state))
                || transactionId == null
                || !UPDATE_TRANSACTION_ID_PATTERN.matcher(
                        transactionId).matches()) {
            throw new IOException(
                    "Invalid plugin delete transaction state");
        }
        File marker = pluginDeleteMarker(directory, pluginId);
        if (!replaceExisting && marker.exists()) {
            throw new IOException(
                    "Plugin deletion is already pending for " + pluginId);
        }
        File staged = new File(
                directory,
                marker.getName() + ".new."
                        + Long.toHexString(System.nanoTime()));
        try {
            try (FileOutputStream output =
                    new FileOutputStream(staged)) {
                output.write((state + "\n" + transactionId + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            try {
                android.system.Os.rename(
                        staged.getAbsolutePath(),
                        marker.getAbsolutePath());
            } catch (android.system.ErrnoException failure) {
                throw new IOException(
                        "Could not publish plugin delete marker",
                        failure);
            }
            syncDirectoryStrict(directory);
        } finally {
            if (staged.exists() && !staged.delete()) {
                FileLog.w("Could not remove plugin delete marker stage "
                        + staged.getAbsolutePath());
            }
        }
    }

    private static boolean completePluginDeletion(
            PluginsController controller, String pluginId,
            File marker, PluginDeleteMarkerData markerData) {
        File directory = controller.getPluginsDir();
        try {
            File source = new File(directory, pluginId + ".py");
            if (source.exists() && (!source.isFile()
                    || !source.delete() || source.exists())) {
                throw new IOException(
                        "Could not remove plugin source " + source);
            }
            syncDirectoryStrict(directory);

            try {
                controller.cleanupPlugin(pluginId);
            } catch (Throwable cleanupFailure) {
                FileLog.e("Could not clear runtime registrations for "
                        + pluginId, cleanupFailure);
            }
            controller.plugins.remove(pluginId);

            if (!PipController.getInstance()
                    .discardDeferredArtifactTransaction(pluginId)) {
                throw new IOException(
                        "Could not discard pending dependency transaction");
            }
            if (!PipController.getInstance()
                    .uninstallDependencies(pluginId)) {
                throw new IOException(
                        "Could not remove plugin dependency ownership");
            }
            PluginsController.PluginsEngine engine =
                    PluginsController.engines.get(
                            PluginsConstants.PYTHON);
            boolean settingsRemoved =
                    engine instanceof PythonPluginsEngine
                            ? ((PythonPluginsEngine) engine)
                                    .removePluginSettingsHostNative(
                                            directory, pluginId)
                            : removePluginSettingsFromDisk(
                                    directory, pluginId);
            if (!settingsRemoved) {
                throw new IOException(
                        "Could not remove plugin settings");
            }
            if (!clearPluginHostPreferences(
                    controller, pluginId)) {
                throw new IOException(
                        "Could not remove plugin host preferences");
            }
            if (!NimarkoConfig.removePluginPinnedDurably(
                    pluginId)) {
                throw new IOException(
                        "Could not remove pinned plugin state");
            }

            if (!DELETE_STATE_COMMITTED.equals(markerData.state)) {
                writePluginDeleteMarker(
                        directory, pluginId,
                        DELETE_STATE_COMMITTED,
                        markerData.transactionId, true);
                markerData = new PluginDeleteMarkerData(
                        DELETE_STATE_COMMITTED,
                        markerData.transactionId);
            }

            if (!discardPluginSourceRecoveryFiles(
                    directory, pluginId)) {
                throw new IOException(
                        "Could not remove plugin source recovery files");
            }
            if (marker.exists() && (!marker.delete()
                    || marker.exists())) {
                throw new IOException(
                        "Could not remove plugin delete marker");
            }
            syncDirectoryStrict(directory);
            RECOVERY_BLOCKED_PLUGIN_IDS.remove(pluginId);
            return true;
        } catch (Throwable failure) {
            RECOVERY_BLOCKED_PLUGIN_IDS.add(pluginId);
            FileLog.e("Plugin deletion remains pending for "
                    + pluginId, failure);
            return false;
        }
    }

    private static boolean discardPluginSourceRecoveryFiles(
            File directory, String pluginId) {
        File[] files = directory.listFiles((dir, name) ->
                name.equals("." + pluginId + ".py.update")
                        || name.equals(
                                "." + pluginId + ".py.update.deps")
                        || name.startsWith(
                                pluginId + ".py.bak.")
                        || name.startsWith(
                                "." + pluginId + ".py.new.")
                        || name.startsWith(
                                "." + pluginId
                                        + ".py.update.new.")
                        || name.startsWith(
                                "." + pluginId
                                        + ".py.deleted.")
                        || name.startsWith(
                                "." + pluginId
                                        + ".py.delete.new."));
        if (files == null) return false;
        boolean complete = true;
        for (File file : files) {
            if (!file.isFile() || (!file.delete() && file.exists())) {
                complete = false;
                FileLog.w("Could not remove plugin recovery file "
                        + file.getAbsolutePath());
            }
        }
        try {
            syncDirectoryStrict(directory);
        } catch (IOException failure) {
            FileLog.e("Could not sync plugin recovery cleanup",
                    failure);
            complete = false;
        }
        return complete;
    }

    private static boolean removePluginSettingsFromDisk(
            File directory, String pluginId) {
        File settings = new File(directory, "plugin_settings.json");
        if (!settings.exists()) return true;
        try {
            if (!settings.isFile()
                    || settings.length() < 0
                    || settings.length() > MAX_PLUGIN_SETTINGS_BYTES) {
                throw new IOException(
                        "Plugin settings file has an invalid size");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    (int) Math.max(32L, settings.length()));
            try (FileInputStream input =
                    new FileInputStream(settings)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) > 0) {
                    if (bytes.size() + count
                            > MAX_PLUGIN_SETTINGS_BYTES) {
                        throw new IOException(
                                "Plugin settings file is too large");
                    }
                    bytes.write(buffer, 0, count);
                }
            }
            com.google.gson.JsonElement parsed =
                    com.google.gson.JsonParser.parseString(
                            bytes.toString(
                                    StandardCharsets.UTF_8.name()));
            if (!parsed.isJsonObject()) {
                throw new IOException(
                        "Plugin settings root is not an object");
            }
            com.google.gson.JsonObject root =
                    parsed.getAsJsonObject();
            if (!root.has(pluginId)) return true;
            root.remove(pluginId);
            byte[] payload = root.toString()
                    .getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_PLUGIN_SETTINGS_BYTES) {
                throw new IOException(
                        "Rewritten plugin settings are too large");
            }
            File staged = new File(
                    directory,
                    ".plugin_settings.delete."
                            + Long.toHexString(System.nanoTime())
                            + ".tmp");
            try {
                try (FileOutputStream output =
                        new FileOutputStream(staged)) {
                    output.write(payload);
                    output.flush();
                    output.getFD().sync();
                }
                try {
                    android.system.Os.rename(
                            staged.getAbsolutePath(),
                            settings.getAbsolutePath());
                } catch (android.system.ErrnoException failure) {
                    throw new IOException(
                            "Could not publish plugin settings cleanup",
                            failure);
                }
                syncDirectoryStrict(directory);
            } finally {
                if (staged.exists() && !staged.delete()) {
                    FileLog.w("Could not remove plugin settings stage "
                            + staged.getAbsolutePath());
                }
            }
            return true;
        } catch (Throwable failure) {
            FileLog.e("Host-side plugin settings cleanup failed for "
                    + pluginId, failure);
            return false;
        }
    }

    private static boolean clearPluginHostPreferences(
            PluginsController controller, String pluginId) {
        SharedPreferences preferences = controller.preferences;
        if (preferences == null) return true;
        try {
            ArrayList<String> knownIds = new ArrayList<>();
            File[] installed = controller.getPluginsDir().listFiles(
                    (dir, name) -> name.endsWith(".py")
                            && name.length() > 3);
            if (installed != null) {
                for (File file : installed) {
                    knownIds.add(file.getName().substring(
                            0, file.getName().length() - 3));
                }
            }
            if (!knownIds.contains(pluginId)) {
                knownIds.add(pluginId);
            }
            knownIds.sort((left, right) ->
                    Integer.compare(right.length(), left.length()));

            SharedPreferences.Editor editor = preferences.edit()
                    .remove("plugin_enabled_" + pluginId)
                    .remove("plugin_crashed_" + pluginId)
                    .remove("plugin_enabled_before_quarantine_"
                            + pluginId);
            String activeWatermark = preferences.getString(
                    "crashed_plugin_id", null);
            if (pluginId.equals(activeWatermark)) {
                editor.remove("crashed_plugin_id")
                        .remove("crashed_plugin_started_at")
                        .remove("crashed_plugin_attribution_exact");
            }
            for (String key : new ArrayList<>(
                    preferences.getAll().keySet())) {
                if (key == null
                        || !key.startsWith("plugin_setting_")) {
                    continue;
                }
                String payload = key.substring(
                        "plugin_setting_".length());
                String owner = null;
                for (String candidate : knownIds) {
                    if (payload.startsWith(candidate + "_")) {
                        owner = candidate;
                        break;
                    }
                }
                if (pluginId.equals(owner)) {
                    editor.remove(key);
                }
            }
            return editor.commit();
        } catch (Throwable failure) {
            FileLog.e("Could not clear plugin preferences for "
                    + pluginId, failure);
            return false;
        }
    }

    private boolean removePluginSettingsHostNative(
            File directory, String pluginId) {
        synchronized (settingsReloadLock) {
            Python current = this.python;
            PyObject settingsModule = null;
            boolean pythonLocked = false;
            try {
                if (current != null && Python.isStarted()) {
                    settingsModule =
                            current.getModule("plugin_settings");
                    PyObject acquired = settingsModule.callAttr(
                            "begin_host_transaction");
                    if (acquired == null || !acquired.toBoolean()) {
                        return false;
                    }
                    pythonLocked = true;
                }
                if (!removePluginSettingsFromDisk(
                        directory, pluginId)) {
                    return false;
                }
                settingsCacheGenerations
                        .computeIfAbsent(
                                pluginId, ignored -> new AtomicLong())
                        .incrementAndGet();
                settingsCache.remove(pluginId);
                if (settingsModule == null) {
                    return true;
                }
                PyObject status = settingsModule.callAttr(
                        "reload_settings");
                return status != null && status.toBoolean();
            } catch (Throwable failure) {
                FileLog.e("Could not remove settings for "
                        + pluginId, failure);
                return false;
            } finally {
                if (pythonLocked) {
                    try {
                        settingsModule.callAttr(
                                "end_host_transaction");
                    } catch (Throwable unlockFailure) {
                        FileLog.e("Could not unlock plugin settings "
                                + "after deleting " + pluginId,
                                unlockFailure);
                    }
                }
            }
        }
    }

    private static File pluginUpdateDependencySnapshot(
            File directory, String pluginId) {
        return new File(
                directory,
                "." + pluginId + ".py.update.deps");
    }

    private static void cleanupOrphanDependencySnapshots(
            File directory) {
        File[] snapshots = directory.listFiles((dir, name) ->
                PLUGIN_UPDATE_DEPENDENCY_PATTERN.matcher(name).matches());
        if (snapshots == null) return;
        boolean changed = false;
        for (File snapshot : snapshots) {
            Matcher matcher = PLUGIN_UPDATE_DEPENDENCY_PATTERN.matcher(
                    snapshot.getName());
            if (!matcher.matches()) continue;
            File marker = new File(
                    directory,
                    "." + matcher.group(1) + ".py.update");
            if (!marker.exists()) {
                if (snapshot.delete()) {
                    changed = true;
                } else {
                    FileLog.w("Could not remove orphan plugin dependency "
                            + "snapshot " + snapshot.getAbsolutePath());
                }
            }
        }
        if (changed) syncDirectory(directory);
    }

    private static void clearUncommittedPluginWatermark(
            PluginsController controller, String pluginId) {
        if (controller.preferences == null
                || !pluginId.equals(controller.preferences.getString(
                        "crashed_plugin_id", null))) {
            return;
        }
        controller.preferences.edit()
                .remove("had_crash")
                .remove("crashed_plugin_id")
                .remove("crashed_plugin_started_at")
                .remove("native_crash_flag_only")
                .remove("crashed_plugin_attribution_exact")
                .commit();
    }

    private static final class EnableCancelledException extends Exception {
        EnableCancelledException(String pluginId) {
            super("Plugin enable cancelled: " + pluginId);
        }
    }

    private void ensureEnableStillRequested(String pluginId, int generation) throws EnableCancelledException {
        if (!getPluginsController().isPluginEnableRequested(pluginId, generation)) {
            throw new EnableCancelledException(pluginId);
        }
    }

    private void claimEnableCode(String pluginId, int generation) throws EnableCancelledException {
        if (!getPluginsController().claimPluginEnableCode(pluginId, generation)) {
            throw new EnableCancelledException(pluginId);
        }
    }

    private PipController.InstallerDelegate enableInstallDelegate(String pluginId, int generation) {
        return new PipController.InstallerDelegate() {
            @Override public boolean isCancelled() {
                return !getPluginsController().isPluginEnableRequested(pluginId, generation);
            }
            @Override public void onProgress(String text) {
            }
        };
    }

    private static ExecutorService newInitExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nimarko-plugin-init");
            t.setDaemon(true);
            return t;
        });
    }

    private void abandonStuckInitExecutor(ExecutorService stuck) {
        if (pluginInitExecutor == stuck) {
            pluginInitExecutor = newInitExecutor();
            stuck.shutdownNow();
        }
    }

    private static void runOnPluginsQueue(Runnable runnable) {
        if (runnable == null) return;
        if (Thread.currentThread() == Utilities.pluginsQueue) {
            runnable.run();
        } else {
            Utilities.pluginsQueue.postRunnable(runnable);
        }
    }

    private boolean deferUntilLifecycleSettled(String pluginId, Runnable action) {
        if (TextUtils.isEmpty(pluginId) || action == null) return false;
        while (true) {
            LifecycleOperation operation = lifecycleOperations.get(pluginId);
            if (operation == null) return false;
            synchronized (operation.monitor) {
                if (lifecycleOperations.get(pluginId) != operation
                        || operation.settled.get()) {
                    lifecycleOperations.remove(pluginId, operation);
                    continue;
                }
                if (operation.timedOut.get()
                        || operation.retirementExpired.get()
                        || operation.deferredAdmissionClosed) {
                    return false;
                }
                operation.deferredActions.add(action);
                return true;
            }
        }
    }

    private LifecycleOperation getTimedOutLifecycle(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) return null;
        LifecycleOperation operation = lifecycleOperations.get(pluginId);
        if (operation == null) return null;
        synchronized (operation.monitor) {
            return lifecycleOperations.get(pluginId) == operation
                    && !operation.settled.get()
                    && (operation.timedOut.get()
                        || operation.retirementExpired.get())
                    ? operation : null;
        }
    }

    private String lifecycleTimeoutMessage(String pluginId) {
        return "Plugin " + pluginId
                + " did not finish its lifecycle callback. Restart the app "
                + "before enabling, updating, or deleting it.";
    }

    private boolean rejectTimedOutLifecycle(
            String pluginId, Utilities.Callback<String> callback) {
        if (getTimedOutLifecycle(pluginId) == null) {
            return false;
        }
        String error = lifecycleTimeoutMessage(pluginId);
        FileLog.w("nimarko: rejecting operation for wedged plugin runtime "
                + pluginId);
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.run(error));
        }
        return true;
    }

    private void releaseDeferredActionsAfterTimeout(
            LifecycleOperation operation) {
        if (operation == null) return;
        final ArrayList<Runnable> actions;
        synchronized (operation.monitor) {
            if (operation.deferredActions.isEmpty()) {
                return;
            }
            actions = new ArrayList<>(operation.deferredActions);
            operation.deferredActions.clear();
        }
        for (Runnable action : actions) {
            Utilities.pluginsQueue.postRunnable(action);
        }
    }

    private boolean deferUntilAnyLifecycleSettles(Runnable action) {
        for (String pluginId : new ArrayList<>(lifecycleOperations.keySet())) {
            if (deferUntilLifecycleSettled(pluginId, action)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTimedOutLifecycleOperation() {
        for (LifecycleOperation operation
                : lifecycleOperations.values()) {
            if (operation != null
                    && !operation.settled.get()
                    && (operation.timedOut.get()
                        || operation.retirementExpired.get())) {
                return true;
            }
        }
        return false;
    }

    private void abandonPythonRuntimeForShutdown() {
        PYTHON_RUNTIME_ABANDONED.set(true);
        LinkedHashSet<String> pluginIds =
                new LinkedHashSet<>();
        pluginIds.addAll(pluginInstances.keySet());
        pluginIds.addAll(pluginRuntimeTokens.keySet());
        pluginIds.addAll(lifecycleOperations.keySet());

        for (String pluginId : pluginIds) {
            PluginsController.PluginRuntimeToken token =
                    pluginRuntimeTokens.remove(pluginId);
            LifecycleOperation operation =
                    lifecycleOperations.remove(pluginId);
            if (token == null && operation != null) {
                token = operation.runtimeToken;
            }
            if (operation != null) {
                synchronized (operation.monitor) {
                    operation.deferredAdmissionClosed = true;
                    operation.deferredActions.clear();
                    operation.settled.set(true);
                    if (operation.timedOut.get()) {
                        ABANDONED_RUNTIME_PLUGIN_IDS.add(pluginId);
                    }
                }
            }
            if (token != null) {
                getPluginsController().revokePluginRuntime(token);
            }
            try {
                getPluginsController().cleanupPlugin(
                        pluginId, token);
            } catch (Throwable cleanupFailure) {
                FileLog.e("Could not detach abandoned plugin runtime "
                        + pluginId, cleanupFailure);
            }
            pluginInstances.remove(pluginId);
            if (token != null) {
                final PluginsController.PluginRuntimeToken retired =
                        token;
                getPluginsController().runWhenPluginRuntimeQuiescent(
                        retired,
                        () -> getPluginsController()
                                .releasePluginRuntime(retired));
            }
        }

        PluginDevInstallBridge bridge = devInstallBridge;
        if (bridge != null) {
            revokeDevInstallBridge(bridge);
        }
        devServerClass = null;
        debuggerListener = null;
        basePluginClass = null;
        pluginsPathAdded = false;
        metadataCache.clear();
        settingsCache.clear();
        revokeAllInstallCandidates();
        ExecutorService executor = pluginInitExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        python = null;
        FileLog.w("nimarko: abandoned timed-out Python runtime; "
                + "process restart is required before re-enabling it");
    }

    public boolean requiresProcessRestart() {
        return PYTHON_RUNTIME_ABANDONED.get()
                || hasTimedOutLifecycleOperation()
                || PipController.getInstance()
                        .requiresProcessRestart();
    }

    public static boolean isProcessPythonRuntimeAbandoned() {
        return PYTHON_RUNTIME_ABANDONED.get();
    }

    private LifecycleOperation beginLifecycleOperation(
            String pluginId, PyObject instance,
            PluginsController.PluginRuntimeToken runtimeToken,
            int enableGeneration, LifecyclePhase phase)
            throws LifecyclePendingException {
        PluginDebugLog.log("PY lifecycle begin plugin=" + pluginId
                + " phase=" + phase
                + " generation=" + enableGeneration
                + " runtime=" + runtimeToken
                + " instance=" + System.identityHashCode(instance));
        LifecycleOperation operation = new LifecycleOperation(
                pluginId, instance, runtimeToken, enableGeneration, phase);
        LifecycleOperation existing = lifecycleOperations.putIfAbsent(pluginId, operation);
        if (existing != null && !existing.settled.get()) {
            PluginDebugLog.log("PY lifecycle begin rejected plugin="
                    + pluginId + " existingPhase=" + existing.phase
                    + " existingRuntime=" + existing.runtimeToken
                    + " timedOut=" + existing.timedOut.get()
                    + " returned=" + existing.actuallyReturned);
            throw new LifecyclePendingException(pluginId);
        }
        if (existing != null) {
            lifecycleOperations.remove(pluginId, existing);
            if (lifecycleOperations.putIfAbsent(pluginId, operation) != null) {
                throw new LifecyclePendingException(pluginId);
            }
        }
        return operation;
    }

    private void settleLifecycleOperation(LifecycleOperation operation) {
        if (operation == null) return;
        final ArrayList<Runnable> actions;
        synchronized (operation.monitor) {
            if (!operation.settled.compareAndSet(false, true)) {
                return;
            }
            operation.deferredAdmissionClosed = true;
            actions = new ArrayList<>(operation.deferredActions);
            operation.deferredActions.clear();
        }
        lifecycleOperations.remove(operation.pluginId, operation);
        PluginDebugLog.log("PY lifecycle settled plugin="
                + operation.pluginId
                + " phase=" + operation.phase
                + " runtime=" + operation.runtimeToken
                + " timedOut=" + operation.timedOut.get()
                + " returned=" + operation.actuallyReturned
                + " deferredActions=" + actions.size());
        for (Runnable action : actions) {
            Utilities.pluginsQueue.postRunnable(action);
        }
    }

    private void scheduleActualReturn(LifecycleOperation operation) {
        if (operation == null
                || !operation.completionScheduled.compareAndSet(false, true)) {
            return;
        }
        Utilities.pluginsQueue.postRunnable(() -> handleActualLifecycleReturn(operation));
    }

    private void markLifecycleTimedOut(
            LifecycleOperation operation, Future<?> future, ExecutorService executor) {
        synchronized (operation.monitor) {
            if (operation.settled.get()) {
                return;
            }
            operation.deferredAdmissionClosed = true;
            operation.timedOut.set(true);
        }
        boolean cancelledBeforeStart = operation.callState.compareAndSet(0, 3);
        PluginDebugLog.log("PY lifecycle timeout plugin="
                + operation.pluginId
                + " phase=" + operation.phase
                + " runtime=" + operation.runtimeToken
                + " callState=" + operation.callState.get()
                + " cancelledBeforeStart=" + cancelledBeforeStart
                + " returned=" + operation.actuallyReturned);
        if (future != null) {
            future.cancel(true);
        }
        abandonStuckInitExecutor(executor);
        if (cancelledBeforeStart) {
            operation.outcome = new LifecyclePendingException(
                    operation.pluginId + " (cancelled before start)");
            operation.actuallyReturned = true;
        }
        if (operation.actuallyReturned) {
            scheduleActualReturn(operation);
        }
        releaseDeferredActionsAfterTimeout(operation);
    }

    private void scheduleRuntimeRetirement(LifecycleOperation operation) {
        getPluginsController().runWhenPluginRuntimeQuiescent(
                operation.runtimeToken,
                () -> runOnPluginsQueue(() -> finalizeRuntimeRetirement(operation)));
    }

    private void scheduleRuntimeRetirementDeadline(
            LifecycleOperation operation) {
        if (operation == null
                || !operation.retirementDeadlineScheduled
                        .compareAndSet(false, true)) {
            return;
        }
        LIFECYCLE_DEADLINE_EXECUTOR.schedule(
                () -> expireRuntimeRetirement(operation),
                PLUGIN_RETIREMENT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
    }

    private void expireRuntimeRetirement(
            LifecycleOperation operation) {
        if (operation == null) return;
        synchronized (operation.monitor) {
            if (lifecycleOperations.get(operation.pluginId) != operation
                    || operation.settled.get()
                    || !operation.retirementExpired
                            .compareAndSet(false, true)) {
                return;
            }
            operation.deferredAdmissionClosed = true;
            operation.timedOut.set(true);
            operation.outcome = new TimeoutException(
                    "Physical runtime retirement exceeded "
                            + PLUGIN_RETIREMENT_TIMEOUT_MS + "ms");
        }
        operation.unloadStarted.compareAndSet(false, true);
        PYTHON_RUNTIME_ABANDONED.set(true);
        ABANDONED_RUNTIME_PLUGIN_IDS.add(operation.pluginId);
        getPluginsController().revokePluginRuntime(
                operation.runtimeToken);

        FileLog.e("nimarko: physical plugin retirement timed out for "
                + operation.pluginId
                + "; Python process restart is required");
        PluginDebugLog.log("PY retirement deadline expired plugin="
                + operation.pluginId
                + " phase=" + operation.phase
                + " runtime=" + operation.runtimeToken
                + " active="
                + getPluginsController().isPluginRuntimeExecuting(
                        operation.runtimeToken));
        getPluginsController().completePluginToggleForAbandonedRuntime(
                operation.pluginId,
                lifecycleTimeoutMessage(operation.pluginId));
        releaseDeferredActionsAfterTimeout(operation);

        scheduleRuntimeRetirement(operation);
    }

    private void schedulePluginUnloadAfterQuiescence(
            LifecycleOperation operation) {
        if (operation == null) return;
        PluginDebugLog.log("PY unload wait-quiescence plugin="
                + operation.pluginId
                + " runtime=" + operation.runtimeToken
                + " executing="
                + getPluginsController().isPluginRuntimeExecuting(
                        operation.runtimeToken));
        getPluginsController().runWhenPluginRuntimeQuiescent(
                operation.runtimeToken,
                () -> runOnPluginsQueue(() -> {
                    PluginDebugLog.log("PY unload quiescent callback plugin="
                            + operation.pluginId
                            + " runtime=" + operation.runtimeToken
                            + " settled=" + operation.settled.get()
                            + " expired="
                            + operation.retirementExpired.get());
                    if (lifecycleOperations.get(operation.pluginId) != operation
                            || operation.settled.get()
                            || operation.retirementExpired.get()
                            || !operation.unloadStarted.compareAndSet(false, true)) {
                        return;
                    }
                    callOnPluginUnloadWithTimeout(
                            operation.pluginId, operation.instance,
                            operation.runtimeToken, operation);
                }));
    }

    private void finalizeRuntimeRetirement(LifecycleOperation operation) {
        if (operation == null || lifecycleOperations.get(operation.pluginId) != operation) {
            return;
        }
        PluginDebugLog.log("PY retirement finalize plugin="
                + operation.pluginId
                + " runtime=" + operation.runtimeToken
                + " phase=" + operation.phase
                + " returned=" + operation.actuallyReturned);
        getPluginsController().cleanupPlugin(
                operation.pluginId, operation.runtimeToken);
        evictPluginInstance(
                operation.pluginId, operation.instance, operation.runtimeToken);
        settleLifecycleOperation(operation);
    }

    private void handleActualLifecycleReturn(LifecycleOperation operation) {
        if (operation == null || lifecycleOperations.get(operation.pluginId) != operation
                || !operation.actuallyReturned) {
            return;
        }
        if (operation.phase == LifecyclePhase.LOAD && operation.outcome == null) {
            operation.phase = LifecyclePhase.UNLOAD;
            operation.timedOut.set(false);
            operation.completionScheduled.set(false);
            scheduleRuntimeRetirementDeadline(operation);
            schedulePluginUnloadAfterQuiescence(operation);
            return;
        }
        scheduleRuntimeRetirement(operation);
    }

    @FunctionalInterface
    interface PyMethodCaller<T> {
        PyObject call(PyObject pyObject, T t);
    }

    @Override
    public boolean canOpenInExternalApp() {
        return true; 
    }

    private PluginsController getPluginsController() {
        return PluginsController.getInstance();
    }

    private PluginsController.PluginRuntimeToken enterCurrentRuntime(
            String pluginId, PyObject expectedInstance) {
        return enterCurrentRuntime(pluginId, expectedInstance, null);
    }

    private PluginsController.PluginRuntimeToken enterCurrentRuntime(
            String pluginId, PyObject expectedInstance,
            PluginsController.PluginRuntimeToken expectedRuntime) {
        if (TextUtils.isEmpty(pluginId) || expectedInstance == null
                || pluginInstances.get(pluginId) != expectedInstance) {
            return null;
        }
        PluginsController.PluginRuntimeToken token = pluginRuntimeTokens.get(pluginId);
        if (token == null
                || (expectedRuntime != null
                        && !expectedRuntime.equals(token))
                || getPluginsController().getPluginRuntimeTaskDecision(token)
                        != PluginsController.RUNTIME_TASK_RUN
                || !getPluginsController().enterPluginRuntime(token)) {
            return null;
        }
        if (pluginInstances.get(pluginId) != expectedInstance
                || pluginRuntimeTokens.get(pluginId) != token) {
            getPluginsController().exitPluginRuntime(token);
            return null;
        }
        return token;
    }

    private Python getPython() {
        if (PYTHON_RUNTIME_ABANDONED.get()) {
            FileLog.w("nimarko: refusing to reuse abandoned Python runtime");
            return null;
        }
        Python current = this.python;
        if (current == null || this.basePluginClass == null) {
            synchronized (PYTHON_START_LOCK) {
                if (PYTHON_RUNTIME_ABANDONED.get()) {
                    return null;
                }
                if (this.python == null) {
                    initPython();
                }
                current = this.python;
                if (current == null) {
                    FileLog.e("Python initialization failed, unable to proceed.");
                    return null;
                }
                if (this.basePluginClass == null) {
                    try {
                        this.basePluginClass = current.getModule("base_plugin").get("BasePlugin");
                    } catch (PyException e) {
                        FileLog.e("Failed to load BasePlugin class", e);
                    }
                }
            }
        }
        return current;
    }

    private boolean ensurePineReady() throws Exception {
        if (ApplicationLoader.isPineAvailable()) {
            return true;
        }
        final ExecutorService exec = pluginInitExecutor;
        AtomicBoolean taskStarted = new AtomicBoolean(false);
        Future<Boolean> future;
        synchronized (PINE_INIT_LOCK) {
            if (ApplicationLoader.isPineAvailable()) {
                return true;
            }
            if (pineInitAttemptRunning) {
                throw new TimeoutException(
                        "A previous Pine initialization attempt is still running");
            }
            pineInitAttemptRunning = true;
            try {
                future = exec.submit(() -> {
                    taskStarted.set(true);
                    try {
                        ApplicationLoader.ensurePineInited();
                        return ApplicationLoader.isPineAvailable();
                    } finally {
                        synchronized (PINE_INIT_LOCK) {
                            pineInitAttemptRunning = false;
                        }
                    }
                });
            } catch (RuntimeException e) {
                synchronized (PINE_INIT_LOCK) {
                    pineInitAttemptRunning = false;
                }
                throw e;
            }
        }
        try {
            return future.get(PINE_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            if (!taskStarted.get()) {
                synchronized (PINE_INIT_LOCK) {
                    pineInitAttemptRunning = false;
                }
            }
            abandonStuckInitExecutor(exec);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new Exception(cause != null ? cause : e);
        }
    }

    private void initPython() {
        synchronized (PYTHON_START_LOCK) {
            if (this.python != null
                    || PYTHON_RUNTIME_ABANDONED.get()) {
                return;
            }
            try {
                try {
                    org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
                } catch (Throwable bypassError) {
                    FileLog.w("nimarko: HiddenApiBypass unavailable before Python.start: " + bypassError);
                }
                try {
                    if (!ensurePineReady()) {
                        FileLog.w("nimarko: Pine unavailable; Python starts with method hooks disabled");
                    }
                } catch (Throwable pineError) {
                    FileLog.e("nimarko: optional Pine initialization failed; continuing without hooks", pineError);
                }
                final Python candidate;
                synchronized (Python.class) {
                    if (!Python.isStarted()) {
                        Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
                    }
                    candidate = Python.getInstance();
                }
                try {
                    candidate.getModule("plugin_compat").callAttr("install");
                } catch (Throwable compatibilityError) {
                    FileLog.e("Failed to initialize plugin compatibility layer",
                            compatibilityError);
                }
                PyObject base = candidate.getModule("base_plugin").get("BasePlugin");
                if (base == null) {
                    throw new IllegalStateException("BasePlugin was not initialized");
                }
                this.basePluginClass = base;
                this.python = candidate;
            } catch (Exception e) {
                FileLog.e("Failed to initialize Python", e);
            }
        }
    }

    @Override
    public boolean isPlugin(File file) {
        return file != null && file.getName().toLowerCase().endsWith(PluginsConstants.PLUGINS_EXT);
    }

    @Override
    public boolean isEngineAvailable() {
        return !PYTHON_RUNTIME_ABANDONED.get()
                && this.python != null && Python.isStarted();
    }

    @Override
    public void init(Runnable runnable) {
        if (!Utilities.pluginsQueue.isAlive()) {
            Utilities.pluginsQueue = new org.telegram.messenger.DispatchQueue("pluginsQueue");
        }
        Utilities.pluginsQueue.postRunnable(() -> {
            try {
                cleanupOrphanedHostInstallStages();
                recoverInterruptedPluginUpdates(getPluginsController());
                registerPluginsMetadataOnly(
                        PYTHON_RUNTIME_ABANDONED.get()
                                || NimarkoConfig.pluginsSafeMode);
                getPluginsController().notifyPluginsChanged();
                if (PYTHON_RUNTIME_ABANDONED.get()) {
                    FileLog.w("nimarko: Python runtime was abandoned; "
                            + "listing plugins without executing code until "
                            + "the process restarts");
                    if (runnable != null) {
                        AndroidUtilities.runOnUIThread(runnable);
                    }
                    return;
                }
                if (NimarkoConfig.pluginsSafeMode) {
                    FileLog.d("nimarko: safe mode active, skipping Python engine init");
                    if (runnable != null) {
                        AndroidUtilities.runOnUIThread(runnable);
                    }
                    return;
                }
                if (getPython() == null) {
                    FileLog.e("nimarko: PythonPluginsEngine.init() getPython()==null");
                    getPluginsController().clearPluginStartupActivations();
                    getPluginsController().notifyPluginsChanged();
                    if (runnable != null) {
                        AndroidUtilities.runOnUIThread(runnable);
                    }
                    return;
                }
                recoverInterruptedPluginUpdates(getPluginsController());
                if (!NimarkoConfig.pluginsSafeMode) {
                    try {
                        String[] strArr = getPython().getModule("plugin_settings").callAttr("init", getPluginsController().pluginsDir.getAbsolutePath(), getPluginsController().preferences.getAll()).toJava(String[].class);
                        if (strArr.length > 0) {
                            SharedPreferences.Editor editorEdit = getPluginsController().preferences.edit();
                            for (String str : strArr) {
                                editorEdit.remove(str);
                            }
                            editorEdit.apply();
                        }
                    } catch (PyException e) {
                        FileLog.e("Failed to initialize plugin_settings module", e);
                    } catch (Throwable th) {
                        FileLog.e("nimarko: plugin_settings init failed", th);
                    }
                }
                loadPlugins(runnable);
                checkDevServer();
            } catch (Throwable th) {
                FileLog.e("nimarko: PythonPluginsEngine.init crashed", th);
                if (runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                }
            }
        });
    }

    @Override
    public void checkDevServer() {
        if (NimarkoConfig.pluginsDevMode) {
            runDevServer();
        } else {
            stopDevServer();
        }
    }

    private void runDevServer() {
        if (PYTHON_RUNTIME_ABANDONED.get()) {
            return;
        }
        final Python current = getPython();
        if (current == null) {
            return;
        }
        synchronized (devServerLock) {
            if (this.devServerClass != null) {
                stopDevServer();
                if (PYTHON_RUNTIME_ABANDONED.get()) {
                    return;
                }
            }
            long bridgeGeneration =
                    devInstallBridgeGeneration.incrementAndGet();
            PluginDevInstallBridge bridge = new PluginDevInstallBridge(
                    this, bridgeGeneration);
            synchronized (installPublicationLock) {
                this.devInstallBridge = bridge;
            }
            try {
                PyObject pyObject = current.getModule(
                        PluginsConstants.DevServer.MODULE).get(
                                PluginsConstants.DevServer.CLASS);
                this.devServerClass = pyObject;
                if (pyObject == null) {
                    revokeDevInstallBridge(bridge);
                    return;
                }
                if (!bridge.startServer(pyObject)) {
                    throw new IllegalStateException(
                            "Java could not start the development server thread");
                }
                if (isCurrentDevInstallBridge(bridge, bridgeGeneration)) {
                    FileLog.d("Dev server started successfully.");
                }
            } catch (Throwable th) {
                FileLog.e("Failed to initialize dev server", th);
                revokeDevInstallBridge(bridge);
                this.devServerClass = null;
            }
        }
    }

    private void stopDevServer() {
        synchronized (devServerLock) {
            PluginDevInstallBridge bridge = this.devInstallBridge;
            if (bridge != null) {
                revokeDevInstallBridge(bridge);
            }
            PyObject pyObject = this.devServerClass;
            if (pyObject == null) {
                return;
            }
            try {
                PyObject result = pyObject.callAttrThrows(
                        PluginsConstants.DevServer.STOP_SERVER);
                boolean stopRequested =
                        result == null || result.toBoolean();
                boolean stopped = bridge == null
                        || bridge.awaitServerTermination(5_000L);
                if (!stopRequested || !stopped) {
                    PYTHON_RUNTIME_ABANDONED.set(true);
                    revokeAllInstallCandidates();
                    FileLog.e("Development server did not terminate; "
                            + "process restart is required");
                } else {
                    FileLog.d("Dev server stopped successfully.");
                }
            } catch (Throwable th) {
                FileLog.e("Failed to stop dev server", th);
                if (bridge != null && bridge.isServerThreadAlive()) {
                    PYTHON_RUNTIME_ABANDONED.set(true);
                    revokeAllInstallCandidates();
                }
            } finally {
                this.devServerClass = null;
            }
        }
    }

    private void revokeDevInstallBridge(
            PluginDevInstallBridge bridge) {
        if (bridge == null) return;
        synchronized (installPublicationLock) {
            if (devInstallBridge == bridge) {
                devInstallBridge = null;
            }
            bridge.revokeFromHost();
        }
    }

    void onDevServerTerminated(
            PluginDevInstallBridge bridge, long generation) {
        synchronized (installPublicationLock) {
            if (bridge != null
                    && bridge == devInstallBridge
                    && bridge.belongsTo(this, generation)) {
                devInstallBridge = null;
                bridge.revokeFromHost();
                devServerClass = null;
            }
        }
    }

    @Override
    public void shutdown(Runnable runnable) {
        if (hasTimedOutLifecycleOperation()) {
            abandonPythonRuntimeForShutdown();
            if (runnable != null) {
                runnable.run();
            }
            return;
        }
        if (deferUntilAnyLifecycleSettles(() -> shutdown(runnable))) {
            FileLog.d("nimarko: engine shutdown waits for physical plugin retirement");
            return;
        }
        stopDevServer();
        revokeAllInstallCandidates();
        if (this.python == null) {
            if (runnable != null) {
                runnable.run();
                return;
            }
            return;
        }
        try {
            for (String pluginId : new ArrayList<>(this.pluginInstances.keySet())) {
                if (!unloadPluginNow(pluginId)) {
                    deferUntilLifecycleSettled(pluginId, () -> shutdown(runnable));
                    return;
                }
            }
            PyObject pyObject = this.debuggerListener;
            if (pyObject != null) {
                this.debuggerListener = null;
            }
            if (!this.pluginInstances.isEmpty()
                    || deferUntilAnyLifecycleSettles(() -> shutdown(runnable))) {
                return;
            }
            for (PluginsController.PluginRuntimeToken token : pluginRuntimeTokens.values()) {
                getPluginsController().revokePluginRuntime(token);
                getPluginsController().runWhenPluginRuntimeQuiescent(
                        token,
                        () -> getPluginsController().releasePluginRuntime(token));
            }
            this.pluginRuntimeTokens.clear();
            this.legacyOverlayProbes.clear();
            this.metadataCache.clear();
            this.python = null;
            FileLog.d("Python plugin engine shut down.");
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (runnable != null) {
            runnable.run();
        }
    }

    private void registerPluginsForSafeMode() {
        registerPluginsMetadataOnly(true);
    }

    private void registerPluginsMetadataOnly(boolean forceDisabled) {
        PluginsController controller = getPluginsController();
        controller.clearPluginStartupActivations();
        try {
            File[] files = controller.pluginsDir.listFiles(
                    (dir, name) -> name.toLowerCase().endsWith(".py"));
            if (files == null) return;
            for (File file : files) {
                String pid = file.getName().substring(0, file.getName().length() - 3);
                if ("nimarkobanner".equalsIgnoreCase(pid)) continue;
                Plugin plugin = createBoundedMetadataPlugin(file, pid);
                boolean recoveryBlocked =
                        RECOVERY_BLOCKED_PLUGIN_IDS.contains(pid);
                boolean quarantined = controller.preferences.getBoolean(
                        "plugin_crashed_" + pid, false);
                if (recoveryBlocked) {
                    plugin.setError(new IOException(
                            "Plugin update recovery is incomplete"));
                } else if (quarantined) {
                    plugin.setError(new Exception(LocaleController.getString(
                            R.string.NM_PluginCrashedDisabled)));
                } else {
                    plugin.setError(null);
                }
                plugin.setEnabled(false);
                controller.plugins.put(pid, plugin);
                boolean restoreEnabled = !forceDisabled
                        && !recoveryBlocked
                        && !quarantined
                        && controller.preferences.getBoolean(
                        PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pid,
                        false);
                controller.setPluginStartupActivationPending(
                        pid, restoreEnabled);
            }
        } catch (Throwable t) {
            FileLog.e("nimarko: safe-mode plugin listing failed", t);
        }
    }

    private Plugin createBoundedMetadataPlugin(
            File file, String expectedId) {
        Plugin plugin = null;
        try {
            Map<String, String> metadata = parseSafeModeMetadata(file);
            String id = metadata.get("id");
            String name = metadata.get("name");
            String minVersion = metadata.get("min_version");
            if (expectedId.equals(id)
                    && !TextUtils.isEmpty(name)
                    && id.matches("^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")
                    && (minVersion == null
                    || SharedConfig.versionBiggerOrEqual(
                    BuildVars.BUILD_VERSION_STRING, minVersion))) {
                plugin = new Plugin(id, name);
                plugin.setAuthor(metadata.getOrDefault(
                        "author", LocaleController.getString(
                        R.string.PluginNoAuthor)));
                plugin.setDescription(metadata.getOrDefault(
                        "description", LocaleController.getString(
                        R.string.PluginNoDescription)));
                plugin.setIcon(metadata.get("icon"));
                plugin.setVersion(metadata.getOrDefault("version", "1.0"));
                plugin.setMinVersion(minVersion);
                plugin.setRequirements(metadata.get("requirements"));
                plugin.setEngine(PluginsConstants.PYTHON);
            }
        } catch (Throwable ignored) {
        }
        if (plugin == null) {
            plugin = new Plugin(expectedId, expectedId);
            plugin.setAuthor(LocaleController.getString(
                    R.string.PluginNoAuthor));
            plugin.setVersion("1.0");
            plugin.setEngine(PluginsConstants.PYTHON);
        }
        return plugin;
    }

    private Map<String, String> parseSafeModeMetadata(File file) throws IOException {
        HashMap<String, String> metadata = new HashMap<>();
        final int maxMetadataBytes = 64 * 1024;
        StringBuilder source = new StringBuilder((int) Math.min(file.length(), maxMetadataBytes));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while (source.length() < maxMetadataBytes
                    && (read = reader.read(buffer, 0, Math.min(buffer.length, maxMetadataBytes - source.length()))) != -1) {
                source.append(buffer, 0, read);
            }
        }
        Matcher matcher = SAFE_MODE_METADATA_PATTERN.matcher(source);
        while (matcher.find()) {
            metadata.put(matcher.group(1), matcher.group(3));
        }
        if (!metadata.containsKey("requirements")) {
            Matcher listMatcher = SAFE_MODE_REQUIREMENTS_LIST_PATTERN.matcher(source);
            if (listMatcher.find()) {
                ArrayList<String> requirements = new ArrayList<>();
                Matcher itemMatcher = SAFE_MODE_LIST_STRING_PATTERN.matcher(listMatcher.group(1));
                while (itemMatcher.find()) requirements.add(itemMatcher.group(2));
                if (!requirements.isEmpty()) metadata.put("requirements", TextUtils.join("\n", requirements));
            }
        }
        return metadata;
    }

    public void loadPlugins(final Runnable runnable) {
        Utilities.pluginsQueue.postRunnable(() -> {
            Plugin plugin;
            if (getPython() == null) {
                FileLog.e("nimarko: loadPlugins() getPython()==null");
                getPluginsController().clearPluginStartupActivations();
                getPluginsController().notifyPluginsChanged();
                if (runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                    return;
                }
                return;
            }
            try {
                try {
                    PipController.getInstance()
                            .bootstrapRuntimeForPluginStartup();
                } catch (RuntimeException bootstrapFailure) {
                    FileLog.e("nimarko: plugin dependency bootstrap failed",
                            bootstrapFailure);
                    registerPluginsForSafeMode();
                    getPluginsController().notifyPluginsChanged();
                    if (runnable != null) {
                        AndroidUtilities.runOnUIThread(runnable);
                    }
                    return;
                }
                PyObject module = getPython().getModule("sys");
                try {
                    PyObject pyObject = module.get("path");
                    if (pyObject != null && !pluginsPathAdded) {
                         pyObject.callAttr("append", getPluginsController().pluginsDir.getAbsolutePath());
                         pluginsPathAdded = true;
                    }
                    module.callAttr("setswitchinterval", 0.01d);
                    if (NimarkoConfig.pluginsSafeMode) {
                        getPluginsController().notifyPluginsChanged();
                        if (runnable != null) {
                            AndroidUtilities.runOnUIThread(runnable);
                        }
                        return;
                    }
                    File[] fileArrListFiles = getPluginsController().pluginsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".py"));
                    {
                        StringBuilder sb = new StringBuilder();
                        if (fileArrListFiles != null) for (File f : fileArrListFiles) sb.append(f.getName()).append("(").append(f.length()).append(") ");
                        PluginDebugLog.log("===== loadPlugins() START dir=" + getPluginsController().pluginsDir.getAbsolutePath()
                                + " files=[" + sb.toString().trim() + "]");
                    }
                    if (fileArrListFiles == null) {
                        getPluginsController().clearPluginStartupActivations();
                        getPluginsController().notifyPluginsChanged();
                        if (runnable != null) {
                            AndroidUtilities.runOnUIThread(runnable);
                            return;
                        }
                        return;
                    }
                    Map<String, File> startupFiles = new LinkedHashMap<>();
                    Map<String, Plugin> startupPlugins =
                            new LinkedHashMap<>();
                    for (File file : fileArrListFiles) {
                        String pluginId = file.getName().substring(
                                0, file.getName().length() - 3);
                        if ("nimarkobanner".equalsIgnoreCase(pluginId)) {
                            try {
                                file.delete();
                            } catch (Throwable ignored) {
                            }
                            try {
                                getPluginsController().preferences.edit()
                                        .remove("plugin_enabled_" + pluginId)
                                        .apply();
                            } catch (Throwable ignored) {
                            }
                            getPluginsController()
                                    .setPluginStartupActivationPending(
                                            pluginId, false);
                            continue;
                        }

                        Plugin parsedPlugin = null;
                        try {
                            PluginsController.PluginValidationResult result =
                                    validatePluginFromFile(
                                            file.getAbsolutePath());
                            parsedPlugin = result != null
                                    ? result.plugin : null;

                            if (RECOVERY_BLOCKED_PLUGIN_IDS.contains(
                                    pluginId)) {
                                if (parsedPlugin == null) {
                                    parsedPlugin =
                                            createBoundedMetadataPlugin(
                                                    file, pluginId);
                                }
                                parsedPlugin.setEnabled(false);
                                parsedPlugin.setError(new IOException(
                                        "Plugin update recovery is incomplete"));
                                getPluginsController().plugins.put(
                                        pluginId, parsedPlugin);
                                getPluginsController()
                                        .setPluginStartupActivationPending(
                                                pluginId, false);
                                continue;
                            }

                            if (getPluginsController().preferences.getBoolean(
                                    "plugin_crashed_" + pluginId, false)) {
                                if (parsedPlugin == null) {
                                    parsedPlugin =
                                            createBoundedMetadataPlugin(
                                                    file, pluginId);
                                }
                                parsedPlugin.setEnabled(false);
                                parsedPlugin.setError(new Exception(
                                        LocaleController.getString(
                                                R.string
                                                        .NM_PluginCrashedDisabled)));
                                getPluginsController().preferences.edit()
                                        .putBoolean(
                                                "plugin_enabled_" + pluginId,
                                                false)
                                        .apply();
                                getPluginsController().plugins.put(
                                        pluginId, parsedPlugin);
                                getPluginsController()
                                        .setPluginStartupActivationPending(
                                                pluginId, false);
                                continue;
                            }

                            if (result == null || result.error != null
                                    || parsedPlugin == null) {
                                throw new Exception(
                                        result != null && result.error != null
                                                ? result.error
                                                : "Plugin metadata is invalid");
                            }
                            if (!pluginId.equals(parsedPlugin.getId())) {
                                throw new Exception(
                                        "Plugin ID mismatch. Expected: "
                                                + pluginId + ", found: "
                                                + parsedPlugin.getId());
                            }

                            parsedPlugin.setError(null);
                            parsedPlugin.setEnabled(false);
                            getPluginsController().plugins.put(
                                    pluginId, parsedPlugin);
                            startupFiles.put(pluginId, file);
                            startupPlugins.put(pluginId, parsedPlugin);
                            getPluginsController()
                                    .setPluginStartupActivationPending(
                                            pluginId,
                                            getPluginsController()
                                                    .preferences.getBoolean(
                                                            "plugin_enabled_"
                                                                    + pluginId,
                                                            false));
                        } catch (Throwable validationFailure) {
                            if (parsedPlugin == null) {
                                parsedPlugin = createBoundedMetadataPlugin(
                                        file, pluginId);
                            }
                            parsedPlugin.setError(validationFailure);
                            parsedPlugin.setEnabled(false);
                            getPluginsController().plugins.put(
                                    pluginId, parsedPlugin);
                            getPluginsController()
                                    .setPluginStartupActivationPending(
                                            pluginId, false);
                            FileLog.e(
                                    "nimarko: plugin metadata validation failed for "
                                            + file.getName(),
                                    validationFailure);
                        }
                    }

                    getPluginsController().notifyPluginsChanged();

                    Map<String, List<String>> reqsByPlugin =
                            new LinkedHashMap<>();
                    java.util.Set<String> dependenciesPrepared =
                            new java.util.HashSet<>();
                    for (Map.Entry<String, Plugin> entry
                            : startupPlugins.entrySet()) {
                        String pluginId = entry.getKey();
                        if (!getPluginsController().preferences.getBoolean(
                                "plugin_enabled_" + pluginId, false)) {
                            continue;
                        }
                        String requirements =
                                entry.getValue().getRequirementsRaw();
                        reqsByPlugin.put(
                                pluginId,
                                TextUtils.isEmpty(requirements)
                                        ? Collections.emptyList()
                                        : parseRequirements(requirements));
                    }
                    if (!reqsByPlugin.isEmpty()) {
                        for (Map.Entry<String, List<String>> e
                                : reqsByPlugin.entrySet()) {
                            try {
                                int generation = getPluginsController()
                                        .getPluginToggleGeneration(
                                                e.getKey());
                                PipController pip =
                                        PipController.getInstance();
                                if (!pip.isDependencyInstallNoOp(
                                        e.getValue(), e.getKey())) {
                                    pip.installDependencies(
                                            e.getValue(), e.getKey(),
                                            enableInstallDelegate(
                                                    e.getKey(),
                                                    generation));
                                }
                                dependenciesPrepared.add(e.getKey());
                            } catch (Throwable t) {
                                FileLog.e(
                                        "nimarko: pip install failed for "
                                                + e.getKey() + " reqs="
                                                + e.getValue()
                                                + " (deps may be bundled already): "
                                                + t.getMessage());
                            }
                        }
                    }

                    for (Map.Entry<String, File> entry
                            : startupFiles.entrySet()) {
                        String pluginId = entry.getKey();
                        File file = entry.getValue();
                        Plugin startupPlugin =
                                startupPlugins.get(pluginId);
                        try {
                            loadPlugin(
                                    pluginId, file.getAbsolutePath(),
                                    startupPlugin, false,
                                    getPluginsController()
                                            .getPluginToggleGeneration(
                                                    pluginId),
                                    dependenciesPrepared.contains(
                                            pluginId));
                        } catch (EnableCancelledException cancelled) {
                            startupPlugin.setEnabled(false);
                            startupPlugin.setError(null);
                            getPluginsController().plugins.put(
                                    pluginId, startupPlugin);
                            evictPluginInstance(
                                    pluginId,
                                    this.pluginInstances.get(pluginId));
                        } catch (Throwable th) {
                            FileLog.e(
                                    "nimarko: loadPlugins() failed for "
                                            + file.getName() + ": "
                                            + th.getMessage(),
                                    th);
                            startupPlugin.setError(th);
                            startupPlugin.setEnabled(false);
                            getPluginsController().plugins.put(
                                    pluginId, startupPlugin);
                        } finally {
                            getPluginsController()
                                    .setPluginStartupActivationPending(
                                            pluginId, false);
                            getPluginsController().notifyPluginsChanged();
                        }
                    }
                    getPluginsController().clearPluginStartupActivations();
                    PipController.getInstance().cleanup();
                    getPluginsController().notifyPluginsChanged();
                    long enabledCount = getPluginsController().plugins.values().stream().filter(p -> p.isEnabled() && !p.hasError()).count();
                    FileLog.d("nimarko: loadPlugins() done. Total=" + getPluginsController().plugins.size() + " Enabled=" + enabledCount);
                    if (runnable != null) {
                        AndroidUtilities.runOnUIThread(runnable);
                    }
                } finally {
                }
            } catch (PyException e) {
                FileLog.e("nimarko: loadPlugins() Python setup failed", e);
                getPluginsController().clearPluginStartupActivations();
                getPluginsController().notifyPluginsChanged();
                if (runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                }
            } catch (Throwable failure) {
                FileLog.e("nimarko: loadPlugins() failed", failure);
                getPluginsController().clearPluginStartupActivations();
                getPluginsController().notifyPluginsChanged();
                if (runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                }
            }
        });
    }

    private void loadPlugin(String str, String str2) throws Exception {
        loadPlugin(str, str2, null, false, getPluginsController().getPluginToggleGeneration(str));
    }

    private void loadPlugin(String str, String str2, Plugin plugin) throws Exception {
        loadPlugin(str, str2, plugin, false, getPluginsController().getPluginToggleGeneration(str));
    }

    private void loadPlugin(String str, String str2, Plugin plugin, boolean forceInstantiate,
                            int enableGeneration) throws Exception {
        loadPlugin(
                str, str2, plugin, forceInstantiate,
                enableGeneration, false, false);
    }

    private void loadPlugin(String str, String str2, Plugin plugin, boolean forceInstantiate,
                            int enableGeneration, boolean dependenciesPrepared) throws Exception {
        loadPlugin(
                str, str2, plugin, forceInstantiate,
                enableGeneration, dependenciesPrepared, false);
    }

    private void loadPlugin(
            String str, String str2, Plugin plugin,
            boolean forceInstantiate, int enableGeneration,
            boolean dependenciesPrepared,
            boolean deferDependencyCleanup) throws Exception {
        if (Thread.currentThread() != Utilities.pluginsQueue) {
            throw new IllegalStateException(
                    "Internal plugin loader is pluginsQueue-only");
        }
        PluginDebugLog.log("loadPlugin START id=" + str + " file=" + str2 + " plugin=" + (plugin != null ? plugin.getId() : "null"));
        if (PYTHON_RUNTIME_ABANDONED.get()) {
            throw new IOException(
                    "Python runtime was abandoned after a lifecycle timeout; "
                            + "restart the app before enabling plugins");
        }
        if (!deferDependencyCleanup
                && RECOVERY_BLOCKED_PLUGIN_IDS.contains(str)) {
            throw new IOException(
                    "Plugin update recovery is incomplete for " + str);
        }
        if (ABANDONED_RUNTIME_PLUGIN_IDS.contains(str)) {
            throw new IOException(
                    "Plugin runtime was abandoned after a lifecycle timeout; "
                            + "restart the app before enabling " + str);
        }
        if (lifecycleOperations.containsKey(str)) {
            throw new LifecyclePendingException(str);
        }
        boolean z = getPluginsController().preferences.getBoolean("plugin_enabled_" + str, false);
        File file = new File(str2);
        if (!file.exists() || !file.isFile()) {
            PluginDebugLog.log("loadPlugin FAIL: file missing " + str2);
            throw new Exception("Plugin file not found: " + str2);
        }
        if (plugin == null) {
            PluginsController.PluginValidationResult pluginValidationResultValidatePluginFromFile = validatePluginFromFile(str2);
            if (pluginValidationResultValidatePluginFromFile.error != null) {
                throw new Exception(pluginValidationResultValidatePluginFromFile.error);
            }
            plugin = pluginValidationResultValidatePluginFromFile.plugin;
        }
        if (!str.equals(plugin.getId())) {
            throw new Exception(String.format("Plugin ID mismatch. Expected: %s, but found: %s in metadata.", str, plugin.getId()));
        }
        final boolean shouldEnable = z || forceInstantiate;
        final java.util.List<String> requestedDependencies;
        final boolean dependencyInstallNoOp;
        if (shouldEnable && !dependenciesPrepared) {
            String requirements = plugin.getRequirementsRaw();
            requestedDependencies =
                    TextUtils.isEmpty(requirements)
                            ? Collections.emptyList()
                            : parseRequirements(requirements);
            dependencyInstallNoOp =
                    PipController.getInstance()
                            .isDependencyInstallNoOp(
                                    requestedDependencies, str);
        } else {
            requestedDependencies = Collections.emptyList();
            dependencyInstallNoOp = false;
        }
        final PipController.DependencySnapshot dependencySnapshot =
                shouldEnable && !dependencyInstallNoOp
                        ? PipController.getInstance().snapshotState(str)
                        : null;
        if (!shouldEnable) {
            plugin.setEnabled(false);
            plugin.setError(null);
            plugin.setEngine(PluginsConstants.PYTHON);
            getPluginsController().plugins.put(str, plugin);
            PyObject oldInstance = pluginInstances.get(str);
            PluginsController.PluginRuntimeToken oldToken = pluginRuntimeTokens.get(str);
            if (oldInstance != null) {
                evictPluginInstance(str, oldInstance, oldToken);
            } else if (oldToken != null) {
                pluginRuntimeTokens.remove(str, oldToken);
                getPluginsController().revokePluginRuntime(oldToken);
                getPluginsController().runWhenPluginRuntimeQuiescent(
                        oldToken,
                        () -> getPluginsController().releasePluginRuntime(oldToken));
            }
            PluginDebugLog.log("loadPlugin metadata-only id=" + str + " (disabled)");
            return;
        }
        plugin.setEnabled(false);
        if (this.pluginInstances.containsKey(str)) {
            if (!unloadPluginNow(str)) {
                throw new LifecyclePendingException(str);
            }
        }
        getPluginsController().preferences.edit()
                .putString("crashed_plugin_id", str)
                .putLong("crashed_plugin_started_at", System.currentTimeMillis())
                .commit();
        getPluginsController().beginPluginInitialization(str, enableGeneration);
        PluginsController.PluginRuntimeToken runtimeToken = null;
        PyObject importedModule = null;
        PyObject createdInstance = null;
        boolean runtimeScopeEntered = false;
        try {
        ensureEnableStillRequested(str, enableGeneration);
        try {
            if (dependenciesPrepared) {
                PluginDebugLog.log("loadPlugin deps already prepared id=" + str);
            } else if (dependencyInstallNoOp) {
                PluginDebugLog.log(
                        "loadPlugin deps unchanged/no-op id=" + str);
            } else {
                PluginDebugLog.log("loadPlugin deps install id=" + str
                        + " reqs=" + requestedDependencies);
                PipController.getInstance().installDependencies(
                        requestedDependencies, str,
                        enableInstallDelegate(str, enableGeneration));
                ensureEnableStillRequested(str, enableGeneration);
                PluginDebugLog.log("loadPlugin deps install DONE id=" + str);
            }
        } catch (Throwable depEx) {
            if (depEx instanceof PipController.InstallCancelledRuntimeException
                    || !getPluginsController().isPluginEnableRequested(str, enableGeneration)) {
                throw new EnableCancelledException(str);
            }
            PluginDebugLog.log("loadPlugin deps install FAILED id=" + str, depEx);
            FileLog.e("nimarko: dependency install before import failed for " + str, depEx);
            throw new Exception("Failed to install dependencies for " + str + ": "
                    + depEx.getMessage(), depEx);
        }
	            claimEnableCode(str, enableGeneration);
            runtimeToken = getPluginsController().preparePluginRuntime(str, enableGeneration);
            if (runtimeToken == null || !getPluginsController().enterPluginRuntime(runtimeToken)) {
                throw new EnableCancelledException(str);
            }
            runtimeScopeEntered = true;
            PluginUiRegistry.DecorChildrenSnapshot overlayProbe =
                    PluginUiRegistry.captureDecorChildrenBlocking(750L);
            if (overlayProbe != null) {
                legacyOverlayProbes.put(runtimeToken, overlayProbe);
            }
            PluginDebugLog.log("loadPlugin importing module id=" + str);
            PyObject module = getPython().getModule(str);
            importedModule = module;
            module.put("__nimarko_runtime_token__", runtimeToken);
            module.put("__nimarko_plugin_id__", str);
            module.put("__nimarko_plugin_generation__", enableGeneration);
            module.put("__nimarko_plugin_instance_id__", runtimeToken.getInstanceId());
            ensureEnableStillRequested(str, enableGeneration);
            PluginDebugLog.log("loadPlugin module imported id=" + str + " → finding BasePlugin class");
            PyObject pyObjectFindPluginClass = findPluginClass(module);
            if (pyObjectFindPluginClass == null) {
                PluginDebugLog.log("loadPlugin FAIL: no BasePlugin subclass in " + str + ".py");
                throw new Exception("Could not find a class inheriting from BasePlugin in " + str + ".py. Make sure your main plugin class extends BasePlugin.");
            }
            PluginDebugLog.log("loadPlugin found class, instantiating id=" + str);
            claimEnableCode(str, enableGeneration);
            PyObject pyObjectCall = pyObjectFindPluginClass.call();
            createdInstance = pyObjectCall;
            ensureEnableStillRequested(str, enableGeneration);
            pyObjectCall.put("id", plugin.getId());
            pyObjectCall.put("_runtime_token", runtimeToken);
            pyObjectCall.put("_nimarko_runtime_token", runtimeToken);
            pyObjectCall.put("name", plugin.getName());
            pyObjectCall.put("description", plugin.getDescription());
            pyObjectCall.put("author", plugin.getAuthor());
            pyObjectCall.put("version", plugin.getVersion());
            pyObjectCall.put("icon", plugin.getIcon());
            pyObjectCall.put("min_version", plugin.getMinVersion());
            pyObjectCall.put("enabled", false);
            pyObjectCall.put("initialized", false);
            pyObjectCall.put("error_message", null);
            getPluginsController().plugins.put(str, plugin);
            this.pluginRuntimeTokens.put(str, runtimeToken);
            this.pluginInstances.put(str, pyObjectCall);
            try {
                java.util.Set<String> impl = new java.util.HashSet<>();
                for (String hook : new String[]{
                        "on_send_message_hook", "pre_request_hook", "post_request_hook",
                        "on_update_hook", "on_updates_hook",
                        "on_app_event"
                }) {
                    try {
                        PyObject fn = pyObjectCall.get(hook);
                        if (fn == null) continue;
                        PyObject ref = fn;
                        try {
                            PyObject inner = fn.get("__func__");
                            if (inner != null) ref = inner;
                        } catch (Throwable ignored) {}
                        PyObject qualObj = ref.get("__qualname__");
                        String qual = qualObj != null ? qualObj.toString() : "";
                        if (!qual.startsWith("BasePlugin.")) {
                            impl.add(hook);
                        }
                    } catch (Throwable t) {
                        throw new RuntimeException("hook probe failed for " + hook, t);
                    }
                }
                plugin.implementedHooks = impl;
                java.util.HashMap<String, PyObject> bound = new java.util.HashMap<>(impl.size() * 2);
                for (String hook : impl) {
                    try {
                        PyObject ref = pyObjectCall.get(hook);
                        if (ref != null) bound.put(hook, ref);
                    } catch (Throwable ignored) {}
                }
                plugin.boundHooks = bound;
                FileLog.d("nimarko: plugin " + str + " implements " + impl);
            } catch (Throwable t) {
                plugin.implementedHooks = null;
            }
            if (z && !forceInstantiate) {
                ensureEnableStillRequested(str, enableGeneration);
                setPluginEnabled(
                        str, true, enableGeneration, null,
                        deferDependencyCleanup);
                LifecycleOperation pending = lifecycleOperations.get(str);
                if (pending != null && pending.instance == createdInstance) {
                    throw new LifecyclePendingException(str);
                }
                ensureEnableStillRequested(str, enableGeneration);
                Plugin published = getPluginsController().plugins.get(str);
                PyObject publishedInstance = pluginInstances.get(str);
                PluginsController.PluginRuntimeToken publishedToken =
                        pluginRuntimeTokens.get(str);
                if (published != plugin
                        || !plugin.isEnabled()
                        || publishedInstance != createdInstance
                        || publishedToken != runtimeToken
                        || !PyObjectUtils.getBoolean(
                                createdInstance, "initialized", false)
                        || getPluginsController()
                                .getPluginRuntimeTaskDecision(runtimeToken)
                                != PluginsController.RUNTIME_TASK_RUN) {
                    Throwable enableFailure = plugin.getError();
                    if (enableFailure instanceof Exception) {
                        throw (Exception) enableFailure;
                    }
                    throw new Exception(
                            "Plugin did not become active after on_plugin_load: "
                                    + str,
                            enableFailure);
                }
            }
            if (!dependenciesPrepared && !forceInstantiate
                    && !deferDependencyCleanup) {
                PipController.getInstance().cleanup();
            }
            PluginDebugLog.log("loadPlugin SUCCESS id=" + str + " enabled=" + shouldEnable);
        } catch (LifecyclePendingException pending) {
            throw pending;
        } catch (EnableCancelledException cancelled) {
            int cancellationGeneration = getPluginsController()
                    .cancelPluginInitialization(str, enableGeneration, true);
            if (cancellationGeneration < 0) {
                getPluginsController().cleanupPlugin(str, runtimeToken);
            }
            finishLegacyOverlayProbe(runtimeToken);
            rollbackPluginImport(
                    str, createdInstance, importedModule, runtimeToken);
            if (dependencySnapshot != null) {
                PipController.getInstance()
                        .restoreState(str, dependencySnapshot);
            }
            throw cancelled;
        } catch (Exception failure) {
            getPluginsController().cleanupPlugin(str, runtimeToken);
            finishLegacyOverlayProbe(runtimeToken);
            rollbackPluginImport(
                    str, createdInstance, importedModule, runtimeToken);
            if (dependencySnapshot != null) {
                PipController.getInstance()
                        .restoreState(str, dependencySnapshot);
            }
            throw failure;
        } catch (Error failure) {
            getPluginsController().cleanupPlugin(str, runtimeToken);
            finishLegacyOverlayProbe(runtimeToken);
            rollbackPluginImport(
                    str, createdInstance, importedModule, runtimeToken);
            if (dependencySnapshot != null) {
                PipController.getInstance()
                        .restoreState(str, dependencySnapshot);
            }
            throw failure;
        } finally {
            if (runtimeScopeEntered) {
                getPluginsController().exitPluginRuntime(runtimeToken);
            }
            getPluginsController().endPluginInitialization(str, enableGeneration);
            getPluginsController().preferences.edit()
                    .remove("plugin_crashed_" + str)
                    .remove("plugin_enabled_before_quarantine_" + str)
                    .remove("crashed_plugin_id")
                    .remove("crashed_plugin_started_at")
                    .commit();
        }
    }

    private PyObject findPluginClass(PyObject pyObject) {
        if (this.basePluginClass == null) {
            FileLog.e("BasePlugin class is not loaded, cannot find plugin class in " + pyObject.get("__name__"));
            return null;
        }
        try {
            PyObject finder = this.basePluginClass.get("_findPluginClass");
            if (finder != null) {
                PyObject result = finder.call(pyObject);
                if (result != null) {
                    return result;
                }
            }
        } catch (Throwable helperFailure) {
            FileLog.w("NimarkoGram: fast plugin class scan failed, using compatibility fallback: "
                    + helperFailure);
        }
        try {
            PyObject builtins = getPython().getBuiltins();
            PyObject pyObject2 = pyObject.get("__dict__");
            if (pyObject2 == null) {
                return null;
            }
            for (PyObject pyObject3 : pyObject2.asMap().values()) {
                if (builtins.callAttr("isinstance", pyObject3, builtins.get(PluginsConstants.Settings.TYPE)).toBoolean() && !pyObject3.equals(this.basePluginClass) && builtins.callAttr("issubclass", pyObject3, this.basePluginClass).toBoolean()) {
                    return pyObject3;
                }
            }
        } catch (PyException e) {
            FileLog.e("Error while searching for a BasePlugin subclass in module " + pyObject.get("__name__"), e);
        }
        return null;
    }

    public void unloadPlugin(String str) {
        if (getTimedOutLifecycle(str) != null) {
            FileLog.w("nimarko: unload already timed out for " + str);
            return;
        }
        if (deferUntilLifecycleSettled(str, () -> unloadPlugin(str))) {
            return;
        }
        unloadPluginNow(str);
    }

    private boolean unloadPluginNow(String str) {
        invalidatePluginSettingCache(str, null);
        Plugin metadata = getPluginsController().plugins.get(str);
        if (metadata != null) metadata.setEnabled(false);
        getPluginsController().endPluginInitialization(str);
        PyObject instance = this.pluginInstances.get(str);
        PluginsController.PluginRuntimeToken runtimeToken =
                this.pluginRuntimeTokens.get(str);
        finishLegacyOverlayProbe(runtimeToken);
        if (instance == null) {
            if (runtimeToken != null) {
                this.pluginRuntimeTokens.remove(str, runtimeToken);
                getPluginsController().revokePluginRuntime(runtimeToken);
                getPluginsController().runWhenPluginRuntimeQuiescent(
                        runtimeToken,
                        () -> getPluginsController().releasePluginRuntime(runtimeToken));
            }
            return true;
        }
        final boolean initialized =
                PyObjectUtils.getBoolean(instance, "initialized", false);
        final LifecycleOperation operation;
        try {
            operation = beginLifecycleOperation(
                    str, instance, runtimeToken,
                    runtimeToken != null ? runtimeToken.getGeneration() : 0,
                    LifecyclePhase.UNLOAD);
        } catch (LifecyclePendingException e) {
            FileLog.w("nimarko: unload deferred for " + str + ": " + e.getMessage());
            return false;
        }
        scheduleRuntimeRetirementDeadline(operation);

        getPluginsController().cleanupPlugin(str, runtimeToken);
        try {
            if (initialized) {
                schedulePluginUnloadAfterQuiescence(operation);
            } else {
                operation.actuallyReturned = true;
                scheduleRuntimeRetirement(operation);
            }
        } catch (Throwable e) {
            FileLog.e("Failed to retire plugin " + str, e);
            scheduleRuntimeRetirement(operation);
            return false;
        }
        return lifecycleOperations.get(str) == null;
    }

    @Override
    public void setPluginEnabled(String str, boolean z, final Utilities.Callback<String> callback) {
        setPluginEnabled(
                str, z,
                getPluginsController().getPluginToggleGeneration(str),
                callback, false);
    }

    @Override
    public void setPluginEnabled(String str, boolean z, int enableGeneration,
                                 final Utilities.Callback<String> callback) {
        setPluginEnabled(
                str, z, enableGeneration, callback, false);
    }

    private void setPluginEnabled(
            String str, boolean z, int enableGeneration,
            final Utilities.Callback<String> callback,
            boolean deferDependencyCleanup) {
        PluginDebugLog.log("PY setEnabled enter plugin=" + str
                + " target=" + z
                + " generation=" + enableGeneration
                + " deferDependencyCleanup="
                + deferDependencyCleanup
                + " instancePresent="
                + this.pluginInstances.containsKey(str)
                + " lifecyclePresent="
                + this.lifecycleOperations.containsKey(str));
        if (z && PYTHON_RUNTIME_ABANDONED.get()) {
            PluginDebugLog.log("PY setEnabled rejected abandoned runtime plugin="
                    + str + " generation=" + enableGeneration);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(
                        "Python runtime needs an app restart"));
            }
            return;
        }
        if (z && !deferDependencyCleanup
                && RECOVERY_BLOCKED_PLUGIN_IDS.contains(str)) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.run("Plugin recovery is incomplete"));
            }
            return;
        }
        if (z && NimarkoConfig.pluginsSafeMode) {
            FileLog.w("nimarko: setPluginEnabled(true) blocked by safe mode for " + str);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(LocaleController.getString(R.string.NM_SafeModeBlocked)));
            }
            return;
        }
        LifecycleOperation timedOut = getTimedOutLifecycle(str);
        if (timedOut != null) {
            PluginDebugLog.log("PY setEnabled sees timed-out lifecycle plugin="
                    + str + " target=" + z
                    + " phase=" + timedOut.phase
                    + " runtime=" + timedOut.runtimeToken);
            if (!z) {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(
                            () -> callback.run(null));
                }
            } else {
                rejectTimedOutLifecycle(str, callback);
            }
            return;
        }
        LifecycleOperation retiring = lifecycleOperations.get(str);
        if (!z && retiring != null && !retiring.settled.get()) {
            PluginDebugLog.log("PY duplicate OFF during retirement plugin="
                    + str + " runtime=" + retiring.runtimeToken);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(
                        () -> callback.run(null));
            }
            return;
        }
        if (deferUntilLifecycleSettled(str, () ->
                setPluginEnabled(
                        str, z, enableGeneration, callback,
                        deferDependencyCleanup))) {
            PluginDebugLog.log("PY setEnabled deferred plugin=" + str
                    + " target=" + z
                    + " generation=" + enableGeneration);
            FileLog.d("nimarko: deferred toggle behind physical retirement for " + str);
            return;
        }
        PyObject operationObject = null;
        PluginsController.PluginRuntimeToken operationToken = null;
        try {
            if (z) ensureEnableStillRequested(str, enableGeneration);
            Plugin plugin = getPluginsController().plugins.get(str);
            PyObject pyObject = this.pluginInstances.get(str);
            PluginsController.PluginRuntimeToken runtimeToken =
                    this.pluginRuntimeTokens.get(str);
            operationObject = pyObject;
            operationToken = runtimeToken;
            if (z && plugin != null && pyObject != null && !plugin.isEnabled()
                    && PyObjectUtils.getBoolean(pyObject, "initialized", false)) {
                PluginDebugLog.log("PY enable found half-disabled instance plugin="
                        + str + " generation=" + enableGeneration
                        + " runtime=" + runtimeToken);
                unloadPluginNow(str);
                if (deferUntilLifecycleSettled(str, () ->
                        setPluginEnabled(
                                str, z, enableGeneration, callback,
                                deferDependencyCleanup))) {
                    return;
                }
                pyObject = null;
                runtimeToken = null;
                ensureEnableStillRequested(str, enableGeneration);
            }
            if (z && pyObject == null && plugin != null) {
                PluginDebugLog.log("PY enable re-import plugin=" + str
                        + " generation=" + enableGeneration);
                try {
                    String path = getPluginPath(str);
                    loadPlugin(
                            str, path, plugin, true, enableGeneration,
                            false, deferDependencyCleanup);
                    pyObject = this.pluginInstances.get(str);
                    runtimeToken = this.pluginRuntimeTokens.get(str);
                    operationObject = pyObject;
                    operationToken = runtimeToken;
                } catch (Throwable th) {
					if (th instanceof EnableCancelledException) {
						throw (EnableCancelledException) th;
					}
                    throw new Exception("Failed to re-import plugin " + str + ": " + th.getMessage(), th);
                }
            }
            if (plugin == null) {
                throw new Exception("Plugin not found: " + str);
            }
            if (pyObject == null) {
                if (!z) {
                    plugin.setEnabled(false);
                    getPluginsController().preferences.edit().putBoolean("plugin_enabled_" + str, false).apply();
                    getPluginsController().cleanupPlugin(str);
                    getPluginsController().invalidatePluginSettings(str);
                    getPluginsController().notifyPluginsChanged();
                    if (callback != null) {
                        AndroidUtilities.runOnUIThread(() -> callback.run(null));
                    }
                    return;
                }
                throw new Exception("Plugin not found: " + str);
            }
            if (z && plugin.isEnabled()
                    && PyObjectUtils.getBoolean(pyObject, "initialized", false)
                    && !plugin.hasError()) {
                if (callback != null) {
                    callback.run(null);
                    return;
                }
                return;
            }
            if (z) {
                ensureEnableStillRequested(str, enableGeneration);
                if (runtimeToken == null) {
                    throw new EnableCancelledException(str);
                }
                PluginDebugLog.log("PY on_plugin_load call plugin=" + str
                        + " generation=" + enableGeneration
                        + " runtime=" + runtimeToken);
                callOnPluginLoadWithTimeout(str, pyObject, enableGeneration, runtimeToken);
                pyObject.put("initialized", true);
                PluginDebugLog.log("PY on_plugin_load returned plugin=" + str
                        + " generation=" + enableGeneration
                        + " runtime=" + runtimeToken);
                ensureEnableStillRequested(str, enableGeneration);
            } else {
                PluginDebugLog.log("PY disable unload-now plugin=" + str
                        + " generation=" + enableGeneration
                        + " runtime=" + runtimeToken);
                unloadPluginNow(str);
                pyObject = null;
                runtimeToken = null;
            }
            if (z) {
                final PyObject committedObject = pyObject;
                final Plugin committedPlugin = plugin;
                if (this.pluginInstances.get(str) != committedObject) {
                    throw new EnableCancelledException(str);
                }
                committedObject.put("enabled", true);
                committedObject.put("error_message", null);
                ensureEnableStillRequested(str, enableGeneration);
                if (this.pluginInstances.get(str) != committedObject) {
                    throw new EnableCancelledException(str);
                }
                boolean committed = getPluginsController().commitPluginRuntime(
                        runtimeToken, committedPlugin);
                if (!committed) throw new EnableCancelledException(str);
                finishLegacyOverlayProbe(runtimeToken);
                PluginDebugLog.log("PY enable committed plugin=" + str
                        + " generation=" + enableGeneration
                        + " runtime=" + runtimeToken);
                getPluginsController().loadPluginSettings(str, enableGeneration);
                getPluginsController().endPluginInitialization(str, enableGeneration);
                if (!deferDependencyCleanup) {
                    PipController.getInstance().cleanup();
                }
            } else {
                plugin.setEnabled(false);
                getPluginsController().preferences.edit()
                        .putBoolean("plugin_enabled_" + str, false).apply();
                getPluginsController().invalidatePluginSettings(str);
            }
            getPluginsController().notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
            }
            PluginDebugLog.log("PY setEnabled success plugin=" + str
                    + " target=" + z
                    + " generation=" + enableGeneration);
        } catch (Throwable th2) {
            PluginDebugLog.log("PY setEnabled failure plugin=" + str
                    + " target=" + z
                    + " generation=" + enableGeneration, th2);
            getPluginsController().endPluginInitialization(str);
            if (th2 instanceof EnableCancelledException) {
                finishLegacyOverlayProbe(operationToken);
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.run(null));
                }
                PluginDebugLog.log("PY setEnabled cancelled as stale plugin="
                        + str + " target=" + z
                        + " generation=" + enableGeneration);
                return;
            }
            FileLog.e("Unexpected error setting enabled state for " + str, th2);
            if (z) {
                PyObject failedObject = operationObject;
                PluginsController.PluginRuntimeToken failedToken = operationToken;
                boolean ownsFailedObject = failedObject != null
                        && this.pluginInstances.get(str) == failedObject
                        && this.pluginRuntimeTokens.get(str) == failedToken
                        && getPluginsController().isPluginRuntimeCurrent(failedToken);
                if (ownsFailedObject) {
                    if (PyObjectUtils.getBoolean(failedObject, "initialized", false)) {
                        unloadPluginNow(str);
                    } else if (lifecycleOperations.get(str) == null) {
                        try {
                            LifecycleOperation retirement = beginLifecycleOperation(
                                    str, failedObject, failedToken, enableGeneration,
                                    LifecyclePhase.UNLOAD);
                            retirement.actuallyReturned = true;
                            scheduleRuntimeRetirement(retirement);
                        } catch (LifecyclePendingException ignored) {}
                    }
                }
                getPluginsController().failPluginEnable(
                        str, enableGeneration, th2, failedToken);
                finishLegacyOverlayProbe(failedToken);
            }
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(PluginCell.stackTraceToString(th2)));
            }
        }
    }

    private void finishLegacyOverlayProbe(
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (runtimeToken == null) {
            return;
        }
        PluginUiRegistry.DecorChildrenSnapshot snapshot =
                legacyOverlayProbes.remove(runtimeToken);
        if (snapshot != null) {
            PluginUiRegistry.adoptNewDecorChildrenDeferred(
                    runtimeToken, snapshot);
        }
    }

    /**
     * Mirror of {@link #callOnPluginLoadWithTimeout} for on_plugin_unload.
     * The plugins queue is single-threaded; if a plugin's unload wedges
     * (deadlock on its own ThreadPoolExecutor, blocking I/O while holding
     * the GIL, etc.) every subsequent toggle queues behind it and the
     * engine appears dead. Cap unload at {@link #PLUGIN_UNLOAD_TIMEOUT_MS}
     * so the toggle always returns and the user can keep using the app.
     * On timeout we log + fall through; cleanupPlugin() still rips out
     * the native hooks so even a half-unloaded plugin stops firing.
     */
    private void callOnPluginUnloadWithTimeout(
            String pluginId, PyObject pyObject,
            PluginsController.PluginRuntimeToken runtimeToken,
            LifecycleOperation operation) {
        if (operation.retirementExpired.get()) {
            scheduleRuntimeRetirement(operation);
            return;
        }
        operation.phase = LifecyclePhase.UNLOAD;
        operation.actuallyReturned = false;
        operation.outcome = null;
        operation.callState.set(0);
        operation.timedOut.set(false);
        operation.completionScheduled.set(false);
        final ExecutorService exec = pluginInitExecutor;
        Future<?> future;
        try {
            PluginDebugLog.log("PY unload submit plugin=" + pluginId
                    + " runtime=" + runtimeToken);
            future = exec.submit(() -> {
                if (!operation.callState.compareAndSet(0, 1)) {
                    operation.actuallyReturned = true;
                    if (operation.timedOut.get()) {
                        scheduleActualReturn(operation);
                    }
                    return null;
                }
                try {
                    PluginDebugLog.log("PY unload worker start plugin="
                            + pluginId + " runtime=" + runtimeToken);
                    boolean entered = false;
                    getPluginsController().beginPluginUnload(runtimeToken);
                    try {
                        entered = getPluginsController()
                                .enterPluginRuntime(runtimeToken);
                        if (!entered) {
                            throw new IllegalStateException(
                                    "Unable to enter revoked runtime for unload: "
                                            + pluginId);
                        }
                        pyObject.put("enabled", false);
                        pyObject.callAttr(PluginsConstants.ON_PLUGIN_UNLOAD);
                    } finally {
                        if (entered) {
                            getPluginsController()
                                    .exitPluginRuntime(runtimeToken);
                        }
                        getPluginsController().endPluginUnload(runtimeToken);
                    }
                    return null;
                } catch (Exception | Error t) {
                    operation.outcome = t;
                    throw t;
                } finally {
                    operation.callState.set(2);
                    operation.actuallyReturned = true;
                    PluginDebugLog.log("PY unload worker returned plugin="
                            + pluginId + " runtime=" + runtimeToken
                            + " outcome=" + operation.outcome);
                    if (operation.timedOut.get()) {
                        scheduleActualReturn(operation);
                    }
                }
            });
        } catch (Throwable t) {
            FileLog.e("nimarko: failed to submit on_plugin_unload for " + pluginId, t);
            operation.outcome = t;
            operation.actuallyReturned = true;
            scheduleRuntimeRetirement(operation);
            return;
        }
        try {
            future.get(PLUGIN_UNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            PluginDebugLog.log("PY unload wait complete plugin="
                    + pluginId + " runtime=" + runtimeToken);
            scheduleRuntimeRetirement(operation);
        } catch (TimeoutException te) {
            markLifecycleTimedOut(operation, future, exec);
            FileLog.w("nimarko: on_plugin_unload timeout (>" + PLUGIN_UNLOAD_TIMEOUT_MS
                    + "ms) id=" + pluginId
                    + " — runtime revoked; physical retirement waits for Python");
        } catch (java.util.concurrent.ExecutionException ee) {
            FileLog.e("Error during on_plugin_unload for " + pluginId, ee.getCause() != null ? ee.getCause() : ee);
            scheduleRuntimeRetirement(operation);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            markLifecycleTimedOut(operation, future, exec);
            FileLog.w("nimarko: on_plugin_unload interrupted for " + pluginId);
        }
    }

    private void evictPluginInstance(String pluginId, PyObject pyObject) {
        evictPluginInstance(pluginId, pyObject, pluginRuntimeTokens.get(pluginId));
    }

    private void rollbackPluginImport(
            String pluginId, PyObject instance, PyObject importedModule,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (instance != null) {
            evictPluginInstance(pluginId, instance, runtimeToken);
            return;
        }
        if (runtimeToken != null) {
            pluginRuntimeTokens.remove(pluginId, runtimeToken);
            getPluginsController().revokePluginRuntime(runtimeToken);
        }
        removePluginModuleIfOwned(
                pluginId, importedModule, runtimeToken, true);
        getPluginsController().runWhenPluginRuntimeQuiescent(
                runtimeToken,
                () -> getPluginsController().releasePluginRuntime(runtimeToken));
    }

    private void evictPluginInstance(String pluginId, PyObject pyObject,
                                     PluginsController.PluginRuntimeToken runtimeToken) {
        if (pyObject == null) return;
        final boolean owned;
        try {
            owned = this.pluginInstances.remove(pluginId, pyObject);
        } catch (Throwable ignored) {
            return;
        }
        if (!owned) {
            getPluginsController().runWhenPluginRuntimeQuiescent(
                    runtimeToken,
                    () -> getPluginsController().releasePluginRuntime(runtimeToken));
            return;
        }
        if (runtimeToken != null) {
            pluginRuntimeTokens.remove(pluginId, runtimeToken);
            getPluginsController().revokePluginRuntime(runtimeToken);
        } else {
            pluginRuntimeTokens.remove(pluginId);
        }
        try {
            invalidatePluginSettingCache(pluginId, null);
        } catch (Throwable ignored) {}
        try {
            Plugin p = getPluginsController().plugins.get(pluginId);
            if (p != null) {
                p.boundHooks = null;
                p.implementedHooks = null;
            }
        } catch (Throwable ignored) {}
        removePluginModuleIfOwned(pluginId, null, runtimeToken, false);
        getPluginsController().runWhenPluginRuntimeQuiescent(
                runtimeToken,
                () -> getPluginsController().releasePluginRuntime(runtimeToken));
    }

    private void removePluginModuleIfOwned(
            String pluginId, PyObject expectedModule,
            PluginsController.PluginRuntimeToken runtimeToken,
            boolean allowUnpublishedFallback) {
        try {
            Python py = getPython();
            if (py != null) {
                PyObject modules = py.getModule("sys").get("modules");
                PyObject module = modules != null
                        ? modules.callAttr("get", pluginId) : null;
                boolean ownsModule = runtimeToken == null;
                if (module != null && runtimeToken != null) {
                    try {
                        PyObject owner = module.get("__nimarko_runtime_token__");
                        PluginsController.PluginRuntimeToken moduleToken = owner != null
                                ? owner.toJava(PluginsController.PluginRuntimeToken.class)
                                : null;
                        ownsModule = runtimeToken.equals(moduleToken);
                    } catch (Throwable ignored) {
                        ownsModule = false;
                    }
                    if (!ownsModule && expectedModule != null) {
                        try {
                            if (module == expectedModule) {
                                ownsModule = true;
                            } else {
                                PyObject builtins = py.getModule("builtins");
                                long moduleId = builtins.callAttr("id", module).toLong();
                                long expectedId =
                                        builtins.callAttr("id", expectedModule).toLong();
                                ownsModule = moduleId == expectedId;
                            }
                        } catch (Throwable ignored) {
                            ownsModule = false;
                        }
                    }
                    if (!ownsModule && allowUnpublishedFallback) {
                        PluginsController.PluginRuntimeToken mapped =
                                pluginRuntimeTokens.get(pluginId);
                        PluginsController.PluginRuntimeToken current =
                                getPluginsController()
                                        .getCurrentPluginRuntime(pluginId);
                        ownsModule = pluginInstances.get(pluginId) == null
                                && (mapped == null || mapped.equals(runtimeToken))
                                && (current == null || current.equals(runtimeToken));
                    }
                }
                if (module != null && ownsModule) {
                    modules.callAttr("pop", pluginId, null);
                }
            }
        } catch (Throwable t) {
            FileLog.e("nimarko: failed to pop sys.modules[" + pluginId + "]", t);
        }
    }

    private void callOnPluginLoadWithTimeout(
            String pluginId, PyObject pyObject, int enableGeneration,
            PluginsController.PluginRuntimeToken runtimeToken) throws Exception {
        ensureEnableStillRequested(pluginId, enableGeneration);
        LifecycleOperation operation = beginLifecycleOperation(
                pluginId, pyObject, runtimeToken, enableGeneration,
                LifecyclePhase.LOAD);
        final ExecutorService exec = pluginInitExecutor;
        Future<?> future;
        try {
            PluginDebugLog.log("PY load submit plugin=" + pluginId
                    + " generation=" + enableGeneration
                    + " runtime=" + runtimeToken);
            future = exec.submit(() -> {
                if (!operation.callState.compareAndSet(0, 1)) {
                    operation.actuallyReturned = true;
                    if (operation.timedOut.get()) {
                        scheduleActualReturn(operation);
                    }
                    return null;
                }
                getPluginsController().beginPluginInitialization(pluginId, enableGeneration);
                boolean entered = runtimeToken != null
                        && getPluginsController().enterPluginRuntime(runtimeToken);
                try {
                    PluginDebugLog.log("PY load worker start plugin="
                            + pluginId + " generation="
                            + enableGeneration + " runtime="
                            + runtimeToken + " entered=" + entered);
                    if (!entered) {
                        throw new EnableCancelledException(pluginId);
                    }
                    claimEnableCode(pluginId, enableGeneration);
                    pyObject.callAttr(PluginsConstants.ON_PLUGIN_LOAD);
                    return null;
                } catch (Exception | Error t) {
                    operation.outcome = t;
                    throw t;
                } finally {
                    operation.callState.set(2);
                    if (entered) {
                        getPluginsController().exitPluginRuntime(runtimeToken);
                    }
                    getPluginsController().endPluginInitialization(pluginId, enableGeneration);
                    operation.actuallyReturned = true;
                    PluginDebugLog.log("PY load worker returned plugin="
                            + pluginId + " generation="
                            + enableGeneration + " runtime="
                            + runtimeToken + " outcome="
                            + operation.outcome);
                    if (operation.timedOut.get()) {
                        scheduleActualReturn(operation);
                    }
                }
            });
        } catch (Throwable t) {
            operation.outcome = t;
            operation.actuallyReturned = true;
            settleLifecycleOperation(operation);
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception(t);
        }
        try {
            future.get(PLUGIN_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            PluginDebugLog.log("PY load wait complete plugin="
                    + pluginId + " generation=" + enableGeneration
                    + " runtime=" + runtimeToken);
            settleLifecycleOperation(operation);
        } catch (TimeoutException te) {
            markLifecycleTimedOut(operation, future, exec);
            getPluginsController().cancelPluginInitialization(pluginId, enableGeneration, false);
            FileLog.w("nimarko: on_plugin_load timeout (>" + PLUGIN_LOAD_TIMEOUT_MS + "ms) id=" + pluginId);
            throw new TimeoutException("on_plugin_load for " + pluginId
                    + " did not return within " + (PLUGIN_LOAD_TIMEOUT_MS / 1000) + "s");
        } catch (java.util.concurrent.ExecutionException ee) {
            settleLifecycleOperation(operation);
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new Exception(cause != null ? cause : ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            markLifecycleTimedOut(operation, future, exec);
            getPluginsController().cancelPluginInitialization(
                    pluginId, enableGeneration, false);
            throw ie;
        }
    }

    @Override
    public void deletePlugin(String str, final Utilities.Callback<String> callback) {
        if (Thread.currentThread() != Utilities.pluginsQueue) {
            Utilities.pluginsQueue.postRunnable(() -> deletePlugin(str, callback));
            return;
        }
        if (str == null
                || !str.matches(
                        "^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.run("Invalid plugin id"));
            }
            return;
        }
        File directory = getPluginsController().getPluginsDir();
        File marker = pluginDeleteMarker(directory, str);
        PluginDeleteMarkerData markerData;
        try {
            if (marker.exists()) {
                markerData = readPluginDeleteMarker(marker);
            } else {
                markerData = new PluginDeleteMarkerData(
                        DELETE_STATE_PREPARED,
                        newPluginUpdateTransactionId());
                writePluginDeleteMarker(
                        directory, str, markerData.state,
                        markerData.transactionId, false);
            }
        } catch (Throwable preparationFailure) {
            String error = "Could not prepare plugin deletion: " + str;
            FileLog.e(error, preparationFailure);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(
                        () -> callback.run(error));
            }
            return;
        }
        RECOVERY_BLOCKED_PLUGIN_IDS.add(str);
        if (rejectTimedOutLifecycle(str, callback)) {
            return;
        }
        if (deferUntilLifecycleSettled(str, () -> deletePlugin(str, callback))) {
            return;
        }
        if (this.pluginInstances.containsKey(str)) {
            if (!unloadPluginNow(str)) {
                deferUntilLifecycleSettled(str, () -> deletePlugin(str, callback));
                return;
            }
        }
        getPluginsController().cleanupPlugin(str);
        getPluginsController().plugins.remove(str);
        invalidatePluginMetadata(
                new File(directory, str + ".py"));
        boolean completed = completePluginDeletion(
                getPluginsController(), str, marker, markerData);
        getPluginsController().notifyPluginsChanged();
        if (callback != null) {
            String result = completed
                    ? null
                    : "Plugin deletion is pending recovery: " + str;
            AndroidUtilities.runOnUIThread(
                    () -> callback.run(result));
        }
    }

    @Override
    public String getPluginPath(String str) {
        return getPluginsController().pluginsDir.getAbsolutePath() + File.separator + str + ".py";
    }

    @Override
    public void openInExternalApp(String str) {
        BaseFragment safeLastFragment = LaunchActivity.getSafeLastFragment();
        if (safeLastFragment == null) {
            return;
        }
        File src = new File(getPluginPath(str));
        if (!src.exists()) {
            return;
        }
        try {
            File tempDir = new File(ApplicationLoader.getFilesDirFixed(), "temp");
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = new File(tempDir, str + ".py");
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                out.getChannel().transferFrom(in.getChannel(), 0L, in.getChannel().size());
            }
            tempFile.deleteOnExit();
            AndroidUtilities.openForView(tempFile, tempFile.getName(), "text/plain",
                    safeLastFragment.getParentActivity(), safeLastFragment.getResourceProvider(), false);
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e("nimarko: openInExternalApp failed", t);
            try {
                BulletinFactory.of(safeLastFragment).createErrorBulletin(t.getMessage() != null ? t.getMessage() : "Failed to open plugin").show();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void sharePlugin(String str) {
        BaseFragment safeLastFragment = LaunchActivity.getSafeLastFragment();
        if (safeLastFragment == null) {
            return;
        }
        String pluginPath = getPluginPath(str);
        File file = new File(ApplicationLoader.getFilesDirFixed(), "temp");
        if (!file.exists()) {
            file.mkdirs();
        }
        File file2 = new File(file, str + PluginsConstants.PLUGINS_EXT);
        try {
            FileInputStream fileInputStream = new FileInputStream(pluginPath);
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(file2);
                try {
                    fileOutputStream.getChannel().transferFrom(fileInputStream.getChannel(), 0L, fileInputStream.getChannel().size());
                    fileOutputStream.close();
                    fileInputStream.close();
                    Uri uriForFile = FileProvider.getUriForFile(safeLastFragment.getContext(), ApplicationLoader.getApplicationId() + ".provider", file2);
                    Intent intent = new Intent("android.intent.action.SEND");
                    intent.setFlags(1);
                    intent.putExtra("android.intent.extra.STREAM", uriForFile);
                    intent.setType("application/x-plugin");
                    safeLastFragment.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
                    file2.deleteOnExit();
                } finally {
                }
            } finally {
            }
        } catch (IOException | IllegalArgumentException e) {
            FileLog.e(e);
        }
    }

    private AuthorizedCandidate stageAuthorizedCandidate(
            String path, String expectedPluginId) throws IOException {
        StagedCandidatePayload payload =
                copyAuthorizedCandidateToHostStage(path);
        return validateAuthorizedCandidatePayload(
                payload, expectedPluginId, path);
    }

    private StagedCandidatePayload copyAuthorizedCandidateToHostStage(
            String path) throws IOException {
        if (TextUtils.isEmpty(path)) {
            throw new IOException("Plugin candidate path is empty");
        }
        File requested = new File(path).getAbsoluteFile();
        File canonical = requested.getCanonicalFile();
        if (!requested.getAbsolutePath().equals(
                canonical.getAbsolutePath())) {
            throw new IOException(
                    "Plugin candidate must not be a symbolic link");
        }
        return copyToExclusiveHostStage(canonical);
    }

    private AuthorizedCandidate validateAuthorizedCandidatePayload(
            StagedCandidatePayload payload, String expectedPluginId,
            String displayPath) throws IOException {
        if (payload == null || payload.file == null) {
            throw new IOException("Plugin candidate staging failed");
        }
        try {
            Map<String, String> metadata =
                    parsePluginMetadataBytes(
                            payload.bytes, displayPath);
            PluginsController.PluginValidationResult validation =
                    validatePluginMetadata(
                            metadata, displayPath);
            if (validation == null || validation.plugin == null) {
                throw new IOException(
                        validation != null
                                && !TextUtils.isEmpty(validation.error)
                                ? validation.error
                                : "Plugin metadata validation failed");
            }
            String pluginId = validation.plugin.getId();
            if (!TextUtils.isEmpty(expectedPluginId)
                    && !expectedPluginId.equals(pluginId)) {
                throw new IOException(
                        "Plugin id mismatch: expected "
                                + expectedPluginId + ", got "
                                + pluginId);
            }
            AuthorizedCandidate candidate = new AuthorizedCandidate(
                    payload.file, payload.sha256,
                    validation.plugin);
            activeAuthorizedCandidates.add(candidate);
            return candidate;
        } catch (Throwable failure) {
            if (payload.file.exists()
                    && !payload.file.delete()) {
                FileLog.w("Could not remove rejected host candidate "
                        + payload.file.getAbsolutePath());
            }
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            throw new IOException(
                    installFailureMessage(failure), failure);
        }
    }

    private Map<String, String> parsePluginMetadataBytes(
            byte[] payload, String displayPath) throws IOException {
        if (payload == null
                || payload.length == 0
                || payload.length > MAX_PLUGIN_CANDIDATE_BYTES) {
            throw new IOException(
                    "Plugin candidate has an invalid size");
        }
        if (getPython() == null) {
            throw new IOException(
                    "Python engine is not initialized");
        }
        final String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException(
                    "Plugin candidate is not valid UTF-8",
                    invalidUtf8);
        }

        HashMap<String, String> metadata = new HashMap<>();
        try {
            PyObject ast = getPython().getModule("ast");
            PyObject builtins = getPython().getBuiltins();
            PyObject tree =
                    ast.callAttr("parse", source, displayPath, "exec");
            PyObject assignType = ast.get("Assign");
            PyObject nameType = ast.get("Name");
            PyObject body = tree != null ? tree.get("body") : null;
            if (assignType == null || nameType == null || body == null) {
                throw new IOException(
                        "Python AST metadata parser is unavailable");
            }
            for (PyObject node : body.asList()) {
                if (!builtins.callAttr(
                        "isinstance", node, assignType).toBoolean()) {
                    continue;
                }
                PyObject targets = node.get("targets");
                PyObject valueNode = node.get("value");
                if (targets == null || valueNode == null) {
                    continue;
                }
                PyObject literal;
                try {
                    literal = ast.callAttr(
                            "literal_eval", valueNode);
                } catch (PyException unsupportedLiteral) {
                    continue;
                }
                for (PyObject target : targets.asList()) {
                    if (!builtins.callAttr(
                            "isinstance", target, nameType)
                            .toBoolean()) {
                        continue;
                    }
                    PyObject idObject = target.get("id");
                    String id = idObject != null
                            ? idObject.toString() : null;
                    if (!AUTHORIZED_METADATA_KEYS.contains(id)) {
                        continue;
                    }
                    String key = id.substring(2, id.length() - 2);
                    if ("requirements".equals(key)) {
                        try {
                            metadata.put(
                                    key,
                                    literal.asList().stream()
                                            .map(Object::toString)
                                            .map(String::trim)
                                            .filter(value ->
                                                    !value.isEmpty())
                                            .collect(Collectors.joining(
                                                    "\n")));
                            continue;
                        } catch (Throwable notASequence) {
                        }
                    }
                    metadata.put(key, literal.toString());
                }
            }
            return metadata;
        } catch (PyException parseFailure) {
            throw new IOException(
                    "Failed to parse plugin metadata: "
                            + parseFailure.getMessage(),
                    parseFailure);
        }
    }

    private HostInstallTicket issueHostInstallTicket(
            AuthorizedCandidate candidate) {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, HostInstallTicket> entry
                : hostInstallTickets.entrySet()) {
            HostInstallTicket stale = entry.getValue();
            if (stale == null
                    || stale.state.get()
                            >= HOST_TICKET_CONSUMED
                    || stale.expiresAtElapsedRealtime <= now) {
                if (stale != null) {
                    forceRevokeHostInstallTicket(stale);
                } else {
                    hostInstallTickets.remove(entry.getKey());
                }
            }
        }
        HostInstallTicket ticket = new HostInstallTicket(
                candidate,
                java.util.UUID.randomUUID().toString()
                        .replace("-", ""),
                now + HOST_INSTALL_TICKET_TTL_MS);
        hostInstallTickets.put(ticket.nonce, ticket);
        return ticket;
    }

    private void revokeHostInstallTicket(
            HostInstallTicket ticket, boolean includeQueued) {
        if (ticket == null) return;
        while (true) {
            int state = ticket.state.get();
            if (state == HOST_TICKET_REVOKED
                    || state == HOST_TICKET_CONSUMED
                    || (!includeQueued
                            && state == HOST_TICKET_QUEUED)) {
                return;
            }
            if (ticket.state.compareAndSet(
                    state, HOST_TICKET_REVOKED)) {
                hostInstallTickets.remove(ticket.nonce, ticket);
                ticket.candidate.cleanup();
                return;
            }
        }
    }

    private void forceRevokeHostInstallTicket(
            HostInstallTicket ticket) {
        if (ticket == null) return;
        hostInstallTickets.remove(ticket.nonce, ticket);
        ticket.state.set(HOST_TICKET_REVOKED);
        ticket.candidate.cleanup();
    }

    private void revokeAllInstallCandidates() {
        for (HostInstallTicket ticket : hostInstallTickets.values()) {
            if (ticket != null) {
                forceRevokeHostInstallTicket(ticket);
            }
        }
        hostInstallTickets.clear();
        for (AuthorizedCandidate candidate
                : activeAuthorizedCandidates) {
            if (candidate != null) {
                candidate.cleanup();
            }
        }
    }

    private void cleanupOrphanedHostInstallStages() {
        File directory = getPluginsController().getPluginsDir();
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        LinkedHashSet<String> activePaths = new LinkedHashSet<>();
        for (AuthorizedCandidate candidate
                : activeAuthorizedCandidates) {
            if (candidate != null && candidate.stagedFile != null) {
                activePaths.add(
                        candidate.stagedFile.getAbsolutePath());
            }
        }
        File[] stages = directory.listFiles((dir, name) ->
                HOST_INSTALL_STAGE_PATTERN.matcher(name).matches());
        if (stages == null || stages.length == 0) {
            return;
        }
        boolean removed = false;
        for (File stage : stages) {
            if (stage == null
                    || activePaths.contains(stage.getAbsolutePath())) {
                continue;
            }
            if (stage.isFile() && stage.delete()) {
                removed = true;
            } else if (stage.exists()) {
                FileLog.w("Could not remove orphaned plugin stage "
                        + stage.getAbsolutePath());
            }
        }
        if (removed) {
            syncDirectory(directory);
        }
    }

    private void consumeHostInstallTicket(
            HostInstallTicket ticket,
            Utilities.Callback<String> callback) {
        if (Thread.currentThread() != Utilities.pluginsQueue) {
            rejectInstall(callback,
                    "Host install left pluginsQueue");
            forceRevokeHostInstallTicket(ticket);
            return;
        }
        if (ticket == null
                || !ticket.state.compareAndSet(
                        HOST_TICKET_QUEUED,
                        HOST_TICKET_CONSUMED)) {
            rejectInstall(callback,
                    "Plugin install authorization is missing "
                            + "or was already used");
            if (ticket != null
                    && ticket.state.get()
                            != HOST_TICKET_CONSUMED) {
                forceRevokeHostInstallTicket(ticket);
            }
            return;
        }
        if (ticket.expiresAtElapsedRealtime
                <= SystemClock.elapsedRealtime()) {
            ticket.candidate.cleanup();
            rejectInstall(callback,
                    "Plugin install authorization has expired");
            return;
        }
        loadAuthorizedPluginFromFile(
                ticket.candidate, callback, null);
    }

    private static String installFailureMessage(Throwable failure) {
        return failure != null
                && !TextUtils.isEmpty(failure.getMessage())
                        ? failure.getMessage()
                        : "Plugin installation was rejected";
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

    private static void rejectInstall(
            Utilities.Callback<String> callback, String error) {
        FileLog.w("nimarko: " + error);
        if (callback != null) {
            AndroidUtilities.runOnUIThread(
                    () -> callback.run(error));
        }
    }

    @SuppressWarnings("unchecked")
    public void loadPluginFromFile(String path, Plugin existing, Object a3, Object a4) {
        Utilities.Callback<String> callback =
                a3 instanceof Utilities.Callback
                        ? (Utilities.Callback<String>) a3 : null;
        rejectInstall(callback,
                "Legacy plugin installation API has no host authority");
    }

    public void loadPluginFromRuntime(
            String path, PluginsController.PluginRuntimeToken requester) {
        PluginsController.PluginRuntimeToken actual =
                getPluginsController().captureCurrentPluginRuntime();
        if (requester == null || actual == null
                || !requester.equals(actual)) {
            throw new SecurityException(
                    "Plugin self-update requires the caller's exact "
                            + "runtime scope");
        }
        final StagedCandidatePayload hostOwnedPayload;
        try {
            hostOwnedPayload =
                    copyAuthorizedCandidateToHostStage(path);
        } catch (IOException stagingFailure) {
            throw new IllegalArgumentException(
                    "Could not take ownership of plugin update",
                    stagingFailure);
        }
        boolean queued = false;
        try {
            queued = Utilities.pluginsQueue.postRunnable(() ->
                    authorizeRuntimeSelfUpdate(
                            hostOwnedPayload, requester, path));
        } finally {
            if (!queued) {
                cleanupStagedCandidatePayload(hostOwnedPayload);
            }
        }
        if (!queued) {
            throw new IllegalStateException(
                    "Plugin update queue is unavailable");
        }
    }

    public void loadPluginFromFile(String str, Plugin plugin, final Utilities.Callback<String> callback) {
        rejectInstall(callback,
                "Legacy plugin installation API has no host authority");
    }

    private void authorizeRuntimeSelfUpdate(
            StagedCandidatePayload hostOwnedPayload,
            PluginsController.PluginRuntimeToken requester,
            String displayPath) {
        if (getPluginsController().getPluginRuntimeTaskDecision(requester)
                != PluginsController.RUNTIME_TASK_RUN) {
            rejectInstall(null,
                    "Plugin self-update was cancelled because its "
                            + "runtime is no longer active");
            cleanupStagedCandidatePayload(hostOwnedPayload);
            return;
        }
        AuthorizedCandidate candidate = null;
        boolean candidateHandedOff = false;
        try {
            candidate = validateAuthorizedCandidatePayload(
                    hostOwnedPayload, requester.getPluginId(),
                    displayPath);
            InstallContinuationGuard runtimeGuard =
                    new RuntimeSelfUpdateGuard(requester);
            loadAuthorizedPluginFromFile(
                    candidate, null, runtimeGuard);
            candidateHandedOff = true;
        } catch (Throwable failure) {
            if (!candidateHandedOff && candidate != null) {
                candidate.cleanup();
            }
            rejectInstall(null,
                    installFailureMessage(failure));
        }
    }

    private static void cleanupStagedCandidatePayload(
            StagedCandidatePayload payload) {
        if (payload != null && payload.file != null
                && payload.file.exists()
                && !payload.file.delete()) {
            FileLog.w("Could not remove unclaimed plugin update stage "
                    + payload.file.getAbsolutePath());
        }
    }

    boolean isCurrentDevInstallBridge(
            PluginDevInstallBridge bridge, long generation) {
        synchronized (installPublicationLock) {
            return NimarkoConfig.pluginsDevMode
                    && bridge != null
                    && bridge == devInstallBridge
                    && bridge.isMarkedActive()
                    && bridge.belongsTo(this, generation);
        }
    }

    boolean installFromDevAuthority(
            PluginDevInstallBridge bridge,
            PluginDevInstallBridge.CommandAuthority authority,
            long generation, long commandGeneration,
            String path, String expectedPluginId,
            Utilities.Callback<String> completion) {
        if (!isCurrentDevInstallAuthority(
                bridge, authority, generation,
                commandGeneration)) {
            rejectInstall(completion,
                    "Development plugin installer was revoked");
            return false;
        }
        final StagedCandidatePayload hostOwnedPayload;
        try {
            hostOwnedPayload =
                    copyAuthorizedCandidateToHostStage(path);
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            rejectInstall(completion,
                    installFailureMessage(failure));
            return false;
        }
        if (!isCurrentDevInstallAuthority(
                bridge, authority, generation,
                commandGeneration)) {
            cleanupStagedCandidatePayload(hostOwnedPayload);
            rejectInstall(completion,
                    "Development plugin installer was revoked");
            return false;
        }
        Runnable authorize = () -> {
            if (!isCurrentDevInstallAuthority(
                    bridge, authority, generation,
                    commandGeneration)) {
                cleanupStagedCandidatePayload(hostOwnedPayload);
                rejectInstall(completion,
                        "Development plugin installer was revoked");
                return;
            }
            AuthorizedCandidate candidate = null;
            boolean candidateHandedOff = false;
            try {
                candidate = validateAuthorizedCandidatePayload(
                        hostOwnedPayload, expectedPluginId, path);
                loadAuthorizedPluginFromFile(
                        candidate, completion,
                        pluginId ->
                                java.util.Objects.equals(
                                        expectedPluginId, pluginId)
                                        && isCurrentDevInstallAuthority(
                                                bridge, authority,
                                                generation,
                                                commandGeneration));
                candidateHandedOff = true;
            } catch (Throwable failure) {
                if (!candidateHandedOff && candidate != null) {
                    candidate.cleanup();
                } else if (candidate == null) {
                    cleanupStagedCandidatePayload(
                            hostOwnedPayload);
                }
                rejectInstall(completion,
                        installFailureMessage(failure));
            }
        };
        boolean queued;
        if (Thread.currentThread() == Utilities.pluginsQueue) {
            authorize.run();
            queued = true;
        } else {
            queued = Utilities.pluginsQueue.postRunnable(authorize);
        }
        if (!queued) {
            cleanupStagedCandidatePayload(hostOwnedPayload);
            rejectInstall(completion,
                    "Development plugin queue is unavailable");
        }
        return queued;
    }

    private boolean isCurrentDevInstallAuthority(
            PluginDevInstallBridge bridge,
            PluginDevInstallBridge.CommandAuthority authority,
            long generation, long commandGeneration) {
        synchronized (installPublicationLock) {
            return isCurrentDevInstallBridge(bridge, generation)
                    && bridge.accepts(authority, generation)
                    && authority.belongsTo(
                            bridge, generation,
                            commandGeneration);
        }
    }

    private void loadAuthorizedPluginFromFile(
            AuthorizedCandidate candidate,
            final Utilities.Callback<String> callback,
            InstallContinuationGuard continuationGuard) {
        if (Thread.currentThread() != Utilities.pluginsQueue) {
            Utilities.pluginsQueue.postRunnable(() ->
                    loadAuthorizedPluginFromFile(
                            candidate, callback,
                            continuationGuard));
            return;
        }
        if (candidate == null || candidate.plugin == null
                || candidate.stagedFile == null) {
            rejectInstall(callback,
                    "Plugin installation has no staged candidate");
            if (candidate != null) candidate.cleanup();
            return;
        }
        final String str = candidate.stagedFile.getAbsolutePath();
        final Plugin plugin = candidate.plugin;
        final String expectedSha256 = candidate.sha256;
        if (continuationGuard != null
                && !continuationGuard.isAuthorized(
                        plugin.getId())) {
            rejectInstall(callback,
                    "Plugin installation authority was revoked");
            candidate.cleanup();
            return;
        }
        PluginDebugLog.log("loadPluginFromFile START file=" + str + " plugin=" + (plugin != null ? plugin.getId() : "null"));
        if (app.nimarkogram.messenger.NimarkoConfig.pluginsSafeMode) {
            PluginDebugLog.log("loadPluginFromFile ABORT: safe mode is active");
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(
                        LocaleController.getString(R.string.PluginsSafeModeOn)));
            }
            candidate.cleanup();
            return;
        }
        if (plugin == null || TextUtils.isEmpty(expectedSha256)) {
            rejectInstall(callback,
                    "Plugin installation has no validated candidate");
            candidate.cleanup();
            return;
        }

        final Plugin p = plugin;
        String id = p.getId();
        try {
            if (!expectedSha256.equals(
                    calculateFileSha256NoFollow(
                            candidate.stagedFile))) {
                rejectInstall(callback,
                        "Plugin candidate changed after authorization");
                candidate.cleanup();
                return;
            }
        } catch (IOException failure) {
            rejectInstall(callback,
                    installFailureMessage(failure));
            candidate.cleanup();
            return;
        }
        if (rejectTimedOutLifecycle(id, callback)) {
            PluginDebugLog.log("loadPluginFromFile ABORT: wedged lifecycle id="
                    + id);
            candidate.cleanup();
            return;
        }
        if (deferUntilLifecycleSettled(id, () ->
                loadAuthorizedPluginFromFile(
                        candidate, callback,
                        continuationGuard))) {
            PluginDebugLog.log("loadPluginFromFile DEFER id=" + id
                    + " waiting for old runtime retirement");
            return;
        }
        File destFile = new File(getPluginsController().getPluginsDir(), id + ".py");
        if (destFile.exists() && !destFile.isFile()) {
            String error = "Plugin destination is not a regular file: "
                    + destFile.getAbsolutePath();
            FileLog.e(error);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(error));
            }
            candidate.cleanup();
            return;
        }
        if (hasAnyPendingPluginUpdateRecovery()) {
            recoverInterruptedPluginUpdates(getPluginsController());
            if (hasAnyPendingPluginUpdateRecovery()) {
                String error = "A pending plugin update must be recovered "
                        + "before installing " + id;
                FileLog.w(error);
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(
                            () -> callback.run(error));
                }
                candidate.cleanup();
                return;
            }
        }
        final boolean hadExistingFile = destFile.isFile();
        final boolean restoreEnabledPreference =
                getPluginsController().preferences.getBoolean(
                        "plugin_enabled_" + id, false);
        final int transactionGeneration =
                getPluginsController().getPluginToggleGeneration(id);
        final Plugin previousPlugin =
                getPluginsController().plugins.get(id);
        final List<String> previousRequirements =
                hadExistingFile
                        ? snapshotPluginRequirements(
                                destFile, id, previousPlugin)
                        : Collections.emptyList();
        final PipController.DependencySnapshot previousDependencyState;
        try {
            previousDependencyState =
                    PipController.getInstance().snapshotState(id);
        } catch (Throwable snapshotFailure) {
            String error =
                    "Could not snapshot plugin dependencies for " + id;
            FileLog.e(error, snapshotFailure);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(
                        () -> callback.run(error));
            }
            candidate.cleanup();
            return;
        }
        final File dependencySnapshotFile =
                pluginUpdateDependencySnapshot(id);
        final String updateCleanupMode =
                restoreEnabledPreference
                        ? UPDATE_CLEANUP_PRUNE
                        : UPDATE_CLEANUP_UNINSTALL;
        final String updateTransactionId =
                newPluginUpdateTransactionId();
        File backupFile = null;
        boolean backupCreated = false;
        boolean candidateWriteStarted = false;
        boolean artifactTransactionPrepared = false;
        boolean dependencySnapshotPrepared = false;
        boolean updateMarkerPrepared = false;
        boolean updateCommitted = false;
        boolean retainCandidateForDeferred = false;
        String previousSourceSha256 = UPDATE_NO_BACKUP;
        PluginDebugLog.log("loadPluginFromFile id=" + id + " dest=" + destFile.getAbsolutePath() + " destExists=" + destFile.exists());

        try {
            if (hadExistingFile) {
                if (continuationGuard != null
                        && !continuationGuard
                                .canRetireExistingRuntime(id)) {
                    throw new SecurityException(
                            "Plugin installation authority was revoked "
                                    + "before runtime retirement");
                }
                boolean retiredImmediately = unloadPluginNow(id);
                if (continuationGuard != null) {
                    continuationGuard.didRetireExistingRuntime(id);
                }
                if (!retiredImmediately) {
                    retainCandidateForDeferred =
                            deferUntilLifecycleSettled(id, () ->
                            loadAuthorizedPluginFromFile(
                                    candidate, callback,
                                    continuationGuard));
                    if (retainCandidateForDeferred) {
                        return;
                    }
                    throw new LifecyclePendingException(id);
                }
                backupFile = allocatePluginBackupFile(id);
                copyFileAndSync(destFile, backupFile);
                syncDirectoryStrict(backupFile.getParentFile());
                backupCreated = true;
                previousSourceSha256 =
                        calculateFileSha256(backupFile);
            }

            PipController.getInstance().beginDeferredArtifactTransaction(
                    id, updateTransactionId);
            artifactTransactionPrepared = true;
            PipController.getInstance().writeDependencySnapshot(
                    dependencySnapshotFile, id, updateTransactionId,
                    previousDependencyState);
            dependencySnapshotPrepared = true;
            writePluginUpdateMarker(
                    getPluginsController().getPluginsDir(),
                    id, backupFile, UPDATE_STATE_PREPARED,
                    updateCleanupMode, updateTransactionId,
                    previousSourceSha256, false);
            updateMarkerPrepared = true;
            RECOVERY_BLOCKED_PLUGIN_IDS.add(id);

            publishAuthorizedCandidate(
                    candidate, destFile, continuationGuard);
            candidateWriteStarted = true;
            invalidatePluginMetadata(destFile);

            PluginDebugLog.log("loadPluginFromFile copied OK, calling loadPlugin(" + id + ")");
            loadPlugin(
                    id, destFile.getAbsolutePath(), p, false,
                    getPluginsController().getPluginToggleGeneration(id),
                    false, true);
            writePluginUpdateMarker(
                    getPluginsController().getPluginsDir(),
                    id, backupFile, UPDATE_STATE_COMMITTED,
                    updateCleanupMode, updateTransactionId,
                    previousSourceSha256, true);
            updateCommitted = true;
            final boolean artifactsCommitted =
                    PipController.getInstance()
                            .commitDeferredArtifactTransaction(
                                    id, updateTransactionId);
            final boolean dependencyCleanupComplete;
            if (!artifactsCommitted) {
                dependencyCleanupComplete = false;
            } else if (UPDATE_CLEANUP_UNINSTALL.equals(updateCleanupMode)) {
                dependencyCleanupComplete =
                        PipController.getInstance()
                                .uninstallDependencies(id);
            } else {
                dependencyCleanupComplete =
                        PipController.getInstance().cleanupAndReport();
            }
            if (dependencyCleanupComplete) {
                if (backupFile != null && backupFile.exists()) {
                    if (!backupFile.delete()) {
                        FileLog.w("Plugin update succeeded but recovery backup "
                                + "could not be removed: "
                                + backupFile.getAbsolutePath());
                    }
                }
                if (dependencySnapshotFile.exists()
                        && !dependencySnapshotFile.delete()) {
                    FileLog.w("Plugin update succeeded but dependency "
                            + "snapshot could not be removed: "
                            + dependencySnapshotFile.getAbsolutePath());
                }
                if ((backupFile == null || !backupFile.exists())
                        && !dependencySnapshotFile.exists()) {
                    if (deletePluginUpdateMarker(id)) {
                        RECOVERY_BLOCKED_PLUGIN_IDS.remove(id);
                    }
                }
            } else {
                FileLog.w("Plugin update committed; dependency cleanup "
                        + "deferred for " + id);
            }
            getPluginsController().notifyPluginsChanged();
            PluginDebugLog.log("loadPluginFromFile SUCCESS id=" + id);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
            }

        } catch (LifecyclePendingException pending) {
            final File deferredBackup = backupFile;
            final boolean deferredBackupCreated = backupCreated;
            final boolean deferredCandidateWriteStarted =
                    candidateWriteStarted;
            final boolean deferredArtifactTransactionPrepared =
                    artifactTransactionPrepared;
            final boolean deferredDependencySnapshotPrepared =
                    dependencySnapshotPrepared;
            final boolean deferredMarkerPrepared =
                    updateMarkerPrepared;
            final String deferredSourceSha256 =
                    previousSourceSha256;
            final Throwable installFailure = new Exception(
                    "Plugin lifecycle did not finish; update was rolled back",
                    pending);
            Runnable rollback = () -> rollbackPluginFileInstall(
                    str, id, destFile, deferredBackup,
                    hadExistingFile, deferredBackupCreated,
                    deferredCandidateWriteStarted,
                    updateTransactionId,
                    deferredArtifactTransactionPrepared,
                    deferredDependencySnapshotPrepared,
                    deferredMarkerPrepared, previousPlugin,
                    previousRequirements, previousDependencyState,
                    restoreEnabledPreference,
                    transactionGeneration, deferredSourceSha256,
                    callback, installFailure);
            if (!deferUntilLifecycleSettled(id, rollback)) {
                rollback.run();
            }
        } catch (Throwable e) {
            if (updateCommitted) {
                FileLog.e("Plugin update committed; post-commit work failed "
                        + "for " + id, e);
                try {
                    getPluginsController().notifyPluginsChanged();
                } catch (Throwable notifyFailure) {
                    FileLog.e("Could not publish committed plugin update "
                            + id, notifyFailure);
                }
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(
                            () -> callback.run(null));
                }
                return;
            }
            rollbackPluginFileInstall(
                    str, id, destFile, backupFile,
                    hadExistingFile, backupCreated,
                    candidateWriteStarted,
                    updateTransactionId,
                    artifactTransactionPrepared,
                    dependencySnapshotPrepared,
                    updateMarkerPrepared,
                    previousPlugin,
                    previousRequirements, previousDependencyState,
                    restoreEnabledPreference,
                    transactionGeneration, previousSourceSha256,
                    callback, e);
        } finally {
            if (!retainCandidateForDeferred) {
                candidate.cleanup();
            }
        }
    }

    private File allocatePluginBackupFile(String pluginId) throws IOException {
        File directory = getPluginsController().getPluginsDir();
        String nonce = Long.toHexString(System.nanoTime());
        for (int attempt = 0; attempt < 128; attempt++) {
            File candidate = new File(
                    directory,
                    pluginId + ".py.bak." + nonce
                            + (attempt == 0 ? "" : "." + attempt));
            if (!candidate.exists()) return candidate;
        }
        throw new IOException("Could not allocate a unique plugin backup path");
    }

    private StagedCandidatePayload copyToExclusiveHostStage(
            File source) throws IOException {
        if (source == null) {
            throw new IOException("Plugin candidate is missing");
        }
        File directory = getPluginsController().getPluginsDir();
        if (directory == null || !directory.isDirectory()) {
            throw new IOException(
                    "Plugin staging directory is unavailable");
        }

        java.io.FileDescriptor input = null;
        java.io.FileDescriptor output = null;
        File staged = null;
        boolean completed = false;
        try {
            input = android.system.Os.open(
                    source.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY
                            | android.system.OsConstants.O_CLOEXEC
                            | android.system.OsConstants.O_NOFOLLOW,
                    0);
            android.system.StructStat sourceStat =
                    android.system.Os.fstat(input);
            if ((sourceStat.st_mode
                            & android.system.OsConstants.S_IFMT)
                            != android.system.OsConstants.S_IFREG
                    || sourceStat.st_size <= 0
                    || sourceStat.st_size
                            > MAX_PLUGIN_CANDIDATE_BYTES) {
                throw new IOException(
                        "Plugin candidate must be a regular file of at most "
                                + MAX_PLUGIN_CANDIDATE_BYTES + " bytes");
            }

            for (int attempt = 0; attempt < 128; attempt++) {
                staged = new File(
                        directory,
                        ".plugin-install-"
                                + java.util.UUID.randomUUID()
                                        .toString()
                                        .replace("-", "")
                                + ".stage");
                try {
                    output = android.system.Os.open(
                            staged.getAbsolutePath(),
                            android.system.OsConstants.O_WRONLY
                                    | android.system.OsConstants.O_CREAT
                                    | android.system.OsConstants.O_EXCL
                                    | android.system.OsConstants.O_CLOEXEC
                                    | android.system.OsConstants.O_NOFOLLOW,
                            android.system.OsConstants.S_IRUSR
                                    | android.system.OsConstants.S_IWUSR);
                    break;
                } catch (android.system.ErrnoException collision) {
                    if (collision.errno
                            != android.system.OsConstants.EEXIST) {
                        throw collision;
                    }
                    staged = null;
                }
            }
            if (output == null || staged == null) {
                throw new IOException(
                        "Could not allocate an exclusive plugin stage");
            }

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream payload =
                    new ByteArrayOutputStream(
                            (int) sourceStat.st_size);
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            while (true) {
                int count = android.system.Os.read(
                        input, buffer, 0, buffer.length);
                if (count == 0) {
                    break;
                }
                total += count;
                if (total > MAX_PLUGIN_CANDIDATE_BYTES) {
                    throw new IOException(
                            "Plugin candidate exceeds "
                                    + MAX_PLUGIN_CANDIDATE_BYTES
                                    + " bytes");
                }
                digest.update(buffer, 0, count);
                payload.write(buffer, 0, count);
                int written = 0;
                while (written < count) {
                    int writeCount = android.system.Os.write(
                            output, buffer, written,
                            count - written);
                    if (writeCount <= 0) {
                        throw new IOException(
                                "Could not write the complete plugin stage");
                    }
                    written += writeCount;
                }
            }
            if (total == 0) {
                throw new IOException(
                        "Plugin candidate is empty");
            }
            android.system.Os.fchmod(
                    output,
                    android.system.OsConstants.S_IRUSR
                            | android.system.OsConstants.S_IWUSR);
            android.system.Os.fsync(output);
            String sha256 = digestHex(digest.digest());
            byte[] exactBytes = payload.toByteArray();

            android.system.Os.close(output);
            output = null;
            android.system.Os.close(input);
            input = null;

            if (!sha256.equals(
                    calculateFileSha256NoFollow(staged))) {
                throw new IOException(
                        "Staged plugin checksum mismatch");
            }
            syncDirectoryStrict(directory);
            completed = true;
            return new StagedCandidatePayload(
                    staged, exactBytes, sha256);
        } catch (android.system.ErrnoException failure) {
            throw new IOException(
                    "Unable to create a safe plugin stage",
                    failure);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IOException(
                    "SHA-256 is unavailable", failure);
        } finally {
            if (output != null) {
                try {
                    android.system.Os.close(output);
                } catch (Throwable ignored) {
                }
            }
            if (input != null) {
                try {
                    android.system.Os.close(input);
                } catch (Throwable ignored) {
                }
            }
            if (!completed && staged != null
                    && staged.exists() && !staged.delete()) {
                FileLog.w("Could not remove incomplete plugin stage "
                        + staged.getAbsolutePath());
            }
        }
    }

    private static void copyFileAndSync(File source, File destination)
            throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException failure) {
            if (destination.exists() && !destination.delete()) {
                FileLog.w("Could not remove incomplete plugin file "
                        + destination.getAbsolutePath());
            }
            throw failure;
        } catch (RuntimeException | Error failure) {
            if (destination.exists() && !destination.delete()) {
                FileLog.w("Could not remove incomplete plugin file "
                        + destination.getAbsolutePath());
            }
            throw failure;
        }
    }

    private void publishAuthorizedCandidate(
            AuthorizedCandidate candidate,
            File destination,
            InstallContinuationGuard continuationGuard)
            throws IOException {
        synchronized (installPublicationLock) {
            File staged = candidate.stagedFile;
            android.system.StructStat stagedStat;
            try {
                stagedStat = android.system.Os.lstat(
                        staged.getAbsolutePath());
            } catch (android.system.ErrnoException failure) {
                throw new IOException(
                        "Staged plugin candidate is unavailable",
                        failure);
            }
            int permissions = stagedStat.st_mode & 0777;
            if ((stagedStat.st_mode
                            & android.system.OsConstants.S_IFMT)
                            != android.system.OsConstants.S_IFREG
                    || permissions
                            != (android.system.OsConstants.S_IRUSR
                                    | android.system.OsConstants.S_IWUSR)
                    || stagedStat.st_size <= 0
                    || stagedStat.st_size
                            > MAX_PLUGIN_CANDIDATE_BYTES
                    || !candidate.sha256.equals(
                            calculateFileSha256NoFollow(staged))) {
                throw new IOException(
                        "Staged plugin candidate identity changed");
            }

            if (continuationGuard != null
                    && !continuationGuard.isAuthorized(
                            candidate.plugin.getId())) {
                throw new IOException(
                        "Plugin installation authority was revoked "
                                + "before publication");
            }
            try {
                android.system.Os.rename(
                        staged.getAbsolutePath(),
                        destination.getAbsolutePath());
            } catch (android.system.ErrnoException failure) {
                throw new IOException(
                        "Unable to publish staged plugin candidate",
                        failure);
            }
            candidate.markPublished();
            syncDirectoryStrict(destination.getParentFile());
        }
    }

    private static String calculateFileSha256NoFollow(
            File source) throws IOException {
        if (source == null) {
            throw new IOException(
                    "Plugin candidate is missing");
        }
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(
                    source.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY
                            | android.system.OsConstants.O_CLOEXEC
                            | android.system.OsConstants.O_NOFOLLOW,
                    0);
            android.system.StructStat stat =
                    android.system.Os.fstat(descriptor);
            if ((stat.st_mode
                            & android.system.OsConstants.S_IFMT)
                            != android.system.OsConstants.S_IFREG
                    || stat.st_size <= 0
                    || stat.st_size > MAX_PLUGIN_CANDIDATE_BYTES) {
                throw new IOException(
                        "Plugin candidate is not a bounded regular file");
            }
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            while (true) {
                int count = android.system.Os.read(
                        descriptor, buffer, 0, buffer.length);
                if (count == 0) break;
                total += count;
                if (total > MAX_PLUGIN_CANDIDATE_BYTES) {
                    throw new IOException(
                            "Plugin candidate exceeds the size limit");
                }
                digest.update(buffer, 0, count);
            }
            return digestHex(digest.digest());
        } catch (android.system.ErrnoException failure) {
            throw new IOException(
                    "Unable to read staged plugin candidate",
                    failure);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IOException(
                    "SHA-256 is unavailable", failure);
        } finally {
            if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String digestHex(byte[] digest) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append(String.format(
                    java.util.Locale.ROOT,
                    "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void syncDirectory(File directory) {
        if (directory == null) return;
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(
                    directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY,
                    0);
            android.system.Os.fsync(descriptor);
        } catch (Exception failure) {
            FileLog.e("Unable to fsync plugin directory", failure);
        } finally {
            if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void syncDirectoryStrict(File directory)
            throws IOException {
        if (directory == null) {
            throw new IOException("Plugin directory is missing");
        }
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(
                    directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY,
                    0);
            android.system.Os.fsync(descriptor);
        } catch (Throwable failure) {
            throw new IOException(
                    "Unable to fsync plugin directory "
                            + directory.getAbsolutePath(),
                    failure);
        } finally {
            if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private File pluginUpdateMarker(String pluginId) {
        return new File(
                getPluginsController().getPluginsDir(),
                "." + pluginId + ".py.update");
    }

    private File pluginUpdateDependencySnapshot(String pluginId) {
        return pluginUpdateDependencySnapshot(
                getPluginsController().getPluginsDir(), pluginId);
    }

    private static String newPluginUpdateTransactionId() {
        return java.util.UUID.randomUUID().toString()
                .replace("-", "");
    }

    private static String calculateFileSha256(File source)
            throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException(
                    "Plugin rollback source is missing");
        }
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            try (FileInputStream input =
                    new FileInputStream(source)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(
                        java.util.Locale.ROOT,
                        "%02x", value & 0xff));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IOException(
                    "SHA-256 is unavailable", failure);
        }
    }

    private static boolean matchesSourceChecksum(
            File source, String expected) throws IOException {
        if (expected == null) {
            return source != null && source.isFile();
        }
        if (UPDATE_NO_BACKUP.equals(expected)) {
            return source == null || !source.exists();
        }
        return source != null && source.isFile()
                && expected.equals(calculateFileSha256(source));
    }

    private static String resolveRollbackSourceChecksum(
            PluginUpdateMarkerData markerData, File backup,
            File destination) throws IOException {
        if (markerData.sourceSha256 != null) {
            return markerData.sourceSha256;
        }
        if (UPDATE_NO_BACKUP.equals(
                markerData.backupName)) {
            return UPDATE_NO_BACKUP;
        }
        if (backup != null && backup.isFile()) {
            return calculateFileSha256(backup);
        }
        if (destination != null && destination.isFile()) {
            return calculateFileSha256(destination);
        }
        throw new IOException(
                "Plugin rollback source is unrecoverable");
    }

    private boolean hasAnyPendingPluginUpdateRecovery() {
        if (!PipController.getInstance()
                        .getPendingDeferredArtifactPluginIds().isEmpty()) {
            return true;
        }
        File[] markers = getPluginsController().getPluginsDir()
                .listFiles((dir, name) ->
                        PLUGIN_UPDATE_MARKER_PATTERN.matcher(name)
                                .matches());
        if (markers != null && markers.length > 0) {
            return true;
        }
        File[] deletes = getPluginsController().getPluginsDir()
                .listFiles((dir, name) ->
                        PLUGIN_DELETE_MARKER_PATTERN.matcher(name)
                                .matches());
        return deletes != null && deletes.length > 0;
    }

    private static void writePluginUpdateMarker(
            File directory, String pluginId, File backup, String state,
            String cleanupMode,
            String transactionId,
            String sourceSha256,
            boolean replaceExisting) throws IOException {
        if ((!UPDATE_STATE_PREPARED.equals(state)
                && !UPDATE_STATE_ROLLING_BACK.equals(state)
                && !UPDATE_STATE_ROLLED_BACK.equals(state)
                && !UPDATE_STATE_COMMITTED.equals(state))
                || (!UPDATE_CLEANUP_PRUNE.equals(cleanupMode)
                        && !UPDATE_CLEANUP_UNINSTALL.equals(
                                cleanupMode))
                || transactionId == null
                || !UPDATE_TRANSACTION_ID_PATTERN.matcher(
                        transactionId).matches()
                || sourceSha256 == null
                || (!UPDATE_NO_BACKUP.equals(sourceSha256)
                        && !sourceSha256.matches(
                                "^[0-9a-f]{64}$"))) {
            throw new IOException(
                    "Invalid plugin update transaction state");
        }
        File marker = new File(
                directory,
                "." + pluginId + ".py.update");
        if (!replaceExisting && marker.exists()) {
            throw new IOException(
                    "Pending plugin update recovery exists for " + pluginId);
        }
        File staged = new File(
                marker.getParentFile(),
                marker.getName() + ".new."
                        + Long.toHexString(System.nanoTime()));
        String backupName =
                backup != null ? backup.getName() : UPDATE_NO_BACKUP;
        try {
            try (FileOutputStream out = new FileOutputStream(staged)) {
                out.write((state + "\n" + backupName + "\n"
                        + cleanupMode + "\n" + transactionId + "\n"
                        + sourceSha256 + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            try {
                android.system.Os.rename(
                        staged.getAbsolutePath(),
                        marker.getAbsolutePath());
            } catch (android.system.ErrnoException failure) {
                throw new IOException(
                        "Unable to publish plugin update marker", failure);
            }
            syncDirectoryStrict(marker.getParentFile());
        } finally {
            if (staged.exists() && !staged.delete()) {
                FileLog.w("Could not remove plugin update marker stage "
                        + staged.getAbsolutePath());
            }
        }
    }

    private boolean deletePluginUpdateMarker(String pluginId) {
        File marker = pluginUpdateMarker(pluginId);
        if (marker.exists() && !marker.delete()) {
            FileLog.w("Could not remove committed plugin update marker "
                    + marker.getAbsolutePath());
            return false;
        }
        try {
            syncDirectoryStrict(marker.getParentFile());
            return !marker.exists();
        } catch (IOException failure) {
            FileLog.e("Could not sync plugin update marker cleanup",
                    failure);
            return false;
        }
    }

    private List<String> snapshotPluginRequirements(
            File sourceFile, String pluginId, Plugin previousPlugin) {
        try {
            if (previousPlugin != null) {
                String requirements = previousPlugin.getRequirementsRaw();
                return requirements == null
                        ? Collections.emptyList()
                        : new ArrayList<>(parseRequirements(requirements));
            }
            String requirements =
                    parsePluginMetadata(sourceFile.getAbsolutePath())
                            .get("requirements");
            return requirements == null
                    ? Collections.emptyList()
                    : new ArrayList<>(parseRequirements(requirements));
        } catch (Throwable metadataFailure) {
            FileLog.e("Failed to snapshot source requirements for "
                    + pluginId + "; falling back to pip ownership",
                    metadataFailure);
            return new ArrayList<>(
                    PipController.getInstance()
                            .snapshotRequirements(pluginId));
        }
    }

    private void rollbackPluginFileInstall(
            String sourcePath, String pluginId, File destFile, File backupFile,
            boolean hadExistingFile, boolean backupCreated,
            boolean candidateWriteStarted,
            String updateTransactionId,
            boolean artifactTransactionPrepared,
            boolean dependencySnapshotPrepared,
            boolean updateMarkerPrepared,
            Plugin previousPlugin,
            List<String> previousRequirements,
            PipController.DependencySnapshot previousDependencyState,
            boolean restoreEnabledPreference, int transactionGeneration,
            String previousSourceSha256,
            Utilities.Callback<String> callback, Throwable failure) {
        if (Thread.currentThread() != Utilities.pluginsQueue) {
            Utilities.pluginsQueue.postRunnable(() -> rollbackPluginFileInstall(
                    sourcePath, pluginId, destFile, backupFile,
                    hadExistingFile, backupCreated,
                    candidateWriteStarted,
                    updateTransactionId,
                    artifactTransactionPrepared,
                    dependencySnapshotPrepared,
                    updateMarkerPrepared,
                    previousPlugin,
                    previousRequirements, previousDependencyState,
                    restoreEnabledPreference,
                    transactionGeneration, previousSourceSha256,
                    callback, failure));
            return;
        }
        if (deferUntilLifecycleSettled(pluginId, () ->
                rollbackPluginFileInstall(
                        sourcePath, pluginId, destFile, backupFile,
                        hadExistingFile, backupCreated,
                        candidateWriteStarted,
                        updateTransactionId,
                        artifactTransactionPrepared,
                        dependencySnapshotPrepared,
                        updateMarkerPrepared,
                        previousPlugin,
                        previousRequirements, previousDependencyState,
                        restoreEnabledPreference,
                        transactionGeneration, previousSourceSha256,
                        callback, failure))) {
            return;
        }

        PluginDebugLog.log("loadPluginFromFile ROLLBACK id=" + pluginId
                + " file=" + sourcePath, failure);
        FileLog.e("Unexpected error loading plugin from file: "
                + sourcePath, failure);

        if (updateMarkerPrepared) {
            try {
                writePluginUpdateMarker(
                        getPluginsController().getPluginsDir(),
                        pluginId, backupFile,
                        UPDATE_STATE_ROLLING_BACK,
                        restoreEnabledPreference
                                ? UPDATE_CLEANUP_PRUNE
                                : UPDATE_CLEANUP_UNINSTALL,
                        updateTransactionId,
                        previousSourceSha256, true);
            } catch (Throwable markerFailure) {
                RECOVERY_BLOCKED_PLUGIN_IDS.add(pluginId);
                String error =
                        "Plugin update rollback could not be started for "
                                + pluginId;
                FileLog.e(error, markerFailure);
                if (previousPlugin != null) {
                    previousPlugin.setEnabled(false);
                    previousPlugin.setError(failure);
                    getPluginsController().plugins.put(
                            pluginId, previousPlugin);
                }
                getPluginsController().notifyPluginsChanged();
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(
                            () -> callback.run(error));
                }
                return;
            }
        }

        boolean artifactStateRecovered =
                !artifactTransactionPrepared
                        || PipController.getInstance()
                                .rollbackDeferredArtifactTransaction(
                                        pluginId,
                                        updateTransactionId);
        if (!artifactStateRecovered) {
            RECOVERY_BLOCKED_PLUGIN_IDS.add(pluginId);
            if (previousPlugin != null) {
                previousPlugin.setEnabled(false);
                previousPlugin.setError(failure);
                getPluginsController().plugins.put(
                        pluginId, previousPlugin);
            }
            String error = "Plugin update rollback is incomplete for "
                    + pluginId;
            FileLog.e(error);
            getPluginsController().notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(
                        () -> callback.run(error));
            }
            return;
        }

        boolean backupRestored = false;
        if (candidateWriteStarted && backupCreated
                && backupFile != null && backupFile.exists()) {
            try {
                if (!matchesSourceChecksum(
                        backupFile, previousSourceSha256)) {
                    throw new IOException(
                            "Plugin rollback backup checksum mismatch");
                }
                android.system.Os.rename(
                        backupFile.getAbsolutePath(),
                        destFile.getAbsolutePath());
                syncDirectory(destFile.getParentFile());
                backupRestored =
                        matchesSourceChecksum(
                                destFile,
                                previousSourceSha256);
                if (!backupRestored) {
                    throw new IOException(
                            "Restored plugin source checksum mismatch");
                }
                invalidatePluginMetadata(destFile);
            } catch (Throwable restoreFailure) {
                FileLog.e("Failed to restore backup for plugin " + pluginId
                        + "; preserved at " + backupFile.getAbsolutePath(),
                        restoreFailure);
            }
        } else if (candidateWriteStarted && destFile.exists()
                && !destFile.delete()) {
            FileLog.e("Failed to remove rejected plugin candidate "
                    + destFile.getAbsolutePath());
        }
        invalidatePluginMetadata(destFile);

        if (!backupRestored && backupCreated && backupFile != null
                && backupFile.exists() && !destFile.exists()) {
            try {
                if (!matchesSourceChecksum(
                        backupFile, previousSourceSha256)) {
                    throw new IOException(
                            "Plugin rollback backup checksum mismatch");
                }
                android.system.Os.rename(
                        backupFile.getAbsolutePath(),
                        destFile.getAbsolutePath());
                syncDirectory(destFile.getParentFile());
                backupRestored =
                        matchesSourceChecksum(
                                destFile,
                                previousSourceSha256);
                if (!backupRestored) {
                    throw new IOException(
                            "Restored plugin source checksum mismatch");
                }
                invalidatePluginMetadata(destFile);
            } catch (Throwable restoreFailure) {
                FileLog.e("Failed to restore backup for plugin " + pluginId
                        + "; preserved at " + backupFile.getAbsolutePath(),
                        restoreFailure);
            }
        }

        boolean sourceRecovered =
                hadExistingFile
                        ? backupRestored
                                || (!candidateWriteStarted
                                        && destFile.isFile())
                        : !destFile.exists();
        boolean dependencyStateRecovered = false;
        boolean reloadRestoredPlugin = false;

        if (hadExistingFile) {
            getPluginsController().restorePluginEnabledPreference(
                    pluginId, transactionGeneration,
                    restoreEnabledPreference);
            dependencyStateRecovered =
                    PipController.getInstance().restoreState(
                            pluginId, previousDependencyState);

            reloadRestoredPlugin =
                    backupRestored
                    || (!candidateWriteStarted && destFile.isFile())
                    || (!backupCreated && destFile.isFile());
            reloadRestoredPlugin &=
                    sourceRecovered && dependencyStateRecovered;
            if (!reloadRestoredPlugin) {
                if (previousPlugin != null) {
                    previousPlugin.setEnabled(false);
                    previousPlugin.setError(failure);
                    getPluginsController().plugins.put(
                            pluginId, previousPlugin);
                }
            }
        } else {
            try {
                dependencyStateRecovered =
                        PipController.getInstance()
                                .uninstallDependencies(pluginId);
            } catch (Throwable dependencyCleanupFailure) {
                FileLog.e("Failed to remove rejected plugin dependencies for "
                        + pluginId, dependencyCleanupFailure);
            }
            getPluginsController().cleanupPlugin(pluginId);
            getPluginsController().plugins.remove(pluginId);
            PyObject instance = pluginInstances.get(pluginId);
            PluginsController.PluginRuntimeToken token =
                    pluginRuntimeTokens.get(pluginId);
            if (instance != null) {
                evictPluginInstance(pluginId, instance, token);
            } else if (token != null) {
                rollbackPluginImport(pluginId, null, null, token);
            }
            removePluginSettingsHostNative(
                    getPluginsController().getPluginsDir(),
                    pluginId);
            clearPluginHostPreferences(
                    getPluginsController(), pluginId);
        }

        File dependencySnapshotFile =
                pluginUpdateDependencySnapshot(pluginId);
        boolean rollbackTransactionClosed = !updateMarkerPrepared;
        if (sourceRecovered && dependencyStateRecovered) {
            if (updateMarkerPrepared) {
                try {
                    writePluginUpdateMarker(
                            getPluginsController().getPluginsDir(),
                            pluginId, backupFile,
                            UPDATE_STATE_ROLLED_BACK,
                            restoreEnabledPreference
                                    ? UPDATE_CLEANUP_PRUNE
                                    : UPDATE_CLEANUP_UNINSTALL,
                            updateTransactionId,
                            previousSourceSha256, true);
                } catch (Throwable markerFailure) {
                    RECOVERY_BLOCKED_PLUGIN_IDS.add(pluginId);
                    FileLog.e("Could not commit plugin rollback state for "
                            + pluginId, markerFailure);
                    if (previousPlugin != null) {
                        previousPlugin.setEnabled(false);
                        previousPlugin.setError(failure);
                        getPluginsController().plugins.put(
                                pluginId, previousPlugin);
                    }
                    getPluginsController().notifyPluginsChanged();
                    if (callback != null) {
                        AndroidUtilities.runOnUIThread(() ->
                                callback.run(
                                        "Plugin rollback remains pending: "
                                                + pluginId));
                    }
                    return;
                }
            }
            if (!candidateWriteStarted && backupFile != null
                    && backupFile.exists() && !backupFile.delete()) {
                FileLog.w("Could not remove redundant update backup "
                        + backupFile.getAbsolutePath());
            }
            if (dependencySnapshotPrepared
                    && dependencySnapshotFile.exists()
                    && !dependencySnapshotFile.delete()) {
                FileLog.w("Could not remove recovered dependency snapshot "
                        + dependencySnapshotFile.getAbsolutePath());
            }
            if (updateMarkerPrepared
                    && (backupFile == null || !backupFile.exists()
                            || backupRestored)
                    && (!dependencySnapshotPrepared
                            || !dependencySnapshotFile.exists())) {
                rollbackTransactionClosed =
                        deletePluginUpdateMarker(pluginId);
            } else if (!updateMarkerPrepared
                    && dependencySnapshotPrepared
                    && dependencySnapshotFile.exists()) {
                FileLog.w("Orphan dependency snapshot retained for "
                        + pluginId);
            }
            syncDirectory(destFile.getParentFile());
        } else if (!updateMarkerPrepared
                && dependencySnapshotPrepared
                && dependencySnapshotFile.exists()
                && !candidateWriteStarted) {
            if (!dependencySnapshotFile.delete()) {
                FileLog.w("Could not remove unpublished dependency snapshot "
                        + dependencySnapshotFile.getAbsolutePath());
            }
            syncDirectory(destFile.getParentFile());
        }

        reloadRestoredPlugin &= rollbackTransactionClosed;
        if (rollbackTransactionClosed) {
            RECOVERY_BLOCKED_PLUGIN_IDS.remove(pluginId);
        }
        if (hadExistingFile && !rollbackTransactionClosed
                && previousPlugin != null) {
            previousPlugin.setEnabled(false);
            previousPlugin.setError(failure);
            getPluginsController().plugins.put(
                    pluginId, previousPlugin);
        }
        if (hadExistingFile && reloadRestoredPlugin) {
            try {
                loadPlugin(
                        pluginId, destFile.getAbsolutePath(),
                        backupRestored ? null : previousPlugin);
            } catch (LifecyclePendingException pendingRestore) {
                FileLog.e("Original plugin is still retiring after rollback for "
                        + pluginId, pendingRestore);
            } catch (Throwable reloadFailure) {
                FileLog.e("Failed to reload restored plugin "
                        + pluginId, reloadFailure);
            }
        }
        getPluginsController().notifyPluginsChanged();

        if (callback != null) {
            String message = failure != null && failure.getMessage() != null
                    ? failure.getMessage()
                    : "Plugin update failed";
            AndroidUtilities.runOnUIThread(() -> callback.run(message));
        }
    }

    public PluginsController.PluginValidationResult validatePluginFromFile(String str) {
        PluginDebugLog.log("validate START file=" + str + " exists=" + new File(str).exists()
                + " size=" + (new File(str).exists() ? new File(str).length() : -1));
        if (!new File(str).exists()) {
            PluginDebugLog.log("validate FAIL: file not found");
            return new PluginsController.PluginValidationResult(null, "Plugin file not found.");
        }
        try {
            Map<String, String> pluginMetadata = parsePluginMetadata(str);
            return validatePluginMetadata(pluginMetadata, str);
        } catch (PyException e) {
            PluginDebugLog.log("validate FAIL: PyException parsing metadata from " + str, e);
            FileLog.e("Failed to parse metadata from " + str + ". Error: " + e.getMessage(), e);
            return new PluginsController.PluginValidationResult(null, e.getMessage());
        } catch (Throwable th) {
            PluginDebugLog.log("validate FAIL: Throwable validating " + str, th);
            FileLog.e("Unexpected error validating plugin " + str, th);
            return new PluginsController.PluginValidationResult(null, th.getMessage());
        }
    }

    private PluginsController.PluginValidationResult
            validatePluginMetadata(
                    Map<String, String> pluginMetadata,
                    String sourceLabel) {
        PluginDebugLog.log("validate metadata=" + pluginMetadata
                + " source=" + sourceLabel);
        String pluginId = pluginMetadata != null
                ? pluginMetadata.get("id") : null;
        String pluginName = pluginMetadata != null
                ? pluginMetadata.get("name") : null;
        if (TextUtils.isEmpty(pluginId)
                || TextUtils.isEmpty(pluginName)) {
            PluginDebugLog.log(
                    "validate FAIL: empty __id__ or __name__ (id='"
                            + pluginId + "' name='" + pluginName + "')");
            return new PluginsController.PluginValidationResult(
                    null,
                    "Plugin metadata must contain non-empty '__id__' "
                            + "and '__name__'.");
        }
        if (!pluginId.matches(
                "^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")) {
            PluginDebugLog.log(
                    "validate FAIL: bad __id__ '" + pluginId
                            + "' (regex)");
            return new PluginsController.PluginValidationResult(
                    null,
                    "Plugin '__id__' must be 2-32 characters long, "
                            + "start with a letter, and contain only "
                            + "latin letters, numbers, dashes and "
                            + "underscores.");
        }
        String minVersion = pluginMetadata.get("min_version");
        boolean versionOk = minVersion == null
                || SharedConfig.versionBiggerOrEqual(
                        BuildVars.BUILD_VERSION_STRING,
                        minVersion);
        PluginDebugLog.log(
                "validate id=" + pluginId + " name=" + pluginName
                        + " min_version=" + minVersion
                        + " appVersion="
                        + BuildVars.BUILD_VERSION_STRING
                        + " versionOk=" + versionOk
                        + " requirements="
                        + pluginMetadata.get("requirements"));
        if (!versionOk) {
            return new PluginsController.PluginValidationResult(
                    null,
                    "Plugin requires app version " + minVersion
                            + " or higher. Current is "
                            + BuildVars.BUILD_VERSION_STRING);
        }

        Plugin plugin = new Plugin(pluginId, pluginName);
        plugin.setEngine(PluginsConstants.PYTHON);
        plugin.setAuthor(pluginMetadata.getOrDefault(
                "author",
                LocaleController.getString(
                        R.string.PluginNoAuthor)));
        plugin.setDescription(pluginMetadata.getOrDefault(
                "description",
                LocaleController.getString(
                        R.string.PluginNoDescription)));
        plugin.setIcon(pluginMetadata.get("icon"));
        plugin.setVersion(
                pluginMetadata.getOrDefault("version", "1.0"));
        plugin.setMinVersion(minVersion);
        plugin.setRequirements(
                pluginMetadata.get("requirements"));
        plugin.setEnabled(
                getPluginsController().preferences.getBoolean(
                        "plugin_enabled_" + pluginId, false));
        PluginDebugLog.log("validate OK id=" + pluginId);
        return new PluginsController.PluginValidationResult(
                plugin, null);
    }

    public List<SettingItem> parsePySettingDefinitions(List<PyObject> list) {
        return parsePySettingDefinitions(
                list, getPluginsController().captureCurrentPluginRuntime());
    }

    public List<SettingItem> parsePySettingDefinitions(
            List<PyObject> list,
            PluginsController.PluginRuntimeToken ownerToken) {
        ArrayList<SettingItem> arrayList = new ArrayList<>(list.size());
        for (PyObject pyObject : list) {
            if (pyObject != null) {
                try {
                    SettingItem item = null;
                    String type = PyObjectUtils.getString(pyObject, PluginsConstants.Settings.TYPE, null);
                    if (type == null) {
                        FileLog.w("A setting item in a plugin is missing its 'type'. Skipping.");
                    } else {
                        String key = PyObjectUtils.getString(pyObject, PluginsConstants.Settings.KEY, null);
                        String text = PyObjectUtils.getString(pyObject, "text", null);
                        String subtext = PyObjectUtils.getString(pyObject, "subtext", null);
                        String icon = PyObjectUtils.getString(pyObject, "icon", null);
                        PyObject onChange = pyObject.get(PluginsConstants.Settings.ON_CHANGE);
                        PyObject onLongClick = pyObject.get(PluginsConstants.Settings.ON_LONG_CLICK);
                        String linkAlias = PyObjectUtils.getString(pyObject, PluginsConstants.Settings.LINK_ALIAS, null);
                        PyObject defVal = pyObject.get(PluginsConstants.Settings.DEFAULT);

                        switch (type) {
                            case PluginsConstants.Settings.TYPE_EDIT_TEXT:
                                String hint = PyObjectUtils.getString(pyObject, PluginsConstants.Settings.HINT, null);
                                boolean multiline = PyObjectUtils.getBoolean(pyObject, PluginsConstants.Settings.MULTILINE, false);
                                int maxLength = PyObjectUtils.getInt(pyObject, PluginsConstants.Settings.MAX_LENGTH, 256);
                                String mask = PyObjectUtils.getString(pyObject, PluginsConstants.Settings.MASK, null);
                                if (key != null && hint != null) {
                                    item = new EditTextSetting(key, hint, defVal != null ? defVal.toString() : "", multiline, maxLength, mask, onChange);
                                }
                                break;
                            case PluginsConstants.Settings.TYPE_HEADER:
                                if (text != null) {
                                    item = new HeaderSetting(text);
                                }
                                break;
                            case PluginsConstants.Settings.TYPE_SWITCH:
                                if (key != null && text != null && defVal != null) {
                                    item = new SwitchSetting(key, text, defVal.toBoolean(), subtext, icon, onChange, onLongClick, linkAlias);
                                }
                                break;
                            case PluginsConstants.Settings.TYPE_TEXT:
                                boolean accent = PyObjectUtils.getBoolean(pyObject, PluginsConstants.Settings.ACCENT, false);
                                boolean red = PyObjectUtils.getBoolean(pyObject, PluginsConstants.Settings.RED, false);
                                PyObject onClick = pyObject.get("on_click");
                                PyObject createSubFragment = pyObject.get(PluginsConstants.Settings.CREATE_SUB_FRAGMENT);
                                if (text != null) {
                                    item = new TextSetting(text, icon, accent, red, onClick, createSubFragment, onLongClick, linkAlias);
                                }
                                break;
                            case PluginsConstants.Settings.TYPE_INPUT:
                                if (key != null && text != null) {
                                    item = new InputSetting(key, text, defVal != null ? defVal.toString() : "", subtext, icon, onChange, onLongClick, linkAlias);
                                }
                                break;
                            case PluginsConstants.Settings.TYPE_SELECTOR:
                                String[] items = PyObjectUtils.getStringArray(pyObject, PluginsConstants.Settings.ITEMS, null);
                                if (key != null && text != null && items != null && items.length != 0 && defVal != null) {
                                    item = new SelectorSetting(key, text, defVal.toInt(), items, icon, onChange, onLongClick, linkAlias);
                                }
                                break;
                            case PluginsConstants.Settings.TYPE_DIVIDER:
                                item = new DividerSetting(text);
                                break;
                            case PluginsConstants.Settings.TYPE_CUSTOM:
                                PyObject createView = pyObject.get("create_view");
                                PyObject bindView = pyObject.get("bind_view");
                                PyObject onClickCustom = pyObject.get("on_click");
                                PyObject createSubCustom = pyObject.get(PluginsConstants.Settings.CREATE_SUB_FRAGMENT);
                                if (createView != null) {
                                    item = new app.nimarkogram.messenger.plugins.models.CustomSetting(
                                            createView, bindView, onClickCustom, createSubCustom, onLongClick, linkAlias);
                                }
                                break;
                        }
                        if (item != null) {
                            item.runtimeToken = ownerToken;
                            arrayList.add(item);
                        }
                    }
                } catch (Exception e) { 
                    FileLog.e("Error parsing specific setting item", e);
                }
            }
        }
        return arrayList;
    }

    @Override
    public List<SettingItem> loadPluginSettings(String str) {
        boolean watchdogStarted = false;
        PluginsController.PluginRuntimeToken runtimeToken = null;
        try {
            Plugin plugin = getPluginsController().plugins.get(str);
            PyObject pyObject = this.pluginInstances.get(str);
            if (plugin != null && plugin.isEnabled() && !plugin.hasError() && pyObject != null
                    && getPluginsController().isPluginActive(str)) {
                runtimeToken = enterCurrentRuntime(str, pyObject);
                if (runtimeToken == null) {
                    return null;
                }
                getPluginsController().getWatchdog().onPluginExecutionStarted(str);
                watchdogStarted = true;
                PyObject pyObjectCallAttr = pyObject.callAttr(PluginsConstants.CREATE_SETTINGS);
                if (pyObjectCallAttr == null) {
                    return null;
                }
                List<PyObject> listAsList = pyObjectCallAttr.asList();
                if (listAsList.isEmpty()) {
                    return null;
                }
                return parsePySettingDefinitions(listAsList, runtimeToken);
            }
            getPluginsController().invalidatePluginSettings(str);
            return null;
        } catch (Exception e) {
            FileLog.e("Failed to load plugin settings", e);
            return null;
        } catch (Error failure) {
            if (watchdogStarted) {
                getPluginsController().getWatchdog().onPluginExecutionFailed(str, failure);
            }
            throw failure;
        } finally {
            if (watchdogStarted) {
                getPluginsController().getWatchdog().onPluginExecutionFinished(str);
            }
            if (runtimeToken != null) {
                getPluginsController().exitPluginRuntime(runtimeToken);
            }
        }
    }

    @Override
    public void executeOnAppEvent(String str) {
        if (NimarkoConfig.pluginsSafeMode) {
            return;
        }
        if (getPython() == null) {
            FileLog.e("nimarko: executeOnAppEvent getPython()==null");
            return;
        }
        PyObject pyObject;
        PyObject pyObjectCall;
        try {
            pyObject = getPython().getModule("base_plugin").get("AppEvent");
            if (pyObject == null) {
                return;
            }
            pyObjectCall = pyObject.call(str);
        } catch (Throwable t) {
            FileLog.e("nimarko: executeOnAppEvent base_plugin load failed", t);
            return;
        }
        PyObject pyObject2 = this.debuggerListener;
            if (pyObject2 != null) {
                try {
                    pyObject2.callAttr(PluginsConstants.ON_APP_EVENT, pyObjectCall);
                } catch (PyException e) {
                    FileLog.e("Failed to execute app event for debugger listener", e);
                }
            }
            for (java.util.Map.Entry<String, PyObject> entry : this.pluginInstances.entrySet()) {
                String pid = entry.getKey();
                PyObject pyObject3 = entry.getValue();
                if (!getPluginsController().isPluginActive(pid)) {
                    continue;
                }
                Plugin plugin = getPluginsController().plugins.get(pid);
                java.util.Set<String> implemented =
                        plugin != null ? plugin.implementedHooks : null;
                if (implemented != null
                        && !implemented.contains(PluginsConstants.ON_APP_EVENT)) {
                    continue;
                }
                PluginsController.PluginRuntimeToken runtimeToken =
                        enterCurrentRuntime(pid, pyObject3);
                if (runtimeToken == null) {
                    continue;
                }
                getPluginsController().getWatchdog().onPluginExecutionStarted(pid);
                try {
                    PyObject callback = plugin != null && plugin.boundHooks != null
                            ? plugin.boundHooks.get(PluginsConstants.ON_APP_EVENT)
                            : null;
                    if (callback != null) {
                        callback.call(pyObjectCall);
                    } else {
                        pyObject3.callAttr(PluginsConstants.ON_APP_EVENT, pyObjectCall);
                    }
                } catch (PyException e2) {
                    FileLog.e("Failed to execute app " + str + " for " + pid, e2);
                } catch (RuntimeException | Error failure) {
                    getPluginsController().getWatchdog().onPluginExecutionFailed(pid, failure);
                    throw failure;
                } finally {
                    getPluginsController().getWatchdog().onPluginExecutionFinished(pid);
                    getPluginsController().exitPluginRuntime(runtimeToken);
                }
            }
    }

    public <T> PluginsController.HookResult<T> executeHook(PyObject pyObject, T t, Class<T> cls, String str, PyMethodCaller<T> pyMethodCaller, Utilities.Callback<PyException> callback) {
        if (pyObject != null) {
            try {
                PyObject pyObjectCall = pyMethodCaller.call(pyObject, t);
                if (pyObjectCall != null) {
                    String string = PyObjectUtils.getString(pyObjectCall, PluginsConstants.STRATEGY, PluginsConstants.Strategy.DEFAULT);
                    if (string.endsWith(PluginsConstants.Strategy.CANCEL)) {
                        return new PluginsController.HookResult<>(null, true, false);
                    }
                    if (string.endsWith(PluginsConstants.Strategy.MODIFY) || string.endsWith(PluginsConstants.Strategy.MODIFY_FINAL)) {
                        PyObject pyObject2 = pyObjectCall.get(str);
                        if (pyObject2 != null) {
                            t = pyObject2.toJava(cls);
                        }
                        if (string.endsWith(PluginsConstants.Strategy.MODIFY_FINAL)) {
                            return new PluginsController.HookResult<>(t, false, true);
                        }
                    }
                }
            } catch (PyException e) {
                callback.run(e);
            }
        }
        return new PluginsController.HookResult<>(t, false, false);
    }

    private <T> PluginsController.HookResult<T> executeHook(String str, T t, Class<T> cls, String str2, PyMethodCaller<T> pyMethodCaller, Utilities.Callback<PyException> callback) {
        return executeHook(
                str, t, cls, str2, pyMethodCaller, callback, null);
    }

    private <T> PluginsController.HookResult<T> executeHook(
            String str, T t, Class<T> cls, String str2,
            PyMethodCaller<T> pyMethodCaller,
            Utilities.Callback<PyException> callback,
            PluginsController.PluginRuntimeToken expectedRuntime) {
        if (!getPluginsController().isPluginActive(str)) {
            return new PluginsController.HookResult<>(t, false, false);
        }
        PyObject instance = this.pluginInstances.get(str);
        if (instance == null) {
            return new PluginsController.HookResult<>(t, false, false);
        }
        PluginsController.PluginRuntimeToken runtimeToken =
                enterCurrentRuntime(str, instance, expectedRuntime);
        if (runtimeToken == null) {
            return new PluginsController.HookResult<>(t, false, false);
        }
        getPluginsController().getWatchdog().onPluginExecutionStarted(str);
        try {
            return executeHook(instance, t, cls, str2, pyMethodCaller, callback);
        } catch (RuntimeException | Error failure) {
            getPluginsController().getWatchdog().onPluginExecutionFailed(str, failure);
            throw failure;
        } finally {
            getPluginsController().getWatchdog().onPluginExecutionFinished(str);
            getPluginsController().exitPluginRuntime(runtimeToken);
        }
    }

    private PyObject callBound(String pluginId, PyObject pyObject, String hookName, Object... args) {
        Plugin p = getPluginsController().plugins.get(pluginId);
        if (p != null) {
            java.util.Map<String, PyObject> bound = p.boundHooks;
            if (bound != null) {
                PyObject ref = bound.get(hookName);
                if (ref != null) {
                    return ref.call(args);
                }
            }
        }
        return pyObject.callAttr(hookName, args);
    }

    @Override
    public PluginsController.HookResult<TLObject> executePreRequestHook(final String str, final int i, TLObject tLObject, final String str2) {
        return executeHook(str2, tLObject, TLObject.class, PluginsConstants.REQUEST, (pyObject, obj) -> callBound(str2, pyObject, "pre_request_hook", str, Integer.valueOf(i), obj), obj -> FileLog.e("Failed to execute pre_request_hook in " + str2 + " for " + str, (PyException) obj));
    }

    @Override
    public PluginsController.HookResult<TLObject> executePreRequestHook(
            final String str, final int i, TLObject request,
            final String pluginId,
            PluginsController.PluginRuntimeToken expectedRuntime) {
        return executeHook(
                pluginId, request, TLObject.class,
                PluginsConstants.REQUEST,
                (pyObject, obj) -> callBound(
                        pluginId, pyObject, "pre_request_hook",
                        str, Integer.valueOf(i), obj),
                error -> FileLog.e(
                        "Failed to execute pre_request_hook in "
                                + pluginId + " for " + str,
                        error),
                expectedRuntime);
    }

    public PluginsController.HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String str, int i, TLObject tLObject, TLRPC.TL_error tL_error, PyObject pyObject) {
        if (pyObject != null) {
            try {
                PyObject pyObjectCallAttr = pyObject.callAttr("post_request_hook", str, Integer.valueOf(i), tLObject, tL_error);
                if (pyObjectCallAttr != null) {
                    String string = PyObjectUtils.getString(pyObjectCallAttr, PluginsConstants.STRATEGY, "");
                    if (string.endsWith(PluginsConstants.Strategy.CANCEL)) {
                        return new PluginsController.HookResult<>(null, true, false);
                    }
                    if (string.endsWith(PluginsConstants.Strategy.MODIFY) || string.endsWith(PluginsConstants.Strategy.MODIFY_FINAL)) {
                        PyObject pyObject2 = pyObjectCallAttr.get(PluginsConstants.RESPONSE);
                        if (pyObject2 != null) {
                            tLObject = pyObject2.toJava(TLObject.class);
                        }
                        PyObject pyObject3 = pyObjectCallAttr.get(PluginsConstants.ERROR);
                        if (pyObject3 != null) {
                            tL_error = pyObject3.toJava(TLRPC.TL_error.class);
                        }
                        if (string.endsWith(PluginsConstants.Strategy.MODIFY_FINAL)) {
                            return new PluginsController.HookResult<>(new PluginsHooks.PostRequestResult(tLObject, tL_error), false, true);
                        }
                    }
                }
            } catch (PyException e) {
                FileLog.e("Failed to execute post_request_hook for " + str, e);
            }
        }
        return new PluginsController.HookResult<>(new PluginsHooks.PostRequestResult(tLObject, tL_error), false, false);
    }

    @Override
    public PluginsController.HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String str, int i, TLObject tLObject, TLRPC.TL_error tL_error, String str2) {
        return executePostRequestHook(
                str, i, tLObject, tL_error, str2, null);
    }

    @Override
    public PluginsController.HookResult<PluginsHooks.PostRequestResult>
            executePostRequestHook(
                    String str, int i, TLObject tLObject,
                    TLRPC.TL_error tL_error, String str2,
                    PluginsController.PluginRuntimeToken expectedRuntime) {
        PluginsHooks.PostRequestResult unchanged = new PluginsHooks.PostRequestResult(tLObject, tL_error);
        if (!getPluginsController().isPluginActive(str2)) {
            return new PluginsController.HookResult<>(unchanged, false, false);
        }
        PyObject instance = this.pluginInstances.get(str2);
        PluginsController.PluginRuntimeToken runtimeToken =
                enterCurrentRuntime(str2, instance, expectedRuntime);
        if (runtimeToken == null) {
            return new PluginsController.HookResult<>(unchanged, false, false);
        }
        getPluginsController().getWatchdog().onPluginExecutionStarted(str2);
        try {
            return executePostRequestHook(str, i, tLObject, tL_error, instance);
        } catch (RuntimeException | Error failure) {
            getPluginsController().getWatchdog().onPluginExecutionFailed(str2, failure);
            throw failure;
        } finally {
            getPluginsController().getWatchdog().onPluginExecutionFinished(str2);
            getPluginsController().exitPluginRuntime(runtimeToken);
        }
    }

    @Override
    public PluginsController.HookResult<TLRPC.Update> executeUpdateHook(final String str, final int i, TLRPC.Update update, String str2) {
        return executeHook(str2, update, TLRPC.Update.class, PluginsConstants.UPDATE, (pyObject, obj) -> callBound(str2, pyObject, "on_update_hook", str, Integer.valueOf(i), obj), obj -> FileLog.e("Failed to execute on_update_hook for " + str, (PyException) obj));
    }

    @Override
    public PluginsController.HookResult<TLRPC.Update> executeUpdateHook(
            final String str, final int i, TLRPC.Update update,
            String pluginId,
            PluginsController.PluginRuntimeToken expectedRuntime) {
        return executeHook(
                pluginId, update, TLRPC.Update.class,
                PluginsConstants.UPDATE,
                (pyObject, obj) -> callBound(
                        pluginId, pyObject, "on_update_hook",
                        str, Integer.valueOf(i), obj),
                error -> FileLog.e(
                        "Failed to execute on_update_hook for " + str,
                        error),
                expectedRuntime);
    }

    @Override
    public PluginsController.HookResult<TLRPC.Updates> executeUpdatesHook(final String str, final int i, TLRPC.Updates updates, String str2) {
        return executeHook(str2, updates, TLRPC.Updates.class, PluginsConstants.UPDATES, (pyObject, obj) -> callBound(str2, pyObject, "on_updates_hook", str, Integer.valueOf(i), obj), obj -> FileLog.e("Failed to execute on_updates_hook for " + str, (PyException) obj));
    }

    @Override
    public PluginsController.HookResult<TLRPC.Updates> executeUpdatesHook(
            final String str, final int i, TLRPC.Updates updates,
            String pluginId,
            PluginsController.PluginRuntimeToken expectedRuntime) {
        return executeHook(
                pluginId, updates, TLRPC.Updates.class,
                PluginsConstants.UPDATES,
                (pyObject, obj) -> callBound(
                        pluginId, pyObject, "on_updates_hook",
                        str, Integer.valueOf(i), obj),
                error -> FileLog.e(
                        "Failed to execute on_updates_hook for " + str,
                        error),
                expectedRuntime);
    }

    @Override
    public PluginsController.HookResult<SendMessagesHelper.SendMessageParams> executeSendMessageHook(final int i, SendMessagesHelper.SendMessageParams sendMessageParams, final String str) {
        return executeHook(str, sendMessageParams, SendMessagesHelper.SendMessageParams.class, PluginsConstants.PARAMS, (pyObject, obj) -> callBound(str, pyObject, "on_send_message_hook", Integer.valueOf(i), obj), obj -> FileLog.e("Failed to execute on_send_message_hook for " + str, (PyException) obj));
    }

    @Override
    public PluginsController.HookResult<SendMessagesHelper.SendMessageParams>
            executeSendMessageHook(
                    final int i,
                    SendMessagesHelper.SendMessageParams params,
                    final String pluginId,
                    PluginsController.PluginRuntimeToken expectedRuntime) {
        return executeHook(
                pluginId, params,
                SendMessagesHelper.SendMessageParams.class,
                PluginsConstants.PARAMS,
                (pyObject, obj) -> callBound(
                        pluginId, pyObject,
                        "on_send_message_hook",
                        Integer.valueOf(i), obj),
                error -> FileLog.e(
                        "Failed to execute on_send_message_hook for "
                                + pluginId,
                        error),
                expectedRuntime);
    }

    public String fetchParameterValue(String str, String str2) {
        if (str == null) {
            return null;
        }
        try {
            File file = new File(str);
            if (file.exists() && file.isFile()) {
                return parsePluginMetadata(str).get(str2);
            }
        } catch (Exception unused) {
        }
        return null;
    }

    private static String metadataCacheKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    private void invalidatePluginMetadata(File file) {
        if (file != null) {
            metadataCache.remove(metadataCacheKey(file));
        }
    }

    public java.util.Map<String, String> parsePluginMetadata(String str) {
        HashMap<String, String> map = new HashMap<>();
        if (str != null) {
            File file = new File(str);
            if (file.exists() && file.isFile()) {
                String cacheKey = metadataCacheKey(file);
                MetadataCacheEntry cached = metadataCache.get(cacheKey);
                if (cached != null) {
                    if (cached.matches(file)) {
                        return new HashMap<>(cached.metadata);
                    }
                    metadataCache.remove(cacheKey, cached);
                }
                if (getPython() == null) {
                    FileLog.e("Python engine not initialized, cannot parse metadata for " + str);
                    return map;
                }
                try {
                    PyObject pyObjectCallAttr = getPython().getModule("utils.metadata_parser").callAttr("get_metadata", str);
                    if (pyObjectCallAttr != null) {
                        for (Map.Entry<PyObject, PyObject> entry : pyObjectCallAttr.asMap().entrySet()) {
                            String key = entry.getKey().toString();
                            PyObject value = entry.getValue();
                            if ("requirements".equals(key)) {
                                try {
                                    java.util.List<PyObject> values = value.asList();
                                    map.put(key, values.stream().map(Object::toString).collect(Collectors.joining("\n")));
                                    continue;
                                } catch (Throwable ignored) {}
                            }
                            map.put(key, value.toString());
                        }
                    }
                    metadataCache.put(cacheKey, new MetadataCacheEntry(file, map));
                } catch (PyException e) {
                    // empty metadata so the caller can fall through to its
                    FileLog.e("Failed to parse metadata from " + str + ". Error: " + e.getMessage(), e);
                }
            }
        }
        return map;
    }

    @Override
    public Object getPluginSetting(String str, String str2, Object obj) {
        if (RECOVERY_BLOCKED_PLUGIN_IDS.contains(str)) {
            return obj;
        }
        synchronized (settingsReloadLock) {
            Object java2;
            ConcurrentHashMap<String, Object> concurrentHashMap = this.settingsCache.get(str);
            if (concurrentHashMap != null && concurrentHashMap.containsKey(str2)) return concurrentHashMap.get(str2);
            if (getPython() != null) {
                for (int attempt = 0; attempt < 2; attempt++) try {
                AtomicLong generation = settingsCacheGenerations.computeIfAbsent(str, ignored -> new AtomicLong());
                long readGeneration = generation.get();
                PyObject pyObjectCallAttr = getPython().getModule("plugin_settings").callAttr("get_setting", str, str2, obj);
                if (pyObjectCallAttr != null && pyObjectCallAttr.toJava(Object.class) != null) {
                    if (obj instanceof Boolean) {
                        java2 = Boolean.valueOf(pyObjectCallAttr.toBoolean());
                    } else if (obj instanceof Integer) {
                        try {
                            java2 = Integer.valueOf(pyObjectCallAttr.toInt());
                        } catch (RuntimeException convErr) {
                            int parsed;
                            try {
                                parsed = (int) Math.round(Double.parseDouble(pyObjectCallAttr.toString().trim()));
                            } catch (Exception ignored) {
                                parsed = (Integer) obj;
                            }
                            java2 = Integer.valueOf(parsed);
                        }
                    } else if (obj instanceof String) {
                        java2 = pyObjectCallAttr.toString();
                    } else if (obj instanceof Float) {
                        java2 = Float.valueOf(pyObjectCallAttr.toFloat());
                    } else if (obj instanceof Long) {
                        java2 = Long.valueOf(pyObjectCallAttr.toLong());
                    } else {
                        java2 = pyObjectCallAttr.toJava(obj.getClass());
                    }
                    if (java2 != null) {
                        if (generation.get() == readGeneration) {
                            this.settingsCache.computeIfAbsent(str, k -> new ConcurrentHashMap<>()).put(str2, java2);
                            if (generation.get() == readGeneration) return java2;
                            ConcurrentHashMap<String, Object> raced = settingsCache.get(str);
                            if (raced != null) raced.remove(str2, java2);
                        }
                        continue;
                    }
                }
                } catch (RuntimeException e) {
                FileLog.e("Failed to get plugin setting " + str + "/" + str2, e);
                    return obj;
                }
            }
            return obj;
        }
    }

    public void invalidatePluginSettingCache(String pluginId, String key) {
        synchronized (settingsReloadLock) {
            settingsCacheGenerations.computeIfAbsent(pluginId, ignored -> new AtomicLong()).incrementAndGet();
            ConcurrentHashMap<String, Object> cache = settingsCache.get(pluginId);
            if (cache == null) return;
            if (key == null) settingsCache.remove(pluginId);
            else cache.remove(key);
        }
    }

    public boolean reloadPortablePluginSettings() {
        synchronized (settingsReloadLock) {
            for (AtomicLong generation : settingsCacheGenerations.values()) generation.incrementAndGet();
            settingsCache.clear();
            if (getPython() == null) return true;
            try {
                PyObject status = getPython().getModule("plugin_settings").callAttr("reload_settings");
                if (status == null || !status.toBoolean()) {
                    FileLog.e("Python plugin settings reload returned a non-success status");
                    for (AtomicLong generation : settingsCacheGenerations.values()) generation.incrementAndGet();
                    settingsCache.clear();
                    return false;
                }
                for (AtomicLong generation : settingsCacheGenerations.values()) generation.incrementAndGet();
                settingsCache.clear();
                return true;
            } catch (Throwable e) {
                for (AtomicLong generation : settingsCacheGenerations.values()) generation.incrementAndGet();
                settingsCache.clear();
                FileLog.e("Failed to reload imported plugin settings", e);
                return false;
            }
        }
    }

    public <T> T withPortablePluginSettingsTransaction(
            PluginsController.PortablePluginSettingsTransaction<T> transaction) throws Exception {
        synchronized (settingsReloadLock) {
            PyObject settingsModule = null;
            boolean pythonLocked = false;
            try {
                Python current = getPython();
                if (current != null) {
                    settingsModule = current.getModule("plugin_settings");
                    PyObject acquired = settingsModule.callAttr("begin_host_transaction");
                    if (acquired == null || !acquired.toBoolean()) {
                        throw new IllegalStateException("Could not lock Python plugin settings");
                    }
                    pythonLocked = true;
                }
                return transaction.run();
            } finally {
                if (pythonLocked) {
                    try {
                        settingsModule.callAttr("end_host_transaction");
                    } catch (Throwable unlockFailure) {
                        FileLog.e("Failed to unlock Python plugin settings transaction", unlockFailure);
                    }
                }
            }
        }
    }

    public static List<String> parseRequirements(String raw) {
        ArrayList<String> result = new ArrayList<>();
        if (raw == null) return result;
        String normalized = raw.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            Matcher matcher = SAFE_MODE_LIST_STRING_PATTERN.matcher(
                    normalized.substring(1, normalized.length() - 1));
            while (matcher.find()) {
                String value = matcher.group(2).trim();
                if (!value.isEmpty()) result.add(value);
            }
            if (!result.isEmpty()) return result;
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        StringBuilder token = new StringBuilder();
        char quote = 0;
        int squareDepth = 0;
        for (int i = 0; i <= normalized.length(); i++) {
            char c = i < normalized.length() ? normalized.charAt(i) : '\n';
            if ((c == '\'' || c == '"') && (i == 0 || normalized.charAt(i - 1) != '\\')) {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
            }
            if (quote == 0 && c == '[') squareDepth++;
            else if (quote == 0 && c == ']' && squareDepth > 0) squareDepth--;
            boolean delimiter = quote == 0 && (c == '\n' || c == '\r');
            if (quote == 0 && squareDepth == 0 && c == ',') {
                int next = i + 1;
                while (next < normalized.length() && Character.isWhitespace(normalized.charAt(next))) next++;
                char nextChar = next < normalized.length() ? normalized.charAt(next) : 0;
                delimiter = "<>=!~".indexOf(nextChar) < 0;
            }
            if (delimiter) {
                String value = token.toString().trim();
                if (value.length() >= 2
                        && ((value.startsWith("'") && value.endsWith("'"))
                        || (value.startsWith("\"") && value.endsWith("\"")))) {
                    value = value.substring(1, value.length() - 1).trim();
                }
                if (!value.isEmpty()) result.add(value);
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        return result;
    }

    @Override
    public void setPluginSetting(String str, String str2, Object obj) {
        if (RECOVERY_BLOCKED_PLUGIN_IDS.contains(str)) {
            invalidatePluginSettingCache(str, str2);
            return;
        }
        synchronized (settingsReloadLock) {
            if (getPython() == null) {
                invalidatePluginSettingCache(str, str2);
                return;
            }
            try {
                PyObject saved = getPython().getModule("plugin_settings")
                        .callAttr("set_setting", str, str2, obj);
                if (saved == null || !saved.toBoolean()) {
                    invalidatePluginSettingCache(str, str2);
                    FileLog.e("Plugin setting write was rejected: "
                            + str + "/" + str2);
                    return;
                }
                ConcurrentHashMap<String, Object> pluginCache =
                        this.settingsCache.computeIfAbsent(
                                str, k -> new ConcurrentHashMap<>());
                if (obj == null) {
                    pluginCache.remove(str2);
                } else {
                    pluginCache.put(str2, obj);
                }
            } catch (RuntimeException e) {
                invalidatePluginSettingCache(str, str2);
                FileLog.e("Failed to set plugin setting " + str + "/" + str2, e);
            }
        }
    }

    @Override
    public boolean clearPluginSettings(String str) {
        if (RECOVERY_BLOCKED_PLUGIN_IDS.contains(str)) {
            return false;
        }
        synchronized (settingsReloadLock) {
            if (getPython() == null) {
                return false;
            }
            try {
                PyObject result = getPython().getModule("plugin_settings")
                        .callAttr("clear_settings", str);
                if (result == null || !result.toBoolean()) {
                    FileLog.e("Plugin settings clear was rejected for " + str);
                    return false;
                }
                settingsCacheGenerations
                        .computeIfAbsent(str, ignored -> new AtomicLong())
                        .incrementAndGet();
                this.settingsCache.remove(str);
                return true;
            } catch (Throwable e) {
                FileLog.e("Failed to clear plugin settings for " + str, e);
                return false;
            }
        }
    }

    @Override
    public java.util.Map<String, ?> getAllPluginSettings(String str) {
        if (RECOVERY_BLOCKED_PLUGIN_IDS.contains(str)) {
            return Collections.emptyMap();
        }
        synchronized (settingsReloadLock) {
            if (getPython() == null) {
                return null;
            }
            try {
                PyObject pyObjectCallAttr = getPython().getModule("plugin_settings").callAttr("get_all_settings", str);
                if (pyObjectCallAttr != null) {
                    HashMap<String, Object> map = new HashMap<>();
                    for (Map.Entry<PyObject, PyObject> entry : pyObjectCallAttr.asMap().entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            Object value = entry.getValue().toJava(Object.class);
                            if (value != null) {
                                map.put(entry.getKey().toString(), value);
                            }
                        }
                    }
                    this.settingsCache.put(str, new ConcurrentHashMap<>(map));
                    return map;
                }
            } catch (PyException e) {
                FileLog.e("Failed to get all plugin settings for " + str, e);
            }
            return null;
        }
    }

    @Override
    public void showInstallDialog(final BaseFragment baseFragment, InstallPluginBottomSheet.PluginInstallParams pluginInstallParams) {
        File file = new File(pluginInstallParams.filePath);
        final String strFetchParameterValue = fetchParameterValue(pluginInstallParams.filePath, "name");
        final String displayName = (TextUtils.isEmpty(strFetchParameterValue) && file.exists()) ? file.getName() : strFetchParameterValue;

        AuthorizedCandidate authorizedCandidate = null;
        String validationError = null;
        try {
            authorizedCandidate = stageAuthorizedCandidate(
                    pluginInstallParams.filePath, null);
        } catch (Throwable failure) {
            validationError = installFailureMessage(failure);
        }
        final PluginsController.PluginValidationResult pluginValidationResultValidatePluginFromFile =
                new PluginsController.PluginValidationResult(
                        authorizedCandidate != null
                                ? authorizedCandidate.plugin : null,
                        validationError);
        if (pluginValidationResultValidatePluginFromFile.plugin != null) {
            HostInstallTicket ticket = null;
            boolean sheetShown = false;
            try {
                com.exteragram.messenger.plugins.PluginsController.PluginValidationResult exteraResult =
                        new com.exteragram.messenger.plugins.PluginsController.PluginValidationResult(
                                pluginValidationResultValidatePluginFromFile.plugin,
                                pluginValidationResultValidatePluginFromFile.error);
                com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams exteraParams =
                        new com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams(
                                pluginInstallParams.filePath, !pluginInstallParams.incompatible);
                final InstallPluginBottomSheet sheet =
                        new com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet(
                                baseFragment, exteraResult,
                                exteraParams);
                ticket = issueHostInstallTicket(
                        authorizedCandidate);
                if (!ticket.bind()
                        || !sheet.bindHostInstallAuthority(ticket)) {
                    throw new SecurityException(
                            "Could not bind host install authority");
                }
                if (baseFragment.showDialog(
                        sheet,
                        dialog -> sheet.onHostFragmentTeardown())
                        != sheet) {
                    throw new IllegalStateException(
                            "Install fragment is no longer active");
                }
                sheetShown = true;
            } catch (Throwable failure) {
                FileLog.e("Could not show authorized plugin install sheet",
                        failure);
            } finally {
                if (!sheetShown) {
                    if (ticket != null) {
                        forceRevokeHostInstallTicket(ticket);
                    } else if (authorizedCandidate != null) {
                        authorizedCandidate.cleanup();
                    }
                }
            }
            if (!sheetShown) {
                AndroidUtilities.runOnUIThread(() ->
                        BulletinFactory.of(baseFragment)
                                .createSimpleBulletin(
                                        R.raw.error,
                                        AndroidUtilities.replaceTags(
                                                LocaleController.formatString(
                                                        R.string.PluginInstallError,
                                                        displayName)))
                                .show());
            }
        } else {
            AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(baseFragment).createSimpleBulletin(R.raw.error, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginInstallError, displayName)), LocaleUtils.createCopySpan(baseFragment), () -> {
                if (AndroidUtilities.addToClipboard(pluginValidationResultValidatePluginFromFile.error)) {
                    BulletinFactory.of(baseFragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                }
            }).show());
        }
    }

    @Override
    public void openPluginSettings(String str, BaseFragment baseFragment) {
        Plugin plugin = getPluginsController().plugins.get(str);
        if (plugin != null) {
            openPluginSettings(plugin, baseFragment);
        }
    }

    @Override
    public void openPluginSettings(final Plugin plugin, final BaseFragment baseFragment) {
        if (plugin == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> baseFragment.presentFragment(new PluginSettingsActivity(plugin)));
    }

    @Override
    public void openPluginSetting(final Plugin plugin, final String str, final BaseFragment baseFragment) {
        if (plugin == null) {
            return;
        }
        Utilities.pluginsQueue.postRunnable(() -> {
            final PluginSettingsActivity pluginSettingsActivity;
            PluginsController.PluginRuntimeToken presentationToken =
                    getPluginsController().getCurrentPluginRuntime(plugin.getId());
            FileLog.d("Opening plugin setting: " + plugin.getId() + "/" + str);
            if (str == null || !str.contains(":")) {
                pluginSettingsActivity = new PluginSettingsActivity(plugin, str);
            } else {
                List<SettingItem> list = getPluginsController().settings.get(plugin.getId());
                if (list == null) {
                    return;
                }
                String[] strArrSplit = str.split(":");
                TextSetting textSetting = null;
                List<SettingItem> pySettingDefinitions = list;
                for (int i = 0; i < strArrSplit.length - 1; i++) {
                    String str2 = strArrSplit[i];
                    textSetting = null;
                    Iterator<SettingItem> it = pySettingDefinitions.iterator();
                    while (true) {
                        if (!it.hasNext()) {
                            break;
                        }
                        SettingItem next = it.next();
                        if (next instanceof TextSetting) {
                            TextSetting textSetting2 = (TextSetting) next;
                            if (str2.equals(textSetting2.linkAlias)) {
                                if (textSetting2.createSubFragmentCallback == null) {
                                    return;
                                }
                                PluginsController.PluginRuntimeToken callbackToken =
                                        textSetting2.runtimeToken;
                                boolean entered = callbackToken == null
                                        ? getPluginsController().isPluginActive(plugin.getId())
                                        : getPluginsController()
                                                .getPluginRuntimeTaskDecision(callbackToken)
                                                == PluginsController.RUNTIME_TASK_RUN
                                                && getPluginsController()
                                                        .enterPluginRuntime(callbackToken);
                                if (!entered) return;
                                try {
                                    getPluginsController().getWatchdog().onPluginExecutionStarted(plugin.getId());
                                    PyObject pyObjectCall;
                                    try {
                                        pyObjectCall = textSetting2.createSubFragmentCallback.call();
                                    } catch (Error failure) {
                                        getPluginsController().getWatchdog()
                                                .onPluginExecutionFailed(plugin.getId(), failure);
                                        throw failure;
                                    } finally {
                                        getPluginsController().getWatchdog().onPluginExecutionFinished(plugin.getId());
                                    }
                                    if (pyObjectCall != null) {
                                        pySettingDefinitions = parsePySettingDefinitions(
                                                pyObjectCall.asList(), callbackToken);
                                    } else {
                                        return;
                                    }
                                } catch (Exception unused) {
                                    return;
                                } finally {
                                    if (callbackToken != null) {
                                        getPluginsController()
                                                .exitPluginRuntime(callbackToken);
                                    }
                                }
                                textSetting = textSetting2;
                                break;
                            }
                        }
                    }
                    if (textSetting == null) {
                        return;
                    }
                }
                if (textSetting == null) {
                    return;
                } else {
                    String prefix = TextUtils.join(":", Arrays.copyOf(strArrSplit, strArrSplit.length - 1));
                    presentationToken = textSetting.runtimeToken;
                    pluginSettingsActivity = new PluginSettingsActivity(plugin, textSetting.text, pySettingDefinitions, textSetting.createSubFragmentCallback, strArrSplit[strArrSplit.length - 1]).setSettingsLinkPrefix(prefix);
                }
            }
            final PluginsController.PluginRuntimeToken screenToken = presentationToken;
            AndroidUtilities.runOnUIThread(() -> {
                if (baseFragment.getParentActivity() == null
                        || baseFragment.getFragmentView() == null
                        || (screenToken == null
                            ? !getPluginsController().isPluginActive(plugin.getId())
                            : getPluginsController()
                                    .getPluginRuntimeTaskDecision(screenToken)
                                    != PluginsController.RUNTIME_TASK_RUN)) {
                    return;
                }
                baseFragment.presentFragment(pluginSettingsActivity);
                pluginSettingsActivity.checkTargetSetting();
            });
        });
    }

    @Override
    public void openPluginSetting(String str, String str2, BaseFragment baseFragment) {
        Plugin plugin = getPluginsController().plugins.get(str);
        if (plugin != null) {
            openPluginSetting(plugin, str2, baseFragment);
        }
    }

    public void setDebuggerListener(PyObject pyObject) {
        this.debuggerListener = pyObject;
    }

    public static final class Updater {
        private static final Updater INSTANCE = new Updater();
        private int status = 0; 
        private boolean notifyWhenChangeStatus = false;
        private boolean sdkFromApk = true;

        public static Updater getInstance() { return INSTANCE; }

        public int getStatus() { return status; }

        public void setStatus(int value) { this.status = value; }

        public boolean getNotifyWhenChangeStatus() { return notifyWhenChangeStatus; }

        public void setNotifyWhenChangeStatus(boolean value) { this.notifyWhenChangeStatus = value; }

        public boolean isSdkFromApk() { return sdkFromApk; }

        public void setBuildFromApk(boolean fromApk) { this.sdkFromApk = fromApk; }

        public String getVersion() { return ""; }

        public String getStateString() {
            return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PluginsPySdkOffline);
        }

        public boolean checkUpdates() { return false; }

        public boolean checkUpdates(boolean force) { return false; }

        public boolean restoreSdkFromApk() { return false; }

        public boolean deleteSdkUpdateFile() { return false; }

        public boolean isAvailable() { return false; }
    }
}
