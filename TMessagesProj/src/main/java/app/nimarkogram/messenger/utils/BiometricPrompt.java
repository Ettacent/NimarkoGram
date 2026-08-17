/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.utils;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FingerprintController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.fingerprint.FingerprintManagerCompat;

import java.util.ArrayList;

import app.nimarkogram.messenger.NimarkoConfig;

public class BiometricPrompt {

    private static final Object AUTH_LOCK = new Object();
    private static final ArrayList<PendingAuth> pendingAuths = new ArrayList<>();
    private static final Object FINGERPRINT_CACHE_LOCK = new Object();
    private static boolean isFirstCheck = true;
    private static boolean fingerprintCachedState = false;

    private static final class PendingAuth {
        final androidx.biometric.BiometricPrompt prompt;
        final Activity owner;
        final int account;
        final long ownerUid;
        boolean terminal;

        PendingAuth(
                androidx.biometric.BiometricPrompt prompt,
                Activity owner,
                int account,
                long ownerUid
        ) {
            this.prompt = prompt;
            this.owner = owner;
            this.account = account;
            this.ownerUid = ownerUid;
        }
    }

    private static androidx.biometric.BiometricPrompt.PromptInfo createPromptInfo() {
        androidx.biometric.BiometricPrompt.PromptInfo.Builder builder = new androidx.biometric.BiometricPrompt.PromptInfo.Builder();
        builder.setTitle(getString(R.string.AppName));
        if (!NimarkoConfig.allowSystemPasscode) {
            builder.setNegativeButtonText(getString(R.string.Cancel));
        }

        builder.setDeviceCredentialAllowed(NimarkoConfig.allowSystemPasscode);
        builder.setConfirmationRequired(false);

        return builder.build();
    }

    private static androidx.biometric.BiometricPrompt.AuthenticationCallback createCallback(
            java.util.function.Consumer<androidx.biometric.BiometricPrompt.AuthenticationResult> onSuccess,
            Runnable onFailed,
            java.util.function.BiConsumer<Integer, CharSequence> onError
    ) {
        return new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull androidx.biometric.BiometricPrompt.AuthenticationResult result) {
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

    private static boolean isOwnerLive(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                || !activity.isDestroyed());
    }

