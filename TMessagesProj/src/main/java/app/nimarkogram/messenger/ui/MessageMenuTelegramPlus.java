 
package app.nimarkogram.messenger.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatScrimPopupContainerLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;
import java.util.List;

import app.nimarkogram.messenger.NimarkoConfig;

public final class MessageMenuTelegramPlus {

    private static final int MIN_WIDTH_DP = 204;
    private static final int MAX_STAGGERED_ITEMS = 10;

    private MessageMenuTelegramPlus() {
    }

    public static boolean isEnabled(boolean ordinaryTap) {
        return ordinaryTap && NimarkoConfig.telegramPlusMessageMenu;
    }

    public static void apply(
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout,
            ActionBarMenuSubItem[] rows,
            List<Integer> options,
            Theme.ResourcesProvider resourcesProvider
    ) {
        if (popupLayout == null || rows == null || options == null) {
            return;
        }
        try {
            int viewport = AndroidUtilities.displaySize != null
                    ? Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y)
                    : AndroidUtilities.dp(360);
            int minWidth = Math.min(AndroidUtilities.dp(MIN_WIDTH_DP),
                    Math.max(AndroidUtilities.dp(184), viewport - AndroidUtilities.dp(28)));
            popupLayout.setMinimumWidth(minWidth);

            int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
            int destructive = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
            float selectorAlpha = Theme.isCurrentThemeDark() ? 0.22f : 0.15f;

            ArrayList<RowEntry> attached = new ArrayList<>();
            int count = Math.min(rows.length, options.size());
            for (int i = 0; i < count; i++) {
                ActionBarMenuSubItem row = rows[i];
                Integer option = options.get(i);
                if (row == null || option == null || !(row.getParent() instanceof ViewGroup)) {
                    continue;
                }
                boolean destructiveAction = isDestructive(option);

                row.setItemHeight(44);
                row.setMinimumWidth(minWidth);
                row.setSelectorColor(Theme.multAlpha(
                        destructiveAction ? destructive : accent,
                        selectorAlpha));
                ScaleStateListAnimator.apply(row, 0.018f, 1.15f);

                View icon = row.getImageView();
                if (icon != null && icon.getVisibility() != View.GONE) {
                    ViewGroup.LayoutParams rawParams = icon.getLayoutParams();
                    if (rawParams instanceof FrameLayout.LayoutParams) {
                        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
                        
                        params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                        params.height = AndroidUtilities.dp(40);
                        params.gravity = Gravity.CENTER_VERTICAL
                                | (org.telegram.messenger.LocaleController.isRTL
                                ? Gravity.RIGHT : Gravity.LEFT);
                        icon.setLayoutParams(params);
                    }
                    icon.setPadding(0, 0, 0, 0);
                    icon.setBackground(null);
                }
                if (row.getVisibility() == View.VISIBLE) {
                    attached.add(new RowEntry(row, category(option)));
                }
            }

            insertSemanticDividers(attached, resourcesProvider);
            updateRoundedSelectors(attached);
            styleSwipeBackPages(popupLayout, minWidth, accent, selectorAlpha);
            popupLayout.setPopupOverlayDrawable(new LiquidGlassForeground(resourcesProvider));
            popupLayout.requestLayout();
        } catch (Throwable ignored) {
            
        }
    }

    public static boolean shouldAnimate() {
        return SharedConfig.animationsEnabled()
                && !AndroidUtilities.isAccessibilityTouchExplorationEnabled();
    }

    private static boolean useReducedEffects() {
        return SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW;
    }

    public static void prepareEntrance(
            ChatScrimPopupContainerLayout container,
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout,
            boolean anchorRight,
            boolean menuBelowAnchor,
            boolean animateContainer
    ) {
        if (container == null || !shouldAnimate()) {
            resetAnimatedState(container, popupLayout);
            return;
        }
        boolean reduced = useReducedEffects();
        int cardOffset = reduced ? 4 : 7;
        if (animateContainer) {
            container.animate().cancel();
            container.setPivotX(anchorRight ? container.getMeasuredWidth() : 0f);
            container.setPivotY(menuBelowAnchor ? 0f : container.getMeasuredHeight());
            container.setAlpha(0f);
            container.setScaleX(reduced ? 0.975f : 0.955f);
            container.setScaleY(reduced ? 0.975f : 0.955f);
            container.setTranslationX(AndroidUtilities.dp(anchorRight ? cardOffset : -cardOffset));
            container.setTranslationY(AndroidUtilities.dp(menuBelowAnchor ? -cardOffset : cardOffset));
        } else {
            container.setAlpha(1f);
            container.setScaleX(1f);
            container.setScaleY(1f);
            container.setTranslationX(0f);
            container.setTranslationY(0f);
        }

        for (View item : collectAnimatedItems(popupLayout)) {
            item.animate().cancel();
            item.setAlpha(0f);
            item.setTranslationX(AndroidUtilities.dp(anchorRight ? cardOffset : -cardOffset));
            item.setTranslationY(AndroidUtilities.dp(menuBelowAnchor ? -2 : 2));
            if (item instanceof ActionBarMenuSubItem) {
                View icon = ((ActionBarMenuSubItem) item).getImageView();
                if (icon != null && icon.getVisibility() != View.GONE) {
                    icon.animate().cancel();
                    icon.setScaleX(reduced ? 0.91f : 0.84f);
                    icon.setScaleY(reduced ? 0.91f : 0.84f);
                }
            }
        }
    }

    public static void startEntrance(
            ChatScrimPopupContainerLayout container,
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout,
            boolean animateContainer
    ) {
        if (container == null || !shouldAnimate()) {
            resetAnimatedState(container, popupLayout);
            return;
        }
        boolean reduced = useReducedEffects();
        if (animateContainer) {
            container.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(reduced ? 165 : 225)
                    .setInterpolator(reduced
                            ? CubicBezierInterpolator.EASE_OUT_QUINT
                            : CubicBezierInterpolator.EASE_OUT_BACK)
                    .start();
        }

        ArrayList<View> items = collectAnimatedItems(popupLayout);
        for (int i = 0; i < items.size(); i++) {
            View item = items.get(i);
            long delay = (animateContainer ? 24L : 38L)
                    + Math.min(i, MAX_STAGGERED_ITEMS) * (reduced ? 6L : 11L);
            item.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(reduced ? 145 : 175)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .start();
            if (item instanceof ActionBarMenuSubItem) {
                View icon = ((ActionBarMenuSubItem) item).getImageView();
                if (icon != null && icon.getVisibility() != View.GONE) {
                    icon.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setStartDelay(delay + (reduced ? 8L : 18L))
                            .setDuration(reduced ? 155 : 205)
                            .setInterpolator(reduced
                                    ? CubicBezierInterpolator.EASE_OUT_QUINT
                                    : CubicBezierInterpolator.EASE_OUT_BACK)
                            .start();
                }
            }
        }
        Drawable foreground = popupLayout != null
                ? popupLayout.getPopupOverlayDrawable() : null;
        if (foreground instanceof LiquidGlassForeground) {
            ((LiquidGlassForeground) foreground).startShimmer(reduced);
        }
    }

    private static void resetAnimatedState(
            ChatScrimPopupContainerLayout container,
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout
    ) {
        if (container != null) {
            container.animate().cancel();
            container.setAlpha(1f);
            container.setScaleX(1f);
            container.setScaleY(1f);
            container.setTranslationX(0f);
            container.setTranslationY(0f);
        }
        for (View item : collectAnimatedItems(popupLayout)) {
            item.animate().cancel();
            item.setAlpha(1f);
            item.setTranslationX(0f);
            item.setTranslationY(0f);
            if (item instanceof ActionBarMenuSubItem) {
                View icon = ((ActionBarMenuSubItem) item).getImageView();
                if (icon != null) {
                    icon.animate().cancel();
                    icon.setAlpha(1f);
                    icon.setScaleX(1f);
                    icon.setScaleY(1f);
                }
            }
        }
    }

    private static ArrayList<View> collectAnimatedItems(
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout
    ) {
        ArrayList<View> result = new ArrayList<>();
        if (popupLayout == null) {
            return result;
        }
        int count = popupLayout.getItemsCount();
        for (int i = 0; i < count; i++) {
            View item = popupLayout.getItemAt(i);
            if (item == null || item.getVisibility() != View.VISIBLE
                    || item instanceof PlusDivider) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    private static void insertSemanticDividers(
            ArrayList<RowEntry> rows,
            Theme.ResourcesProvider resourcesProvider
    ) {
        RowEntry previous = null;
        for (RowEntry current : rows) {
            if (previous != null
                    && current.category > previous.category
                    && current.row.getParent() == previous.row.getParent()
                    && current.row.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) current.row.getParent();
                int previousIndex = parent.indexOfChild(previous.row);
                int currentIndex = parent.indexOfChild(current.row);
                if (previousIndex >= 0 && currentIndex == previousIndex + 1) {
                    PlusDivider divider = new PlusDivider(parent.getContext(), resourcesProvider);
                    parent.addView(divider, currentIndex, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            AndroidUtilities.dp(6)));
                }
            }
            previous = current;
        }
    }

    private static void updateRoundedSelectors(ArrayList<RowEntry> rows) {
        for (RowEntry entry : rows) {
            ViewParentState state = getNeighbourState(entry.row);
            entry.row.updateSelectorBackground(state.top, state.bottom, 14);
        }
    }

    private static void styleSwipeBackPages(
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout,
            int minWidth,
            int accent,
            float selectorAlpha
    ) {
        PopupSwipeBackLayout swipeBack = popupLayout.getSwipeBack();
        if (swipeBack == null) {
            return;
        }
        
        swipeBack.setDrawForegroundBackground(false);
        swipeBack.setDrawBackgroundTransitionOverlay(false);
        for (int i = 1; i < swipeBack.getChildCount(); i++) {
            View page = swipeBack.getChildAt(i);
            if (page instanceof LinearLayout) {
                page.setBackground(null);
            }
            styleNestedRows(page, minWidth, accent, selectorAlpha);
        }
    }

    private static void styleNestedRows(
            View view,
            int minWidth,
            int accent,
            float selectorAlpha
    ) {
        if (view instanceof ActionBarPopupWindow.GapView) {
            
            view.setAlpha(0f);
            return;
        }
        if (view instanceof ActionBarMenuSubItem) {
            ActionBarMenuSubItem row = (ActionBarMenuSubItem) view;
            row.setItemHeight(48);
            row.setMinimumWidth(minWidth);
            row.setSelectorColor(Theme.multAlpha(accent, selectorAlpha));
            ScaleStateListAnimator.apply(row, 0.018f, 1.15f);
            View icon = row.getImageView();
            if (icon != null && icon.getVisibility() != View.GONE) {
                ViewGroup.LayoutParams rawParams = icon.getLayoutParams();
                if (rawParams instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    params.height = AndroidUtilities.dp(40);
                    params.gravity = Gravity.CENTER_VERTICAL
                            | (org.telegram.messenger.LocaleController.isRTL
                            ? Gravity.RIGHT : Gravity.LEFT);
                    icon.setLayoutParams(params);
                }
                icon.setPadding(0, 0, 0, 0);
                icon.setBackground(null);
            }
            ViewParentState state = getNeighbourState(row);
            row.updateSelectorBackground(state.top, state.bottom, 14);
            return;
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            styleNestedRows(group.getChildAt(i), minWidth, accent, selectorAlpha);
        }
    }

    private static ViewParentState getNeighbourState(ActionBarMenuSubItem row) {
        if (!(row.getParent() instanceof ViewGroup)) {
            return new ViewParentState(true, true);
        }
        ViewGroup parent = (ViewGroup) row.getParent();
        int index = parent.indexOfChild(row);
        View previous = findVisibleSibling(parent, index, -1);
        View next = findVisibleSibling(parent, index, 1);
        return new ViewParentState(
                !(previous instanceof ActionBarMenuSubItem),
                !(next instanceof ActionBarMenuSubItem));
    }

    private static View findVisibleSibling(ViewGroup parent, int index, int direction) {
        for (int i = index + direction; i >= 0 && i < parent.getChildCount(); i += direction) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                return child;
            }
        }
        return null;
    }

    private static int category(int option) {
        switch (option) {
            case ChatActivity.OPTION_FORWARD:
            case ChatActivity.OPTION_SHARE:
            case ChatActivity.OPTION_COPY_LINK:
            case ChatActivity.OPTION_SAVE_TO_GALLERY:
            case ChatActivity.OPTION_SAVE_TO_GALLERY2:
            case ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC:
                return 1;

            case ChatActivity.OPTION_PIN:
            case ChatActivity.OPTION_UNPIN:
            case ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD:
            case ChatActivity.OPTION_VIEW_IN_TOPIC:
            case ChatActivity.OPTION_STATISTICS:
            case ChatActivity.OPTION_VIEW_STATISTICS:
            case ChatActivity.OPTION_OPEN_PROFILE:
            case ChatActivity.OPTION_ADD_CONTACT:
            case ChatActivity.OPTION_CALL:
            case ChatActivity.OPTION_CALL_AGAIN:
                return 2;

            default:
                return isDestructive(option) ? 3 : 0;
        }
    }

    private static boolean isDestructive(int option) {
        switch (option) {
            case ChatActivity.OPTION_DELETE:
            case ChatActivity.OPTION_CANCEL_SENDING:
            case ChatActivity.OPTION_REPORT_CHAT:
            case ChatActivity.OPTION_REPORT_AD:
            case ChatActivity.OPTION_HIDE_SPONSORED_MESSAGE:
            case ChatActivity.OPTION_STOP_POLL_OR_QUIZ:
                return true;
            default:
                return false;
        }
    }

    private static final class RowEntry {
        final ActionBarMenuSubItem row;
        final int category;

        RowEntry(ActionBarMenuSubItem row, int category) {
            this.row = row;
            this.category = category;
        }
    }

    private static final class ViewParentState {
        final boolean top;
        final boolean bottom;

        ViewParentState(boolean top, boolean bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static final class PlusDivider extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int dividerColor;
        private final int accentColor;

        PlusDivider(android.content.Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            dividerColor = Theme.multAlpha(
                    Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator, resourcesProvider),
                    Theme.isCurrentThemeDark() ? 0.72f : 0.58f);
            accentColor = Theme.multAlpha(
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider),
                    Theme.isCurrentThemeDark() ? 0.24f : 0.16f);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float inset = AndroidUtilities.dp(14);
            paint.setShader(new LinearGradient(
                    inset, 0f, Math.max(inset + 1f, w - inset), 0f,
                    new int[]{0x00000000, dividerColor, accentColor, 0x00000000},
                    new float[]{0f, 0.26f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = AndroidUtilities.dp(14);
            float thickness = Math.max(1f, AndroidUtilities.density * 0.5f);
            float y = (getHeight() - thickness) / 2f;
            canvas.drawRoundRect(inset, y, getWidth() - inset, y + thickness,
                    thickness / 2f, thickness / 2f, paint);
        }
    }

    private static final class LiquidGlassForeground extends Drawable {
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF glassRect = new RectF();
        private final Path glassPath = new Path();
        private final Matrix shimmerMatrix = new Matrix();
        private final int accent;
        private final boolean dark;

        private Shader shimmerShader;
        private float shimmerProgress = -1f;
        private int drawableAlpha = 255;
        private ValueAnimator shimmerAnimator;

        LiquidGlassForeground(Theme.ResourcesProvider resourcesProvider) {
            accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
            dark = resourcesProvider != null
                    ? resourcesProvider.isDark()
                    : Theme.isCurrentThemeDark();
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(1f, AndroidUtilities.density * 0.72f));
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            float inset = AndroidUtilities.dp(8) + AndroidUtilities.density * 0.45f;
            glassRect.set(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset);
            float radius = AndroidUtilities.dp(15);
            glassPath.rewind();
            glassPath.addRoundRect(glassRect, radius, radius, Path.Direction.CW);

            int topEdge = dark ? 0x78FFFFFF : 0x90FFFFFF;
            int middleEdge = ColorUtils.setAlphaComponent(accent, dark ? 54 : 38);
            int bottomEdge = dark ? 0x24FFFFFF : 0x1C000000;
            strokePaint.setShader(new LinearGradient(
                    0f, glassRect.top, 0f, glassRect.bottom,
                    new int[]{topEdge, middleEdge, bottomEdge},
                    new float[]{0f, 0.54f, 1f}, Shader.TileMode.CLAMP));

            glowPaint.setShader(new RadialGradient(
                    glassRect.left + glassRect.width() * 0.20f,
                    glassRect.top + AndroidUtilities.dp(2),
                    Math.max(AndroidUtilities.dp(96), glassRect.width() * 0.78f),
                    new int[]{dark ? 0x25FFFFFF : 0x32FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));

            float band = AndroidUtilities.dp(34);
            shimmerShader = new LinearGradient(
                    -band, 0f, band, 0f,
                    new int[]{0x00FFFFFF, dark ? 0x32FFFFFF : 0x3DFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            shimmerPaint.setShader(shimmerShader);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (glassRect.isEmpty()) {
                return;
            }
            int save = canvas.save();
            canvas.clipPath(glassPath);
            glowPaint.setAlpha(drawableAlpha);
            canvas.drawRect(glassRect, glowPaint);
            if (shimmerProgress >= 0f && shimmerShader != null) {
                float band = AndroidUtilities.dp(34);
                float travel = glassRect.width() + band * 2f;
                shimmerMatrix.reset();
                shimmerMatrix.setRotate(-14f);
                shimmerMatrix.postTranslate(
                        glassRect.left - band + travel * shimmerProgress,
                        glassRect.centerY());
                shimmerShader.setLocalMatrix(shimmerMatrix);
                shimmerPaint.setAlpha(drawableAlpha);
                canvas.drawRect(glassRect, shimmerPaint);
            }
            canvas.restoreToCount(save);

            strokePaint.setAlpha(drawableAlpha);
            float radius = AndroidUtilities.dp(15);
            canvas.drawRoundRect(glassRect, radius, radius, strokePaint);
        }

        void startShimmer(boolean reduced) {
            if (shimmerAnimator != null) {
                shimmerAnimator.cancel();
                shimmerAnimator = null;
            }
            if (reduced) {
                shimmerProgress = -1f;
                invalidateSelf();
                return;
            }
            shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
            shimmerAnimator.setStartDelay(55L);
            shimmerAnimator.setDuration(470L);
            shimmerAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            shimmerAnimator.addUpdateListener(animation -> {
                shimmerProgress = (float) animation.getAnimatedValue();
                invalidateSelf();
            });
            shimmerAnimator.start();
        }

        @Override
        public void setAlpha(int alpha) {
            drawableAlpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            glowPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
            shimmerPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
