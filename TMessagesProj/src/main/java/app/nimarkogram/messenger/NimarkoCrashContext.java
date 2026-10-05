/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger;
import android.os.Build;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
public final class NimarkoCrashContext {
    public enum PineInitPhase {
        HIDDEN_API_BEGIN, HIDDEN_API_END,
        CONFIG_BEGIN, CONFIG_END,
        ENGINE_BEGIN, ENGINE_END,
        HOOK_MODE_BEGIN, HOOK_MODE_END,
        STATIC_PREPARE, STATIC_HOOK_BEGIN, STATIC_HOOK_END,
        STATIC_CALL_BEGIN, STATIC_CALL_END,
        MIXED_PREPARE, MIXED_HOOK_BEGIN, MIXED_HOOK_END,
        MIXED_CALL_BEGIN, MIXED_CALL_END,
        CONSTRUCTOR_PREPARE, CONSTRUCTOR_HOOK_BEGIN, CONSTRUCTOR_HOOK_END,
        CONSTRUCTOR_CALL_BEGIN, CONSTRUCTOR_CALL_END,
        CONSTRUCTOR_UNHOOK_BEGIN, CONSTRUCTOR_UNHOOK_END,
        MIXED_UNHOOK_BEGIN, MIXED_UNHOOK_END,
        STATIC_UNHOOK_BEGIN, STATIC_UNHOOK_END
    }
    private static final int MAX_VALUE_LENGTH = 160;
    private static final long MIN_UPDATE_INTERVAL_MS = 250L;
    private static final long INIT_RETRY_INTERVAL_MS = 5000L;
    private static final int MAX_INIT_ATTEMPTS = 5;
    private static final long FAILURE_WINDOW_MS = 10000L;
    private static final int MAX_FAILURES = 4;
    private static final class DiagnosticState {
        boolean busy;
        long invocation;
        long startedMs;
    }
    private static final ThreadLocal<DiagnosticState> diagnosticState =
            new ThreadLocal<DiagnosticState>() {
                @Override
                protected DiagnosticState initialValue() { return new DiagnosticState(); }
            };

    private static DiagnosticState enterDiagnostic() {
        DiagnosticState state = diagnosticState.get();
        if (state.busy) return null;
        state.busy = true;
        return state;
    }
    private static volatile Object crashlytics;
    private static volatile Method setCustomKey;
    private static volatile Method log;
    private static volatile boolean initialized;
    private static final AtomicLong nextUpdateMs = new AtomicLong();
    private static final AtomicLong nextInvocationMs = new AtomicLong();
    private static final AtomicLong invocationIds = new AtomicLong();
    private static final ReentrantLock outputLock = new ReentrantLock();
    private static long outputWindowMs;
    private static int outputCount;
    private static long anomalyWindowMs;
    private static int anomalyCount;
    private static boolean runtimeIdentityWritten;
    private static boolean diagnosticIdentityWritten;
    private static final String[] workerSnapshotKeys = {
            "ng_diag_worker_0", "ng_diag_worker_1", "ng_diag_worker_2"
    };
    private static boolean pineInitStarted;
    private static boolean pineInitActive;
    private static long pineInitThread;
    private static long pineInitStartedMs;
    private static long pineInitPhases;
    private static long nextInitAttemptMs;
    private static int initAttempts;
    private static boolean emitting;
    private static final String[] failureKeys = new String[MAX_FAILURES];
    private static final long[] failureTimes = new long[MAX_FAILURES];
    private NimarkoCrashContext() {
    }
    public static void pine(String stage, String target) {
        DiagnosticState state = enterDiagnostic();
        if (state == null) return;
        try {
            if (!admitNormal()) return;
            emit("pine", null, stage, target, null, 0, "", false);
        } catch (Throwable ignored) {
        } finally {
            state.busy = false;
        }
    }

    public static void python(String pluginId, String callback, int argumentCount) {
        DiagnosticState state = enterDiagnostic();
        if (state == null) return;
        try {
            if (!admitNormal()) return;
            emit("python", pluginId, "callback", callback, "argc=" + argumentCount, 0, "", false);
        } catch (Throwable ignored) {
        } finally {
            state.busy = false;
        }
    }

    public static void failure(String domain, String pluginId, String target, Throwable failure) {
        DiagnosticState state = enterDiagnostic();
        if (state == null) return;
        try {
            failureGuarded(domain, pluginId, target, failure);
        } catch (Throwable ignored) {
        } finally {
            state.busy = false;
        }
    }

