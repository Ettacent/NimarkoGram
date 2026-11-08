/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

import java.util.Arrays;

final class PdfReadingColors {
    private static final float[] LUMA = {.2126f, .7152f, .0722f};
    final float[] matrix = new float[20];
    int page = 0xffffffff;
    boolean filtered;
    boolean dark;
    private int lastPaper, lastInk;
    private boolean initialized;

    boolean update(int paper, int ink, boolean dark) {
        paper |= 0xff000000;
        ink |= 0xff000000;
        if (initialized && lastPaper == paper && lastInk == ink && this.dark == dark) return false;
        initialized = true;
        lastPaper = paper;
        lastInk = ink;
        this.dark = dark;
        filtered = false;
        Arrays.fill(matrix, 0f);
        if (!dark) {
            matrix[0] = matrix[6] = matrix[12] = matrix[18] = 1;
            page = 0xffffffff;
            return true;
        }
        float chroma = .16f;
        for (int row = 0; row < 3; row++) {
            int shift = 16 - row * 8;
            int paperChannel = Math.min(64, (paper >> shift) & 255);
            int inkChannel = Math.max(208, Math.min(240, (ink >> shift) & 255));
            float range = (inkChannel - paperChannel) / 255f;
            chroma = Math.min(chroma, ((255 - inkChannel) / 255f + range * LUMA[row]) / (1 - LUMA[row]));
            chroma = Math.min(chroma, inkChannel / (255f * (1 - LUMA[row])) - range);
        }
        int mappedPage = 0xff000000;
        for (int row = 0; row < 3; row++) {
            int shift = 16 - row * 8;
            int paperChannel = Math.min(64, (paper >> shift) & 255);
            int inkChannel = Math.max(208, Math.min(240, (ink >> shift) & 255));
            for (int column = 0; column < 3; column++) {
                float identity = row == column ? 1f : 0f;
                float night = LUMA[column] * (paperChannel - inkChannel) / 255f
                        + chroma * (identity - LUMA[column]);
                matrix[row * 5 + column] = night;
            }
            matrix[row * 5 + 4] = inkChannel;
            mappedPage |= paperChannel << shift;
        }
        matrix[18] = 1f;
        page = mappedPage;
        return true;
    }

}
