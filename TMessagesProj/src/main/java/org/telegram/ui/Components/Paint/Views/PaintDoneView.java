/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.Paint.Views;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import app.nimarkogram.messenger.icons.IconResources;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BoundedPackIconRenderer;
public class PaintDoneView extends View {
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;

    private final BoundedPackIconRenderer packCheck = new BoundedPackIconRenderer(), packAdd = new BoundedPackIconRenderer();
    public PaintDoneView(Context context) {
        super(context);

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2));
    }

    public void setProgress(float progress) {
        this.progress = progress;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (drawPackGlyphs(canvas)) {
            return;
        }
        canvas.drawLine(
            getWidth() / 2f + dp(lerp(-6.7f, -7, progress)),
            getHeight() / 2f + dp(lerp(.71f, 0, progress)),
            getWidth() / 2f + dp(lerp(-2.45f, 7, progress)),
            getHeight() / 2f + dp(lerp(4.79f, 0, progress)),
            paint
        );
        canvas.drawLine(
            getWidth() / 2f + dp(lerp(-2.45f, 0, progress)),
            getHeight() / 2f + dp(lerp(4.79f, 7, progress)),
            getWidth() / 2f + dp(lerp(6.59f, 0, progress)),
            getHeight() / 2f + dp(lerp(-4.27f, -7, progress)),
            paint
        );
    }
    private boolean drawPackGlyphs(Canvas canvas) {
        if (paint.getXfermode() != null || progress < 0f || progress > 1f) return false;
        final IconResources.NavigationIcons batch = IconResources.activeNavigationIcons();
        if (!packCheck.prepare(batch, R.drawable.ic_ab_done)
                || !packAdd.prepare(batch, R.drawable.msg_add)) return false;
        final float x1 = dp(lerp(-6.7f, -7, progress)), y1 = dp(lerp(.71f, 0, progress));
        final float x2 = dp(lerp(-2.45f, 7, progress)), y2 = dp(lerp(4.79f, 0, progress));
        final float x3 = dp(lerp(-2.45f, 0, progress)), y3 = dp(lerp(4.79f, 7, progress));
        final float x4 = dp(lerp(6.59f, 0, progress)), y4 = dp(lerp(-4.27f, -7, progress));
        final float left = Math.min(Math.min(x1, x2), Math.min(x3, x4));
        final float right = Math.max(Math.max(x1, x2), Math.max(x3, x4));
        final float top = Math.min(Math.min(y1, y2), Math.min(y3, y4));
        final float bottom = Math.max(Math.max(y1, y2), Math.max(y3, y4));
        final float cx = getWidth() / 2f + (left + right) / 2f;
        final float cy = getHeight() / 2f + (top + bottom) / 2f;
        final float width = right - left + paint.getStrokeWidth();
        final float height = bottom - top + paint.getStrokeWidth();
        packCheck.draw(canvas, paint, cx, cy, width, height, 1f - progress, false);
        packAdd.draw(canvas, paint, cx, cy, width, height, progress, false);
        return true;
}
}
