/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.plugins.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;


import app.nimarkogram.messenger.plugins.bridge.PythonBoundarySanitizer;

import app.nimarkogram.messenger.plugins.bridge.PythonBoundarySanitizer.ScanResult;
import app.nimarkogram.messenger.plugins.bridge.PythonInterfaceProxy;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.NimarkoCrashContext;
public final class PineAdapter extends MethodHook {

    private static final Method BEFORE_HOOKED_METHOD;
    private static final Method AFTER_HOOKED_METHOD;

    static {
        Method before = null;
        Method after = null;
        try {
            before = XC_MethodHook.class.getDeclaredMethod(
                    "beforeHookedMethod", XC_MethodHook.MethodHookParam.class);
            before.setAccessible(true);
            after = XC_MethodHook.class.getDeclaredMethod(
                    "afterHookedMethod", XC_MethodHook.MethodHookParam.class);
            after.setAccessible(true);
        } catch (Throwable t) {
            PluginHookGuard.error("nimarko: PineAdapter reflection init failed", t);
        }
        BEFORE_HOOKED_METHOD = before;
        AFTER_HOOKED_METHOD = after;
    }

    private final XC_MethodHook xcHook;
    private final Member member;
    private final boolean runtimeSupportMethod;
    private final String diagnosticTarget;
    private final String diagnosticPlugin;
    private final String diagnosticRuntime;
    private final Class<?>[] paramTypes;
    private static final Object INVALID_RESULT = new Object();

    private static final class AdapterParam extends XC_MethodHook.MethodHookParam {
        AdapterParam previousActive;
        Pine.CallFrame invocationFrame;
        Object originalThisObject;
        Object[] originalArgsReference;
        Object[] originalArgsSnapshot;
        int originalArgsCount;
        boolean skipAfterCallback;

        boolean unverifiedBaseline;
        long diagnosticToken;
        long diagnosticStartedMs;
        void captureInvocation(Pine.CallFrame frame) {
            originalThisObject = frame.thisObject;
            originalArgsReference = frame.args;
            originalArgsCount = frame.args == null ? -1 : frame.args.length;
            if (originalArgsCount > 0) {
                if (originalArgsSnapshot == null
                        || originalArgsSnapshot.length != originalArgsCount) {
                    originalArgsSnapshot = new Object[originalArgsCount];
                }
                System.arraycopy(frame.args, 0, originalArgsSnapshot, 0,
                        originalArgsCount);
            }
        }

        void restoreInvocation(Pine.CallFrame frame) {
            frame.thisObject = originalThisObject;
            if (originalArgsCount < 0) {
                frame.args = null;
            } else {
                if (originalArgsReference == null
                        || originalArgsReference.length != originalArgsCount) {
                    originalArgsReference = new Object[originalArgsCount];
                }
                if (originalArgsCount > 0) {
                    System.arraycopy(originalArgsSnapshot, 0,
                            originalArgsReference, 0, originalArgsCount);
                }
                frame.args = originalArgsReference;
            }
            thisObject = frame.thisObject;
            args = frame.args;
        }

        void clearInvocation() {
            invocationFrame = null;
            originalThisObject = null;
            originalArgsReference = null;
            if (originalArgsSnapshot != null) {
                Arrays.fill(originalArgsSnapshot, null);
            }
            originalArgsCount = 0;
            skipAfterCallback = false;
            unverifiedBaseline = false;
            previousActive = null;
            diagnosticToken = 0;
            diagnosticStartedMs = 0;
        }
    }

    private final ArrayDeque<AdapterParam> params = new ArrayDeque<>(4);
    private final ThreadLocal<AdapterParam> activeParam = new ThreadLocal<>();

