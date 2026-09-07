// Run from any directory:
// node TMessagesProj/src/test/scripts/plugin-load-recovery-regression.js
// Requires Node.js and JDK 11+ (java/javac on PATH), not Android SDK or Gradle.
// Compiles production methods extracted below. Only Android names and the
// wall clock are substituted; recovery decisions are not reimplemented.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');

const javaRoot = path.resolve(__dirname, '../../main/java');
const read = name => fs.readFileSync(path.join(javaRoot, name), 'utf8');
const native = read('app/nimarkogram/messenger/plugins/utils/NativeCrashHandler.java');
const engine = read('app/nimarkogram/messenger/plugins/PythonPluginsEngine.java');
const application = read('org/telegram/messenger/ApplicationLoader.java');

function member(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, `Missing production member: ${signature}`);
    const body = source.indexOf('{', start);
    assert(body >= 0, `Missing body: ${signature}`);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = body;
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(`Unterminated member: ${signature}`);
}

function field(source, name) {
    const match = source.match(new RegExp(`^    private static [^\\n]*\\b${name}\\s*(?:=[^;]*)?;`, 'm'));
    assert(match, `Missing production field: ${name}`);
    return match[0];
}

function platformStubs(source) {
    return source
        .replaceAll('app.nimarkogram.messenger.plugins.utils.NativeCrashHandler', 'NativeCrashHandler')
        .replaceAll('android.app.ActivityManager', 'ActivityManager')
        .replaceAll('android.app.Application', 'Application')
        .replaceAll('android.os.Process', 'Process')
        .replaceAll('android.os.SystemClock', 'SystemClock')
        .replaceAll('System.currentTimeMillis()', 'Clock.now');
}

const nativeMethods = [
    'private static ApplicationExitInfo lastExitInfo()',
    'private static boolean isSupportedMainProcess()',
    'private static boolean isNativeCrashExit(',
    'public static boolean lastExitWasLoadCrashAfter(long loadStartedAtMs)',
    'public static boolean lastExitWasLoadCrashAfter(long loadStartedAtMs, int loadPid)',
    'public static boolean conservativePre30LoadCrash(',
].map(s => member(native, s)).join('\n');
const nativeFields = ['EXIT_INFO_LOCK', 'exitInfoLoaded', 'cachedExitInfo']
    .map(s => field(native, s)).join('\n');
