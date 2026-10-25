/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.DialogCell;

final class ResumeListRegions {
    private static final int MAX_LISTS = 12;
    private static final int MAX_ROWS = 192;
    private static final int MAX_VIEWS = 1024;
    private static final int MAX_DEPTH = 64;

    private final ViewGroup root;
    private View capturedExclusion;
    private View liveExclusion;
    private final ListState[] lists = new ListState[MAX_LISTS];
    private final RecyclerView[] currentLists = new RecyclerView[MAX_LISTS];
    private final Row[] liveRows = new Row[MAX_ROWS];
    private final int[] rootLocation = new int[2];
    private final int[] location = new int[2];
    private final Point globalOffset = new Point();
    private final Rect bounds = new Rect();
    private final Rect visible = new Rect();
    private final Rect viewport = new Rect();
    private final Rect destination = new Rect();
    private final Rect captureRoot = new Rect();
    private IBinder window;
    private int listCount;
    private int currentCount;
    private int visited;
    private boolean overflow;
    private boolean capturedOverflow;
    private boolean captureValidated;
    private boolean captureValid;

    ResumeListRegions(ViewGroup root) {
        this.root = root;
        for (int i = 0; i < MAX_ROWS; i++) {
            liveRows[i] = new Row();
        }
    }

    void capture() {
        capture(null);
    }

    void stopScrolling(View excluded) {
        capturedExclusion = liveExclusion = excluded;
        window = root.getWindowToken();
        if (!ready()) return;
        collect();
        for (int i = 0; i < currentCount; i++) {
            if (currentLists[i].getScrollState() == RecyclerView.SCROLL_STATE_SETTLING) {
                currentLists[i].stopScroll();
            }
        }
        releaseCurrent();
    }

    void capture(View excluded) {
        clear();
        capturedExclusion = liveExclusion = excluded;
        window = root.getWindowToken();
        if (!ready()) {
            return;
        }
        captureRoot.set(rootLocation[0], rootLocation[1],
                rootLocation[0] + root.getWidth(), rootLocation[1] + root.getHeight());
        collect();
        capturedOverflow = overflow;
        int remaining = MAX_ROWS;
        for (int i = 0; i < currentCount; i++) {
            RecyclerView list = currentLists[i];
            if (!geometry(list, bounds, visible)) {
                continue;
            }
            ListState state = new ListState(list, bounds, visible);
            lists[listCount++] = state;
            if (!contentGeometry(list, bounds, state.viewport)) {
                state.viewport.set(0, 0, 0, 0);
                continue;
            }
            visible.set(state.viewport.left, state.viewport.top,
                    state.viewport.right, state.viewport.bottom);
            if (state.adapter == null || !translationOnly(list) || captureBusy(list)
                    || list.getChildCount() > remaining) {
                continue;
            }
            remaining -= list.getChildCount();
            state.adapter.registerAdapterDataObserver(state);
            state.registered = true;
            state.dirty = list.hasPendingAdapterUpdates();
            state.captureUnsafe = captureBusy(list);
            for (int j = 0; j < list.getChildCount(); j++) {
                View child = list.getChildAt(j);
                if (outsideViewport(child, state.viewport)) {
                    state.captureExcluded[j] = true;
                    continue;
                }
                Row row = new Row();
                if (!readRow(state, child, row)) {
                    state.captureUnsafe = true;
                    continue;
                }
                state.captureRows[j] = row;
                if (row.visible.intersect(state.viewport)) {
                    state.rows[state.count++] = row;
                }
            }
            for (int j = 0; j < state.count; j++) {
                for (int k = j + 1; k < state.count; k++) {
                    if (sameIdentity(state.rows[j], state.rows[k])) {
                        state.rows[j].ambiguous = true;
                        state.rows[k].ambiguous = true;
                    }
                }
            }
        }
        releaseCurrent();
    }

    boolean validateCapture() {
        if (captureValidated) return captureValid;
        captureValidated = true;
        captureValid = checkCapture(true);
        return captureValid;
    }

    boolean isCaptureCurrent() {
        return checkCapture(false);
    }

