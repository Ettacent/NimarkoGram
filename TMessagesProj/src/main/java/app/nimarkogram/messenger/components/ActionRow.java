package app.nimarkogram.messenger.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("ViewConstructor")
public class ActionRow extends FrameLayout {
    private final int HORIZONTAL_PADDING_DP = 10;
    private final int ITEM_SIZE_DP = 40;
    private final int GAP_DP = 8;
    private final FrameLayout buttonsView;
    private final List<ActionItem> currentItems = new ArrayList<>();

    public ActionRow(Context context, Theme.ResourcesProvider resourcesProvider, List<ActionItem> items) {
        super(context);
        this.buttonsView = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                for (int i = 0; i < getChildCount(); i++) {
                    int spread = (r - l) - AndroidUtilities.dp((getChildCount() * ITEM_SIZE_DP) + 20);
                    int divisor = Math.max(1, getChildCount() - 1);
                    int x = AndroidUtilities.dp((i * ITEM_SIZE_DP) + HORIZONTAL_PADDING_DP) + ((spread / divisor) * i);
                    int y = AndroidUtilities.dp(GAP_DP);
                    View child = getChildAt(i);
                    child.layout(x, y, child.getMeasuredWidth() + x, child.getMeasuredHeight() + y);
                }
            }
        };
        addView(buttonsView, LayoutHelper.createFrame(-1, -1));
        updateItems(items, resourcesProvider);
    }

    private void addImageButton(Context context, Theme.ResourcesProvider resourcesProvider,
                                FrameLayout parent, final ActionItem item, final int index) {
        final ImageView imageView = new ImageView(context) {
            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                setAlpha(enabled ? 1.0f : 0.5f);
            }
        };
        ScaleStateListAnimator.apply(imageView, 0.15f, 1.5f);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setEnabled(item.enabled);
        imageView.setImageDrawable(ContextCompat.getDrawable(context, item.icon).mutate());
        imageView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        imageView.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider), 1, AndroidUtilities.dp(20.0f)));
        imageView.setOnClickListener(item.action);
        if (item.longAction != null) {
            imageView.setOnLongClickListener(item.longAction);
        }
        imageView.setTag(item);
        imageView.setAlpha(0.0f);
        imageView.setTranslationX(AndroidUtilities.dp(12.0f));
        parent.addView(imageView, LayoutHelper.createFrame(ITEM_SIZE_DP, ITEM_SIZE_DP, 51));
        imageView.post(() -> imageView.animate()
                .alpha(item.enabled ? 1.0f : 0.5f)
                .translationX(0.0f)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .setStartDelay((index * 35L) + 100)
                .setDuration(400L)
                .start());
    }

    public boolean isItemPresent(int icon) {
        for (int i = 0; i < buttonsView.getChildCount(); i++) {
            View child = buttonsView.getChildAt(i);
            if (child instanceof ImageView) {
                Object tag = child.getTag();
                if (tag instanceof ActionItem && ((ActionItem) tag).icon == icon) {
                    return true;
                }
            }
        }
        return false;
    }

    public void updateItems(List<ActionItem> items, Theme.ResourcesProvider resourcesProvider) {
        buttonsView.removeAllViews();
        currentItems.clear();
        List<ActionItem> overflow = new ArrayList<>();
        int shown = 0;
        for (ActionItem item : items) {
            if (!item.enabled || shown >= 4) {
                overflow.add(item);
            } else {
                addImageButton(getContext(), resourcesProvider, buttonsView, item, shown);
                currentItems.add(item);
                shown++;
            }
        }
        for (ActionItem item : overflow) {
            if (shown >= 4) return;
            addImageButton(getContext(), resourcesProvider, buttonsView, item, shown);
            currentItems.add(item);
            shown++;
        }
    }

    public static class ActionItem {
        public View.OnClickListener action;
        public boolean enabled;
        public int icon;
        public View.OnLongClickListener longAction;

        public ActionItem(int icon, boolean enabled, View.OnClickListener action, View.OnLongClickListener longAction) {
            this.icon = icon;
            this.enabled = enabled;
            this.action = action;
            this.longAction = longAction;
        }

        public ActionItem(int icon, boolean enabled, View.OnClickListener action) {
            this(icon, enabled, action, null);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            return obj != null && getClass() == obj.getClass() && this.icon == ((ActionItem) obj).icon;
        }

        @Override
        public int hashCode() {
            return this.icon;
        }
    }
}
