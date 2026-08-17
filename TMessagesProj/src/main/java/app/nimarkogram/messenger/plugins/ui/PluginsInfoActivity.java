package app.nimarkogram.messenger.plugins.ui;

import android.os.Build;
import android.text.Html;
import android.text.SpannableString;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.preferences.BasePreferencesActivity;
import app.nimarkogram.messenger.utils.text.LocaleUtils;

public class PluginsInfoActivity extends BasePreferencesActivity {

    public enum PreferenceItem {
        COMPACT_VIEW,
        SAFE_MODE,
        PLUGINS_DISABLE_ART_OPTS,
        DOCUMENTATION;

        public int getId() {
            return ordinal() + 1;
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.PluginsEngine);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Settings)));

        items.add(UItem.asCheck(PreferenceItem.COMPACT_VIEW.getId(),
                        LocaleController.getString(R.string.PluginsCompactView), R.drawable.msg_topics)
                .setChecked(NimarkoConfig.pluginsCompactView)
                .setEnabled(NimarkoConfig.pluginsEngine));

        UItem disableArt = UItem.asCheck(PreferenceItem.PLUGINS_DISABLE_ART_OPTS.getId(),
                        LocaleController.getString(R.string.PluginsDisableArt),
                        LocaleController.getString(R.string.PluginsDisableArtInfo),
                        true)
                .setChecked(NimarkoConfig.pluginsDisableArtOpts)
                .setEnabled(NimarkoConfig.pluginsEngine);
        items.add(disableArt);

        items.add(UItem.asCheck(PreferenceItem.SAFE_MODE.getId(),
                        LocaleController.getString(R.string.PluginsSafeMode), R.drawable.msg_secret)
                .setChecked(NimarkoConfig.pluginsSafeMode));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsSafeModeInfo2)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Links)));
        UItem docButton = UItem.asButton(PreferenceItem.DOCUMENTATION.getId(),
                LocaleController.getString(R.string.PluginsDocumentation));
        docButton.iconResId = R.drawable.menu_intro;
        items.add(docButton);

        SpannableString spannableString;
        String string = LocaleController.getString(R.string.PluginsPoweredBy);
        if (Build.VERSION.SDK_INT >= 24) {
            spannableString = new SpannableString(Html.fromHtml(string, Html.FROM_HTML_MODE_LEGACY));
        } else {
            spannableString = new SpannableString(Html.fromHtml(string));
        }
        items.add(UItem.asShadow(LocaleUtils.formatWithHtmlURLs(spannableString)));
    }

    @Override
    public void onClick(UItem uItem, View view, int position, float x, float y) {
        int id = uItem.id;
        if (id <= 0 || id > PreferenceItem.values().length) {
            return;
        }
        PreferenceItem preferenceItem = PreferenceItem.values()[id - 1];

        if (view instanceof TextCheckCell) {
            TextCheckCell checkCell = (TextCheckCell) view;
            if (NimarkoConfig.pluginsEngine || preferenceItem == PreferenceItem.SAFE_MODE) {
                switch (preferenceItem) {
                    case COMPACT_VIEW:
                        NimarkoConfig.togglePluginsCompactView();
                        checkCell.setChecked(NimarkoConfig.pluginsCompactView);
                        uItem.checked = NimarkoConfig.pluginsCompactView;
                        NotificationCenter.getGlobalInstance()
                                .postNotificationName(NotificationCenter.reloadInterface);
                        break;
                    case PLUGINS_DISABLE_ART_OPTS:
                        NimarkoConfig.setPluginsDisableArtOpts(!NimarkoConfig.pluginsDisableArtOpts);
                        checkCell.setChecked(NimarkoConfig.pluginsDisableArtOpts);
                        uItem.checked = NimarkoConfig.pluginsDisableArtOpts;
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                                LocaleController.getString(R.string.RestartRequired)).show();
                        break;
                    case SAFE_MODE:
                        NimarkoConfig.togglePluginsSafeMode();
                        checkCell.setChecked(NimarkoConfig.pluginsSafeMode);
                        uItem.checked = NimarkoConfig.pluginsSafeMode;
                        PluginsController.getInstance().restart();
                        break;
                    default:
                        break;
                }
                this.listView.adapter.update(true);
                return;
            }
        }

        if (preferenceItem == PreferenceItem.DOCUMENTATION) {
            Browser.openUrl(getParentActivity(), "https://plugins.exteragram.app/");
        }
    }

}
