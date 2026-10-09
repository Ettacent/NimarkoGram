/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils.ui;

import android.view.View;
import android.graphics.Rect;

import androidx.core.view.OneShotPreDrawListener;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.ui.Components.BlurredRecyclerView;

public final class ProfileListPadding {
    private final RecyclerView list;
    private OneShotPreDrawListener completion;
    private RecyclerView.Adapter<?> anchorAdapter;
    private int anchorPosition;
    private int anchorOffset;
    private final Rect anchorBounds = new Rect();

    public ProfileListPadding(RecyclerView list) {
        this.list = list;
        list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
            }
            @Override public void onViewDetachedFromWindow(View view) {
                clearAnchor();
            }
        });
    }

    private void clearAnchor() {
        if (completion != null) completion.removeListener();
        completion = null;
        anchorAdapter = null;
    }

    private void releaseAnchorAfterLayout() {
        if (list.isShown() && list.isLayoutRequested()) {
            completion = OneShotPreDrawListener.add(list, this::releaseAnchorAfterLayout);
        } else {
            completion = null;
            anchorAdapter = null;
        }
    }

    public void apply(int left, int top, int right, int bottom) {
        int logicalTop = list instanceof BlurredRecyclerView
                ? ((BlurredRecyclerView) list).topPadding : list.getPaddingTop();
        if (list.getPaddingLeft() == left && logicalTop == top
                && list.getPaddingRight() == right && list.getPaddingBottom() == bottom) return;
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        boolean preserve = manager instanceof LinearLayoutManager && adapter != null
                && adapter.getItemCount() > 0 && list.isShown() && list.isLaidOut()
                && !list.isComputingLayout() && list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE;
        if (preserve && (completion == null || anchorAdapter != adapter)) {
            clearAnchor();
            LinearLayoutManager layout = (LinearLayoutManager) manager;
            int first = layout.findFirstVisibleItemPosition();
            View row = first == RecyclerView.NO_POSITION ? null : layout.findViewByPosition(first);
            if (row == null) {
                preserve = false;
            } else {
                manager.getDecoratedBoundsWithMargins(row, anchorBounds);
                anchorPosition = first;
                anchorOffset = first == 0 && !list.canScrollVertically(-1)
                        ? 0 : anchorBounds.top - list.getPaddingTop();
                anchorAdapter = adapter;
                completion = OneShotPreDrawListener.add(list, this::releaseAnchorAfterLayout);
            }
        }
        list.setPadding(left, top, right, bottom);
        if (preserve) {
            ((LinearLayoutManager) manager).scrollToPositionWithOffset(
                    Math.min(anchorPosition, adapter.getItemCount() - 1), anchorOffset);
        } else {
            clearAnchor();
        }
    }
}
