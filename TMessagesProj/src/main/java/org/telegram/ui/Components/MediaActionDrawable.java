/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import app.nimarkogram.messenger.icons.NimarkoIconResources;
import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
import android.view.animation.DecelerateInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;

public class MediaActionDrawable extends Drawable {

    public static final int ICON_PLAY = 0;
    public static final int ICON_PAUSE = 1;
    public static final int ICON_DOWNLOAD = 2;
    public static final int ICON_CANCEL = 3;
    public static final int ICON_NONE = 4;
    public static final int ICON_FILE = 5;
    public static final int ICON_CHECK = 6;
    public static final int ICON_FIRE = 7;
    public static final int ICON_GIF = 8;
    public static final int ICON_SECRETCHECK = 9;
    public static final int ICON_EMPTY = 10;
    public static final int ICON_EMPTY_NOPROGRESS = 11;
    public static final int ICON_CANCEL_NOPROFRESS = 12;
    public static final int ICON_CANCEL_PERCENT = 13;
    public static final int ICON_CANCEL_FILL = 14;
    public static final int ICON_UPDATE = 15;

    private TextPaint textPaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
    public Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public Paint paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint paint3 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF rect = new RectF();
    private ColorFilter colorFilter;
    private float scale = 1.0f;
    private DecelerateInterpolator interpolator = new DecelerateInterpolator();

    private boolean isMini;

    private float transitionAnimationTime = 400.0f;

    private int lastPercent = -1;
    private String percentString;
    private int percentStringWidth;

    private float overrideAlpha = 1.0f;

    private int currentIcon;
    private int nextIcon;
    private float transitionProgress = 1.0f;
    private float savedTransitionProgress;
    private long lastAnimationTime;
    private boolean animatingTransition;

    private float downloadRadOffset;
    private float downloadProgress;
    private float animatedDownloadProgress;
    private float downloadProgressAnimationStart;
    private float downloadProgressTime;

    private final static float EPS = 0.001f;

    private final static float DOWNLOAD_TO_CANCEL_STAGE1 = 0.5f;
    private final static float DOWNLOAD_TO_CANCEL_STAGE2 = 0.2f;
    private final static float DOWNLOAD_TO_CANCEL_STAGE3 = 0.3f;

    private final static float CANCEL_TO_CHECK_STAGE1 = 0.5f;
    private final static float CANCEL_TO_CHECK_STAGE2 = 0.5f;

    private MediaActionDrawableDelegate delegate;

    private MessageDrawable messageDrawable;
    private LinearGradient gradientDrawable;
    private Matrix gradientMatrix;
    private boolean hasOverlayImage;

    private final PackIconRenderer packIconRenderer = new PackIconRenderer(true);

    static final class PackIconRenderer {
        private final boolean useSourceForeground;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint colorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bitmapBounds = new RectF();
        private final Paint morphPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private NimarkoIconResources.PreparedIcon morphPlay, morphPause;
        private float[][] morphFrom, morphTo;
        private float[] morphVertices;
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix progressMatrix = new Matrix();
        private LinearGradient progressGradient;

        PackIconRenderer() {
            this(false);
        }

        PackIconRenderer(boolean useSourceForeground) {
            this.useSourceForeground = useSourceForeground;
            colorPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            morphPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        }

        static NimarkoIconResources.PreparedIcon get(NimarkoIconResources.NavigationIcons icons, int id) {
            NimarkoIconResources.PreparedIcon icon = icons == null ? null : icons.getIcon(id);
            return icon == null || icon.bitmap == null || icon.bitmap.isRecycled() ? null : icon;
        }

        void drawProgressArc(Canvas canvas, RectF bounds, float start, float sweep, Paint source, int selection) {
            drawProgressArc(canvas, bounds, start, sweep, source, selection, 1f);
        }

        void drawProgressArc(Canvas canvas, RectF bounds, float start, float sweep, Paint source, int selection, float alpha) {
            int opacity = Math.max(0, Math.min(255, Math.round(source.getAlpha() * alpha)));
            if (opacity == 0) {
                return;
            }
            if (selection != NimarkoConfig.ICON_REPLACE_LIQUID_GLASS) {
                if (opacity == source.getAlpha()) {
                    canvas.drawArc(bounds, start, sweep, false, source);
                } else {
                    progressPaint.set(source);
                    progressPaint.setAlpha(opacity);
                    canvas.drawArc(bounds, start, sweep, false, progressPaint);
                }
                return;
            }
            if (progressGradient == null) {
                progressGradient = new LinearGradient(0, 0, 1, 1,
                        new int[]{0x94ffffff, 0x7cffffff, 0x64ffffff}, null, Shader.TileMode.CLAMP);
            }
            progressMatrix.setScale(bounds.width(), bounds.height());
            progressMatrix.postTranslate(bounds.left, bounds.top);
            progressGradient.setLocalMatrix(progressMatrix);
            progressPaint.set(source);
            progressPaint.setColor(0xffffffff);
            progressPaint.setAlpha(opacity);
            progressPaint.setColorFilter(null);
            progressPaint.setShader(progressGradient);
            float inset = source.getStrokeWidth();
            int save = canvas.saveLayer(bounds.left - inset, bounds.top - inset,
                    bounds.right + inset, bounds.bottom + inset, null);
            try {
                canvas.drawArc(bounds, start, sweep, false, progressPaint);
                colorPaint.setColor(source.getColor() | 0xff000000);
                colorPaint.setColorFilter(source.getColorFilter());
                colorPaint.setShader(source.getShader());
                canvas.drawRect(bounds.left - inset, bounds.top - inset,
                        bounds.right + inset, bounds.bottom + inset, colorPaint);
            } finally {
                colorPaint.setShader(null);
                canvas.restoreToCount(save);
            }
        }

        void draw(Canvas canvas, NimarkoIconResources.PreparedIcon icon, Paint source, float cx, float cy,
                  float size, float alpha, float rotation) {
            draw(canvas, icon, source, cx, cy, size, alpha, rotation, 1, 1);
        }

        void draw(Canvas canvas, NimarkoIconResources.PreparedIcon icon, Paint source, float cx, float cy,
                  float size, float alpha, float rotation, float scaleX, float scaleY) {
            if (icon == null || alpha <= 0 || size <= 0) {
                return;
            }
            boolean preserveColor = !useSourceForeground && icon.noTint;
            bitmapPaint.setAlpha(Math.min(255, Math.max(0, (int) (255 * alpha))));
            float factor = size / Math.max(icon.bitmap.getWidth(), icon.bitmap.getHeight());
            float halfWidth = icon.bitmap.getWidth() * factor * scaleX / 2;
            float halfHeight = icon.bitmap.getHeight() * factor * scaleY / 2;
            bitmapBounds.set(-halfWidth, -halfHeight, halfWidth, halfHeight);
            float extent = halfWidth + halfHeight;
            int saveCount = preserveColor ? canvas.save()
                    : canvas.saveLayer(cx - extent, cy - extent, cx + extent, cy + extent, null);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(rotation);
            canvas.drawBitmap(icon.bitmap, null, bitmapBounds, bitmapPaint);
            canvas.restore();
            if (!preserveColor) {
                colorPaint.setColor(source.getColor() | 0xff000000);
                colorPaint.setColorFilter(source.getColorFilter());
                colorPaint.setShader(source.getShader());
                canvas.drawRect(cx - extent, cy - extent, cx + extent, cy + extent, colorPaint);
                colorPaint.setShader(null);
            }
            canvas.restoreToCount(saveCount);
        }