    private boolean checkCapture(boolean finish) {
        boolean rootStable = ready() && captureRoot.left == rootLocation[0]
                && captureRoot.top == rootLocation[1]
                && captureRoot.width() == root.getWidth() && captureRoot.height() == root.getHeight();
        if (rootStable) collect();
        boolean allSafe = rootStable && !capturedOverflow && !overflow && currentCount == listCount;
        boolean globallySafe = allSafe;
        int remaining = MAX_ROWS;
        for (int i = 0; i < listCount; i++) {
            ListState state = lists[i];
            RecyclerView list = state.list;
            boolean safe = globallySafe && !state.captureUnsafe && !state.dirty
                    && containsCurrent(list) && list.getAdapter() == state.adapter
                    && state.adapter != null && state.adapter.getItemCount() == state.itemCount
                    && !captureBusy(list) && translationOnly(list)
                    && list.getChildCount() == state.childCount && state.childCount <= remaining
                    && state.paddingLeft == list.getPaddingLeft() && state.paddingTop == list.getPaddingTop()
                    && state.paddingRight == list.getPaddingRight() && state.paddingBottom == list.getPaddingBottom()
                    && geometry(list, bounds, visible) && sameRect(state.bounds, bounds)
                    && sameRect(state.outerViewport, visible)
                    && contentGeometry(list, bounds, visible) && sameRect(state.viewport, visible);
            if (safe) {
                remaining -= state.childCount;
                Row live = liveRows[0];
                for (int j = 0; j < state.childCount; j++) {
                    View child = list.getChildAt(j);
                    if (state.captureExcluded[j]) {
                        if (!outsideViewport(child, state.viewport)) {
                            safe = false;
                            break;
                        }
                        continue;
                    }
                    Row old = state.captureRows[j];
                    if (old == null || !readRow(state, child, live)) {
                        safe = false;
                        break;
                    }
                    live.visible.intersect(state.viewport);
                    if (old.view != live.view || old.position != live.position || old.type != live.type
                            || !sameIdentity(old, live) || !sameRect(old.bounds, live.bounds)
                            || !sameRect(old.visible, live.visible)) {
                        safe = false;
                        break;
                    }
                }
                live.view = null;
            }
            if (!safe) {
                if (finish) {
                    for (int j = 0; j < state.count; j++) state.rows[j] = null;
                    state.count = 0;
                }
                allSafe = false;
            }
            if (finish) for (int j = 0; j < MAX_ROWS; j++) state.captureRows[j] = null;
        }
        releaseCurrent();
        return allSafe;
    }

    boolean needsRecapture() {
        if (!captureValidated) return false;
        for (int i = 0; i < listCount; i++) {
            if (lists[i].primaryContent && lists[i].count == 0) return true;
        }
        return false;
    }

    private static boolean captureBusy(RecyclerView list) {
        return list.hasPendingAdapterUpdates() || list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE
                || list.isComputingLayout() || list.isLayoutRequested() || list.isAnimating();
    }

    private static boolean sameRect(Rect a, Rect b) {
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }

    void clipLists(Canvas canvas) {
        if (!ready()) {
            canvas.clipRect(0, 0, 0, 0);
            return;
        }
        collect();
        if (capturedOverflow || overflow) {
            canvas.clipRect(0, 0, 0, 0);
        } else {
            for (int i = 0; i < listCount; i++) {
                canvas.clipRect(lists[i].outerViewport, Region.Op.DIFFERENCE);
            }
            for (int i = 0; i < currentCount; i++) {
                if (geometry(currentLists[i], bounds, visible)) {
                    canvas.clipRect(visible, Region.Op.DIFFERENCE);
                }
            }
        }
        releaseCurrent();
    }

