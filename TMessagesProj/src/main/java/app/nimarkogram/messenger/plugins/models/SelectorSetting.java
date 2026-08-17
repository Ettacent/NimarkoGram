package app.nimarkogram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import app.nimarkogram.messenger.plugins.PluginsConstants;

public class SelectorSetting extends SettingItem {
    public int defaultValue;
    public String[] items;
    public String key;
    public volatile PyObject onChangeCallback;
    public String text;

    public SelectorSetting(String str, String str2, int i, String[] strArr, String str3, PyObject pyObject, PyObject pyObject2, String str4) {
        super(PluginsConstants.Settings.TYPE_SELECTOR, str3, pyObject2, str4);
        this.key = str;
        this.text = str2;
        this.defaultValue = i;
        this.items = strArr;
        this.onChangeCallback = pyObject;
    }

    @Override
    public void clearPythonReferences() {
        onChangeCallback = null;
        super.clearPythonReferences();
    }
}
