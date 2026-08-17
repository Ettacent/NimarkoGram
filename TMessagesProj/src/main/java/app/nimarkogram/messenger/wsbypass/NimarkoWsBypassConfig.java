package app.nimarkogram.messenger.wsbypass;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.security.SecureRandom;

public final class NimarkoWsBypassConfig {

    public static final String PREFS_NAME = "nimarko_wsbypass";

    private static volatile SharedPreferences cached;

    public static SharedPreferences prefs() {
        SharedPreferences p = cached;
        if (p == null) {
            synchronized (NimarkoWsBypassConfig.class) {
                p = cached;
                if (p == null) {
                    p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    cached = p;
                }
            }
        }
        return p;
    }

    public static SharedPreferences.Editor editor() {
        return prefs().edit();
    }

    public static boolean enabled = loadEnabledWithSentinel();

    private static boolean loadEnabledWithSentinel() {
        SharedPreferences p = prefs();
        if (!p.getBoolean("configured", false)) {
            p.edit().putBoolean("configured", true).putBoolean("enabled", true).apply();
            return true;
        }
        return p.getBoolean("enabled", true);
    }

    public static void setEnabled(boolean v) {
        app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig.mutateRelayState(() -> {
            if (enabled == v) return false;
            enabled = v;
            return true;
        });
        editor().putBoolean("enabled", v).apply();
    }

    public static int localPort = prefs().getInt("local_port", 0);

    public static void setLocalPort(int v) {
        localPort = v;
        editor().putInt("local_port", v).apply();
    }

    public static boolean suspendOnVpn = prefs().getBoolean("suspend_on_vpn", true);

    public static void setSuspendOnVpn(boolean v) {
        app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig.mutateRelayState(() -> {
            if (suspendOnVpn == v) return false;
            suspendOnVpn = v;
            return true;
        });
        editor().putBoolean("suspend_on_vpn", v).apply();
    }

    public static String mtprotoSecret = loadOrGenerateSecret();

    private static String loadOrGenerateSecret() {
        SharedPreferences p = prefs();
        String s = p.getString("mtproto_secret", "");
        if (MtprotoHandshake.validSecretHex(s) != null) {
            return MtprotoHandshake.validSecretHex(s);
        }
        String gen = MtprotoHandshake.generateSecretHex();
        p.edit().putString("mtproto_secret", gen).apply();
        return gen;
    }

    public static void setMtprotoSecret(String v) {
        String norm = MtprotoHandshake.validSecretHex(v);
        if (norm == null) {
            
            return;
        }
        mtprotoSecret = norm;
        editor().putString("mtproto_secret", norm).apply();
    }

    private NimarkoWsBypassConfig() {}
}
