package app.nimarkogram.messenger.utils.system;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import app.nimarkogram.messenger.NimarkoConfig;

public abstract class VibratorUtils {

    private static final Vibrator vibrator = resolveVibrator();

    private static Vibrator resolveVibrator() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return null;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) {
                    return manager.getDefaultVibrator();
                }
            }
            return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean isInAppVibrationEnabled() {
        try {
            return !NimarkoConfig.disableVibration;
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
    }

    private static boolean isVibrationAllowed() {
        try {
            return isInAppVibrationEnabled() && vibrator != null && vibrator.hasVibrator();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void disableHapticFeedback(View view) {
        if (view == null) {
            return;
        }
        try {
            view.setHapticFeedbackEnabled(false);
            if (view instanceof ViewGroup) {
                ViewGroup viewGroup = (ViewGroup) view;
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    disableHapticFeedback(viewGroup.getChildAt(i));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static int getType(int type) {
        if (isInAppVibrationEnabled()) {
            return type;
        }
        return -1;
    }

    public static void vibrate(long duration) {
        if (!isVibrationAllowed()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                vibrator.vibrate(duration);
            } catch (Exception e) {
                FileLog.e(e);
            }
            return;
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void vibrateEffect(VibrationEffect vibrationEffect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isVibrationAllowed()) {
            return;
        }
        try {
            vibrator.cancel();
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            vibrator.vibrate(vibrationEffect);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void vibrate() {
        vibrate(200L);
    }
}
