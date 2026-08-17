package app.nimarkogram.messenger.plugins.utils;

import com.android.dx.Code;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;

import dalvik.system.InMemoryDexClassLoader;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.telegram.messenger.FileLog;

public class ClassProxy {

    private static volatile ClassLoader sharedGeneratedClassLoader;
    private static final String PYTHON_PEER_FIELD_NAME = "__nimarko_python_peer";
    private static final ConcurrentHashMap<String, Serializable> mvelExpressionCache = new ConcurrentHashMap<>();
    private static final Object generatedClassLoaderLock = new Object();

    public interface MvelEvaluator {
        Object evaluate(String expression, Map<String, Object> context);
    }
    private static volatile MvelEvaluator mvelEvaluator;
    public static void setMvelEvaluator(MvelEvaluator e) { mvelEvaluator = e; }

    public interface DexMakerHook {
        void apply(DexMaker dexMaker, TypeId<?> typeId, TypeId<?> typeId2, List<TypeId<?>> list);
    }

    public static final class FieldMethodSpec {
        private final String name;
        private final int modifiers;
        private final boolean getter;

        public FieldMethodSpec(String name, int modifiers, boolean getter) {
            this.name = name;
            this.modifiers = modifiers;
            this.getter = getter;
        }
        public String getName() { return name; }
        public int getModifiers() { return modifiers; }
        public boolean isGetter() { return getter; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FieldMethodSpec)) return false;
            FieldMethodSpec other = (FieldMethodSpec) o;
            return getter == other.getter && modifiers == other.modifiers && Objects.equals(name, other.name);
        }
        @Override public int hashCode() { return Objects.hash(name, modifiers, getter); }
        @Override public String toString() {
            return "FieldMethodSpec[name=" + name + ", modifiers=" + modifiers + ", getter=" + getter + "]";
        }
    }

    public static class HookSpec {
        public final String name;
        public final String before;
        public final String after;
        public HookSpec(String name, String before, String after) {
            this.name = name; this.before = before; this.after = after;
        }
    }

    private final Class<?> targetClass;
    private final Map<String, HookSpec> hooks = new LinkedHashMap<>();

    public ClassProxy(Class<?> targetClass) {
        this.targetClass = targetClass;
    }

    public ClassProxy addHook(String methodName, String before, String after) {
        hooks.put(methodName, new HookSpec(methodName, before, after));
        return this;
    }

    public Object invoke(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        HookSpec hs = hooks.get(methodName);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("target", target);
        ctx.put("args", args);
        MvelEvaluator ev = mvelEvaluator;
        if (hs != null && hs.before != null && ev != null) {
            try { ev.evaluate(hs.before, ctx); }
            catch (Throwable t) { FileLog.e("nimarko: ClassProxy before-hook failed", t); }
        }
        Object result = findAndInvoke(target, methodName, args);
        ctx.put("result", result);
        if (hs != null && hs.after != null && ev != null) {
            try {
                Object newResult = ev.evaluate(hs.after, ctx);
                if (newResult != null) result = newResult;
            } catch (Throwable t) { FileLog.e("nimarko: ClassProxy after-hook failed", t); }
        }
        return result;
    }

    private Object findAndInvoke(Object target, String methodName, Object[] args) throws ReflectiveOperationException {
        Method bestMatch = null;
        int bestScore = -1;
        for (Method m : targetClass.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != (args == null ? 0 : args.length)) continue;
            int score = 0;
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                Object a = args[i];
                if (a == null) {
                    if (params[i].isPrimitive()) { ok = false; break; }
                    score++;
                } else if (params[i].isInstance(a) || boxesMatch(params[i], a.getClass())) {
                    score += 2;
                } else {
                    ok = false; break;
                }
            }
            if (ok && score > bestScore) {
                bestMatch = m;
                bestScore = score;
            }
        }
        if (bestMatch == null) {
            throw new NoSuchMethodException(targetClass.getName() + "." + methodName);
        }
        bestMatch.setAccessible(true);
        return bestMatch.invoke(target, args);
    }

    private static boolean boxesMatch(Class<?> param, Class<?> argClass) {
        if (param == int.class && argClass == Integer.class) return true;
        if (param == long.class && argClass == Long.class) return true;
        if (param == boolean.class && argClass == Boolean.class) return true;
        if (param == float.class && argClass == Float.class) return true;
        if (param == double.class && argClass == Double.class) return true;
        if (param == short.class && argClass == Short.class) return true;
        if (param == byte.class && argClass == Byte.class) return true;
        if (param == char.class && argClass == Character.class) return true;
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Class<?> generateProxyClass(Class<?> parent, String suffix, DexMakerHook hook) {
        try {
            DexMaker dm = new DexMaker();
            TypeId generated = TypeId.get("L" + parent.getName().replace('.', '/') + "$Nimarko$" + suffix + ";");
            TypeId parentType = TypeId.get(parent);
            List<TypeId<?>> interfaces = new ArrayList<>();
            dm.declare(generated, "Generated.java", Modifier.PUBLIC, parentType);
            
            FieldId peerField = generated.getField(TypeId.STRING, PYTHON_PEER_FIELD_NAME);
            dm.declare(peerField, Modifier.PUBLIC, null);
            
            MethodId ctor = generated.getConstructor();
            Code code = dm.declare(ctor, Modifier.PUBLIC);
            Local self = code.getThis(generated);
            code.invokeDirect(parentType.getConstructor(), null, self);
            code.returnVoid();
            if (hook != null) {
                try { hook.apply(dm, generated, parentType, interfaces); }
                catch (Throwable t) { FileLog.e("nimarko: ClassProxy hook failed", t); }
            }
            byte[] dex = dm.generate();
            ClassLoader cl = getSharedLoader(parent.getClassLoader(), dex);
            return cl.loadClass(parent.getName() + "$Nimarko$" + suffix);
        } catch (Throwable t) {
            FileLog.e("nimarko: ClassProxy.generateProxyClass failed", t);
            return null;
        }
    }

    private static ClassLoader getSharedLoader(ClassLoader parent, byte[] dex) {
        synchronized (generatedClassLoaderLock) {
            if (sharedGeneratedClassLoader == null) {
                sharedGeneratedClassLoader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), parent);
            }
            return sharedGeneratedClassLoader;
        }
    }

    public static String getPythonPeerFieldName() { return PYTHON_PEER_FIELD_NAME; }

    public static Serializable getCompiledExpression(String expression) {
        if (expression == null) return null;
        return mvelExpressionCache.get(expression);
    }

    public static void putCompiledExpression(String expression, Serializable compiled) {
        if (expression != null && compiled != null) {
            
            if (mvelExpressionCache.size() > 256) {
                mvelExpressionCache.clear();
            }
            mvelExpressionCache.put(expression, compiled);
        }
    }
}
