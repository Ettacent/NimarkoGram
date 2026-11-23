/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.textanim;

import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.telegram.messenger.FileLog;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import static app.nimarkogram.messenger.textanim.TextAnim.*;

final class EditorLegacyHooks {
    private EditorLegacyHooks() {}
    private static final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    static void install(Class<?> target) throws Exception {
        hookOnDraw(target);
        hookFocusChanged(target);
        hookTouchEvent(target);
    }
    static void clear() {
        for (XC_MethodHook.Unhook h : hooks) {
            try { h.unhook(); } catch (Throwable ignored) {}
        }
        hooks.clear();
    }
    private static void hookOnDraw(Class<?> cls) throws Exception {
        Method m = cls.getDeclaredMethod("onDraw", Canvas.class);
        m.setAccessible(true);
        hooks.add(XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!masterEnabled) return;
                try {
                    handleBeforeDraw((EditText) param.thisObject);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!masterEnabled) return;
                try {
                    handleAfterDraw(
                            (EditText) param.thisObject, (Canvas) param.args[0]);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        }));
    }

    private static void hookFocusChanged(Class<?> cls) {
        Method m = findMethod(cls, "onFocusChanged");
        if (m == null) return;
        m.setAccessible(true);
        hooks.add(XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    EditText view = (EditText) param.thisObject;
                    boolean focused = (Boolean) param.args[0];
                    handleFocusChanged(view, focused);
                } catch (Throwable t) { FileLog.e(t); }
            }
        }));
    }

    private static void hookTouchEvent(Class<?> cls) {
        Method m = findMethod(cls, "onTouchEvent");
        if (m == null) return;
        m.setAccessible(true);
        hooks.add(XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View view = (View) param.thisObject;
                    view.invalidate();
                    startHeartbeat(view);
                } catch (Throwable ignored) {}
            }
        }));
    }
}
