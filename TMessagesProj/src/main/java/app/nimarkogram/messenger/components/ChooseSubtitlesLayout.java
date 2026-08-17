package app.nimarkogram.messenger.components;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.Components.PopupSwipeBackLayout;

public class ChooseSubtitlesLayout {
    private final ActionBarMenuSubItem disableItem;
    public final ActionBarPopupWindow.ActionBarPopupWindowLayout layout;

    public interface Callback {
        void onChooseSubtitles();

        void onDisableSubtitles();
    }

    public ChooseSubtitlesLayout(Context context, final PopupSwipeBackLayout popupSwipeBackLayout, final Callback callback) {
        ActionBarPopupWindow.ActionBarPopupWindowLayout actionBarPopupWindowLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, null);
        this.layout = actionBarPopupWindowLayout;
        actionBarPopupWindowLayout.setFitItems(true);

        ActionBarMenuSubItem backItem = ActionBarMenuItem.addItem(actionBarPopupWindowLayout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, null);
        backItem.setOnClickListener(view -> popupSwipeBackLayout.closeForeground());
        backItem.setColors(-328966, -328966);
        backItem.setSelectorColor(268435455);

        View frameLayout = new FrameLayout(context);
        frameLayout.setMinimumWidth(AndroidUtilities.dp(196.0f));
        frameLayout.setBackgroundColor(-15198184);
        actionBarPopupWindowLayout.addView(frameLayout);
        LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) frameLayout.getLayoutParams();
        layoutParams.gravity = LocaleController.isRTL ? 5 : 3;
        layoutParams.width = -1;
        layoutParams.height = AndroidUtilities.dp(8.0f);
        frameLayout.setLayoutParams(layoutParams);

        ActionBarMenuSubItem chooseItem = ActionBarMenuItem.addItem(actionBarPopupWindowLayout, R.drawable.msg_folders, LocaleController.getString(R.string.ChooseSubtitles), false, null);
        chooseItem.setColors(-328966, -328966);
        chooseItem.setSelectorColor(268435455);
        chooseItem.setOnClickListener(view -> callback.onChooseSubtitles());

        ActionBarMenuSubItem disableItem = ActionBarMenuItem.addItem(actionBarPopupWindowLayout, R.drawable.msg_cancel, LocaleController.getString(R.string.DisableSubtitles), false, null);
        this.disableItem = disableItem;
        disableItem.setColors(-328966, -328966);
        disableItem.setSelectorColor(268435455);
        disableItem.setOnClickListener(view -> callback.onDisableSubtitles());
    }

    public void update(boolean z) {
        this.disableItem.setVisibility(z ? View.VISIBLE : View.GONE);
    }
}
