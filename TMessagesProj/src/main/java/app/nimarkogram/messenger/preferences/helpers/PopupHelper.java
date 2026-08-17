package app.nimarkogram.messenger.preferences.helpers;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import app.nimarkogram.messenger.utils.ui.ModernOptionsSheet;

public final class PopupHelper {

    private PopupHelper() {}

    public static void show(ArrayList<? extends CharSequence> entries, String title, int checkedIndex, Context context, IntConsumer listener) {
        if (context == null || entries == null) {
            return;
        }
        showSimpleAlert(context, null, title, entries.toArray(new CharSequence[0]), checkedIndex, listener);
    }

    public static void show(ArrayList<? extends CharSequence> entries, String title, int checkedIndex, Context context, IntConsumer listener, Theme.ResourcesProvider resourcesProvider) {
        if (context == null || entries == null) {
            return;
        }
        showSimpleAlert(context, resourcesProvider, title, entries.toArray(new CharSequence[0]), checkedIndex, listener);
    }

    public static void showLegacy(ArrayList<? extends CharSequence> entries, String title, int checkedIndex, Context context, IntConsumer listener) {
        if (context == null || entries == null || entries.isEmpty()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        builder.setView(rows);

        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(
                    Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(entries.get(i), checkedIndex == i);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            cell.setOnClickListener((View v) -> {
                builder.getDismissRunnable().run();
                if (listener != null) {
                    listener.accept(index);
                }
            });
            rows.addView(cell);
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    public static void show(
            String title,
            ArrayList<String> prefTitle,
            ArrayList<String> prefDesc,
            int checkedIndex,
            Context context,
            OnItemClickListener listener,
            Theme.ResourcesProvider resourcesProvider
    ) {
        if (context == null || prefTitle == null || prefDesc == null) {
            return;
        }
        ModernOptionsSheet.showChoicesWithDescriptions(
                context,
                resourcesProvider,
                title,
                prefTitle,
                prefDesc,
                checkedIndex,
                listener == null ? null : listener::onClick);
    }

    public interface OnItemClickListener {
        void onClick(int i);
    }

    public static void showSimpleAlert(BaseFragment fragment, String title, CharSequence[] options, int current, IntConsumer onSelected) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        showSimpleAlert(fragment.getParentActivity(), fragment.getResourceProvider(), title, options, current, onSelected);
    }

    public static void showSimpleAlert(Context context, Theme.ResourcesProvider resourceProvider, String title, CharSequence[] options, int current, IntConsumer onSelected) {
        if (context == null || options == null || options.length == 0) {
            return;
        }
        ModernOptionsSheet.showChoices(
                context,
                resourceProvider,
                title,
                options,
                null,
                current,
                true,
                onSelected);
    }

    public static void showSwitchAlert(
            String title,
            BaseFragment fragment,
            List<String> labels,
            List<Integer> icons,
            List<Boolean> initialChecks,
            List<Boolean> checkInvisible,
            List<Boolean> donateLock,
            List<Boolean> dividers,
            List<Runnable> listeners,
            Runnable dismissRunnable
    ) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (labels == null || labels.isEmpty() || initialChecks == null || listeners == null) {
            return;
        }
        ModernOptionsSheet.showSwitches(
                fragment.getParentActivity(),
                fragment.getResourceProvider(),
                title,
                labels,
                icons,
                initialChecks,
                checkInvisible,
                dividers,
                listeners,
                dismissRunnable);
    }

    public static void showSwitchAlert(
            String title,
            BaseFragment fragment,
            List<String> labels,
            List<Boolean> initialChecks,
            List<Runnable> listeners
    ) {
        showSwitchAlert(title, fragment, labels, null, initialChecks, null, null, null, listeners, null);
    }
}