    public PineAdapter(Member member, XC_MethodHook xcHook) {
        if (member != null) {
            for (Class<?> owner = member.getDeclaringClass(); owner != null;
                    owner = owner.getEnclosingClass()) {
                if (owner == PluginHookGuard.class || owner == PineAdapter.class
                        || owner == PythonBoundarySanitizer.class
                        || owner == PythonInterfaceProxy.class
                        || owner == NimarkoCrashContext.class
                        || owner.getName().startsWith("app.nimarkogram.messenger.diagnostics.")) {
                    throw new IllegalArgumentException("Plugin bridge implementation cannot be hooked");
                }
            }
        }
        this.member = member;
        this.runtimeSupportMethod = PluginHookGuard.isSupportMethod(member);
        this.xcHook = xcHook;
        this.diagnosticTarget = member == null ? "unknown" : member.toString();
        PluginsController.PluginRuntimeToken installer = xcHook instanceof PyMethodHook
                || xcHook instanceof PyMethodReplacement ? null : PluginHookGuard.captureRuntime();
        this.diagnosticPlugin = xcHook instanceof PyMethodHook
                ? ((PyMethodHook) xcHook).diagnosticPluginId()
                : xcHook instanceof PyMethodReplacement
                ? ((PyMethodReplacement) xcHook).diagnosticPluginId()
                : installer == null ? "unattributed" : installer.getPluginId();
        this.diagnosticRuntime = xcHook instanceof PyMethodHook
                ? ((PyMethodHook) xcHook).diagnosticRuntimeOwner()
                : xcHook instanceof PyMethodReplacement
                ? ((PyMethodReplacement) xcHook).diagnosticRuntimeOwner()
                : installer == null ? "" : "installer:" + installer.getGeneration() + ":" + installer.getInstanceId();
        this.paramTypes = parameterTypesOf(member);
    }

    private AdapterParam claim(Pine.CallFrame cf) {
        long token = NimarkoCrashContext.beginInvocation();
        long startedMs = NimarkoCrashContext.invocationStartedMs(token);
        boolean claimed = false;
        try {
            NimarkoCrashContext.phase(token, startedMs, "adapter_claim", diagnosticTarget,
                    diagnosticPlugin, diagnosticRuntime);
            AdapterParam p;
            synchronized (params) {
                p = params.pollFirst();
            }
            if (p == null) p = new AdapterParam();
            p.invocationFrame = cf;
            p.method = member;
            p.thisObject = cf.thisObject;
            p.args = cf.args;
            p.captureInvocation(cf);
            p.setUserData(null);
            try { p.setResult(null); } catch (Throwable ignored) {}
            try { p.setThrowable(null); } catch (Throwable ignored) {}
            p.returnEarly = false;
            p.diagnosticToken = token;
            p.diagnosticStartedMs = startedMs;
            diagnosticPhase(p, "adapter_before_enter");
            claimed = true;
            return p;
        } finally {
            if (!claimed) NimarkoCrashContext.endInvocation(token);
        }
    }

    private void pushActive(AdapterParam p) {
        p.previousActive = activeParam.get();
        activeParam.set(p);
    }

    private AdapterParam popActive(Pine.CallFrame frame) {
        AdapterParam p = activeParam.get();
        if (p == null || p.invocationFrame != frame) return null;
        AdapterParam previous = p.previousActive;
        p.previousActive = null;
        if (previous == null) {
            activeParam.remove();
        } else {
            activeParam.set(previous);
        }
        return p;
    }

    private void release(AdapterParam p) {
        long diagnosticToken = p.diagnosticToken;
        try {
            diagnosticPhase(p, "adapter_scope_closed");
            p.diagnosticToken = 0;
            p.thisObject = null;
            p.args = null;
            p.method = null;
            p.setUserData(null);
            p.clearInvocation();
            synchronized (params) {
                if (params.size() < 8) {
                    params.offerFirst(p);
                }
            }
        } finally {
            NimarkoCrashContext.endInvocation(diagnosticToken);
        }
    }

    private void releaseFailedBefore(AdapterParam p) {
        try {


            if (activeParam.get() == p) popActive(p.invocationFrame);
        } finally {
            release(p);
        }
    }

    private boolean receiverMismatched(Pine.CallFrame cf) {
        if (cf == null) return true;
        if (PythonBoundarySanitizer.scan(cf.thisObject) == ScanResult.RAW_PROXY) {
            return true;
        }
        if (member instanceof Method
                && Modifier.isStatic(((Method) member).getModifiers())) {
            return cf.thisObject != null;
        }
        if (cf.thisObject == null) return true;
        Class<?> decl = member.getDeclaringClass();
        if (decl == null) return false;
        return !decl.isInstance(cf.thisObject);
    }

    private static boolean invocationUnverified(
            Pine.CallFrame cf) {
        return cf != null && scanInvocation(cf) != ScanResult.SAFE;
    }

