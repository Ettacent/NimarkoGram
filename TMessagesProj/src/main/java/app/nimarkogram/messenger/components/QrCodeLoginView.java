package app.nimarkogram.messenger.components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.SlideView;

import java.util.HashMap;

public abstract class QrCodeLoginView extends SlideView {
    private final QrRenderView qrRenderView;
    private final TextView subtitleView;
    private final TextView titleView;

    public QrCodeLoginView(Context context) {
        super(context);
        setOrientation(1);
        setGravity(17);

        TextView textView = new TextView(context);
        this.titleView = textView;
        textView.setTextSize(1, 18.0f);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setText(LocaleController.getString(R.string.LoginQrTitle));
        textView.setGravity(17);
        textView.setLineSpacing(AndroidUtilities.dp(2.0f), 1.0f);
        addView(textView, LayoutHelper.createLinear(-1, -2, 1, 32, 16, 32, 0));

        TextView textView2 = new TextView(context);
        this.subtitleView = textView2;
        textView2.setTextSize(1, 14.0f);
        textView2.setGravity(1);
        textView2.setLineSpacing(AndroidUtilities.dp(2.0f), 1.0f);
        textView2.setText(LocaleController.getString(R.string.LoginQrSubtitle));
        addView(textView2, LayoutHelper.createLinear(-2, -2, 1, 12, 8, 12, 0));

        QrRenderView renderView = new QrRenderView(context);
        this.qrRenderView = renderView;
        addView(renderView, LayoutHelper.createLinear(280, 280, 1, 30, 30, 30, 30));
    }

    public void clear() {
        this.qrRenderView.clear();
    }

    public void clear(boolean z) {
        this.qrRenderView.clear(z);
    }

    @Override
    public String getHeaderName() {
        return LocaleController.getString(R.string.LoginQrTitle);
    }

    @Override
    public boolean needBackButton() {
        return true;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.qrRenderView.dispose();
    }

    public void setData(String str) {
        this.qrRenderView.setData(str);
    }

    @Override
    public void updateColors() {
        this.titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        this.subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        this.qrRenderView.updateColors();
    }

    public static class QrRenderView extends View {
        private final int crossfadeWidthDp = 140;
        private final Paint paint = new Paint(1);
        private final AnimatedFloat contentBitmapAlpha;
        private final Paint crossfadeFromPaint;
        private final Paint crossfadeToPaint;
        private final Path clipPath = new Path();
        private final Path qrAreaClipPath = new Path();

        private Bitmap contentBitmap;
        private Bitmap oldContentBitmap;
        private boolean firstPrepare = true;
        private boolean loadingVisible = true;
        private int transitionDirection = 0;

        private Integer hadHeight;
        private Integer hadWidth;
        private String hadLink;
        private String link;
        private long prepareGeneration;
        private boolean disposed;

        private RLottieDrawable loadingMatrix;
        private Bitmap qrLogo;
        private int qrLogoSize;

        public QrRenderView(Context context) {
            super(context);
            this.contentBitmapAlpha = new AnimatedFloat(1.0f, this, 0L, 2000L, CubicBezierInterpolator.EASE_OUT_QUINT);
            Paint paint = new Paint(1);
            this.crossfadeFromPaint = paint;
            Paint paint2 = new Paint(1);
            this.crossfadeToPaint = paint2;
            Shader.TileMode tileMode = Shader.TileMode.CLAMP;
            paint.setShader(new LinearGradient(0.0f, 0.0f, 0.0f, AndroidUtilities.dp(140.0f), new int[]{-1, 0}, new float[]{0.0f, 1.0f}, tileMode));
            PorterDuff.Mode mode = PorterDuff.Mode.DST_OUT;
            paint.setXfermode(new PorterDuffXfermode(mode));
            paint2.setShader(new LinearGradient(0.0f, 0.0f, 0.0f, AndroidUtilities.dp(140.0f), new int[]{0, -1}, new float[]{0.0f, 1.0f}, tileMode));
            paint2.setXfermode(new PorterDuffXfermode(mode));
            clear();
        }

