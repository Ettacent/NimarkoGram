package app.nimarkogram.messenger.utils.ui;

import android.content.Context;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;

import org.telegram.ui.ActionBar.Theme;

public final class PopupUtils {

    private PopupUtils() {
    }

    public interface OnItemClickListener {
        void onClick(int which);
    }

    public static void showDialog(CharSequence[] items, int[] icons, String title, int selected, Context context, final OnItemClickListener listener, Theme.ResourcesProvider resourcesProvider, boolean useRadio) {
        ModernOptionsSheet.showChoices(
                context,
                resourcesProvider,
                title,
                items,
                icons,
                selected,
                useRadio,
                listener == null ? null : listener::onClick);
    }

    public static void showDialog(CharSequence[] items, int[] icons, String title, int selected, Context context, OnItemClickListener listener) {
        showDialog(items, icons, title, selected, context, listener, null, true);
    }

    public static void showDialog(CharSequence[] items, String title, int selected, Context context, OnItemClickListener listener) {
        showDialog(items, null, title, selected, context, listener, null, true);
    }

    public static void showDialogWithoutRadio(ArrayList<? extends CharSequence> items, String title, Context context, OnItemClickListener listener) {
        CharSequence[] array = items.stream()
                .map(String::valueOf)
                .toArray(CharSequence[]::new);
        showDialog(array, null, title, -1, context, listener, null, false);
    }

    public static int measureMaxWidth(TextPaint paint, CharSequence[] items) {
        try {
            if (paint == null || items == null) {
                return 0;
            }
            float max = 0f;
            for (CharSequence item : items) {
                if (item == null) {
                    continue;
                }
                float w = paint.measureText(item, 0, item.length());
                if (w > max) {
                    max = w;
                }
            }
            return Math.round(max);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int measureWidth(TextPaint paint, CharSequence text) {
        try {
            if (paint == null || text == null) {
                return 0;
            }
            return Math.round(paint.measureText(text, 0, text.length()));
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int clampToView(int desiredWidth, View host) {
        try {
            if (host == null) {
                return desiredWidth;
            }
            int available = host.getWidth() - host.getPaddingLeft() - host.getPaddingRight();
            if (available > 0 && desiredWidth > available) {
                return available;
            }
            return desiredWidth;
        } catch (Throwable t) {
            return desiredWidth;
        }
    }
}
