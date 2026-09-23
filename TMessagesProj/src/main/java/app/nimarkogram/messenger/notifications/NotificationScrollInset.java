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
    private int insetWidth;
    private int insetHeight;

    @Override
    public boolean onPreDraw() {
        if (observer == null || scroll.isLayoutRequested()) return true;
        cancelPendingScroll();
        if (scroll.getPaddingTop() != writtenTop || scroll.getWidth() != insetWidth
                || scroll.getHeight() != insetHeight) {
            finishInsetFocus();
            return true;
        }
        scroll.scrollTo(targetScrollX, targetScrollY);
        return true;
    }
    private void cancelPendingScroll() {
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
    }
    private void finishInsetFocus() {
        if (scroll instanceof NotificationSectionsScrollView) {
            ((NotificationSectionsScrollView) scroll).finishNotificationInset();
        }
    }

    NotificationScrollInset(ScrollView scroll) {
        this.scroll = scroll;
    }

    boolean apply(int height, int anchor, float visibility) {
        if (observer != null && !scroll.isLayoutRequested()) onPreDraw();
        if (scroll.getPaddingTop() != writtenTop) {
            applied = 0;
            cancelPendingScroll();
            finishInsetFocus();
        }
        int base = scroll.getPaddingTop() - applied;
        int margin = scroll.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) scroll.getLayoutParams()).topMargin : 0;
        int next = Math.max(0, height) + Math.round(Math.max(0, anchor - margin - base)
                * Math.max(0f, Math.min(1f, visibility)));
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
            insetWidth = scroll.getWidth();
            insetHeight = scroll.getHeight();
            applied = next;
            writtenTop = base + next;
            if (scroll instanceof NotificationSectionsScrollView) {
                ((NotificationSectionsScrollView) scroll).prepareNotificationInset(this::cancelPendingScroll);
            }
            scroll.setPadding(scroll.getPaddingLeft(), writtenTop, scroll.getPaddingRight(), scroll.getPaddingBottom());
            if (observer == null || !observer.isAlive()) {
                observer = scroll.getViewTreeObserver();
                observer.addOnPreDrawListener(this);
            }
        }
        if (next == 0 && active) {
            if (!scroll.getClipToPadding()) scroll.setClipToPadding(originalClip);
            active = false;
        }
        return changed;
    }

    void release() {
        apply(0, 0, 0);
        if (observer != null) {
            cancelPendingScroll();
            if (scroll.getWidth() == insetWidth && scroll.getHeight() == insetHeight) {
                scroll.scrollTo(targetScrollX, targetScrollY);
            }
        }
        finishInsetFocus();
    }
}
