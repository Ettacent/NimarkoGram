package app.nimarkogram.messenger.plugins.bridge;

import com.chaquo.python.PyObject;
import com.chaquo.python.PyProxy;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

import app.nimarkogram.messenger.plugins.PluginsController;

public final class PythonBoundarySanitizer {
    public static final Object UNSAFE_VALUE = new Object();

    private static final int MAX_NESTING = 16;
    private static final int MAX_VISITED = 256;

    private PythonBoundarySanitizer() {
    }

    public static Object convertPythonResult(
            PyObject result,
            Class<?> declaredType,
            PluginsController.PluginRuntimeToken runtimeToken) {
        if (result == null || declaredType == null
                || declaredType == Void.TYPE) {
            return null;
        }
        if (declaredType.isInterface()) {
            Object generic = result.toJava(Object.class);
            if (generic == null) {
                return null;
            }
            if (declaredType.isInstance(generic)
                    && !containsRawPythonProxy(generic)) {
                return generic;
            }
            if (runtimeToken == null
                    || PluginsController.getInstance()
                    .getPluginRuntimeTaskDecision(runtimeToken)
                    != PluginsController.RUNTIME_TASK_RUN) {
                return UNSAFE_VALUE;
            }
            return PythonInterfaceProxy.create(
                    result, runtimeToken, new Class<?>[]{declaredType});
        }

        Object converted = result.toJava(declaredType);
        return containsRawPythonProxy(converted)
                ? UNSAFE_VALUE : converted;
    }

    public static boolean containsRawPythonProxy(Object value) {
        try {
            return containsRawPythonProxy(
                    value, new IdentityHashMap<>(), 0, new int[]{0});
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable traversalFailure) {
            
            return true;
        }
    }

    private static boolean containsRawPythonProxy(
            Object value,
            IdentityHashMap<Object, Boolean> visited,
            int depth,
            int[] visitedCount) {
        if (value == null) return false;
        if (value instanceof PyProxy || value instanceof PyObject) return true;
        if (depth >= MAX_NESTING || visitedCount[0] >= MAX_VISITED) {
            
            return true;
        }

        Class<?> type = value.getClass();
        if (type.isArray()) {
            if (type.getComponentType().isPrimitive()) return false;
            if (visited.put(value, Boolean.TRUE) != null) return false;
            visitedCount[0]++;
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (containsRawPythonProxy(
                        Array.get(value, i), visited,
                        depth + 1, visitedCount)) {
                    return true;
                }
            }
            return false;
        }

        if (value instanceof Collection) {
            if (visited.put(value, Boolean.TRUE) != null) return false;
            visitedCount[0]++;
            for (Object item : (Collection<?>) value) {
                if (containsRawPythonProxy(
                        item, visited, depth + 1, visitedCount)) {
                    return true;
                }
            }
            return false;
        }

        if (value instanceof Map) {
            if (visited.put(value, Boolean.TRUE) != null) return false;
            visitedCount[0]++;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (containsRawPythonProxy(
                        entry.getKey(), visited,
                        depth + 1, visitedCount)
                        || containsRawPythonProxy(
                        entry.getValue(), visited,
                        depth + 1, visitedCount)) {
                    return true;
                }
            }
        }
        return false;
    }
}
