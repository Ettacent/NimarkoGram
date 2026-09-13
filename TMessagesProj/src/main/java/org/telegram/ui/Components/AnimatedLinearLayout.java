package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.lerp;

import android.content.Context;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

import me.vkryl.android.animator.ListAnimator;
import me.vkryl.core.lambda.Destroyable;

public class AnimatedLinearLayout extends LinearLayout {
    public interface IndependentPanel {
        boolean isDirectResize();
        default float getLayoutCoverage() { return 1f; }
    }
    protected float getSharedBackgroundOffset() {
        float offset = Float.MAX_VALUE;
        boolean independent = false, shared = false;
        for (ListAnimator.Entry<Holder> entry : listAnimator) {
            if (entry.getVisibility() <= 0) continue;
            if (entry.item.view instanceof IndependentPanel) {
                independent = true;
            } else {
                shared = true;
                offset = Math.min(offset, entry.getRectF().top);
            }
        }
        return independent ? shared ? Math.max(0, offset) : -1 : 0;
    }
    private final HashMap<View, Holder> viewHolders = new HashMap<>();
    private final ArrayList<Holder> visibleHolders = new ArrayList<>();
    private ArrayList<Holder> trackedLayout;
    private final ListAnimator.Callback callback = animator -> {
        checkViewsVisibility();
        onItemsChanged();
    };

    private final ListAnimator<Holder> listAnimator = new ListAnimator<>(
        callback, CubicBezierInterpolator.EASE_OUT_QUINT, 420L);

    public AnimatedLinearLayout(Context context) {
        super(context);
    }

    public boolean isViewVisible(View child) {
        final Holder holder = viewHolders.get(child);
        return holder != null && holder.isVisible;
    }

    public void setPriority(View child, int priority) {
        final Holder holder = viewHolders.get(child);
        if (holder != null) {
            holder.priority = priority;
        }
    }
    public void setTrackChildSize(View child) {
        Holder holder = viewHolders.get(child);
        if (holder != null) {
            holder.trackSize = true;
            if (trackedLayout == null) trackedLayout = new ArrayList<>();
        }
    }

    public void setDebugName(View child, String tag) {
        final Holder holder = viewHolders.get(child);
        if (holder != null) {
            holder.tag = tag;
        }
    }

    public void setViewVisible(View child, boolean visible) {
        setViewVisible(child, visible, true);
    }

    private boolean skipNextAnimation;

    public void setViewVisible(View child, boolean visible, boolean animated) {
        if (child == null) {
            return;
        }

        final Holder holder = viewHolders.get(child);
        if (holder != null && holder.isVisible != visible) {
            holder.isVisible = visible;
            if (visible) {
                holder.view.setVisibility(VISIBLE);
            }
            if (!visible && !holder.hasInAnimator){
                holder.view.setVisibility(GONE);
            }
            if (!animated) {
                skipNextAnimation = true;
            }
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        calculateTotalSizesAfterMeasure();
    }

    protected final void calculateTotalSizesAfterMeasure() {
        totalHeight = 0;
        totalWidth = 0;
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View view = getChildAt(a);
            final Holder holder = viewHolders.get(view);

            if (view.getVisibility() == VISIBLE && holder != null && holder.isVisible) {
                totalWidth += view.getMeasuredWidth();
                totalHeight += view.getMeasuredHeight();
            }
        }
    }

    private int totalWidth;
    private int totalHeight;

    public int getSumWidthOfAllVisibleChild() {
        return totalWidth;
    }

    public int getSumHeightOfAllVisibleChild() {
        return totalHeight;
    }

    public float getAnimatedHeightWithPadding(float padding) {
        return getMetadata().getTotalHeight() + (padding) * getLayoutVisibility();
    }
    public float getLayoutVisibility() {
        boolean independent = false;
        float coverage = 0f;
        for (ListAnimator.Entry<Holder> entry : listAnimator) {
            float factor = entry.getVisibility();
            if (entry.item.view instanceof IndependentPanel) {
                independent = true;
                factor *= ((IndependentPanel) entry.item.view).getLayoutCoverage();
            }
            coverage += factor;
        }
        return independent ? Math.min(getMetadata().getTotalVisibility(), Math.max(0f, coverage))
                : getMetadata().getTotalVisibility();
    }

