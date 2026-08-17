package app.nimarkogram.messenger.wsbypass.voip;

import android.content.SharedPreferences;

import java.net.InetAddress;

import app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig;

public final class VoipBypassConfig {

    private static final Object RELAY_STATE_LOCK = new Object();
    private static long relayStateGeneration;

    @FunctionalInterface
    public interface RelayStateMutation {
        boolean mutate();
    }

    public static final class RelayState {
        public final long generation;
        public final boolean required;

        private RelayState(long generation, boolean required) {
            this.generation = generation;
            this.required = required;
        }
    }

    public static final String RELAY_HOST = "calls.nimarko.org";
    public static final String ASIA_RELAY_HOST = configuredHost(
            org.telegram.messenger.BuildConfig.NIMARKO_ASIA_RELAY_HOST, RELAY_HOST);
    public static final int RELAY_CONTROL_PORT = 8765;

    public static String relayHost() {
        return relayHost(org.telegram.messenger.UserConfig.selectedAccount);
    }

    public static String relayHost(int account) {
        return app.nimarkogram.messenger.wsbypass.RelayRegion.isAsia(account) ? ASIA_RELAY_HOST : RELAY_HOST;
    }

    public static String[] relayHosts(int account) {
        if (app.nimarkogram.messenger.wsbypass.RelayRegion.isAsia(account)) {
            return new String[]{ASIA_RELAY_HOST, RELAY_HOST};
        }
        return new String[]{RELAY_HOST, ASIA_RELAY_HOST};
    }

    public static final String VOIP_AUTH_URL = "https://calls.nimarko.org";

