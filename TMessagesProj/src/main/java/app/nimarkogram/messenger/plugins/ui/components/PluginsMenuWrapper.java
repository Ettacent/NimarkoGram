package app.nimarkogram.messenger.plugins.ui.components;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ScrollView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PopupSwipeBackLayout;

import java.util.List;
import java.util.Map;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.hooks.MenuItemRecord;

public class PluginsMenuWrapper {
    public static final int GAP_ITEM_HEIGHT = 8;
    public static final int ITEM_HEIGHT = 48;
    public static final int SUBTITLE_ITEM_HEIGHT = 56;
    private final Map<String, Object> contextData;
    private final BaseFragment fragment;
    private final LinearLayout menuItemsContainer;
    private final String menuType;
    private final Theme.ResourcesProvider resourcesProvider;
    public LinearLayout swipeBack;

    protected void closeMenu() {
    }

    public PluginsMenuWrapper(BaseFragment baseFragment, PopupSwipeBackLayout popupSwipeBackLayout, String str, Map<String, Object> map, Theme.ResourcesProvider resourcesProvider) {
        this(baseFragment, popupSwipeBackLayout, null, str, map, resourcesProvider);
    }

    public PluginsMenuWrapper(BaseFragment baseFragment, final PopupSwipeBackLayout popupSwipeBackLayout, List<MenuItemRecord> list, String str, Map<String, Object> map, Theme.ResourcesProvider resourcesProvider) {
        this.fragment = baseFragment;
        this.resourcesProvider = resourcesProvider;
        this.menuType = str;
        this.contextData = map;
        Activity parentActivity = baseFragment.getParentActivity();
        LinearLayout linearLayout = new LinearLayout(parentActivity);
        this.swipeBack = linearLayout;
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        ActionBarMenuSubItem actionBarMenuSubItem = new ActionBarMenuSubItem((Context) parentActivity, true, false, resourcesProvider);
        actionBarMenuSubItem.setItemHeight(44);
        actionBarMenuSubItem.setTextAndIcon(LocaleController.getString(R.string.Back), R.drawable.msg_arrow_back);
        actionBarMenuSubItem.getTextView().setPadding(LocaleController.isRTL ? 0 : AndroidUtilities.dp(40.0f), 0, LocaleController.isRTL ? AndroidUtilities.dp(40.0f) : 0, 0);
        actionBarMenuSubItem.setOnClickListener(view -> popupSwipeBackLayout.closeForeground());
        this.swipeBack.addView(actionBarMenuSubItem, LayoutHelper.createLinear(-1, -2));
        ScrollView scrollViewCreateScrollView = createScrollView(parentActivity);
        LinearLayout linearLayout2 = new LinearLayout(parentActivity);
        this.menuItemsContainer = linearLayout2;
        linearLayout2.setOrientation(LinearLayout.VERTICAL);
        scrollViewCreateScrollView.addView(linearLayout2);
        this.swipeBack.addView(scrollViewCreateScrollView);
        rebuildMenu(list);
    }