    private static long captureOwnerUid(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0L;
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static boolean isIdentityLive(PendingAuth auth) {
        return auth != null
                && auth.ownerUid > 0
                && isOwnerLive(auth.owner)
                && UserConfig.selectedAccount == auth.account
                && captureOwnerUid(auth.account) == auth.ownerUid;
    }

    private static void startPrompt(Activity activity, CGBiometricListener listener) {
        final int account = UserConfig.selectedAccount;
        final long ownerUid = captureOwnerUid(account);
        if (!(activity instanceof FragmentActivity)
                || !isOwnerLive(activity)
                || ownerUid <= 0) {
            if (listener != null) {
                listener.onError(androidx.biometric.BiometricPrompt.ERROR_CANCELED,
                        "Authentication owner is no longer active");
            }
            return;
        }

        final PendingAuth[] authRef = new PendingAuth[1];
        androidx.biometric.BiometricPrompt prompt = new androidx.biometric.BiometricPrompt(
                (FragmentActivity) activity,
                ContextCompat.getMainExecutor(activity),
                createCallback(
                        result -> {
                            synchronized (AUTH_LOCK) {
                                PendingAuth auth = authRef[0];
                                if (auth == null || auth.terminal) return;
                                auth.terminal = true;
                                pendingAuths.remove(auth);
                                if (isIdentityLive(auth) && listener != null) {
                                    listener.onSuccess(result);
                                }
                            }
                        },
                        () -> {
                            synchronized (AUTH_LOCK) {
                                PendingAuth auth = authRef[0];
                                if (auth == null || auth.terminal || !isIdentityLive(auth)) {
                                    return;
                                }
                                if (listener != null) {
                                    listener.onFailed();
                                }
                            }
                        },
                        (error, message) -> {
                            synchronized (AUTH_LOCK) {
                                PendingAuth auth = authRef[0];
                                if (auth == null || auth.terminal) return;
                                auth.terminal = true;
                                pendingAuths.remove(auth);
                                if (isIdentityLive(auth) && listener != null) {
                                    listener.onError(error, message);
                                }
                            }
                        }
                )
        );
        PendingAuth auth = new PendingAuth(prompt, activity, account, ownerUid);
        authRef[0] = auth;

        Throwable authenticateFailure = null;
        boolean ownerRejected = false;
        synchronized (AUTH_LOCK) {
            if (!isIdentityLive(auth)) {
                auth.terminal = true;
                ownerRejected = true;
            } else {
                pendingAuths.add(auth);
                try {
                    prompt.authenticate(createPromptInfo());
                } catch (Throwable t) {
                    auth.terminal = true;
                    pendingAuths.remove(auth);
                    authenticateFailure = t;
                }
            }
        }
        if (ownerRejected) {
            return;
        } else if (authenticateFailure != null) {
            FileLog.e(authenticateFailure);
            if (listener != null && isIdentityLive(auth)) {
                listener.onError(androidx.biometric.BiometricPrompt.ERROR_CANCELED,
                        "Unable to start authentication");
            }
        }
    }

    public static void callBiometricPrompt(Activity activity, CGBiometricListener listener) {
        startPrompt(activity, listener);
    }

    public static void prompt(Activity activity, Runnable successCallback) {
        prompt(activity, successCallback, null);
    }

    public static void prompt(Activity activity, Runnable successCallback, Runnable failCallback) {
        BiometricPrompt.callBiometricPrompt(activity, new BiometricPrompt.CGBiometricListener() {
            @Override
            public void onSuccess(androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                if (successCallback != null) successCallback.run();
                if (NimarkoConfig.showRPCErrors)
                    Toast.makeText(activity, "Success", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailed() {
                if (failCallback != null) failCallback.run();
                if (NimarkoConfig.showRPCErrors)
                    Toast.makeText(activity, "Fail", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(int error, CharSequence msg) {
                if (failCallback != null) failCallback.run();
                if (NimarkoConfig.showRPCErrors)
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public interface CGBiometricListener {
        void onSuccess(androidx.biometric.BiometricPrompt.AuthenticationResult result);
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

    public static int getBiometricIconResId() {
        boolean hasFingerprint = hasEnrolledFingerprints();
        boolean hasFace = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BiometricManager bm = ApplicationLoader.applicationContext.getSystemService(BiometricManager.class);
            if (bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS && !hasFingerprint) {
                hasFace = true;
            } else if (bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS && hasFingerprint) {
                hasFace = true;
            }
        }

        if (hasFingerprint && hasFace) return R.drawable.fingerprint;
        if (hasFingerprint) return R.drawable.fingerprint;
        if (hasFace) return R.drawable.face_scan_square_filled_solar;
        return R.drawable.fingerprint;
    }

    private static final String TAG = "CGBiometricPrompt";

    public static void fixFingerprint(Activity activity, CGBiometricListener callback) {

        FingerprintController.checkKeyReady();
        FingerprintController.deleteInvalidKey();
        FingerprintController.checkKeyReady();

        cancelPendingAuthentications();

        startPrompt(activity, new CGBiometricListener() {
            @Override
            public void onSuccess(androidx.biometric.BiometricPrompt.AuthenticationResult result) {
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
        ArrayList<androidx.biometric.BiometricPrompt> prompts = new ArrayList<>();
        synchronized (AUTH_LOCK) {
            for (PendingAuth auth : pendingAuths) {
                auth.terminal = true;
                prompts.add(auth.prompt);
            }
            pendingAuths.clear();
        }
        for (androidx.biometric.BiometricPrompt prompt : prompts) {
            try {
                prompt.cancelAuthentication();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    public static boolean hasFingerprintCached() {
        synchronized (FINGERPRINT_CACHE_LOCK) {
            if (isFirstCheck) {
                fingerprintCachedState = hasFingerprintInternal();
                isFirstCheck = false;
            }
            return fingerprintCachedState;
        }
    }

    public static void reloadFingerprintState() {
        synchronized (FINGERPRINT_CACHE_LOCK) {
            fingerprintCachedState = hasFingerprintInternal();
            isFirstCheck = false;
        }
    }

    private static boolean hasFingerprintInternal() {
        try {
            Log.d(TAG, "Starting fingerprint check...");

            FingerprintManagerCompat fingerprintManager = FingerprintManagerCompat.from(ApplicationLoader.applicationContext);

            boolean conditions = fingerprintManager.isHardwareDetected();
            Log.d(TAG, "Fingerprint hardware detected: " + conditions);

            conditions &= fingerprintManager.hasEnrolledFingerprints();
            Log.d(TAG, "Enrolled fingerprints: " + fingerprintManager.hasEnrolledFingerprints());

            conditions &= FingerprintController.isKeyReady();
            Log.d(TAG, "Fingerprint key ready: " + FingerprintController.isKeyReady());

            conditions &= !FingerprintController.checkDeviceFingerprintsChanged();
            Log.d(TAG, "Device fingerprints changed: " + !FingerprintController.checkDeviceFingerprintsChanged());

            Log.d(TAG, "Final fingerprint check result: " + conditions);
            return conditions;
        } catch (Throwable e) {
            FileLog.e("Error checking fingerprint availability", e);
        }
        return false;
    }

}
