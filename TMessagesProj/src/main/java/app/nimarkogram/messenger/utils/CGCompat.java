/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This file is part of NimarkoGram for Android.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 * Copyright Ettacent, 2026.
 */

package app.nimarkogram.messenger.utils;

import android.app.Activity;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.security.AppBiometricPrompt;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

public final class CGCompat {

    private CGCompat() {}

    public static void runOrAskBiometricsBeforeDelete(Activity activity, Runnable action) {
        runOrAsk(activity, UserConfig.selectedAccount, AppConfig.askPasscodeBeforeDelete, action);
    }

    public static void runOrAskBiometricsBeforeDelete(Activity activity, int account, Runnable action) {
        runOrAsk(activity, account, AppConfig.askPasscodeBeforeDelete, action);
    }

    public static void runOrAskBeforeDestructive(Activity activity, Runnable action) {
        runOrAsk(activity, UserConfig.selectedAccount, AppConfig.askPasscodeBeforeDelete, action);
    }

    public static void runOrAskBeforeDestructive(Activity activity, int account, Runnable action) {
        runOrAsk(activity, account, AppConfig.askPasscodeBeforeDelete, action);
    }

    private static void runOrAsk(Activity activity, int account, boolean wantPrompt, Runnable action) {
        if (action == null) return;
        if (!wantPrompt) {
            action.run();
            return;
        }
        if (activity == null) return;
        if (!AppBiometricPrompt.canAuthenticateConfigured()) return;
        try {
            AppBiometricPrompt.prompt(activity, account, action, null);
        } catch (Throwable t) {
            FileLog.e("Nimarko destructive authentication prompt failed closed", t);
        }
    }

    public static boolean isChatLocked(long dialogId) {
        return isChatLocked(UserConfig.selectedAccount, dialogId);
    }

    public static boolean isChatLocked(int account, long dialogId) {
        if (!AppConfig.askBiometricsToOpenChat) return false;
        if (dialogId == 0L) return false;
        return LockedChats.isLocked(account, dialogId);
    }

    public static boolean shouldRequireBiometricsToOpenChats() {
        return AppConfig.askBiometricsToOpenChat;
    }

    public static boolean shouldRequireBiometricsToOpenEncryptedChats() {
        return AppConfig.askBiometricsToOpenEncrypted;
    }

    public static boolean shouldRequireBiometricsToOpenArchive() {
        return AppConfig.askBiometricsToOpenArchive;
    }

    public static void unlockChatThen(Activity activity, long dialogId, Runnable onUnlocked) {
        unlockChatThen(activity, UserConfig.selectedAccount, dialogId, onUnlocked);
    }

    public static void unlockChatThen(Activity activity, int account, long dialogId, Runnable onUnlocked) {
        if (onUnlocked == null) return;
        if (!isChatLocked(account, dialogId)
                || AppBiometricPrompt.isRecentlyVerified(account, 0L, dialogId, 0)) {
            onUnlocked.run();
            return;
        }
        if (activity == null) return;
        if (!AppBiometricPrompt.canAuthenticateConfigured()) return;
        try {
            AppBiometricPrompt.prompt(activity, account, () -> {
                AppBiometricPrompt.markVerified(account, 0L, dialogId, 0);
                onUnlocked.run();
            }, null);
        } catch (Throwable t) {
            FileLog.e("Nimarko chat authentication prompt failed closed", t);
        }
    }
}
