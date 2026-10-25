/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.plugins.bridge;

import android.content.Context;
import android.os.Build;
import androidx.annotation.Keep;
import com.android.dx.Code;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import top.canyie.pine.Pine;

@Keep
public final class ManagedHookBridge {
    private static final String GENERATED_PREFIX = "app.nimarkogram.messenger.plugins.bridge.generated.ManagedHook";
    private static final AtomicInteger sequence = new AtomicInteger();
    private static final Pine.HookEntryFactory FACTORY = ManagedHookBridge::create;
    private static Context applicationContext;

    @Keep
    public interface Dispatcher {
        Object invoke(Object receiver, Object[] args) throws Throwable;
    }

    private ManagedHookBridge() {}

    public static synchronized void install(Context context) {
        if (Pine.getHookEntryFactory() == FACTORY) return;
        if (context == null) throw new IllegalStateException("Application context is unavailable");
        Context app = context.getApplicationContext();
        applicationContext = app == null ? context : app;
        Pine.setHookEntryFactory(FACTORY);
    }

    private static Pine.HookEntry create(Pine.HookRecord record) throws Exception {
        Member target = record.target;
        for (Class<?> owner = target.getDeclaringClass(); owner != null; owner = owner.getEnclosingClass()) {
            if (owner == ManagedHookBridge.class || owner.getName().startsWith(GENERATED_PREFIX)) {
                throw new IllegalArgumentException("Managed bridge implementation cannot be hooked");
            }
        }
        Class<?> result = target instanceof Method ? ((Method) target).getReturnType() : void.class;
        return generate(applicationContext, record.paramTypes, result, record.isStatic,
                (receiver, args) -> Pine.handleCall(record, receiver, args));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Pine.HookEntry generate(Context context, Class<?>[] parameters, Class<?> returnType, boolean isStatic, Dispatcher dispatcher) throws Exception {
        String name = GENERATED_PREFIX + sequence.incrementAndGet();
        TypeId generated = TypeId.get("L" + name.replace('.', '/') + ";");
        TypeId object = TypeId.OBJECT, objects = TypeId.get(Object[].class), dispatcherType = TypeId.get(Dispatcher.class);
        Class<?> erasedReturn = returnType.isPrimitive() ? returnType : Object.class;
        TypeId resultType = TypeId.get(erasedReturn);
        int offset = isStatic ? 0 : 1;
        TypeId[] signature = new TypeId[parameters.length + offset];
        Class<?>[] reflected = new Class<?>[signature.length];
        if (!isStatic) { signature[0] = object; reflected[0] = Object.class; }
        for (int i = 0; i < parameters.length; i++) {
            reflected[i + offset] = parameters[i].isPrimitive() ? parameters[i] : Object.class;
            signature[i + offset] = TypeId.get(reflected[i + offset]);
        }
        DexMaker maker = new DexMaker();
        maker.declare(generated, "ManagedHook.java", Modifier.PUBLIC | Modifier.FINAL, object);
        FieldId handler = generated.getField(dispatcherType, "dispatcher");
        maker.declare(handler, Modifier.PUBLIC | Modifier.STATIC | Modifier.VOLATILE, null);
        MethodId bridge = generated.getMethod(resultType, "entry", signature);
        Code code = maker.declare(bridge, Modifier.PUBLIC | Modifier.STATIC);
        Local count = code.newLocal(TypeId.INT), at = code.newLocal(TypeId.INT);
        Local args = code.newLocal(objects), receiver = isStatic ? code.newLocal(object) : code.getParameter(0, object);
        Local handlerLocal = code.newLocal(dispatcherType), returned = code.newLocal(object);
        Local[] incoming = new Local[parameters.length], boxed = new Local[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            incoming[i] = code.getParameter(i + offset, signature[i + offset]);
            if (parameters[i].isPrimitive()) boxed[i] = code.newLocal(TypeId.get(wrapper(parameters[i])));
        }
        Local resultLocal = erasedReturn == void.class || !erasedReturn.isPrimitive() ? null : code.newLocal(resultType);
        Local resultBox = resultLocal == null ? null : code.newLocal(TypeId.get(wrapper(erasedReturn)));
        code.loadConstant(count, parameters.length);
        code.newArray(args, count);
        if (isStatic) code.loadConstant(receiver, null);
        for (int i = 0; i < parameters.length; i++) {
            Local value = incoming[i];
            if (parameters[i].isPrimitive()) {
                TypeId wrapper = TypeId.get(wrapper(parameters[i]));
                code.invokeStatic(wrapper.getMethod(wrapper, "valueOf", signature[i + offset]), boxed[i], value);
                value = boxed[i];
            }
            code.loadConstant(at, i);
            code.aput(args, at, value);
        }
        code.sget(handler, handlerLocal);
        code.invokeInterface(dispatcherType.getMethod(object, "invoke", object, objects), returned, handlerLocal, receiver, args);
        if (erasedReturn == void.class) code.returnVoid();
        else if (erasedReturn.isPrimitive()) {
            code.cast(resultBox, returned);
            code.invokeVirtual(TypeId.get(wrapper(erasedReturn)).getMethod(resultType, erasedReturn.getName() + "Value"), resultLocal, resultBox);
            code.returnValue(resultLocal);
        } else code.returnValue(returned);
        Code backup = maker.declare(generated.getMethod(resultType, "backup", signature), Modifier.PUBLIC | Modifier.STATIC);
        if (erasedReturn == void.class) backup.returnVoid();
        else {
            Local zero = backup.newLocal(resultType);
            backup.loadConstant(zero, defaultValue(erasedReturn)); backup.returnValue(zero);
        }
        ClassLoader loader = Build.VERSION.SDK_INT >= 26
                ? Api26.load(maker.generate(), ManagedHookBridge.class.getClassLoader())
                : maker.generateAndLoad(ManagedHookBridge.class.getClassLoader(), context.getCodeCacheDir());
        Class<?> owner = Class.forName(name, true, loader);
        owner.getField("dispatcher").set(null, dispatcher);
        Method entry = owner.getDeclaredMethod("entry", reflected);
        Method original = owner.getDeclaredMethod("backup", reflected);
        entry.setAccessible(true); original.setAccessible(true);
        return new Pine.HookEntry(entry, original);
    }
    private static Class<?> wrapper(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        throw new IllegalArgumentException(type.getName());
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        throw new IllegalArgumentException(type.getName());
    }
    private static final class Api26 {
        static ClassLoader load(byte[] dex, ClassLoader parent) { return new dalvik.system.InMemoryDexClassLoader(ByteBuffer.wrap(dex), parent); }
    }
}
