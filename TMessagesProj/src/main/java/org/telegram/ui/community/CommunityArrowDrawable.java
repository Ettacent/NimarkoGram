package org.telegram.ui.community;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Gravity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.utils.DrawableUtils;
import org.telegram.ui.ActionBar.Theme;

import app.nimarkogram.messenger.NimarkoConfig;

public class CommunityArrowDrawable extends Drawable {
    private static final float INLINE_MAX_WIDTH_DP = 20f;
    private static final float INLINE_MAX_HEIGHT_DP = 20f;

    private Drawable arrowDrawable;
    private int loadedIconPack = Integer.MIN_VALUE;
    private boolean loadedInline;
    private int lastColor = Integer.MIN_VALUE;
    private boolean stockTelegramArrow;
    private float arrowRotationDegrees;
    private boolean drawCircle;
    private boolean inline;
    private Integer inlineColor;

    public CommunityArrowDrawable() {
        ensureArrowDrawable();
    }

    private void ensureArrowDrawable() {
        final int iconPack = NimarkoConfig.iconReplacement;
        if (arrowDrawable != null
                && loadedIconPack == iconPack
                && loadedInline == inline) {
            return;
        }
        loadedIconPack = iconPack;
        loadedInline = inline;
        final int drawableId;
        stockTelegramArrow = false;
        arrowRotationDegrees = 0f;
        switch (iconPack) {
            case NimarkoConfig.ICON_REPLACE_LIQUID_GLASS:
                drawableId = R.drawable.arrow_more_liquid;
                break;
            case NimarkoConfig.ICON_REPLACE_PLUMPY:
                
                drawableId = R.drawable.community_arrow_plumpy;
                break;
            case NimarkoConfig.ICON_REPLACE_SOLAR:
                drawableId = R.drawable.arrow_more_solar;
                break;
            case NimarkoConfig.ICON_REPLACE_NONE:
            default:
                if (inline) {
                    
                    drawableId = R.drawable.arrow_more;
                } else {
                    
                    drawableId = R.drawable.settings_arrow;
                    stockTelegramArrow = true;
                    arrowRotationDegrees = 90f;
                }
                break;
        }
        android.content.res.Resources resources = ApplicationLoader.rawResources();
        if (resources == null) {
            resources = ApplicationLoader.applicationContext.getResources();
        }
        arrowDrawable = resources.getDrawable(drawableId).mutate();
        arrowDrawable.setAlpha(alpha);
        lastColor = Integer.MIN_VALUE;
    }

    private void setArrowBounds(float cx, float cy, float maxWidthDp, float maxHeightDp) {
        int sourceWidth = Math.max(1, arrowDrawable.getIntrinsicWidth());
        int sourceHeight = Math.max(1, arrowDrawable.getIntrinsicHeight());
        
        float scale = Math.min(1f, Math.min(
                dp(maxWidthDp) / (float) sourceWidth,
                dp(maxHeightDp) / (float) sourceHeight));
        int width = Math.max(1, Math.round(sourceWidth * scale));
        int height = Math.max(1, Math.round(sourceHeight * scale));
        int left = Math.round(cx - width / 2f);
        int top = Math.round(cy - height / 2f);
        arrowDrawable.setBounds(left, top, left + width, top + height);
    }

    public int getInlineVisualWidth() {
        ensureArrowDrawable();
        int sourceWidth = Math.max(1, arrowDrawable.getIntrinsicWidth());
        int sourceHeight = Math.max(1, arrowDrawable.getIntrinsicHeight());
        float scale = Math.min(1f, Math.min(
                dp(INLINE_MAX_WIDTH_DP) / (float) sourceWidth,
                dp(INLINE_MAX_HEIGHT_DP) / (float) sourceHeight));
        return Math.max(1, Math.round(sourceWidth * scale));
    }

    public CommunityArrowDrawable withCircle() {
        drawCircle = true;
        return this;
    }

    public CommunityArrowDrawable setInline(boolean inline) {
        if (this.inline != inline) {
            this.inline = inline;
            lastColor = Integer.MIN_VALUE;
            invalidateSelf();
        }
        return this;
    }

    public CommunityArrowDrawable setInlineColor(int color) {
        if (inlineColor == null || inlineColor != color) {
            inlineColor = color;
            lastColor = Integer.MIN_VALUE;
            invalidateSelf();
        }
        return this;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        ensureArrowDrawable();
        final float cx = getBounds().exactCenterX();
        final float cy = getBounds().exactCenterY();

        final int bgColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int iconColor = inline
                ? (inlineColor != null ? inlineColor : Theme.getColor(Theme.key_actionBarDefaultTitle))
                : Theme.getColor(Theme.key_windowBackgroundWhite);
        if (!inline && ColorUtils.calculateContrast(
                ColorUtils.setAlphaComponent(iconColor, 255),
                ColorUtils.setAlphaComponent(bgColor, 255)) < 3.0) {
            iconColor = ColorUtils.calculateLuminance(
                    ColorUtils.setAlphaComponent(bgColor, 255)) > 0.5
                    ? Color.BLACK : Color.WHITE;
        }
        if (lastColor != iconColor) {
            lastColor = iconColor;
            
            arrowDrawable.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        }

        if (inline) {
            setArrowBounds(cx, cy, INLINE_MAX_WIDTH_DP, INLINE_MAX_HEIGHT_DP);
            canvas.save();
            canvas.rotate(arrowRotationDegrees, cx, cy);
            arrowDrawable.draw(canvas);
            canvas.restore();
            return;
        }

        canvas.drawCircle(cx, cy, dp(46 / 6f), Theme.fillingPaint(ColorUtils.setAlphaComponent(iconColor, alpha)));

        canvas.drawCircle(cx, cy, dp(40 / 6f), Theme.fillingPaint(ColorUtils.setAlphaComponent(bgColor, alpha)));
        canvas.save();
        if (stockTelegramArrow) {
            
            DrawableUtils.setBounds(arrowDrawable, cx, cy, Gravity.CENTER);
            canvas.translate(0, dp(0.66f));
        } else {
            setArrowBounds(cx, cy, 12f, 12f);
        }
        canvas.rotate(arrowRotationDegrees, cx, cy);
        if (stockTelegramArrow) {
            DrawableUtils.drawWithScale(canvas, arrowDrawable, 0.8f);
        } else {
            arrowDrawable.draw(canvas);
        }
        canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() {
        return dp(40 / 3f);
    }

    @Override
    public int getIntrinsicHeight() {
        return dp(40 / 3f);
    }

    private int alpha = 255;

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        if (arrowDrawable != null) {
            arrowDrawable.setAlpha(alpha);
        }
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
