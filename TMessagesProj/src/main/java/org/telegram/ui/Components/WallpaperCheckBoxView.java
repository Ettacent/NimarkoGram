/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
import android.util.Property;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import app.nimarkogram.messenger.icons.IconResources;
import app.nimarkogram.messenger.AppConfig;
public class WallpaperCheckBoxView extends View {

    Theme.ResourcesProvider resourcesProvider;
    private Paint eraserPaint;
    private Paint checkPaint;
    private TextPaint textPaint;
    private Paint backgroundPaint;

    private String currentText;
    private int currentTextSize;
    private int maxTextSize;
    private RectF rect;

    private boolean isChecked;
    private Canvas drawCanvas;
    private Bitmap drawBitmap;
    private float progress;
    private ObjectAnimator checkAnimator;

    private Drawable packCheckDrawable;
    private Bitmap packRingBitmap;
    private final Rect packRingInk = new Rect();
    private final Paint packRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int packCheckSelection;
    private PorterDuffColorFilter packCheckTint;
    private int packCheckColor;
    private Resources packCheckResources;
    private Configuration packCheckConfiguration;
    private long packCheckGeneration = Long.MIN_VALUE;
    private int packCheckDensityDpi;
    private float packCheckDensity;
    private int packCheckSize;
    private View parentView;

    private int[] colors = new int[4];

    private final static float progressBounceDiff = 0.2f;

    public final Property<WallpaperCheckBoxView, Float> PROGRESS_PROPERTY = new AnimationProperties.FloatProperty<WallpaperCheckBoxView>("progress") {
        @Override
        public void setValue(WallpaperCheckBoxView object, float value) {
            progress = value;
            invalidate();
        }

        @Override
        public Float get(WallpaperCheckBoxView object) {
            return progress;
        }
    };

