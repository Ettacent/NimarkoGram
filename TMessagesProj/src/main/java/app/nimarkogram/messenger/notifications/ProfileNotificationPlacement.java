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
        if (requestedHeight == reservedHeight) return;
        if (list.isComputingLayout() || list.isShown() && list.hasPendingAdapterUpdates()) {
            list.postOnAnimation(this);
        } else {
            run();
        }
    }

    @Override public void run() {
        if (released || requestedHeight == reservedHeight) return;
        if (list.isComputingLayout() || list.isShown() && list.hasPendingAdapterUpdates()) {
            list.removeCallbacks(this);
            list.postOnAnimation(this);
            return;
        }

        View first = layout.findViewByPosition(0);
        if (list.isShown() && first != null && !layout.hasPendingScrollPosition() && !layout.isSmoothScrolling()) {
            layout.scrollToPositionWithOffset(0, layout.getDecoratedTop(first) - list.getPaddingTop());
        }
        reservedHeight = requestedHeight;
        reservationLayoutPending = true;
        list.invalidateItemDecorations();
    }

    public void prepareForDraw() {
        if (released || !reservationLayoutPending || !list.isShown()
                || list.isComputingLayout() || list.hasPendingAdapterUpdates()
                || list.getMeasuredWidth() == 0 || list.getMeasuredHeight() == 0) return;

        list.measure(View.MeasureSpec.makeMeasureSpec(list.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(list.getMeasuredHeight(), View.MeasureSpec.EXACTLY));
        list.layout(list.getLeft(), list.getTop(), list.getRight(), list.getBottom());
    }

    @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                         int oldLeft, int oldTop, int oldRight, int oldBottom) {
        reservationLayoutPending = false;
    }

    public int getAnchorBottom(int fallback) {
        int row = anchorRow.getAsInt();
        View anchor = row < 0 ? null : layout.findViewByPosition(row);
        return anchor == null ? fallback : Math.round(list.getY()) + anchor.getBottom();
    }

    @Override public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        outRect.set(0, 0, 0, 0);
        int row = anchorRow.getAsInt();
        if (!released && row >= 0 && parent.getChildAdapterPosition(view) == row) {
            outRect.bottom = reservedHeight;
        }
    }

    public void release() {
        if (released) return;
        released = true;
        list.removeCallbacks(this);
        list.removeOnLayoutChangeListener(this);
        if (list.isComputingLayout()) {
            list.post(() -> list.removeItemDecoration(this));
        } else {
            list.removeItemDecoration(this);
        }
    }
}
