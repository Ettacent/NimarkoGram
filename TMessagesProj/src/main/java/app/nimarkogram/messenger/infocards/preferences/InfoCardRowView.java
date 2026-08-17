package app.nimarkogram.messenger.infocards.preferences;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import app.nimarkogram.messenger.infocards.InfoCardRegistry;
import app.nimarkogram.messenger.utils.ui.MonetHelper;

public class InfoCardRowView extends FrameLayout {

    public interface Listener {
        void onToggle(int pillId, boolean active);
        void onBodyTap(int pillId);
    }

    private final Theme.ResourcesProvider rp;
    private final FrameLayout badge;
    private final ImageView badgeIcon;
    private final TextView title;
    private final TextView value;
    private final Switch switchView;

    private int pillId = -1;
    private boolean active;
    private Listener listener;

    public InfoCardRowView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.rp = resourcesProvider;
        setMinimumHeight(dp(56));
        setClipChildren(false);

        badge = new FrameLayout(context);
        LayoutParams badgeParams = LayoutHelper.createFrame(38, 38, Gravity.CENTER_VERTICAL | Gravity.START);
        badgeParams.setMarginStart(dp(16));
        addView(badge, badgeParams);
        badgeIcon = new ImageView(context);
        badgeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        badgeIcon.setColorFilter(MonetHelper.getSettingsIconForegroundColor(0xffffffff));
        badge.addView(badgeIcon, LayoutHelper.createFrame(20, 20, Gravity.CENTER));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LayoutParams textParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START);
        textParams.setMarginStart(dp(66));
        textParams.setMarginEnd(dp(62));
        addView(textColumn, textParams);

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textColumn.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        value.setMaxLines(1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textColumn.addView(value, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        switchView = new Switch(context, rp);
        switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked,
                Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        LayoutParams switchParams = LayoutHelper.createFrame(37, 20, Gravity.CENTER_VERTICAL | Gravity.END);
        switchParams.setMarginEnd(dp(18));
        addView(switchView, switchParams);
        switchView.setOnClickListener(v -> {
            boolean next = !active;
            active = next;
            switchView.setChecked(next, true);
            if (listener != null) listener.onToggle(pillId, next);
        });

        setOnClickListener(v -> {
            if (listener != null) listener.onBodyTap(pillId);
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void bind(InfoCardRegistry.CardInfo info, boolean active, CharSequence valueText,
                     boolean draggable, boolean clickable) {
        this.pillId = info.id;
        this.active = active;

        int badgeTop = MonetHelper.getSettingsIconBackgroundColor(info.colorTop);
        int badgeBottom = MonetHelper.getSettingsIconBackgroundColor(info.colorBottom);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{badgeTop, badgeBottom});
        g.setCornerRadius(dp(11));
        badge.setBackground(g);
        badgeIcon.setImageResource(info.iconRes);
        badgeIcon.setColorFilter(MonetHelper.getSettingsIconForegroundColor(0xffffffff));

        title.setText(info.getName());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        value.setText(valueText);
        value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        value.setVisibility(android.text.TextUtils.isEmpty(valueText) ? GONE : VISIBLE);

        switchView.setChecked(active, false);
        switchView.setContentDescription(info.getName());
        setContentDescription(android.text.TextUtils.isEmpty(valueText)
                ? info.getName() : info.getName() + ": " + valueText);

        setClickable(clickable);
        setBackground(clickable ? Theme.getSelectorDrawable(false, rp) : null);

        float alpha = active ? 1f : 0.55f;
        badge.setAlpha(alpha);
        title.setAlpha(alpha);
        value.setAlpha(alpha);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (isClickable()) info.setClassName("android.widget.Button");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
    }
}
