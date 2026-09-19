package app.nimarkogram.messenger.preferences;

import android.view.View;
import android.view.ViewTreeObserver;

import org.telegram.ui.Components.UniversalRecyclerView;


final class SettingsSearchHighlight implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener, Runnable {
    private final UniversalRecyclerView list;
    private final int itemId;
    private ViewTreeObserver observer;
    private View highlightedView;
    private boolean finished;

    SettingsSearchHighlight(UniversalRecyclerView list, int itemId) {
        this.list = list;
        this.itemId = itemId;
        list.removeHighlightRow();
        observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        list.addOnAttachStateChangeListener(this);
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
        androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                list.findViewHolderForAdapterPosition(position);
        if (holder == null) {
            if (highlightedView != null) run();
            return true;
        }
        if (highlightedView == null) {
            highlightedView = holder.itemView;
            list.highlightRow(() -> list.findPositionByItemId(itemId));
            list.postDelayed(this, 700);
        } else if (holder.itemView != highlightedView) {

            run();
        } else {

            list.updateSelector();
        }
        return true;
    }

    @Override
    public void run() {
        if (finished) return;
        finished = true;
        list.removeCallbacks(this);
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        list.getViewTreeObserver().removeOnPreDrawListener(this);
        list.removeOnAttachStateChangeListener(this);
        list.removeHighlightRow();
    }

    @Override
    public void onViewAttachedToWindow(View view) {

        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        run();
    }
}