    private static String configuredHost(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static final String KEY_VOIP_BYPASS_ENABLED = "voip_bypass_enabled";
    private static final String KEY_VOIP_BYPASS_CONFIGURED = "voip_bypass_configured";

    public static boolean isVoipBypassEnabled() {
        synchronized (RELAY_STATE_LOCK) {
            return isVoipBypassEnabledLocked();
        }
    }

    public static boolean isDataBypassEnabled() {
        synchronized (RELAY_STATE_LOCK) {
            return NimarkoWsBypassConfig.enabled;
        }
    }

    public static boolean isSuspendOnVpnEnabled() {
        synchronized (RELAY_STATE_LOCK) {
            return NimarkoWsBypassConfig.suspendOnVpn;
        }
    }

    public static boolean isDataBypassRequired() {
        synchronized (RELAY_STATE_LOCK) {
            return dataBypassRequiredLocked(false);
        }
    }

    public static boolean isDataBypassRequiredFresh() {
        synchronized (RELAY_STATE_LOCK) {
            return dataBypassRequiredLocked(true);
        }
    }

    public static boolean isVoipRelayRequiredFresh() {
        synchronized (RELAY_STATE_LOCK) {
            return NimarkoWsBypassConfig.enabled
                    && isVoipBypassEnabledLocked()
                    && !vpnSuspendsRelayLocked(true);
        }
    }

    private static boolean isVoipBypassEnabledLocked() {
        SharedPreferences p = NimarkoWsBypassConfig.prefs();
        if (!p.getBoolean(KEY_VOIP_BYPASS_CONFIGURED, false)) {
            p.edit()
                    .putBoolean(KEY_VOIP_BYPASS_CONFIGURED, true)
                    .putBoolean(KEY_VOIP_BYPASS_ENABLED, true)
                    .apply();
            return true;
        }
        return p.getBoolean(KEY_VOIP_BYPASS_ENABLED, true);
    }

    public static void setVoipBypassEnabled(boolean v) {
        mutateRelayState(() -> {
            SharedPreferences preferences = NimarkoWsBypassConfig.prefs();
            boolean changed = !preferences.getBoolean(KEY_VOIP_BYPASS_CONFIGURED, false)
                    || preferences.getBoolean(KEY_VOIP_BYPASS_ENABLED, true) != v;
            preferences.edit()
                    .putBoolean(KEY_VOIP_BYPASS_CONFIGURED, true)
                    .putBoolean(KEY_VOIP_BYPASS_ENABLED, v)
                    .apply();
            return changed;
        });
        if (!v) VoipRelayAuth.cancelPendingAuth();
    }

    public static boolean mutateRelayState(RelayStateMutation mutation) {
        synchronized (RELAY_STATE_LOCK) {
            boolean changed = mutation.mutate();
            if (changed) relayStateGeneration++;
            return changed;
        }
    }

    public static RelayState captureRelayState() {
        synchronized (RELAY_STATE_LOCK) {
            boolean required = relayRequiredLocked();
            return new RelayState(relayStateGeneration, required);
        }
    }

    public static boolean publishIfRelayStateCurrent(RelayState expected, Runnable publication) {
        synchronized (RELAY_STATE_LOCK) {
            boolean required = relayRequiredLocked();
            if (expected == null || expected.generation != relayStateGeneration
                    || expected.required != required) {
                return false;
            }
            publication.run();
            return true;
        }
    }

    private static boolean relayRequiredLocked() {
        return NimarkoWsBypassConfig.enabled
                && isVoipBypassEnabledLocked()
                && !vpnSuspendsRelayLocked(false);
    }

    private static boolean dataBypassRequiredLocked(boolean freshVpnState) {
        return NimarkoWsBypassConfig.enabled && !vpnSuspendsRelayLocked(freshVpnState);
    }

    private static boolean vpnSuspendsRelayLocked(boolean freshVpnState) {
        if (!NimarkoWsBypassConfig.suspendOnVpn) return false;
        return freshVpnState
                ? app.nimarkogram.messenger.wsbypass.NimarkoVpnDetector.isVpnActiveFresh()
                : app.nimarkogram.messenger.wsbypass.NimarkoVpnDetector.isVpnActive();
    }

    private static final String[] TELEGRAM_REFLECTOR_CIDRS = {
            "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22", "91.108.16.0/22", "91.108.20.0/22",
            "91.108.56.0/22", "91.108.58.0/23", "91.105.192.0/23", "95.161.64.0/20",
            "149.154.160.0/20", "185.76.151.0/24",
    };
    private static final long[][] TG_CIDRS = parseCidrs(TELEGRAM_REFLECTOR_CIDRS);

    private static long[][] parseCidrs(String[] cidrs) {
        long[][] out = new long[cidrs.length][2];
        for (int i = 0; i < cidrs.length; i++) {
            int slash = cidrs[i].indexOf('/');
            long base = ipv4ToLong(cidrs[i].substring(0, slash));
            int prefix = Integer.parseInt(cidrs[i].substring(slash + 1));
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            out[i][0] = base & mask;   
            out[i][1] = mask;
        }
        return out;
    }

    private static long ipv4ToLong(String ip) {
        try {
            String[] p = ip.split("\\.");
            if (p.length != 4) return -1;
            long v = 0;
            for (String s : p) {
                int b = Integer.parseInt(s);
                if (b < 0 || b > 255) return -1;
                v = (v << 8) | b;
            }
            return v & 0xFFFFFFFFL;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isTelegramReflectorIp(String ip) {
        if (ip == null) return false;
        long v = ipv4ToLong(ip);
        if (v < 0) return false;
        for (long[] c : TG_CIDRS) {
            if ((v & c[1]) == c[0]) return true;
        }
        return false;
    }

    public static boolean isIpv6Literal(String ip) {
        if (ip == null) return false;
        String value = stripIpv6Decorations(ip);
        return value.indexOf(':') >= 0;
    }

    public static String relayProtocolTarget(String ip) {
        if (ip == null) return null;
        String value = stripIpv6Decorations(ip);
        if (isTelegramReflectorIp(value)) return value;
        if (value.indexOf(':') < 0) return null;
        try {
            byte[] bytes = InetAddress.getByName(value).getAddress();
            if (bytes.length == 4) {
                String mapped = ipv4BytesToString(bytes, 0);
                return isTelegramReflectorIp(mapped) ? mapped : null;
            }
            if (bytes.length == 16) {
                boolean mapped = true;
                for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
                mapped &= (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
                if (mapped) {
                    String ipv4 = ipv4BytesToString(bytes, 12);
                    return isTelegramReflectorIp(ipv4) ? ipv4 : null;
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private static String stripIpv6Decorations(String ip) {
        String value = ip.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        return zone >= 0 ? value.substring(0, zone) : value;
    }

    private static String ipv4BytesToString(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) + "." + (bytes[offset + 1] & 0xFF) + "."
                + (bytes[offset + 2] & 0xFF) + "." + (bytes[offset + 3] & 0xFF);
    }

    private VoipBypassConfig() {}
}
