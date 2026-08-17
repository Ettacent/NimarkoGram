package app.nimarkogram.messenger.preferences.cells;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

public class StickerSliderCell extends LinearLayout {
    private final SeekBarView sizeBar;
    private final TextView titleView;
    private final TextView valueView;
    private final TextView hintView;
    private TGSLContract contract;
    private int startRadius;
    private int endRadius;

    public StickerSliderCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(16),
                Theme.getColor(Theme.key_dialogBackgroundGray, resourcesProvider)));

        FrameLayout header = new FrameLayout(context);
        addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38));

        titleView = new TextView(context);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        FrameLayout.LayoutParams titleParams = LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        titleParams.setMarginEnd(AndroidUtilities.dp(72));
        header.addView(titleView, titleParams);

        valueView = new TextView(context);
        valueView.setGravity(Gravity.CENTER);
        valueView.setMinWidth(AndroidUtilities.dp(56));
        valueView.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(2), AndroidUtilities.dp(9), AndroidUtilities.dp(2));
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        int accent = Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider);
        valueView.setTextColor(accent);
        valueView.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(12),
                ColorUtils.blendARGB(
                        Theme.getColor(Theme.key_dialogBackgroundGray, resourcesProvider),
                        accent,
                        0.14f)));
        header.addView(valueView, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT,
                28,
                Gravity.END | Gravity.CENTER_VERTICAL));

        sizeBar = new SeekBarView(context, resourcesProvider);
        sizeBar.setReportChanges(true);
        sizeBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                if (LocaleController.isRTL) progress = 1f - progress;
                contract.setValue(Math.round(startRadius + (endRadius - startRadius) * progress));
                updateValue();
            }

            @Override
            public void onSeekBarPressed(boolean pressed) {
            }
            @Override public CharSequence getContentDescription() {
                if (contract == null) return null;
                CharSequence value = contract.formatValue(contract.getPreferenceValue());
                CharSequence label = contract.getAccessibilityLabel();
                return label == null || label.length() == 0 ? value : label + ": " + value;
            }
            @Override public int getStepsCount() { return Math.max(0, endRadius - startRadius); }
            @Override public boolean isAccessibilityProgressInverted() { return LocaleController.isRTL; }
        });
        addView(sizeBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38));

        hintView = new TextView(context);
        hintView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hintView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        hintView.setVisibility(GONE);
        addView(hintView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.FILL_HORIZONTAL,
                4,
                4,
                4,
                0));
    }

    public StickerSliderCell setTitle(CharSequence title) {
        titleView.setText(title);
        return this;
    }

    public StickerSliderCell setHint(CharSequence hint) {
        hintView.setText(hint);
        hintView.setVisibility(TextUtils.isEmpty(hint) ? GONE : VISIBLE);
        return this;
    }

    public StickerSliderCell setContract(TGSLContract contract) {
        this.contract = contract;
        this.startRadius = contract.getMin();
        this.endRadius = contract.getMax();
        updateValue();
        updateProgress();
        return this;
    }

    private void updateValue() {
        if (contract == null) {
            return;
        }
        valueView.setText(contract.formatValue(contract.getPreferenceValue()));
    }

    private void updateProgress() {
        if (contract == null || endRadius <= startRadius) {
            return;
        }
        float progress = (contract.getPreferenceValue() - startRadius) / (float) (endRadius - startRadius);
        progress = Math.max(0f, Math.min(1f, progress));
        sizeBar.setProgress(LocaleController.isRTL ? 1f - progress : progress);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        updateProgress();
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    public interface TGSLContract {
        void setValue(int value);

        int getPreferenceValue();

        int getMin();

        int getMax();

        default CharSequence getAccessibilityLabel() {
            return null;
        }

        default CharSequence formatValue(int value) {
            return String.valueOf(value);
        }
    }
}
