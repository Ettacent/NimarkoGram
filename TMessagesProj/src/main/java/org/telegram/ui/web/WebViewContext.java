/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.web;

import android.content.Context;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;

import app.nimarkogram.messenger.icons.IconResources;

public final class WebViewContext {
    private WebViewContext() {}

    public static Context wrap(Context context) {
        Resources resources = context.getResources();
        if (!(resources instanceof IconResources)) {
            return context;
        }
        do {
            resources = ((IconResources) resources).getOriginalResources();
        } while (resources instanceof IconResources);
        final Resources original = resources;
        final Resources.Theme theme = original.newTheme();
        theme.setTo(context.getTheme());
        return new ContextThemeWrapper(context, theme) {
            @Override
            public Resources getResources() {
                return original;
            }

            @Override
            public Resources.Theme getTheme() {
                Resources.Theme current = context.getTheme();
                if (!theme.equals(current)) {
                    theme.setTo(current);
                }
                return theme;
            }
        };
    }
}
