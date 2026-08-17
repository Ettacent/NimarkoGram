package app.nimarkogram.messenger.infocards;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class InfoCardsConfig {

    private static final String PREFS_NAME = "nm_pillstack";

    private static volatile SharedPreferences cached;

    public static SharedPreferences prefs() {
        SharedPreferences p = cached;
        if (p == null) {
            synchronized (InfoCardsConfig.class) {
                p = cached;
                if (p == null) {
                    p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    cached = p;
                }
            }
        }
        return p;
    }

    public static boolean isEnabled() {
        
        return prefs().getBoolean("enabled", false);
    }

    public static void setEnabled(boolean v) {
        prefs().edit().putBoolean("enabled", v).apply();
        if (v) {
            
            InfoCardRates.prefetch();
        }
        notifyLayoutChanged();
    }

    private static List<Integer> defaultActive() {
        ArrayList<Integer> out = new ArrayList<>();
        for (InfoCardRegistry.CardInfo info : InfoCardRegistry.all()) out.add(info.id);
        return out;
    }

    public static List<Integer> getActiveCards() {
        return reconcileLayout(null, null, false)[0];
    }

    public static List<Integer> getHiddenCards() {
        return reconcileLayout(null, null, false)[1];
    }

    public static void setLayout(List<Integer> active, List<Integer> hidden) {
        reconcileLayout(active, hidden, true);
        notifyLayoutChanged();
    }

    @SuppressWarnings("unchecked")
    private static synchronized List<Integer>[] reconcileLayout(List<Integer> requestedActive,
                                                                  List<Integer> requestedHidden,
                                                                  boolean forcePersist) {
        List<Integer> known = defaultActive();
        
        boolean configured = requestedActive != null
                || prefs().getBoolean("layoutCustomized", false)
                || prefs().contains("activePills");
        List<Integer> rawActive = requestedActive != null
                ? requestedActive : parseList(prefs().getString("activePills", null));
        List<Integer> rawHidden = requestedHidden != null
                ? requestedHidden : parseList(prefs().getString("hiddenPills", ""));
        LinkedHashSet<Integer> active = new LinkedHashSet<>();
        LinkedHashSet<Integer> hidden = new LinkedHashSet<>();
        if (!configured) {
            active.addAll(known);
        } else {
            for (Integer id : rawActive) if (known.contains(id)) active.add(id);
            for (Integer id : rawHidden) if (known.contains(id) && !active.contains(id)) hidden.add(id);
            for (Integer id : known) if (!active.contains(id) && !hidden.contains(id)) hidden.add(id);
            if (active.isEmpty() && !known.isEmpty()) {
                Integer first = known.get(0);
                hidden.remove(first);
                active.add(first);
            }
        }
        ArrayList<Integer> outActive = new ArrayList<>(active);
        ArrayList<Integer> outHidden = new ArrayList<>(hidden);
        String activeString = serializeList(outActive);
        String hiddenString = serializeList(outHidden);
        if (configured && (forcePersist || !activeString.equals(prefs().getString("activePills", null))
                || !hiddenString.equals(prefs().getString("hiddenPills", "")))) {
            prefs().edit().putString("activePills", activeString)
                    .putString("hiddenPills", hiddenString)
                    .putBoolean("layoutCustomized", true).apply();
        }
        return new List[]{outActive, outHidden};
    }

    public static boolean isCardActive(int id) {
        return getActiveCards().contains(id);
    }

    public static String getTargetCurrency(int pillId) {
        return prefs().getString("ccy_" + pillId, "AUTO");
    }

    public static void setTargetCurrency(int pillId, String ccy) {
        prefs().edit().putString("ccy_" + pillId, ccy).apply();
        notifySettingsChanged(pillId);
    }

    public static boolean isInfiniteScrolling() {
        return prefs().getBoolean("infiniteScrolling", true);
    }

    public static void setInfiniteScrolling(boolean v) {
        prefs().edit().putBoolean("infiniteScrolling", v).apply();
    }

    public static boolean isAutoScroll() {
        return prefs().getBoolean("autoScroll", false);
    }

    public static void setAutoScroll(boolean v) {
        prefs().edit().putBoolean("autoScroll", v).apply();
    }

    public static final int COLOR_MODE_CUSTOM = 0;
     
    public static final int COLOR_MODE_THEME = 1;

    public static int getColorMode() {
        int mode = prefs().getInt("colorMode", COLOR_MODE_CUSTOM);
        return mode == COLOR_MODE_THEME ? COLOR_MODE_THEME : COLOR_MODE_CUSTOM;
    }

    public static void setColorMode(int mode) {
        prefs().edit().putInt("colorMode", mode == COLOR_MODE_THEME ? COLOR_MODE_THEME : COLOR_MODE_CUSTOM).apply();
        
        notifyColorModeChanged();
    }

    public static int getLastActiveCardId() {
        return prefs().getInt("lastActivePillId", -1);
    }

    public static void setLastActiveCardId(int id) {
        prefs().edit().putInt("lastActivePillId", id).apply();
    }

    public static boolean useCurrentLocation() {
        return prefs().getBoolean("weatherUseCurrentLocation", true);
    }

    public static void setUseCurrentLocation(boolean v) {
        prefs().edit().putBoolean("weatherUseCurrentLocation", v).apply();
        notifySettingsChanged(InfoCardType.WEATHER.id);
    }

    public static String getCustomWeatherLocation() {
        return prefs().getString("weatherCustomLocation", null);
    }

    public static String getCustomWeatherAddress() {
        return prefs().getString("weatherCustomAddress", null);
    }

    public static void setCustomWeather(String locationJson, String address) {
        prefs().edit()
                .putString("weatherCustomLocation", locationJson)
                .putString("weatherCustomAddress", address)
                .apply();
        notifySettingsChanged(InfoCardType.WEATHER.id);
    }

    private static List<Integer> parseList(String s) {
        ArrayList<Integer> out = new ArrayList<>();
        if (s == null) return out;
        s = s.replace("[", "").replace("]", "").trim();
        if (s.isEmpty()) return out;
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                seen.add(Integer.parseInt(part));
            } catch (NumberFormatException ignore) {
            }
        }
        out.addAll(seen);
        return out;
    }

    private static String serializeList(List<Integer> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    static void notifyLayoutChanged() {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.infoCardsLayoutChanged));
    }

    static void notifyColorModeChanged() {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.infoCardsColorModeChanged));
    }

    static void notifySettingsChanged(int... pillIds) {
        final Object[] args = new Object[pillIds.length];
        for (int i = 0; i < pillIds.length; i++) args[i] = pillIds[i];
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.infoCardsSettingsChanged, args));
    }

    private InfoCardsConfig() {}
}
