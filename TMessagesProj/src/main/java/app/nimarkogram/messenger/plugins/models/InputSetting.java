package app.nimarkogram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import app.nimarkogram.messenger.plugins.PluginsConstants;

public class InputSetting extends SettingItem {
    public String defaultValue;
    public String key;
    public volatile PyObject onChangeCallback;
    public String subtext;
    public String text;

    public InputSetting(String str, String str2, String str3, String str4, String str5, PyObject pyObject, PyObject pyObject2, String str6) {
        super(PluginsConstants.Settings.TYPE_INPUT, str5, pyObject2, str6);
        this.key = str;
        this.text = str2;
        this.defaultValue = str3;
        this.subtext = str4;
        this.onChangeCallback = pyObject;
    }

    @Override
    public void clearPythonReferences() {
        onChangeCallback = null;
        super.clearPythonReferences();
    }
}
