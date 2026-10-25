/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.QrShare;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.camera.CameraController;
import org.telegram.messenger.camera.CameraSessionWrapper;
import org.telegram.messenger.camera.CameraView;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.BasePermissionsActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PhotoAlbumPickerActivity;

import java.util.ArrayList;

@SuppressLint("ViewConstructor")
public final class QrScanOverlay extends FrameLayout {

    public interface Listener {
        void onScanned(String link);

        void onClosed(QrScanOverlay overlay);
    }

    private static final long STRETCH_MS = 390, CLOSE_MS = 330;
    private static final long OPEN_MS = STRETCH_MS;
    private static final CubicBezierInterpolator STRETCH = new CubicBezierInterpolator(0.05, 0.7, 0.1, 1.0);
    private static final CubicBezierInterpolator SHRINK = new CubicBezierInterpolator(0.3, 0.0, 0.1, 1.0);
    private static final long SCAN_INTERVAL = 150, FOUND_DELAY = 450;
    private static final int MAX_FRAME_SIDE = 720;
    private static final int FLAT_SHADE = 0x29000000, NO_BLUR_SHADE = 0x80000000;
    private static final int DIM_COLOR = 0x40000000;
    private static final int FOUND_COLOR = 0xFFFFDE07;

    private final Listener listener;
    private @Nullable CameraView cameraView;
    private @Nullable Object blurNode;

