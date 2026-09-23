package app.nimarkogram.messenger.notifications;

import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public final class NotificationListInset {
    private final RecyclerView list;
    private int applied;
    private int writtenTop = -1;
    private boolean originalClip;
    private boolean active;

    private LinearLayoutManager anchorLayout;
    private int anchorPosition = RecyclerView.NO_POSITION;
    private int anchorOffset;
    public NotificationListInset(RecyclerView list) {
        this.list = list;
    }

    public boolean apply(int height, int anchor, float visibility) {
        if (list.getPaddingTop() != writtenTop) {
            applied = 0;
            anchorLayout = null;
        }
        int base = list.getPaddingTop() - applied;
        int margin = list.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) list.getLayoutParams()).topMargin : 0;
        int next = Math.max(0, height) + Math.round(Math.max(0, anchor - margin - base)
                * Math.max(0f, Math.min(1f, visibility)));
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
            int startPosition = 0;
            if (!list.hasPendingAdapterUpdates() && !list.isComputingLayout()
                    && list.getLayoutManager() instanceof LinearLayoutManager) {
                layout = (LinearLayoutManager) list.getLayoutManager();
                startPosition = layout.getReverseLayout() ? layout.getItemCount() - 1 : 0;
                if (layout.getOrientation() == RecyclerView.VERTICAL && !layout.isSmoothScrolling()) {
                    if (layout == anchorLayout && layout.hasPendingScrollPosition(anchorPosition, anchorOffset)) {
                        position = anchorPosition;
                        top = previousPadding + anchorOffset;
                    } else if (!layout.hasPendingScrollPosition()) {
                        position = layout.getReverseLayout()
                                ? layout.findLastVisibleItemPosition() : layout.findFirstVisibleItemPosition();
                        View first = layout.findViewByPosition(position);
                        if (position != RecyclerView.NO_POSITION && first != null) {
                            top = layout.getDecoratedTop(first)
                                    - ((RecyclerView.LayoutParams) first.getLayoutParams()).topMargin;
                        } else {
                            position = RecyclerView.NO_POSITION;
                        }
                    }
                }
            }
            applied = next;
            writtenTop = base + next;
            list.setPadding(list.getPaddingLeft(), writtenTop, list.getPaddingRight(), list.getPaddingBottom());
            if (layout != null && position != RecyclerView.NO_POSITION) {
                int targetTop;
                if (position != startPosition || layout.getStackFromEnd() && top > previousPadding + 1) {
                    targetTop = top;
                } else if (next > previous) {
                    targetTop = top < previousPadding - 1 ? top : top + next - previous;
                } else {
                    targetTop = Math.min(top, writtenTop);
                }
                anchorLayout = layout;
                anchorPosition = position;
                anchorOffset = targetTop - writtenTop;
                layout.scrollToPositionWithOffset(position, anchorOffset, false);
            } else {
                anchorLayout = null;
            }
        }
        if (next == 0 && active) {
            if (!list.getClipToPadding()) list.setClipToPadding(originalClip);
            active = false;
        }
        return changed;
    }

    public void release() {
        apply(0, 0, 0);
    }
}
