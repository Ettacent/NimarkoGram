package app.nimarkogram.messenger.mediaglow;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import java.lang.ref.WeakReference;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import app.nimarkogram.messenger.NimarkoConfig;

public final class MediaGlowController {

    private static final int MAX_SIDE = 256;             
    private static final int VIDEO_REFRESH_MS = 450;     
    private static final float VIDEO_BLEND_ALPHA = 0.32f;
    private static final float VIDEO_FADE_MS = 280f;     
    private static final long PHOTO_RETRY_INTERVAL_MS = 120L;
    private static final long NEW_MEDIA_CAPTURE_TIMEOUT_MS = 1800L;
    private static final long CLOSE_FADE_MS = 280L;      
    private static final int[] RETRY_DELAYS = {0, 120, 350}; 
    private static final DispatchQueue queue = new DispatchQueue("nimarko-media-glow");
    private static final PorterDuffXfermode ADD_XFERMODE = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile MediaGlowController instance;

    public static MediaGlowController getInstance() {
        MediaGlowController local = instance;
        if (local == null) {
            synchronized (MediaGlowController.class) {
                local = instance;
                if (local == null) instance = local = new MediaGlowController();
            }
        }
        return local;
    }

    private MediaGlowController() {}

    private Bitmap current;
    private Bitmap previous;
    private Bitmap pending;
    private boolean currentIsVideo;
    private boolean previousIsVideo;
    private boolean pendingIsVideo;
    private boolean pendingFirstOfItem;
    private long fadeStartedAt;
    private long closeFadeStartedAt;                                 
    private final Runnable closeReleaseRunnable = this::nmReleaseNow; 
    private float liveDismiss = 1f;  
    private float closeDismiss = 1f; 
    
    private float closeDstLeft, closeDstTop, closeDstRight, closeDstBottom;
    private float closePrevDstLeft, closePrevDstTop, closePrevDstRight, closePrevDstBottom;
    private float closeTransitionProgress = 1f;
    private int lastDrawWidth, lastDrawHeight;
    private boolean closeGeomValid;
    private boolean closePrevGeomValid;
    private volatile String lastSignature;
    private long lastVideoCaptureAt;
    private long lastVideoFrameRequestAt;
    private long lastPhotoCaptureAttemptAt;
    private long mediaChangedAt;
    private boolean active;
    private ImageReceiver pendingPhotoRecapture;
    
    private volatile int capturingGeneration = -1;
    private volatile int captureGeneration;
    private boolean capturedThisOpen;
    private boolean captureTimedOut;
    
    private WeakReference<View> containerRef;
    private WeakReference<TextureView> videoTextureRef;
    
    private volatile boolean forceNextCapture;
    private WeakReference<Object> ownerRef;

    private volatile int scrimColorPhoto = Color.argb(80, 0, 0, 0);
    private volatile int scrimColorVideo = Color.argb(100, 0, 0, 0);
    private volatile boolean darkTheme;

    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private void refreshTheme() {
        try {
            int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
            boolean dark = ColorUtils.calculateLuminance(bg) < 0.5;
            darkTheme = dark;
            if (dark) {
                int tint = ColorUtils.blendARGB(bg, Color.BLACK, 0.85f);  
                scrimColorPhoto = ColorUtils.setAlphaComponent(tint, 110);
                scrimColorVideo = ColorUtils.setAlphaComponent(tint, 135); 
            } else {
                scrimColorPhoto = Color.argb(70, 0, 0, 0);
                scrimColorVideo = Color.argb(90, 0, 0, 0);
            }
        } catch (Throwable t) {
            darkTheme = false;
            scrimColorPhoto = Color.argb(80, 0, 0, 0);
            scrimColorVideo = Color.argb(100, 0, 0, 0);
        }
    }

