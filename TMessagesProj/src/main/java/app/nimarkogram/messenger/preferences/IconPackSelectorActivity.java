/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SmoothRippleDrawable;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.icons.NimarkoIconResources;

public class IconPackSelectorActivity extends BaseFragment {


    private static final int[] VALUES = {
            NimarkoConfig.ICON_REPLACE_NONE, NimarkoConfig.ICON_REPLACE_SOLAR,
            NimarkoConfig.ICON_REPLACE_LIQUID_GLASS, NimarkoConfig.ICON_REPLACE_PLUMPY
    };
    private static final int[] TITLES = {
            R.string.NM_IconPack_DefaultTitle, R.string.NM_IconPack_SolarTitle,
            R.string.NM_IconPack_LiquidTitle, R.string.NM_IconPack_PlumpyTitle
    };
    private static final int[] DESCRIPTIONS = {
            R.string.NM_IconPicker_DefaultDescription, R.string.NM_IconPicker_SolarDescription,
            R.string.NM_IconPicker_LiquidDescription, R.string.NM_IconPicker_PlumpyDescription
    };
    private static final String PREVIEW_KEY = "nimarko_icon_pack_preview";

    private final ArrayList<IconPackPreviewCard> cards = new ArrayList<>();
    private final int[] pagerLocation = new int[2];
    private FrameLayout root;
    private LinearLayout footer;
    private ScrollView scrollView;
    private PreviewPager pager;
    private TextView positionLabel, applyButton;
    private ImageView previousButton, nextButton;
    private PageIndicator indicator;
    private int previewValue = -1;
    private int chromePackValue = -1;
    private Configuration chromeConfiguration;
    private boolean rtl;
    private int pagerScrollState = ViewPager.SCROLL_STATE_IDLE;
    private final Runnable refreshColorsRunnable = this::refreshColors;

