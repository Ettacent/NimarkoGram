 
package app.nimarkogram.messenger.preferences.components;

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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

import app.nimarkogram.messenger.NimarkoConfig;

@SuppressLint("ViewConstructor")
public class StickerSizeCell extends FrameLayout {

    private final AnimatedTextView valueChip;
    private final TextView leftLabel;
    private final TextView rightLabel;
    private final SeekBarView seekBar;
    private StickerSizePreviewCell preview;

    private final int min = (int) NimarkoConfig.STICKER_SIZE_MIN;
    private final int max = (int) NimarkoConfig.STICKER_SIZE_MAX;
    private int lastHaptic = Integer.MIN_VALUE;

    public StickerSizeCell(Context context, BaseFragment fragment) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        
        setClipChildren(false);
        setClipToPadding(false);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.START);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        title.setText(getString(R.string.NM_StickerSize));
        header.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        valueChip = new AnimatedTextView(context, false, true, true) {
            final Drawable bg = Theme.createRoundRectDrawable(AndroidUtilities.dp(4),
                    Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader), 0.15f));
            @Override
            protected void onDraw(Canvas canvas) {
                bg.setBounds(0, 0, (int) (getPaddingLeft() + getDrawable().getCurrentWidth() + getPaddingRight()), getMeasuredHeight());
                bg.draw(canvas);
                super.onDraw(canvas);
            }
        };
        valueChip.setAnimationProperties(.45f, 0, 240, CubicBezierInterpolator.EASE_OUT_QUINT);
        valueChip.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        valueChip.setPadding(AndroidUtilities.dp(5.33f), AndroidUtilities.dp(2), AndroidUtilities.dp(5.33f), AndroidUtilities.dp(2));
        valueChip.setTextSize(AndroidUtilities.dp(12));
        valueChip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        header.addView(valueChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 17, Gravity.CENTER_VERTICAL, 6, 1, 0, 0));

        addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 21, 15, 21, 0));

        seekBar = new SeekBarView(context, true, null);
        seekBar.setReportChanges(true);
        seekBar.setLineWidth(6); 
        seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                if (LocaleController.isRTL) progress = 1f - progress;
                float value = min + (max - min) * progress;
                NimarkoConfig.setStickerSize(value);
                onValueChanged(Math.round(value));
                
                if (preview != null) preview.update();
            }
            @Override
            public void onSeekBarPressed(boolean pressed) {}
            @Override public CharSequence getContentDescription() {
                return getString(R.string.NM_StickerSize) + ": " + Math.round(NimarkoConfig.stickerSize);
            }
            @Override public int getStepsCount() { return max - min; }
            @Override public boolean isAccessibilityProgressInverted() { return LocaleController.isRTL; }
        });
        addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.TOP, 6, 44, 6, 0));

        leftLabel = new TextView(context);
        leftLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        leftLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        leftLabel.setText(getString(R.string.NM_StickerSizeSmall));
        addView(leftLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START, 21, 84, 21, 0));

        rightLabel = new TextView(context);
        rightLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rightLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        rightLabel.setText(getString(R.string.NM_StickerSizeBig));
        addView(rightLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.END, 21, 84, 21, 0));

        try {
            preview = new StickerSizePreviewCell(context, fragment,
                    fragment != null ? fragment.getParentLayout() : null);
            addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.FILL_HORIZONTAL, 0, 112, 0, 0));
        } catch (Throwable ignore) {
            
            preview = null;
        }

        float progress = (NimarkoConfig.stickerSize - min) / (float) (max - min);
        seekBar.setProgress(LocaleController.isRTL ? 1f - progress : progress);
        onValueChanged(Math.round(NimarkoConfig.stickerSize), false);
    }

    public void refreshPreview() {
        if (preview != null) preview.update();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        
        if (preview != null && preview.getVisibility() != GONE) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            preview.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int needed = AndroidUtilities.dp(112) + preview.getMeasuredHeight();
            
            setMeasuredDimension(getMeasuredWidth(), needed);
        }
    }

    private void onValueChanged(int value) {
        onValueChanged(value, true);
    }

    private void onValueChanged(int value, boolean animated) {
        CharSequence text;
        if (value <= min) {
            text = leftLabel.getText();
        } else if (value >= max) {
            text = rightLabel.getText();
        } else {
            text = String.valueOf(value);
        }
        valueChip.cancelAnimation();
        valueChip.setText(text.toString().toUpperCase(), animated);
        
        if (value != lastHaptic) {
            try {
                performHapticFeedback(
                        (value == min || value == max) ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.CLOCK_TICK,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {}
            lastHaptic = value;
        }
    }

    private static String getString(int res) {
        return LocaleController.getString(res);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (seekBar != null) seekBar.invalidate();
    }
}
