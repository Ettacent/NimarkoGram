package org.telegram.utils.glass;

import android.graphics.RectF;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.postdrawcompat.OnPostDrawListener;
import org.telegram.messenger.postdrawcompat.ViewOnPostDrawListener;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.utils.glass.positions.GlassPositionsArray;
import org.telegram.utils.glass.positions.GlassPositionsMerger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class GlassEngine {
    public static final int FLAG_INVALIDATED_SCROLL = 1;
    public static final int FLAG_INVALIDATED_SCROLL_EDGES = 1 << 1;
    public static final int FLAG_INVALIDATED_POSITIONS = 1 << 2;
    public static final int FLAG_INVALIDATED_OTHER = 1 << 3;
    public static final int FLAG_INVALIDATED_THEMED = 1 << 4;



    private GlassInvalidationListener glassInvalidationListener;

    public interface GlassInvalidationListener {
        void onGlassInvalidated(int flags);
    }

    public void setGlassInvalidationListener(GlassInvalidationListener glassInvalidationListener) {
        this.glassInvalidationListener = glassInvalidationListener;
    }



    private final OnPostDrawListener onPostDrawListener = this::onPostDraw;

    private final ArrayList<InvalidationCondition> additionalInvalidationCondition = new ArrayList<>();

    private final ArrayList<ViewPositionState> viewPositionStates = new ArrayList<>();
    private boolean positionsDirty;

    private GlassPositionsMerger positionsMerger = GlassPositionsMerger.DEFAULT;

    public void setPositionsMerger(GlassPositionsMerger positionsMerger) {
        this.positionsMerger = positionsMerger;
        positionsDirty = true;
    }

    public void registerDrawable(@NonNull View view, @NonNull BlurredBackgroundDrawable drawable) {

        for (int i = viewPositionStates.size() - 1; i >= 0; i--) {
            ViewPositionState state = viewPositionStates.get(i);
            View registeredView = state.view.get();
            BlurredBackgroundDrawable registeredDrawable = state.drawable.get();
            if (registeredView == null || registeredDrawable == null) {
                viewPositionStates.remove(i);
                positionsDirty = true;
            } else if (registeredView == view && registeredDrawable == drawable) {
                return;
            }
        }
        viewPositionStates.add(new ViewPositionState(view, drawable));
        positionsDirty = true;
        if (rootView != null) rootView.invalidate();
    }

    public void unregisterDrawable(@NonNull View view, @NonNull BlurredBackgroundDrawable drawable) {
        for (int i = viewPositionStates.size() - 1; i >= 0; i--) {
            ViewPositionState state = viewPositionStates.get(i);
            if (state.view.get() == view && state.drawable.get() == drawable) {

                state.view.clear();
                state.drawable.clear();
                viewPositionStates.remove(i);
                positionsDirty = true;
            }
        }
        if (positionsDirty && rootView != null) rootView.invalidate();
    }



    private long scrollGenerationId;
    private long scrollEdgesGenerationId;
    private long otherGenerationId;
    private boolean themedInvalidated;

    public interface InvalidationCondition {
        boolean check();
    }


    private View rootView;

    public void setRoot(View root) {
        if (rootView != root) {
            if (rootView != null) {
                ViewOnPostDrawListener.removeListener(rootView, onPostDrawListener);
            }
            if (root != null) {
                ViewOnPostDrawListener.addListener(root, onPostDrawListener);
            }
            rootView = root;
            positionsDirty = true;
            allVisibleDrawablesWithDisplayListPositions.clear();
            allVisibleDrawablesWithDisplayListPositionsMerged.clear();
            for (ViewPositionState state : viewPositionStates) {
                state.visible = false;
                state.invalidated = true;
            }
        }
    }

    public void addScrolledView(ViewPager viewPager) {
        if (viewPager == null) {
            return;
        }

        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                scrollGenerationId++;
            }
        });
    }

    public void addScrolledView(RecyclerListView recyclerListView) {
        if (recyclerListView == null) {
            return;
        }

        recyclerListView.addEdgeEffectListener((direction, isVisible) -> scrollEdgesGenerationId++);
        recyclerListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                scrollGenerationId++;
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                scrollGenerationId++;
            }
        });
    }

    public void addAdditionalInvalidationCondition(InvalidationCondition condition) {
        additionalInvalidationCondition.add(condition);
    }

    public void invalidate() {
        otherGenerationId++;
    }

    private long lastScrollGenerationId;

    private long lastScrollEdgesGenerationId;

    private long lastOtherGenerationId;

    private void onPostDraw() {

        if (rootView == null) return;
        final boolean hasInvalidatedPositionsOrVisibility = checkDrawablePositionsIfNeeded();
        final boolean hasColorSourcesUpdates = checkColorSources();

        for (InvalidationCondition condition : additionalInvalidationCondition) {
            if (condition.check()) {
                otherGenerationId++;
            }
        }

        int flags = 0;

        if (hasInvalidatedPositionsOrVisibility) {
            flags |= FLAG_INVALIDATED_POSITIONS;
        }

        if (hasColorSourcesUpdates) {
            flags |= FLAG_INVALIDATED_THEMED;
        }

        if (lastOtherGenerationId != otherGenerationId) {
            lastOtherGenerationId = otherGenerationId;
            flags |= FLAG_INVALIDATED_OTHER;
        }

        if (lastScrollEdgesGenerationId != scrollEdgesGenerationId) {
            lastScrollEdgesGenerationId = scrollEdgesGenerationId;
            flags |= FLAG_INVALIDATED_SCROLL_EDGES;
        }

        if (lastScrollGenerationId != scrollGenerationId) {
            lastScrollGenerationId = scrollGenerationId;
            flags |= FLAG_INVALIDATED_SCROLL;
        }

        if (flags != 0) {
            if (glassInvalidationListener != null) {
                glassInvalidationListener.onGlassInvalidated(flags);
            }



            for (ViewPositionState state : new ArrayList<>(viewPositionStates)) {
                final View view = state.view.get();
                final BlurredBackgroundDrawable drawable = state.drawable.get();
                if (view == null || drawable == null) continue;
                if (state.visible && state.invalidated) {
                    state.invalidated = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && drawable instanceof BlurredBackgroundDrawableRenderNode) {
                        drawable.updateDisplayList();
                    } else {
                        drawable.invalidateSelf();
                    }
                }
            }
        }
    }





    private final RectF tmpPositionRect = new RectF();

    private int lastRootWidth, lastRootHeight;

    private final GlassPositionsArray allVisibleDrawablesWithDisplayListPositions = new GlassPositionsArray();

    private final GlassPositionsArray allVisibleDrawablesWithDisplayListPositionsMerged = new GlassPositionsArray();

    private final GlassPositionsArray output = new GlassPositionsArray();

    private boolean checkDrawablePositionsIfNeeded() {
        boolean hasInvalidatedPositionsOrVisibility = positionsDirty;
        positionsDirty = false;

        final int W = rootView.getWidth();
        final int H = rootView.getHeight();
        if (lastRootWidth != W || lastRootHeight != H) {
            lastRootWidth = W;
            lastRootHeight = H;
            hasInvalidatedPositionsOrVisibility = true;
        }

        for (int i = viewPositionStates.size() - 1; i >= 0; i--) {
            final ViewPositionState state = viewPositionStates.get(i);
            final View view = state.view.get();
            final BlurredBackgroundDrawable drawable = state.drawable.get();
            if (view == null || drawable == null) {
                viewPositionStates.remove(i);
                hasInvalidatedPositionsOrVisibility = true;
                continue;
            }
            if (!view.isAttachedToWindow()
                    || !ViewPositionWatcher.computeRectInParent(view, rootView, tmpPositionRect)) {


                if (state.visible) {
                    state.visible = false;
                    state.invalidated = true;
                    hasInvalidatedPositionsOrVisibility = true;
                }
                continue;
            }

            if (!state.viewPositionRelativeRoot.equals(tmpPositionRect)) {
                state.viewPositionRelativeRoot.set(tmpPositionRect);
                state.invalidated = true;
                drawable.setSourceOffset(state.viewPositionRelativeRoot.left, state.viewPositionRelativeRoot.top);
                hasInvalidatedPositionsOrVisibility = true;
            }

            tmpPositionRect.set(drawable.getBounds());
            if (!state.drawableBoundsRelativeView.equals(tmpPositionRect)) {
                state.drawableBoundsRelativeView.set(tmpPositionRect);
                state.invalidated = true;
                hasInvalidatedPositionsOrVisibility = true;
            }

            tmpPositionRect.offset(state.viewPositionRelativeRoot.left, state.viewPositionRelativeRoot.top);
            if (!state.drawableBoundsRelativeRoot.equals(tmpPositionRect)) {
                state.drawableBoundsRelativeRoot.set(tmpPositionRect);
                state.invalidated = true;
                hasInvalidatedPositionsOrVisibility = true;
            }

            tmpPositionRect.set(state.drawableBoundsRelativeRoot);
            tmpPositionRect.inset(-drawable.getOutsetX(), -drawable.getOutsetY());
            if (!state.drawableBoundsRelativeRootWithOutset.equals(tmpPositionRect)) {
                state.drawableBoundsRelativeRootWithOutset.set(tmpPositionRect);
                state.invalidated = true;
                hasInvalidatedPositionsOrVisibility = true;
            }

            final boolean isViewVisible = !state.drawableBoundsRelativeRoot.isEmpty()
                && view.isAttachedToWindow()
                && state.drawableBoundsRelativeRoot.intersects(0, 0, W, H)
                && drawable.getAlpha() > 0
                && view.getVisibility() == View.VISIBLE
                && view.getAlpha() > 0
                && view.getScaleX() != 0
                && view.getScaleY() != 0;

            final boolean hasDisplayList = isViewVisible && drawable.hasDisplayList();
            if (state.visible != isViewVisible || isViewVisible && !hasDisplayList) {
                state.visible = isViewVisible;
                state.invalidated = true;
                hasInvalidatedPositionsOrVisibility = true;
            }
        }

        if (hasInvalidatedPositionsOrVisibility) {
            allVisibleDrawablesWithDisplayListPositions.clear();
            for (ViewPositionState state : viewPositionStates) {
                if (state.visible) {
                    allVisibleDrawablesWithDisplayListPositions.add(state.drawableBoundsRelativeRootWithOutset);
                }
            }
            positionsMerger.merge(
                allVisibleDrawablesWithDisplayListPositions,
                allVisibleDrawablesWithDisplayListPositionsMerged);
        }

        return hasInvalidatedPositionsOrVisibility;
    }

    public GlassPositionsArray getAllVisibleDrawablesWithDisplayListPositionsMerged() {
        output.set(allVisibleDrawablesWithDisplayListPositionsMerged);
        return output;
    }





    private static class ViewPositionState {

        private final WeakReference<View> view;
        private final WeakReference<BlurredBackgroundDrawable> drawable;
        private final RectF viewPositionRelativeRoot = new RectF();
        private final RectF drawableBoundsRelativeView = new RectF();
        private final RectF drawableBoundsRelativeRoot = new RectF();
        private final RectF drawableBoundsRelativeRootWithOutset = new RectF();
        private boolean invalidated;
        private boolean visible;

        private ViewPositionState(@NonNull View view, @NonNull BlurredBackgroundDrawable drawable) {
            this.view = new WeakReference<>(view);
            this.drawable = new WeakReference<>(drawable);
        }
    }





    private final ArrayList<ThemedSourceColorState> themedSourceColorStates = new ArrayList<>();

    public void addColorSource(BlurredBackgroundSourceColor sourceColor, ThemedColorDelegate delegate) {
        themedSourceColorStates.add(new ThemedSourceColorState(sourceColor, delegate));
    }

    private boolean checkColorSources() {
        boolean hasUpdates = false;
        for (int a = 0, N = themedSourceColorStates.size(); a < N; a++) {
            final ThemedSourceColorState state = themedSourceColorStates.get(a);
            final int color = state.colorDelegate.getThemedColor();
            final int oldColor = state.sourceColor.getColor();
            if (oldColor != color) {
                state.sourceColor.setColor(color);
                hasUpdates = true;
            }
        }

        return hasUpdates;
    }

    public interface ThemedColorDelegate {
        int getThemedColor();
    }

    private static class ThemedSourceColorState {
        private final BlurredBackgroundSourceColor sourceColor;
        private final ThemedColorDelegate colorDelegate;

        private ThemedSourceColorState(BlurredBackgroundSourceColor sourceColor, ThemedColorDelegate colorDelegate) {
            this.sourceColor = sourceColor;
            this.colorDelegate = colorDelegate;
        }
    }
}
