/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;

import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.TimeZone;

import app.nimarkogram.messenger.search.MessageSearchDateRange;

public final class MessageSearchDateSheet extends BottomSheet {
    private static final int CHOICE_RADIUS_DP = 8;
    public interface Delegate {
        void onApply(MessageSearchDateRange dates, TimeZone timeZone);
        void onJumpToDay(int date);
    }

    static final class Draft {
        static final int MIN_YEAR = 2013;
        final TimeZone timeZone;
        final long minimum, today;
        long first, last;
        boolean period;
        int endpoint;

        Draft(MessageSearchDateRange dates, TimeZone zone, long now) {
            timeZone = (TimeZone) zone.clone();
            today = MessageSearchDateRange.ofDays(now, now, timeZone).getStartDayMillis();
            Calendar earliest = new java.util.GregorianCalendar(timeZone);
            earliest.clear();
            earliest.set(MIN_YEAR, Calendar.JANUARY, 1);
            minimum = MessageSearchDateRange.ofDays(earliest.getTimeInMillis(), earliest.getTimeInMillis(), timeZone).getStartDayMillis();
            MessageSearchDateRange seed = dates.isActive() ? dates : MessageSearchDateRange.ofDays(today, today, timeZone);
            first = boundedDay(seed.getStartDayMillis());
            last = boundedDay(seed.getEndDayMillis());
            period = seed.getStartDayMillis() != seed.getEndDayMillis();
        }

        Calendar calendar(long value) {
            Calendar calendar = Calendar.getInstance(timeZone);
            calendar.setTimeInMillis(value);
            return calendar;
        }

        void setPeriod(boolean value) {
            period = value;
            if (!period) {
                last = first;
                endpoint = 0;
            }
        }

        void setDay(int year, int month, int day) {
            Calendar calendar = Calendar.getInstance(timeZone);
            calendar.clear();
            calendar.set(year, month, day);
            long midnight = boundedDay(calendar.getTimeInMillis());
            if (endpoint == 0) first = midnight;
            else last = midnight;
            if (!period) last = first;
        }

        void preset(long now, int days, boolean yesterday) {
            Calendar end = calendar(boundedDay(now));
            if (yesterday) end.add(Calendar.DAY_OF_MONTH, -1);
            Calendar start = (Calendar) end.clone();
            start.add(Calendar.DAY_OF_MONTH, 1 - days);
            first = boundedDay(start.getTimeInMillis());
            last = boundedDay(end.getTimeInMillis());
            period = days > 1;
            endpoint = 0;
        }

        MessageSearchDateRange range() {
            if (first < minimum || last > today || first > last) throw new IllegalArgumentException("invalid calendar period");
            return MessageSearchDateRange.ofDays(first, last, timeZone);
        }

        private long boundedDay(long value) {
            long bounded = Math.max(minimum, Math.min(today, value));
            return MessageSearchDateRange.ofDays(bounded, bounded, timeZone).getStartDayMillis();
        }

        int maxMonth(int year) {
            Calendar limit = calendar(today);
            return year >= limit.get(Calendar.YEAR) ? limit.get(Calendar.MONTH) : Calendar.DECEMBER;
        }

        int maxDay(int year, int month) {
            Calendar value = calendar(today);
            if (year == value.get(Calendar.YEAR) && month == value.get(Calendar.MONTH)) return value.get(Calendar.DAY_OF_MONTH);
            value.set(Calendar.DAY_OF_MONTH, 1);
            value.set(Calendar.YEAR, year);
            value.set(Calendar.MONTH, month);
            return value.getActualMaximum(Calendar.DAY_OF_MONTH);
        }
    }

