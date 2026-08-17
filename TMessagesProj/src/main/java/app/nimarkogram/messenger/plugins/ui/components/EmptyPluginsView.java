package app.nimarkogram.messenger.plugins.ui.components;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;

public class EmptyPluginsView extends FrameLayout {
    private final BackupImageView backupImageView;
    private final EffectsTextView textView;

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public EmptyPluginsView(Context context) {
        this(context, null);
    }

    public EmptyPluginsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setPadding(AndroidUtilities.dp(20.0f), 0, AndroidUtilities.dp(20.0f), 0);
        linearLayout.setGravity(17);
        linearLayout.setClipChildren(false);
        linearLayout.setClipToPadding(false);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        
        this.backupImageView = new BackupImageView(context);
        linearLayout.addView(this.backupImageView, LayoutHelper.createLinear(100, 100, 17, 0, 0, 0, 20));
        
        this.textView = new EffectsTextView(context);
        this.textView.setTextSize(1, 14.0f);
        this.textView.setTextColor(Theme.getColor(Theme.key_emptyListPlaceholder, resourcesProvider));
        this.textView.setGravity(17);
        this.textView.setText(LocaleController.getString(R.string.NoResult));
        this.textView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
        this.textView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        this.textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        
        linearLayout.addView(this.textView, LayoutHelper.createLinear(-2, -2, 17));
        
        addView(linearLayout, LayoutHelper.createFrame(-2, -2, 17));
        
        setOnTouchListener((view, motionEvent) -> true);
    }

    public void setText(CharSequence text) {
        this.textView.setText(text);
    }

    public BackupImageView getBackupImageView() {
        return this.backupImageView;
    }
}