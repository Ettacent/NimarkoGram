/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AnimationNotificationsLocker;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;

import java.util.ArrayList;

public class SimpleActionBarMenuOverlay extends FrameLayout {
    private static final float MENU_RADIUS = 22;

    private float glassColorsFactor = 1f;
    private PopupSwipeBackLayout swipeBackListening;
    private final PopupSwipeBackLayout.OnSwipeBackProgressListener swipeBackProgressListener = (layout, toProgress, progress) -> {
        if (layout == swipeBackListening) invalidate();
    };
    private final BlurredBackgroundColorProvider glassColors = new BlurredBackgroundColorProvider() {
        @Nullable
        private BlurredBackgroundColorProvider base() {
            return bar != null ? bar.getMenuGlassColorProvider() : null;
        }

        @Override
        public int getShadowColor() {
            final BlurredBackgroundColorProvider base = base(), buttons = buttons();
            final int menu = base != null ? base.getShadowColor() : 0;
            return buttons != null ? blend(buttons.getShadowColor(), menu) : menu;
        }

        @Override
        public int getBackgroundColor() {
            final BlurredBackgroundColorProvider base = base();
            final int menuColor = base != null ? base.getBackgroundColor() : 0;
            if (glassColorsFactor >= 1f || bar == null) {
                return menuColor;
            }
            return ColorUtils.blendARGB(bar.getButtonsGlassBackgroundColor(), menuColor, glassColorsFactor);
        }

        @Override
        public int getStrokeColorTop() {
            final BlurredBackgroundColorProvider base = base();
            final int menu = base != null && bar.hasMenuGlassStroke() ? base.getStrokeColorTop() : 0;
            final BlurredBackgroundColorProvider buttons = buttons();
            return buttons != null ? blend(buttons.getStrokeColorTop(), menu) : menu;
        }

        @Override
        public int getStrokeColorBottom() {
            final BlurredBackgroundColorProvider base = base();
            final int menu = base != null && bar.hasMenuGlassStroke() ? base.getStrokeColorBottom() : 0;
            final BlurredBackgroundColorProvider buttons = buttons();
            return buttons != null ? blend(buttons.getStrokeColorBottom(), menu) : menu;
        }

        @Override
        public int getOutlineColor() {
            final BlurredBackgroundColorProvider base = base(), buttons = buttons();
            final int menu = base != null ? base.getOutlineColor() : 0;
            return buttons != null ? blend(buttons.getOutlineColor(), menu) : menu;
        }

        @Override
        public float getOutlineWidth() {
            final BlurredBackgroundColorProvider base = base(), buttons = buttons();
            final float menu = base != null ? base.getOutlineWidth() : 0;
            return buttons != null ? AndroidUtilities.lerp(buttons.getOutlineWidth(), menu, glassColorsFactor) : menu;
        }

        @Override
        public int getOutlineShadowColor() {
            final BlurredBackgroundColorProvider base = base(), buttons = buttons();
            final int menu = base != null ? base.getOutlineShadowColor() : 0;
            return buttons != null ? blend(buttons.getOutlineShadowColor(), menu) : menu;
        }

        @Override
        public float getShadowRadiusOverride() {
            final BlurredBackgroundColorProvider base = base(), buttons = buttons();
            final float menu = base != null ? base.getShadowRadiusOverride() : -1;
            final float from = buttons != null ? buttons.getShadowRadiusOverride() : menu;
            return from >= 0 && menu >= 0 ? AndroidUtilities.lerp(from, menu, glassColorsFactor) : glassColorsFactor < 0.5f ? from : menu;
        }

        @Override
        public float getShadowDyOverride() {
            final BlurredBackgroundColorProvider base = base(), buttons = buttons();
            final float menu = base != null ? base.getShadowDyOverride() : -1;
            final float from = buttons != null ? buttons.getShadowDyOverride() : menu;
            return from >= 0 && menu >= 0 ? AndroidUtilities.lerp(from, menu, glassColorsFactor) : glassColorsFactor < 0.5f ? from : menu;
        }

        @Nullable
        private BlurredBackgroundColorProvider buttons() {
            return glassColorsFactor < 1f && bar != null ? bar.getButtonsGlassColorProvider() : null;
        }

        private int blend(int from, int to) {
            return ColorUtils.blendARGB(from, to, glassColorsFactor);
        }
    };

    private MenuOverlayHost bar;
    private ActionBarMenuItem item;
    private ActionBarPopupWindow.ActionBarPopupWindowLayout content;
    private BlurredBackgroundDrawable glass;
    private MenuOverlayHost glassBar;
    private final java.util.WeakHashMap<MenuOverlayHost, BlurredBackgroundDrawable> glasses = new java.util.WeakHashMap<>();
    private boolean pillBelow;
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();

