package app.nimarkogram.messenger.components;

import android.annotation.SuppressLint;
import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import com.exteragram.messenger.utils.text.TranslatorUtils;

@SuppressLint("ViewConstructor")
public abstract class TranslateBeforeSendWrapper extends ActionBarMenuSubItem {

    public TranslateBeforeSendWrapper(final Context context, boolean top, boolean bottom,
                                      Theme.ResourcesProvider resourcesProvider) {
        super(context, top, bottom, resourcesProvider);
        setTextAndIcon(LocaleController.getString(R.string.TranslateTo), R.drawable.msg_translate);
        setSubtext(getCurrentTargetTitle());
        setMinimumWidth(AndroidUtilities.dp(196.0f));
        setItemHeight(56);
        setOnClickListener(view -> onClick());
        setOnLongClickListener(view -> showDialog(context));
        setRightIcon(R.drawable.msg_arrowright);
        if (getRightIcon() != null) {
            getRightIcon().setOnClickListener(view -> showDialog(context));
        }
    }

    private static String getCurrentTargetTitle() {
        try {
            return TranslatorUtils.getTargetLanguageTitle();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean showDialog(Context context) {
        try {
            CharSequence[] titles = TranslatorUtils.getTargetLanguageTitles();
            if (titles == null) {
                titles = new CharSequence[0];
            }
            final CharSequence[] items = new CharSequence[titles.length];
            System.arraycopy(titles, 0, items, 0, titles.length);

            AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
            builder.setTitle(LocaleController.getString(R.string.Language));
            builder.setItems(items, (dialog, which) -> {
                TranslatorUtils.setTargetLanguage(TranslatorUtils.getTargetLanguageCodeByIndex(which));
                setSubtext(getCurrentTargetTitle());
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.show();
        } catch (Throwable ignored) {
        }
        return true;
    }

    public abstract void onClick();
}
