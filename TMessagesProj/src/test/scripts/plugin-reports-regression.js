// Host-only formatter regression. Run with Node.js and a JDK (no Android SDK).
// Production methods are compiled unchanged; only platform/metadata lookup and
// the native report's save sink are stubbed. Queues and persistence are not tested.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');

const javaRoot = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(javaRoot,
    'app/nimarkogram/messenger/plugins/utils/PluginCrashReports.java'), 'utf8');

// Ignore braces in Java comments, strings and character literals. A changed
// production signature fails extraction rather than silently testing a copy.
function extract(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, `Missing production declaration: ${signature}`);
    const open = source.indexOf('{', start);
    assert(open >= 0, `Missing body: ${signature}`);
    let depth = 1;
    let state = 'code';
    for (let i = open + 1; i < source.length; i++) {
        const c = source[i], next = source[i + 1];
        if (state === 'line') {
            if (c === '\n') state = 'code';
        } else if (state === 'block') {
            if (c === '*' && next === '/') { state = 'code'; i++; }
        } else if (state === '"' || state === "'") {
            if (c === '\\') i++;
            else if (c === state) state = 'code';
        } else if (c === '/' && next === '/') {
            state = 'line'; i++;
        } else if (c === '/' && next === '*') {
            state = 'block'; i++;
        } else if (c === '"' || c === "'") {
            state = c;
        } else if (c === '{') {
            depth++;
        } else if (c === '}' && --depth === 0) {
            return source.slice(start, i + 1);
        }
    }
    assert.fail(`Unterminated production declaration: ${signature}`);
}

const maxChars = source.match(/private static final int MAX_CHARS\s*=\s*[^;]+;/);
const reportKeys = source.match(/private static final Map<String,\s*String> reportKeys\s*=\s*[^;]+;/);
assert(maxChars, 'Production output bound must be extracted');
assert(reportKeys, 'Production key-cache declaration must be extracted');
const declarations = [
    'public static void writeIdentity(',
    'private static String format(',
    'private static String clean(',
    'private static String key(',
    'private static final class LimitedWriter extends Writer',
    // Exercise the real native note and terminated marker, not a test-owned note.
    'public static void recordLoadInterruption(',
].map(extract).join('\n');

const ids = ['abc', '../outside', '../../plugin_settings', '/absolute/path',
    '..\\..\\outside', 'C:\\plugins\\outside', '.', '..', '', '插件/отчёт',
    'plugin\nInjected: value'];
const hashChecks = ids.map(id => {
    const expected = crypto.createHash('sha256').update(id, 'utf8').digest('hex');
    return `checkKey(${JSON.stringify(id)}, "${expected}");`;
}).join('\n');

