/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.preferences;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
final class SettingsFooterItem extends UItem {
    private SettingsFooterItem(int id, CharSequence text) {
        super(UniversalAdapter.VIEW_TYPE_SHADOW, false);
        this.id = id;
        this.text = text;
    }
    static UItem of(int id, CharSequence text) {
        return new SettingsFooterItem(id, text);
    }
    @Override
    public boolean compare(AdapterWithDiffUtils.Item other) {
        return other instanceof SettingsFooterItem && id == ((SettingsFooterItem) other).id;
    }
}