        private void drawFinderPatterns(Canvas canvas, int i, int i2) {
            float f;
            float f2;
            float f3 = i2;
            float f4 = 2.0f * f3;
            float f5 = 7.0f * f3;
            float f6 = (f5 / 3.0f) * 0.75f;
            float f7 = (f5 / 4.0f) * 0.75f;
            float f8 = ((5.0f * f3) / 4.0f) * 0.75f;
            for (int i3 = 0; i3 < 3; i3++) {
                if (i3 == 0) {
                    f = 16.0f;
                    f2 = 16.0f;
                } else if (i3 == 1) {
                    f2 = 16.0f;
                    f = (i - f5) - 16.0f;
                } else {
                    f = 16.0f;
                    f2 = (i - f5) - 16.0f;
                }
                this.paint.setColor(-16777216);
                float f9 = f + f5;
                float f10 = f2 + f5;
                canvas.drawRoundRect(f, f2, f9, f10, f6, f6, this.paint);
                this.paint.setColor(-1);
                float f11 = 6.0f * f3;
                canvas.drawRoundRect(f + f3, f2 + f3, f + f11, f2 + f11, f7, f7, this.paint);
                this.paint.setColor(-16777216);
                canvas.drawRoundRect(f + f4, f2 + f4, f9 - f4, f10 - f4, f8, f8, this.paint);
            }
        }

        private void drawLoading(Canvas canvas, int i, int i2, float f) {
            RLottieDrawable rLottieDrawable = this.loadingMatrix;
            if (rLottieDrawable == null) {
                RLottieDrawable rLottieDrawable2 = new RLottieDrawable(R.raw.qr_matrix, "qr_matrix", AndroidUtilities.dp(200.0f), AndroidUtilities.dp(200.0f));
                this.loadingMatrix = rLottieDrawable2;
                rLottieDrawable2.setMasterParent(this);
                this.loadingMatrix.setAutoRepeat(1);
                this.loadingMatrix.setColorFilter(-16777216, PorterDuff.Mode.MULTIPLY);
                this.loadingMatrix.start();
            } else if (!rLottieDrawable.isRunning()) {
                this.loadingMatrix.start();
            }
            int width = getWidth();
            int iRound = Math.round(16.0f * f);
            int i3 = width - iRound;
            this.loadingMatrix.setBounds(iRound, iRound, i3, i3);
            this.loadingMatrix.setAlpha(255);
            this.loadingMatrix.draw(canvas);
            float f2 = i;
            int iRound2 = Math.round(((i2 - 32) / 4.65f) / f2);
            if (iRound2 % 2 != 1) {
                iRound2++;
            }
            int i4 = iRound2 * i;
            int i5 = i4 - 24;
            int i6 = (i2 - i4) / 2;
            int i7 = (i2 - i5) / 2;
            canvas.save();
            canvas.scale(f, f);
            drawFinderPatterns(canvas, i2, i);
            this.paint.setColor(-1);
            float f3 = ((f2 * 7.0f) / 4.0f) * 0.75f;
            float f4 = i6;
            float f5 = i6 + i4;
            canvas.drawRoundRect(f4, f4, f5, f5, f3, f3, this.paint);
            Bitmap bitmap = this.qrLogo;
            if (bitmap == null) {
                this.qrLogo = SvgHelper.getBitmap(AndroidUtilities.readRes(null, R.raw.qr_logo), i5, i5, false);
                this.qrLogoSize = i5;
            } else if (this.qrLogoSize != i5) {
                bitmap.recycle();
                this.qrLogo = SvgHelper.getBitmap(AndroidUtilities.readRes(null, R.raw.qr_logo), i5, i5, false);
                this.qrLogoSize = i5;
            }
            Bitmap bitmap2 = this.qrLogo;
            if (bitmap2 != null) {
                float f6 = i7;
                canvas.drawBitmap(bitmap2, f6, f6, (Paint) null);
            }
            canvas.restore();
        }

        private Paint getNewLayerMaskPaint() {
            return this.transitionDirection == 1 ? this.crossfadeToPaint : this.crossfadeFromPaint;
        }

        private Paint getOldLayerMaskPaint() {
            return this.transitionDirection == 1 ? this.crossfadeFromPaint : this.crossfadeToPaint;
        }

