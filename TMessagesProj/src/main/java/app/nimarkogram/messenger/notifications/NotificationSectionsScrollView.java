package app.nimarkogram.messenger.notifications;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.SectionsScrollView;

public final class NotificationSectionsScrollView extends SectionsScrollView {
    private TextView insetFocus;
    private String insetText;
    private int insetSelectionStart, insetSelectionEnd;
    private int insetWidth, insetHeight, focusWidth, focusHeight;
    private Runnable interruptInset;
    private final Runnable finishInsetLayout = this::clearInsetFocus;

    public NotificationSectionsScrollView(Context context, LinearLayout content, Theme.ResourcesProvider provider) {
        super(context, content, provider, false);
    }

    void prepareNotificationInset(Runnable interrupt) {
        clearInsetFocus();
        interruptInset = interrupt;
        View focused = findFocus();
        if (focused instanceof TextView) {
            insetFocus = (TextView) focused;
            insetText = insetFocus.getText().toString();
            insetSelectionStart = insetFocus.getSelectionStart();
            insetSelectionEnd = insetFocus.getSelectionEnd();
            insetWidth = getWidth();
            insetHeight = getHeight();
            focusWidth = insetFocus.getWidth();
            focusHeight = insetFocus.getHeight();
        }
    }

    private void clearInsetFocus() {
        removeCallbacks(finishInsetLayout);
        insetFocus = null;
        insetText = null;
        interruptInset = null;
    }
    void finishNotificationInset() {
        clearInsetFocus();
    }
    private void interruptNotificationInset() {
        Runnable interrupt = interruptInset;
        clearInsetFocus();
        if (interrupt != null) interrupt.run();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (interruptInset != null) {
            removeCallbacks(finishInsetLayout);
            post(finishInsetLayout);
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (!immediate && insetFocus != null && containsInsetFocus(child) && findFocus() == insetFocus
                && !insetFocus.isAccessibilityFocused()
                && getWidth() == insetWidth && getHeight() == insetHeight
                && insetFocus.getWidth() == focusWidth && insetFocus.getHeight() == focusHeight
                && insetFocus.getSelectionStart() == insetSelectionStart
                && insetFocus.getSelectionEnd() == insetSelectionEnd
                && TextUtils.equals(insetText, insetFocus.getText())) {
            return false;
        }
        interruptNotificationInset();
        return super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }
    private boolean containsInsetFocus(View child) {
        View current = insetFocus;
        while (current != null) {
            if (current == child) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) interruptNotificationInset();
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        interruptNotificationInset();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        interruptNotificationInset();
        super.onDetachedFromWindow();
    }
}
