package androidx.viewpager.widget;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;







public abstract class EmojiViewPager extends ViewPager {
    private int pageScrollState;
    private boolean finishingPageTransition;

    public EmojiViewPager(Context context) {
        super(context);
    }

    public abstract boolean isPageMotionEnabled();

    public boolean isPageTransitionRunning() {
        return pageScrollState != SCROLL_STATE_IDLE;
    }

    @Override
    void setScrollState(int state) {
        pageScrollState = state;
        super.setScrollState(state);
    }

    @Override
    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always, int velocity) {
        smoothScroll &= !finishingPageTransition && isPageMotionEnabled();
        super.setCurrentItemInternal(item, smoothScroll,
                always || (!smoothScroll && isPageTransitionRunning()), velocity);
        if (!smoothScroll) {

            setScrollState(SCROLL_STATE_IDLE);
        }
    }

    @Override
    void smoothScrollTo(int x, int y, int velocity) {


        if (!finishingPageTransition && isPageMotionEnabled()) {
            super.smoothScrollTo(x, y, velocity);
        } else {
            super.setCurrentItemInternal(getCurrentItem(), false, true, 0);
            setScrollState(SCROLL_STATE_IDLE);
        }
    }

    public void finishPageTransition() {
        if (finishingPageTransition || !isPageTransitionRunning()) {
            return;
        }
        final int target = getCurrentItem();
        finishingPageTransition = true;
        try {
            if (isFakeDragging()) {
                endFakeDrag();
            }

            final long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            try {
                super.onTouchEvent(cancel);
            } finally {
                cancel.recycle();
            }
            super.setCurrentItemInternal(target, false, true, 0);
            setScrollState(SCROLL_STATE_IDLE);
        } finally {
            finishingPageTransition = false;
        }
    }

    @Override
    public void computeScroll() {
        if (pageScrollState == SCROLL_STATE_SETTLING && !isPageMotionEnabled()) {
            finishPageTransition();
        }
        super.computeScroll();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            finishPageTransition();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            finishPageTransition();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        finishPageTransition();
        super.onDetachedFromWindow();
    }
}
