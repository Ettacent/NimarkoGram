/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

final class TextZoomGeometry {
    static final float MIN_SCALE = .8f;
    static final float MAX_SCALE = 2f;

    static float scale(float value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    static float horizontal(float translation, float width, float scale) {
        float remaining = width * (1f - scale);
        return remaining >= 0 ? remaining / 2f : Math.max(remaining, Math.min(0f, translation));
    }

    static float focalScroll(float oldFocus, float newFocus, float oldScale, float newScale) {
        return oldFocus / oldScale - newFocus / newScale;
    }

    static float maximumOffset(boolean atEnd, float lastBottom, float bottomPadding,
                               float physicalHeight, float scale) {
        return atEnd ? Math.max(0f, lastBottom + bottomPadding - physicalHeight / scale) : 1f;
    }

    private TextZoomGeometry() {}
}
