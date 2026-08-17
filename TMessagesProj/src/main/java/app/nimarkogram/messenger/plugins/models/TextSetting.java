package app.nimarkogram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public class TextSetting extends SettingItem {
    public boolean accent;
    public volatile PyObject createSubFragmentCallback;
    public volatile PyObject onClickCallback;
    public boolean red;
    public String text;

    public TextSetting(String str, String str2, boolean z, boolean z2, PyObject pyObject, PyObject pyObject2, PyObject pyObject3, String str3) {
        super("text", str2, pyObject3, str3);
        this.text = str;
        this.accent = z;
        this.red = z2;
        this.onClickCallback = pyObject;
        this.createSubFragmentCallback = pyObject2;
    }

    @Override
    public void clearPythonReferences() {
        createSubFragmentCallback = null;
        onClickCallback = null;
        super.clearPythonReferences();
    }
}
