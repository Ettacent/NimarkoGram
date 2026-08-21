package app.nimarkogram.messenger.plugins.xposed;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;

import org.telegram.messenger.FileLog;

import app.nimarkogram.messenger.plugins.bridge.PythonBoundarySanitizer;

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
            FileLog.e("nimarko: PineAdapter reflection init failed", t);
        }
        BEFORE_HOOKED_METHOD = before;
        AFTER_HOOKED_METHOD = after;
    }

    private final XC_MethodHook xcHook;
    private final Member member;
    private final Class<?>[] paramTypes;
    private static final Object INVALID_RESULT = new Object();

    private static final class AdapterParam extends XC_MethodHook.MethodHookParam {
        AdapterParam previousActive;
        Object originalThisObject;
        Object[] originalArgsReference;
        Object[] originalArgsSnapshot;
        int originalArgsCount;
        boolean skipAfterCallback;

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
            originalThisObject = null;
            originalArgsReference = null;
            if (originalArgsSnapshot != null) {
                Arrays.fill(originalArgsSnapshot, null);
            }
            originalArgsCount = 0;
            skipAfterCallback = false;
            previousActive = null;
        }
    }

    private final ArrayDeque<AdapterParam> params = new ArrayDeque<>(4);
    private final ThreadLocal<AdapterParam> activeParam = new ThreadLocal<>();

    public PineAdapter(Member member, XC_MethodHook xcHook) {
        this.member = member;
        this.xcHook = xcHook;
        this.paramTypes = parameterTypesOf(member);
    }

    private AdapterParam claim(Pine.CallFrame cf) {
        AdapterParam p;
        synchronized (params) {
            p = params.pollFirst();
        }
        if (p == null) p = new AdapterParam();
        p.method = member;
        p.thisObject = cf.thisObject;
        p.args = cf.args;
        p.captureInvocation(cf);
        p.setUserData(null);
        try { p.setResult(null); } catch (Throwable ignored) {}
        try { p.setThrowable(null); } catch (Throwable ignored) {}
        p.returnEarly = false;
        return p;
    }

    private void pushActive(AdapterParam p) {
        p.previousActive = activeParam.get();
        activeParam.set(p);
    }

    private AdapterParam popActive() {
        AdapterParam p = activeParam.get();
        if (p == null) return null;
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
    }

    private boolean receiverMismatched(Pine.CallFrame cf) {
        if (cf == null) return true;
        if (PythonBoundarySanitizer.containsRawPythonProxy(
                cf.thisObject)) {
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

    private static String receiverName(Pine.CallFrame cf) {
        if (cf == null) return "<null call frame>";
        Object receiver = cf.thisObject;
        return receiver == null ? "<null>" : receiver.getClass().getName();
    }

    private static boolean invocationContainsRawPythonProxy(
            Pine.CallFrame cf) {
        return cf != null
                && (PythonBoundarySanitizer.containsRawPythonProxy(
                cf.thisObject)
                || PythonBoundarySanitizer.containsRawPythonProxy(
                cf.args));
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
        if (PythonBoundarySanitizer.containsRawPythonProxy(args)) {
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
        if (PythonBoundarySanitizer.containsRawPythonProxy(value)) {
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
        if (m instanceof java.lang.reflect.Constructor) return ((java.lang.reflect.Constructor<?>) m).getParameterTypes();
        return null;
    }

    @Override
    public void beforeCall(Pine.CallFrame cf) throws Throwable {
        if (cf == null) {
            FileLog.e("nimarko: Pine supplied a null CallFrame for " + member);
            return;
        }
        if (cf.thisObject instanceof PluginHookBypassTarget
                && ((PluginHookBypassTarget) cf.thisObject).shouldBypassPluginHooks()) {
            AdapterParam skipped = claim(cf);
            skipped.skipAfterCallback = true;
            pushActive(skipped);
            return;
        }
        if (receiverMismatched(cf)) {
            FileLog.w("nimarko: Pine type-mismatch on " + member
                    + " — receiver " + receiverName(cf)
                    + " is not a " + member.getDeclaringClass().getName()
                    + "; skipping invocation to avoid Pine crash");
            AdapterParam skipped = claim(cf);
            skipped.skipAfterCallback = true;
            pushActive(skipped);
            cf.setResult(defaultReturnFor(member));
            return;
        }
        AdapterParam param = claim(cf);
        boolean callbackFailed = false;
        try {
            try {
                if (BEFORE_HOOKED_METHOD != null) {
                    BEFORE_HOOKED_METHOD.invoke(xcHook, param);
                }
            } catch (Throwable t) {
                FileLog.e("nimarko: hook callback threw for " + member, t);
                callbackFailed = true;
                param.restoreInvocation(cf);
                param.setResult(null);
                param.returnEarly = false;
            }
            if (!callbackFailed) {
                cf.thisObject = param.thisObject;
                cf.args = param.args;
                if (invocationContainsRawPythonProxy(cf)) {
                    FileLog.w("nimarko: raw Python proxy in hook invocation state on "
                            + member + " — restoring original receiver/arguments");
                    param.restoreInvocation(cf);
                }
            }
            if (param.hasThrowable()) {
                cf.setThrowable(param.getThrowable());
            } else if (param.returnEarly
                    || (!callbackFailed && xcHook instanceof XC_MethodReplacement)) {
                Object result = normalizeResult(member, param.getResult());
                if (result == INVALID_RESULT) {
                    FileLog.w("nimarko: incompatible hook result on " + member
                            + " — running original method instead");
                    param.restoreInvocation(cf);
                    param.setResult(null);
                    param.returnEarly = false;
                } else {
                    param.setResult(result);
                    cf.setResult(result);
                }
            } else if (argsTypeMismatched(cf)) {
                FileLog.w("nimarko: Pine arg type-mismatch on " + member
                        + " from a plugin hook — restoring original arguments");
                param.restoreInvocation(cf);
            } else if (receiverMismatched(cf)) {
                FileLog.w("nimarko: Pine receiver type-mismatch on " + member
                        + " after plugin hook — restoring original receiver");
                param.restoreInvocation(cf);
            }
            pushActive(param);
        } catch (Throwable t) {
            release(param);
            throw t;
        }
    }

    @Override
    public void afterCall(Pine.CallFrame cf) throws Throwable {
        AdapterParam param = popActive();
        if (param != null && param.skipAfterCallback) {
            release(param);
            return;
        }
        if (xcHook instanceof XC_MethodReplacement) {
            if (param != null) release(param);
            return;
        }
        if (receiverMismatched(cf)) {
            if (param != null) release(param);
            return;
        }
        if (param == null) {
            FileLog.w("nimarko: missing before-hook state for " + member);
            param = claim(cf);
        }
        try {
            param.thisObject = cf.thisObject;
            param.args = cf.args;
            param.captureInvocation(cf);
            Object originalResult = cf.hasThrowable() ? null : cf.getResult();
            Throwable originalThrowable = cf.getThrowable();
            if (cf.hasThrowable()) {
                param.setThrowable(cf.getThrowable());
            } else {
                param.setResult(cf.getResult());
                param.returnEarly = false;
            }
            boolean callbackFailed = false;
            try {
                if (AFTER_HOOKED_METHOD != null) {
                    AFTER_HOOKED_METHOD.invoke(xcHook, param);
                }
            } catch (Throwable t) {
                FileLog.e("nimarko: afterHookedMethod threw", t);
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
                if (receiverMismatched(cf) || argsTypeMismatched(cf)) {
                    FileLog.w("nimarko: incompatible after-hook invocation state on "
                            + member + " — restoring original receiver/arguments");
                    param.restoreInvocation(cf);
                }
            }
            if (param.hasThrowable()) {
                cf.setThrowable(param.getThrowable());
            } else {
                Object result = normalizeResult(member, param.getResult());
                if (result == INVALID_RESULT) {
                    FileLog.w("nimarko: incompatible after-hook result on " + member
                            + " — restoring original result");
                    result = normalizeResult(member, originalResult);
                    if (result == INVALID_RESULT) {
                        result = defaultReturnFor(member);
                    }
                }
                cf.setResult(result);
            }
        } finally {
            release(param);
        }
    }

    @Deprecated
    public static void precompileHotPath() {
    }
}
