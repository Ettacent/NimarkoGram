/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils;

import android.net.Uri;

import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.messenger.R;
import static org.telegram.messenger.LocaleController.getString;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stars.StarsIntroActivity;

import java.util.Locale;

import app.nimarkogram.messenger.preferences.AppearancePreferencesActivity;
import app.nimarkogram.messenger.preferences.BottomTabsPreferencesActivity;
import app.nimarkogram.messenger.preferences.CameraPreferencesActivity;
import app.nimarkogram.messenger.preferences.ChatsPreferencesActivity;
import app.nimarkogram.messenger.preferences.DebugPreferencesActivity;
import app.nimarkogram.messenger.preferences.ExperimentalPreferencesActivity;
import app.nimarkogram.messenger.preferences.FoldersPreferencesActivity;
import app.nimarkogram.messenger.preferences.GeneralPreferencesActivity;
import app.nimarkogram.messenger.preferences.MainPreferencesActivity;
import app.nimarkogram.messenger.preferences.MessageFiltersPreferencesActivity;
import app.nimarkogram.messenger.preferences.MessageMenuPreferencesActivity;
import app.nimarkogram.messenger.preferences.MessagesAndProfilesPreferencesActivity;
import app.nimarkogram.messenger.preferences.PrivacyPreferencesActivity;

public class NimarkoDeeplinkHelper {

    public static void processDeepLink(Uri uri, BaseFragment fragment, Callback callback, Runnable unknown, Browser.Progress progress) {
        if (fragment == null) {
            fragment = LaunchActivity.getSafeLastFragment();
        }
        if (fragment == null) {
            return;
        }
        if (uri == null) {
            unknown.run();
            return;
        }
        var segments = uri.getPathSegments();
        if (segments.isEmpty() || segments.size() > 2) {
            unknown.run();
            return;
        }

        if (segments.size() == 1) {
            var segment = segments.get(0).toLowerCase(Locale.US);
            BaseFragment target = null;
            switch (segment) {
                case DeepLinksRepo.NG_Settings:
                case "nimarko_main":
                    target = new MainPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_General:
                    target = new GeneralPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Appearance:
                    target = new AppearancePreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Tabs:
                    target = new BottomTabsPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Folders:
                    target = new FoldersPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Messages_And_Profiles:
                    target = new MessagesAndProfilesPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Chats:
                    target = new ChatsPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Messages:
                    target = new ChatsPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Message_Menu:
                case "nimarko_messages_menu":
                    target = new MessageMenuPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Message_Filters:
                case "nimarko_filter":
                    target = new MessageFiltersPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Camera:
                    target = new CameraPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Privacy:
                case "nimarko_security":
                    target = new PrivacyPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Experimental:
                    target = new ExperimentalPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Debug:
                    target = new DebugPreferencesActivity();
                    break;
                case DeepLinksRepo.NG_Stars:
                    new StarsIntroActivity.StarsOptionsSheet(
                            fragment.getContext(),
                            fragment.getCurrentAccount(),
                            fragment.getResourceProvider()
                    ).show();
                    return;
                case DeepLinksRepo.NG_Username_Limits:
                    fragment.showDialog(new LimitReachedBottomSheet(
                            fragment,
                            fragment.getContext(),
                            LimitReachedBottomSheet.TYPE_PUBLIC_LINKS,
                            fragment.getCurrentAccount(),
                            fragment.getResourceProvider()
                    ));
                    return;
                case DeepLinksRepo.NG_Restart:
                case "nimarko_reboot":
                case "restart":
                case "reboot":
                    if (fragment.getParentActivity() == null) return;
                    AlertDialog.Builder restart = new AlertDialog.Builder(fragment.getParentActivity());
                    restart.setTitle(getString(R.string.NM_HUB_Restart));
                    restart.setMessage(getString(R.string.NM_RestartRequired));
                    BaseFragment restartFragment = fragment;
                    restart.setPositiveButton(getString(R.string.NM_Restart), (d, w) ->
                            AppRestartHelper.triggerRebirth(restartFragment.getContext()));
                    restart.setNegativeButton(getString(R.string.Cancel), null);
                    fragment.showDialog(restart.create());
                    return;
                default:
                    unknown.run();
                    return;
            }
            if (target != null) {
                final BaseFragment finalTarget = target;
                callback.presentFragment(finalTarget);
                return;
            }
        }
        callback.presentFragment(fragment);
    }

    public interface Callback {
        void presentFragment(BaseFragment fragment);
    }

    public static class DeepLinksRepo {

        public static final String NG_Settings = "nimarko_settings";

        public static final String NG_General = "nimarko_general";

        public static final String NG_Appearance = "nimarko_appearance";
        public static final String NG_Folders = "nimarko_folders";
        public static final String NG_Luck = "nimarko_luck";
        public static final String NG_Tabs = "nimarko_tabs";
        public static final String NG_Messages_And_Profiles = "nimarko_messages_profiles";

        public static final String NG_Chats = "nimarko_chats";
        public static final String NG_Messages = "nimarko_messages";
        public static final String NG_Message_Menu = "nimarko_message_menu";
        public static final String NG_Message_Filters = "nimarko_filters";

        public static final String NG_Camera = "nimarko_camera";

        public static final String NG_Experimental = "nimarko_experimental";

        public static final String NG_Privacy = "nimarko_privacy";

        public static final String NG_Restart = "nimarko_restart";

        public static final String NG_Debug = "nimarko_debug";

        public static final String NG_Stars = "nimarko_stars";
        public static final String NG_Username_Limits = "nimarko_username_limits";

        private DeepLinksRepo() {}
    }

}
