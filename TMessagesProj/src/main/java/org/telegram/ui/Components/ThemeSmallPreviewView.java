package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatThemeController;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.EmojiThemes;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.theme.ITheme;
import org.telegram.ui.ChatBackgroundDrawable;

import java.util.LinkedHashMap;
import java.util.List;
import java.io.File;

public class ThemeSmallPreviewView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final static int PATTERN_BITMAP_MAXWIDTH = 120;
    private final static int PATTERN_BITMAP_MAXHEIGHT = 140;

    public final static int TYPE_DEFAULT = 0;
    public final static int TYPE_GRID = 1;
    public final static int TYPE_QR = 2;
    public final static int TYPE_CHANNEL = 3;
    public final static int TYPE_GRID_CHANNEL = 4;

    private final float STROKE_RADIUS = AndroidUtilities.dp(8);
    private final float INNER_RADIUS = AndroidUtilities.dp(6);
    private final float INNER_RECT_SPACE = AndroidUtilities.dp(4);
    private final float BUBBLE_HEIGHT = AndroidUtilities.dp(21);
    private final float BUBBLE_WIDTH = AndroidUtilities.dp(41);

    ThemeDrawable themeDrawable = new ThemeDrawable();
    ThemeDrawable animateOutThemeDrawable;
    private float changeThemeProgress = 1f;
    private long changeThemeStartedAt;
    private Bitmap paletteFrom;
    private boolean waitingForPattern;
    private boolean transitionPending;
    private boolean patternFailed;
    private PatternLoad patternLoad;

    Paint outlineBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rectF = new RectF();
    private final Path clipPath = new Path();
    private final Theme.ResourcesProvider resourcesProvider;

    private ValueAnimator strokeAlphaAnimator;
    private TextPaint noThemeTextPaint;
    private StaticLayout textLayout;
    public ChatThemeBottomSheet.ChatThemeItem chatThemeItem;
    private BackupImageView backupImageView;
    private boolean hasAnimatedEmoji;
    private final int currentAccount;
    Runnable animationCancelRunnable;
    private int currentType;
    int patternColor;
    private float selectionProgress;
    ChatBackgroundDrawable chatBackgroundDrawable;
    boolean attached;

    private final ImageReceiver avatarImageReceiver;
    private AvatarDrawable avatarDrawable;

    public ThemeSmallPreviewView(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider, int currentType) {
        super(context);
        this.currentType = currentType;
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        this.avatarImageReceiver = new ImageReceiver(this);
        this.avatarImageReceiver.setRoundRadius(AndroidUtilities.dp(8));
        setBackgroundColor(getThemedColor(Theme.key_dialogBackgroundGray));
        backupImageView = new BackupImageView(context);
        backupImageView.getImageReceiver().setCrossfadeWithOldImage(true);
        backupImageView.getImageReceiver().setAllowStartLottieAnimation(false);
        backupImageView.getImageReceiver().setAutoRepeat(0);
        if (currentType == TYPE_DEFAULT || currentType == TYPE_CHANNEL || currentType == TYPE_QR) {
            addView(backupImageView, LayoutHelper.createFrame(28, 28, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 12));
        } else {
            addView(backupImageView, LayoutHelper.createFrame(36, 36, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 12));
        }

        outlineBackgroundPaint.setStrokeWidth(AndroidUtilities.dp(2));
        outlineBackgroundPaint.setStyle(Paint.Style.STROKE);
        outlineBackgroundPaint.setColor(0x20E3E3E3);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (currentType == TYPE_GRID || currentType == TYPE_GRID_CHANNEL) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = (int) (width * 1.2f);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        } else {
            int width = AndroidUtilities.dp(currentType == TYPE_DEFAULT ? 77 : 83);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            if (height == 0) {
                height = (int) (width * 1.35f);
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        backupImageView.setPivotY(backupImageView.getMeasuredHeight());
        backupImageView.setPivotX(backupImageView.getMeasuredWidth() / 2f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == oldw && h == oldh) {
            return;
        }
        rectF.set(INNER_RECT_SPACE, INNER_RECT_SPACE, w - INNER_RECT_SPACE, h - INNER_RECT_SPACE);
        clipPath.reset();
        clipPath.addRoundRect(rectF, INNER_RADIUS, INNER_RADIUS, Path.Direction.CW);
        paletteFrom = null;
    }

    MessageDrawable messageDrawableOut = new MessageDrawable(MessageDrawable.TYPE_TEXT, true, false);
    MessageDrawable messageDrawableIn = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, false);

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (chatThemeItem == null) {
            super.dispatchDraw(canvas);
            return;
        }
        if (transitionPending && !waitingForPattern) {
            transitionPending = false;
            changeThemeStartedAt = SystemClock.uptimeMillis();
        }
        if (waitingForPattern && animateOutThemeDrawable == null && paletteFrom == null) {
            return;
        }
        int reveal = animateOutThemeDrawable == null && paletteFrom == null && changeThemeProgress < 1f
                ? canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * previewProgress())) : -1;
        if (chatBackgroundDrawable != null) {
            canvas.save();
            canvas.clipPath(clipPath);
            chatBackgroundDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
            chatBackgroundDrawable.draw(canvas);
            canvas.restore();
        }
        drawPalette(canvas);
        super.dispatchDraw(canvas);
        if (reveal != -1) canvas.restoreToCount(reveal);
    }
    private float previewProgress() {
        return transitionPending || waitingForPattern ? 0f
                : Math.min(1f, (SystemClock.uptimeMillis() - changeThemeStartedAt) / 220f);
    }
    private void drawPalette(Canvas canvas) {
        if (patternFailed && (paletteFrom != null || animateOutThemeDrawable != null)) {
            if (paletteFrom != null) canvas.drawBitmap(paletteFrom, 0, 0, null);
            if (animateOutThemeDrawable != null) {
                animateOutThemeDrawable.drawBackground(canvas, 1f);
                animateOutThemeDrawable.draw(canvas, 1f);
            }
            return;
        }
        if (changeThemeProgress < 1f) changeThemeProgress = previewProgress();
        if (changeThemeProgress != 1 && paletteFrom != null) {
            canvas.drawBitmap(paletteFrom, 0, 0, null);
        }
        if (changeThemeProgress != 1 && animateOutThemeDrawable != null) {
            animateOutThemeDrawable.drawBackground(canvas, 1f);
            animateOutThemeDrawable.draw(canvas, 1f);
        }
        if (!waitingForPattern) {
            int save = changeThemeProgress < 1f && (animateOutThemeDrawable != null || paletteFrom != null)
                    ? canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * changeThemeProgress)) : -1;
            themeDrawable.drawBackground(canvas, 1f);
            themeDrawable.draw(canvas, 1f);
            if (save != -1) canvas.restoreToCount(save);
        }
        if (changeThemeProgress != 1f && !waitingForPattern) {
            invalidate();
        } else if (changeThemeProgress == 1f) {
            animateOutThemeDrawable = null;
            paletteFrom = null;
        }
    }

    public TLRPC.WallPaper fallbackWallpaper;
    public void setFallbackWallpaper(TLRPC.WallPaper wallPaper) {
        if (fallbackWallpaper != wallPaper) {
            this.fallbackWallpaper = wallPaper;
            if (chatThemeItem != null && (chatThemeItem.chatTheme == null || chatThemeItem.chatTheme.wallpaper == null)) {
                ChatThemeBottomSheet.ChatThemeItem item = chatThemeItem;
                chatThemeItem = null;
                setItem(item, parentDialogId, false);
            }
        }
    }

    private long themeUserByUserId;
    private long parentDialogId;

    public int lastThemeIndex;
    private int previewGeneration;
    private final LinkedHashMap<String, Bitmap> previewPatterns = new LinkedHashMap<>();
    private static String patternKey(TLRPC.WallPaper wallpaper) {
        if (wallpaper.document != null && wallpaper.document.id != 0) {
            return "document:" + wallpaper.document.id;
        }
        return wallpaper.id != 0 ? "wallpaper:" + wallpaper.id : null;
    }
    private boolean reusePattern(Drawable target, String key, int intensity, int color) {
        Bitmap bitmap = key == null ? null : previewPatterns.get(key);
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        applyPattern(target, key, bitmap, intensity, color, true);
        return true;
    }
    private void applyPattern(Drawable target, String key, Bitmap bitmap, int intensity, int color, boolean immediate) {
        if (!(target instanceof MotionBackgroundDrawable) || target != themeDrawable.previewDrawable || patternFailed) {
            return;
        }
        if (bitmap == null || bitmap.isRecycled()) {
            failPattern(target, intensity);
            return;
        }
        MotionBackgroundDrawable motion = (MotionBackgroundDrawable) target;
        if (motion.getPatternBitmap() != null) {
            return;
        }
        if (!immediate) {
            try {
                bitmap = prescaleBitmap(bitmap).copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable e) {
                FileLog.e(e);
                failPattern(target, intensity);
                return;
            }
            if (bitmap == null) {
                failPattern(target, intensity);
                return;
            }
            if (key != null) {
                previewPatterns.put(key, bitmap);
                if (previewPatterns.size() > 2) {
                    previewPatterns.remove(previewPatterns.keySet().iterator().next());
                }
            }
        }
        motion.setPatternBitmap(intensity, bitmap, true); // true = doNotScale, not animate
        motion.setPatternColorFilter(color);
        waitingForPattern = false;
        invalidate();
    }
    private void failPattern(Drawable target, int intensity) {
        if (target != themeDrawable.previewDrawable || !waitingForPattern || patternFailed) return;
        patternFailed = true;
        waitingForPattern = false;
        if (paletteFrom == null && animateOutThemeDrawable == null && intensity < 0) {
            themeDrawable.previewDrawable = new ColorDrawable(Color.BLACK);
        }
        transitionPending = true;
        invalidate();
    }
    private void loadPattern(TLRPC.Document document, Drawable target, String key, int intensity, int color) {
        patternLoad = new PatternLoad(document, target, key, intensity, color);
        if (attached) patternLoad.start();
    }
    private void resumePatternLoad() {
        if (patternLoad != null && waitingForPattern) {
            PatternLoad pending = patternLoad;
            if (pending.finished) {
                loadPattern(pending.document, pending.target, pending.key, pending.intensity, pending.color);
            } else if (!pending.started) {
                pending.start();
            }
        }
    }
    private Bitmap decodePatternThumb(TLRPC.PhotoSize thumb) {
        try {
            return thumb instanceof TLRPC.TL_photoStrippedSize
                    ? ImageLoader.getStrippedPhotoBitmap(thumb.bytes, "b")
                    : BitmapFactory.decodeByteArray(thumb.bytes, 0, thumb.bytes.length);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
    private Bitmap decodeDefaultPattern() {
        try {
            return SvgHelper.getBitmap(R.raw.default_pattern, AndroidUtilities.dp(PATTERN_BITMAP_MAXWIDTH),
                    AndroidUtilities.dp(PATTERN_BITMAP_MAXHEIGHT), Color.BLACK, AndroidUtilities.density);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
    private class PatternLoad implements NotificationCenter.NotificationCenterDelegate {
        final int generation = previewGeneration;
        final TLRPC.Document document;
        final Drawable target;
        final String key;
        final int intensity, color;
        String fileName;
        boolean finished;
        boolean started;
        PatternLoad(TLRPC.Document document, Drawable target, String key, int intensity, int color) {
            this.document = document;
            this.target = target;
            this.key = key;
            this.intensity = intensity;
            this.color = color;
        }
        void stopObserving() {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadFailed);
        }
        void cancel() {
            finished = true;
            stopObserving(); // Do not cancel a shared FileLoader transfer.
        }
        void start() {
            if (started || finished || !attached) return;
            started = true;
            try {
                startRequest();
            } catch (Throwable e) {
                FileLog.e(e);
                complete(null);
            }
        }
        void startRequest() {
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, PATTERN_BITMAP_MAXWIDTH);
            if (thumb instanceof TLRPC.TL_photoCachedSize || thumb instanceof TLRPC.TL_photoStrippedSize) {
                ChatThemeController.chatThemeQueue.postRunnable(() -> {
                    final Bitmap result = decodePatternThumb(thumb);
                    AndroidUtilities.runOnUIThread(() -> complete(result));
                });
                return;
            }
            ImageLocation location = ImageLocation.getForDocument(thumb, document);
            if (location == null || location.location == null) {
                complete(null);
                return;
            }
            fileName = FileLoader.getAttachFileName(location.location);
            if (TextUtils.isEmpty(fileName)) {
                complete(null);
                return;
            }
            FileLoader loader = FileLoader.getInstance(currentAccount);
            File local = loader.getLocalFile(location);
            if (local != null) {
                decode(local);
                return;
            }
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadFailed);
            loader.loadFile(location, document, null, FileLoader.PRIORITY_NORMAL, 1);
        }
        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (finished || account != currentAccount || !fileName.equals(args[0])) return;
            if (id == NotificationCenter.fileLoaded) {
                stopObserving();
                decode((File) args[1]);
            } else if (id == NotificationCenter.fileLoadFailed) {
                complete(null);
            }
        }
        void decode(File file) {
            ChatThemeController.chatThemeQueue.postRunnable(() -> {
                Bitmap bitmap = null;
                try {
                    bitmap = AndroidUtilities.getScaledBitmap(AndroidUtilities.dp(PATTERN_BITMAP_MAXWIDTH),
                            AndroidUtilities.dp(PATTERN_BITMAP_MAXHEIGHT), file.getAbsolutePath(), null, 0);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                final Bitmap result = bitmap;
                AndroidUtilities.runOnUIThread(() -> complete(result));
            });
        }
        void complete(Bitmap bitmap) {
            if (finished) return;
            finished = true;
            stopObserving();
            if (patternLoad != this || generation != previewGeneration) return;
            patternLoad = null;
            applyPattern(target, key, bitmap, intensity, color, false);
        }
    }
    public void setItem(ChatThemeBottomSheet.ChatThemeItem item, boolean animated) {
        setItem(item, 0, animated);
    }

    public void setItem(ChatThemeBottomSheet.ChatThemeItem item, long parentDialogId, boolean animated) {
        this.parentDialogId = parentDialogId;
        boolean itemChanged = chatThemeItem != item;
        boolean darkModeChanged = lastThemeIndex != item.themeIndex;
        boolean hasPreviousPalette = !waitingForPattern || animateOutThemeDrawable != null || paletteFrom != null;
        Bitmap interruptedPalette = null;
        if (!itemChanged && darkModeChanged && animated && hasPreviousPalette && changeThemeProgress < 1f
                && getWidth() > 0 && getHeight() > 0) {
            interruptedPalette = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            drawPalette(new Canvas(interruptedPalette));
        }
        lastThemeIndex = item.themeIndex;
        this.chatThemeItem = item;
        hasAnimatedEmoji = false;
        final TLRPC.Document document = item.chatTheme.getEmojiAnimatedSticker();

        themeUserByUserId = item.chatTheme.getBusyByUserId();
        if (parentDialogId == themeUserByUserId) {
            themeUserByUserId = 0;
        }
        if (themeUserByUserId != 0) {
            if (avatarDrawable == null) {
                avatarDrawable = new AvatarDrawable();
            }
            TLObject infoObject = MessagesController.getInstance(currentAccount).getUserOrChat(themeUserByUserId);
            avatarDrawable.setInfo(currentAccount, infoObject);
            avatarImageReceiver.setForUserOrChat(infoObject, avatarDrawable);
        } else {
            avatarImageReceiver.clearImage();
        }

        if (itemChanged) {
            if (animationCancelRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(animationCancelRunnable);
                animationCancelRunnable = null;
            }
            backupImageView.animate().cancel();
            backupImageView.setScaleX(1f);
            backupImageView.setScaleY(1f);
        }
        if (itemChanged) {
            Drawable thumb = null;
            if (document != null) {
                thumb = DocumentObject.getSvgThumb(document, Theme.key_emptyListPlaceholder, 0.2f);
            }
            if (thumb == null) {
                Emoji.preloadEmoji(item.chatTheme.getEmoticon());
                thumb = Emoji.getEmojiDrawable(item.chatTheme.getEmoticon());
            }
            backupImageView.setImage(ImageLocation.getForDocument(document), "50_50", thumb, null);
            TLRPC.WallPaper wallPaper = item.chatTheme.wallpaper;
            if (wallPaper == null) {
                wallPaper = fallbackWallpaper;
            }
            if (wallPaper != null) {
                if (attached && chatBackgroundDrawable != null) {
                    chatBackgroundDrawable.onDetachedFromWindow(ThemeSmallPreviewView.this);
                }
                chatBackgroundDrawable = new ChatBackgroundDrawable(wallPaper, false, true);
                chatBackgroundDrawable.setParent(this);
                if (attached) {
                    chatBackgroundDrawable.onAttachedToWindow(ThemeSmallPreviewView.this);
                }
            } else {
                if (attached && chatBackgroundDrawable != null) {
                    chatBackgroundDrawable.onDetachedFromWindow(ThemeSmallPreviewView.this);
                }
                chatBackgroundDrawable = null;
            }
        }
        backupImageView.setVisibility(item.chatTheme.isAnyStub() && fallbackWallpaper != null ? View.GONE : View.VISIBLE);

        if (itemChanged || darkModeChanged) {
            if (patternLoad != null) {
                patternLoad.cancel();
                patternLoad = null;
            }
            final int generation = ++previewGeneration;
            animateOutThemeDrawable = animated && !itemChanged && hasPreviousPalette && interruptedPalette == null ? themeDrawable : null;
            paletteFrom = interruptedPalette;
            changeThemeProgress = 0f;
            transitionPending = true;
            waitingForPattern = false;
            patternFailed = false;
            themeDrawable = new ThemeDrawable();
            updatePreviewBackground(themeDrawable);
            final Drawable targetPreview = themeDrawable.previewDrawable;
            final int targetPatternColor = patternColor;
            final long themeId = item.chatTheme.getThemeId(lastThemeIndex);
            if (themeId != 0) {
                TLRPC.WallPaper wallPaper = item.chatTheme.getWallpaper(lastThemeIndex);
                if (wallPaper != null && wallPaper.document != null && targetPreview instanceof MotionBackgroundDrawable) {
                    waitingForPattern = true;
                    final int intensity = wallPaper.settings == null ? 100 : wallPaper.settings.intensity;
                    final String key = patternKey(wallPaper);
                    if (!reusePattern(targetPreview, key, intensity >= 0 ? 100 : -100, targetPatternColor)) {
                        loadPattern(wallPaper.document, targetPreview, key, intensity >= 0 ? 100 : -100, targetPatternColor);
                    }
                }
            } else {
                Theme.ThemeInfo themeInfo = item.chatTheme.getThemeInfo(lastThemeIndex);
                Theme.ThemeAccent accent = null;

                if (themeInfo.themeAccentsMap != null) {
                    accent = themeInfo.themeAccentsMap.get(item.chatTheme.getAccentId(lastThemeIndex));
                }

                if (accent != null && accent.info != null && accent.info.settings.size() > 0) {
                    TLRPC.WallPaper wallPaper = accent.info.settings.get(0).wallpaper;

                    if (wallPaper != null && wallPaper.document != null && targetPreview instanceof MotionBackgroundDrawable) {
                        waitingForPattern = true;
                        TLRPC.Document wallpaperDocument = wallPaper.document;
                        final String key = patternKey(wallPaper);
                        final int intensity = wallPaper.settings == null || wallPaper.settings.intensity >= 0 ? 100 : -100;
                        if (!reusePattern(targetPreview, key, intensity, targetPatternColor)) {
                            loadPattern(wallpaperDocument, targetPreview, key, intensity, targetPatternColor);
                        }
                    }
                } else if (accent != null && accent.info == null && targetPreview instanceof MotionBackgroundDrawable) {
                    waitingForPattern = true;
                    int intensity = (int) (accent.patternIntensity * 100);
                    if (!reusePattern(targetPreview, "default", intensity, targetPatternColor)) {
                        ChatThemeController.chatThemeQueue.postRunnable(() -> {
                            final Bitmap result = decodeDefaultPattern();
                            AndroidUtilities.runOnUIThread(() -> {
                                if (generation == previewGeneration && chatThemeItem == item && targetPreview instanceof MotionBackgroundDrawable) {
                                    applyPattern(targetPreview, "default", result, intensity, targetPatternColor, false);
                                }
                            });
                        });
                    }
                }
            }
        }

        if (!animated) {
            backupImageView.animate().cancel();;
            backupImageView.setScaleX(1f);
            backupImageView.setScaleY(1f);
            AndroidUtilities.cancelRunOnUIThread(animationCancelRunnable);
            if (backupImageView.getImageReceiver().getLottieAnimation() != null) {
                backupImageView.getImageReceiver().getLottieAnimation().stop();
                backupImageView.getImageReceiver().getLottieAnimation().setCurrentFrame(0, false);
            }
        }

        if (chatThemeItem.chatTheme == null || chatThemeItem.chatTheme.isAnyStub()) {
            setContentDescription(LocaleController.getString(R.string.ChatNoTheme));
        } else {
            setContentDescription(chatThemeItem.chatTheme.getEmoticonOrSlug());
        }
    }

    boolean isSelected;

    public void setSelected(boolean selected, boolean animated) {
        if (!animated) {
            if (strokeAlphaAnimator != null) {
                strokeAlphaAnimator.cancel();
            }
            isSelected = selected;
            selectionProgress = selected ? 1f : 0;
            invalidate();
            return;
        }
        if (isSelected != selected) {
            float currentProgress = selectionProgress;
            if (strokeAlphaAnimator != null) {
                strokeAlphaAnimator.cancel();
            }
            strokeAlphaAnimator = ValueAnimator.ofFloat(currentProgress, selected ? 1f : 0);
            strokeAlphaAnimator.addUpdateListener(valueAnimator -> {
                selectionProgress = (float) valueAnimator.getAnimatedValue();
                invalidate();
            });
            strokeAlphaAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    selectionProgress = selected ? 1f : 0;
                    invalidate();
                }
            });
            strokeAlphaAnimator.setDuration(250);
            strokeAlphaAnimator.start();
        }
        isSelected = selected;
    }

    private Bitmap prescaleBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        float scale = Math.max(AndroidUtilities.dp(PATTERN_BITMAP_MAXWIDTH) / bitmap.getWidth(), AndroidUtilities.dp(PATTERN_BITMAP_MAXHEIGHT) / bitmap.getHeight());
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0 || Math.abs(scale - 1f) < .0125f) {
            return bitmap;
        }
        int w = (int) (bitmap.getWidth() * scale);
        int h = (int) (bitmap.getHeight() * scale);
        if (h <= 0 || w <= 0) {
            return bitmap;
        }
        return Bitmap.createScaledBitmap(bitmap, w, h, true);
    }

    @Override
    public void setBackgroundColor(int color) {
        backgroundFillPaint.setColor(getThemedColor(Theme.key_dialogBackgroundGray));
        if (noThemeTextPaint != null) {
            noThemeTextPaint.setColor(getThemedColor(Theme.key_chat_emojiPanelTrendingDescription));
        }
        invalidate();
    }

    private void fillOutBubblePaint(Paint paint, List<Integer> messageColors) {
        if (messageColors.size() > 1) {
            int[] colors = new int[messageColors.size()];
            for (int i = 0; i != messageColors.size(); ++i) {
                colors[i] = messageColors.get(i) | 0xFF000000;
            }
            float top = INNER_RECT_SPACE + AndroidUtilities.dp(8);
            paint.setShader(new LinearGradient(0f, top, 0f, top + BUBBLE_HEIGHT, colors, null, Shader.TileMode.CLAMP));
        } else {
            paint.setShader(null);
        }
    }

    public void updatePreviewBackground(ThemeDrawable themeDrawable) {
        if (chatThemeItem == null || chatThemeItem.chatTheme == null) {
            return;
        }
        EmojiThemes.ThemeItem themeItem = chatThemeItem.chatTheme.getThemeItem(chatThemeItem.themeIndex);
        int color = themeItem.inBubbleColor;
        if (themeUserByUserId != 0) {
            color = themeItem.patternBgColor;
        }
        themeDrawable.inBubblePaint.setColor(color);
        color = themeItem.outBubbleColor;
        themeDrawable.outBubblePaintSecond.setColor(color);

        int strokeColor = chatThemeItem.chatTheme.isAnyStub()
                ? getThemedColor(Theme.key_featuredStickers_addButton)
                : themeItem.outLineColor;
        int strokeAlpha = themeDrawable.strokePaint.getAlpha();
        themeDrawable.strokePaint.setColor(strokeColor);
        themeDrawable.strokePaint.setAlpha(strokeAlpha);


        final ITheme iTheme = chatThemeItem.chatTheme.getITheme(chatThemeItem.themeIndex);

        if (iTheme != null && iTheme.getThemeId() != 0) {
            int index = chatThemeItem.chatTheme.getSettingsIndex(chatThemeItem.themeIndex);
            TLRPC.ThemeSettings themeSettings = iTheme.getThemeSettings(index);
            fillOutBubblePaint(themeDrawable.outBubblePaintSecond, themeSettings.message_colors);

            themeDrawable.outBubblePaintSecond.setAlpha(255);
            getPreviewDrawable(iTheme, index);
        } else {
            EmojiThemes.ThemeItem item = chatThemeItem.chatTheme.getThemeItem(chatThemeItem.themeIndex);
            getPreviewDrawable(item);
        }
        themeDrawable.previewDrawable = chatThemeItem.previewDrawable;
        invalidate();
    }

    private Drawable getPreviewDrawable(ITheme theme, int settingsIndex) {
        if (chatThemeItem == null) {
            return null;
        }

        int color1 = 0;
        int color2 = 0;
        int color3 = 0;
        int color4 = 0;

        Drawable drawable;
        if (settingsIndex >= 0) {
            TLRPC.ThemeSettings themeSettings = theme.getThemeSettings(settingsIndex);
            TLRPC.WallPaperSettings wallPaperSettings = themeSettings.wallpaper.settings;
            color1 = wallPaperSettings.background_color;
            color2 = wallPaperSettings.second_background_color;
            color3 = wallPaperSettings.third_background_color;
            color4 = wallPaperSettings.fourth_background_color;
        }
        if (color2 != 0) {
            MotionBackgroundDrawable motionBackgroundDrawable = new MotionBackgroundDrawable(color1, color2, color3, color4, true);
            patternColor = motionBackgroundDrawable.getPatternColor();
            drawable = motionBackgroundDrawable;
        } else {
            drawable = new MotionBackgroundDrawable(color1, color1, color1, color1, true);
            patternColor = Color.BLACK;
        }
        chatThemeItem.previewDrawable = drawable;

        return drawable;
    }

    private Drawable getPreviewDrawable(EmojiThemes.ThemeItem item) {
        if (chatThemeItem == null) {
            return null;
        }
        Drawable drawable = null;

        int color1 = item.patternBgColor;
        int color2 = item.patternBgGradientColor1;
        int color3 = item.patternBgGradientColor2;
        int color4 = item.patternBgGradientColor3;
        int rotation = item.patternBgRotation;

        if (item.themeInfo.getAccent(false) != null) {
            if (color2 != 0) {
                MotionBackgroundDrawable motionBackgroundDrawable = new MotionBackgroundDrawable(color1, color2, color3, color4, rotation, true);
                patternColor = motionBackgroundDrawable.getPatternColor();
                drawable = motionBackgroundDrawable;
            } else {
                drawable = new MotionBackgroundDrawable(color1, color1, color1, color1, rotation, true);
                patternColor = Color.BLACK;
            }
        } else {
            if (color1 != 0 && color2 != 0) {
                drawable = new MotionBackgroundDrawable(color1, color2, color3, color4, rotation, true);
            } else if (color1 != 0) {
                drawable = new ColorDrawable(color1);
            } else if (item.themeInfo != null && (item.themeInfo.previewWallpaperOffset > 0 || item.themeInfo.pathToWallpaper != null)) {
                Bitmap wallpaper = AndroidUtilities.getScaledBitmap(AndroidUtilities.dp(112), AndroidUtilities.dp(134), item.themeInfo.pathToWallpaper, item.themeInfo.pathToFile, item.themeInfo.previewWallpaperOffset);
                if (wallpaper != null) {
                    BitmapDrawable bitmapDrawable = new BitmapDrawable(wallpaper);
                    bitmapDrawable.setFilterBitmap(true);
                    drawable = bitmapDrawable;
                }
            } else if (!(chatThemeItem.chatTheme != null && chatThemeItem.chatTheme.isAnyStub())) {
                drawable = new MotionBackgroundDrawable(0xffdbddbb, 0xff6ba587, 0xffd5d88d, 0xff88b884, true);
            }
        }

        chatThemeItem.previewDrawable = drawable;

        return drawable;
    }

    private StaticLayout getNoThemeStaticLayout() {
        if (textLayout != null) {
            return textLayout;
        }
        noThemeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG + TextPaint.SUBPIXEL_TEXT_FLAG);
        noThemeTextPaint.setColor(getThemedColor(Theme.key_chat_emojiPanelTrendingDescription));
        noThemeTextPaint.setTextSize(AndroidUtilities.dp(noThemeStringTextSize()));
        noThemeTextPaint.setTypeface(AndroidUtilities.bold());
        int width = AndroidUtilities.dp(52);
        if (currentType == TYPE_CHANNEL || currentType == TYPE_GRID_CHANNEL) {
            width = AndroidUtilities.dp(77);
        }
        textLayout = StaticLayoutEx.createStaticLayout2(
                noThemeString(),
                noThemeTextPaint,
                width,
                Layout.Alignment.ALIGN_CENTER,
                1f, 0f, true,
                TextUtils.TruncateAt.END,
                width,
                3
        );
        return textLayout;
    }

    protected int noThemeStringTextSize() {
        return 14;
    }

    protected String noThemeString() {
        return LocaleController.getString(R.string.ChatNoTheme);
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }


    public void playEmojiAnimation() {
        if (backupImageView.getImageReceiver().getLottieAnimation() != null) {
            AndroidUtilities.cancelRunOnUIThread(animationCancelRunnable);
            backupImageView.setVisibility(View.VISIBLE);
            if (!backupImageView.getImageReceiver().getLottieAnimation().isRunning) {
                backupImageView.getImageReceiver().getLottieAnimation().setCurrentFrame(0, true);
                backupImageView.getImageReceiver().getLottieAnimation().start();
            }
            backupImageView.animate().scaleX(2f).scaleY(2f).setDuration(300).setInterpolator(AndroidUtilities.overshootInterpolator).start();

            AndroidUtilities.runOnUIThread(animationCancelRunnable = () -> {
                animationCancelRunnable = null;
                backupImageView.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
            }, 2500);
        }
    }

    public void cancelAnimation() {
        if (animationCancelRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(animationCancelRunnable);
            animationCancelRunnable.run();
        }
    }

    private class ThemeDrawable {

        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outBubblePaintSecond = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inBubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Drawable previewDrawable;
        Drawable rotateDrawable;

        ThemeDrawable() {
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(AndroidUtilities.dp(2));
        }

        public void drawBackground(Canvas canvas, float alpha) {
            drawPreviewBackground(canvas, alpha);
        }
        private void drawPreviewBackground(Canvas canvas, float alpha) {
            if (previewDrawable != null) {
                canvas.save();
                canvas.clipPath(clipPath);
                if (previewDrawable instanceof BitmapDrawable) {
                    int drawableW = previewDrawable.getIntrinsicWidth();
                    int drawableH = previewDrawable.getIntrinsicHeight();
                    if (drawableW / (float) drawableH >  getWidth() / (float) getHeight()) {
                        int w = (int) (getWidth() * (float) drawableH / drawableW);
                        int padding = (w - getWidth()) / 2;
                        previewDrawable.setBounds(padding, 0, padding + w , getHeight());
                    } else {
                        int h = (int) (getHeight() * (float) drawableH / drawableW);
                        int padding = (getHeight() - h) / 2;
                        previewDrawable.setBounds(0, padding, getWidth(), padding + h);
                    }
                } else {
                    previewDrawable.setBounds(0, 0, getWidth(), getHeight());
                }
                previewDrawable.setAlpha((int) (255 * alpha));
                previewDrawable.draw(canvas);
                if (previewDrawable instanceof ColorDrawable || (previewDrawable instanceof MotionBackgroundDrawable && ((MotionBackgroundDrawable) previewDrawable).isOneColor())) {
                    int wasAlpha = outlineBackgroundPaint.getAlpha();
                    outlineBackgroundPaint.setAlpha((int) (wasAlpha * alpha));
                    float padding = INNER_RECT_SPACE;
                    AndroidUtilities.rectTmp.set(padding, padding, getWidth() - padding, getHeight() - padding);
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, INNER_RADIUS, INNER_RADIUS, outlineBackgroundPaint);
                    outlineBackgroundPaint.setAlpha(wasAlpha);
                }
                canvas.restore();
            } else if (!(chatThemeItem != null && chatThemeItem.chatTheme != null && chatThemeItem.chatTheme.isAnyStub() && chatBackgroundDrawable != null)) {
                canvas.drawRoundRect(rectF, INNER_RADIUS, INNER_RADIUS, backgroundFillPaint);
            }
        }

        public void draw(Canvas canvas, float alpha) {
            if (isSelected || strokeAlphaAnimator != null) {
                strokePaint.setAlpha((int) (selectionProgress * alpha * 255));
                float rectSpace = strokePaint.getStrokeWidth() * 0.5f + AndroidUtilities.dp(4) * (1f - selectionProgress);
                rectF.set(rectSpace, rectSpace, getWidth() - rectSpace, getHeight() - rectSpace);
                canvas.drawRoundRect(rectF, STROKE_RADIUS, STROKE_RADIUS, strokePaint);
            }
            outBubblePaintSecond.setAlpha((int) (255 * alpha));
            inBubblePaint.setAlpha((int) (255 * alpha));
            rectF.set(INNER_RECT_SPACE, INNER_RECT_SPACE, getWidth() - INNER_RECT_SPACE, getHeight() - INNER_RECT_SPACE);

            if (chatThemeItem.chatTheme == null || (chatThemeItem.chatTheme.isAnyStub() && chatThemeItem.chatTheme.wallpaper == null)) {
                if (fallbackWallpaper == null) {
                    canvas.drawRoundRect(rectF, INNER_RADIUS, INNER_RADIUS, backgroundFillPaint);
                    canvas.save();
                    StaticLayout textLayout = getNoThemeStaticLayout();
                    canvas.translate((getWidth() - textLayout.getWidth()) * 0.5f, AndroidUtilities.dp(18));
                    textLayout.draw(canvas);
                    canvas.restore();
                }
            } else if (currentType != TYPE_GRID_CHANNEL) {
                if (currentType == TYPE_QR) {
                    if (chatThemeItem.icon != null) {
                        float left = (getWidth() - chatThemeItem.icon.getWidth()) * 0.5f;
                        canvas.drawBitmap(chatThemeItem.icon, left, AndroidUtilities.dp(21), null);
                    }
                } else {
                    float bubbleTop = INNER_RECT_SPACE + AndroidUtilities.dp(8);
                    float bubbleLeft = INNER_RECT_SPACE + AndroidUtilities.dp(currentType == TYPE_CHANNEL ? 5 : 22);
                    if (currentType == TYPE_DEFAULT || currentType == TYPE_CHANNEL) {
                        rectF.set(bubbleLeft, bubbleTop, bubbleLeft + BUBBLE_WIDTH * (currentType == TYPE_CHANNEL ? 1.2f : 1f), bubbleTop + BUBBLE_HEIGHT);
                    } else {
                        bubbleTop = getMeasuredHeight() * 0.12f;
                        bubbleLeft = getMeasuredWidth() - getMeasuredWidth() * 0.65f;
                        float bubbleRight = getMeasuredWidth() - getMeasuredWidth() * 0.1f;
                        float bubbleBottom = getMeasuredHeight() * 0.32f;
                        rectF.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom);
                    }

                    Paint paint = currentType == TYPE_CHANNEL ? inBubblePaint : outBubblePaintSecond;
                    if (currentType == TYPE_DEFAULT || currentType == TYPE_CHANNEL) {
                        canvas.drawRoundRect(rectF, rectF.height() * 0.5f, rectF.height() * 0.5f, paint);
                    } else {
                        messageDrawableOut.setBounds((int) rectF.left, (int) rectF.top - AndroidUtilities.dp(2), (int) rectF.right + AndroidUtilities.dp(4), (int) rectF.bottom + AndroidUtilities.dp(2));
                        messageDrawableOut.setRoundRadius((int) (rectF.height() * 0.5f));
                        messageDrawableOut.draw(canvas, paint);
                    }

                    if (currentType == TYPE_DEFAULT || currentType == TYPE_CHANNEL) {
                        bubbleLeft = INNER_RECT_SPACE + AndroidUtilities.dp(5);
                        bubbleTop += BUBBLE_HEIGHT + AndroidUtilities.dp(4);
                        rectF.set(bubbleLeft, bubbleTop, bubbleLeft + BUBBLE_WIDTH * (currentType == TYPE_CHANNEL ? 0.8f : 1f), bubbleTop + BUBBLE_HEIGHT);
                    } else {
                        bubbleTop = getMeasuredHeight() * 0.35f;
                        bubbleLeft = getMeasuredWidth() * 0.1f;
                        float bubbleRight = getMeasuredWidth() * 0.65f;
                        float bubbleBottom = getMeasuredHeight() * 0.55f;
                        rectF.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom);
                    }

                    if (currentType == TYPE_DEFAULT || currentType == TYPE_CHANNEL) {
                        canvas.drawRoundRect(rectF, rectF.height() * 0.5f, rectF.height() * 0.5f, inBubblePaint);

                        if (themeUserByUserId != 0) {
                            float cy = rectF.centerY();
                            float cx = rectF.left + rectF.height() / 2f;
                            float cx2 = rectF.right - rectF.height() / 2f;
                            rectF.set(
                                cx - AndroidUtilities.dp(8),
                                cy - AndroidUtilities.dp(8),
                                cx + AndroidUtilities.dp(8),
                                cy + AndroidUtilities.dp(8)
                            );
                            avatarImageReceiver.setImageCoords(rectF);
                            avatarImageReceiver.draw(canvas);

                            if (rotateDrawable == null) {
                                rotateDrawable = getContext().getDrawable(R.drawable.mini_replace_16).mutate();
                            }
                            rotateDrawable.setBounds(
                                (int) cx2 - AndroidUtilities.dp(8),
                                (int) cy - AndroidUtilities.dp(8),
                                (int) cx2 + AndroidUtilities.dp(8),
                                (int) cy + AndroidUtilities.dp(8)
                            );
                            rotateDrawable.draw(canvas);
                        }
                    } else {
                        messageDrawableIn.setBounds((int) rectF.left - AndroidUtilities.dp(4), (int) rectF.top - AndroidUtilities.dp(2), (int) rectF.right, (int) rectF.bottom + AndroidUtilities.dp(2));
                        messageDrawableIn.setRoundRadius((int) (rectF.height() * 0.5f));
                        messageDrawableIn.draw(canvas, inBubblePaint);
                    }
                }
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
        attached = true;
        if (chatThemeItem != null && lastThemeIndex != chatThemeItem.themeIndex) {
            setItem(chatThemeItem, parentDialogId, false);
        }
        resumePatternLoad();
        if (chatBackgroundDrawable != null) {
            chatBackgroundDrawable.onAttachedToWindow(ThemeSmallPreviewView.this);
        }
        avatarImageReceiver.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
        attached = false;
        if (patternLoad != null) {
            patternLoad.cancel();
        }
        if (chatBackgroundDrawable != null) {
            chatBackgroundDrawable.onDetachedFromWindow(ThemeSmallPreviewView.this);
        }
        avatarImageReceiver.onDetachedFromWindow();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded) {
            invalidate();
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setEnabled(true);
        info.setSelected(isSelected);
    }
}
