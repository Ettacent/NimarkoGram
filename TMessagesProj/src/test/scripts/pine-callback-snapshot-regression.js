const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const root = path.resolve(__dirname, '../../../..');
const jar = path.join(root, 'TMessagesProj/libs/pine-core-16kb.jar');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pine-snapshot-test-'));
function write(name, text) {
    const target = path.join(tmp, name);
    fs.mkdirSync(path.dirname(target), {recursive: true});
    fs.writeFileSync(target, text);
    return target;
}
try {
    const javac = fs.realpathSync(cp.execFileSync('which', ['javac'], {encoding: 'utf8'}).trim());
    const jdk = path.dirname(path.dirname(javac));
    const native = write('stub.c', `#include <jni.h>
JNIEXPORT void JNICALL Java_top_canyie_pine_Pine_syncMethodInfo
(JNIEnv *env, jclass cls, jobject origin, jobject backup, jboolean skip) {}`);
    const library = path.join(tmp, 'libtestpine.so');
    cp.execFileSync('gcc', ['-shared', '-fPIC', '-I' + path.join(jdk, 'include'),
        '-I' + path.join(jdk, 'include/linux'), native, '-o', library]);
    const sources = [
        write('android/os/Build.java', `package android.os; public class Build {
            public static class VERSION { public static int SDK_INT = 36; public static String CODENAME = "REL"; }
        }`),
        write('android/util/Log.java', `package android.util; public class Log {
            public static int d(String tag, String text) { return 0; }
            public static int e(String tag, String text, Throwable error) { return 0; }
        }`),
        write('SnapshotTest.java', `
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;
public class SnapshotTest {
    static int calls;
    static class Sentinel extends RuntimeException {}
    public static int original() { calls++; return 42; }
    public static int failure() { calls++; throw new Sentinel(); }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static Pine.HookRecord record(String name) throws Exception {
        Method method = SnapshotTest.class.getMethod(name);
        Pine.HookRecord r = new Pine.HookRecord(method, 0);
        r.backup = method;
        return r;
    }
    static Object invoke(Pine.HookRecord r) throws Throwable {
        return Pine.handleCall(r, null, new Object[0]);
    }
    static void callbacks(Pine.HookRecord r, Set<MethodHook> hooks) throws Exception {
        Field field = Pine.HookRecord.class.getDeclaredField("callbacks");
        field.setAccessible(true); field.set(r, hooks);
    }
    static class RemovedBeforeSnapshot extends HashSet<MethodHook> {
        @Override public int size() { clear(); return 0; }
    }
    public static void main(String[] args) throws Throwable {
        System.load(args[0]); PineConfig.debug = false;
        Pine.HookRecord r = record("original");
        Set<MethodHook> hooks = new RemovedBeforeSnapshot();
        hooks.add(new MethodHook() { public void beforeCall(Pine.CallFrame f) { throw new AssertionError(); } });
        callbacks(r, hooks);
        check(invoke(r).equals(42) && calls == 1);
        check(invoke(record("original")).equals(42) && calls == 2);

        List<String> events = new ArrayList<>();
        final Pine.HookRecord ordered = record("original");
        final MethodHook[] pair = new MethodHook[2];
        pair[0] = new MethodHook() {
            public void beforeCall(Pine.CallFrame f) {
                events.add("a-before"); ordered.removeCallback(pair[0]); ordered.removeCallback(pair[1]);
            }
            public void afterCall(Pine.CallFrame f) { events.add("a-after"); }
        };
        pair[1] = new MethodHook() {
            public void beforeCall(Pine.CallFrame f) { events.add("b-before"); }
            public void afterCall(Pine.CallFrame f) { events.add("b-after"); }
        };
        callbacks(ordered, new LinkedHashSet<>(Arrays.asList(pair)));
        int before = calls;
        check(invoke(ordered).equals(42) && calls == before + 1);
        check(events.equals(Arrays.asList("a-before", "b-before", "b-after", "a-after")));

        r = record("original");
        r.addCallback(new MethodHook() { public void beforeCall(Pine.CallFrame f) { f.setResult(17); }
            public void afterCall(Pine.CallFrame f) { f.setResult(18); } });
        before = calls; check(invoke(r).equals(18) && calls == before);
        PineConfig.disableHooks = true;
        check(invoke(r).equals(42) && calls == before + 1);
        PineConfig.disableHooks = false;

        r = record("original");
        r.addCallback(new MethodHook() { public void beforeCall(Pine.CallFrame f) {
            f.setResult(99); throw new Sentinel();
        }});
        before = calls; check(invoke(r).equals(42) && calls == before + 1);
        for (boolean hooked : new boolean[]{false, true}) {
            r = record("failure");
            if (hooked) r.addCallback(new MethodHook() {});
            before = calls;
            try { invoke(r); throw new AssertionError("exception swallowed"); }
            catch (Sentinel expected) { check(calls == before + 1); }
        }
        final Pine.HookRecord stress = record("original");
        MethodHook hook = new MethodHook() {};
        AtomicBoolean running = new AtomicBoolean(true);
        Thread unhook = new Thread(() -> {
            while (running.get()) { stress.addCallback(hook); stress.removeCallback(hook); }
        });
        unhook.start(); before = calls;
        try { for (int i = 0; i < 100000; i++) check(invoke(stress).equals(42)); }
        finally { running.set(false); unhook.join(); }
        check(calls == before + 100000);
        System.out.println("PASS: packaged Pine JAR, empty/removal snapshot, before/after order, early return, exceptions, disabled hooks and 100000 concurrent calls");
    }
}`),
    ];
    cp.execFileSync(javac, ['-cp', jar, '-d', tmp, ...sources]);
    const run = candidate => cp.spawnSync('java', ['-cp', tmp + path.delimiter + candidate, 'SnapshotTest', library],
        {encoding: 'utf8', timeout: 60000});
    const fixed = run(jar);
    assert.equal(fixed.status, 0, fixed.stderr || fixed.error?.message);
    console.log(fixed.stdout.trim());
    if (process.argv[2]) {
        const old = run(path.resolve(process.argv[2]));
        assert.notEqual(old.status, 0);
        assert.match(old.stderr, /ArrayIndexOutOfBoundsException/);
        assert.match(old.stderr, /Pine.handleCall/);
        console.log('PASS: old JAR deterministically reproduces the reported crash');
    }
} finally {
    fs.rmSync(tmp, {recursive: true, force: true});
}