        private float getScanLineY(float f, int i, float f2) {
            float f3;
            float f4;
            if (this.transitionDirection == 1) {
                f3 = -f2;
                f4 = (i + f2) * f;
            } else {
                f3 = -f2;
                f4 = (i + f2) * (1.0f - f);
            }
            return f3 + f4;
        }

        private void prepareContent(final int i, final int i2, final String str, final long generation) {
            Bitmap bitmapEncode;
            if (i == 0 || i2 == 0) {
                return;
            }
            if (TextUtils.isEmpty(str)) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!this.disposed
                            && this.prepareGeneration == generation
                            && TextUtils.equals(this.link, str)) {
                        this.firstPrepare = false;
                        Bitmap bitmap = this.contentBitmap;
                        if (bitmap != null) {
                            this.contentBitmap = null;
                            this.contentBitmapAlpha.set(0.0f, true);
                            Bitmap bitmap2 = this.oldContentBitmap;
                            if (bitmap2 != null) {
                                bitmap2.recycle();
                            }
                            this.oldContentBitmap = bitmap;
                            invalidate();
                        }
                    }
                });
                return;
            }
            HashMap<EncodeHintType, Object> map = new HashMap<>();
            map.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            map.put(EncodeHintType.MARGIN, 0);
            int iMax = (Math.max(1, i / 37) * 37) + 32;
            try {
                bitmapEncode = new QRCodeWriter().encode(str, iMax, iMax, map, null, 0.75f, 0, -16777216);
            } catch (Exception e) {
                FileLog.e(e);
                bitmapEncode = null;
            }
            final Bitmap bitmap = bitmapEncode;
            if (bitmap == null) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!this.disposed
                        && this.prepareGeneration == generation
                        && TextUtils.equals(this.link, str)) {
                    this.hadWidth = i;
                    this.hadHeight = i2;
                    this.hadLink = str;
                    applyContent(bitmap);
                    return;
                }
                if (bitmap.isRecycled()) {
                    return;
                }
                bitmap.recycle();
            });
        }

        private void applyContent(Bitmap bitmap) {
            boolean z = this.firstPrepare;
            boolean z2 = z && this.loadingVisible;
            Bitmap bitmap2 = this.contentBitmap;
            this.contentBitmap = bitmap;
            if (!z || z2) {
                this.transitionDirection = 0;
                this.contentBitmapAlpha.set(0.0f, true);
            }
            this.firstPrepare = false;
            Bitmap bitmap3 = this.oldContentBitmap;
            if (bitmap3 != null) {
                bitmap3.recycle();
            }
            this.oldContentBitmap = bitmap2;
            this.loadingVisible = z2;
            invalidate();
        }

        public void clear(boolean z) {
            RLottieDrawable rLottieDrawable;
            this.prepareGeneration++;
            this.link = null;
            this.hadLink = null;
            this.hadWidth = null;
            this.hadHeight = null;
            this.firstPrepare = true;
            Bitmap bitmap = this.oldContentBitmap;
            if (bitmap != null) {
                bitmap.recycle();
                this.oldContentBitmap = null;
            }
            Bitmap bitmap2 = this.contentBitmap;
            if (bitmap2 == null || !z) {
                if (bitmap2 != null) {
                    bitmap2.recycle();
                    this.contentBitmap = null;
                }
                this.loadingVisible = z;
                this.contentBitmapAlpha.set(1.0f, true);
            } else {
                this.oldContentBitmap = bitmap2;
                this.contentBitmap = null;
                this.loadingVisible = true;
                this.transitionDirection = 1;
                this.contentBitmapAlpha.set(0.0f, true);
                this.contentBitmapAlpha.set(1.0f);
            }
            if (!z && (rLottieDrawable = this.loadingMatrix) != null && rLottieDrawable.isRunning()) {
                this.loadingMatrix.stop();
            }
            invalidate();
        }

        public void clear() {
            clear(true);
        }

        public void dispose() {
            this.disposed = true;
            this.prepareGeneration++;
            this.hadLink = null;
            this.hadWidth = null;
            this.hadHeight = null;
            RLottieDrawable rLottieDrawable = this.loadingMatrix;
            if (rLottieDrawable != null) {
                rLottieDrawable.stop();
                this.loadingMatrix.recycle(false);
                this.loadingMatrix = null;
            }
            Bitmap bitmap = this.qrLogo;
            if (bitmap != null) {
                bitmap.recycle();
                this.qrLogo = null;
                this.qrLogoSize = 0;
            }
            Bitmap bitmap2 = this.contentBitmap;
            if (bitmap2 != null) {
                bitmap2.recycle();
                this.contentBitmap = null;
            }
            Bitmap bitmap3 = this.oldContentBitmap;
            if (bitmap3 != null) {
                bitmap3.recycle();
                this.oldContentBitmap = null;
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (this.disposed) {
                this.disposed = false;
                String currentLink = this.link;
                if (currentLink != null) {
                    setData(currentLink);
                } else {
                    invalidate();
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width == 0 || height == 0) {
                return;
            }

            float radius = AndroidUtilities.dp(12.0f);
            this.clipPath.rewind();
            this.clipPath.addRoundRect(new RectF(0, 0, width, height), radius, radius, Path.Direction.CW);

            float alpha = this.contentBitmapAlpha.set(this.contentBitmap != null ? 1.0f : 0.0f);

            if (this.loadingVisible && alpha < 1.0f) {
                canvas.save();
                canvas.clipPath(this.clipPath);
                float density = AndroidUtilities.density;
                int matrixModule = Math.max(1, Math.round(width / 37.0f));
                drawLoading(canvas, matrixModule, width, density <= 0.0f ? 1.0f : density);
                canvas.restore();
            }

            boolean hasNew = this.contentBitmap != null && !this.contentBitmap.isRecycled();
            boolean hasOld = this.oldContentBitmap != null && !this.oldContentBitmap.isRecycled();

            if (!hasNew && !hasOld) {
                return;
            }

            float scanLineHeight = AndroidUtilities.dp(this.crossfadeWidthDp);

            if (hasOld && alpha < 1.0f) {
                int layer = canvas.saveLayer(0, 0, width, height, null);
                drawBitmapFitted(canvas, this.oldContentBitmap, width, height);
                canvas.save();
                float y = getScanLineY(alpha, height, scanLineHeight);
                canvas.translate(0, y);
                canvas.drawRect(0, 0, width, scanLineHeight, getOldLayerMaskPaint());
                canvas.restore();
                canvas.restoreToCount(layer);
            }

            if (hasNew) {
                int layer = canvas.saveLayer(0, 0, width, height, null);
                drawBitmapFitted(canvas, this.contentBitmap, width, height);
                if (alpha < 1.0f) {
                    canvas.save();
                    float y = getScanLineY(alpha, height, scanLineHeight);
                    canvas.translate(0, y);
                    canvas.drawRect(0, 0, width, scanLineHeight, getNewLayerMaskPaint());
                    canvas.restore();
                }
                canvas.restoreToCount(layer);
            }
        }

        private void drawBitmapFitted(Canvas canvas, Bitmap bitmap, int width, int height) {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            this.qrAreaClipPath.rewind();
            float radius = AndroidUtilities.dp(12.0f);
            this.qrAreaClipPath.addRoundRect(new RectF(0, 0, width, height), radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(this.qrAreaClipPath);
            float scale = Math.min(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
            float dx = (width - bitmap.getWidth() * scale) / 2.0f;
            float dy = (height - bitmap.getHeight() * scale) / 2.0f;
            canvas.translate(dx, dy);
            canvas.scale(scale, scale);
            canvas.drawBitmap(bitmap, 0, 0, this.paint);
            canvas.restore();
        }

        public void setData(final String str) {
            this.link = str;
            final long generation = ++this.prepareGeneration;
            if (!TextUtils.isEmpty(str) && this.contentBitmap == null) {
                this.loadingVisible = true;
            }
            final int width = getWidth();
            final int height = getHeight();
            if (this.disposed) {
                invalidate();
                return;
            }
            if (!TextUtils.isEmpty(str)
                    && TextUtils.equals(str, this.hadLink)
                    && this.hadWidth != null
                    && this.hadHeight != null
                    && this.hadWidth == width
                    && this.hadHeight == height) {
                invalidate();
                return;
            }
            Utilities.themeQueue.postRunnable(() -> prepareContent(width, height, str, generation));
            invalidate();
        }

        public void updateColors() {
            invalidate();
        }
    }
}
