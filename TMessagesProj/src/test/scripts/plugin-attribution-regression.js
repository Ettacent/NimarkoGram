// Standalone host regression: node TMessagesProj/src/test/scripts/plugin-attribution-regression.js
// Requires only Node.js and a JDK. The Java attribution logic is extracted, not reimplemented.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');

const javaRoot = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(javaRoot,
    'app/nimarkogram/messenger/plugins/utils/PluginsWatchdog.java'), 'utf8');

// Ignore braces in comments and literals while extracting complete Java members.
function member(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, `Missing production member: ${signature}`);
    const body = source.indexOf('{', start);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = body;
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(`Unterminated member: ${signature}`);
}

const classStart = source.indexOf('public final class PluginsWatchdog {');
assert(classStart >= 0);
const fields = source.slice(source.indexOf('{', classStart) + 1,
    source.indexOf('    public PluginsWatchdog('));
assert.match(fields, /static volatile PluginsWatchdog activeWatchdog/);
assert.equal((fields.match(/Collections\.synchronizedMap\(new WeakHashMap<>\(\)\)/g) || []).length, 2,
    'Both thread registries must have synchronized weak keys');
for (const signature of ['public static String findCrashingPlugin(', 'public static Plugin getKnownPlugin(']) {
    assert(!member(signature).includes('PluginsController.getInstance('),
        'Crash lookup cannot initialize the controller');
}
assert(!member('private static final class CrashSnapshot').includes('getSuppressed('),
    'Suppressed diagnostic exceptions are not attribution evidence');

const members = [
    'public PluginsWatchdog(PluginsController controller)',
    'public static String findCrashingPlugin(',
    'public static Plugin getKnownPlugin(',
    'public static String currentExecutingPluginId(',
    'private void tick()',
    'public static final class ExecutionInfo',
    'private static final class CrashSnapshot',
    'public void start()',
    'public void stop()',
    'public void onPluginExecutionStarted(',
    'public void onPluginExecutionFailed(',
    'public void onPluginExecutionFailed(String pluginId, Throwable throwable, String phase)',
    'public void onPluginExecutionFinished(',
    'public String getExecutingPluginId(',
    'public String getCrashingPluginId(',
].map(member).join('\n');

