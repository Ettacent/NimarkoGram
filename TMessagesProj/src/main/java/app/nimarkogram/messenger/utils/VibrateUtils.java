package app.nimarkogram.messenger.utils;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import app.nimarkogram.messenger.NimarkoConfig;

public final class VibrateUtils {

    private static volatile Vibrator vibrator;

    private VibrateUtils() {}

    public static boolean hasVibrator() {
        try {
            if (vibrator == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    VibratorManager mgr = (VibratorManager)
                            ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    vibrator = mgr != null ? mgr.getDefaultVibrator() : null;
                } else {
                    vibrator = (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
                }
            }
            return vibrator != null && vibrator.hasVibrator();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void disableHapticFeedback(View view) {
        view.setHapticFeedbackEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableHapticFeedback(group.getChildAt(i));
            }
        }
    }

    public static void vibrate() {
        vibrate(200L);
    }

    public static void vibrate(long timeMs) {
        if (NimarkoConfig.disableVibration) {
            return;
        }
        if (vibrator == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager mgr = (VibratorManager)
                        ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = mgr != null ? mgr.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
            }
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(timeMs, VibrationEffect.DEFAULT_AMPLITUDE), (android.media.AudioAttributes) null);
            } else {
                vibrator.vibrate(timeMs);
            }
        } catch (Throwable ignored) {}
    }

    public static void makeClickVibration() {
        if (NimarkoConfig.disableVibration) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Vibrator v = AndroidUtilities.getVibrator();
                if (v == null) return;
                v.cancel();
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            }
        } catch (Throwable ignored) {}
    }

    public static void vibrateForChatMode(int mode) {
        switch (mode) {
            case NimarkoConfig.VIBRATE_CLICK:
                makeClickVibration();
                break;
            case NimarkoConfig.VIBRATE_WAVE:
                makeWaveVibration();
                break;
            case NimarkoConfig.VIBRATE_KEYBOARD:
                makeClickVibration();
                break;
            case NimarkoConfig.VIBRATE_LONG:
                vibrate();
                break;
            case NimarkoConfig.VIBRATE_DISABLE:
            default:
                break;
        }
    }

    public static void makeWaveVibration() {
        if (NimarkoConfig.disableVibration) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Vibrator v = AndroidUtilities.getVibrator();
                if (v == null) return;
                v.cancel();
                v.vibrate(VibrationEffect.createWaveform(
                        new long[]{75, 10, 5, 10},
                        new int[]{5, 20, 90, 20},
                        -1
                ));
            }
        } catch (Throwable ignored) {}
    }
}
