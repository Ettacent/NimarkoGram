package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.util.ArrayMap;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;

public class RecyclerItemsEnterAnimator {

    private final RecyclerListView listView;
    private final ArrayMap<View, AlphaAnimation> alphaOwners = new ArrayMap<>();
    HashSet<View> ignoreView = new HashSet<>();
    boolean invalidateAlpha;
    boolean alwaysCheckItemsAlpha;
    public boolean animateAlphaProgressView = true;

    ArrayList<AnimatorSet> currentAnimations = new ArrayList<>();
    private final ArrayList<Animator> progressAnimations = new ArrayList<>();
    private ViewTreeObserver.OnPreDrawListener preDrawListener;
    private int pendingFrom = Integer.MAX_VALUE;
    private static class AlphaAnimation {
        final View view;
        final RecyclerView.ViewHolder holder;
        final RecyclerView.Adapter adapter;
        final long itemId;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        float alpha;
        float appliedAlpha = 1f;
        boolean applied;
        AlphaAnimation(View view, RecyclerView.ViewHolder holder, RecyclerView.Adapter adapter) {
            this.view = view;
            this.holder = holder;
            this.adapter = adapter;
            itemId = holder.getItemId();
        }
    }

    public RecyclerItemsEnterAnimator(RecyclerListView listView, boolean alwaysCheckItemsAlpha) {
        this.listView = listView;
        this.alwaysCheckItemsAlpha = alwaysCheckItemsAlpha;
        listView.setItemsEnterAnimator(this);
        listView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
            }
            @Override
            public void onChildViewDetachedFromWindow(View view) {
                AlphaAnimation owner = alphaOwners.get(view);
                if (owner != null) {
                    releaseAlpha(owner);
                    owner.animator.cancel();
                }
            }
        });
    }

    public void dispatchDraw() {
        if (invalidateAlpha || alwaysCheckItemsAlpha) {
            for (int i = alphaOwners.size() - 1; i >= 0; i--) {
                AlphaAnimation owner = alphaOwners.valueAt(i);
                View child = owner.view;
                if (child.getParent() != listView || listView.getAdapter() != owner.adapter
                        || listView.getChildViewHolder(child) != owner.holder
                        || owner.holder.getItemId() != owner.itemId
                        || listView.getChildAdapterPosition(child) == RecyclerView.NO_POSITION
                        || child.getAlpha() != owner.appliedAlpha) {
                    releaseAlpha(owner);
                    owner.animator.cancel();
                } else {
                    child.setAlpha(owner.alpha);
                    owner.appliedAlpha = owner.alpha;
                    owner.applied = true;
                }
            }
            invalidateAlpha = false;
        }
    }
    private void releaseAlpha(AlphaAnimation owner) {
        if (alphaOwners.get(owner.view) != owner) {
            return;
        }
        alphaOwners.remove(owner.view);
        if (owner.applied && owner.view.getAlpha() == owner.appliedAlpha) {
            owner.view.setAlpha(1f);
        }
    }

    public void showItemsAnimated(int from) {
        if (!listView.isAttachedToWindow()) {
            return;
        }
        final View finalProgressView = getProgressView();
        RecyclerView.LayoutManager layoutManager = listView.getLayoutManager();
        if (finalProgressView != null && layoutManager != null) {
            AlphaAnimation entrance = alphaOwners.remove(finalProgressView);
            if (entrance != null) {
                entrance.animator.cancel();
            }
            listView.removeView(finalProgressView);
            ignoreView.add(finalProgressView);
            listView.addView(finalProgressView);
            layoutManager.ignoreView(finalProgressView);
            Animator animator;
            if (animateAlphaProgressView) {
                animator = ObjectAnimator.ofFloat(finalProgressView, View.ALPHA, finalProgressView.getAlpha(), 0f);
            } else {
                animator = ValueAnimator.ofFloat(0f, 1f);
            }
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    progressAnimations.remove(animation);
                    finalProgressView.setAlpha(1f);
                    ignoreView.remove(finalProgressView);
                    layoutManager.stopIgnoringView(finalProgressView);
                    if (finalProgressView.getParent() == listView) {
                        listView.removeView(finalProgressView);
                    }
                }
            });
            progressAnimations.add(animator);
            animator.start();
            from--;
        }
        pendingFrom = Math.min(pendingFrom, Math.max(0, from));
        if (preDrawListener != null) {
            return;
        }
        preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (preDrawListener != this) {
                    return true;
                }
                listView.getViewTreeObserver().removeOnPreDrawListener(this);
                preDrawListener = null;
                int finalFrom = pendingFrom;
                pendingFrom = Integer.MAX_VALUE;
                if (!listView.isAttachedToWindow()) {
                    return true;
                }
                int n = listView.getChildCount();
                int height = listView.getMeasuredHeight();
                AnimatorSet animatorSet = new AnimatorSet();
                for (int i = 0; i < n; i++) {
                    View child = listView.getChildAt(i);
                    int position = listView.getChildAdapterPosition(child);
                    if (!ignoreView.contains(child) && position >= finalFrom
                            && !alphaOwners.containsKey(child) && child.getAlpha() == 1f) {
                        AlphaAnimation owner = new AlphaAnimation(child, listView.getChildViewHolder(child), listView.getAdapter());
                        alphaOwners.put(child, owner);
                        int s = Math.min(height, Math.max(0, child.getTop()));
                        int delay = height > 0 ? (int) ((s / (float) height) * 100) : 0;
                        ValueAnimator a = owner.animator;
                        a.addUpdateListener(valueAnimator -> {
                            if (alphaOwners.get(child) == owner) {
                                owner.alpha = (float) valueAnimator.getAnimatedValue();
                                invalidateAlpha = true;
                                listView.invalidate();
                            }
                        });
                        a.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                releaseAlpha(owner);
                            }
                        });
                        a.setStartDelay(delay);
                        a.setDuration(200);
                        animatorSet.playTogether(a);
                    }
                }
                if (animatorSet.getChildAnimations().isEmpty()) {
                    return true;
                }
                animatorSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        currentAnimations.remove(animatorSet);
                    }
                });
                currentAnimations.add(animatorSet);
                invalidateAlpha = true;
                listView.invalidate();
                animatorSet.start();
                return true;
            }
        };
        listView.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    public View getProgressView() {
        View progressView = null;
        int n = listView.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = listView.getChildAt(i);
            if (!ignoreView.contains(child) && listView.getChildAdapterPosition(child) >= 0 && child instanceof FlickerLoadingView) {
                progressView = child;
            }
        }
        return progressView;
    }

    public void onDetached() {
        cancel();
    }

    public void cancel() {
        if (preDrawListener != null) {
            listView.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
            preDrawListener = null;
        }
        pendingFrom = Integer.MAX_VALUE;
        for (Animator animation : new ArrayList<>(progressAnimations)) {
            animation.cancel();
        }
        progressAnimations.clear();
        if (!currentAnimations.isEmpty()) {
            ArrayList<AnimatorSet> animations = new ArrayList<>(currentAnimations);
            for (int i = 0; i < animations.size(); i++) {
                animations.get(i).cancel();
            }
        }
        currentAnimations.clear();
        while (!alphaOwners.isEmpty()) {
            releaseAlpha(alphaOwners.valueAt(alphaOwners.size() - 1));
        }
        listView.invalidate();
        invalidateAlpha = true;
    }
}
