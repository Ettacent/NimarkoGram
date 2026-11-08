/* Copyright (C) 2026 Ettacent. */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.telegram.messenger.SharedConfig;

public final class PdfDocumentView extends View implements PdfRenderClient.Callback {
    public interface Listener {
        void onReady(int count);
        void onPageChanged(int zeroBasedPage, int count);
        void onFailure(int code);
        void onFirstPageRendered();
    }

    private static final int TILE = 512;
    private static final int NAV_IDLE = 0, NAV_SCROLL = 1, NAV_COVER_IN = 2, NAV_COVERED = 3,
            NAV_WAIT = 4, NAV_COVER_OUT = 5;
    private static final int ERROR_RENDER = PdfRenderClient.ERROR_RENDER;
    private static final int ERROR_SERVICE = PdfRenderClient.ERROR_SERVICE;
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final PdfReadingColors readingColors = new PdfReadingColors();
    private ValueAnimator appearanceAnimator;
    private float appearanceCover, appearanceTarget;
    private int requestedPaper = Color.WHITE, requestedInk = Color.BLACK;
    private boolean requestedDark, colorsRequested, appearanceWaiting, appearancePosted;
    private final Runnable commitAppearance = this::commitAppearance;
    private final RectF rect = new RectF();
    private final Rect drawClip = new Rect();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scales;
    private final long cacheLimit;
    private final float inset;
    private final LinkedHashMap<TileKey, Bitmap> cache = new LinkedHashMap<>(32, .75f, true);
    private final ArrayList<Map.Entry<TileKey, Bitmap>> drawingTiles = new ArrayList<>();
    private final ArrayList<RasterGroup> rasterGroups = new ArrayList<>();
    private final HashMap<TileKey, RasterGroup> coveredTiles = new HashMap<>();
    private final Region coveredTileCheck = new Region();
    private final Matrix drawMatrix = new Matrix();
    private final float[] drawMatrixValues = new float[9];
    private final LinkedHashMap<TileKey, QualityBlend> qualityBlends = new LinkedHashMap<>();
    private boolean qualityFramePosted;
    private boolean admissionDeferred;
    private boolean renderingPaused;
    private final Runnable qualityFrame = this::advanceQuality;
    private final ArrayList<TileKey> wanted = new ArrayList<>();
    private int visibleTileCount;
    private boolean wantedDirty = true;
    private double qualityScaleLimit = Double.POSITIVE_INFINITY;
    private final HashSet<TileKey> protectedTiles = new HashSet<>();
    private int navigationState, navigationTarget = -1;
    private long navigationStart, navigationDuration, coverDeadline;
    private double navigationFromY;
    private float coverAlpha, coverFromAlpha;
    private boolean coverDrawn;
    private final Runnable navigationFrame = this::advanceNavigation;
    private PdfRenderClient client;
    private PdfViewport viewport;
    private ValueAnimator zoomAnimator;
    private float zoomTarget, zoomFocusX, zoomFocusY;
    private TileKey pendingTile;
    private TileKey pendingColdPreview;
    private int attemptedColdPrefetch = -1;
    private final HashSet<Integer> coldPreviewAttempts = new HashSet<>();
    private final HashSet<TileKey> coldPreviewKeys = new HashSet<>();
    private int pendingSize = -1;
    private long cacheBytes;
    private int generation;
    private int currentPage;
    private int requestedPage;
    private int pageJump = -1;
    private int topInset, bottomInset;
    private int rejectedRequests;
    private int background = 0xff282828;
    private int placeholder = 0xffeeeeee;
    private double scrollX;
    private double scrollY;
    private double presentedScrollX, presentedScrollY;
    private float presentedZoom;
    private DocumentChromeLayout presentedChrome;
    private boolean scrollPresented;
    private double flingX;
    private double flingY;
    private float zoom = 1;
    private float scaleFocusX;
    private float scaleFocusY;
    private boolean zoomSettling;
    private boolean firstRendered;
    private boolean failed;
    private boolean pumpPosted;
    private boolean gestureScaled;

    private final Runnable pumpTask = () -> {
        pumpPosted = false;
        pump();
    };
    private final Runnable settleTask = () -> {
        zoomSettling = false;
        schedulePump();
        invalidate();
    };

