/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.NonNull;
import org.telegram.ui.ActionBar.Theme;

public class RecyclerPagerView extends ViewPagerFixed {
    private final SiblingRecyclerPagerLayout coordinator;
    private boolean touchStartedOnCommon;
    private View cancelledCurrentPage;
    private View cancelledOtherPage;

    public RecyclerPagerView(Context context, Theme.ResourcesProvider resourcesProvider,
                             SiblingRecyclerPagerLayout coordinator) {
        super(context, resourcesProvider);
        this.coordinator = coordinator;
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void onPageBound(@NonNull View page) {
        clearCancelledPagePair();
        coordinator.onPageBound(page);
    }

    @Override
    public void onStartTracking() {
        clearCancelledPagePair();
        super.onStartTracking();
    }

    @Override
    public boolean onTouchEventInternal(MotionEvent event) {
        boolean wasTracking = isTouch();
        boolean handled = super.onTouchEventInternal(event);
        if (wasTracking && !isTouch() && event != null
                && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            rememberCancelledPagePair();
        }
        return handled;
    }

    @Override
    public void resetTouch() {
        boolean wasTracking = isTouch();
        super.resetTouch();
        if (wasTracking && !isTouch()) {
            rememberCancelledPagePair();
        }
    }

    private void rememberCancelledPagePair() {
        View[] visiblePages = getViewPages();
        View current = visiblePages[0];
        View other = visiblePages[1];
        if (current == null || other == null || current.getTranslationX() != 0f
                || current.getMeasuredWidth() <= 0
                || Math.abs(other.getTranslationX()) < current.getMeasuredWidth()) {
            return;
        }
        cancelledCurrentPage = current;
        cancelledOtherPage = other;
        coordinator.debugPagerEvent("PAGER_DRAG_ABORTED");
        coordinator.onPagerChanged();
    }

    private void clearCancelledPagePair() {
        cancelledCurrentPage = null;
        cancelledOtherPage = null;
    }

    boolean isCancelledPagePair(View current, View other) {
        return current != null && other != null && current == cancelledCurrentPage
                && other == cancelledOtherPage && !isTouch()
                && current.getTranslationX() == 0f && current.getMeasuredWidth() > 0
                && Math.abs(other.getTranslationX()) >= current.getMeasuredWidth();
    }

    @Override
    public void onTabAnimationUpdate(boolean manual) {
        super.onTabAnimationUpdate(manual);
        coordinator.onPagerChanged();
    }

    @Override
    protected void onTabScrollEnd(int position) {
        super.onTabScrollEnd(position);
        coordinator.debugPagerEvent("PAGER_TAB_END");
        coordinator.onPagerChanged();
    }

    @Override
    protected void onScrollEnd() {
        super.onScrollEnd();
        coordinator.debugPagerEvent("PAGER_SCROLL_END");
        coordinator.onPagerChanged();
    }

    @Override
    protected float getAvailableTranslationX() {
        return Math.max(1, getMeasuredWidth());
    }

    @Override
    protected void setTranslationX(View view, float translationX) {
        if (view.getTranslationX() == translationX) {
            return;
        }
        super.setTranslationX(view, translationX);
        invalidate();
    }

    @Override
    protected boolean canScroll(MotionEvent event) {
        return !touchStartedOnCommon && coordinator.canStartPageSwipe() && super.canScroll(event);
    }

    private void rememberTouchOrigin(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchStartedOnCommon = coordinator.isCommonContentAt(event.getX(), event.getY());
            coordinator.debugPagerEvent(touchStartedOnCommon ? "PAGER_DOWN_COMMON" : "PAGER_DOWN_PAGE");
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        rememberTouchOrigin(event);
        return !touchStartedOnCommon && super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) {
            coordinator.debugPagerEvent("PAGER_CANCEL_NULL");
            touchStartedOnCommon = false;
            return super.onTouchEvent(null);
        }
        rememberTouchOrigin(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            coordinator.debugPagerEvent(event.getActionMasked() == MotionEvent.ACTION_UP ? "PAGER_UP" : "PAGER_CANCEL");
        }
        return !touchStartedOnCommon && super.onTouchEvent(event);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        int save = canvas.save();
        canvas.clipRect(child.getX(), -coordinator.getTopBleed(), child.getX() + child.getWidth(),
                getHeight() + coordinator.getBottomBleed());
        boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(save);
        return result;
    }
}
