package org.telegram.ui.Components;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.google.zxing.EncodeHintType;
import org.telegram.messenger.TelegramQRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import java.lang.ref.WeakReference;

import java.util.HashMap;

public class QRCodeBottomSheet extends BottomSheet {

    Bitmap qrCode;
    private final TextView help;
    private final TextView buttonTextView;
    private final ImageView imageView;
    private final String qrLink;
    private final String helpMessage;
    private TextView button2TextView;
    private QrRenderRequest renderRequest;
    private volatile int shareGeneration;
    private boolean sharingQr;
    int imageSize;
    RLottieImageView iconImage;

    public QRCodeBottomSheet(Context context, String title, String link, String helpMessage, boolean includeShareLink) {
        this(context, title, link, helpMessage, includeShareLink, null);
    }
    public QRCodeBottomSheet(Context context, String title, String link, String helpMessage, boolean includeShareLink, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        qrLink = link;
        this.helpMessage = helpMessage;
        fixNavigationBar();

        setTitle(title, true);
        imageView = new ImageView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int size = MeasureSpec.getSize(widthMeasureSpec);
                super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
            }
            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                requestQr();
            }
            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                releaseQr();
            }
        };
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            imageView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight(), AndroidUtilities.dp(12));
                }
            });
            imageView.setClipToOutline(true);
        }

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(0, AndroidUtilities.dp(16), 0, 0);

        iconImage = new RLottieImageView(context);
        iconImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconImage.setBackgroundColor(Color.WHITE);
        iconImage.setVisibility(View.INVISIBLE);
        //iconImage.setPadding(-AndroidUtilities.dp(4), -AndroidUtilities.dp(4), -AndroidUtilities.dp(4), -AndroidUtilities.dp(4));

        FrameLayout frameLayout = new FrameLayout(context) {

            float lastX;
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                float x = qrCode == null ? 0 : imageSize / (float) qrCode.getWidth() * imageView.getMeasuredHeight();
                if (lastX != x) {
                    lastX = x;
                    iconImage.getLayoutParams().height = iconImage.getLayoutParams().width = (int) x;
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            }
        };
        frameLayout.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        frameLayout.addView(iconImage, LayoutHelper.createFrame(60, 60, Gravity.CENTER));
        linearLayout.addView(frameLayout, LayoutHelper.createLinear(220, 220, Gravity.CENTER_HORIZONTAL, 30, 0,30 ,0));

        help = new TextView(context);
        help.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        help.setText(helpMessage);
        help.setGravity(Gravity.CENTER_HORIZONTAL);
        linearLayout.addView(help, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 40, 8, 40, 8));

        buttonTextView = new TextView(context);
        buttonTextView.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
        buttonTextView.setGravity(Gravity.CENTER);
        buttonTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        buttonTextView.setTypeface(AndroidUtilities.bold());
        buttonTextView.setText(LocaleController.getString(R.string.ShareQrCode));
        buttonTextView.setEnabled(false);
        buttonTextView.setAlpha(0.5f);
        buttonTextView.setOnClickListener(view -> {
            if (qrCode == null || isDismissed() || sharingQr) return;
            sharingQr = true;
            buttonTextView.setEnabled(false);
            buttonTextView.setAlpha(0.5f);
            final Bitmap bitmap = qrCode;
            final int generation = ++shareGeneration;
            Utilities.themeQueue.postRunnable(() -> {
                if (generation != shareGeneration) return;
                Uri uri = null;
                try {
                    uri = AndroidUtilities.getBitmapShareUri(bitmap, "qr_tmp.png", Bitmap.CompressFormat.PNG);
                } catch (RuntimeException | OutOfMemoryError e) {
                    FileLog.e(e);
                }
                final Uri shareUri = uri;
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation != shareGeneration || qrCode != bitmap || isDismissed()
                            || !imageView.isAttachedToWindow() || !AndroidUtilities.isSafeToShow(context)) return;
                    sharingQr = false;
                    buttonTextView.setEnabled(true);
                    buttonTextView.setAlpha(1f);
                    if (shareUri == null || AndroidUtilities.findActivity(context) == null) return;
                    Intent intent = new Intent(Intent.ACTION_SEND).setType("image/*")
                            .putExtra(Intent.EXTRA_STREAM, shareUri);
                    try {
                        AndroidUtilities.findActivity(context).startActivityForResult(
                                Intent.createChooser(intent, getTitleView().getText()), 500);
                    } catch (ActivityNotFoundException e) {
                        FileLog.e(e);
                    }
                });
            });
        });
        linearLayout.addView(buttonTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 15, 16, 3));

        if (includeShareLink) {
            button2TextView = new TextView(context);
            button2TextView.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
            button2TextView.setGravity(Gravity.CENTER);
            button2TextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            //        button2TextView.setTypeface(AndroidUtilities.medium());
            button2TextView.setText(LocaleController.getString(R.string.ShareLink));
            button2TextView.setOnClickListener(view -> {
                if (isDismissed() || !AndroidUtilities.isSafeToShow(context)) return;
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, link);
                Intent chooserIntent = Intent.createChooser(shareIntent, LocaleController.getString(R.string.ShareLink));
                chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(chooserIntent);
            });
            linearLayout.addView(button2TextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 3, 16, 16));
        }

        updateColors();
        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(linearLayout);
        setCustomView(scrollView);
    }
    @Override
    public void show() {
        super.show();
        if (imageView.isAttachedToWindow()) requestQr();
        if (qrCode != null && !sharingQr) {
            buttonTextView.setEnabled(true);
            buttonTextView.setAlpha(1f);
        }
    }
    private void requestQr() {
        if (isDismissed() || qrCode != null || renderRequest != null) return;
        help.setText(helpMessage);
        renderRequest = new QrRenderRequest(this, qrLink);
        Utilities.themeQueue.postRunnable(renderRequest);
    }
    private void cancelQrPreparation() {
        if (renderRequest != null) {
            renderRequest.cancelled = true;
            Utilities.themeQueue.cancelRunnable(renderRequest);
            renderRequest = null;
        }
    }
    private void releaseQr() {
        cancelQrPreparation();
        cancelQrSharing();
        imageView.setImageDrawable(null);
        qrCode = null;
        imageSize = 0;
        iconImage.setVisibility(View.INVISIBLE);
        buttonTextView.setEnabled(false);
        buttonTextView.setAlpha(0.5f);
    }
    @Override
    public void onDismissAnimationStart() {
        cancelQrPreparation();
        cancelQrSharing();
        super.onDismissAnimationStart();
    }
    @Override
    public void dismissInternal() {
        cancelQrPreparation();
        cancelQrSharing();
        super.dismissInternal();
        if (!imageView.isAttachedToWindow()) releaseQr();
    }
    private void cancelQrSharing() {
        ++shareGeneration;
        sharingQr = false;
    }
    private static final class PreparedQr {
        final Bitmap bitmap;
        final int imageSize;
        PreparedQr(Bitmap bitmap, int imageSize) {
            this.bitmap = bitmap;
            this.imageSize = imageSize;
        }
    }
    private static final class QrRenderRequest implements Runnable {
        private final WeakReference<QRCodeBottomSheet> owner;
        private final String link;
        volatile boolean cancelled;
        QrRenderRequest(QRCodeBottomSheet owner, String link) {
            this.owner = new WeakReference<>(owner);
            this.link = link;
        }
        @Override
        public void run() {
            if (cancelled) return;
            final PreparedQr prepared = prepareQr(link, null);
            if (cancelled) {
                if (prepared != null) prepared.bitmap.recycle();
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                QRCodeBottomSheet sheet = owner.get();
                if (cancelled || sheet == null || sheet.renderRequest != this || sheet.isDismissed()
                        || !sheet.imageView.isAttachedToWindow() || !AndroidUtilities.isSafeToShow(sheet.getContext())) {
                    if (prepared != null) prepared.bitmap.recycle();
                    return;
                }
                sheet.renderRequest = null;
                if (prepared == null) {
                    sheet.help.setText(LocaleController.getString(R.string.ErrorOccurred));
                    return;
                }
                sheet.qrCode = prepared.bitmap;
                sheet.imageSize = prepared.imageSize;
                sheet.imageView.setImageBitmap(prepared.bitmap);
                sheet.imageView.requestLayout();
                sheet.iconImage.setVisibility(View.VISIBLE);
                sheet.buttonTextView.setEnabled(true);
                sheet.buttonTextView.setAlpha(1f);
            });
        }
    }

    public Bitmap createQR(Context context, String key, Bitmap oldBitmap) {
        PreparedQr prepared = prepareQr(key, oldBitmap);
        imageSize = prepared == null ? 0 : prepared.imageSize;
        return prepared == null ? null : prepared.bitmap;
    }
    private static PreparedQr prepareQr(String key, Bitmap oldBitmap) {
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
            Bitmap bitmap = writer.encode(key, 768, 768, hints, oldBitmap);
            if (bitmap == null) return null;
            return new PreparedQr(bitmap, writer.getImageSize());
        } catch (Exception | OutOfMemoryError e) {
            FileLog.e(e);
        }
        return null;
    }

    public void setCenterAnimation(int resId) {
        iconImage.setAutoRepeat(true);
        iconImage.setAnimation(resId, 60, 60);
        iconImage.playAnimation();
    }

    public void setCenterImage(int resId) {
        iconImage.setImageResource(resId);
    }

    public void setCenterImage(Drawable drawable) {
        iconImage.setImageDrawable(drawable);
    }

    public void setCenterImage(Bitmap bitmap) {
        iconImage.setImageBitmap(bitmap);
    }

    void updateColors() {
        buttonTextView.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        buttonTextView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
        if (button2TextView != null) {
            button2TextView.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
            button2TextView.setBackground(Theme.createSelectorDrawable(ColorUtils.setAlphaComponent(getThemedColor(Theme.key_featuredStickers_addButton), Math.min(255, Color.alpha(getThemedColor(Theme.key_listSelector)) * 2)), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        }
        help.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        help.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        if (getTitleView() != null) {
            getTitleView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
    }
}
