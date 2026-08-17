package app.nimarkogram.messenger.components;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.concurrent.atomic.AtomicReference;

import com.exteragram.messenger.utils.ui.FolderIcons;

public abstract class IconSelectorAlert {

    private static final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public interface OnIconSelectedListener {
        void onIconSelected(String emoticon);
    }

    private static void onIconClicked(String selected, String tapped, AtomicReference<ActionBarPopupWindow> popupRef,
                                      OnIconSelectedListener listener, View ignored) {
        if (selected.equals(tapped)) {
            return;
        }
        ActionBarPopupWindow popup = popupRef.getAndSet(null);
        if (popup != null) {
            popup.dismiss();
        }
        listener.onIconSelected(tapped);
    }

    public static void show(BaseFragment fragment, View anchor, final String selected,
                            final OnIconSelectedListener listener) {
        selectedPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));

        final Activity parentActivity = fragment.getParentActivity();
        ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout =
                new ActionBarPopupWindow.ActionBarPopupWindowLayout(parentActivity, R.drawable.popup_fixed_alert3, null);

        Rect padding = new Rect();
        fragment.getParentActivity().getResources().getDrawable(R.drawable.popup_fixed_alert3).mutate().getPadding(padding);
        popupLayout.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground));

        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        final int x = ((location[0] - AndroidUtilities.dp(8.0f)) - padding.left) + anchor.getMeasuredWidth();
        final int y = ((location[1] - AndroidUtilities.dp(8.0f)) - padding.top) + anchor.getMeasuredHeight();

        final AtomicReference<ActionBarPopupWindow> popupRef = new AtomicReference<>();

        GridLayout gridLayout = new GridLayout(parentActivity);
        int columns = 6;
        while (AndroidUtilities.displaySize.x - x < (columns * 48) + AndroidUtilities.dp(8.0f)) {
            columns--;
        }
        gridLayout.setColumnCount(columns);

        for (final String emoticon : FolderIcons.folderIcons.keySet().toArray(new String[0])) {
            FrameLayout cell = new FrameLayout(parentActivity) {
                @Override
                @SuppressLint("DrawAllocation")
                protected void onDraw(Canvas canvas) {
                    int inset = AndroidUtilities.dp(6.0f);
                    Drawable drawable = ContextCompat.getDrawable(parentActivity, FolderIcons.getTabIcon(emoticon));
                    drawable.setColorFilter(new PorterDuffColorFilter(
                            Theme.getColor(isSelected()
                                    ? Theme.key_windowBackgroundWhiteValueText
                                    : Theme.key_windowBackgroundWhiteGrayIcon),
                            PorterDuff.Mode.MULTIPLY));
                    drawable.setBounds(inset, inset, getMeasuredWidth() - inset, getMeasuredHeight() - inset);
                    if (isSelected()) {
                        RectF rectF = AndroidUtilities.rectTmp;
                        rectF.set(0.0f, 0.0f, getMeasuredWidth(), getMeasuredHeight());
                        selectedPaint.setAlpha(40);
                        canvas.drawRoundRect(rectF, AndroidUtilities.dp(7.0f), AndroidUtilities.dp(7.0f), selectedPaint);
                    }
                    drawable.draw(canvas);
                    super.onDraw(canvas);
                }
            };
            cell.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 7, 7));
            cell.setSelected(emoticon.equals(selected));
            cell.setOnClickListener(v -> onIconClicked(selected, emoticon, popupRef, listener, v));
            gridLayout.addView(cell, LayoutHelper.createFrame(48, 48.0f, 17, 1.0f, 1.0f, 1.0f, 1.0f));
        }

        popupLayout.addView(gridLayout, LayoutHelper.createLinear(-1, -2, 4.0f, 4.0f, 4.0f, 4.0f));

        ActionBarPopupWindow popupWindow = new ActionBarPopupWindow(popupLayout, -2, -2);
        popupRef.set(popupWindow);
        popupWindow.setPauseNotifications(true);
        popupWindow.setDismissAnimationDuration(220);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setClippingEnabled(true);
        popupWindow.setAnimationStyle(R.style.PopupContextAnimation);
        popupWindow.setFocusable(true);
        popupLayout.measure(
                View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000.0f), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000.0f), View.MeasureSpec.AT_MOST));
        popupWindow.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popupWindow.setSoftInputMode(0);
        popupWindow.getContentView().setFocusableInTouchMode(true);
        popupWindow.showAtLocation(anchor, 51, x, y);
        popupWindow.dimBehind();
    }
}