const harness = `
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

public class PluginAttributionHarness {
    static boolean controllerInitializationAllowed;
    static class PluginsController {
        static {
            if (!controllerInitializationAllowed) throw new AssertionError("Lazy controller initialization");
        }
        static class PluginRuntimeToken {
            String getPluginId() { return "initializing-plugin"; }
        }
        PluginRuntimeToken runtime;
        final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
        PluginRuntimeToken captureCurrentPluginRuntime() { return runtime; }
        boolean isPluginRuntimeExecuting(PluginRuntimeToken token) { return true; }
    }
    static class Plugin { void setNotResponding(boolean value) {} }
    static class PluginDexTracking {
        static final Map<Throwable, String> origins = new IdentityHashMap<>();
        static String findOwner(Throwable error) { return origins.get(error); }
    }
    static class SystemClock { static long now; static long elapsedRealtime() { return now; } }
    static class AlertDialog {}
    static class AndroidUtilities { static void runOnUIThread(Runnable task) { task.run(); } }
    static class FileLog {
        static void w(String message) {}
        static void e(Exception error) { throw new AssertionError(error); }
    }
    static class NotificationCenter {
        static final int pluginIsNotResponding = 1;
        static final NotificationCenter instance = new NotificationCenter();
        static NotificationCenter getGlobalInstance() { return instance; }
        void postNotificationNameOnUIThread(int id) {}
    }
    // Contract stub only: persistence/queue bounding is owned by PluginCrashReports.
    static class PluginCrashReports {
        static final List<String> owners = new ArrayList<>();
        static final List<String> phases = new ArrayList<>();
        static WeakReference<Throwable> lastError;
        static boolean fail;
        static void recordFailure(String id, String phase, Throwable error) {
            owners.add(id);
            phases.add(phase);
            lastError = new WeakReference<>(error);
            if (fail) throw new IllegalStateException("reporter unavailable");
        }
        static void reset() { owners.clear(); phases.clear(); lastError = null; fail = false; }
    }
    static final class PluginsWatchdog {
        ${fields}
        ${members}
        void dismissStaleAlert(String id) {}
        void showNotRespondingAlertInternal(Plugin plugin, PluginsController.PluginRuntimeToken runtime) {}
    }

    static void eq(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static PluginsWatchdog fresh() {
        PluginCrashReports.reset();
        PluginDexTracking.origins.clear();
        return new PluginsWatchdog(new PluginsController());
    }
    static void owner(PluginsWatchdog w, Throwable error, String expected, String message) {
        eq(expected, w.getCrashingPluginId(Thread.currentThread(), error), message);
        eq(expected, PluginsWatchdog.findCrashingPlugin(Thread.currentThread(), error), "static " + message);
    }
    static void reports(String... owners) {
        eq(Arrays.asList(owners), PluginCrashReports.owners, "report exactly once per failure origin");
        eq(Collections.nCopies(owners.length, "callback"), PluginCrashReports.phases, "default callback phase");
    }
    static void loadPhases() {
        PluginsWatchdog w = fresh();
        Throwable fatal = new LinkageError("B import error");
        w.onPluginExecutionStarted("A");
        w.onPluginExecutionStarted("B");
        w.onPluginExecutionFailed("B", fatal, "load");
        w.onPluginExecutionFinished("B");
        owner(w, fatal, "B", "fatal import retains load owner after finally");
        w.onPluginExecutionFailed("A", new RuntimeException(fatal));
        w.onPluginExecutionFinished("A");
        owner(w, fatal, "B", "outer callback cannot replace fatal load snapshot");
        eq(Arrays.asList("B"), PluginCrashReports.owners, "load origin reported once");
        eq(Arrays.asList("load"), PluginCrashReports.phases, "load phase is not overwritten by callback phase");
        w.onPluginExecutionFailed("C", new AssertionError("separate callback"));
        eq(Arrays.asList("load", "callback"), PluginCrashReports.phases, "two-argument API defaults to callback");

        w = fresh();
        w.onPluginExecutionFailed("inner", fatal);
        w.onPluginExecutionFailed("loader", new RuntimeException(fatal), "import");
        owner(w, fatal, "inner", "outer load cannot steal inner callback failure");
        reports("inner");
        w = fresh();
        w.onPluginExecutionFailed("B", fatal, "import");
        owner(w, fatal, "B", "load failure need not have an active callback");
        eq(Arrays.asList("import"), PluginCrashReports.phases, "custom load phase preserved verbatim");
    }
    static void attribution() {
        Thread thread = Thread.currentThread();
        Throwable error = new IllegalStateException("B callback");
        PluginsWatchdog w = fresh();
        w.onPluginExecutionStarted("A");
        w.onPluginExecutionStarted("B");
        owner(w, error, "B", "innermost active callback");
        w.onPluginExecutionFailed("B", error);
        w.onPluginExecutionFinished("B");
        eq("A", w.getExecutingPluginId(thread), "outer callback restored");
        owner(w, error, "B", "exact snapshot beats active outer owner");
        w.onPluginExecutionFailed("A", error);
        owner(w, error, "B", "same Throwable propagation preserves B");
        w.onPluginExecutionFinished("A");
        owner(w, error, "B", "snapshot survives finally unwinding");
        check(PluginCrashReports.lastError.get() == error, "report original Throwable");
        reports("B");

        w = fresh();
        w.onPluginExecutionStarted("A");
        w.onPluginExecutionStarted("B");
        w.onPluginExecutionFailed("B", error);
        w.onPluginExecutionFinished("B");
        Throwable wrapper = new RuntimeException("A wrapper", error);
        owner(w, wrapper, "B", "cause snapshot beats active owner");
        w.onPluginExecutionFailed("A", wrapper);
        w.onPluginExecutionFinished("A");
        owner(w, new RuntimeException(wrapper), "B", "multiple wrapping owners");
        reports("B");

        w = fresh();
        w.onPluginExecutionFailed("B", wrapper);
        w.onPluginExecutionFailed("A", error);
        owner(w, error, "B", "unwrapped cause preserves innermost origin");
        reports("B");
        // A collected wrapper must not lose a still-live cause's identity.
        w.callbackFailures.get(thread).throwables.get(0).clear();
        owner(w, error, "B", "weak cause survives cleared root");

        w = fresh();
        w.onPluginExecutionStarted("A");
        w.onPluginExecutionStarted("B");
        w.onPluginExecutionStarted("C");
        w.onPluginExecutionFailed("C", error);
        w.onPluginExecutionFinished("C");
        w.onPluginExecutionFailed("B", new RuntimeException(error));
        w.onPluginExecutionFinished("B");
        w.onPluginExecutionStarted("cleanup");
        w.onPluginExecutionFinished("cleanup");
        w.onPluginExecutionFailed("A", error);
        w.onPluginExecutionFinished("A");
        owner(w, error, "C", "three owners and nested cleanup preserve C");
        reports("C");

        Throwable host = new RuntimeException("unrelated original method");
        host.addSuppressed(error);
        owner(w, host, null, "suppressed handled plugin failure cannot blame host");
        owner(w, new RuntimeException(host), null, "suppressed under cause is not followed");
        w.onPluginExecutionStarted("A");
        w.onPluginExecutionFailed("B", error);
        owner(w, host, "A", "suppressed B cannot steal active A context");
        w.onPluginExecutionFailed("A", host);
        owner(w, host, "A", "new callback failure has its own origin");
        w.onPluginExecutionFinished("A");
        reports("C", "B", "A");

        w = fresh();
        w.onPluginExecutionStarted("A");
        w.onPluginExecutionStarted("B");
        w.onPluginExecutionFailed("B", error);
        w.onPluginExecutionFinished("B");
        Throwable newError = new AssertionError("A new failure after handling B");
        owner(w, newError, "A", "old handled failure cannot steal new active error");
        w.onPluginExecutionFailed("A", newError);
        w.onPluginExecutionFinished("A");
        owner(w, newError, "A", "new failure replaces old snapshot");
        owner(w, error, null, "old snapshot no longer applies");
        reports("B", "A");
        w.onPluginExecutionStarted("C");
        w.onPluginExecutionFinished("C");
        owner(w, newError, null, "new top-level callback clears handled history");

        w = fresh();
        w.onPluginExecutionStarted("A");
        w.onPluginExecutionFinished("foreign");
        owner(w, newError, "A", "foreign finish cannot pop owner");
        eq(null, w.getCrashingPluginId(new Thread(), newError), "thread isolation");
        eq(null, w.getCrashingPluginId(null, newError), "null crash thread");
        eq(null, w.getCrashingPluginId(thread, null), "null crash error");
        eq(null, w.getExecutingPluginId(null), "null execution thread");
        w.onPluginExecutionStarted(null);
        w.onPluginExecutionFailed(null, error);
        w.onPluginExecutionFailed("A", null);
        reports();
        w.onPluginExecutionFinished("A");
        owner(w, newError, null, "original host method runs outside callback ownership");

        w.onPluginExecutionFailed("B", error);
        Throwable lookalike = new IllegalStateException(error.getMessage());
        lookalike.setStackTrace(error.getStackTrace());
        owner(w, lookalike, null, "same message and stack are not identity evidence");
        PluginCrashReports.fail = true;
        w.onPluginExecutionFailed("A", newError);
        w.onPluginExecutionFailed("outer", newError);
        owner(w, newError, "A", "reporter failure cannot replace original attribution");
        reports("B", "A");
        w.stop();
        owner(w, newError, null, "stop clears attribution");
        eq(0, w.executingPlugins.size(), "stop clears execution state");
        eq(0, w.callbackFailures.size(), "stop clears failure state");
    }

    static void boundedCauses() {
        PluginsWatchdog w = fresh();
        Throwable first = new RuntimeException("cycle 1");
        Throwable second = new RuntimeException("cycle 2");
        first.initCause(second);
        second.initCause(first);
        w.onPluginExecutionFailed("cycle", first);
        owner(w, second, "cycle", "cyclic causes terminate and match");
        owner(w, new RuntimeException("other"), null, "cyclic snapshot non-match terminates");
        w.onPluginExecutionFailed("outer", second);
        reports("cycle");
        eq(16, w.callbackFailures.get(Thread.currentThread()).throwables.size(), "bounded cause snapshot");
        Throwable deep = first;
        for (int i = 0; i < 32; i++) deep = new RuntimeException(deep);
        owner(w, deep, null, "cause lookup is depth bounded");
    }

    static List<WeakReference<?>> installWeakState(PluginsWatchdog w) throws Exception {
        Throwable cause = new RuntimeException("collectable cause");
        Throwable root = new RuntimeException("collectable root", cause);
        w.onPluginExecutionFailed("main", root);
        Thread worker = new Thread(() -> {
            // Deliberately omit finally cleanup, simulating an abandoned thread.
            w.onPluginExecutionStarted("worker");
            w.onPluginExecutionFailed("worker", new RuntimeException("worker error"));
        });
        worker.start();
        worker.join();
        return Arrays.asList(new WeakReference<>(root), new WeakReference<>(cause), new WeakReference<>(worker));
    }

    static void weakRetention() throws Exception {
        PluginsWatchdog w = fresh();
        List<WeakReference<?>> references = installWeakState(w);
        for (int attempt = 0; attempt < 100; attempt++) {
            System.gc();
            // Any map access drains the WeakHashMap reference queue.
            w.executingPlugins.size();
            w.callbackFailures.size();
            boolean collected = true;
            for (WeakReference<?> reference : references) collected &= reference.get() == null;
            if (collected && w.executingPlugins.isEmpty() && w.callbackFailures.size() == 1) break;
            Thread.sleep(20);
        }
        for (WeakReference<?> reference : references) check(reference.get() == null, "no retained Throwable graph/dead Thread");
        eq(0, w.executingPlugins.size(), "dead execution thread evicted");
        eq(1, w.callbackFailures.size(), "only live main thread snapshot remains");
        owner(w, new RuntimeException(), null, "collected snapshot cannot match a fresh failure");
        w.stop();
    }

    static void concurrentTicks() throws Exception {
        PluginsWatchdog w = fresh();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                work.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        w.onPluginExecutionStarted("A");
                        w.onPluginExecutionStarted("B");
                        eq("B", w.getExecutingPluginId(Thread.currentThread()), "concurrent nested ownership");
                        w.tick();
                        w.onPluginExecutionFinished("B");
                        w.onPluginExecutionFinished("A");
                    }
                }));
            }
            for (Future<?> task : work) task.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        eq(0, w.executingPlugins.size(), "concurrent callbacks balance");
    }

    static void asynchronousDex() {
        PluginsWatchdog w = fresh();
        w.controller.runtime = new PluginsController.PluginRuntimeToken();
        eq("initializing-plugin", PluginsWatchdog.currentExecutingPluginId(), "import scope before callback tracking");
        w.controller.runtime = null;
        w.onPluginExecutionStarted("dex-plugin");
        eq("dex-plugin", PluginsWatchdog.currentExecutingPluginId(), "loader captures tracked identity");
        Throwable fatal = new NoSuchFieldError("chat_timePaint");
        PluginDexTracking.origins.put(fatal, "dex-plugin");
        w.onPluginExecutionFinished("dex-plugin");
        eq(null, PluginsWatchdog.currentExecutingPluginId(), "loader scope retired");
        owner(w, fatal, "dex-plugin", "later Handler callback attributed without active execution");
        owner(w, new NoSuchFieldError("chat_timePaint"), null, "error text alone is not attribution");
        w.onPluginExecutionStarted("outer");
        owner(w, fatal, "dex-plugin", "DEX origin beats outer active callback");
        w.onPluginExecutionFinished("outer");
        w.onPluginExecutionFailed("inner", fatal);
        owner(w, fatal, "inner", "exact callback snapshot retains precedence");
        PluginDexTracking.origins.clear();
    }

    public static void main(String[] args) throws Exception {
        eq(null, PluginsWatchdog.findCrashingPlugin(Thread.currentThread(), new RuntimeException()), "lookup before construction");
        eq(null, PluginsWatchdog.getKnownPlugin("missing"), "plugin lookup before construction");
        eq(null, PluginsWatchdog.getKnownPlugin(null), "null id before construction");
        controllerInitializationAllowed = true;
        PluginsWatchdog w = fresh();
        Plugin known = new Plugin();
        w.controller.plugins.put("known", known);
        check(PluginsWatchdog.getKnownPlugin("known") == known, "lookup uses existing controller registry");
        eq(null, PluginsWatchdog.getKnownPlugin("missing"), "unknown plugin");
        eq(null, PluginsWatchdog.getKnownPlugin(null), "null plugin id with active watchdog");
        w.onPluginExecutionStarted("old");
        fresh();
        eq(null, PluginsWatchdog.getKnownPlugin("known"), "last constructed registry wins");
        eq(null, PluginsWatchdog.findCrashingPlugin(Thread.currentThread(), new RuntimeException()), "last constructed watchdog wins");
        new PluginsWatchdog(null);
        eq(null, PluginsWatchdog.getKnownPlugin("known"), "null controller safe lookup");
        attribution();
        loadPhases();
        boundedCauses();
        weakRetention();
        concurrentTicks();
        asynchronousDex();
        System.out.println("PASS: static lookup, nested/cause attribution, report deduplication, suppressed/host boundaries, stale failures, weak retention, bounded causes, concurrent ticks");
    }
}
`;

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-plugin-attribution-'));
try {
    const file = path.join(directory, 'PluginAttributionHarness.java');
    const application = path.join(directory, 'org/telegram/messenger/ApplicationLoader.java');
    fs.mkdirSync(path.dirname(application), {recursive: true});
    fs.writeFileSync(application, 'package org.telegram.messenger; public class ApplicationLoader { public static boolean mainInterfacePaused; }');
    fs.writeFileSync(file, harness);
    cp.execFileSync('javac', ['-Xlint:all', '-d', directory, application, file], {stdio: 'inherit', timeout: 30000});
    cp.execFileSync('java', ['-Xmx64m', '-cp', directory, 'PluginAttributionHarness'], {stdio: 'inherit', timeout: 30000});
} finally {
    fs.rmSync(directory, {recursive: true, force: true});
}
