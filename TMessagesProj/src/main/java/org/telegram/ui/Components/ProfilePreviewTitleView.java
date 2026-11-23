/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;

import org.telegram.ui.ActionBar.SimpleTextView;

public class ProfilePreviewTitleView extends SimpleTextView {
    private final int badgeGutter;
    private float centerCorrection;

    public ProfilePreviewTitleView(Context context) {
        super(context);
        int gap = dp(4);
        badgeGutter = dp(24) + gap;
        setDrawablePadding(gap);
        setLeftDrawableOutside(true);
        setRightDrawableOutside(true);
        setOutsideRightDrawableTextClipInset(0);
        setWidthWrapContent(true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int rightSlot = getRightDrawablesWidth();
        int leftSlot = getSideDrawablesSize() - rightSlot;
        int gutter = Math.max(badgeGutter, Math.max(leftSlot, rightSlot));
        int width = Math.max(0, MeasureSpec.getSize(widthSpec) - 2 * gutter + leftSlot + rightSlot);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthSpec)), heightSpec);
        centerCorrection = (rightSlot - leftSlot) / 2f;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        setTranslationX(centerCorrection);
    }
}
