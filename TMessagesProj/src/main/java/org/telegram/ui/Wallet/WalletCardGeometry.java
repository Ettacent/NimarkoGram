/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Wallet;

import android.graphics.Path;

final class WalletCardGeometry {
    static final float HALF_WIDTH = 1.0f;
    static final float TOP = 0.618561f;
    static final float BOTTOM = -0.593561f;
    static final float HALF_THICKNESS = 0.0125f;
    private static final float CORNER_RADIUS = 0.15f;

    static float radiusX(float width) {
        return width * CORNER_RADIUS / (2.0f * HALF_WIDTH);
    }

    static float radiusY(float height) {
        return height * CORNER_RADIUS / (TOP - BOTTOM);
    }

    static void setFacePath(Path path, float width, float height) {
        path.rewind();
        path.addRoundRect(0, 0, width, height, radiusX(width), radiusY(height), Path.Direction.CW);
    }
}
