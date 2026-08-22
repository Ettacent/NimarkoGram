package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS;

import android.animation.Animator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.math.MathUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FolderDrawable;
import org.telegram.ui.Components.HintsController;
import org.telegram.ui.Components.ItemOptions;
import app.nimarkogram.messenger.preferences.BottomTabsPreferencesActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.Stories.recorder.HintView2;

import java.util.ArrayList;
import java.util.Collections;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class MainTabsActivity extends ViewPagerActivity implements NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target {
    public static final int TABS_COUNT = 3;
    private static final int FALLBACK_CHATS = 1;
    private static final int FALLBACK_SETTINGS = 2;

    private static final int INDEX_PROFILE = 0;
    private static final int INDEX_CHATS = 1;
    private static final int INDEX_SETTINGS = 2;
    private static final int INDEX_CALLS = 3;

    private static int posProfile() {
        return app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE
                .getPosition(app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.PROFILE);
    }

    private static int posChats() {
        int p = app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE
                .getPosition(app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.CHATS);
        return p >= 0 ? p : FALLBACK_CHATS;
    }

    private static int posSettings() {
        int p = app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE
                .getPosition(app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.SETTINGS);
        return p >= 0 ? p : FALLBACK_SETTINGS;
    }

    private static app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType tabTypeAt(int position) {
        java.util.List<app.nimarkogram.messenger.utils.ui.MainTabsManager.Tab> enabled =
                app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE.getEnabledTabs();
        if (position < 0 || position >= enabled.size()) return null;
        return enabled.get(position).getType();
    }

    private static int indexToPosition(int index) {
        switch (index) {
            case INDEX_PROFILE:  return posProfile();
            case INDEX_CHATS:    return posChats();
            case INDEX_SETTINGS:
            case INDEX_CALLS:    return posSettings();
            default:             return Math.min(index, 2);
        }
    }

    private boolean visualDirectionMismatchesPager(int tappedTabIndex, int targetPagerPosition, int currentPagerPosition) {
        if (tabsView == null || tabs == null) return false;
        GlassTabView currentTab = null;
        for (int i = 0; i < tabs.length; i++) {
            if (i == tappedTabIndex) continue;
            if (tabs[i] == null || tabs[i].getParent() != tabsView) continue;
            if (tabs[i].getVisibility() != View.VISIBLE) continue;
            if (indexToPosition(i) == currentPagerPosition) {
                currentTab = tabs[i];
                break;
            }
        }
        if (currentTab == null) return false;
        final float tappedCenter = tabs[tappedTabIndex].getX() + tabs[tappedTabIndex].getWidth() / 2f;
        final float currentCenter = currentTab.getX() + currentTab.getWidth() / 2f;
        if (tappedCenter == currentCenter) return false;
        final boolean visualForward = tappedCenter > currentCenter;
        final boolean pagerForward = targetPagerPosition > currentPagerPosition;
        return visualForward != pagerForward;
    }

    private static final int ANIMATOR_ID_TABS_VISIBLE = 0;
    private final BoolAnimator animatorTabsVisible = new BoolAnimator(ANIMATOR_ID_TABS_VISIBLE,
        this, CubicBezierInterpolator.EASE_OUT_QUINT, 380, true);


    private IUpdateLayout updateLayout;
    private boolean dropCallsFragmentAfterPageScroll;

    private UpdateLayoutWrapper updateLayoutWrapper;
    private FrameLayout tabsViewWrapper;
    private MainTabsLayout tabsView;
    private BlurredBackgroundDrawable tabsViewBackground;
    private View fadeView;

    private LinearLayout tabsContainer;
    private GlassTabView searchButton;
    private BlurredBackgroundDrawable searchButtonBackground;

    public MainTabsActivity() {
        super();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            iBlur3SourceTabGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceTabGlass.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                hash.addF(fragmentPosition.left);
                                hash.addF(fragmentPosition.top);
                                hash.add(fragment.getClassGuid());
                            }
                        }
                    }
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    final int width = fragmentView.getMeasuredWidth();
                    final int height = fragmentView.getMeasuredHeight();

                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                canvas.save();
                                canvas.translate(fragmentPosition.left, fragmentPosition.top);
                                source.draw(canvas, 0, 0, width, height);
                                canvas.restore();
                            }
                        }
                    }
                }
            });
        } else {
            iBlur3SourceTabGlass = null;
        }

        iBlur3SourceColor = new BlurredBackgroundSourceColor();

        Bulletin.Delegate delegate = new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN);
            }
        };

        Bulletin.addDelegate(this, delegate);
        Bulletin.addDelegate(contentView, delegate);
    }

    @Override
    protected FrameLayout createContentView(Context context) {
        return new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                checkUi_tabsPosition();
                checkUi_fadeView();
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                final int color = getEstBackgroundColor();
                if (insetLeft != 0) {
                    canvas.drawRect(0, 0, insetLeft, getHeight(), Theme.fillingPaint(color));
                }
                if (insetRight != 0) {
                    canvas.drawRect(getWidth() - insetRight, 0, getWidth(), getHeight(), Theme.fillingPaint(color));
                }

                super.dispatchDraw(canvas);
                blur3_invalidateBlur();
                blur3_updateFadeColors(color);
            }
        };
    }

    private int getEstBackgroundColor() {
        final float whiteSurfaceVisibility = viewPager == null ? 1f : Math.max(
                viewPager.getPositionVisibility(posChats()),
                viewPager.getPositionVisibility(posSettings()));
        return ColorUtils.blendARGB(
                getThemedColor(Theme.key_windowBackgroundGray),
                getThemedColor(Theme.key_windowBackgroundWhite),
                whiteSurfaceVisibility);
    }

    private boolean tabletLayout;
    public void updateLayout() {
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayout();
    }

    @Override
    public void onResume() {
        super.onResume();
        blur3_updateColors();
        checkUnreadCount(true);

        showAccountChangeHint();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (accountSwitchHint != null) {
            accountSwitchHint.hide();
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        tabletLayout = false;

        if (viewPager != null) {
            viewPager.setSwipeTargetResolver((current, physicalForward) -> {
                if (tabsView == null || tabs == null) return -1;
                GlassTabView currentTab = null;
                for (int j = 0; j < tabs.length; j++) {
                    if (tabs[j] != null && indexToPosition(j) == current
                            && tabs[j].getVisibility() == View.VISIBLE
                            && tabs[j].getParent() == tabsView) {
                        currentTab = tabs[j];
                        break;
                    }
                }
                if (currentTab == null) return -1;
                float currentCenter = currentTab.getX() + currentTab.getWidth() / 2f;
                GlassTabView neighbour = null;
                float bestDelta = Float.MAX_VALUE;
                for (int j = 0; j < tabs.length; j++) {
                    if (tabs[j] == null || tabs[j] == currentTab) continue;
                    if (tabs[j].getParent() != tabsView || tabs[j].getVisibility() != View.VISIBLE) continue;
                    float center = tabs[j].getX() + tabs[j].getWidth() / 2f;
                    float delta = physicalForward ? (center - currentCenter) : (currentCenter - center);
                    if (delta > 0 && delta < bestDelta) {
                        bestDelta = delta;
                        neighbour = tabs[j];
                    }
                }
                if (neighbour == null) return -1;
                for (int j = 0; j < tabs.length; j++) {
                    if (tabs[j] == neighbour) return indexToPosition(j);
                }
                return -1;
            });
        }

        tabsView = new MainTabsLayout(context, resourceProvider);
        tabsView.setClipChildren(false);
        tabsView.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4));
        tabsView.setMaxWidth(dp(328 + DialogsActivity.MAIN_TABS_MARGIN * 2));

        tabs = new GlassTabView[4];
        tabs[INDEX_PROFILE] = GlassTabView.createAvatar(context, resourceProvider, currentAccount, R.string.MainTabsProfile);
        tabs[INDEX_CHATS] = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CHATS, R.string.MainTabsChats);
        tabs[INDEX_SETTINGS] = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.SETTINGS, R.string.Settings);
        tabs[INDEX_CALLS] = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CALLS, R.string.MainTabsCalls);
        tabs[INDEX_CHATS].setOnLongClickListener(this::openFoldersSelector);
        tabs[INDEX_CALLS].setOnLongClickListener(this::openCallsSelector);
        tabs[INDEX_PROFILE].setOnLongClickListener(this::openAccountSelector);

        tabsView.addTabToIgnoreClick(tabs[INDEX_CHATS]);
        tabsView.addTabToIgnoreClick(tabs[INDEX_PROFILE]);
        tabsView.addTabToIgnoreClick(tabs[INDEX_CALLS]);

        for (int index = 0; index < tabs.length; index++) {
            final GlassTabView view = tabs[index];

            final int tabIndex = index;
            tabs[index].setOnClickListener(v -> {
                if (viewPager.isManualScrolling() || viewPager.isTouch()) {
                    return;
                }

                final int position = indexToPosition(tabIndex);
                if (position < 0 || position >= getFragmentsCount()) {
                    return;
                }

                if (viewPager.getCurrentPosition() == position) {
                    final BaseFragment fragment = getCurrentVisibleFragment();
                    if (fragment instanceof MainTabsActivity.TabFragmentDelegate) {
                        ((MainTabsActivity.TabFragmentDelegate) fragment).onParentScrollToTop();
                    }
                    return;
                }

                selectTab(position, true);
                Boolean forwardVisual = null;
                if (tabsView != null) {
                    int currentPos = viewPager.getCurrentPosition();
                    GlassTabView currentTab = null;
                    for (int j = 0; j < tabs.length; j++) {
                        if (tabs[j] != null && indexToPosition(j) == currentPos
                                && tabs[j].getVisibility() == View.VISIBLE
                                && tabs[j].getParent() == tabsView) {
                            currentTab = tabs[j];
                            break;
                        }
                    }
                    if (currentTab != null) {
                        float currentCenter = currentTab.getX() + currentTab.getWidth() / 2f;
                        float tappedCenter = view.getX() + view.getWidth() / 2f;
                        if (currentCenter != tappedCenter) {
                            forwardVisual = tappedCenter > currentCenter;
                        }
                    }
                }
                viewPager.scrollToPosition(position, forwardVisual);
            });

            tabsView.addView(tabs[index]);
            tabsView.setViewVisible(view, true, false);
            tabs[index].setTitleVisible(app.nimarkogram.messenger.NimarkoConfig.showMainTabsTitle);
        }
        checkUi_callTabVisible(getUserConfig().showCallsTab, false);
        applyEditorTabsVisibility(false);

        selectTab(viewPager.getCurrentPosition(), false);

        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));

        final ViewPositionWatcher viewPositionWatcher = new ViewPositionWatcher(contentView);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceTabGlass != null ? iBlur3SourceTabGlass : iBlur3SourceColor);
        iBlur3FactoryGlass.setSourceRootView(viewPositionWatcher, contentView);
        iBlur3FactoryGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));

        tabsViewBackground = iBlur3FactoryGlass.create(tabsView, BlurredBackgroundProviderImpl.mainTabs(resourceProvider));
        tabsViewBackground.setRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
        tabsViewBackground.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
        tabsView.setBackground(tabsViewBackground);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryFade = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        iBlur3FactoryFade.setSourceRootView(viewPositionWatcher, contentView);

        fadeView = new View(context);
        BlurredBackgroundWithFadeDrawable fadeDrawable = new BlurredBackgroundWithFadeDrawable(iBlur3FactoryFade.create(fadeView, null));
        fadeDrawable.setFadeHeight(dp(60), true);
        fadeView.setBackground(fadeDrawable);

        contentView.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

        tabsViewWrapper = new FrameLayout(context);
        tabsViewWrapper.setOnClickListener(v -> {});
        tabsViewWrapper.setClipToPadding(false);
        if (app.nimarkogram.messenger.NimarkoConfig.showSearchInTabs) {
            tabsContainer = new LinearLayout(context);
            tabsContainer.setOrientation(LinearLayout.HORIZONTAL);
            tabsContainer.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
            tabsContainer.setPadding(dp(2), dp(2), dp(2), dp(2));

            LinearLayout.LayoutParams tabsParams = LayoutHelper.createLinear(
                    0, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS, 1.0f);
            tabsContainer.addView(tabsView, tabsParams);

            searchButton = GlassTabView.createStaticTab(
                    context,
                    resourceProvider,
                    R.drawable.outline_header_search,
                    R.string.Search,
                    false
            );
            searchButton.setOnClickListener(v -> onSearchButtonClick());
            searchButton.setOnLongClickListener(v -> {
                app.nimarkogram.messenger.chats.CGChatMenuInjector.INSTANCE.openArchivedChats(this);
                return true;
            });
            searchButtonBackground = iBlur3FactoryGlass.create(
                    searchButton, BlurredBackgroundProviderImpl.mainTabs(resourceProvider));
            searchButtonBackground.setRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
            searchButtonBackground.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
            searchButton.setBackground(searchButtonBackground);

            int searchSize = DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS;
            tabsContainer.addView(searchButton,
                    LayoutHelper.createLinear(searchSize, searchSize, -8f, 0f, 0f, 0f));

            tabsViewWrapper.addView(tabsContainer,
                    LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                            DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS,
                            Gravity.BOTTOM));
        } else {
            tabsViewWrapper.addView(tabsView, LayoutHelper.createFrame(
                    328 + DialogsActivity.MAIN_TABS_MARGIN * 2,
                    DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }
        tabsViewWrapper.setVisibility(app.nimarkogram.messenger.NimarkoConfig.showMainTabs ? View.VISIBLE : View.GONE);
        contentView.addView(tabsViewWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayoutWrapper = new UpdateLayoutWrapper(context);
        contentView.addView(updateLayoutWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayout = ApplicationLoader.applicationLoaderInstance.takeUpdateLayout(getParentActivity(), updateLayoutWrapper);
        if (updateLayout != null) {
            updateLayout.updateAppUpdateViews(currentAccount, false);
        }

        updateLayout();
        checkUnreadCount(false);
        return contentView;
    }

    private void checkUnreadCount(boolean animated) {
        if (tabsView == null) {
            return;
        }

        if (app.nimarkogram.messenger.NimarkoConfig.tabsNoUnread) return;

        final int unreadCount = MessagesStorage.getInstance(currentAccount).getMainUnreadCount();
        if (unreadCount > 0) {
            final String unreadCountFmt = LocaleController.formatNumber(unreadCount, ',');
            tabs[INDEX_CHATS].setCounter(unreadCountFmt, false, animated);
        } else {
            tabs[INDEX_CHATS].setCounter(null, false, animated);
        }
    }


    public boolean openCallsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.menu_call_create, getString(R.string.GroupCallCreate2), () -> CallLogActivity.openCreateCall(this));
        if (getUserConfig().showCallsTab) {
            o.add(R.drawable.msg_archive_hide, getString(R.string.HideCallTab), () -> {
                getUserConfig().setShowCallsTab(false);
                checkUi_callTabVisible(false, true);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.callTabsVisibleToggled);
            });
        } else {
            o.add(R.drawable.menu_add_tab_24, getString(R.string.GroupCallShowInMainTabs), () -> {
                getUserConfig().setShowCallsTab(true);
                checkUi_callTabVisible(true, true);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.callTabsVisibleToggled);
            });
        }
        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    private Integer pendingFolderId;

    private boolean openFoldersSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        final boolean hasFolders = filters != null && filters.size() > 1;

        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        if (hasFolders) for (int i = 0; i < filters.size(); i++) {
            final MessagesController.DialogFilter folder = filters.get(i);
            final ActionBarMenuSubItem folderItem = new ActionBarMenuSubItem(getParentActivity(), 2, false, false, getResourceProvider());
            folderItem.setPadding(dp(18), 0, dp(18), 0);
            CharSequence title = folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name;
            title = Emoji.replaceEmoji(title, folderItem.getTextView().getPaint().getFontMetricsInt(), false);
            if (!folder.isDefault()) {
                title = MessageObject.replaceAnimatedEmoji(title, folder.entities, folderItem.getTextView().getPaint().getFontMetricsInt());
            }
            final int unreadCount = folder.isDefault()
                    ? MessagesStorage.getInstance(currentAccount).getMainUnreadCount()
                    : folder.unreadCount;
            if (unreadCount > 0) {
                final SpannableStringBuilder titleWithCounter = new SpannableStringBuilder(title);
                final int counterStart = titleWithCounter.length();
                titleWithCounter.append(String.valueOf(unreadCount));
                titleWithCounter.setSpan(
                        new FolderCounterSpan(unreadCount, hasUnmutedUnreadDialogs(folder)),
                        counterStart,
                        titleWithCounter.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                title = titleWithCounter;
                folderItem.setContentDescription(
                        TextUtils.concat(
                                folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name,
                                "\n",
                                LocaleController.formatPluralString("AccDescrUnreadCount", unreadCount)
                        )
                );
            }
            folderItem.setEmojiCacheType(folder.title_noanimate ? AnimatedEmojiDrawable.CACHE_TYPE_NOANIMATE_FOLDER : AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
            final int color = getMessagesController().folderTags ? folder.color : -1;
            String nmEmoticon = folder.isDefault() ? null : folder.emoticon;
            int nmFolderIcon = (nmEmoticon != null && !nmEmoticon.isEmpty())
                    ? app.nimarkogram.messenger.preferences.folders.helpers.FolderIconHelper.getTabIcon(nmEmoticon)
                    : R.drawable.msg_folders;
            folderItem.setTextAndIcon(title, 0, new FolderDrawable(getContext(), nmFolderIcon, color));
            folderItem.getTextView().setEmojiColor(getThemedColor(Theme.key_featuredStickers_addButton));
            folderItem.setMinimumWidth(160);
            folderItem.setOnClickListener(e -> {
                o.dismiss();
                openFolder(folder.id);
            });
            o.addView(folderItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        if (o.getItemsCount() == 0) return false;
        o.translate(-dp(8), -dp(4));
        o.setMaxHeight(dp(400));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.setGravity(Gravity.LEFT);
        o.show();

        return true;
    }

    private boolean hasUnmutedUnreadDialogs(MessagesController.DialogFilter folder) {
        final MessagesController messagesController = getMessagesController();
        final ArrayList<TLRPC.Dialog> dialogs = folder.isDefault()
                ? messagesController.getDialogs(0)
                : messagesController.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (!folder.isDefault()) {
                long dialogId = dialog.id;
                if (DialogObject.isEncryptedDialog(dialogId)) {
                    final TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                    if (encryptedChat != null) {
                        dialogId = encryptedChat.user_id;
                    }
                }
                if (!folder.includesDialog(getAccountInstance(), dialogId, dialog)) {
                    continue;
                }
            }
            if ((messagesController.getDialogUnreadCount(dialog) > 0 || dialog.unread_mark)
                    && !messagesController.isDialogMuted(dialog.id, 0)) {
                return true;
            }
        }
        return false;
    }

    private class FolderCounterSpan extends ReplacementSpan {

        private static final float HEIGHT_DP = 17.333f;
        private final String count;
        private final boolean hasUnmutedUnreadDialogs;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float counterWidth;

        FolderCounterSpan(int count, boolean hasUnmutedUnreadDialogs) {
            this.count = String.valueOf(count);
            this.hasUnmutedUnreadDialogs = hasUnmutedUnreadDialogs;
            textPaint.setTextSize(AndroidUtilities.dpf2(11));
            textPaint.setTypeface(AndroidUtilities.bold());
            counterWidth = Math.max(dp(HEIGHT_DP - 10), textPaint.measureText(this.count)) + dp(10);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            return (int) Math.ceil(dp(5) + counterWidth);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            final float left = x + dp(5);
            final float centerY = (top + bottom) / 2f + dp(1);
            final float halfHeight = dp(HEIGHT_DP) / 2f;
            backgroundPaint.setColor(getThemedColor(
                hasUnmutedUnreadDialogs ?
                    Theme.key_featuredStickers_addButton :
                    Theme.key_chats_tabUnreadUnactiveBackground
            ));
            textPaint.setColor(getThemedColor(Theme.key_actionBarDefault));
            AndroidUtilities.rectTmp.set(left, centerY - halfHeight, left + counterWidth, centerY + halfHeight);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, halfHeight, halfHeight, backgroundPaint);
            final Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
            final float baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
            canvas.drawText(count, left + (counterWidth - textPaint.measureText(count)) / 2f, baseline, textPaint);
        }
    }

    private void openFolder(int folderId) {
        if (viewPager.getCurrentPosition() == posChats() && dialogsActivity != null) {
            dialogsActivity.scrollToFolder(folderId);
        } else {
            if (dialogsActivity == null) {
                prepareDialogsActivity(null);
            }
            pendingFolderId = folderId;
            selectTab(posChats(), true);
            viewPager.scrollToPosition(posChats());
        }
    }

    public boolean openAccountSelector(View button) {
        final ArrayList<Integer> accountNumbers = new ArrayList<>();

        accountNumbers.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            if (l1 > l2) {
                return 1;
            } else if (l1 < l2) {
                return -1;
            }
            return 0;
        });

        ItemOptions o = ItemOptions.makeOptions(this, button);
        if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
            o.add(R.drawable.msg_addbot, getString(R.string.AddAccount), () -> {
                int freeAccounts = 0;
                Integer availableAccount = null;
                for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
                    if (!UserConfig.getInstance(a).isClientActivated()) {
                        freeAccounts++;
                        if (availableAccount == null) {
                            availableAccount = a;
                        }
                    }
                }
                if (!UserConfig.hasPremiumOnAccounts()) {
                    freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);
                }
                if (freeAccounts > 0 && availableAccount != null) {
                    presentFragment(new LoginActivity(availableAccount));
                } else if (!UserConfig.hasPremiumOnAccounts()) {
                    showDialog(new LimitReachedBottomSheet(this, getContext(), TYPE_ACCOUNTS, currentAccount, null));
                }
            });
        }

        if (BuildConfig.DEBUG_PRIVATE_VERSION) {
            o.add(R.drawable.menu_download_round, "Dump Canvas", () -> AndroidUtilities.runOnUIThread(this::dumpCanvas, 1000));
        }

        if (accountNumbers.size() > 0) {
            if (o.getItemsCount() > 0) o.addGap();
            for (int acc : accountNumbers) {
                final int account = acc;
                final View btn = accountView(acc, currentAccount == acc);
                btn.setOnClickListener(v -> {
                    if (currentAccount == account) return;
                    o.dismiss();
                    if (LaunchActivity.instance != null) {
                        LaunchActivity.instance.switchToAccount(account, true);
                    }
                });
                o.addView(btn, LayoutHelper.createLinear(230, 48));
            }
        }

        if (o.getItemsCount() > 0) o.addGap();
        o.add(R.drawable.tabs_reorder, getString(R.string.NM_BT_OpenEditor),
                () -> presentFragment(new BottomTabsPreferencesActivity()));

        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();

        HintsController.Hint.AccountSwitchHint.doNotShowAgain();

        return true;
    }

    public LinearLayout accountView(int account, boolean selected) {
        final LinearLayout btn = new LinearLayout(getContext());
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 0, 0));

        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(user);

        final FrameLayout avatarContainer = new FrameLayout(getContext()) {
            private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (selected) {
                    selectedPaint.setStyle(Paint.Style.STROKE);
                    selectedPaint.setStrokeWidth(dp(1.33f));
                    selectedPaint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                    canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, dp(16), selectedPaint);
                }
                super.dispatchDraw(canvas);
            }
        };
        btn.addView(avatarContainer, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final BackupImageView avatarView = new BackupImageView(getContext());
        if (selected) {
            avatarView.setScaleX(0.833f);
            avatarView.setScaleY(0.833f);
        }
        avatarView.setRoundRadius(dp(16));
        avatarView.getImageReceiver().setCurrentAccount(account);
        avatarView.setForUserOrChat(user, avatarDrawable);
        avatarContainer.addView(avatarView, LayoutHelper.createLinear(32, 32, Gravity.CENTER, 1, 1, 1, 1));

        final TextView textView = new TextView(getContext());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        textView.setText(UserObject.getUserName(user));
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        btn.addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 13, 0, 14, 0));

        return btn;
    }

    @Override
    protected void onViewPagerScrollEnd() {
        if (tabsView != null) {
            selectTab(viewPager.getCurrentPosition(), true);
            setGestureSelectedOverride(0, false);
        }
        blur3_invalidateBlur();

        if (viewPager != null) {
            final int currentPosition = viewPager.getCurrentPosition();
            if (currentPosition != posSettings() && dropCallsFragmentAfterPageScroll) {
                dropFragmentAtPosition(posSettings());
                dropCallsFragmentAfterPageScroll = false;
            }
            int profilePosition = posProfile();
            if (profilePosition >= 0 && currentPosition != profilePosition) {
                dropFragmentAtPosition(profilePosition);
            }
            if (pendingFolderId != null && currentPosition == posChats() && dialogsActivity != null) {
                dialogsActivity.scrollToFolder(pendingFolderId);
                pendingFolderId = null;
            }
            if (app.nimarkogram.messenger.banners.NimarkoBannerConfig.enabled) {
                try {
                    app.nimarkogram.messenger.banners.NimarkoBannerRenderer.getInstance()
                            .onTabVisibilityChanged(currentPosition == posProfile() ? 1f : 0f);
                } catch (Throwable ignored) {}
            }
        }

    }

    @Override
    protected void onViewPagerTabAnimationUpdate(boolean manual) {
        final boolean isDragByGesture = !manual;

        if (tabsView != null) {
            final float position = viewPager.getPositionAnimated();
            setGestureSelectedOverride(position, isDragByGesture);
            if (isDragByGesture) {
                final int target = viewPager.getNextPositionAlpha() > viewPager.getCurrentPositionAlpha()
                        ? viewPager.getNextPosition()
                        : viewPager.getCurrentPosition();
                selectTab(target, true);
            }
        }

        if (app.nimarkogram.messenger.banners.NimarkoBannerConfig.enabled) {
            try {
                float pos = viewPager.getPositionAnimated();
                int profilePosition = posProfile();
                float vis = profilePosition < 0 ? 0f : Math.max(0f, 1f - Math.abs(profilePosition - pos));
                app.nimarkogram.messenger.banners.NimarkoBannerRenderer.getInstance().onTabVisibilityChanged(vis);
            } catch (Throwable ignored) {}
        }

        checkUi_fadeView();
        blur3_invalidateBlur();
        contentView.invalidate();
    }


    @Override
    protected int getFragmentsCount() {
        return app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE
                .getEnabledTabs().size();
    }

    @Override
    protected int getStartPosition() {
        return posChats();
    }

    private DialogsActivity dialogsActivity;

    @Override
    public boolean onBackPressed(boolean invoked) {
        final boolean result = super.onBackPressed(invoked);
        if (result) {
            final int startPosition = getStartPosition();
            if (viewPager.getCurrentPosition() != startPosition) {
                if (invoked) {
                    viewPager.scrollToPosition(startPosition);
                }
                return false;
            }
        }
        return result;
    }

    public DialogsActivity prepareDialogsActivity(Bundle bundle) {
        if (bundle == null) {
            bundle = new Bundle();
        }

        bundle.putBoolean("hasMainTabs", app.nimarkogram.messenger.NimarkoConfig.showMainTabs);
        dialogsActivity = new DialogsActivity(bundle);
        dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
        putFragmentAtPosition(posChats(), dialogsActivity);
        return dialogsActivity;
    }

    @Override
    protected BaseFragment createBaseFragmentAt(int position) {
        final boolean showTabsBar = app.nimarkogram.messenger.NimarkoConfig.showMainTabs;
        app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType type = tabTypeAt(position);
        if (type == null) return null;
        Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", showTabsBar);
        switch (type) {
            case CHATS:
                dialogsActivity = new DialogsActivity(args);
                dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
                return dialogsActivity;
            case PROFILE:
                args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                args.putBoolean("my_profile", true);
                return new ProfileActivity(args);
            case SETTINGS:
                if (getUserConfig().showCallsTab) {
                    args.putBoolean("needFinishFragment", false);
                    return new CallLogActivity(args);
                }
                return new SettingsActivity(args);
            default:
                return null;
        }
    }

    public DialogsActivity getDialogsActivity() {
        return dialogsActivity;
    }

    private void syncFragmentsWithSettings() {
        if (viewPager == null) return;
        java.util.List<app.nimarkogram.messenger.utils.ui.MainTabsManager.Tab> enabled =
                app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE.getEnabledTabs();
        app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType currentType = null;
        if (viewPager != null) {
            org.telegram.ui.ViewPagerActivity.FragmentState curState =
                    fragmentsArr.get(viewPager.getCurrentPosition());
            if (curState != null && curState.fragment != null) {
                org.telegram.ui.ActionBar.BaseFragment f = curState.fragment;
                if (f instanceof DialogsActivity) {
                    currentType = app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.CHATS;
                } else if (f instanceof ProfileActivity) {
                    currentType = app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.PROFILE;
                } else if (f instanceof SettingsActivity || f instanceof CallLogActivity) {
                    currentType = app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.SETTINGS;
                }
            }
        }
        for (int i = 0, n = Math.max(enabled.size(), TABS_COUNT); i < n; i++) {
            dropFragmentAtPosition(i);
        }
        dialogsActivity = null;
        int targetPos = posChats();
        if (currentType != null) {
            int p = app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE
                    .getPosition(currentType);
            if (p >= 0) targetPos = p;
        }
        viewPager.setPosition(targetPos);
        viewPager.rebuild(false);
        if (tabs != null) {
            selectTab(targetPos, false);
        }
    }


    public GlassTabView[] tabs;

    public void selectTab(int position, boolean animated) {
        for (int a = 0; a < tabs.length; a++) {
            GlassTabView tab = tabs[a];
            tab.setSelected(indexToPosition(a) == position, animated);
        }
    }

    public void setGestureSelectedOverride(float animatedPosition, boolean allow) {
        final float curAlpha = viewPager.getCurrentPositionAlpha();
        final float nextAlpha = viewPager.getNextPositionAlpha();
        final int curPagerPos = viewPager.getCurrentPosition();
        final int nextPagerPos = viewPager.getNextPosition();
        for (int index = 0; index < tabs.length; index++) {
            final int position = indexToPosition(index);
            float visibility;
            if (position == curPagerPos) {
                visibility = curAlpha;
            } else if (position == nextPagerPos) {
                visibility = nextAlpha;
            } else {
                visibility = 0f;
            }
            tabs[index].setGestureSelectedOverride(visibility, allow);
        }
        tabsView.invalidate();
    }


    public interface TabFragmentDelegate {
        default boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
            return false;
        }

        default void onParentScrollToTop() {

        }

        default BlurredBackgroundSourceRenderNode getGlassSource() {
            return null;
        }
    }

    @Override
    protected boolean canScrollForward(MotionEvent ev) {
        return canScrollInternal(ev, true);
    }

    @Override
    protected boolean canScrollBackward(MotionEvent ev) {
        return canScrollInternal(ev, false);
    }

    private boolean canScrollInternal(MotionEvent ev, boolean forward) {
        final BaseFragment fragment = getCurrentVisibleFragment();

        if (app.nimarkogram.messenger.NimarkoConfig.showMainTabs) {
            if (fragment instanceof TabFragmentDelegate) {
                final TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                return delegate.canParentTabsSlide(ev, forward);
            }
        } else if (app.nimarkogram.messenger.NimarkoConfig.openSettingsBySwipe || !app.nimarkogram.messenger.NimarkoConfig.showMainTabs) {
            final int pos = viewPager != null ? viewPager.getCurrentPosition() : 0;
            final int count = getFragmentsCount();
            if (fragment instanceof DialogsActivity) {
                final DialogsActivity da = getDialogsActivity();
                if (da == null) return false;
                if (da.getRightSlidingProgress() > 0.5f) return false;
                if (da.isSearchVisible()) return false;

                final org.telegram.ui.Components.FilterTabsView ftv = da.getFilterTabsView();
                final boolean isFirstTab = ftv == null
                        || ftv.getTabsCount() < 2
                        || ftv.getCurrentTabId() == ftv.getFirstTabId();
                final boolean isLastTab = ftv == null
                        || ftv.getTabsCount() < 2
                        || ftv.getCurrentTabId() == ftv.getLastTabId();
                if (forward) {
                    return isLastTab && pos < count - 1;
                } else {
                    return isFirstTab && pos > 0;
                }
            }
            return forward ? (pos < count - 1) : (pos > 0);
        }

        return false;
    }


    private int navigationBarHeight;
    private int insetLeft;
    private int insetRight;

    @NonNull
    @Override
    protected WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);

        insetLeft = systemInsets.left;
        insetRight = systemInsets.right;

        navigationBarHeight = systemInsets.bottom;
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        updateLayoutWrapper.setPadding(0, 0, 0, navigationBarHeight);

        ViewGroup.MarginLayoutParams lp;
        {
            final int height = app.nimarkogram.messenger.NimarkoConfig.showMainTabs
                    ? (navigationBarHeight + updateLayoutHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS))
                    : 0;
            lp = (ViewGroup.MarginLayoutParams) fadeView.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                fadeView.setLayoutParams(lp);
            }
        }
        {
            int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
            if (tabletLayout) {
                bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
            }
            lp = (ViewGroup.MarginLayoutParams) viewPager.getLayoutParams();
            if (lp.bottomMargin != bottomMargin || lp.leftMargin != systemInsets.left || lp.rightMargin != systemInsets.right) {
                lp.leftMargin = systemInsets.left;
                lp.rightMargin = systemInsets.right;
                lp.bottomMargin = bottomMargin;
                viewPager.setLayoutParams(lp);
            }
        }

        tabsViewWrapper.setPadding(systemInsets.left, 0, systemInsets.right, navigationBarHeight);

        final WindowInsetsCompat consumed = isUpdateLayoutVisible ?
            insets.inset(0, 0, 0, navigationBarHeight) : insets;

        checkUi_tabsPosition();
        checkUi_fadeView();

        return super.onApplyWindowInsets(v, consumed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (lifecycleDestroyed) {
            return;
        }
        if (id == NotificationCenter.notificationsCountUpdated || id == NotificationCenter.updateInterfaces) {
            checkUnreadCount(fragmentView != null && fragmentView.isAttachedToWindow());
        } else if (id == NotificationCenter.appUpdateLoading) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(null);
                updateLayout.updateAppUpdateViews(currentAccount, true);
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(args);
            }
        } else if (id == NotificationCenter.appUpdateAvailable) {
            if (updateLayout != null && LaunchActivity.instance != null) {
                updateLayout.updateAppUpdateViews(currentAccount, LaunchActivity.instance.getMainFragmentsStackSize() == 1);
            }
        } else if (id == NotificationCenter.needSetDayNightTheme) {
            if (parentLayout == null || parentLayout.getLastFragment() == this) {
                clearAllHiddenFragments();
            }
        } else if (id == NotificationCenter.callTabsVisibleToggled) {
            final boolean callTabsVisible = getUserConfig().showCallsTab;
            checkUi_callTabVisible(callTabsVisible, true);
            if (viewPager != null && viewPager.getCurrentPosition() == posSettings()) {
                viewPager.scrollToPosition(posChats());
                selectTab(posChats(), true);
                dropCallsFragmentAfterPageScroll = true;
            } else {
                dropFragmentAtPosition(posSettings());
            }
        } else if (id == NotificationCenter.mainUserInfoChanged) {
            if (tabs != null && tabs[INDEX_PROFILE] != null) {
                tabs[INDEX_PROFILE].updateUserAvatar(currentAccount);
            }
        } else if (id == NotificationCenter.cgTabsUpdated) {
            boolean showMainTabsFlag = app.nimarkogram.messenger.NimarkoConfig.showMainTabs;
            if (tabsViewWrapper != null) {
                tabsViewWrapper.setVisibility(showMainTabsFlag ? View.VISIBLE : View.GONE);
            }
            syncFragmentsWithSettings();
            applyEditorTabsVisibility(true);
            if (tabs != null) {
                for (GlassTabView t : tabs) {
                    if (t != null) {
                        t.setTitleVisible(app.nimarkogram.messenger.NimarkoConfig.showMainTabsTitle);
                    }
                }
            }
            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
        }
    }

    private NotificationCenter.ObserversGroup observersGroup;
    private NotificationCenter.ObserversGroup globalObserversGroup;
    private boolean lifecycleDestroyed;


    @Override
    public boolean onFragmentCreate() {
        lifecycleDestroyed = false;
        observersGroup = NotificationCenter.getInstance(currentAccount).createObserversGroup(this)
            .add(NotificationCenter.fileLoaded)
            .add(NotificationCenter.fileLoadProgressChanged)
            .add(NotificationCenter.fileLoadFailed)
            .add(NotificationCenter.notificationsCountUpdated)
            .add(NotificationCenter.updateInterfaces)
            .add(NotificationCenter.callTabsVisibleToggled)
            .add(NotificationCenter.mainUserInfoChanged)
            .add(NotificationCenter.contactsPermissionBadgeCheck);

        globalObserversGroup = NotificationCenter.getGlobalInstance().createObserversGroup(this)
            .add(NotificationCenter.appUpdateAvailable)
            .add(NotificationCenter.appUpdateLoading)
            .add(NotificationCenter.needSetDayNightTheme)
            .add(NotificationCenter.cgTabsUpdated);

        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        lifecycleDestroyed = true;
        if (accountChangeHintRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(accountChangeHintRunnable);
            accountChangeHintRunnable = null;
        }
        if (openSearchChatsRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(openSearchChatsRunnable);
            openSearchChatsRunnable = null;
        }
        if (accountSwitchHint != null) {
            accountSwitchHint.hide();
            accountSwitchHint = null;
        }
        Bulletin.removeDelegate(this);
        Bulletin.removeDelegate(contentView);

        if (observersGroup != null) {
            observersGroup.removeAllObservers();
            observersGroup = null;
        }
        if (globalObserversGroup != null) {
            globalObserversGroup.removeAllObservers();
            globalObserversGroup = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_TABS_VISIBLE) {
            checkUi_tabsPosition();
            checkUi_fadeView();
        }
    }

    private void checkUi_fadeView() {
        if (viewPager == null || fadeView == null) {
            return;
        }

        final boolean showMainTabs = app.nimarkogram.messenger.NimarkoConfig.showMainTabs;

        final float animatedPosition = viewPager.getPositionAnimated();
        final int profilePosition = posProfile();
        final float isProfile = profilePosition < 0 ? 0f
                : 1f - MathUtils.clamp(Math.abs(profilePosition - animatedPosition), 0, 1);
        final float hide = 1f - AndroidUtilities.getNavigationBarThirdButtonsFactor(0, 1f, navigationBarHeight);
        float alpha = showMainTabs
                ? (1f - isProfile * hide) * animatorTabsVisible.getFloatValue()
                : 0f;
        if (tabletLayout) {
            alpha = 0.0f;
        }

        fadeView.setAlpha(alpha);
        fadeView.setTranslationY(isProfile * dp(48));
        fadeView.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
    }

    private boolean hiddenByOverlay = false;

    private void checkUi_tabsPosition() {
        if (hiddenByOverlay) return;
        if (!app.nimarkogram.messenger.NimarkoConfig.showMainTabs) {
            final View off = tabsContainer != null ? tabsContainer : tabsViewWrapper;
            off.setVisibility(View.GONE);
            off.setAlpha(0f);
            off.setClickable(false);
            off.setEnabled(false);
            if (tabsViewWrapper != off) {
                tabsViewWrapper.setVisibility(View.GONE);
            }
            return;
        }
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        final int normalY = -(updateLayoutHeight);
        final int hiddenY = normalY + dp(40);

        final float factor = animatorTabsVisible.getFloatValue();

        final View surface = tabsContainer != null ? tabsContainer : tabsViewWrapper;
        surface.setTranslationY(lerp(hiddenY, normalY, factor));
        surface.setAlpha(factor);
        surface.setClickable(factor >= 1);
        surface.setEnabled(factor >= 1);
        surface.setVisibility(factor > 0 ? View.VISIBLE : View.GONE);
        if (tabsViewWrapper != surface) {
            tabsViewWrapper.setTranslationY(lerp(hiddenY, normalY, factor));
        }
    }

    private void checkUi_callTabVisible(boolean callTabsVisible, boolean animated) {
        if (tabsView != null) {
            boolean settingsAllowed = app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE
                    .hasTab(app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.SETTINGS);
            tabsView.setViewVisible(tabs[INDEX_SETTINGS], settingsAllowed, animated);
            tabsView.setViewVisible(tabs[INDEX_CALLS], false, animated);
        }
    }

    private void applyEditorTabsVisibility(boolean animated) {
        if (tabsView == null || tabs == null) return;
        app.nimarkogram.messenger.utils.ui.MainTabsManager mgr =
                app.nimarkogram.messenger.utils.ui.MainTabsManager.INSTANCE;
        tabsView.setViewVisible(tabs[INDEX_PROFILE],
                mgr.hasTab(app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.PROFILE), animated);
        tabsView.setViewVisible(tabs[INDEX_CHATS],
                mgr.hasTab(app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType.CHATS), animated);
        checkUi_callTabVisible(getUserConfig().showCallsTab, animated);
        for (app.nimarkogram.messenger.utils.ui.MainTabsManager.Tab t : mgr.getAllTabs()) {
            GlassTabView view = tabForType(t.getType());
            if (view != null && view.getParent() == tabsView) {
                tabsView.bringChildToFront(view);
            }
        }
    }

    private GlassTabView tabForType(app.nimarkogram.messenger.utils.ui.MainTabsManager.TabType type) {
        switch (type) {
            case PROFILE: return tabs[INDEX_PROFILE];
            case CHATS: return tabs[INDEX_CHATS];
            case SETTINGS: return tabs[INDEX_SETTINGS];
            default: return null;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = this::blur3_updateColors;
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_dialogBackground));

        return themeDescriptions;
    }


    private class MainTabsActivityControllerImpl implements MainTabsActivityController {
        @Override
        public void setTabsVisible(boolean visible) {
            animatorTabsVisible.setValue(visible, true);
        }

        @Override
        public void setTabsHiddenByOverlay(boolean hidden) {
            if (tabsViewWrapper == null) return;
            hiddenByOverlay = hidden;
            if (hidden) {
                tabsViewWrapper.animate().cancel();
                tabsViewWrapper.animate()
                        .alpha(0f)
                        .translationY(dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 16))
                        .setDuration(200)
                        .withEndAction(() -> tabsViewWrapper.setVisibility(View.GONE))
                        .start();
            } else {
                tabsViewWrapper.setVisibility(View.VISIBLE);
                tabsViewWrapper.animate().cancel();
                tabsViewWrapper.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(200)
                        .withEndAction(() -> checkUi_tabsPosition())
                        .start();
            }
        }
    }


    @Override
    public boolean canBeginSlide() {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null && fragment.canBeginSlide();
    }

    @Override
    public void onBeginSlide() {
        super.onBeginSlide();
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onBeginSlide();
        }
    }

    @Override
    public void onSlideProgress(boolean isOpen, float progress) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onSlideProgress(isOpen, progress);
        }
    }

    @Override
    public Animator getCustomSlideTransition(boolean topFragment, boolean backAnimation, float distanceToMove) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null ? fragment.getCustomSlideTransition(topFragment, backAnimation, distanceToMove) : null;
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.prepareFragmentToSlide(topFragment, beginSlide);
        }
    }


    private HintView2 accountSwitchHint;
    private boolean accountSwitchHintShown;
    private Runnable accountChangeHintRunnable;
    private Runnable openSearchChatsRunnable;

    private void showAccountChangeHint() {
        if (accountSwitchHintShown) return;

        if (accountSwitchHint == null && HintsController.Hint.AccountSwitchHint.show()) {
            accountChangeHintRunnable = () -> {
                accountChangeHintRunnable = null;
                if (lifecycleDestroyed || isFinished || fragmentView == null || getContext() == null
                        || tabs == null || INDEX_PROFILE < 0 || INDEX_PROFILE >= tabs.length
                        || tabsView == null || contentView == null) {
                    return;
                }

                final View v = tabs[INDEX_PROFILE];
                if (v == null || !v.isAttachedToWindow()) {
                    return;
                }
                final float translate = (contentView.getWidth() - ((tabsView.getX() + v.getX()) + v.getWidth()) + v.getWidth() / 2f) / AndroidUtilities.density;

                final HintView2 hint = new HintView2(getContext(), HintView2.DIRECTION_BOTTOM);
                accountSwitchHint = hint;
                hint.setTranslationY(-navigationBarHeight + dp(4));
                hint.setPadding(dp(7.33f), 0, dp(7.33f), 0);
                hint.setMultilineText(false);
                hint.setCloseButton(true);
                hint.setText(getString(R.string.SwitchAccountHint));
                hint.setJoint(1, -translate + 7.33f);
                contentView.addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0, 0, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
                hint.setOnHiddenListener(() -> {
                    AndroidUtilities.removeFromParent(hint);
                    if (accountSwitchHint == hint) {
                        accountSwitchHint = null;
                    }
                });
                hint.setDuration(8000);
                hint.show();

                HintsController.Hint.AccountSwitchHint.increment();
            };
            AndroidUtilities.runOnUIThread(accountChangeHintRunnable, 1500);
        }

        accountSwitchHintShown = true;
    }


    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceTabGlass;

    private final RectF fragmentPosition = new RectF();
    private void blur3_invalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || iBlur3SourceTabGlass == null || fragmentView == null) {
            return;
        }

        final int width = fragmentView.getMeasuredWidth();
        final int height = fragmentView.getMeasuredHeight();

        iBlur3SourceTabGlass.setSize(width, height);
        iBlur3SourceTabGlass.updateDisplayListIfNeeded();
    }

    private void blur3_updateFadeColors() {
        blur3_updateFadeColors(getEstBackgroundColor());
    }

    private void blur3_updateFadeColors(int color) {
        if (iBlur3SourceColor.getColor() != color) {
            iBlur3SourceColor.setColor(color);
            if (fadeView != null) {
                fadeView.invalidate();
            }
        }
    }

    private void blur3_updateColors() {
        blur3_updateFadeColors();
        if (tabsViewBackground != null) {
            tabsViewBackground.updateColors();
        }
        if (searchButtonBackground != null) {
            searchButtonBackground.updateColors();
        }
        blur3_invalidateBlur();
        if (tabsView != null) {
            tabsView.invalidate();
        }
        if (tabs != null) {
            for (GlassTabView tabView : tabs) {
                tabView.updateColorsLottie();
            }
        }
        if (searchButton != null) {
            searchButton.invalidate();
            searchButton.updateColorsLottie();
        }
    }


    private void onSearchButtonClick() {
        if (app.nimarkogram.messenger.NimarkoConfig.mainTabsForceOpenChats) {
            openSearchChats();
            return;
        }
        BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment instanceof SettingsActivity) {
            ((SettingsActivity) fragment).openSearch();
            return;
        }
        openSearchChats();
    }

    private void openSearchChats() {
        if (viewPager == null) return;
        if (viewPager.getCurrentPosition() != posChats()) {
            selectTab(posChats(), true);
            viewPager.scrollToPosition(posChats());
        }
        if (openSearchChatsRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(openSearchChatsRunnable);
        }
        openSearchChatsRunnable = () -> {
            openSearchChatsRunnable = null;
            if (lifecycleDestroyed || isFinished || fragmentView == null) {
                return;
            }
            final DialogsActivity da = getDialogsActivity();
            if (da != null) {
                da.search("", true);
            }
        };
        AndroidUtilities.runOnUIThread(openSearchChatsRunnable, 100);
    }

    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }
}
