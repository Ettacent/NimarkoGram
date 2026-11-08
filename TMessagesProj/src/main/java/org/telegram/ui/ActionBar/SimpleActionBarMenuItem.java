/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.ActionBar;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.ui.Components.EditTextBoldCursor;

public class SimpleActionBarMenuItem extends ActionBarMenuItem {
    private final SimpleActionBarMenu parentMenu;

    private boolean isSearchField;
    private ActionBarMenuItemSearchListener searchListener;

    SimpleActionBarMenuItem(Context context, SimpleActionBarMenu menu, int backgroundColor, int iconColor, boolean text, Theme.ResourcesProvider resourcesProvider) {
        super(context, null, backgroundColor, iconColor, text, resourcesProvider);
        parentMenu = menu;
        setDelegate(menu::onItemClick);
    }

    public SimpleActionBarMenu getParentMenu() {
        return parentMenu;
    }

    private boolean shown = true;
    private boolean enabledBeforeHide = true;

    public void setShown(boolean shown, boolean animated) {
        if (this.shown == shown) {
            if (!shown && !animated && getVisibility() != GONE) {
                super.setVisibility(GONE);
                parentMenu.onItemShownChanged(false);
            } else if (shown && getVisibility() != VISIBLE) {
                super.setVisibility(VISIBLE);
            }
            return;
        }
        this.shown = shown;
        if (shown) {
            setEnabled(enabledBeforeHide);
        } else {
            enabledBeforeHide = isEnabled();
            setEnabled(false);
        }
        if (shown) {
            super.setVisibility(VISIBLE);
        } else if (!animated || !isLaidOut() || !parentMenu.isLaidOut()) {
            super.setVisibility(GONE);
        }
        parentMenu.onItemShownChanged(animated);
    }

    public boolean isItemShown() {
        return shown;
    }

    @Override
    public void setVisibility(int visibility) {
        if (parentMenu == null || visibility == INVISIBLE) {
            super.setVisibility(visibility);
        } else {
            setShown(visibility == VISIBLE, parentMenu.canAnimateItems());
        }
    }

    void onHideFinished() {
        if (!shown) {
            super.setVisibility(GONE);
        }
    }

    @Nullable
    @Override
    SimpleActionBarMenuOverlay getSubMenuOverlay() {
        return parentMenu.parentBar.getMenuOverlay();
    }

    @Nullable
    @Override
    MenuOverlayHost getSubMenuOverlayHost() {
        return parentMenu.parentBar;
    }

    @Override
    boolean canOpenSubMenuOverlay() {
        return !parentMenu.isActionMode || parentMenu.parentBar.isActionModeShowed();
    }

    @Override
    public ActionBarMenuItem setIsSearchField(boolean value) {
        return setIsSearchField(value, false);
    }

    @Override
    public ActionBarMenuItem setIsSearchField(boolean value, boolean wrapInScrollView) {
        isSearchField = value;
        parentMenu.parentBar.setSearchItem(value ? this : null);
        return this;
    }

    @Override
    public boolean isSearchField() {
        return isSearchField;
    }

    @Override
    public ActionBarMenuItem setActionBarMenuItemSearchListener(ActionBarMenuItemSearchListener listener) {
        searchListener = listener;
        return this;
    }

    ActionBarMenuItemSearchListener getSearchListener() {
        return searchListener;
    }

    @Override
    public void setSearchFieldHint(CharSequence hint) {
        parentMenu.parentBar.setSearchFieldHint(hint);
        setContentDescription(hint);
    }

    @Override
    public void setSearchFieldText(CharSequence text, boolean animated) {
        parentMenu.parentBar.setSearchFieldText(text);
    }

    @Override
    public EditTextBoldCursor getSearchField() {
        return parentMenu.parentBar.getSearchField();
    }

    @Override
    public void openSearch(boolean openKeyboard) {
        parentMenu.parentBar.openSearchField(openKeyboard, true);
    }

    @Override
    public boolean toggleSearch(boolean openKeyboard) {
        final SimpleActionBar bar = parentMenu.parentBar;
        if (bar.isSearchFieldVisible()) {
            bar.closeSearchField(openKeyboard);
            return false;
        }
        return bar.openSearchField(openKeyboard, true);
    }

    @Override
    public boolean isSearchFieldVisible() {
        return parentMenu.parentBar.isSearchFieldVisible();
    }

    @Override
    public boolean isSearchFieldVisible2() {
        return parentMenu.parentBar.isSearchFieldVisible();
    }

    @Override
    public void setSearchFieldCaption(CharSequence caption) {
        parentMenu.parentBar.setSearchFieldCaption(caption);
    }

    @Override
    public void clearSearchText() {
        parentMenu.parentBar.setSearchFieldText("");
    }

    @Override
    public void onSearchPressed() {
        if (searchListener != null) {
            searchListener.onSearchPressed(getSearchField());
        }
    }
}
