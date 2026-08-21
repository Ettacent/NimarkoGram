package com.exteragram.messenger.preferences.components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.slider.Slider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

@SuppressLint("ViewConstructor")
public class AltSeekbar extends FrameLayout {

    public interface OnDrag {
        void run(float value);
    }

    private final AnimatedTextView headerValue;
    private final TextView leftTextView;
    private final TextView rightTextView;
    public SeekBarView seekBarView;
    public Slider slider;
    private final int min;
    private final int max;
    private float currentValue;
    private int roundedValue;
    private int vibro = Integer.MIN_VALUE;

    public AltSeekbar(Context context, OnDrag onDrag, int min, int max,
                      String title, String left, String right) {
        super(context);
        this.min = min;
        this.max = Math.max(min + 1, max);

        LinearLayout headerLayout = new LinearLayout(context);
        headerLayout.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        header.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        header.setText(title);
        headerLayout.addView(header, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        headerValue = new AnimatedTextView(context, false, true, true) {
            final Drawable background = Theme.createRoundRectDrawable(
                    dp(4), Theme.multAlpha(
                            Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader), 0.15f));

            @Override
            protected void onDraw(Canvas canvas) {
                background.setBounds(0, 0,
                        (int) (getPaddingLeft() + getDrawable().getCurrentWidth()
                                + getPaddingRight()), getMeasuredHeight());
                background.draw(canvas);
                super.onDraw(canvas);
            }
        };
        headerValue.setAnimationProperties(
                .45f, 0, 240, CubicBezierInterpolator.EASE_OUT_QUINT);
        headerValue.setTypeface(AndroidUtilities.getTypeface(
                AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        headerValue.setPadding(dp(5.33f), dp(2), dp(5.33f), dp(2));
        headerValue.setTextSize(dp(12));
        headerValue.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        headerLayout.addView(headerValue, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, 17, Gravity.CENTER_VERTICAL, 6, 1, 0, 0));
        addView(headerLayout, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.FILL_HORIZONTAL, 21, 17, 21, 0));

        seekBarView = new SeekBarView(context, true, null);
        seekBarView.setReportChanges(true);
        seekBarView.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                float value = AltSeekbar.this.min
                        + (AltSeekbar.this.max - AltSeekbar.this.min) * progress;
                updateProgress(progress, true);
                if (onDrag != null) {
                    onDrag.run(value);
                }
            }

            @Override
            public void onSeekBarPressed(boolean pressed) {
            }
        });
        addView(seekBarView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 44, Gravity.TOP, 6, 68, 6, 0));

        FrameLayout values = new FrameLayout(context);
        leftTextView = makeCaption(context, left, Gravity.LEFT);
        rightTextView = makeCaption(context, right, Gravity.RIGHT);
        values.addView(leftTextView, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL));
        values.addView(rightTextView, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        addView(values, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.FILL_HORIZONTAL, 21, 52, 21, 0));

        updateProgress(0.0f, false);
    }

    private static TextView makeCaption(Context context, String text, int gravity) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setGravity(gravity);
        view.setText(text);
        return view;
    }

    public void setProgress(float value) {
        float progress = (value - min) / (float) (max - min);
        updateProgress(Math.max(0.0f, Math.min(1.0f, progress)), false);
    }

    private void updateProgress(float progress, boolean fromUser) {
        currentValue = min + (max - min) * progress;
        roundedValue = Math.round(currentValue);
        seekBarView.setProgress(progress);
        headerValue.cancelAnimation();
        headerValue.setText(getTextForHeader(), fromUser);
        if ((roundedValue == min || roundedValue == max) && roundedValue != vibro) {
            vibro = roundedValue;
            if (fromUser) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        } else if (roundedValue > min && roundedValue < max) {
            vibro = Integer.MIN_VALUE;
        }
        updateCaptionColors();
    }

    private void updateCaptionColors() {
        float progress = (currentValue - min) / (float) (max - min);
        int inactive = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int active = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        leftTextView.setTextColor(progress < 0.5f
                ? ColorUtils.blendARGB(active, inactive, progress * 2.0f) : inactive);
        rightTextView.setTextColor(progress > 0.5f
                ? ColorUtils.blendARGB(inactive, active, (progress - 0.5f) * 2.0f) : inactive);
    }

    public CharSequence getTextForHeader() {
        if (roundedValue == min) {
            return String.valueOf(leftTextView.getText()).toUpperCase();
        }
        if (roundedValue == max) {
            return String.valueOf(rightTextView.getText()).toUpperCase();
        }
        return String.valueOf(roundedValue);
    }

    public void updateStyle() {
        headerValue.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        updateCaptionColors();
        seekBarView.invalidate();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(112), MeasureSpec.EXACTLY));
    }
}