    public float getAnimatedHeightWithPadding() {
        return getAnimatedHeightWithPadding(getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        visibleHolders.clear();
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View view = getChildAt(a);
            final Holder holder = viewHolders.get(view);
            if (holder == null) {
                continue;
            }

            holder.order = a;
            if (view.getVisibility() == VISIBLE && holder.isVisible) {
                visibleHolders.add(holder);
            }
        }
        Collections.sort(visibleHolders, comparator);
        boolean sizeChanged = false;
        boolean directResize = false;
        boolean otherSizeChanged = false;
        for (Holder holder : visibleHolders) {
            if (trackedLayout != null) {
                boolean resized = holder.width != holder.view.getMeasuredWidth() || holder.height != holder.view.getMeasuredHeight();
                if (holder.trackSize) sizeChanged |= resized;
                else otherSizeChanged |= resized;
                if (resized && holder.view instanceof IndependentPanel) directResize |= ((IndependentPanel) holder.view).isDirectResize();
                holder.width = holder.view.getMeasuredWidth();
                holder.height = holder.view.getMeasuredHeight();
            }
        }
        boolean sameItems = trackedLayout != null && trackedLayout.equals(visibleHolders);
        if (!sameItems || skipNextAnimation || otherSizeChanged) {
            listAnimator.reset(visibleHolders, !skipNextAnimation);
        }
        else if (sizeChanged) {
            if (directResize && !listAnimator.isAnimating()) listAnimator.measureImpl(false);
            else listAnimator.measure(true);
        }
        if (trackedLayout != null) {
            trackedLayout.clear();
            trackedLayout.addAll(visibleHolders);
        }
        for (Holder holder : visibleHolders) {
            holder.hasInAnimator = true;
        }

        skipNextAnimation = false;
        checkViewsVisibility();
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        child.setVisibility(GONE);
        viewHolders.put(child, new Holder(child));
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        viewHolders.remove(child);
    }

    private Runnable onAnimatedHeightChanged;

    public void setOnAnimatedHeightChangedListener(Runnable onAnimatedHeightChanged) {
        this.onAnimatedHeightChanged = onAnimatedHeightChanged;
    }

    private float lastAnimatedHeight;
    private float lastAnimatedVisibility;

    private void checkViewsVisibility() {
        for (ListAnimator.Entry<Holder> entry : listAnimator) {
            final View view = entry.item.view;
            final RectF pos = entry.getRectF();
            if (getOrientation() == VERTICAL) {
                view.setTranslationY((getPaddingTop() + pos.top) - view.getTop());
            } else {
                view.setTranslationX((getPaddingLeft() + pos.left) - view.getLeft());
            }

            final float factor = entry.getVisibility();
            setChildVisibilityFactor(view, factor);
        }

        final float animatedHeight = getMetadata().getTotalHeight();
        final float animatedVisibility = getLayoutVisibility();
        if (lastAnimatedHeight != animatedHeight || lastAnimatedVisibility != animatedVisibility) {
            lastAnimatedHeight = animatedHeight;
            lastAnimatedVisibility = animatedVisibility;
            if (onAnimatedHeightChanged != null) {
                onAnimatedHeightChanged.run();
            }
        }
    }

    protected void setChildVisibilityFactor(View view, float factor) {
        final float s = lerp(0.95f, 1f, factor);
        view.setAlpha(factor);
        view.setScaleX(s);
        view.setScaleY(s);
    }

    public ListAnimator.Metadata getMetadata() {
        return listAnimator.getMetadata();
    }

    public boolean isAnimating() {
        return listAnimator.isAnimating();
    }

    protected void onItemsChanged() {

    }

    protected int getEntriesCount() {
        return listAnimator.size();
    }

    protected ListAnimator.Entry<Holder> getEntry(int index) {
        return listAnimator.getEntry(index);
    }

    private static final Comparator<Holder> comparator = Comparator.comparingInt((Holder it) -> it.priority)
        .thenComparingInt(it -> it.order);

    protected static class Holder implements ListAnimator.Measurable, Destroyable {
        public final View view;
        private boolean isVisible;
        private boolean hasInAnimator;
        private String tag;
        private int priority;
        private int order;
        private boolean trackSize;
        private int width, height;

        public Holder(@NonNull View view) {
            this.view = view;
        }

        @Override
        public void performDestroy() {
            if (!isVisible) {
                view.setVisibility(View.GONE);
            }
            hasInAnimator = false;
        }

        @Override
        public int hashCode() {
            return view.hashCode();
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof Holder) {
                return view.equals(((Holder) obj).view);
            }
            return false;
        }

        @Override
        public int getWidth() {
            return view.getMeasuredWidth();
        }

        @Override
        public int getHeight() {
            return view.getMeasuredHeight();
        }
    }
}
