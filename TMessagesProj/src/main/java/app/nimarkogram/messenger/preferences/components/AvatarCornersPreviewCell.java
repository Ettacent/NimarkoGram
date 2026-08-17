
package app.nimarkogram.messenger.preferences.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Region;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

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
public class AvatarCornersPreviewCell extends FrameLayout {

    private final BaseFragment fragment;

    private final AnimatedTextView valueChip;
    private final TextView leftLabel;
    private final TextView rightLabel;
    private final SeekBarView seekBar;

    private final int min = (int) NimarkoConfig.AVATAR_CORNERS_MIN;
    private final int max = (int) NimarkoConfig.AVATAR_CORNERS_MAX;
    private int lastHaptic = Integer.MIN_VALUE;

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint onlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint initialsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();
    private LinearGradient avatarGradient;
    private int gradientForHeight = -1;
    private int gradientForColor = 0;

    private boolean needDivider;

    public AvatarCornersPreviewCell(Context context, BaseFragment fragment) {
        super(context);
        this.fragment = fragment;
        setWillNotDraw(false);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.START);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        title.setText(getString(R.string.NM_AvatarCorners));
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
                NimarkoConfig.setAvatarCorners(value);
                onValueChanged(Math.round(value));
                invalidate();
                if (stop) {
                    applyToWholeApp();
                }
            }
            @Override
            public void onSeekBarPressed(boolean pressed) {}
            @Override public CharSequence getContentDescription() {
                return getString(R.string.NM_AvatarCorners) + ": " + Math.round(NimarkoConfig.avatarCorners);
            }
            @Override public int getStepsCount() { return max - min; }
            @Override public boolean isAccessibilityProgressInverted() { return LocaleController.isRTL; }
        });
        addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.TOP, 6, 44, 6, 0));

        leftLabel = new TextView(context);
        leftLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        leftLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        leftLabel.setText(getString(R.string.NM_AvatarCornersSquare));
        addView(leftLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START, 21, 84, 21, 0));

        rightLabel = new TextView(context);
        rightLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rightLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        rightLabel.setText(getString(R.string.NM_AvatarCornersCircle));
        addView(rightLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.END, 21, 84, 21, 0));

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_switchTrack), 0x3F));
        outlinePaint.setStrokeWidth(Math.max(2, AndroidUtilities.dp(1f)));

        initialsPaint.setColor(Color.WHITE);
        initialsPaint.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        initialsPaint.setTextAlign(Paint.Align.CENTER);
        initialsPaint.setTextSize(AndroidUtilities.dp(22));

        float progress = (NimarkoConfig.avatarCorners - min) / (float) (max - min);
        seekBar.setProgress(LocaleController.isRTL ? 1f - progress : progress);
        onValueChanged(Math.round(NimarkoConfig.avatarCorners), false);
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
            if (value == min || value == max) {
                try {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignore) {}
            } else {
                try {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignore) {}
            }
            lastHaptic = value;
        }
    }

    private void applyToWholeApp() {
        if (fragment != null && fragment.getParentLayout() != null) {
            fragment.getParentLayout().rebuildAllFragmentViews(false, false);
        }
    }

    private static String getString(int res) {
        return LocaleController.getString(res);
    }

    public void setNeedDivider(boolean needDivider) {
        this.needDivider = needDivider;
        invalidate();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (seekBar != null) seekBar.invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float density = AndroidUtilities.density;
        final int previewTop = AndroidUtilities.dp(116);
        final float left = AndroidUtilities.dp(21);
        final float right = getMeasuredWidth() - AndroidUtilities.dp(21);
        final float top = previewTop;
        final float bottom = getMeasuredHeight() - AndroidUtilities.dp(needDivider ? 22 : 21);

        int track = Theme.getColor(Theme.key_switchTrack);
        cardPaint.setColor(Color.argb(20, Color.red(track), Color.green(track), Color.blue(track)));
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, AndroidUtilities.dp(12), AndroidUtilities.dp(12), cardPaint);
        float s = outlinePaint.getStrokeWidth() / 2f;
        rect.set(left + s, top + s, right - s, bottom - s);
        canvas.drawRoundRect(rect, AndroidUtilities.dp(12), AndroidUtilities.dp(12), outlinePaint);

        final float avSize = AndroidUtilities.dp(56);
        final float avLeft = left + AndroidUtilities.dp(14);
        final float avTop = top + (bottom - top - avSize) / 2f;
        rect.set(avLeft, avTop, avLeft + avSize, avTop + avSize);

        int h = getMeasuredHeight();
        
        int grayBase = Theme.getColor(Theme.key_avatar_backgroundGray);
        if (avatarGradient == null || gradientForHeight != h || gradientForColor != grayBase) {
            int topC = ColorUtils.blendARGB(grayBase, Color.WHITE, 0.12f);
            int bottomC = ColorUtils.blendARGB(grayBase, Color.BLACK, 0.12f);
            avatarGradient = new LinearGradient(0, avTop, 0, avTop + avSize,
                    new int[]{topC, bottomC}, null, Shader.TileMode.CLAMP);
            gradientForHeight = h;
            gradientForColor = grayBase;
        }
        avatarPaint.setShader(avatarGradient);
        
        float r = NimarkoConfig.getAvatarCorners(56);
        
        r = Math.min(r, avSize / 2f);
        canvas.drawRoundRect(rect, r, r, avatarPaint);
        avatarPaint.setShader(null);

        float cy = avTop + avSize / 2f - (initialsPaint.descent() + initialsPaint.ascent()) / 2f;
        canvas.drawText(":)", avLeft + avSize / 2f, cy, initialsPaint);

        float textLeft = avLeft + avSize + AndroidUtilities.dp(14);
        barPaint.setColor(Color.argb(0xCC, Color.red(track), Color.green(track), Color.blue(track)));
        float nameTop = avTop + AndroidUtilities.dp(8);
        rect.set(textLeft, nameTop, textLeft + AndroidUtilities.dp(108), nameTop + AndroidUtilities.dp(12));
        canvas.drawRoundRect(rect, AndroidUtilities.dp(6), AndroidUtilities.dp(6), barPaint);

        barPaint.setColor(Color.argb(0x66, Color.red(track), Color.green(track), Color.blue(track)));
        float subTop = nameTop + AndroidUtilities.dp(22);
        rect.set(textLeft, subTop, right - AndroidUtilities.dp(18), subTop + AndroidUtilities.dp(10));
        canvas.drawRoundRect(rect, AndroidUtilities.dp(5), AndroidUtilities.dp(5), barPaint);

        float dotR = AndroidUtilities.dp(7);
        float dotCx = avLeft + avSize - AndroidUtilities.dp(5);
        float dotCy = avTop + avSize - AndroidUtilities.dp(5);
        canvas.save();
        clipPath.rewind();
        clipPath.addCircle(dotCx, dotCy, dotR + AndroidUtilities.dp(2.5f), Path.Direction.CCW);
        canvas.clipPath(clipPath, Region.Op.DIFFERENCE);
        
        canvas.restore();
        onlinePaint.setColor(Theme.getColor(Theme.key_chats_onlineCircle));
        canvas.drawCircle(dotCx, dotCy, dotR, onlinePaint);

        if (!NimarkoConfig.disableDividers && needDivider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(21),
                    getMeasuredHeight() - 1,
                    getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(21) : 0),
                    getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(116 + 88 + 16), MeasureSpec.EXACTLY));
    }
}
