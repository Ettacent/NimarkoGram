package app.nimarkogram.messenger.notifications;

import android.widget.FrameLayout;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

final class NotificationListInset {
    private final RecyclerView list;
    private int applied;
    private int writtenTop = -1;
    private boolean originalClip;
    private boolean active;

    NotificationListInset(RecyclerView list) {
        this.list = list;
    }

    boolean apply(int height, int anchor, float visibility) {
        if (!(list.getLayoutParams() instanceof FrameLayout.LayoutParams)) return false;
        if (list.getPaddingTop() != writtenTop) applied = 0;
        int base = list.getPaddingTop() - applied;
        int margin = ((FrameLayout.LayoutParams) list.getLayoutParams()).topMargin;
        int next = height + Math.round(Math.max(0, anchor - margin - base) * visibility);
        if (next > 0 && !active) {
            originalClip = list.getClipToPadding();
            active = true;
            list.setClipToPadding(false);
        }
        boolean changed = next != applied;
        if (changed) {
            int previous = applied;
            int previousPadding = list.getPaddingTop();
            LinearLayoutManager layout = null;
            int position = RecyclerView.NO_POSITION;
            int top = 0;
            if (!list.hasPendingAdapterUpdates() && !list.isComputingLayout()
                    && list.getLayoutManager() instanceof LinearLayoutManager) {
                layout = (LinearLayoutManager) list.getLayoutManager();
                if (layout.getOrientation() == RecyclerView.VERTICAL && !layout.getReverseLayout()
                        && !layout.getStackFromEnd() && !layout.isSmoothScrolling()
                        && !layout.hasPendingScrollPosition()) {
                    position = layout.findFirstVisibleItemPosition();
                    View first = layout.findViewByPosition(position);
                    if (position != RecyclerView.NO_POSITION && first != null) {
                        top = layout.getDecoratedTop(first);
                    }
                }
            }
            applied = next;
            writtenTop = base + next;
            list.setPadding(list.getPaddingLeft(), writtenTop, list.getPaddingRight(), list.getPaddingBottom());
            if (layout != null && position != RecyclerView.NO_POSITION) {
                int targetTop;
                if (position > 0) {
                    targetTop = top;
                } else if (next > previous) {
                    targetTop = top < previousPadding - 1 ? top : writtenTop;
                } else {
                    targetTop = Math.min(top, writtenTop);
                }
                layout.scrollToPositionWithOffset(position, targetTop - writtenTop);
            }
        }
        if (next == 0 && active) {
            list.setClipToPadding(originalClip);
            active = false;
        }
        return changed;
    }

    void release() {
        apply(0, 0, 0);
    }
}
