// Run with Node.js + JDK; no Android SDK, Gradle, or third-party dependencies.
// Production recovery, reporting, and persistence methods are extracted unchanged.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const javaRoot = path.resolve(__dirname, '../../main/java');
const read = file => fs.readFileSync(path.join(javaRoot, file), 'utf8');
const controller = read('app/nimarkogram/messenger/plugins/PluginsController.java');
const reports = read('app/nimarkogram/messenger/plugins/utils/PluginCrashReports.java');
const watchdog = read('app/nimarkogram/messenger/plugins/utils/PluginsWatchdog.java');
const engine = read('app/nimarkogram/messenger/plugins/PythonPluginsEngine.java');

function member(source, signature) {
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

const support = `
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
class HostSupport {
    static final List<String> events = new ArrayList<>();
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void eq(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
    // commit(false) may change Android's in-memory view without reaching disk.
    static class SharedPreferences {
        final Map<String, Object> data = new HashMap<>(), durable = new HashMap<>();
        boolean failCommit; int commits;
        SharedPreferences seed(String key, Object value) { data.put(key, value); durable.put(key, value); return this; }
        String getString(String key, String fallback) { return (String) data.getOrDefault(key, fallback); }
        boolean getBoolean(String key, boolean fallback) { return (Boolean) data.getOrDefault(key, fallback); }
        int getInt(String key, int fallback) { return (Integer) data.getOrDefault(key, fallback); }
        long getLong(String key, long fallback) { return (Long) data.getOrDefault(key, fallback); }
        boolean contains(String key) { return data.containsKey(key); }
        Editor edit() { return new Editor(); }
        class Editor {
            final Map<String, Object> changes = new HashMap<>();
            Editor remove(String key) { changes.put(key, null); return this; }
            Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
            Editor putString(String key, String value) { changes.put(key, value); return this; }
            Editor putLong(String key, long value) { changes.put(key, value); return this; }
            void merge(Map<String, Object> target) {
                changes.forEach((key, value) -> { if (value == null) target.remove(key); else target.put(key, value); });
            }
            boolean commit() {
                commits++; merge(data);
                events.add(failCommit ? "commit-failed" : "commit-ok");
                if (!failCommit) merge(durable);
                return !failCommit;
            }
            void apply() { merge(data); merge(durable); }
        }
    }
    static class TextUtils { static boolean isEmpty(String value) { return value == null || value.isEmpty(); } }
    static class NimarkoConfig {
        static final String VERSION_NAME = "host-test";
        static boolean pluginsSafeMode; static int safeModeWrites;
        static void setPluginsSafeMode(boolean value) { pluginsSafeMode = value; safeModeWrites++; }
    }
    static class AndroidUtilities {
        static final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
        static final List<Long> delays = new ArrayList<>();
        static void runOnUIThread(Runnable work, long delay) { pending.add(work); delays.add(delay); }
        static void runOnUIThread(Runnable work) { pending.add(work); }
    }
    static class NotificationCenter {
        static final int pluginsUpdated = 1;
        static final NotificationCenter instance = new NotificationCenter();
        static int delivered;
        static NotificationCenter getGlobalInstance() { return instance; }
        void postNotificationNameOnUIThread(int id) {
            eq(pluginsUpdated, id, "report completion updates plugin UI");
            AndroidUtilities.runOnUIThread(() -> delivered++);
        }
    }
    static class BaseFragment {}
    static class LaunchActivity { static BaseFragment getLastFragment() { return new BaseFragment(); } }
    static class BulletinFactory {
        static int shown;
        static BulletinFactory of(BaseFragment fragment) { return new BulletinFactory(); }
        BulletinFactory createSimpleBulletin(int icon, String text) { return this; }
        void show() { shown++; }
    }
    static class LocaleController { static String formatString(int message, String id) { return message + ":" + id; } }
    static class R {
        static class string { static final int NM_PluginLoadCrashDisabled = 1, NM_PluginCrashDisabled = 2; }
        static class raw { static final int info = 1; }
    }
    static class FileLog {
        static int errors;
        static void e(String message, Throwable error) { errors++; }
    }
    static class SystemClock { static long now = 100_000L; static long elapsedRealtime() { return now; } }
    static class Context {
        static final int MODE_PRIVATE = 0;
        final SharedPreferences preferences = new SharedPreferences();
        SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }
    static class ApplicationLoader {
        static final Context applicationContext = new Context();
        static File files;
        static File getFilesDirFixed() { return files; }
    }
    // File I/O is real; Android AtomicFile transaction semantics are outside this host test.
    static class AtomicFile {
        final File file;
        AtomicFile(File file) { this.file = file; }
        FileOutputStream startWrite() throws IOException { return new FileOutputStream(file); }
        FileInputStream openRead() throws IOException { return new FileInputStream(file); }
        void finishWrite(FileOutputStream output) throws IOException { output.close(); }
        void failWrite(FileOutputStream output) throws IOException { if (output != null) output.close(); }
    }
    static class Build { static final String MODEL = "host"; static class VERSION { static final int SDK_INT = 35; } }
    static class Plugin {
        String getName() { return "test"; } String getVersion() { return "1"; } String getAuthor() { return "host"; }
    }
    static class PluginsWatchdog { static Plugin getKnownPlugin(String id) { return null; } }
}
`;

const recovery = `
import java.util.*;
public class PluginSafetyRecoveryHarness extends HostSupport {
    ${controller.match(/public static final String PREF_PLUGIN_ENABLED_KEY_PREFIX\s*=\s*[^;]+;/)[0]}
    final SharedPreferences preferences = new SharedPreferences();
    ${member(controller, 'private void recoverPluginSafetyState(boolean startWithSafeMode)')}
    void recover() { recoverPluginSafetyState(false); }
    static class NativeCrashHandler {
        static boolean confirmed, benign; static int checks, benignChecks; static long start; static int pid;
        static boolean lastExitWasLoadCrashAfter(long time, int process) {
            checks++; start = time; pid = process; return confirmed;
        }
        static boolean lastExitWasBenignKill() { benignChecks++; return benign; }
    }
    static class PluginCrashReports {
        static String reason; static final List<String> correlations = new ArrayList<>();
        static void setSafeModeReason(String value) { reason = value; }
        static void recordLoadInterruption(String id, long time) {
            eq("commit-ok", events.get(events.size() - 1), "native report only after durable acknowledgement");
            correlations.add(id + ":" + time);
        }
    }
    static final String[] EVIDENCE = {"had_crash", "unattributed_native_crashes", "crashed_plugin_id",
        "crashed_plugin_started_at", "crashed_plugin_pid", "crashed_plugin_load_token",
        "crashed_plugin_attribution_exact", "native_crash_flag_only", "pending_plugin_fatal_id", "pending_plugin_fatal_at"};
    static PluginSafetyRecoveryHarness fresh() {
        events.clear(); AndroidUtilities.pending.clear(); AndroidUtilities.delays.clear(); BulletinFactory.shown = 0;
        NimarkoConfig.pluginsSafeMode = false; NimarkoConfig.safeModeWrites = 0; FileLog.errors = 0;
        NativeCrashHandler.confirmed = false; NativeCrashHandler.benign = false;
        NativeCrashHandler.checks = 0; NativeCrashHandler.benignChecks = 0;
        PluginCrashReports.reason = null; PluginCrashReports.correlations.clear();
        PluginSafetyRecoveryHarness h = new PluginSafetyRecoveryHarness();
        for (String id : new String[]{"A", "B", "C"}) h.preferences.seed("plugin_enabled_" + id, true);
        h.preferences.seed("unrelated_setting", "preserve");
        return h;
    }
    void loading(String id) {
        preferences.seed("crashed_plugin_id", id).seed("crashed_plugin_started_at", 1234L)
            .seed("crashed_plugin_pid", 42).seed("crashed_plugin_load_token", "session-token")
            .seed("native_crash_flag_only", true);
    }
    void fatal(String id) { preferences.seed("pending_plugin_fatal_id", id).seed("pending_plugin_fatal_at", 2345L); }
    void acknowledged() {
        for (String key : EVIDENCE) check(!preferences.durable.containsKey(key), "durable evidence acknowledged: " + key);
        eq("preserve", preferences.durable.get("unrelated_setting"), "unrelated preferences preserved");
    }
    void quarantinedOnly(String culprit) {
        for (String id : new String[]{"A", "B", "C"}) {
            eq(!id.equals(culprit), preferences.getBoolean("plugin_enabled_" + id, false), "enabled state " + id);
            eq(id.equals(culprit), preferences.getBoolean("plugin_crashed_" + id, false), "quarantine state " + id);
            eq(id.equals(culprit), preferences.contains("plugin_enabled_before_quarantine_" + id), "restore ownership " + id);
        }
    }
    static void noGlobal() {
        check(!NimarkoConfig.pluginsSafeMode, "must not activate global safe mode");
        eq(0, NimarkoConfig.safeModeWrites, "must not write global safe mode");
    }
    static void unknownAndUnconfirmed() {
        PluginSafetyRecoveryHarness h = fresh();
        for (int streak : new int[]{1, 2, 3, 1000, Integer.MAX_VALUE}) {
            h.preferences.seed("had_crash", true).seed("unattributed_native_crashes", streak);
            h.recoverPluginSafetyState(false); h.acknowledged(); h.quarantinedOnly(null); noGlobal();
        }
        eq(0, NativeCrashHandler.checks, "no marker is no native attribution");
        eq(0, AndroidUtilities.pending.size(), "unknown crashes do not announce quarantine");
        for (String reason : new String[]{"force-stop", "low-memory", "missing history", "pre-30", "unconfirmed native"}) {
            h = fresh(); h.loading("A");
            NativeCrashHandler.benign = reason.equals("force-stop") || reason.equals("low-memory");
            h.preferences.seed("had_crash", true).seed("unattributed_native_crashes", 50);
            h.recoverPluginSafetyState(false); h.acknowledged(); h.quarantinedOnly(null); noGlobal();
            eq(1, NativeCrashHandler.checks, reason + " requires confirmed native evidence");
            eq(1234L, NativeCrashHandler.start, "native checker receives marker timestamp");
            eq(42, NativeCrashHandler.pid, "native checker receives marker PID");
            check(PluginCrashReports.correlations.isEmpty(), "unconfirmed marker produces no report");
        }
    }
    static void exactAndRepeated() {
        PluginSafetyRecoveryHarness h = fresh(); h.loading("A"); h.fatal("B");
        NativeCrashHandler.benign = true; NativeCrashHandler.confirmed = true;
        h.recoverPluginSafetyState(false); h.acknowledged(); h.quarantinedOnly("B"); noGlobal();
        eq(0, NativeCrashHandler.checks, "pending exact fatal overrides stale load/native history");
        eq(0, NativeCrashHandler.benignChecks, "stale benign exit cannot erase exact fatal");
        check(PluginCrashReports.correlations.isEmpty(), "exact fatal is not labeled native correlation");
        eq(1, h.preferences.commits, "quarantine and acknowledgement use one transaction");
        eq(Arrays.asList(800L), AndroidUtilities.delays, "quarantine UI is scheduled");
        eq(0, BulletinFactory.shown, "no synchronous UI from recovery thread");
        AndroidUtilities.pending.remove().run(); eq(1, BulletinFactory.shown, "scheduled bulletin executes");
        for (int count = 0; count < 5; count++) {
            h.fatal("B"); h.recoverPluginSafetyState(false); h.acknowledged(); h.quarantinedOnly("B"); noGlobal();
            check(h.preferences.getBoolean("plugin_enabled_before_quarantine_B", false), "repeat quarantine retains original enabled state");
        }
        h = fresh(); h.loading("C");
        h.preferences.seed("had_crash", true).seed("crashed_plugin_attribution_exact", true);
        NativeCrashHandler.benign = true;
        h.recoverPluginSafetyState(false); h.quarantinedOnly("C"); h.acknowledged(); noGlobal();
        eq(0, NativeCrashHandler.checks, "legacy exact attribution also overrides benign history");
        h = fresh(); h.loading("A"); h.preferences.seed("crashed_plugin_attribution_exact", true);
        h.recoverPluginSafetyState(false); h.quarantinedOnly(null); noGlobal();
        h = fresh(); h.preferences.seed("plugin_enabled_B", false); h.fatal("B");
        h.recoverPluginSafetyState(false);
        check(!h.preferences.getBoolean("plugin_enabled_before_quarantine_B", true), "already disabled plugin restores disabled");
    }
    static void manual() {
        for (boolean marker : new boolean[]{false, true}) for (boolean culprit : new boolean[]{false, true}) {
            PluginSafetyRecoveryHarness h = fresh();
            if (marker) h.loading("manual!");
            if (culprit) h.fatal("B");
            h.recoverPluginSafetyState(!marker); h.acknowledged(); h.quarantinedOnly(culprit ? "B" : null);
            check(NimarkoConfig.pluginsSafeMode, "manual mode preserved");
            eq("manual", PluginCrashReports.reason, "explicit manual reason");
            eq(0, NativeCrashHandler.checks, "manual marker is never a plugin/native owner");
            check(!h.preferences.contains("plugin_crashed_manual!"), "manual sentinel is not quarantined");
        }
        PluginSafetyRecoveryHarness h = fresh();
        NimarkoConfig.pluginsSafeMode = true; PluginCrashReports.reason = "manual";
        h.fatal("A"); h.recoverPluginSafetyState(false); h.quarantinedOnly("A");
        check(NimarkoConfig.pluginsSafeMode, "previous manual state is not cleared");
        eq("manual", PluginCrashReports.reason, "previous manual reason preserved");
    }
    static void nativeAndAcknowledgement() {
        PluginSafetyRecoveryHarness h = fresh(); h.loading("C"); NativeCrashHandler.confirmed = true;
        h.recoverPluginSafetyState(false); h.quarantinedOnly("C"); h.acknowledged(); noGlobal();
        eq(Arrays.asList("C:1234"), PluginCrashReports.correlations, "confirmed native report keeps loading correlation");
        Map<String, Object> disk = new HashMap<>(h.preferences.durable);
        int notices = AndroidUtilities.pending.size();
        for (int repeat = 0; repeat < 4; repeat++) h.recoverPluginSafetyState(false);
        eq(disk, h.preferences.durable, "acknowledgement is idempotent");
        eq(1, NativeCrashHandler.checks, "consumed marker is not checked again");
        eq(1, PluginCrashReports.correlations.size(), "consumed native incident is not reported again");
        eq(notices, AndroidUtilities.pending.size(), "consumed incident is not announced again");
        noGlobal();
        h = fresh(); h.fatal("B"); h.recoverPluginSafetyState(false);
        notices = AndroidUtilities.pending.size(); disk = new HashMap<>(h.preferences.durable);
        h.recoverPluginSafetyState(false);
        eq(disk, h.preferences.durable, "exact fatal acknowledgement is idempotent");
        eq(notices, AndroidUtilities.pending.size(), "exact fatal is announced once");
    }
    static void failedCommit() {
        for (boolean nativeCrash : new boolean[]{false, true}) {
            PluginSafetyRecoveryHarness h = fresh();
            if (nativeCrash) { h.loading("B"); NativeCrashHandler.confirmed = true; } else h.fatal("B");
            Map<String, Object> before = new HashMap<>(h.preferences.durable);
            h.preferences.failCommit = true; h.recoverPluginSafetyState(false);
            check(NimarkoConfig.pluginsSafeMode, "failed durable recovery must prevent plugin startup");
            eq("recovery_failed", PluginCrashReports.reason, "explicit recovery failure reason");
            eq(before, h.preferences.durable, "failed commit does not durably acknowledge incident");
            eq(1, FileLog.errors, "commit failure logged");
            eq(0, AndroidUtilities.pending.size(), "no successful quarantine UI on failed commit");
            check(PluginCrashReports.correlations.isEmpty(), "no success report on failed commit");
        }
    }
    public static void main(String[] args) {
        unknownAndUnconfirmed(); exactAndRepeated(); manual(); nativeAndAcknowledgement(); failedCommit();
        System.out.println("PASS recovery: unknown streaks, unconfirmed/benign exits, exact precedence, isolated repeated quarantine, manual mode, native correlation, idempotent acknowledgement, commit failure");
    }
}
`;

const reportFields = reports.slice(reports.indexOf('{', reports.indexOf('public final class PluginCrashReports')) + 1,
    reports.indexOf('    private PluginCrashReports()'));
const reportMethods = [
    'private static SharedPreferences preferences()', 'public static boolean hasReport(',
    'public static void recordFailure(', 'public static void recordLoadInterruption(',
    'public static void writeIdentity(', 'private static String format(', 'private static String clean(',
    'private static String key(', 'private static File directory()', 'private static synchronized void save(',
    'private static synchronized String read(', 'private static final class LimitedWriter',
].map(signature => member(reports, signature)).join('\n');

const runtime = `
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
public class PluginRuntimeReportHarness extends HostSupport {
    static class PluginCrashReports {
        ${reportFields}
        ${reportMethods}
    }
    static class CallbackWatchdog {
        ${watchdog.match(/private final Map<Thread, CrashSnapshot> callbackFailures\s*=[\s\S]*?;/)[0]}
        ${member(watchdog, 'private static final class CrashSnapshot')}
        ${member(watchdog, 'public void onPluginExecutionFailed(String pluginId, Throwable throwable)')}
        ${member(watchdog, 'public void onPluginExecutionFailed(String pluginId, Throwable throwable, String phase)')}
    }
    static class EngineHarness {
        final ControllerStub controller = new ControllerStub();
        ControllerStub getPluginsController() { return controller; }
        static class ControllerStub {
            final CallbackWatchdog watchdog = new CallbackWatchdog();
            CallbackWatchdog getWatchdog() { return watchdog; }
        }
        ${member(engine, 'private void reportPluginLoadFailure(String pluginId, String phase, Throwable failure)')}
    }
    static class ObservedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        volatile Thread formattingThread;
        ObservedFailure() { super("callback exception detail"); }
        @Override public void printStackTrace(PrintWriter writer) { formattingThread = Thread.currentThread(); super.printStackTrace(writer); }
    }
    static void barrier() throws Exception { PluginCrashReports.worker.submit(() -> {}).get(5, TimeUnit.SECONDS); }
    public static void main(String[] args) throws Exception {
        ApplicationLoader.files = new File(args[0]);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            PluginCrashReports.worker.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "worker gate entered");
            ObservedFailure failure = new ObservedFailure();
            Thread caller = Thread.currentThread();
            CallbackWatchdog watchdog = new CallbackWatchdog();
            watchdog.onPluginExecutionFailed("B", failure);
            eq(1, PluginCrashReports.worker.getQueue().size(), "callback report enqueued behind worker gate");
            eq(null, failure.formattingThread, "stack formatting is not synchronous");
            check(!PluginCrashReports.hasReport("B"), "no report flag before asynchronous save");
            eq(0, AndroidUtilities.pending.size(), "no UI refresh before save");
            check(!new File(ApplicationLoader.files, "plugin-crash-reports").exists(), "no caller-thread file persistence");
            PluginCrashReports.recordFailure("B", "callback", failure);
            PluginCrashReports.recordFailure(null, "callback", failure);
            PluginCrashReports.recordFailure("null-error", "callback", null);
            PluginCrashReports.recordFailure("oom", "callback", new OutOfMemoryError());
            eq(1, PluginCrashReports.worker.getQueue().size(), "duplicates, nulls and OOM are not queued");
            release.countDown(); barrier();
            check(failure.formattingThread != null && failure.formattingThread != caller, "format on background worker");
            check(PluginCrashReports.hasReport("B"), "saved callback report is discoverable");
            eq(1, AndroidUtilities.pending.size(), "successful save schedules plugin UI refresh");
            eq(0, NotificationCenter.delivered, "worker must not deliver UI update inline");
            AndroidUtilities.pending.remove().run();
            eq(1, NotificationCenter.delivered, "scheduled plugin UI notification executes");
            File saved = new File(PluginCrashReports.directory(), PluginCrashReports.key("B") + ".txt");
            String text = Files.readString(saved.toPath());
            check(text.contains("Plugin ID: B") && text.contains("Phase: callback")
                && text.contains("callback exception detail"), "actual saved report contains identity, phase and exception");
            check(text.contains("Thread: " + caller.getName()), "report preserves original callback thread name");
            int capacity = PluginCrashReports.worker.getQueue().remainingCapacity();
            check(capacity > 0 && capacity <= 64, "worker queue is bounded");
            check(PluginCrashReports.worker.getMaximumPoolSize() == 1, "single report worker");
            SystemClock.now += 10_001L; // A distinct load incident outside the reporter's per-plugin throttle.
            EngineHarness engine = new EngineHarness();
            engine.reportPluginLoadFailure("oom-load", "import", new OutOfMemoryError());
            engine.reportPluginLoadFailure("wrapped-oom-load", "import", new RuntimeException(new OutOfMemoryError()));
            check(engine.controller.watchdog.callbackFailures.isEmpty(), "engine excludes direct and wrapped OOM from ownership snapshots");
            Throwable loadError = new LinkageError("fatal import detail");
            engine.reportPluginLoadFailure("B", "import", loadError);
            watchdog = engine.controller.watchdog;
            watchdog.onPluginExecutionFailed("outer", new RuntimeException(loadError));
            CallbackWatchdog.CrashSnapshot snapshot = watchdog.callbackFailures.get(caller);
            eq("B", snapshot.pluginId, "fatal import snapshot survives outer callback reporting");
            check(snapshot.matches(loadError), "fatal import exact identity is retained for uncaught attribution");
            barrier();
            String loadText = PluginCrashReports.read(new File(PluginCrashReports.directory(), PluginCrashReports.key("B") + ".txt"));
            check(loadText.contains("Phase: import") && loadText.contains("fatal import detail"), "watchdog overload persists original load phase and Error");
            check(!PluginCrashReports.hasReport("outer"), "outer callback does not create duplicate/misattributed report");
            // Feed the retained origin into the actual next-start recovery method.
            PluginSafetyRecoveryHarness recovered = PluginSafetyRecoveryHarness.fresh();
            recovered.fatal(snapshot.pluginId); recovered.recover();
            recovered.quarantinedOnly("B"); recovered.acknowledged(); PluginSafetyRecoveryHarness.noGlobal();
            PluginCrashReports.recordLoadInterruption("native", System.currentTimeMillis());
            String nativeText = PluginCrashReports.read(new File(PluginCrashReports.directory(), PluginCrashReports.key("native") + ".txt"));
            check(nativeText.contains("Load-time correlation only") && nativeText.contains("not proven"),
                "native report distinguishes correlation from proven fault attribution");
            System.out.println("PASS runtime reporting: deferred background formatting + actual file save, report flag, callback identity, deduplication, OOM exclusion, bounded worker, native correlation wording");
        } finally {
            release.countDown(); PluginCrashReports.worker.shutdownNow();
            check(PluginCrashReports.worker.awaitTermination(5, TimeUnit.SECONDS), "report worker stops");
        }
    }
}
`;

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-plugin-safety-'));
try {
    const files = [];
    for (const [name, source] of Object.entries({HostSupport: support,
        PluginSafetyRecoveryHarness: recovery, PluginRuntimeReportHarness: runtime})) {
        const file = path.join(directory, `${name}.java`);
        fs.writeFileSync(file, source);
        files.push(file);
    }
    cp.execFileSync('javac', ['-Xlint:all', '-d', directory, ...files], {stdio: 'inherit', timeout: 30000});
    for (const name of ['PluginSafetyRecoveryHarness', 'PluginRuntimeReportHarness']) {
        cp.execFileSync('java', ['-cp', directory, name, directory], {stdio: 'inherit', timeout: 15000});
    }
    // Informational audit, not a claim that Android rendering was host-tested.
    const callback = member(reports, 'public static void recordFailure(');
    const save = member(reports, 'private static synchronized void save(');
    if (!/runOnUIThread|postNotificationNameOnUIThread/.test(callback + save)) {
        console.warn('NOTE: callback report persistence has no direct UI dispatch; main still needs to wire report/error UI refresh.');
    }
    if (!member(reports, 'public static String safeModeExplanation()').includes('recovery_failed')) {
        console.warn('NOTE: recovery_failed is persisted explicitly but safeModeExplanation currently falls back to Unknown.');
    }
} finally {
    fs.rmSync(directory, {recursive: true, force: true});
}
