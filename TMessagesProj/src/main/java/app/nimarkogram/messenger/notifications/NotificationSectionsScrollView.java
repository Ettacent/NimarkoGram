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
    private final Runnable finishInsetLayout = this::clearInsetFocus;

    public NotificationSectionsScrollView(Context context, LinearLayout content, Theme.ResourcesProvider provider) {
        super(context, content, provider, false);
    }

    void prepareNotificationInset() {
        clearInsetFocus();
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
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (insetFocus != null) post(finishInsetLayout);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (!immediate && insetFocus != null && findFocus() == insetFocus
                && !insetFocus.isAccessibilityFocused()
                && getWidth() == insetWidth && getHeight() == insetHeight
                && insetFocus.getWidth() == focusWidth && insetFocus.getHeight() == focusHeight
                && insetFocus.getSelectionStart() == insetSelectionStart
                && insetFocus.getSelectionEnd() == insetSelectionEnd
                && TextUtils.equals(insetText, insetFocus.getText())) {
            return false;
        }
        return super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) clearInsetFocus();
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        clearInsetFocus();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        clearInsetFocus();
        super.onDetachedFromWindow();
    }
}
