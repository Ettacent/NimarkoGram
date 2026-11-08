/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.os.SystemClock;

import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

public class DownloadSpeedEstimator {

    private static final long MAX_AGE_MS = 60_000;

    private static double bitrate;
    private static long updatedAt;

    public static synchronized void onSample(long bits, long durationMs) {
        if (bits <= 0 || durationMs <= 0) {
            return;
        }
        double sample = bits * 1000.0 / durationMs;
        long now = SystemClock.elapsedRealtime();
        bitrate = updatedAt == 0 || now - updatedAt > MAX_AGE_MS ? sample : bitrate * 0.7 + sample * 0.3;
        updatedAt = now;
    }

    public static boolean isFast() {
        return getBitrate() >= 3_000_000;
    }

    public static synchronized long getBitrate() {
        if (updatedAt != 0 && SystemClock.elapsedRealtime() - updatedAt <= MAX_AGE_MS) {
            return (long) bitrate;
        }
        return DefaultBandwidthMeter.getSingletonInstance(ApplicationLoader.applicationContext).getBitrateEstimate();
    }
}
