/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.ActionBar;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

public interface MenuOverlayHost {
    interface SourceGuard {
        boolean isCurrent();
    }

    final class SourceState implements SourceGuard {
        private final MenuOverlayHost host;
        private final BaseFragment logical;
        private final BaseFragment hostLogical;
        private final View anchor, root, fragmentView, fragmentRoot;
        private final ViewParent parent;
        private final Object window;
        private final int account;
        private final long generation;
        private final ActionBar actionBar;
        private final SimpleActionBar simpleActionBar;

        public SourceState(@Nullable MenuOverlayHost host, View anchor, @Nullable BaseFragment logical) {
            this.host = host;
            hostLogical = host == null ? null : host.getMenuOverlaySourceFragment();
            this.logical = logical != null ? logical : hostLogical;
            this.anchor = anchor;
            root = anchor == null ? null : anchor.getRootView();
            parent = anchor == null ? null : anchor.getParent();
            window = anchor == null ? null : anchor.getWindowToken();
            fragmentView = this.logical == null ? null : this.logical.getFragmentView();
            fragmentRoot = fragmentView == null ? null : fragmentView.getRootView();
            account = this.logical == null ? -1 : this.logical.getCurrentAccount();
            generation = this.logical == null ? 0 : this.logical.getHeaderMenuSourceGeneration();
            actionBar = this.logical == null ? null : this.logical.getActionBar();
            simpleActionBar = this.logical == null ? null : this.logical.getSimpleActionBar();
        }

        public boolean isCurrent() {
            return anchor != null && anchor.isAttachedToWindow() && anchor.isShown() && anchor.isEnabled()
                    && anchor.getWindowVisibility() == View.VISIBLE && window != null
                    && anchor.getWindowToken() == window && anchor.getRootView() == root && anchor.getParent() == parent
                    && (host == null || host.isMenuOverlaySourceActive() && host.getMenuOverlaySourceFragment() == hostLogical)
                    && (logical == null || logical.isHeaderMenuOverlaySourceActive()
                    && logical.getCurrentAccount() == account && logical.getHeaderMenuSourceGeneration() == generation
                    && logical.getFragmentView() == fragmentView && (fragmentView == null || fragmentView.getRootView() == fragmentRoot)
                    && logical.getActionBar() == actionBar && logical.getSimpleActionBar() == simpleActionBar);
        }
    }

    interface IconSource {
        void getMenuIconBounds(RectF out);
        void drawMenuIcon(Canvas canvas);
        void setMenuIconOverlayOwned(boolean owned);
    }

    default boolean getMenuIconSourceBounds(RectF out) { return false; }
    default void drawMenuIconSource(Canvas canvas) {}

    default boolean hasUnclippedMenuIconSource() { return false; }
    default void drawUnclippedMenuIconSource(Canvas canvas) {}
    default boolean interpolateMenuColors() { return false; }

    @Nullable
    default BaseFragment getMenuOverlaySourceFragment() {
        return null;
    }

    default boolean isMenuOverlaySourceActive() {
        final View view = getMenuHostView();
        return view != null && view.isAttachedToWindow() && view.isShown()
                && view.getWindowVisibility() == View.VISIBLE;
    }

    @Nullable
    BlurredBackgroundDrawable createMenuGlass(View owner);

    @Nullable
    BlurredBackgroundColorProvider getMenuGlassColorProvider();

    int getButtonsGlassBackgroundColor();

    @Nullable
    default BlurredBackgroundColorProvider getButtonsGlassColorProvider() {
        return null;
    }

    boolean hasMenuGlassStroke();

    void setMenuGlassFrostedProgress(float progress);

    boolean getMenuItemGlassRect(ActionBarMenuItem item, RectF out);

    View getMenuView();

    void setMenuOverlay(@Nullable SimpleActionBarMenuOverlay overlay, @Nullable ActionBarMenuItem item);

    @Nullable
    SimpleActionBarMenuOverlay getOpenMenuOverlay();

    @Nullable
    Theme.ResourcesProvider getMenuResourcesProvider();

    default View getMenuHostView() {
        return (View) this;
    }

    default boolean isMenuFromPillBelow() {
        return false;
    }

    default int getMenuRise() {
        return 0;
    }
}
