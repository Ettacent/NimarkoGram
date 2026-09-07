// Node.js + JDK only. Deadline methods and foreground listener are extracted unchanged.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const javaRoot = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(javaRoot,
    'app/nimarkogram/messenger/plugins/PluginsController.java'), 'utf8');

function member(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, `Missing production member: ${signature}`);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(`Unterminated member: ${signature}`);
}

const fields = source.slice(source.indexOf('    private final Object controllerLifecycleLock'),
    source.indexOf('    public app.nimarkogram.messenger.plugins.utils.PluginsWatchdog getWatchdog()'));
assert(fields.includes('initializationForegroundListener'), 'Extract the real foreground/background listener');
const methods = [
    'public void init(final boolean startWithSafeMode, final Runnable runnable)',
    'public long getInitializationAttempt()',
    'public void reportInitializationProgress(long attempt)',
    'private void checkControllerInitializationDeadline(long attempt)',
    'private void timeoutControllerInitialization(long attempt)',
    'private void failControllerInitialization(long attempt, String reason)',
    'private boolean finishControllerInitialization(\n',
    'private boolean finishControllerInitialization(long attempt, boolean success, String reason)',
].map(member).join('\n');

const harness = `
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import app.nimarkogram.messenger.utils.AppRestartHelper;

public class PluginStartupDeadlineHarness {
    ${source.match(/private static final long ENGINE_INIT_TIMEOUT_MS\s*=[^;]+;/)[0]}
    ${fields}
    ${source.match(/public volatile boolean initialized\s*=[^;]+;/)[0]}
    final Watchdog watchdog = new Watchdog();
    final DispatchQueue queue = new DispatchQueue();
    int engineStarts;
    ${methods}
    // Engine/Binder/Python work is intentionally out of scope; init scheduling is real.
    boolean isPluginEngineSupported() { return true; }
    DispatchQueue getOrCreatePluginsQueue() { return queue; }
    void startControllerInitialization(boolean safeMode, long attempt) { engineStarts++; }

    static class SystemClock {
        static volatile long now;
        static int reads, hookRead;
        static Runnable hook;
        static long elapsedRealtime() {
            reads++;
            if (hook != null && reads == hookRead) {
                Runnable work = hook; hook = null; work.run();
            }
            return now;
        }
    }
    static class AndroidUtilities {
        static class Task {
            final long at; final Runnable work;
            Task(long at, Runnable work) { this.at = at; this.work = work; }
        }
        static final List<Task> tasks = new ArrayList<>();
        static synchronized void runOnUIThread(Runnable work, long delay) {
            check(delay >= 0, "non-negative handler delay"); tasks.add(new Task(SystemClock.now + delay, work));
        }
        static void runOnUIThread(Runnable work) { runOnUIThread(work, 0); }
        static void runDue() {
            for (int count = 0; count < 1000; count++) {
                Task task = tasks.stream().filter(t -> t.at <= SystemClock.now)
                    .min(Comparator.comparingLong(t -> t.at)).orElse(null);
                if (task == null) return;
                tasks.remove(task); task.work.run();
            }
            throw new AssertionError("handler reschedule loop");
        }
        static long nextAt() { return tasks.stream().mapToLong(t -> t.at).min().orElse(-1); }
    }
    static class ForegroundDetector {
        interface Listener { void onBecameForeground(); void onBecameBackground(); }
        static final ForegroundDetector instance = new ForegroundDetector();
        final List<Listener> listeners = new ArrayList<>();
        static ForegroundDetector getInstance() { return instance; }
        void addListener(Listener listener) { listeners.add(listener); }
        void transition(boolean background) {
            ApplicationLoader.mainInterfacePaused = background;
            for (Listener listener : new ArrayList<>(listeners)) {
                if (background) listener.onBecameBackground(); else listener.onBecameForeground();
            }
        }
    }
    static class ApplicationLoader {
        static volatile boolean mainInterfacePaused;
        static final Object applicationContext = new Object();
    }
    static class NimarkoConfig {
        static boolean pluginsEngine = true;
        static volatile boolean pluginsSafeMode;
        static void setPluginsSafeMode(boolean value) { pluginsSafeMode = value; }
    }
    static class PluginCrashReports {
        static volatile String reason;
        static void setSafeModeReason(String value) { reason = value; }
    }
    static class FileLog {
        static void d(String message) {}
        static void w(String message) {}
        static void e(String message) {}
        static void e(String message, Throwable failure) {}
    }
    static class Watchdog { final AtomicInteger starts = new AtomicInteger(); void start() { starts.incrementAndGet(); } }
    static class DispatchQueue extends Thread {
        final List<Runnable> work = new ArrayList<>(); boolean accepts = true;
        boolean postRunnable(Runnable task) { if (accepts) work.add(task); return accepts; }
    }
    static void eq(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static PluginStartupDeadlineHarness fresh() {
        AndroidUtilities.tasks.clear(); ForegroundDetector.instance.listeners.clear();
        SystemClock.now = 1_000L; SystemClock.reads = 0; SystemClock.hook = null;
        ApplicationLoader.mainInterfacePaused = false; NimarkoConfig.pluginsSafeMode = false;
        PluginCrashReports.reason = null; AppRestartHelper.restarts = 0;
        return new PluginStartupDeadlineHarness();
    }
    void running(String message) {
        check(initializationInProgress && !initialized, message);
        check(!NimarkoConfig.pluginsSafeMode && PluginCrashReports.reason == null, "no premature safe mode: " + message);
        eq(0, watchdog.starts.get(), "no premature completion: " + message);
    }
    static void at(long now) { SystemClock.now = now; AndroidUtilities.runDue(); }

    static void progressBeyondOverallLimit() {
        PluginStartupDeadlineHarness h = fresh(); AtomicInteger completions = new AtomicInteger();
        h.init(false, completions::incrementAndGet);
        long attempt = h.getInitializationAttempt(), start = SystemClock.now;
        eq(1L, attempt, "init allocates attempt");
        eq(start + 90_000L, AndroidUtilities.nextAt(), "initial deadline posted before engine work");
        eq(1, h.queue.work.size(), "engine initialization enqueued");
        h.init(false, completions::incrementAndGet);
        eq(attempt, h.getInitializationAttempt(), "concurrent init joins existing attempt");
        eq(1, ForegroundDetector.instance.listeners.size(), "one lifecycle listener");
        for (int stage = 1; stage <= 10; stage++) {
            at(start + stage * 60_000L);
            h.reportInitializationProgress(attempt);
            h.running("progress stage " + stage);
        }
        check(SystemClock.now - start > 90_000L, "test exceeds old total deadline");
        check(h.finishControllerInitialization(attempt, true), "progressing startup completes");
        eq(2, completions.get(), "joined callbacks each run once");
        eq(0L, h.getInitializationAttempt(), "finished attempt not exposed");
        eq(1, h.watchdog.starts.get(), "watchdog starts once");
        at(SystemClock.now + 180_000L);
        eq(2, completions.get(), "stale delayed check cannot complete twice");
        eq(0, AppRestartHelper.restarts, "successful long startup never restarts");
    }
    static void idleDeadlineAndReasonOrdering() {
        PluginStartupDeadlineHarness h = fresh(); AtomicInteger completions = new AtomicInteger();
        h.init(false, () -> {
            check(NimarkoConfig.pluginsSafeMode, "safe mode enabled before completion callbacks");
            eq("engine_timeout", PluginCrashReports.reason, "timeout reason visible before callbacks");
            completions.incrementAndGet();
        });
        long start = SystemClock.now;
        at(start + 89_999L); h.running("89,999 ms idle is not timeout");
        at(start + 90_000L);
        check(!h.initializationInProgress && !h.initialized && h.shutdownRequiresProcessRestart, "90,000 ms idle fails closed");
        eq(1, completions.get(), "timeout callback once");
        eq(1, AppRestartHelper.restarts, "timeout schedules one restart");
        h.timeoutControllerInitialization(1L); at(SystemClock.now + 90_000L);
        eq(1, completions.get(), "duplicate timeout is inert");
        eq(1, AppRestartHelper.restarts, "duplicate timeout cannot restart twice");
    }
    static void backgroundAndResume() {
        for (boolean handlerRunsInBackground : new boolean[]{false, true}) {
            PluginStartupDeadlineHarness h = fresh(); h.init(false, null);
            SystemClock.now += 50_000L;
            ForegroundDetector.instance.transition(true);
            eq(SystemClock.now, h.initializationProgressAt, "background transition rebaselines idle clock");
            SystemClock.now += 3_600_000L;
            if (handlerRunsInBackground) {
                AndroidUtilities.runDue(); h.running("background time is not idle failure");
                eq(SystemClock.now, h.initializationProgressAt, "background check resets idle baseline");
            }
            SystemClock.now += 3_600_000L;
            ForegroundDetector.instance.transition(false);
            long resumed = SystemClock.now;
            eq(resumed, h.initializationProgressAt, "foreground listener grants fresh grace");
            AndroidUtilities.runDue(); h.running("overdue handler after resume is safe");
            at(resumed + 89_999L); h.running("full post-resume idle grace");
            at(resumed + 90_000L);
            eq("engine_timeout", PluginCrashReports.reason, "real foreground idle still times out");
        }
    }
    static void staleAttempts() {
        PluginStartupDeadlineHarness h = fresh(); h.init(false, null);
        long old = h.getInitializationAttempt();
        check(h.finishControllerInitialization(old, true), "first attempt succeeds");
        h.initialized = false; h.init(false, null);
        long current = h.getInitializationAttempt(), baseline = h.initializationProgressAt;
        check(current != old, "new attempt has distinct token");
        SystemClock.now += 30_000L;
        h.reportInitializationProgress(old);
        h.checkControllerInitializationDeadline(old);
        h.failControllerInitialization(old, "engine_failure");
        check(!h.finishControllerInitialization(old, true), "stale success rejected");
        eq(baseline, h.initializationProgressAt, "old progress cannot extend new attempt");
        eq(current, h.getInitializationAttempt(), "old failure cannot finish new attempt");
        eq(null, PluginCrashReports.reason, "stale attempt cannot set safe mode reason");
        eq(1, ForegroundDetector.instance.listeners.size(), "subsequent init does not duplicate listener");
        h.reportInitializationProgress(current);
        eq(SystemClock.now, h.initializationProgressAt, "current token advances progress");
    }
    static void progressBeforeGuardedFinish() {
        PluginStartupDeadlineHarness h = fresh(); h.init(false, null);
        long attempt = h.getInitializationAttempt(); SystemClock.now += 90_000L;
        // First clock read belongs to check; second is finish's guarded recheck.
        // Inject an intervening progress event at that second sampling boundary,
        // without rewriting any production method body or relying on thread timing.
        SystemClock.reads = 0; SystemClock.hookRead = 2;
        SystemClock.hook = () -> h.reportInitializationProgress(attempt);
        h.checkControllerInitializationDeadline(attempt);
        check(SystemClock.hook == null, "finish actually rechecks after deadline check");
        h.running("progress arriving before finish idle recheck prevents false timeout");
        eq(0, AppRestartHelper.restarts, "racing progress does not restart process");
        check(AndroidUtilities.tasks.stream().anyMatch(t -> t.at == SystemClock.now + 90_000L), "fresh idle deadline rearmed");
        check(h.finishControllerInitialization(attempt, true), "completion can win after guarded timeout is cancelled");
    }
    static void singleWinner() throws Exception {
        for (int repeat = 0; repeat < 100; repeat++) {
            PluginStartupDeadlineHarness h = fresh(); AtomicInteger callbacks = new AtomicInteger();
            String reason = repeat % 2 == 0 ? "engine_timeout" : "engine_failure";
            h.init(false, () -> {
                if (!h.initialized) {
                    check(NimarkoConfig.pluginsSafeMode, "winning failure publishes mode before callback");
                    eq(reason, PluginCrashReports.reason, "winning failure publishes reason before callback");
                }
                callbacks.incrementAndGet();
            });
            long attempt = h.getInitializationAttempt();
            SystemClock.now += 90_000L;
            CountDownLatch gate = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> success = pool.submit(() -> { gate.await(); return h.finishControllerInitialization(attempt, true); });
                Future<Boolean> failure = pool.submit(() -> { gate.await(); return h.finishControllerInitialization(attempt, false, reason); });
                gate.countDown(); boolean wonSuccess = success.get(5, TimeUnit.SECONDS), wonFailure = failure.get(5, TimeUnit.SECONDS);
                check(wonSuccess ^ wonFailure, "exactly one finish wins");
                eq(wonSuccess, h.initialized, "loser cannot overwrite winner state");
                eq(!wonSuccess, NimarkoConfig.pluginsSafeMode, "loser cannot overwrite global safe state");
                eq(wonSuccess ? null : reason, PluginCrashReports.reason, "loser cannot overwrite reason");
                eq(1, callbacks.get(), "single completion delivery");
                eq(1, h.watchdog.starts.get(), "single watchdog start");
            } finally { gate.countDown(); pool.shutdownNow(); }
        }
    }
    static void immediateFailureIsNotTimeout() {
        PluginStartupDeadlineHarness h = fresh(); h.queue.accepts = false;
        AtomicInteger callbacks = new AtomicInteger();
        h.init(false, () -> {
            eq("engine_failure", PluginCrashReports.reason, "queue failure is not idle timeout");
            check(NimarkoConfig.pluginsSafeMode, "safe state precedes failure callback");
            callbacks.incrementAndGet();
        });
        eq(1, callbacks.get(), "enqueue failure completes immediately");
        AndroidUtilities.runDue(); eq(1, AppRestartHelper.restarts, "enqueue failure schedules restart");
    }
    public static void main(String[] args) throws Exception {
        eq(90_000L, ENGINE_INIT_TIMEOUT_MS, "90 second idle budget");
        progressBeyondOverallLimit(); idleDeadlineAndReasonOrdering(); backgroundAndResume();
        staleAttempts(); progressBeforeGuardedFinish(); singleWinner(); immediateFailureIsNotTimeout();
        System.out.println("PASS startup deadline: >90s with progress, exact idle threshold, background/resume grace, stale attempts, guarded progress race, 100 concurrent single-winner finishes, reason-before-callback ordering");
    }
}
`;

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-startup-deadline-'));
try {
    const file = path.join(directory, 'PluginStartupDeadlineHarness.java');
    const restart = path.join(directory, 'app/nimarkogram/messenger/utils/AppRestartHelper.java');
    fs.mkdirSync(path.dirname(restart), {recursive: true});
    fs.writeFileSync(restart, 'package app.nimarkogram.messenger.utils; public class AppRestartHelper { public static volatile int restarts; public static void triggerRebirth(Object context) { restarts++; } }');
    fs.writeFileSync(file, harness);
    cp.execFileSync('javac', ['-Xlint:all', '-d', directory, restart, file], {stdio: 'inherit', timeout: 30000});
    cp.execFileSync('java', ['-cp', directory, 'PluginStartupDeadlineHarness'], {stdio: 'inherit', timeout: 15000});
} finally {
    fs.rmSync(directory, {recursive: true, force: true});
}
