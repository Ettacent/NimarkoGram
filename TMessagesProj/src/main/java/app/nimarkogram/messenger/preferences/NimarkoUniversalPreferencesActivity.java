package app.nimarkogram.messenger.preferences;

import android.content.Context;
import android.view.View;

import androidx.core.view.ViewCompat;

import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalFragment;

public abstract class NimarkoUniversalPreferencesActivity extends UniversalFragment {

    private int initialSearchItemId;

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ViewCompat.setOnApplyWindowInsetsListener(view, this::onInsetsInternal);
        ViewCompat.requestApplyInsets(view);
        if (initialSearchItemId != 0 && listView != null) {
            listView.post(() -> scrollToItem(initialSearchItemId));
        }
        return view;
    }

    public NimarkoUniversalPreferencesActivity openAtSetting(int itemId) {
        initialSearchItemId = itemId;
        return this;
    }

    protected UItem asPlainSettingsRow(int id, CharSequence title) {
        return UItem.asButton(id, title);
    }

    protected UItem asPlainSettingsRow(int id, CharSequence title, CharSequence value) {
        return UItem.asButton(id, title, value);
    }

    protected UItem asPlainSettingsRowWithSubtitle(int id, CharSequence title, CharSequence subtitle) {
        return SettingsActivity.SettingCell.Factory.of(id, 0, 0, 0, title, subtitle, null);
    }

    protected UItem asSettingsLink(int id, IconBackgroundColors colors, int icon, CharSequence title) {
        return SettingsActivity.SettingCell.Factory.of(id, colors.top, colors.bottom, icon, title);
    }

    protected UItem asSettingsLink(int id, IconBackgroundColors colors, int icon,
                                   CharSequence title, CharSequence subtitle) {
        return SettingsActivity.SettingCell.Factory.of(
                id, colors.top, colors.bottom, icon, title, subtitle, null);
    }

    protected UItem asSettingsValue(int id, IconBackgroundColors colors, int icon,
                                    CharSequence title, CharSequence value) {
        return SettingsActivity.SettingCell.Factory.of(
                id, colors.top, colors.bottom, icon, title, null, value);
    }

    private void scrollToItem(int itemId) {
        if (listView == null || listView.adapter == null || listView.layoutManager == null) {
            return;
        }
        int position = listView.findPositionByItemId(itemId);
        if (position < 0 || position >= listView.adapter.getItemCount()) {
            return;
        }
        listView.layoutManager.scrollToPositionWithOffset(position, org.telegram.messenger.AndroidUtilities.dp(80));
        listView.highlightRow(() -> listView.findPositionByItemId(itemId));
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
            listView.setClipToPadding(false);
        }
    }
}
