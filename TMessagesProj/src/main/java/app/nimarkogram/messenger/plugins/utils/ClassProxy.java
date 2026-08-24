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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.telegram.messenger.FileLog;

/**
 * Java port of {@code com.exteragram.messenger.plugins.utils.ClassProxy}.
 *
 * <p>The original extera class uses dexmaker to synthesise runtime classes
 * that bridge between Java callers and Python plugin objects, and MVEL2 to
 * evaluate hooked expressions. We do not ship MVEL — that path is gated by
 * {@link #setMvelEvaluator(MvelEvaluator)} so callers can wire in their own
 * expression evaluator if they need MVEL semantics.
 *
 * <p>The dexmaker-based class generation in {@link #generateProxyClass} is
 * preserved verbatim where possible; without the original Kotlin source we
 * keep the public surface (the {@code FieldMethodSpec}, {@code DexMakerHook}
 * types, and the static cache) and provide a working {@link #invoke} method.
 */
public class ClassProxy {

    private static volatile ClassLoader sharedGeneratedClassLoader;
    private static final String PYTHON_PEER_FIELD_NAME = "__nimarko_python_peer";
    private static final ConcurrentHashMap<String, Serializable> mvelExpressionCache = new ConcurrentHashMap<>();
    private static final Object generatedClassLoaderLock = new Object();
    private static final int MAX_METHOD_CACHE_SIZE = 128;

    /** Optional plug-in for MVEL expression evaluation. */
    public interface MvelEvaluator {
        Object evaluate(String expression, Map<String, Object> context);
    }
    private static volatile MvelEvaluator mvelEvaluator;
    public static void setMvelEvaluator(MvelEvaluator e) { mvelEvaluator = e; }

    public interface DexMakerHook {
        void apply(DexMaker dexMaker, TypeId<?> typeId, TypeId<?> typeId2, List<TypeId<?>> list);
    }

    /** Mirror of extera's FieldMethodSpec — describes a generated getter/setter. */
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
    private final ConcurrentHashMap<MethodCacheKey, Method> methodCache = new ConcurrentHashMap<>();

    public ClassProxy(Class<?> targetClass) {
        this.targetClass = targetClass;
    }

    public ClassProxy addHook(String methodName, String before, String after) {
        hooks.put(methodName, new HookSpec(methodName, before, after));
        return this;
    }

    /**
     * Invoke a method on a target instance, applying any registered MVEL
     * before/after hooks. Without an evaluator the hook bodies are skipped.
     */
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
        MethodCacheKey cacheKey = new MethodCacheKey(methodName, args);
        Method cached = methodCache.get(cacheKey);
        if (cached != null) {
            return cached.invoke(target, args);
        }

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
        if (methodCache.size() >= MAX_METHOD_CACHE_SIZE) {
            methodCache.clear();
        }
        Method raced = methodCache.putIfAbsent(cacheKey, bestMatch);
        return (raced != null ? raced : bestMatch).invoke(target, args);
    }

    private static final class MethodCacheKey {
        private final String name;
        private final Class<?>[] argumentTypes;
        private final int hashCode;

        MethodCacheKey(String name, Object[] args) {
            this.name = name;
            int count = args == null ? 0 : args.length;
            this.argumentTypes = new Class<?>[count];
            for (int i = 0; i < count; i++) {
                Object argument = args[i];
                argumentTypes[i] = argument == null ? null : argument.getClass();
            }
            this.hashCode = 31 * name.hashCode() + Arrays.hashCode(argumentTypes);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MethodCacheKey)) return false;
            MethodCacheKey key = (MethodCacheKey) other;
            return name.equals(key.name) && Arrays.equals(argumentTypes, key.argumentTypes);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
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

    /**
     * Generates a synthetic subclass of {@code parent} that forwards every
     * non-final method to an injected python peer. Returns the generated
     * Class, loaded into the shared generated class loader so subsequent
     * generations re-use the same loader (DexMaker requires this).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Class<?> generateProxyClass(Class<?> parent, String suffix, DexMakerHook hook) {
        try {
            DexMaker dm = new DexMaker();
            TypeId generated = TypeId.get("L" + parent.getName().replace('.', '/') + "$Nimarko$" + suffix + ";");
            TypeId parentType = TypeId.get(parent);
            List<TypeId<?>> interfaces = new ArrayList<>();
            dm.declare(generated, "Generated.java", Modifier.PUBLIC, parentType);
            // Inject a String peer-id field so the python engine can look up
            // the wrapped object by name.
            FieldId peerField = generated.getField(TypeId.STRING, PYTHON_PEER_FIELD_NAME);
            dm.declare(peerField, Modifier.PUBLIC, null);
            // Synthesize a no-arg constructor that invokes the parent's no-arg ctor.
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
            ClassLoader generatedParent = sharedGeneratedClassLoader != null
                    ? sharedGeneratedClassLoader : parent;
            sharedGeneratedClassLoader = new InMemoryDexClassLoader(
                    ByteBuffer.wrap(dex), generatedParent);
            return sharedGeneratedClassLoader;
        }
    }

    public static String getPythonPeerFieldName() { return PYTHON_PEER_FIELD_NAME; }

    /** Cached compiled MVEL expression; returns null if no evaluator is set. */
    public static Serializable getCompiledExpression(String expression) {
        if (expression == null) return null;
        return mvelExpressionCache.get(expression);
    }

    public static void putCompiledExpression(String expression, Serializable compiled) {
        if (expression != null && compiled != null) {
            // NG (OOM): bound this cache — a plugin building dynamic expression strings would
            // otherwise grow it (compiled MVEL ASTs) without limit. Cap like MenuItemRecord.
            if (mvelExpressionCache.size() > 256) {
                mvelExpressionCache.clear();
            }
            mvelExpressionCache.put(expression, compiled);
        }
    }
}