    private final Draft draft;
    private final DateFormat dayFormat, compactDayFormat;
    private final Delegate delegate;
    private final DatePicker dayPicker, monthPicker, yearPicker;
    private final Choice dayMode, periodMode, fromButton, untilButton;
    private final Choice[] presets = new Choice[4];
    private final ExpansionRow endpoints, validation;
    private final TextView applyButton, errorView;
    private final LinearLayout wheels;
    private ValueAnimator wheelAnimation, validationAnimation;
    private int wheelGeneration, validationGeneration, selectedPreset = -1;
    private boolean updating, confirmed, opened, wheelValuesPending, validDraft;
    private boolean layoutTransitionPending;
    private Runnable pendingSelection, pendingConfirmation;
    private final Runnable selectionDrain = this::drainSelection;

    public MessageSearchDateSheet(Context context, Theme.ResourcesProvider resourcesProvider,
                                  MessageSearchDateRange dates, TimeZone timeZone, Delegate delegate) {
        super(context, false, resourcesProvider);
        this.delegate = delegate;
        draft = new Draft(dates, timeZone, System.currentTimeMillis());
        dayFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, LocaleController.getInstance().getCurrentLocale());
        dayFormat.setTimeZone(draft.timeZone);
        compactDayFormat = DateFormat.getDateInstance(DateFormat.SHORT, LocaleController.getInstance().getCurrentLocale());
        compactDayFormat.setTimeZone(draft.timeZone);
        setShowWithoutAnimation(!motionEnabled());
        setAllowNestedScroll(true);
        setApplyBottomPadding(false);
        setCanDismissWithSwipe(false);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(context) {
            @Override
            protected void onDetachedFromWindow() {
                retireTransitions();
                super.onDetachedFromWindow();
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(12));
        content.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.addView(content, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout heading = new FrameLayout(context);
        TextView title = text(LocaleController.getString(R.string.NM_SearchDatesTitle), 20, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMinHeight(dp(40));
        ViewCompat.setAccessibilityHeading(title, true);
        heading.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 48 : 4, 8, LocaleController.isRTL ? 4 : 48, 8));
        ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.ic_layer_close);
        close.setScaleType(ImageView.ScaleType.CENTER);
        ThemeBind.colorFilter(close, Theme.key_dialogIcon, resourcesProvider, PorterDuff.Mode.MULTIPLY);
        ThemeBind.selectorBackground(close, Theme.createSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider)), null, ThemeBind.of(Theme.key_dialogButtonSelector), resourcesProvider);
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setOnClickListener(v -> dismiss());
        heading.addView(close, LayoutHelper.createFrame(44, 48, Gravity.END | Gravity.CENTER_VERTICAL));
        content.addView(heading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout modeRow = new LinearLayout(context);
        modeRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        ThemeBind.selectorBackground(modeRow, Theme.createRoundRectDrawable(dp(10), Theme.getColor(Theme.key_dialogBackgroundGray, resourcesProvider)),
                ThemeBind.of(Theme.key_dialogBackgroundGray), null, resourcesProvider);
        dayMode = new Choice(context, LocaleController.getString(R.string.NM_SearchDatesDay), true, false);
        periodMode = new Choice(context, LocaleController.getString(R.string.NM_SearchDatesPeriod), true, false);
        dayMode.setOnClickListener(v -> select(() -> { draft.setPeriod(false); selectedPreset = -1; }));
        periodMode.setOnClickListener(v -> select(() -> { draft.setPeriod(true); selectedPreset = -1; }));
        modeRow.addView(dayMode, weighted(0, 0));
        modeRow.addView(periodMode, weighted(0, 0));
        content.addView(modeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        int[] labels = {R.string.NM_SearchDatesToday, R.string.NM_SearchDatesYesterday, R.string.NM_SearchDatesLast7, R.string.NM_SearchDatesLast30};
        PresetRow presetRow = new PresetRow(context);
        for (int index = 0; index < labels.length; index++) {
            final int preset = index;
            String label = preset < 2 ? LocaleController.getString(labels[preset])
                    : LocaleController.formatPluralString("Days", preset == 2 ? 7 : 30);
            Choice item = presets[preset] = new Choice(context, label, false, false);
            item.setContentDescription(LocaleController.getString(labels[preset]));
            item.setOnClickListener(v -> select(() -> {
                draft.preset(System.currentTimeMillis(), preset < 2 ? 1 : preset == 2 ? 7 : 30, preset == 1);
                selectedPreset = preset;
            }));
            presetRow.addView(item, new ViewGroup.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }
        content.addView(presetRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        endpoints = new ExpansionRow(context);
        endpoints.setPadding(0, 0, 0, dp(2));
        fromButton = new Choice(context, "", false, true);
        untilButton = new Choice(context, "", false, true);
        fromButton.setOnClickListener(v -> select(() -> draft.endpoint = 0));
        untilButton.setOnClickListener(v -> select(() -> { draft.setPeriod(true); draft.endpoint = 1; }));
        endpoints.addView(fromButton, weighted(0, 0));
        endpoints.addView(untilButton, weighted(8, 0));
        content.addView(endpoints, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        wheels = new LinearLayout(context);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        dayPicker = new DatePicker(context);
        monthPicker = new DatePicker(context);
        yearPicker = new DatePicker(context);
        NumberPicker[] pickers = {dayPicker, monthPicker, yearPicker};
        float[] weights = {.25f, .50f, .25f};
        for (int index = 0; index < pickers.length; index++) {
            NumberPicker picker = pickers[index];
            picker.setItemCount(3);
            picker.setWrapSelectorWheel(false);
            picker.setOnTouchListener((v, event) -> {
                scroll.requestDisallowInterceptTouchEvent(event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                return false;
            });
            picker.setOnValueChangedListener((p, oldValue, newValue) -> wheelChanged());
            wheels.addView(picker, LayoutHelper.createLinear(0, NumberPicker.DEFAULT_SIZE_PER_COUNT * 3, weights[index]));
        }
        dayPicker.setMinValue(1);
        dayPicker.setMaxValue(31);
        monthPicker.setMinValue(0);
        monthPicker.setMaxValue(11);
        final String[] months = new DateFormatSymbols(LocaleController.getInstance().getCurrentLocale()).getMonths();
        monthPicker.setFormatter(value -> months[value]);
        yearPicker.setMinValue(draft.calendar(draft.minimum).get(Calendar.YEAR));
        yearPicker.setMaxValue(draft.calendar(draft.today).get(Calendar.YEAR));
        dayPicker.setContentDescriptionCallback(value -> LocaleController.formatString(R.string.NM_SearchDatesDayValue, value));
        monthPicker.setContentDescriptionCallback(value -> LocaleController.formatString(R.string.NM_SearchDatesMonthValue, months[value]));
        yearPicker.setContentDescriptionCallback(value -> LocaleController.formatString(R.string.NM_SearchDatesYearValue, value));
        content.addView(wheels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        errorView = text(LocaleController.getString(R.string.NM_SearchDatesInvalid), 13, false);
        ThemeBind.textColor(errorView, Theme.key_text_RedRegular, resourcesProvider);
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        validation = new ExpansionRow(context);
        validation.setPadding(dp(4), 0, dp(4), dp(6));
        validation.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(validation, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        applyButton = button(LocaleController.getString(R.string.NM_SearchDatesApply), true);
        applyButton.setOnClickListener(v -> confirm(false));
        content.addView(applyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        if (dates.isActive()) {
            TextView clear = button(LocaleController.getString(R.string.NM_SearchDatesClear), false);
            clear.setOnClickListener(v -> confirm(true));
            content.addView(clear, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        setCustomView(scroll);
        bindWheels();
        bindDraft(false);
    }

    private TextView text(String label, int size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ThemeBind.textColor(view, Theme.key_dialogTextBlack, resourcesProvider);
        return view;
    }

    private static LinearLayout.LayoutParams weighted(int start, int end) {
        LinearLayout.LayoutParams params = LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f);
        params.setMarginStart(dp(start));
        params.setMarginEnd(dp(end));
        return params;
    }

    private static int presetColumns(int[] intrinsicWidths, int available, int gap) {
        int widest = 0;
        for (int width : intrinsicWidths) widest = Math.max(widest, width);
        if ((long) widest * 4 + gap * 3 <= available) return 4;
        if ((long) widest * 2 + gap <= available) return 2;
        return 1;
    }

    private static int layoutPresetRows(int[] widths, int[] heights, int available, int gap, boolean rtl, int columns, int[] positions) {
        if (widths.length == 0) return 0;
        int height = 0;
        for (int value : heights) height = Math.max(height, value);
        int space = Math.max(0, available - gap * (columns - 1));
        int cell = space / columns, remainder = space % columns;
        for (int i = 0; i < widths.length; i++) {
            int column = i % columns;
            widths[i] = cell + (column < remainder ? 1 : 0);
            heights[i] = height;
            int x = column * (cell + gap) + Math.min(column, remainder);
            positions[i * 2] = rtl ? available - x - widths[i] : x;
            positions[i * 2 + 1] = i / columns * (height + gap);
        }
        int rows = (widths.length + columns - 1) / columns;
        return rows * height + (rows - 1) * gap;
    }

    private static final class PresetRow extends ViewGroup {
        private int[] widths = new int[0], heights = new int[0], positions = new int[0];

        PresetRow(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int count = getChildCount();
            if (widths.length != count) {
                widths = new int[count];
                heights = new int[count];
                positions = new int[count * 2];
            }
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                widths[i] = child.getMeasuredWidth();
                heights[i] = child.getMeasuredHeight();
            }
            int columns = presetColumns(widths, available, dp(6));
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            layoutPresetRows(widths, heights, available, dp(6), rtl, columns, positions);
            for (int i = 0; i < count; i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(widths[i], MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                heights[i] = getChildAt(i).getMeasuredHeight();
            }
            int height = layoutPresetRows(widths, heights, available, dp(6), rtl, columns, positions);
            for (int i = 0; i < count; i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(widths[i], MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heights[i], MeasureSpec.EXACTLY));
            setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                    resolveSize(height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                int x = getPaddingLeft() + positions[i * 2];
                int y = getPaddingTop() + positions[i * 2 + 1];
                getChildAt(i).layout(x, y, x + widths[i], y + heights[i]);
            }
        }
    }

    private final class ExpansionRow extends LinearLayout {
        private float expansion;
        private boolean expanded;
        private ValueAnimator animation;

        ExpansionRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setClipChildren(true);
            setAlpha(0f);
        }

        void setExpanded(boolean value, boolean animated) {
            boolean changed = expanded != value;
            expanded = value;
            setImportantForAccessibility(value ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            final float target = value ? 1f : 0f;
            if (!changed && animation != null && animated) return;
            if (animation == null && Math.abs(expansion - target) < .001f) return;
            ValueAnimator old = animation;
            animation = null;
            if (old != null) old.cancel();
            if (!animated || !motionEnabled()) {
                setExpansion(target);
                return;
            }
            final ValueAnimator next = ValueAnimator.ofFloat(expansion, target);
            animation = next;
            next.setDuration(180);
            next.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            next.addUpdateListener(a -> {
                if (animation != next || confirmed) return;
                setExpansion(motionEnabled() ? (float) a.getAnimatedValue() : target);
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    if (animation != next || confirmed) return;
                    animation = null;
                    setExpansion(target);
                }
            });
            next.start();
        }

        private void setExpansion(float value) {
            expansion = value;
            setAlpha(value);
            requestLayout();
        }

        void cancelExpansion() {
            ValueAnimator old = animation;
            animation = null;
            if (old != null) old.cancel();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            setMeasuredDimension(getMeasuredWidth(), Math.round(getMeasuredHeight() * expansion));
        }
    }

    private TextView button(String label, boolean primary) {
        TextView view = new TextView(getContext()) {
            @Override
            public CharSequence getAccessibilityClassName() {
                return Button.class.getName();
            }
        };
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setFocusable(true);
        view.setContentDescription(label);
        int background = primary ? Theme.key_featuredStickers_addButton : Theme.key_dialogBackground;
        int pressed = primary ? Theme.key_featuredStickers_addButtonPressed : Theme.key_dialogButtonSelector;
        ThemeBind.textColor(view, primary ? Theme.key_featuredStickers_buttonText : Theme.key_dialogTextBlue2, resourcesProvider);
        ThemeBind.selectorBackground(view, Theme.createSimpleSelectorRoundRectDrawable(dp(8), Theme.getColor(background, resourcesProvider), Theme.getColor(pressed, resourcesProvider)), ThemeBind.of(background), ThemeBind.of(pressed), resourcesProvider);
        return view;
    }

    private final class Choice extends LinearLayout implements Theme.Colorable {
        final TextView label, value;
        final boolean mode, endpoint;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float progress;
        ValueAnimator animation;

        Choice(Context context, String text, boolean mode, boolean endpoint) {
            super(context);
            this.mode = mode;
            this.endpoint = endpoint;
            setOrientation(VERTICAL);
            setGravity(endpoint ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
            setPadding(dp(8), dp(4), dp(8), dp(4));
            setMinimumHeight(dp(endpoint ? 44 : 36));
            setFocusable(true);
            setClickable(true);
            setWillNotDraw(false);
            label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, endpoint ? 12 : mode ? 14 : 13);
            label.setGravity(endpoint ? Gravity.START : Gravity.CENTER);
            label.setMaxLines(endpoint ? 1 : 2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (mode) label.setTypeface(AndroidUtilities.bold());
            label.setText(text);
            addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            if (endpoint) {
                value = new TextView(context);
                value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                value.setTypeface(AndroidUtilities.bold());
                value.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                value.setSingleLine(true);
                value.setEllipsize(TextUtils.TruncateAt.END);
                value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            } else {
                value = null;
            }
            setContentDescription(text);
            updateColors();
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return mode ? android.widget.RadioButton.class.getName() : Button.class.getName();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (mode) {
                info.setCheckable(true);
                info.setChecked(isSelected());
            }
        }

        void bindDate(String caption, String displayed, String accessible) {
            label.setText(caption);
            value.setText(displayed);
            setContentDescription(caption + ": " + accessible);
        }

        void choose(boolean selected, boolean animated) {
            boolean changed = isSelected() != selected;
            setSelected(selected);
            if (!changed && animation != null && animated) return;
            ValueAnimator old = animation;
            animation = null;
            if (old != null) old.cancel();
            final float target = selected ? 1f : 0f;
            if (!animated || Math.abs(progress - target) < .001f) {
                progress = target;
                updateTextColors();
                invalidate();
                return;
            }
            final ValueAnimator next = ValueAnimator.ofFloat(progress, target);
            animation = next;
            next.setDuration(180);
            next.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            next.addUpdateListener(a -> {
                if (animation != next || confirmed) return;
                progress = motionEnabled() ? (float) a.getAnimatedValue() : target;
                updateTextColors();
                invalidate();
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    if (animation == next) animation = null;
                }
            });
            next.start();
        }

        void finishChoice() {
            choose(isSelected(), false);
        }

        private void updateTextColors() {
            int color = ColorUtils.blendARGB(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider),
                    Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider), progress);
            label.setTextColor(value == null ? color : Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
            if (value != null) value.setTextColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int base = Theme.getColor(Theme.key_dialogBackgroundGray, resourcesProvider);
            int accent = Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider);
            paint.setColor(endpoint ? ColorUtils.blendARGB(base, accent, .10f * progress)
                    : ColorUtils.blendARGB(base, mode ? Theme.getColor(Theme.key_dialogBackground, resourcesProvider)
                    : ColorUtils.blendARGB(base, accent, .12f), progress));
            AndroidUtilities.rectTmp.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(CHOICE_RADIUS_DP), dp(CHOICE_RADIUS_DP), paint);
            super.onDraw(canvas);
        }

        @Override
        public void updateColors() {
            updateTextColors();
            setForegroundGravity(Gravity.FILL);
            setForeground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider),
                    Theme.RIPPLE_MASK_ROUNDRECT_6DP, dp(CHOICE_RADIUS_DP)));
            invalidate();
        }

        @Override
        public void addColorKeys(HashSet<Integer> keys) {
            keys.add(Theme.key_dialogBackgroundGray);
            keys.add(Theme.key_dialogBackground);
            keys.add(Theme.key_dialogTextBlue2);
            keys.add(Theme.key_dialogTextBlack);
            keys.add(Theme.key_dialogTextGray2);
            keys.add(Theme.key_dialogButtonSelector);
        }
    }

    private final class DatePicker extends NumberPicker {
        boolean touching, settling, computing, nextFrame;
        int scrollState;

        DatePicker(Context context) {
            super(context, MessageSearchDateSheet.this.resourcesProvider);
            setOnScrollListener((picker, state) -> {
                scrollState = state;
                if (state != OnScrollListener.SCROLL_STATE_IDLE) holdLayoutTransitions();
                scheduleSelectionDrain();
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touching = true;
                holdLayoutTransitions();
                cancelWheelTransition(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
                settling = true;
                postInvalidateOnAnimation();
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void changeValueByOne(boolean increment) {
            settling = true;
            holdLayoutTransitions();
            super.changeValueByOne(increment);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) cancelWheelTransition(true);
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            cancelWheelTransition(true);
            return super.performAccessibilityAction(action, arguments);
        }

        @Override
        public void invalidate() {
            if (computing) nextFrame = true;
            super.invalidate();
        }

        @Override
        public void computeScroll() {
            computing = true;
            nextFrame = false;
            try {
                super.computeScroll();
            } finally {
                computing = false;
            }
            settling = nextFrame;
            if (!settling) scheduleSelectionDrain();
        }

        boolean busy() {
            return touching || settling || computing || scrollState != OnScrollListener.SCROLL_STATE_IDLE;
        }
    }

    private static boolean motionEnabled() {
        return SharedConfig.animationsEnabled() && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? ValueAnimator.areAnimatorsEnabled() : AndroidUtilities.getAnimatorDurationScale() > 0f);
    }

    private boolean animateChanges() {
        return opened && !confirmed && isShowing() && motionEnabled();
    }

    private boolean wheelsBusy() {
        return dayPicker == null || monthPicker == null || yearPicker == null
                || dayPicker.busy() || monthPicker.busy() || yearPicker.busy();
    }

    private void select(Runnable change) {
        if (confirmed) return;
        if (wheelsBusy()) {
            pendingSelection = change;
            return;
        }
        cancelWheelTransition(true);
        final long previousDay = draft.endpoint == 0 ? draft.first : draft.last;
        change.run();
        bindDraft(animateChanges());
        final long selectedDay = draft.endpoint == 0 ? draft.first : draft.last;
        if (previousDay != selectedDay && animateChanges()) {
            wheelValuesPending = true;
            animateWheelAlpha(0f, 80, true, ++wheelGeneration);
        } else {
            bindWheels();
        }
    }

    private void scheduleSelectionDrain() {
        if (updating || confirmed || wheels == null || wheelsBusy()
                || pendingSelection == null && pendingConfirmation == null && !layoutTransitionPending) return;
        wheels.removeCallbacks(selectionDrain);
        wheels.post(selectionDrain);
    }

    private void drainSelection() {
        if (confirmed || updating || wheelsBusy()) return;
        Runnable selection = pendingSelection;
        pendingSelection = null;
        if (selection != null) select(selection);
        Runnable confirmation = pendingConfirmation;
        pendingConfirmation = null;
        if (confirmation != null) confirmation.run();
        if (!confirmed && layoutTransitionPending) bindLayoutTransitions(animateChanges());
    }

    private void holdLayoutTransitions() {
        if (confirmed) return;
        layoutTransitionPending = true;
        if (endpoints != null) endpoints.cancelExpansion();
        if (validation != null) validation.cancelExpansion();
    }

    private void bindLayoutTransitions(boolean animated) {
        if (confirmed) return;
        if (wheelsBusy()) {
            holdLayoutTransitions();
            return;
        }
        layoutTransitionPending = false;
        endpoints.setExpanded(draft.period, animated);
        validation.setExpanded(!validDraft, animated);
    }

    private void bindWheels() {
        updating = true;
        try {
            Calendar value = draft.calendar(draft.endpoint == 0 ? draft.first : draft.last);
            yearPicker.setValue(value.get(Calendar.YEAR));
            monthPicker.setMaxValue(draft.maxMonth(value.get(Calendar.YEAR)));
            monthPicker.setValue(value.get(Calendar.MONTH));
            dayPicker.setMaxValue(draft.maxDay(value.get(Calendar.YEAR), value.get(Calendar.MONTH)));
            dayPicker.setValue(value.get(Calendar.DAY_OF_MONTH));
        } finally {
            updating = false;
        }
        scheduleSelectionDrain();
    }

    private void bindDraft(boolean animated) {
        fromButton.bindDate(LocaleController.getString(R.string.NM_SearchDatesFrom),
                compactDayFormat.format(new Date(draft.first)), dayFormat.format(new Date(draft.first)));
        untilButton.bindDate(LocaleController.getString(R.string.NM_SearchDatesUntil),
                compactDayFormat.format(new Date(draft.last)), dayFormat.format(new Date(draft.last)));
        fromButton.setEnabled(draft.period && !confirmed);
        untilButton.setEnabled(draft.period && !confirmed);
        dayMode.choose(!draft.period, animated);
        periodMode.choose(draft.period, animated);
        fromButton.choose(draft.endpoint == 0, animated);
        untilButton.choose(draft.period && draft.endpoint == 1, animated);
        for (int i = 0; i < presets.length; i++) presets[i].choose(selectedPreset == i, animated);
        boolean valid;
        try { draft.range(); valid = true; } catch (IllegalArgumentException error) { valid = false; }
        bindValidation(valid, animated);
    }

    private void bindValidation(boolean valid, boolean animated) {
        final boolean changed = validDraft != valid;
        validDraft = valid;
        applyButton.setEnabled(valid);
        errorView.setImportantForAccessibility(valid ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        bindLayoutTransitions(animated);
        if (!changed && validationAnimation != null && animated) return;
        final int generation = ++validationGeneration;
        ValueAnimator old = validationAnimation;
        validationAnimation = null;
        if (old != null) old.cancel();
        final float targetApply = valid ? 1f : .5f;
        if (!animated || !changed) {
            applyButton.setAlpha(targetApply);
            return;
        }
        final float fromApply = applyButton.getAlpha();
        final ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        validationAnimation = next;
        next.setDuration(180);
        next.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        next.addUpdateListener(a -> {
            if (confirmed || generation != validationGeneration || validationAnimation != next) return;
            float t = motionEnabled() ? (float) a.getAnimatedValue() : 1f;
            applyButton.setAlpha(AndroidUtilities.lerp(fromApply, targetApply, t));
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (generation != validationGeneration || validationAnimation != next) return;
                validationAnimation = null;
            }
        });
        next.start();
    }

    private void animateWheelAlpha(float target, long duration, boolean bindAtEnd, int generation) {
        final ValueAnimator next = ValueAnimator.ofFloat(wheels.getAlpha(), target);
        wheelAnimation = next;
        next.setDuration(duration);
        next.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        next.addUpdateListener(a -> {
            if (confirmed || generation != wheelGeneration || wheelAnimation != next) return;
            wheels.setAlpha(motionEnabled() ? (float) a.getAnimatedValue() : target);
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (confirmed || generation != wheelGeneration || wheelAnimation != next) return;
                wheelAnimation = null;
                if (bindAtEnd) {
                    wheelValuesPending = false;
                    bindWheels();
                    animateWheelAlpha(1f, 120, false, generation);
                } else {
                    wheels.setAlpha(1f);
                }
            }
        });
        next.start();
    }

    private void cancelWheelTransition(boolean bindPending) {
        ++wheelGeneration;
        ValueAnimator old = wheelAnimation;
        wheelAnimation = null;
        if (old != null) old.cancel();
        if (bindPending && wheelValuesPending && !confirmed) bindWheels();
        wheelValuesPending = false;
        wheels.setAlpha(1f);
    }

    private void wheelChanged() {
        if (updating || confirmed) return;
        if (wheelValuesPending) {
            cancelWheelTransition(true);
            return;
        }
        Calendar value = draft.calendar(draft.endpoint == 0 ? draft.first : draft.last);
        value.set(Calendar.DAY_OF_MONTH, 1);
        value.set(Calendar.YEAR, yearPicker.getValue());
        value.set(Calendar.MONTH, monthPicker.getValue());
        draft.setDay(yearPicker.getValue(), monthPicker.getValue(), Math.min(dayPicker.getValue(), value.getActualMaximum(Calendar.DAY_OF_MONTH)));
        selectedPreset = -1;
        bindWheels();
        bindDraft(animateChanges());
    }

    private void confirm(boolean reset) {
        if (confirmed) return;
        if (wheelsBusy() || pendingSelection != null) {
            pendingConfirmation = () -> confirm(reset);
            scheduleSelectionDrain();
            return;
        }
        final MessageSearchDateRange dates;
        try { dates = reset ? MessageSearchDateRange.ALL : draft.range(); }
        catch (IllegalArgumentException invalid) { return; }
        confirmed = true;
        dismiss();
        delegate.onApply(dates, (TimeZone) draft.timeZone.clone());
    }

    private void jumpToDay() {
        if (confirmed) return;
        if (wheelsBusy() || pendingSelection != null) {
            pendingConfirmation = this::jumpToDay;
            scheduleSelectionDrain();
            return;
        }
        final long selectedDay = draft.endpoint == 0 ? draft.first : draft.last;
        try { MessageSearchDateRange.ofDays(selectedDay, selectedDay, draft.timeZone); }
        catch (IllegalArgumentException invalid) { return; }
        confirmed = true;
        dismiss();
        delegate.onJumpToDay((int) (selectedDay / 1000L));
    }

    @Override
    public void onOpenAnimationEnd() {
        super.onOpenAnimationEnd();
        if (!confirmed) opened = true;
    }

    private void retireTransitions() {
        confirmed = true;
        opened = false;
        layoutTransitionPending = false;
        pendingSelection = pendingConfirmation = null;
        if (endpoints != null) endpoints.cancelExpansion();
        if (validation != null) validation.cancelExpansion();
        if (fromButton != null) fromButton.setEnabled(false);
        if (untilButton != null) untilButton.setEnabled(false);
        if (wheels != null) {
            wheels.removeCallbacks(selectionDrain);
            cancelWheelTransition(false);
        }
        ++validationGeneration;
        ValueAnimator old = validationAnimation;
        validationAnimation = null;
        if (old != null) old.cancel();
        if (dayMode != null) dayMode.finishChoice();
        if (periodMode != null) periodMode.finishChoice();
        if (fromButton != null) fromButton.finishChoice();
        if (untilButton != null) untilButton.finishChoice();
        for (Choice preset : presets) if (preset != null) preset.finishChoice();
    }

    @Override
    public void dismiss() {
        retireTransitions();
        if (!motionEnabled()) skipDismissAnimation();
        super.dismiss();
    }

    @Override
    public void dismissInternal() {
        retireTransitions();
        super.dismissInternal();
    }

    public static String formatRange(MessageSearchDateRange dates, TimeZone timeZone) {
        if (!dates.isActive()) return LocaleController.getString(R.string.NM_SearchDatesReset);
        String first = formatDay(dates.getStartDayMillis(), timeZone);
        return dates.getStartDayMillis() == dates.getEndDayMillis() ? first
                : LocaleController.formatString(R.string.NM_SearchDatesRange, first, formatDay(dates.getEndDayMillis(), timeZone));
    }

    private static String formatDay(long day, TimeZone timeZone) {
        DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM, LocaleController.getInstance().getCurrentLocale());
        format.setTimeZone(timeZone);
        return format.format(new Date(day));
    }
}
