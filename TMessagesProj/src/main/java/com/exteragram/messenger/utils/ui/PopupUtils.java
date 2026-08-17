package com.exteragram.messenger.utils.ui;

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

    private static app.nimarkogram.messenger.utils.ui.PopupUtils.OnItemClickListener adapt(final OnItemClickListener listener) {
        if (listener == null) {
            return null;
        }
        return new app.nimarkogram.messenger.utils.ui.PopupUtils.OnItemClickListener() {
            @Override
            public void onClick(int which) {
                listener.onClick(which);
            }
        };
    }

    public static void showDialog(CharSequence[] items, int[] icons, String title, int selected, Context context, OnItemClickListener listener, Theme.ResourcesProvider resourcesProvider, boolean useRadio) {
        app.nimarkogram.messenger.utils.ui.PopupUtils.showDialog(items, icons, title, selected, context, adapt(listener), resourcesProvider, useRadio);
    }

    public static void showDialog(CharSequence[] items, int[] icons, String title, int selected, Context context, OnItemClickListener listener) {
        app.nimarkogram.messenger.utils.ui.PopupUtils.showDialog(items, icons, title, selected, context, adapt(listener));
    }

    public static void showDialog(CharSequence[] items, String title, int selected, Context context, OnItemClickListener listener) {
        app.nimarkogram.messenger.utils.ui.PopupUtils.showDialog(items, title, selected, context, adapt(listener));
    }

    public static void showDialogWithoutRadio(ArrayList<? extends CharSequence> items, String title, Context context, OnItemClickListener listener) {
        app.nimarkogram.messenger.utils.ui.PopupUtils.showDialogWithoutRadio(items, title, context, adapt(listener));
    }

    public static int measureMaxWidth(TextPaint paint, CharSequence[] items) {
        return app.nimarkogram.messenger.utils.ui.PopupUtils.measureMaxWidth(paint, items);
    }

    public static int measureWidth(TextPaint paint, CharSequence text) {
        return app.nimarkogram.messenger.utils.ui.PopupUtils.measureWidth(paint, text);
    }

    public static int clampToView(int desiredWidth, View host) {
        return app.nimarkogram.messenger.utils.ui.PopupUtils.clampToView(desiredWidth, host);
    }
}
