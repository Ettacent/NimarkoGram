/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;

import app.nimarkogram.messenger.utils.LockedChats;
import org.telegram.messenger.MessagesController;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.view.View;

import androidx.biometric.BiometricPrompt;

import java.util.ArrayList;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;
import app.nimarkogram.messenger.security.AppBiometricPrompt;
import app.nimarkogram.messenger.utils.chats.ChatMenuInjector;

public class PrivacyPreferencesActivity extends BasePreferencesActivity {
    private static final int ID_HIDE_PROXY = 1;
    private static final int ID_DELETE_ACCOUNT = 2;

    private static final int ID_HIDE_ARCHIVED_STORIES = 3;
    private static final int ID_HIDE_ARCHIVE_LIST = 4;
    private static final int ID_PROTECT_SELECTED_CHATS = 5;
    private static final int ID_LOCKED_CHATS = 6;
    private static final int ID_REQUIRE_BIO_DELETE = 7;
    private static final int ID_ALLOW_SYSTEM_PASSCODE = 8;
    private static final int ID_TEST_FINGERPRINT = 9;
    private static final int ID_LOCKED_CHATS_TTL = 11;
    private static final int ID_PROTECT_SECRET_CHATS = 12;
    private static final int ID_PROTECT_ARCHIVE = 13;
    private static final int ID_OPEN_ARCHIVE = 14;
    private static final int ID_PROTECT_SAVED_MESSAGES = 15;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_Cat_Privacy);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionAuthentication)));
        items.add(SettingsHelper.asSwitchCG(ID_ALLOW_SYSTEM_PASSCODE,
                        LocaleController.getString(R.string.NM_PR_AllowSystemPasscode),
                        LocaleController.getString(R.string.NM_PR_AllowSystemPasscode_Desc))
                .setChecked(AppConfig.allowSystemPasscode));
        items.add(asSettingsLink(ID_TEST_FINGERPRINT, IconBackgroundColors.BLUE,
                R.drawable.msg_pin_code,
                LocaleController.getString(R.string.NM_PR_TestFingerprint),
                LocaleController.getString(R.string.NM_PR_TestFingerprint_Desc)));
        items.add(UItem.asShadow(-1, null));

        items.add(UItem.asHeader(-2, LocaleController.getString(R.string.NM_PR_Header_ChatProtection)));
        items.add(SettingsHelper.asSwitchCG(ID_PROTECT_SELECTED_CHATS,
                        LocaleController.getString(R.string.NM_PR_AskBioOpenChats),
                        LocaleController.getString(R.string.NM_PR_AskBioOpenChats_Desc))
                .setChecked(AppConfig.askBiometricsToOpenChat));
        items.add(SettingsHelper.asSwitchCG(ID_PROTECT_SAVED_MESSAGES,
                        LocaleController.getString(R.string.NM_PR_AskBioOpenSavedMessages),
                        LocaleController.getString(R.string.NM_PR_AskBioOpenSavedMessages_Desc))
                .setChecked(AppConfig.askBiometricsToOpenSavedMessages));
        if (AppConfig.askBiometricsToOpenChat) {
            int count = LockedChats.count(currentAccount);
            items.add(asSettingsValue(ID_LOCKED_CHATS, IconBackgroundColors.GREEN,
                    R.drawable.msg_saved,
                    LocaleController.getString(R.string.NM_PR_LockedChats), String.valueOf(count)));
            items.add(asSettingsValue(ID_LOCKED_CHATS_TTL, IconBackgroundColors.ORANGE,
                    R.drawable.msg_recent,
                    LocaleController.getString(R.string.NM_PR_LockedChatsTtl), getLockedChatsTtlValueText()));
        }
        items.add(SettingsHelper.asSwitchCG(ID_PROTECT_SECRET_CHATS,
                        LocaleController.getString(R.string.NM_PR_AskBioOpenEncrypted),
                        LocaleController.getString(R.string.NM_PR_AskBioOpenEncrypted_Desc))
                .setChecked(AppConfig.askBiometricsToOpenEncrypted));
        items.add(SettingsHelper.asSwitchCG(ID_PROTECT_ARCHIVE,
                        LocaleController.getString(R.string.NM_PR_AskBioOpenArchive),
                        LocaleController.getString(R.string.NM_PR_AskBioOpenArchive_Desc))
                .setChecked(AppConfig.askBiometricsToOpenArchive));
        items.add(SettingsHelper.asSwitchCG(ID_REQUIRE_BIO_DELETE,
                        LocaleController.getString(R.string.NM_PR_RequireBiometricsToDelete),
                        LocaleController.getString(R.string.NM_PR_RequireBiometricsToDelete_Desc))
                .setChecked(AppConfig.askPasscodeBeforeDelete));
        items.add(UItem.asShadow(-3, null));

        items.add(UItem.asHeader(-4, LocaleController.getString(R.string.NM_SettingsSectionHiddenItems)));
        items.add(UItem.asCheck(ID_HIDE_PROXY, LocaleController.getString(R.string.NM_PR_HideProxy))
                .setChecked(AppConfig.hideProxySponsor));
        items.add(SettingsHelper.asSwitchCG(ID_HIDE_ARCHIVED_STORIES,
                        LocaleController.getString(R.string.NM_PR_HideArchivedStories),
                        LocaleController.getString(R.string.NM_PR_HideArchivedStories_Desc))
                .setChecked(AppConfig.hideArchivedStories));
        items.add(SettingsHelper.asSwitchCG(ID_HIDE_ARCHIVE_LIST,
                        LocaleController.getString(R.string.NM_PR_HideArchiveList),
                        LocaleController.getString(R.string.NM_PR_HideArchiveList_Desc))
                .setChecked(AppConfig.hideArchiveFromChatsList));
        if (AppConfig.hideArchiveFromChatsList) {
            items.add(asSettingsLink(ID_OPEN_ARCHIVE, IconBackgroundColors.BLUE_DEEP,
                    R.drawable.msg_archive,
                    LocaleController.getString(R.string.NM_PR_OpenArchive)));
        }
        items.add(UItem.asShadow(-5, null));

        items.add(UItem.asHeader(-6, LocaleController.getString(R.string.NM_SettingsSectionAccount)));
        items.add(asSettingsLink(ID_DELETE_ACCOUNT, IconBackgroundColors.RED,
                R.drawable.msg_user_remove,
                LocaleController.getString(R.string.NM_PR_DeleteAccount)).red());
        items.add(UItem.asShadow(-7, null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_HIDE_PROXY) {
            AppConfig.toggleHideProxySponsor();
            applyCheck(item, view, AppConfig.hideProxySponsor);
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    MessagesController.getInstance(account).checkPromoInfo(true);
                }
            }
        } else if (id == ID_DELETE_ACCOUNT) {
            if (AppConfig.askPasscodeBeforeDelete) {
                runAfterAuthentication(() -> DeleteAccountDialog.showDeleteAccountDialog(this));
            } else {
                DeleteAccountDialog.showDeleteAccountDialog(this);
            }
        } else if (id == ID_HIDE_ARCHIVED_STORIES) {
            AppConfig.toggleHideArchivedStories();
            applyCheck(item, view, AppConfig.hideArchivedStories);
            showRestartBulletin();
        } else if (id == ID_HIDE_ARCHIVE_LIST) {
            AppConfig.toggleHideArchiveFromChatsList();
            applyCheck(item, view, AppConfig.hideArchiveFromChatsList);
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
                }
            }
            updateItemsAfterToggle();
        } else if (id == ID_OPEN_ARCHIVE) {
            ChatMenuInjector.openArchivedChats(this);
        } else if (id == ID_PROTECT_SELECTED_CHATS) {
            changeProtectedSetting(AppConfig.askBiometricsToOpenChat, () -> {
                AppConfig.toggleAskBiometricsToOpenChat();
                applyCheck(item, view, AppConfig.askBiometricsToOpenChat);
                updateItemsAfterToggle();
            });
        } else if (id == ID_PROTECT_SAVED_MESSAGES) {
            changeProtectedSetting(AppConfig.askBiometricsToOpenSavedMessages, () -> {
                AppConfig.toggleAskBiometricsToOpenSavedMessages();
                applyCheck(item, view, AppConfig.askBiometricsToOpenSavedMessages);
            });
        } else if (id == ID_PROTECT_SECRET_CHATS) {
            changeProtectedSetting(AppConfig.askBiometricsToOpenEncrypted, () -> {
                AppConfig.toggleAskBiometricsToOpenEncrypted();
                applyCheck(item, view, AppConfig.askBiometricsToOpenEncrypted);
            });
        } else if (id == ID_PROTECT_ARCHIVE) {
            changeProtectedSetting(AppConfig.askBiometricsToOpenArchive, () -> {
                AppConfig.toggleAskBiometricsToOpenArchive();
                applyCheck(item, view, AppConfig.askBiometricsToOpenArchive);
            });
        } else if (id == ID_LOCKED_CHATS) {
            runAfterAuthentication(() -> presentFragment(new LockedChatsPreferencesActivity()));
        } else if (id == ID_LOCKED_CHATS_TTL) {
            runAfterAuthentication(() -> showLockedChatsTtlPicker(view));
        } else if (id == ID_REQUIRE_BIO_DELETE) {
            changeProtectedSetting(AppConfig.askPasscodeBeforeDelete, () -> {
                AppConfig.toggleAskPasscodeBeforeDelete();
                applyCheck(item, view, AppConfig.askPasscodeBeforeDelete);
            });
        } else if (id == ID_ALLOW_SYSTEM_PASSCODE) {
            Runnable toggle = () -> {
                AppConfig.toggleAllowSystemPasscode();
                applyCheck(item, view, AppConfig.allowSystemPasscode);
            };
            if (AppConfig.allowSystemPasscode && !AppBiometricPrompt.canAuthenticate(true)) {
                confirmProtectionReset(toggle);
            } else {
                runAfterAuthentication(true, toggle);
            }
        } else if (id == ID_TEST_FINGERPRINT) {
            testFingerprint();
        }
    }

    private void runAfterAuthentication(Runnable action) {
        runAfterAuthentication(AppConfig.allowSystemPasscode, action);
    }

    private void runAfterAuthentication(boolean allowSystem, Runnable action) {
        if (getParentActivity() == null) {
            showAuthenticationRequired();
            return;
        }
        if (!AppBiometricPrompt.canAuthenticate(allowSystem)) {
            showAuthenticationRequired();
            return;
        }
        AppBiometricPrompt.prompt(getParentActivity(), currentAccount, allowSystem,
                action, this::showAuthenticationRequired);
    }

    private void changeProtectedSetting(boolean currentlyEnabled, Runnable action) {
        boolean allowSystem = AppConfig.allowSystemPasscode;
        if (currentlyEnabled && !AppBiometricPrompt.canAuthenticate(allowSystem)
                && AppBiometricPrompt.canAuthenticate(true)) {
            allowSystem = true;
        }
        if (currentlyEnabled && !AppBiometricPrompt.canAuthenticate(allowSystem)) {
            confirmProtectionReset(action);
            return;
        }
        runAfterAuthentication(allowSystem, action);
    }

    private void confirmProtectionReset(Runnable action) {
        if (getParentActivity() == null) {
            showAuthenticationRequired();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.NM_PR_AuthenticationUnavailable))
                .setMessage(LocaleController.getString(R.string.NM_PR_AuthenticationUnavailable_Desc))
                .setPositiveButton(LocaleController.getString(R.string.Disable), (dialog, which) -> action.run())
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }


    private void showAuthenticationRequired() {
        BulletinFactory.of(this).createErrorBulletin(
                LocaleController.getString(R.string.NM_PR_AuthenticationRequired)
        ).show();
    }

    private void testFingerprint() {
        if (getParentActivity() == null) return;
        AppBiometricPrompt.fixFingerprint(getParentActivity(), new AppBiometricPrompt.BiometricListener() {
            @Override
            public void onSuccess(BiometricPrompt.AuthenticationResult result) {
                AppBiometricPrompt.cancelPendingAuthentications();
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
                AndroidUtilities.runOnUIThread(() ->
                        BulletinFactory.of(PrivacyPreferencesActivity.this).createSimpleBulletin(
                                R.raw.chats_infotip,
                                LocaleController.getString(R.string.NM_PR_TestFingerprint)
                        ).show(), 300);
            }

            @Override
            public void onFailed() {

            }

            @Override
            public void onError(int error, CharSequence msg) {
                showError(error);
            }

            private void showError(int error) {
                BulletinFactory.of(PrivacyPreferencesActivity.this).createSimpleBulletin(
                        R.raw.chats_infotip,
                        LocaleController.getString(R.string.NM_PR_TestFingerprint_Desc),
                        LocaleController.getString(R.string.Settings),
                        () -> openFingerprintSettings(getContext())
                ).show();
            }
        });
    }

    private static void openFingerprintSettings(Context context) {
        if (context == null) return;
        Intent fallbackIntent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Intent fingerprintIntent = new Intent(Settings.ACTION_FINGERPRINT_ENROLL);
                fingerprintIntent.setPackage("com.android.settings");
                if (fingerprintIntent.resolveActivity(context.getPackageManager()) != null) {
                    context.startActivity(fingerprintIntent);
                    return;
                }
            }
            context.startActivity(fallbackIntent);
        } catch (SecurityException e) {
            FileLog.e(e);
            try { context.startActivity(fallbackIntent); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;

        updateCheckState(view, value);
    }

    private String getLockedChatsTtlValueText() {
        int s = AppConfig.lockedChatsBiometricTtlSec;
        if (s == AppConfig.LOCKED_CHATS_TTL_ALWAYS) {
            return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_Always);
        }
        if (s == AppConfig.LOCKED_CHATS_TTL_UNTIL_RESTART) {
            return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_UntilRestart);
        }
        if (s == AppConfig.LOCKED_CHATS_TTL_1_MIN) {
            return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_1Min);
        }
        if (s == AppConfig.LOCKED_CHATS_TTL_15_MIN) {
            return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_15Min);
        }
        return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_5Min);
    }

    private void showLockedChatsTtlPicker(View anchor) {
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_Always));
        values.add(AppConfig.LOCKED_CHATS_TTL_ALWAYS);
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_1Min));
        values.add(AppConfig.LOCKED_CHATS_TTL_1_MIN);
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_5Min));
        values.add(AppConfig.LOCKED_CHATS_TTL_5_MIN);
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_15Min));
        values.add(AppConfig.LOCKED_CHATS_TTL_15_MIN);
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_UntilRestart));
        values.add(AppConfig.LOCKED_CHATS_TTL_UNTIL_RESTART);
        int current = values.indexOf(AppConfig.lockedChatsBiometricTtlSec);
        if (current < 0) current = values.indexOf(AppConfig.LOCKED_CHATS_TTL_5_MIN);
        PopupHelper.show(labels,
                LocaleController.getString(R.string.NM_PR_LockedChatsTtl),
                current,
                getContext(),
                i -> {
                    AppConfig.setLockedChatsBiometricTtl(values.get(i));

                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
    }
}
