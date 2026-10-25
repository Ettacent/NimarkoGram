/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.media;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.function.IntConsumer;

public final class MediaSendChoiceView extends FrameLayout {
    public static final int MEDIA = 1, LINK = 2, VIDEO = 3, AUDIO = 4;
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout content;
    private final ArrayList<ChoiceButton> choices = new ArrayList<>(3);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private MediaDownloader.Platform platform;
    private boolean allowLink;
    private IntConsumer callback;
    private float reveal;

    public MediaSendChoiceView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setClipChildren(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        content = new LinearLayout(context);
        content.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.setLayoutDirection(LocaleController.isRTL ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
        addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        updateColors();
    }

    public void bind(MediaDownloader.Platform platform, int selected, IntConsumer callback) {
        bind(platform, selected, true, callback);
    }

    public void bind(MediaDownloader.Platform platform, int selected, boolean allowLink, IntConsumer callback) {
        this.callback = callback;
        if (this.platform != platform || this.allowLink != allowLink) {
            for (ChoiceButton button : choices) button.finishAnimation();
            this.platform = platform;
            this.allowLink = allowLink;
            content.removeAllViews();
            choices.clear();
            if (platform == MediaDownloader.Platform.YOUTUBE) {
                addChoice(R.string.NM_MediaSendChoiceVideo, R.drawable.msg_played, VIDEO);
                addChoice(R.string.NM_MediaSendChoiceAudio, R.drawable.msg_tone_on, AUDIO);
            } else {
                addChoice(platform.isAudioOnly() ? R.string.NM_MediaSendChoiceAudio : R.string.NM_MediaSendChoiceMedia,
                        platform.isAudioOnly() ? R.drawable.msg_tone_on : R.drawable.msg_gallery, MEDIA);
            }
            if (allowLink) addChoice(R.string.NM_MediaSendChoiceLink, R.drawable.msg_link, LINK);
        }
        setSelection(selected, false);
        setReveal(reveal);
        updateColors();
    }

    private void addChoice(int label, int icon, int choice) {
        ChoiceButton button = new ChoiceButton(getContext(), label, icon, choice);
        button.setOnClickListener(v -> {
            IntConsumer action = callback;
            if (action != null && (choice != LINK || allowLink)) action.accept(choice);
        });
        choices.add(button);
        content.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    public void setSelection(int selected, boolean animated) {
        for (ChoiceButton button : choices) button.select(button.choice == selected, animated);
    }

    public void clearActions() {
        callback = null;
        setReveal(reveal);
    }

    public void setReveal(float value) {
        reveal = Math.max(0, Math.min(1, value));
        content.setAlpha(reveal);
        content.setTranslationY(dp(4) * (1 - reveal));
        boolean interactive = callback != null && reveal >= .99f;
        content.setImportantForAccessibility(interactive ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (ChoiceButton button : choices) button.setEnabled(interactive);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int contentWidth = width;
        int count = Math.max(1, choices.size());
        int available = Math.max(0, contentWidth - dp(8) - dp(4) * (count - 1));
        int cellWidth = available / count;
        boolean icons = true, stacked = false;
        for (ChoiceButton button : choices) {
            icons &= button.requiredWidth(true) <= cellWidth;
            stacked |= button.requiredWidth(false) > cellWidth;
        }
        content.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < choices.size(); i++) {
            ChoiceButton button = choices.get(i);
            button.showIcon(stacked || icons);
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) button.getLayoutParams();
            int w = stacked ? LayoutParams.MATCH_PARENT : 0;
            float weight = stacked ? 0 : 1;
            int start = !stacked && i != 0 ? dp(4) : 0;
            int top = stacked && i != 0 ? dp(4) : 0;
            if (p.width != w || p.weight != weight || p.getMarginStart() != start || p.topMargin != top) {
                p.width = w;
                p.weight = weight;
                p.setMarginStart(start);
                p.topMargin = top;
                button.setLayoutParams(p);
            }
        }
        content.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(width, Math.round(content.getMeasuredHeight() * reveal));
    }

    public void updateColors() {
        for (ChoiceButton button : choices) button.updateColors();
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (getHeight() <= 0 || reveal <= 0f) return;
        int color = Theme.getColor(Theme.key_chat_messagePanelText, resourcesProvider);
        dividerPaint.setColor(ColorUtils.setAlphaComponent(color,
                Math.round(Color.alpha(color) * .10f * reveal)));
        canvas.drawRect(dp(8), Math.max(0, getHeight() - dp(1)),
                Math.max(dp(8), getWidth() - dp(8)), getHeight(), dividerPaint);
    }

    private final class ChoiceButton extends TextView {
        final int choice;
        final Drawable icon;
        final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ValueAnimator animator;
        float selection;
        boolean iconShown;

        ChoiceButton(Context context, int label, int resource, int choice) {
            super(context);
            this.choice = choice;
            setText(LocaleController.getString(label));
            setTextSize(13);
            setTypeface(AndroidUtilities.bold());
            setGravity(Gravity.CENTER);
            setSingleLine(true);
            setMinHeight(dp(48));
            setMinWidth(dp(48));
            setPadding(dp(4), dp(10), dp(4), dp(10));
            setCompoundDrawablePadding(dp(6));
            setFocusable(true);
            setBackground(null);
            setContentDescription(LocaleController.getString(R.string.NM_MediaSendChoiceTitle)
                    + ": " + getText());
            icon = context.getDrawable(resource).mutate();
            float scale = (float) dp(20) / Math.max(1, Math.max(icon.getIntrinsicWidth(), icon.getIntrinsicHeight()));
            icon.setBounds(0, 0, Math.max(1, Math.round(icon.getIntrinsicWidth() * scale)),
                    Math.max(1, Math.round(icon.getIntrinsicHeight() * scale)));
        }

        int requiredWidth(boolean withIcon) {
            return Math.max(dp(48), (int) Math.ceil(getPaint().measureText(getText().toString())) + dp(8)
                    + (withIcon ? icon.getBounds().width() + dp(6) : 0));
        }

        void showIcon(boolean show) {
            if (iconShown == show) return;
            iconShown = show;
            setCompoundDrawablesRelative(show ? icon : null, null, null, null);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int padding = dp(4);
            if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY && iconShown) {
                int contentWidth = (int) Math.ceil(getPaint().measureText(getText().toString()))
                        + icon.getBounds().width() + getCompoundDrawablePadding();
                padding = Math.max(padding, (MeasureSpec.getSize(widthSpec) - contentWidth) / 2);
            }
            if (getPaddingLeft() != padding || getPaddingRight() != padding) {
                setPadding(padding, dp(10), padding, dp(10));
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        void select(boolean selected, boolean animated) {
            float target = selected ? 1 : 0;
            if (isSelected() == selected && (selection == target
                    || animator != null && animator.isRunning())) return;
            setSelected(selected);
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animated || !isAttachedToWindow() || !SharedConfig.animationsEnabled()) {
                selection = target;
                applyColors();
                return;
            }
            animator = ValueAnimator.ofFloat(selection, target);
            animator.setDuration(240);
            animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
            animator.addUpdateListener(a -> {
                selection = (float) a.getAnimatedValue();
                applyColors();
            });
            animator.start();
        }

        void updateColors() {
            applyColors();
        }

        void applyColors() {
            int accent = Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
            int text = Theme.getColor(Theme.key_chat_messagePanelText, resourcesProvider);
            int secondary = Theme.getColor(Theme.key_chat_messagePanelIcons, resourcesProvider);
            indicatorPaint.setColor(ColorUtils.setAlphaComponent(accent,
                    Math.round(Color.alpha(accent) * selection)));
            setTextColor(text);
            Theme.setDrawableColor(icon, ColorUtils.blendARGB(secondary, accent, selection));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (selection > 0f) {
                int save = canvas.save();
                canvas.translate(getScrollX(), getScrollY());
                float width = Math.min(dp(24), Math.max(0, getWidth() - dp(16)))
                        * (.65f + .35f * selection);
                float left = (getWidth() - width) * .5f;
                canvas.drawRoundRect(left, getHeight() - dp(4), left + width,
                        getHeight() - dp(2), dp(1), dp(1), indicatorPaint);
                canvas.restoreToCount(save);
            }
            super.onDraw(canvas);
        }

        void finishAnimation() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            selection = isSelected() ? 1 : 0;
            applyColors();
        }

        @Override
        protected void onDetachedFromWindow() {
            finishAnimation();
            super.onDetachedFromWindow();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(isSelected());
        }
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