    private boolean opened;
    private boolean startFromPill;
    private boolean trackingOutside;
    private long openedAt;
    private Runnable onClosed;
    private int menuGeneration;
    private MenuSession menuSession;
    private Object backRegistration;
    private boolean hostRegistered;
    private MenuOverlayHost.SourceGuard sourceGuard;

    public static final class MenuSession implements View.OnAttachStateChangeListener {
        private SimpleActionBarMenuOverlay overlay;
        private View owner;
        private Runnable onClosed;
        private boolean closed;
        private boolean cancelled;
        private boolean ownerBound;
        private MenuOverlayHost.SourceGuard request;

        private MenuSession(SimpleActionBarMenuOverlay overlay, Runnable onClosed) {
            this.overlay = overlay;
            this.onClosed = onClosed;
        }

        public boolean isShown() {
            return !closed && overlay != null && overlay.menuSession == this && (overlay.opened || overlay.animating);
        }

        public void close(boolean animated) {
            if (closed) return;
            cancelled = true;
            if (overlay != null && overlay.menuSession == this) overlay.close(animated);
            else complete();
        }

        private boolean isCurrent() {
            return !closed && !cancelled && (request == null || request.isCurrent());
        }

        private void bindOwner(View owner) {
            this.owner = owner;
            ownerBound = true;
            owner.addOnAttachStateChangeListener(this);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            close(false);
        }

        private void complete() {
            if (closed) return;
            closed = true;
            overlay = null;
            if (owner != null && ownerBound) owner.removeOnAttachStateChangeListener(this);
            owner = null;
            ownerBound = false;
            request = null;
            final Runnable listener = onClosed;
            onClosed = null;
            if (listener != null) listener.run();
        }
    }

    static MenuSession rejectedItemOptionsMenu(Runnable onClosed) {
        final MenuSession session = new MenuSession(null, onClosed);
        session.complete();
        return session;
    }

    MenuSession openItemOptions(MenuOverlayHost host, ActionBarMenuItem item, ActionBarPopupWindow.ActionBarPopupWindowLayout content, Runnable onClosed) {
        return openItemOptions(host, item, content, onClosed, null);
    }

    MenuSession openItemOptions(MenuOverlayHost host, ActionBarMenuItem item, ActionBarPopupWindow.ActionBarPopupWindowLayout content, Runnable onClosed, @Nullable MenuOverlayHost.SourceGuard request) {
        final MenuSession session = new MenuSession(this, onClosed);
        session.request = request;
        if (host == null || item == null || content == null || content.getParent() != null) {
            session.complete();
            return session;
        }
        try {
            open(host, item, content, session);
            if (!session.isShown()) {
                if (menuSession == session) finish();
                else session.complete();
            }
        } catch (RuntimeException | Error e) {
            if (menuSession == session) finish();
            else session.complete();
            throw e;
        }
        return session;
    }

    MenuSession openCustomItemOptions(MenuOverlayHost host, View owner, ActionBarPopupWindow.ActionBarPopupWindowLayout content, Runnable onClosed) {
        return openCustomItemOptions(host, owner, content, onClosed, null);
    }

    MenuSession openCustomItemOptions(MenuOverlayHost host, View owner, ActionBarPopupWindow.ActionBarPopupWindowLayout content, Runnable onClosed, @Nullable MenuOverlayHost.SourceGuard request) {
        final MenuSession session = new MenuSession(this, onClosed);
        session.request = request;
        if (host == null || owner == null || content == null || content.getParent() != null
                || !owner.isAttachedToWindow() || !owner.isShown() || !owner.isEnabled()
                || !isAttachedToWindow() || getRootView() != owner.getRootView()
                || getWindowToken() == null || getWindowToken() != owner.getWindowToken()) {
            session.complete();
            return session;
        }
        session.owner = owner;
        try {
            open(host, null, content, session);
            if (!session.isShown()) {
                if (menuSession == session) finish();
                else session.complete();
            }
        } catch (RuntimeException | Error e) {
            if (menuSession == session) finish();
            else session.complete();
            throw e;
        }
        return session;
    }

    private static final class BackApi {
        final android.window.OnBackInvokedDispatcher dispatcher;
        final android.window.OnBackInvokedCallback callback;

        BackApi(android.window.OnBackInvokedDispatcher dispatcher, android.window.OnBackInvokedCallback callback) {
            this.dispatcher = dispatcher;
            this.callback = callback;
        }

        static Object register(View owner, Runnable action) {
            final android.window.OnBackInvokedDispatcher dispatcher = owner.findOnBackInvokedDispatcher();
            if (dispatcher == null) return null;
            final android.window.OnBackInvokedCallback callback = action::run;
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            return new BackApi(dispatcher, callback);
        }

        static void unregister(Object registration) {
            if (registration instanceof BackApi) {
                final BackApi value = (BackApi) registration;
                value.dispatcher.unregisterOnBackInvokedCallback(value.callback);
            }
        }
    }

