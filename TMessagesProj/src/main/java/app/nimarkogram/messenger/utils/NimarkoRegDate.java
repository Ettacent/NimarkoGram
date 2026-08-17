package app.nimarkogram.messenger.utils;

import android.text.format.DateFormat;

import org.telegram.messenger.LocaleController;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

public final class NimarkoRegDate {

    private NimarkoRegDate() {}

    public enum Flag { EXACT, INTERPOLATED, LT, ET }

    private static final long SERVICE_NOTIFICATIONS = 777000L;        
    private static final long GROUP_ANONYMOUS_BOT   = 1087968824L;    
    private static final long CHANNEL_BOT           = 136817688L;     

    private static final long[] ANCHOR_ID = {
            2768409L,    7679610L,    11538514L,   15835244L,   23646077L,
            38015510L,   44634663L,   46145305L,   54845238L,   63263518L,
            101260938L,  111220210L,  116812045L,  124872445L,  133909606L,
            143445125L,  148670295L,  171295414L,  181783990L,  222021233L,
            225034354L,  278941742L,  285253072L,  294851037L,  297621225L,
            328594461L,  337808429L,  352940995L,  369669043L,  400169472L,
            805158066L,  1974255900L, 5795034000L, 6227468000L, 7583599300L,
            7947063900L, 8235679900L
    };
    private static final long[] ANCHOR_TS = {
            1383264000L, 1388448000L, 1391212000L, 1392940000L, 1393459000L,
            1393632000L, 1399334000L, 1400198000L, 1411257000L, 1414454000L,
            1425600000L, 1429574000L, 1437696000L, 1439856000L, 1444176000L,
            1448928000L, 1452211000L, 1457481000L, 1460246000L, 1465344000L,
            1466208000L, 1473465000L, 1476835000L, 1479600000L, 1481846000L,
            1482969000L, 1487707000L, 1487894000L, 1490918000L, 1501459000L,
            1563208000L, 1634000000L, 1662076800L, 1679270400L, 1739664000L,
            1754092800L, 1758758400L
    };

    public static boolean isEstimatable(long id) {
        if (id <= 0) return false; 
        if (id == SERVICE_NOTIFICATIONS || id == GROUP_ANONYMOUS_BOT || id == CHANNEL_BOT) return false;
        return true;
    }

    public static long estimate(long id) {
        if (!isEstimatable(id)) return 0L;

        final int n = ANCHOR_ID.length;

        if (id < ANCHOR_ID[0]) return 0L;

        if (id >= ANCHOR_ID[n - 1]) return ANCHOR_TS[n - 1];

        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ANCHOR_ID[mid] <= id) lo = mid; else hi = mid;
        }

        if (id == ANCHOR_ID[lo]) return ANCHOR_TS[lo];

        long loId = ANCHOR_ID[lo], hiId = ANCHOR_ID[hi];
        long loT  = ANCHOR_TS[lo], hiT  = ANCHOR_TS[hi];

        if (hiT < loT) return loT;

        double frac = (double) (id - loId) / (double) (hiId - loId);
        return loT + Math.round(frac * (hiT - loT));
    }

    public static Flag flagFor(long id) {
        final int n = ANCHOR_ID.length;
        if (id < ANCHOR_ID[0]) return Flag.LT;
        if (id > ANCHOR_ID[n - 1]) return Flag.ET;
        
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ANCHOR_ID[mid] <= id) lo = mid; else hi = mid;
        }
        if (id == ANCHOR_ID[lo] || id == ANCHOR_ID[hi]) return Flag.EXACT;
        return Flag.INTERPOLATED;
    }

    private static String formatMonthYear(long unixSeconds) {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(unixSeconds * 1000L);
        
        CharSequence s = DateFormat.format("LLLL yyyy", c);
        String out = s == null ? "" : s.toString();
        if (out.length() > 0) {
            out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
        }
        return out;
    }

    public static String estimateString(long id) {
        if (!isEstimatable(id)) return null;

        final int n = ANCHOR_ID.length;
        Flag flag = flagFor(id);

        long ts;
        String prefix;
        switch (flag) {
            case LT:
                
                ts = ANCHOR_TS[0];
                prefix = "< ";
                break;
            case ET:
                ts = ANCHOR_TS[n - 1];
                prefix = "> ";
                break;
            case EXACT:
                ts = estimate(id);
                prefix = "";
                break;
            case INTERPOLATED:
            default:
                ts = estimate(id);
                prefix = "~ ";
                break;
        }
        if (ts <= 0L) return null;
        return prefix + formatMonthYear(ts);
    }

    public static String estimateStringOrUnknown(long id) {
        String s = estimateString(id);
        if (s != null) return s;
        return LocaleController.getString(org.telegram.messenger.R.string.NM_RegDateUnknown);
    }
}