    public PdfDocumentView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        inset = 8 * getResources().getDisplayMetrics().density;
        cacheLimit = Math.min(32L * 1024 * 1024,
                Math.max(1024L * 1024, Runtime.getRuntime().maxMemory() / 16));
        scroller = new OverScroller(context);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        scales = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                stopAnimation();
                pageJump = -1;
                gestureScaled = true;
                zoomSettling = true;
                scaleFocusX = detector.getFocusX();
                scaleFocusY = detector.getFocusY();
                removeCallbacks(settleTask);
                return viewport != null;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                scrollX += scaleFocusX - detector.getFocusX();
                scrollY += scaleFocusY - detector.getFocusY();
                setZoom(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                scaleFocusX = detector.getFocusX();
                scaleFocusY = detector.getFocusY();
                return true;
            }

            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                settleZoom();
            }
        });
        scales.setQuickScaleEnabled(false);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
                if (!scales.isInProgress() && !gestureScaled) {
                    if (dy != 0) pageJump = -1;
                    scrollX += dx;
                    scrollY += dy;
                    viewportChanged();
                }
                return true;
            }

            @Override public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                if (viewport != null && !gestureScaled) {
                    pageJump = -1;
                    flingX = scrollX;
                    flingY = scrollY;
                    scroller.fling(0, 0, (int) -vx, (int) -vy,
                            -safeInt(scrollX), safeInt(maxX() - scrollX),
                            -safeInt(scrollY - minY()), safeInt(maxY() - scrollY));
                    postInvalidateOnAnimation();
                }
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                animateZoom(zoom > 1.05f ? 1 : 2.5f, event.getX(), event.getY());
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                return performClick();
            }
        });
    }

    public void open(File file, String password) {
        close();
        failed = false;
        final int token = generation;
        client = new PdfRenderClient(getContext().getApplicationContext(), file, password,
                new PdfRenderClient.Callback() {
                    private boolean active() {
                        return token == generation && client != null && !failed;
                    }
                    @Override public void onOpened(int count) { if (active()) PdfDocumentView.this.onOpened(count); }
                    @Override public void onPageSize(int page, int w, int h) {
                        if (active()) PdfDocumentView.this.onPageSize(page, w, h);
                    }
                    @Override public void onTile(int page, int rw, int rh, int x, int y, Bitmap bitmap) {
                        if (active()) PdfDocumentView.this.onTile(page, rw, rh, x, y, bitmap);
                    }
                    @Override public void onError(int code) { if (active()) PdfDocumentView.this.onError(code); }
                });
        client.open();
    }

    public void close() {
        scrollPresented = false;
        presentedChrome = null;
        finishQuality();
        finishAppearance();
        generation++;
        stopAnimation();
        removeCallbacks(pumpTask);
        removeCallbacks(settleTask);
        pumpPosted = false;
        zoomSettling = false;
        PdfRenderClient old = client;
        client = null;
        if (old != null) old.close();
        viewport = null;
        pendingSize = -1;
        pendingTile = null;
        pendingColdPreview = null;
        attemptedColdPrefetch = -1;
        coldPreviewAttempts.clear();
        coldPreviewKeys.clear();
        wanted.clear();
        qualityScaleLimit = Double.POSITIVE_INFINITY;
        visibleTileCount = 0;
        protectedTiles.clear();
        cache.clear();
        drawingTiles.clear();
        rasterGroups.clear();
        coveredTiles.clear();
        cacheBytes = 0;
        scrollX = 0;
        scrollY = minY();
        zoom = 1;
        currentPage = requestedPage = rejectedRequests = 0;
        pageJump = -1;
        firstRendered = false;
        invalidate();
    }

    public void goToPage(int zeroBased) {
        requestedPage = Math.max(0, zeroBased);
        if (viewport == null) return;
        int page = Math.min(requestedPage, viewport.count() - 1);
        if (navigationTarget == page && motionAllowed()) return;
        scroller.forceFinished(true);
        stopZoom(false);
        finishQuality();
        if (!firstRendered || !motionAllowed()) {
            cancelNavigation(false);
            pageJump = page;
            scrollY = pageScroll(page);
            viewportChanged();
            return;
        }
        double destination = pageScroll(page);
        long now = SystemClock.uptimeMillis();
        boolean retargetingScroll = navigationState == NAV_SCROLL;
        int fromPage = retargetingScroll ? navigationTarget : viewport.pageAt((scrollY + contentTop() + inset) / zoom);
        boolean coveredNavigation = navigationState >= NAV_COVER_IN;
        removeCallbacks(navigationFrame);
        navigationTarget = page;
        navigationStart = now;
        if (!coveredNavigation && Math.abs(destination - scrollY) < .5) {
            cancelNavigation(false);
            pageJump = page;
            scrollY = destination;
        } else if (!coveredNavigation
                && Math.abs(page - fromPage) <= 1
                && (retargetingScroll || Math.abs(destination - scrollY) <= 2 * Math.max(effectiveHeight(), getWidth()))) {
            pageJump = -1;
            navigationState = NAV_SCROLL;
            navigationFromY = scrollY;
            navigationDuration = 260;
        } else {
            if (!coveredNavigation) coverDeadline = now + 1500;
            coverFromAlpha = coverAlpha;
            navigationDuration = Math.max(1, Math.round(120 * (1 - coverAlpha)));
            navigationState = coverAlpha == 1 ? NAV_COVERED : NAV_COVER_IN;
            if (coverAlpha < 1) coverDrawn = false;
        }
        viewportChanged();
        if (navigationState != NAV_IDLE) postOnAnimation(navigationFrame);
    }

    public void zoomIn() { animateZoom((zoomAnimator != null ? zoomTarget : zoom) * 1.25f, getWidth() / 2f, contentTop() + effectiveHeight() / 2f); }
    public void zoomOut() { animateZoom((zoomAnimator != null ? zoomTarget : zoom) / 1.25f, getWidth() / 2f, contentTop() + effectiveHeight() / 2f); }
    public void resetZoom() { animateZoom(1, getWidth() / 2f, contentTop() + effectiveHeight() / 2f); }
    public boolean isZoomed() { return zoom > 1.001f; }
    public int getCurrentPage() { return navigationTarget >= 0 ? navigationTarget : currentPage; }
    public int getPageCount() { return viewport == null ? 0 : viewport.count(); }

    public void setTopInset(int pixels) {
        int next = Math.max(0, pixels);
        if (topInset == next) return;
        int previousTop = contentTop();
        boolean atStart = scrollY == minY() && (pageJump <= 0) && navigationTarget < 0;
        topInset = next;
        if (atStart) {
            scrollY -= contentTop() - previousTop;
            scrollPresented = false;
        }
        if (navigationState == NAV_IDLE) pageJump = -1;
        viewportChanged();
    }

    public void setBottomInset(int pixels) {
        int next = Math.max(0, pixels);
        if (bottomInset == next) return;
        bottomInset = next;
        finishMotion();
        if (viewport != null && pageJump >= 0) scrollY = pageScroll(pageJump);
        viewportChanged();
    }

    public void setReadingColors(int paper, int ink, boolean dark) {
        requestedPaper = paper;
        requestedInk = ink;
        requestedDark = dark;
        colorsRequested = true;
        if (!firstRendered || !motionAllowed()) {
            finishAppearance();
        } else if (readingColors.dark == dark) {
            applyReadingColors();
            if (appearanceTarget == 1f || appearanceWaiting) animateAppearance(0f);
        } else if (appearanceTarget != 1f && !appearanceWaiting) {
            animateAppearance(1f);
        }
    }

    private void applyReadingColors() {
        if (!colorsRequested || !readingColors.update(requestedPaper, requestedInk, requestedDark)) return;
        tilePaint.setColorFilter(readingColors.filtered ? new ColorMatrixColorFilter(readingColors.matrix) : null);
        invalidate();
    }

    private void cancelAppearanceAnimator() {
        if (appearanceAnimator == null) return;
        ValueAnimator animator = appearanceAnimator;
        appearanceAnimator = null;
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        animator.cancel();
    }

    private void animateAppearance(float target) {
        cancelAppearanceAnimator();
        removeCallbacks(commitAppearance);
        appearanceWaiting = appearancePosted = false;
        appearanceTarget = target;
        if (appearanceCover == target) {
            appearanceWaiting = target == 1f;
            invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(appearanceCover, target);
        appearanceAnimator = animator;
        animator.setDuration(Math.max(1, Math.round((target == 1f ? 90 : 160) * Math.abs(target - appearanceCover))));
        animator.addUpdateListener(animation -> {
            if (appearanceAnimator != animation) return;
            if (!motionAllowed()) { finishAppearance(); return; }
            appearanceCover = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (appearanceAnimator != animation) return;
                appearanceAnimator = null;
                appearanceCover = target;
                appearanceWaiting = target == 1f;
                invalidate();
            }
        });
        animator.start();
    }

    private void commitAppearance() {
        appearancePosted = false;
        if (!appearanceWaiting) return;
        appearanceWaiting = false;
        applyReadingColors();
        if (motionAllowed()) animateAppearance(0f); else finishAppearance();
    }

    private void finishAppearance() {
        cancelAppearanceAnimator();
        removeCallbacks(commitAppearance);
        appearanceWaiting = appearancePosted = false;
        appearanceCover = appearanceTarget = 0f;
        applyReadingColors();
        invalidate();
    }

    public void setColors(int background, int border, int placeholder) {
        this.background = background;
        this.placeholder = placeholder;
        invalidate();
    }

    @Override public void onOpened(int count) {
        if (client == null || failed || viewport != null) return;
        if (count <= 0) { onError(ERROR_RENDER); return; }
        viewport = new PdfViewport(count);
        viewport.layout(getWidth(), inset, inset * 1.5);
        int token = generation;
        listener.onReady(count);
        if (token != generation || viewport == null) return;
        currentPage = -1;
        goToPage(requestedPage);
    }

    @Override public void onPageSize(int page, int width, int height) {
        if (client == null || failed || viewport == null || pendingSize != page) return;
        pendingSize = -1;
        if (width <= 0 || height <= 0) { onError(ERROR_RENDER); return; }
        int anchor = viewport.pageAt((scrollY + contentTop()) / zoom);
        double fraction = viewport.fraction(anchor, (scrollY + contentTop()) / zoom);
        double previousScrollY = scrollY;
        viewport.size(page, width, height);
        scrollY = pageJump >= 0 ? pageScroll(pageJump)
                : (viewport.top(anchor) + fraction * viewport.pageHeight(anchor)) * zoom - contentTop();
        flingY += scrollY - previousScrollY;
        if (scrollY != previousScrollY) scrollPresented = false;
        if (navigationState == NAV_SCROLL) {
            long now = SystemClock.uptimeMillis();
            navigationDuration = Math.max(1, navigationStart + navigationDuration - now);
            navigationStart = now;
            navigationFromY = scrollY;
        }
        viewportChanged();
    }

    @Override public void onTile(int page, int rw, int rh, int x, int y, Bitmap bitmap) {
        boolean retained = false;
        TileKey completedPreview = null;
        try {
            if (client == null || failed || pendingTile == null) return;
            TileKey key = pendingTile;
            if (key.page != page || key.rw != rw || key.rh != rh || key.x != x || key.y != y) {
                return;
            }
            completedPreview = pendingColdPreview;
            pendingTile = null;
            if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() != key.width()
                    || bitmap.getHeight() != key.height()) { onError(ERROR_RENDER); return; }
            long bytes = bitmap.getAllocationByteCount();
            if (bytes > cacheLimit) { onError(ERROR_RENDER); return; }
            rebuildWanted();
            if (protectedTiles.contains(key)) {
                Bitmap cached = cache.get(key);
                long incoming = bytes - (cached == null ? 0 : cached.getAllocationByteCount());
                QualityBlend blend = null;
                if (firstRendered && !cache.containsKey(key) && motionAllowed()
                        && coverAlpha == 0 && appearanceCover == 0) {
                    blend = previewFor(key);
                    if (blend == null && tileVisible(key)) {
                        blend = new QualityBlend(SystemClock.uptimeMillis());
                        blend.alpha = 0f;
                        blend.fresh = true;
                    }
                }
                if (blend != null) qualityBlends.put(key, blend);
                if (completedPreview != null) {
                    for (Map.Entry<TileKey, Bitmap> entry : cache.entrySet()) {
                        if (entry.getKey().page == key.page) protectedTiles.add(entry.getKey());
                    }
                }
                evict(incoming, key);
                if (cacheBytes + incoming > cacheLimit && blend != null) {
                    qualityBlends.remove(key);
                    evict(incoming, key);
                }
                if (cacheBytes + incoming <= cacheLimit) {
                    Bitmap previous = cache.put(key, bitmap);
                    retained = true;
                    if (previous != null) cacheBytes -= previous.getAllocationByteCount();
                    cacheBytes += bytes;
                    rebuildDrawingTiles();
                    if (!qualityBlends.isEmpty() && !qualityFramePosted) {
                        qualityFramePosted = true;
                        postOnAnimation(qualityFrame);
                    }
                } else {
                    if (completedPreview != null) {
                    } else if (!qualityBlends.isEmpty()) {
                        admissionDeferred = true;
                    } else {
                        qualityScaleLimit = Math.min(qualityScaleLimit,
                                Math.min(key.rw / viewport.pageWidth, key.rh / viewport.pageHeight(key.page)) * .5);
                        wantedDirty = true;
                    }
                    rebuildDrawingTiles();
                }
            }
            invalidate();
            schedulePump();
        } finally {
            if (completedPreview != null && pendingColdPreview == completedPreview) pendingColdPreview = null;
            if (!retained && bitmap != null && !bitmap.isRecycled() && !cache.containsValue(bitmap)) bitmap.recycle();
        }
    }

    @Override public void onError(int code) {
        if (client == null || failed) return;
        finishQuality();
        finishAppearance();
        failed = true;
        stopAnimation();
        removeCallbacks(pumpTask);
        removeCallbacks(settleTask);
        pumpPosted = false;
        pendingSize = -1;
        pendingTile = null;
        PdfRenderClient old = client;
        client = null;
        old.close();
        listener.onFailure(code);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scrollPresented = false;
        if (viewport == null) return;
        finishMotion();
        int page = viewport.pageAt((scrollY + contentTop()) / zoom);
        double fraction = viewport.fraction(page, (scrollY + contentTop()) / zoom);
        double horizontal = (scrollX + oldw / 2.0) / Math.max(1, oldw * zoom);
        viewport.layout(w, inset, inset * 1.5);
        scrollY = pageJump >= 0 ? pageScroll(pageJump)
                : (viewport.top(page) + fraction * viewport.pageHeight(page)) * zoom - contentTop();
        scrollX = horizontal * w * zoom - w / 2.0;
        viewportChanged();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!motionAllowed() && !qualityBlends.isEmpty()) finishQuality();
        if (!motionAllowed() && (navigationState != NAV_IDLE || zoomAnimator != null
                || zoomSettling && !scales.isInProgress())) finishMotion();
        drawContent(canvas, false);
        presentScrollForGlass();
    }

    private void presentScrollForGlass() {
        ViewParent parent = getParent();
        while (parent instanceof View && !(parent instanceof DocumentChromeLayout)) {
            parent = ((View) parent).getParent();
        }
        DocumentChromeLayout chrome = parent instanceof DocumentChromeLayout ? (DocumentChromeLayout) parent : null;
        if (chrome == null) {
            scrollPresented = false;
            presentedChrome = null;
            return;
        }
        float dx = 0, dy = 0;
        if (scrollPresented && presentedChrome == chrome && presentedZoom == zoom) {
            dx = (float) (scrollX - presentedScrollX);
            dy = (float) (scrollY - presentedScrollY);
        }
        presentedScrollX = scrollX;
        presentedScrollY = scrollY;
        presentedZoom = zoom;
        presentedChrome = chrome;
        scrollPresented = true;
        chrome.onContentPresentedForGlass(this, dx, dy);
    }

    public void captureForBlur(Canvas canvas) {
        int save = canvas.save();
        try {
            canvas.clipRect(0, 0, getWidth(), getHeight());
            drawContent(canvas, true);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawContent(Canvas canvas, boolean capture) {
        canvas.drawColor(background);
        if (viewport == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        if (!canvas.getClipBounds(drawClip) || !drawClip.intersect(0, 0, getWidth(), getHeight())) {
            return;
        }
        boolean culling = !coveredTiles.isEmpty();
        for (View view = this; culling && view != null;) {
            culling = view.getAlpha() == 1f && view.getMatrix().isIdentity();
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        if (culling) {
            canvas.getMatrix(drawMatrix);
            drawMatrix.getValues(drawMatrixValues);
        }
        int start = viewport.pageAt((scrollY + drawClip.top) / zoom);
        int end = viewport.pageAt((scrollY + drawClip.bottom) / zoom);
        boolean drew = false;
        for (int page = start; page <= end; page++) {
            float left = (float) (inset * zoom - scrollX);
            float top = (float) (viewport.top(page) * zoom - scrollY);
            float width = (float) (viewport.pageWidth * zoom);
            float height = (float) (viewport.pageHeight(page) * zoom);
            rect.set(left, top, left + width, top + height);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(readingColors.page);
            canvas.drawRect(rect, paint);
            int save = canvas.save();
            canvas.clipRect(rect);
            boolean pageDrawn = false;
            for (Map.Entry<TileKey, Bitmap> entry : drawingTiles) {
                TileKey key = entry.getKey();
                Bitmap bitmap = entry.getValue();
                if (key.page != page || bitmap.isRecycled()) continue;
                rect.set(left + width * key.x / key.rw, top + height * key.y / key.rh,
                        left + width * (key.x + key.width()) / key.rw,
                        top + height * (key.y + key.height()) / key.rh);
                if (rect.right <= drawClip.left || rect.left >= drawClip.right
                        || rect.bottom <= drawClip.top || rect.top >= drawClip.bottom) continue;
                if (culling && tileCovered(key, left, top, width, height)) continue;
                QualityBlend blend = qualityBlends.get(key);
                tilePaint.setAlpha(blend == null ? 255 : Math.round(255 * blend.alpha));
                canvas.drawBitmap(bitmap, null, rect, tilePaint);
                tilePaint.setAlpha(255);
                pageDrawn = true;
            }
            drew |= pageDrawn;
            canvas.restoreToCount(save);
        }
        float overlayAlpha = Math.max(coverAlpha, appearanceCover);
        if (overlayAlpha > 0) {
            paint.setColor(background | 0xff000000);
            paint.setAlpha(Math.round(255 * overlayAlpha));
            canvas.drawRect(drawClip.left, drawClip.top, drawClip.right, drawClip.bottom, paint);
            paint.setAlpha(255);
            if (!capture && navigationState == NAV_COVERED && coverAlpha == 1) coverDrawn = true;
            if (!capture && appearanceWaiting && appearanceCover == 1f && !appearancePosted) {
                appearancePosted = true;
                postOnAnimation(commitAppearance);
            }
        }
        if (!capture && drew && !firstRendered && coverAlpha == 0 && initialPageReady()) {
            firstRendered = true;
            final int token = generation;
            post(() -> {
                if (generation == token && client != null && !failed) {
                    listener.onFirstPageRendered();
                }
            });
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (viewport == null || failed) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            stopAnimation();
            viewportChanged();
            gestureScaled = false;
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        }
        scales.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            if (gestureScaled) settleZoom();
            schedulePump();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = flingX + scroller.getCurrX();
            scrollY = flingY + scroller.getCurrY();
            viewportChanged();
            postInvalidateOnAnimation();
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(viewport != null && maxY() > minY());
        if (viewport != null) {
            info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(viewport.count(), 1, false));
            if (scrollY < maxY()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (scrollY > minY()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setScrollable(viewport != null && maxY() > minY());
        event.setItemCount(getPageCount());
        event.setFromIndex(currentPage);
        event.setToIndex(currentPage);
        event.setScrollX(safeInt(scrollX));
        event.setScrollY(safeInt(scrollY - minY()));
        event.setMaxScrollX(safeInt(maxX()));
        event.setMaxScrollY(safeInt(maxY() - minY()));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (viewport != null && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            stopAnimation();
            pageJump = -1;
            scrollY += (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1) * effectiveHeight() * .85;
            viewportChanged();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override protected void onDetachedFromWindow() {
        scrollPresented = false;
        presentedChrome = null;
        finishMotion();
        removeCallbacks(pumpTask);
        removeCallbacks(settleTask);
        pumpPosted = false;
        zoomSettling = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        schedulePump();
        invalidate();
    }

    private void animateZoom(float target, float x, float y) {
        if (viewport == null || Float.isNaN(target) || Float.isInfinite(target)) return;
        float clamped = Math.max(1, Math.min(4, target));
        if (zoomAnimator != null && clamped == zoomTarget) return;
        if (clamped == zoom && zoomAnimator == null) return;
        stopAnimation();
        pageJump = -1;
        zoomTarget = clamped;
        zoomFocusX = x;
        zoomFocusY = y;
        if (!motionAllowed()) {
            setZoom(clamped, x, y);
            return;
        }
        zoomSettling = true;
        zoomAnimator = ValueAnimator.ofFloat(zoom, clamped);
        zoomAnimator.setDuration(220);
        zoomAnimator.addUpdateListener(animation -> {
            if (animation != zoomAnimator) return;
            if (!motionAllowed()) {
                finishMotion();
                return;
            }
            setZoom((float) animation.getAnimatedValue(), x, y);
            settleZoom();
        });
        zoomAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (animation != zoomAnimator) return;
                zoomAnimator = null;
                removeCallbacks(settleTask);
                zoomSettling = false;
                schedulePump();
                invalidate();
            }
        });
        zoomAnimator.start();
    }

    private void setZoom(float value, float x, float y) {
        if (!Float.isFinite(value)) return;
        float next = Math.max(1, Math.min(4, value));
        scrollX = (scrollX + x) * next / zoom - x;
        scrollY = (scrollY + y) * next / zoom - y;
        zoom = next;
        viewportChanged();
    }

    private void settleZoom() {
        removeCallbacks(settleTask);
        if (motionAllowed()) postDelayed(settleTask, 180);
        else {
            zoomSettling = false;
            schedulePump();
        }
    }

    private void stopAnimation() {
        cancelNavigation(false);
        invalidate();
        scroller.forceFinished(true);
        stopZoom(false);
    }

    private void stopZoom(boolean finish) {
        ValueAnimator animator = zoomAnimator;
        zoomAnimator = null;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.cancel();
        }
        removeCallbacks(settleTask);
        zoomSettling = false;
        if (finish && animator != null) setZoom(zoomTarget, zoomFocusX, zoomFocusY);
        schedulePump();
    }

    private void finishMotion() {
        finishQuality();
        finishAppearance();
        cancelNavigation(true);
        scroller.forceFinished(true);
        stopZoom(true);
        viewportChanged();
    }

    private boolean motionAllowed() {
        return canPump() && hasWindowFocus()
                && SharedConfig.animationsEnabled()
                && (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
    }

    private double pageScroll(int page) {
        return Math.max(minY(), Math.min(maxY(), viewport.top(page) * zoom - inset - contentTop()));
    }

    private void cancelNavigation(boolean finish) {
        int target = navigationTarget;
        removeCallbacks(navigationFrame);
        navigationTarget = -1;
        navigationState = NAV_IDLE;
        coverAlpha = coverFromAlpha = 0;
        coverDrawn = false;
        coverDeadline = 0;
        if (target >= 0 && viewport != null) {
            pageJump = finish ? target : -1;
            if (finish) scrollY = pageScroll(target);
        }
    }

    private void advanceNavigation() {
        if (navigationState == NAV_IDLE || viewport == null) return;
        if (!motionAllowed()) {
            finishMotion();
            return;
        }
        long now = SystemClock.uptimeMillis();
        float t = Math.max(0, Math.min(1, (now - navigationStart) / (float) navigationDuration));
        float eased = 1 - (1 - t) * (1 - t) * (1 - t);
        if (navigationState == NAV_SCROLL) {
            scrollY = navigationFromY + (pageScroll(navigationTarget) - navigationFromY) * eased;
            if (t == 1) cancelNavigation(true);
            viewportChanged();
        } else if (navigationState == NAV_COVER_IN) {
            coverAlpha = coverFromAlpha + (1 - coverFromAlpha) * eased;
            if (t == 1) {
                coverAlpha = 1;
                navigationState = NAV_COVERED;
            }
            invalidate();
        } else if (navigationState == NAV_COVERED) {
            if (coverDrawn) {
                pageJump = navigationTarget;
                scrollY = pageScroll(navigationTarget);
                navigationState = NAV_WAIT;
                viewportChanged();
            }
        }
        if (navigationState == NAV_WAIT && (visibleTilesReady() || now >= coverDeadline)) {
            navigationState = NAV_COVER_OUT;
            navigationStart = now;
            navigationDuration = 160;
        } else if (navigationState == NAV_COVER_OUT) {
            coverAlpha = 1 - eased;
            if (t == 1) cancelNavigation(true);
            invalidate();
        }
        if (navigationState != NAV_IDLE) postOnAnimation(navigationFrame);
    }

    private boolean visibleTilesReady() {
        if (wantedDirty) rebuildWanted();
        int start = viewport.pageAt((scrollY + contentTop()) / zoom);
        int end = viewport.pageAt((scrollY + contentTop() + effectiveHeight()) / zoom);
        boolean visible = false;
        for (int page = start; page <= end; page++) {
            double top = viewport.top(page) * zoom - scrollY;
            if (top < contentTop() + effectiveHeight() && top + viewport.pageHeight(page) * zoom > contentTop()) {
                if (!viewport.known(page) || !pageTilesReady(page)) return false;
                visible = true;
            }
        }
        return visible;
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus && scroller != null) finishMotion();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && scroller != null) finishMotion();
        else if (visibility == VISIBLE && scroller != null) schedulePump();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE && scroller != null) finishMotion();
        else if (visibility == VISIBLE && scroller != null) schedulePump();
    }

    public void setRenderingPaused(boolean paused) {
        if (renderingPaused == paused) return;
        renderingPaused = paused;
        if (paused) {
            finishMotion();
            removeCallbacks(pumpTask);
            pumpPosted = false;
        } else {
            schedulePump();
            invalidate();
        }
    }

    private boolean canPump() {
        return !renderingPaused && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE && isShown();
    }

    private int contentTop() { return Math.min(topInset, Math.max(0, getHeight() - 1)); }
    private int effectiveHeight() { return Math.max(1, getHeight() - contentTop() - Math.max(0, bottomInset)); }
    private boolean pageVisible(int page) {
        double top = viewport.top(page) * zoom - scrollY;
        return top < getHeight() && top + viewport.pageHeight(page) * zoom > 0;
    }
    private double maxX() { return Math.max(0, getWidth() * (double) zoom - getWidth()); }
    private double minY() { return -contentTop(); }
    private double maxY() {
        return viewport == null ? minY() : Math.max(minY(), viewport.height * zoom - contentTop() - effectiveHeight());
    }
    private static int safeInt(double value) { return (int) Math.min(Integer.MAX_VALUE - 1, Math.max(0, value)); }

    private void viewportChanged() {
        wantedDirty = true;
        qualityScaleLimit = Double.POSITIVE_INFINITY;
        scrollX = Math.max(0, Math.min(maxX(), scrollX));
        scrollY = Math.max(minY(), Math.min(maxY(), scrollY));
        if (viewport != null) {
            int page = navigationTarget >= 0 ? navigationTarget
                    : pageJump >= 0 ? pageJump
                    : maxY() - minY() > .5 && maxY() - scrollY <= .5 ? viewport.count() - 1
                    : viewport.pageAt((scrollY + contentTop() + inset) / zoom);
            if (currentPage != page) {
                currentPage = page;
                listener.onPageChanged(page, viewport.count());
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
            }
        }
        invalidate();
        schedulePump();
    }

    private void schedulePump() {
        if (canPump() && !pumpPosted && !admissionDeferred && client != null && !failed
                && pendingSize < 0 && pendingTile == null) {
            pumpPosted = true;
            postOnAnimation(pumpTask);
        }
    }

    private void pump() {
        if (!canPump() || client == null || failed || viewport == null || pendingSize >= 0 || pendingTile != null
                || getWidth() <= 0 || getHeight() <= 0 || zoomSettling || admissionDeferred) return;
        rebuildWanted();
        int start = viewport.pageAt(scrollY / zoom);
        int end = viewport.pageAt((scrollY + getHeight()) / zoom);
        for (int page = start; page <= end; page++) {
            if (pageVisible(page) && !viewport.known(page)) { requestSize(page); return; }
        }
        for (TileKey key : wanted) {
            Bitmap bitmap = cache.get(key);
            if (bitmap != null && !bitmap.isRecycled()) continue;
            if (bitmap != null) { onError(ERROR_RENDER); return; }
            if (key.rw <= TILE && key.rh <= TILE && opaqueWholePage(key.page, key.rw, key.rh)) continue;
            pendingColdPreview = coldPreviewKeys.contains(key) ? key : null;
            if (pendingColdPreview != null) {
                if (pageVisible(key.page)) coldPreviewAttempts.add(key.page);
                else attemptedColdPrefetch = key.page;
            }
            pendingTile = key;
            boolean accepted = client.renderTile(key.page, key.rw, key.rh, key.x, key.y, key.width(), key.height());
            if (!accepted) {
                pendingTile = null;
                if (pendingColdPreview != null) { pendingColdPreview = null; schedulePump(); }
                else rejected();
            }
            else rejectedRequests = 0;
            return;
        }
        if (end + 1 < viewport.count() && !viewport.known(end + 1)) requestSize(end + 1);
        else if (start > 0 && !viewport.known(start - 1)) requestSize(start - 1);
    }

    private void requestSize(int page) {
        pendingSize = page;
        if (!client.requestPageSize(page)) { pendingSize = -1; rejected(); }
        else rejectedRequests = 0;
    }

    private void rejected() {
        if (++rejectedRequests >= 3) { onError(ERROR_SERVICE); return; }
        pumpPosted = true;
        postDelayed(pumpTask, 100);
    }

    private double renderScale() {
        return Math.min(qualityScaleLimit, Math.max(.0625, Math.floor(zoom * 64) / 64));
    }

    private void rebuildWanted() {
        wantedDirty = false;
        wanted.clear();
        visibleTileCount = 0;
        protectedTiles.clear();
        coldPreviewKeys.clear();
        if (viewport == null || getWidth() <= 0 || getHeight() <= 0) return;
        int start = viewport.pageAt(scrollY / zoom);
        int end = viewport.pageAt((scrollY + getHeight()) / zoom);
        coldPreviewAttempts.removeIf(page -> page < start || page > end || !pageVisible(page));
        if (attemptedColdPrefetch != end + 1) attemptedColdPrefetch = -1;
        double scale = renderScale();
        long bytes;
        do {
            wanted.clear();
            for (int page = start; page <= end; page++) addVisibleTiles(page, scale);
            bytes = 0;
            for (TileKey key : wanted) bytes += 4L * key.width() * key.height();
            if (bytes <= cacheLimit * .65) break;
            scale *= .5;
        } while (scale > .000001);
        wanted.sort((a, b) -> {
            int visible = Boolean.compare(tileVisible(b), tileVisible(a));
            return visible != 0 ? visible : Double.compare(distance(a), distance(b));
        });
        for (TileKey key : wanted) if (tileVisible(key)) visibleTileCount++;
        ArrayList<TileKey> previews = new ArrayList<>();
        for (int page = start; page <= end; page++) {
            if (!pageVisible(page) || !viewport.known(page)) continue;
            TileKey key = coldPreviewKey(page);
            TileKey detail = keyFor(page, scale, 0, 0);
            if (key.rw >= detail.rw && key.rh >= detail.rh || wanted.contains(key)) continue;
            Bitmap cached = cache.get(key);
            if (cached != null && !cached.isRecycled()) {
                if (!previewNeeded(key, null)) continue;
            } else if (coldPreviewAttempts.contains(page) && !key.equals(pendingColdPreview) || pageFullyOpaque(page)) continue;
            long cost = 4L * key.width() * key.height();
            if (bytes + cost > cacheLimit * .75) continue;
            previews.add(key);
            coldPreviewKeys.add(key);
            bytes += cost;
        }
        wanted.addAll(0, previews);
        visibleTileCount += previews.size();
        if (end + 1 < viewport.count() && viewport.known(end + 1) && !pageFullyOpaque(end + 1)) {
            TileKey detail = keyFor(end + 1, scale, 0, 0);
            boolean coarse = detail.rw > TILE || detail.rh > TILE;
            TileKey key = coarse ? coldPreviewKey(end + 1) : detail;
            if ((!coarse || attemptedColdPrefetch != end + 1 || key.equals(pendingColdPreview))
                    && bytes + 4L * key.width() * key.height() <= cacheLimit * .75) {
                wanted.add(key);
                if (coarse) coldPreviewKeys.add(key);
                bytes += 4L * key.width() * key.height();
            }
        }
        for (int direction = 1; direction >= -1; direction -= 2) {
            double y = (scrollY + (direction > 0 ? getHeight() : 0)) / zoom;
            int page = viewport.pageAt(y);
            if (!viewport.known(page)) continue;
            TileKey base = keyFor(page, scale, 0, 0);
            double boundary = (y - viewport.top(page)) / viewport.pageHeight(page) * base.rh / TILE;
            int row = direction > 0 ? (int) Math.ceil(boundary) * TILE
                    : ((int) Math.floor(boundary) - 1) * TILE;
            if (row < 0 || row >= base.rh) {
                page += direction;
                if (page < 0 || page >= viewport.count() || !viewport.known(page)) continue;
                base = keyFor(page, scale, 0, 0);
                row = direction > 0 ? 0 : (base.rh - 1) / TILE * TILE;
            }
            if (page == end + 1 && (base.rw > TILE || base.rh > TILE)) continue;
            int x = Math.max(0, Math.min((base.rw - 1) / TILE * TILE,
                    (int) Math.floor((scrollX / zoom - inset) / viewport.pageWidth * base.rw / TILE) * TILE));
            double right = Math.min(base.rw, ((scrollX + getWidth()) / zoom - inset) / viewport.pageWidth * base.rw);
            for (; x < right; x += TILE) {
                TileKey key = keyFor(page, scale, x, row);
                long cost = 4L * key.width() * key.height();
                if (!wanted.contains(key) && bytes + cost <= cacheLimit * .75) {
                    wanted.add(key);
                    bytes += cost;
                }
            }
        }
        protectedTiles.addAll(wanted);
    }

    private void addVisibleTiles(int page, double scale) {
        TileKey base = keyFor(page, scale, 0, 0);
        double left = (scrollX / zoom - inset) / viewport.pageWidth * base.rw;
        double right = ((scrollX + getWidth()) / zoom - inset) / viewport.pageWidth * base.rw;
        double top = (scrollY / zoom - viewport.top(page)) / viewport.pageHeight(page) * base.rh;
        double bottom = ((scrollY + getHeight()) / zoom - viewport.top(page)) / viewport.pageHeight(page) * base.rh;
        if (right <= 0 || left >= base.rw || bottom <= 0 || top >= base.rh) return;
        int x0 = Math.max(0, (int) Math.floor(left / TILE) * TILE);
        int y0 = Math.max(0, (int) Math.floor(top / TILE) * TILE);
        for (int y = y0; y < Math.min(base.rh, bottom); y += TILE) {
            for (int x = x0; x < Math.min(base.rw, right); x += TILE) {
                wanted.add(new TileKey(page, base.rw, base.rh, x, y));
            }
        }
    }

    private TileKey keyFor(int page, double scale, int x, int y) {
        double limited = Math.min(scale, 65535.0 / Math.max(viewport.pageWidth, viewport.pageHeight(page)));
        limited = Math.min(limited, Math.sqrt((Integer.MAX_VALUE - 131072.0)
                / (viewport.pageWidth * viewport.pageHeight(page))));
        return new TileKey(page, Math.max(1, (int) Math.ceil(viewport.pageWidth * limited)),
                Math.max(1, (int) Math.ceil(viewport.pageHeight(page) * limited)), x, y);
    }

    private TileKey coldPreviewKey(int page) {
        double width = viewport.pageWidth, height = viewport.pageHeight(page);
        double scale = TILE / Math.max(width, height);
        return new TileKey(page, Math.max(1, Math.min(TILE, (int) Math.ceil(width * scale))),
                Math.max(1, Math.min(TILE, (int) Math.ceil(height * scale))), 0, 0);
    }

    private boolean pageFullyOpaque(int page) {
        if (opaqueWholePage(page, 1, 1)) return true;
        for (RasterGroup group : rasterGroups) {
            if (group.page != page) continue;
            coveredTileCheck.set(0, 0, group.rw, group.rh);
            coveredTileCheck.op(group.opaque, Region.Op.DIFFERENCE);
            if (coveredTileCheck.isEmpty()) return true;
        }
        return false;
    }

    private boolean opaqueWholePage(int page, int minWidth, int minHeight) {
        for (Map.Entry<TileKey, Bitmap> entry : cache.entrySet()) {
            TileKey key = entry.getKey();
            if (key.page == page && key.x == 0 && key.y == 0 && key.rw <= TILE && key.rh <= TILE
                    && key.rw >= minWidth && key.rh >= minHeight
                    && !entry.getValue().isRecycled() && !qualityBlends.containsKey(key)) return true;
        }
        return false;
    }

    private boolean pageTilesReady(int page) {
        if (opaqueWholePage(page, 1, 1)) return true;
        boolean visible = false;
        for (TileKey key : wanted) {
            if (key.page != page || coldPreviewKeys.contains(key) || !tileVisible(key)) continue;
            double left = (inset + viewport.pageWidth * key.x / key.rw) * zoom - scrollX;
            double right = (inset + viewport.pageWidth * (key.x + key.width()) / key.rw) * zoom - scrollX;
            if (right <= 0 || left >= getWidth()) continue;
            Bitmap bitmap = cache.get(key);
            if (bitmap == null || bitmap.isRecycled() || qualityBlends.containsKey(key)) return false;
            visible = true;
        }
        return visible;
    }

    private double distance(TileKey key) {
        double x = (inset + viewport.pageWidth * (key.x + key.width() / 2.0) / key.rw) * zoom - scrollX;
        double y = (viewport.top(key.page) + viewport.pageHeight(key.page) * (key.y + key.height() / 2.0) / key.rh) * zoom - scrollY;
        return Math.abs(y - contentTop() - effectiveHeight() / 2.0) + Math.abs(x - getWidth() / 2.0) * .25;
    }

    private boolean tileVisible(TileKey key) {
        double top = (viewport.top(key.page) + viewport.pageHeight(key.page) * key.y / key.rh) * zoom - scrollY;
        return top < contentTop() + effectiveHeight()
                && top + viewport.pageHeight(key.page) * key.height() / key.rh * zoom > contentTop();
    }

    private QualityBlend previewFor(TileKey target) {
        for (int index = rasterGroups.size() - 1; index >= 0; index--) {
            RasterGroup group = rasterGroups.get(index);
            if (group.page != target.page || (long) group.rw * group.rh >= (long) target.rw * target.rh) continue;
            int left = (int) Math.floor(target.x * (double) group.rw / target.rw);
            int top = (int) Math.floor(target.y * (double) group.rh / target.rh);
            int right = (int) Math.ceil((target.x + target.width()) * (double) group.rw / target.rw);
            int bottom = (int) Math.ceil((target.y + target.height()) * (double) group.rh / target.rh);
            Region missing = null;
            QualityBlend blend = null;
            for (int i = group.start; i < group.end; i++) {
                Map.Entry<TileKey, Bitmap> entry = drawingTiles.get(i);
                TileKey key = entry.getKey();
                if (entry.getValue().isRecycled() || qualityBlends.containsKey(key)
                        || key.x >= right || key.x + key.width() <= left
                        || key.y >= bottom || key.y + key.height() <= top) continue;
                if (missing == null) {
                    missing = new Region(left, top, right, bottom);
                    blend = new QualityBlend(SystemClock.uptimeMillis());
                }
                blend.previews.add(key);
                missing.op(key.x, key.y, key.x + key.width(), key.y + key.height(), Region.Op.DIFFERENCE);
                if (missing.isEmpty()) return blend;
            }
        }
        return null;
    }

    private boolean initialPageReady() {
        if (viewport == null || failed) return false;
        if (wantedDirty) rebuildWanted();
        int page = pageJump >= 0 ? pageJump : viewport.pageAt((scrollY + contentTop() + inset) / zoom);
        if (!viewport.known(page)) return false;
        boolean visible = pageTilesReady(page);
        return visible;
    }

    private void rebuildCoveredTiles() {
        coveredTiles.clear();
        for (RasterGroup group : rasterGroups) {
            group.opaque.setEmpty();
            for (int i = group.start; i < group.end; i++) {
                Map.Entry<TileKey, Bitmap> entry = drawingTiles.get(i);
                TileKey key = entry.getKey();
                if (!entry.getValue().isRecycled() && !qualityBlends.containsKey(key)) {
                    group.opaque.op(key.x, key.y, key.x + key.width(), key.y + key.height(), Region.Op.UNION);
                }
            }
        }
        for (Map.Entry<TileKey, Bitmap> entry : drawingTiles) {
            TileKey key = entry.getKey();
            if (entry.getValue().isRecycled()) continue;
            for (int i = rasterGroups.size() - 1; i >= 0; i--) {
                RasterGroup group = rasterGroups.get(i);
                if (group.page != key.page || (long) group.rw * group.rh <= (long) key.rw * key.rh) continue;
                int left = (int) Math.floor(key.x * (double) group.rw / key.rw) - 1;
                int top = (int) Math.floor(key.y * (double) group.rh / key.rh) - 1;
                int right = (int) Math.ceil((key.x + key.width()) * (double) group.rw / key.rw) + 1;
                int bottom = (int) Math.ceil((key.y + key.height()) * (double) group.rh / key.rh) + 1;
                coveredTileCheck.set(left, top, right, bottom);
                coveredTileCheck.op(group.opaque, Region.Op.DIFFERENCE);
                if (coveredTileCheck.isEmpty()) {
                    coveredTiles.put(key, group);
                    break;
                }
            }
        }
    }

    private boolean tileCovered(TileKey key, float left, float top, float width, float height) {
        RasterGroup group = coveredTiles.get(key);
        if (group == null || drawMatrixValues[Matrix.MSKEW_X] != 0 || drawMatrixValues[Matrix.MSKEW_Y] != 0
                || drawMatrixValues[Matrix.MPERSP_0] != 0 || drawMatrixValues[Matrix.MPERSP_1] != 0
                || drawMatrixValues[Matrix.MPERSP_2] != 1) return false;
        return coveredAxis(left, width * TILE / group.rw, Math.max(rect.left, drawClip.left),
                Math.min(rect.right, drawClip.right), drawMatrixValues[Matrix.MSCALE_X], drawMatrixValues[Matrix.MTRANS_X])
                && coveredAxis(top, height * TILE / group.rh, Math.max(rect.top, drawClip.top),
                Math.min(rect.bottom, drawClip.bottom), drawMatrixValues[Matrix.MSCALE_Y], drawMatrixValues[Matrix.MTRANS_Y]);
    }

    private static boolean coveredAxis(float origin, float step, float start, float end, float scale, float translation) {
        if (!(scale > 0) || !(step > 0) || !Float.isFinite(scale) || !Float.isFinite(step)
                || !Float.isFinite(origin) || !Float.isFinite(translation)) return false;
        double first = (start - origin) / step;
        double last = (end - origin) / step;
        double seam = Math.ceil(first);
        if (seam > last && (first - Math.floor(first)) * step * scale >= 1
                && (Math.ceil(last) - last) * step * scale >= 1) return true;
        double deviceOrigin = origin * (double) scale + translation;
        double deviceStep = step * (double) scale;
        return deviceStep >= 1 && deviceOrigin == Math.rint(deviceOrigin) && deviceStep == Math.rint(deviceStep);
    }

    private void rebuildDrawingTiles() {
        drawingTiles.clear();
        drawingTiles.addAll(cache.entrySet());
        drawingTiles.sort((a, b) -> {
            TileKey first = a.getKey(), second = b.getKey();
            int order = Integer.compare(first.page, second.page);
            if (order == 0) order = Long.compare((long) first.rw * first.rh, (long) second.rw * second.rh);
            if (order == 0) order = Integer.compare(first.rw, second.rw);
            if (order == 0) order = Integer.compare(first.rh, second.rh);
            return order;
        });
        rasterGroups.clear();
        for (int start = 0; start < drawingTiles.size();) {
            TileKey key = drawingTiles.get(start).getKey();
            int end = start + 1;
            while (end < drawingTiles.size()) {
                TileKey next = drawingTiles.get(end).getKey();
                if (key.page != next.page || key.rw != next.rw || key.rh != next.rh) break;
                end++;
            }
            rasterGroups.add(new RasterGroup(key, start, end));
            start = end;
        }
        rebuildCoveredTiles();
    }

    private boolean previewNeeded(TileKey key, TileKey incoming) {
        for (QualityBlend blend : qualityBlends.values()) if (blend.previews.contains(key)) return true;
        double left = Math.max(key.x, (scrollX / zoom - inset) / viewport.pageWidth * key.rw);
        double right = Math.min(key.x + key.width(), ((scrollX + getWidth()) / zoom - inset) / viewport.pageWidth * key.rw);
        double top = Math.max(key.y, (scrollY / zoom - viewport.top(key.page)) / viewport.pageHeight(key.page) * key.rh);
        double bottom = Math.min(key.y + key.height(), ((scrollY + getHeight()) / zoom - viewport.top(key.page)) / viewport.pageHeight(key.page) * key.rh);
        if (left >= right || top >= bottom) return false;
        Region missing = null;
        for (TileKey replacement : wanted) {
            if (replacement.equals(key) || replacement.page != key.page || qualityBlends.containsKey(replacement)
                    || !replacement.equals(incoming) && !cache.containsKey(replacement)) continue;
            if (missing == null) {
                missing = new Region((int) Math.floor(left * replacement.rw / key.rw),
                        (int) Math.floor(top * replacement.rh / key.rh),
                        (int) Math.ceil(right * replacement.rw / key.rw),
                        (int) Math.ceil(bottom * replacement.rh / key.rh));
            }
            missing.op(replacement.x, replacement.y, replacement.x + replacement.width(),
                    replacement.y + replacement.height(), Region.Op.DIFFERENCE);
            if (missing.isEmpty()) return false;
        }
        return true;
    }

    private void advanceQuality() {
        qualityFramePosted = false;
        if (!motionAllowed()) { finishQuality(); return; }
        long now = SystemClock.uptimeMillis();
        Iterator<QualityBlend> iterator = qualityBlends.values().iterator();
        boolean completed = false;
        while (iterator.hasNext()) {
            QualityBlend blend = iterator.next();
            float t = Math.max(0, Math.min(1, (now - blend.started) / (blend.fresh ? 160f : 140f)));
            blend.alpha = blend.fresh ? t * t * (3 - 2 * t) : 1 - (1 - t) * (1 - t) * (1 - t);
            if (t == 1) {
                iterator.remove();
                completed = true;
            }
        }
        if (completed) rebuildCoveredTiles();
        invalidate();
        if (!qualityBlends.isEmpty()) {
            qualityFramePosted = true;
            postOnAnimation(qualityFrame);
        } else resumeAdmission();
    }

    private void finishQuality() {
        removeCallbacks(qualityFrame);
        qualityFramePosted = false;
        qualityBlends.clear();
        rebuildCoveredTiles();
        tilePaint.setAlpha(255);
        resumeAdmission();
        invalidate();
    }

    private void resumeAdmission() {
        if (!admissionDeferred) return;
        admissionDeferred = false;
        schedulePump();
    }

    private void evict(long incoming, TileKey target) {
        long needed = cacheBytes + incoming - cacheLimit;
        if (needed <= 0) return;
        ArrayList<TileKey> candidates = new ArrayList<>();
        for (Map.Entry<TileKey, Bitmap> entry : cache.entrySet()) {
            if (!protectedTiles.contains(entry.getKey()) && !previewNeeded(entry.getKey(), target)) {
                candidates.add(entry.getKey());
                needed -= entry.getValue().getAllocationByteCount();
                if (needed <= 0) break;
            }
        }
        if (needed > 0) {
            return;
        }
        for (TileKey key : candidates) {
            Bitmap bitmap = cache.remove(key);
            cacheBytes -= bitmap.getAllocationByteCount();
        }
    }

    private static final class QualityBlend {
        final long started;
        final ArrayList<TileKey> previews = new ArrayList<>();
        float alpha;
        boolean fresh;
        QualityBlend(long started) { this.started = started; }
    }

    private static final class RasterGroup {
        final int page, rw, rh, start, end;
        final Region opaque = new Region();
        RasterGroup(TileKey key, int start, int end) {
            page = key.page;
            rw = key.rw;
            rh = key.rh;
            this.start = start;
            this.end = end;
        }
    }

    private static final class TileKey {
        final int page, rw, rh, x, y;
        TileKey(int page, int rw, int rh, int x, int y) {
            this.page = page; this.rw = rw; this.rh = rh; this.x = x; this.y = y;
        }
        int width() { return Math.min(TILE, rw - x); }
        int height() { return Math.min(TILE, rh - y); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof TileKey)) return false;
            TileKey key = (TileKey) other;
            return page == key.page && rw == key.rw && rh == key.rh && x == key.x && y == key.y;
        }
        @Override public int hashCode() { return ((((page * 31 + rw) * 31 + rh) * 31 + x) * 31 + y); }
    }
}
