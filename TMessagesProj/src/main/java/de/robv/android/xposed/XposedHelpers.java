package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader != null ? classLoader : XposedHelpers.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader != null ? classLoader : XposedHelpers.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static Method findMethodExact(String className, ClassLoader classLoader, String methodName, Object... parameterTypes) {
        Class<?> clazz = findClass(className, classLoader);
        return findMethodExact(clazz, methodName, parameterTypes);
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            Class<?>[] types = resolveTypes(clazz.getClassLoader(), parameterTypes);
            Method m = clazz.getDeclaredMethod(methodName, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        }
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            return findMethodExact(clazz, methodName, parameterTypes);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        try {
            Class<?>[] types = resolveTypes(clazz.getClassLoader(), parameterTypes);
            Constructor<?> c = clazz.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Class<?>[] argTypes = inferTypes(args);
            Method m = obj.getClass().getMethod(methodName, argTypes);
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Class<?>[] argTypes = inferTypes(args);
            Method m = clazz.getMethod(methodName, argTypes);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            return f.get(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            Field f = findField(clazz, fieldName);
            return f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field f = findField(clazz, fieldName);
            f.set(null, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            return f.getInt(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setInt(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            return f.getLong(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            return f.getBoolean(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Class<?>[] resolveTypes(ClassLoader cl, Object[] params) {
        if (params == null) return new Class<?>[0];
        Class<?>[] out = new Class<?>[params.length];
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Class<?>) {
                out[i] = (Class<?>) p;
            } else if (p instanceof String) {
                try {
                    out[i] = Class.forName((String) p, false, cl != null ? cl : XposedHelpers.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new ClassNotFoundError(e);
                }
            } else {
                out[i] = p != null ? p.getClass() : Object.class;
            }
        }
        return out;
    }

    private static Class<?>[] inferTypes(Object[] args) {
        if (args == null) return new Class<?>[0];
        Class<?>[] out = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        return out;
    }

    public static class ClassNotFoundError extends Error {
        public ClassNotFoundError(Throwable cause) {
            super(cause);
        }
        public ClassNotFoundError(String msg) {
            super(msg);
        }
    }
}
