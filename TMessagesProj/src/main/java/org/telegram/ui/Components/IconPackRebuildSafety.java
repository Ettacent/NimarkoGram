/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ArticleViewer;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.SecretMediaViewer;

public final class IconPackRebuildSafety {
    private IconPackRebuildSafety() {
    }

    public static boolean hasVisibleMediaViewer() {
        return PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()
                || SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isVisible()
                || ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible();
    }

    public static boolean canRebuildLayout(INavigationLayout layout, boolean allowNonChatFragments) {
        if (layout == null) {
            return true;
        }
        if (!layout.canRebuildFragmentViewsNow() || layout.isInPreviewMode()) {
            return false;
        }
        for (BaseFragment fragment : layout.getFragmentStack()) {
            if (fragment == null || !canRebuildFragment(fragment, allowNonChatFragments)) {
                return false;
            }
        }
        return true;
    }

    public static boolean canRebuildFragment(BaseFragment fragment, boolean allowNonChatFragments) {
        if (fragment.getVisibleDialog() != null && fragment.getVisibleDialog().isShowing()
                || fragment.hasSheet() || BaseFragment.hasSheets(fragment)) {
            return false;
        }
        if (!(fragment instanceof ChatActivity)) {
            return allowNonChatFragments;
        }
        ChatActivity chat = (ChatActivity) fragment;
        if (chat.editingMessageObject != null || chat.isEditingMessageMedia()
                || chat.getChatMode() == ChatActivity.MODE_EDIT_BUSINESS_LINK
                || chat.hasSelectedMessages() || chat.hasTextSelection() || chat.isTryingTextSelection()
                || chat.getReplyMessage() != null && chat.getReplyMessage() != chat.getThreadMessage()
                || chat.getReplyQuote() != null || chat.getReplyTopMessage() != null
                || chat.getForwardingMessage() != null || chat.getForwardingMessageGroup() != null
                || chat.messagePreviewParams != null && !chat.messagePreviewParams.isEmpty()
                || chat.messageSuggestionParams != null
                || chat.chatAttachAlert != null && chat.chatAttachAlert.isShowing()
                || chat.scrimPopupWindow != null && chat.scrimPopupWindow.isShowing()
                || chat.getActionBar() != null && (chat.getActionBar().isActionModeShowed()
                || chat.getActionBar().isSearchFieldVisible())) {
            return false;
        }
        ChatActivityEnterView enterView = chat.getChatActivityEnterView();
        return enterView == null || !(enterView.isEditingMessage() || enterView.isEditingCaption()
                || enterView.isRecordingAudioVideo() || enterView.hasAudioToSend()
                || enterView.hasText() || enterView.isRichDraftActive()
                || enterView.getReplyingMessageObject() != null
                && enterView.getReplyingMessageObject() != chat.getThreadMessage()
                || enterView.isTopViewVisible() || enterView.isInTopViewVisibilityAnimating()
                || enterView.isPopupShowing() || enterView.botCommandsMenuIsVisibleOrAnimating()
                || enterView.isKeyboardVisible() || enterView.isKeyboardRestorePending()
                || enterView.panelAnimationInProgress());
    }
}
