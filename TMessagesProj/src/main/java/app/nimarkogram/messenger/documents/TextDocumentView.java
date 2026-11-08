/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.content.Context;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.View;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class TextDocumentView extends FrameLayout {
    public static final int ERROR_FILE = 1;
    public static final int ERROR_TOO_LARGE = 2;
    public static final int ERROR_ENCODING = 3;
    public static final int MAX_MATCHES = 1000;

    public interface Listener {
        void onReady(int blocks);
        void onSearchChanged(int zeroBasedIndex, int count, boolean truncated);
        void onFailure(int code);
        default void onScaleChanged(float scale) {}
    }

    private boolean allowCopy;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RecyclerListView list;
    private final ZoomLayoutManager layout;
    private final DocumentViewport viewport;
    private final BlocksAdapter adapter;
    private final FrameLayout selectionOverlay;
    private final TextDocumentSelection selection;
    private final Set<TextView> boundViews = Collections.newSetFromMap(new WeakHashMap<>());
    private ThreadPoolExecutor worker;
    private Future<?> loading;
    private Future<?> searching;
    private Delivery delivery;
    private BoundedTextLoader.Result document;
    private List<Hit> hits = Collections.emptyList();
    private String query = "";
    private long ownerGeneration;
    private long searchGeneration;
    private boolean closed = true;
    private boolean truncated;
    private int currentHit = -1;
    public static final float MIN_SCALE = TextZoomGeometry.MIN_SCALE;
    public static final float MAX_SCALE = TextZoomGeometry.MAX_SCALE;
    private float zoomScale = 1f;
    private float requestedScale = 1f;
    private float horizontalOffset, verticalOffset;
    private ValueAnimator zoomAnimator;
    private boolean touchActive, stopping, pendingSearch;
    private Runnable searchTask;
    private ReadingAnchor geometryAnchor;
    private int topInset, bottomInset;
    private ValueAnimator searchAnimator;
    private ValueAnimator highlightAnimator;
    private float highlightProgress = 1f;
    private long navigationGeneration;
    private long searchNavigationGeneration;
    private boolean monospaced;
    private int textColor = 0xff202020;
    private int paperColor = 0xffffffff;
    private int secondaryColor = 0xff707070;
    private int highlightColor = 0xffffd666;

    public TextDocumentView(Context context, boolean allowCopy, Listener listener) {
        super(context);
        this.allowCopy = allowCopy;
        this.listener = listener;
        layout = new ZoomLayoutManager(context);
        viewport = new DocumentViewport(context);
        list = new RecyclerListView(context) {
            @Override
            protected boolean allowSelectChildAtPosition(View child) {
                return false;
            }

            @Override
            public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
                return revealNativeRectangle(child, rect);
            }

            @Override public boolean canScrollVertically(int direction) {
                if (layout == null || viewport == null) return super.canScrollVertically(direction);
                return canScrollDocument(direction);
            }

        };
        list.setLayoutManager(layout);
        list.setVerticalScrollBarEnabled(false);
        list.setHorizontalScrollBarEnabled(false);
        list.setItemAnimator(null);
        list.setNestedScrollingEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setPivotX(0f);
        list.setPivotY(0f);
        list.setDrawSelection(false);
        list.setClipToPadding(false);
        list.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        adapter = new BlocksAdapter();
        list.setAdapter(adapter);
        viewport.addView(list, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        selectionOverlay = new FrameLayout(context);
        selection = new TextDocumentSelection(new TextDocumentSelection.Host() {
            @Override public boolean allowCopy(TextDocumentSelection.BlockTextView view) {
                if (closed || document == null || !TextDocumentView.this.allowCopy
                        || view.getParent() != list || view.documentOwner != ownerGeneration
                        || list.getAdapter() != adapter || list.hasPendingAdapterUpdates()) return false;
                int position = list.getChildAdapterPosition(view);
                return position >= 0 && position < document.blocks.size();
            }

            @Override public void stopScroll() {
                cancelZoom();
                stopSearchScroll();
                pendingSearch = false;
                list.stopScroll();
            }

            @Override public boolean textToOverlay(TextDocumentSelection.BlockTextView view, float[] point) {
                return TextDocumentView.this.selection.mapToAncestor(view, viewport, point);
            }

            @Override public boolean overlayToText(TextDocumentSelection.BlockTextView view, float[] point) {
                return TextDocumentView.this.selection.mapFromAncestor(view, viewport, point);
            }
        }, selectionOverlay);
        viewport.addView(selectionOverlay, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView recyclerView, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) selection.onScrollStarted();
            }
        });
        addView(viewport, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setBackgroundColor(0xffffffff);
    }

    public void open(File file, String encoding) {
        requireUiThread();
        stopScrolling();
        cancelWork();
        closed = false;
        delivery = new Delivery(handler);
        long generation = ++ownerGeneration;
        ++searchGeneration;
        searchNavigationGeneration = navigationGeneration;
        document = null;
        geometryAnchor = null;
        verticalOffset = 0;
        clearHits();
        adapter.notifyDataSetChanged();
        if (worker == null || worker.isShutdown()) {
            worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1), runnable -> {
                        Thread thread = new Thread(runnable, "NimarkoTextDocument");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.DiscardOldestPolicy());
            worker.allowCoreThreadTimeOut(true);
        }
        WeakReference<TextDocumentView> owner = new WeakReference<>(this);
        Delivery target = delivery;
        loading = worker.submit(() -> load(owner, target, generation, file, encoding));
    }

    public void setBottomInset(int pixels) {
        int next = Math.max(0, pixels);
        if (bottomInset == next) return;
        saveGeometryAnchor();
        bottomInset = next;
        updateViewportInsets();
    }

    public void setTopInset(int pixels) {
        int next = Math.max(0, pixels);
        if (topInset == next) return;
        saveGeometryAnchor();
        topInset = next;
        updateViewportInsets();
    }

    private void updateViewportInsets() {
        list.setPadding(0, nativeTop() + AndroidUtilities.dp(12), 0,
                nativeBottom() + AndroidUtilities.dp(12));
        viewport.requestLayout();
        pendingSearch |= currentHit >= 0;
    }

    private int nativeTop() {
        return (int) Math.ceil(topInset / TextZoomGeometry.MIN_SCALE);
    }

    private int nativeBottom() {
        return (int) Math.ceil(bottomInset / TextZoomGeometry.MIN_SCALE);
    }

    private int safeHeight() {
        return Math.max(0, viewport.getHeight() - topInset - bottomInset);
    }

    void captureForBlur(Canvas canvas) {
        draw(canvas);
    }

    public void close() {
        requireUiThread();
        closed = true;
        cancelTextSelection();
        ++ownerGeneration;
        ++searchGeneration;
        cancelWork();
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        document = null;
        query = "";
        clearHits();
        list.stopScroll();
        list.setAdapter(null);
        list.getRecycledViewPool().clear();
        list.setAdapter(adapter);
        boundViews.clear();
    }

    public void setQuery(String value) {
        requireUiThread();
        String next = value == null ? "" : value;
        if (query.equals(next)) return;
        stopSearchScroll();
        query = next;
        searchNavigationGeneration = navigationGeneration;
        ++searchGeneration;
        if (delivery != null) delivery.setSearch(searchGeneration);
        if (searching != null) searching.cancel(true);
        searching = null;
        clearHits();
        refreshHighlights();
        if (!closed && document != null) scheduleSearch();
    }

    public void nextMatch(boolean forward) {
        requireUiThread();
        if (closed || hits.isEmpty()) return;
        stopSearchScroll();
        currentHit = (currentHit + (forward ? 1 : hits.size() - 1)) % hits.size();
        refreshHighlights();
        scrollToCurrent();
        notifySearch();
    }

    public float getFontScale() {
        return zoomScale;
    }

    public float getRequestedFontScale() {
        return requestedScale;
    }

    public boolean isZoomed() {
        return zoomScale > 1.0001f || requestedScale > 1.0001f || viewport.pinch || viewport.pan;
    }

    public void setFontScale(float scale) {
        requireUiThread();
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return;
        float target = TextZoomGeometry.scale(scale);
        cancelZoom();
        requestedScale = target;
        pendingSearch |= searchAnimator != null;
        stopSearchScroll();
        if (target == zoomScale) {
            resumePendingSearch();
            return;
        }
        final float focusX = viewport.getWidth() / 2f;
        final float focusY = topInset + safeHeight() / 2f;
        if (document == null || !animationsEnabled()) {
            applyZoom(target, focusX, focusY, focusX, focusY);
            requestedScale = zoomScale;
            resumePendingSearch();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(zoomScale, target);
        zoomAnimator = animator;
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            if (zoomAnimator != animation) return;
            if (!SharedConfig.animationsEnabled()
                    || android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
                cancelZoom();
                applyZoom(target, focusX, focusY, focusX, focusY);
                requestedScale = zoomScale;
                resumePendingSearch();
                return;
            }
            applyZoom((float) animation.getAnimatedValue(), focusX, focusY, focusX, focusY);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (zoomAnimator != animation) return;
                zoomAnimator = null;
                requestedScale = zoomScale;
                resumePendingSearch();
            }
        });
        animator.start();
    }

    private boolean animationsEnabled() {
        return !closed && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && SharedConfig.animationsEnabled()
                && (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
    }

    private void cancelZoom() {
        requestedScale = zoomScale;
        if (zoomAnimator == null) return;
        ValueAnimator previous = zoomAnimator;
        zoomAnimator = null;
        previous.cancel();
    }

    private void applyZoom(float scale, float oldX, float oldY, float newX, float newY) {
        float previous = zoomScale;
        zoomScale = TextZoomGeometry.scale(scale);
        horizontalOffset = newX - (oldX - horizontalOffset) * zoomScale / previous;
        scrollNative(TextZoomGeometry.focalScroll(oldY - topInset, newY - topInset, previous, zoomScale));
        applyViewportTransform();
        if (listener != null) listener.onScaleChanged(zoomScale);
    }

    private float maximumVerticalOffset() {
        int count = layout.getItemCount();
        View last = count == 0 ? null : layout.findViewByPosition(count - 1);
        return count == 0 ? 0 : TextZoomGeometry.maximumOffset(last != null,
                last == null ? 0 : last.getBottom() - nativeTop(), AndroidUtilities.dp(12), safeHeight(), zoomScale);
    }

    private void clampVerticalOffset() {
        verticalOffset = Math.max(0f, Math.min(maximumVerticalOffset(), verticalOffset));
    }

    private boolean canScrollDocument(int direction) {
        int count = layout.getItemCount();
        if (count == 0 || list.getChildCount() == 0 || safeHeight() == 0) return false;
        if (direction < 0) {
            View first = layout.findViewByPosition(0);
            return first == null || first.getTop() - list.getPaddingTop() - verticalOffset < -.01f;
        }
        View last = layout.findViewByPosition(count - 1);
        return direction > 0 && (last == null ||
                last.getBottom() + AndroidUtilities.dp(12) - nativeTop() - verticalOffset > safeHeight() / zoomScale + .01f);
    }

    private void applyViewportTransform() {
        if (list == null || viewport == null) return;
        horizontalOffset = TextZoomGeometry.horizontal(horizontalOffset, viewport.getWidth(), zoomScale);
        list.setScaleX(zoomScale);
        list.setScaleY(zoomScale);
        list.setTranslationX(horizontalOffset);
        list.setTranslationY(topInset - (nativeTop() + verticalOffset) * zoomScale);
        viewport.invalidate();
    }

    private void scrollNative(float distance) {
        if (list.isComputingLayout()) return;
        float desired = verticalOffset + distance;
        int whole = (int) Math.floor(desired);
        verticalOffset = desired - whole;
        if (whole != 0) list.scrollBy(0, whole);
        clampVerticalOffset();
        applyViewportTransform();
    }

    private final class ZoomLayoutManager extends LinearLayoutManager {
        ZoomLayoutManager(Context context) { super(context); }

        @Override public boolean performAccessibilityAction(RecyclerView.Recycler recycler, RecyclerView.State state,
                                                             int action, android.os.Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                int direction = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1;
                if (stopping || !canScrollDocument(direction)) return false;
                cancelZoom();
                stopSearchScroll();
                list.stopScroll();
                scrollNative(direction * safeHeight() / zoomScale);
                return true;
            }
            return super.performAccessibilityAction(recycler, state, action, arguments);
        }

        @Override public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
            float before = verticalOffset;
            float wanted = before + dy;
            int consumed = super.scrollVerticallyBy((int) Math.floor(wanted), recycler, state);
            verticalOffset = wanted - consumed;
            clampVerticalOffset();
            applyViewportTransform();
            int moved = Math.round(consumed + verticalOffset - before);
            return dy > 0 ? Math.max(0, Math.min(dy, moved)) : Math.min(0, Math.max(dy, moved));
        }

        @Override public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
            ReadingAnchor anchor = geometryAnchor;
            if (anchor != null && !state.isPreLayout()) {
                scrollToPositionWithOffset(anchor.position, -anchor.rowOffset);
            }
            super.onLayoutChildren(recycler, state);
            if (!state.isPreLayout()) {
                geometryAnchor = null;
                if (anchor != null) {
                    View row = findViewByPosition(anchor.position);
                    if (row instanceof TextView && ((TextView) row).getLayout() != null) {
                        TextView text = (TextView) row;
                        int line = text.getLayout().getLineForOffset(Math.min(anchor.offset, text.length()));
                        float y = row.getTop() + text.getPaddingTop() + text.getLayout().getLineTop(line);
                        float wanted = y - nativeTop() - (anchor.physicalY - topInset) / zoomScale;
                        verticalOffset = 0;
                        int consumed = super.scrollVerticallyBy((int) Math.floor(wanted), recycler, state);
                        verticalOffset = wanted - consumed;
                    }
                }
                clampVerticalOffset();
                applyViewportTransform();
            }
        }
    }

    private final class DocumentViewport extends FrameLayout {
        private final android.view.ScaleGestureDetector detector;
        private final int touchSlop;
        private float downX, downY, lastX, lastY, focusX, focusY;
        private boolean pinch, pan;
        private boolean pointerChange;

        DocumentViewport(Context context) {
            super(context);
            setClipChildren(true);
            setClipToPadding(true);
            touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
            detector = new android.view.ScaleGestureDetector(context,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                    cancelZoom();
                    stopSearchScroll();
                    pendingSearch = false;
                    list.stopScroll();
                    cancelTextSelection();
                    pinch = true;
                    focusX = detector.getFocusX();
                    focusY = detector.getFocusY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }

                @Override public boolean onScale(android.view.ScaleGestureDetector detector) {
                    float x = detector.getFocusX(), y = detector.getFocusY();
                    applyZoom(zoomScale * detector.getScaleFactor(), pointerChange ? x : focusX,
                            pointerChange ? y : focusY, x, y);
                    requestedScale = zoomScale;
                    focusX = x;
                    focusY = y;
                    return true;
                }
            });
            detector.setQuickScaleEnabled(false);
            detector.setStylusScaleEnabled(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            selectionOverlay.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            int safe = Math.max(0, height - topInset - bottomInset);
            list.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(nativeTop() + nativeBottom()
                            + (int) Math.ceil(safe / TextZoomGeometry.MIN_SCALE) + 2, MeasureSpec.EXACTLY));
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            list.layout(0, 0, list.getMeasuredWidth(), list.getMeasuredHeight());
            selectionOverlay.layout(0, 0, right - left, bottom - top);
            clampVerticalOffset();
            applyViewportTransform();
            resumePendingSearch();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancelZoom();
                stopSearchScroll();
                pendingSearch = false;
                pinch = pan = false;
                touchActive = true;
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                if (zoomScale > 1f) getParent().requestDisallowInterceptTouchEvent(true);
            }
            pointerChange = action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP;
            detector.onTouchEvent(event);
            if (pointerChange) {
                focusX = detector.getFocusX();
                focusY = detector.getFocusY();
            }
            if (pinch || zoomScale > 1f) {
                super.requestDisallowInterceptTouchEvent(false);
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchActive = false;
                pinch = pan = false;
                resumePendingSearch();
            }
            return handled;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (pinch) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && zoomScale > 1f && !hasTextSelection()) {
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    pan = true;
                    lastX = event.getX();
                    lastY = event.getY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            return pan;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!pinch && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (zoomScale > 1f) horizontalOffset += event.getX() - lastX;
                scrollNative((lastY - event.getY()) / zoomScale);
                lastX = event.getX();
                lastY = event.getY();
                applyViewportTransform();
            }
            return true;
        }
    }

    private boolean hasTextSelection() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).hasSelection()) return true;
        }
        return false;
    }

    private boolean revealNativeRectangle(View child, Rect rect) {
        if (zoomAnimator != null || viewport.pinch || list.isComputingLayout() || stopping) return false;
        float left = child.getLeft() + rect.left - child.getScrollX();
        float right = child.getLeft() + rect.right - child.getScrollX();
        float top = topInset + (child.getTop() + rect.top - child.getScrollY() - nativeTop() - verticalOffset) * zoomScale;
        float bottom = topInset + (child.getTop() + rect.bottom - child.getScrollY() - nativeTop() - verticalOffset) * zoomScale;
        float delta = top < topInset ? top - topInset : Math.max(0, bottom - (viewport.getHeight() - bottomInset));
        float previous = horizontalOffset;
        if (left * zoomScale + horizontalOffset < 0) horizontalOffset = -left * zoomScale;
        else if (right * zoomScale + horizontalOffset > viewport.getWidth()) {
            horizontalOffset = viewport.getWidth() - right * zoomScale;
        }
        scrollNative(delta / zoomScale);
        return delta != 0 || previous != horizontalOffset;
    }

    private static final class ReadingAnchor {
        final int position, offset, rowOffset;
        final float physicalY;
        ReadingAnchor(int position, int offset, int rowOffset, float physicalY) {
            this.position = position;
            this.offset = offset;
            this.rowOffset = rowOffset;
            this.physicalY = physicalY;
        }
    }

    private void saveGeometryAnchor() {
        cancelZoom();
        list.stopScroll();
        if (geometryAnchor != null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (!(row instanceof TextView) || row.getBottom() <= nativeTop() + verticalOffset) continue;
            TextView text = (TextView) row;
            if (text.getLayout() == null) continue;
            int line = text.getLayout().getLineForVertical(Math.max(0,
                    nativeTop() + (int) verticalOffset - row.getTop() - text.getPaddingTop()));
            int lineY = text.getPaddingTop() + text.getLayout().getLineTop(line);
            int position = list.getChildAdapterPosition(row);
            if (position != RecyclerView.NO_POSITION) geometryAnchor = new ReadingAnchor(position,
                    text.getLayout().getLineStart(line), lineY,
                    topInset + (row.getTop() + lineY - nativeTop() - verticalOffset) * zoomScale);
            return;
        }
    }

    private void cancelPresentation() {
        cancelTextSelection();
        cancelZoom();
        stopSearchScroll();
        finishHighlightAnimation();
        pendingSearch = false;
        removeSearchTask();
        list.stopScroll();
    }

    @Override protected void onDetachedFromWindow() {
        stopScrolling();
        cancelPresentation();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && list != null) { stopScrolling(); cancelPresentation(); }
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE && list != null) { stopScrolling(); cancelPresentation(); }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (list != null && oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) saveGeometryAnchor();
    }

    private void updateTextStyle() {
        for (TextView view : new ArrayList<>(boundViews)) applyTextStyle(view);
    }

    private void applyTextStyle(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        view.setTypeface(monospaced ? Typeface.MONOSPACE : Typeface.DEFAULT);
    }

    public void setMonospaced(boolean value) {
        requireUiThread();
        if (monospaced == value) return;
        saveGeometryAnchor();
        monospaced = value;
        updateTextStyle();
        list.requestLayout();
    }

    public void setAllowCopy(boolean value) {
        requireUiThread();
        if (allowCopy == value) return;
        if (!value) cancelTextSelection();
        allowCopy = value;
        selection.permissionsChanged();
        for (TextView view : new ArrayList<>(boundViews)) {
            setSelectionEnabled(view, value && view.isAttachedToWindow());
        }
        list.getRecycledViewPool().clear();
    }

    public void setColors(int background, int text, int secondary, int highlight) {
        requireUiThread();
        paperColor = background;
        setBackgroundColor(background);
        textColor = text;
        secondaryColor = secondary;
        highlightColor = highlight;
        for (int i = 0; i < list.getChildCount(); i++) {
            if (!(list.getChildAt(i) instanceof TextView)) continue;
            applyTextColors((TextView) list.getChildAt(i));
        }
    }

    private void applyTextColors(TextView view) {
        view.setTextColor(textColor);
        view.setHintTextColor(secondaryColor);
        view.setHighlightColor(highlightColor);
        view.invalidate();
    }

    private final class SearchHighlight extends CharacterStyle implements UpdateAppearance {
        private final float from, to, underlineFrom, underlineTo;

        SearchHighlight(float from, float to, float underlineFrom, float underlineTo) {
            this.from = from;
            this.to = to;
            this.underlineFrom = underlineFrom;
            this.underlineTo = underlineTo;
        }

        float level() { return from + (to - from) * highlightProgress; }

        float underline() { return underlineFrom + (underlineTo - underlineFrom) * highlightProgress; }

        @Override public void updateDrawState(TextPaint paint) {
            paint.bgColor = (highlightColor & 0x00ffffff)
                    | ((int) ((highlightColor >>> 24) * level() / 3f) << 24);
            float underline = underline();
            if (underline > 0) {
                paint.underlineColor = (paint.getColor() & 0x00ffffff)
                        | (Math.max(1, Math.round((paint.getColor() >>> 24) * underline)) << 24);
                paint.underlineThickness = paint.getTextSize() / 16f;
            }
        }
    }

    private void cancelWork() {
        stopScrolling();
        if (loading != null) loading.cancel(true);
        if (searching != null) searching.cancel(true);
        loading = null;
        searching = null;
        if (worker != null) worker.getQueue().clear();
        if (delivery != null) delivery.cancel();
        delivery = null;
        handler.removeCallbacksAndMessages(null);
    }

    private void clearHits() {
        hits = Collections.emptyList();
        currentHit = -1;
        truncated = false;
        pendingSearch = false;
    }

    private static final class Delivery {
        private final Handler handler;
        private final Object searchToken = new Object();
        private boolean cancelled;
        private long searchGeneration;

        Delivery(Handler handler) {
            this.handler = handler;
        }

        synchronized void post(Runnable callback) {
            if (!cancelled) handler.post(callback);
        }

        synchronized void setSearch(long generation) {
            searchGeneration = generation;
            handler.removeCallbacksAndMessages(searchToken);
        }

        synchronized void postSearch(long generation, Runnable callback) {
            if (!cancelled && searchGeneration == generation) {
                handler.postAtTime(callback, searchToken, SystemClock.uptimeMillis());
            }
        }

        synchronized void cancel() {
            cancelled = true;
            handler.removeCallbacksAndMessages(null);
        }
    }

    private static void load(WeakReference<TextDocumentView> owner, Delivery target,
                             long generation, File file, String encoding) {
        try {
            BoundedTextLoader.Result result = BoundedTextLoader.load(file, encoding);
            if (Thread.currentThread().isInterrupted()) return;
            target.post(() -> {
                TextDocumentView view = owner.get();
                if (view == null || view.closed || view.ownerGeneration != generation) return;
                view.loading = null;
                view.document = result;
                view.adapter.notifyDataSetChanged();
                view.layout.scrollToPositionWithOffset(0, 0);
                if (view.listener != null) view.listener.onReady(result.blocks.size());
                if (!view.closed && view.ownerGeneration == generation) view.scheduleSearch();
            });
        } catch (BoundedTextLoader.LoadException e) {
            if (Thread.currentThread().isInterrupted()) return;
            int error = e.code;
            target.post(() -> {
                TextDocumentView view = owner.get();
                if (view == null || view.closed || view.ownerGeneration != generation) return;
                view.loading = null;
                if (view.listener != null) view.listener.onFailure(error);
            });
        } catch (CancellationException ignored) {
        } catch (OutOfMemoryError e) {
            target.post(() -> {
                TextDocumentView view = owner.get();
                if (view != null && !view.closed && view.ownerGeneration == generation && view.listener != null) {
                    view.loading = null;
                    view.listener.onFailure(ERROR_TOO_LARGE);
                }
            });
        }
    }

    private void scheduleSearch() {
        if (closed || document == null || worker == null) return;
        if (searching != null) searching.cancel(true);
        worker.getQueue().clear();
        long generation = ownerGeneration;
        long search = ++searchGeneration;
        long navigation = searchNavigationGeneration;
        delivery.setSearch(search);
        String source = document.text;
        String needle = query;
        WeakReference<TextDocumentView> owner = new WeakReference<>(this);
        Delivery target = delivery;
        searching = worker.submit(() -> search(owner, target, generation, search, navigation, source, needle));
    }

    private static final class Hit {
        final int start;
        final int end;

        Hit(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static int fold(int value) {
        return Character.toLowerCase(Character.toUpperCase(value));
    }

    private static void search(WeakReference<TextDocumentView> owner, Delivery target,
                               long generation, long search, long navigation, String source, String needle) {
        ArrayList<Hit> found = new ArrayList<>();
        boolean limited = false;
        if (!needle.isEmpty() && needle.length() <= source.length()) {
            int[] pattern = new int[needle.codePointCount(0, needle.length())];
            int[] failure = new int[pattern.length];
            for (int i = 0, offset = 0; i < pattern.length; i++) {
                if ((i & 1023) == 0 && Thread.currentThread().isInterrupted()) return;
                int codePoint = needle.codePointAt(offset);
                pattern[i] = fold(codePoint);
                offset += Character.charCount(codePoint);
            }
            for (int i = 1, j = 0; i < pattern.length; i++) {
                if ((i & 1023) == 0 && Thread.currentThread().isInterrupted()) return;
                while (j > 0 && pattern[i] != pattern[j]) j = failure[j - 1];
                if (pattern[i] == pattern[j]) j++;
                failure[i] = j;
            }
            for (int i = 0, j = 0, scanned = 0; i < source.length(); scanned++) {
                if ((scanned & 1023) == 0 && Thread.currentThread().isInterrupted()) return;
                int codePoint = source.codePointAt(i);
                int c = fold(codePoint);
                i += Character.charCount(codePoint);
                while (j > 0 && c != pattern[j]) j = failure[j - 1];
                if (c == pattern[j]) j++;
                if (j == pattern.length) {
                    if (found.size() == MAX_MATCHES) {
                        limited = true;
                        break;
                    }
                    found.add(new Hit(source.offsetByCodePoints(i, -j), i));
                    j = 0;
                }
            }
        }
        if (Thread.currentThread().isInterrupted()) return;
        boolean wasTruncated = limited;
        List<Hit> result = Collections.unmodifiableList(found);
        target.postSearch(search, () -> {
            TextDocumentView view = owner.get();
            if (view == null || view.closed || view.ownerGeneration != generation || view.searchGeneration != search) return;
            view.searching = null;
            view.hits = result;
            view.truncated = wasTruncated;
            view.currentHit = result.isEmpty() ? -1 : 0;
            view.refreshHighlights();
            if (view.navigationGeneration == navigation) view.scrollToCurrent();
            view.notifySearch();
        });
    }

    private void notifySearch() {
        if (!closed && listener != null) listener.onSearchChanged(currentHit, hits.size(), truncated);
    }

    private void scrollToCurrent() {
        if (document == null || currentHit < 0 || stopping) return;
        if (safeHeight() <= 0 || viewport.getWidth() <= 0 || touchActive || zoomAnimator != null
                || list.isComputingLayout() || list.isLayoutRequested()) {
            pendingSearch = true;
            return;
        }
        int offset = hits.get(currentHit).start;
        int low = 0, high = document.blocks.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (document.blocks.get(middle).start <= offset) low = middle;
            else high = middle - 1;
        }
        int inBlock = offset - document.blocks.get(low).start;
        View child = layout.findViewByPosition(low);
        if (child instanceof TextView && ((TextView) child).getLayout() != null) {
            TextView text = (TextView) child;
            android.text.Layout textLayout = text.getLayout();
            int start = Math.min(inBlock, text.length());
            int line = textLayout.getLineForOffset(start);
            float top = topInset + (text.getTop() + text.getPaddingTop() + textLayout.getLineTop(line)
                    - text.getScrollY() - nativeTop() - verticalOffset) * zoomScale;
            float bottom = topInset + (text.getTop() + text.getPaddingTop() + textLayout.getLineBottom(line)
                    - text.getScrollY() - nativeTop() - verticalOffset) * zoomScale;
            int end = Math.min(hits.get(currentHit).end - document.blocks.get(low).start,
                    textLayout.getLineEnd(line));
            float x1 = text.getPaddingLeft() - text.getScrollX() + textLayout.getPrimaryHorizontal(start);
            float x2 = text.getPaddingLeft() - text.getScrollX() + textLayout.getPrimaryHorizontal(Math.max(start, end));
            float left = Math.min(x1, x2) * zoomScale;
            float right = Math.max(x1, x2) * zoomScale;
            if (left + horizontalOffset < 0 || right - left >= viewport.getWidth()) horizontalOffset = -left;
            else if (right + horizontalOffset > viewport.getWidth()) horizontalOffset = viewport.getWidth() - right;
            applyViewportTransform();
            float delta = top < topInset ? top - topInset : Math.max(0, bottom - (viewport.getHeight() - bottomInset));
            if (Math.abs(delta) > 2f * safeHeight()) scrollNative(delta / zoomScale);
            else if (delta != 0) animateSearchScroll(delta / zoomScale);
            return;
        }
        TextView probe = (TextView) adapter.onCreateViewHolder(list, 0).itemView;
        probe.setLayoutDirection(list.getLayoutDirection());
        probe.setTextDirection(list.getTextDirection());
        applyTextStyle(probe);
        probe.setText(new SpannableString(document.blocks.get(low).text), TextView.BufferType.SPANNABLE);
        probe.measure(MeasureSpec.makeMeasureSpec(list.getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        android.text.Layout target = probe.getLayout();
        if (target == null) { pendingSearch = true; return; }
        int line = target.getLineForOffset(Math.min(inBlock, probe.length()));
        verticalOffset = 0;
        layout.scrollToPositionWithOffset(low, -AndroidUtilities.dp(12) - probe.getPaddingTop() - target.getLineTop(line));
        pendingSearch = true;
    }

    private void removeSearchTask() {
        if (searchTask != null) list.removeCallbacks(searchTask);
        searchTask = null;
    }

    private void resumePendingSearch() {
        if (closed || stopping || !pendingSearch || searchTask != null || !isAttachedToWindow()
                || !isShown() || getWindowVisibility() != VISIBLE) return;
        long owner = ownerGeneration, search = searchGeneration, navigation = navigationGeneration;
        searchTask = () -> {
            searchTask = null;
            if (!closed && !stopping && owner == ownerGeneration && search == searchGeneration
                    && navigation == navigationGeneration && pendingSearch && isAttachedToWindow()
                    && isShown() && getWindowVisibility() == VISIBLE && !touchActive && zoomAnimator == null
                    && !list.isComputingLayout() && !list.isLayoutRequested() && safeHeight() > 0) {
                pendingSearch = false;
                scrollToCurrent();
            }
        };
        list.postOnAnimation(searchTask);
    }

    public void stopScrolling() {
        requireUiThread();
        cancelTextSelection();
        stopping = true;
        pendingSearch = false;
        removeSearchTask();
        cancelZoom();
        stopSearchScroll();
        finishHighlightAnimation();
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try { viewport.dispatchTouchEvent(cancel); }
        finally { cancel.recycle(); }
        touchActive = false;
        list.stopScroll();
        stopping = false;
    }

    private void stopSearchScroll() {
        ++navigationGeneration;
        removeSearchTask();
        if (searchAnimator != null) {
            ValueAnimator previous = searchAnimator;
            searchAnimator = null;
            previous.cancel();
        }
    }

    private void animateSearchScroll(float distance) {
        if (distance == 0) return;
        list.stopScroll();
        if (!animationsEnabled()) { scrollNative(distance); return; }
        float[] previous = {0};
        ValueAnimator animator = ValueAnimator.ofFloat(0, distance);
        searchAnimator = animator;
        animator.setDuration(Math.max(220L, (long) Math.ceil(Math.abs(distance) * zoomScale * 64d / Math.max(1, safeHeight()))));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            if (searchAnimator != animation) return;
            float value = (float) animation.getAnimatedValue();
            scrollNative(value - previous[0]);
            previous[0] = value;
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (searchAnimator == animation) searchAnimator = null;
            }
        });
        animator.start();
    }

    private void refreshHighlights() {
        if (highlightAnimator != null) {
            ValueAnimator previous = highlightAnimator;
            highlightAnimator = null;
            previous.cancel();
        }
        boolean animate = !closed && isAttachedToWindow() && isShown() && SharedConfig.animationsEnabled()
                && (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof TextView) applyHighlights((TextView) child, list.getChildAdapterPosition(child), animate);
        }
        highlightProgress = animate ? 0f : 1f;
        if (!animate || list.getChildCount() == 0) return;
        highlightAnimator = ValueAnimator.ofFloat(0f, 1f);
        highlightAnimator.setDuration(160);
        highlightAnimator.setInterpolator(new DecelerateInterpolator());
        highlightAnimator.addUpdateListener(animation -> {
            highlightProgress = (float) animation.getAnimatedValue();
            if (!SharedConfig.animationsEnabled()
                    || android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
                finishHighlightAnimation();
                return;
            }
            for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).invalidate();
        });
        highlightAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (highlightAnimator == animation) finishHighlightAnimation();
            }
        });
        highlightAnimator.start();
    }

    private void finishHighlightAnimation() {
        if (highlightAnimator != null) {
            ValueAnimator previous = highlightAnimator;
            highlightAnimator = null;
            previous.cancel();
        }
        highlightProgress = 1f;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof TextView) applyHighlights((TextView) child, list.getChildAdapterPosition(child), false);
        }
    }

    private void applyHighlights(TextView view, int position) {
        applyHighlights(view, position, false);
    }

    private void applyHighlights(TextView view, int position, boolean retain) {
        if (document == null || position < 0 || position >= document.blocks.size()) return;
        BoundedTextLoader.Block block = document.blocks.get(position);
        Spannable value = textSpans(view);
        SearchHighlight[] old = value.getSpans(0, value.length(), SearchHighlight.class);
        int blockEnd = block.start + block.text.length();
        int first = 0, high = hits.size();
        while (first < high) {
            int middle = (first + high) >>> 1;
            if (hits.get(middle).end <= block.start) first = middle + 1;
            else high = middle;
        }
        if (old.length == 0 && (first == hits.size() || hits.get(first).start >= blockEnd)) return;
        float[] previous = retain ? new float[value.length()] : null;
        float[] underline = retain ? new float[value.length()] : null;
        byte[] target = new byte[value.length()];
        for (SearchHighlight span : old) {
            if (retain) {
                int start = value.getSpanStart(span), end = value.getSpanEnd(span);
                Arrays.fill(previous, start, end, span.level());
                Arrays.fill(underline, start, end, span.underline());
            }
            value.removeSpan(span);
        }
        for (int i = first; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            if (hit.start >= blockEnd) break;
            int startInBlock = Math.max(0, hit.start - block.start);
            int endInBlock = Math.min(block.text.length(), hit.end - block.start);
            Arrays.fill(target, startInBlock, endInBlock, (byte) (i == currentHit ? 3 : 1));
        }
        for (int start = 0; start < target.length;) {
            int next = start + 1;
            while (next < target.length && target[next] == target[start]
                    && (!retain || previous[next] == previous[start] && underline[next] == underline[start])) next++;
            float from = retain ? previous[start] : highlightAnimator != null ? 0 : target[start];
            float underlineTo = target[start] == 3 ? 1 : 0;
            float underlineFrom = retain ? underline[start] : highlightAnimator != null ? 0 : underlineTo;
            if (from != 0 || target[start] != 0) {
                value.setSpan(new SearchHighlight(from, target[start], underlineFrom, underlineTo), start, next,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = next;
        }
        view.invalidate();
    }

    private void cancelTextSelection() {
        if (selection == null) return;
        selection.cancel();
        for (TextView view : new ArrayList<>(boundViews)) {
            if (view instanceof TextDocumentSelection.BlockTextView) {
                selection.beforeRebind((TextDocumentSelection.BlockTextView) view);
            }
        }
    }

    private void bind(TextView view, int position) {
        TextDocumentSelection.BlockTextView row = (TextDocumentSelection.BlockTextView) view;
        selection.beforeRebind(row);
        row.documentOwner = -1;
        if (document == null || position < 0 || position >= document.blocks.size()) return;
        row.documentOwner = ownerGeneration;
        view.clearFocus();
        setSelectionEnabled(view, allowCopy && view.isAttachedToWindow());
        applyTextColors(view);
        applyTextStyle(view);
        view.setText(new SpannableString(document.blocks.get(position).text), TextView.BufferType.SPANNABLE);
        applyHighlights(view, position);
    }

    private void setSelectionEnabled(TextView view, boolean enabled) {
        if (!enabled) {
            view.clearFocus();
            if (view.getText() instanceof Spannable) android.text.Selection.removeSelection((Spannable) view.getText());
        }
        view.setTextIsSelectable(enabled);
        Spannable text = textSpans(view);
        if (!enabled) android.text.Selection.removeSelection(text);
        view.setLongClickable(enabled);
    }

    private Spannable textSpans(TextView view) {
        if (!(view.getText() instanceof Spannable)) view.setText(view.getText(), TextView.BufferType.SPANNABLE);
        return (Spannable) view.getText();
    }

    private final class BlocksAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return document == null ? 0 : document.blocks.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView view = new TextDocumentSelection.BlockTextView(parent.getContext(), selection) {
                @Override protected void onAttachedToWindow() {
                    super.onAttachedToWindow();
                    setSelectionEnabled(this, allowCopy);
                }

                @Override public boolean onTextContextMenuItem(int id) {
                    return !allowCopy || super.onTextContextMenuItem(id);
                }

                @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
                    if (!allowCopy && (action == AccessibilityNodeInfo.ACTION_SET_SELECTION
                            || action == AccessibilityNodeInfo.ACTION_COPY || action == AccessibilityNodeInfo.ACTION_CUT
                            || arguments != null && arguments.getBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN)
                            && (action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                            || action == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY))) return false;
                    return super.performAccessibilityAction(action, arguments);
                }

                @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(info);
                    if (!allowCopy) {
                        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION);
                        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY);
                        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT);
                    }
                }
            };
            view.setLayoutParams(new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            view.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
            view.setLineSpacing(AndroidUtilities.dp(3), 1.1f);
            view.setIncludeFontPadding(false);
            view.setPaintFlags(view.getPaintFlags() | android.graphics.Paint.LINEAR_TEXT_FLAG
                    | android.graphics.Paint.SUBPIXEL_TEXT_FLAG);
            view.setAutoLinkMask(0);
            view.setLinksClickable(false);
            view.setSaveEnabled(false);
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextView view = (TextView) holder.itemView;
            boundViews.add(view);
            bind(view, position);
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            super.onViewAttachedToWindow(holder);
            applyTextColors((TextView) holder.itemView);
            applyTextStyle((TextView) holder.itemView);
            applyHighlights((TextView) holder.itemView, holder.getAdapterPosition());
        }

        @Override
        public void onViewRecycled(RecyclerView.ViewHolder holder) {
            TextView view = (TextView) holder.itemView;
            selection.beforeRebind((TextDocumentSelection.BlockTextView) view);
            boundViews.remove(view);
            view.clearFocus();
            setSelectionEnabled(view, false);
            view.setText(null);
            super.onViewRecycled(holder);
        }
    }

    private static void requireUiThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException();
    }
}
