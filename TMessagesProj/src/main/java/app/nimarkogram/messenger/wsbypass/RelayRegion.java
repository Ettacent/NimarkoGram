package app.nimarkogram.messenger.wsbypass;

import android.content.Context;
import android.telephony.TelephonyManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

public final class RelayRegion {

    private static final Set<String> ASIA_COUNTRIES = new HashSet<>(Arrays.asList(
            "CN", "HK", "MO", "TW", "JP", "KR", "KP", "MN",
            "SG", "MY", "ID", "PH", "VN", "TH", "KH", "LA", "MM", "BN", "TL",
            "IN", "BD", "LK", "NP", "BT", "MV", "PK", "AF"
    ));

    private static final int DC_SINGAPORE = 5;

    public static boolean isAsia() {
        return isAsia(UserConfig.selectedAccount);
    }

    public static boolean isAsia(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            account = UserConfig.selectedAccount;
        }
        int dc = 0;
        try {
            dc = ConnectionsManager.getInstance(account).getCurrentDatacenterId();
        } catch (Throwable ignored) {}

        boolean activated = false;
        try {
            activated = UserConfig.getInstance(account).isClientActivated();
        } catch (Throwable ignored) {}

        boolean knownDc = dc > 0 && dc != ConnectionsManager.DEFAULT_DATACENTER_ID;
        if (activated && knownDc) {
            return dc == DC_SINGAPORE;
        }
        
        if (!activated && dc == DC_SINGAPORE) {
            return true;
        }

        String cc = country();
        return cc != null && ASIA_COUNTRIES.contains(cc);
    }

    public static void invalidate() {
    }

    private static String country() {
        try {
            TelephonyManager tm = (TelephonyManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String c = tm.getSimCountryIso();
                if (c == null || c.isEmpty()) {
                    c = tm.getNetworkCountryIso();
                }
                if (c != null && !c.isEmpty()) {
                    return c.toUpperCase(Locale.US);
                }
            }
        } catch (Throwable ignored) {}
        try {
            String c = Locale.getDefault().getCountry();
            if (c != null && !c.isEmpty()) {
                return c.toUpperCase(Locale.US);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private RelayRegion() {}
}
