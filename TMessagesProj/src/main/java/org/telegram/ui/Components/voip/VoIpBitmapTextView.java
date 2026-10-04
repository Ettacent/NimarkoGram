/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.voip;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
/**
 * Fixed ANR on Samsung after one and a half minutes.
 * Anr occurs due to drawing long text.
 */
@SuppressLint("ViewConstructor")
public class VoIpBitmapTextView extends View {

    private final TextPaint textPaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float textWidth;
    private Typeface measuredTypeface;
    private final String text;
    private Bitmap bitmap;
    private boolean bitmapDirty = true;
    private int renderGeneration;

    public VoIpBitmapTextView(Context context, String text) {
        super(context);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(AndroidUtilities.bold());
        textWidth = textPaint.measureText(text);
        measuredTypeface = textPaint.getTypeface();
        this.text = text;
    }
    private boolean refreshTextMetrics() {
        int textSize = dp(13);
        if (measuredTypeface == textPaint.getTypeface() && textPaint.getTextSize() == textSize) {
            return false;
        }
        textPaint.setTextSize(textSize);
        measuredTypeface = textPaint.getTypeface();
        textWidth = textPaint.measureText(text);
        invalidateTextBitmap();
        return true;
    }
    private void invalidateTextBitmap() {
        renderGeneration++;
        bitmap = null;
        bitmapDirty = true;
    }
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshTextMetrics();
        invalidateTextBitmap();
        requestLayout();
        invalidate();
    }
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshTextMetrics();
        requestLayout();
        invalidate();
    }
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        invalidateTextBitmap();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        refreshTextMetrics();
        super.onMeasure(
                MeasureSpec.makeMeasureSpec((int) textWidth + getPaddingLeft() + getPaddingRight(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY)
        );
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        refreshTextMetrics();
        if (!changed && !bitmapDirty) {
            return;
        }
        invalidateTextBitmap();
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        bitmapDirty = false;
        final int generation = renderGeneration;
        final TextPaint renderPaint = new TextPaint(textPaint);
            Utilities.globalQueue.postRunnable(() -> {
            Bitmap renderedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(renderedBitmap);
            int xPos = width / 2;
            int yPos = (int) ((height / 2) - ((renderPaint.descent() + renderPaint.ascent()) / 2));
            canvas.drawText(text, xPos, yPos, renderPaint);
            AndroidUtilities.runOnUIThread(() -> {
                if (refreshTextMetrics()) {
                    requestLayout();
                }
                if (generation != renderGeneration) {
                    renderedBitmap.recycle();
                    return;
                }
                bitmap = renderedBitmap;
                invalidate();
            });
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (refreshTextMetrics()) {
            requestLayout();
        }
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0, 0, paint);
        }
    }
}
