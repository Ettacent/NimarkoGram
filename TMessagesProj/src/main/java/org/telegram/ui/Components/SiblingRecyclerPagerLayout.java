/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class SiblingRecyclerPagerLayout extends FrameLayout implements NestedScrollingParent3 {
    private static final String DEBUG_TAG = "SiblingScroll";
    private static final long DEBUG_SAMPLE_INTERVAL_MS = 250;

    private boolean debugLoggingEnabled;
    private int debugTraceFrames;
    private int debugDrawSequence;
    private long debugLastScrollLogTime;
    private long debugLastBlockedLogTime;
    private long debugTransitionStartTime;
    private long debugProgressChangeTime;
    private int debugGestureId;
    private int debugTransitionId;

    public void setDebugLoggingEnabled(boolean enabled) {
        debugLoggingEnabled = enabled;
        if (enabled) {
            debugLastScrollLogTime = 0;
            debugLastBlockedLogTime = 0;
            debugTransitionStartTime = SystemClock.uptimeMillis();
            debugProgressChangeTime = debugTransitionStartTime;
            debugEvent("DEBUG_ENABLED", null, 0, 0, true);
        }
    }

    public void logDebugState() {
        debugEvent("SNAPSHOT", null, 0, 0, true);
    }

    public void traceNextDraws(String reason, int frames) {
        if (!debugLoggingEnabled) {
            return;
        }
        debugTraceFrames = Math.max(debugTraceFrames, Math.min(60, Math.max(0, frames)));
        debugEvent(reason, null, 0, 0, true);
    }

    private String debugBoundary() {
        int position = commonContent == null ? RecyclerView.NO_POSITION : commonContent.boundaryPosition();
        RecyclerView.LayoutManager manager = commonView == null ? null : commonView.getLayoutManager();
        View anchor = manager == null || position == RecyclerView.NO_POSITION
                ? null : manager.findViewByPosition(position);
        return " row=" + position + " anchor=" + debugView(anchor)
                + " anchorTop=" + (anchor == null ? 0 : anchor.getTop())
                + " anchorHeight=" + (anchor == null ? 0 : anchor.getHeight())
                + " decoratedTop=" + (anchor == null ? 0 : manager.getDecoratedTop(anchor))
                + " adapterPosition=" + (anchor == null ? RecyclerView.NO_POSITION
                        : commonView.getChildAdapterPosition(anchor))
                + " pageOffset=" + (activeView == null || pages == null ? 0f : pages.scrollOffset(activeView))
                + " tabsTop=" + tabsTop + " draw=" + debugDrawSequence;
    }

    private void debugEvent(String event, View target, int requestedDy, int consumedDy, boolean immediate) {
        if (!debugLoggingEnabled) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!immediate) {
            if (now - debugLastScrollLogTime < DEBUG_SAMPLE_INTERVAL_MS) {
                return;
            }
            debugLastScrollLogTime = now;
        }
        View currentPage = pager == null ? null : pager.getViewPages()[0];
        View otherPage = pager == null ? null : pager.getViewPages()[1];
        Log.d(DEBUG_TAG, "root=" + Integer.toHexString(System.identityHashCode(this))
                + " gesture=" + debugGestureId + " transition=" + debugTransitionId
                + " event=" + event + " target=" + debugView(target)
                + " dy=" + requestedDy + " used=" + consumedDy
                + " transitioning=" + transitioning + " progress=" + transition.getProgress()
                + " ageMs=" + (transitioning ? now - debugTransitionStartTime : 0)
                + " unchangedMs=" + (transitioning ? now - debugProgressChangeTime : 0)
                + " depth=" + programmaticDepth + " updating=" + updating
                + " callback=" + debugView(callbackSource)
                + " common=" + debugView(commonView) + " active=" + debugView(activeView)
                + " current=" + debugView(currentPage) + " other=" + debugView(otherPage)
                + " source=" + debugView(sourcePage) + " incoming=" + debugView(incomingPage)
                + " boundary=" + nativeBoundaryTop() + " pin=" + pinTop + " tail=" + commonTail
                + " alignPending=" + alignBoundaryPending + debugBoundary());
    }

    private static String debugView(View view) {
        if (view == null) {
            return "null";
        }
        String state = view.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(view))
                + "(x=" + view.getTranslationX() + ",y=" + view.getTranslationY()
                + ",w=" + view.getWidth() + ",visibility=" + view.getVisibility()
                + ",attached=" + view.isAttachedToWindow()
                + ",top=" + view.getTop() + ",height=" + view.getHeight()
                + ",layoutRequested=" + view.isLayoutRequested();
        if (view instanceof RecyclerView) {
            RecyclerView list = (RecyclerView) view;
            state += ",scrollState=" + list.getScrollState()
                    + ",up=" + list.canScrollVertically(-1) + ",down=" + list.canScrollVertically(1)
                    + ",layout=" + list.isComputingLayout()
                    + ",pendingUpdates=" + list.hasPendingAdapterUpdates()
                    + ",animating=" + list.isAnimating()
                    + ",paddingTop=" + list.getPaddingTop()
                    + ",count=" + (list.getAdapter() == null ? 0 : list.getAdapter().getItemCount());
        }
        return state + ")";
    }

    private void debugBlockedScroll(String event, View target, int dy, int type) {
        if (!debugLoggingEnabled) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - debugLastBlockedLogTime < DEBUG_SAMPLE_INTERVAL_MS) {
            return;
        }
        debugLastBlockedLogTime = now;
        debugEvent(event + (type == ViewCompat.TYPE_TOUCH ? "_TOUCH" : "_NON_TOUCH"), target, dy, dy, true);
    }

    void debugPagerEvent(String event) {
        debugEvent(event, pager, 0, 0, true);
    }
    public interface ScrollCapture {
        void capture(Canvas canvas, RectF bounds, RecyclerView list);
    }
    public interface Geometry {
        float tabsPinnedTop(SiblingRecyclerPagerLayout parent);
        default float commonHiddenTop(SiblingRecyclerPagerLayout parent) {
            return 0f;
        }
        default int commonTopInset(SiblingRecyclerPagerLayout parent) {
            return 0;
        }
        default int pageBottomInset(SiblingRecyclerPagerLayout parent) {
            return 0;
        }
        default void onTabsPinnedChanged(boolean pinned) {}
        default void onTabsPositionChanged(float top) {}
    }

    public interface CommonContent {
        int boundaryPosition();
    }

    public interface Pages {
        RecyclerView recyclerView(View page);
        default float scrollOffset(RecyclerView view) {
            return getStartOffset(view);
        }
        default void resetToStart(RecyclerView view) {
            view.stopScroll();
            RecyclerView.LayoutManager manager = view.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(0, 0);
            } else {
                view.scrollToPosition(0);
            }
        }
    }

    private final RectF commonCaptureBounds = new RectF();
    private final CommonLayer commonLayer;
    private final NestedScrollingParentHelper nestedHelper = new NestedScrollingParentHelper(this);
    private final int[] legacyConsumed = new int[2];
    private final RecyclerPagerModel.Transition transition = new RecyclerPagerModel.Transition();
    private RecyclerView commonView;
    private RecyclerView activeView;
    private RecyclerView capturedView;
    private ViewPagerFixed pager;
    private View pagerContainer;
    private View tabs;
    private View sourcePage;
    private View incomingPage;
    private Geometry geometry;
    private CommonContent commonContent;
    private Pages pages;
    private View callbackSource;
    private int programmaticDepth;
    private int capturedDy;
    private int topBleed;
    private int bottomBleed;
    private float pinTop;
    private float hiddenTop;
    private float boundaryTop;
    private float commonTail;
    private float tabsTop;
    private boolean updating;
    private boolean transitioning;
    private boolean tabsPinned;
    private boolean metricsInitialized;
    private boolean alignBoundaryPending;
    private boolean commonInsetsManagedExternally;

    private final RecyclerPagerModel.ScrollHost scrollHost = new RecyclerPagerModel.ScrollHost() {
        @Override
        public float distanceToTabs() {
            float top = nativeBoundaryTop();
            return Float.isInfinite(top) ? top : Math.max(0f, top - pinTop);
        }
        @Override
        public int scrollCommon(int dy) {
            return scrollOther(commonView, dy);
        }
        @Override
        public int scrollPage(int dy) {
            return commonContent == null || commonContent.boundaryPosition() == RecyclerView.NO_POSITION
                    ? 0 : scrollOther(activeView, dy);
        }
    };

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
            if (view == capturedView) {
                capturedDy += dy;
            }
            if (callbackSource == null && programmaticDepth == 0) {
                updateGeometry();
            }
        }
    };

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
        if (debugLoggingEnabled && debugTraceFrames > 0) {
            debugEvent("PRE_DRAW_BEFORE", null, 0, 0, true);
        }
        onPagerChanged();
        if (alignBoundaryPending && commonView != null && !commonView.isComputingLayout()) {
            float top = nativeBoundaryTop();
            if (!Float.isInfinite(top)) {
                alignBoundaryPending = false;
                scrollOther(commonView, Math.round(top - pinTop));
                updateGeometry();
            }
        }
        if (debugLoggingEnabled && debugTraceFrames > 0) {
            debugEvent("PRE_DRAW_AFTER", null, 0, 0, true);
            debugTraceFrames--;
            debugDrawSequence++;
        }
        return true;
    };

    public SiblingRecyclerPagerLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        commonLayer = new CommonLayer(context);
        addView(commonLayer, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setGeometry(@NonNull Geometry value) {
        geometry = value;
        requestLayout();
        updateGeometry();
    }

    public void setCommonInsetsManagedExternally(boolean value) {
        if (commonInsetsManagedExternally == value) {
            return;
        }
        commonInsetsManagedExternally = value;
        updateGeometry();
        requestLayout();
    }

    public void setVerticalBleed(int top, int bottom) {
        topBleed = Math.max(0, top);
        bottomBleed = Math.max(0, bottom);
        requestLayout();
        updateGeometry();
    }

    public int getTopBleed() {
        return topBleed;
    }
    public int getBottomBleed() {
        return bottomBleed;
    }
    public boolean areTabsPinned() {
        return tabsPinned;
    }
    public float getTabsTop() {
        return tabsTop;
    }

    public void invalidateGeometry() {
        updateGeometry();
        requestLayout();
        invalidate();
    }

    public void alignCommonBoundaryToPin() {
        debugEvent("ALIGN_BOUNDARY", commonView, 0, 0, true);
        if (commonView == null || commonContent == null || geometry == null) {
            return;
        }
        int position = commonContent.boundaryPosition();
        RecyclerView.LayoutManager manager = commonView.getLayoutManager();
        if (position == RecyclerView.NO_POSITION || !(manager instanceof LinearLayoutManager)) {
            return;
        }
        updateMetrics();
        ((LinearLayoutManager) manager).scrollToPositionWithOffset(position,
                Math.round(pinTop) - commonView.getTop() - commonView.getPaddingTop());
        alignBoundaryPending = true;
        requestLayout();
    }

    public void setCommonRecyclerView(@NonNull RecyclerView view, @NonNull CommonContent content) {
        if (commonView != null) {
            commonView.stopScroll();
            commonView.removeOnScrollListener(scrollListener);
            commonLayer.removeView(commonView);
        }
        commonView = view;
        commonContent = content;
        commonLayer.addView(view, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        view.addOnScrollListener(scrollListener);
        view.setNestedScrollingEnabled(true);
        updateGeometry();
    }

    public void setPager(@NonNull ViewPagerFixed value, @NonNull Pages pageProvider) {
        setPager(value, value, pageProvider);
    }

    public void setPager(@NonNull View container, @NonNull ViewPagerFixed value, @NonNull Pages pageProvider) {
        if (pagerContainer != null) {
            removeView(pagerContainer);
        }
        pagerContainer = container;
        pager = value;
        pages = pageProvider;
        addView(container, 0, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        updateGeometry();
    }

    public void captureScrollingContent(Canvas canvas, RectF bounds, ScrollCapture capture) {
        if (pager != null && pages != null && pagerContainer.getVisibility() == VISIBLE
                && pager.getVisibility() == VISIBLE) {
            View[] visible = pager.getViewPages();
            for (int i = 0; i < visible.length; i++) {
                View page = visible[i];
                if (page == null || page.getParent() != pager || page.getVisibility() != VISIBLE) {
                    continue;
                }
                capture.capture(canvas, bounds, pages.recyclerView(page));
            }
        }
        if (commonView != null && commonView.getVisibility() == VISIBLE) {
            float x = transitioning ? transition.commonTranslationX(incomingPage.getX()) : 0f;
            if (x == 0f) {
                capture.capture(canvas, bounds, commonView);
            } else {
                commonCaptureBounds.set(bounds);
                commonCaptureBounds.offset(-x, 0f);
                int saveCount = canvas.save();
                try {
                    canvas.translate(x, 0f);
                    capture.capture(canvas, commonCaptureBounds, commonView);
                } finally {
                    canvas.restoreToCount(saveCount);
                }
            }
        }
    }

    public void setTabs(@NonNull View value, int height) {
        if (tabs != null) {
            removeView(tabs);
        }
        tabs = value;
        addView(value, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height, Gravity.TOP));
        updateGeometry();
    }

    public View createBoundarySpacer(Context context) {
        return new View(context) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                setMeasuredDimension(MeasureSpec.getSize(widthSpec), SiblingRecyclerPagerLayout.this.getMeasuredHeight());
            }
        };
    }

    public LinearLayoutManager createCommonLayoutManager(Context context) {
        return new LinearLayoutManager(context, RecyclerView.VERTICAL, false) {
            @Override
            public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
                if (dy <= 0 || commonContent == null) {
                    return super.scrollVerticallyBy(dy, recycler, state);
                }
                int totalConsumed = 0;
                while (totalConsumed < dy) {
                    int stepLimit = Math.max(1, Math.round((SiblingRecyclerPagerLayout.this.getHeight() - pinTop) * 0.5f));
                    int requestedStep = Math.min(dy - totalConsumed, stepLimit);
                    float boundaryY = nativeBoundaryTop();
                    if (!Float.isInfinite(boundaryY)) {
                        requestedStep = Math.min(requestedStep, Math.max(0, Math.round(boundaryY - pinTop)));
                    }
                    if (requestedStep == 0) {
                        break;
                    }
                    int consumedStep = super.scrollVerticallyBy(requestedStep, recycler, state);
                    totalConsumed += consumedStep;
                    if (consumedStep < requestedStep) {
                        break;
                    }
                }
                return totalConsumed;
            }
        };
    }

    public void onPageBound(@NonNull View page) {
        debugEvent("PAGE_BOUND", page, 0, 0, true);
        if (pages == null) {
            return;
        }
        if (transitioning) {
            finishTransition("page_bound");
        }
        RecyclerView view = pages.recyclerView(page);
        applyPageInsets(view);
        pages.resetToStart(view);
        onPagerChanged();
    }

    public boolean canStartPageSwipe() {
        return pager != null && pager.getCurrentView() != null
                && !Float.isInfinite(nativeBoundaryTop()) && tabsTop < getHeight();
    }

    public boolean isCommonContentAt(float x, float y) {
        float left = transitioning ? transition.commonTranslationX(incomingPage.getX()) : 0f;
        return y >= 0f && y < commonTail && x >= Math.max(0f, left)
                && x < Math.min(getWidth(), left + getWidth());
    }

    public void onPagerChanged() {
        if (updating || pager == null || pages == null || geometry == null) {
            return;
        }
        View[] visiblePages = pager.getViewPages();
        View currentPage = visiblePages[0];
        View otherPage = visiblePages[1];
        boolean dragAborted = pager instanceof RecyclerPagerView
                && ((RecyclerPagerView) pager).isCancelledPagePair(currentPage, otherPage);
        if (transitioning && dragAborted) {
            finishTransition("drag_aborted");
        }
        if (transitioning && (otherPage == null
                || (currentPage != sourcePage && currentPage != incomingPage)
                || (otherPage != sourcePage && otherPage != incomingPage))) {
            finishTransition("page_slots_changed");
        }
        resolveActive();
        if (!transitioning && !dragAborted && currentPage != null && otherPage != null) {
            float nativeTop = nativeBoundaryTop();
            if (!Float.isInfinite(nativeTop)) {
                updateMetrics();
                stopScrolling();
                sourcePage = currentPage;
                incomingPage = otherPage;
                float top = normalizedBoundary(nativeTop);
                transition.begin(top, pages.scrollOffset(pages.recyclerView(currentPage)), pinTop, hiddenTop);
                transitioning = true;
                if (debugLoggingEnabled) {
                    debugTransitionId++;
                    debugTransitionStartTime = SystemClock.uptimeMillis();
                    debugProgressChangeTime = debugTransitionStartTime;
                    debugLastBlockedLogTime = 0;
                    debugEvent("TRANSITION_BEGIN", currentPage, 0, 0, true);
                }
                commonLayer.invalidate();
            }
        }
        if (transitioning) {
            float width = Math.max(1, sourcePage.getWidth());
            float previousProgress = transition.getProgress();
            transition.setProgress(Math.abs(sourcePage.getTranslationX()) / width);
            if (previousProgress != transition.getProgress()) {
                commonLayer.invalidate();
                if (debugLoggingEnabled) {
                    debugProgressChangeTime = SystemClock.uptimeMillis();
                }
            }
        }
        updateGeometry();
    }

    private void finishTransition(String reason) {
        if (debugLoggingEnabled) {
            debugEvent("TRANSITION_END_" + reason, null, 0, 0, true);
        }
        transitioning = false;
        sourcePage = incomingPage = null;
        commonLayer.invalidate();
    }

    private void updateMetrics() {
        if (geometry == null) {
            return;
        }
        float nextPin = geometry.tabsPinnedTop(this);
        float nextHidden = geometry.commonHiddenTop(this);
        if (Float.isNaN(nextPin) || Float.isInfinite(nextPin) || Float.isNaN(nextHidden)
                || Float.isInfinite(nextHidden) || nextPin < nextHidden || nextHidden < 0f) {
            throw new IllegalArgumentException("Require finite 0 <= commonHiddenTop <= tabsPinnedTop");
        }
        if (metricsInitialized && (nextPin != pinTop || nextHidden != hiddenTop)) {
            float top = nativeBoundaryTop();
            if (!Float.isInfinite(top)) {
                boolean atPin = top <= pinTop + 0.5f || top < nextPin;
                if (atPin && nextPin != pinTop) {
                    alignBoundaryPending = true;
                }
                if (transitioning) {
                    float progress = transition.getProgress();
                    transition.begin(atPin ? nextPin : top, pages.scrollOffset(pages.recyclerView(sourcePage)),
                            nextPin, nextHidden);
                    transition.setProgress(progress);
                    commonLayer.invalidate();
                }
            }
        }
        pinTop = nextPin;
        hiddenTop = nextHidden;
        metricsInitialized = true;
    }

    private void resolveActive() {
        RecyclerView next = pager == null || pages == null || pager.getCurrentView() == null
                ? null : pages.recyclerView(pager.getCurrentView());
        if (next == activeView) {
            return;
        }
        debugEvent("ACTIVE_CHANGE", next, 0, 0, true);
        if (activeView != null) {
            activeView.stopScroll();
            activeView.removeOnScrollListener(scrollListener);
        }
        activeView = next;
        if (next != null) {
            next.addOnScrollListener(scrollListener);
        }
    }

    private float nativeBoundaryTop() {
        if (commonView == null || commonContent == null || commonView.getLayoutManager() == null) {
            return Float.POSITIVE_INFINITY;
        }
        RecyclerView.LayoutManager manager = commonView.getLayoutManager();
        int position = commonContent.boundaryPosition();
        if (position == RecyclerView.NO_POSITION) {
            return Float.POSITIVE_INFINITY;
        }
        View anchor = manager.findViewByPosition(position);
        if (anchor == null) {
            return Float.POSITIVE_INFINITY;
        }
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) anchor.getLayoutParams();
        return commonView.getTop() + manager.getDecoratedTop(anchor) - params.topMargin;
    }

    private float normalizedBoundary(float top) {
        return top <= pinTop + 0.5f ? pinTop : top;
    }

    private void updateGeometry() {
        if (updating || geometry == null) {
            return;
        }
        updating = true;
        try {
            float previousTail = commonTail;
            updateMetrics();
            if (commonView != null && !commonInsetsManagedExternally) {
                setBleed(commonView);
                setPaddingIfChanged(commonView, topBleed + geometry.commonTopInset(this), bottomBleed);
            }
            if (pager == null || pages == null || tabs == null) {
                return;
            }
            View[] visiblePages = pager.getViewPages();
            for (int i = 0; i < visiblePages.length; i++) {
                View page = visiblePages[i];
                if (page != null && page.getParent() == pager) {
                    applyPageInsets(pages.recyclerView(page));
                }
            }
            resolveActive();
            float nativeTop = nativeBoundaryTop();
            if (Float.isInfinite(nativeTop)) {
                commonTail = Float.POSITIVE_INFINITY;
                pagerContainer.setVisibility(INVISIBLE);
                tabs.setVisibility(INVISIBLE);
                if (commonView != null) {
                    commonView.setTranslationY(0f);
                }
                setTabsPinned(false);
                if (previousTail != commonTail) {
                    commonLayer.invalidate();
                }
                return;
            }
            boundaryTop = normalizedBoundary(nativeTop);
            pagerContainer.setVisibility(VISIBLE);
            tabs.setVisibility(VISIBLE);
            if (transitioning) {
                commonTail = transition.commonTail();
                tabsTop = transition.tabsTop();
                setPageGeometry(sourcePage, transition.outgoingPageTranslation());
                setPageGeometry(incomingPage, transition.incomingPageTranslation());
            } else {
                float offset = activeView == null ? 0f : pages.scrollOffset(activeView);
                commonTail = boundaryTop - RecyclerPagerModel.collapse(offset, pinTop, hiddenTop);
                tabsTop = Math.max(pinTop, commonTail);
                setPageGeometry(pager.getCurrentView(), RecyclerPagerModel.pageTranslation(boundaryTop, pinTop));
            }
            commonView.setTranslationY(commonTail - nativeTop);
            tabs.setTranslationY(tabsTop - tabs.getTop());
            setTabsPinned(tabsTop <= pinTop + 0.5f);
            geometry.onTabsPositionChanged(tabsTop);
            if (previousTail != commonTail) {
                commonLayer.invalidate();
            }
        } finally {
            updating = false;
        }
    }

    private void setTabsPinned(boolean value) {
        if (tabsPinned == value) {
            return;
        }
        tabsPinned = value;
        debugEvent(value ? "TABS_PINNED" : "TABS_UNPINNED", commonView, 0, 0, true);
        geometry.onTabsPinnedChanged(value);
    }

    private void setPageGeometry(View page, float translation) {
        if (page == null) {
            return;
        }
        RecyclerView view = pages.recyclerView(page);
        view.setTranslationY(translation + pinTop - Math.round(pinTop));
    }

    private void applyPageInsets(RecyclerView view) {
        if (geometry == null || view == null) {
            return;
        }
        updateMetrics();
        int tabsHeight = tabs == null ? 0 : (tabs.getHeight() > 0 ? tabs.getHeight() : tabs.getLayoutParams().height);
        setBleed(view);
        setPaddingIfChanged(view, topBleed + Math.round(pinTop) + Math.max(0, tabsHeight),
                bottomBleed + geometry.pageBottomInset(this));
        if (!view.isNestedScrollingEnabled()) {
            view.setNestedScrollingEnabled(true);
        }
    }

    private void setBleed(RecyclerView view) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
        if (params.topMargin != -topBleed || params.bottomMargin != -bottomBleed) {
            params.topMargin = -topBleed;
            params.bottomMargin = -bottomBleed;
            view.setLayoutParams(params);
        }
    }

    private static void setPaddingIfChanged(RecyclerView view, int top, int bottom) {
        if (view.getPaddingTop() == top && view.getPaddingBottom() == bottom) {
            return;
        }
        RecyclerView.LayoutManager manager = view.getLayoutManager();
        View anchor = manager == null || manager.getChildCount() == 0 ? null : manager.getChildAt(0);
        int position = anchor == null ? RecyclerView.NO_POSITION : manager.getPosition(anchor);
        int offset = anchor == null ? 0 : manager.getDecoratedTop(anchor) - view.getPaddingTop()
                - ((RecyclerView.LayoutParams) anchor.getLayoutParams()).topMargin;
        view.setPadding(view.getPaddingLeft(), top, view.getPaddingRight(), bottom);
        if (position != RecyclerView.NO_POSITION && manager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, offset);
        }
    }

    public static float getStartOffset(RecyclerView view) {
        RecyclerView.LayoutManager manager = view.getLayoutManager();
        if (manager == null || !view.canScrollVertically(-1)) {
            return 0f;
        }
        View first = manager.findViewByPosition(0);
        if (first == null) {
            return Float.POSITIVE_INFINITY;
        }
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) first.getLayoutParams();
        return Math.max(0f, view.getPaddingTop() - manager.getDecoratedTop(first) + params.topMargin);
    }

    private final class CommonLayer extends FrameLayout {
        CommonLayer(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (transitioning || event.getY() >= commonTail)) return false;
            return super.dispatchTouchEvent(event);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            float x = transitioning ? transition.commonTranslationX(incomingPage.getX()) : 0f;
            int save = canvas.save();
            canvas.clipRect(Math.max(0f, x), -topBleed, Math.min(getWidth(), x + getWidth()),
                    Math.min(getHeight() + bottomBleed, commonTail));
            canvas.translate(x, 0f);
            boolean result = super.drawChild(canvas, child, drawingTime);
            canvas.restoreToCount(save);
            return result;
        }
    }

    private int scrollOther(RecyclerView view, int requestedDy) {
        if (view == null || requestedDy == 0) {
            if (requestedDy != 0) {
                debugEvent("TRANSFER_NO_TARGET", view, requestedDy, 0, false);
            }
            return 0;
        }
        if (view == callbackSource) {
            debugEvent("TRANSFER_REENTRY", view, requestedDy, 0, true);
            throw new IllegalStateException("Reentrant scrollBy on nested source");
        }
        RecyclerView previousView = capturedView;
        int previousDy = capturedDy;
        capturedView = view;
        capturedDy = 0;
        programmaticDepth++;
        int actualConsumed;
        try {
            view.scrollBy(0, requestedDy);
            actualConsumed = capturedDy;
        } finally {
            programmaticDepth--;
            capturedView = previousView;
            capturedDy = previousDy;
        }
        int boundedConsumption = requestedDy > 0 ? Math.max(0, Math.min(requestedDy, actualConsumed)) : Math.min(0, Math.max(requestedDy, actualConsumed));
        debugEvent(actualConsumed == 0 ? "TRANSFER_ZERO" : "TRANSFER", view, requestedDy, boundedConsumption, false);
        return boundedConsumption;
    }

    private boolean knownTarget(View target) {
        return target == commonView || target == activeView;
    }
    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
        boolean vertical = (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        boolean accepted = programmaticDepth == 0 && vertical && knownTarget(target);
        if (debugLoggingEnabled) {
            debugEvent((accepted ? "NESTED_START" : "NESTED_REJECT")
                    + "_axes=" + axes + "_type=" + type, target, 0, 0, true);
        }
        return accepted;
    }
    @Override
    public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes, int type) {
        nestedHelper.onNestedScrollAccepted(child, target, axes, type);
    }
    @Override
    public void onStopNestedScroll(@NonNull View target, int type) {
        debugEvent(type == ViewCompat.TYPE_TOUCH ? "NESTED_STOP_TOUCH" : "NESTED_STOP_NON_TOUCH", target, 0, 0, true);
        nestedHelper.onStopNestedScroll(target, type);
    }
    @Override
    public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        if (programmaticDepth != 0 || !knownTarget(target)) {
            debugEvent("PRE_IGNORED", target, dy, 0, false);
            return;
        }
        if (transitioning) {
            debugBlockedScroll("PRE_BLOCKED", target, dy, type);
            consumed[1] += dy;
            return;
        }
        callbackSource = target;
        try {
            int parentConsumed = RecyclerPagerModel.preScroll(scrollHost, target == commonView, dy);
            consumed[1] += parentConsumed;
            debugEvent("PRE", target, dy, parentConsumed, false);
        } finally {
            callbackSource = null;
            updateGeometry();
        }
    }
    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, @NonNull int[] consumed) {
        if (programmaticDepth != 0 || !knownTarget(target)) {
            debugEvent("POST_IGNORED", target, dyUnconsumed, 0, false);
            return;
        }
        if (transitioning) {
            debugBlockedScroll("POST_BLOCKED", target, dyUnconsumed, type);
            consumed[1] += dyUnconsumed;
            return;
        }
        callbackSource = target;
        try {
            int parentConsumed = RecyclerPagerModel.postScroll(scrollHost, target == commonView, dyConsumed, dyUnconsumed);
            consumed[1] += parentConsumed;
            debugEvent("POST", target, dyUnconsumed, parentConsumed, false);
        } finally {
            callbackSource = null;
            updateGeometry();
        }
    }
    @Override
    public boolean onNestedPreFling(@NonNull View target, float vx, float vy) {
        return false;
    }
    @Override
    public boolean onNestedFling(@NonNull View target, float vx, float vy, boolean consumed) {
        return false;
    }
    @Override
    public int getNestedScrollAxes() {
        return nestedHelper.getNestedScrollAxes();
    }
    @Override
    public boolean onStartNestedScroll(View child, View target, int axes) {
        return onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH);
    }
    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH);
    }
    @Override
    public void onStopNestedScroll(View target) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH);
    }
    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH);
    }
    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type) {
        legacyConsumed[0] = legacyConsumed[1] = 0;
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, legacyConsumed);
    }
    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH);
    }

    private void stopScrolling() {
        debugEvent("STOP_SCROLLING", null, 0, 0, true);
        if (commonView != null) {
            commonView.stopScroll();
        }
        if (pager != null && pages != null) {
            View[] visible = pager.getViewPages();
            for (int i = 0; i < visible.length; i++) {
                if (visible[i] != null) {
                    pages.recyclerView(visible[i]).stopScroll();
                }
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (debugLoggingEnabled) {
                debugGestureId++;
                debugLastBlockedLogTime = 0;
                debugEvent("TOUCH_DOWN", null, 0, 0, true);
            }
            stopScrolling();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            debugEvent(event.getActionMasked() == MotionEvent.ACTION_UP ? "TOUCH_UP" : "TOUCH_CANCEL", null, 0, 0, true);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }
    @Override
    protected void onDetachedFromWindow() {
        stopScrolling();
        getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        super.onDetachedFromWindow();
    }
}
