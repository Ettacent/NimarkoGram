/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

final class WidgetClientNative {

    static native void filterPixels(
            android.graphics.Bitmap bitmap, int[] kind, float[] amount, float[] x, float[] y, int[] color, float scale);

    static native long arbiterCreate();

    static native void arbiterDestroy(long arbiter);

    static native int arbiterDown(
            long arbiter, double t, double x, double y, boolean claimX, boolean claimY, boolean hold, boolean mouse, int button);

    static native int arbiterMove(long arbiter, double t, double x, double y);

    static native int arbiterTick(long arbiter, double t);

    static native int arbiterUp(long arbiter, double t, double x, double y);

    static native void arbiterCancel(long arbiter);

    static native double arbiterHoldDeadline(long arbiter);

    private WidgetClientNative() {}
}
