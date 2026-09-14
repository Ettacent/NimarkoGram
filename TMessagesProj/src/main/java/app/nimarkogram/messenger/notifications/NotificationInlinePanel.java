package app.nimarkogram.messenger.notifications;

import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.LayoutHelper;
import java.util.function.IntSupplier;
import java.util.function.IntConsumer;

public final class NotificationInlinePanel extends AnimatedLinearLayout implements ViewTreeObserver.OnPreDrawListener {
    public interface CompactContent {
        int getCompactHeight();
        float getCompactVisibleHeight();
    }
    private final BaseFragment fragment;
    private final FrameLayout root;
    private final View[] contents;
    private final int[] offsets;
    private final FrameLayout.LayoutParams[] contentParams;
    private final int[] location = new int[2];
    private ViewTreeObserver observer;
    private int reserved;
    private int anchorTop;
    private float clipHeight = -1;
    private IntSupplier overlayAnchor;
    private IntConsumer reservationListener;
    private Runnable reservationLayout;
    private IntConsumer overlayPositionListener;
    public NotificationInlinePanel withOverlayPositionListener(IntConsumer listener) {
        overlayPositionListener = listener;
        return this;
    }
    public NotificationInlinePanel withCompactReservation(IntConsumer listener, Runnable layout) {
        reservationListener = listener;
        reservationLayout = layout;
        return this;
    }
    private void updateCompactReservation() {
        if (reservationListener == null) return;
        float height = 0f;
        for (int i = 0; i < getEntriesCount(); i++) {
            var entry = getEntry(i);
            if (!(entry.item.view instanceof CompactContent)) continue;
            CompactContent content = (CompactContent) entry.item.view;
            int compact = content.getCompactHeight();
            float visible = content.getCompactVisibleHeight();
            float coverage = compact > 0 ? Math.min(1f, visible / compact) : 0f;
            height += entry.getVisibility() * (visible + (getPaddingTop() + getPaddingBottom()) * coverage);
        }
        reservationListener.accept(Math.round(height));
    }
    public NotificationInlinePanel withOverlayAnchor(IntSupplier anchor) {
        overlayAnchor = anchor;
        return this;
    }
    public boolean isOverlay() {
        return contents.length == 0;
    }
    private int overlayTop() {
        View content = fragment.getFragmentView();
        ActionBar bar = fragment.getActionBar();
        int top = content != null && content.getParent() == root ? content.getTop() : 0;
        int toolbarBottom = top + ActionBar.getCurrentActionBarHeight()
                + (bar != null && bar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0);
        if (overlayAnchor == null) return toolbarBottom;
        int desired = Math.max(toolbarBottom, overlayAnchor.getAsInt());
        int maxTop = Math.max(toolbarBottom, root.getHeight() - AndroidUtilities.dp(132) - getPaddingTop() - getPaddingBottom());
        return Math.min(desired, maxTop);
    }
    public int getAvailableContentHeight(int viewportBottom) {
        int bottom = Math.min(root.getHeight(), viewportBottom);
        int available = bottom - overlayTop() - getPaddingTop() - getPaddingBottom() - AndroidUtilities.dp(64);
        int limit = Math.min(AndroidUtilities.dp(260), Math.max(AndroidUtilities.dp(120), Math.round(root.getHeight() * .4f)));
        return Math.max(AndroidUtilities.dp(68), Math.min(limit, available));
    }

    public NotificationInlinePanel(BaseFragment fragment, FrameLayout root, View... contents) {
        super(root.getContext());
        this.fragment = fragment;
        this.root = root;
        this.contents = contents;
        offsets = new int[contents.length];
        contentParams = new FrameLayout.LayoutParams[contents.length];
        setOrientation(VERTICAL);
        setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4));
        setOnAnimatedHeightChangedListener(this::updateReservedHeight);
        root.addView(this, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
    }
    public void release() {
        setOnAnimatedHeightChangedListener(null);
        if (overlayPositionListener != null) overlayPositionListener.accept(-1);
        overlayPositionListener = null;
        if (reservationListener != null) reservationListener.accept(0);
        reservationListener = null;
        reservationLayout = null;
        for (int i = 0; i < contents.length; i++) {
            View child = contents[i];
            if (child.getParent() == root && child.getLayoutParams() == contentParams[i]) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
                params.topMargin -= offsets[i];
                child.setLayoutParams(params);
            }
            offsets[i] = 0;
        }
        root.removeView(this);
    }

    private void updateReservedHeight() {
        updateCompactReservation();
        float height = getAnimatedHeightWithPadding();
        if (clipHeight != height) {
            clipHeight = height;
            invalidate();
        }
        int next = Math.round(height);
        boolean changed = reserved != next;
        reserved = next;
        for (int i = 0; i < contents.length; i++) {
            View child = contents[i];
            if (child.getParent() != root || !(child.getLayoutParams() instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
            if (contentParams[i] != params) {
                contentParams[i] = params;
                offsets[i] = 0;
            }
            int overlap = Math.max(0, anchorTop - (params.topMargin - offsets[i]) - child.getPaddingTop());
            int offset = next + Math.round(overlap * getLayoutVisibility());
            if (offset != offsets[i]) {
                params.topMargin += offset - offsets[i];
                offsets[i] = offset;
                child.setLayoutParams(params);
                changed = true;
            }
        }
        if (changed) {
            if (isOverlay()) {
                if (fragment.getFragmentView() != null) fragment.getFragmentView().invalidate();
            } else {
                root.requestLayout();
            }
        }
    }

    @Override public boolean onPreDraw() {
        if (isOverlay()) {
            updateReservedHeight();
            if (reservationLayout != null) reservationLayout.run();
            anchorTop = overlayTop();
            if (getTranslationY() != anchorTop) setTranslationY(anchorTop);
            if (overlayPositionListener != null) overlayPositionListener.accept(anchorTop);
            return true;
        }
        int top = 0;
        View bar = fragment.getActionBar();
        if (bar != null && bar.isShown()) {
            bar.getLocationOnScreen(location);
            int bottom = location[1] + bar.getHeight();
            root.getLocationOnScreen(location);
            top = Math.max(0, bottom - location[1]);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
        anchorTop = top;
        if (params.topMargin != top) {
            params.topMargin = top;
            setLayoutParams(params);
        }
        updateReservedHeight();
        return true;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipRect(0, 0, getWidth(), Math.max(getHeight(), getAnimatedHeightWithPadding()));
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observer = getViewTreeObserver();
        observer.addOnPreDrawListener(this);
    }

    @Override protected void onDetachedFromWindow() {
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        super.onDetachedFromWindow();
    }
}
