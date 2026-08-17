package com.exteragram.messenger.speech.ui;

import android.content.Context;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;

public class LoadingModelView extends app.nimarkogram.messenger.speech.ui.LoadingModelView {

    public TextView title;
     
    public TextView subtitle;

    public LoadingModelView(Context context) {
        super(context);
        mirrorFields();
    }

    public LoadingModelView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        mirrorFields();
    }

    private void mirrorFields() {
        this.title = getTitleView();
        this.subtitle = getSubtitleView();
    }
}
