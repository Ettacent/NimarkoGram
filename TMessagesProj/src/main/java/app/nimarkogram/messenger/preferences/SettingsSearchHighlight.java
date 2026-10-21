/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;
import android.graphics.Rect;
import android.view.MotionEvent;

import android.view.View;
import android.view.ViewTreeObserver;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.AndroidUtilities;

import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;


final class SettingsSearchHighlight extends RecyclerView.SimpleOnItemTouchListener
        implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener, Runnable {
    private final RecyclerListView list;
    private final RecyclerListView.IntReturnCallback positionCallback;
    private ViewTreeObserver observer;
    private View highlightedView;
    private int highlightedPosition = RecyclerView.NO_POSITION;
    private int scrollPosition = RecyclerView.NO_POSITION;
    private final Rect highlightedBounds = new Rect();
    private boolean keepTouchSelector;
    private boolean finished;

    SettingsSearchHighlight(UniversalRecyclerView list, int itemId) {
        this(list, () -> findItemPosition(list, itemId));
    }

    SettingsSearchHighlight(RecyclerListView list, RecyclerListView.IntReturnCallback positionCallback) {
        this.list = list;
        this.positionCallback = positionCallback;
        list.removeHighlightRow();
        observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        list.addOnAttachStateChangeListener(this);
        list.addOnItemTouchListener(this);
    }

    @Override
    public boolean onPreDraw() {
        if (finished || list.isLayoutRequested() || list.isComputingLayout() || list.hasPendingAdapterUpdates()) {
            return true;
        }
        int position = findPosition();
        if (position < 0 || !(list.getLayoutManager() instanceof LinearLayoutManager)) {
            run();
            return true;
        }
        if (highlightedView == null && scrollPosition != position) {
            scrollPosition = position;
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, AndroidUtilities.dp(80));
            return true;
        }
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (holder == null || holder.itemView.getVisibility() != View.VISIBLE
                || holder.itemView.getBottom() <= list.getPaddingTop()
                || holder.itemView.getTop() >= list.getHeight() - list.getPaddingBottom()) {
            run();
            return true;
        }
        final boolean firstHighlight = highlightedView == null;
        if (firstHighlight || holder.itemView != highlightedView || position != highlightedPosition) {
            highlightedView = holder.itemView;
            highlightedPosition = position;
            list.highlightRow(() -> position, 0);
            if (firstHighlight) list.postDelayed(this, 700);
        } else if (highlightedBounds.left != highlightedView.getLeft()
                || highlightedBounds.top != highlightedView.getTop()
                || highlightedBounds.right != highlightedView.getRight()
                || highlightedBounds.bottom != highlightedView.getBottom()
                || list.getSelectorRect().isEmpty()) {

            list.updateSelector();
        }
        highlightedBounds.set(highlightedView.getLeft(), highlightedView.getTop(),
                highlightedView.getRight(), highlightedView.getBottom());
        return true;
    }

    private int findPosition() {
        if (list.getAdapter() == null) return RecyclerView.NO_POSITION;
        int position = positionCallback.run();
        return position >= 0 && position < list.getAdapter().getItemCount()
                ? position : RecyclerView.NO_POSITION;
    }

    private static int findItemPosition(UniversalRecyclerView list, int itemId) {
        if (list.adapter == null || list.getAdapter() != list.adapter) return RecyclerView.NO_POSITION;
        int position = RecyclerView.NO_POSITION;
        for (int i = 0; i < list.adapter.getItemCount(); ++i) {
            UItem item = list.adapter.getItem(i);
            if (item == null || item.id != itemId) continue;
            switch (item.viewType) {
                case UniversalAdapter.VIEW_TYPE_HEADER:
                case UniversalAdapter.VIEW_TYPE_ANIMATED_HEADER:
                case UniversalAdapter.VIEW_TYPE_LARGE_HEADER:
                case UniversalAdapter.VIEW_TYPE_BLACK_HEADER:
                case UniversalAdapter.VIEW_TYPE_SHADOW:
                case UniversalAdapter.VIEW_TYPE_GRAY_SECTION:
                    continue;
            }
            if (position != RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION;
            position = i;
        }
        return position;
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView recyclerView, MotionEvent event) {
        if (!finished && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            keepTouchSelector = list.getPressedChildView() != null && !list.getSelectorRect().isEmpty();
            run();
        }
        return false;
    }
    @Override
    public void run() {
        if (finished) return;
        finished = true;
        list.removeCallbacks(this);
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        ViewTreeObserver currentObserver = list.getViewTreeObserver();
        if (currentObserver.isAlive()) currentObserver.removeOnPreDrawListener(this);
        list.removeOnAttachStateChangeListener(this);
        AndroidUtilities.runOnUIThread(() -> list.removeOnItemTouchListener(this));
        if (!keepTouchSelector) list.removeHighlightRow();
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        if (finished) return;

        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        run();
    }
}
