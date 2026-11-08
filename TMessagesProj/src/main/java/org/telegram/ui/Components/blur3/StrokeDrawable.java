/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.blur3;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.GlassRim;
import org.telegram.ui.Components.blur3.drawable.GlassRimRenderer;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import java.util.Arrays;
public class StrokeDrawable extends Drawable {

    private BlurredBackgroundColorProvider colorProvider;
    protected int strokeColorTop, strokeColorBottom;

    private float alpha = 1.0f;
    private final RectF rect = new RectF();
    private int padding;

    private final Paint paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GlassRimRenderer rimRenderer = new GlassRimRenderer();
    private final float[] radii = new float[8];
    private GlassRim rim;

    public void setBackgroundColor(int color) {
        paintFill.setColor(color);
        invalidateSelf();
    }

    public void setPadding(int padding) {
        this.padding = padding;
    }

    public void setColorProvider(BlurredBackgroundColorProvider colorProvider) {
        this.colorProvider = colorProvider;


        updateColors();
    }

    private int themeGeneration = org.telegram.ui.ActionBar.ThemeBind.generation();
    private boolean lastGlare = app.nimarkogram.messenger.AppConfig.glareOnElements;
    public void updateColors() {
        themeGeneration = org.telegram.ui.ActionBar.ThemeBind.generation();
        lastGlare = app.nimarkogram.messenger.AppConfig.glareOnElements;
        if (colorProvider == null) return;

        if (app.nimarkogram.messenger.AppConfig.glareOnElements) {
            strokeColorTop = Theme.multAlpha(colorProvider.getStrokeColorTop(), alpha);
            strokeColorBottom = Theme.multAlpha(colorProvider.getStrokeColorBottom(), alpha);
        } else {
            strokeColorTop = 0;
            strokeColorBottom = 0;
        }

        rim = GlassRim.of(GlassRim.LIGHT_TOP, dpf2(1), strokeColorTop, dpf2(2 / 3f), strokeColorBottom, 0, 0);
    }

    public boolean nonRound;
    public float radius;

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (themeGeneration != org.telegram.ui.ActionBar.ThemeBind.generation()
                || lastGlare != app.nimarkogram.messenger.AppConfig.glareOnElements) {
            try {
                updateColors();
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
            }
        }
        final float cx = getBounds().centerX();
        final float cy = getBounds().centerY();
        float radius = Math.min(getBounds().width(), getBounds().height()) / 2.0f - padding;
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        if (nonRound) {
            rect.set(getBounds());
            radius = this.radius;
        }

        if (Color.alpha(paintFill.getColor()) > 0) {
            canvas.drawCircle(cx, cy, radius, paintFill);
        }
        if (rim != null) {
            Arrays.fill(radii, radius);
            rimRenderer.draw(canvas, rect.left, rect.top, rect.right, rect.bottom, radii, rim, 1f);
        }
    }

    @Override
    public void setAlpha(int i) {
        alpha = i / 255.0f;
        updateColors();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }
}
