package app.nimarkogram.messenger.plugins.ui.components;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.StickerImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.plugins.PluginsController;

public class SafeModeBottomSheet extends BottomSheet {
    
    @Override
    public void setLastVisible(boolean z) {
        super.setLastVisible(z);
    }

    public SafeModeBottomSheet(BaseFragment baseFragment) {
        super(baseFragment.getParentActivity(), false, baseFragment.getResourceProvider());
        Activity parentActivity = baseFragment.getParentActivity();
        fixNavigationBar();
        FrameLayout frameLayout = new FrameLayout(parentActivity);
        LinearLayout linearLayout = new LinearLayout(parentActivity);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        frameLayout.addView(linearLayout);
        
        StickerImageView stickerImageView = new StickerImageView(parentActivity, this.currentAccount);
        
        stickerImageView.setStickerPackName("UtyaDuck");
        stickerImageView.setStickerNum(34);   
        stickerImageView.getImageReceiver().setAutoRepeat(1);
        stickerImageView.getImageReceiver().setAutoRepeatCount(0);
        linearLayout.addView(stickerImageView, LayoutHelper.createLinear(144, 144, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        
        TextView textView = new TextView(parentActivity);
        textView.setGravity(Gravity.CENTER_HORIZONTAL);
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(1, 20.0f);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setText(LocaleController.getString(R.string.PluginsSafeMode));
        linearLayout.addView(textView, LayoutHelper.createFrame(-1, -2.0f, Gravity.TOP | Gravity.LEFT, 40.0f, 20.0f, 40.0f, 0.0f));
        
        TextView textView2 = new TextView(parentActivity);
        textView2.setGravity(Gravity.CENTER_HORIZONTAL);
        textView2.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
        textView2.setTextSize(1, 14.0f);
        textView2.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        textView2.setText(LocaleController.getString(R.string.PluginsSafeModeInfo));
        linearLayout.addView(textView2, LayoutHelper.createFrame(-1, -2.0f, Gravity.TOP | Gravity.LEFT, 21.0f, 8.0f, 21.0f, 0.0f));
        
        ButtonWithCounterView buttonWithCounterView = new ButtonWithCounterView(parentActivity, true, this.resourcesProvider);
        buttonWithCounterView.setText(LocaleController.getString(R.string.NM_ExitSafeMode), false);
        buttonWithCounterView.setOnClickListener(this::lambda$new$0);
        linearLayout.addView(buttonWithCounterView, LayoutHelper.createFrame(-1, 48.0f, Gravity.TOP | Gravity.LEFT, 16.0f, 28.0f, 16.0f, 16.0f));
        setCustomView(frameLayout);
    }

    private   void lambda$new$0(View view) {
        dismiss();
        NimarkoConfig.setPluginsSafeMode(false);
        PluginsController.getInstance().restart();
    }
}