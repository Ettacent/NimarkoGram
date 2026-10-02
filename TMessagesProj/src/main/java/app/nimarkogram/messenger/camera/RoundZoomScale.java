/* Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.camera;

import java.util.ArrayList;

public final class RoundZoomScale {
    private RoundZoomScale() {}

    public static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    public static boolean valid(float min, float max) {
        return Float.isFinite(min) && Float.isFinite(max) && min > 0 && max > min + 0.001f;
    }

    public static float drag(float start, float distanceDp, float min, float max) {
        return clamp((float) (start * Math.pow(2, distanceDp / 80f)), min, max);
    }

    public static float offset(float value, float current) {
        return (float) (Math.log(value / current) / Math.log(2) * 80);
    }

    public static float selection(float value, float[] stops) {
        if (stops.length == 0) return 0;
        value = clamp(value, stops[0], stops[stops.length - 1]);
        for (int i = 1; i < stops.length; i++) {
            if (value < stops[i]) {
                return i - 1 + (float) (Math.log(value / stops[i - 1]) / Math.log(stops[i] / stops[i - 1]));
            }
        }
        return stops.length - 1;
    }

    public static float[] stops(float min, float max) {
        if (!valid(min, max)) return new float[0];
        ArrayList<Float> values = new ArrayList<>();
        values.add(min);
        for (float value : new float[]{1, 2, 5}) {
            if (value > min * 1.06f && value < max / 1.06f) values.add(value);
        }
        values.add(max);
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }
}
