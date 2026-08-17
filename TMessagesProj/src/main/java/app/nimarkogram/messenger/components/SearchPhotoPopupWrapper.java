package app.nimarkogram.messenger.components;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.Stories.DarkThemeResourceProvider;

import com.exteragram.messenger.utils.system.SystemUtils;

public class SearchPhotoPopupWrapper {

    private static final String GOOGLE_LENS_PACKAGE = "com.google.android.googlequicksearchbox";

    private static final String SEARCH_PHOTO_INFO_FALLBACK =
            "Search this image on the web. Results open in your browser.";

    public ActionBarMenuSubItem lensItem;
    public ActionBarPopupWindow.ActionBarPopupWindowLayout searchSwipeBackLayout;

    public SearchPhotoPopupWrapper(Context context,
                                   final PopupSwipeBackLayout popupSwipeBackLayout,
                                   final Utilities.Callback2<String, Boolean> callback) {
        searchSwipeBackLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, null);
        searchSwipeBackLayout.setFitItems(true);

        ActionBarMenuSubItem backItem = ActionBarMenuItem.addItem(
                searchSwipeBackLayout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, null);
        backItem.setOnClickListener(view -> popupSwipeBackLayout.closeForeground());
        backItem.setColors(0xFFFAFAFA, 0xFFFAFAFA);
        backItem.setSelectorColor(0x0FFFFFFF);

        FrameLayout topGap = new FrameLayout(context);
        topGap.setMinimumWidth(AndroidUtilities.dp(196.0f));
        topGap.setBackgroundColor(0xFF1A1A1A);
        searchSwipeBackLayout.addView(topGap);
        LinearLayout.LayoutParams gapParams = (LinearLayout.LayoutParams) topGap.getLayoutParams();
        if (LocaleController.isRTL) {
            gapParams.gravity = 5;
        }
        gapParams.width = -1;
        gapParams.height = AndroidUtilities.dp(8.0f);
        topGap.setLayoutParams(gapParams);

        addSearchItem("Yandex",
                () -> callback.run("https://yandex.com/images/search?rpt=imageview&url=", Boolean.FALSE));
        addSearchItem("Google",
                () -> callback.run("https://www.google.com/searchbyimage?client=app&image_url=", Boolean.FALSE));
        addSearchItem("Bing",
                () -> callback.run("https://www.bing.com/images/search?view=detailv2&iss=SBI&form=SBIIDP&sbisrc=UrlPaste&q=imgurl:", Boolean.FALSE));
        addSearchItem("TinEye",
                () -> callback.run("https://tineye.com/search/?url=", Boolean.FALSE));

        if (isLensAvailable()) {
            lensItem = ActionBarMenuItem.addItem(searchSwipeBackLayout, 0, "Google Lens", false, null);
            lensItem.setColors(0xFFFAFAFA, 0xFFFAFAFA);
            lensItem.setOnClickListener(view -> callback.run(null, Boolean.TRUE));
            lensItem.setSelectorColor(0x0FFFFFFF);
        }

        FrameLayout bottomGap = new FrameLayout(context);
        bottomGap.setMinimumWidth(AndroidUtilities.dp(196.0f));
        bottomGap.setBackgroundColor(0xFF1A1A1A);
        searchSwipeBackLayout.addView(bottomGap);
        bottomGap.setLayoutParams(gapParams);

        LinkSpanDrawable.LinksTextView info = new LinkSpanDrawable.LinksTextView(context);
        info.setTag(R.id.fit_width_tag, 1);
        info.setPadding(AndroidUtilities.dp(13.0f), 0, AndroidUtilities.dp(13.0f), AndroidUtilities.dp(8.0f));
        info.setTextSize(1, 12.0f);
        info.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, new DarkThemeResourceProvider()));
        info.setMovementMethod(LinkMovementMethod.getInstance());
        info.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, new DarkThemeResourceProvider()));
        
        info.setText(SEARCH_PHOTO_INFO_FALLBACK);
        searchSwipeBackLayout.addView(info, LayoutHelper.createLinear(-1, -2, 0.0f, 0, 0, 8, 0, 0));
    }

    private void addSearchItem(String title, Runnable onClick) {
        ActionBarMenuSubItem item = ActionBarMenuItem.addItem(searchSwipeBackLayout, 0, title, false, null);
        item.setColors(0xFFFAFAFA, 0xFFFAFAFA);
        item.setOnClickListener(view -> onClick.run());
        item.setSelectorColor(0x0FFFFFFF);
    }

    private static boolean isLensAvailable() {
        try {
            return SystemUtils.isAppInstalled(GOOGLE_LENS_PACKAGE);
        } catch (Throwable t) {
            return false;
        }
    }
}
