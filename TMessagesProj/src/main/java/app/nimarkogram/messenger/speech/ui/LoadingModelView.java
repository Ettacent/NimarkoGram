package app.nimarkogram.messenger.speech.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

@SuppressLint("ViewConstructor")
public class LoadingModelView extends LinearLayout {

    public TextView title;
     
    public TextView subtitle;

    private final RadialProgressView progressView;
    private boolean determinate = false;

    public LoadingModelView(Context context) {
        this(context, null);
    }

    public LoadingModelView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(
                AndroidUtilities.dp(24),
                AndroidUtilities.dp(24),
                AndroidUtilities.dp(24),
                AndroidUtilities.dp(24));

        progressView = new RadialProgressView(context, resourcesProvider);
        progressView.setSize(AndroidUtilities.dp(44));
        progressView.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
        
        progressView.setNoProgress(true);
        addView(progressView, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL));

        title = new TextView(context);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        addView(title, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));

        subtitle = new TextView(context);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER);
        addView(subtitle, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
    }

    public TextView getTitleView() {
        return title;
    }

    public TextView getSubtitleView() {
        return subtitle;
    }

    public void setProgress(float progress) {
        try {
            if (!determinate) {
                determinate = true;
                progressView.setNoProgress(false);
            }
            if (progress < 0f) {
                progress = 0f;
            } else if (progress > 1f) {
                progress = 1f;
            }
            progressView.setProgress(progress);
            progressView.invalidate();
        } catch (Throwable ignore) {
        }
    }
}