    public void onMediaChanged(Object owner, final ImageReceiver centerImage, final TextureView videoTexture) {
        if (owner == null) return;
        if (!NimarkoConfig.mediaGlow) {
            release(owner);
            return;
        }
        if (!isOwner(owner)) {
            View oldHost = containerRef != null ? containerRef.get() : null;
            nmReleaseNow();
            requestFrame(oldHost);
            ownerRef = new WeakReference<>(owner);
        }
        active = true;
        videoTextureRef = videoTexture == null ? null : new WeakReference<>(videoTexture);
        refreshTheme();
        
        if (pending != null) { recycle(pending); pending = null; }
        pendingIsVideo = false;
        pendingFirstOfItem = false;
        if (closeFadeStartedAt != 0) {
            
            recycle(previous);
            previous = null;
            previousIsVideo = false;
            closeTransitionProgress = 1f;
        }
        closeFadeStartedAt = 0;                              
        closeGeomValid = false;
        closePrevGeomValid = false;
        AndroidUtilities.cancelRunOnUIThread(closeReleaseRunnable);
        capturingGeneration = -1;
        pendingPhotoRecapture = null;
        capturedThisOpen = false;
        captureTimedOut = false;
        lastSignature = null;
        forceNextCapture = true;   
        lastVideoCaptureAt = 0;
        lastVideoFrameRequestAt = 0;
        lastPhotoCaptureAttemptAt = 0;
        mediaChangedAt = SystemClock.elapsedRealtime();
        final int gen = ++captureGeneration;
        scheduleRetry(centerImage, videoTexture, 0, gen);
        AndroidUtilities.runOnUIThread(() -> clearStaleGlowAfterTimeout(gen), NEW_MEDIA_CAPTURE_TIMEOUT_MS);
    }

