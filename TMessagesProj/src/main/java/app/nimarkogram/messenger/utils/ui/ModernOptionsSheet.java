package app.nimarkogram.messenger.utils.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;
import java.util.function.IntConsumer;

public final class ModernOptionsSheet {

    private static final int HORIZONTAL_INSET_DP = 10;

    private ModernOptionsSheet() {
    }

    public static BottomSheet showChoices(
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            CharSequence title,
            CharSequence[] items,
            int[] icons,
            int selected,
            boolean showSelection,
            IntConsumer listener
    ) {
        if (context == null || items == null || items.length == 0) {
            return null;
        }

        LinearLayout rows = createRowsContainer(context);
        final BottomSheet[] sheet = new BottomSheet[1];
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) {
                continue;
            }
            final int index = i;
            BottomSheet.BottomSheetCell cell = new BottomSheet.BottomSheetCell(context, 0, resourcesProvider);
            cell.setTextAndIcon(items[i], icons != null && i < icons.length ? icons[i] : 0);
            boolean checked = showSelection && selected == i;
            cell.setChecked(checked);
            cell.isSelected = checked;
            applyCardBackground(cell, checked, resourcesProvider);
            cell.setOnClickListener(v -> {
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
                if (listener != null) {
                    listener.accept(index);
                }
            });
            rows.addView(cell, createCardParams(48));
        }

        sheet[0] = createSheet(context, resourcesProvider, title, rows, items.length, 52);
        sheet[0].show();
        return sheet[0];
    }

    public static BottomSheet showChoicesWithDescriptions(
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            CharSequence title,
            List<String> labels,
            List<String> descriptions,
            int selected,
            IntConsumer listener
    ) {
        if (context == null || labels == null || descriptions == null) {
            return null;
        }
        int count = Math.min(labels.size(), descriptions.size());
        if (count == 0) {
            return null;
        }

        LinearLayout rows = createRowsContainer(context);
        final BottomSheet[] sheet = new BottomSheet[1];
        for (int i = 0; i < count; i++) {
            final int index = i;
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
            cell.setCheckColor(
                    Theme.getColor(Theme.key_radioBackground, resourcesProvider),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            cell.setTextAndText2AndValue(labels.get(i), descriptions.get(i), selected == i);
            applyCardBackground(cell, selected == i, resourcesProvider);
            cell.setOnClickListener(v -> {
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
                if (listener != null) {
                    listener.accept(index);
                }
            });
            rows.addView(cell, createCardParams(64));
        }

        sheet[0] = createSheet(context, resourcesProvider, title, rows, count, 68);
        sheet[0].show();
        return sheet[0];
    }

    public static BottomSheet showSwitches(
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            CharSequence title,
            List<String> labels,
            List<Integer> icons,
            List<Boolean> initialChecks,
            List<Boolean> checkInvisible,
            List<Boolean> dividers,
            List<Runnable> listeners,
            Runnable dismissRunnable
    ) {
        if (context == null || labels == null || initialChecks == null || listeners == null) {
            return null;
        }
        int count = Math.min(labels.size(), Math.min(initialChecks.size(), listeners.size()));
        if (count == 0) {
            return null;
        }

        boolean[] state = new boolean[count];
        LinearLayout rows = createRowsContainer(context);
        for (int i = 0; i < count; i++) {
            final int index = i;
            state[i] = Boolean.TRUE.equals(initialChecks.get(i));
            TextCell cell = new TextCell(context, 23, false, true, resourcesProvider);
            int icon = icons != null && i < icons.size() && icons.get(i) != null ? icons.get(i) : 0;
            boolean divider = dividers != null && i < dividers.size() && Boolean.TRUE.equals(dividers.get(i));
            cell.setTextAndCheckAndIcon(labels.get(i), state[i], icon, divider);
            if (checkInvisible != null && i < checkInvisible.size() && Boolean.TRUE.equals(checkInvisible.get(i)) && cell.getCheckBox() != null) {
                cell.getCheckBox().setVisibility(View.INVISIBLE);
            }
            applyCardBackground(cell, state[i], resourcesProvider);
            cell.setOnClickListener(v -> {
                state[index] = !state[index];
                cell.setChecked(state[index]);
                applyCardBackground(cell, state[index], resourcesProvider);
                Runnable action = listeners.get(index);
                if (action != null) {
                    action.run();
                }
            });
            rows.addView(cell, createCardParams(56));
        }

        BottomSheet sheet = createSheet(context, resourcesProvider, title, rows, count, 60);
        if (dismissRunnable != null) {
            sheet.setOnDismissListener(dialog -> dismissRunnable.run());
        }
        sheet.show();
        return sheet;
    }

    private static LinearLayout createRowsContainer(Context context) {
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(10));
        return rows;
    }

    private static LinearLayout.LayoutParams createCardParams(int heightDp) {
        return LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                heightDp,
                Gravity.FILL_HORIZONTAL,
                HORIZONTAL_INSET_DP,
                2,
                HORIZONTAL_INSET_DP,
                2);
    }

    private static void applyCardBackground(View view, boolean selected, Theme.ResourcesProvider resourcesProvider) {
        int background = Theme.getColor(Theme.key_dialogBackgroundGray, resourcesProvider);
        if (selected) {
            background = ColorUtils.blendARGB(
                    background,
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider),
                    0.12f);
        }
        view.setBackground(Theme.AdaptiveRipple.filledRect(background, 14));
    }

    private static BottomSheet createSheet(
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            CharSequence title,
            LinearLayout rows,
            int rowCount,
            int rowHeightDp
    ) {
        int displayHeight = Math.max(AndroidUtilities.dp(320), AndroidUtilities.displaySize.y);
        int maxHeight = Math.max(AndroidUtilities.dp(144), displayHeight - AndroidUtilities.dp(176));
        int contentHeight = Math.min(AndroidUtilities.dp(rowCount * rowHeightDp + 12), maxHeight);

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY));
            }
        };
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(rowCount * rowHeightDp > contentHeight ? View.OVER_SCROLL_IF_CONTENT_SCROLLS : View.OVER_SCROLL_NEVER);
        scrollView.addView(rows, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider)
                .setApplyTopPadding(false)
                .setApplyBottomPadding(false)
                .setCustomView(scrollView);
        if (!TextUtils.isEmpty(title)) {
            builder.setTitle(title, true).setTitleMultipleLines(true);
        }
        return builder.create();
    }
}
