/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.graphics.Bitmap;

interface IWidgetEngineCallback {
    float[] defineFont(int id, in byte[] families, float size, int weight, boolean italic, in byte[] features);
    float textWidth(int id, in byte[] text);
    void deleteFont(int id);
    void defineImage(int id, in Bitmap image);

    void part(in byte[] value);

    void record(in byte[] list, in byte[] composition, double clock, int width, int height, float density,
            float layoutMs, int passes, int builds);
    void recordLayer(int index, int version, in byte[] list);
    void selectableText(in byte[] runs);
    void signal(int kind, in byte[] message);
    void canvasRun(int id, int width, int height, in int[] ops);
    byte[] canvasRead(int id, int x, int y, int width, int height);
    void canvasRelease(int id);
}
