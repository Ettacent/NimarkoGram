package app.nimarkogram.messenger.notifications;

import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;

final class NotificationScrollInset implements ViewTreeObserver.OnPreDrawListener {
    private final ScrollView scroll;
    private int applied;
    private int writtenTop = -1;
    private boolean originalClip;
    private boolean active;
    private ViewTreeObserver observer;
    private int targetScrollY;
    private int targetScrollX;

    @Override
    public boolean onPreDraw() {
        if (observer == null || scroll.isLayoutRequested()) return true;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        scroll.scrollTo(targetScrollX, targetScrollY);
        return true;
    }

    NotificationScrollInset(ScrollView scroll) {
        this.scroll = scroll;
    }

    boolean apply(int height, int anchor, float visibility) {
        if (observer != null && !scroll.isLayoutRequested()) onPreDraw();
        if (!(scroll.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return false;
        if (scroll.getPaddingTop() != writtenTop) applied = 0;
        int base = scroll.getPaddingTop() - applied;
        int margin = ((ViewGroup.MarginLayoutParams) scroll.getLayoutParams()).topMargin;
        int next = height + Math.round(Math.max(0, anchor - margin - base) * visibility);
        if (next > 0 && !active) {
            originalClip = scroll.getClipToPadding();
            active = true;
            scroll.setClipToPadding(false);
        }
        boolean changed = next != applied;
        if (changed) {
            int delta = next - applied;
            int oldScroll = observer != null ? targetScrollY : scroll.getScrollY();
            targetScrollX = scroll.getScrollX();
            targetScrollY = oldScroll > 0 ? Math.max(0, oldScroll + delta) : 0;
            applied = next;
            writtenTop = base + next;
            if (scroll instanceof NotificationSectionsScrollView) {
                ((NotificationSectionsScrollView) scroll).prepareNotificationInset();
            }
            scroll.setPadding(scroll.getPaddingLeft(), writtenTop, scroll.getPaddingRight(), scroll.getPaddingBottom());
            if (observer == null || !observer.isAlive()) {
                observer = scroll.getViewTreeObserver();
                observer.addOnPreDrawListener(this);
            }
        }
        if (next == 0 && active) {
            scroll.setClipToPadding(originalClip);
            active = false;
        }
        return changed;
    }

    void release() {
        apply(0, 0, 0);
    }
}
