package app.nimarkogram.messenger.plugins.utils;

import android.text.TextUtils;
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import java.util.List;

public final class PyObjectUtils {
    private PyObjectUtils() {
    }

    public static String getString(PyObject pyObject, String str, String str2) {
        return getString(pyObject, str, str2, false);
    }

    public static String getString(PyObject pyObject, String str, String str2, boolean z) {
        if (pyObject != null && !TextUtils.isEmpty(str)) {
            try {
                PyObject value = z
                        ? pyObject.callAttr("get", str)
                        : pyObject.get((Object) str);
                return value != null ? value.toString() : str2;
            } catch (PyException | ClassCastException unused) {
            }
        }
        return str2;
    }

    public static boolean getBoolean(PyObject pyObject, String str, boolean z) {
        if (pyObject != null && !TextUtils.isEmpty(str)) {
            try {
                PyObject value = pyObject.get((Object) str);
                return value != null ? value.toBoolean() : z;
            } catch (PyException | ClassCastException unused) {
            }
        }
        return z;
    }

    public static int getInt(PyObject pyObject, String str, int i) {
        return getInt(pyObject, str, i, false);
    }

    public static int getInt(PyObject pyObject, String str, int i, boolean z) {
        if (pyObject != null && !TextUtils.isEmpty(str)) {
            try {
                PyObject value = z
                        ? pyObject.callAttr("get", str)
                        : pyObject.get((Object) str);
                return value != null ? value.toInt() : i;
            } catch (PyException | ClassCastException unused) {
            }
        }
        return i;
    }

    public static String[] getStringArray(PyObject obj, String key, String[] defaultValue) {
        if (obj == null) return defaultValue;
        try {
            PyObject val = obj.get(key);
            if (val == null) return defaultValue;
            
            List<PyObject> list = val.asList();
            String[] result = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                result[i] = list.get(i).toString();
            }
            return result;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