const pineMethods = [
    'private static boolean isPineRecoveryMainProcess(',
    'private static SharedPreferences pineRuntimePreferences()',
    'private static synchronized void preparePineRecoveryGuard()',
    'private static void markPineInitializationStarted()',
    'private static void clearPineInitializationMarker(',
].map(s => member(application, s)).join('\n');
const pineFields = [
    'NG_PINE_RUNTIME_PREFS', 'NG_PINE_INIT_SIGNATURE', 'NG_PINE_INIT_STARTED_AT',
    'NG_PINE_INIT_PID', 'NG_PINE_BLOCKED_SIGNATURE', 'ngPineRecoveryChecked',
    'ngPineBlockedByRecovery', 'ngPineUnavailableReason',
].map(s => field(application, s)).join('\n');
const clearLoad = member(engine, 'private static void clearOwnedPluginLoadMarker(');
const reportLoad = member(engine, 'private void reportPluginLoadFailure(');
assert(!reportLoad.includes('PluginCrashReports.recordFailure('), 'Load failure must retain watchdog attribution');
assert(!/\.remove\("pending_plugin_fatal_/.test(engine), 'Engine must not clear fatal records');
assert(!/\.put(?:String|Long)\("pending_plugin_fatal_/.test(engine), 'Engine must not write fatal records');
assert.match(engine, /\.putInt\("crashed_plugin_pid", loadPid\)/);
assert.match(engine, /loadSucceeded && cleanupSucceeded && !forceInstantiate/,
    'Import-only success must not clear quarantine before activation');
assert.match(engine, /clearOwnedPluginLoadMarker\(activationPreferences/,
    'Explicit re-enable must close its own activation marker');

const harness = `
import java.util.*;

public class PluginLoadRecoveryHarness {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    static class Clock { static long now = 1_000_000L; }
    static class SystemClock { static long elapsedRealtime() { return 800_000L; } }
    static class Process {
        static int myPid() { return 99; }
        static long getStartElapsedRealtime() { return 600_000L; }
    }
    static class Build {
        static class VERSION { static int SDK_INT = 30; }
        static class VERSION_CODES { static final int P = 28, R = 30; }
    }
    static class Application {
        static String processName = "app";
        static String getProcessName() { return processName; }
    }
    static class ApplicationExitInfo {
        static final int REASON_SIGNALED = 2, REASON_LOW_MEMORY = 3,
            REASON_CRASH = 4, REASON_CRASH_NATIVE = 5, REASON_ANR = 6,
            REASON_USER_REQUESTED = 10;
        final int pid, reason, status;
        final long timestamp;
        final String processName;
        ApplicationExitInfo(int p, int r, int s, long at, String name) {
            pid = p; reason = r; status = s; timestamp = at; processName = name;
        }
        int getPid() { return pid; }
        int getReason() { return reason; }
        int getStatus() { return status; }
        long getTimestamp() { return timestamp; }
        String getProcessName() { return processName; }
    }
    static class ActivityManager {
        List<ApplicationExitInfo> history = new ArrayList<>();
        boolean fail;
        int queries;
        List<RunningAppProcessInfo> running = new ArrayList<>();
        static class RunningAppProcessInfo {
            int pid; String processName;
            RunningAppProcessInfo(int p, String n) { pid = p; processName = n; }
        }
        List<ApplicationExitInfo> getHistoricalProcessExitReasons(String name, int pid, int max) {
            queries++;
            if (fail) throw new SecurityException("history unavailable");
            check(pid == 0, "lookup must not search past a newer main-process kill by PID");
            if (history == null) return null;
            return history.subList(0, Math.min(max, history.size()));
        }
        List<RunningAppProcessInfo> getRunningAppProcesses() {
            if (fail) throw new SecurityException("identity unavailable");
            return running;
        }
    }
    static class SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        boolean commitResult = true;
        int commits;
        String getString(String k, String d) { return (String) values.getOrDefault(k, d); }
        long getLong(String k, long d) { return (Long) values.getOrDefault(k, d); }
        int getInt(String k, int d) { return (Integer) values.getOrDefault(k, d); }
        Editor edit() { return new Editor(); }
        class Editor {
            final Map<String, Object> updates = new HashMap<>();
            Editor remove(String k) { updates.put(k, null); return this; }
            Editor putString(String k, String v) { updates.put(k, v); return this; }
            Editor putLong(String k, long v) { updates.put(k, v); return this; }
            Editor putInt(String k, int v) { updates.put(k, v); return this; }
            boolean commit() {
                commits++;
                updates.forEach((k,v) -> { if (v == null) values.remove(k); else values.put(k,v); });
                return commitResult;
            }
        }
    }
    static class Context {
        static final String ACTIVITY_SERVICE = "activity";
        static final int MODE_PRIVATE = 0;
        ActivityManager manager = new ActivityManager();
        SharedPreferences prefs = new SharedPreferences();
        int preferenceOpens;
        String getPackageName() { return "app"; }
        Object getSystemService(String service) { return manager; }
        SharedPreferences getSharedPreferences(String name, int mode) {
            preferenceOpens++;
            return prefs;
        }
    }
    static class FileLog {
        static int errors;
        static void e(String message) { errors++; }
        static void e(String message, Throwable failure) { errors++; }
    }
    static class NativeCrashHandler {
        ${platformStubs(nativeFields + '\n' + nativeMethods)}
    }
    static class ApplicationLoader {
        static Context applicationContext = new Context();
        static String signature = "build1";
        static String pineRuntimeSignature() { return signature; }
        ${platformStubs(pineFields + '\n' + pineMethods)}
    }
    static class Engine {
        ${clearLoad}
        ${reportLoad}
        final Controller controller = new Controller();
        int controllerLookups;
        Controller getPluginsController() { controllerLookups++; return controller; }
    }
    // Contract stub: nested-owner selection/deduplication are exercised with
    // actual watchdog code by plugin-attribution-regression.js, not copied here.
    static class Controller {
        final Watchdog watchdog = new Watchdog();
        Watchdog getWatchdog() { return watchdog; }
    }
    static class Watchdog {
        String id, phase;
        Throwable failure;
        int calls;
        boolean fail;
        void onPluginExecutionFailed(String owner, Throwable error, String loadPhase) {
            calls++; id = owner; phase = loadPhase; failure = error;
            if (fail) throw new IllegalStateException("reporting unavailable");
        }
    }
    static void reset() {
        Clock.now = 1_000_000L;
        Build.VERSION.SDK_INT = 30;
        Application.processName = "app";
        ApplicationLoader.applicationContext = new Context();
        ApplicationLoader.signature = "build1";
        ApplicationLoader.ngPineRecoveryChecked = false;
        ApplicationLoader.ngPineBlockedByRecovery = false;
        ApplicationLoader.ngPineUnavailableReason = null;
        NativeCrashHandler.exitInfoLoaded = false;
        NativeCrashHandler.cachedExitInfo = null;
        FileLog.errors = 0;
    }
    static void history(ApplicationExitInfo... exits) {
        NativeCrashHandler.exitInfoLoaded = false;
        NativeCrashHandler.cachedExitInfo = null;
        ApplicationLoader.applicationContext.manager.history = Arrays.asList(exits);
    }
    static ApplicationExitInfo exit(int pid, int reason, int signal, long at, String name) {
        return new ApplicationExitInfo(pid, reason, signal, at, name);
    }
    static ApplicationExitInfo crash() { return exit(42, 5, 0, 700_000L, "app"); }
    static void nativeCases() {
        reset();
        history(crash());
        check(NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "matched native load crash");
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L), "legacy no-PID overload fails closed");
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 43), "wrong PID");
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 0), "missing PID");
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(0, 42), "missing timestamp");
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(-1, 42), "invalid timestamp");
        check(NativeCrashHandler.lastExitWasLoadCrashAfter(700_000L, 42), "window start inclusive");
        check(NativeCrashHandler.lastExitWasLoadCrashAfter(100_000L, 42), "ten minutes inclusive");
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(99_999L, 42), "stale window");
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(700_001L, 42), "exit before marker");
        history(exit(42, 5, 0, 800_000L, "app"));
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "current-session exit excluded");
        history(exit(42, 5, 0, 1_000_001L, "app"));
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "future exit excluded");
        for (int signal : new int[]{4,6,7,8,11,31}) {
            history(exit(42, 2, signal, 700_000L, "app"));
            check(NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "fatal signal " + signal);
        }
        for (int signal : new int[]{0,9,15}) {
            history(exit(42, 2, signal, 700_000L, "app"));
            check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "non-crash signal " + signal);
        }
        for (int reason : new int[]{0,1,3,4,6,8,10,11,13,15,16}) {
            history(exit(42, reason, 0, 700_000L, "app"));
            check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "non-native reason " + reason);
        }
        history(exit(42, 2, 9, 750_000L, "app"), crash());
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "do not skip newer SIGKILL for old crash");
        history(exit(43, 10, 0, 750_000L, "app"), crash());
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "do not skip newer PID's force-stop");
        history(exit(77, 5, 0, 750_000L, "app:restart"), crash());
        check(NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "ignore auxiliary-process exit");
        history(exit(42, 5, 0, 700_000L, "app:restart"));
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "same PID but wrong process");
        history();
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "empty history");
        history();
        ApplicationLoader.applicationContext.manager.history = null;
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "null history");
        history(crash());
        ApplicationLoader.applicationContext.manager.fail = true;
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "history Binder failure");
        reset(); history(crash()); Application.processName = "app:restart";
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "no recovery in auxiliary process");
        check(ApplicationLoader.applicationContext.manager.queries == 0, "auxiliary process skips lookup");
        reset(); history(crash()); Build.VERSION.SDK_INT = 29;
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "pre-30 no classifier");
        check(!NativeCrashHandler.conservativePre30LoadCrash(650_000L), "pre-30 watermark alone insufficient");
        reset(); ApplicationLoader.applicationContext = null;
        check(!NativeCrashHandler.lastExitWasLoadCrashAfter(650_000L, 42), "no context");
    }
    static SharedPreferences loadMarker() {
        SharedPreferences p = new SharedPreferences();
        p.values.put("crashed_plugin_id", "A");
        p.values.put("crashed_plugin_started_at", 650_000L);
        p.values.put("crashed_plugin_pid", 42);
        p.values.put("crashed_plugin_load_token", "owner");
        p.values.put("plugin_crashed_A", true);
        p.values.put("plugin_enabled_before_quarantine_A", true);
        p.values.put("pending_plugin_fatal_id", "B");
        p.values.put("pending_plugin_fatal_at", 700_000L);
        p.values.put("unrelated", "keep");
        return p;
    }
    static void ownershipCases() {
        for (boolean success : new boolean[]{false,true}) {
            SharedPreferences p = loadMarker();
            Engine.clearOwnedPluginLoadMarker(p, "A", 650_000L, 42, "owner", success);
            for (String k : new String[]{"id", "started_at", "pid", "load_token"}) {
                check(!p.values.containsKey("crashed_plugin_" + k), "owned load field removed: " + k);
            }
            check(p.values.containsKey("plugin_crashed_A") != success, "quarantine cleared only on success");
            check(p.values.containsKey("plugin_enabled_before_quarantine_A") != success, "retry restore state preserved");
            check("B".equals(p.getString("pending_plugin_fatal_id", null)), "fatal owner not cleared");
            check(p.getLong("pending_plugin_fatal_at", 0) == 700_000L, "fatal timestamp not cleared");
            check("keep".equals(p.getString("unrelated", null)), "unrelated state untouched");
            check(p.commits == 1, "single cleanup transaction");
        }
        for (int mismatch = 0; mismatch < 4; mismatch++) {
            SharedPreferences p = loadMarker();
            Map<String,Object> before = new HashMap<>(p.values);
            Engine.clearOwnedPluginLoadMarker(p, mismatch == 0 ? "B" : "A",
                mismatch == 1 ? 650_001L : 650_000L, mismatch == 2 ? 43 : 42,
                mismatch == 3 ? "new-owner" : "owner", true);
            check(p.values.equals(before) && p.commits == 0, "ownership mismatch " + mismatch);
        }
        SharedPreferences p = loadMarker();
        p.values.remove("crashed_plugin_load_token");
        Map<String,Object> before = new HashMap<>(p.values);
        Engine.clearOwnedPluginLoadMarker(p, "A", 650_000L, 42, "owner", true);
        check(p.values.equals(before), "legacy ownerless record cannot be cleared by another load");
        p = loadMarker(); p.commitResult = false; FileLog.errors = 0;
        Engine.clearOwnedPluginLoadMarker(p, "A", 650_000L, 42, "owner", false);
        check(FileLog.errors == 1, "cleanup persistence failure logged");
    }
    static void reportCases() {
        Engine e = new Engine();
        Watchdog w = e.controller.watchdog;
        Throwable failure = new LinkageError("import failure");
        e.reportPluginLoadFailure("A", "import", failure);
        check(w.calls == 1 && w.failure == failure, "original import throwable forwarded exactly once");
        check("A".equals(w.id) && "import".equals(w.phase), "phase and owner forwarded");
        for (Throwable oom : new Throwable[]{new OutOfMemoryError("heap"),
                new RuntimeException(new OutOfMemoryError("wrapped heap"))}) {
            e.reportPluginLoadFailure("host", "import", oom);
        }
        e.reportPluginLoadFailure(null, "import", failure);
        e.reportPluginLoadFailure("A", "import", null);
        check(w.calls == 1 && e.controllerLookups == 1, "OOM/null never initializes or reaches watchdog");
        Throwable deep = new OutOfMemoryError("deep heap");
        for (int i = 0; i < 20; i++) deep = new RuntimeException(deep);
        e.reportPluginLoadFailure("host", "import", deep);
        Throwable cycleA = new RuntimeException(), cycleB = new RuntimeException(cycleA);
        cycleA.initCause(cycleB);
        e.reportPluginLoadFailure("host", "import", cycleA);
        check(w.calls == 1, "deep/cyclic unknown cause chain not attributed");
        w.fail = true; FileLog.errors = 0;
        e.reportPluginLoadFailure("A", "instantiate", failure);
        check(FileLog.errors == 1, "report failure cannot replace original load failure");
    }
    static SharedPreferences seedPine(int pid) {
        SharedPreferences p = ApplicationLoader.applicationContext.prefs;
        p.values.put("init_signature", "build1");
        p.values.put("init_started_at", 650_000L);
        if (pid > 0) p.values.put("init_pid", pid);
        return p;
    }
    static void consumed(SharedPreferences p) {
        for (String k : new String[]{"init_signature", "init_started_at", "init_pid"}) {
            check(!p.values.containsKey(k), "Pine marker consumed: " + k);
        }
    }
    static void pineCases() {
        reset(); history(crash()); SharedPreferences p = seedPine(42);
        ApplicationLoader.preparePineRecoveryGuard();
        check(ApplicationLoader.ngPineBlockedByRecovery, "matching Pine init PID blocks");
        check("build1".equals(p.getString("blocked_signature", null)), "Pine block persisted");
        consumed(p);
        for (int pid : new int[]{0,43}) {
            reset(); history(crash()); p = seedPine(pid);
            ApplicationLoader.preparePineRecoveryGuard();
            check(!ApplicationLoader.ngPineBlockedByRecovery, "legacy/mismatched Pine PID does not block");
            check(!p.values.containsKey("blocked_signature"), "no false Pine block");
            consumed(p);
        }
        for (int reason : new int[]{2,3,10}) {
            reset(); history(exit(42, reason, 9, 700_000L, "app")); p = seedPine(42);
            ApplicationLoader.preparePineRecoveryGuard();
            check(!ApplicationLoader.ngPineBlockedByRecovery, "Pine kill is not a crash: " + reason);
            consumed(p);
        }
        reset(); history(); p = seedPine(42);
        ApplicationLoader.preparePineRecoveryGuard();
        check(!ApplicationLoader.ngPineBlockedByRecovery, "Pine no history does not block"); consumed(p);
        reset(); history(crash()); p = seedPine(42); ApplicationLoader.signature = "build2";
        p.values.put("blocked_signature", "build1");
        ApplicationLoader.preparePineRecoveryGuard();
        check(!ApplicationLoader.ngPineBlockedByRecovery && !p.values.containsKey("blocked_signature"), "new build retries");
        consumed(p);
        reset(); p = seedPine(42); p.values.put("blocked_signature", "build1");
        ApplicationLoader.preparePineRecoveryGuard();
        check(ApplicationLoader.ngPineBlockedByRecovery, "existing same-build block retained"); consumed(p);
        for (boolean initialized : new boolean[]{false,true}) {
            reset(); p = ApplicationLoader.applicationContext.prefs;
            ApplicationLoader.markPineInitializationStarted();
            check(p.getInt("init_pid", 0) == Process.myPid(), "Pine PID stamped");
            check(p.getLong("init_started_at", 0) == Clock.now, "Pine time stamped");
            check("build1".equals(p.getString("init_signature", null)), "Pine build stamped");
            p.values.put("blocked_signature", "build1");
            ApplicationLoader.clearPineInitializationMarker(initialized);
            consumed(p);
            check(p.values.containsKey("blocked_signature") != initialized, "only successful init clears block");
        }
        reset(); p = seedPine(42); Map<String,Object> before = new HashMap<>(p.values);
        ApplicationLoader.clearPineInitializationMarker(true);
        check(p.values.equals(before), "finally cannot clear foreign Pine PID");
        for (String name : new String[]{"app:nimarko_restart", null}) {
            reset(); p = seedPine(42); before = new HashMap<>(p.values); Application.processName = name;
            ApplicationLoader.preparePineRecoveryGuard();
            ApplicationLoader.markPineInitializationStarted();
            ApplicationLoader.clearPineInitializationMarker(true);
            check(p.values.equals(before) && p.commits == 0, "auxiliary/unknown process cannot alter recovery");
            check(ApplicationLoader.applicationContext.preferenceOpens == 0, "auxiliary/unknown skips preference access");
            check(!ApplicationLoader.ngPineRecoveryChecked, "unknown identity not latched as checked");
        }
        reset(); Context context = ApplicationLoader.applicationContext;
        ApplicationLoader.applicationContext = null;
        ApplicationLoader.preparePineRecoveryGuard();
        check(!ApplicationLoader.ngPineRecoveryChecked, "early missing context permits later recovery");
        ApplicationLoader.applicationContext = context; history(crash()); p = seedPine(42);
        ApplicationLoader.preparePineRecoveryGuard();
        check(ApplicationLoader.ngPineBlockedByRecovery, "recovery retried after context available");
        for (String name : new String[]{"app", "app:nimarko_restart"}) {
            reset(); Build.VERSION.SDK_INT = 24; context = ApplicationLoader.applicationContext;
            context.manager.running = Arrays.asList(new ActivityManager.RunningAppProcessInfo(99, name));
            ApplicationLoader.markPineInitializationStarted();
            check(context.prefs.values.containsKey("init_pid") == name.equals("app"), "pre-28 PID/process identification");
        }
        reset(); Build.VERSION.SDK_INT = 24; context = ApplicationLoader.applicationContext;
        context.manager.fail = true;
        ApplicationLoader.markPineInitializationStarted();
        check(context.preferenceOpens == 0, "pre-28 failed identity lookup fails closed");
        System.out.println("PASS: " + checks + " checks (native exits, load ownership, reporting/OOM, Pine recovery)");
    }
    public static void main(String[] args) {
        nativeCases(); ownershipCases(); reportCases(); pineCases();
    }
}
`;

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-load-recovery-'));
try {
    const file = path.join(directory, 'PluginLoadRecoveryHarness.java');
    fs.writeFileSync(file, harness);
    cp.execFileSync('javac', ['-encoding', 'UTF-8', '-d', directory, file], {stdio: 'inherit'});
    cp.execFileSync('java', ['-cp', directory, 'PluginLoadRecoveryHarness'], {stdio: 'inherit'});
} finally {
    // Only this invocation's mkdtemp directory; never a caller-provided path.
    fs.rmSync(directory, {recursive: true, force: true});
}
