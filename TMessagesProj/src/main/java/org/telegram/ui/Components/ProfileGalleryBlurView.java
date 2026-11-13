/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.ui.Components.ProfileMetaballView.profileBlurQueue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.RequiresApi;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ProfileActivity;

import java.util.HashSet;
public class ProfileGalleryBlurView extends View {

    private boolean usingRenderNode = Build.VERSION.SDK_INT >= 31;

    private final ProfileMetaballView.BlurBitmapHolder[] nextFrame = new ProfileMetaballView.BlurBitmapHolder[3];
    private final ProfileMetaballView.BlurBitmapHolder[] currentFrame = new ProfileMetaballView.BlurBitmapHolder[3];

    private final HashSet<ProfileMetaballView.BlurBitmapHolder> hardwareFrames = new HashSet<>();
    private final View[] sourceViews = new View[3];
    private final int[] sourceWidths = new int[3];
    private final int[] sourceHeights = new int[3];
    private final Runnable[] sourceCallbacks = new Runnable[3];
    private BlurBatch activeBatch;
    private long captureGeneration;
    private boolean capturing;
    private boolean softwareDetached = true;
    private Runnable captureInvalidation;
    private int captureWidth, captureHeight, captureBlurSize, captureActionSize;
    private long captureDialogId = Long.MIN_VALUE;

    private final Paint[] paints = new Paint[]{
            new Paint(),
            new Paint(),
    };

    private ProfileGalleryView view;
    public int actionSize;
    public int size;

    private final Runnable invalidateTask = this::updateContent;

    private int currentPosition = -1;
    private int offset;

    private boolean loopInvalidate = false;

    private boolean sizeChanged = false;
    private boolean needNewFrame = false;
    private long lastCaptureTime = -1;

    private RenderNode blurNode;

    private final SizeNotifierFrameLayout.IViewWithInvalidateCallback[] listeners =
            new SizeNotifierFrameLayout.IViewWithInvalidateCallback[3];

