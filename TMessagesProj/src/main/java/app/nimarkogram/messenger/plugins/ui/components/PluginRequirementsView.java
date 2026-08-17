package app.nimarkogram.messenger.plugins.ui.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ScaleStateListAnimator;

public final class PluginRequirementsView extends ViewGroup {
    private static final Pattern REQUIREMENT_PATTERN = Pattern.compile("^([a-zA-Z0-9_\\-.]+)");

    private final Theme.ResourcesProvider resourcesProvider;
    private final int itemSpacing;
    private final int lineSpacing;

    public PluginRequirementsView(Context context) {
        this(context, null);
    }

    public PluginRequirementsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.itemSpacing = AndroidUtilities.dp(4f);
        this.lineSpacing = AndroidUtilities.dp(4f);
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public void setRequirements(List<String> requirements) {
        removeAllViews();
        if (requirements == null || requirements.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        for (final String requirement : requirements) {
            final TextView textView = new TextView(getContext());
            textView.setText(requirement);
            textView.setTextSize(1, 12.0f);
            int themedColor = getThemedColor(Theme.key_featuredStickers_addButton);
            textView.setTextColor(themedColor);
            textView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
            textView.setBackground(Theme.createRoundRectDrawable(
                    AndroidUtilities.dp(6f),
                    ColorUtils.setAlphaComponent(themedColor, 30)));
            textView.setPadding(
                    AndroidUtilities.dp(6f),
                    AndroidUtilities.dp(2f),
                    AndroidUtilities.dp(6f),
                    AndroidUtilities.dp(2f));
            ScaleStateListAnimator.apply(textView);
            textView.setOnClickListener(v -> {
                Matcher matcher = REQUIREMENT_PATTERN.matcher(requirement);
                if (matcher.find()) {
                    Browser.openUrl(textView.getContext(),
                            "https://pypi.org/project/" + matcher.group(1) + '/');
                }
            });
            addView(textView);
        }
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = MeasureSpec.getSize(widthMeasureSpec);
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int availableWidth = size - getPaddingLeft() - getPaddingRight();
        int yOffset = getPaddingTop();
        int childCount = getChildCount();
        int lineHeight = 0;
        int maxLineWidth = 0;
        int xOffset = 0;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (xOffset + w > availableWidth) {
                yOffset += lineHeight + lineSpacing;
                lineHeight = 0;
                xOffset = 0;
            }
            xOffset += w + itemSpacing;
            lineHeight = Math.max(lineHeight, h);
            maxLineWidth = Math.max(maxLineWidth, xOffset - itemSpacing);
        }
        int totalHeight = yOffset + lineHeight + getPaddingBottom();
        if (mode != MeasureSpec.EXACTLY) {
            size = maxLineWidth + getPaddingLeft() + getPaddingRight();
        }
        setMeasuredDimension(size, totalHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int xOffset = getPaddingLeft();
        int yOffset = getPaddingTop();
        int childCount = getChildCount();
        int lineHeight = 0;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (xOffset + w > width - getPaddingRight()) {
                xOffset = getPaddingLeft();
                yOffset += lineHeight + lineSpacing;
                lineHeight = 0;
            }
            child.layout(xOffset, yOffset, xOffset + w, yOffset + h);
            xOffset += w + itemSpacing;
            lineHeight = Math.max(lineHeight, h);
        }
    }
}
