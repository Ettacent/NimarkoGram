/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.os.SystemClock;

import java.nio.charset.StandardCharsets;

final class WidgetGate {
    static final long PRESS_MS = 10_000;
    static final long BACKGROUND_MS = 1_000;
    static final int HAPTICS_PER_SECOND = 10;
    static final int MAX_LINK = 256, MAX_COPY_TEXT = 256, MAX_REQUEST_DATA = 64, MAX_STORAGE_COMMIT = 2 * 12 * 4096 + 64,
            MAX_WALLET_CALL = 256 * 1024;
    private static final String[] TELEGRAM_HOSTS = {"t.me", "telegram.me", "telegram.dog"};

    private final boolean canQuery, backgroundQuery, wallet;
    private int presses, usedPress;
    private long lastPress = Long.MIN_VALUE / 2, lastBackground = Long.MIN_VALUE / 2, grantAt = Long.MIN_VALUE / 2;
    private boolean requestInFlight, granting;
    private final long[] haptics = new long[HAPTICS_PER_SECOND];
    private int nextHaptic;

    WidgetGate(org.json.JSONObject context) {
        boolean preview = context != null && context.optBoolean("preview");
        canQuery = context != null && !preview && context.optBoolean("canQuery");
        backgroundQuery = canQuery && context.optBoolean("backgroundQuery");
        wallet = canQuery && context.has("wallet") && !context.isNull("wallet");
    }

    synchronized void press() {
        presses++;
        lastPress = SystemClock.elapsedRealtime();
    }

    private boolean pressAvailable(long now) {
        return usedPress < presses && now - lastPress <= PRESS_MS;
    }

    synchronized boolean privileged(boolean visible) {
        long now = SystemClock.elapsedRealtime();
        if (!visible || !pressAvailable(now)) return false;
        usedPress = presses;
        return true;
    }

    synchronized boolean request(String data, boolean withWallet, boolean visible) {
        if (!canQuery || requestInFlight || data == null || data.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_DATA
                || withWallet && !wallet) return false;
        long now = SystemClock.elapsedRealtime();
        if (visible && pressAvailable(now)) {
            usedPress = presses;
            granting = withWallet;
        } else {
            if (!backgroundQuery || withWallet || now - lastBackground < BACKGROUND_MS) return false;
            lastBackground = now;
        }
        requestInFlight = true;
        return true;
    }

    synchronized boolean walletCall(String call, boolean visible) {
        if (!wallet || requestInFlight || call == null || call.length() > MAX_WALLET_CALL || !visible) return false;
        long now = SystemClock.elapsedRealtime();
        if (pressAvailable(now)) usedPress = presses;
        else if (now - grantAt > PRESS_MS) return false;
        requestInFlight = true;
        return true;
    }

    synchronized void answered(boolean answered) {
        if (granting && answered) grantAt = SystemClock.elapsedRealtime();
        requestInFlight = granting = false;
    }

    synchronized boolean haptic(boolean visible) {
        long now = SystemClock.elapsedRealtime();
        if (!visible || now - haptics[nextHaptic] < 1000) return false;
        haptics[nextHaptic] = now;
        nextHaptic = (nextHaptic + 1) % haptics.length;
        return true;
    }

    static boolean copyText(String text) {
        return text != null && text.length() <= MAX_COPY_TEXT;
    }

    static boolean storageCommit(String commit) {
        return commit != null && commit.length() <= MAX_STORAGE_COMMIT;
    }

    static boolean link(String url, boolean telegram) {
        if (url == null || url.length() > MAX_LINK || url.length() < 9 || !url.regionMatches(true, 0, "https://", 0, 8)) return false;
        int end = 8;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
        String authority = url.substring(8, end);
        if (authority.indexOf('@') >= 0) return false;
        int colon = authority.indexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        if (host.isEmpty() || host.length() > 253) return false;
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') return false;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-')) return false;
            }
        }
        boolean telegramHost = false;
        for (String t : TELEGRAM_HOSTS) telegramHost |= host.equals(t) || host.endsWith("." + t);
        return telegramHost == telegram;
    }
}