    private final AnimatedFloat alpha = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.DEFAULT);

    private static final class FrameCapture {
        final int slot, page, width, height;
        final View source;
        final ProfileMetaballView.BlurBitmapHolder holder;
        final Bitmap bitmap;

        FrameCapture(int slot, int page, View source, ProfileMetaballView.BlurBitmapHolder holder) {
            this.slot = slot;
            this.page = page;
            this.source = source;
            this.holder = holder;
            bitmap = holder.bitmap;
            width = source.getMeasuredWidth();
            height = source.getMeasuredHeight();
        }
    }

    private static final class BlurBatch {
        final ProfileGalleryBlurView owner;
        final ProfileGalleryView gallery;
        final long generation, dialogId;
        final int position, width, height, blurSize, actionSize;
        final FrameCapture[] frames = new FrameCapture[3];

        BlurBatch(ProfileGalleryBlurView owner, ProfileGalleryView gallery) {
            this.owner = owner;
            this.gallery = gallery;
            generation = owner.captureGeneration;
            dialogId = gallery.getDialogId();
            position = owner.currentPosition;
            width = gallery.getMeasuredWidth();
            height = gallery.getMeasuredHeight();
            blurSize = owner.size;
            actionSize = owner.actionSize;
        }
    }

    private ViewPager.OnPageChangeListener listener;

    private ViewPager.OnPageChangeListener createPageListener(ProfileGalleryView gallery) {
        return new ViewPager.OnPageChangeListener() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (view != gallery || listener != this) return;
                int oldPosition = currentPosition;
                int oldOffset = offset;
            if (!usingRenderNode) {
                    if (position != oldPosition) {
                        captureGeneration++;
                        clearSourceListeners();
                    }
                if (Math.abs(position - currentPosition) == 1) {
                    if (position > currentPosition) {
                        swap(0, 1, 1);
                            swapFrames(1, 2);
                            clearOutput(1);
                    } else if (position < currentPosition) {
                        swap(1, 0, 0);
                        swap(2, 0, -1);
                            clearOutput(2);
                        }
                    } else if (position != oldPosition) {
                        clearCurrentFrames();
                    }
                }

            currentPosition = position;
            offset = positionOffsetPixels;

            if (oldPosition != currentPosition) {
                updateContent();
            } else if (oldOffset != offset) {
                    if (!usingRenderNode && (oldOffset == 0 || offset == 0)) {
                        needNewFrame = true;
                    }
                postInvalidateOnAnimation();
                } else if (!usingRenderNode && view != null && haveSourceChanges(view)) {
                    updateContent();
            }
        }

        @Override
        public void onPageSelected(int position) {
                if (view != gallery || listener != this) return;
                if (!usingRenderNode) updateContent();
        }

        @Override
        public void onPageScrollStateChanged(int state) {

                if (view != gallery || listener != this) return;
                if (!usingRenderNode && state == ViewPager.SCROLL_STATE_IDLE) updateContent();
        }
    };

    }
    public ProfileGalleryBlurView(Context context) {
        super(context);
        alpha.set(1f, true);

        usingRenderNode &= SharedConfig.useNewBlur;
        if (usingRenderNode) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            return;
        }

        setLayerType(View.LAYER_TYPE_SOFTWARE, paints[0]);
        setLayerType(View.LAYER_TYPE_SOFTWARE, paints[1]);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), size + actionSize);
    }

    public void restartAlpha() {
        if (usingRenderNode) {
            alpha.set(0f, true);
            invalidate();
        }
    }

    public boolean isUsingRenderNode() {
        return usingRenderNode;
    }


    private void swap(int from, int to, int clear) {
        swapFrames(from, to);

        if (from == 2) {
            paints[to].setShader(null);
            if (isDrawable(currentFrame[to])) {
                applyShader(currentFrame[to].bitmap, to);
            }
        } else {
            Paint tmp = paints[from];
            paints[from] = paints[to];
            paints[to] = tmp;
        }

        if (clear != -1) {
            paints[clear].setShader(null);
            if (hasBitmap(nextFrame[clear]) && !nextFrame[clear].isBusy) {
                nextFrame[clear].clear();
            }
        }
    }

    private void swapFrames(int from, int to) {
            ProfileMetaballView.BlurBitmapHolder tmp2 = nextFrame[from];
            nextFrame[from] = nextFrame[to];
            nextFrame[to] = tmp2;

            tmp2 = currentFrame[from];
            currentFrame[from] = currentFrame[to];
            currentFrame[to] = tmp2;

    }

    private void clearOutput(int slot) {
        if (slot < paints.length) paints[slot].setShader(null);
        final ProfileMetaballView.BlurBitmapHolder frame = currentFrame[slot];
        if (hardwareFrames.remove(frame)) {
            currentFrame[slot] = null;
        } else if (hasBitmap(frame)) {
            frame.clear();
        }
    }

    private void updateContent() {
        if (view == null) return;
        needNewFrame = true;
        if (usingRenderNode) {
            postInvalidateOnAnimation();
            } else {
            requestCapture();
        }
    }

    public void notifyUpdateSize() {
        captureGeneration++;
        sizeChanged = true;
        postInvalidateOnAnimation();
    }

    public void setSize(int size) {
        final int blurSize = (int) (dp(64) * 1.5f);
        if (this.actionSize == size && this.size == blurSize) {
            return;
        }
        this.actionSize = size;
        this.size = blurSize;
        captureGeneration++;
        sizeChanged = true;
        requestLayout();
        postInvalidateOnAnimation();
    }

    public void setView(ProfileGalleryView view) {
        destroy();
        this.view = view;
        if (view == null) return;
        currentPosition = view.getCurrentItem();
        offset = 0;
        needNewFrame = sizeChanged = true;
        listener = createPageListener(view);
        view.addOnPageChangeListener(listener);
        if (!usingRenderNode) requestCapture();
    }

    public void destroy() {
        captureGeneration++;
        cancelCaptureInvalidation();
        final ProfileGalleryView gallery = view;
        final ViewPager.OnPageChangeListener oldListener = listener;
            view = null;
        listener = null;
        if (gallery != null && oldListener != null) gallery.removeOnPageChangeListener(oldListener);
        clearSourceListeners();
        loopInvalidate = false;
        needNewFrame = sizeChanged = true;
        captureWidth = captureHeight = captureBlurSize = captureActionSize = 0;
        captureDialogId = Long.MIN_VALUE;
        lastCaptureTime = -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (blurNode != null) {
                blurNode.discardDisplayList();
                blurNode = null;
            }
        }
        retireNextFrames();
        clearCurrentFrames();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        softwareDetached = false;
        if (!usingRenderNode) {
            captureGeneration++;
            needNewFrame = sizeChanged = true;
            requestCapture();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        softwareDetached = true;
        if (!usingRenderNode) {
            captureGeneration++;
            cancelCaptureInvalidation();
            clearSourceListeners();
            retireNextFrames();
            loopInvalidate = false;
            needNewFrame = sizeChanged = true;
            lastCaptureTime = -1;
        }
        super.onDetachedFromWindow();
    }

    private static boolean hasBitmap(ProfileMetaballView.BlurBitmapHolder frame) {
        return frame != null && !frame.destroying && !frame.destroyed
                && frame.bitmap != null && !frame.bitmap.isRecycled();
    }

    private static boolean isDrawable(ProfileMetaballView.BlurBitmapHolder frame) {
        return hasBitmap(frame) && !frame.isBusy && frame.hasContent;
    }

    private static void retire(ProfileMetaballView.BlurBitmapHolder frame) {
        if (frame != null && !frame.destroying && !frame.destroyed) frame.recycle();
    }

    private void retireNextFrames() {
        for (int i = 0; i < nextFrame.length; i++) {
            retire(nextFrame[i]);
                    nextFrame[i] = null;
                }
    }

    private void clearCurrentFrames() {
        paints[0].setShader(null);
        paints[1].setShader(null);
        for (int i = 0; i < currentFrame.length; i++) {
            if (!hardwareFrames.remove(currentFrame[i])) retire(currentFrame[i]);
                    currentFrame[i] = null;
                }
    }

    private void clearSourceListeners() {
        for (int i = 0; i < listeners.length; i++) {
            sourceCallbacks[i] = null;
            sourceViews[i] = null;
            sourceWidths[i] = sourceHeights[i] = 0;
                if (listeners[i] != null) {
                    listeners[i].listenInvalidate(null);
                    listeners[i] = null;
                }
            }
    }

    private void updateSoftwareSources(ProfileGalleryView gallery) {
        if (captureDialogId != gallery.getDialogId()) {
            captureDialogId = gallery.getDialogId();
            captureGeneration++;
            needNewFrame = sizeChanged = true;
            clearSourceListeners();
            retireNextFrames();
            clearCurrentFrames();
        }
        final int width = gallery.getMeasuredWidth(), height = gallery.getMeasuredHeight();
        if (captureWidth != width || captureHeight != height || captureBlurSize != size || captureActionSize != actionSize) {
            captureWidth = width;
            captureHeight = height;
            captureBlurSize = size;
            captureActionSize = actionSize;
            captureGeneration++;
            needNewFrame = sizeChanged = true;
            retireNextFrames();
        }
        boolean changed = false;
        for (int i = 0; i < sourceViews.length; i++) {
            final int slot = i;
            final View source = gallery.getItemViewAt(currentPosition + (i == 2 ? -1 : i));
            if (sourceViews[i] != source) {
                sourceCallbacks[i] = null;
                if (listeners[i] != null) listeners[i].listenInvalidate(null);
                listeners[i] = null;
                sourceViews[i] = source;
                changed = true;
                if (source instanceof SizeNotifierFrameLayout.IViewWithInvalidateCallback) {
                    final Runnable callback = new Runnable() {
                        @Override
                        public void run() {
                            if (Looper.myLooper() != Looper.getMainLooper()) {
                                AndroidUtilities.runOnUIThread(this);
                                return;
                            }
                            if (view == gallery && !softwareDetached && sourceViews[slot] == source
                                    && sourceCallbacks[slot] == this) updateContent();
                        }
            };
                    sourceCallbacks[i] = callback;
                    listeners[i] = (SizeNotifierFrameLayout.IViewWithInvalidateCallback) source;
                    listeners[i].listenInvalidate(callback);
                }
            }
            int w = source == null ? 0 : source.getMeasuredWidth();
            int h = source == null ? 0 : source.getMeasuredHeight();
            if (sourceWidths[i] != w || sourceHeights[i] != h) {
                sourceWidths[i] = w;
                sourceHeights[i] = h;
                changed = true;
            }
        }
        if (changed) {
            captureGeneration++;
            needNewFrame = true;
        }
        loopInvalidate = sourceViews[0] != null && listeners[0] == null
                || offset != 0 && sourceViews[1] != null && listeners[1] == null;
    }

    private boolean haveSourceChanges(ProfileGalleryView gallery) {
        if (captureDialogId != gallery.getDialogId()) return true;
        for (int i = 0; i < sourceViews.length; i++) {
            final View source = gallery.getItemViewAt(currentPosition + (i == 2 ? -1 : i));
            if (sourceViews[i] != source || source != null
                    && (sourceWidths[i] != source.getMeasuredWidth() || sourceHeights[i] != source.getMeasuredHeight())) return true;
        }
        return false;
    }

    private boolean canCapture(ProfileGalleryView gallery) {
        return gallery != null && view == gallery && !softwareDetached && gallery.isAttachedToWindow()
                && gallery.getVisibility() != GONE && !gallery.isZooming();
    }

    private void cancelCaptureInvalidation() {
        if (captureInvalidation != null) {
            AndroidUtilities.cancelRunOnUIThread(captureInvalidation);
            captureInvalidation = null;
        }
    }

    private void requestCapture() {
        final ProfileGalleryView gallery = view;
        if (usingRenderNode || capturing || activeBatch != null || !canCapture(gallery)
                || !(needNewFrame || sizeChanged || loopInvalidate)) return;
        if (captureInvalidation != null) return;
        final long generation = captureGeneration;
        final long delay = sizeChanged || lastCaptureTime < 0 ? 0
                : Math.max(0, 32L - (SystemClock.uptimeMillis() - lastCaptureTime));
        captureInvalidation = new Runnable() {
            @Override
            public void run() {
                if (captureInvalidation != this) return;
                captureInvalidation = null;
                if (captureGeneration != generation || !canCapture(gallery)) {
                    if (view == gallery) requestCapture();
                    return;
                }
            postInvalidateOnAnimation();
        }

        };
        AndroidUtilities.runOnUIThread(captureInvalidation, delay);
    }

    private boolean isBatchCurrent(BlurBatch batch) {
        final ProfileGalleryView gallery = batch.gallery;
        return view == gallery && !softwareDetached && gallery.isAttachedToWindow()
                && gallery.getVisibility() != GONE && captureGeneration == batch.generation
                && gallery.getDialogId() == batch.dialogId
                && currentPosition == batch.position && size == batch.blurSize && actionSize == batch.actionSize
                && gallery.getMeasuredWidth() == batch.width && gallery.getMeasuredHeight() == batch.height;
    }

    private boolean isFrameCurrent(BlurBatch batch, FrameCapture frame) {
        return nextFrame[frame.slot] == frame.holder && hasBitmap(frame.holder)
                && frame.holder.isBusy && frame.holder.bitmap == frame.bitmap
                && batch.gallery.getItemViewAt(frame.page) == frame.source
                && frame.source.getMeasuredWidth() == frame.width && frame.source.getMeasuredHeight() == frame.height;
    }

    private void releaseInput(FrameCapture frame) {
        final ProfileMetaballView.BlurBitmapHolder holder = frame.holder;
        holder.unlock();
        if (hasBitmap(holder)) {
            boolean retained = false;
            for (ProfileMetaballView.BlurBitmapHolder next : nextFrame) {
                if (next == holder) retained = true;
            }
            if (retained) holder.clear();
            else retire(holder);
        }
    }

    private static void blur(BlurBatch batch) {
        final boolean[] ready = new boolean[batch.frames.length];
        try {
            for (int i = 0; i < batch.frames.length; i++) {
                final FrameCapture frame = batch.frames[i];
                if (frame == null) continue;
                try {
                    Utilities.stackBlurBitmap(frame.bitmap, Math.max(10, frame.bitmap.getWidth() / 180));
                    ready[i] = true;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        } finally {
            AndroidUtilities.runOnUIThread(() -> batch.owner.onBlurFinished(batch, ready));
        }
    }

    private void onBlurFinished(BlurBatch batch, boolean[] ready) {
        if (activeBatch != batch) return;
        final boolean current = isBatchCurrent(batch);
        boolean stale = !current, published = false;
        for (int i = 0; i < batch.frames.length; i++) {
            final FrameCapture frame = batch.frames[i];
            if (frame == null) continue;
            if (current && isFrameCurrent(batch, frame)) {
                if (ready[i]) {
                    final ProfileMetaballView.BlurBitmapHolder previous = currentFrame[i];
                    frame.holder.unlock();
                    currentFrame[i] = frame.holder;
                    final boolean recordedOnHardware = hardwareFrames.remove(previous);
                    nextFrame[i] = recordedOnHardware ? null : previous;
                    applyShader(frame.bitmap, i);
                    if (!recordedOnHardware) {
                        if (hasBitmap(previous) && previous.canUse(frame.bitmap.getWidth(), frame.bitmap.getHeight())) {
                            previous.clear();
        } else {
                            retire(previous);
                            nextFrame[i] = null;
                        }
                    }
                    published = true;
                    continue;
                }
            } else {
                stale = true;
            }
            releaseInput(frame);
        }
        activeBatch = null;
        if (stale) needNewFrame = true;
        if (published) postInvalidateOnAnimation();
        if (stale || needNewFrame || sizeChanged || published && loopInvalidate) requestCapture();
    }

    private boolean captureNextFrame() {
        final ProfileGalleryView gallery = view;
        if (activeBatch != null || capturing || !canCapture(gallery)) return false;
        final int w = (int) (gallery.getMeasuredWidth() / 6.0f), h = (int) (size / 6.0f);
        if (w <= 0 || h <= 0) return false;
        cancelCaptureInvalidation();
        final BlurBatch batch = new BlurBatch(this, gallery);
        boolean submitted = false, retry = false;
        capturing = true;
        needNewFrame = sizeChanged = false;
        lastCaptureTime = SystemClock.uptimeMillis();
        try {
            boolean hasFrames = false;
            for (int i = 0; i < batch.frames.length; i++) {
                if (i == 2 && offset != 0) continue;
                final View source = sourceViews[i];
                if (source == null) continue;
                ProfileMetaballView.BlurBitmapHolder frame = nextFrame[i];
                if (frame != null && frame.isBusy) {
                    needNewFrame = true;
            return false;
        }
                if (!hasBitmap(frame) || !frame.canUse(w, h)) {
                    retire(frame);
                    nextFrame[i] = frame = new ProfileMetaballView.BlurBitmapHolder(w, h);
                }
                frame.clear();
                final FrameCapture capture = new FrameCapture(i, currentPosition + (i == 2 ? -1 : i), source, frame);
                batch.frames[i] = capture;
                frame.lock();
                final Canvas canvas = frame.canvas;
                final int save = canvas.save();
                try {
                    canvas.scale(1f / 6f, 1f / 6f);
                    canvas.translate(0, batch.blurSize - capture.height);
                    source.draw(canvas);
                } finally {
                    canvas.restoreToCount(save);
                }
                if (!isBatchCurrent(batch) || !isFrameCurrent(batch, capture)) {
                    retry = true;
                    needNewFrame = true;
                    return false;
                }
            frame.ready();
                hasFrames = true;
            }
            if (!hasFrames) return false;
            activeBatch = batch;
            submitted = profileBlurQueue.postRunnable(() -> blur(batch));
            if (!submitted) {
                activeBatch = null;
                needNewFrame = true;
            }
            return submitted;
        } catch (Exception e) {
            FileLog.e(e);
            if (activeBatch == batch) activeBatch = null;
            needNewFrame = true;
            return false;
        } finally {
            if (!submitted) {
                for (FrameCapture frame : batch.frames) if (frame != null) releaseInput(frame);
            }
            capturing = false;
            if (retry) requestCapture();
        }
    }

    private void applyShader(Bitmap bitmap, int i) {
        if (i < 0 || i >= 2 || size <= 0 || bitmap == null || bitmap.isRecycled()) {
            return;
        }


        LinearGradient alphaGradient = new LinearGradient(
                0, 0, 0, size / 6f,
                new int[]{Color.TRANSPARENT, Color.WHITE},
                new float[]{0f, AndroidUtilities.dpf2(56) / size},
                Shader.TileMode.CLAMP
        );
        BitmapShader bitmapShader = new BitmapShader(
                bitmap,
                Shader.TileMode.MIRROR,
                Shader.TileMode.MIRROR
        );
        ComposeShader composed = new ComposeShader(
                bitmapShader,
                alphaGradient,
                PorterDuff.Mode.DST_IN
        );
        paints[i].setShader(composed);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final ProfileGalleryView gallery = view;
        if (gallery == null) return;
        draw(canvas, null, gallery.getMeasuredWidth(), gallery.getMeasuredHeight(), false, 0f, 1f);
    }

    public void draw(Canvas canvas, ProfileActivity.AvatarImageView avatarImageView, float width, float height, boolean translate, float fraction, float alpha) {
        final ProfileGalleryView gallery = view;
        if (gallery == null || !gallery.isAttachedToWindow() || gallery.getVisibility() == GONE) {
            return;
        }

        if (usingRenderNode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (canvas.isHardwareAccelerated()) {
                if (avatarImageView == null && getVisibility() == View.VISIBLE && getAlpha() > 0f) {
                    drawRenderNode(canvas, width);
                } else if (avatarImageView != null) {
                    drawOpeningRenderNode(avatarImageView, canvas, width, height, fraction, alpha);
                }
                return;
            } else if (avatarImageView == null && !AndroidUtilities.makingGlobalBlurBitmap) {
                usingRenderNode = false;
                captureGeneration++;
                clearSourceListeners();
                needNewFrame = sizeChanged = true;
                setLayerType(View.LAYER_TYPE_SOFTWARE, paints[0]);
                setLayerType(View.LAYER_TYPE_SOFTWARE, paints[1]);
            } else {
                return;
            }
        }

        if (softwareDetached || width <= 0) return;
        updateSoftwareSources(gallery);
        if (gallery.isZooming()) {
            needNewFrame = true;
        } else if (activeBatch == null && !capturing && (needNewFrame || sizeChanged || loopInvalidate)) {
            if (!sizeChanged && lastCaptureTime >= 0 && SystemClock.uptimeMillis() - lastCaptureTime < 32L) {
                requestCapture();
            } else {
                captureNextFrame();
            }
        }
        if (view != gallery || softwareDetached) return;
        final int targetWidth = (int) (gallery.getMeasuredWidth() / 6f), targetHeight = (int) (size / 6f);
        if (targetWidth <= 0 || targetHeight <= 0) return;
        final float stripHeight = width * targetHeight / targetWidth;
        drawSoftwareFrame(canvas, 0, width, stripHeight, translate, fraction, alpha);
        if (offset != 0) drawSoftwareFrame(canvas, 1, width, stripHeight, translate, fraction, alpha);
    }

    private void drawSoftwareFrame(Canvas canvas, int slot, float width, float stripHeight, boolean translate, float fraction, float alpha) {
        final ProfileMetaballView.BlurBitmapHolder frame = currentFrame[slot];
        if (!isDrawable(frame)) {
            paints[slot].setShader(null);
            return;
        }
        if (paints[slot].getShader() == null) return;
        final int frameWidth = frame.bitmap.getWidth(), frameHeight = frame.bitmap.getHeight();
        if (frameWidth <= 0 || frameHeight <= 0 || stripHeight <= 0) return;
        if (canvas.isHardwareAccelerated()) hardwareFrames.add(frame);
        final int save = canvas.save();
        if (translate) canvas.translate(0, -stripHeight);
        canvas.translate(slot == 0 ? -offset : -offset + width, 0);
        final float scaleY = stripHeight / frameHeight;
        canvas.scale(width / frameWidth, scaleY);
        final float hAction = actionSize / scaleY;

                canvas.save();
                canvas.scale(1f, 2f, 0, frameHeight);
        canvas.drawRect(0, frameHeight, frameWidth, frameHeight + hAction, paints[slot]);
                canvas.restore();

        paints[slot].setAlpha((int) (0xFF * alpha));
        canvas.drawRect(0, frameHeight * fraction, frameWidth, frameHeight, paints[slot]);
        paints[slot].setAlpha(0xFF);
        canvas.restoreToCount(save);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void initRenderNode() {
        if (blurNode == null) {
            float scale = getRenderNodeScale();
            blurNode = new RenderNode("profileBlurNode");
            LinearGradient renderNodeAlphaGradient = new LinearGradient(
                    0, 0, 0, size / scale,
                    new int[]{Color.TRANSPARENT, Color.WHITE},
                    new float[]{0f, AndroidUtilities.dpf2(56) / size},
                    Shader.TileMode.CLAMP
            );

            float r = getBlurRadius();
            blurNode.setRenderEffect(RenderEffect.createBlendModeEffect(
                    RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP),
                    RenderEffect.createShaderEffect(renderNodeAlphaGradient),
                    BlendMode.DST_IN
            ));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void drawRenderNode(Canvas canvas, float width) {
        initRenderNode();

        if (listeners[0] != null) listeners[0].listenInvalidate(null);
        if (listeners[1] != null) listeners[1].listenInvalidate(null);

        float scale = getRenderNodeScale();
        blurNode.setPosition(0, 0, (int) Math.ceil(width / scale), (int) Math.ceil((size + actionSize) / scale));
        RecordingCanvas recordingCanvas = blurNode.beginRecording();
        recordingCanvas.scale(1f / scale, 1f / scale);

        recordingCanvas.save();
        recordingCanvas.translate(-offset, 0);
        drawViewWithRenderNode(recordingCanvas, 0);
        recordingCanvas.restore();

        if (offset != 0) {
            recordingCanvas.save();
            recordingCanvas.translate(-offset + width, 0f);
            drawViewWithRenderNode(recordingCanvas, 1);
            recordingCanvas.restore();
        }

        blurNode.endRecording();
        blurNode.setAlpha(alpha.set(1f));

        canvas.save();
        canvas.scale(scale, scale);
        canvas.drawRenderNode(blurNode);
        canvas.restore();

    }

    private void drawViewWithRenderNode(Canvas recordingCanvas, int index) {
        View drawingView = view.getItemViewAt(currentPosition + index);

        if (drawingView != null) {
            int h = drawingView.getMeasuredHeight();
            recordingCanvas.save();
            recordingCanvas.translate(0, size - h);
            drawingView.draw(recordingCanvas);
            recordingCanvas.restore();

            recordingCanvas.save();
            recordingCanvas.scale(1f, -1f);
            recordingCanvas.translate(0f, -h - size);
            recordingCanvas.scale(1f, 2f, 0, h);
            drawingView.draw(recordingCanvas);
            recordingCanvas.restore();
        }

        if (drawingView instanceof SizeNotifierFrameLayout.IViewWithInvalidateCallback) {
            listeners[index] = (SizeNotifierFrameLayout.IViewWithInvalidateCallback) drawingView;
            listeners[index].listenInvalidate(invalidateTask);
        } else {
            listeners[index] = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void drawOpeningRenderNode(ProfileActivity.AvatarImageView avatarImageView, Canvas canvas, float width, float height, float fraction, float alpha) {
        fraction = 1f - fraction;
        float openingScale = width / view.getMeasuredWidth();
        float croppedSize = size * fraction;
        float scaledSize = size * fraction * openingScale;
        float scale = getRenderNodeScale() * openingScale;
        initRenderNode();

        blurNode.setPosition(0, 0, (int) Math.ceil(width / scale), (int) Math.ceil((croppedSize + actionSize) / scale));
        final RecordingCanvas recordingCanvas = blurNode.beginRecording();
        recordingCanvas.scale(1f / scale, 1f / scale);

        final ImageReceiver imageReceiver = avatarImageView.animatedEmojiDrawable != null
                ? avatarImageView.animatedEmojiDrawable.getImageReceiver()
                : avatarImageView.imageReceiver;

        drawOpeningImageRenderNode(imageReceiver, recordingCanvas, scaledSize, height);
        if (avatarImageView.drawForeground && avatarImageView.foregroundAlpha > 0f) {
            drawOpeningImageRenderNode(avatarImageView.foregroundImageReceiver, recordingCanvas, scaledSize, height);
        }

        blurNode.endRecording();
        blurNode.setAlpha(alpha);

        canvas.translate(0f, -scaledSize);
        canvas.scale(scale, scale);
        canvas.drawRenderNode(blurNode);

    }

    private void drawOpeningImageRenderNode(
            ImageReceiver imageReceiver,
            Canvas recordingCanvas,
            float scaledSize,
            float height
    ) {
        if (imageReceiver == null) {
            return;
        }
        int oldR = imageReceiver.getRoundRadius()[0];
        imageReceiver.setRoundRadius(0);

        recordingCanvas.save();
        recordingCanvas.translate(0, scaledSize - height);
        imageReceiver.draw(recordingCanvas);
        recordingCanvas.restore();

        recordingCanvas.save();
        recordingCanvas.scale(1f, -1f);
        recordingCanvas.translate(0f, -height - scaledSize);
        recordingCanvas.scale(1f, 2f, 0, height);
        imageReceiver.draw(recordingCanvas);
        recordingCanvas.restore();

        imageReceiver.setRoundRadius(oldR);
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (alpha != 0f && usingRenderNode) {
            invalidate();
        }
    }

    private float getRenderNodeScale() {
        return dp(1);
    }

    private float getBlurRadius() {
        switch (SharedConfig.getDevicePerformanceClass()) {
            case SharedConfig.PERFORMANCE_CLASS_HIGH:
                return 20;
            case SharedConfig.PERFORMANCE_CLASS_AVERAGE:
                return 12;
            default:
            case SharedConfig.PERFORMANCE_CLASS_LOW:
                return 8;
        }
    }
}