        void drawPlayPause(Canvas canvas, NimarkoIconResources.PreparedIcon play, NimarkoIconResources.PreparedIcon pause,
                           Paint source, float cx, float cy, float size, float progress, float alpha) {
            if (alpha <= 0 || size <= 0) {
                return;
            }
            if (play == null || pause == null || play == pause) {
                draw(canvas, play == null ? pause : play, source, cx, cy, size, alpha, 0);
                return;
            }
            progress = Math.max(0, Math.min(1, progress));
            if (progress == 0 || progress == 1) {
                draw(canvas, progress == 0 ? play : pause, source, cx, cy, size, alpha, 0);
                return;
            }
            if (morphPlay != play || morphPause != pause) {
                morphPlay = play;
                morphPause = pause;
                try {
                    prepareMorph(play, pause);
                } catch (RuntimeException | OutOfMemoryError ignore) {
                    morphFrom = morphTo = null;
                }
            }
            if (morphFrom == null) {
                draw(canvas, progress < 0.5f ? play : pause, source, cx, cy, size, alpha, 0);
                return;
            }
            float p = progress * progress * (3 - 2 * progress);
            float angle = p * (float) Math.PI / 2;
            float cosine = (float) Math.cos(angle);
            float sine = (float) Math.sin(angle);
            int save = canvas.saveLayerAlpha(cx - size, cy - size, cx + size, cy + size,
                    Math.max(0, Math.min(255, (int) (alpha * 255))), Canvas.ALL_SAVE_FLAG);
            for (int endpoint = 0; endpoint < 2; endpoint++) {
                NimarkoIconResources.PreparedIcon icon = endpoint == 0 ? play : pause;
                boolean preserveColor = !useSourceForeground && icon.noTint;
                int material = canvas.saveLayer(cx - size, cy - size, cx + size, cy + size, morphPaint);
                bitmapPaint.setAlpha(Math.round(255 * (endpoint == 0 ? 1 - p : p)));
                for (int side = 0; side < 2; side++) {
                    int mesh = endpoint * 2 + side;
                    for (int i = 0; i < morphVertices.length; i += 2) {
                        float x = morphFrom[mesh][i] + (morphTo[mesh][i + 1] - morphFrom[mesh][i]) * p;
                        float y = morphFrom[mesh][i + 1] + (-morphTo[mesh][i] - morphFrom[mesh][i + 1]) * p;
                        morphVertices[i] = cx + size * (x * cosine - y * sine);
                        morphVertices[i + 1] = cy + size * (x * sine + y * cosine);
                    }
                    canvas.drawBitmapMesh(icon.bitmap, 32, 32, morphVertices, 0, null, 0, bitmapPaint);
                }
                if (!preserveColor) {
                    colorPaint.setColor(source.getColor() | 0xff000000);
                    colorPaint.setColorFilter(source.getColorFilter());
                    colorPaint.setShader(source.getShader());
                    canvas.drawRect(cx - size, cy - size, cx + size, cy + size, colorPaint);
                    colorPaint.setShader(null);
                }
                canvas.restoreToCount(material);
            }
            canvas.restoreToCount(save);
        }

        private void prepareMorph(NimarkoIconResources.PreparedIcon play, NimarkoIconResources.PreparedIcon pause) {
            if (morphVertices == null) {
                morphVertices = new float[2 * 33 * 33];
            }
            morphFrom = new float[4][morphVertices.length];
            morphTo = new float[4][morphVertices.length];
            for (int side = 0; side < 2; side++) {
                MorphContour triangle = new MorphContour(play.bitmap, false, side);
                MorphContour bar = new MorphContour(pause.bitmap, true, side);
                for (int endpoint = 0; endpoint < 2; endpoint++) {
                    MorphContour source = endpoint == 0 ? triangle : bar;
                    MorphContour target = endpoint == 0 ? bar : triangle;
                    float[] from = morphFrom[endpoint * 2 + side];
                    float[] to = morphTo[endpoint * 2 + side];
                    for (int row = 0, i = 0; row <= 32; row++) {
                        for (int column = 0; column <= 32; column++, i += 2) {
                            float x = source.width * column / 32f;
                            float y = source.height * row / 32f;
                            if (endpoint == 0) {
                                y = side == 0 ? Math.min(y, source.height / 2f) : Math.max(y, source.height / 2f);
                            } else {
                                x = side == 0 ? Math.max(x, source.width / 2f) : Math.min(x, source.width / 2f);
                            }
                            float along = endpoint == 0 ? x : y;
                            float u = Math.max(0, Math.min(1, (along - source.start) / Math.max(1, source.end - source.start)));
                            float low = source.edge(source.low, along);
                            float high = source.edge(source.high, along);
                            float v = endpoint == 0 ? (y - low) / Math.max(1, high - low) : (high - x) / Math.max(1, high - low);
                            v = Math.max(-1, Math.min(2, v));
                            float targetAlong = target.start + (target.end - target.start) * u;
                            float targetLow = target.edge(target.low, targetAlong);
                            float targetHigh = target.edge(target.high, targetAlong);
                            float tx = endpoint == 0 ? targetHigh - v * (targetHigh - targetLow) : targetAlong;
                            float ty = endpoint == 0 ? targetAlong : targetLow + v * (targetHigh - targetLow);
                            float sourceScale = Math.max(source.width, source.height);
                            float targetScale = Math.max(target.width, target.height);
                            float[] original = endpoint == 0 ? from : to;
                            float[] mapped = endpoint == 0 ? to : from;
                            original[i] = (x - source.width / 2f) / sourceScale;
                            original[i + 1] = (y - source.height / 2f) / sourceScale;
                            mapped[i] = (tx - target.width / 2f) / targetScale;
                            mapped[i + 1] = (ty - target.height / 2f) / targetScale;
                        }
                    }
                }
            }
            morphPlay = play;
            morphPause = pause;
        }

        private static final class MorphContour {
            final int width, height;
            final float[] low, high;
            int start, end;

            MorphContour(Bitmap bitmap, boolean vertical, int side) {
                width = bitmap.getWidth();
                height = bitmap.getHeight();
                if ((long) width * height > 1048576) {
                    throw new IllegalArgumentException();
                }
                int length = vertical ? height : width;
                int cross = vertical ? width : height;
                low = new float[length];
                high = new float[length];
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                start = length;
                end = 0;
                for (int a = 0; a < length; a++) {
                    int first = cross, last = -1;
                    int from = (vertical ? side == 0 : side == 1) ? cross / 2 : 0;
                    int to = from == 0 ? cross / 2 : cross;
                    for (int b = from; b < to; b++) {
                        if ((pixels[vertical ? a * width + b : b * width + a] >>> 24) != 0) {
                            first = Math.min(first, b);
                            last = b;
                        }
                    }
                    if (last >= first) {
                        start = Math.min(start, a);
                        end = a + 1;
                        low[a] = !vertical && side == 1 ? height / 2f : first;
                        high[a] = !vertical && side == 0 ? height / 2f : last + 1;
                    } else {
                        low[a] = Float.NaN;
                    }
                }
                if (start == length) {
                    start = 0;
                    end = length;
                }
                int nearest = start;
                for (int a = 0; a < length; a++) {
                    if (Float.isNaN(low[a])) {
                        low[a] = Float.isNaN(low[nearest]) ? cross / 2f : low[nearest];
                        high[a] = Math.max(low[a] + 1, high[nearest]);
                    } else {
                        nearest = a;
                    }
                }
            }

