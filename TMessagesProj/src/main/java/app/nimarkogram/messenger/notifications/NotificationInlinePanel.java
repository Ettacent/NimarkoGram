package app.nimarkogram.messenger.notifications;

import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.LayoutHelper;

public final class NotificationInlinePanel extends AnimatedLinearLayout implements ViewTreeObserver.OnPreDrawListener {
    private final BaseFragment fragment;
    private final FrameLayout root;
    private final View[] contents;
    private final int[] offsets;
    private final int[] location = new int[2];
    private ViewTreeObserver observer;
    private int reserved;
    private int anchorTop;
    private float clipHeight = -1;

    public NotificationInlinePanel(BaseFragment fragment, FrameLayout root, View... contents) {
        super(root.getContext());
        this.fragment = fragment;
        this.root = root;
        this.contents = contents;
        offsets = new int[contents.length];
        setOrientation(VERTICAL);
        setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4));
        setOnAnimatedHeightChangedListener(this::updateReservedHeight);
        root.addView(this, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
    }

    private void updateReservedHeight() {
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
            int overlap = Math.max(0, anchorTop - (params.topMargin - offsets[i]) - child.getPaddingTop());
            int offset = next + Math.round(overlap * getLayoutVisibility());
            if (offset != offsets[i]) {
                params.topMargin += offset - offsets[i];
                offsets[i] = offset;
                child.setLayoutParams(params);
                changed = true;
            }
        }
        if (changed) root.requestLayout();
    }

    @Override public boolean onPreDraw() {
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
