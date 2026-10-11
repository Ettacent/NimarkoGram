/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.plugins.bridge;

import com.chaquo.python.PyObject;
import com.chaquo.python.PyProxy;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import app.nimarkogram.messenger.plugins.PluginsController;

public final class PythonBoundarySanitizer {
    public static final Object UNSAFE_VALUE = new Object();

    public enum ScanResult { SAFE, RAW_PROXY, INDETERMINATE }
    private static final int MAX_NESTING = 16;
    private static final int MAX_VISITED = 256;

    private static final ThreadLocal<boolean[]> activeScan = new ThreadLocal<>();
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
        return scan(value) != ScanResult.SAFE;
    }

    public static boolean isScanning() {
        return activeScan.get() != null;
    }

    public static ScanResult scan(Object value) {
        if (value == null) return ScanResult.SAFE;
        if (value instanceof PyProxy || value instanceof PyObject) return ScanResult.RAW_PROXY;
        if (!isContainer(value)) return ScanResult.SAFE;
        boolean[] enclosing = activeScan.get();
        if (enclosing != null) {
            enclosing[0] = true;
            return ScanResult.INDETERMINATE;
        }
        boolean[] reentered = new boolean[1];
        activeScan.set(reentered);
        try {
            return scan(value, new IdentityHashMap<>(), 0, new int[]{0}, reentered);
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable traversalFailure) {
            
            return ScanResult.INDETERMINATE;
        } finally {
            activeScan.remove();
        }
    }

    private static boolean isContainer(Object value) {
        return value instanceof Collection || value instanceof Map
                || (value.getClass().isArray()
                && !value.getClass().getComponentType().isPrimitive());
    }

    private static ScanResult scan(
            Object value,
            IdentityHashMap<Object, Boolean> visited,
            int depth,
            int[] visitedCount,
            boolean[] reentered) {


        if (value instanceof PyProxy || value instanceof PyObject) return ScanResult.RAW_PROXY;
        if (reentered[0]) return ScanResult.INDETERMINATE;
        if (value == null || !isContainer(value)) return ScanResult.SAFE;
        if (visited.put(value, Boolean.TRUE) != null) return ScanResult.SAFE;
        if (depth >= MAX_NESTING || visitedCount[0] >= MAX_VISITED) {
            
            return ScanResult.INDETERMINATE;
        }

        visitedCount[0]++;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                ScanResult result = scan(
                        Array.get(value, i), visited,
                        depth + 1, visitedCount, reentered);
                if (result != ScanResult.SAFE) return result;
            }
            return ScanResult.SAFE;
        }

        if (value instanceof Collection) {
            Iterator<?> iterator = ((Collection<?>) value).iterator();
            if (reentered[0]) return ScanResult.INDETERMINATE;
            while (true) {
                boolean hasNext = iterator.hasNext();
                if (reentered[0]) return ScanResult.INDETERMINATE;
                if (!hasNext) return ScanResult.SAFE;
                Object item = iterator.next();
                ScanResult result = scan(item, visited, depth + 1, visitedCount, reentered);
                if (result != ScanResult.SAFE) return result;
            }
        }

        if (value instanceof Map) {
            Collection<? extends Map.Entry<?, ?>> entries = ((Map<?, ?>) value).entrySet();
            if (reentered[0]) return ScanResult.INDETERMINATE;
            Iterator<? extends Map.Entry<?, ?>> iterator = entries.iterator();
            if (reentered[0]) return ScanResult.INDETERMINATE;
            while (true) {
                boolean hasNext = iterator.hasNext();
                if (reentered[0]) return ScanResult.INDETERMINATE;
                if (!hasNext) return ScanResult.SAFE;
                Map.Entry<?, ?> entry = iterator.next();
                if (reentered[0]) return ScanResult.INDETERMINATE;
                ScanResult result = scan(entry.getKey(), visited,
                        depth + 1, visitedCount, reentered);
                if (result != ScanResult.SAFE) return result;
                result = scan(entry.getValue(), visited,
                        depth + 1, visitedCount, reentered);
                if (result != ScanResult.SAFE) return result;
            }
        }
        return ScanResult.SAFE;
    }
}