            float edge(float[] values, float position) {
                float p = Math.max(0, Math.min(values.length - 1, position - 0.5f));
                int i = (int) p;
                return values[i] + (values[Math.min(i + 1, values.length - 1)] - values[i]) * (p - i);
            }
        }
    }

    private static NimarkoIconResources.PreparedIcon packIcon(int icon, NimarkoIconResources.PreparedIcon play,
            NimarkoIconResources.PreparedIcon pause, NimarkoIconResources.PreparedIcon download, NimarkoIconResources.PreparedIcon cancel,
            NimarkoIconResources.PreparedIcon file, NimarkoIconResources.PreparedIcon update, NimarkoIconResources.PreparedIcon check,
            NimarkoIconResources.PreparedIcon stop) {
        switch (icon) {
            case ICON_PLAY:
                return play;
            case ICON_PAUSE:
                return pause;
            case ICON_DOWNLOAD:
                return download;
            case ICON_CANCEL:
            case ICON_CANCEL_NOPROFRESS:
                return cancel;
            case ICON_CANCEL_FILL:
                return stop;
            case ICON_FILE:
                return file;
            case ICON_UPDATE:
                return update;
            case ICON_CHECK:
            case ICON_SECRETCHECK:
                return check;
            default:
                return null;
        }
    }
    public interface MediaActionDrawableDelegate {
        void invalidate();
    }

    public MediaActionDrawable() {
        paint.setColor(0xffffffff);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(AndroidUtilities.dp(3));
        paint.setStyle(Paint.Style.STROKE);

        paint3.setColor(0xffffffff);

        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(AndroidUtilities.dp(13));
        textPaint.setColor(0xffffffff);

        paint2.setColor(0xffffffff);
    }

    @Override
    public void setAlpha(int alpha) {

    }

    public void setOverrideAlpha(float alpha) {
        overrideAlpha = alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        paint2.setColorFilter(colorFilter);
        paint3.setColorFilter(colorFilter);
        textPaint.setColorFilter(colorFilter);
    }

    public void setColor(int value) {
        paint.setColor(value | 0xff000000);
        paint2.setColor(value | 0xff000000);
        paint3.setColor(value | 0xff000000);
        textPaint.setColor(value | 0xff000000);
        colorFilter = new PorterDuffColorFilter(value, PorterDuff.Mode.MULTIPLY);
    }

    public void setBackColor(int value) {
        backPaint.setColor(value | 0xff000000);
    }

    public int getColor() {
        return paint.getColor();
    }

