/*
 * Derived from Cherrygram. Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later.
 */
package app.nimarkogram.messenger.utils;

import android.app.Activity;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.security.NimarkoBiometricPrompt;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

public final class CGCompat {

    private CGCompat() {}

    public static void runOrAskBiometricsBeforeDelete(Activity activity, Runnable action) {
        runOrAsk(activity, NimarkoConfig.askBiometricsBeforeDelete, action);
    }

    public static void runOrAskBeforeDestructive(Activity activity, Runnable action) {
        boolean wantPrompt = NimarkoConfig.askBiometricsBeforeDelete || NimarkoConfig.askPasscodeBeforeDelete;
        runOrAsk(activity, wantPrompt, action);
    }

    private static void runOrAsk(Activity activity, boolean wantPrompt, Runnable action) {
        if (action == null) return;
        if (!wantPrompt) {
            action.run();
            return;
        }
        if (activity == null) return;
        if (!NimarkoBiometricPrompt.canAuthenticateConfigured()) return;
        try {
            NimarkoBiometricPrompt.prompt(activity, action, null);
        } catch (Throwable t) {
            FileLog.e("Nimarko destructive authentication prompt failed closed", t);
        }
    }

    public static boolean isChatLocked(long dialogId) {
        return isChatLocked(UserConfig.selectedAccount, dialogId);
    }

    public static boolean isChatLocked(int account, long dialogId) {
        if (!NimarkoConfig.askBiometricsToOpenChat) return false;
        if (dialogId == 0L) return false;
        return LockedChats.isLocked(account, dialogId);
    }

    public static boolean shouldRequireBiometricsToOpenChats() {
        return NimarkoConfig.askBiometricsToOpenChat;
    }

    public static boolean shouldRequireBiometricsToOpenEncryptedChats() {
        return NimarkoConfig.askBiometricsToOpenEncrypted;
    }

    public static boolean shouldRequireBiometricsToOpenArchive() {
        return NimarkoConfig.askBiometricsToOpenArchive;
    }

    public static void unlockChatThen(Activity activity, long dialogId, Runnable onUnlocked) {
        unlockChatThen(activity, UserConfig.selectedAccount, dialogId, onUnlocked);
    }

    public static void unlockChatThen(Activity activity, int account, long dialogId, Runnable onUnlocked) {
        if (onUnlocked == null) return;
        if (!isChatLocked(account, dialogId)
                || NimarkoBiometricPrompt.isRecentlyVerified(account, 0L, dialogId, 0)) {
            onUnlocked.run();
            return;
        }
        if (activity == null) return;
        if (!NimarkoBiometricPrompt.canAuthenticateConfigured()) return;
        try {
            NimarkoBiometricPrompt.prompt(activity, () -> {
                NimarkoBiometricPrompt.markVerified(account, 0L, dialogId, 0);
                onUnlocked.run();
            }, null);
        } catch (Throwable t) {
            FileLog.e("Nimarko chat authentication prompt failed closed", t);
        }
    }
}
