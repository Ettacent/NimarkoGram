package app.nimarkogram.messenger.wsbypass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.wsbypass.voip.VoipBypassConfig;

public final class DomainPool {

    private static volatile String installId;

    static String installId() {
        String id = installId;
        if (id != null && !id.isEmpty()) return id;
        synchronized (DomainPool.class) {
            id = installId;
            if (id == null || id.isEmpty()) {
                id = NimarkoConfig.ensureWsInstallId();
                installId = id == null ? "" : id;
            }
            return installId;
        }
    }

    private static final String[] RELAY_HOSTS_NL = {
            "r1.nimarko.org",
            "r2.nimarko.org",
            "r3.nimarko.org",
    };
    private static final String[] RELAY_HOSTS_ASIA = {
            VoipBypassConfig.ASIA_RELAY_HOST,
    };

    private static String[] primaryPool() {
        return RelayRegion.isAsia() ? RELAY_HOSTS_ASIA : RELAY_HOSTS_NL;
    }

    private static String[] primaryPoolForDc(int dc) {
        return dc == 5 ? RELAY_HOSTS_ASIA : RELAY_HOSTS_NL;
    }

    private static String[] fallbackPool() {
        return RelayRegion.isAsia() ? RELAY_HOSTS_NL : RELAY_HOSTS_ASIA;
    }

    private static String[] fallbackPoolForDc(int dc) {
        return dc == 5 ? RELAY_HOSTS_NL : RELAY_HOSTS_ASIA;
    }

    private static int stickyBase(int len) {
        try {
            String id = installId();
            if (id != null && !id.isEmpty()) {
                return Math.floorMod(id.hashCode(), len);
            }
        } catch (Throwable ignore) {}
        return 0;
    }

    public static String relayHost() {
        String[] pool = primaryPool();
        return pool[stickyBase(pool.length)];
    }

    public static List<String> relayHosts() {
        return orderedHosts(primaryPool(), fallbackPool());
    }

    public static List<String> relayHostsForDc(int dc) {
        return orderedHosts(primaryPoolForDc(dc), fallbackPoolForDc(dc));
    }

    public static boolean isAsiaRelayHost(String host) {
        if (host == null) return false;
        for (String relayHost : RELAY_HOSTS_ASIA) {
            if (relayHost.equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    private static List<String> orderedHosts(String[] primary, String[] fallback) {
        int base = stickyBase(primary.length);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; i < primary.length; i++) {
            out.add(primary[(base + i) % primary.length]);
        }
        for (String h : fallback) {
            out.add(h);
        }
        return new ArrayList<>(out);
    }

    public static String relayPathForDc(int dc) {
        return "/apiws?dc=" + dc;
    }

    private static final String WEB_TELEGRAM_DOMAIN = "web.telegram.org";

    private static final Map<Integer, Integer> DC_OVERRIDES;
    static {
        Map<Integer, Integer> m = new HashMap<>();
        m.put(203, 2);
        DC_OVERRIDES = Collections.unmodifiableMap(m);
    }

    public static List<String> wsDomainsForDc(int dc, boolean isMedia) {
        int effective = dc;
        Integer ov = DC_OVERRIDES.get(dc);
        if (ov != null) effective = ov;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (isMedia) {
            out.add("kws" + effective + "-1." + WEB_TELEGRAM_DOMAIN);
            out.add("kws" + effective + "." + WEB_TELEGRAM_DOMAIN);
        } else {
            out.add("kws" + effective + "." + WEB_TELEGRAM_DOMAIN);
            out.add("kws" + effective + "-1." + WEB_TELEGRAM_DOMAIN);
        }
        return new ArrayList<>(out);
    }
}