    void draw(Canvas canvas, Bitmap snapshot, Paint paint, int capturedWidth,
              int capturedHeight, Rect oldBanner, Rect oldNotification, Rect oldComposer, Rect oldHeader, Rect oldFolders, Rect oldInfoCard) {
        if (!ready() || capturedOverflow || snapshot == null || snapshot.isRecycled()
                || capturedWidth <= 0 || capturedHeight <= 0) {
            return;
        }
        collect();
        if (overflow) {
            releaseCurrent();
            return;
        }
        destination.set(0, 0, capturedWidth, capturedHeight);
        int remaining = MAX_ROWS;
        for (int i = 0; i < listCount; i++) {
            ListState state = lists[i];
            RecyclerView list = state.list;
            if (state.adapter == null || !containsCurrent(list) || list.getAdapter() != state.adapter
                    || !translationOnly(list) || !contentGeometry(list, bounds, viewport)
                    || list.getChildCount() > remaining) {
                continue;
            }
            remaining -= list.getChildCount();
            int count = 0;
            for (int j = 0; j < list.getChildCount(); j++) {
                if (readRow(state, list.getChildAt(j), liveRows[count])) {
                    count++;
                }
            }
            for (int j = 0; j < state.count; j++) {
                Row old = state.rows[j];
                if (old.ambiguous || old.kind == 0
                        && (state.dirty || list.hasPendingAdapterUpdates())) {
                    continue;
                }
                Row match = null;
                for (int k = 0; k < count; k++) {
                    if (sameIdentity(old, liveRows[k])) {
                        if (match != null) {
                            match = null;
                            break;
                        }
                        match = liveRows[k];
                    }
                }
                if (match == null || old.type != match.type
                        || old.bounds.width() != match.bounds.width()
                        || old.bounds.height() != match.bounds.height()) {
                    continue;
                }
                int save = canvas.save();
                try {
                    canvas.clipRect(viewport);
                    canvas.clipRect(match.bounds);
                    canvas.clipRect(match.visible);
                    canvas.translate(match.bounds.left - old.bounds.left,
                            match.bounds.top - old.bounds.top);
                    canvas.clipRect(old.visible);
                    if (oldBanner != null && !oldBanner.isEmpty()) {
                        canvas.clipRect(oldBanner, Region.Op.DIFFERENCE);
                    }
                    if (oldNotification != null && !oldNotification.isEmpty()) {
                        canvas.clipRect(oldNotification, Region.Op.DIFFERENCE);
                    }
                    if (oldComposer != null && !oldComposer.isEmpty()) {
                        canvas.clipRect(oldComposer, Region.Op.DIFFERENCE);
                    }
                    if (oldHeader != null && !oldHeader.isEmpty()) {
                        canvas.clipRect(oldHeader, Region.Op.DIFFERENCE);
                    }
                    if (oldFolders != null && !oldFolders.isEmpty()) {
                        canvas.clipRect(oldFolders, Region.Op.DIFFERENCE);
                    }
                    if (oldInfoCard != null && !oldInfoCard.isEmpty()) {
                        canvas.clipRect(oldInfoCard, Region.Op.DIFFERENCE);
                    }
                    canvas.drawBitmap(snapshot, null, destination, paint);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            for (int j = 0; j < count; j++) {
                liveRows[j].view = null;
            }
        }
        releaseCurrent();
    }

    void clear() {
        capturedExclusion = liveExclusion = null;
        for (int i = 0; i < listCount; i++) {
            ListState state = lists[i];
            if (state.registered) {
                state.adapter.unregisterAdapterDataObserver(state);
            }
            lists[i] = null;
        }
        for (Row row : liveRows) {
            row.view = null;
        }
        releaseCurrent();
        listCount = 0;
        window = null;
        capturedOverflow = false;
        captureValidated = false;
        captureValid = false;
    }

    private boolean ready() {
        if (window == null || root.getWindowToken() != window || !root.isShown()
                || root.getWindowVisibility() != View.VISIBLE
                || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return false;
        }
        root.getLocationInWindow(rootLocation);
        return true;
    }

    private void collect() {
        releaseCurrent();
        visited = 0;
        overflow = false;
        visit(root, 0);
    }

    void setLiveExclusion(View view) {
        liveExclusion = view;
    }

    private void visit(View view, int depth) {
        if (view == capturedExclusion || view == liveExclusion) return;
        if (++visited > MAX_VIEWS || depth > MAX_DEPTH) {
            overflow = true;
            return;
        }
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0
                || view.getWindowToken() != window) {
            return;
        }
        if (view instanceof RecyclerView) {
            if (geometry(view, bounds, visible)) {
                if (!translationOnly(view) || currentCount == MAX_LISTS) {
                    overflow = true;
                } else {
                    currentLists[currentCount++] = (RecyclerView) view;
                }
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && !overflow; i++) {
                visit(group.getChildAt(i), depth + 1);
            }
        }
    }

    private void releaseCurrent() {
        for (int i = 0; i < currentCount; i++) {
            currentLists[i] = null;
        }
        currentCount = 0;
    }

    private boolean containsCurrent(RecyclerView list) {
        for (int i = 0; i < currentCount; i++) {
            if (currentLists[i] == list) {
                return true;
            }
        }
        return false;
    }

    private boolean geometry(View view, Rect outBounds, Rect outVisible) {
        if (view.getWindowToken() != window || !view.isShown() || view.getAlpha() <= 0
                || view.getWidth() <= 0 || view.getHeight() <= 0
                || !view.getGlobalVisibleRect(outVisible, globalOffset)) {
            return false;
        }
        view.getLocationInWindow(location);
        int x = location[0] - rootLocation[0];
        int y = location[1] - rootLocation[1];
        outBounds.set(x, y, x + view.getWidth(), y + view.getHeight());
        outVisible.offset(x - globalOffset.x, y - globalOffset.y);
        return outVisible.intersect(outBounds)
                && outVisible.intersect(0, 0, root.getWidth(), root.getHeight());
    }

    private boolean outsideViewport(View child, Rect viewport) {
        if (child.getWindowToken() != window || !translationOnly(child)
                || child.getWidth() <= 0 || child.getHeight() <= 0) return false;
        child.getLocationInWindow(location);
        int x = location[0] - rootLocation[0];
        int y = location[1] - rootLocation[1];
        return x >= viewport.right || x + child.getWidth() <= viewport.left
                || y >= viewport.bottom || y + child.getHeight() <= viewport.top;
    }

    private boolean contentGeometry(RecyclerView list, Rect outBounds, Rect outVisible) {
        if (!geometry(list, outBounds, outVisible)) return false;
        return outVisible.intersect(outBounds.left + list.getPaddingLeft(),
                outBounds.top + list.getPaddingTop(),
                outBounds.right - list.getPaddingRight(),
                outBounds.bottom - list.getPaddingBottom());
    }

    private boolean translationOnly(View view) {
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            if (view.getScaleX() != 1f || view.getScaleY() != 1f
                    || view.getRotation() != 0f || view.getRotationX() != 0f
                    || view.getRotationY() != 0f || view.getAlpha() <= 0) {
                return false;
            }
            if (view == root) {
                return true;
            }
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) {
                return false;
            }
            view = (View) parent;
        }
        return false;
    }

    private boolean readRow(ListState state, View child, Row row) {
        row.view = null;
        if (!translationOnly(child) || !geometry(child, row.bounds, row.visible)) {
            return false;
        }
        RecyclerView.ViewHolder holder = state.list.getChildViewHolder(child);
        int position = state.list.getChildAdapterPosition(child);
        if (holder == null || holder.isRemoved() || holder.isInvalid()
                || position == RecyclerView.NO_POSITION) {
            return false;
        }
        row.position = position;
        row.kind = 0;
        row.id = 0;
        row.dialog = 0;
        row.account = 0;
        row.type = holder.getItemViewType();
        if (child instanceof DialogCell) {
            DialogCell cell = (DialogCell) child;
            if (cell.getDialogId() == 0) {
                return false;
            }
            row.kind = 2;
            row.dialog = cell.getDialogId();
            row.account = cell.getEmojiAccount();
            int topicId = cell.getResumeSnapshotTopicId();
            int messageId = cell.getResumeSnapshotMessageId();
            if (topicId != 0) {
                row.kind = 4;
                row.id = topicId;
            } else if (messageId != 0) {
                row.kind = 5;
                row.id = messageId;
            }
        } else if (state.adapter.hasStableIds() && holder.getItemId() != RecyclerView.NO_ID) {
            row.kind = 1;
            row.id = holder.getItemId();
        } else if (child instanceof ChatMessageCell) {
            MessageObject message = ((ChatMessageCell) child).getMessageObject();
            if (message == null || message.getDialogId() == 0 || message.getId() == 0) {
                return false;
            }
            row.kind = 3;
            row.dialog = message.getDialogId();
            row.account = message.currentAccount;
            row.id = message.getId();
        }
        row.view = child;
        return true;
    }

    private static boolean sameIdentity(Row a, Row b) {
        if (a.kind != b.kind) {
            return false;
        }
        if (a.kind == 0) {
            return a.view == b.view && a.position == b.position;
        }
        return a.id == b.id && a.dialog == b.dialog && a.account == b.account;
    }

    private static final class Row {
        final Rect bounds = new Rect();
        final Rect visible = new Rect();
        View view;
        int position;
        int kind;
        int type;
        int account;
        long id;
        long dialog;
        boolean ambiguous;
    }

    private static final class ListState extends RecyclerView.AdapterDataObserver {
        final RecyclerView list;
        final RecyclerView.Adapter adapter;
        final Rect viewport;
        final Rect outerViewport;
        final Rect bounds;
        final Row[] rows = new Row[MAX_ROWS];
        final Row[] captureRows = new Row[MAX_ROWS];
        final boolean[] captureExcluded = new boolean[MAX_ROWS];
        final int childCount, itemCount;
        final int paddingLeft, paddingTop, paddingRight, paddingBottom;
        final boolean primaryContent;
        boolean captureUnsafe = true;
        int count;
        boolean dirty;
        boolean registered;

        ListState(RecyclerView list, Rect bounds, Rect viewport) {
            this.list = list;
            this.adapter = list.getAdapter();
            this.viewport = new Rect(viewport);
            this.outerViewport = new Rect(viewport);
            this.bounds = new Rect(bounds);
            childCount = list.getChildCount();
            boolean primary = false;
            for (int i = 0; i < Math.min(childCount, MAX_ROWS); i++) {
                View child = list.getChildAt(i);
                if (child instanceof DialogCell || child instanceof ChatMessageCell) {
                    primary = true;
                    break;
                }
            }
            primaryContent = primary;
            itemCount = adapter == null ? 0 : adapter.getItemCount();
            paddingLeft = list.getPaddingLeft();
            paddingTop = list.getPaddingTop();
            paddingRight = list.getPaddingRight();
            paddingBottom = list.getPaddingBottom();
        }

        private void markDirty() {
            dirty = true;
        }

        @Override
        public void onChanged() {
            markDirty();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            markDirty();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount, Object payload) {
            markDirty();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            markDirty();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            markDirty();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            markDirty();
        }
    }
}
