/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.ListView;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

import org.telegram.ui.Components.RecyclerListView;

public class RecyclerListViewWithOverlayDraw extends RecyclerListView {

    boolean invalidated;
    private final RectF overlayBounds = new RectF();
    public RecyclerListViewWithOverlayDraw(Context context) {
        super(context);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        invalidated = false;
        for (int i = 0; i < getChildCount(); i++) {
            drawOverlayChild(canvas, getChildAt(i));
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected void captureChildren(Canvas canvas, RectF position, long drawingTime) {
        super.captureChildren(canvas, position, drawingTime);
        for (int i = 0, N = getChildCount(); i < N; i++) {
            final View child = getChildAt(i);
            if (!(child instanceof OverlayView)) {
                continue;
            }

            overlayBounds.set(0, 0, child.getWidth(), child.getHeight());
            child.getMatrix().mapRect(overlayBounds);
            overlayBounds.offset(child.getLeft(), child.getTop());
            if (!RectF.intersects(position, overlayBounds)) {
                continue;
            }

            drawOverlayChild(canvas, child);
        }
    }

    private void drawOverlayChild(Canvas canvas, View child) {
        if (!(child instanceof OverlayView) || child.getVisibility() != View.VISIBLE
                || child.getWidth() <= 0 || child.getHeight() <= 0) return;
        final int alpha = Math.max(0, Math.min(255, Math.round(child.getAlpha() * 255)));
        if (alpha == 0) return;
        final int save = canvas.save();
        try {
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            canvas.clipRect(0, 0, child.getWidth(), child.getHeight());
            if (alpha != 255) {
                canvas.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), alpha);
            }
            ((OverlayView) child).preDraw(this, canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    @Override
    public void invalidate() {
        if (invalidated) {
            return;
        }
        super.invalidate();
        invalidated = true;
    }

    public interface OverlayView {
        void preDraw(View view, Canvas canvas);
        float getX();
        float getY();
    }
}
