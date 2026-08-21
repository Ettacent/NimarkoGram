package app.nimarkogram.messenger.plugins.hooks;

import android.content.Context;
import android.text.TextUtils;
import com.chaquo.python.PyObject;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.mvel2.MVEL;
import org.telegram.messenger.ApplicationLoader;

import app.nimarkogram.messenger.plugins.PluginsConstants;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.utils.PyObjectUtils;

public class MenuItemRecord {
    public final String conditionString;
    private final Serializable compiledCondition;
    public final String iconName;
    public final int iconResId;
    public final String itemId;
    public final String menuType;
    public volatile PyObject onClickCallback;
    public final String pluginId;
    public final PluginsController.PluginRuntimeToken runtimeToken;
    public final int priority;
    public final String subtext;
    public final String text;

    public MenuItemRecord(String pluginId, PyObject pyObject) {
        this.pluginId = pluginId;
        this.runtimeToken = PluginsController.getInstance().captureCurrentPluginRuntime();
        if (this.runtimeToken == null
                || !pluginId.equals(this.runtimeToken.getPluginId())) {
            throw new IllegalArgumentException(
                    "Menu item requires an exact plugin runtime");
        }
        this.menuType = PyObjectUtils.getString(pyObject, PluginsConstants.MenuItemProperties.MENU_TYPE, null, true);
        this.text = PyObjectUtils.getString(pyObject, "text", null, true);
        
        this.onClickCallback = pyObject.callAttr("get", "on_click");
        
        String id = PyObjectUtils.getString(pyObject, PluginsConstants.MenuItemProperties.ITEM_ID, null, true);
        this.itemId = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        
        this.iconName = PyObjectUtils.getString(pyObject, "icon", null, true);
        this.subtext = PyObjectUtils.getString(pyObject, "subtext", null, true);
        this.conditionString = PyObjectUtils.getString(pyObject, "condition", null, true);
        Serializable condition = null;
        if (!TextUtils.isEmpty(this.conditionString)) {
            try {
                condition = MVEL.compileExpression(this.conditionString);
            } catch (Exception e) {
                try {
                    org.telegram.messenger.FileLog.d("nimarko: invalid menu condition for plugin "
                            + this.pluginId + " condition='" + this.conditionString + "': " + e);
                } catch (Throwable ignored) {}
            }
        }
        this.compiledCondition = condition;
        this.priority = PyObjectUtils.getInt(pyObject, PluginsConstants.MenuItemProperties.PRIORITY, 0, true);

        int resId = 0;
        if (!TextUtils.isEmpty(this.iconName)) {
            Context context = ApplicationLoader.applicationContext;
            String pkg = context.getPackageName();
            String[] candidates = new String[] {
                this.iconName,                                        
                this.iconName.startsWith("msg_") ? null : "msg_" + this.iconName,    
                this.iconName.startsWith("ic_") ? null : "ic_" + this.iconName,      
                this.iconName.toLowerCase(),                          
            };
            for (String name : candidates) {
                if (TextUtils.isEmpty(name)) continue;
                try {
                    int candidateId = context.getResources().getIdentifier(name, "drawable", pkg);
                    if (candidateId != 0) { resId = candidateId; break; }
                } catch (Exception ignored) {}
            }
            if (resId == 0) {
                try {
                    org.telegram.messenger.FileLog.d("nimarko: plugin menu icon '" + this.iconName + "' not found in drawables");
                } catch (Throwable ignored) {}
            }
        }
        this.iconResId = resId;

        if (TextUtils.isEmpty(this.menuType) || TextUtils.isEmpty(this.text) || this.onClickCallback == null) {
            throw new IllegalArgumentException("MenuItemRecord missing essential fields: menuType, text, or onClickCallback.");
        }
    }

    public void releaseCallback(
            PluginsController.PluginRuntimeToken expectedRuntime) {
        if (expectedRuntime != null
                && expectedRuntime.equals(this.runtimeToken)) {
            this.onClickCallback = null;
        }
    }

    public boolean checkCondition(Map<String, Object> map) {
        if (TextUtils.isEmpty(this.conditionString) || map == null) {
            return true;
        }
        if (compiledCondition == null) {
            return false;
        }
        try {
            Object result = MVEL.executeExpression(compiledCondition, map, Boolean.class);

            return result instanceof Boolean ? (Boolean) result : false;
        } catch (Exception e) {
            try {
                org.telegram.messenger.FileLog.d("nimarko: MenuItemRecord.checkCondition failed for plugin "
                        + this.pluginId + " condition='" + this.conditionString + "': " + e);
            } catch (Throwable ignored) {}
            return false;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj != null && getClass() == obj.getClass()) {
            MenuItemRecord other = (MenuItemRecord) obj;
            return Objects.equals(this.itemId, other.itemId) && 
                   Objects.equals(this.pluginId, other.pluginId);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.itemId, this.pluginId);
    }
}
