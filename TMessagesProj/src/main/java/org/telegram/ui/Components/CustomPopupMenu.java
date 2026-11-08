/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import android.widget.FrameLayout;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.MenuOverlayHost;
import org.telegram.ui.ActionBar.SimpleActionBarMenuOverlay;
import org.telegram.ui.ActionBar.Theme;

import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
public abstract class CustomPopupMenu {

    ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout;
    ActionBarPopupWindow popupWindow;
    boolean isShowing;

    private final Theme.ResourcesProvider resourcesProvider;
    private int showGeneration;
    private OverlayPresentation overlayPresentation;
    private static final WeakHashMap<View, WeakReference<OverlayPresentation>> overlayOwners = new WeakHashMap<>();
    private static final BlurredBackgroundColorProvider transparentButtonColors = new BlurredBackgroundColorProvider() {
        @Override
        public int getShadowColor() { return 0; }

        @Override
        public int getBackgroundColor() { return 0; }

        @Override
        public int getStrokeColorTop() { return 0; }

        @Override
        public int getStrokeColorBottom() { return 0; }
    };
    public CustomPopupMenu(Context context, Theme.ResourcesProvider resourcesProvider, boolean containsSwipeBack) {
        this.resourcesProvider = resourcesProvider;
        popupLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, R.drawable.popup_fixed_alert2, resourcesProvider, containsSwipeBack ? ActionBarPopupWindow.ActionBarPopupWindowLayout.FLAG_USE_SWIPEBACK : 0);
        popupLayout.setAnimationEnabled(false);
        popupLayout.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (popupWindow != null && popupWindow.isShowing()) {
                    v.getHitRect(AndroidUtilities.rectTmp2);
                    if (!AndroidUtilities.rectTmp2.contains((int) event.getX(), (int) event.getY())) {
                        popupWindow.dismiss();
                    }
                }
            }
            return false;
        });
        popupLayout.setDispatchKeyEventListener(keyEvent -> {
            if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_BACK && keyEvent.getRepeatCount() == 0 && popupWindow != null && popupWindow.isShowing()) {
                popupWindow.dismiss();
            }
        });
        popupLayout.setShownFromBottom(false);

        onCreate(popupLayout);

        popupWindow = new ActionBarPopupWindow(popupLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        popupWindow.setAnimationEnabled(false);
        popupWindow.setAnimationStyle(R.style.PopupContextAnimation2);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setClippingEnabled(true);
        popupWindow.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        popupWindow.getContentView().setFocusableInTouchMode(true);
        if (AndroidUtilities.isAccessibilityTouchExplorationEnabled()) {
            popupWindow.setFocusable(true);
        }
    }

    public void show(View anchorView, int x, int y) {
        if (isShowing) return;
        final int generation = ++showGeneration;
        isShowing = true;
        popupWindow.setOnDismissListener(() -> finishShowing(generation));
        popupWindow.showAsDropDown(anchorView, x, y);
    }

    public boolean showWithMenuOverlay(View anchor, FrameLayout root, BlurredBackgroundDrawableViewFactory factory, int x, int y) {
        if (isShowing) return true;
        if (anchor == null || popupWindow.isShowing() || popupLayout.getParent() != null || getOverlayOwner(anchor) != null) {
            final int generation = ++showGeneration;
            isShowing = true;
            finishShowing(generation);
            return false;
        }
        if (factory == null || !canUseMenuOverlay(anchor, root)) {
            show(anchor, x, y);
            if (!popupWindow.isShowing()) finishShowing(showGeneration);
            return isShowing;
        }
        final int generation = ++showGeneration;
        final OverlayPresentation presentation = new OverlayPresentation(anchor, root, factory, generation);
        overlayPresentation = presentation;
        isShowing = true;
        overlayOwners.put(anchor, new WeakReference<>(presentation));
        presentation.overlay.setOnClosed(presentation::complete);
        anchor.addOnAttachStateChangeListener(presentation);
        root.addOnAttachStateChangeListener(presentation);
        try {
            root.addView(presentation.overlay, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            if (!presentation.isCurrent()) return false;
            presentation.overlay.open(presentation, popupLayout);
            if (!presentation.overlay.isShown(presentation)) presentation.complete();
        } catch (RuntimeException error) {
            FileLog.e(error);
            presentation.close(false);
        }
        return presentation.isCurrent();
    }

    private static boolean canUseMenuOverlay(View anchor, FrameLayout root) {
        if (anchor == null || root == null || !anchor.isAttachedToWindow() || !root.isAttachedToWindow()
                || !anchor.isShown() || anchor.getWidth() <= 0 || anchor.getHeight() <= 0
                || anchor.getWindowToken() == null || anchor.getWindowToken() != root.getWindowToken()) return false;
        for (android.view.ViewParent parent = anchor.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == root) return true;
        }
        return false;
    }

    private static OverlayPresentation getOverlayOwner(View anchor) {
        final WeakReference<OverlayPresentation> reference = overlayOwners.get(anchor);
        final OverlayPresentation owner = reference != null ? reference.get() : null;
        return owner != null && !owner.closed ? owner : null;
    }

    private void finishShowing(int generation) {
        if (showGeneration != generation || !isShowing) return;
        isShowing = false;
            onDismissed();
    }

    private final class OverlayPresentation implements MenuOverlayHost, View.OnAttachStateChangeListener {
        private final View anchor;
        private final FrameLayout root;
        private final BlurredBackgroundDrawableViewFactory factory;
        private final BlurredBackgroundColorProvider colors;
        private final SimpleActionBarMenuOverlay overlay;
        private final int generation;
        private final float anchorAlpha;
        private SimpleActionBarMenuOverlay registeredOverlay;
        private boolean closing;
        private boolean closed;

        private OverlayPresentation(View anchor, FrameLayout root, BlurredBackgroundDrawableViewFactory factory, int generation) {
            this.anchor = anchor;
            this.root = root;
            this.factory = factory;
            this.generation = generation;
            anchorAlpha = anchor.getAlpha();
            colors = new BlurredBackgroundColorProviderThemed(resourcesProvider, Theme.key_actionBarDefaultSubmenuBackground);
            overlay = new SimpleActionBarMenuOverlay(anchor.getContext());
        }

        private boolean isCurrent() {
            return !closed && overlayPresentation == this && showGeneration == generation && getOverlayOwner(anchor) == this;
        }

        private void close(boolean animated) {
            if (closed) return;
            if (animated && (closing || !overlay.isOpened() && overlay.isShown(this))) {
                closing = true;
                return;
            }
            closing = true;
            try {
                overlay.close(animated);
            } finally {
                if (!overlay.isShown(this)) complete();
            }
        }

        private void complete() {
            if (closed) return;
            closed = true;
            registeredOverlay = null;
            if (overlayPresentation == this) overlayPresentation = null;
            anchor.removeOnAttachStateChangeListener(this);
            root.removeOnAttachStateChangeListener(this);
            final WeakReference<OverlayPresentation> owner = overlayOwners.get(anchor);
            if (owner != null && owner.get() == this) {
                overlayOwners.remove(anchor);
                anchor.setAlpha(anchorAlpha);
            }
            overlay.setOnClosed(null);
            AndroidUtilities.removeFromParent(overlay);
            finishShowing(generation);
        }

        @Override
        public void onViewAttachedToWindow(View view) {}

        @Override
        public void onViewDetachedFromWindow(View view) {
            close(false);
        }

        @Override
        public BlurredBackgroundDrawable createMenuGlass(View owner) {
            return factory.create(owner, colors).setRadius(AndroidUtilities.dp(20)).setPadding(AndroidUtilities.dp(6));
        }

        @Override
        public BlurredBackgroundColorProvider getMenuGlassColorProvider() { return colors; }

        @Override
        public BlurredBackgroundColorProvider getButtonsGlassColorProvider() { return transparentButtonColors; }

        @Override
        public int getButtonsGlassBackgroundColor() { return 0; }

        @Override
        public boolean hasMenuGlassStroke() { return true; }

        @Override
        public void setMenuGlassFrostedProgress(float progress) {}

        @Override
        public boolean getMenuItemGlassRect(ActionBarMenuItem item, RectF out) {
            out.set(0, 0, anchor.getWidth(), anchor.getHeight());
            return true;
        }

        @Override
        public View getMenuView() { return anchor; }

        @Override
        public View getMenuHostView() { return anchor; }

        @Override
        public void setMenuOverlay(SimpleActionBarMenuOverlay overlay, ActionBarMenuItem item) {
            if (!isCurrent()) return;
            registeredOverlay = overlay;
            anchor.setAlpha(overlay != null ? 0f : anchorAlpha);
        }

        @Override
        public SimpleActionBarMenuOverlay getOpenMenuOverlay() { return registeredOverlay; }

        @Override
        public Theme.ResourcesProvider getMenuResourcesProvider() { return resourcesProvider; }
    }


    protected abstract void onCreate(ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout);

    protected abstract void onDismissed();

    public void dismiss() {
        if (overlayPresentation != null) {
            overlayPresentation.close(true);
            return;
        }
        if (popupWindow != null) {
            popupWindow.dismiss();
        }
    }

    public void dismissImmediately() {
        if (overlayPresentation != null) {
            overlayPresentation.close(false);
        } else if (popupWindow != null) {
            popupWindow.dismiss(false);
        }
    }

    public boolean isAnchorDrawnInOverlay(View anchor) {
        return overlayPresentation != null && overlayPresentation.anchor == anchor
                && overlayPresentation.isCurrent() && overlayPresentation.registeredOverlay != null;
    }
    public boolean isShowing() {
        return isShowing;
    }
}