    public void rebuildMenu(List<MenuItemRecord> list) {
        int height;
        this.menuItemsContainer.removeAllViews();
        if (list == null) {
            list = PluginsController.getInstance().getMenuItemsForLocation(this.menuType, this.contextData);
        }
        this.menuItemsContainer.addView(createGap(), LayoutHelper.createLinear(-1, GAP_ITEM_HEIGHT));
        int totalHeight = 0;
        for (final MenuItemRecord menuItemRecord : list) {
            if (menuItemRecord == null) {
                this.menuItemsContainer.addView(createGap(), LayoutHelper.createLinear(-1, GAP_ITEM_HEIGHT));
                totalHeight += GAP_ITEM_HEIGHT;
            } else if (!TextUtils.isEmpty(menuItemRecord.text)) {
                ActionBarMenuSubItem actionBarMenuSubItem = new ActionBarMenuSubItem((Context) this.fragment.getParentActivity(), false, false, this.resourcesProvider);
                actionBarMenuSubItem.setTextAndIcon(menuItemRecord.text, menuItemRecord.iconResId);
                actionBarMenuSubItem.setMinimumWidth(AndroidUtilities.dp(196.0f));
                
                actionBarMenuSubItem.setOnClickListener(view -> onItemClick(menuItemRecord));
                
                if (TextUtils.isEmpty(menuItemRecord.subtext)) {
                    height = ITEM_HEIGHT;
                } else {
                    actionBarMenuSubItem.setSubtext(menuItemRecord.subtext);
                    height = SUBTITLE_ITEM_HEIGHT;
                    actionBarMenuSubItem.setItemHeight(SUBTITLE_ITEM_HEIGHT);
                }
                this.menuItemsContainer.addView(actionBarMenuSubItem, LayoutHelper.createLinear(-1, height));
                totalHeight += height;
                actionBarMenuSubItem.setTag(menuItemRecord);
            }
        }
        int maxHeight = AndroidUtilities.dp(436.0f);
        View view = (View) this.menuItemsContainer.getParent();
        LayoutParams layoutParams = (LayoutParams) view.getLayoutParams();
        if (layoutParams == null) {
            layoutParams = LayoutHelper.createLinear(-1, -2);
        }
        if (totalHeight > maxHeight && Math.abs(totalHeight - maxHeight) > 112) {
            layoutParams.height = maxHeight;
        } else {
            layoutParams.height = -2;
        }
        view.setLayoutParams(layoutParams);
    }

    private void onItemClick(MenuItemRecord menuItemRecord) {
        closeMenu();
        PluginsController controller = PluginsController.getInstance();
        
        boolean entered = menuItemRecord.runtimeToken != null
                && controller.getPluginRuntimeTaskDecision(menuItemRecord.runtimeToken)
                        == PluginsController.RUNTIME_TASK_RUN
                && controller.enterPluginRuntime(menuItemRecord.runtimeToken);
        if (!entered) {
            return;
        }
        controller.getWatchdog().onPluginExecutionStarted(menuItemRecord.pluginId);
        try {
            menuItemRecord.onClickCallback.call(this.contextData);
        } catch (Exception e) {
            controller.getWatchdog()
                    .onPluginExecutionFailed(menuItemRecord.pluginId, e);
            FileLog.e(e);
        } catch (Error failure) {
            controller.getWatchdog()
                    .onPluginExecutionFailed(menuItemRecord.pluginId, failure);
            throw failure;
        } finally {
            controller.getWatchdog().onPluginExecutionFinished(menuItemRecord.pluginId);
            if (menuItemRecord.runtimeToken != null) {
                controller.exitPluginRuntime(menuItemRecord.runtimeToken);
            }
        }
    }

    private ScrollView createScrollView(Context context) {
        return new ScrollView(context) {
            final AnimatedFloat alphaFloat = new AnimatedFloat(this, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
            Drawable topShadowDrawable;
            private boolean wasCanScrollVertically;

            @Override
            public void onNestedScroll(View view, int i, int i2, int i3, int i4) {
                super.onNestedScroll(view, i, i2, i3, i4);
                boolean zCanScrollVertically = canScrollVertically(-1);
                if (this.wasCanScrollVertically != zCanScrollVertically) {
                    invalidate();
                    this.wasCanScrollVertically = zCanScrollVertically;
                }
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                float f = this.alphaFloat.set(canScrollVertically(-1) ? 1.0f : 0.0f) * 0.5f;
                if (f > 0.0f) {
                    if (this.topShadowDrawable == null) {
                        this.topShadowDrawable = ContextCompat.getDrawable(getContext(), R.drawable.header_shadow);
                    }
                    Drawable drawable = this.topShadowDrawable;
                    if (drawable != null) {
                        drawable.setBounds(0, getScrollY(), getWidth(), getScrollY() + this.topShadowDrawable.getIntrinsicHeight());
                        this.topShadowDrawable.setAlpha((int) (f * 255.0f));
                        this.topShadowDrawable.draw(canvas);
                    }
                }
            }
        };
    }

    private View createGap() {
        FrameLayout frameLayout = new FrameLayout(this.fragment.getContext());
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator, this.resourcesProvider));
        return frameLayout;
    }
}
