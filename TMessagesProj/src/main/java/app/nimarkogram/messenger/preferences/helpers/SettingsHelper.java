 
package app.nimarkogram.messenger.preferences.helpers;

import android.view.View;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public class SettingsHelper {

    public static UItem asCustomWithBackground(View view) {
        return UItem.asCustom(view);
    }

    public static UItem asCustomWithBackground(int id, View view) {
        return UItem.asCustom(id, view);
    }

    public static UItem asCustomWithBackground(int id, View view, int heightDp) {
        UItem i = UItem.asCustom(view, heightDp);
        i.id = id;
        return i;
    }

    public static UItem asCustomWithBackground(View view, int heightDp) {
        return UItem.asCustom(view, heightDp);
    }

    public static UItem asSpaceCG(int height) {
        return UItem.asSpace(height);
    }

    public static UItem asTextDetail(int id, int iconResId, CharSequence text, CharSequence value) {
        
        UItem i = TextDetailCell.Factory.of(id, text, value);
        
        i.iconResId = iconResId;
        return i;
    }

    public static UItem asSwitchCG(int id, CharSequence text) {
        return UItem.asCheck(id, text);
    }

    public static UItem asSwitchCG(int id, CharSequence text, CharSequence subtext) {
        
        UItem i = new UItem(UniversalAdapter.VIEW_TYPE_TEXT_CHECK, false);
        i.id = id;
        i.text = text;
        i.subtext = subtext;
        return i;
    }

    public static void updateCheckState(View view, boolean isChecked) {
        if (view instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) view).setChecked(isChecked);
        } else if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(isChecked);
        } else {
            if (view != null) {
                FileLog.e("Unknown view type for setChecked: " + view.getClass().getName());
            } else {
                FileLog.e("Attempted to update check state on a NULL view");
            }
        }
    }

    public static void updateButtonValue(View view, String value) {
        if (view instanceof TextCell) {
            ((TextCell) view).setValue(value, true);
        } else {
            if (view != null) {
                FileLog.e("Unknown view type for setValue: " + view.getClass().getName());
            } else {
                FileLog.e("Attempted to update button value on a NULL view");
            }
        }
    }
}