    public void setMini(boolean value) {
        isMini = value;
        paint.setStrokeWidth(AndroidUtilities.dp(isMini ? 2 : 3));
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    public void setDelegate(MediaActionDrawableDelegate mediaActionDrawableDelegate) {
        delegate = mediaActionDrawableDelegate;
    }

    public boolean setIcon(int icon, boolean animated) {
        if (animated && animatingTransition && icon == currentIcon && currentIcon != nextIcon &&
                (currentIcon == ICON_PLAY || currentIcon == ICON_PAUSE) &&
                (nextIcon == ICON_PLAY || nextIcon == ICON_PAUSE)) {
            NimarkoIconResources.NavigationIcons pack = NimarkoIconResources.activeNavigationIcons();
            if (PackIconRenderer.get(pack, R.drawable.ic_action_play) != null &&
                    PackIconRenderer.get(pack, R.drawable.ic_action_pause) != null) {
                currentIcon = nextIcon;
                nextIcon = icon;
                transitionProgress = 1 - transitionProgress;
                if (transitionProgress >= 1) {
                    currentIcon = nextIcon;
                    animatingTransition = false;
                }
                lastAnimationTime = System.currentTimeMillis();
                invalidateSelf();
                return true;
            }
        }
        if (currentIcon == icon && nextIcon != icon) {
            currentIcon = nextIcon;
            transitionProgress = 1.0f;
        }
        if (animated) {
            if (currentIcon == icon || nextIcon == icon) {
                return false;
            }
            if (currentIcon == ICON_PLAY && icon == ICON_PAUSE || currentIcon == ICON_PAUSE && icon == ICON_PLAY) {
                transitionAnimationTime = 300.0f;
            } else if (currentIcon == ICON_DOWNLOAD && (icon == ICON_CANCEL || icon == ICON_CANCEL_FILL)) {
                transitionAnimationTime = 400.0f;
            } else if (currentIcon != ICON_NONE && icon == ICON_CHECK) {
                transitionAnimationTime = 360.0f;
            } else if (currentIcon == ICON_NONE && icon == ICON_CANCEL_FILL || currentIcon == ICON_CANCEL_FILL && icon == ICON_NONE) {
                transitionAnimationTime = 160.0f;
            } else {
                transitionAnimationTime = 220.0f;
            }
            if (animatingTransition) {
                currentIcon = nextIcon;
            }
            animatingTransition = true;
            nextIcon = icon;
            savedTransitionProgress = transitionProgress;
            transitionProgress = 0.0f;
        } else {
            if (currentIcon == icon) {
                return false;
            }
            animatingTransition = false;
            currentIcon = nextIcon = icon;
            savedTransitionProgress = transitionProgress;
            transitionProgress = 1.0f;
        }
        if (icon == ICON_CANCEL || icon == ICON_CANCEL_FILL) {
            downloadRadOffset = 112;
            animatedDownloadProgress = 0.0f;
            downloadProgressAnimationStart = 0.0f;
            downloadProgressTime = 0.0f;
        }
        invalidateSelf();
        return true;
    }

    public int getCurrentIcon() {
        return nextIcon;
    }

    public int getPreviousIcon() {
        return currentIcon;
    }

    public void setProgress(float value, boolean animated) {
        if (downloadProgress == value) {
            return;
        }
        if (!animated) {
            animatedDownloadProgress = value;
            downloadProgressAnimationStart = value;
        } else {
            if (animatedDownloadProgress > value) {
                animatedDownloadProgress = value;
            }
            downloadProgressAnimationStart = animatedDownloadProgress;
        }
        downloadProgress = value;
        downloadProgressTime = 0;
        invalidateSelf();
    }

    public float getProgress() {
        return downloadProgress;
    }

    public static float getCircleValue(float value) {
        while (value > 360) {
            value -= 360;
        }
        return value;
    }

    public float getProgressAlpha() {
        return 1.0f - transitionProgress;
    }

    public float getTransitionProgress() {
        return animatingTransition ? transitionProgress : 1.0f;
    }

    public void setBackgroundDrawable(MessageDrawable drawable) {
        messageDrawable = drawable;
    }

    public void setBackgroundGradientDrawable(LinearGradient drawable) {
        gradientDrawable = drawable;
        gradientMatrix = new Matrix();
    }

    public void setHasOverlayImage(boolean value) {
        hasOverlayImage = value;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        scale = (right - left) / (float) getIntrinsicWidth();
        if (scale < 0.7f) {
            paint.setStrokeWidth(AndroidUtilities.dp(2));
        }
    }

    @Override
    public void invalidateSelf() {
        super.invalidateSelf();
        if (delegate != null) {
            delegate.invalidate();
        }
    }

    public void applyShaderMatrix(boolean path) {
        if (messageDrawable != null && messageDrawable.hasGradient() && !hasOverlayImage) {
            android.graphics.Rect bounds = getBounds();
            Shader shader = messageDrawable.getGradientShader();
            Matrix matrix = messageDrawable.getMatrix();
            matrix.reset();
            messageDrawable.applyMatrixScale();
            if (path) {
                matrix.postTranslate(-bounds.centerX(), -messageDrawable.getTopY() + bounds.top);
            } else {
                matrix.postTranslate(0, -messageDrawable.getTopY());
            }
            shader.setLocalMatrix(matrix);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect bounds = getBounds();

        NimarkoIconResources.NavigationIcons pack = NimarkoIconResources.activeNavigationIcons();
        NimarkoIconResources.PreparedIcon packPlay = PackIconRenderer.get(pack, R.drawable.ic_action_play);
        NimarkoIconResources.PreparedIcon packPause = PackIconRenderer.get(pack, R.drawable.ic_action_pause);
        NimarkoIconResources.PreparedIcon packDownload = PackIconRenderer.get(pack, R.drawable.ng_file_download);
        NimarkoIconResources.PreparedIcon packCancel = PackIconRenderer.get(pack, R.drawable.ng_media_cancel);
        NimarkoIconResources.PreparedIcon packStop = PackIconRenderer.get(pack, R.drawable.ng_media_stop);
        if (packDownload == null || packCancel == null || packStop == null) {
            packDownload = packCancel = packStop = null;
        }
        NimarkoIconResources.PreparedIcon packCancelShape = currentIcon == ICON_CANCEL_FILL || nextIcon == ICON_CANCEL_FILL
                ? packStop : packCancel;
        int progressSelection = pack != null && packDownload != null && packCancel != null && packStop != null
                ? pack.selection : NimarkoConfig.ICON_REPLACE_NONE;
        NimarkoIconResources.PreparedIcon packFile = PackIconRenderer.get(pack, R.drawable.msg_sendfile);
        NimarkoIconResources.PreparedIcon packUpdate = PackIconRenderer.get(pack, R.drawable.msg_retry);
        NimarkoIconResources.PreparedIcon packCheck = PackIconRenderer.get(pack, R.drawable.ic_ab_done);
        if (packPlay == null || packPause == null) {
            packPlay = packPause = null;
        }
        boolean packPlayback = packPlay != null;
        if (messageDrawable != null && messageDrawable.hasGradient() && !hasOverlayImage) {
            Shader shader = messageDrawable.getGradientShader();
            paint.setShader(shader);
            paint2.setShader(shader);
            paint3.setShader(shader);
        } else if (gradientDrawable != null && !hasOverlayImage) {
            gradientMatrix.reset();
            gradientMatrix.setTranslate(0, bounds.top);
            gradientDrawable.setLocalMatrix(gradientMatrix);
            paint.setShader(gradientDrawable);
            paint2.setShader(gradientDrawable);
            paint3.setShader(gradientDrawable);
        } else {
            paint.setShader(null);
            paint2.setShader(null);
            paint3.setShader(null);
        }

        int cx = bounds.centerX();
        int cy = bounds.centerY();

        int saveCount = 0;

        if (nextIcon == ICON_NONE) {
            if (currentIcon != ICON_CANCEL && currentIcon != ICON_CANCEL_FILL) {
                saveCount = canvas.save();
                float progress = 1.0f - transitionProgress;
                canvas.scale(progress, progress, cx, cy);
            }
        } else if ((nextIcon == ICON_CHECK || nextIcon == ICON_EMPTY) && currentIcon == ICON_NONE) {
            saveCount = canvas.save();
            canvas.scale(transitionProgress, transitionProgress, cx, cy);
        }

        int width = AndroidUtilities.dp(3);
        float packSize = AndroidUtilities.dp(24) * scale;
        boolean packDownloadMotion = currentIcon == nextIcon ||
                currentIcon == ICON_DOWNLOAD && (nextIcon == ICON_CANCEL || nextIcon == ICON_CANCEL_FILL) ||
                nextIcon == ICON_DOWNLOAD && (currentIcon == ICON_CANCEL || currentIcon == ICON_CANCEL_FILL);
        boolean packCancelMotion = currentIcon == nextIcon || currentIcon == ICON_NONE ||
                nextIcon == ICON_DOWNLOAD || nextIcon == ICON_PLAY || nextIcon == ICON_PAUSE || nextIcon == ICON_NONE;
        if (currentIcon == ICON_DOWNLOAD || nextIcon == ICON_DOWNLOAD) {
            applyShaderMatrix(false);
            float yStart = cy - AndroidUtilities.dp(9) * scale;
            float yEnd = cy + AndroidUtilities.dp(9) * scale;
            float yStart2;
            float yEnd2 = cy + AndroidUtilities.dp(12) * scale;

            float transition;
            if ((currentIcon == ICON_CANCEL || currentIcon == ICON_CANCEL_FILL) && nextIcon == ICON_DOWNLOAD) {
                paint.setAlpha((int) (255 * Math.min(1.0f, transitionProgress / 0.5f)));
                transition = transitionProgress;
                yStart2 = cy + AndroidUtilities.dp(12) * scale;
            } else {
                if (nextIcon != ICON_CANCEL && nextIcon != ICON_CANCEL_FILL && nextIcon != ICON_DOWNLOAD) {
                    paint.setAlpha((int) (255 * Math.min(1.0f, savedTransitionProgress / 0.5f) * (1.0f - transitionProgress)));
                    transition = savedTransitionProgress;
                } else {
                    paint.setAlpha(255);
                    transition = transitionProgress;
                }
                yStart2 = cy + AndroidUtilities.dp(1) * scale;
            }

            float y1, y2, x1, x2, y3;
            if (animatingTransition) {
                float progress = transition;
                if (nextIcon == ICON_DOWNLOAD || progress <= DOWNLOAD_TO_CANCEL_STAGE1) {
                    float currentProgress;
                    float currentBackProgress;
                    if (nextIcon == ICON_DOWNLOAD) {
                        currentBackProgress = transition;
                        currentProgress = 1.0f - currentBackProgress;
                    } else {
                        currentProgress = transition / DOWNLOAD_TO_CANCEL_STAGE1;
                        currentBackProgress = 1.0f - currentProgress;
                    }
                    y1 = yStart + (yStart2 - yStart) * currentProgress;
                    y2 = yEnd + (yEnd2 - yEnd) * currentProgress;
                    x1 = cx - AndroidUtilities.dp(8) * currentBackProgress * scale;
                    x2 = cx + AndroidUtilities.dp(8) * currentBackProgress * scale;
                    y3 = y2 - AndroidUtilities.dp(8) * currentBackProgress * scale;
                } else {
                    float currentProgress;
                    float currentProgress2;
                    float currentProgress3;
                    float d = AndroidUtilities.dp(13) * scale * scale + (isMini ? AndroidUtilities.dp(2) : 0);

                    progress -= DOWNLOAD_TO_CANCEL_STAGE1;
                    currentProgress3 = progress / (DOWNLOAD_TO_CANCEL_STAGE2 + DOWNLOAD_TO_CANCEL_STAGE3);
                    if (progress > DOWNLOAD_TO_CANCEL_STAGE2) {
                        progress -= DOWNLOAD_TO_CANCEL_STAGE2;
                        currentProgress = 1.0f;
                        currentProgress2 = progress / DOWNLOAD_TO_CANCEL_STAGE3;
                    } else {
                        currentProgress = progress / DOWNLOAD_TO_CANCEL_STAGE2;
                        currentProgress2 = 0.0f;
                    }
                    rect.set(cx - d, yEnd2 - d / 2, cx, yEnd2 + d / 2);
                    float start = 100 * currentProgress2;
                    packIconRenderer.drawProgressArc(canvas, rect, start, 104 * currentProgress3 - start, paint, progressSelection,
                            progressSelection == NimarkoConfig.ICON_REPLACE_NONE ? 1f : overrideAlpha);

                    y1 = yStart2 + (yEnd2 - yStart2) * currentProgress;
                    y2 = y3 = yEnd2;
                    x1 = x2 = cx;

                    if (currentProgress2 > 0) {
                        float rotation;
                        if (nextIcon == ICON_CANCEL_FILL) {
                            rotation = 0;
                        } else {
                            rotation = -45 * (1.0f - currentProgress2);
                        }
                        d = AndroidUtilities.dp(7) * currentProgress2 * scale;
                        int alpha = (int) (255 * currentProgress2);
                        if (nextIcon != ICON_CANCEL && nextIcon != ICON_CANCEL_FILL && nextIcon != ICON_DOWNLOAD) {
                            float backProgress = (1.0f - Math.min(1.0f, transitionProgress / 0.5f));
                            //d *= backProgress;
                            alpha *= backProgress;
                        }

                        if (rotation != 0) {
                            canvas.save();
                            canvas.rotate(rotation, cx, cy);
                        }
                        if (alpha != 0) {
                            paint.setAlpha(alpha);
                            if (packCancelShape != null && (nextIcon == ICON_CANCEL || nextIcon == ICON_CANCEL_FILL)) {
                                packIconRenderer.draw(canvas, packCancelShape, paint, cx, cy, packSize * currentProgress2,
                                        alpha / 255f * overrideAlpha, 0);
                            }
                            if (nextIcon == ICON_CANCEL_FILL) {
                                paint3.setAlpha(alpha);
                                rect.set(cx - AndroidUtilities.dp(3.5f), cy - AndroidUtilities.dp(3.5f), cx + AndroidUtilities.dp(3.5f), cy + AndroidUtilities.dp(3.5f));
                                if (packCancelShape == null) {
                                canvas.drawRoundRect(rect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), paint3);

                                }
                                paint.setAlpha((int) (alpha * 0.15f));
                                int diff = AndroidUtilities.dp(isMini ? 2 : 4);
                                rect.set(bounds.left + diff, bounds.top + diff, bounds.right - diff, bounds.bottom - diff);
                                packIconRenderer.drawProgressArc(canvas, rect, 0, 360, paint, progressSelection,
                                        progressSelection == NimarkoConfig.ICON_REPLACE_NONE ? 1f : overrideAlpha);
                                paint.setAlpha(alpha);
                            } else if (packCancelShape == null) {
                                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
                                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint);
                            }
                        }
                        if (rotation != 0) {
                            canvas.restore();
                        }
                    }
                }
            } else {
                y1 = yStart;
                y2 = yEnd;
                x1 = cx - AndroidUtilities.dp(8) * scale;
                x2 = cx + AndroidUtilities.dp(8) * scale;
                y3 = y2 - AndroidUtilities.dp(8) * scale;
            }
            if (packDownload == null && y1 != y2) {
                canvas.drawLine(cx, y1, cx, y2, paint);
            }
            if (packDownload == null && x1 != cx) {
                canvas.drawLine(x1, y3, cx, y2, paint);
                canvas.drawLine(x2, y3, cx, y2, paint);
            }
            if (packDownload != null && packDownloadMotion && scale > 0 && y1 != y2) {
                packIconRenderer.draw(canvas, packDownload, paint, cx, (y1 + y2) / 2, packSize, paint.getAlpha() / 255f * overrideAlpha, 0,
                        Math.min(1, Math.max(paint.getStrokeWidth(), x2 - x1) / (AndroidUtilities.dp(16) * scale)),
                        Math.min(1, Math.max(0, y2 - y1) / (AndroidUtilities.dp(18) * scale)));
            }
        }

        if (currentIcon == ICON_CANCEL || currentIcon == ICON_CANCEL_FILL || currentIcon == ICON_NONE && (nextIcon == ICON_CANCEL_FILL || nextIcon == ICON_CANCEL)) {
            applyShaderMatrix(false);
            float d;
            float rotation;
            float iconScale = 1.0f, progressScale = 1.0f;
            float iconScaleX = 0, progressScaleX = 0;
            float iconScaleY = 0, progressScaleY = 0;
            int alpha;
            if (nextIcon == ICON_DOWNLOAD) {
                if (transitionProgress <= DOWNLOAD_TO_CANCEL_STAGE3 + DOWNLOAD_TO_CANCEL_STAGE2) {
                    float progress = transitionProgress / (DOWNLOAD_TO_CANCEL_STAGE3 + DOWNLOAD_TO_CANCEL_STAGE2);
                    float backProgress = 1.0f - progress;
                    d = AndroidUtilities.dp(7) * backProgress * scale;
                    alpha = (int) (255 * backProgress);
                } else {
                    d = 0;
                    alpha = 0;
                }
                rotation = 0;
            } else if (nextIcon == ICON_UPDATE || nextIcon == ICON_PLAY || nextIcon == ICON_PAUSE || nextIcon == ICON_FILE || nextIcon == ICON_GIF || nextIcon == ICON_SECRETCHECK || nextIcon == ICON_FIRE || nextIcon == ICON_CHECK) {
                float progress;
                float backProgress;
                if (nextIcon == ICON_CHECK) {
                    progress = Math.min(1.0f, transitionProgress / CANCEL_TO_CHECK_STAGE1);
                } else {
                    progress = transitionProgress;
                }
                backProgress = 1.0f - progress;
                rotation = 0;
                progressScale = 1.0f;
                iconScale = 1.0f - progress;
                iconScaleX = progressScaleX = bounds.centerX();
                iconScaleY = progressScaleY = bounds.centerY();
                d = AndroidUtilities.dp(7) * backProgress * scale;
                alpha = (int) (255 * Math.min(1.0f, backProgress * 2.0f));
            } else if (nextIcon == ICON_NONE) {
                float progress = transitionProgress;
                float backProgress = 1.0f - progress;
                d = AndroidUtilities.dp(7) * scale;
                alpha = (int) (255 * backProgress);
                rotation = 0;
                iconScale = backProgress;
                progressScale = 1f;
                if (currentIcon == ICON_CANCEL_FILL) {
                    iconScaleX = progressScaleX = bounds.left;
                    iconScaleY = progressScaleY = bounds.top;
                } else {
                    iconScaleX = progressScaleX = bounds.centerX();
                    iconScaleY = progressScaleY = bounds.centerY();
                }
            } else if (nextIcon == ICON_CANCEL_FILL || nextIcon == ICON_CANCEL) {
                float progress = transitionProgress;
                float backProgress = 1.0f - progress;
                if (currentIcon == ICON_NONE) {
                    rotation = 0;
                    progressScale = 1.0f;
                    iconScale = progress;
                } else {
                    rotation = 45 * backProgress;
                    iconScale = progressScale = 1.0f;
                }
                d = AndroidUtilities.dp(7) * scale;
                alpha = (int) (255 * progress);
                if (nextIcon == ICON_CANCEL_FILL) {
                    iconScaleX = progressScaleX = bounds.left;
                    iconScaleY = progressScaleY = bounds.top;
                } else {
                    iconScaleX = progressScaleX = bounds.centerX();
                    iconScaleY = progressScaleY = bounds.centerY();
                }
            } else {
                rotation = 0;
                d = AndroidUtilities.dp(7) * scale;
                alpha = 255;
            }
            if (iconScale != 1.0f) {
                canvas.save();
                canvas.scale(iconScale, iconScale, iconScaleX, iconScaleY);
            }
            if (rotation != 0) {
                canvas.save();
                canvas.rotate(rotation, cx, cy);
            }
            if (alpha != 0) {
                paint.setAlpha((int) (alpha * overrideAlpha));
                if (packCancelShape != null && packCancelMotion && scale > 0) {
                    float cancelScale = currentIcon == ICON_CANCEL_FILL || nextIcon == ICON_CANCEL_FILL
                            ? 1 : d / (AndroidUtilities.dp(7) * scale);
                    packIconRenderer.draw(canvas, packCancelShape, paint, cx, cy, packSize * cancelScale,
                            paint.getAlpha() / 255f, 0);
                }
                if (currentIcon == ICON_CANCEL_FILL || nextIcon == ICON_CANCEL_FILL) {
                    paint3.setAlpha((int) (alpha * overrideAlpha));
                    rect.set(cx - AndroidUtilities.dp(3.5f), cy - AndroidUtilities.dp(3.5f), cx + AndroidUtilities.dp(3.5f), cy + AndroidUtilities.dp(3.5f));
                    if (packCancelShape == null) {
                    canvas.drawRoundRect(rect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), paint3);
                    }
                } else if (packCancelShape == null) {
                    canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
                    canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint);
                }
            }
            if (rotation != 0) {
                canvas.restore();
            }
            if (iconScale != 1.0f) {
                canvas.restore();
            }
            if (progressScale != 1.0f) {
                canvas.save();
                canvas.scale(progressScale, progressScale, progressScaleX, progressScaleY);
            }
            if ((currentIcon == ICON_CANCEL || currentIcon == ICON_CANCEL_FILL || currentIcon == ICON_NONE && (nextIcon == ICON_CANCEL_FILL || nextIcon == ICON_CANCEL)) && alpha != 0) {
                float rad = Math.max(4, 360 * animatedDownloadProgress);
                int diff = AndroidUtilities.dp(isMini ? 2 : 4);
                rect.set(bounds.left + diff, bounds.top + diff, bounds.right - diff, bounds.bottom - diff);

                if (currentIcon == ICON_CANCEL_FILL || currentIcon == ICON_NONE && nextIcon == ICON_CANCEL_FILL) {
                    paint.setAlpha((int) (alpha * 0.15f * overrideAlpha));
                    packIconRenderer.drawProgressArc(canvas, rect, 0, 360, paint, progressSelection);
                    paint.setAlpha((int) (alpha * overrideAlpha));
                }
                packIconRenderer.drawProgressArc(canvas, rect, downloadRadOffset, rad, paint, progressSelection);
            }
            if (progressScale != 1.0f) {
                canvas.restore();
            }
        } else if (currentIcon == ICON_EMPTY || nextIcon == ICON_EMPTY || currentIcon == ICON_CANCEL_PERCENT) {
            int alpha;
            if (nextIcon == ICON_NONE || nextIcon == ICON_CHECK) {
                float progress = transitionProgress;
                float backProgress = 1.0f - progress;
                alpha = (int) (255 * backProgress);
            } else {
                alpha = 255;
            }

            if (alpha != 0) {
                applyShaderMatrix(false);
                paint.setAlpha((int) (alpha * overrideAlpha));
                float rad = Math.max(4, 360 * animatedDownloadProgress);
                int diff = AndroidUtilities.dp(isMini ? 2 : 4);
                rect.set(bounds.left + diff, bounds.top + diff, bounds.right - diff, bounds.bottom - diff);
                packIconRenderer.drawProgressArc(canvas, rect, downloadRadOffset, rad, paint, progressSelection);
            }
        }

        Drawable nextDrawable = null;
        Drawable previousDrawable = null;
        Path[] nextPath = null;
        Path[] previousPath = null;

        float drawableScale;
        float previowsDrawableScale;
        if (currentIcon == nextIcon) {
            drawableScale = previowsDrawableScale = 1.0f;
        } else if (currentIcon == ICON_NONE || currentIcon == ICON_CANCEL || currentIcon == ICON_CANCEL_FILL) {
            drawableScale = transitionProgress;
            previowsDrawableScale = 1.0f - transitionProgress;
        } else {
            drawableScale = Math.min(1.0f, transitionProgress / 0.5f);
            previowsDrawableScale = Math.max(0.0f, 1.0f - transitionProgress / 0.5f);
        }

        if (packUpdate == null && nextIcon == ICON_UPDATE) {
            nextPath = Theme.chat_updatePath;
        } else if (packUpdate == null && currentIcon == ICON_UPDATE) {
            previousPath = Theme.chat_updatePath;
        }
        if (packFile == null && nextIcon == ICON_FILE) {
            nextPath = Theme.chat_filePath;
        } else if (packFile == null && currentIcon == ICON_FILE) {
            previousPath = Theme.chat_filePath;
        }
        if (nextIcon == ICON_FIRE) {
            nextDrawable = Theme.chat_flameIcon;
        } else if (currentIcon == ICON_FIRE) {
            previousDrawable = Theme.chat_flameIcon;
        }
        if (nextIcon == ICON_GIF) {
            nextDrawable = Theme.chat_gifIcon;
        } else if (currentIcon == ICON_GIF) {
            previousDrawable = Theme.chat_gifIcon;
        }


        if (packCheck == null && (currentIcon == ICON_SECRETCHECK || nextIcon == ICON_SECRETCHECK)) {
            applyShaderMatrix(false);
            paint.setAlpha(currentIcon == nextIcon ? 255 : (int) (transitionProgress * 255));
            int y = cy + AndroidUtilities.dp(7);
            int x = cx - AndroidUtilities.dp(3);
            if (currentIcon != nextIcon) {
                canvas.save();
                canvas.scale(transitionProgress, transitionProgress, cx, cy);
            }
            canvas.drawLine(x - AndroidUtilities.dp(6), y - AndroidUtilities.dp(6), x, y, paint);
            canvas.drawLine(x, y, x + AndroidUtilities.dp(12), y - AndroidUtilities.dp(12), paint);
            if (currentIcon != nextIcon) {
                canvas.restore();
            }
        }
        if (currentIcon == ICON_CANCEL_NOPROFRESS || nextIcon == ICON_CANCEL_NOPROFRESS) {
            applyShaderMatrix(false);
            float transition;
            if (currentIcon == nextIcon) {
                transition = 1.0f;
            } else if (nextIcon == ICON_CANCEL_PERCENT) {
                transition = transitionProgress;
            } else {
                transition = 1.0f - transitionProgress;
            }

            paint.setAlpha(currentIcon == nextIcon ? 255 : (int) (transition * 255));
            int y = cy + AndroidUtilities.dp(7);
            int x = cx - AndroidUtilities.dp(3);
            if (currentIcon != nextIcon) {
                canvas.save();
                canvas.scale(transition, transition, cx, cy);
            }

            float d = AndroidUtilities.dp(7) * scale;
            if (packCancel == null) {
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint);

            }
            if (currentIcon != nextIcon) {
                canvas.restore();
            }
        }
        if (currentIcon == ICON_CANCEL_PERCENT || nextIcon == ICON_CANCEL_PERCENT) {
            applyShaderMatrix(false);
            float transition;
            if (currentIcon == nextIcon) {
                transition = 1.0f;
            } else if (nextIcon == ICON_CANCEL_PERCENT) {
                transition = transitionProgress;
            } else {
                transition = 1.0f - transitionProgress;
            }

            textPaint.setAlpha((int) (transition * 255 * overrideAlpha));
            int y = cy + AndroidUtilities.dp(5);
            int x = cx - percentStringWidth / 2;
            if (currentIcon != nextIcon) {
                canvas.save();
                canvas.scale(transition, transition, cx, cy);
            }
            int newPercent = (int) (animatedDownloadProgress * 100);
            if (percentString == null || newPercent != lastPercent) {
                lastPercent = newPercent;
                percentString = String.format("%d%%", lastPercent);
                percentStringWidth = (int) Math.ceil(textPaint.measureText(percentString));
            }
            canvas.drawText(percentString, x, y, textPaint);
            if (currentIcon != nextIcon) {
                canvas.restore();
            }
        }
        if (!packPlayback && (currentIcon == ICON_PLAY || currentIcon == ICON_PAUSE || nextIcon == ICON_PLAY || nextIcon == ICON_PAUSE)) {
            float p;
            if (currentIcon == ICON_PLAY && nextIcon == ICON_PAUSE || currentIcon == ICON_PAUSE && nextIcon == ICON_PLAY) {
                if (animatingTransition) {
                    if (nextIcon == ICON_PLAY) {
                        p = 1.0f - transitionProgress;
                    } else {
                        p = transitionProgress;
                    }
                } else {
                    p = nextIcon == ICON_PAUSE ? 1.0f : 0.0f;
                }
            } else {
                p = currentIcon == ICON_PAUSE ? 1.0f : 0.0f;
            }

            if (nextIcon != ICON_PLAY && nextIcon != ICON_PAUSE || currentIcon != ICON_PLAY && currentIcon != ICON_PAUSE) {
                if (nextIcon == ICON_NONE) {
                    paint2.setAlpha((int) (255 * (1.0f - transitionProgress)));
                } else {
                    paint2.setAlpha(currentIcon == nextIcon ? 255 : (int) (transitionProgress * 255));
                }
            } else {
                paint2.setAlpha(255);
            }

            applyShaderMatrix(true);

            canvas.save();
            canvas.translate(bounds.centerX() + AndroidUtilities.dp(1) * (1.0f - p), bounds.centerY());
            float ms = 500.0f * p;
            float rotation = currentIcon == ICON_PAUSE ? 90 : 0;
            if (currentIcon == ICON_PLAY && nextIcon == ICON_PAUSE) {
                if (ms < 384) {
                    rotation = 95 * CubicBezierInterpolator.EASE_BOTH.getInterpolation(ms / 384);
                } else if (ms < 484) {
                    rotation = 95 - 5 * CubicBezierInterpolator.EASE_BOTH.getInterpolation((ms - 384) / 100.0f);
                } else {
                    rotation = 90;
                }
                ms += 100;
            } else if (currentIcon == ICON_PAUSE && nextIcon == ICON_PLAY) {
                if (ms < 100) {
                    rotation = -5 * CubicBezierInterpolator.EASE_BOTH.getInterpolation(ms / 100.0f);
                } else if (ms < 484) {
                    rotation = -5 + 95 * CubicBezierInterpolator.EASE_BOTH.getInterpolation((ms - 100) / 384);
                } else {
                    rotation = 90;
                }
            }
            canvas.rotate(rotation);
            if (currentIcon != ICON_PLAY && currentIcon != ICON_PAUSE || currentIcon == ICON_NONE) {
                canvas.scale(drawableScale, drawableScale);
            }
            Theme.playPauseAnimator.draw(canvas, paint2, ms);
            canvas.scale(1.0f, -1.0f);
            Theme.playPauseAnimator.draw(canvas, paint2, ms);

            canvas.restore();
        }
        if (packCheck == null && (currentIcon == ICON_CHECK || nextIcon == ICON_CHECK)) {
            applyShaderMatrix(false);
            float progress1;
            float progress2;
            if (currentIcon != ICON_CHECK) {
                if (transitionProgress > CANCEL_TO_CHECK_STAGE1) {
                    float progress = (transitionProgress - CANCEL_TO_CHECK_STAGE1) / CANCEL_TO_CHECK_STAGE2;
                    progress1 = 1.0f - Math.min(1.0f, progress / 0.5f);
                    progress2 = progress > 0.5f ? ((progress - 0.5f) / 0.5f) : 0.0f;
                } else {
                    progress1 = 1.0f;
                    progress2 = 0.0f;
                }
                paint.setAlpha(255);
            } else {
                progress1 = 0.0f;
                progress2 = 1.0f;
                if (nextIcon != ICON_CHECK) {
                    paint.setAlpha((int) (255 * (1.0f - transitionProgress)));
                } else {
                    paint.setAlpha(255);
                }
            }
            int y = cy + AndroidUtilities.dp(7);
            int x = cx - AndroidUtilities.dp(3);
            if (progress1 < 1) {
                canvas.drawLine(x - AndroidUtilities.dp(6), y - AndroidUtilities.dp(6), x - AndroidUtilities.dp(6) * progress1, y - AndroidUtilities.dp(6) * progress1, paint);
            }
            if (progress2 > 0) {
                canvas.drawLine(x, y, x + AndroidUtilities.dp(12) * progress2, y - AndroidUtilities.dp(12) * progress2, paint);
            }
        }

        if (previousDrawable != null && previousDrawable != nextDrawable) {
            int w = (int) (previousDrawable.getIntrinsicWidth() * previowsDrawableScale);
            int h = (int) (previousDrawable.getIntrinsicHeight() * previowsDrawableScale);
            previousDrawable.setColorFilter(colorFilter);
            previousDrawable.setAlpha((int) (overrideAlpha * (currentIcon == nextIcon ? 255 : (int) ((1.0f - transitionProgress) * 255))));
            previousDrawable.setBounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            previousDrawable.draw(canvas);
        }
        if (nextDrawable != null) {
            int w = (int) (nextDrawable.getIntrinsicWidth() * drawableScale);
            int h = (int) (nextDrawable.getIntrinsicHeight() * drawableScale);
            nextDrawable.setColorFilter(colorFilter);
            nextDrawable.setAlpha((int) (overrideAlpha * (currentIcon == nextIcon ? 255 : (int) (transitionProgress * 255))));
            nextDrawable.setBounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            nextDrawable.draw(canvas);
        }

        if (previousPath != null && previousPath != nextPath) {
            int size = AndroidUtilities.dp(24);
            paint2.setStyle(Paint.Style.FILL_AND_STROKE);
            paint2.setAlpha(currentIcon == nextIcon ? 255 : (int) ((1.0f - transitionProgress) * 255));
            applyShaderMatrix(true);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(previowsDrawableScale, previowsDrawableScale);
            canvas.translate(-size / 2, -size / 2);
            if (previousPath[0] != null) {
                canvas.drawPath(previousPath[0], paint2);
            }
            if (previousPath[1] != null) {
                canvas.drawPath(previousPath[1], backPaint);
            }
            canvas.restore();
        }
        if (nextPath != null) {
            int size = AndroidUtilities.dp(24);
            int alpha = currentIcon == nextIcon ? 255 : (int) (transitionProgress * 255);
            paint2.setStyle(Paint.Style.FILL_AND_STROKE);
            paint2.setAlpha(alpha);
            applyShaderMatrix(true);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(drawableScale, drawableScale);
            canvas.translate(-size / 2, -size / 2);
            if (nextPath[0] != null) {
                canvas.drawPath(nextPath[0], paint2);
            }
            if (nextPath.length >= 3 && nextPath[2] != null) {
                canvas.drawPath(nextPath[2], paint);
            }
            if (nextPath[1] != null) {
                if (alpha != 255) {
                    int backgroundAlpha = backPaint.getAlpha();
                    backPaint.setAlpha((int) (backgroundAlpha * (alpha / 255f)));
                    canvas.drawPath(nextPath[1], backPaint);
                    backPaint.setAlpha(backgroundAlpha);
                } else {
                    canvas.drawPath(nextPath[1], backPaint);
                }
            }
            canvas.restore();
        }

        NimarkoIconResources.PreparedIcon previousPack = packIcon(currentIcon, packPlay, packPause, packDownload, packCancel, packFile, packUpdate, packCheck, packStop);
        NimarkoIconResources.PreparedIcon nextPack = packIcon(nextIcon, packPlay, packPause, packDownload, packCancel, packFile, packUpdate, packCheck, packStop);
        if (currentIcon == ICON_DOWNLOAD && packDownloadMotion ||
                (currentIcon == ICON_CANCEL || currentIcon == ICON_CANCEL_FILL) && packCancelMotion) {
            previousPack = null;
        }
        if (nextIcon == ICON_DOWNLOAD && packDownloadMotion ||
                (nextIcon == ICON_CANCEL || nextIcon == ICON_CANCEL_FILL) &&
                        (currentIcon == ICON_DOWNLOAD || packCancelMotion &&
                                (currentIcon == ICON_CANCEL || currentIcon == ICON_CANCEL_FILL || currentIcon == ICON_NONE))) {
            nextPack = null;
        }
        if (previousPack != null || nextPack != null) {
            applyShaderMatrix(false);
        }
        if ((currentIcon == ICON_PLAY || currentIcon == ICON_PAUSE) && (nextIcon == ICON_PLAY || nextIcon == ICON_PAUSE) && packPlayback) {
            float progress = currentIcon == nextIcon ? (nextIcon == ICON_PAUSE ? 1 : 0)
                    : nextIcon == ICON_PAUSE ? transitionProgress : 1 - transitionProgress;
            packIconRenderer.drawPlayPause(canvas, packPlay, packPause, paint2,
                    cx + AndroidUtilities.dp(1) * (1 - progress), cy, packSize, progress, overrideAlpha);
        } else if (currentIcon != nextIcon && previousPack != null && previousPack == nextPack) {
            packIconRenderer.draw(canvas, nextPack, paint, cx, cy, packSize, overrideAlpha, 0);
        } else if (currentIcon == nextIcon) {
            packIconRenderer.draw(canvas, nextPack, paint, cx, cy, packSize, overrideAlpha, 0);
        } else {
            float progress = nextIcon == ICON_CHECK ? Math.min(1, transitionProgress / CANCEL_TO_CHECK_STAGE1) : transitionProgress;
            float previousScale = nextIcon == ICON_NONE && currentIcon != ICON_CANCEL && currentIcon != ICON_CANCEL_FILL
                    ? 1 : 1 - progress;
            if (currentIcon == ICON_FILE || currentIcon == ICON_UPDATE) {
                previousScale = previowsDrawableScale;
            } else if (currentIcon == ICON_CHECK || currentIcon == ICON_SECRETCHECK) {
                previousScale = 1;
            }
            float nextProgress = nextIcon == ICON_CHECK
                    ? Math.max(0, (transitionProgress - CANCEL_TO_CHECK_STAGE1) / CANCEL_TO_CHECK_STAGE2) : transitionProgress;
            float nextScale = nextIcon == ICON_FILE || nextIcon == ICON_UPDATE ? drawableScale : nextProgress;
            if (nextIcon == ICON_PLAY || nextIcon == ICON_PAUSE) {
                nextScale = drawableScale;
            }
            packIconRenderer.draw(canvas, previousPack, paint, cx + (currentIcon == ICON_PLAY ? AndroidUtilities.dp(1) : 0), cy, packSize * previousScale,
                    overrideAlpha * (1 - progress), currentIcon == ICON_PLAY || currentIcon == ICON_PAUSE ? 0 : -45 * progress);
            packIconRenderer.draw(canvas, nextPack, paint, cx + (nextIcon == ICON_PLAY ? AndroidUtilities.dp(1) : 0), cy, packSize * nextScale,
                    overrideAlpha * nextProgress, nextIcon == ICON_PLAY || nextIcon == ICON_PAUSE ? 0 : 45 * (1 - nextProgress));
        }
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastAnimationTime;
        if (dt > 17) {
            dt = 17;
        }
        lastAnimationTime = newTime;

        if (currentIcon == ICON_CANCEL || currentIcon == ICON_CANCEL_FILL || currentIcon == ICON_NONE && nextIcon == ICON_CANCEL_FILL || currentIcon == ICON_EMPTY || currentIcon == ICON_CANCEL_PERCENT) {
            downloadRadOffset += 360 * dt / 2500.0f;
            downloadRadOffset = getCircleValue(downloadRadOffset);

            if (nextIcon != ICON_DOWNLOAD) {
                float progressDiff = downloadProgress - downloadProgressAnimationStart;
                if (progressDiff > 0) {
                    downloadProgressTime += dt;
                    if (downloadProgressTime >= 200.0f) {
                        animatedDownloadProgress = downloadProgress;
                        downloadProgressAnimationStart = downloadProgress;
                        downloadProgressTime = 0;
                    } else {
                        animatedDownloadProgress = downloadProgressAnimationStart + progressDiff * interpolator.getInterpolation(downloadProgressTime / 200.0f);
                    }
                }
            }
            invalidateSelf();
        }

        if (animatingTransition) {
            if (transitionProgress < 1.0f) {
                transitionProgress += dt / transitionAnimationTime;
                if (transitionProgress >= 1.0f) {
                    currentIcon = nextIcon;
                    transitionProgress = 1.0f;
                    animatingTransition = false;
                }
                invalidateSelf();
            }
        }
        if (saveCount >= 1) {
            canvas.restoreToCount(saveCount);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(48);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(48);
    }

    @Override
    public int getMinimumWidth() {
        return AndroidUtilities.dp(48);
    }

    @Override
    public int getMinimumHeight() {
        return AndroidUtilities.dp(48);
    }
}
