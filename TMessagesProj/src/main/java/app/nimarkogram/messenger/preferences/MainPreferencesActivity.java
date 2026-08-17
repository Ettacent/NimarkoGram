package app.nimarkogram.messenger.preferences;

import android.os.Build;
import android.text.Html;
import android.text.SpannableString;
import android.view.View;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginsActivity;
import app.nimarkogram.messenger.utils.AppRestartHelper;
import app.nimarkogram.messenger.utils.text.LocaleUtils;
import app.nimarkogram.messenger.wsbypass.preferences.WsBypassPreferencesActivity;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public class MainPreferencesActivity extends BasePreferencesActivity {

    public static final int ID_GENERAL      = 1;
    public static final int ID_APPEARANCE   = 2;
    public static final int ID_CHATS        = 3;
    public static final int ID_CAMERA       = 4;
    public static final int ID_PRIVACY      = 6;

    public static final int ID_RESTART      = 10;

    public static final int ID_PLUGINS        = 20;
    public static final int ID_DEBUG          = 21;
    private static final int ID_EXPERIMENTAL   = 22;
    public static final int ID_NIMARKO_MEDIA  = 23;
    private static final int ID_CHERRYGRAM_FORK = 24;
    public static final int ID_BANNERS        = 25;
    public static final int ID_UPDATES        = 26;
    public static final int ID_WSBYPASS       = 27;
    public static final int ID_TEXTANIM       = 28;
    public static final int ID_PILLSTACK      = 29;
    public static final int ID_SOURCE_CODE    = 30;

    private static final String SOURCE_REPOSITORY_URL = "https://github.com/Ettacent/NimarkoGram";

    private UItem category(int id, IconBackgroundColors colors, int icon, int title, String alias) {
        return SettingsActivity.SettingCell.Factory.of(
                        id, colors.top, colors.bottom, icon,
                        LocaleController.getString(title))
                .setSearchable(this)
                .setLinkAlias(alias, this);
    }

    @Override
    public void fillItems(ArrayList<UItem> arrayList, UniversalAdapter universalAdapter) {
        
        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.AP_Header_General)));
        arrayList.add(category(ID_GENERAL, IconBackgroundColors.BLUE,
                R.drawable.msg_settings_solar, R.string.NM_Cat_General, "nimarko_general"));
        arrayList.add(category(ID_APPEARANCE, IconBackgroundColors.PURPLE,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, "nimarko_appearance"));
        arrayList.add(category(ID_CHATS, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_msgbubble3_solar, R.string.NM_Cat_Chats, "nimarko_chats"));
        arrayList.add(category(ID_CAMERA, IconBackgroundColors.CYAN,
                R.drawable.camera_solar, R.string.NM_Cat_Camera, "nimarko_camera"));
        
        arrayList.add(category(ID_PRIVACY, IconBackgroundColors.GREEN,
                R.drawable.msg_secret_solar, R.string.NM_Cat_Privacy, "nimarko_privacy"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_HUB_Header_Misc)));
        if (PluginsController.isPluginEngineSupported()) {
            arrayList.add(category(ID_PLUGINS, IconBackgroundColors.PURPLE,
                    R.drawable.msg_plugins, R.string.Plugins, "nimarko_plugins"));
        }
        arrayList.add(category(ID_NIMARKO_MEDIA, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_download_solar, R.string.NM_DownloadMedia, "nimarko_media"));
        arrayList.add(category(ID_BANNERS, IconBackgroundColors.ORANGE,
                R.drawable.msg_photos_solar, R.string.NM_BAN_Title, "nimarko_banners"));
        arrayList.add(category(ID_WSBYPASS, IconBackgroundColors.GREEN,
                R.drawable.msg_secret_solar, R.string.NM_WSB_Title, "nimarko_wsbypass"));
        arrayList.add(category(ID_TEXTANIM, IconBackgroundColors.PURPLE,
                R.drawable.msg_edit_solar, R.string.NM_TA_Title, "nimarko_textanim"));
        arrayList.add(category(ID_PILLSTACK, IconBackgroundColors.CYAN,
                R.drawable.msg_search_solar, R.string.NM_CARDS_Title, "nimarko_infocards"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_HUB_Header_Advanced)));
        arrayList.add(category(ID_DEBUG, IconBackgroundColors.GRAY,
                R.drawable.msg_info_solar, R.string.NM_HUB_Debug, "nimarko_debug"));
        arrayList.add(category(ID_SOURCE_CODE, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_link_2_solar, R.string.NM_HUB_SourceCode, "nimarko_source_code"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_HUB_Header_Other)));
        arrayList.add(category(ID_RESTART, IconBackgroundColors.ORANGE_DEEP,
                R.drawable.msg_retry_solar, R.string.NM_HUB_Restart, "nimarko_restart"));
        arrayList.add(category(ID_UPDATES, IconBackgroundColors.BLUE,
                R.drawable.msg_info_solar, R.string.UP_CheckForUpdates, "nimarko_updates"));
        arrayList.add(UItem.asShadow(null));

        SpannableString forkedSub;
        String forkedSubRaw = LocaleController.getString(R.string.NM_HUB_ForkedFromCherrygramSub);
        if (Build.VERSION.SDK_INT >= 24) {
            forkedSub = new SpannableString(Html.fromHtml(forkedSubRaw, Html.FROM_HTML_MODE_LEGACY));
        } else {
            forkedSub = new SpannableString(Html.fromHtml(forkedSubRaw));
        }
        arrayList.add(UItem.asShadow(LocaleUtils.formatWithHtmlURLs(forkedSub)));

    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NimarkoGramSettings);
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        switch (uItem.id) {
            case ID_GENERAL:
                presentFragment(new GeneralPreferencesActivity());
                break;
            case ID_APPEARANCE:
                presentFragment(new AppearancePreferencesActivity());
                break;
            case ID_CHATS:
                presentFragment(new ChatsPreferencesActivity());
                break;
            case ID_CAMERA:
                presentFragment(new CameraPreferencesActivity());
                break;
            case ID_PRIVACY:
                presentFragment(new PrivacyPreferencesActivity());
                break;
            case ID_PLUGINS:
                presentFragment(new PluginsActivity());
                break;
            case ID_NIMARKO_MEDIA:
                presentFragment(new NimarkoMediaPreferencesActivity());
                break;
            case ID_BANNERS:
                presentFragment(new BannerPreferencesActivity());
                break;
            case ID_WSBYPASS:
                presentFragment(new WsBypassPreferencesActivity());
                break;
            case ID_PILLSTACK:
                presentFragment(new app.nimarkogram.messenger.infocards.preferences.InfoCardsPreferencesActivity());
                break;
            case ID_TEXTANIM:
                presentFragment(new NimarkoTextAnimPreferencesActivity());
                break;
            case ID_UPDATES:
                app.nimarkogram.messenger.updater.NimarkoUpdaterSheet.showAlert(this, false, null);
                break;
            case ID_DEBUG:
                presentFragment(new DebugPreferencesActivity());
                break;
            case ID_EXPERIMENTAL:
                presentFragment(new ExperimentalPreferencesActivity());
                break;
            case ID_RESTART:
                AppRestartHelper.triggerRebirth(getParentActivity() != null ? getParentActivity() : getContext());
                break;
            case ID_SOURCE_CODE:
                org.telegram.messenger.browser.Browser.openUrl(
                        getParentActivity() != null ? getParentActivity() : getContext(),
                        SOURCE_REPOSITORY_URL);
                break;
            case ID_CHERRYGRAM_FORK:
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(),
                        "https://github.com/arslan4k1390/Cherrygram");
                break;
            default:
                break;
        }
    }
}
