/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

final class DownloadRequestPacer {
    private double intervalMs = 50;
    private long nextRequestAt;

    long remainingDelay(long now) {
        return Math.max(0, nextRequestAt - now);
    }

    void onRequestSent(long now) {
        nextRequestAt = now + (long) Math.ceil(intervalMs);
        intervalMs = Math.max(3, intervalMs * 0.8);
    }
}