    private long animStart;
    private float animFrom, menuW, menuH, wobble;
    private boolean animating;
    private AnimationNotificationsLocker notificationsLocker;

    private final RectF startRect = new RectF(), endRect = new RectF(), shapeRect = new RectF(), tmpRect = new RectF();
    private final RectF bulgedRect = new RectF();
    private final float[] shapeRadii = new float[8];
    private final Path clipPath = new Path();

    public SimpleActionBarMenuOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setVisibility(GONE);
        dimPaint.setColor(0xFF000000);
    }

    public boolean isOpened() {
        return opened;
    }

    public boolean isShown(ActionBarMenuItem item) {
        return this.item == item && (opened || animating);
    }

    public boolean isShown(MenuOverlayHost host) {
        return bar == host && item == null && (opened || animating);
    }

    boolean isItemOptionsMenu(ActionBarMenuItem owner) {
        return item == owner && menuSession != null;
    }

    @Nullable
    MenuSession getItemOptionsSession(ActionBarMenuItem owner) {
        return isItemOptionsMenu(owner) ? menuSession : null;
    }

    int getSelectionGeneration() { return menuGeneration; }

    @Nullable
    ActionBarPopupWindow.ActionBarPopupWindowLayout getItemOptionsContent(ActionBarMenuItem owner, MenuSession session) {
        if (!opened || item != owner || session == null || menuSession != session || !session.isCurrent()
                || sourceGuard == null || !sourceGuard.isCurrent() || content == null || content.getParent() != this) return null;
        final PopupSwipeBackLayout swipeBack = content.getSwipeBack();
        if (swipeBack != null && swipeBack.transitionProgress > .5f) {
            for (int i = 1; i < swipeBack.getChildCount(); i++) {
                final View child = swipeBack.getChildAt(i);
                if (child.getVisibility() == VISIBLE) {
                    return child instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout ? (ActionBarPopupWindow.ActionBarPopupWindowLayout) child : null;
                }
            }
            return null;
        }
        return content;
    }

    boolean isSelectionCurrent(ActionBarMenuItem owner, @Nullable MenuSession session, ActionBarPopupWindow.ActionBarPopupWindowLayout layout, int generation, View selected) {
        if (!opened || item != owner || menuGeneration != generation || menuSession != session || sourceGuard == null || !sourceGuard.isCurrent()
                || layout == null || selected.getVisibility() != VISIBLE || session != null && !selected.isEnabled()) return false;
        if (session != null ? getItemOptionsContent(owner, session) != layout : content != layout) return false;
        for (View v = selected; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == layout) return true;
        }
        return false;
    }

    public void open(MenuOverlayHost host, ActionBarPopupWindow.ActionBarPopupWindowLayout content) {
        open(host, null, content);
    }

    void open(MenuOverlayHost bar, @Nullable ActionBarMenuItem item, ActionBarPopupWindow.ActionBarPopupWindowLayout content) {
        open(bar, item, content, null);
    }

    void openGuarded(MenuOverlayHost bar, @Nullable ActionBarMenuItem item, ActionBarPopupWindow.ActionBarPopupWindowLayout content, MenuOverlayHost.SourceGuard request) {
        open(bar, item, content, null, request);
    }

    private void open(MenuOverlayHost bar, @Nullable ActionBarMenuItem item, ActionBarPopupWindow.ActionBarPopupWindowLayout content, @Nullable MenuSession session) {
        open(bar, item, content, session, null);
    }

    private void open(MenuOverlayHost bar, @Nullable ActionBarMenuItem item, ActionBarPopupWindow.ActionBarPopupWindowLayout content, @Nullable MenuSession session, @Nullable MenuOverlayHost.SourceGuard request) {
        if (bar == null || content == null || session != null && !session.isCurrent()) return;
        final View sourceOwner = item != null ? item : session != null ? session.owner : bar.getMenuHostView();
        if (sourceOwner == null || !isAttachedToWindow() || getRootView() != sourceOwner.getRootView()
                || getWindowToken() == null || getWindowToken() != sourceOwner.getWindowToken()) return;
        final MenuOverlayHost.SourceState source = new MenuOverlayHost.SourceState(bar, sourceOwner, null);
        final MenuOverlayHost.SourceGuard current = () -> source.isCurrent() && (request == null || request.isCurrent()) && (session == null || session.isCurrent());
        if (!current.isCurrent()) return;
        if (opened && this.bar == bar && this.item == item && this.content == content && menuSession == session) {
            return;
        }
        if (this.bar != null && (this.bar != bar || this.item != item || this.content != content || menuSession != session)) {
            final int generation = menuGeneration;
            finish();
            if (menuGeneration != generation + 1) return;
        }
        if (!current.isCurrent() || session != null && onClosed != null) return;
        final int generation = ++menuGeneration;
        menuSession = session;
        sourceGuard = current;
        this.bar = bar;
        this.item = item;
        pillBelow = bar.isMenuFromPillBelow();
        if (this.content != content) {
            if (this.content != null) {
                AndroidUtilities.removeFromParent(this.content);
                if (menuGeneration != generation) return;
            }
            this.content = content;
        }
        if (session != null && !session.ownerBound) session.bindOwner(sourceOwner);
        if (!validateOpen(generation, current)) return;
        if (!prepareContent(content, generation)) return;
        if (!validateOpen(generation, current)) return;
        if (content.getParent() != this) {
            AndroidUtilities.removeFromParent(content);
            if (menuGeneration != generation) return;
            addView(content, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            if (!validateOpen(generation, current)) return;
        }
        if (glass == null || glassBar != bar) {
            if (glass != null) {
                glass.clearReservedBounds();
                glass.setBounds(0, 0, 0, 0);
            }
            BlurredBackgroundDrawable nextGlass = glasses.get(bar);
            if (nextGlass == null) {
                nextGlass = bar.createMenuGlass(this);
                if (!validateOpen(generation, current)) return;
                if (nextGlass != null) {
                    nextGlass.setColorProvider(glassColors);
                    glasses.put(bar, nextGlass);
                }
            }
            glass = nextGlass;
            glassBar = bar;
        }
        final ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).bringChildToFront(this);
        }
        if (!validateOpen(generation, current)) return;
        setVisibility(VISIBLE);
        opened = true;
        openedAt = SystemClock.uptimeMillis();
        startAnimation();
        if (!validateOpen(generation, current)) return;
        hostRegistered = true;
        bar.setMenuOverlay(this, item);
        if (!validateOpen(generation, current)) return;
        computeStartRect();
        if (!validateOpen(generation, current)) return;
        if (!startFromPill && item != null) {
            item.getIconView().setAlpha(0f);
        }
        final View focused = getRootView() != null ? getRootView().findFocus() : null;
        if (focused == null || (!(focused instanceof EditText) && !focused.onCheckIsTextEditor()) || !focused.isShown()) {
            setFocusableInTouchMode(true);
            requestFocus();
        }
        if (Build.VERSION.SDK_INT >= 33 && menuGeneration == generation) {
            BackApi.unregister(backRegistration);
            backRegistration = BackApi.register(this, () -> {
                if (menuGeneration == generation && (opened || animating)) close(true);
            });
        }
    }

    private boolean validateOpen(int generation, MenuOverlayHost.SourceGuard current) {
        if (menuGeneration != generation) return false;
        if (current.isCurrent()) return true;
        finish();
        return false;
    }

    public void close(boolean animated) {
        if (!opened && !animating) {
            if (bar != null || content != null || menuSession != null) finish();
            return;
        }
        if (opened) {
            ++menuGeneration;
            opened = false;
            if (animated && isAttachedToWindow() && isLaidOut()) {
                startAnimation();
                return;
            }
        }
        finish();
    }

    public void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    private void finish() {
        final int generation = ++menuGeneration;
        final ActionBarMenuItem wasItem = item;
        final MenuOverlayHost wasBar = bar;
        final ActionBarPopupWindow.ActionBarPopupWindowLayout wasContent = content;
        final BlurredBackgroundDrawable wasGlass = glass;
        final PopupSwipeBackLayout wasSwipeBack = swipeBackListening;
        final Runnable wasOnClosed = onClosed;
        final MenuSession wasSession = menuSession;
        final boolean wasHostRegistered = hostRegistered;
        final Object wasBackRegistration = backRegistration;
        final AnimationNotificationsLocker locker = notificationsLocker;
        final ArrayList<PopupState> states = new ArrayList<>(popupStates);
        popupStates.clear();
        item = null;
        bar = null;
        content = null;
        glass = null;
        glassBar = null;
        swipeBackListening = null;
        onClosed = null;
        menuSession = null;
        hostRegistered = false;
        sourceGuard = null;
        backRegistration = null;
        notificationsLocker = null;
        animating = false;
        opened = false;
        if (wasItem != null) wasItem.clearOverlaySelection(this);
        if (Build.VERSION.SDK_INT >= 33) BackApi.unregister(wasBackRegistration);
        trackingOutside = false;
        menuW = menuH = wobble = 0;
        clearFocus();
        setVisibility(GONE);
        if (wasGlass != null) {
            wasGlass.clearReservedBounds();
            wasGlass.setBounds(0, 0, 0, 0);
        }
        if (wasSwipeBack != null) {
            wasSwipeBack.removeOnSwipeBackProgressListener(swipeBackProgressListener);
        }
        restorePopupLook(states, wasContent);
        if (wasItem != null) {
            wasItem.getIconView().setAlpha(1f);
        }
        if (wasBar != null && wasHostRegistered) {
            wasBar.setMenuOverlay(null, null);
        }
        if (wasContent != null && wasContent.getParent() == this) {
            removeView(wasContent);
        }
        if (menuGeneration == generation && wasContent != null && wasContent.getParent() == null && wasContent.getSwipeBack() != null) {
            wasContent.getSwipeBack().closeForeground(false);
        }
        try {
            if (wasItem != null && wasSession == null) wasItem.onOverlayMenuClosed();
        } finally {
            try {
                try {
                    if (wasSession != null) wasSession.complete();
                } finally {
                    if (wasOnClosed != null) wasOnClosed.run();
                }
            } finally {
                if (locker != null) locker.unlock();
            }
        }
    }

    private final ArrayList<PopupState> popupStates = new ArrayList<>();

    private static final class PopupState {
        final ActionBarPopupWindow.ActionBarPopupWindowLayout layout;
        final Drawable background;
        final Drawable backgroundDrawable;
        final int backgroundColor;
        final int paddingLeft, paddingTop, paddingRight, paddingBottom;

        PopupState(ActionBarPopupWindow.ActionBarPopupWindowLayout layout) {
            this.layout = layout;
            background = layout.getBackground();
            backgroundDrawable = layout.getBackgroundDrawable();
            backgroundColor = layout.getBackgroundColor();
            paddingLeft = layout.getPaddingLeft();
            paddingTop = layout.getPaddingTop();
            paddingRight = layout.getPaddingRight();
            paddingBottom = layout.getPaddingBottom();
        }

        void restore() {
            layout.setBackground(background);
            layout.setBackgroundDrawable(backgroundDrawable);
            if (backgroundDrawable != null) {
                layout.setBackgroundColor(backgroundColor);
            }
            layout.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
        }
    }

    private void takeOffPopupLook(ActionBarPopupWindow.ActionBarPopupWindowLayout layout, int paddingVertical) {
        boolean saved = false;
        for (int i = 0; i < popupStates.size(); i++) {
            if (popupStates.get(i).layout == layout) {
                saved = true;
                break;
            }
        }
        if (!saved) {
            popupStates.add(new PopupState(layout));
        }
        layout.setBackground(null);
        layout.setBackgroundDrawable(null);
        layout.setPadding(0, paddingVertical, 0, paddingVertical);
    }

    private void restorePopupLook(ArrayList<PopupState> states, ActionBarPopupWindow.ActionBarPopupWindowLayout content) {
        for (int i = 0; i < states.size(); i++) {
            states.get(i).restore();
        }
        if (content != null && content.getSwipeBack() != null) {
            content.getSwipeBack().setForegroundOnGlass(false);
        }
    }

    private boolean prepareContent(ActionBarPopupWindow.ActionBarPopupWindowLayout content, int generation) {
        final MenuOverlayHost.SourceGuard current = sourceGuard;
        final PopupSwipeBackLayout swipeBack = content.getSwipeBack();
        if (swipeBack != null) {
            swipeBack.closeForeground(false);
            if (menuGeneration != generation || this.content != content) return false;
            if (!validateOpen(generation, current)) return false;
        }
        takeOffPopupLook(content, dp(5));
        content.setTranslationX(0);
        content.setTranslationY(0);
        content.setAlpha(1f);
        ThemeBind.updateTree(content, false);
        if (!validateOpen(generation, current)) return false;
        ItemOptions.setGapBackgroundColor(content, Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, bar.getMenuResourcesProvider()), 0.06f));
        content.updateRadialSelectors();
        if (!validateOpen(generation, current)) return false;
        if (swipeBack != null) {
            swipeBack.setForegroundOnGlass(true);
            if (swipeBackListening != swipeBack) {
                if (swipeBackListening != null) {
                    swipeBackListening.removeOnSwipeBackProgressListener(swipeBackProgressListener);
                }
                swipeBackListening = swipeBack;
                swipeBack.addOnSwipeBackProgressListener(swipeBackProgressListener);
            }
            for (int i = 1; i < swipeBack.getChildCount(); i++) {
                if (swipeBack.getChildAt(i) instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
                    final ActionBarPopupWindow.ActionBarPopupWindowLayout page = (ActionBarPopupWindow.ActionBarPopupWindowLayout) swipeBack.getChildAt(i);
                    takeOffPopupLook(page, 0);
                    ThemeBind.updateTree(page, false);
                    if (!validateOpen(generation, current)) return false;
                    ItemOptions.setGapBackgroundColor(page, Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, bar.getMenuResourcesProvider()), 0.06f));
                }
            }
        }
        content.scrollToTop();
        if (!validateOpen(generation, current)) return false;
        return menuGeneration == generation && this.content == content;
    }

    private void computeContentRect(RectF rect) {
        final float w = content.getMeasuredWidth() * content.getBackScaleX();
        final float h = content.getMeasuredHeight() * content.getBackScaleY();
        final PopupSwipeBackLayout swipeBack = content.getSwipeBack();
        if (swipeBack != null && (swipeBack.stickToRight || content.swipeBackGravityRight)) {
            rect.set(content.getRight() - w, content.getTop(), content.getRight(), content.getTop() + h);
        } else {
            rect.set(content.getLeft(), content.getTop(), content.getLeft() + w, content.getTop() + h);
        }
    }

    private static double springStep(double t, double z, double period) {
        if (t <= 0) return 0;
        final double w0 = 2 * Math.PI / period, wd = w0 * Math.sqrt(1 - z * z);
        return 1 - Math.exp(-z * w0 * t) * (Math.cos(wd * t) + z / Math.sqrt(1 - z * z) * Math.sin(wd * t));
    }

    private void startAnimation() {
        animFrom = Math.max(menuW, menuH);
        lastMenuH = menuH;
        lastStepTime = 0;
        animStart = 0;
        setAnimating(true);
        invalidate();
    }

    private void setAnimating(boolean animating) {
        if (this.animating == animating) {
            return;
        }
        this.animating = animating;
        if (animating) {
            notificationsLocker = new AnimationNotificationsLocker();
            notificationsLocker.lock();
        } else {
            final AnimationNotificationsLocker locker = notificationsLocker;
            notificationsLocker = null;
            if (locker != null) locker.unlock();
        }
    }

    private float getAnimatorDurationScale() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ValueAnimator.getDurationScale();
        }
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Exception e) {
            return 1f;
        }
    }

    private float animationTime() {
        final long now = AnimationUtils.currentAnimationTimeMillis();
        if (animStart == 0) {
            animStart = now;
        }
        final float scale = getAnimatorDurationScale();
        return scale <= 0 ? 10f : (now - animStart) / 1000f / scale;
    }

    private void stepAnimation() {
        if (!animating) {
            return;
        }
        final int generation = menuGeneration;
        final float t = animationTime();
        final float f = animFrom;
        if (opened) {
            wobble = 0;
            menuW = t > 0.7f ? 1f : f + (1f - f) * (float) springStep(t, 0.78, 0.30);
            menuH = t > 0.7f ? 1f : f + (1f - f) * (float) springStep(t - 0.02, 0.80, 0.34);
            if (t > 0.7f) {
                setAnimating(false);
                if (menuGeneration != generation) return;
            }
        } else {
            final float u = Utilities.clamp01(t / 0.2f), uw = Utilities.clamp01(t / 0.17f);
            menuW = f * (1f - uw * uw * (3f - 2f * uw));
            menuH = f * (1f - u * u * (3f - 2f * u));
            final float tw = t - 0.2f;
            wobble = tw > 0 && startFromPill ? 0.07f * f * (float) Math.exp(-tw / 0.11f) * (float) Math.sin(2 * Math.PI * tw / 0.17f) : 0f;
            if (startFromPill ? tw > 0.45f : tw >= 0) {
                wobble = 0;
                post(() -> {
                    if (menuGeneration == generation && !opened && animating) {
                        finish();
                    }
                });
            }
        }
        final float dt = lastStepTime == 0 ? 0 : t - lastStepTime;
        if (dt > 0.001f) {
            menuHVel = (menuH - lastMenuH) / dt;
        } else if (lastStepTime == 0) {
            menuHVel = 0;
        }
        lastMenuH = menuH;
        lastStepTime = Math.max(t, 0.0001f);
    }

    private float lastMenuH, menuHVel, lastStepTime;

    private static float smooth(float from, float to, float x) {
        final float u = Utilities.clamp01((x - from) / (to - from));
        return u * u * (3f - 2f * u);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec), height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        if (content != null && content.getParent() == this) {
            computeStartRect();
            final int maxHeight = pillBelow
                ? (int) (startRect.bottom - AndroidUtilities.statusBarHeight - dp(16))
                : height - getContentTop() - dp(16) - AndroidUtilities.navigationBarHeight;
            content.measure(MeasureSpec.makeMeasureSpec(width - dp(16), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(Math.max(dp(48), maxHeight), MeasureSpec.AT_MOST));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (content != null && content.getParent() == this) {
            computeStartRect();
            final int w = content.getMeasuredWidth(), h = content.getMeasuredHeight();
            if (pillBelow) {
                final int l = (int) Math.max(dp(8), Math.min(getWidth() - dp(8) - w, startRect.centerX() - w / 2f));
                final int b = (int) startRect.bottom;
                content.layout(l, b - h, l + w, b);
            } else {
                final int r = (int) startRect.right, t = getContentTop();
                final int l = Math.max(dp(8), r - w);
                content.layout(l, t, l + w, t + h);
            }
        }
    }

    private int getContentTop() {
        final int top = (int) startRect.top;
        return Math.max(Math.min(top, 0), top - bar.getMenuRise());
    }

    private void computeStartRect() {
        if (bar == null) {
            return;
        }
        startFromPill = bar.getMenuItemGlassRect(item, startRect);
        computeOffset(bar.getMenuHostView());
        startRect.offset(offset[0], offset[1]);
    }

    private final float[] offset = new float[2];
    private final RectF offsetRect = new RectF();

    private void computeOffset(View view) {
        if (ViewPositionWatcher.computeRectInParent(view, this, offsetRect)) {
            offset[0] = offsetRect.left;
            offset[1] = offsetRect.top;
        } else {
            offset[0] = offset[1] = 0;
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (bar == null || content == null) {
            return;
        }
        final int generation = menuGeneration;
        stepAnimation();
        if (menuGeneration != generation || bar == null || content == null) return;
        computeStartRect();
        computeContentRect(endRect);

        final float pw = menuW, ph = menuH, m = Utilities.clamp01(Math.max(pw, ph));
        shapeRect.set(
            lerp(startRect.left, endRect.left, pw),
            lerp(startRect.top, endRect.top, ph),
            lerp(startRect.right, endRect.right, pw),
            lerp(startRect.bottom, endRect.bottom, ph)
        );
        if (wobble != 0) {
            final float cx = shapeRect.centerX(), cy = shapeRect.centerY();
            final float hw = shapeRect.width() / 2f * (1f + wobble * 0.5f), hh = shapeRect.height() / 2f * (1f - wobble);
            shapeRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
        }
        final float radius = Math.min(Math.min(shapeRect.height(), shapeRect.width()) / 2f, lerp(startRect.height() / 2f, dp(MENU_RADIUS), Utilities.clamp01(ph)));
        final float bulge = pillBelow ? 0.75f * dp(5) * Utilities.clamp01(menuHVel / 9f) : 0f;
        final float topRadius = radius + bulge;
        shapeRadii[0] = shapeRadii[1] = shapeRadii[2] = shapeRadii[3] = topRadius;
        shapeRadii[4] = shapeRadii[5] = shapeRadii[6] = shapeRadii[7] = radius;
        bulgedRect.set(shapeRect.left, shapeRect.top - bulge, shapeRect.right, shapeRect.bottom);

        dimPaint.setAlpha((int) (0x1A * m));
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);

        final int p = dp(6);
        final float glassAlpha = startFromPill ? 1f : Utilities.clamp01(m / 0.25f);
        glassColorsFactor = startFromPill || bar.interpolateMenuColors() ? Utilities.clamp01(m) : 1f;
        if (glass != null) {
            bar.setMenuGlassFrostedProgress(glassColorsFactor);
            glass.updateColors();
            glass.setStrokeWidth(
                lerp(strokeWidth(bar.getButtonsGlassColorProvider(), true), strokeWidth(glassColors, true), glassColorsFactor),
                lerp(strokeWidth(bar.getButtonsGlassColorProvider(), false), strokeWidth(glassColors, false), glassColorsFactor));
            if (bulge > 0) {
                glass.setRadius(topRadius, topRadius, radius, radius, false);
            } else {
                glass.setRadius(radius);
            }
            glass.setBounds(Math.round(bulgedRect.left) - p, Math.round(bulgedRect.top) - p, Math.round(bulgedRect.right) + p, Math.round(bulgedRect.bottom) + p);
            reserveGlass();
            glass.setAlpha((int) (255 * glassAlpha));
            glass.draw(canvas);
        } else {
            fallbackPaint.setColor(HeaderMenuOverlay.fallbackBackground(bar, glassColorsFactor));
            fallbackPaint.setAlpha((int) (fallbackPaint.getAlpha() * glassAlpha));
            clipPath.rewind();
            clipPath.addRoundRect(bulgedRect, shapeRadii, Path.Direction.CW);
            canvas.drawPath(clipPath, fallbackPaint);
        }

        final float ia = 1f - Utilities.clamp01(m / (opened ? 0.3f : 0.7f));
        if (ia > 0.004f && bar.hasUnclippedMenuIconSource() && bar.getMenuIconSourceBounds(tmpRect)) {
            computeOffset(bar.getMenuHostView());
            tmpRect.offset(offset[0], offset[1]);
            final float s = 1f - 0.15f * Utilities.clamp01(m / 0.3f);
            saveFaded(canvas, tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom, ia);
            canvas.scale(s * (1f + wobble * 0.5f), s * (1f - wobble), tmpRect.centerX(), tmpRect.centerY());
            canvas.translate(offset[0], offset[1]);
            bar.drawUnclippedMenuIconSource(canvas);
            canvas.restore();
        }

        canvas.save();
        clipPath.rewind();
        clipPath.addRoundRect(bulgedRect, shapeRadii, Path.Direction.CW);
        canvas.clipPath(clipPath);
        final View icon = startFromPill || item == null ? bar.getMenuView() : item.getIconView();
        if (ia > 0.004f && bar.getMenuIconSourceBounds(tmpRect)) {
            computeOffset(bar.getMenuHostView());
            tmpRect.offset(offset[0], offset[1]);
            final float s = 1f - 0.15f * Utilities.clamp01(m / 0.3f);
            saveFaded(canvas, tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom, ia);
            canvas.scale(s * (1f + wobble * 0.5f), s * (1f - wobble), tmpRect.centerX(), tmpRect.centerY());
            canvas.translate(offset[0], offset[1]);
            bar.drawMenuIconSource(canvas);
            canvas.restore();
        } else if (ia > 0.004f && icon != null) {
            computeViewRect(icon, tmpRect);
            final float s = 1f - 0.15f * Utilities.clamp01(m / 0.3f);
            saveFaded(canvas, tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom, ia);
            canvas.scale(s * (1f + wobble * 0.5f), s * (1f - wobble), tmpRect.centerX(), tmpRect.centerY());
            canvas.translate(tmpRect.left, tmpRect.top);
            icon.draw(canvas);
            canvas.restore();
        }

        final float ca = pillBelow
            ? (opened ? smooth(0.15f, 0.6f, m) : smooth(0.6f, 1f, m))
            : (opened ? smooth(0.25f, 0.75f, m) : smooth(0.5f, 0.95f, m));
        if (ca > 0.004f && pillBelow) {
            float tx = shapeRect.centerX() - endRect.centerX(), ty = shapeRect.top - endRect.top;
            if (Math.abs(menuHVel) < 0.6f) {
                tx = Math.round(tx);
                ty = Math.round(ty);
            }
            saveFaded(canvas, bulgedRect.left, bulgedRect.top, bulgedRect.right, bulgedRect.bottom, ca);
            canvas.translate(tx, ty);
            super.dispatchDraw(canvas);
            canvas.restore();
        } else if (ca > 0.004f) {
            final float cs = Math.max(0.3f, shapeRect.width() / Math.max(1f, endRect.width()));
            saveFaded(canvas, shapeRect.left, shapeRect.top, shapeRect.right, shapeRect.bottom, ca);
            canvas.translate(shapeRect.right - endRect.right, shapeRect.top - endRect.top);
            canvas.scale(cs, cs, endRect.right, endRect.top);
            super.dispatchDraw(canvas);
            canvas.restore();
        }
        canvas.restore();

        if (animating) {
            invalidate();
        }
    }

    private static void saveFaded(Canvas canvas, float left, float top, float right, float bottom, float alpha) {
        if (alpha >= 1f) {
            canvas.save();
        } else {
            canvas.saveLayerAlpha(left, top, right, bottom, (int) (255 * alpha));
        }
    }

    private float strokeWidth(@Nullable BlurredBackgroundColorProvider colors, boolean top) {
        if (colors == glassColors) {
            colors = bar != null ? bar.getMenuGlassColorProvider() : null;
        }
        if (colors instanceof BlurredBackgroundProvider) {
            final BlurredBackgroundProvider provider = (BlurredBackgroundProvider) colors;
            return top ? provider.getStrokeWidthTop() : provider.getStrokeWidthBottom();
        }
        return top ? AndroidUtilities.dpf2(1) : AndroidUtilities.dpf2(2 / 3f);
    }

    private void reserveGlass() {
        tmpRect.set(content.getLeft(), content.getTop(), content.getRight(), content.getBottom());
        tmpRect.union(startRect);
        final float over = dp(4) + 0.04f * Math.max(tmpRect.width(), tmpRect.height());
        glass.setReservedBounds(
            (int) Math.floor(tmpRect.left - over), (int) Math.floor(tmpRect.top - over),
            (int) Math.ceil(tmpRect.right + over), (int) Math.ceil(tmpRect.bottom + over));
    }

    private void computeViewRect(View view, RectF out) {
        computeOffset(view);
        out.set(offset[0], offset[1], offset[0] + view.getWidth(), offset[1] + view.getHeight());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!opened) {
            return false;
        }
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            final ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
            final boolean repeatedTap = SystemClock.uptimeMillis() - openedAt < 300 && startRect.contains(ev.getX(), ev.getY());
            trackingOutside = repeatedTap || !endRect.contains(ev.getX(), ev.getY());
            if (trackingOutside) {
                close(true);
                return true;
            }
        }
        if (trackingOutside) {
            return true;
        }
        super.dispatchTouchEvent(ev);
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (opened && event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0
                && (event.getKeyCode() == KeyEvent.KEYCODE_MENU || event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
            close(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (opened || animating || bar != null || content != null) {
            finish();
        }
        super.onDetachedFromWindow();
    }

    @Nullable
    public ActionBarMenuItem getItem() {
        return item;
    }
}
