package app.nimarkogram.messenger.notifications;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.IntSupplier;

public final class ProfileNotificationPlacement extends RecyclerView.ItemDecoration implements Runnable, View.OnLayoutChangeListener {
    private final RecyclerView list;
    private final LinearLayoutManager layout;
    private final IntSupplier anchorRow;
    private int reservedHeight;
    private int requestedHeight;
    private int reservedRow = RecyclerView.NO_POSITION;
    private boolean released;
    private boolean reservationLayoutPending;

    public ProfileNotificationPlacement(RecyclerView list, LinearLayoutManager layout, IntSupplier anchorRow) {
        this.list = list;
        this.layout = layout;
        this.anchorRow = anchorRow;
        list.addItemDecoration(this);
        list.addOnLayoutChangeListener(this);
    }

    public void setReservedHeight(int height) {
        if (released) return;
        requestedHeight = Math.max(0, height);
        list.removeCallbacks(this);
        run();
    }

    @Override public void run() {
        if (list.isComputingLayout() || !released && list.isShown() && list.hasPendingAdapterUpdates()) {
            list.removeCallbacks(this);
            list.postOnAnimation(this);
            return;
        }

        if (released) {
            list.removeItemDecoration(this);
            return;
        }
        int row = requestedHeight == 0 ? RecyclerView.NO_POSITION : getAnchorRow();
        int next = row < 0 ? 0 : requestedHeight;
        if (next == reservedHeight && row == reservedRow) return;
        if (list.isShown() && list.getLayoutManager() == layout
                && layout.getOrientation() == RecyclerView.VERTICAL
                && !layout.hasPendingScrollPosition() && !layout.isSmoothScrolling()) {
            int position = layout.getReverseLayout()
                    ? layout.findLastVisibleItemPosition() : layout.findFirstVisibleItemPosition();
            View first = layout.findViewByPosition(position);
            if (position != RecyclerView.NO_POSITION && first != null) {
                int top = layout.getDecoratedTop(first)
                        - ((RecyclerView.LayoutParams) first.getLayoutParams()).topMargin;
                layout.scrollToPositionWithOffset(position, top - list.getPaddingTop(), false);
            }
        }
        reservedHeight = next;
        reservedRow = row;
        reservationLayoutPending = true;
        list.invalidateItemDecorations();
    }

    public void prepareForDraw() {
        if (released || !reservationLayoutPending || !list.isShown()
                || list.isComputingLayout() || list.hasPendingAdapterUpdates() || list.isLayoutSuppressed()
                || list.getMeasuredWidth() == 0 || list.getMeasuredHeight() == 0) return;
        if (!list.isLayoutRequested()) {
            reservationLayoutPending = false;
            return;
        }

        list.measure(View.MeasureSpec.makeMeasureSpec(list.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(list.getMeasuredHeight(), View.MeasureSpec.EXACTLY));
        list.layout(list.getLeft(), list.getTop(), list.getRight(), list.getBottom());
        reservationLayoutPending = false;
    }

    @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                         int oldLeft, int oldTop, int oldRight, int oldBottom) {
        reservationLayoutPending = false;
    }

    public int getAnchorBottom(int fallback) {
        int row = getAnchorRow();
        View anchor = row < 0 ? null : layout.findViewByPosition(row);
        return anchor == null ? fallback : Math.round(list.getY() + anchor.getY() + anchor.getHeight());
    }
    private int getAnchorRow() {
        if (released || anchorRow == null || !list.isShown() || list.getLayoutManager() != layout) {
            return RecyclerView.NO_POSITION;
        }
        int row = anchorRow.getAsInt();
        return row >= 0 && row < layout.getItemCount() ? row : RecyclerView.NO_POSITION;
    }

    @Override public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        outRect.set(0, 0, 0, 0);
        if (!released && parent == list && parent.getLayoutManager() == layout
                && reservedRow >= 0 && parent.getChildAdapterPosition(view) == reservedRow) {
            outRect.bottom = reservedHeight;
        }
    }

    public void release() {
        if (released) return;
        released = true;
        requestedHeight = reservedHeight = 0;
        reservedRow = RecyclerView.NO_POSITION;
        reservationLayoutPending = false;
        list.removeCallbacks(this);
        list.removeOnLayoutChangeListener(this);
        run();
    }
}