    public WallpaperCheckBoxView(Context context, boolean check, View parent, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        rect = new RectF();

        if (check) {
            drawBitmap = Bitmap.createBitmap(AndroidUtilities.dp(18), AndroidUtilities.dp(18), Bitmap.Config.ARGB_8888);
            drawCanvas = new Canvas(drawBitmap);
        }

        parentView = parent;

        textPaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(AndroidUtilities.dp(14));
        textPaint.setTypeface(AndroidUtilities.bold());

        checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeWidth(AndroidUtilities.dp(2));
        checkPaint.setColor(0);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eraserPaint.setColor(0);
        eraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    public void setText(String text, int current, int max) {
        currentText = text;
        currentTextSize = current;
        maxTextSize = max;
    }

    public void setColor(int index, int color) {
        if (colors == null) {
            colors = new int[4];
        }
        colors[index] = color;
        invalidate();
    }

    public TextPaint getTextPaint() {
        return textPaint;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(maxTextSize + AndroidUtilities.dp(14 * 2 + 28), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(32), MeasureSpec.EXACTLY));
    }

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float dimAmount;
    public void setDimAmount(float dimAmount) {
        this.dimAmount = dimAmount;
        dimPaint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, (int) (0xFF * dimAmount)));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
        Theme.applyServiceShaderMatrixForView(this, parentView, resourcesProvider);
        canvas.drawRoundRect(rect, getMeasuredHeight() / 2, getMeasuredHeight() / 2, getThemedPaint(Theme.key_paint_chatActionBackground));
        boolean hasGradient = resourcesProvider == null ? Theme.hasGradientService() : resourcesProvider.hasGradientService();
        if (hasGradient) {
            canvas.drawRoundRect(rect, getMeasuredHeight() / 2, getMeasuredHeight() / 2, getThemedPaint(Theme.key_paint_chatActionBackgroundDarken));
        }
        if (dimAmount > 0) {
            canvas.drawRoundRect(rect, getMeasuredHeight() / 2, getMeasuredHeight() / 2, dimPaint);
        }

        textPaint.setColor(Theme.getColor(Theme.key_chat_serviceText, resourcesProvider));
        int x = (getMeasuredWidth() - currentTextSize - AndroidUtilities.dp(28)) / 2;
        canvas.drawText(currentText, x + AndroidUtilities.dp(28), AndroidUtilities.dp(21), textPaint);

        canvas.save();
        canvas.translate(x, AndroidUtilities.dp(7));
        if (drawBitmap != null) {
            int checkSize = AndroidUtilities.dp(18);
            if (drawBitmap.getWidth() != checkSize || drawBitmap.getHeight() != checkSize) {
                drawBitmap = Bitmap.createBitmap(checkSize, checkSize, Bitmap.Config.ARGB_8888);
                drawCanvas = new Canvas(drawBitmap);
            }
            float checkProgress;
            float bounceProgress;
            if (progress <= 0.5f) {
                bounceProgress = checkProgress = progress / 0.5f;
            } else {
                bounceProgress = 2.0f - progress / 0.5f;
                checkProgress = 1.0f;
            }

            float bounce = AndroidUtilities.dp(1) * bounceProgress;
            rect.set(bounce, bounce, AndroidUtilities.dp(18) - bounce, AndroidUtilities.dp(18) - bounce);

            Drawable packCheck = getPackCheckDrawable(checkSize);
            drawBitmap.eraseColor(0);
            int serviceColor = Theme.getColor(Theme.key_chat_serviceText, resourcesProvider);
            backgroundPaint.setColor(serviceColor);
            if (packCheck != null) {
                if (packCheckTint == null || packCheckColor != serviceColor) {
                    packCheckColor = serviceColor;
                    packCheckTint = new PorterDuffColorFilter(serviceColor | 0xff000000, PorterDuff.Mode.MULTIPLY);
                    packCheck.setColorFilter(packCheckTint);
                    packRingPaint.setColorFilter(packCheckTint);
                }
                int ringAlpha = Math.round(Color.alpha(serviceColor) * (1.0f - progress));
                if (packRingBitmap != null) {
                    packRingPaint.setAlpha(ringAlpha);
                    drawCanvas.drawBitmap(packRingBitmap, packRingInk, rect, packRingPaint);
                } else {
                    backgroundPaint.setAlpha(ringAlpha);
                    drawCanvas.drawRoundRect(rect, rect.width() / 2, rect.height() / 2, backgroundPaint);
                }
                float ringInset = packCheckSelection == AppConfig.ICON_REPLACE_SOLAR
                        ? checkSize * 1.5f / 24f : AndroidUtilities.dp(2);
                rect.set(ringInset + bounce, ringInset + bounce,
                        checkSize - ringInset - bounce, checkSize - ringInset - bounce);
                drawCanvas.drawRoundRect(rect, rect.width() / 2, rect.height() / 2, eraserPaint);
                canvas.drawBitmap(drawBitmap, 0, 0, null);
                packCheck.setAlpha(Math.round(Color.alpha(serviceColor) * progress));
                canvas.save();
                float scale = 1.0f - 2.0f * bounce / checkSize;
                canvas.scale(scale, scale, checkSize / 2.0f, checkSize / 2.0f);
                packCheck.draw(canvas);
                canvas.restore();
            } else {
            drawCanvas.drawRoundRect(rect, rect.width() / 2, rect.height() / 2, backgroundPaint);

            if (checkProgress != 1) {
                float rad = Math.min(AndroidUtilities.dp(7), AndroidUtilities.dp(7) * checkProgress + bounce);
                rect.set(AndroidUtilities.dp(2) + rad, AndroidUtilities.dp(2) + rad, AndroidUtilities.dp(16) - rad, AndroidUtilities.dp(16) - rad);
                drawCanvas.drawRoundRect(rect, rect.width() / 2, rect.height() / 2, eraserPaint);
            }

            if (progress > 0.5f) {
                int endX = (int) (AndroidUtilities.dp(7.3f) - AndroidUtilities.dp(2.5f) * (1.0f - bounceProgress));
                int endY = (int) (AndroidUtilities.dp(13) - AndroidUtilities.dp(2.5f) * (1.0f - bounceProgress));
                drawCanvas.drawLine(AndroidUtilities.dp(7.3f), AndroidUtilities.dp(13), endX, endY, checkPaint);
                endX = (int) (AndroidUtilities.dp(7.3f) + AndroidUtilities.dp(6) * (1.0f - bounceProgress));
                endY = (int) (AndroidUtilities.dp(13) - AndroidUtilities.dp(6) * (1.0f - bounceProgress));
                drawCanvas.drawLine(AndroidUtilities.dp(7.3f), AndroidUtilities.dp(13), endX, endY, checkPaint);
            }
            canvas.drawBitmap(drawBitmap, 0, 0, null);
            }
        } else {
            rect.set(0, 0, AndroidUtilities.dp(18), AndroidUtilities.dp(18));
            if (colors[3] != 0) {
                for (int a = 0; a < 4; a++) {
                    backgroundPaint.setColor(colors[a]);
                    canvas.drawArc(rect, -90 + 90 * a, 90, true, backgroundPaint);
                }
            } else if (colors[2] != 0) {
                for (int a = 0; a < 3; a++) {
                    backgroundPaint.setColor(colors[a]);
                    canvas.drawArc(rect, -90 + 120 * a, 120, true, backgroundPaint);
                }
            } else if (colors[1] != 0) {
                for (int a = 0; a < 2; a++) {
                    backgroundPaint.setColor(colors[a]);
                    canvas.drawArc(rect, -90 + 180 * a, 180, true, backgroundPaint);
                }
            } else {
                backgroundPaint.setColor(colors[0]);
                canvas.drawRoundRect(rect, rect.width() / 2, rect.height() / 2, backgroundPaint);
            }
        }
        canvas.restore();
    }

    private Drawable getPackCheckDrawable(int size) {
        Resources resources = getContext().getResources();
        long generation = resources instanceof IconResources
                ? ((IconResources) resources).getReplacementGeneration() : -1L;
        int densityDpi = resources.getDisplayMetrics().densityDpi;
        float density = resources.getDisplayMetrics().density;
        Configuration configuration = resources.getConfiguration();
        if (packCheckResources == resources && packCheckGeneration == generation
                && packCheckDensityDpi == densityDpi && packCheckDensity == density
                && packCheckSize == size && configuration.equals(packCheckConfiguration)) {
            if (packRingBitmap != null && packRingBitmap.isRecycled()) {
                packRingBitmap = null;
                packCheckDrawable = null;
            }
            return packCheckDrawable;
        }
        packCheckResources = resources;
        packCheckGeneration = generation;
        packCheckDensityDpi = densityDpi;
        packCheckDensity = density;
        packCheckSize = size;
        packCheckConfiguration = new Configuration(configuration);
        packCheckDrawable = null;
        packRingBitmap = null;
        packCheckTint = null;
        if (!(resources instanceof IconResources)
                || !((IconResources) resources).hasReplacement(R.drawable.ic_ab_done)) {
            return null;
        }
        packCheckSelection = ((IconResources) resources).getAppliedSelection();
        if (packCheckSelection != AppConfig.ICON_REPLACE_SOLAR
                && packCheckSelection != AppConfig.ICON_REPLACE_LIQUID_GLASS
                && packCheckSelection != AppConfig.ICON_REPLACE_PLUMPY) {
            return null;
        }
        try {
            if (packCheckSelection != AppConfig.ICON_REPLACE_SOLAR && !preparePackRing(resources)) {
                return null;
            }
            Drawable glyph = resources.getDrawable(R.drawable.ic_ab_done).mutate();
            int width = glyph.getIntrinsicWidth();
            int height = glyph.getIntrinsicHeight();
            int innerSize = Math.max(1, size);
            if (width <= 0 || height <= 0) {
                width = height = innerSize;
            }
            float fit = Math.min((float) innerSize / width, (float) innerSize / height);
            width = Math.max(1, Math.round(width * fit));
            height = Math.max(1, Math.round(height * fit));
            int left = (size - width) / 2;
            int top = (size - height) / 2;
            glyph.setBounds(left, top, left + width, top + height);
            packCheckDrawable = glyph;
        } catch (Resources.NotFoundException missingGlyph) {
        }
        return packCheckDrawable;
    }

    private boolean preparePackRing(Resources resources) {
        if (!((IconResources) resources).hasReplacement(R.drawable.photo_circle)) {
            return false;
        }
        Drawable circle = resources.getDrawable(R.drawable.photo_circle);
        if (!(circle instanceof BitmapDrawable)) {
            return false;
        }
        Bitmap bitmap = ((BitmapDrawable) circle).getBitmap();
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width <= 0 || height <= 0 || width > 256 || height > 256) {
            return false;
        }
        int left = width, top = height, right = 0, bottom = 0;
        int[] row = new int[width];
        try {
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    if (Color.alpha(row[x]) != 0) {
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x + 1);
                        bottom = Math.max(bottom, y + 1);
                    }
                }
            }
        } catch (RuntimeException unreadable) {
            return false;
        }
        if (right <= left || bottom <= top || right - left != bottom - top) {
            return false;
        }
        packRingInk.set(left, top, right, bottom);
        packRingBitmap = bitmap;
        return true;
    }
    private void setProgress(float value) {
        if (progress == value) {
            return;
        }
        progress = value;
        invalidate();
    }

    private void cancelCheckAnimator() {
        if (checkAnimator != null) {
            checkAnimator.cancel();
            checkAnimator = null;
        }
    }

    private void animateToCheckedState(boolean newCheckedState) {
        cancelCheckAnimator();
        checkAnimator = ObjectAnimator.ofFloat(this, PROGRESS_PROPERTY, newCheckedState ? 1.0f : 0.0f);
        checkAnimator.setDuration(300);
        checkAnimator.start();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
    }

    public void setChecked(boolean checked, boolean animated) {
        if (checked == isChecked) {
            if (!animated) {
                cancelCheckAnimator();
                setProgress(checked ? 1.0f : 0.0f);
            }
            return;
        }
        isChecked = checked;
        if (animated) {
            animateToCheckedState(checked);
        } else {
            cancelCheckAnimator();
            progress = checked ? 1.0f : 0.0f;
            invalidate();
        }
    }

    public boolean isChecked() {
        return isChecked;
    }

    private Paint getThemedPaint(String paintKey) {
        Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(paintKey) : null;
        return paint != null ? paint : Theme.getThemePaint(paintKey);
    }
}