    private void scheduleRetry(final ImageReceiver centerImage, final TextureView videoTexture, int attempt, int gen) {
        if (attempt >= RETRY_DELAYS.length) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (gen != captureGeneration || capturedThisOpen || !NimarkoConfig.mediaGlow) return;
            boolean isVideo = supportsAsyncVideoCapture() && isViewReady(videoTexture);
            maybeCapture(centerImage, videoTexture, isVideo, gen);
            scheduleRetry(centerImage, videoTexture, attempt + 1, gen);
        }, RETRY_DELAYS[attempt]);
    }

    private void clearStaleGlowAfterTimeout(int gen) {
        if (!active || gen != captureGeneration || capturedThisOpen || !NimarkoConfig.mediaGlow) return;
        captureGeneration++; 
        capturingGeneration = -1;
        pendingPhotoRecapture = null;
        captureTimedOut = true;
        Bitmap oldCurrent = current;
        Bitmap oldPrevious = previous;
        Bitmap oldPending = pending;
        current = null;
        previous = null;
        pending = null;
        currentIsVideo = false;
        previousIsVideo = false;
        pendingIsVideo = false;
        pendingFirstOfItem = false;
        fadeStartedAt = 0;
        lastSignature = null;
        forceNextCapture = false;
        recycle(oldCurrent);
        recycle(oldPrevious);
        recycle(oldPending);
        requestFrame(containerRef != null ? containerRef.get() : null);
    }

    public void onClose(Object owner) {
        if (!isOwner(owner)) return;
        active = false;
        if (current != null && NimarkoConfig.mediaGlow && closeFadeStartedAt == 0) {
            captureGeneration++;       
            capturingGeneration = -1;
            if (pending != null) { recycle(pending); pending = null; }
            pendingIsVideo = false;
            pendingFirstOfItem = false;
            
            closeTransitionProgress = fadeStartedAt != 0
                    ? transitionProgress(SystemClock.elapsedRealtime()) : 1f;
            fadeStartedAt = 0;
            closeDismiss = liveDismiss;   
            closeGeomValid = false;       
            closePrevGeomValid = false;
            if (lastDrawWidth > 0 && lastDrawHeight > 0) {
                prepareCloseGeometry(current, lastDrawWidth, lastDrawHeight, false);
                prepareCloseGeometry(previous, lastDrawWidth, lastDrawHeight, true);
            }
            closeFadeStartedAt = SystemClock.elapsedRealtime();
            AndroidUtilities.cancelRunOnUIThread(closeReleaseRunnable);
            AndroidUtilities.runOnUIThread(closeReleaseRunnable, CLOSE_FADE_MS);   
            return;
        }
        nmReleaseNow();
    }

    public void release() {
        View host = containerRef != null ? containerRef.get() : null;
        nmReleaseNow();
        requestFrame(host);
    }

    public void release(Object owner) {
        if (isOwner(owner)) {
            release();
        }
    }

    public void onPhotoUpdated(Object owner, ImageReceiver centerImage) {
        if (!isOwner(owner) || !active || !NimarkoConfig.mediaGlow || closeFadeStartedAt != 0 || centerImage == null) return;
        if (captureTimedOut) {
            captureTimedOut = false;
            forceNextCapture = true;
            mediaChangedAt = SystemClock.elapsedRealtime();
            int gen = ++captureGeneration;
            maybeCapture(centerImage, null, false, gen);
            AndroidUtilities.runOnUIThread(() -> clearStaleGlowAfterTimeout(gen), NEW_MEDIA_CAPTURE_TIMEOUT_MS);
            return;
        }
        if (capturingGeneration == captureGeneration) {
            pendingPhotoRecapture = centerImage;
            return;
        }
        maybeCapture(centerImage, null, false, captureGeneration);
    }

    public void onVideoFrameAvailable(Object owner) {
        if (!isOwner(owner) || !active || !NimarkoConfig.mediaGlow || closeFadeStartedAt != 0) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastVideoFrameRequestAt < VIDEO_REFRESH_MS) return;
        lastVideoFrameRequestAt = now;
        final int gen = captureGeneration;
        final TextureView texture = videoTextureRef != null ? videoTextureRef.get() : null;
        if (!isViewReady(texture)) return;
        
        AndroidUtilities.runOnUIThread(() -> {
            if (active && gen == captureGeneration && closeFadeStartedAt == 0
                    && isViewReady(texture)) {
                maybeCapture(null, texture, true, gen);
            }
        });
    }

    private void nmReleaseNow() {
        AndroidUtilities.cancelRunOnUIThread(closeReleaseRunnable);
        captureGeneration++;          
        capturingGeneration = -1;
        pendingPhotoRecapture = null;
        capturedThisOpen = false;
        captureTimedOut = false;
        forceNextCapture = false;
        active = false;
        Bitmap c = current, p = previous, n = pending;
        current = null;
        previous = null;
        pending = null;
        currentIsVideo = false;
        previousIsVideo = false;
        pendingIsVideo = false;
        pendingFirstOfItem = false;
        fadeStartedAt = 0;
        closeFadeStartedAt = 0;
        closeGeomValid = false;
        closePrevGeomValid = false;
        closeTransitionProgress = 1f;
        lastSignature = null;
        lastVideoCaptureAt = 0;
        lastVideoFrameRequestAt = 0;
        lastPhotoCaptureAttemptAt = 0;
        mediaChangedAt = 0;
        lastDrawWidth = lastDrawHeight = 0;
        containerRef = null;
        videoTextureRef = null;
        ownerRef = null;
        recycle(c);
        recycle(p);
        recycle(n);
    }

    public void draw(Object owner, Canvas canvas, View container, ImageReceiver centerImage, TextureView videoTexture, float dismiss) {
        if (!isOwner(owner)) return;
        if (!NimarkoConfig.mediaGlow) {
            if (current != null || previous != null || pending != null || capturingGeneration >= 0) {
                release(owner);   
            }
            return;
        }
        if (container != null && (containerRef == null || containerRef.get() != container)) {
            containerRef = new WeakReference<>(container);   
        }
        if (videoTexture != null && (videoTextureRef == null || videoTextureRef.get() != videoTexture)) {
            videoTextureRef = new WeakReference<>(videoTexture);
        }
        liveDismiss = dismiss;   
        
        if (closeFadeStartedAt != 0) {
            if (current == null || current.isRecycled()) {
                nmReleaseNow();
                return;
            }
            int cw = canvas.getWidth(), ch = canvas.getHeight();
            if (cw <= 0 || ch <= 0) return;
            float a = strengthAlpha() * (darkTheme ? 0.85f : 1f) * closeDismiss;
            boolean hasPrevious = previous != null && !previous.isRecycled() && closeTransitionProgress < 1f;
            if (hasPrevious) {
                drawCloseCover(canvas, previous, cw, ch,
                        a * mediaAlpha(previousIsVideo) * (1f - closeTransitionProgress), true, false);
            }
            drawCloseCover(canvas, current, cw, ch,
                    a * mediaAlpha(currentIsVideo) * closeTransitionProgress, false, hasPrevious);
            return;   
        }
        if (!active) return;
        long now = SystemClock.elapsedRealtime();
        if (!(supportsAsyncVideoCapture() && isViewReady(videoTexture)) && !capturedThisOpen
                && now - mediaChangedAt < NEW_MEDIA_CAPTURE_TIMEOUT_MS
                && now - lastPhotoCaptureAttemptAt >= PHOTO_RETRY_INTERVAL_MS) {
            
            lastPhotoCaptureAttemptAt = now;
            maybeCapture(centerImage, null, false, captureGeneration);
        }
        if (current == null) return;

        int w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        lastDrawWidth = w;
        lastDrawHeight = h;

        float baseAlpha = strengthAlpha() * (darkTheme ? 0.85f : 1f) * liveDismiss;
        float progress = 1f;
        if (fadeStartedAt != 0) {
            progress = transitionProgress(now);
        }
        boolean hasPrevious = previous != null && !previous.isRecycled() && progress < 1f;
        if (hasPrevious) {
            drawCover(canvas, previous, w, h,
                    baseAlpha * mediaAlpha(previousIsVideo) * (1f - progress), false);
        }
        drawCover(canvas, current, w, h,
                baseAlpha * mediaAlpha(currentIsVideo) * (fadeStartedAt != 0 ? progress : 1f), hasPrevious);

        if (progress < 1f) {
            requestFrame(container);
        } else if (fadeStartedAt != 0) {
            finishTransition();
            fadeStartedAt = 0;
            if (startPendingTransition()) {
                requestFrame(container);
            }
        }
    }

    private void drawCover(Canvas canvas, Bitmap bmp, int w, int h, float alpha, boolean additive) {
        if (bmp == null || bmp.isRecycled()) return;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0 || alpha <= 0f) return;
        float scale = Math.max((float) w / bw, (float) h / bh);
        float dw = bw * scale, dh = bh * scale;
        float left = (w - dw) / 2f, top = (h - dh) / 2f;
        srcRect.set(0, 0, bw, bh);
        dstRect.set(left, top, left + dw, top + dh);
        
        coverPaint.setFilterBitmap(true);
        coverPaint.setAlpha(Math.max(0, Math.min(255, (int) (255 * alpha))));
        
        coverPaint.setXfermode(additive ? ADD_XFERMODE : null);
        canvas.drawBitmap(bmp, srcRect, dstRect, coverPaint);
        coverPaint.setXfermode(null);
    }

    private void drawCloseCover(Canvas canvas, Bitmap bmp, int w, int h, float alpha,
                                boolean oldLayer, boolean additive) {
        if (bmp == null || bmp.isRecycled() || alpha <= 0f) return;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) return;
        boolean geometryValid = oldLayer ? closePrevGeomValid : closeGeomValid;
        if (!geometryValid) {
            prepareCloseGeometry(bmp, w, h, oldLayer);
        }
        srcRect.set(0, 0, bw, bh);
        if (oldLayer) {
            dstRect.set(closePrevDstLeft, closePrevDstTop, closePrevDstRight, closePrevDstBottom);
        } else {
            dstRect.set(closeDstLeft, closeDstTop, closeDstRight, closeDstBottom);
        }
        coverPaint.setFilterBitmap(true);
        coverPaint.setAlpha(Math.max(0, Math.min(255, (int) (255 * alpha))));
        coverPaint.setXfermode(additive ? ADD_XFERMODE : null);
        canvas.drawBitmap(bmp, srcRect, dstRect, coverPaint);
        coverPaint.setXfermode(null);
    }

    private void prepareCloseGeometry(Bitmap bmp, int w, int h, boolean oldLayer) {
        if (bmp == null || bmp.isRecycled() || w <= 0 || h <= 0) return;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float scale = Math.max((float) w / bw, (float) h / bh);
        float dw = bw * scale, dh = bh * scale;
        float left = (w - dw) / 2f, top = (h - dh) / 2f;
        if (oldLayer) {
            closePrevDstLeft = left;
            closePrevDstTop = top;
            closePrevDstRight = left + dw;
            closePrevDstBottom = top + dh;
            closePrevGeomValid = true;
        } else {
            closeDstLeft = left;
            closeDstTop = top;
            closeDstRight = left + dw;
            closeDstBottom = top + dh;
            closeGeomValid = true;
        }
    }

    private void maybeCapture(ImageReceiver centerImage, TextureView videoTexture, boolean isVideo, final int gen) {
        if (!active || capturingGeneration == gen || gen != captureGeneration) return;

        if (isVideo) {
            refreshTheme();   
            lastVideoCaptureAt = SystemClock.elapsedRealtime();
            capturingGeneration = gen;
            final int radius = blurRadius();
            final int scrim = scrimColorVideo;
            final boolean force = forceNextCapture;
            captureVideoFrame(videoTexture, frame -> queue.postRunnable(() -> {
                if (frame == null) {
                    AndroidUtilities.runOnUIThread(() -> captureFinished(gen));
                    return;
                }
                if (gen != captureGeneration || !NimarkoConfig.mediaGlow) {
                    recycle(frame);
                    AndroidUtilities.runOnUIThread(() -> captureFinished(gen));
                    return;
                }
                String sig = signature(frame);
                if (!force && sig.equals(lastSignature)) {
                    recycle(frame);
                    AndroidUtilities.runOnUIThread(() -> captureFinished(gen));
                    return;
                }
                Bitmap glow = makeGlow(frame, radius, scrim);
                AndroidUtilities.runOnUIThread(() -> apply(glow, true, force, sig, gen));
            }));
        } else {
            
            refreshTheme();   
            final ImageReceiver.BitmapHolder holder = grabImageHolder(centerImage);
            if (holder == null) return;
            capturingGeneration = gen;
            final int radius = blurRadius();
            final int scrim = scrimColorPhoto;
            final boolean force = forceNextCapture;
            queue.postRunnable(() -> {
                if (gen != captureGeneration || !NimarkoConfig.mediaGlow) {
                    holder.release();
                    AndroidUtilities.runOnUIThread(() -> captureFinished(gen));
                    return;
                }
                Bitmap copy = null;
                try {
                    copy = copyScaled(holder.bitmap);
                } catch (Throwable ignore) {
                } finally {
                    holder.release();
                }
                if (copy == null) {
                    AndroidUtilities.runOnUIThread(() -> captureFinished(gen));
                    return;
                }
                String sig = signature(copy);
                if (!force && sig.equals(lastSignature)) {
                    recycle(copy);
                    AndroidUtilities.runOnUIThread(() -> captureFinished(gen));
                    return;
                }
                Bitmap glow = makeGlow(copy, radius, scrim);
                AndroidUtilities.runOnUIThread(() -> apply(glow, false, force, sig, gen));
            });
        }
    }

    private void captureFinished(int gen) {
        if (capturingGeneration == gen) {
            capturingGeneration = -1;
            ImageReceiver retry = pendingPhotoRecapture;
            pendingPhotoRecapture = null;
            if (active && retry != null && gen == captureGeneration) {
                
                AndroidUtilities.runOnUIThread(() -> {
                    if (active && gen == captureGeneration && capturingGeneration != gen) {
                        maybeCapture(retry, null, false, gen);
                    }
                });
            }
        }
    }

    private void apply(Bitmap glow, boolean isVideo, boolean firstOfItem, String signature, int gen) {
        captureFinished(gen);
        if (glow == null) return;
        if (!active || gen != captureGeneration || !NimarkoConfig.mediaGlow) {
            recycle(glow);   
            return;
        }
        lastSignature = signature;
        capturedThisOpen = true;
        captureTimedOut = false;
        
        if (firstOfItem) {
            forceNextCapture = false;
        }
        long now = SystemClock.elapsedRealtime();
        if (fadeStartedAt != 0 && transitionProgress(now) < 1f) {
            
            recycle(pending);
            pending = glow;
            pendingIsVideo = isVideo;
            pendingFirstOfItem = firstOfItem;
        } else {
            if (fadeStartedAt != 0) {
                finishTransition();
                fadeStartedAt = 0;
            }
            startTransition(glow, isVideo, firstOfItem);
        }
        
        View c = containerRef != null ? containerRef.get() : null;
        requestFrame(c);
    }

    private float transitionProgress(long now) {
        if (fadeStartedAt == 0) return 1f;
        float duration = currentIsVideo ? VIDEO_FADE_MS : fadeDurationMs();
        float t = (now - fadeStartedAt) / duration;
        return currentIsVideo ? easeInOut(t) : easeOut(t);
    }

    private void finishTransition() {
        recycle(previous);
        previous = null;
        previousIsVideo = false;
    }

    private void startTransition(Bitmap glow, boolean isVideo, boolean firstOfItem) {
        if (glow == null || glow.isRecycled()) return;
        Bitmap target = glow;
        if (isVideo && !firstOfItem && current != null && !current.isRecycled()) {
            
            target = blendOnto(current, glow, VIDEO_BLEND_ALPHA);
        }
        if (current != null && !current.isRecycled()) {
            if (previous != null && previous != current) {
                recycle(previous);
            }
            previous = current;
            previousIsVideo = currentIsVideo;
        }
        current = target;
        currentIsVideo = isVideo;
        fadeStartedAt = SystemClock.elapsedRealtime();
    }

    private boolean startPendingTransition() {
        if (pending == null || pending.isRecycled()) {
            pending = null;
            pendingIsVideo = false;
            pendingFirstOfItem = false;
            return false;
        }
        Bitmap next = pending;
        boolean nextIsVideo = pendingIsVideo;
        boolean nextFirst = pendingFirstOfItem;
        pending = null;
        pendingIsVideo = false;
        pendingFirstOfItem = false;
        startTransition(next, nextIsVideo, nextFirst);
        return true;
    }

    private static float mediaAlpha(boolean video) {
        return video ? 0.9f : 1f;
    }

    private static void requestFrame(View view) {
        if (view != null) {
            view.postInvalidateOnAnimation();
        }
    }

    private boolean isOwner(Object owner) {
        return owner != null && ownerRef != null && ownerRef.get() == owner;
    }

    private static Bitmap blendOnto(Bitmap base, Bitmap incoming, float a) {
        if (base == null || base.isRecycled()) return incoming;
        if (incoming == null || incoming.isRecycled()) return base;
        Bitmap copy = null;
        try {
            copy = base.copy(Bitmap.Config.ARGB_8888, true);
            Canvas c = new Canvas(copy);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(Math.max(0, Math.min(255, (int) (255 * a))));
            c.drawBitmap(incoming,
                    new Rect(0, 0, incoming.getWidth(), incoming.getHeight()),
                    new RectF(0, 0, copy.getWidth(), copy.getHeight()), p);
            recycle(incoming);
            return copy;
        } catch (Throwable t) {
            recycle(copy);
            return incoming;   
        }
    }

    private static ImageReceiver.BitmapHolder grabImageHolder(ImageReceiver image) {
        if (image == null) return null;
        try {
            ImageReceiver.BitmapHolder h = image.getBitmapSafe();
            if (h == null || h.bitmap == null || h.bitmap.isRecycled()) {
                if (h != null) h.release();
                return null;
            }
            return h;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private interface BitmapCallback { void run(Bitmap bitmap); }

    private static boolean supportsAsyncVideoCapture() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    private static void captureVideoFrame(TextureView texture, BitmapCallback callback) {
        if (texture == null) { callback.run(null); return; }
        if (!supportsAsyncVideoCapture()) { callback.run(null); return; }
        Bitmap target = null;
        Surface surface = null;
        try {
            int w = texture.getWidth(), h = texture.getHeight();
            if (w <= 0 || h <= 0 || !texture.isAvailable()) { callback.run(null); return; }
            float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
            int tw = Math.max(16, (int) (w * scale));
            int th = Math.max(16, (int) (h * scale));
            SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
            if (surfaceTexture == null) { callback.run(null); return; }
            target = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            surface = new Surface(surfaceTexture);
            final Bitmap requestedBitmap = target;
            final Surface requestedSurface = surface;
            PixelCopy.request(requestedSurface, new Rect(0, 0, w, h), requestedBitmap, result -> {
                try { requestedSurface.release(); } catch (Throwable ignored) {}
                if (result == PixelCopy.SUCCESS) callback.run(requestedBitmap);
                else { recycle(requestedBitmap); callback.run(null); }
            }, mainHandler);
            target = null;
            surface = null;
        } catch (Throwable ignore) {
            if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
            recycle(target);
            callback.run(null);
        }
    }

    private static Bitmap copyScaled(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= 0 || h <= 0) return null;
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
        int tw = Math.max(16, (int) (w * scale));
        int th = Math.max(16, (int) (h * scale));
        Bitmap out;
        if (tw != w || th != h) {
            out = Bitmap.createScaledBitmap(bmp, tw, th, true);
        } else {
            out = bmp.copy(Bitmap.Config.ARGB_8888, true);
        }
        if (out != null && (out.getConfig() != Bitmap.Config.ARGB_8888 || !out.isMutable())) {
            Bitmap m = out.copy(Bitmap.Config.ARGB_8888, true);
            if (m != out) out.recycle();
            out = m;
        }
        return out;
    }

    private static Bitmap makeGlow(Bitmap bmp, int radius, int scrim) {
        if (bmp == null || bmp.isRecycled()) return null;
        try {
            
            Utilities.stackBlurBitmap(bmp, Math.max(1, Math.min(radius, 60)));
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            
            int alpha = Color.alpha(scrim);
            int cEdge = ColorUtils.setAlphaComponent(scrim, Math.min(255, Math.round(alpha * 1.15f)));
            int cMid = ColorUtils.setAlphaComponent(scrim, Math.max(0, Math.round(alpha * 0.85f)));
            p.setShader(new LinearGradient(0, 0, 0, bh,
                    new int[]{cEdge, cMid, cEdge}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);
            return bmp;
        } catch (Throwable t) {
            recycle(bmp);
            return null;
        }
    }

    private static boolean isViewReady(View view) {
        if (view == null) return false;
        try {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
            if (view.getVisibility() == View.VISIBLE) return true;
            return view instanceof TextureView && ((TextureView) view).isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String signature(Bitmap bmp) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            StringBuilder sb = new StringBuilder(w + "x" + h);
            for (int gy = 0; gy < 3; gy++) {
                for (int gx = 0; gx < 3; gx++) {
                    int px = Math.min(w - 1, Math.max(0, (int) ((gx + 0.5f) / 3f * w)));
                    int py = Math.min(h - 1, Math.max(0, (int) ((gy + 0.5f) / 3f * h)));
                    int cc = bmp.getPixel(px, py);
                    int luma = (((cc >> 16) & 0xFF) * 77 + ((cc >> 8) & 0xFF) * 151 + (cc & 0xFF) * 28) >> 8;
                    sb.append(':').append(luma >> 3);   
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(System.identityHashCode(bmp));
        }
    }

    private static void recycle(Bitmap b) {
        try {
            if (b != null && !b.isRecycled()) b.recycle();
        } catch (Throwable ignore) {}
    }

    private static float easeInOut(float v) {
        v = Math.max(0f, Math.min(1f, v));
        return v * v * (3f - 2f * v);
    }

    private static float easeOut(float v) {   
        v = Math.max(0f, Math.min(1f, v));
        return 1f - (1f - v) * (1f - v);
    }

    private static float strengthAlpha() {
        return 0.62f;
    }
    private static int blurRadius() {
        return 36;
    }
    private static float fadeDurationMs() {
        return 480f;   
    }
}