    private static void failureGuarded(String domain, String pluginId, String target, Throwable failure) {
        if (!outputLock.tryLock()) return;
        try {
            failureLocked(domain, pluginId, target, failure);
        } catch (Throwable ignored) {
        } finally {
            outputLock.unlock();
        }
    }

    private static void failureLocked(String domain, String pluginId, String target, Throwable failure) {
        if (emitting) return;
        long now = android.os.SystemClock.elapsedRealtime();
        int slot = -1;
        for (int i = 0; i < MAX_FAILURES; i++) {
            if (failureKeys[i] == null || now - failureTimes[i] >= FAILURE_WINDOW_MS) {
                slot = i;
            }
        }
        if (slot < 0 || !ensure()) return;
        String safeDomain = safe(domain);
        String safePlugin = safe(pluginId);
        String safeTarget = safe(target);
        String detail = failure == null ? "" : safe(failure.getClass().getName());
        String key = safeDomain.length() + ":" + safeDomain
                + safePlugin.length() + ":" + safePlugin
                + safeTarget.length() + ":" + safeTarget + detail;
        for (int i = 0; i < MAX_FAILURES; i++) {
            if (now - failureTimes[i] < FAILURE_WINDOW_MS && key.equals(failureKeys[i])) return;
        }
        failureKeys[slot] = key;
        failureTimes[slot] = now;
        mark(safeDomain, safePlugin, "failure", safeTarget, detail, 0, "", "exact_observation_bounded");
    }
    private static boolean admitNormal() {
        long now = android.os.SystemClock.elapsedRealtime();
        long next = nextUpdateMs.get();
        return now >= next && nextUpdateMs.compareAndSet(next, now + MIN_UPDATE_INTERVAL_MS);
    }

