/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBar;

final class MessageProfileLayout extends LinearLayout {
    private final FrameLayout header;
    private final FrameLayout controls;
    private final View divider;
    private int topInset;

    MessageProfileLayout(Context context, View background, FrameLayout controls, View pager) {
        super(context);
        this.controls = controls;
        setOrientation(VERTICAL);
        header = new FrameLayout(context);
        header.addView(background, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        header.addView(controls, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));
        divider = new View(context);
        divider.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(divider, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, 0));
        addView(pager, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    void setSafeArea(int left, int top, int right) {
        setPadding(left, 0, right, 0);
        if (topInset != top) {
            topInset = top;
            requestLayout();
        }
    }

    void setDividerColor(int color) {
        divider.setBackgroundColor(color);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int toolbarHeight = ActionBar.getCurrentActionBarHeight();
        header.getLayoutParams().height = topInset + toolbarHeight;
        controls.getLayoutParams().height = toolbarHeight;
        super.onMeasure(widthSpec, heightSpec);
    }

    static int readableColor(int preferred, int background) {
        int opaque = ColorUtils.setAlphaComponent(background, 255);
        int painted = ColorUtils.compositeColors(preferred, opaque);
        if (ColorUtils.calculateContrast(painted, opaque) >= 4.5) return painted;
        return ColorUtils.calculateContrast(Color.WHITE, opaque) >= ColorUtils.calculateContrast(Color.BLACK, opaque)
                ? Color.WHITE : Color.BLACK;
    }

    static final class Tabs extends LinearLayout {
        interface Listener { void select(int page); }
        private final TextView[] labels = new TextView[2];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path indicator = new Path();
        private PorterDuffColorFilter selectedFilter;
        private float position;
        private int track, pill, normalInk, selectedInk;
        private int themeNormal, themeSelected;

        Tabs(Context context, CharSequence message, CharSequence profile, Listener listener) {
            super(context);
            setOrientation(HORIZONTAL);
            setLayoutDirection(LocaleController.isRTL ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
            setWillNotDraw(false);
            setPadding(dp(4), 0, dp(4), 0);
            for (int i = 0; i < labels.length; i++) {
                int page = i;
                TextView label = labels[i] = new TextView(context) {
                    @Override
                    protected void onDraw(Canvas canvas) {
                        super.onDraw(canvas);
                        if (position <= 0f || position >= 1f || selectedInk == normalInk) return;
                        int save = canvas.save();
                        canvas.translate(getScrollX() - getLeft(), getScrollY() - getTop());
                        canvas.clipPath(indicator);
                        canvas.translate(getLeft() - getScrollX(), getTop() - getScrollY());
                        getPaint().setColorFilter(selectedFilter);
                        super.onDraw(canvas);
                        getPaint().setColorFilter(null);
                        canvas.restoreToCount(save);
                    }
                };
                label.setText(i == 0 ? message : profile);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                label.setTypeface(AndroidUtilities.bold());
                label.setPadding(dp(12), 0, dp(12), 0);
                label.setFocusable(true);
                label.setOnClickListener(v -> listener.select(page));
                addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
            }
            updateLabels();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int first = desiredWidth(labels[0]), second = desiredWidth(labels[1]);
            int padding = getPaddingLeft() + getPaddingRight();
            int width = resolveSize(first + second + padding, widthSpec);
            int room = Math.max(0, width - padding);
            int firstWidth = room >= first + second ? first + (room - first - second) / 2
                    : Math.max(room - second, Math.min(first, room / 2));
            labels[0].getLayoutParams().width = firstWidth;
            labels[1].getLayoutParams().width = room - firstWidth;
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
        }

        private static int desiredWidth(TextView label) {
            return Math.max(dp(48), (int) Math.ceil(Layout.getDesiredWidth(label.getText(), label.getPaint()))
                    + label.getPaddingLeft() + label.getPaddingRight());
        }

        void setPosition(float value) {
            float next = Math.max(0f, Math.min(1f, value));
            if (next == position) return;
            position = next;
            updateLabels();
            invalidate();
        }

        void setColors(int newTrack, int newPill, int normal, int selected) {
            if (newPill == newTrack) newPill = ColorUtils.blendARGB(newTrack, readableColor(selected, newTrack), .12f);
            if (track == newTrack && pill == newPill && themeNormal == normal && themeSelected == selected) return;
            track = newTrack;
            pill = newPill;
            themeNormal = normal;
            themeSelected = selected;
            normalInk = tabInk(normal, track);
            int newSelected = tabInk(selected, pill);
            if (selectedInk != newSelected) selectedFilter = new PorterDuffColorFilter(newSelected, PorterDuff.Mode.SRC_IN);
            selectedInk = newSelected;
            updateLabels();
            invalidate();
        }

        static int tabInk(int preferred, int surface) {
            int tone = ColorUtils.compositeColors(preferred, surface);
            int readable = readableColor(preferred, surface);
            if (readable == tone) return tone;
            for (int i = 0; i < 8; i++) {
                int mid = ColorUtils.blendARGB(tone, readable, .5f);
                if (ColorUtils.calculateContrast(mid, surface) >= 4.5) readable = mid;
                else tone = mid;
            }
            return readable;
        }

        private void updateLabels() {
            for (int i = 0; i < labels.length; i++) {
                labels[i].setSelected(i == Math.round(position));
                int color = position == i ? selectedInk : normalInk;
                if (labels[i].getCurrentTextColor() != color) labels[i].setTextColor(color);
                labels[i].invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float height = Math.min(getHeight(), Math.max(dp(36), labels[0].getLineHeight() + dp(12)));
            float top = (getHeight() - height) / 2f, bottom = top + height;
            paint.setColor(track);
            canvas.drawRoundRect(0, top, getWidth(), bottom, height / 2f, height / 2f, paint);
            float left = AndroidUtilities.lerp((float) labels[0].getLeft(), labels[1].getLeft(), position);
            float right = AndroidUtilities.lerp((float) labels[0].getRight(), labels[1].getRight(), position);
            float inset = dp(4), radius = Math.max(0f, height / 2f - inset);
            if (position > 0f && position < 1f) {
                indicator.rewind();
                indicator.addRoundRect(left, top + inset, right, bottom - inset, radius, radius, Path.Direction.CW);
            }
            paint.setColor(pill);
            canvas.drawRoundRect(left, top + inset, right, bottom - inset, radius, radius, paint);
        }
    }
}
