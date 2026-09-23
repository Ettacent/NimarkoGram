package app.nimarkogram.messenger.preferences;
import android.graphics.Rect;
import android.view.MotionEvent;

import android.view.View;
import android.view.ViewTreeObserver;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.AndroidUtilities;

import org.telegram.ui.Components.UniversalRecyclerView;


final class SettingsSearchHighlight extends RecyclerView.SimpleOnItemTouchListener
        implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener, Runnable {
    private final UniversalRecyclerView list;
    private final int itemId;
    private ViewTreeObserver observer;
    private View highlightedView;
    private int highlightedPosition = RecyclerView.NO_POSITION;
    private final Rect highlightedBounds = new Rect();
    private boolean keepTouchSelector;
    private boolean finished;

    SettingsSearchHighlight(UniversalRecyclerView list, int itemId) {
        this.list = list;
        this.itemId = itemId;
        list.removeHighlightRow();
        observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        list.addOnAttachStateChangeListener(this);
        list.addOnItemTouchListener(this);
    }

    @Override
    public boolean onPreDraw() {
        if (finished || list.isLayoutRequested() || list.hasPendingAdapterUpdates()) {
            return true;
        }
        int position = list.findPositionByItemId(itemId);
        if (position < 0) {
            run();
            return true;
        }
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (holder == null) {
            run();
            return true;
        }
        final boolean firstHighlight = highlightedView == null;
        if (firstHighlight || holder.itemView != highlightedView || position != highlightedPosition) {
            highlightedView = holder.itemView;
            highlightedPosition = position;
            list.highlightRow(() -> list.findPositionByItemId(itemId), 0);
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
        list.getViewTreeObserver().removeOnPreDrawListener(this);
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
