/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.RotateDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.view.Gravity;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBarMenuItem;

public final class AlbumTitleChevron extends RotateDrawable implements ActionBarMenuItem.AnchoredDropdownDelegate {
    private final TextView title;
    private final ActionBarMenuItem menu;
    private final Paint ink;

    public AlbumTitleChevron(TextView title, ActionBarMenuItem menu) {
        this.title = title;
        this.menu = menu;
        Path path = new Path();
        path.moveTo(1.75f, 5f);
        path.lineTo(7f, 10f);
        path.lineTo(12.25f, 5f);
        ShapeDrawable mark = new ShapeDrawable(new PathShape(path, 14f, 12f));
        mark.setIntrinsicWidth(dp(14));
        mark.setIntrinsicHeight(dp(12));
        ink = mark.getPaint();
        ink.setAntiAlias(true);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(1.8f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        setDrawable(mark);
        setFromDegrees(0f);
        setToDegrees(180f);
        setPivotYRelative(true);
        setPivotY(7.5f / 12f);
        title.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        title.setHorizontallyScrolling(false);
        title.setCompoundDrawablePadding(dp(6));
        setTopInset(title.getPaddingTop());
        menu.setOnClickListener(v -> {
            if (v.isEnabled()) menu.toggleSubMenu();
        });
        menu.setSubMenuDelegate(this);
        setAvailable(false);
    }

    public void setTopInset(int top) {
        title.setPadding(LocaleController.isRTL ? dp(10) : 0, top, LocaleController.isRTL ? 0 : dp(10), 0);
    }

    public void setAvailable(boolean available) {
        menu.setEnabled(available);
        if (!available) {
            if (menu.isSubMenuShowing()) menu.closeSubMenu();
            setLevel(0);
        }
        menu.setShowSubmenuByMove(available);
        title.setCompoundDrawablesWithIntrinsicBounds(available && LocaleController.isRTL ? this : null,
                null, available && !LocaleController.isRTL ? this : null, null);
    }

    @Override public void onDropdownProgress(float progress) {
        setLevel(menu.isEnabled() ? Math.round(10000f * progress) : 0);
    }

    @Override public void draw(Canvas canvas) {
        ink.setColor(title.getCurrentTextColor());
        super.draw(canvas);
    }
}
