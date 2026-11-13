/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class ExtendedGridLayoutManager extends GridLayoutManager {

    private final boolean firstRowFullWidth;
    private final boolean lastRowFullWidth;

    private SparseIntArray itemSpans = new SparseIntArray();
    private SparseIntArray itemsToRow = new SparseIntArray();
    private int lastSpanCount = -1;
    private int lastItemCount = -1;
    private int firstRowMax;
    private int rowsCount;
    private int calculatedWidth = -1;
    private long dataVersion;
    private long calculatedDataVersion = -1;

    public ExtendedGridLayoutManager(Context context, int spanCount) {
        this(context, spanCount, false);
    }

    public ExtendedGridLayoutManager(Context context, int spanCount, boolean lastRowFullWidth) {
        this(context, spanCount, lastRowFullWidth, false);
    }

    public ExtendedGridLayoutManager(Context context, int spanCount, boolean lastRowFullWidth, boolean firstRowFullWidth) {
        super(context, spanCount);
        this.lastRowFullWidth = lastRowFullWidth;
        this.firstRowFullWidth = firstRowFullWidth;
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    @Override
    public void onAdapterChanged(RecyclerView.Adapter oldAdapter, RecyclerView.Adapter newAdapter) {
        super.onAdapterChanged(oldAdapter, newAdapter);
        invalidateLayout();
    }

    @Override
    public void onItemsChanged(RecyclerView recyclerView) {
        super.onItemsChanged(recyclerView);
        invalidateLayout();
    }

    @Override
    public void onItemsAdded(RecyclerView recyclerView, int positionStart, int itemCount) {
        super.onItemsAdded(recyclerView, positionStart, itemCount);
        invalidateLayout();
    }

    @Override
    public void onItemsRemoved(RecyclerView recyclerView, int positionStart, int itemCount) {
        super.onItemsRemoved(recyclerView, positionStart, itemCount);
        invalidateLayout();
    }

    @Override
    public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount) {
        super.onItemsUpdated(recyclerView, positionStart, itemCount);
        invalidateLayout();
    }

    @Override
    public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount, Object payload) {
        super.onItemsUpdated(recyclerView, positionStart, itemCount, payload);
        invalidateLayout();
    }

    @Override
    public void onItemsMoved(RecyclerView recyclerView, int from, int to, int itemCount) {
        super.onItemsMoved(recyclerView, from, to, itemCount);
        invalidateLayout();
    }

    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        invalidateLayout();
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, RecyclerView.Recycler recycler) {
        super.onDetachedFromWindow(view, recycler);
        invalidateLayout();
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        if (lastItemCount >= 0 && (calculatedWidth != getWidth() || lastSpanCount != getSpanCount())) {
            getSpanSizeLookup().invalidateSpanIndexCache();
            getSpanSizeLookup().invalidateSpanGroupIndexCache();
        }
        super.onLayoutChildren(recycler, state);
    }

    private void invalidateLayout() {
        dataVersion++;
        getSpanSizeLookup().invalidateSpanIndexCache();
        getSpanSizeLookup().invalidateSpanGroupIndexCache();
    }

    private void prepareLayout(float viewPortAvailableSize, int itemsCount, int spanCount) {
        if (viewPortAvailableSize == 0) {
            viewPortAvailableSize = 100;
        }
        itemSpans.clear();
        itemsToRow.clear();
        rowsCount = 0;
        firstRowMax = 0;

        if (itemsCount == 0) {
            return;
        }

        final int preferredRowSize = AndroidUtilities.dp(100);

        int spanLeft = spanCount;
        int currentItemsInRow = 0;
        int currentItemsSpanAmount = 0;
        for (int a = 0, N = itemsCount + (lastRowFullWidth ? 1 : 0); a < N; a++) {
            if (a == 0 && firstRowFullWidth) {
                itemSpans.put(a, itemSpans.get(a) + spanCount);
                itemsToRow.put(0, rowsCount);
                rowsCount++;
                currentItemsSpanAmount = 0;
                currentItemsInRow = 0;
                spanLeft = spanCount;
                continue;
            }
            Size size = a < itemsCount ? sizeForItem(a) : null;
            int requiredSpan;
            boolean moveToNewRow;
            if (size == null) {
                moveToNewRow = currentItemsInRow != 0;
                requiredSpan = spanCount;
            } else {
                requiredSpan = Math.max(1, Math.min(spanCount, (int) Math.floor(spanCount * (size.width / size.height * preferredRowSize / viewPortAvailableSize))));
                moveToNewRow = spanLeft < requiredSpan || requiredSpan > 33 && spanLeft < requiredSpan - 15;
                if (size.full) {
                    itemSpans.put(a, spanLeft);
                    rowsCount++;
                    currentItemsSpanAmount = 0;
                    currentItemsInRow = 0;
                    spanLeft = spanCount;
                    continue;
                }
            }
            if (moveToNewRow) {
                if (spanLeft != 0 && currentItemsInRow != 0) {
                    int spanPerItem = spanLeft / currentItemsInRow;
                    for (int start = a - currentItemsInRow, b = start; b < start + currentItemsInRow; b++) {
                        if (b == start + currentItemsInRow - 1) {
                            itemSpans.put(b, itemSpans.get(b) + spanLeft);
                        } else {
                            itemSpans.put(b, itemSpans.get(b) + spanPerItem);
                        }
                        spanLeft -= spanPerItem;
                    }
                    itemsToRow.put(a - 1, rowsCount);
                }
                if (a == itemsCount) {
                    break;
                }
                rowsCount++;
                currentItemsSpanAmount = 0;
                currentItemsInRow = 0;
                spanLeft = spanCount;
            } else {
                if (spanLeft < requiredSpan) {
                    requiredSpan = spanLeft;
                }
            }
            if (rowsCount == 0) {
                firstRowMax = Math.max(firstRowMax, a);
            }
            if (a == itemsCount - 1 && !lastRowFullWidth) {
                itemsToRow.put(a, rowsCount);
            }
            currentItemsSpanAmount += requiredSpan;
            currentItemsInRow++;
            spanLeft -= requiredSpan;

            itemSpans.put(a, requiredSpan);
        }
        rowsCount++;
    }

    private Size sizeForItem(int i) {
        return fixSize(getSizeForItem(i));
    }

    protected Size fixSize(Size size) {
        if (size == null) {
            return null;
        }
        if (size.width == 0) {
            size.width = 100;
        }
        if (size.height == 0) {
            size.height = 100;
        }
        float aspect = size.width / size.height;
        if (aspect > 4.0f || aspect < 0.2f) {
            size.height = size.width = Math.max(size.width, size.height);
        }
        return size;
    }

    protected Size getSizeForItem(int i) {
        return new Size(100, 100);
    }

    private void checkLayout() {
        checkLayout(getWidth());
    }

    private void checkLayout(int width) {
        final int itemsCount = getFlowItemCount();
        final int spanCount = getSpanCount();
        final long version = dataVersion;
        if (lastItemCount != itemsCount || calculatedWidth != width || lastSpanCount != spanCount || calculatedDataVersion != version) {
            getSpanSizeLookup().invalidateSpanIndexCache();
            getSpanSizeLookup().invalidateSpanGroupIndexCache();
            prepareLayout(width, itemsCount, spanCount);
            calculatedWidth = width;
            lastItemCount = itemsCount;
            lastSpanCount = spanCount;
            calculatedDataVersion = version;
        }
    }

    public int getSpanSizeForItem(int i) {
        checkLayout();
        return itemSpans.get(i);
    }

    public int getRowsCount(int width) {
        checkLayout(width);
        return rowsCount;
    }

    public boolean isLastInRow(int i) {
        checkLayout();
        return itemsToRow.get(i, Integer.MAX_VALUE) != Integer.MAX_VALUE;
    }

    public boolean isFirstRow(int i) {
        checkLayout();
        return i <= firstRowMax;
    }

    protected int getFlowItemCount() {
        return getItemCount();
    }

    @Override
    public int getRowCountForAccessibility(RecyclerView.Recycler recycler, RecyclerView.State state) {
        return state.getItemCount();
    }

    @Override
    public int getColumnCountForAccessibility(RecyclerView.Recycler recycler, RecyclerView.State state) {
        return 1;
    }
}
