package app.nimarkogram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import app.nimarkogram.messenger.plugins.PluginsConstants;

public class SwitchSetting extends SettingItem {
    public boolean defaultValue;
    public String key;
    public volatile PyObject onChangeCallback;
    public String subtext;
    public String text;

    public SwitchSetting(String str, String str2, boolean z, String str3, String str4, PyObject pyObject, PyObject pyObject2, String str5) {
        super(PluginsConstants.Settings.TYPE_SWITCH, str4, pyObject2, str5);
        this.key = str;
        this.text = str2;
        this.defaultValue = z;
        this.subtext = str3;
        this.onChangeCallback = pyObject;
    }

    @Override
    public void clearPythonReferences() {
        onChangeCallback = null;
        super.clearPythonReferences();
    }
}
