package com.exteragram.messenger.components;

import android.annotation.SuppressLint;
import android.content.Context;

import org.telegram.ui.ActionBar.Theme;

@SuppressLint("ViewConstructor")
public abstract class TranslateBeforeSendWrapper extends app.nimarkogram.messenger.components.TranslateBeforeSendWrapper {
    public TranslateBeforeSendWrapper(Context context, boolean top, boolean bottom,
                                      Theme.ResourcesProvider resourcesProvider) {
        super(context, top, bottom, resourcesProvider);
    }

    @Override
    public abstract void onClick();
}