    private static ScanResult scanInvocation(Pine.CallFrame cf) {
        ScanResult receiver = PythonBoundarySanitizer.scan(cf.thisObject);
        ScanResult args = PythonBoundarySanitizer.scan(cf.args);
        if (receiver == ScanResult.RAW_PROXY || args == ScanResult.RAW_PROXY) {
            return ScanResult.RAW_PROXY;
        }
        return receiver == ScanResult.SAFE && args == ScanResult.SAFE
                ? ScanResult.SAFE : ScanResult.INDETERMINATE;
    }

    private void skipCallbacks(Pine.CallFrame cf, AdapterParam skipped, boolean reject) {
        skipped.skipAfterCallback = true;
        skipped.unverifiedBaseline = !reject && !PythonBoundarySanitizer.isScanning();
        pushActive(skipped);
        if (reject) cf.setResult(defaultReturnFor(member));
        diagnosticPhase(skipped, "before_bridge_return_skipped");
    }

    private static Object defaultReturnFor(Member m) {
        if (!(m instanceof Method)) return null;
        Class<?> rt = ((Method) m).getReturnType();
        if (rt == null || !rt.isPrimitive() || rt == void.class) return null;
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == byte.class)    return (byte) 0;
        if (rt == short.class)   return (short) 0;
        if (rt == int.class)     return 0;
        if (rt == long.class)    return 0L;
        if (rt == float.class)   return 0f;
        if (rt == double.class)  return 0d;
        if (rt == char.class)    return '\0';
        return null;
    }

    private boolean argsTypeMismatched(Pine.CallFrame cf) {
        final Class<?>[] pt = paramTypes;
        if (pt == null || cf == null) return false;
        final Object[] args = cf.args;
        if (args == null || args.length != pt.length) return true;
        if (PythonBoundarySanitizer.scan(args) != ScanResult.SAFE) {
            return true;
        }
        for (int i = 0; i < pt.length; i++) {
            if (!argCompatible(pt[i], args[i])) return true;
        }
        return false;
    }

    private static boolean argCompatible(Class<?> pt, Object a) {
        if (a == null) {
            return !pt.isPrimitive();
        }
        if (pt.isPrimitive()) {
            if (pt == boolean.class) return a instanceof Boolean;
            if (pt == char.class)    return a instanceof Character;
            if (pt == byte.class)    return a instanceof Byte;
            if (pt == short.class)   return a instanceof Byte || a instanceof Short;
            if (pt == int.class)     return a instanceof Byte || a instanceof Short
                    || a instanceof Character || a instanceof Integer;
            if (pt == long.class)    return a instanceof Byte || a instanceof Short
                    || a instanceof Character || a instanceof Integer || a instanceof Long;
            if (pt == float.class)   return a instanceof Byte || a instanceof Short
                    || a instanceof Character || a instanceof Integer || a instanceof Long
                    || a instanceof Float;
            if (pt == double.class)  return a instanceof Byte || a instanceof Short
                    || a instanceof Character || a instanceof Integer || a instanceof Long
                    || a instanceof Float || a instanceof Double;
            return false;
        }
        return pt.isInstance(a);
    }

    private static Object normalizeResult(Member member, Object value) {
        return normalizeResult(member, value, false);
    }

    private static Object normalizeResult(Member member, Object value, boolean hostBaseline) {
        ScanResult scan = PythonBoundarySanitizer.scan(value);
        if (scan == ScanResult.RAW_PROXY
                || (!hostBaseline && scan == ScanResult.INDETERMINATE)) {
            return INVALID_RESULT;
        }
        if (!(member instanceof Method)) return value;
        Class<?> type = ((Method) member).getReturnType();
        if (type == void.class) return null;
        if (!type.isPrimitive()) {
            return value == null || type.isInstance(value) ? value : INVALID_RESULT;
        }
        if (type == boolean.class) {
            return value instanceof Boolean ? value : INVALID_RESULT;
        }
        if (type == char.class) {
            return value instanceof Character ? value : INVALID_RESULT;
        }
        if (!(value instanceof Number)) return INVALID_RESULT;
        Number number = (Number) value;
        if (type == byte.class) return number.byteValue();
        if (type == short.class) return number.shortValue();
        if (type == int.class) return number.intValue();
        if (type == long.class) return number.longValue();
        if (type == float.class) return number.floatValue();
        if (type == double.class) return number.doubleValue();
        return INVALID_RESULT;
    }

    private static Class<?>[] parameterTypesOf(Member m) {
        if (m instanceof Method) return ((Method) m).getParameterTypes();
        if (m instanceof Constructor) return ((Constructor<?>) m).getParameterTypes();
        return null;
    }

    @Override
    public void beforeCall(Pine.CallFrame cf) throws Throwable {
        if (runtimeSupportMethod && PluginHookGuard.isActive()) return;
        if (cf == null) {
            PluginHookGuard.error("nimarko: Pine supplied a null CallFrame for " + member);
            return;
        }
        AdapterParam param = claim(cf);
        try {
            diagnosticPhase(param, "before_scan_gate");
            if (PythonBoundarySanitizer.isScanning()) {
                skipCallbacks(cf, param, false);
                return;
            }
            diagnosticPhase(param, "before_bypass_check");
            if (cf.thisObject instanceof PluginHookBypassTarget
                    && ((PluginHookBypassTarget) cf.thisObject).shouldBypassPluginHooks()) {
                param.skipAfterCallback = true;
                pushActive(param);
                diagnosticPhase(param, "before_bridge_return_skipped");
                return;
            }
            if (activeParam.get() != null) {
                skipCallbacks(cf, param, false);
                return;
            }
            diagnosticPhase(param, "before_baseline_receiver_scan");
            if (receiverMismatched(cf)) {
                NimarkoCrashContext.anomaly("receiver_mismatch", diagnosticTarget,
                        diagnosticPlugin, diagnosticRuntime);
                skipCallbacks(cf, param, true);
                return;
            }
            diagnosticPhase(param, "before_baseline_scan");
            ScanResult baseline = scanInvocation(cf);
            if (baseline != ScanResult.SAFE) {
                skipCallbacks(cf, param, baseline == ScanResult.RAW_PROXY);
                return;
            }
            boolean callbackFailed = false;
            try {
                if (BEFORE_HOOKED_METHOD != null) {
                    diagnosticPhase(param, "callback_before_enter");
                    try {
                        BEFORE_HOOKED_METHOD.invoke(xcHook, param);
                    } finally {
                        diagnosticPhase(param, "callback_before_exit");
                    }
                }
            } catch (Throwable t) {
                NimarkoCrashContext.failure("pine_before_callback", diagnosticPlugin, diagnosticTarget,
                        t instanceof InvocationTargetException && t.getCause() != null
                                ? t.getCause() : t);
                PluginHookGuard.error("nimarko: hook callback threw for " + member, t);
                callbackFailed = true;
                param.restoreInvocation(cf);
                param.setResult(null);
                param.returnEarly = false;
            }
            if (!callbackFailed) {
                cf.thisObject = param.thisObject;
                cf.args = param.args;
                diagnosticPhase(param, "before_proxy_check");
                if (invocationUnverified(cf)) {
                    param.restoreInvocation(cf);
                }
            }
            if (param.hasThrowable()) {
                cf.setThrowable(param.getThrowable());
            } else if (param.returnEarly
                    || (!callbackFailed && xcHook instanceof XC_MethodReplacement)) {
                diagnosticPhase(param, "before_result_check");
                Object result = normalizeResult(member, param.getResult());
                if (result == INVALID_RESULT) {
                    param.restoreInvocation(cf);
                    param.setResult(null);
                    param.returnEarly = false;
                } else {
                    param.setResult(result);
                    cf.setResult(result);
                }
            } else {
                diagnosticPhase(param, "before_args_check");
                if (argsTypeMismatched(cf)) {
                    param.restoreInvocation(cf);
                } else {
                    diagnosticPhase(param, "before_receiver_check");
                    if (receiverMismatched(cf)) param.restoreInvocation(cf);
                }
            }
            pushActive(param);
            diagnosticPhase(param, "before_bridge_return");
        } catch (Throwable t) {
            diagnosticPhase(param, "before_bridge_throw");
            releaseFailedBefore(param);
            throw t;
        }
    }

    @Override
    public void afterCall(Pine.CallFrame cf) throws Throwable {
        if (runtimeSupportMethod && PluginHookGuard.isActive()) return;
        AdapterParam param = popActive(cf);
        if (param == null && activeParam.get() != null) {
            NimarkoCrashContext.anomaly("unmatched_after_state", diagnosticTarget,
                    diagnosticPlugin, diagnosticRuntime);
            return;
        }
        try {
            if (param == null && cf != null) {
                NimarkoCrashContext.anomaly("missing_before_state", diagnosticTarget,
                        diagnosticPlugin, diagnosticRuntime);
                param = claim(cf);
            }
            diagnosticPhase(param, "after_enter");
            if (param != null && param.skipAfterCallback) {
                diagnosticPhase(param, "after_skipped_result_scan");
                if (param.unverifiedBaseline && !cf.hasThrowable()
                        && PythonBoundarySanitizer.scan(cf.getResult()) == ScanResult.RAW_PROXY) {
                    cf.setResult(defaultReturnFor(member));
                }
                diagnosticPhase(param, "after_bridge_return_skipped");
                return;
            }
            if (xcHook instanceof XC_MethodReplacement) {
                diagnosticPhase(param, "after_bridge_return_replacement");
                return;
            }
            diagnosticPhase(param, "after_receiver_check");
            if (receiverMismatched(cf)) {
                NimarkoCrashContext.anomaly("after_receiver_mismatch", diagnosticTarget,
                        diagnosticPlugin, diagnosticRuntime);
                return;
            }
            diagnosticPhase(param, "after_capture");
            param.thisObject = cf.thisObject;
            param.args = cf.args;
            param.captureInvocation(cf);
            Object originalResult = cf.hasThrowable() ? null : cf.getResult();
            Throwable originalThrowable = cf.getThrowable();
            diagnosticPhase(param, "after_baseline_result_scan");
            ScanResult resultBaseline = PythonBoundarySanitizer.scan(originalResult);
            diagnosticPhase(param, "after_baseline_scan");
            if (scanInvocation(cf) != ScanResult.SAFE
                    || resultBaseline == ScanResult.INDETERMINATE) {
                if (originalThrowable == null && resultBaseline == ScanResult.RAW_PROXY) {
                    cf.setResult(defaultReturnFor(member));
                }
                return;
            }
            if (cf.hasThrowable()) {
                param.setThrowable(cf.getThrowable());
            } else {
                param.setResult(cf.getResult());
                param.returnEarly = false;
            }
            boolean callbackFailed = false;
            try {
                if (AFTER_HOOKED_METHOD != null) {
                    diagnosticPhase(param, "callback_after_enter");
                    try {
                        AFTER_HOOKED_METHOD.invoke(xcHook, param);
                    } finally {
                        diagnosticPhase(param, "callback_after_exit");
                    }
                }
            } catch (Throwable t) {
                NimarkoCrashContext.failure("pine_after_callback", diagnosticPlugin, diagnosticTarget,
                        t instanceof InvocationTargetException && t.getCause() != null
                                ? t.getCause() : t);
                PluginHookGuard.error("nimarko: afterHookedMethod threw", t);
                callbackFailed = true;
                param.restoreInvocation(cf);
                if (originalThrowable != null) {
                    param.setThrowable(originalThrowable);
                } else {
                    param.setResult(originalResult);
                    param.returnEarly = false;
                }
            }
            if (!callbackFailed) {
                cf.thisObject = param.thisObject;
                cf.args = param.args;
                diagnosticPhase(param, "after_args_check");
                if (invocationUnverified(cf)
                        || receiverMismatched(cf) || argsTypeMismatched(cf)) {
                    param.restoreInvocation(cf);
                }
            }
            if (param.hasThrowable()) {
                cf.setThrowable(param.getThrowable());
            } else {
                diagnosticPhase(param, "after_result_check");
                Object result = normalizeResult(member, param.getResult(),
                        originalThrowable == null && param.getResult() == originalResult);
                if (result == INVALID_RESULT) {
                    if (originalThrowable != null) {
                        cf.setThrowable(originalThrowable);
                        return;
                    }
                    result = normalizeResult(member, originalResult, true);
                    if (result == INVALID_RESULT) {
                        result = defaultReturnFor(member);
                    }
                }
                cf.setResult(result);
            }
            diagnosticPhase(param, "after_bridge_return");
        } finally {
            if (param != null) release(param);
        }
    }

    private void diagnosticPhase(AdapterParam param, String phase) {
        if (param != null && param.diagnosticToken != 0) {
            NimarkoCrashContext.phase(param.diagnosticToken, param.diagnosticStartedMs, phase, diagnosticTarget,
                    diagnosticPlugin, diagnosticRuntime);
        }
    }

    @Deprecated
    public static void precompileHotPath() {
    }
}