    private final RectF button = new RectF(), shape = new RectF();
    private final RectF closeRect = new RectF(), flashRect = new RectF(), galleryRect = new RectF(), frameRect = new RectF(), textureRect = new RectF();
    private final Path clip = new Path(), path = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG), foundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RimPainter rim = new RimPainter();
    private final Drawable scanIcon, flashIcon, galleryIcon;
    private final BackDrawable closeIcon = new BackDrawable(true);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private @Nullable String buttonLabel;
    private final TextPaint buttonLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public void setButtonLabel(@Nullable String label) {
        buttonLabel = label;
        buttonLabelPaint.setTypeface(AndroidUtilities.bold());
        buttonLabelPaint.setColor(0xFFFFFFFF);
        invalidate();
    }

    private boolean plainButton;
    private Utilities.CallbackReturn<String, Boolean> accept = QrScanOverlay::isTelegramLink;

    public void setPlainButton(boolean plain) {
        plainButton = plain;
        invalidate();
    }

    public void setAccept(Utilities.CallbackReturn<String, Boolean> accept) {
        this.accept = accept;
    }

    private @Nullable Runnable onGallery;

    public void setOnGallery(@Nullable Runnable onGallery) {
        this.onGallery = onGallery;
        requestLayout();
        invalidate();
    }

    @Nullable
    public static QrScanOverlay open(BaseFragment fragment, View button, Utilities.CallbackReturn<String, Boolean> accept, Utilities.Callback<String> onScanned, @Nullable Runnable onClosed) {
        final INavigationLayout layout = fragment.getParentLayout();
        final FrameLayout container = layout != null ? layout.getOverlayContainerView() : null;
        if (container == null || fragment.getParentActivity() == null) {
            return null;
        }
        final int[] c = new int[2], b = new int[2];
        container.getLocationInWindow(c);
        button.getLocationInWindow(b);
        final float side = Math.min(button.getWidth(), button.getHeight());
        final float cx = b[0] - c[0] + button.getWidth() / 2f, cy = b[1] - c[1] + button.getHeight() / 2f;
        final RectF from = new RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        final QrScanOverlay overlay = new QrScanOverlay(fragment.getParentActivity(), from, new RectF(), new Listener() {
            @Override
            public void onScanned(String link) {
                onScanned.run(link);
            }

            @Override
            public void onClosed(QrScanOverlay overlay) {
                AndroidUtilities.removeFromParent(overlay);
                if (onClosed != null) onClosed.run();
            }
        });
        overlay.setPlainButton(true);
        overlay.setAccept(accept);
        overlay.setOnGallery(() -> {
            if (!checkGalleryPermission(fragment.getParentActivity())) {
                return;
            }
            overlay.close();
            pickFromGallery(fragment, accept, onScanned);
        });
        container.addView(overlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return overlay;
    }

    public static boolean checkGalleryPermission(@Nullable android.app.Activity activity) {
        if (activity == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);
                return false;
            }
        }
        return true;
    }

    public static void pickFromGallery(BaseFragment fragment, Utilities.CallbackReturn<String, Boolean> accept, Utilities.Callback<String> onScanned) {
        final PhotoAlbumPickerActivity picker = new PhotoAlbumPickerActivity(PhotoAlbumPickerActivity.SELECT_TYPE_QR, false, false, null);
        picker.setMaxSelectedPhotos(1, false);
        picker.setAllowSearchImages(false);
        picker.setDelegate(new PhotoAlbumPickerActivity.PhotoAlbumPickerActivityDelegate() {
            @Override
            public void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> photos, boolean notify, int scheduleDate) {
                final String path = !photos.isEmpty() ? photos.get(0).path : null;
                if (path == null) {
                    return;
                }
                Utilities.globalQueue.postRunnable(() -> {
                    String text = null;
                    try {
                        final Point screen = AndroidUtilities.getRealScreenSize();
                        final Bitmap bitmap = ImageLoader.loadBitmap(path, null, screen.x, screen.y, true);
                        if (bitmap != null) {
                            text = readQr(bitmap, accept);
                            bitmap.recycle();
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    final String found = text;
                    AndroidUtilities.runOnUIThread(() -> whenOnTop(fragment, 0, () -> {
                        if (found != null) {
                            onScanned.run(found);
                        } else {
                            BulletinFactory.of(fragment).createSimpleBulletin(
                                LocaleController.getString(R.string.ScanQrCode),
                                LocaleController.getString(R.string.ErrorOccurred)
                            ).show();
                        }
                    }));
                });
            }

            @Override
            public void startPhotoSelectActivity() {
            }
        });
        fragment.presentFragment(picker);
    }

    private static void whenOnTop(BaseFragment fragment, int attempt, Runnable action) {
        if (fragment.isFinished) {
            return;
        }
        final INavigationLayout layout = fragment.getParentLayout();
        final boolean ready = layout == null || layout.getLastFragment() == fragment && !layout.isTransitionAnimationInProgress();
        if (ready || attempt >= 40) {
            action.run();
            return;
        }
        AndroidUtilities.runOnUIThread(() -> whenOnTop(fragment, attempt + 1, action), 50);
    }

    @Nullable
    public static String readQr(Bitmap bitmap, Utilities.CallbackReturn<String, Boolean> accept) {
        BarcodeDetector vision = null;
        try {
            vision = new BarcodeDetector.Builder(ApplicationLoader.applicationContext).setBarcodeFormats(Barcode.QR_CODE).build();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            final Scanned scanned = decode(bitmap, vision, new QRCodeReader(), accept);
            return scanned != null ? scanned.link : null;
        } finally {
            if (vision != null) {
                vision.release();
            }
        }
    }

    private @Nullable StaticLayout titleLayout;
    private final float cornerRadius;

    private float progress;
    private @Nullable ValueAnimator animator;
    private boolean closing, destroyed;

    private int pressed = -1;
    private float closePress = 1f, flashPress = 1f, galleryPress = 1f;
    private boolean torch;
    private float torchK;

    private final HandlerThread scanThread = new HandlerThread("QrScan");
    private final Handler scanHandler;
    private volatile boolean scanning;
    private @Nullable BarcodeDetector visionReader;
    private @Nullable QRCodeReader zxingReader;
    private @Nullable Bitmap frameBitmap;
    private boolean found;
    private @Nullable PointF[] foundPoints;
    private float foundK;

    QrScanOverlay(Context context, RectF from, RectF closeButton, Listener listener) {
        super(context);
        this.listener = listener;
        button.set(from);
        closeRect.set(closeButton);
        setWillNotDraw(false);

        scanIcon = tinted(context, R.drawable.msg_qr_scan);
        flashIcon = tinted(context, R.drawable.qr_flashlight);
        galleryIcon = tinted(context, R.drawable.qr_gallery);
        closeIcon.setColor(0xFFFFFFFF);
        closeIcon.setRotatedColor(0xFFFFFFFF);
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextSize(dp(22));
        titlePaint.setTypeface(AndroidUtilities.bold());
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setStrokeJoin(Paint.Join.ROUND);
        cornerPaint.setColor(0xFFFFFFFF);
        cornerPaint.setStrokeWidth(dp(4));
        foundPaint.setStyle(Paint.Style.STROKE);
        foundPaint.setStrokeCap(Paint.Cap.ROUND);
        foundPaint.setStrokeJoin(Paint.Join.ROUND);
        foundPaint.setColor(FOUND_COLOR);
        foundPaint.setStrokeWidth(dp(6));
        cornerRadius = screenCornerRadius();

        scanThread.start();
        scanHandler = new Handler(scanThread.getLooper());
        scanHandler.post(() -> {
            try {
                visionReader = new BarcodeDetector.Builder(ApplicationLoader.applicationContext).setBarcodeFormats(Barcode.QR_CODE).build();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            zxingReader = new QRCodeReader();
        });

        applyProgress();
        animate(1f, OPEN_MS, this::startCamera);
    }

    private static Drawable tinted(Context context, int res) {
        final Drawable d = ContextCompat.getDrawable(context, res).mutate();
        d.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        return d;
    }

    private float screenCornerRadius() {
        final android.app.Activity activity = AndroidUtilities.findActivity(getContext());
        return QrRender.screenCornerRadius(activity != null ? activity.getWindow().getDecorView() : this);
    }

    public void close() {
        if (closing || destroyed) {
            return;
        }
        closing = true;
        scanning = false;
        takeSnapshot();
        releaseCamera();
        animate(0f, CLOSE_MS, () -> {
            destroy();
            listener.onClosed(this);
        });
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        scanning = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        releaseCamera();
        scanHandler.removeCallbacksAndMessages(null);
        scanHandler.post(() -> {
            if (visionReader != null) {
                visionReader.release();
                visionReader = null;
            }
            if (frameBitmap != null) {
                frameBitmap.recycle();
                frameBitmap = null;
            }
            scanThread.quitSafely();
        });
    }

    private void animate(float to, long duration, @Nullable Runnable onEnd) {
        if (animator != null) {
            animator.cancel();
        }
        final ValueAnimator a = ValueAnimator.ofFloat(progress, to);
        a.addUpdateListener(anim -> {
            progress = (float) anim.getAnimatedValue();
            applyProgress();
        });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) {
                    animator = null;
                }
                if (!cancelled && onEnd != null) {
                    onEnd.run();
                }
            }
        });
        a.setDuration((long) (duration * Math.abs(to - progress)));
        a.setInterpolator(new LinearInterpolator());
        animator = a;
        a.start();
    }

    private float stretch() {
        if (closing) {
            return 1f - SHRINK.getInterpolation(1f - progress);
        }
        return STRETCH.getInterpolation(Utilities.clamp01(progress));
    }

    private float contentAlpha() {
        return Utilities.clamp01((stretch() - 0.15f) / 0.45f);
    }

    private void applyProgress() {
        invalidate();
    }

    private void startCamera() {
        if (destroyed || closing || cameraView != null || !CameraView.isCameraAllowed()) {
            return;
        }
        CameraController.getInstance().initCamera(null);
        final CameraView camera = new CameraView(getContext(), false);
        camera.setUseMaxPreview(true);
        camera.setOptimizeForBarcode(true);
        camera.setAlpha(0f);
        camera.setDelegate(() -> {
            camera.animate().alpha(1f).setDuration(180).start();
            startScanning();
        });
        cameraView = camera;
        addView(camera, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private void releaseCamera() {
        final CameraView camera = cameraView;
        if (camera == null) {
            return;
        }
        cameraView = null;
        blurNode = null;
        final CameraSessionWrapper session = camera.getCameraSession();
        if (torch && session != null) {
            session.setCurrentFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        }
        camera.animate().cancel();
        camera.destroy(true, null);
        removeView(camera);
    }

    private @Nullable Bitmap snapshot, snapshotBlur;
    private final RectF snapshotRect = new RectF();
    private final Paint snapshotPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private @Nullable android.graphics.BitmapShader snapshotBlurShader;
    private final Paint snapshotGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix snapshotMatrix = new android.graphics.Matrix();

    private void takeSnapshot() {
        final CameraView camera = cameraView;
        if (camera == null || camera.getAlpha() < 0.5f) {
            return;
        }
        final TextureView tv = camera.getTextureView();
        if (tv == null || !tv.isAvailable() || tv.getWidth() <= 0 || tv.getHeight() <= 0) {
            return;
        }
        try {
            snapshot = tv.getBitmap(Math.max(1, tv.getWidth() / 2), Math.max(1, tv.getHeight() / 2));
        } catch (Throwable e) {
            FileLog.e(e);
            snapshot = null;
        }
        if (snapshot == null) {
            return;
        }
        snapshotRect.set(camera.getLeft() + tv.getLeft(), camera.getTop() + tv.getTop(), camera.getLeft() + tv.getRight(), camera.getTop() + tv.getBottom());
        if (blurAllowed()) {
            snapshotBlur = Bitmap.createScaledBitmap(snapshot, Math.max(1, snapshot.getWidth() / 8), Math.max(1, snapshot.getHeight() / 8), true);
            Utilities.stackBlurBitmap(snapshotBlur, 6);
            snapshotBlurShader = new android.graphics.BitmapShader(snapshotBlur, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            snapshotMatrix.setScale(snapshotRect.width() / snapshotBlur.getWidth(), snapshotRect.height() / snapshotBlur.getHeight());
            snapshotMatrix.postTranslate(snapshotRect.left, snapshotRect.top);
            snapshotBlurShader.setLocalMatrix(snapshotMatrix);
            snapshotGlassPaint.setShader(snapshotBlurShader);
        }
    }

    private static boolean blurAllowed() {
        return QrRender.hasEffects() && LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR);
    }

    private void updateShape() {
        final float e = stretch();
        final float hw = button.width() / 2f, hh = button.height() / 2f;
        final float cx = button.centerX(), cy = button.centerY();
        shape.set(
            lerp(cx - hw, 0, e),
            lerp(cy - hh, 0, e),
            lerp(cx + hw, getWidth(), e),
            lerp(cy + hh, getHeight(), e)
        );
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        final int w = right - left, h = bottom - top;
        final float side = Math.min(w, h) * 0.66f;
        final float cy = h / 2f - dp(20);
        frameRect.set(w / 2f - side / 2f, cy - side / 2f, w / 2f + side / 2f, cy + side / 2f);
        final float fs = dp(56);
        final float fy = frameRect.bottom + dp(64);
        final float fx = onGallery != null ? w / 2f + dp(35) + fs / 2f : w / 2f;
        flashRect.set(fx - fs / 2f, fy, fx + fs / 2f, fy + fs);
        final float gx = w / 2f - dp(35) - fs / 2f;
        galleryRect.set(gx - fs / 2f, fy, gx + fs / 2f, fy + fs);
        titleLayout = new StaticLayout(LocaleController.getString(R.string.ScanQrCode), titlePaint, Math.max(1, w - dp(72)), Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        if (closeRect.isEmpty()) {
            final float ccy = QrShareView.insetTop(this) + ActionBar.getCurrentActionBarHeight() / 2f;
            closeRect.set(dp(9), ccy - dp(20), dp(49), ccy + dp(20));
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        updateShape();
        final float radius = lerp(button.height() / 2f, cornerRadius, stretch());
        final int save = canvas.save();
        if (progress < 1f) {
            clip.rewind();
            clip.addRoundRect(shape, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        final float buttonGlass = 1f - Utilities.clamp01(stretch() / 0.35f);
        final float dark = Utilities.clamp01(stretch() / 0.3f);
        if (dark > 0f) {
            fill.setColor(0xFF000000);
            fill.setAlpha((int) (255 * dark));
            canvas.drawRect(shape, fill);
        }
        final int w = getWidth(), h = getHeight();
        if (progress < 1f && w > 0 && h > 0) {
            final float k = Math.max(shape.width() / w, shape.height() / h);
            canvas.translate(shape.centerX(), shape.centerY());
            canvas.scale(k, k);
            canvas.translate(-w / 2f, -h / 2f);
        }
        super.dispatchDraw(canvas);
        if (snapshot != null) {
            snapshotPaint.setAlpha((int) (255 * contentAlpha()));
            canvas.drawBitmap(snapshot, null, snapshotRect, snapshotPaint);
        }
        final float a = contentAlpha();
        if (a > 0f) {
            drawScanner(canvas, a);
        }
        canvas.restoreToCount(save);
        if (buttonGlass > 0f && !plainButton) {
            drawGlassBody(canvas, shape, radius, buttonGlass, false);
            final float gs = 1f;
            if (buttonLabel != null) {
                final int size = (int) (dp(20) * gs);
                final float ix = shape.left + (dp(12) + dp(10)) * gs;
                scanIcon.setBounds((int) (ix - size / 2f), (int) (shape.centerY() - size / 2f), (int) (ix + size / 2f), (int) (shape.centerY() + size / 2f));
                scanIcon.setAlpha((int) (255 * buttonGlass));
                scanIcon.draw(canvas);
                buttonLabelPaint.setTextSize(dp(14) * gs);
                buttonLabelPaint.setAlpha((int) (255 * buttonGlass));
                final Paint.FontMetrics fm = buttonLabelPaint.getFontMetrics();
                canvas.drawText(buttonLabel, shape.left + (dp(12) + dp(20) + dp(6)) * gs, shape.centerY() - (fm.ascent + fm.descent) / 2f, buttonLabelPaint);
            } else {
                final int size = (int) (dp(22) * gs);
                scanIcon.setBounds((int) (shape.centerX() - size / 2f), (int) (shape.centerY() - size / 2f), (int) (shape.centerX() + size / 2f), (int) (shape.centerY() + size / 2f));
                scanIcon.setAlpha((int) (255 * buttonGlass));
                scanIcon.draw(canvas);
            }
        }
    }

    private void drawScanner(Canvas canvas, float alpha) {
        final float r = dp(14);
        fill.setColor(DIM_COLOR);
        fill.setAlpha((int) ((DIM_COLOR >>> 24) * alpha));
        if (Build.VERSION.SDK_INT >= 29) {
            screenRect.set(0, 0, getWidth(), getHeight());
            canvas.drawDoubleRoundRect(screenRect, 0, 0, frameRect, r, r, fill);
        } else {
            path.rewind();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            path.addRoundRect(frameRect, r, r, Path.Direction.CW);
            canvas.drawPath(path, fill);
            path.setFillType(Path.FillType.WINDING);
        }

        drawCorners(canvas, r, alpha * (1f - foundK));

        if (titleLayout != null) {
            canvas.save();
            canvas.translate(getWidth() / 2f - titleLayout.getWidth() / 2f, frameRect.top - dp(36) - titleLayout.getHeight());
            titlePaint.setAlpha((int) (255 * alpha));
            titleLayout.draw(canvas);
            canvas.restore();
        }

        drawFound(canvas, alpha);

        closePress = approach(closePress, pressed == 0 ? 0.88f : 1f);
        flashPress = approach(flashPress, pressed == 1 ? 0.88f : 1f);
        galleryPress = approach(galleryPress, pressed == 2 ? 0.88f : 1f);
        torchK = approach(torchK, torch ? 1f : 0f);
        drawButton(canvas, closeRect, closePress, alpha, null);
        drawButton(canvas, flashRect, flashPress, alpha, flashIcon);
        if (onGallery != null) {
            drawButton(canvas, galleryRect, galleryPress, alpha, galleryIcon);
        }
    }

    private @Nullable Bitmap cornerBitmap;
    private final Paint cornerBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private void drawCorners(Canvas canvas, float r, float alpha) {
        if (alpha <= 0.004f) return;
        final float len = dp(28), sw = cornerPaint.getStrokeWidth();
        final int pad = (int) Math.ceil(sw / 2f) + 1;
        if (cornerBitmap == null) {
            final int size = (int) Math.ceil(len) + 2 * pad;
            cornerBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            final Path p = new Path();
            p.moveTo(pad, pad + len);
            p.lineTo(pad, pad + r);
            p.quadTo(pad, pad, pad + r, pad);
            p.lineTo(pad + len, pad);
            final Paint stroke = new Paint(cornerPaint);
            stroke.setAlpha(255);
            new Canvas(cornerBitmap).drawPath(p, stroke);
            cornerBitmap.prepareToDraw();
        }
        cornerBitmapPaint.setAlpha((int) (255 * alpha));
        final RectF f = frameRect;
        for (int k = 0; k < 4; k++) {
            final boolean right = k == 1 || k == 2, bottom = k >= 2;
            canvas.save();
            canvas.translate(right ? f.right : f.left, bottom ? f.bottom : f.top);
            canvas.scale(right ? -1f : 1f, bottom ? -1f : 1f);
            canvas.drawBitmap(cornerBitmap, -pad, -pad, cornerBitmapPaint);
            canvas.restore();
        }
    }

    private void drawFound(Canvas canvas, float alpha) {
        foundK = approach(foundK, foundPoints != null ? 1f : 0f);
        if (foundPoints == null || foundPoints.length < 3 || cameraView == null || foundK <= 0f) {
            return;
        }
        final TextureView tv = cameraView.getTextureView();
        textureRect.set(cameraView.getLeft() + tv.getLeft(), cameraView.getTop() + tv.getTop(), cameraView.getLeft() + tv.getRight(), cameraView.getTop() + tv.getBottom());
        final int n = Math.min(4, foundPoints.length);
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += foundPoints[i].x;
            cy += foundPoints[i].y;
        }
        cx = textureRect.left + cx / n * textureRect.width();
        cy = textureRect.top + cy / n * textureRect.height();
        path.rewind();
        for (int i = 0; i < n; i++) {
            final PointF l = foundPoints[(i + n - 1) % n], p = foundPoints[i], rr = foundPoints[(i + 1) % n];
            final float px = textureRect.left + p.x * textureRect.width(), py = textureRect.top + p.y * textureRect.height();
            final float lx = textureRect.left + l.x * textureRect.width(), ly = textureRect.top + l.y * textureRect.height();
            final float rx = textureRect.left + rr.x * textureRect.width(), ry = textureRect.top + rr.y * textureRect.height();
            path.moveTo(px + (lx - px) * .18f, py + (ly - py) * .18f);
            path.lineTo(px, py);
            path.lineTo(px + (rx - px) * .18f, py + (ry - py) * .18f);
        }
        canvas.save();
        final float s = lerp(0.5f, 1.1f, foundK);
        canvas.scale(s, s, cx, cy);
        foundPaint.setAlpha((int) (255 * foundK * alpha));
        canvas.drawPath(path, foundPaint);
        canvas.restore();
    }

    private float approach(float value, float target) {
        if (Math.abs(value - target) < 0.005f) {
            return target;
        }
        invalidate();
        return value + (target - value) * 0.3f;
    }

    private void drawButton(Canvas canvas, RectF rect, float scale, float alpha, @Nullable Drawable icon) {
        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), rect.centerY());
        final float radius = rect.height() / 2f;
        drawGlassBody(canvas, rect, radius, alpha, true);
        if (icon == flashIcon && torchK > 0f) {
            fill.setColor(0xFFFFFFFF);
            fill.setAlpha((int) (0x66 * torchK * alpha));
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
        if (icon != null) {
            final int size = dp(28);
            icon.setBounds((int) (rect.centerX() - size / 2f), (int) (rect.centerY() - size / 2f), (int) (rect.centerX() + size / 2f), (int) (rect.centerY() + size / 2f));
            icon.setAlpha((int) (255 * alpha));
            icon.draw(canvas);
        } else {
            closeIcon.setAlpha((int) (255 * alpha));
            canvas.save();
            canvas.translate(rect.centerX() - closeIcon.getIntrinsicWidth() / 2f, rect.centerY() - closeIcon.getIntrinsicHeight() / 2f);
            closeIcon.draw(canvas);
            canvas.restore();
        }
        canvas.restore();
    }

    private void drawGlassBody(Canvas canvas, RectF rect, float radius, float alpha, boolean blurred) {
        if (alpha <= 0f) {
            return;
        }
        final int white = (int) (255 * Utilities.clamp01(RimTune.v[RimTune.FILL] / 100f));
        if (blurred && blurReady(canvas)) {
            path.rewind();
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);
            canvas.save();
            canvas.translate(cameraView.getLeft(), cameraView.getTop());
            QrRender.setAlpha(blurNode, alpha);
            QrRender.draw(canvas, blurNode);
            canvas.restore();
            fill.setColor(0x00FFFFFF | (white << 24));
            fill.setAlpha((int) (white * alpha));
            canvas.drawRect(rect, fill);
            QrGlass.drawSheen(canvas, rect, false, alpha);
            canvas.restore();
        } else if (blurred && snapshotBlur != null) {
            snapshotGlassPaint.setAlpha((int) (255 * alpha));
            canvas.drawRoundRect(rect, radius, radius, snapshotGlassPaint);
            fill.setColor(0x00FFFFFF | (white << 24));
            fill.setAlpha((int) (white * alpha));
            canvas.drawRoundRect(rect, radius, radius, fill);
            QrGlass.drawSheenRound(canvas, rect, radius, false, alpha);
        } else {
            fill.setColor(ColorUtils.compositeColors(0x00FFFFFF | (white << 24), blurAllowed() ? FLAT_SHADE : NO_BLUR_SHADE));
            fill.setAlpha((int) (Color.alpha(fill.getColor()) * alpha));
            canvas.drawRoundRect(rect, radius, radius, fill);
            QrGlass.drawSheenRound(canvas, rect, radius, false, alpha);
        }
        rim.draw(canvas, rect, radius, RimTune.BIG, alpha);
    }

    private boolean blurReady(Canvas canvas) {
        if (cameraView == null || !canvas.isHardwareAccelerated() || !blurAllowed()) {
            return false;
        }
        if (blurNode == null) {
            blurNode = cameraView.getBlurRenderNode();
            invalidate();
        }
        return QrRender.hasDisplayList(blurNode);
    }

    private final RectF screenRect = new RectF();

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (progress < 1f || closing) {
            return true;
        }
        final float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(closeRect, x, y) ? 0 : hit(flashRect, x, y) ? 1 : onGallery != null && hit(galleryRect, x, y) ? 2 : -1;
                invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                if (pressed == 0 && !hit(closeRect, x, y) || pressed == 1 && !hit(flashRect, x, y) || pressed == 2 && !hit(galleryRect, x, y)) {
                    pressed = -1;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (pressed == 0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    close();
                } else if (pressed == 1) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    toggleTorch();
                } else if (pressed == 2 && onGallery != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    onGallery.run();
                } else if (cameraView != null) {
                    cameraView.focusToPoint((int) x, (int) y);
                }
                pressed = -1;
                invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                break;
        }
        return true;
    }

    private static boolean hit(RectF rect, float x, float y) {
        return x >= rect.left - dp(8) && x <= rect.right + dp(8) && y >= rect.top - dp(8) && y <= rect.bottom + dp(8);
    }

    private void toggleTorch() {
        final CameraSessionWrapper session = cameraView != null ? cameraView.getCameraSession() : null;
        if (session == null) {
            return;
        }
        torch = !torch;
        session.setCurrentFlashMode(torch ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
        invalidate();
    }

    private void startScanning() {
        if (destroyed || scanning || found) {
            return;
        }
        scanning = true;
        scanHandler.postDelayed(scanFrame, SCAN_INTERVAL);
    }

    private final Runnable scanFrame = new Runnable() {
        @Override
        public void run() {
            final CameraView camera = cameraView;
            if (!scanning || camera == null) {
                return;
            }
            final TextureView tv = camera.getTextureView();
            int w = tv.getWidth(), h = tv.getHeight();
            if (w > 0 && h > 0) {
                if (w > MAX_FRAME_SIDE || h > MAX_FRAME_SIDE) {
                    final float s = Math.min((float) MAX_FRAME_SIDE / w, (float) MAX_FRAME_SIDE / h);
                    w = Math.max(1, (int) (w * s));
                    h = Math.max(1, (int) (h * s));
                }
                if (frameBitmap == null || frameBitmap.getWidth() != w || frameBitmap.getHeight() != h) {
                    frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                try {
                    tv.getBitmap(frameBitmap);
                    final Scanned scanned = decode(frameBitmap, visionReader, zxingReader, accept);
                    if (scanned != null) {
                        AndroidUtilities.runOnUIThread(() -> onFound(scanned));
                        return;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (scanning) {
                scanHandler.postDelayed(this, SCAN_INTERVAL);
            }
        }
    };

    private static final class Scanned {
        final String link;
        final PointF[] points;

        Scanned(String link, PointF[] points) {
            this.link = link;
            this.points = points;
        }
    }

    @Nullable
    private static Scanned decode(Bitmap bitmap, @Nullable BarcodeDetector visionReader, @Nullable QRCodeReader zxingReader, Utilities.CallbackReturn<String, Boolean> accept) {
        final int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (visionReader != null && visionReader.isOperational()) {
            final SparseArray<Barcode> codes = visionReader.detect(new Frame.Builder().setBitmap(bitmap).build());
            for (int i = 0; codes != null && i < codes.size(); i++) {
                final Barcode code = codes.valueAt(i);
                final String text = code.rawValue != null ? code.rawValue.trim() : null;
                if (!accepts(accept, text)) {
                    continue;
                }
                final PointF[] points = new PointF[code.cornerPoints != null ? code.cornerPoints.length : 0];
                for (int k = 0; k < points.length; k++) {
                    points[k] = new PointF(code.cornerPoints[k].x / (float) w, code.cornerPoints[k].y / (float) h);
                }
                return new Scanned(text, points);
            }
            return null;
        }
        if (zxingReader == null) {
            return null;
        }
        final int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        try {
            final Result result = zxingReader.decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels))));
            final String text = result != null && result.getText() != null ? result.getText().trim() : null;
            if (!accepts(accept, text)) {
                return null;
            }
            final ResultPoint[] rp = result.getResultPoints();
            final PointF[] points = new PointF[rp != null && rp.length == 4 ? 4 : 0];
            for (int k = 0; k < points.length; k++) {
                points[k] = new PointF(rp[k].getX() / w, rp[k].getY() / h);
            }
            return new Scanned(text, points);
        } catch (Exception ignore) {
            return null;
        } finally {
            zxingReader.reset();
        }
    }

    private static boolean accepts(Utilities.CallbackReturn<String, Boolean> accept, @Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(accept.run(text));
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    static boolean isTelegramLink(@Nullable String text) {
        return !TextUtils.isEmpty(text) && (text.startsWith("tg:") || Browser.isInternalUrl(text, null));
    }

    private void onFound(Scanned scanned) {
        if (found || destroyed || closing) {
            return;
        }
        found = true;
        scanning = false;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        final boolean outlined = scanned.points.length >= 3;
        if (outlined) {
            foundPoints = scanned.points;
            invalidate();
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed) {
                return;
            }
            close();
            listener.onScanned(scanned.link);
        }, outlined ? FOUND_DELAY : 0);
    }
}
