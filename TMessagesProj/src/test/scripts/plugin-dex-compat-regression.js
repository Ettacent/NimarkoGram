const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../../main/java');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-dex-compat-'));
const source = relative => fs.readFileSync(path.join(root, relative), 'utf8');
function write(base, name, text) {
    const file = path.join(tmp, base, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
    return file;
}
function run(command, args) {
    const result = cp.spawnSync(command, args, { encoding: 'utf8', timeout: 60000 });
    assert.equal(result.status, 0, `${command}: ${result.error || ''}\n${result.stdout}\n${result.stderr}`);
    return result.stdout;
}
function compile(out, files, classpath) {
    run('javac', [...(classpath ? ['-cp', classpath] : []), '-d', path.join(tmp, out), ...files]);
}

try {
    const paint = 'com/exteragram/messenger/utils/ui/TextPaint.java';
    const bridge = 'com/exteragram/messenger/utils/ui/LegacyThemeFields.java';
    const themePath = 'org/telegram/ui/ActionBar/Theme.java';
    const theme = source(themePath);
    const declaration = theme.match(/public class Theme extends [\w.]+ \{/)[0];
    const field = theme.match(/public static TextPaint chat_timePaint;/)[0];
    const assignments = [...theme.matchAll(/chat_timePaint = [^;]+;/g)].map(m => m[0]);
    assert.equal(assignments.length, 2, 'Audit every host replacement of the shared paint');
    assert(assignments.every(s => s === 'chat_timePaint = createChatTimePaint(TextPaint.ANTI_ALIAS_FLAG);'));
    assert(source('../../../proguard-rules.pro').includes('-keep class com.exteragram.** { *; }'));
    const platform = [
        write('src', 'android/graphics/Paint.java', `package android.graphics;
            public class Paint {
                public static final int ANTI_ALIAS_FLAG = 1;
                private int color; private float size;
                public Paint() {} public Paint(int flags) {}
                public Paint(Paint p) { color = p.color; size = p.size; }
                public void setColor(int c) { color = c; }
                public int getColor() { return color; }
                public void setTextSize(float s) { size = s; }
                public float getTextSize() { return size; }
            }`),
        write('src', 'android/text/TextPaint.java', `package android.text;
            public class TextPaint extends android.graphics.Paint {
                public TextPaint() {} public TextPaint(int f) { super(f); }
                public TextPaint(android.graphics.Paint p) { super(p); }
            }`),
        write('src', paint, source(paint)),
        write('src', bridge, source(bridge)),
    ];
    compile('runtime', [...platform, write('src', themePath, `package org.telegram.ui.ActionBar;
        import android.text.TextPaint;
        ${declaration}
            ${field}
            public static void reset() { ${assignments[0]} }
            public static void resetAgain() { ${assignments[1]} }
        }`)]);
    for (const [variant, descriptor] of [['legacy', 'com.exteragram.messenger.utils.ui.TextPaint'],
        ['android', 'android.text.TextPaint']]) {
        compile(variant, [
            write(variant + '-src', themePath, `package org.telegram.ui.ActionBar;
                public class Theme { public static ${descriptor} chat_timePaint; }`),
            write(variant + '-src', 'com/maxplugins/sample/Main.java', `package com.maxplugins.sample;
                import org.telegram.ui.ActionBar.Theme;
                public class Main {
                    public static Object read() { return Theme.chat_timePaint; }
                    public static float size() { return Theme.chat_timePaint.getTextSize(); }
                    public static void color(int c) { Theme.chat_timePaint.setColor(c); }
                    public static class Callback {}
                }`),
        ], path.join(tmp, 'runtime'));
    }
    const registryPath = 'app/nimarkogram/messenger/plugins/utils/PluginDexRegistry.java';
    compile('runtime', [write('src', registryPath, source(registryPath)),
        write('src', 'app/nimarkogram/messenger/plugins/utils/DexHarness.java', `
        package app.nimarkogram.messenger.plugins.utils;
        import java.net.*;
        import java.io.*;
        import java.lang.reflect.*;
        import org.telegram.ui.ActionBar.Theme;
        import com.exteragram.messenger.utils.ui.LegacyThemeFields;
        public class DexHarness {
            static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
            static Throwable error(String name) {
                Throwable e = new NoSuchFieldError("chat_timePaint");
                e.setStackTrace(new StackTraceElement[] {
                    new StackTraceElement(name, "stampCell", "Main.java", 4692),
                    new StackTraceElement("android.os.Handler", "handleCallback", "Handler.java", 1029)
                });
                return e;
            }
            static URLClassLoader loader(String path) throws Exception {
                return new URLClassLoader(new URL[] { new File(path).toURI().toURL() }, DexHarness.class.getClassLoader());
            }
            public static void main(String[] args) throws Exception {
                try (URLClassLoader a = loader(args[0]); URLClassLoader b = loader(args[1]);
                     URLClassLoader unowned = loader(args[0])) {
                    Class<?> legacy = a.loadClass("com.maxplugins.sample.Main");
                    Class<?> stock = b.loadClass("com.maxplugins.sample.Main");
                    for (int i = 0; i < 20; i++) {
                        if (i % 2 == 0) Theme.reset(); else Theme.resetAgain();
                        Theme.chat_timePaint.setTextSize(12 + i);
                        check(legacy.getMethod("read").invoke(null) == Theme.chat_timePaint, "legacy exact descriptor");
                        check(stock.getMethod("read").invoke(null) == Theme.chat_timePaint, "Android exact descriptor");
                        check(LegacyThemeFields.chat_timePaint == Theme.chat_timePaint, "same instance after reset");
                        check(((Float) legacy.getMethod("size").invoke(null)) == 12f + i, "text size synchronised");
                        legacy.getMethod("color", int.class).invoke(null, i);
                        check(Theme.chat_timePaint.getColor() == i, "plugin color visible to host");
                    }
                    check(Theme.class.getDeclaredField("chat_timePaint").getType() == android.text.TextPaint.class,
                        "reflection and Android ABI unchanged");
                    PluginDexRegistry registry = new PluginDexRegistry();
                    registry.registerLoader(a, "plugin-a");
                    registry.registerClass(a, legacy);
                    Class<?> callback = a.loadClass("com.maxplugins.sample.Main$Callback");
                    registry.registerClass(a, callback);
                    Throwable e = error(callback.getName());
                    check("plugin-a".equals(registry.findOwner(e)), "Handler callback retains DEX owner");
                    check("plugin-a".equals(registry.findOwner(new RuntimeException(e))), "causal wrapper");
                    Throwable host = error("org.telegram.ui.Cells.ChatMessageCell");
                    host.addSuppressed(e);
                    check(registry.findOwner(host) == null, "suppressed failure not evidence");
                    host.setStackTrace(new StackTraceElement[] { host.getStackTrace()[0], e.getStackTrace()[0] });
                    check(registry.findOwner(host) == null, "plugin caller not proof of host fault");
                    check(registry.findOwner(error("com.maxplugins.sample.Unknown")) == null, "no package guessing");
                    registry.registerClass(a, Theme.class);
                    check(registry.findOwner(error(Theme.class.getName())) == null, "delegated host class not owned");
                    registry.registerLoader(b, "plugin-b");
                    registry.registerClass(b, stock);
                    check(registry.findOwner(error(legacy.getName())) == null, "ambiguous duplicate names");
                    registry.registerClass(unowned, unowned.loadClass(callback.getName()));
                    check(registry.findOwner(e) == null, "untracked loader collision");
                    PluginDexRegistry reverse = new PluginDexRegistry();
                    reverse.registerClass(unowned, unowned.loadClass(legacy.getName()));
                    reverse.registerLoader(a, "plugin-a");
                    reverse.registerClass(a, legacy);
                    check(reverse.findOwner(error(legacy.getName())) == null, "collision before registration");
                    Field overflow = PluginDexRegistry.class.getDeclaredField("overflow");
                    overflow.setAccessible(true); overflow.setBoolean(registry, true);
                    check(registry.findOwner(e) == null, "bounded registry fails closed");
                    System.out.println("DEX ABI + ownership regressions passed");
                }
            }
        }`)], path.join(tmp, 'runtime'));
    console.log(run('java', ['-cp', path.join(tmp, 'runtime'),
        'app.nimarkogram.messenger.plugins.utils.DexHarness', path.join(tmp, 'legacy'), path.join(tmp, 'android')]).trim());

    // Negative control: the same precompiled plugin must reproduce the original linkage failure.
    compile('broken', [write('broken-src', themePath, `package org.telegram.ui.ActionBar;
        public class Theme {
            public static android.text.TextPaint chat_timePaint;
            public static void reset() { chat_timePaint = new android.text.TextPaint(); }
            public static void resetAgain() { reset(); }
        }`)], path.join(tmp, 'runtime'));
    const broken = cp.spawnSync('java', ['-cp', [path.join(tmp, 'broken'), path.join(tmp, 'runtime')].join(path.delimiter),
        'app.nimarkogram.messenger.plugins.utils.DexHarness', path.join(tmp, 'legacy'), path.join(tmp, 'android')],
        { encoding: 'utf8', timeout: 60000 });
    assert.notEqual(broken.status, 0);
    assert.match(broken.stderr, /NoSuchFieldError: chat_timePaint/);
    console.log('Negative control reproduces NoSuchFieldError without bridge');

    const trackingPath = 'app/nimarkogram/messenger/plugins/utils/PluginDexTracking.java';
    compile('runtime', [
        write('src', trackingPath, source(trackingPath)),
        write('src', 'dalvik/system/BaseDexClassLoader.java', `package dalvik.system;
            public class BaseDexClassLoader extends java.net.URLClassLoader {
                public BaseDexClassLoader(java.net.URL url) { super(new java.net.URL[] {url}); }
                protected Class<?> findClass(String name) throws ClassNotFoundException { return super.findClass(name); }
            }`),
        write('src', 'org/telegram/messenger/FileLog.java', `package org.telegram.messenger;
            public class FileLog { public static void e(String text, Throwable error) {} }`),
        write('src', 'app/nimarkogram/messenger/plugins/utils/PluginsWatchdog.java', `
            package app.nimarkogram.messenger.plugins.utils;
            public class PluginsWatchdog {
                public static String owner;
                public static String currentExecutingPluginId() { return owner; }
            }`),
        write('src', 'top/canyie/pine/callback/MethodHook.java', `package top.canyie.pine.callback;
            public class MethodHook {
                public void afterCall(top.canyie.pine.Pine.CallFrame frame) {}
                public class Unhook {
                    final java.lang.reflect.Member member;
                    public Unhook(java.lang.reflect.Member member) { this.member = member; }
                    public void unhook() { top.canyie.pine.Pine.hooks.remove(member); }
                }
            }`),
        write('src', 'top/canyie/pine/Pine.java', `package top.canyie.pine;
            import java.lang.reflect.*;
            import java.util.*;
            import top.canyie.pine.callback.MethodHook;
            public class Pine {
                public static final Map<Member, MethodHook> hooks = new LinkedHashMap<>();
                public static boolean fail;
                public static int attempts;
                public static MethodHook.Unhook hook(Member m, MethodHook h) {
                    attempts++;
                    if (fail && attempts == 2) throw new IllegalStateException("unsupported runtime");
                    if (m.getName().equals("loadClass")) throw new AssertionError("hot-path hook");
                    hooks.put(m, h); return h.new Unhook(m);
                }
                public static class CallFrame {
                    public Object thisObject, result; public boolean failed;
                    public boolean hasThrowable() { return failed; }
                    public Object getResult() { return result; }
                }
            }`),
        write('src', 'app/nimarkogram/messenger/plugins/utils/TrackingHarness.java', `
            package app.nimarkogram.messenger.plugins.utils;
            import dalvik.system.BaseDexClassLoader;
            import top.canyie.pine.Pine;
            import java.lang.reflect.*;
            public class TrackingHarness {
                public static void main(String[] args) throws Exception {
                    Pine.fail = args.length > 1;
                    PluginDexTracking.install();
                    int attempts = Pine.attempts;
                    PluginDexTracking.install();
                    DexHarness.check(Pine.attempts == attempts, "installed once");
                    if (Pine.fail) {
                        DexHarness.check(Pine.hooks.isEmpty(), "partial installation rolls back");
                        System.out.println("DEX tracking installation rollback passed"); return;
                    }
                    try (BaseDexClassLoader loader = new BaseDexClassLoader(new java.io.File(args[0]).toURI().toURL())) {
                        Pine.CallFrame frame = new Pine.CallFrame(); frame.thisObject = loader;
                        PluginsWatchdog.owner = "loaded-plugin";
                        for (var entry : Pine.hooks.entrySet()) {
                            if (entry.getKey() instanceof Constructor<?>) entry.getValue().afterCall(frame);
                        }
                        PluginsWatchdog.owner = null;
                        Class<?> type = loader.loadClass("com.maxplugins.sample.Main$Callback");
                        frame.result = type;
                        for (var entry : Pine.hooks.entrySet()) {
                            if (entry.getKey() instanceof Method) entry.getValue().afterCall(frame);
                        }
                        Throwable error = DexHarness.error(type.getName());
                        DexHarness.check("loaded-plugin".equals(PluginDexTracking.findOwner(error)),
                            "constructor ownership survives loading callback class later");
                        frame.result = org.telegram.ui.ActionBar.Theme.class;
                        for (var entry : Pine.hooks.entrySet()) {
                            if (entry.getKey() instanceof Method) entry.getValue().afterCall(frame);
                        }
                        DexHarness.check(PluginDexTracking.findOwner(DexHarness.error(((Class<?>)frame.result).getName())) == null,
                            "parent-delegated class never attributed");
                        System.out.println("DEX tracking callback wiring passed (host Pine contract stub)");
                    }
                }
            }`),
    ], path.join(tmp, 'runtime'));
    for (const extra of [[], ['fail']]) {
        console.log(run('java', ['-cp', path.join(tmp, 'runtime'),
            'app.nimarkogram.messenger.plugins.utils.TrackingHarness', path.join(tmp, 'legacy'), ...extra]).trim());
    }
} finally {
    fs.rmSync(tmp, { recursive: true, force: true });
}