    public static long beginInvocation() {
        DiagnosticState state = enterDiagnostic();
        if (state == null) return 0L;
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            long next = nextInvocationMs.get();
            if (now < next || !nextInvocationMs.compareAndSet(next, now + 1000L)) return 0L;
            state.startedMs = now;
            state.invocation = invocationIds.incrementAndGet();
            return state.invocation;
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            state.busy = false;
        }
    }

    public static long invocationStartedMs(long token) {
        DiagnosticState state = diagnosticState.get();
        return !state.busy && token != 0 && token == state.invocation ? state.startedMs : 0L;
    }

    public static void phase(long token, long startedMs, String phase, String target, String plugin, String runtime) {
        if (token == 0) return;
        DiagnosticState state = enterDiagnostic();
        if (state == null) return;
        try {
            emit("pine", plugin, phase, target,
                    "elapsed_ms=" + Math.max(0L, android.os.SystemClock.elapsedRealtime() - startedMs),
                    token, runtime, false);
        } catch (Throwable ignored) {
        } finally {
            state.busy = false;
        }
    }

    public static void anomaly(String phase, String target, String plugin, String runtime) {
        DiagnosticState state = enterDiagnostic();
        if (state == null) return;
        try {
            emit("pine", plugin, phase, target, null, 0, runtime, true);
        } catch (Throwable ignored) {
        } finally {
            state.busy = false;
        }
    }

    public static void pythonPhase(long token, long startedMs, String phase,
                                   String target, String plugin, String runtime) {
        if (token == 0) return;
        DiagnosticState state = enterDiagnostic();
        if (state == null) return;
        try {
            emit("python", plugin, phase, target,
                    "elapsed_ms=" + Math.max(0L, android.os.SystemClock.elapsedRealtime() - startedMs),
                    token, runtime, false);
        } catch (Throwable ignored) {
        } finally {
            state.busy = false;
        }
    }
    private static void emit(String domain, String plugin, String phase, String target,
            String detail, long token, String runtime, boolean anomaly) {
        if (!outputLock.tryLock()) return;
        try {
            if (emitting) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (anomaly) {
                if (now - anomalyWindowMs >= FAILURE_WINDOW_MS) {
                    anomalyWindowMs = now;
                    anomalyCount = 0;
                }
                if (anomalyCount >= MAX_FAILURES) return;
                anomalyCount++;
            } else {
                if (now - outputWindowMs >= 1000L) {
                    outputWindowMs = now;
                    outputCount = 0;
                }
                if (outputCount >= 8) return;
                outputCount++;
            }
            if (ensure()) mark(domain, plugin, phase, target, detail, token, runtime,
                    anomaly ? "exact_observation_bounded" : "sampled_observation");
        } catch (Throwable ignored) {
        } finally {
            outputLock.unlock();
        }
    }

    public static void initialization(boolean started, boolean ready) {
        DiagnosticState state = enterDiagnostic();
        if (state == null) return;
        try {
            initializationGuarded(started, ready);
        } catch (Throwable ignored) {
        } finally {
            state.busy = false;
        }
    }

    private static void initializationGuarded(boolean started, boolean ready) {
        if (!outputLock.tryLock()) return;
        try {
            if (emitting) return;
            if (started) {
                if (pineInitStarted) return;
                long startedMs = android.os.SystemClock.elapsedRealtime();
                pineInitStarted = true;
                pineInitActive = true;
                pineInitThread = Thread.currentThread().getId();
                pineInitStartedMs = startedMs;
            } else {
                if (!pineInitActive || pineInitThread != Thread.currentThread().getId()) return;
                pineInitActive = false;
            }
            if (!ensure()) return;
            emitting = true;
            try {
                setCustomKey.invoke(crashlytics, "ng_pine_init_state",
                        started ? "started" : ready ? "ready" : "failed");
                setCustomKey.invoke(crashlytics, "ng_pine_init_native_tid",
                        String.valueOf(android.os.Process.myTid()));
            } finally {
                emitting = false;
            }
            if (!runtimeIdentityWritten) {
                runtimeIdentityWritten = true;
                emitting = true;
                try {
                    setCustomKey.invoke(crashlytics, "ng_diag_runtime_api", String.valueOf(Build.VERSION.SDK_INT));
                    setCustomKey.invoke(crashlytics, "ng_diag_runtime_abis", safe(java.util.Arrays.toString(Build.SUPPORTED_ABIS)));
                    setCustomKey.invoke(crashlytics, "ng_diag_runtime_fingerprint", safe(Build.FINGERPRINT));
                } finally {
                    emitting = false;
                }
            }
            mark("pine", null, started ? "initialization_started" : "initialization_completed",
                    "ApplicationLoader.ensurePineInited", started ? "" : (ready ? "ready" : "not_ready"),
                    0, "", "exact_observation_bounded");
        } catch (Throwable ignored) {
        } finally {
            outputLock.unlock();
        }
    }
    public static void initializationPhase(PineInitPhase phase) {
        if (phase == null) return;
        DiagnosticState state = enterDiagnostic();
        if (state == null) return;
        try {
            if (!outputLock.tryLock()) return;
            try {
                if (emitting || !pineInitActive
                        || pineInitThread != Thread.currentThread().getId()) return;
                long bit = 1L << phase.ordinal();
                if ((pineInitPhases & bit) != 0 || !ensure()) return;
                pineInitPhases |= bit;
                String elapsed = String.valueOf(Math.max(0L,
                        android.os.SystemClock.elapsedRealtime() - pineInitStartedMs));
                emitting = true;
                try {
                    setCustomKey.invoke(crashlytics, "ng_pine_init_phase", phase.name());
                    setCustomKey.invoke(crashlytics, "ng_pine_init_elapsed_ms", elapsed);
                    log.invoke(crashlytics, "ng_pine_init phase=" + phase.name()
                            + " native_tid=" + android.os.Process.myTid()
                            + " elapsed_ms=" + elapsed);
                } finally {
                    emitting = false;
                }
            } finally {
                outputLock.unlock();
            }
        } catch (Throwable ignored) {
        } finally {
            state.busy = false;
        }
    }
    private static void mark(String domain, String pluginId, String stage,
            String target, String detail, long token, String runtime, String observation) {
        String safeDomain = safe(domain);
        String safePlugin = safe(pluginId);
        String safeStage = safe(stage);
        String safeTarget = safe(target);
        String safeDetail = safe(detail);
        String safeRuntime = safe(runtime);
        Thread thread = Thread.currentThread();
        String threadId = String.valueOf(thread.getId());
        String nativeTid = String.valueOf(android.os.Process.myTid());
        String threadName = android.os.Looper.getMainLooper().getThread() == thread ? "main" : "worker";
        String invocation = String.valueOf(token);
        emitting = true;
        try {
            if (!diagnosticIdentityWritten) {
                setCustomKey.invoke(crashlytics, "ng_diag_schema", "3");
                setCustomKey.invoke(crashlytics, "ng_diag_mapping_id", readMappingId());
                diagnosticIdentityWritten = true;
            }
            String snapshot = "observation=" + observation
                    + " uptime_ms=" + android.os.SystemClock.elapsedRealtime()
                    + " native_tid=" + nativeTid + " java_tid=" + threadId
                    + " invocation=" + invocation + " domain=" + safeDomain
                    + " stage=" + safeStage + " plugin=" + safePlugin
                    + " runtime=" + safeRuntime + " target=" + safeTarget
                    + " detail=" + safeDetail;
            if (snapshot.length() > 1000) snapshot = snapshot.substring(0, 1000);
            String snapshotKey = "main".equals(threadName) ? "ng_diag_main"
                    : workerSnapshotKeys[(int) (thread.getId() % workerSnapshotKeys.length)];
            setCustomKey.invoke(crashlytics, snapshotKey, snapshot);
            if ("failure".equals(safeStage)) {
                setCustomKey.invoke(crashlytics, "ng_diag_last_failure", snapshot);
            }
            setCustomKey.invoke(crashlytics, "ng_diag_domain", safeDomain);
            setCustomKey.invoke(crashlytics, "ng_diag_plugin", safePlugin);
            setCustomKey.invoke(crashlytics, "ng_diag_stage", safeStage);
            setCustomKey.invoke(crashlytics, "ng_diag_target", safeTarget);
            setCustomKey.invoke(crashlytics, "ng_diag_detail", safeDetail);
            setCustomKey.invoke(crashlytics, "ng_diag_sdk", String.valueOf(Build.VERSION.SDK_INT));
            setCustomKey.invoke(crashlytics, "ng_diag_observation", observation);
            setCustomKey.invoke(crashlytics, "ng_diag_thread_id", threadId);
            setCustomKey.invoke(crashlytics, "ng_diag_native_tid", nativeTid);
            setCustomKey.invoke(crashlytics, "ng_diag_thread_name", threadName);
            setCustomKey.invoke(crashlytics, "ng_diag_invocation", invocation);
            setCustomKey.invoke(crashlytics, "ng_diag_runtime_owner", safeRuntime);
            log.invoke(crashlytics, "ng_diag domain=" + safeDomain
                    + " stage=" + safeStage + " target=" + safeTarget
                    + " observation=" + observation + " tid=" + threadId
                    + " native_tid=" + nativeTid
                    + " thread=" + threadName + " invocation=" + invocation
                    + " runtime=" + safeRuntime
                    + (safePlugin.isEmpty() ? "" : " plugin=" + safePlugin)
                    + (safeDetail.isEmpty() ? "" : " detail=" + safeDetail));
        } catch (Throwable ignored) {
        } finally {
            emitting = false;
        }
    }
    private static boolean ensure() {
        if (initialized) return crashlytics != null;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < nextInitAttemptMs) return false;
        nextInitAttemptMs = now + INIT_RETRY_INTERVAL_MS;
        initAttempts++;
        emitting = true;
        try {
            Class<?> type = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics");
            Method keyMethod = type.getMethod("setCustomKey", String.class, String.class);
            Method logMethod = type.getMethod("log", String.class);
            Object instance = type.getMethod("getInstance").invoke(null);
            setCustomKey = keyMethod;
            log = logMethod;
            crashlytics = instance;
            initialized = true;
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : null;
            boolean notReady = cause instanceof IllegalStateException
                    && cause.getMessage() != null
                    && cause.getMessage().startsWith("Default FirebaseApp is not initialized in this process");
            initialized = !notReady || initAttempts >= MAX_INIT_ATTEMPTS;
        } finally {
            emitting = false;
        }
        return crashlytics != null;
    }
    private static String readMappingId() {
        try {
            android.content.Context context = org.telegram.messenger.ApplicationLoader.applicationContext;
            if (context == null) return "unavailable";
            android.content.res.Resources resources = context.getResources();
            int id = resources.getIdentifier("com.google.firebase.crashlytics.mapping_file_id",
                    "string", context.getPackageName());
            if (id != 0) return safe(resources.getString(id));
        } catch (Throwable ignored) {
        }
        return "unavailable";
    }
    private static String safe(String value) {
        if (value == null) return "";
        String result = value.replace('\n', ' ').replace('\r', ' ');
        return result.length() <= MAX_VALUE_LENGTH
                ? result : result.substring(0, MAX_VALUE_LENGTH);
    }
}