    @Override
    public View createView(Context context) {
        releasePager();
        rtl = LocaleController.isRTL;
        if (!isKnownValue(previewValue)) previewValue = normalizedValue(NimarkoConfig.iconReplacement);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.AP_IconReplacements));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        LinearLayout content = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), dp(560));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(12));

        for (int i = 0; i < VALUES.length; i++) {
            cards.add(new IconPackPreviewCard(context, VALUES[i], getString(TITLES[i]),
                    getString(DESCRIPTIONS[i]), getResourceProvider()));
        }
        pager = new PreviewPager(context);
        pager.setId(View.generateViewId());
        pager.setPadding(dp(24), dp(8), dp(24), dp(12));
        pager.setClipToPadding(false);
        pager.setClipChildren(false);
        pager.setPageMargin(dp(12));
        pager.setOffscreenPageLimit(VALUES.length - 1);
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pager.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        final ArrayList<IconPackPreviewCard> pages = new ArrayList<>(cards);
        final boolean pagesRtl = rtl;
        pager.setAdapter(new PagerAdapter() {
            @Override public int getCount() { return pages.size(); }
            @Override public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }
            @NonNull @Override public Object instantiateItem(@NonNull ViewGroup container, int position) {
                IconPackPreviewCard card = pages.get(logicalIndex(position, pagesRtl));
                card.setLayoutDirection(pagesRtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
                container.addView(card);
                return card;
            }
            @Override public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
            @Override public CharSequence getPageTitle(int position) {
                return getString(TITLES[logicalIndex(position, pagesRtl)]);
            }
        });
        pager.setCurrentItem(visualIndex(indexOfValue(previewValue), rtl), false);
        final PreviewPager currentPager = pager;
        pager.setPageTransformer(false, (page, ignoredPosition) -> {
            int width = currentPager.getWidth() - currentPager.getPaddingLeft() - currentPager.getPaddingRight();
            float offset = width <= 0 ? 0 : (page.getLeft() - currentPager.getScrollX() - currentPager.getPaddingLeft()) / (float) width;
            float distance = Math.min(1f, Math.abs(offset));
            boolean effects = allowMotion();
            page.setScaleX(effects ? 1f - .045f * distance : 1f);
            page.setScaleY(effects ? 1f - .045f * distance : 1f);
            page.setTranslationY(effects ? dp(6) * distance : 0f);
            page.setAlpha(effects ? 1f - .25f * distance : 1f);
        }, View.LAYER_TYPE_NONE);
        content.addView(pager, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f));

        LinearLayout navigation = new LinearLayout(context);
        navigation.setGravity(Gravity.CENTER);
        navigation.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        previousButton = navigationButton(context, true);
        nextButton = navigationButton(context, false);
        navigation.addView(rtl ? nextButton : previousButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL));
        LinearLayout middle = new LinearLayout(context);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        indicator = new PageIndicator(context);
        middle.addView(indicator, LayoutHelper.createLinear(100, 24));
        positionLabel = text(context, 12, false);
        positionLabel.setGravity(Gravity.CENTER);
        positionLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        middle.addView(positionLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        navigation.addView(middle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        navigation.addView(rtl ? previousButton : nextButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL));
        content.addView(navigation, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        applyButton = new IconPackApplyButton(context, getResourceProvider());
        applyButton.setFocusable(true);
        applyButton.setOnClickListener(v -> {
            if (pagerScrollState == ViewPager.SCROLL_STATE_IDLE) select(previewValue);
        });
        ViewCompat.setAccessibilityDelegate(applyButton, new androidx.core.view.AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        footer = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), dp(560));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(24), dp(12), dp(24), dp(12));
        footer.addView(applyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageScrolled(int position, float offset, int pixels) {
                if (indicator != null) indicator.setPosition(position + offset);
            }
            @Override public void onPageSelected(int position) {
                previewValue = VALUES[logicalIndex(position, rtl)];
                refreshSelection();
            }
            @Override public void onPageScrollStateChanged(int state) {
                pagerScrollState = state;
                refreshSelection();
            }
        });
        FrameLayout centered = new FrameLayout(context);
        centered.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(centered, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        LinearLayout scaffold = new LinearLayout(context);
        scaffold.setOrientation(LinearLayout.VERTICAL);
        scaffold.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        scaffold.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        root = new FrameLayout(context);
        root.addView(scaffold, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        chromePackValue = installedPack(root.getResources());
        chromeConfiguration = new Configuration(root.getResources().getConfiguration());
        refreshColors();
        indicator.setPosition(pager.getCurrentItem());
        ViewCompat.setOnApplyWindowInsetsListener(root, this::onInsetsInternal);
        ViewCompat.requestApplyInsets(root);
        return fragmentView;
    }

    private TextView text(Context context, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private ImageView navigationButton(Context context, boolean previous) {
        ImageView view = new ImageView(context);
        view.setImageResource(R.drawable.msg_arrowright);
        view.setRotation(previous != rtl ? 180f : 0f);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setFocusable(true);
        view.setContentDescription(getString(previous ? R.string.NM_IconPicker_Previous : R.string.NM_IconPicker_Next));
        view.setOnClickListener(v -> {
            if (pager == null) return;
            int index = indexOfValue(previewValue) + (previous ? -1 : 1);
            if (index >= 0 && index < VALUES.length) pager.setCurrentItem(visualIndex(index, rtl), allowMotion());
        });
        return view;
    }

    private static boolean allowMotion() {
        return SharedConfig.animationsEnabled() && !AndroidUtilities.isAccessibilityTouchExplorationEnabled()
                && (Build.VERSION.SDK_INT >= 26 ? android.animation.ValueAnimator.areAnimatorsEnabled()
                : AndroidUtilities.getAnimatorDurationScale() > 0f);
    }
    private static boolean isKnownValue(int value) {
        for (int known : VALUES) if (known == value) return true;
        return false;
    }
    private static int normalizedValue(int value) {
        return isKnownValue(value) ? value : NimarkoConfig.ICON_REPLACE_NONE;
    }
    private static int indexOfValue(int value) {
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i] == value) return i;
        return 0;
    }
    private static int logicalIndex(int position, boolean rtl) {
        return rtl ? VALUES.length - 1 - position : position;
    }
    private static int visualIndex(int index, boolean rtl) { return logicalIndex(index, rtl); }

    private static int installedPack(Resources resources) {
        return resources instanceof NimarkoIconResources
                ? ((NimarkoIconResources) resources).getAppliedSelection() : NimarkoConfig.iconReplacement;
    }

    @Override public boolean onIconPackResourcesChanged() {
        if (isFinished || isFinishing() || root == null || fragmentView != root || actionBar == null
                || previousButton == null || nextButton == null || actionBar.getBackButton() == null
                || rtl != LocaleController.isRTL) return false;
        Resources resources = root.getResources();
        if (chromeConfiguration == null || !chromeConfiguration.equals(resources.getConfiguration())) return false;
        int selection = installedPack(resources);
        if (chromePackValue == selection) return true;

        Drawable previous = resources.getDrawable(R.drawable.msg_arrowright).mutate();
        Drawable next = resources.getDrawable(R.drawable.msg_arrowright).mutate();
        Drawable back = resources.getDrawable(R.drawable.ic_ab_back).mutate();
        boolean animate = allowMotion() && root.isAttachedToWindow() && root.isShown();
        previousButton.setImageDrawable(chromeTransition(previousButton.getDrawable(), previous, animate));
        nextButton.setImageDrawable(chromeTransition(nextButton.getDrawable(), next, animate));
        actionBar.setBackButtonDrawable(chromeTransition(actionBar.getBackButton().getDrawable(), back, animate));
        previousButton.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        nextButton.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        actionBar.getBackButton().setColorFilter(actionBar.getItemsColor());
        chromePackValue = selection;
        return true;
    }

    private Drawable chromeTransition(Drawable previous, Drawable next, boolean animated) {
        if (!animated || previous == null) return next;
        if (previous instanceof TransitionDrawable) previous = ((TransitionDrawable) previous).getDrawable(1);
        TransitionDrawable transition = new TransitionDrawable(new Drawable[]{previous, next});
        transition.setLayerGravity(0, Gravity.CENTER);
        transition.setLayerGravity(1, Gravity.CENTER);
        transition.setCrossFadeEnabled(true);
        transition.startTransition(220);
        return transition;
    }

    private void finishPresentationAnimations() {
        if (applyButton instanceof IconPackApplyButton) ((IconPackApplyButton) applyButton).finishTransition();
        int selected = normalizedValue(NimarkoConfig.iconReplacement);
        for (int i = 0; i < cards.size(); i++) cards.get(i).setApplied(VALUES[i] == selected, false);
        for (ImageView arrow : new ImageView[]{previousButton, nextButton}) {
            if (arrow != null && arrow.getDrawable() instanceof TransitionDrawable) {
                arrow.setImageDrawable(((TransitionDrawable) arrow.getDrawable()).getDrawable(1));
            }
        }
        if (actionBar != null && actionBar.getBackButton() != null
                && actionBar.getBackButton().getDrawable() instanceof TransitionDrawable) {
            actionBar.setBackButtonDrawable(((TransitionDrawable) actionBar.getBackButton().getDrawable()).getDrawable(1));
        }
    }

    private void refreshSelection() {
        refreshSelection(true);
    }

    private void refreshSelection(boolean animated) {
        if (applyButton == null || positionLabel == null) return;
        int index = indexOfValue(previewValue);
        int selected = normalizedValue(NimarkoConfig.iconReplacement);
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).setApplied(VALUES[i] == selected, animated && allowMotion());
            cards.get(i).setImportantForAccessibility(i == index
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        boolean applied = previewValue == NimarkoConfig.iconReplacement;
        applyButton.setText(getString(applied ? R.string.NM_IconPicker_Applied : R.string.NM_IconPicker_Apply));
        applyButton.setEnabled(!applied && pagerScrollState == ViewPager.SCROLL_STATE_IDLE);
        applyButton.setContentDescription(applied
                ? LocaleController.formatString(R.string.NM_IconPicker_CurrentDescription, getString(TITLES[index]))
                : LocaleController.formatString(R.string.NM_IconPicker_ApplyDescription, getString(TITLES[index])));
        positionLabel.setText(LocaleController.formatString(R.string.NM_IconPicker_Position, index + 1, VALUES.length));
        positionLabel.setContentDescription(LocaleController.formatString(R.string.NM_IconPicker_PageDescription,
                getString(TITLES[index]), index + 1, VALUES.length));
        previousButton.setEnabled(index > 0);
        nextButton.setEnabled(index < VALUES.length - 1);
        previousButton.setAlpha(index > 0 ? 1f : .35f);
        nextButton.setAlpha(index < VALUES.length - 1 ? 1f : .35f);
        ((IconPackApplyButton) applyButton).setAppliedState(applied, animated && allowMotion());
    }

    private SmoothRippleDrawable ripple(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(dp(radius));
        return new SmoothRippleDrawable(ColorStateList.valueOf(getThemedColor(Theme.key_listSelector)), background, mask);
    }

    private void refreshColors() {
        if (root == null) return;
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        footer.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        int secondary = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
        positionLabel.setTextColor(secondary);
        for (ImageView arrow : new ImageView[]{previousButton, nextButton}) {
            arrow.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
            arrow.setBackground(ripple(getThemedColor(Theme.key_windowBackgroundWhite), 24));
        }
        for (IconPackPreviewCard card : cards) card.refreshTheme();
        indicator.invalidate();
        refreshSelection(false);
    }

    @Override public void onResume() { super.onResume(); refreshColors(); }
    @Override public boolean canBeginSlide() {
        boolean allowed = super.canBeginSlide();
        if (allowed) flushPendingIconPack();
        return allowed;
    }
    @Override public void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        if (!isOpen && backward) flushPendingIconPack();
        super.onTransitionAnimationStart(isOpen, backward);
    }
    @Override public void onPause() {
        finishPresentationAnimations();
        super.onPause();
    }
    @Override public boolean isSupportEdgeToEdge() { return true; }
    @Override public boolean drawEdgeNavigationBar() { return false; }
    @Override public void onInsets(int left, int top, int right, int bottom) {
        if (scrollView != null) scrollView.setPadding(left, 0, right, 0);
        if (footer != null) footer.setPadding(dp(24) + left, dp(12), dp(24) + right, dp(12) + bottom);
    }
    @Override public boolean isSwipeBackEnabled(MotionEvent event) {
        if (pager != null && event != null) {
            pager.getLocationOnScreen(pagerLocation);
            float x = event.getRawX() - pagerLocation[0], y = event.getRawY() - pagerLocation[1];
            if (x >= 0 && x < pager.getWidth() && y >= 0 && y < pager.getHeight()) return false;
        }
        return super.isSwipeBackEnabled(event);
    }
    @Override public void saveSelfArgs(Bundle args) {
        super.saveSelfArgs(args);
        args.putInt(PREVIEW_KEY, previewValue);
    }
    @Override public void restoreSelfArgs(Bundle args) {
        super.restoreSelfArgs(args);
        if (args != null && args.containsKey(PREVIEW_KEY)) previewValue = normalizedValue(args.getInt(PREVIEW_KEY));
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> result = new ArrayList<>();
        result.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        result.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        result.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        result.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            AndroidUtilities.cancelRunOnUIThread(refreshColorsRunnable);
            AndroidUtilities.runOnUIThread(refreshColorsRunnable);
        };
        for (int key : new int[]{Theme.key_windowBackgroundGray, Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteGrayText,
                Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlueText,
                Theme.key_windowBackgroundWhiteBlueIcon,
                Theme.key_featuredStickers_addButton, Theme.key_featuredStickers_buttonText,
                Theme.key_listSelector, Theme.key_divider, Theme.key_chat_messagePanelBackground,
                Theme.key_chat_messagePanelIcons, Theme.key_chat_messagePanelHint, Theme.key_chat_messagePanelSend,
                Theme.key_chat_outSentCheck, Theme.key_chat_inBubble, Theme.key_chat_outBubble,
                Theme.key_chat_messageTextIn, Theme.key_chat_messageTextOut}) {
            result.add(new ThemeDescription(null, 0, null, null, null, delegate, key));
        }
        return result;
    }

    private boolean nmApplyPending;
    private int nmApplyGeneration;
    private int nmPendingApplyGeneration;
    private int nmPendingSelection = NimarkoConfig.ICON_REPLACE_NONE;

    private final Runnable nmApplyPackRunnable = () -> {
        final int generation = nmPendingApplyGeneration;
        final int selection = nmPendingSelection;
        nmApplyPending = false;
        if (generation == nmApplyGeneration && selection == NimarkoConfig.iconReplacement
                && getParentActivity() instanceof LaunchActivity) {
            ((LaunchActivity) getParentActivity()).reloadResources();   // off-thread pack load + re-skin
        }
    };

    private void flushPendingIconPack() {
        if (!nmApplyPending) return;
        AndroidUtilities.cancelRunOnUIThread(nmApplyPackRunnable);
        nmApplyPackRunnable.run();
    }

    private void select(int value) {
        if (!isKnownValue(value)) return;
        if (NimarkoConfig.iconReplacement != value) {
            NimarkoConfig.setIconReplacement(value);
            refreshSelection();
            AndroidUtilities.cancelRunOnUIThread(nmApplyPackRunnable);
            nmPendingApplyGeneration = ++nmApplyGeneration;
            nmPendingSelection = value;
            nmApplyPending = true;
            AndroidUtilities.runOnUIThread(nmApplyPackRunnable, 500);
        }
    }

    private void releasePager() {
        AndroidUtilities.cancelRunOnUIThread(refreshColorsRunnable);
        finishPresentationAnimations();
        if (pager != null) {
            pager.clearOnPageChangeListeners();
            pager.setAdapter(null);
            pager = null;
        }
        cards.clear();
        pagerScrollState = ViewPager.SCROLL_STATE_IDLE;
    }
    @Override public void clearViews() {
        releasePager();
        root = null;
        footer = null;
        scrollView = null;
        positionLabel = applyButton = null;
        previousButton = nextButton = null;
        indicator = null;
        super.clearViews();
    }
    @Override public void onFragmentDestroy() {
        flushPendingIconPack();
        releasePager();
        root = null;
        footer = null;
        scrollView = null;
        positionLabel = applyButton = null;
        previousButton = nextButton = null;
        indicator = null;
        super.onFragmentDestroy();
    }

    private class PreviewPager extends ViewPager {
        PreviewPager(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int pageWidth = Math.max(1, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            int height = 0;
            for (IconPackPreviewCard card : cards) {
                card.measure(MeasureSpec.makeMeasureSpec(pageWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                height = Math.max(height, card.getMeasuredHeight());
            }
            int naturalHeight = height + getPaddingTop() + getPaddingBottom();
            int viewportHeight = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                    ? MeasureSpec.getSize(heightSpec) : 0;
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.max(naturalHeight, viewportHeight), MeasureSpec.EXACTLY));
        }
    }

    private class PageIndicator extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float position;
        PageIndicator(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setPosition(float value) { position = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float step = dp(20), start = (getWidth() - step * (VALUES.length - 1)) / 2f;
            for (int i = 0; i < VALUES.length; i++) {
                float active = Math.max(0f, 1f - Math.abs(position - i));
                paint.setColor(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlueText), .22f + .78f * active));
                float half = dp(3) + dp(3) * active, x = start + i * step, y = getHeight() / 2f;
                canvas.drawRoundRect(x - half, y - dp(3), x + half, y + dp(3), dp(3), dp(3), paint);
            }
        }
    }
}
