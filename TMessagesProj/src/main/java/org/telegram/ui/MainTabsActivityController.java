package org.telegram.ui;

public interface MainTabsActivityController {
    void setTabsVisible(boolean visible);
    default void setTabsHiddenByOverlay(boolean hidden) {}
}
