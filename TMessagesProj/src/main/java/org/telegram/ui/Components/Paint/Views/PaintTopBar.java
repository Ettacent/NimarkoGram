/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.Paint.Views;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

public final class PaintTopBar extends FrameLayout {
    private final FrameLayout startActions, endActions;
    private final TextView zoom;
    private final String zoomLabel;
    private final boolean rtl;
    private boolean compact, zoomVisible;

    public PaintTopBar(Context context, Runnable zoomOut) {
        super(context);
        rtl = LocaleController.isRTL;
        startActions = new FrameLayout(context);
        endActions = new FrameLayout(context);
        addView(startActions, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL));
        addView(endActions, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        zoomLabel = LocaleController.getString(R.string.PhotoEditorZoomOut);
        zoom = new TextView(context);
        zoom.setSingleLine(true);
        zoom.setText(zoomLabel);
        zoom.setContentDescription(zoomLabel);
        zoom.setGravity(Gravity.CENTER);
        zoom.setTextColor(Color.WHITE);
        zoom.setTypeface(AndroidUtilities.bold());
        zoom.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        zoom.setBackground(Theme.createSelectorDrawable(0x30ffffff, Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        zoom.setPadding(dp(8), 0, dp(8), 0);
        Drawable icon = context.getDrawable(R.drawable.photo_zoomout).mutate();
        icon.setBounds(0, 0, dp(24), dp(24));
        zoom.setCompoundDrawables(rtl ? null : icon, null, rtl ? icon : null, null);
        zoom.setCompoundDrawablePadding(dp(8));
        zoom.setOnClickListener(v -> zoomOut.run());
        zoom.setClickable(false);
        zoom.setFocusable(false);
        zoom.setAlpha(0f);
        zoom.setVisibility(INVISIBLE);
        addView(zoom, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER));
    }

    public void addStartAction(View view, int width, int inset) {
        addSideAction(startActions, view, !rtl, width, inset);
    }

    public void addEndAction(View view, int width, int inset) {
        addSideAction(endActions, view, rtl, width, inset);
    }

    private void addSideAction(FrameLayout group, View view, boolean left, int width, int inset) {
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
        }
        group.addView(view, LayoutHelper.createFrame(width, 32, left ? Gravity.LEFT : Gravity.RIGHT,
            left ? inset : 0, 0, left ? 0 : inset, 0));
    }

    public void setZoomVisible(boolean visible) {
        if (zoomVisible == visible) {
            return;
        }
        zoomVisible = visible;
        zoom.animate().cancel();
        zoom.setClickable(visible);
        zoom.setFocusable(visible);
        zoom.setImportantForAccessibility(visible ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (visible) {
            zoom.setVisibility(VISIBLE);
        }
        zoom.animate().alpha(visible ? 1f : 0f).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .setDuration(240).withEndAction(() -> {
                if (!zoomVisible) {
                    zoom.setVisibility(INVISIBLE);
                }
            }).start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        int iconWidth = zoom.getPaddingLeft() + dp(24) + zoom.getPaddingRight();
        int sideBudget = Math.max(0, width - iconWidth - 2 * dp(8));
        int startWidth = startActions.getMeasuredWidth(), endWidth = endActions.getMeasuredWidth();
        int height = MeasureSpec.makeMeasureSpec(dp(32), MeasureSpec.EXACTLY);
        if (startWidth + endWidth > sideBudget) {
            int startLimit = Math.min(startWidth, Math.max(sideBudget / 2, sideBudget - endWidth));
            startActions.measure(MeasureSpec.makeMeasureSpec(startLimit, MeasureSpec.AT_MOST), height);
            endActions.measure(MeasureSpec.makeMeasureSpec(sideBudget - startActions.getMeasuredWidth(), MeasureSpec.AT_MOST), height);
        }
        int available = Math.max(0, width - startActions.getMeasuredWidth() - endActions.getMeasuredWidth() - 2 * dp(8));
        boolean iconOnly = available < iconWidth + dp(8) + (int) Math.ceil(Layout.getDesiredWidth(zoomLabel, zoom.getPaint()));
        if (compact != iconOnly) {
            compact = iconOnly;
            zoom.setText(compact ? null : zoomLabel);
            zoom.setCompoundDrawablePadding(compact ? 0 : dp(8));
        }
        zoom.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int min = (rtl ? endActions : startActions).getRight() + dp(8);
        int max = (rtl ? startActions : endActions).getLeft() - dp(8) - zoom.getWidth();
        int x = Math.max(min, Math.min(zoom.getLeft(), max));
        zoom.offsetLeftAndRight(x - zoom.getLeft());
    }
}
