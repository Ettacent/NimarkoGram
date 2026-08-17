package app.nimarkogram.messenger.preferences;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.view.View;

import androidx.biometric.BiometricPrompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;
import app.nimarkogram.messenger.security.NimarkoBiometricPrompt;

public class PrivacyPreferencesActivity extends BasePreferencesActivity {
    private static final int ID_HIDE_PROXY = 1;
    private static final int ID_DELETE_ACCOUNT = 2;

    private static final int ID_HIDE_ARCHIVED_STORIES = 3;
    private static final int ID_HIDE_ARCHIVE_LIST = 4;
    private static final int ID_ASK_BIO_CHAT = 5;
    private static final int ID_LOCKED_CHATS = 6;
    private static final int ID_REQUIRE_BIO_DELETE = 7;
    private static final int ID_ALLOW_SYSTEM_PASSCODE = 8;
    private static final int ID_TEST_FINGERPRINT = 9;
    
    private static final int ID_LOCKED_CHATS_TTL = 11;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_Cat_Privacy);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_PR_Header_Privacy)));
        items.add(UItem.asCheck(ID_HIDE_PROXY, LocaleController.getString(R.string.NM_PR_HideProxy))
                .setChecked(NimarkoConfig.hideProxySponsor));

        UItem deleteAccountBtn = UItem.asButton(ID_DELETE_ACCOUNT, R.drawable.msg_delete,
                LocaleController.getString(R.string.NM_PR_DeleteAccount));
        deleteAccountBtn.red = true;
        items.add(deleteAccountBtn);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.FilterChats)));
        
        items.add(UItem.asButtonCheck(ID_HIDE_ARCHIVED_STORIES,
                        LocaleController.getString(R.string.NM_PR_HideArchivedStories),
                        LocaleController.getString(R.string.NM_PR_HideArchivedStories_Desc))
                .setChecked(NimarkoConfig.hideArchivedStories));
        
        items.add(UItem.asButtonCheck(ID_HIDE_ARCHIVE_LIST,
                        LocaleController.getString(R.string.NM_PR_HideArchiveList),
                        LocaleController.getString(R.string.NM_PR_HideArchiveList_Desc))
                .setChecked(NimarkoConfig.hideArchiveFromChatsList));
        
        boolean bioStateActive = NimarkoConfig.askBiometricsToOpenChat
                || NimarkoConfig.askBiometricsToOpenEncrypted
                || NimarkoConfig.askBiometricsToOpenArchive
                || NimarkoConfig.askPasscodeBeforeDelete
                || app.nimarkogram.messenger.utils.LockedChats.count(currentAccount) > 0;
        if (NimarkoBiometricPrompt.hasBiometricEnrolled() || bioStateActive) {
            
            items.add(UItem.asButton(ID_ASK_BIO_CHAT, R.drawable.msg_pin_code,
                    LocaleController.getString(R.string.NM_PR_AskBioOpenChats)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.NM_PR_AskBioOpenChats_Desc)));
            if (NimarkoConfig.askBiometricsToOpenChat) {
                int count = app.nimarkogram.messenger.utils.LockedChats.count(currentAccount);
                items.add(UItem.asButton(ID_LOCKED_CHATS,
                        R.drawable.msg_discussion,
                        LocaleController.getString(R.string.NM_PR_LockedChats),
                        String.valueOf(count)));
                items.add(UItem.asButton(ID_LOCKED_CHATS_TTL,
                        R.drawable.msg_recent,
                        LocaleController.getString(R.string.NM_PR_LockedChatsTtl),
                        getLockedChatsTtlValueText()));
            }
            
            items.add(UItem.asButtonCheck(ID_REQUIRE_BIO_DELETE,
                            LocaleController.getString(R.string.NM_PR_RequireBiometricsToDelete),
                            LocaleController.getString(R.string.NM_PR_RequireBiometricsToDelete_Desc))
                    .setChecked(NimarkoConfig.askPasscodeBeforeDelete));
            
            items.add(UItem.asButtonCheck(ID_ALLOW_SYSTEM_PASSCODE,
                            LocaleController.getString(R.string.NM_PR_AllowSystemPasscode),
                            LocaleController.getString(R.string.NM_PR_AllowSystemPasscode_Desc))
                    .setChecked(NimarkoConfig.allowSystemPasscode));
        }

        items.add(UItem.asButton(ID_TEST_FINGERPRINT, R.drawable.fingerprint,
                LocaleController.getString(R.string.NM_PR_TestFingerprint)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_PR_TestFingerprint_Desc)));
        
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_HIDE_PROXY) {
            NimarkoConfig.toggleHideProxySponsor();
            applyCheck(item, view, NimarkoConfig.hideProxySponsor);
            
            getMessagesController().checkPromoInfo(true);
        } else if (id == ID_DELETE_ACCOUNT) {
            runAfterAuthentication(() -> DeleteAccountDialog.showDeleteAccountDialog(this));
        } else if (id == ID_HIDE_ARCHIVED_STORIES) {
            NimarkoConfig.toggleHideArchivedStories();
            applyCheck(item, view, NimarkoConfig.hideArchivedStories);
            showRestartBulletin();
        } else if (id == ID_HIDE_ARCHIVE_LIST) {
            NimarkoConfig.toggleHideArchiveFromChatsList();
            applyCheck(item, view, NimarkoConfig.hideArchiveFromChatsList);
            
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        } else if (id == ID_ASK_BIO_CHAT) {
            runAfterAuthentication(this::showPasscodeItemsSelector);
        } else if (id == ID_LOCKED_CHATS) {
            runAfterAuthentication(() -> presentFragment(new LockedChatsPreferencesActivity()));
        } else if (id == ID_LOCKED_CHATS_TTL) {
            runAfterAuthentication(() -> showLockedChatsTtlPicker(view));
        } else if (id == ID_REQUIRE_BIO_DELETE) {
            runAfterAuthentication(() -> {
                NimarkoConfig.toggleAskPasscodeBeforeDelete();
                applyCheck(item, view, NimarkoConfig.askPasscodeBeforeDelete);
            });
        } else if (id == ID_ALLOW_SYSTEM_PASSCODE) {
            runAfterAuthentication(() -> {
                NimarkoConfig.toggleAllowSystemPasscode();
                applyCheck(item, view, NimarkoConfig.allowSystemPasscode);
            });
        } else if (id == ID_TEST_FINGERPRINT) {
            testFingerprint();
        }
    }

    private void runAfterAuthentication(Runnable action) {
        if (getParentActivity() == null) {
            showAuthenticationRequired();
            return;
        }
        NimarkoBiometricPrompt.prompt(getParentActivity(), action, this::showAuthenticationRequired);
    }

    private void showAuthenticationRequired() {
        BulletinFactory.of(this).createErrorBulletin(
                LocaleController.getString(R.string.NM_PR_AuthenticationRequired)
        ).show();
    }

    private void testFingerprint() {
        if (getParentActivity() == null) return;
        NimarkoBiometricPrompt.fixFingerprint(getParentActivity(), new NimarkoBiometricPrompt.NimarkoBiometricListener() {
            @Override
            public void onSuccess(BiometricPrompt.AuthenticationResult result) {
                NimarkoBiometricPrompt.cancelPendingAuthentications();
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

    private void showPasscodeItemsSelector() {
        if (getParentActivity() == null) return;

        List<String> labels = Arrays.asList(
                LocaleController.getString(R.string.FilterChats),
                LocaleController.getString(R.string.SecretChat),
                LocaleController.getString(R.string.ArchivedChats)
        );
        List<Boolean> checks = Arrays.asList(
                NimarkoConfig.askBiometricsToOpenChat,
                NimarkoConfig.askBiometricsToOpenEncrypted,
                NimarkoConfig.askBiometricsToOpenArchive
        );
        List<Runnable> listeners = Arrays.asList(
                () -> {
                    NimarkoConfig.toggleAskBiometricsToOpenChat();
                    if (listView != null && listView.adapter != null) listView.adapter.update(true);
                },
                NimarkoConfig::toggleAskBiometricsToOpenEncrypted,
                NimarkoConfig::toggleAskBiometricsToOpenArchive
        );

        PopupHelper.showSwitchAlert(
                LocaleController.getString(R.string.SelectChats),
                this,
                labels,
                checks,
                listeners
        );
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        
        updateCheckState(view, value);
    }

    private String getLockedChatsTtlValueText() {
        int s = NimarkoConfig.lockedChatsBiometricTtlSec;
        if (s == NimarkoConfig.LOCKED_CHATS_TTL_ALWAYS) {
            return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_Always);
        }
        if (s == NimarkoConfig.LOCKED_CHATS_TTL_UNTIL_RESTART) {
            return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_UntilRestart);
        }
        if (s == NimarkoConfig.LOCKED_CHATS_TTL_1_MIN) {
            return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_1Min);
        }
        if (s == NimarkoConfig.LOCKED_CHATS_TTL_15_MIN) {
            return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_15Min);
        }
        return LocaleController.getString(R.string.NM_PR_LockedChatsTtl_5Min);
    }

    private void showLockedChatsTtlPicker(View anchor) {
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_Always));
        values.add(NimarkoConfig.LOCKED_CHATS_TTL_ALWAYS);
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_1Min));
        values.add(NimarkoConfig.LOCKED_CHATS_TTL_1_MIN);
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_5Min));
        values.add(NimarkoConfig.LOCKED_CHATS_TTL_5_MIN);
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_15Min));
        values.add(NimarkoConfig.LOCKED_CHATS_TTL_15_MIN);
        labels.add(LocaleController.getString(R.string.NM_PR_LockedChatsTtl_UntilRestart));
        values.add(NimarkoConfig.LOCKED_CHATS_TTL_UNTIL_RESTART);
        int current = values.indexOf(NimarkoConfig.lockedChatsBiometricTtlSec);
        if (current < 0) current = values.indexOf(NimarkoConfig.LOCKED_CHATS_TTL_5_MIN);
        PopupHelper.show(labels,
                LocaleController.getString(R.string.NM_PR_LockedChatsTtl),
                current,
                getContext(),
                i -> {
                    NimarkoConfig.setLockedChatsBiometricTtl(values.get(i));
                    
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
    }
}
