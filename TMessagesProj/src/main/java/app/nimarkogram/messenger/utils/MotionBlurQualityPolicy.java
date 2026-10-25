/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

public final class MotionBlurQualityPolicy {
    private MotionBlurQualityPolicy() {
    }

    public static float refreshRate(float rate) {
        return Float.isFinite(rate) && rate > 0f ? Math.max(60f, rate) : 120f;
    }

    public static float maximumSpan(int samples) {
        return 1.5f * (normalizeSamples(samples) - 1);
    }

    public static int normalizeSamples(int samples) {
        return samples >= 17 ? 17 : samples >= 9 ? 9 : 5;
    }

    public static double workBudget(double pixelBudget, boolean high, float rate) {
        return pixelBudget * Math.max(5d, (high ? 12d : 9d) * 60d / refreshRate(rate));
    }

    public static int areaLimit(double area, double rootArea, boolean high, float rate, int limit) {
        double work = rootArea * (high ? 9d : 6d) * 60d / refreshRate(rate);
        return Math.min(normalizeSamples(limit), Math.max(5, fittingSamples(area, work, 17)));
    }

    public static int fittingSamples(double area, double work, int limit) {
        if (!(area > 0d) || !Double.isFinite(area) || !(work >= area * 5d)) return 0;
        if (limit >= 17 && area * 17d <= work) return 17;
        return limit >= 9 && area * 9d <= work ? 9 : 5;
    }

    public static void allocate(double[] areas, int[] limits, int[] order, int count,
            double rootArea, double pixelBudget, boolean high, float rate) {
        double remaining = workBudget(pixelBudget, high, rate);
        for (int i = 0; i < count; i++) {
            limits[i] = areaLimit(areas[i], rootArea, high, rate, limits[i]);
            remaining -= areas[i] * 5d;
            int j = i;
            while (j > 0 && areas[order[j - 1]] > areas[i]) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = i;
        }
        for (int i = 0; i < count; i++) {
            int index = order[i];
            int samples = fittingSamples(areas[index], remaining + areas[index] * 5d, limits[index]);
            limits[index] = Math.max(5, samples);
            remaining -= areas[index] * (limits[index] - 5d);
        }
    }
}
