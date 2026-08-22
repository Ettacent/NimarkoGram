package app.nimarkogram.messenger.security;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FingerprintController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.fingerprint.FingerprintManagerCompat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class NimarkoBiometricPrompt {

    private static final String TAG = "NimarkoBiometricPrompt";

    private static BiometricPrompt.PromptInfo createPromptInfo(boolean allowSystem) {
        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder();
        builder.setTitle(getString(R.string.exteraAppName));
        if (allowSystem) {
            builder.setDeviceCredentialAllowed(true);
        } else {
            builder.setNegativeButtonText(getString(R.string.Cancel));
            builder.setDeviceCredentialAllowed(false);
        }
        builder.setConfirmationRequired(false);
        return builder.build();
    }

    private static BiometricPrompt.AuthenticationCallback createCallback(
            java.util.function.Consumer<BiometricPrompt.AuthenticationResult> onSuccess,
            Runnable onFailed,
            java.util.function.BiConsumer<Integer, CharSequence> onError
    ) {
        return new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                if (onSuccess != null) onSuccess.accept(result);
            }

            @Override
            public void onAuthenticationFailed() {
                if (onFailed != null) onFailed.run();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                if (onError != null) onError.accept(errorCode, errString);
            }
        };
    }

    public static void callBiometricPrompt(Activity activity, NimarkoBiometricListener listener) {
        callBiometricPrompt(activity, UserConfig.selectedAccount, listener);
    }

    public static void callBiometricPrompt(Activity activity, int account, NimarkoBiometricListener listener) {
        callBiometricPrompt(activity, account,
                app.nimarkogram.messenger.NimarkoConfig.allowSystemPasscode, listener);
    }

    public static void callBiometricPrompt(Activity activity, int account,
                                           boolean allowSystem, NimarkoBiometricListener listener) {
        if (!(activity instanceof FragmentActivity) || !canAuthenticate(allowSystem)) {
            if (listener != null) listener.onError(BiometricPrompt.ERROR_HW_NOT_PRESENT, "Authentication unavailable");
            return;
        }
        startPrompt((FragmentActivity) activity, account, allowSystem, listener);
    }

    public static void prompt(Activity activity, Runnable successCallback) {
        prompt(activity, successCallback, null);
    }

    public static void prompt(Activity activity, Runnable successCallback, Runnable failCallback) {
        prompt(activity, UserConfig.selectedAccount, successCallback, failCallback);
    }

    public static void prompt(Activity activity, int account, Runnable successCallback) {
        prompt(activity, account, successCallback, null);
    }

    public static void prompt(Activity activity, int account, Runnable successCallback, Runnable failCallback) {
        prompt(activity, account, app.nimarkogram.messenger.NimarkoConfig.allowSystemPasscode,
                successCallback, failCallback);
    }

    public static void prompt(Activity activity, int account, boolean allowSystem,
                              Runnable successCallback, Runnable failCallback) {
        callBiometricPrompt(activity, account, allowSystem, new NimarkoBiometricListener() {
            @Override
            public void onSuccess(BiometricPrompt.AuthenticationResult result) {
                if (successCallback != null) successCallback.run();
            }

            @Override
            public void onFailed() {
                if (failCallback != null) failCallback.run();
            }

            @Override
            public void onError(int error, CharSequence msg) {
                if (failCallback != null) failCallback.run();
            }
        });
    }

    public interface NimarkoBiometricListener {
        void onSuccess(BiometricPrompt.AuthenticationResult result);
        void onFailed();
        void onError(int error, CharSequence msg);
    }

    public static boolean hasBiometricEnrolled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BiometricManager biometricManager = ApplicationLoader.applicationContext.getSystemService(BiometricManager.class);
            if (biometricManager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
            } else {
                return biometricManager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            }
        } else {
            return hasEnrolledFingerprints();
        }
    }

    public static boolean canAuthenticateConfigured() {
        return canAuthenticate(app.nimarkogram.messenger.NimarkoConfig.allowSystemPasscode);
    }

    public static boolean canAuthenticate(boolean allowSystem) {
        try {
            int authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK;
            if (allowSystem) {
                authenticators |= androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            }
            return androidx.biometric.BiometricManager.from(ApplicationLoader.applicationContext)
                    .canAuthenticate(authenticators) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static boolean hasEnrolledFingerprints() {
        FingerprintManager fingerprintManager = ApplicationLoader.applicationContext.getSystemService(FingerprintManager.class);
        if (fingerprintManager != null) {
            try {
                return fingerprintManager.isHardwareDetected() && fingerprintManager.hasEnrolledFingerprints();
            } catch (SecurityException e) {
                FileLog.e(e);
                return false;
            }
        } else {
            try {
                FingerprintManagerCompat compat = FingerprintManagerCompat.from(ApplicationLoader.applicationContext);
                return compat.isHardwareDetected() && compat.hasEnrolledFingerprints();
            } catch (Throwable e) {
                FileLog.e(e);
                return false;
            }
        }
    }


    private static final Object AUTH_LOCK = new Object();
    private static final ArrayList<PendingAuth> pendingAuths = new ArrayList<>();
    private static final java.util.HashSet<Integer> loggingOutAccounts = new java.util.HashSet<>();

    private static final class PendingAuth {
        final BiometricPrompt prompt;
        final Activity owner;
        final int account;
        final long ownerUid;
        boolean terminal;

        PendingAuth(BiometricPrompt prompt, Activity owner, int account, long ownerUid) {
            this.prompt = prompt;
            this.owner = owner;
            this.account = account;
            this.ownerUid = ownerUid;
        }
    }

    private static long captureOwnerUid(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0L;
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static boolean isOwnerLive(Activity owner) {
        return owner != null
                && !owner.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !owner.isDestroyed());
    }

    private static boolean isIdentityLive(PendingAuth auth) {
        return auth != null
                && auth.ownerUid > 0
                && isOwnerLive(auth.owner)
                && !loggingOutAccounts.contains(auth.account)
                && captureOwnerUid(auth.account) == auth.ownerUid;
    }

    private static void startPrompt(FragmentActivity activity, int account, boolean allowSystem,
                                    NimarkoBiometricListener callback) {
        final long ownerUid = captureOwnerUid(account);
        if (ownerUid <= 0 || !isOwnerLive(activity)) {
            if (callback != null) {
                callback.onError(BiometricPrompt.ERROR_CANCELED, "Authentication owner is no longer active");
            }
            return;
        }

        final PendingAuth[] authRef = new PendingAuth[1];
        BiometricPrompt prompt = new BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                createCallback(
                        result -> {
                            synchronized (AUTH_LOCK) {
                                PendingAuth auth = authRef[0];
                                if (auth == null || auth.terminal) return;
                                auth.terminal = true;
                                pendingAuths.remove(auth);
                                if (isIdentityLive(auth)) {
                                    if (callback != null) callback.onSuccess(result);
                                } else if (callback != null) {
                                    callback.onError(BiometricPrompt.ERROR_CANCELED,
                                            "Authentication owner changed");
                                }
                            }
                        },
                        () -> {
                            synchronized (AUTH_LOCK) {
                                PendingAuth auth = authRef[0];
                                if (auth == null || auth.terminal || !isIdentityLive(auth)) return;
                                if (callback != null) callback.onFailed();
                            }
                        },
                        (code, errStr) -> {
                            synchronized (AUTH_LOCK) {
                                PendingAuth auth = authRef[0];
                                if (auth == null || auth.terminal) return;
                                auth.terminal = true;
                                pendingAuths.remove(auth);
                                if (callback != null) callback.onError(code, errStr);
                            }
                        }
                )
        );
        PendingAuth auth = new PendingAuth(prompt, activity, account, ownerUid);
        authRef[0] = auth;
        synchronized (AUTH_LOCK) {
            if (!isIdentityLive(auth)) {
                auth.terminal = true;
                if (callback != null) {
                    callback.onError(BiometricPrompt.ERROR_CANCELED,
                            "Authentication owner changed");
                }
                return;
            }
            pendingAuths.add(auth);
            prompt.authenticate(createPromptInfo(allowSystem));
        }
    }

    public static void fixFingerprint(Activity activity, NimarkoBiometricListener callback) {
        fixFingerprint(activity, UserConfig.selectedAccount, callback);
    }

    public static void fixFingerprint(Activity activity, int account, NimarkoBiometricListener callback) {
        if (!(activity instanceof FragmentActivity) || !canAuthenticateConfigured()) {
            if (callback != null) {
                callback.onError(BiometricPrompt.ERROR_HW_NOT_PRESENT, "Authentication unavailable");
            }
            return;
        }

        FingerprintController.checkKeyReady();
        FingerprintController.deleteInvalidKey();
        FingerprintController.checkKeyReady();

        cancelPendingAuthentications();
        startPrompt((FragmentActivity) activity, account,
                app.nimarkogram.messenger.NimarkoConfig.allowSystemPasscode,
                new NimarkoBiometricListener() {
            @Override
            public void onSuccess(BiometricPrompt.AuthenticationResult result) {
                Log.d(TAG, "PasscodeView onAuthenticationSucceeded");
                if (FingerprintController.isKeyReady() && FingerprintController.checkDeviceFingerprintsChanged()) {
                    FingerprintController.deleteInvalidKey();
                }
                if (callback != null) callback.onSuccess(result);
            }

            @Override
            public void onFailed() {
                if (callback != null) callback.onFailed();
            }

            @Override
            public void onError(int error, CharSequence msg) {
                Log.d(TAG, "PasscodeView onAuthenticationError: " + error + " \"" + msg + "\"");
                if (callback != null) callback.onError(error, msg);
            }
        });
    }

    public static void cancelPendingAuthentications() {
        cancelPendingAuthenticationsForAccount(-1);
    }

    public static void cancelPendingAuthenticationsForAccount(int account) {
        List<BiometricPrompt> prompts = new ArrayList<>();
        synchronized (AUTH_LOCK) {
            Iterator<PendingAuth> iterator = pendingAuths.iterator();
            while (iterator.hasNext()) {
                PendingAuth auth = iterator.next();
                if (account < 0 || auth.account == account) {
                    auth.terminal = true;
                    iterator.remove();
                    prompts.add(auth.prompt);
                }
            }
        }
        for (BiometricPrompt prompt : prompts) {
            try {
                prompt.cancelAuthentication();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }


    private static final java.util.HashMap<String, Long> recentlyVerified = new java.util.HashMap<>();
    private static final java.util.HashMap<Integer, Long> verifiedAccountUids = new java.util.HashMap<>();

    private static String verifyKey(int account, long userId, long chatId, int encId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return null;
        long uid = UserConfig.getInstance(account).getClientUserId();
        if (uid <= 0) return null;
        synchronized (recentlyVerified) {
            Long previousUid = verifiedAccountUids.put(account, uid);
            if (previousUid != null && previousUid != uid) removeAccountTokensLocked(account);
        }
        return account + ":" + uid + ":" + userId + ":" + chatId + ":" + encId;
    }

    public static void markVerified(int account, long userId, long chatId, int encId) {
        String key = verifyKey(account, userId, chatId, encId);
        if (key == null) return;
        synchronized (recentlyVerified) {
            recentlyVerified.put(key, SystemClock.elapsedRealtime());
        }
    }

    public static boolean isRecentlyVerified(int account, long userId, long chatId, int encId) {
        int ttlSec = app.nimarkogram.messenger.NimarkoConfig.lockedChatsBiometricTtlSec;
        long effectiveTtlMs;
        if (ttlSec == 0) {
            effectiveTtlMs = 500L;
        } else if (ttlSec < 0) {
            effectiveTtlMs = Long.MAX_VALUE;
        } else {
            effectiveTtlMs = ttlSec * 1000L;
        }
        String key = verifyKey(account, userId, chatId, encId);
        if (key == null) return false;
        synchronized (recentlyVerified) {
            Long ts = recentlyVerified.get(key);
            if (ts == null) return false;
            if (effectiveTtlMs != Long.MAX_VALUE
                    && SystemClock.elapsedRealtime() - ts > effectiveTtlMs) {
                recentlyVerified.remove(key);
                return false;
            }
            return true;
        }
    }

    public static void clearVerified() {
        synchronized (recentlyVerified) {
            recentlyVerified.clear();
            verifiedAccountUids.clear();
        }
    }

    public static void clearVerifiedForAccount(int account) {
        synchronized (AUTH_LOCK) {
            loggingOutAccounts.add(account);
        }
        cancelPendingAuthenticationsForAccount(account);
        synchronized (recentlyVerified) {
            removeAccountTokensLocked(account);
            verifiedAccountUids.remove(account);
        }
    }

    public static void onAccountOwnerCleared(int account) {
        synchronized (AUTH_LOCK) {
            loggingOutAccounts.remove(account);
        }
    }

    private static void removeAccountTokensLocked(int account) {
        String prefix = account + ":";
        java.util.Iterator<String> iterator = recentlyVerified.keySet().iterator();
        while (iterator.hasNext()) if (iterator.next().startsWith(prefix)) iterator.remove();
    }

    private static final long ARCHIVE_SENTINEL = Long.MIN_VALUE;

    public static void markArchiveVerified(int account) {
        markVerified(account, ARCHIVE_SENTINEL, ARCHIVE_SENTINEL, Integer.MIN_VALUE);
    }

    public static boolean isArchiveRecentlyVerified(int account) {
        return isRecentlyVerified(account, ARCHIVE_SENTINEL, ARCHIVE_SENTINEL, Integer.MIN_VALUE);
    }

    public static void onAppBackgrounded() {
        synchronized (recentlyVerified) {
            if (app.nimarkogram.messenger.NimarkoConfig.lockedChatsBiometricTtlSec == 0) {
                recentlyVerified.clear();
            }
        }
    }
}
