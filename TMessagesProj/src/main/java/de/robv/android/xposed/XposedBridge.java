package de.robv.android.xposed;

import org.telegram.messenger.FileLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class XposedBridge {

    private XposedBridge() {}

    private static boolean ensureHookBackend(Member member) {
        if (org.telegram.messenger.ApplicationLoader.isPineAvailable()) {
            return true;
        }
        boolean mainThread =
                android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        if (!org.telegram.messenger.ApplicationLoader.wasPineInitializationAttempted()
                && !mainThread) {
            org.telegram.messenger.ApplicationLoader.ensurePineInited();
        }
        if (!mainThread
                && org.telegram.messenger.ApplicationLoader
                        .awaitPineInitializationForHooks()) {
            return true;
        }
        String reason = org.telegram.messenger.ApplicationLoader.getPineUnavailableReason();
        FileLog.w("nimarko: Pine unavailable; hook ignored for "
                + describeMember(member)
                + (reason == null ? "" : " (" + reason + ")"));
        return false;
    }

    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        if (member == null) {
            FileLog.w("nimarko: hookMethod(null) ignored");
            return null;
        }
        if (callback == null) {
            FileLog.w("nimarko: hookMethod(callback=null) ignored for " + member);
            return null;
        }
        if (!ensureHookBackend(member)) {
            return null;
        }
        try {
            app.nimarkogram.messenger.plugins.xposed.PineAdapter adapter =
                    new app.nimarkogram.messenger.plugins.xposed.PineAdapter(member, callback);
            
            Object pineHandle = top.canyie.pine.Pine.hook(member, adapter);
            if (pineHandle == null) {
                FileLog.w("nimarko: Pine.hook returned null for " + describeMember(member)
                        + " — hook NOT installed");
                return null;
            }
            
            XC_MethodHook.Unhook ourUnhook = callback.new Unhook(member);
            ourUnhook.setPineUnhook(pineHandle);
            return ourUnhook;
        } catch (Throwable t) {
            
            String sig;
            try {
                sig = describeMember(member);
            } catch (Throwable inner) {
                sig = String.valueOf(member);
            }
            FileLog.e("nimarko: Pine.hook FAILED for " + sig
                    + " — " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            return null;
        }
    }

    private static String describeMember(Member member) {
        if (member == null) return "null";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(member.getDeclaringClass().getName());
        } catch (Throwable t) {
            sb.append("?");
        }
        sb.append('.');
        try {
            sb.append(member.getName());
        } catch (Throwable t) {
            sb.append("?");
        }
        sb.append('(');
        try {
            Class<?>[] params = null;
            if (member instanceof Method) {
                params = ((Method) member).getParameterTypes();
            } else if (member instanceof Constructor) {
                params = ((Constructor<?>) member).getParameterTypes();
            }
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(',');
                    try {
                        sb.append(params[i] == null ? "null" : params[i].getName());
                    } catch (Throwable t) {
                        sb.append("?");
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("?params");
        }
        sb.append(')');
        return sb.toString();
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        if (hookClass == null || methodName == null) {
            return result;
        }
        
        for (Method m : hookClass.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            XC_MethodHook.Unhook u = hookMethod(m, callback);
            if (u != null) {
                result.add(u);
            }
        }
        return result;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        if (hookClass == null) {
            return result;
        }
        for (Constructor<?> c : hookClass.getDeclaredConstructors()) {
            XC_MethodHook.Unhook u = hookMethod(c, callback);
            if (u != null) {
                result.add(u);
            }
        }
        return result;
    }

    private static List<Method> getDeclaredMethodsRecursive(Class<?> cls) {
        List<Method> out = new ArrayList<>();
        if (cls != null) {
            for (Method m : cls.getDeclaredMethods()) {
                out.add(m);
            }
        }
        return out;
    }

    public static void log(String text) {
        FileLog.d("xposed: " + text);
    }

    public static void log(Throwable t) {
        FileLog.e("xposed", t);
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        if (method == null) {
            return null;
        }
        if (!org.telegram.messenger.ApplicationLoader.isPineAvailable()) {
            return invokeReflectively(method, thisObject, args);
        }
        
        try {
            return top.canyie.pine.Pine.invokeOriginalMethod(method, thisObject, args);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            throw (cause != null) ? cause : ite;
        }
    }

    private static Object invokeReflectively(Member method, Object thisObject, Object[] args) throws Throwable {
        if (method instanceof Method) {
            Method m = (Method) method;
            m.setAccessible(true);
            try {
                return m.invoke(thisObject, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                throw (cause != null) ? cause : ite;
            }
        }
        if (method instanceof Constructor) {
            Constructor<?> c = (Constructor<?>) method;
            if (thisObject != null) {
                throw new IllegalArgumentException(
                        "Cannot invoke an unhooked constructor with a receiver");
            }
            c.setAccessible(true);
            try {
                return c.newInstance(args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                throw (cause != null) ? cause : ite;
            }
        }
        throw new IllegalArgumentException("Only methods and constructors can be invoked: " + method);
    }

    public static void disableHiddenApiRestrictions() {
        
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass
                    .addHiddenApiExemptions("L");
        } catch (Throwable t) {
            FileLog.w("nimarko: HiddenApiBypass failed: " + t);
        }
    }

    public static void disableProfileSaver() {
        if (!org.telegram.messenger.ApplicationLoader.isPineAvailable()) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            
            FileLog.w("nimarko: disableProfileSaver ignored on modern ART");
            return;
        }
        try {
            top.canyie.pine.Pine.disableProfileSaver();
        } catch (Throwable t) {
            FileLog.w("nimarko: disableProfileSaver failed: " + t);
        }
    }
}
