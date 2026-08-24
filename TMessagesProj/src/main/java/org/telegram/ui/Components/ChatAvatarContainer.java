/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.replaceArrows;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.BusinessLinksController;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.Stories.StoriesUtilities;
import org.telegram.ui.TopicsFragment;
import org.telegram.ui.community.CommunityArrowDrawable;

import java.util.concurrent.atomic.AtomicReference;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class ChatAvatarContainer extends FrameLayout implements FactorAnimator.Target, NotificationCenter.NotificationCenterDelegate {

    private static final int ANIMATOR_ID_TIME_ITEM_VISIBLE = 0;
    private static final int COMMUNITY_BADGE_TOUCH_SIZE_DP = 28;
    private static final int INLINE_COMMUNITY_TOUCH_SIZE_DP = 32;
    private static final int INLINE_COMMUNITY_GAP_DP = 2;
    private final BoolAnimator animatorTimeVisible = new BoolAnimator(ANIMATOR_ID_TIME_ITEM_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320);

    // NimarkoGram (CG parity, L81): centerChatTitle is a field, not a local —
    // onMeasure / onLayout / onTouchEvent / openProfile / star-drawable picker
    // all read it, so a setup-block-local variable does not reach those sites
    // and the centered layout never actually takes effect at draw time.
    // Initial value comes from the user pref; the per-context guards (Saved
    // mode, self-chat, REPLY_BOT, reply-comment) AND-out inside the setup
    // block once parentFragment is known.
    private boolean centerChatTitle = app.nimarkogram.messenger.NimarkoConfig.centerChatTitle;
    private boolean useChatTitleLayoutOutsideChat;

    private boolean resolveCenterChatTitle() {
        if (!app.nimarkogram.messenger.NimarkoConfig.centerChatTitle) {
            return false;
        }
        if (parentFragment == null) {
            // Forum/community headers are chat surfaces too.  They use this
            // same container so the user's chat-title preference controls the
            // whole title/avatar group instead of falling back to the global
            // ActionBar title preference.
            return useChatTitleLayoutOutsideChat;
        }
        return parentFragment != null
                && parentFragment.getChatMode() != ChatActivity.MODE_SAVED
                && !parentFragment.isReplyChatComment()
                && parentFragment.getDialogId() != 0
                && parentFragment.getDialogId() != org.telegram.messenger.UserConfig.getInstance(org.telegram.messenger.UserConfig.selectedAccount).getClientUserId()
                && parentFragment.getDialogId() != org.telegram.messenger.UserObject.REPLY_BOT;
    }

    private void updateCenterChatTitleState() {
        boolean value = resolveCenterChatTitle();
        if (centerChatTitle == value) {
            return;
        }
        centerChatTitle = value;
        if (titleTextView != null) {
            titleTextView.setGravity(value ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
            // Keep status / Premium drawables in their own measured trailing
            // slot. With a marquee title, treating them as part of the scrolling
            // text computes their X from the full (unclipped) nickname width and
            // can paint the star beyond the glass island.
            titleTextView.setRightDrawableOutside(true);
            titleTextView.setScrollNonFitText(value);
        }
        if (subtitleTextView != null) {
            subtitleTextView.setGravity(value ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
            subtitleTextView.setPadding(value ? dp(10) : 0, 0, dp(10), 0);
        }
        if (animatedSubtitleTextView != null) {
            animatedSubtitleTextView.setGravity(value ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
            animatedSubtitleTextView.setPadding(value ? dp(10) : 0, 0, dp(10), 0);
        }
        if (timeItem != null) {
            timeItem.setPadding(value ? dp(5) : 10, dp(10), value ? dp(20) : 5, dp(5));
        }
        updateCommunityIndicatorStyle();
        if (value || useChatTitleLayoutOutsideChat) {
            clearLargerTextCopies();
        }
        if (actionBar != null) {
            actionBar.checkAvatarContainerWidth(!useChatTitleLayoutOutsideChat);
            actionBar.requestLayout();
            actionBar.invalidate();
        }
    }

    public boolean isCenterChatTitleEnabled() {
        return centerChatTitle;
    }

    /** Community/forum lists keep the avatar inside the central title island. */
    public boolean isInlineCenteredAvatar() {
        return useChatTitleLayoutOutsideChat && centerChatTitle;
    }

    /**
     * The compact identity island is part of the centred-chat-title design.
     * Forum/community lists used to force it merely because they reuse this
     * container, which left the title centred and the glass capsule narrow even
     * after the user disabled "Centre title in chats".
     */
    public boolean shouldUseCompactTitleIsland() {
        return centerChatTitle;
    }

    /**
     * Returns the stable menu slot used by the centred-title avatar, in this container's
     * coordinates. The old first-frame estimate was roughly 60dp too far left and was
     * replaced by the real menu position a moment later, which caused the visible jump.
     */
    private int nmLastCenteredAvatarGlobalCx = Integer.MIN_VALUE;

    private int resolveCenteredAvatarCx() {
        if (!(getParent() instanceof ActionBar)) {
            return (getWidth() - leftPadding) - dp(24);
        }
        ActionBar parentActionBar = (ActionBar) getParent();
        View headerItem = parentFragment != null ? parentFragment.getHeaderItem() : null;
        if (headerItem != null && headerItem.getVisibility() == VISIBLE
                && headerItem.getWidth() > 0 && headerItem.getParent() instanceof View) {
            View menuView = (View) headerItem.getParent();
            nmLastCenteredAvatarGlobalCx = Math.round(menuView.getX() + headerItem.getX() + headerItem.getWidth() / 2f);
            return Math.round(nmLastCenteredAvatarGlobalCx - getX());
        }
        if (nmLastCenteredAvatarGlobalCx != Integer.MIN_VALUE) {
            return Math.round(nmLastCenteredAvatarGlobalCx - getX());
        }
        float menuTranslation = parentActionBar.menu != null ? parentActionBar.menu.getTranslationX() : 0f;
        return Math.round(parentActionBar.getWidth() - dp(24) + menuTranslation - getX());
    }

    /** Keep the avatar and its attached indicators pinned while the glass title oval animates. */
    public void syncCenteredAvatarAnchor() {
        if (avatarImageView == null) {
            return;
        }
        float translation = 0f;
        if (centerChatTitle && !isInlineCenteredAvatar() && avatarImageView.getWidth() > 0) {
            float laidOutCenter = avatarImageView.getLeft() + avatarImageView.getWidth() / 2f;
            translation = resolveCenteredAvatarCx() - laidOutCenter;
        }
        avatarImageView.setTranslationX(translation);
        if (communityItem != null) {
            // In centred chat headers the community chevron belongs to the
            // title island, while the avatar occupies the overflow-menu slot.
            communityItem.setTranslationX(shouldUseInlineCommunityIndicator() ? 0f : translation);
        }
        if (timeItem != null) {
            timeItem.setTranslationX(translation);
        }
    }
    public boolean allowDrawStories;
    private Integer storiesForceState;
    private int avatarSizeInDp = 42;
    public BackupImageView avatarImageView;
    private boolean avatarImageIsHidden;
    private SimpleTextView titleTextView;
    private AtomicReference<SimpleTextView> titleTextLargerCopyView = new AtomicReference<>();
    private SimpleTextView subtitleTextView;
    private AnimatedTextView animatedSubtitleTextView;
    private AtomicReference<SimpleTextView> subtitleTextLargerCopyView = new AtomicReference<>();
    private ImageView timeItem;
    private ImageView communityItem;
    private CommunityArrowDrawable communityArrowDrawable;
    private ImageView starBgItem, starFgItem;
    private TimerDrawable timerDrawable;
    private ChatActivity parentFragment;
    private StatusDrawable[] statusDrawables = new StatusDrawable[6];
    private AvatarDrawable avatarDrawable = new AvatarDrawable();
    private org.telegram.tgnet.TLObject headerIdentityTarget;
    private int currentAccount = UserConfig.selectedAccount;
    private boolean occupyStatusBar = true;
    private int leftPadding = dp(8);
    private int rightAvatarPadding = 0;
    private int nmCenteredAvatarCx;   // NimarkoGram: centered-title avatar center-x (aligned to the headerItem at layout time)
    StatusDrawable currentTypingDrawable;

    private int lastWidth = -1;
    private int largerWidth = -1;


    private AnimatorSet titleAnimation;

    private boolean[] isOnline = new boolean[1];
    public boolean[] statusMadeShorter = new boolean[1];

    private boolean secretChatTimer;

    private int onlineCount = -1;
    private int currentConnectionState;
    private CharSequence lastSubtitle;
    // Forum/community headers use a compact, content-sized glass island.  A
    // subtitle change is cross-faded, so for part of the animation the view
    // still contains the old text.  Keep the destination width reserved during
    // that interval; regular chat headers already have a fixed minimum width.
    private float inlineSubtitleWidthReserve;
    private int subtitleTransitionGeneration;
    private boolean subtitleHiddenByPreference;
    // Text children are allowed to draw outside their own bounds for badges
    // and gradient ellipsising.  In an inline Topics identity island that must
    // still be clipped to the animated glass content column, otherwise the new
    // subtitle can appear outside the capsule while its width catches up.
    private boolean inlineTextClipEnabled;
    private int inlineTextClipLeft;
    private int inlineTextClipRight;
    private int lastSubtitleColorKey = -1;
    private Integer overrideSubtitleColor;

    private SharedMediaLayout.SharedMediaPreloader sharedMediaPreloader;
    private Theme.ResourcesProvider resourcesProvider;

    public boolean allowShorterStatus = false;
    public boolean premiumIconHiddable = false;

    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emojiStatusDrawable;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable botVerificationDrawable;
    // NimarkoGram: extera-style badge slot — populated from BadgesController
    // when the currently-shown user has a registered badge.
    private AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable badgeEmojiDrawable;
    private app.nimarkogram.messenger.api.dto.BadgeDTO currentNimarkoBadge;

    protected boolean useAnimatedSubtitle() {
        return false;
    }

    public void hideSubtitle() {
        if (getSubtitleTextView() != null) {
            getSubtitleTextView().setVisibility(View.GONE);
        }
        inlineSubtitleWidthReserve = 0f;
        checkActionBar(true);
    }

    public void setStoriesForceState(Integer storiesForceState) {
        this.storiesForceState = storiesForceState;
    }

    private class SimpleTextConnectedView extends SimpleTextView {

        private AtomicReference<SimpleTextView> reference;
        public SimpleTextConnectedView(Context context, AtomicReference<SimpleTextView> reference) {
            super(context);
            this.reference = reference;
        }

        @Override
        public void setTranslationY(float translationY) {
            if (reference != null) {
                SimpleTextView connected = reference.get();
                if (connected != null) {
                    connected.setTranslationY(translationY);
                }
            }
            super.setTranslationY(translationY);
        }

        @Override
        public boolean setText(CharSequence value) {
            if (reference != null) {
                SimpleTextView connected = reference.get();
                if (connected != null) {
                    connected.setText(value);
                }
            }
            return super.setText(value);
        }
    }

    public ChatAvatarContainer(Context context, BaseFragment baseFragment, boolean needTime) {
        this(context, baseFragment, needTime, null);
    }

    public ChatAvatarContainer(Context context, BaseFragment baseFragment, boolean needTime, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        if (baseFragment instanceof ChatActivity) {
            parentFragment = (ChatActivity) baseFragment;
        } else if (baseFragment instanceof TopicsFragment
                || baseFragment instanceof DialogsActivity && ((DialogsActivity) baseFragment).isCommunityDialogList()) {
            useChatTitleLayoutOutsideChat = true;
        }

        final boolean avatarClickable = parentFragment != null && (parentFragment.getChatMode() == 0 || parentFragment.getChatMode() == ChatActivity.MODE_SUGGESTIONS) && !UserObject.isReplyUser(parentFragment.getCurrentUser()) && (parentFragment.getCurrentUser() == null || parentFragment.getCurrentUser().id != UserObject.VERIFY);
        avatarImageView = new BackupImageView(context) {

            StoriesUtilities.AvatarStoryParams params = new StoriesUtilities.AvatarStoryParams(true) {
                @Override
                public void openStory(long dialogId, Runnable onDone) {
                    baseFragment.getOrCreateStoryViewer().open(getContext(), dialogId, (dialogId1, messageId, storyId, type, holder) -> {
                        holder.crossfadeToAvatarImage = holder.storyImage = imageReceiver;
                        holder.params = params;
                        holder.isLive = params.drawnLive;
                        holder.view = avatarImageView;
                        holder.alpha = avatarImageView.getAlpha();
                        holder.clipTop = 0;
                        holder.clipBottom = AndroidUtilities.displaySize.y;
                        holder.clipParent = (View) getParent();
                        return true;
                    });
                }
            };

            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                if (avatarClickable && getImageReceiver().hasNotThumb()) {
                    info.setText(getString(R.string.AccDescrProfilePicture));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, getString(R.string.Open)));
                } else {
                    info.setVisibleToUser(false);
                }
            }

            @Override
            protected void onDraw(Canvas canvas) {
                if (allowDrawStories && animatedEmojiDrawable == null) {
                    params.originalAvatarRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    params.drawSegments = true;
                    params.drawInside = true;
                    params.resourcesProvider = resourcesProvider;
                    if (storiesForceState != null) {
                        params.forceState = storiesForceState;
                    }

                    long dialogId = 0;
                    if (parentFragment != null) {
                        dialogId = parentFragment.getDialogId();
                    } else if (baseFragment instanceof TopicsFragment) {
                        dialogId = ((TopicsFragment) baseFragment).getDialogId();
                    }

                    StoriesUtilities.drawAvatarWithStory(dialogId, canvas, imageReceiver, params);
                } else {
                    super.onDraw(canvas);
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (allowDrawStories) {
                    if (params.checkOnTouchEvent(event, this)) {
                        return true;
                    }
                }
                return super.onTouchEvent(event);
            }
        };
        if (baseFragment instanceof ChatActivity || baseFragment instanceof TopicsFragment) {
            if (parentFragment == null || (parentFragment.getChatMode() != ChatActivity.MODE_QUICK_REPLIES && parentFragment.getChatMode() != ChatActivity.MODE_WELCOME_MESSAGES && parentFragment.getChatMode() != ChatActivity.MODE_EDIT_BUSINESS_LINK) && parentFragment.getChatMode() != ChatActivity.MODE_SUGGESTIONS && !parentFragment.isInBotForumMode()) {
                sharedMediaPreloader = new SharedMediaLayout.SharedMediaPreloader(baseFragment);
            }
            avatarImageIsHidden = parentFragment != null && (parentFragment.isThreadChat() || parentFragment.getChatMode() == ChatActivity.MODE_PINNED || parentFragment.getChatMode() == ChatActivity.MODE_QUICK_REPLIES || parentFragment.getChatMode() == ChatActivity.MODE_WELCOME_MESSAGES || parentFragment.getChatMode() == ChatActivity.MODE_EDIT_BUSINESS_LINK);
            if (avatarImageIsHidden) {
                avatarImageView.setVisibility(GONE);
            }
        }
        avatarImageView.setContentDescription(getString(R.string.AccDescrProfilePicture));
        avatarImageView.setRoundRadius(AndroidUtilities.dp(21));   // Chat header keeps Telegram's own shape.
        addView(avatarImageView);

        // NimarkoGram (CG parity, L262/L270-273): full-set centerChatTitle guards.
        // Title centring is disabled in Saved-Messages mode, reply-comment threads,
        // empty-dialog chats, your own self-chat, and the REPLY_BOT chat — these
        // contexts already have CG-default left alignment and forcing center would
        // collide with the avatar/back stack. Assign the *field* (not a local) so
        // onMeasure / onLayout / openProfile / onTouchEvent see the same value.
        centerChatTitle = resolveCenterChatTitle();

        // CG L262: when title is centered, the avatar is no longer clickable —
        // tapping the centered title is the avatar's old action. Without this
        // guard the avatar competes with the centered title for the tap and the
        // user reports the click going to the "wrong" spot.
        if (avatarClickable && !centerChatTitle) {
            // Upstream 12.9.0: linked-community chats get a scale press animation.
            final TLRPC.Chat chat = parentFragment != null ? parentFragment.getCurrentChat() : null;
            if (chat != null && chat.linked_community_id != 0) {
                ScaleStateListAnimator.apply(avatarImageView, .05f, 1.2f);
            }
            // The community selector has its own arrow target. Keep the avatar's
            // normal Telegram contract: it always opens chat/profile info.
            avatarImageView.setOnClickListener(v -> openProfile(true));
        }

        titleTextView = new SimpleTextConnectedView(context, titleTextLargerCopyView);
        titleTextView.setEllipsizeByGradient(
                true, useChatTitleLayoutOutsideChat ? LocaleController.isRTL : null);
        titleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
        titleTextView.setTextSize(18);
        titleTextView.setGravity(centerChatTitle ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setLeftDrawableTopPadding(-dp(1.3f));
        titleTextView.setCanHideRightDrawable(false);
        // Drawables use a measured trailing slot. For short titles they remain
        // directly beside the name; for long/marquee titles the slot is clamped
        // to the title view and cannot escape the glass island.
        titleTextView.setRightDrawableOutside(true);
        // setTitleIcons() intentionally does not draw the mute bell, therefore
        // the former one-sided 25dp compensation only skewed the visible group.
        titleTextView.setPadding(0, dp(6), 0, dp(12));
        titleTextView.setScrollNonFitText(centerChatTitle);
        addView(titleTextView);

        if (useAnimatedSubtitle()) {
            animatedSubtitleTextView = new AnimatedTextView(context, true, true, true);
            animatedSubtitleTextView.setAnimationProperties(.3f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
            animatedSubtitleTextView.setEllipsizeByGradient(true);
            animatedSubtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
            animatedSubtitleTextView.setTag(Theme.key_actionBarDefaultSubtitle);
            animatedSubtitleTextView.setTextSize(dp(14));
            animatedSubtitleTextView.setGravity(centerChatTitle ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
            // CG L306: subtitle gets symmetric dp(10) horizontal padding when centered.
            animatedSubtitleTextView.setPadding(centerChatTitle ? dp(10) : 0, 0, dp(10), 0);
            animatedSubtitleTextView.setTranslationY(-dp(1));
            addView(animatedSubtitleTextView);
        } else {
            subtitleTextView = new SimpleTextConnectedView(context, subtitleTextLargerCopyView);
            subtitleTextView.setEllipsizeByGradient(
                    true, useChatTitleLayoutOutsideChat ? LocaleController.isRTL : null);
            subtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
            subtitleTextView.setTag(Theme.key_actionBarDefaultSubtitle);
            subtitleTextView.setTextSize(14);
            subtitleTextView.setGravity(centerChatTitle ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
            subtitleTextView.setPadding(centerChatTitle ? dp(10) : 0, 0, dp(10), 0);
            addView(subtitleTextView);
        }

        if (parentFragment != null) {
            communityItem = new ImageView(context);
            communityItem.setScaleType(ImageView.ScaleType.CENTER);
            communityItem.setVisibility(GONE);
            communityItem.setImageDrawable(communityArrowDrawable = new CommunityArrowDrawable());
            communityItem.setContentDescription(getString(R.string.CommunitySectionChatsYouCanView));
            communityItem.setOnClickListener(v -> {
                if (!onCommunityClick()) {
                    openProfile(false);
                }
            });
            ScaleStateListAnimator.apply(communityItem, .06f, 1.2f);
            addView(communityItem);

            timeItem = new ImageView(context);
            // CG L323: shift the secret-chat timer to the right edge when title
            // is centered so it does not collide with the centered name block.
            timeItem.setPadding(centerChatTitle ? dp(5) : 10, dp(10), centerChatTitle ? dp(20) : 5, dp(5));
            timeItem.setScaleType(ImageView.ScaleType.CENTER);
            timeItem.setVisibility(GONE);
            timeItem.setImageDrawable(timerDrawable = new TimerDrawable(context, resourcesProvider));
            timerDrawable.setBackgroundColor(0);
            addView(timeItem);
            secretChatTimer = needTime;

            timeItem.setOnClickListener(v -> {
                if (secretChatTimer) {
                    parentFragment.showDialog(AlertsCreator.createTTLAlert(getContext(), parentFragment.getCurrentEncryptedChat(), resourcesProvider).create());
                } else {
                    openSetTimer();
                }
            });
            if (secretChatTimer) {
                timeItem.setContentDescription(getString(R.string.SetTimer));
            } else {
                timeItem.setContentDescription(getString(R.string.AccAutoDeleteTimer));
            }

            starBgItem = new ImageView(context);
            starBgItem.setImageResource(R.drawable.star_small_outline);
            starBgItem.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_actionBarDefault), PorterDuff.Mode.SRC_IN));
            starBgItem.setAlpha(0.0f);
            starBgItem.setVisibility(View.INVISIBLE);
            starBgItem.setScaleY(0.0f);
            starBgItem.setScaleX(0.0f);
            addView(starBgItem);

            starFgItem = new ImageView(context);
            starFgItem.setImageResource(R.drawable.star_small_inner);
            starFgItem.setAlpha(0.0f);
            starFgItem.setVisibility(View.INVISIBLE);
            starFgItem.setScaleY(0.0f);
            starFgItem.setScaleX(0.0f);
            addView(starFgItem);
        }

        if (parentFragment != null && (parentFragment.getChatMode() == 0 || parentFragment.getChatMode() == ChatActivity.MODE_SUGGESTIONS || parentFragment.getChatMode() == ChatActivity.MODE_SAVED)) {
            if ((!parentFragment.isThreadChat() || parentFragment.isTopic || parentFragment.isComments) && !UserObject.isReplyUser(parentFragment.getCurrentUser()) && (parentFragment.getCurrentUser() == null || parentFragment.getCurrentUser().id != UserObject.VERIFY)) {
                setOnClickListener(v -> openProfile(false));
            }

            TLRPC.Chat chat = parentFragment.getCurrentChat();
            statusDrawables[0] = new TypingDotsDrawable(true);
            statusDrawables[1] = new RecordStatusDrawable(true);
            statusDrawables[2] = new SendingFileDrawable(true);
            statusDrawables[3] = new PlayingGameDrawable(false, resourcesProvider);
            statusDrawables[4] = new RoundStatusDrawable(true);
            statusDrawables[5] = new ChoosingStickerStatusDrawable(true);
            // NG: only the drawables owned by ChatAvatarContainer (chat actionbar
            // subtitle) get the centerChatTitle math applied. Drawables fetched
            // via Theme.getChatStatusDrawable() — e.g. inside DialogCell — never
            // see this flag set, so they keep vanilla left-aligned positioning
            // and don't drift left in the chat list.
            ((RecordStatusDrawable) statusDrawables[1]).setUseCenteredOverride(true);
            ((SendingFileDrawable) statusDrawables[2]).setUseCenteredOverride(true);
            ((PlayingGameDrawable) statusDrawables[3]).setUseCenteredOverride(true);
            ((RoundStatusDrawable) statusDrawables[4]).setUseCenteredOverride(true);
            for (int a = 0; a < statusDrawables.length; a++) {
                statusDrawables[a].setIsChat(chat != null);
            }
        }

        emojiStatusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(titleTextView, dp(24));
        botVerificationDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(titleTextView, dp(17));
    }

    public ButtonBounce bounce = new ButtonBounce(this);
    private Runnable onLongClick = () -> {
        pressed = false;
        bounce.setPressed(false);
        if (canSearch()) {
            // CG-parity: emit a keyboard-tap haptic when long-press opens the
            // header search — unless the user disabled vibration globally.
            if (!app.nimarkogram.messenger.NimarkoConfig.disableVibration) {
                try {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignored) {}
            }
            // CG-parity: reset messages-search filter to NONE before opening
            // the header search so a stale per-chat filter doesn't leak in.
            app.nimarkogram.messenger.NimarkoConfig.setMessagesSearchFilter(app.nimarkogram.messenger.NimarkoConfig.FILTER_NONE);
            openSearch();
        }
    };

    private boolean pressed;
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && canSearch()) {
            pressed = true;
            // CG L406: disable the press-scale bounce when the title is
            // centered — the centered name doubles as the search trigger,
            // and the scale-bounce on the whole container reads as a glitch.
            bounce.setPressed(!centerChatTitle);
            AndroidUtilities.cancelRunOnUIThread(this.onLongClick);
            AndroidUtilities.runOnUIThread(this.onLongClick, ViewConfiguration.getLongPressTimeout());
            return true;
        } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            if (pressed) {
                bounce.setPressed(false);
                pressed = false;
                if (isClickable()) {
                    openProfile(false);
                }
                AndroidUtilities.cancelRunOnUIThread(this.onLongClick);
            }
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        bounce.setPressed(pressed);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        final float s = bounce.getScale(.02f);
        canvas.scale(s, s, getWidth() / 2f, getHeight() - ActionBar.getCurrentActionBarHeight() / 2f);
        super.dispatchDraw(canvas);
        canvas.restore();
    }


    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        if (inlineTextClipEnabled
                && (child == titleTextView
                || child == subtitleTextView
                || child == animatedSubtitleTextView
                || child == titleTextLargerCopyView.get()
                || child == subtitleTextLargerCopyView.get())) {
            final int save = canvas.save();
            canvas.clipRect(inlineTextClipLeft, 0, inlineTextClipRight, getHeight());
            final boolean drawn = super.drawChild(canvas, child, drawingTime);
            canvas.restoreToCount(save);
            return drawn;
        }
        if (child == avatarImageView) {
            final boolean hasTimer = timeItem != null && timeItem.getVisibility() == VISIBLE;
            final boolean hasCommunity = communityItem != null
                    && communityItem.getVisibility() == VISIBLE
                    && !shouldUseInlineCommunityIndicator();
            if (hasTimer || hasCommunity) {
                AndroidUtilities.rectTmp.set(child.getX(), child.getY(), child.getX() + child.getWidth(), child.getY() + child.getHeight());
                AndroidUtilities.rectTmp.inset(-dp(3), -dp(3));
                canvas.saveLayer(AndroidUtilities.rectTmp, null);
                final boolean b = super.drawChild(canvas, child, drawingTime);
                if (hasTimer) {
                    final float cx = timeItem.getX() + timeItem.getWidth() / 2f;
                    final float cy = timeItem.getY() + timeItem.getHeight() / 2f;
                    final float r = dpf2(12f) * timeItem.getScaleX();
                    canvas.drawCircle(cx, cy - dpf2(0.33f), r, Theme.PAINT_CLEAR);
                }
                if (hasCommunity) {
                    final float cx = communityItem.getX() + communityItem.getWidth() / 2f;
                    final float cy = communityItem.getY() + communityItem.getHeight() / 2f;
                    final float r = dpf2(7.66f) * communityItem.getScaleX();
                    canvas.drawCircle(cx, cy, r, Theme.PAINT_CLEAR);
                }
                canvas.restore();
                return b;
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    public boolean ignoreTouches;
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ignoreTouches) return false;
        return super.dispatchTouchEvent(ev);
    }

    protected boolean canSearch() {
        return false;
    }

    protected void openSearch() {

    }

    protected boolean onCommunityClick() {
        return false;
    }

    /**
     * Centred chat titles detach the avatar into the right overflow slot. Keep
     * the community affordance with the clickable title instead of attaching a
     * misleading arrow to an avatar which opens a different action.
     */
    private boolean shouldUseInlineCommunityIndicator() {
        return centerChatTitle
                && !isInlineCenteredAvatar()
                && communityItem != null
                && communityItem.getVisibility() == VISIBLE;
    }

    private int getInlineCommunityIndicatorSpace() {
        return shouldUseInlineCommunityIndicator()
                ? dp(INLINE_COMMUNITY_GAP_DP + INLINE_COMMUNITY_TOUCH_SIZE_DP)
                : 0;
    }

    /**
     * Trailing distance from the title edge to the visible arrow edge. The
     * complete 32dp target is still reserved by getInlineCommunityIndicatorSpace,
     * but centring against that invisible box shifts the visible pair sideways.
     */
    private int getInlineCommunityIndicatorVisualAdvance() {
        if (!shouldUseInlineCommunityIndicator()) {
            return 0;
        }
        final int visualWidth = communityArrowDrawable != null
                ? communityArrowDrawable.getInlineVisualWidth()
                : dp(20);
        return Math.round(
                dp(INLINE_COMMUNITY_GAP_DP)
                        + (dp(INLINE_COMMUNITY_TOUCH_SIZE_DP) + visualWidth) / 2f);
    }

    private void updateCommunityIndicatorStyle() {
        if (communityArrowDrawable == null) {
            return;
        }
        communityArrowDrawable
                .setInline(shouldUseInlineCommunityIndicator())
                .setInlineColor(titleTextView != null
                        ? titleTextView.getTextPaint().getColor()
                        : getThemedColor(Theme.key_actionBarDefaultTitle));
        if (communityItem != null) {
            communityItem.invalidate();
        }
    }

    // NimarkoGram: reserves right padding for the centered-title / hide-call-icon feature (called from
    // ChatActivity.setTitleExpand). Dropped during the 12.9.0 merge resolution — restored.
    public void setTitleExpand(boolean titleExpand) {
        int newRightPadding = titleExpand ? dp(10) : 0;
        if (titleTextView.getPaddingRight() != newRightPadding) {
            titleTextView.setPadding(0, dp(6), newRightPadding, dp(12));
            requestLayout();
            invalidate();
        }
    }

    public void setOverrideSubtitleColor(Integer overrideSubtitleColor) {
        this.overrideSubtitleColor = overrideSubtitleColor;
    }

    public boolean openSetTimer() {
        if (parentFragment.getParentActivity() == null) {
            return false;
        }
        TLRPC.Chat chat = parentFragment.getCurrentChat();
        if (chat != null && !ChatObject.canUserDoAdminAction(chat, ChatObject.ACTION_DELETE_MESSAGES)) {
            if (animatorTimeVisible.getValue()) {
                parentFragment.showTimerHint();
            }
            return false;
        }
        TLRPC.ChatFull chatInfo = parentFragment.getCurrentChatInfo();
        TLRPC.UserFull userInfo = parentFragment.getCurrentUserInfo();
        int ttl = 0;
        if (userInfo != null) {
            ttl = userInfo.ttl_period;
        } else if (chatInfo != null) {
            ttl = chatInfo.ttl_period;
        }

        ActionBarPopupWindow[] scrimPopupWindow = new ActionBarPopupWindow[1];
        AutoDeletePopupWrapper autoDeletePopupWrapper = new AutoDeletePopupWrapper(getContext(), null, new AutoDeletePopupWrapper.Callback() {
            @Override
            public void dismiss() {
                if (scrimPopupWindow[0] != null) {
                    scrimPopupWindow[0].dismiss();
                }
            }

            @Override
            public void setAutoDeleteHistory(int time, int action) {
                if (parentFragment == null) {
                    return;
                }
                parentFragment.getMessagesController().setDialogHistoryTTL(parentFragment.getDialogId(), time);
                TLRPC.ChatFull chatInfo = parentFragment.getCurrentChatInfo();
                TLRPC.UserFull userInfo = parentFragment.getCurrentUserInfo();
                if (userInfo != null || chatInfo != null) {
                    UndoView undoView = parentFragment.getUndoView();
                    if (undoView != null) {
                        undoView.showWithAction(parentFragment.getDialogId(), action, parentFragment.getCurrentUser(), userInfo != null ? userInfo.ttl_period : chatInfo.ttl_period, null, null);
                    }
                }

            }
        }, true, 0, resourcesProvider);
        autoDeletePopupWrapper.updateItems(ttl);

        scrimPopupWindow[0] = new ActionBarPopupWindow(autoDeletePopupWrapper.windowLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() {
                super.dismiss();
                if (parentFragment != null) {
                    parentFragment.dimBehindView(false);
                }
            }
        };
        scrimPopupWindow[0].setPauseNotifications(true);
        scrimPopupWindow[0].setDismissAnimationDuration(220);
        scrimPopupWindow[0].setOutsideTouchable(true);
        scrimPopupWindow[0].setClippingEnabled(true);
        scrimPopupWindow[0].setAnimationStyle(R.style.PopupContextAnimation);
        scrimPopupWindow[0].setFocusable(true);
        autoDeletePopupWrapper.windowLayout.measure(View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST));
        scrimPopupWindow[0].setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        scrimPopupWindow[0].getContentView().setFocusableInTouchMode(true);
        scrimPopupWindow[0].showAtLocation(avatarImageView, 0, (int) (avatarImageView.getX() + getX()), (int) avatarImageView.getY());
        parentFragment.dimBehindView(true);
        return true;
    }

    public void openProfile(boolean byAvatar) {
        openProfile(byAvatar, true, false);
    }

    public void openProfile(boolean byAvatar, boolean fromChatAnimation, boolean removeLast) {
        if (byAvatar && (AndroidUtilities.isTablet() || AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y)) {
            byAvatar = false;
        }
        TLRPC.User user = parentFragment.getCurrentUser();
        TLRPC.Chat chat = parentFragment.getCurrentChat();
        final boolean monoforum = chat != null && chat.monoforum;
        if (chat != null && chat.monoforum) {
            TLRPC.Chat channel = parentFragment.getMessagesController().getChat(chat.linked_monoforum_id);
            if (channel == null) return;
            chat = channel;
            if (parentFragment.getSendMonoForumPeerId() != 0) {
                TLRPC.User fromUser = parentFragment.getMessagesController().getUser(parentFragment.getSendMonoForumPeerId());
                if (fromUser != null) {
                    user = fromUser;
                    chat = null;
                }
            }
        }
        ImageReceiver imageReceiver = avatarImageView.getImageReceiver();
        String key = imageReceiver.getImageKey();
        ImageLoader imageLoader = ImageLoader.getInstance();
        if (key != null && !imageLoader.isInMemCache(key, false)) {
            Drawable drawable = imageReceiver.getDrawable();
            if (drawable instanceof BitmapDrawable && !(drawable instanceof AnimatedFileDrawable)) {
                imageLoader.putImageToCache((BitmapDrawable) drawable, key, false);
            }
        }

        if (byAvatar) {
            // Keep the user's intent (full avatar expansion) separate from the
            // current image-loading stage. On the first open ImageReceiver may
            // still contain only a thumbnail, so hasNotThumb() is false even
            // though the peer has a real profile photo. Downgrading to the
            // compact animation in that state produces the deterministic
            // 96dp/138dp (~70%) opening. ProfileActivity already crossfades
            // from this source receiver to the full-resolution gallery image.
            final boolean hasProfilePhoto = user != null
                    ? UserObject.hasPhoto(user)
                    : ChatObject.hasPhoto(chat);
            if (!hasProfilePhoto) {
                byAvatar = false;
            }
        }

        if (parentFragment.isComments) {
            if (chat == null) return;
            parentFragment.presentFragment(ProfileActivity.of(-chat.id), removeLast);
            return;
        }

        if (user != null) {
            if (user.id == UserObject.VERIFY) {
                return;
            }
            Bundle args = new Bundle();
            if (UserObject.isUserSelf(user)) {
                if (!sharedMediaPreloader.hasSharedMedia()) {
                    return;
                }
                args.putLong("dialog_id", parentFragment.getDialogId());
                if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                    args.putLong("topic_id", parentFragment.getSavedDialogId());
                }
                MediaActivity fragment = new MediaActivity(args, sharedMediaPreloader);
                fragment.setChatInfo(parentFragment.getCurrentChatInfo());
                parentFragment.presentFragment(fragment, removeLast);
            } else {
                if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                    long dialogId = parentFragment.getSavedDialogId();
                    args.putBoolean("saved", true);
                    if (dialogId >= 0) {
                        args.putLong("user_id", dialogId);
                    } else {
                        args.putLong("chat_id", -dialogId);
                    }
                } else {
                    args.putLong("user_id", user.id);
                    if (timeItem != null && !monoforum) {
                        args.putLong("dialog_id", parentFragment.getDialogId());
                    }
                }
                if (UserObject.isBotForum(user)) {
                    args.putLong("topic_id", parentFragment.getTopicId());
                }
                args.putBoolean("reportSpam", parentFragment.hasReportSpam());
                args.putInt("actionBarColor", getThemedColor(Theme.key_actionBarDefault));
                final ProfileActivity fragment = new ProfileActivity(args, sharedMediaPreloader);
                if (!monoforum) {
                    fragment.setUserInfo(parentFragment.getCurrentUserInfo(), parentFragment.profileChannelMessageFetcher, parentFragment.birthdayAssetsFetcher);
                }
                if (fromChatAnimation) {
                    // ProfileActivity resolves the real centred-header geometry.
                    // A title-cloud tap uses the compact morph: the detached
                    // right-side avatar travels continuously into the profile.
                    fragment.setPlayProfileAnimation(byAvatar ? 2 : 1);
                }
                parentFragment.presentFragment(fragment, removeLast);
            }
        } else if (chat != null) {
            Bundle args = new Bundle();
            args.putLong("chat_id", chat.id);
            if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                args.putLong("topic_id", parentFragment.getSavedDialogId());
            } else if (parentFragment.isTopic) {
                args.putLong("topic_id", parentFragment.getThreadMessage().getId());
            }
            final ProfileActivity fragment = new ProfileActivity(args, sharedMediaPreloader);
            if (!monoforum) {
                fragment.setChatInfo(parentFragment.getCurrentChatInfo());
            }
            if (fromChatAnimation) {
                fragment.setPlayProfileAnimation(byAvatar ? 2 : 1);
            }
            parentFragment.presentFragment(fragment, removeLast);
        }
    }

    public void setOccupyStatusBar(boolean value) {
        occupyStatusBar = value;
    }

    public void setTitleColors(int title, int subtitle) {
        titleTextView.setTextColor(title);
        subtitleTextView.setTextColor(subtitle);
        subtitleTextView.setTag(subtitle);
        updateCommunityIndicatorStyle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // CG-VERBATIM (CG L672-675): re-evaluate centerChatTitle on every
        // measure pass. The constructor may have run when getDialogId() was 0
        // (transient open-chat state), permanently disabling centering via the
        // AND-guard. CG mirrors these exact guards in onMeasure / onLayout /
        // fadeOutToLessWidth — match that so the centered layout actually
        // takes effect once the dialog finishes loading.
        updateCenterChatTitleState();

        // CG-VERBATIM (CG L677-706): no menuOverlap math, no shrink of the
        // reported width. The right-edge avatar slot
        // ((getWidth() - leftPadding) - dp(86) .. - dp(50)) is anchored to the
        // container's own width and the menu-icon gutter is already encoded
        // by ChatActivity's rightMargin on avatarContainer's LayoutParams
        // (40 / 96 dp depending on visible icons). The previous menuOverlap
        // subtraction pulled the avatar inward, manifesting as "avatar sits
        // between middle." Replicate CG byte-for-byte for the centered path.
        // The extra 40dp belongs to ChatActivity's detached right-side avatar
        // slot. Community/forum list headers keep the avatar inside the title
        // island, so growing an AT_MOST spec by those 40dp lets the container
        // run underneath search/overflow.
        final boolean inlineCenteredAvatar = isInlineCenteredAvatar();
        int padding = centerChatTitle && !inlineCenteredAvatar ? dp(40) : 0;
        int width = MeasureSpec.getSize(widthMeasureSpec) + padding + titleTextView.getPaddingRight();
        int availableWidth = width - dp(((avatarImageView.getVisibility() == VISIBLE || centerChatTitle) ? 54 : 0) + 16);
        if (useChatTitleLayoutOutsideChat
                && !inlineCenteredAvatar
                && getParent() instanceof ActionBar) {
            final int islandContentWidth =
                    ((ActionBar) getParent()).getForumChatAvatarContentWidth();
            if (islandContentWidth > 0) {
                // The normal Topics layout keeps its avatar/title left-aligned,
                // but its old fixed right margin only reserved search + overflow.
                // Selection can expose several wider action buttons. Constrain
                // the text to the live glass-island boundary so it ellipsizes
                // before those controls instead of drawing underneath them.
                final int contentInsets = dp(
                        (avatarImageView.getVisibility() == VISIBLE ? 54 : 0) + 16);
                availableWidth = Math.min(
                        availableWidth,
                        Math.max(0, islandContentWidth - contentInsets));
            }
        }
        // Upstream 12.9.0: normal (non-centered) avatar shrinks by 2px (dp(avatarSizeInDp) - 2).
        // The detached ChatActivity avatar occupies a 36dp menu slot.  In
        // Topics/community headers the avatar stays inside the identity island,
        // so keep Telegram's normal header size; shrinking that inline avatar
        // made the profile morph hand off between two different sizes.
        int nmAvatarMeasure = centerChatTitle && !inlineCenteredAvatar
                ? dp(36) : dp(avatarSizeInDp) - 2;
        avatarImageView.measure(MeasureSpec.makeMeasureSpec(nmAvatarMeasure, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(nmAvatarMeasure, MeasureSpec.EXACTLY));
        final int centeredTitleReserve = centerChatTitle && !inlineCenteredAvatar ? dp(60) : 0;
        // The inline community chevron is part of the centred title group. Give
        // it a real width budget so long names ellipsize before the indicator.
        final int inlineCommunityReserve = getInlineCommunityIndicatorSpace();
        final int subtitleAvailableWidth = Math.max(0, availableWidth - centeredTitleReserve);
        // Right-side status drawables are painted outside the text layout.
        // Keep a small trailing inset in every header mode so a premium star
        // cannot touch or cross the rounded glass content boundary.
        final int titleTrailingSafety = titleTextView.getRightDrawableOutside()
                && titleTextView.getRightDrawablesWidth() > 0 ? dp(4) : 0;
        final int titleAvailableWidth = Math.max(
                0, subtitleAvailableWidth - inlineCommunityReserve - titleTrailingSafety);
        int centeredTitleCapacity = titleAvailableWidth;
        int inlineTextCapacity = titleAvailableWidth;
        if (centerChatTitle && getParent() instanceof ActionBar) {
            final int compactContentWidth =
                    ((ActionBar) getParent()).getChatAvatarCompactContentWidth();
            int animatedTextCapacity = compactContentWidth - dp(4) * 2;
            if (inlineCenteredAvatar) {
                animatedTextCapacity -= avatarImageView.getMeasuredWidth() + dp(8);
                if (animatedTextCapacity > 0) {
                    inlineTextCapacity = Math.min(inlineTextCapacity, animatedTextCapacity);
                    centeredTitleCapacity = Math.min(
                            centeredTitleCapacity, inlineTextCapacity);
                }
            } else {
                // The detached-avatar chat header keeps the community arrow
                // beside the actual title. Cap the title itself to the live
                // glass content width, leaving the complete arrow touch slot
                // inside the capsule throughout width animations.
                animatedTextCapacity -= inlineCommunityReserve;
                if (animatedTextCapacity > 0) {
                    centeredTitleCapacity = Math.min(
                            centeredTitleCapacity, animatedTextCapacity);
                }
            }
        }
        titleTextView.measure(MeasureSpec.makeMeasureSpec(titleAvailableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(24 + 8) + titleTextView.getPaddingRight(), MeasureSpec.AT_MOST));
        if (centerChatTitle && titleTextView.getMeasuredWidth() > 0) {
            // SimpleTextView normally consumes the complete AT_MOST width even
            // for a short name. The inline community arrow is laid out from
            // titleTextView.getRight(), while the glass width is content-sized;
            // keeping that oversized invisible box sent the arrow outside the
            // capsule on short channel names. Measure every centred chat title
            // to its real text+badge width, capped by the current glass budget.
            final int exactTitleWidth = Math.max(1, Math.min(centeredTitleCapacity,
                    (int) Math.ceil(getInlineDesiredWidth(titleTextView))));
            if (exactTitleWidth != titleTextView.getMeasuredWidth()) {
                titleTextView.measure(
                        MeasureSpec.makeMeasureSpec(exactTitleWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(dp(24 + 8) + titleTextView.getPaddingRight(), MeasureSpec.AT_MOST));
            }
        }
        if (subtitleTextView != null) {
            subtitleTextView.measure(MeasureSpec.makeMeasureSpec(subtitleAvailableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.AT_MOST));
            if (inlineCenteredAvatar && subtitleTextView.getVisibility() != GONE) {
                // Keep the currently drawn subtitle at its own natural width.
                // inlineSubtitleWidthReserve belongs only to the capsule's
                // destination width; applying it to this still-old text made
                // the child and avatar jump before the glass animation began.
                final int exactSubtitleWidth = Math.max(1, Math.min(inlineTextCapacity,
                        (int) Math.ceil(getInlineDesiredWidth(subtitleTextView))));
                if (exactSubtitleWidth != subtitleTextView.getMeasuredWidth()) {
                    subtitleTextView.measure(
                            MeasureSpec.makeMeasureSpec(exactSubtitleWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.AT_MOST));
                }
            }
        } else if (animatedSubtitleTextView != null) {
            animatedSubtitleTextView.measure(MeasureSpec.makeMeasureSpec(subtitleAvailableWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.AT_MOST));
        }
        if (communityItem != null) {
            final int communityTouchSize = dp(shouldUseInlineCommunityIndicator()
                    ? INLINE_COMMUNITY_TOUCH_SIZE_DP : COMMUNITY_BADGE_TOUCH_SIZE_DP);
            communityItem.measure(
                    MeasureSpec.makeMeasureSpec(communityTouchSize, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(communityTouchSize, MeasureSpec.EXACTLY));
        }
        if (timeItem != null) {
            timeItem.measure(MeasureSpec.makeMeasureSpec(dp(34), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(34), MeasureSpec.EXACTLY));
        }
        if (starBgItem != null) {
            starBgItem.measure(MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY));
        }
        if (starFgItem != null) {
            starFgItem.measure(MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec));
        if (lastWidth != -1 && lastWidth != width && lastWidth > width) {
            fadeOutToLessWidth(lastWidth);
        }
        SimpleTextView titleTextLargerCopyView = this.titleTextLargerCopyView.get();
        if (titleTextLargerCopyView != null) {
            int largerAvailableWidth = largerWidth - dp((avatarImageView.getVisibility() == VISIBLE ? 54 : 0) + 16);
            titleTextLargerCopyView.measure(MeasureSpec.makeMeasureSpec(largerAvailableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(24), MeasureSpec.AT_MOST));
        }
        lastWidth = width;
    }

    private void fadeOutToLessWidth(int largerWidth) {
        // CG-VERBATIM (CG L710-713): mirror the centerChatTitle re-evaluation
        // here too — the fade-out copy is measured against the centered/uncentered
        // gravity, so the field must reflect the current per-context guards.
        updateCenterChatTitleState();

        // The centered header is repositioned as a single unit. Telegram's
        // temporary larger-width text copies are meant for the stock left-aligned
        // header; here they become a second title layer after reconnect/back.
        if (centerChatTitle || useChatTitleLayoutOutsideChat) {
            clearLargerTextCopies();
            return;
        }

        this.largerWidth = largerWidth;
        SimpleTextView titleTextLargerCopyView = this.titleTextLargerCopyView.get();
        if (titleTextLargerCopyView != null) {
            removeView(titleTextLargerCopyView);
        }
        titleTextLargerCopyView = new SimpleTextView(getContext());
        this.titleTextLargerCopyView.set(titleTextLargerCopyView);
        titleTextLargerCopyView.setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
        titleTextLargerCopyView.setTextSizePx(dp(glassMode ? 17.5f : 18));
        // NimarkoGram: keep gravity consistent with the live titleTextView so the
        // cross-fade copy created on setTitle change does not visibly jump from
        // centered to left-aligned when centerChatTitle is enabled (CG parity).
        titleTextLargerCopyView.setGravity(centerChatTitle ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
        titleTextLargerCopyView.setTypeface(AndroidUtilities.bold());
        titleTextLargerCopyView.setLeftDrawableTopPadding(-dp(1.3f));
        titleTextLargerCopyView.setRightDrawable(titleTextView.getRightDrawable());
        titleTextLargerCopyView.setRightDrawable2(titleTextView.getRightDrawable2());
        titleTextLargerCopyView.setRightDrawableOutside(titleTextView.getRightDrawableOutside());
        titleTextLargerCopyView.setCanHideRightDrawable(false);
        titleTextLargerCopyView.setLeftDrawable(titleTextView.getLeftDrawable());
        titleTextLargerCopyView.setText(titleTextView.getText());
        titleTextLargerCopyView.animate().alpha(0).setDuration(350).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() -> {
            SimpleTextView titleTextLargerCopyView2 = this.titleTextLargerCopyView.get();
            if (titleTextLargerCopyView2 != null) {
                removeView(titleTextLargerCopyView2);
                this.titleTextLargerCopyView.set(null);
            }
        }).start();
        addView(titleTextLargerCopyView);

        SimpleTextView subtitleTextLargerCopyView = this.subtitleTextLargerCopyView.get();
        if (subtitleTextLargerCopyView != null) {
            removeView(subtitleTextLargerCopyView);
        }
        subtitleTextLargerCopyView = new SimpleTextView(getContext());
        this.subtitleTextLargerCopyView.set(subtitleTextLargerCopyView);
        subtitleTextLargerCopyView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        subtitleTextLargerCopyView.setTag(Theme.key_actionBarDefaultSubtitle);
        subtitleTextLargerCopyView.setTextSizePx(dp(glassMode ? 13.5f : 14));
        // NimarkoGram: same fix as titleTextLargerCopyView — gravity must mirror
        // the live subtitle so centerChatTitle works across the fade animation.
        subtitleTextLargerCopyView.setGravity(centerChatTitle ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
        if (subtitleTextView != null) {
            subtitleTextLargerCopyView.setText(subtitleTextView.getText());
        } else if (animatedSubtitleTextView != null) {
            subtitleTextLargerCopyView.setText(animatedSubtitleTextView.getText());
        }
        subtitleTextLargerCopyView.animate().alpha(0).setDuration(350).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() -> {
            SimpleTextView subtitleTextLargerCopyView2 = this.subtitleTextLargerCopyView.get();
            if (subtitleTextLargerCopyView2 != null) {
                removeView(subtitleTextLargerCopyView2);
                this.subtitleTextLargerCopyView.set(null);
                if (!allowDrawStories) {
                    setClipChildren(true);
                }
            }
        }).start();
        addView(subtitleTextLargerCopyView);

        setClipChildren(false);
    }

    private void clearLargerTextCopies() {
        SimpleTextView titleCopy = titleTextLargerCopyView.getAndSet(null);
        if (titleCopy != null) {
            titleCopy.animate().cancel();
            removeView(titleCopy);
        }
        SimpleTextView subtitleCopy = subtitleTextLargerCopyView.getAndSet(null);
        if (subtitleCopy != null) {
            subtitleCopy.animate().cancel();
            removeView(subtitleCopy);
        }
        if (!allowDrawStories) {
            setClipChildren(true);
        }
    }

    private boolean glassMode;
    public void setGlassMode() {
        if (titleTextView != null) {
            titleTextView.setTextSizePx(dp(17.5f));
            // The compact glass title is measured to its exact text + badge
            // width. SimpleTextView's legacy static-drawable clip inset would
            // therefore cut the last pixels of a nickname before the Premium
            // star even though the full drawable slot and its 4dp gap are
            // already reserved by measurement.
            titleTextView.setOutsideRightDrawableTextClipInset(0);
        }
        if (subtitleTextView != null) {
            subtitleTextView.setTextSizePx(dp(13.5f));
        }
        glassMode = true;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // CG-VERBATIM (CG L773-776): re-evaluate centerChatTitle each layout pass.
        updateCenterChatTitleState();
        inlineTextClipEnabled = false;

        final int actionBarHeight = ActionBar.getCurrentActionBarHeight();
        final int viewTop = (actionBarHeight - avatarImageView.getMeasuredHeight() - 2) / 2 + (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0);
        final int subtitleTop = viewTop + dp(glassMode ? 23.66f : 24);

        // CG-VERBATIM (CG L780-784): centered → avatar pinned to right edge
        // (36 dp box, inset by leftPadding+50dp from container's right).
        // Non-centered → standard avatar at leftPadding (measured size).
        if (centerChatTitle && !isInlineCenteredAvatar()) {
            // NimarkoGram: REALLY center the avatar on the headerItem (3-dots) circle it visually replaces, by
            // reading the headerItem's actual on-screen position at layout time. Exact for glass AND non-glass and
            // any device/density — no hardcoded dp guess. Falls back to a static estimate only if the menu hasn't
            // been laid out yet (first frame); the next layout pass snaps it to the real position.
            int cx = resolveCenteredAvatarCx();
            nmCenteredAvatarCx = cx;
            avatarImageView.layout(cx - dp(18), viewTop + 1, cx + dp(18), dp(36) + viewTop + 1);
        } else {
            // Upstream 12.9.0: non-centered avatar layout inset by +1px on both axes.
            avatarImageView.layout(1 + leftPadding, 1 + viewTop, 1 + leftPadding + avatarImageView.getMeasuredWidth(), 1 + viewTop + avatarImageView.getMeasuredHeight());
        }
        // CG-VERBATIM (CG L785): when avatar visible AND not centered → base inset,
        // otherwise the collapsed inset. Same formula CG uses; merged with upstream glassMode insets.
        // Non-glass values follow upstream 12.9.0 (55 / 1); NG keeps its tuned glass-mode insets.
        int l = leftPadding + (avatarImageView.getVisibility() == VISIBLE && !centerChatTitle ? dp(glassMode ? 48.66f : 55) : dp(glassMode ? 12 : 1)) + rightAvatarPadding;
        // NG: in centered glass mode the header draws a glass "oval" around the name+status (ActionBar.dispatchDraw).
        // Centre the title AND subtitle on the OVAL's centre instead of the fixed left offset `l`, else the content
        // sits in the left part of the wide oval and reads as "not centred / crooked". Oval centre (container-local)
        // = ovalWidth/2 + leftPadding - dp9 (derived from the translationX that aligns the container with the oval).
        // ovalCenterLocal < 0 => no oval (non-glass / not centered) → keep the legacy left anchor unchanged.
        float ovalCenterLocal = -1f;
        int compactContentWidth = 0;
        if (centerChatTitle && getParent() instanceof ActionBar) {
            ActionBar parentActionBar = (ActionBar) getParent();
            // ActionBar owns the glass geometry and applies the matching
            // container translation before returning this local centre.  This
            // avoids reconstructing it from width plus magic padding values.
            ovalCenterLocal = parentActionBar.getChatAvatarOvalCenterInContainer(this);
            compactContentWidth = parentActionBar.getChatAvatarCompactContentWidth();
            if (Float.isNaN(ovalCenterLocal)) {
                // First-frame/non-glass fallback.
                ovalCenterLocal = parentActionBar.getWidth() / 2f - getX();
            }
        }
        final float ovalCenter = ovalCenterLocal;
        float textColumnCenter = ovalCenter;
        if (centerChatTitle && ovalCenter >= 0 && compactContentWidth > 0) {
            // Clip every compact centred header to the same live content bounds
            // that ActionBar uses for its animated capsule. ChatActivity used to
            // move only the glass while the unconstrained text remained at the
            // old geometry and then jumped on the next unrelated layout pass.
            final int contentInset = dp(4);
            inlineTextClipLeft = Math.round(
                    ovalCenter - compactContentWidth / 2f) + contentInset;
            inlineTextClipRight = Math.round(
                    ovalCenter + compactContentWidth / 2f) - contentInset;
            inlineTextClipEnabled = inlineTextClipRight > inlineTextClipLeft;
        }
        int titleL = ovalCenter < 0 ? l : Math.round(ovalCenter - titleTextView.getMeasuredWidth() / 2f);
        SimpleTextView titleTextLargerCopyView = this.titleTextLargerCopyView.get();
        int titleCopyL = (ovalCenter < 0 || titleTextLargerCopyView == null) ? l : Math.round(ovalCenter - titleTextLargerCopyView.getMeasuredWidth() / 2f);
        if (isInlineCenteredAvatar() && avatarImageView.getVisibility() == VISIBLE && ovalCenter >= 0) {
            final int gap = dp(8);
            final int avatarWidth = avatarImageView.getMeasuredWidth();
            final int titleWidth = titleTextView.getMeasuredWidth();
            final int subtitleWidth;
            if (subtitleTextView != null && subtitleTextView.getVisibility() != GONE) {
                subtitleWidth = subtitleTextView.getMeasuredWidth();
            } else if (animatedSubtitleTextView != null && animatedSubtitleTextView.getVisibility() != GONE) {
                subtitleWidth = animatedSubtitleTextView.getMeasuredWidth();
            } else {
                subtitleWidth = 0;
            }
            final int naturalTextColumnWidth = Math.max(titleWidth, subtitleWidth);
            // Anchor the complete identity row to the CURRENT animated glass
            // width, not to the destination subtitle width.  The old code
            // widened this group immediately for "N members" while the
            // capsule was still animating, so the avatar teleported sideways.
            final int contentInset = dp(4);
            final int naturalGroupWidth = avatarWidth + gap + naturalTextColumnWidth;
            final int animatedGroupWidth = compactContentWidth - contentInset * 2;
            final int groupWidth = animatedGroupWidth >= avatarWidth + gap + 1
                    ? animatedGroupWidth : naturalGroupWidth;
            final int textColumnWidth = Math.max(1, groupWidth - avatarWidth - gap);
            final int groupLeft = Math.round(ovalCenter - groupWidth / 2f);
            final int avatarLeft;
            final int textColumnLeft;
            if (LocaleController.isRTL) {
                textColumnLeft = groupLeft;
                avatarLeft = textColumnLeft + textColumnWidth + gap;
            } else {
                avatarLeft = groupLeft;
                textColumnLeft = avatarLeft + avatarWidth + gap;
            }
            titleL = textColumnLeft + (textColumnWidth - titleWidth) / 2;
            nmCenteredAvatarCx = avatarLeft + avatarWidth / 2;
            textColumnCenter = textColumnLeft + textColumnWidth / 2f;
            inlineTextClipLeft = textColumnLeft;
            inlineTextClipRight = textColumnLeft + textColumnWidth;
            inlineTextClipEnabled = inlineTextClipRight > inlineTextClipLeft;
            avatarImageView.layout(
                    avatarLeft,
                    viewTop + 1,
                    avatarLeft + avatarWidth,
                    viewTop + 1 + avatarImageView.getMeasuredHeight());
            if (titleTextLargerCopyView != null) {
                final int copyWidth = titleTextLargerCopyView.getMeasuredWidth();
                titleCopyL = textColumnLeft + (textColumnWidth - copyWidth) / 2;
            }
        }
        final int inlineCommunityVisualAdvance =
                getInlineCommunityIndicatorVisualAdvance();
        if (inlineCommunityVisualAdvance > 0 && ovalCenter >= 0) {
            // Centre the visible [name + chevron] group. The arrow lives in a
            // larger touch target, whose invisible outer area must not skew
            // the optical centre. The trailing side naturally mirrors in RTL.
            final int opticalOffset =
                    Math.round(inlineCommunityVisualAdvance / 2f);
            titleL += LocaleController.isRTL ? opticalOffset : -opticalOffset;
            if (titleTextLargerCopyView != null) {
                titleCopyL += LocaleController.isRTL
                        ? opticalOffset : -opticalOffset;
            }
        }
        if (inlineTextClipEnabled) {
            // The visible title unit includes every trailing badge (Premium,
            // emoji status, verification and Nimarko badge).  Optical centring
            // for the community arrow and fractional glass-width animation can
            // otherwise move the whole view one or more pixels past the live
            // capsule.  Clamp the complete measured unit, not the text alone,
            // so RTL and the final animation frame obey the same bounds.
            final int maxTitleLeft = inlineTextClipRight - titleTextView.getMeasuredWidth();
            titleL = maxTitleLeft >= inlineTextClipLeft
                    ? Math.max(inlineTextClipLeft, Math.min(titleL, maxTitleLeft))
                    : inlineTextClipLeft;
            if (titleTextLargerCopyView != null) {
                final int maxCopyLeft = inlineTextClipRight
                        - titleTextLargerCopyView.getMeasuredWidth();
                titleCopyL = maxCopyLeft >= inlineTextClipLeft
                        ? Math.max(inlineTextClipLeft, Math.min(titleCopyL, maxCopyLeft))
                        : inlineTextClipLeft;
            }
        }
        if (getSubtitleTextView().getVisibility() != GONE) {
            titleTextView.layout(titleL, viewTop + dp(1.66f) - titleTextView.getPaddingTop(), titleL + titleTextView.getMeasuredWidth(), viewTop + titleTextView.getTextHeight() + dp(1.66f) - titleTextView.getPaddingTop() + titleTextView.getPaddingBottom());
            if (titleTextLargerCopyView != null) {
                titleTextLargerCopyView.layout(titleCopyL, viewTop + dp(1.66f), titleCopyL + titleTextLargerCopyView.getMeasuredWidth(), viewTop + titleTextLargerCopyView.getTextHeight() + dp(1.66f));
            }
        } else {
            // NG: when the status is hidden the title is centered; nudge it up ~2dp so it sits
            // a touch higher in the bar (user-tuned) instead of slightly low at dead-center.
            int titleTop = app.nimarkogram.messenger.NimarkoConfig.hideActionBarStatus ? dp(9) : dp(11);
            titleTextView.layout(titleL, viewTop + titleTop - titleTextView.getPaddingTop(), titleL + titleTextView.getMeasuredWidth(), viewTop + titleTextView.getTextHeight() + titleTop - titleTextView.getPaddingTop() + titleTextView.getPaddingBottom());
            if (titleTextLargerCopyView != null) {
                titleTextLargerCopyView.layout(titleCopyL, viewTop + titleTop, titleCopyL + titleTextLargerCopyView.getMeasuredWidth(), viewTop + titleTextLargerCopyView.getTextHeight() + titleTop);
            }
        }
        if (communityItem != null) {
            final int communityLeft;
            final int communityTop;
            if (shouldUseInlineCommunityIndicator()) {
                final int gap = dp(INLINE_COMMUNITY_GAP_DP);
                communityLeft = LocaleController.isRTL
                        ? titleTextView.getLeft() - gap - communityItem.getMeasuredWidth()
                        : titleTextView.getRight() + gap;
                final int titleTextCenterY = titleTextView.getTop()
                        + titleTextView.getPaddingTop()
                        + titleTextView.getTextHeight() / 2;
                communityTop = titleTextCenterY - communityItem.getMeasuredHeight() / 2;
            } else if (centerChatTitle) {
                // Preserve the original 14dp badge centre while giving it a
                // larger independent touch target.
                final int communityCenterX = avatarImageView.getRight() - dp(5);
                final int communityCenterY = Math.round(avatarImageView.getBottom() - dpf2(6.67f));
                communityLeft = communityCenterX - communityItem.getMeasuredWidth() / 2;
                communityTop = communityCenterY - communityItem.getMeasuredHeight() / 2;
            } else {
                final int communityCenterX = leftPadding + dp(36);
                final int communityCenterY = viewTop + Math.round(dpf2(34.33f));
                communityLeft = communityCenterX - communityItem.getMeasuredWidth() / 2;
                communityTop = communityCenterY - communityItem.getMeasuredHeight() / 2;
            }
            communityItem.layout(
                communityLeft,
                communityTop,
                communityLeft + communityItem.getMeasuredWidth(),
                communityTop + communityItem.getMeasuredHeight());
        }
        if (timeItem != null) {
            // CG L799-803: timeItem follows the avatar — left edge when default,
            // right edge when title is centered.
            if (centerChatTitle) {
                timeItem.layout(nmCenteredAvatarCx + dp(8), dp(5) + viewTop, nmCenteredAvatarCx + dp(42), viewTop + dp(15 + 34));   // NimarkoGram: TTL badge follows the (dynamically centered) avatar's lower-right corner
            } else {
                // Upstream 12.9.0: repositioned TTL badge (dp(19.333f) left, viewTop - dp(8)).
                timeItem.layout(
                    leftPadding + dp(19.333f),
                    viewTop - dp(8),
                    leftPadding + dp(19.333f) + timeItem.getMeasuredWidth(),
                    viewTop - dp(8) + timeItem.getMeasuredHeight()
                );
            }
        }
        if (starBgItem != null) {
            final int starLeft = centerChatTitle ? avatarImageView.getRight() - dp(8) : leftPadding + dp(28);
            final int starTop = centerChatTitle ? avatarImageView.getTop() + dp(23) : viewTop + dp(24);
            starBgItem.layout(starLeft, starTop, starLeft + starBgItem.getMeasuredWidth(), starTop + starBgItem.getMeasuredHeight());
        }
        if (starFgItem != null) {
            final int starLeft = centerChatTitle ? avatarImageView.getRight() - dp(8) : leftPadding + dp(28);
            final int starTop = centerChatTitle ? avatarImageView.getTop() + dp(23) : viewTop + dp(24);
            starFgItem.layout(starLeft, starTop, starLeft + starFgItem.getMeasuredWidth(), starTop + starFgItem.getMeasuredHeight());
        }
        if (subtitleTextView != null) {
            int subtitleL = textColumnCenter < 0 ? l : Math.round(textColumnCenter - subtitleTextView.getMeasuredWidth() / 2f);
            subtitleTextView.layout(subtitleL, subtitleTop, subtitleL + subtitleTextView.getMeasuredWidth(), subtitleTop + subtitleTextView.getTextHeight());
        } else if (animatedSubtitleTextView != null) {
            int subtitleL = textColumnCenter < 0 ? l : Math.round(textColumnCenter - animatedSubtitleTextView.getMeasuredWidth() / 2f);
            animatedSubtitleTextView.layout(subtitleL, subtitleTop, subtitleL + animatedSubtitleTextView.getMeasuredWidth(), subtitleTop + animatedSubtitleTextView.getTextHeight());
        }
        SimpleTextView subtitleTextLargerCopyView = this.subtitleTextLargerCopyView.get();
        if (subtitleTextLargerCopyView != null) {
            int subtitleCopyL = textColumnCenter < 0 ? l : Math.round(textColumnCenter - subtitleTextLargerCopyView.getMeasuredWidth() / 2f);
            subtitleTextLargerCopyView.layout(subtitleCopyL, subtitleTop, subtitleCopyL + subtitleTextLargerCopyView.getMeasuredWidth(), subtitleTop + subtitleTextLargerCopyView.getTextHeight());
        }
        syncCenteredAvatarAnchor();
    }

    public void setLeftPadding(int value) {
        leftPadding = value;
    }

    public int getLeftPadding() {
        return leftPadding;
    }

    public void setRightAvatarPadding(int value) {
        rightAvatarPadding = value;
    }

    public void setCommunityItemVisible(boolean visible) {
        if (communityItem != null) {
            final int newVisibility = visible && !avatarImageIsHidden ? VISIBLE : GONE;
            if (communityItem.getVisibility() != newVisibility) {
                communityItem.setVisibility(newVisibility);
                updateCommunityIndicatorStyle();
                requestLayout();
                checkActionBar(true);
            } else {
                updateCommunityIndicatorStyle();
            }
        }
    }


    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_TIME_ITEM_VISIBLE) {
            if (timeItem != null) {
                timeItem.setAlpha(factor);
                timeItem.setScaleX(factor * 0.85f);
                timeItem.setScaleY(factor * 0.85f);
                timeItem.setVisibility(factor > 0 ? VISIBLE : GONE);
            }
        }
    }


    public void showTimeItem(boolean animated) {
        animatorTimeVisible.setValue(true, animated);
    }

    public void hideTimeItem(boolean animated) {
        animatorTimeVisible.setValue(false, animated);
    }

    public void setTime(int value, boolean animated) {
        if (timerDrawable == null) {
            return;
        }
        boolean show = !stars;
        if (value == 0 && !secretChatTimer) {
            show = false;
            return;
        }
        if (show) {
            showTimeItem(animated);
            timerDrawable.setTime(value);
        } else {
            hideTimeItem(animated);
        }
    }

    public boolean stars;
    public void setStars(boolean stars, boolean animated) {
        if (starBgItem == null || starFgItem == null) return;
        this.stars = stars;
        if (!animated) {
            starBgItem.setVisibility(stars ? VISIBLE : INVISIBLE);
            starBgItem.setAlpha(stars ? 1f : 0f);
            starBgItem.setScaleX(stars ? 1.1f : 0f);
            starBgItem.setScaleY(stars ? 1.1f : 0f);
            starFgItem.setVisibility(stars ? VISIBLE : INVISIBLE);
            starFgItem.setAlpha(stars ? 1f : 0f);
            starFgItem.setScaleX(stars ? 1f : 0f);
            starFgItem.setScaleY(stars ? 1f : 0f);
        } else {
            if (stars) {
                starBgItem.setVisibility(VISIBLE);
                starFgItem.setVisibility(VISIBLE);
            }
            starBgItem.animate().alpha(stars ? 1f : 0f).scaleX(stars ? 1.1f : 0f).scaleY(stars ? 1.1f : 0f).withEndAction(() -> {
                if (!stars) {
                    starBgItem.setVisibility(INVISIBLE);
                }
            }).start();
            starFgItem.animate().alpha(stars ? 1f : 0f).scaleX(stars ? 1f : 0f).scaleY(stars ? 1f : 0f).withEndAction(() -> {
                if (!stars) {
                    starFgItem.setVisibility(INVISIBLE);
                }
            }).start();
        }
    }

    private boolean rightDrawableIsScamOrVerified = false;
    private boolean rightDrawableIsScam = false;
    private String rightDrawableContentDescription = null;
    private String rightDrawable2ContentDescription = null;

    public void setTitleIcons(Drawable leftIcon, Drawable mutedIcon) {
        titleTextView.setLeftDrawable(leftIcon);
        // NG: never show the muted bell in the chat header (slot 2). User
        // reported a brief flash when opening a chat from search — setTitleIcons
        // fired with mutedIcon != null before applyNimarkoBadge ran, the bell
        // appeared for a frame and then vanished. The bell also clobbered NG
        // badges already in slot 2. Mute status is still visible to the user
        // via the chat-list cell mute icon and notification settings.
        checkActionBar(true);
    }

    // NG: tracks whether slot 2 currently holds our badge (so setTitleIcons
    // doesn't overwrite it with the mute bell). Updated in setTitle below.
    private boolean rightDrawable2IsBadge = false;

    public AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable getBotVerificationDrawable(long icon, boolean animated) {
        if (icon == 0) {
            return null;
        }
        botVerificationDrawable.set(icon, animated);
        botVerificationDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
        botVerificationDrawable.offset(0, dp(1));
        return botVerificationDrawable;
    }

    public void setTitle(CharSequence value) {
        setTitle(value, false, false, false, false, null, false);
    }

    public void setTitle(CharSequence value, boolean scam, boolean fake, boolean verified, boolean premium, TLRPC.EmojiStatus emojiStatus, boolean animated) {
        // NimarkoGram slot layout (CG-derived, badge guaranteed visible):
        //   slot 1 (rightDrawable)
        //     emoji_status              (highest)
        //     premium + badge           → badge replaces default star
        //     premium                   → default premium-star
        //     non-premium + badge       → badge
        //     else                      → null
        //   slot 2 (rightDrawable2)
        //     scam / fake               (highest)
        //     emoji_status + badge      → badge (kicks verified, since badge
        //                                 cannot ride in slot 1 there)
        //     verified                  → verified-check
        //     else                      → null
        //   muted bell stays in slot 2 via setTitleIcons() (CG L908-918);
        //   it is fed only when rightDrawableIsScamOrVerified == false, so the
        //   slot-2 badge path keeps that flag false to preserve mute-icon flow.
        if (value != null) {
            value = Emoji.replaceEmoji(value, titleTextView.getPaint().getFontMetricsInt(), false);
        }
        titleTextView.setText(value);

        // NG: disablePremiumStatuses kills emoji-status display entirely (CG-parity).
        if (app.nimarkogram.messenger.NimarkoConfig.disablePremiumStatuses) {
            emojiStatus = null;
            premium = false;
        }

        // NG: in Saved Messages (self chat) don't show our own emoji status /
        // premium star — the user doesn't want to see their own premium emoji
        // in their own Saved Messages header. The NG badge is still allowed
        // through below (plugins inject badges at the self id).
        boolean isSelfChat = parentFragment != null
                && parentFragment.getCurrentUser() != null
                && UserObject.isUserSelf(parentFragment.getCurrentUser());
        if (isSelfChat) {
            emojiStatus = null;
            premium = false;
        }

        // Lookup NimarkoGram badge for the dialog owner.
        app.nimarkogram.messenger.api.dto.BadgeDTO badge = null;
        try {
            org.telegram.tgnet.TLObject target = null;
            if (parentFragment != null) {
                if (parentFragment.getCurrentUser() != null) target = parentFragment.getCurrentUser();
                else if (parentFragment.getCurrentChat() != null) target = parentFragment.getCurrentChat();
            }
            if (target == null) {
                target = headerIdentityTarget;
            }
            // NG: show the badge in Saved Messages too — plugins
            // (NimarkoAchievements streaks, etc.) inject badges at the user's
            // own id and need to be visible on the Saved Messages header.
            if (target != null) {
                badge = app.nimarkogram.messenger.badges.BadgesController.getInstance().i(target);
                if (badge != null && badge.getDocumentId() == 0L) badge = null;
            }
        } catch (Throwable ignored) {}

        if (badge != null) {
            if (badgeEmojiDrawable == null) {
                badgeEmojiDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(titleTextView, dp(24));
                if (isAttachedToWindow()) badgeEmojiDrawable.attach();
            }
            boolean animateBadge = animated && lastNimarkoBadgeDocId != 0L && lastNimarkoBadgeDocId != badge.getDocumentId();
            badgeEmojiDrawable.set(badge.getDocumentId(), animateBadge);
            badgeEmojiDrawable.setParticles(true, false);
            badgeEmojiDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
            lastNimarkoBadgeDocId = badge.getDocumentId();
            // NG: the bulletin click is wired AFTER slot placement (below) so it
            // lands on whichever slot the badge actually occupies — tapping the
            // BADGE shows the bulletin, not the emoji status next to it.
        } else {
            lastNimarkoBadgeDocId = 0L;
            if (badgeEmojiDrawable != null) {
                badgeEmojiDrawable.set((Drawable) null, false);
                badgeEmojiDrawable.setParticles(false, false);
            }
        }
        CharSequence badgeAccessibilityDescription = badge == null || TextUtils.isEmpty(badge.getText())
                ? LocaleController.getString(R.string.NM_ProfileBadge)
                : LocaleController.getString(R.string.NM_ProfileBadge) + ": " + badge.getText();

        boolean emojiStatusPresent = DialogObject.getEmojiStatusDocumentId(emojiStatus) != 0;

        // NG slot-2 priority (CG L944-965 + NG badge fallback):
        //   scam/fake  > verified  > badge (when slot 1 is occupied by emoji_status)
        // Slot 2 hosts the badge ONLY when emoji_status is present AND the badge
        // cannot ride along in slot 1 (slot 1 is the custom status). In that case
        // the badge wins over verified (badge is more interesting) but never over
        // scam/fake.
        rightDrawableContentDescription = null;
        rightDrawable2ContentDescription = null;
        titleTextView.setRightDrawableTopPadding(0);
        boolean badgeInSlot2 = badge != null && emojiStatusPresent;
        rightDrawableIsScam = false;
        if (scam || fake) {
            rightDrawableIsScam = true;
            rightDrawable2IsBadge = false;
            if (!(titleTextView.getRightDrawable2() instanceof ScamDrawable)) {
                ScamDrawable sd = new ScamDrawable(11, scam ? 0 : 1);
                sd.setColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
                titleTextView.setRightDrawable2(sd);
                rightDrawable2ContentDescription = LocaleController.getString(R.string.ScamMessage);
                rightDrawableIsScamOrVerified = true;
            }
        } else if (badgeInSlot2) {
            // NG: emoji_status + badge → badge takes slot 2 (overrides verified).
            titleTextView.setRightDrawable2(badgeEmojiDrawable);
            rightDrawableIsScamOrVerified = false;
            rightDrawable2IsBadge = true;
            rightDrawable2ContentDescription = badgeAccessibilityDescription.toString();
        } else if (verified) {
            verifiedBackground = getResources().getDrawable(R.drawable.verified_area).mutate();
            verifiedBackground.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
            verifiedCheck = getResources().getDrawable(R.drawable.verified_check).mutate();
            verifiedCheck.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedCheck), PorterDuff.Mode.MULTIPLY));
            Drawable verifiedDrawable = new CombinedDrawable(verifiedBackground, verifiedCheck);
            titleTextView.setRightDrawable2(verifiedDrawable);
            rightDrawableIsScamOrVerified = true;
            rightDrawable2IsBadge = false;
            rightDrawable2ContentDescription = LocaleController.getString(R.string.AccDescrVerified);
        } else {
            // No slot-2 content — clear whatever was there (scam drawable, stale badge).
            titleTextView.setRightDrawable2(null);
            rightDrawableIsScamOrVerified = false;
            rightDrawable2IsBadge = false;
        }

        // NG slot-1 priority:
        //   emoji_status > (premium + badge → badge replaces star) > premium-star > badge (non-premium) > null
        Drawable primaryTitleDrawable = null;
        if ((premium || emojiStatusPresent) && !app.nimarkogram.messenger.NimarkoConfig.disablePremiumStatuses) {
            if (titleTextView.getRightDrawable() instanceof AnimatedEmojiDrawable.WrapSizeDrawable
                    && ((AnimatedEmojiDrawable.WrapSizeDrawable) titleTextView.getRightDrawable()).getDrawable() instanceof AnimatedEmojiDrawable) {
                ((AnimatedEmojiDrawable) ((AnimatedEmojiDrawable.WrapSizeDrawable) titleTextView.getRightDrawable()).getDrawable()).removeView(titleTextView);
            }
            if (emojiStatusPresent) {
                emojiStatusDrawable.set(DialogObject.getEmojiStatusDocumentId(emojiStatus), animated);
                primaryTitleDrawable = emojiStatusDrawable;
            } else if (premium && badge != null) {
                // NG: premium without custom emoji_status + has badge → show badge
                // instead of the boring default premium-star.
                emojiStatusDrawable.set(badgeEmojiDrawable, animated);
                primaryTitleDrawable = emojiStatusDrawable;
            } else if (premium) {
                // Telegram uses the compact list star in every chat header.
                // It is a real 14dp drawable. Do not put it into the 24dp
                // animated-emoji slot: that leaves a transparent 10dp tail on
                // the right, shifts the centred title group by 5dp, and creates
                // an oversized gap before slot 2. Custom emoji and Nimarko
                // badges still use the full 24dp swap drawable above.
                emojiStatusDefaultDrawable = ContextCompat.getDrawable(
                        ApplicationLoader.applicationContext,
                        R.drawable.msg_premium_liststar
                ).mutate();
                emojiStatusDefaultDrawable.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
                emojiStatusDrawable.set((Drawable) null, false);
                primaryTitleDrawable = emojiStatusDefaultDrawable;
            } else {
                emojiStatusDrawable.set((Drawable) null, animated);
            }
            emojiStatusDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
            titleTextView.setRightDrawable(primaryTitleDrawable);
            rightDrawableIsScamOrVerified = false;
            rightDrawableContentDescription = badge != null && !emojiStatusPresent
                    ? badgeAccessibilityDescription.toString()
                    : LocaleController.getString(R.string.AccDescrPremium);
        } else if (badge != null) {
            // Non-premium user with a NG badge — show badge in slot 1.
            titleTextView.setRightDrawable(badgeEmojiDrawable);
            rightDrawableContentDescription = badgeAccessibilityDescription.toString();
        } else {
            titleTextView.setRightDrawable(null);
            rightDrawableContentDescription = null;
        }

        // NG: wire the badge-bulletin click onto the slot the badge ended up in.
        //   - badge in slot 2 (premium user w/ emoji_status) → tap slot 2.
        //   - badge in slot 1 (non-premium / premium-no-status / self) → tap slot 1.
        // Clear the other slot's listener so tapping the emoji status does
        // nothing (user wants the bulletin only on the badge itself).
        boolean badgeInSlot1 = badge != null && !emojiStatusPresent;
        final app.nimarkogram.messenger.api.dto.BadgeDTO renderedBadge =
                badgeInSlot1 || rightDrawable2IsBadge ? badge : null;
        currentNimarkoBadge = renderedBadge;
        if (renderedBadge != null) {
            final app.nimarkogram.messenger.api.dto.BadgeDTO finalBadge = renderedBadge;
            if (rightDrawable2IsBadge) {
                titleTextView.setRightDrawable2OnClick(v -> showNimarkoBadgeBulletin(finalBadge));
                titleTextView.setRightDrawableOnClick(null);
            } else {
                titleTextView.setRightDrawableOnClick(v -> showNimarkoBadgeBulletin(finalBadge));
                titleTextView.setRightDrawable2OnClick(null);
            }
        } else {
            titleTextView.setRightDrawableOnClick(null);
            titleTextView.setRightDrawable2OnClick(null);
        }
        // NimarkoGram: SimpleTextView.setRightDrawable{,2} only rebuilds the
        // StaticLayout via recreateLayoutMaybe() — it does NOT requestLayout
        // upward. Without a full re-measure, the parent container's onMeasure
        // never re-runs, so the avatarContainer width / menu-overlap math
        // (above, in onMeasure) stays stale until something else triggers a
        // layout pass. Force the parent re-measure so the right-drawable
        // intrinsic widths actually participate in the menu-overlap budget.
        requestLayout();
        // Topics refreshes the same title together with asynchronously loaded
        // chat info and passes animated=false.  Forcing the width animator here
        // snapped an in-flight subtitle expansion to its end.  Once the inline
        // header is on screen, always retarget it smoothly.
        checkActionBar(animated);
    }

    // NG: refresh handler for badge cache updates. Now that badge lives in
    // slot 1 (CG-parity layout) rather than slot 2, we cannot just write
    // the drawable directly — we have to redo the slot-1 priority decision
    // (emoji_status > premium-star > badge > null). The simplest correct
    // implementation is to re-invoke setTitle with the current state.
    private void applyNimarkoBadge(boolean animated) {
        try {
            if (parentFragment == null) return;
            TLRPC.User user = parentFragment.getCurrentUser();
            TLRPC.Chat chat = parentFragment.getCurrentChat();
            boolean premium = user != null && user.premium;
            TLRPC.EmojiStatus emojiStatus = null;
            if (user != null) emojiStatus = user.emoji_status;
            else if (chat != null) emojiStatus = chat.emoji_status;
            boolean verified = (user != null && user.verified) || (chat != null && chat.verified);
            boolean scam = (user != null && user.scam) || (chat != null && chat.scam);
            boolean fake = (user != null && user.fake) || (chat != null && chat.fake);
            setTitle(titleTextView.getText(), scam, fake, verified, premium, emojiStatus, animated);
        } catch (Throwable ignored) {}
    }

    private long lastNimarkoBadgeDocId = 0L;

    // NimarkoGram: tap-to-info bulletin for the badge — matches extera's
    // BulletinFactory.createEmojiBulletin behaviour.
    private void showNimarkoBadgeBulletin(app.nimarkogram.messenger.api.dto.BadgeDTO badge) {
        try {
            if (badge == null || parentFragment == null) return;
            CharSequence rawText = badge.getText();
            // Keep touch and accessibility actions functional for text-less badges
            // without restoring the old upstream brand fallback.
            final CharSequence text = TextUtils.isEmpty(rawText)
                    ? LocaleController.getString(R.string.NM_ProfileBadge) : rawText;
            final long docId = badge.getDocumentId();
            org.telegram.tgnet.TLRPC.Document cached =
                    org.telegram.ui.Components.AnimatedEmojiDrawable.findDocument(currentAccount, docId);
            if (cached != null) {
                showBulletinForDoc(cached, text);
                return;
            }
            // Plugin's custom emoji not in cache yet → async fetch then show.
            // Same pattern Telegram uses in SetupEmojiStatusSheet for custom emoji.
            org.telegram.ui.Components.AnimatedEmojiDrawable
                    .getDocumentFetcher(currentAccount)
                    .fetchDocument(docId, d -> {
                        if (d == null) return;
                        AndroidUtilities.runOnUIThread(() -> showBulletinForDoc(d, text));
                    });
        } catch (Throwable ignored) {}
    }

    private void showBulletinForDoc(org.telegram.tgnet.TLRPC.Document doc, CharSequence text) {
        try {
            if (doc == null || parentFragment == null) return;
            org.telegram.ui.Components.Bulletin b = org.telegram.ui.Components.BulletinFactory
                    .of(parentFragment)
                    .createEmojiBulletin(doc, text);
            try {
                if (b.getLayout() instanceof org.telegram.ui.Components.Bulletin.LottieLayout) {
                    org.telegram.ui.Components.RLottieImageView iv =
                            ((org.telegram.ui.Components.Bulletin.LottieLayout) b.getLayout()).imageView;
                    if (iv.getImageReceiver() != null) {
                        iv.getImageReceiver().setRoundRadius(AndroidUtilities.dp(8));
                    }
                }
            } catch (Throwable ignored) {}
            b.show();
        } catch (Throwable ignored) {}
    }

    private Drawable emojiStatusDefaultDrawable;
    private Drawable verifiedBackground;
    private Drawable verifiedCheck;


    public void setSubtitle(CharSequence value) {
        // NG: hide the chat-header status line (online / last seen / member count)
        // when the user opts out via Appearance settings.
        if (app.nimarkogram.messenger.NimarkoConfig.hideActionBarStatus) {
            subtitleHiddenByPreference = true;
            inlineSubtitleWidthReserve = 0f;
            subtitleTransitionGeneration++;
            if (subtitleTextView != null) {
                subtitleTextView.animate().cancel();
                subtitleTextView.setText("");
                subtitleTextView.setAlpha(0f);
                subtitleTextView.setVisibility(GONE);
            } else if (animatedSubtitleTextView != null) {
                animatedSubtitleTextView.setText("");
                animatedSubtitleTextView.setVisibility(GONE);
            }
            requestLayout();
            checkActionBar(true);
            return;
        }
        if (subtitleHiddenByPreference) {
            subtitleHiddenByPreference = false;
            if (subtitleTextView != null) {
                subtitleTextView.setVisibility(VISIBLE);
            } else if (animatedSubtitleTextView != null) {
                animatedSubtitleTextView.setVisibility(VISIBLE);
            }
        }
        if (lastSubtitle == null) {
            if (subtitleTextView != null) {
                setSubtitleTextSmooth(value);
            } else if (animatedSubtitleTextView != null) {
                animatedSubtitleTextView.setText(value);
            }
        } else {
            lastSubtitle = value;
        }
        checkActionBar(true);
    }

    // NG: cross-fade the chat-header status line (online / last seen / "N members") when it changes, instead of
    // swapping the text instantly — smooth appear (fade in), disappear (fade out) and change (fade through).
    // No-op when the text is unchanged, so frequent identical status refreshes don't flicker.
    private void setSubtitleTextSmooth(CharSequence value) {
        if (subtitleTextView == null) {
            return;
        }
        final int transitionGeneration = ++subtitleTransitionGeneration;
        subtitleTextView.animate().cancel();
        CharSequence current = subtitleTextView.getText();
        if (android.text.TextUtils.equals(current, value)) {
            inlineSubtitleWidthReserve = 0f;
            subtitleTextView.setAlpha(TextUtils.isEmpty(value) ? 0f : 1f);
            return;
        }
        if (isInlineCenteredAvatar() && !TextUtils.isEmpty(value)) {
            try {
                inlineSubtitleWidthReserve = Layout.getDesiredWidth(value, subtitleTextView.getTextPaint())
                        + subtitleTextView.getSideDrawablesSize();
            } catch (Throwable ignored) {
                inlineSubtitleWidthReserve = subtitleTextView.getTextPaint().measureText(value.toString())
                        + subtitleTextView.getSideDrawablesSize();
            }
        } else {
            inlineSubtitleWidthReserve = 0f;
        }
        if (android.text.TextUtils.isEmpty(current)) {
            // appearing
            subtitleTextView.setText(value);
            inlineSubtitleWidthReserve = 0f;
            subtitleTextView.setAlpha(0f);
            subtitleTextView.animate().alpha(1f).setDuration(180)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        } else {
            // changing or disappearing: fade the old out, swap, fade the new in (unless it became empty)
            subtitleTextView.animate().alpha(0f).setDuration(120)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .withEndAction(() -> {
                        if (transitionGeneration != subtitleTransitionGeneration) {
                            return;
                        }
                        subtitleTextView.setText(value);
                        inlineSubtitleWidthReserve = 0f;
                        requestLayout();
                        checkActionBar(true);
                        if (android.text.TextUtils.isEmpty(value)) {
                            subtitleTextView.setAlpha(0f);
                        } else {
                            subtitleTextView.setAlpha(0f);
                            subtitleTextView.animate().alpha(1f).setDuration(150)
                                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                        }
                    }).start();
        }
    }

    public ImageView getTimeItem() {
        return timeItem;
    }

    public SimpleTextView getTitleTextView() {
        return titleTextView;
    }

    public View getSubtitleTextView() {
        if (subtitleTextView != null) {
            return subtitleTextView;
        }
        if (animatedSubtitleTextView != null) {
            return animatedSubtitleTextView;
        }
        return null;
    }

    public TextPaint getSubtitlePaint() {
        return subtitleTextView != null ? subtitleTextView.getTextPaint() : animatedSubtitleTextView.getPaint();
    }

    public void onDestroy() {
        clearLargerTextCopies();
        if (actionBar != null) {
            actionBar.clearChatAvatarContainer(this);
            actionBar = null;
        }
        if (sharedMediaPreloader != null) {
            sharedMediaPreloader.onDestroy(parentFragment);
        }
    }

    private void setTypingAnimation(boolean start) {
        if (subtitleTextView == null) return;
        if (start) {
            try {
                int type = subtitleIsThinkingBot ? 0 : MessagesController.getInstance(currentAccount).getPrintingStringType(parentFragment.getDialogId(), parentFragment.getThreadId());
                if (statusDrawables[type] == null) return;
                if (type == 5) {
                    subtitleTextView.replaceTextWithDrawable(statusDrawables[type], "**oo**");
                    statusDrawables[type].setColor(getThemedColor(Theme.key_chat_status));
                    subtitleTextView.setLeftDrawable(null);
                } else {
                    subtitleTextView.replaceTextWithDrawable(null, null);
                    statusDrawables[type].setColor(getThemedColor(Theme.key_chat_status));
                    subtitleTextView.setLeftDrawable(statusDrawables[type]);
                }
                currentTypingDrawable = statusDrawables[type];
                for (int a = 0; a < statusDrawables.length; a++) {
                    if (statusDrawables[a] == null) continue;
                    if (a == type) {
                        statusDrawables[a].start();
                    } else {
                        statusDrawables[a].stop();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else {
            currentTypingDrawable = null;
            subtitleTextView.setLeftDrawable(null);
            subtitleTextView.replaceTextWithDrawable(null, null);
            for (int a = 0; a < statusDrawables.length; a++) {
                if (statusDrawables[a] != null) {
                    statusDrawables[a].stop();
                }
            }
        }
    }

    public void updateSubtitle() {
        updateSubtitle(false);
    }

    private boolean subtitleIsThinkingBot;

    private boolean showingSavedMessagesHint;

    public void updateSubtitle(boolean animated) {
        if (parentFragment == null) {
            return;
        }
        if (parentFragment.getChatMode() == ChatActivity.MODE_EDIT_BUSINESS_LINK) {
            setSubtitle(BusinessLinksController.stripHttps(parentFragment.businessLink.link));
            return;
        }
        TLRPC.User user = parentFragment.getCurrentUser();
        TLRPC.Chat chat = parentFragment.getCurrentChat();
        boolean showSavedMessagesHint = (
            UserObject.isUserSelf(user) &&
            parentFragment.getChatMode() == ChatActivity.MODE_DEFAULT &&
            parentFragment.getMessagesController().getSavedMessagesController().getAllCount() >= 3 &&
            (showingSavedMessagesHint || (MessagesController.getGlobalMainSettings().getInt("savedmsgschatshint", 0) < 3))
        );
        if ((UserObject.isUserSelf(user) && !showSavedMessagesHint || UserObject.isReplyUser(user) || user != null && user.id == UserObject.VERIFY || parentFragment.getChatMode() != 0 && parentFragment.getChatMode() != ChatActivity.MODE_SUGGESTIONS) && parentFragment.getChatMode() != ChatActivity.MODE_SAVED) {
            if (getSubtitleTextView().getVisibility() != GONE) {
                getSubtitleTextView().setVisibility(GONE);
            }
            return;
        } else if (showSavedMessagesHint) {
            if (getSubtitleTextView().getVisibility() != VISIBLE) {
                getSubtitleTextView().setVisibility(VISIBLE);
            }
            if (!showingSavedMessagesHint) {
                MessagesController.getGlobalMainSettings().edit().putInt(
                    "savedmsgschatshint", MessagesController.getGlobalMainSettings().getInt("savedmsgschatshint", 0) + 1
                ).apply();
                showingSavedMessagesHint = true;
            }
        }

        subtitleIsThinkingBot = false;
        CharSequence printString = MessagesController.getInstance(currentAccount).getPrintingString(parentFragment.getDialogId(), parentFragment.getThreadId(), false);
        if (printString == null && UserObject.isBotForum(user)) {
            //if (BotForumHelper.getInstance(currentAccount).isThinking(user.id, (int) parentFragment.getTopicId())) {
            //    printString = "thinking";
            //    subtitleIsThinkingBot = true;
            //}
        }

        if (printString != null) {
            printString = TextUtils.replace(printString, new String[]{"..."}, new String[]{""});
        }
        CharSequence newSubtitle;
        boolean useOnlineColor = false;
        if (printString == null || printString.length() == 0 || ChatObject.isChannel(chat) && !chat.megagroup) {
            if (parentFragment.isThreadChat() && !parentFragment.isTopic) {
                if (titleTextView.getTag() != null) {
                    return;
                }
                titleTextView.setTag(1);
                if (titleAnimation != null) {
                    titleAnimation.cancel();
                    titleAnimation = null;
                }
                if (animated) {
                    titleAnimation = new AnimatorSet();
                    titleAnimation.playTogether(
                        ObjectAnimator.ofFloat(titleTextView, View.TRANSLATION_Y, dp(9.7f)),
                        ObjectAnimator.ofFloat(getSubtitleTextView(), View.ALPHA, 0.0f)
                    );
                    titleAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationCancel(Animator animation) {
                            titleAnimation = null;
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (titleAnimation == animation) {
                                getSubtitleTextView().setVisibility(INVISIBLE);
                                titleAnimation = null;
                            }
                        }
                    });
                    titleAnimation.setDuration(180);
                    titleAnimation.start();
                } else {
                    titleTextView.setTranslationY(dp(9.7f));
                    getSubtitleTextView().setAlpha(0.0f);
                    getSubtitleTextView().setVisibility(INVISIBLE);
                }
                return;
            }
            setTypingAnimation(false);
            if (parentFragment.getChatMode() == ChatActivity.MODE_SUGGESTIONS) {
                if (parentFragment.isSubscriberSuggestions) {
                    newSubtitle = getString(R.string.ChatMessageSuggestions);
                } else {
                    final long dialogId = parentFragment.getTopicId();
                    if (dialogId == 0) {
                        int topicsCount = parentFragment.getMessagesController().getTopicsController().getTopicsCount(-parentFragment.getDialogId());
                        if (topicsCount > 0) {
                            newSubtitle = LocaleController.formatPluralStringComma("Chats", topicsCount);
                        } else {
                            newSubtitle = getString(R.string.ChatMessageSuggestions);
                        }
                    } else {
                        TLRPC.TL_forumTopic topic = MessagesController.getInstance(currentAccount).getTopicsController().findTopic(chat.id, parentFragment.getTopicId());
                        int count = 0;
                        if (topic != null) {
                            count = topic.totalMessagesCount;
                        }
                        if (count > 0) {
                            newSubtitle = LocaleController.formatPluralString("messages", count, count);
                        } else {
                            newSubtitle = LocaleController.formatString(R.string.TopicProfileStatus, ForumUtilities.getMonoForumTitle(currentAccount, chat));
                        }
                    }
                }
            } else if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                int messagesCount = parentFragment.getMessagesController().getSavedMessagesController().getMessagesCount(parentFragment.getSavedDialogId());
                newSubtitle = LocaleController.formatPluralString("SavedMessagesCount", Math.max(1, messagesCount));
            } else if (parentFragment.isTopic && chat != null) {
                TLRPC.TL_forumTopic topic = MessagesController.getInstance(currentAccount).getTopicsController().findTopic(chat.id, parentFragment.getTopicId());
                int count = 0;
                if (topic != null) {
                    count = topic.totalMessagesCount - 1;
                }
                if (count > 0) {
                    newSubtitle = LocaleController.formatPluralString("messages", count, count);
                } else {
                    newSubtitle = LocaleController.formatString(R.string.TopicProfileStatus, chat.title);
                }
            } else if (chat != null) {
                TLRPC.ChatFull info = parentFragment.getCurrentChatInfo();
                newSubtitle = getChatSubtitle(chat, info, onlineCount);
            } else if (user != null) {
                TLRPC.User newUser = MessagesController.getInstance(currentAccount).getUser(user.id);
                if (newUser != null) {
                    user = newUser;
                }
                CharSequence newStatus;
                if (UserObject.isReplyUser(user)) {
                    newStatus = "";
                } else if (user.id == UserObject.VERIFY) {
                    newStatus = "";//LocaleController.getString(R.string.VerifyCodesNotifications);
                } else if (user.id == UserConfig.getInstance(currentAccount).getClientUserId()) {
                    if (showSavedMessagesHint) {
                        newStatus = replaceArrows(getString(R.string.SavedMessagesViewAsChatsHint), false);
                    } else {
                        newStatus = getString(R.string.ChatYourSelf);
                    }
                } else if (user.id == 333000 || user.id == 777000 || user.id == 42777) {
                    newStatus = getString(R.string.ServiceNotifications);
                } else if (MessagesController.isSupportUser(user)) {
                    newStatus = getString(R.string.SupportStatus);
                } else if (user.bot && user.bot_active_users != 0) {
                    newStatus = LocaleController.formatPluralStringComma("BotUsers", user.bot_active_users, ',');
                } else if (user.bot) {
                    newStatus = getString(R.string.Bot);
                } else {
                    isOnline[0] = false;
                    newStatus = app.nimarkogram.messenger.NimarkoConfig.oldTimeStyle
                            ? LocaleController.formatUserStatus(currentAccount, user, isOnline, allowShorterStatus ? statusMadeShorter : null)
                            : LocaleController.formatUserStatusIOS(currentAccount, user, isOnline, allowShorterStatus ? statusMadeShorter : null);
                    useOnlineColor = isOnline[0];
                }
                newSubtitle = newStatus;
            } else {
                newSubtitle = "";
            }
        } else {
            if (parentFragment.isThreadChat()) {
                if (titleTextView.getTag() != null) {
                    titleTextView.setTag(null);
                    getSubtitleTextView().setVisibility(VISIBLE);
                    if (titleAnimation != null) {
                        titleAnimation.cancel();
                        titleAnimation = null;
                    }
                    if (animated) {
                        titleAnimation = new AnimatorSet();
                        titleAnimation.playTogether(
                                ObjectAnimator.ofFloat(titleTextView, View.TRANSLATION_Y, 0),
                                ObjectAnimator.ofFloat(getSubtitleTextView(), View.ALPHA, 1.0f));
                        titleAnimation.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                titleAnimation = null;
                            }
                        });
                        titleAnimation.setDuration(180);
                        titleAnimation.start();
                    } else {
                        titleTextView.setTranslationY(0.0f);
                        getSubtitleTextView().setAlpha(1.0f);
                    }
                }
            }
            newSubtitle = printString;
            Integer type = MessagesController.getInstance(currentAccount).getPrintingStringType(parentFragment.getDialogId(), parentFragment.getThreadId());
            if (type != null && type == 5) {
                newSubtitle = Emoji.replaceEmoji(newSubtitle, getSubtitlePaint().getFontMetricsInt(), false);
            }
            useOnlineColor = true;
            setTypingAnimation(true);
        }
        if (app.nimarkogram.messenger.NimarkoConfig.hideActionBarStatus) {
            // Real status path: updateSubtitle() writes online/last-seen/typing/member-count
            // straight to the subtitle view, bypassing the guarded setSubtitle overload.
            // Blank it here and kill the animated typing dots so nothing shows.
            newSubtitle = "";
            setTypingAnimation(false);
            // GONE (not just empty) so onLayout centers the title vertically — no empty gap below.
            if (getSubtitleTextView() != null && getSubtitleTextView().getVisibility() != GONE) {
                getSubtitleTextView().setVisibility(GONE);
            }
        }
        lastSubtitleColorKey = useOnlineColor ? Theme.key_chat_status : Theme.key_actionBarDefaultSubtitle;
        if (lastSubtitle == null) {
            if (subtitleTextView != null) {
                subtitleTextView.setText(newSubtitle);
                if (overrideSubtitleColor == null) {
                    subtitleTextView.setTextColor(getThemedColor(lastSubtitleColorKey));
                    subtitleTextView.setTag(lastSubtitleColorKey);
                } else {
                    subtitleTextView.setTextColor(overrideSubtitleColor);
                }
            } else {
                animatedSubtitleTextView.setText(newSubtitle, animated);
                if (overrideSubtitleColor == null) {
                    animatedSubtitleTextView.setTextColor(getThemedColor(lastSubtitleColorKey));
                    animatedSubtitleTextView.setTag(lastSubtitleColorKey);
                } else {
                    animatedSubtitleTextView.setTextColor(overrideSubtitleColor);
                }
            }
        } else {
            lastSubtitle = newSubtitle;
        }
        checkActionBar(animated);
    }

    public static CharSequence getChatSubtitle(TLRPC.Chat chat, TLRPC.ChatFull info, int onlineCount) {
        CharSequence newSubtitle = null;
        if (ChatObject.isChannel(chat)) {
            if (info != null && info.participants_count != 0) {
                if (chat.megagroup) {
                    if (onlineCount > 1) {
                        newSubtitle = String.format("%s, %s", LocaleController.formatPluralString("Members", info.participants_count), LocaleController.formatPluralString("OnlineCount", Math.min(onlineCount, info.participants_count)));
                    } else {
                        newSubtitle = LocaleController.formatPluralString("Members", info.participants_count);
                    }
                } else {
                    int[] result = new int[1];
                    boolean ignoreShort = AndroidUtilities.isAccessibilityScreenReaderEnabled();
                    String shortNumber = ignoreShort ? String.valueOf(result[0] = info.participants_count) : LocaleController.formatShortNumber(info.participants_count, result);
                    if (chat.megagroup) {
                        newSubtitle = LocaleController.formatPluralString("Members", result[0]).replace(String.format("%d", result[0]), shortNumber);
                    } else {
                        newSubtitle = LocaleController.formatPluralString("Subscribers", result[0]).replace(String.format("%d", result[0]), shortNumber);
                    }
                }
            } else {
                if (chat.megagroup) {
                    if (info == null) {
                        newSubtitle = getString(R.string.Loading).toLowerCase();
                    } else {
                        if (chat.has_geo) {
                            newSubtitle = getString(R.string.MegaLocation).toLowerCase();
                        } else if (ChatObject.isPublic(chat)) {
                            newSubtitle = getString(R.string.MegaPublic).toLowerCase();
                        } else {
                            newSubtitle = getString(R.string.MegaPrivate).toLowerCase();
                        }
                    }
                } else {
                    if (ChatObject.isPublic(chat)) {
                        newSubtitle = getString(R.string.ChannelPublic).toLowerCase();
                    } else {
                        newSubtitle = getString(R.string.ChannelPrivate).toLowerCase();
                    }
                }
            }
        } else {
            if (ChatObject.isKickedFromChat(chat)) {
                newSubtitle = getString(R.string.YouWereKicked);
            } else if (ChatObject.isLeftFromChat(chat)) {
                newSubtitle = getString(R.string.YouLeft);
            } else {
                int count = chat.participants_count;
                if (info != null && info.participants != null) {
                    count = info.participants.participants.size();
                }
                if (onlineCount > 1 && count != 0) {
                    newSubtitle = String.format("%s, %s", LocaleController.formatPluralString("Members", count), LocaleController.formatPluralString("OnlineCount", onlineCount));
                } else {
                    newSubtitle = LocaleController.formatPluralString("Members", count);
                }
            }
        }
        return newSubtitle;
    }

    public int getLastSubtitleColorKey() {
        return lastSubtitleColorKey;
    }

    public void setChatAvatar(TLRPC.Chat chat) {
        headerIdentityTarget = chat;
        avatarDrawable.setInfo(currentAccount, chat);
        if (avatarImageView != null) {
            avatarImageView.setForUserOrChat(chat, avatarDrawable);
            avatarImageView.setRoundRadius(ChatObject.isForum(chat)
                    ? AndroidUtilities.dp(16) : AndroidUtilities.dp(21));
        }
    }

    public void setUserAvatar(TLRPC.User user) {
        setUserAvatar(user, false);
    }

    public void setUserAvatar(TLRPC.User user, boolean showSelf) {
        headerIdentityTarget = user;
        avatarDrawable.setInfo(currentAccount, user);
        if (avatarImageView != null) {
            avatarImageView.setRoundRadius(AndroidUtilities.dp(21));
        }
        if (UserObject.isReplyUser(user)) {
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_REPLIES);
            avatarDrawable.setScaleSize(.8f);
            if (avatarImageView != null) {
                avatarImageView.setImage(null, null, avatarDrawable, user);
            }
        } else if (UserObject.isAnonymous(user)) {
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_ANONYMOUS);
            avatarDrawable.setScaleSize(.8f);
            if (avatarImageView != null) {
                avatarImageView.setImage(null, null, avatarDrawable, user);
            }
        } else if (UserObject.isUserSelf(user) && !showSelf) {
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
            avatarDrawable.setScaleSize(.8f);
            if (avatarImageView != null) {
                avatarImageView.setImage(null, null, avatarDrawable, user);
            }
        } else {
            avatarDrawable.setScaleSize(1f);
            if (avatarImageView != null) {
                avatarImageView.setForUserOrChat(user, avatarDrawable);
            }
        }
    }

    public void checkAndUpdateAvatar() {
        if (parentFragment == null) {
            return;
        }

        TLRPC.User user = parentFragment.getCurrentUser();
        TLRPC.Chat chat = parentFragment.getCurrentChat();
        if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
            long dialogId = parentFragment.getSavedDialogId();
            if (dialogId >= 0) {
                user = parentFragment.getMessagesController().getUser(dialogId);
                chat = null;
            } else {
                user = null;
                chat = parentFragment.getMessagesController().getChat(-dialogId);
            }
        }
        if (user != null) {
            avatarDrawable.setInfo(currentAccount, user);
            if (UserObject.isReplyUser(user)) {
                avatarDrawable.setScaleSize(.8f);
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_REPLIES);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setImage(null, null, avatarDrawable, user);
                }
            } else if (UserObject.isAnonymous(user)) {
                avatarDrawable.setScaleSize(.8f);
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_ANONYMOUS);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setImage(null, null, avatarDrawable, user);
                }
            } else if (UserObject.isUserSelf(user) && parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                avatarDrawable.setScaleSize(.8f);
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_MY_NOTES);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setImage(null, null, avatarDrawable, user);
                }
            } else if (UserObject.isUserSelf(user)) {
                avatarDrawable.setScaleSize(.8f);
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setImage(null, null, avatarDrawable, user);
                }
            } else {
                avatarDrawable.setScaleSize(1f);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.imageReceiver.setForUserOrChat(user, avatarDrawable,  null, true, VectorAvatarThumbDrawable.TYPE_STATIC, false);
                }
            }
        } else if (ChatObject.isMonoForum(chat)) {
            final long dialogId = parentFragment.getTopicId();
            if (ChatObject.canManageMonoForum(currentAccount, chat) && dialogId != 0) {
                if (dialogId > 0) {
                    final TLRPC.User user2 = parentFragment.getMessagesController().getUser(dialogId);
                    avatarDrawable.setInfo(user2);
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setForUserOrChat(user2, avatarDrawable);
                } else {
                    final TLRPC.Chat chat2 = parentFragment.getMessagesController().getChat(-dialogId);
                    avatarDrawable.setInfo(chat2);
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setForUserOrChat(chat2, avatarDrawable);
                }
            } else {
                avatarImageView.setAnimatedEmojiDrawable(null);
                ForumUtilities.setMonoForumAvatar(currentAccount, chat, avatarDrawable, avatarImageView);
            }
            avatarImageView.setRoundRadius(AndroidUtilities.dp(21));
        } else if (chat != null) {
            avatarDrawable.setScaleSize(1f);
            avatarDrawable.setInfo(currentAccount, chat);

            if (avatarImageView != null) {
                avatarImageView.setAnimatedEmojiDrawable(null);
                avatarImageView.setForUserOrChat(chat, avatarDrawable);
                avatarImageView.setRoundRadius(chat.forum
                        ? AndroidUtilities.dp(16) : AndroidUtilities.dp(21));
            }
        }
    }

    public void updateOnlineCount() {
        if (parentFragment == null) {
            return;
        }
        onlineCount = 0;
        TLRPC.ChatFull info = parentFragment.getCurrentChatInfo();
        if (info == null) {
            return;
        }
        int currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        if (info instanceof TLRPC.TL_chatFull || info instanceof TLRPC.TL_channelFull && info.participants_count <= 200 && info.participants != null) {
            for (int a = 0; a < info.participants.participants.size(); a++) {
                TLRPC.ChatParticipant participant = info.participants.participants.get(a);
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(participant.user_id);
                if (user != null && user.status != null && (user.status.expires > currentTime || user.id == UserConfig.getInstance(currentAccount).getClientUserId()) && user.status.expires > 10000) {
                    onlineCount++;
                }
            }
        } else if (info instanceof TLRPC.TL_channelFull && info.participants_count > 200) {
            onlineCount = info.online_count;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerWithActionBarIfAttached();
        if (parentFragment != null) {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.dialogsNeedReload);
            // NimarkoGram: retrigger badge apply when chat/user info finally
            // loads — otherwise the first setTitle() fires with empty
            // parentFragment data and badge probe returns null.
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.chatInfoDidLoad);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.userInfoDidLoad);
            if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.savedMessagesDialogsUpdate);
            }
            currentConnectionState = ConnectionsManager.getInstance(currentAccount).getConnectionState();
            updateCurrentConnectionState();
        }
        if (emojiStatusDrawable != null) {
            emojiStatusDrawable.attach();
        }
        if (botVerificationDrawable != null) {
            botVerificationDrawable.attach();
        }
        if (badgeEmojiDrawable != null) {
            badgeEmojiDrawable.attach();
        }
        // NG: profile-open then back → ChatAvatarContainer detaches/reattaches.
        // setTitle wasn't re-fired by ChatActivity on resume, so the badge slot
        // ended up empty. Re-evaluate the badge state every time the view
        // re-attaches; cheap (no allocation when badge unchanged).
        if (parentFragment != null) {
            applyNimarkoBadge(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (actionBar != null) {
            actionBar.clearChatAvatarContainer(this);
        }
        clearLargerTextCopies();
        if (titleTextView != null) {
            titleTextView.animate().cancel();
        }
        if (subtitleTextView != null) {
            subtitleTextView.animate().cancel();
        }
        if (animatedSubtitleTextView != null) {
            animatedSubtitleTextView.animate().cancel();
        }
        if (parentFragment != null) {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.dialogsNeedReload);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.chatInfoDidLoad);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.userInfoDidLoad);
            if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.savedMessagesDialogsUpdate);
            }
        }
        if (emojiStatusDrawable != null) {
            emojiStatusDrawable.detach();
        }
        if (botVerificationDrawable != null) {
            botVerificationDrawable.detach();
        }
        if (badgeEmojiDrawable != null) {
            badgeEmojiDrawable.detach();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
            if (currentConnectionState != state) {
                currentConnectionState = state;
                updateCurrentConnectionState();
            }
        } else if (id == NotificationCenter.emojiLoaded) {
            if (titleTextView != null) {
                titleTextView.invalidate();
            }
            if (getSubtitleTextView() != null) {
                getSubtitleTextView().invalidate();
            }
            invalidate();
        } else if (id == NotificationCenter.savedMessagesDialogsUpdate) {
            updateSubtitle(true);
        } else if (id == NotificationCenter.updateInterfaces || id == NotificationCenter.dialogsNeedReload
                || id == NotificationCenter.chatInfoDidLoad || id == NotificationCenter.userInfoDidLoad) {
            // NimarkoGram: badge cache may have just been populated by
            // BadgesController.refreshSync() OR the chat/user info finally
            // loaded — re-evaluate the slot so the badge appears without a
            // manual scroll/rebind. animated=false because we already
            // dedupe on lastNimarkoBadgeDocId.
            applyNimarkoBadge(false);
        }
    }

    private void updateCurrentConnectionState() {
        String title = null;
        if (currentConnectionState == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            title = getString(R.string.WaitingForNetwork);
        } else if (currentConnectionState == ConnectionsManager.ConnectionStateConnecting) {
            title = getString(R.string.Connecting);
        } else if (currentConnectionState == ConnectionsManager.ConnectionStateUpdating) {
            title = getString(R.string.Updating);
        } else if (currentConnectionState == ConnectionsManager.ConnectionStateConnectingToProxy) {
            title = getString(R.string.ConnectingToProxy);
        }
        if (title == null) {
            if (lastSubtitle != null) {
                if (subtitleTextView != null) {
                    subtitleTextView.setText(lastSubtitle);
                    lastSubtitle = null;
                    if (overrideSubtitleColor != null) {
                        subtitleTextView.setTextColor(overrideSubtitleColor);
                    } else if (lastSubtitleColorKey >= 0) {
                        subtitleTextView.setTextColor(getThemedColor(lastSubtitleColorKey));
                        subtitleTextView.setTag(lastSubtitleColorKey);
                    }
                } else if (animatedSubtitleTextView != null) {
                    animatedSubtitleTextView.setText(lastSubtitle, !LocaleController.isRTL);
                    lastSubtitle = null;
                    if (overrideSubtitleColor != null) {
                        animatedSubtitleTextView.setTextColor(overrideSubtitleColor);
                    } else if (lastSubtitleColorKey >= 0) {
                        animatedSubtitleTextView.setTextColor(getThemedColor(lastSubtitleColorKey));
                        animatedSubtitleTextView.setTag(lastSubtitleColorKey);
                    }
                }
            }
        } else {
            if (subtitleTextView != null) {
                if (lastSubtitle == null) {
                    lastSubtitle = subtitleTextView.getText();
                }
                subtitleTextView.setText(title);
                if (overrideSubtitleColor != null) {
                    subtitleTextView.setTextColor(overrideSubtitleColor);
                } else {
                    subtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
                    subtitleTextView.setTag(Theme.key_actionBarDefaultSubtitle);
                }
            } else if (animatedSubtitleTextView != null) {
                if (lastSubtitle == null) {
                    lastSubtitle = animatedSubtitleTextView.getText();
                }
                animatedSubtitleTextView.setText(title, !LocaleController.isRTL);
                if (overrideSubtitleColor != null) {
                    animatedSubtitleTextView.setTextColor(overrideSubtitleColor);
                } else {
                    animatedSubtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
                    animatedSubtitleTextView.setTag(Theme.key_actionBarDefaultSubtitle);
                }
            }
        }
        checkActionBar(true);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        StringBuilder sb = new StringBuilder();
        sb.append(titleTextView.getText());
        if (rightDrawableContentDescription != null) {
            sb.append(", ");
            sb.append(rightDrawableContentDescription);
        }
        if (rightDrawable2ContentDescription != null) {
            sb.append(", ");
            sb.append(rightDrawable2ContentDescription);
        }
        sb.append("\n");
        if (subtitleTextView != null) {
            sb.append(subtitleTextView.getText());
        } else if (animatedSubtitleTextView != null) {
            sb.append(animatedSubtitleTextView.getText());
        }
        info.setContentDescription(sb);
        if (info.isClickable()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    getString(R.string.OpenProfile)));
        }
        if (currentNimarkoBadge != null) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    R.id.acc_action_badge_info, getString(R.string.NM_ProfileBadge)));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == R.id.acc_action_badge_info && currentNimarkoBadge != null) {
            showNimarkoBadgeBulletin(currentNimarkoBadge);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    public SharedMediaLayout.SharedMediaPreloader getSharedMediaPreloader() {
        return sharedMediaPreloader;
    }

    public BackupImageView getAvatarImageView() {
        return avatarImageView;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public void updateColors() {
        if (titleTextView != null) {
            titleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
        }
        if (subtitleTextView != null) {
            subtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        }
        if (animatedSubtitleTextView != null) {
            animatedSubtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        }
        if (currentTypingDrawable != null) {
            currentTypingDrawable.setColor(getThemedColor(Theme.key_chat_status));
        }
        if (emojiStatusDefaultDrawable != null) {
            emojiStatusDefaultDrawable.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
        }
        if (botVerificationDrawable != null) {
            botVerificationDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
        }
        if (emojiStatusDrawable != null) {
            emojiStatusDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
        }
        if (verifiedBackground != null) {
            verifiedBackground.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
        }
        if (verifiedCheck != null) {
            verifiedCheck.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedCheck), PorterDuff.Mode.MULTIPLY));
        }
        updateCommunityIndicatorStyle();
        invalidate();
    }

    private ActionBar actionBar;

    private void registerWithActionBarIfAttached() {
        if (actionBar != null
                && isAttachedToWindow()
                && getParent() == actionBar
                && getLayoutParams() != null) {
            actionBar.setChatAvatarContainer(this);
        }
    }

    public void setActionBar(ActionBar actionBar) {
        if (this.actionBar == actionBar) {
            registerWithActionBarIfAttached();
            return;
        }
        if (this.actionBar != null) {
            this.actionBar.clearChatAvatarContainer(this);
        }
        this.actionBar = actionBar;
        registerWithActionBarIfAttached();
    }

    private void checkActionBar(boolean animated) {
        if (actionBar != null) {
            // Before the first real layout there is no meaningful width to
            // animate from.  Force the initial state so "Loading" never starts
            // as a zero-width capsule; later retargets remain animated.
            actionBar.checkAvatarContainerWidth(
                    (animated || shouldUseCompactTitleIsland())
                            && isLaidOut() && actionBar.isLaidOut());
        }
    }

    public boolean hasVisibleAvatar() {
        return avatarImageView != null && avatarImageView.getVisibility() == VISIBLE;
    }

    private float getInlineDesiredWidth(SimpleTextView view) {
        if (view == null) {
            return 0f;
        }
        float textWidth;
        try {
            final CharSequence text = view.getText();
            textWidth = TextUtils.isEmpty(text)
                    ? 0f : Layout.getDesiredWidth(text, view.getTextPaint());
        } catch (Throwable ignored) {
            textWidth = view.getTextPaint().measureText(view.getText().toString());
        }
        // Do not max this with getExactWidthIncludeDrawables(): immediately
        // after setText(), SimpleTextView still exposes the previous StaticLayout
        // width until measure. Keeping that stale value prevents a long -> short
        // topic switch from ever starting its shrink animation.
        final float contentWidth = Math.max(0f, textWidth)
                + view.getSideDrawablesSize();
        return contentWidth + view.getPaddingLeft() + view.getPaddingRight();
    }

    public int getVisualWidth() {
        float width = 0;
        final boolean compactTitle = shouldUseCompactTitleIsland();

        if (titleTextView != null) {
            float titleWidth = compactTitle
                    ? getInlineDesiredWidth(titleTextView)
                    : titleTextView.getExactWidthIncludeDrawables()
                            + titleTextView.getPaddingLeft()
                            + titleTextView.getPaddingRight();
            titleWidth += getInlineCommunityIndicatorSpace();
            width = Math.max(width, titleWidth);
        }
        if (subtitleTextView != null && subtitleTextView.getVisibility() != GONE) {
            final float subtitleWidth = compactTitle
                    ? getInlineDesiredWidth(subtitleTextView)
                    : subtitleTextView.getExactWidthIncludeDrawables()
                            + subtitleTextView.getPaddingLeft()
                            + subtitleTextView.getPaddingRight();
            width = Math.max(width, Math.max(
                    subtitleWidth,
                    inlineSubtitleWidthReserve
                            + subtitleTextView.getPaddingLeft()
                            + subtitleTextView.getPaddingRight()));
        }
        if (isInlineCenteredAvatar() && hasVisibleAvatar()) {
            final int avatarWidth = avatarImageView.getMeasuredWidth() > 0
                    ? avatarImageView.getMeasuredWidth() : dp(avatarSizeInDp) - 2;
            // Keep real content breathing room separate from the glass
            // drawable's technical 6dp effect padding.
            width += avatarWidth + dp(8) + dp(4) * 2;
        } else if (hasVisibleAvatar()) {
            width += dp(52 + 12);
        } else {
            width += dp(34);
        }
        // ActionBar adds its own symmetric glass padding. Round outward here:
        // flooring a fractional glyph width can otherwise leave the final
        // pixel of a subtitle/badge beyond the settled capsule.
        return (int) Math.ceil(width);
    }
}