const harness = String.raw`import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public class PluginReportsHarness {
    static class Plugin {
        final String id, name, version, author;
        Plugin(String id, String name, String version, String author) {
            this.id = id; this.name = name; this.version = version; this.author = author;
        }
        String getId() { return id; }
        String getName() { return name; }
        String getVersion() { return version; }
        String getAuthor() { return author; }
    }
    static class PluginsWatchdog {
        static final Map<String, Plugin> known = new HashMap<>();
        static Plugin getKnownPlugin(String id) { return known.get(id); }
    }
    static Plugin stubPlugin(String id, String name, String version, String author) {
        Plugin plugin = new Plugin(id, name, version, author);
        PluginsWatchdog.known.put(id, plugin);
        return plugin;
    }
    static class Build {
        static final String MODEL = "host-test-device";
        static class VERSION { static final int SDK_INT = 36; }
    }
    static class NimarkoConfig { static final String VERSION_NAME = "host-client-version"; }
    static String savedId, savedReport;
    static long savedTime;
    static int saves;
    static void save(String id, String text, long time) throws Exception {
        savedId = id; savedReport = text; savedTime = time; saves++;
    }
    ${maxChars[0]}
    ${reportKeys[0]}
    ${declarations}

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static void contains(String text, String value) {
        check(text.contains(value), "Missing: " + value);
    }
    static void line(String text, String value) {
        check(text.lines().anyMatch(value::equals), "Missing exact line: " + value);
    }
    static void pluginHeader(String report) {
        check(report.startsWith("=== NimarkoGram plugin report ==="), "plugin-specific title");
        check(!report.contains("=== NimarkoGram crash ==="), "must not use global client crash title");
        check(!report.contains("ПРИШЛИТЕ @Ettacent"), "must not route plugin report to global support");
    }
    static String identity(String id) {
        StringWriter output = new StringWriter();
        PrintWriter writer = new PrintWriter(output);
        writeIdentity(writer, id);
        writer.flush();
        return output.toString();
    }
    static void metadata() {
        stubPlugin("alpha", "Alpha plugin", "1.2.3", "@alpha_author");
        stubPlugin("beta", "Бета 插件", "9.8.7", "@beta_author");
        String alpha = format("alpha", "queued callback", "callback-thread", 1000L,
                new IllegalStateException("callback failed"), null);
        pluginHeader(alpha);
        line(alpha, "Plugin ID: alpha");
        line(alpha, "Plugin name: Alpha plugin");
        line(alpha, "Plugin version: 1.2.3");
        line(alpha, "Plugin author: @alpha_author");
        line(alpha, "Phase: queued callback");
        line(alpha, "Thread: callback-thread");
        line(alpha, "App: host-client-version");
        contains(alpha, "java.lang.IllegalStateException: callback failed");
        contains(alpha, "Client process terminated: no");
        check(!alpha.contains("Client process terminated: yes"), "contained callback is not a process crash");
        String beta = format("beta", "request callback", "network", 2000L, null, null);
        pluginHeader(beta);
        line(beta, "Plugin ID: beta");
        line(beta, "Plugin name: Бета 插件");
        line(beta, "Plugin version: 9.8.7");
        line(beta, "Plugin author: @beta_author");
        check(!beta.contains("Alpha plugin") && !beta.contains("@alpha_author")
                && !beta.contains("1.2.3"), "metadata must not leak between plugins");
        check(!alpha.contains("@beta_author"), "reverse metadata isolation");
        String unknown = identity("not-installed");
        line(unknown, "Plugin ID: not-installed");
        check(!unknown.contains("Plugin name:") && !unknown.contains("Plugin author:"),
                "unknown plugin must not borrow metadata");
        stubPlugin("nullable", null, null, null);
        String nullable = identity("nullable");
        line(nullable, "Plugin name: unknown");
        line(nullable, "Plugin version: unknown");
        line(nullable, "Plugin author: unknown");
        System.out.println("PASS: callback title, metadata identity/isolation, phase, thread and unknown metadata");
    }
    static void sanitation() {
        check(clean(null).equals("unknown"), "null metadata");
        check(clean("one\r\ntwo").equals("one  two"), "CR/LF sanitation");
        check(clean("x".repeat(700)).length() == 512, "individual metadata bound");
        stubPlugin("unsafe\nid", "name\nPlugin ID: forged", "v\r99", "author\nApp: forged");
        String value = identity("unsafe\nid");
        line(value, "Plugin ID: unsafe id");
        line(value, "Plugin name: name Plugin ID: forged");
        line(value, "Plugin version: v 99");
        line(value, "Plugin author: author App: forged");
        check(value.lines().count() == 4, "metadata cannot inject header lines");
        String report = format("alpha", "hook\nPhase: forged", "ui\rThread: forged", 0L, null, null);
        line(report, "Phase: hook Phase: forged");
        line(report, "Thread: ui Thread: forged");
        System.out.println("PASS: null, oversized and header-injecting metadata sanitation");
    }
    static void boundedOutput() throws Exception {
        LimitedWriter exact = new LimitedWriter();
        exact.write("x".repeat(MAX_CHARS));
        check(exact.toString().length() == MAX_CHARS, "exact bound needs no truncation marker");
        check(!exact.truncated, "exactly full is not truncated");
        exact.write(new char[]{'z'}, 0, 1);
        check(exact.truncated && exact.text.length() == MAX_CHARS, "overflow does not grow payload");
        contains(exact.toString(), "[report truncated]");
        check(exact.toString().length() <= MAX_CHARS + 64, "bounded truncation marker");
        LimitedWriter offset = new LimitedWriter();
        offset.write(new char[]{'x', 'a', 'b', 'y'}, 1, 2);
        check(offset.toString().equals("ab"), "writer honors offset/count");
        String stable = exact.toString();
        exact.write("ignored".repeat(MAX_CHARS));
        exact.flush(); exact.close();
        check(exact.toString().equals(stable), "writes after saturation remain bounded");
        RuntimeException failure = new RuntimeException("huge:" + "界".repeat(MAX_CHARS * 4));
        String report = format("alpha", "callback", "main", 0L, failure, null);
        pluginHeader(report);
        line(report, "Plugin ID: alpha");
        contains(report, "[report truncated]");
        check(report.length() <= MAX_CHARS + 64, "full report including marker remains bounded");
        // MAX_CHARS is a UTF-16 character cap, not a UTF-8 byte cap.
        check(report.getBytes(StandardCharsets.UTF_8).length <= (MAX_CHARS + 64) * 3,
                "bounded UTF-8 expansion");
        RuntimeException caused = new RuntimeException("outer", new IllegalArgumentException("inner"));
        caused.addSuppressed(new IOException("diagnostic only"));
        String chain = format("beta", "callback", "main", 0L, caused, null);
        contains(chain, "Caused by: java.lang.IllegalArgumentException: inner");
        contains(chain, "Suppressed: java.io.IOException: diagnostic only");
        System.out.println("PASS: LimitedWriter boundaries, offsets, saturation, Unicode and exception chains");
    }
    static void checkKey(String id, String expected) throws Exception {
        String value = key(id);
        check(value.equals(expected), "SHA-256/UTF-8 hash for " + id);
        check(value.matches("[0-9a-f]{64}"), "key must be a safe flat filename");
        check(value.equals(key(id)), "cached hash is stable");
        Path root = Path.of("report-root").toAbsolutePath().normalize();
        Path target = root.resolve(value + ".txt").normalize();
        check(target.getParent().equals(root), "plugin ID cannot escape report directory");
    }
    static void hashes() throws Exception {
        ${hashChecks}
        String original = key("../outside");
        for (int i = 0; i < 200; i++) key("other-" + i);
        check(key("../outside").equals(original), "cache churn cannot change ownership key");
        check(!key("alpha").equals(key("beta")), "distinct plugin ownership keys");
        System.out.println("PASS: independent SHA-256 oracle, traversal/Unicode IDs and cache stability");
    }
    static void nativeUncertainty() {
        saves = 0; savedId = null; savedReport = null;
        recordLoadInterruption("alpha", 123456789L);
        check(saves == 1 && "alpha".equals(savedId), "native report saved for the named plugin");
        check(savedTime == 123456789L, "native incident timestamp preserved");
        pluginHeader(savedReport);
        line(savedReport, "Plugin ID: alpha");
        line(savedReport, "Plugin name: Alpha plugin");
        contains(savedReport, "Phase: native process exit during loading");
        contains(savedReport, "Load-time correlation only");
        contains(savedReport, "not proven");
        contains(savedReport, "precaution");
        contains(savedReport, "Client process terminated: yes");
        check(!savedReport.contains("Client process terminated: no"),
                "native report must not contradict its process-terminated marker");
        check(savedReport.lines().filter(s -> s.startsWith("Client process terminated:")).count() == 1,
                "one unambiguous native process-termination field");
        System.out.println("PASS: real native call site preserves identity, termination and explicit uncertainty");
    }
    public static void main(String[] args) throws Exception {
        metadata(); sanitation(); boundedOutput(); hashes(); nativeUncertainty();
    }
}
`;

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-plugin-reports-test-'));
try {
    const file = path.join(directory, 'PluginReportsHarness.java');
    fs.writeFileSync(file, harness);
    cp.execFileSync('javac', ['-encoding', 'UTF-8', file], {stdio: 'inherit', timeout: 60000});
    cp.execFileSync('java', ['-Dfile.encoding=UTF-8', '-cp', directory, 'PluginReportsHarness'],
        {stdio: 'inherit', timeout: 30000});
} finally {
    // Only the uniquely-created harness directory is removed, including on failure.
    fs.rmSync(directory, {recursive: true, force: true});
}
