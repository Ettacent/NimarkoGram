package app.nimarkogram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import app.nimarkogram.messenger.plugins.PluginsConstants;

public class EditTextSetting extends SettingItem {
    public String defaultValue;
    public String hint;
    public String key;
    public String mask;
    public int maxLength;
    public boolean multiline;
    public volatile PyObject onChangeCallback;

    public EditTextSetting(String str, String str2, String str3, boolean z, int i, String str4, PyObject pyObject) {
        super(PluginsConstants.Settings.TYPE_EDIT_TEXT);
        this.key = str;
        this.hint = str2;
        this.defaultValue = str3;
        this.multiline = z;
        this.maxLength = i;
        this.mask = str4;
        this.onChangeCallback = pyObject;
    }

    @Override
    public void clearPythonReferences() {
        onChangeCallback = null;
        super.clearPythonReferences();
    }
}
