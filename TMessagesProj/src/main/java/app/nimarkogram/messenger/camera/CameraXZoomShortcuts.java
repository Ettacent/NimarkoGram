/* Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.camera;

import java.util.Arrays;

public final class CameraXZoomShortcuts {
    private CameraXZoomShortcuts() {}

    public static float opticalScale(float[] focalLengths, float width, float height) {
        if (focalLengths == null || focalLengths.length != 1
                || !Float.isFinite(focalLengths[0]) || focalLengths[0] <= 0f
                || !Float.isFinite(width) || !Float.isFinite(height)
                || width <= 0f || height <= 0f) return Float.NaN;
        double scale = focalLengths[0] / Math.hypot(width, height);
        return scale > 0 && scale <= Float.MAX_VALUE ? (float) scale : Float.NaN;
    }

    public static float[] fromOpticalScales(float reference, float[] scales) {
        if (!Float.isFinite(reference) || reference <= 0f || scales == null) return new float[0];
        float[] ratios = new float[scales.length];
        int count = 0;
        boolean anchored = false;
        for (float scale : scales) {
            float ratio = scale / reference;
            if (!Float.isFinite(ratio) || ratio <= 0f) continue;
            ratios[count++] = ratio;
            if (Math.abs(ratio - 1f) <= .05f) anchored = true;
        }
        return anchored ? Arrays.copyOf(ratios, count) : new float[0];
    }

    private static long label(float value) {
        return Math.round((double) value * 10);
    }

    private static float canonicalRatio(float value, float min, float max) {
        float nominal = Math.round(value);
        if (nominal >= 2f && nominal >= min && nominal <= max
                && Math.abs(value - nominal) / nominal <= .050001f) return nominal;
        return value;
    }

    public static float[] select(float min, float max, float[] candidates) {
        if (!Float.isFinite(min) || !Float.isFinite(max) || min <= 0f || max < min) {
            return new float[0];
        }
        float[] values = new float[(candidates == null ? 0 : candidates.length) + 1];
        int count = 0;
        if (min <= 1f && max >= 1f) values[count++] = 1f;
        if (candidates != null) {
            for (float value : candidates) {
                if (Float.isFinite(value) && value >= min && value <= max) {
                    value = canonicalRatio(value, min, max);
                    values[count++] = label(value) == 10 && min <= 1f && max >= 1f
                            ? 1f : value;
                }
            }
        }
        Arrays.sort(values, 0, count);
        int distinct = 0;
        for (int i = 0; i < count; i++) {
            if (distinct == 0 || label(values[i]) != label(values[distinct - 1])) {
                values[distinct++] = values[i];
            }
        }
        return Arrays.copyOf(values, distinct);
    }
}
