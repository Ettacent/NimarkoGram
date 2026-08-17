package app.nimarkogram.messenger.utils.system;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;

public abstract class SystemUtils {

    private static final int ROUND_AUDIO_BITRATE = 64;     
    private static final int ROUND_VIDEO_BITRATE = 1000;   
    private static final int ROUND_VIDEO_RESOLUTION = 512;  

    private SystemUtils() {
    }

    private static Context getContext() {
        return ApplicationLoader.applicationContext;
    }

    public static boolean isAppInstalled(String packageName) {
        if (packageName == null) {
            return false;
        }
        try {
            getContext().getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPermissionGranted(String permission) {
        if (permission == null) {
            return false;
        }
        try {
            return ContextCompat.checkSelfPermission(getContext(), permission)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return isImagesPermissionGranted()
                    && isVideoPermissionGranted()
                    && isAudioPermissionGranted();
        }
        return isPermissionGranted("android.permission.READ_EXTERNAL_STORAGE");
    }

    public static boolean isImagesPermissionGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return isPermissionGranted("android.permission.READ_MEDIA_IMAGES");
        }
        return isStoragePermissionGranted();
    }

    public static boolean isVideoPermissionGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return isPermissionGranted("android.permission.READ_MEDIA_VIDEO");
        }
        return isStoragePermissionGranted();
    }

    public static boolean isAudioPermissionGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return isPermissionGranted("android.permission.READ_MEDIA_AUDIO");
        }
        return isStoragePermissionGranted();
    }

    public static boolean isImagesAndVideoPermissionGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return isImagesPermissionGranted() && isVideoPermissionGranted();
        }
        return isStoragePermissionGranted();
    }

    public static boolean hasBiometrics() {
        try {
            int sdk = Build.VERSION.SDK_INT;
            if (sdk < 29) {
                FingerprintManager fingerprintManager =
                        (FingerprintManager) getContext().getSystemService(FingerprintManager.class);
                return fingerprintManager != null
                        && fingerprintManager.isHardwareDetected()
                        && fingerprintManager.hasEnrolledFingerprints();
            }
            BiometricManager biometricManager =
                    (BiometricManager) getContext().getSystemService(BiometricManager.class);
            if (biometricManager == null) {
                return false;
            }
            if (sdk >= 30) {
                return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        == BiometricManager.BIOMETRIC_SUCCESS;
            }
            return biometricManager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    public static void requestPermissions(Activity activity, int requestCode, String... permissions) {
        if (activity == null || permissions == null) {
            return;
        }
        try {
            ActivityCompat.requestPermissions(activity, permissions, requestCode);
        } catch (Exception ignored) {
        }
    }

    public static void requestStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(activity, 4,
                    "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_MEDIA_VIDEO",
                    "android.permission.READ_MEDIA_AUDIO");
        } else {
            requestPermissions(activity, 4, "android.permission.READ_EXTERNAL_STORAGE");
        }
    }

    public static int getRoundAudioBitrate() {
        return ROUND_AUDIO_BITRATE;
    }

    public static int getRoundVideoBitrate() {
        return ROUND_VIDEO_BITRATE;
    }

    public static int getRoundVideoResolution() {
        return ROUND_VIDEO_RESOLUTION;
    }
}
