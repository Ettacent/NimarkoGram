/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Stories;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.Utilities.clamp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.google.zxing.common.detector.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.RadialProgress;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.nimarkogram.messenger.utils.ProfileAppearanceMotion;
public class ProfileStoriesView extends View implements NotificationCenter.NotificationCenterDelegate {

    private static final int CIRCLES_MAX = 3;
    public static final String FRAGMENT_TRANSITION_PROPERTY = "fragmentTransitionProgress";

    private int readPaintAlpha;
    private final Paint readPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint livePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int currentAccount;
    private final long dialogId;
    private final boolean isTopic;
    private final View avatarContainer;
    private final ProfileActivity.AvatarImageView avatarImage;

    private final Theme.ResourcesProvider resourcesProvider;
    private boolean initialized;
    private final AnimatedTextView.AnimatedTextDrawable titleDrawable = new AnimatedTextView.AnimatedTextDrawable(false, true, true);

    private final Paint clipOutAvatar = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int unreadCount;
    private int count;
    private StoryCircle mainCircle;
    private final ArrayList<StoryCircle> circles = new ArrayList<>();

    private boolean attached;
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean lastDrawnStateIsFailed;
    private RadialProgress radialProgress;
    private boolean progressWasDrawn;
    private boolean progressIsDone;
    private float bounceScale = 1f;
    private float progressToInsets = 1f;
    private float fragmentTransitionProgress = 1f;
    private boolean hasStoryRing;
    private final AnimatedFloat storyRingPresence = new AnimatedFloat(0f, this, 0, 250, CubicBezierInterpolator.DEFAULT);
    private float presentedStoryRingPresence;
    private int uploadingStoriesCount;
    private StoriesController.UploadingStory lastUploadingStory;
    private final StoriesUtilities.StoryGradientTools gradientTools;

    public void setProgressToStoriesInsets(float progressToInsets) {
        if (this.progressToInsets == progressToInsets) {
            return;
        }
        this.progressToInsets = progressToInsets;
        invalidate();
    }

    private class StoryCircle {
        public StoryCircle(TL_stories.StoryItem storyItem) {
            this.storyId = storyItem.id;
            this.imageReceiver.setCurrentAccount(currentAccount);
            this.imageReceiver.setRoundRadius(dp(200));
            this.imageReceiver.setParentView(ProfileStoriesView.this);
            if (attached) {
                this.imageReceiver.onAttachedToWindow();
            }
            bindContent(storyItem);
        }

        int storyId;
        private String contentKey;
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        ImageReceiver imageReceiver = new ImageReceiver();
        int index = 0;
        boolean read = false;
        float scale = 1;
        final AnimatedFloat readAnimated = new AnimatedFloat(ProfileStoriesView.this, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
        final AnimatedFloat indexAnimated = new AnimatedFloat(ProfileStoriesView.this, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
        final AnimatedFloat scaleAnimated = new AnimatedFloat(ProfileStoriesView.this, 420, CubicBezierInterpolator.EASE_OUT_QUINT);

        float cachedIndex;
        float cachedScale;
        float cachedRead;
        boolean live;
        final RectF cachedRect = new RectF();
        final RectF borderRect = new RectF();

        public float getIndex() {
            return indexAnimated.set(index, !ProfileAppearanceMotion.isEnabled());
        }

        private void bindContent(TL_stories.StoryItem storyItem) {
            storyItem.dialogId = dialogId;
            final String nextKey = storyContentKey(storyItem);
            if (nextKey.equals(contentKey)) return;
            imageReceiver.setCrossfadeWithOldImage(contentKey != null);
            StoriesUtilities.setThumbImage(avatarDrawable, imageReceiver, storyItem, 25, 25);
            live = storyItem.media instanceof TLRPC.TL_messageMediaVideoStream;
            contentKey = nextKey;
        }

        public void destroy() {
            imageReceiver.onDetachedFromWindow();
        }

        public void apply() {
            readAnimated.set(read, true);
            indexAnimated.set(index, true);
            scaleAnimated.set(scale, true);
        }
    }

    StoriesController storiesController;

    public ProfileStoriesView(Context context, int currentAccount, long dialogId, boolean isTopic, @NonNull View avatarContainer, ProfileActivity.AvatarImageView avatarImage, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.isTopic = isTopic;
        this.avatarContainer = avatarContainer;
        this.avatarImage = avatarImage;
        this.resourcesProvider = resourcesProvider;
        gradientTools = new StoriesUtilities.StoryGradientTools(currentAccount, this, false);
        avatarImage.getImageReceiver().setVisibleInvalidate(this::invalidate);
        storiesController = MessagesController.getInstance(currentAccount).getStoriesController();

        readPaint.setColor(0x5affffff);
        readPaintAlpha = readPaint.getAlpha();
        readPaint.setStrokeWidth(dpf2(1.5f));
        readPaint.setStyle(Paint.Style.STROKE);
        readPaint.setStrokeCap(Paint.Cap.ROUND);

        livePaint.setColor(Theme.getColor(Theme.key_stories_circle_live1, resourcesProvider));
        livePaint.setStrokeWidth(dpf2(1.5f));
        livePaint.setStyle(Paint.Style.STROKE);
        livePaint.setStrokeCap(Paint.Cap.ROUND);

        whitePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        titleDrawable.setTextSize(dp(18));
        titleDrawable.setAnimationProperties(.4f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        titleDrawable.setTypeface(AndroidUtilities.bold());
        titleDrawable.setTextColor(Color.WHITE/*Theme.getColor(Theme.key_actionBarDefaultTitle, resourcesProvider)*/);
        titleDrawable.setEllipsizeByGradient(true);
        titleDrawable.setCallback(this);

        clipOutAvatar.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        paint.setStrokeWidth(dpf2(2.33f));
        paint.setStyle(Paint.Style.STROKE);
        initialized = true;
        updateStories(false, false);
    }

    private String storyContentKey(TL_stories.StoryItem storyItem) {
        final StringBuilder key = new StringBuilder().append(currentAccount).append(':')
                .append(dialogId).append(':').append(storyItem.id).append(':');
        final TLRPC.MessageMedia media = storyItem.media;
        key.append(media == null ? "none" : media.getClass().getName());
        if (media instanceof TLRPC.TL_messageMediaVideoStream) {
            final TLObject peer = MessagesController.getInstance(currentAccount).getUserOrChat(dialogId);
            appendImageKey(key, ImageLocation.getForUserOrChat(currentAccount, peer, ImageLocation.TYPE_SMALL), peer);
            if (peer instanceof TLRPC.User) {
                TLRPC.User user = (TLRPC.User) peer;
                key.append(':').append(UserObject.getUserName(user)).append(':').append(UserObject.getColorId(user));
                if (user.photo != null) key.append(':').append(user.photo.photo_id).append(':')
                        .append(Utilities.bytesToHex(user.photo.stripped_thumb));
            } else if (peer instanceof TLRPC.Chat) {
                TLRPC.Chat chat = (TLRPC.Chat) peer;
                key.append(':').append(chat.title).append(':').append(ChatObject.getColorId(chat));
                if (chat.photo != null) key.append(':').append(chat.photo.photo_id).append(':')
                        .append(Utilities.bytesToHex(chat.photo.stripped_thumb));
            }
        } else if (media != null && media.document != null) {
            final TLRPC.Document document = media.document;
            key.append(':').append(document.id);
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, dp(25), false, null, true);
            appendImageKey(key, ImageLocation.getForDocument(size, document), storyItem);
            appendStrippedKey(key, document.thumbs);
        } else if (media != null && media.photo != null) {
            final TLRPC.Photo photo = media.photo;
            key.append(':').append(photo.id);
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, dp(25), false, null, true);
            appendImageKey(key, ImageLocation.getForPhoto(size, photo), storyItem);
            appendStrippedKey(key, photo.sizes);
        }
        return key.toString();
    }

    private static void appendImageKey(StringBuilder key, ImageLocation location, Object parent) {
        key.append(':').append(location == null ? null : location.getKey(parent, null, false));
        if (location != null && location.photoSize != null) {
            key.append(':').append(Utilities.bytesToHex(location.photoSize.bytes));
        }
    }

    private static void appendStrippedKey(StringBuilder key, ArrayList<TLRPC.PhotoSize> sizes) {
        if (sizes == null) return;
        for (TLRPC.PhotoSize size : sizes) {
            if (size instanceof TLRPC.TL_photoStrippedSize) {
                key.append(':').append(Utilities.bytesToHex(size.bytes));
                break;
            }
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == titleDrawable || super.verifyDrawable(who);
    }

    private TL_stories.PeerStories peerStories;
    private boolean controllerStoriesObserved;

    private TL_stories.PeerStories storiesForThisPeer(TL_stories.PeerStories stories) {
        if (dialogId == 0 || stories == null || stories.peer == null
                || DialogObject.getPeerDialogId(stories.peer) != dialogId) return null;
        if (stories.stories != null) {
            for (int i = 0; i < stories.stories.size(); i++) {
                final TL_stories.StoryItem story = stories.stories.get(i);
                if (story == null || story.dialogId != 0 && story.dialogId != dialogId) return null;
            }
        }
        return stories;
    }

    private TL_stories.StoryItem storyForPreview(TL_stories.StoryItem story, TL_stories.PeerStories stateStories, int now) {
        if (story instanceof TL_stories.TL_storyItemDeleted
                || story.expire_date != 0 && now > story.expire_date) return null;
        if (story instanceof TL_stories.TL_storyItemSkipped && stateStories != null && stateStories.stories != null) {
            for (int i = 0; i < stateStories.stories.size(); i++) {
                if (stateStories.stories.get(i).id == story.id) {
                    story = stateStories.stories.get(i);
                    break;
                }
            }
        }
        if (story instanceof TL_stories.TL_storyItemSkipped && peerStories != null && peerStories.stories != null) {
            for (int i = 0; i < peerStories.stories.size(); i++) {
                if (peerStories.stories.get(i).id == story.id) {
                    story = peerStories.stories.get(i);
                    break;
                }
            }
        }
        if (story instanceof TL_stories.TL_storyItemDeleted || story instanceof TL_stories.TL_storyItemSkipped
                || story.expire_date != 0 && now > story.expire_date) return null;
        return story;
    }
    public void setStories(TL_stories.PeerStories peerStories) {
        if (peerStories != null && storiesForThisPeer(peerStories) == null) return;
        this.peerStories = peerStories;
        updateStories(true, false);
    }

    public void update() {
        updateStories(true, true);
    }

    public void refreshOnResume() {
        if (!initialized) return;
        updateStories(false, false, true);
    }

    public boolean hasActiveStoryRing() {
        return !isTopic && !app.nimarkogram.messenger.AppConfig.hideStories && hasStoryRing;
    }
    private void updateStories(boolean animated, boolean asUpdate) {
        updateStories(animated, asUpdate, false);
    }

    private void updateStories(boolean animated, boolean asUpdate, boolean preservePresentation) {
        if (app.nimarkogram.messenger.AppConfig.hideStories) return;
        if (isTopic) {
            return;
        }
        final boolean motionEnabled = ProfileAppearanceMotion.isEnabled();
        animated = animated && attached && motionEnabled;
        if (!motionEnabled) cancelStoryBounces();
        final boolean hadStoryRing = hasStoryRing;
        final boolean me = dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
        final int now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        peerStories = storiesForThisPeer(peerStories);
        TL_stories.PeerStories userFullStories = storiesForThisPeer(storiesController.getStoriesFromFullPeer(dialogId));
        TL_stories.PeerStories stateStories = storiesForThisPeer(storiesController.getStories(dialogId));
        if (userFullStories != null || stateStories != null) {
            controllerStoriesObserved = true;
        }
        final TL_stories.PeerStories userStories = userFullStories != null ? userFullStories
                : stateStories != null ? stateStories
                : controllerStoriesObserved ? null : storiesForThisPeer(peerStories);
        int max_read_id = storiesController.getMaxStoriesReadId(dialogId);
        if (userStories != null) {
            max_read_id = Math.max(max_read_id, userStories.max_read_id);
        }
        if (stateStories != null) {
            max_read_id = Math.max(max_read_id, stateStories.max_read_id);
        }
        List<TL_stories.StoryItem> stories = userStories == null || userStories.stories == null ? new ArrayList() : userStories.stories;
        ArrayList<TL_stories.StoryItem> storiesToShow = new ArrayList<>();
        int count = 0;
        final int lastUnreadCount = unreadCount;
        unreadCount = 0;
        if (stories != null) {
            for (int i = 0; i < stories.size(); ++i) {
                TL_stories.StoryItem storyItem = stories.get(i);
                if (storyItem instanceof TL_stories.TL_storyItemDeleted
                        || storyItem.expire_date != 0 && now > storyItem.expire_date) {
                    continue;
                }
                if (storyItem.id > max_read_id) {
                    unreadCount++;
                }
                count++;
            }
            for (int i = 0; i < stories.size(); ++i) {
                TL_stories.StoryItem storyItem = storyForPreview(stories.get(i), stateStories, now);
                if (storyItem == null) continue;
                if (me || storyItem.id > max_read_id) {
                    storiesToShow.add(storyItem);
                    if (storiesToShow.size() >= CIRCLES_MAX) {
                        break;
                    }
                }
            }
        }
        if (storiesToShow.size() < CIRCLES_MAX) {
            for (int i = 0; i < stories.size(); ++i) {
                TL_stories.StoryItem storyItem = storyForPreview(stories.get(i), stateStories, now);
                if (storyItem == null) continue;
                if (!storiesToShow.contains(storyItem)) {
                    storiesToShow.add(storyItem);
                    if (storiesToShow.size() >= CIRCLES_MAX) {
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < circles.size(); ++i) {
            StoryCircle circle = circles.get(i);

            final float oldScale = circle.scale;
            final int oldIndex = circle.index;
            final boolean oldRead = circle.read;
            int index = -1;
            TL_stories.StoryItem storyItem = null;
            for (int j = 0; j < storiesToShow.size(); ++j) {
                TL_stories.StoryItem storyItem2 = storiesToShow.get(j);
                if (storyItem2.id == circle.storyId) {
                    index = j;
                    storyItem = storyItem2;
                    break;
                }
            }

            if (index == -1) {
                circle.scale = 0f;
            } else {
                circle.bindContent(storyItem);
                circle.scale = 1f;
                circle.index = index;
                circle.read = me || storyItem != null && storyItem.id <= max_read_id;
            }
            if (!animated && (!preservePresentation || !motionEnabled || circle.scale != oldScale
                    || circle.index != oldIndex || circle.read != oldRead)) {
                circle.apply();
            }
        }

        for (int i = 0; i < storiesToShow.size(); ++i) {
            TL_stories.StoryItem storyItem = storiesToShow.get(i);

            int index = -1;
            for (int j = 0; j < circles.size(); ++j) {
                StoryCircle circle = circles.get(j);
                if (circle.storyId == storyItem.id) {
                    index = j;
                    break;
                }
            }
            if (index == -1) {
                storyItem.dialogId = dialogId;
                StoryCircle circle = new StoryCircle(storyItem);
                circle.index = i;
                circle.scale = 1f;
                circle.scaleAnimated.set(0f, true);
                circle.read = me || storyItem.id <= max_read_id;
                if (!animated) {
                    circle.apply();
                }
                circles.add(circle);
            }
        }

        mainCircle = null;
        for (int i = 0; i < circles.size(); ++i) {
            StoryCircle circle = circles.get(i);
            if (circle.scale > 0) {
                mainCircle = circle;
                break;
            }
        }
        ArrayList<StoriesController.UploadingStory> uploadingStories = storiesController.getUploadingStories(dialogId);
        uploadingStoriesCount = uploadingStories == null ? 0 : uploadingStories.size();

        int newCount = Math.max(storiesToShow.size(), count);
        if (newCount == 0 && uploadingStoriesCount != 0) {
            newCount = 1;
        }
        if (asUpdate && animated && newCount == this.count + 1 && unreadCount == lastUnreadCount + 1) {
            animateNewStory();
        }
        this.count = newCount;
        hasStoryRing = mainCircle != null || uploadingStoriesCount > 0 || storiesController.isLastUploadingFailed(dialogId);
        if (!animated && (!preservePresentation || !motionEnabled || hasStoryRing != hadStoryRing)) {
            presentedStoryRingPresence = hasStoryRing ? 1f : 0f;
            storyRingPresence.set(presentedStoryRingPresence, true);
        }
        titleDrawable.setText(this.count > 0 ? LocaleController.formatPluralString("Stories", this.count) : "", animated && !LocaleController.isRTL);

        updateGradientColors(animated);
        invalidate();
    }

    private void updateGradientColors(boolean animated) {
        animated = animated && ProfileAppearanceMotion.isEnabled();
        if (dialogId >= 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user != null && DialogObject.isEmojiStatusCollectible(user.emoji_status)) {
                gradientTools.setColor(MessagesController.PeerColor.fromCollectible(user.emoji_status), animated);
            } else {
                gradientTools.setUser(user, animated);
            }
        } else {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            if (chat != null && DialogObject.isEmojiStatusCollectible(chat.emoji_status)) {
                gradientTools.setColor(MessagesController.PeerColor.fromCollectible(chat.emoji_status), animated);
            } else {
                gradientTools.setChat(chat, animated);
            }
        }

    }

    public void updateColors() {

        if (!initialized) return;
        livePaint.setColor(Theme.getColor(Theme.key_stories_circle_live1, resourcesProvider));
        updateGradientColors(attached && ProfileAppearanceMotion.isEnabled());
        if (!ProfileAppearanceMotion.isEnabled()) cancelStoryBounces();
        invalidate();
    }

    private float expandProgress;
    public void setExpandProgress(float progress) {
        if (this.expandProgress != progress) {
            this.expandProgress = progress;
            invalidate();
        }
    }

    private float actionBarProgress;
    public void setActionBarActionMode(float progress) {
        if (Theme.isCurrentThemeDark()) {
            return;
        }
        actionBarProgress = progress;
        invalidate();
    }

    private final RectF rect1 = new RectF();
    private final RectF rect2 = new RectF();
    private final RectF rect3 = new RectF();

    private final Path clipPath = new Path();

    private final AnimatedFloat segmentsCountAnimated = new AnimatedFloat(this, 0, 240 * 2, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat segmentsUnreadCountAnimated = new AnimatedFloat(this, 0, 240, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat progressToUploading = new AnimatedFloat(this, 0, 150, CubicBezierInterpolator.DEFAULT);

    private float newStoryBounceT = 1;
    private ValueAnimator newStoryBounce;

    private AnimatorSet avatarBounce;
    private final Runnable newStoryHaptic = () -> {
        if (canPlayStoryHaptic()) AndroidUtilities.vibrateCursor(this);
    };

    private boolean canPlayStoryHaptic() {
        return initialized && attached && ProfileAppearanceMotion.isEnabled()
                && isShown() && hasWindowFocus() && getWindowVisibility() == VISIBLE;
    }

    private void cancelNewStoryBounce() {
        AndroidUtilities.cancelRunOnUIThread(newStoryHaptic);
        if (newStoryBounce != null) {
            ValueAnimator previous = newStoryBounce;
            newStoryBounce = null;
            previous.removeAllUpdateListeners();
            previous.removeAllListeners();
            previous.cancel();
        }
        newStoryBounceT = 1f;
    }

    private void cancelStoryBounces() {
        final boolean changed = newStoryBounce != null || avatarBounce != null
                || newStoryBounceT != 1f || bounceScale != 1f;
        cancelNewStoryBounce();
        cancelAvatarBounce();
        if (changed) {
            if (avatarImage != null) avatarImage.invalidate();
            invalidate();
        }
    }

    private void cancelAvatarBounce() {
        if (avatarBounce != null) {
            AnimatorSet previous = avatarBounce;
            avatarBounce = null;
            previous.removeAllListeners();
            for (Animator child : previous.getChildAnimations()) {
                if (child instanceof ValueAnimator) ((ValueAnimator) child).removeAllUpdateListeners();
            }
            previous.cancel();
        }
        bounceScale = 1f;
        if (avatarImage != null) avatarImage.bounceScale = 1f;
    }
    private void vibrateNewStory() {
        if (!canPlayStoryHaptic() || SharedConfig.getDevicePerformanceClass() <= SharedConfig.PERFORMANCE_CLASS_LOW) {
            return;
        }
        AndroidUtilities.vibrateCursor(this);
        AndroidUtilities.cancelRunOnUIThread(newStoryHaptic);
        AndroidUtilities.runOnUIThread(newStoryHaptic, 180);
    }

    public void animateNewStory() {
        cancelNewStoryBounce();
        if (!initialized || !attached || !ProfileAppearanceMotion.isEnabled()) return;

        final boolean[] vibrated = new boolean[] { false };

        final ValueAnimator next = ValueAnimator.ofFloat(0, 1);
        newStoryBounce = next;
        next.addUpdateListener(anm -> {
            if (newStoryBounce != anm) return;
            if (!ProfileAppearanceMotion.isEnabled() || !attached) {
                cancelStoryBounces();
                return;
            }
            float t = (float) anm.getAnimatedValue();
            if (!vibrated[0] && t > .2f) {
                vibrated[0] = true;
                vibrateNewStory();
            }
            newStoryBounceT = Math.max(1, t);
            invalidate();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (newStoryBounce != animation) return;
                newStoryBounce = null;
            if (!vibrated[0]) {
                vibrated[0] = true;
                vibrateNewStory();
            }
            newStoryBounceT = 1;
            invalidate();
            }
        });
        next.setInterpolator(new OvershootInterpolator(3.0f));
        next.setDuration(400);
        next.setStartDelay(120);
        next.start();
    }

    public void pauseRingPresentation() {
        if (storyRingPresence != null) storyRingPresence.set(presentedStoryRingPresence, true);
        if (initialized) cancelStoryBounces();
    }

    protected boolean canPresentStoryRing() {
        if (!attached || !isShown() || !hasWindowFocus() || getWindowVisibility() != VISIBLE
                || getWidth() <= 0 || getHeight() <= 0
                || avatarContainer.getWidth() <= 0 || avatarContainer.getHeight() <= 0
                || fragmentTransitionProgress <= 0f || expandProgress >= .2f) return false;
        for (View view = this; view != null;
                view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.getVisibility() != VISIBLE || view.getAlpha() <= 0f) return false;
        }
        return true;
    }

    private float storyRingPresenceForDraw(Canvas canvas) {
        if (!ProfileAppearanceMotion.isEnabled()) {
            presentedStoryRingPresence = hasStoryRing ? 1f : 0f;
            pauseRingPresentation();
        } else if (!canPresentStoryRing()) {
            pauseRingPresentation();
        } else if (canvas.isHardwareAccelerated()) {
            presentedStoryRingPresence = storyRingPresence.set(hasStoryRing ? 1f : 0f);
        }
        return presentedStoryRingPresence;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) pauseRingPresentation();
        else {
            refreshOnResume();
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) pauseRingPresentation();
        else {
            refreshOnResume();
            invalidate();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) pauseRingPresentation();
        else {
            refreshOnResume();
            invalidate();
        }
    }

    float w;

    @Override
    protected void dispatchDraw(Canvas canvas) {
        final boolean motionEnabled = ProfileAppearanceMotion.isEnabled();
        if (!motionEnabled) {
            cancelStoryBounces();
            if (titleDrawable.isAnimating()) titleDrawable.cancelAnimation();
        }
        float rright = rightAnimated.set(this.right, !motionEnabled);
        float avatarPullProgress = Utilities.clamp((avatarContainer.getScaleX() - 1f) / 0.4f, 1f, 0f);
        float insetMain = lerp(dpf2(4f), dpf2(3.5f), avatarPullProgress);
        insetMain *= progressToInsets;
        float ax = avatarContainer.getX() + insetMain * avatarContainer.getScaleX();
        float ay = avatarContainer.getY() + insetMain * avatarContainer.getScaleY();
        float aw = (avatarContainer.getWidth() - insetMain * 2) * avatarContainer.getScaleX();
        float ah = (avatarContainer.getHeight() - insetMain * 2) * avatarContainer.getScaleY();
        rect1.set(ax, ay, ax + aw, ay + ah);

        float maxX = this.left;
        boolean needsSort = false;
        for (int i = 0; i < circles.size(); ++i) {
            StoryCircle circle = circles.get(i);
            circle.cachedScale = circle.scaleAnimated.set(circle.scale, !motionEnabled);
            if (circle.cachedScale <= 0 && circle.scale <= 0) {
                circle.destroy();
                circles.remove(i);
                i--;
                continue;
            }
            circle.cachedIndex = circle.indexAnimated.set(circle.index, !motionEnabled);
            circle.cachedRead = circle.readAnimated.set(circle.read, !motionEnabled);
            if (i > 0 && circles.get(i - 1).cachedIndex > circle.cachedIndex) {
                needsSort = true;
                break;
            }
        }
        if (needsSort) {
            Collections.sort(circles, (a, b) -> (int) (b.cachedIndex - a.cachedIndex));
        }

        final float ringAlpha = storyRingPresenceForDraw(canvas)
                * clamp(fragmentTransitionProgress, 1f, 0f);
        float segmentsAlpha = clamp(1f - expandProgress / 0.2f, 1, 0) * ringAlpha;
        boolean isFailed = storiesController.isLastUploadingFailed(dialogId);
        boolean hasUploadingStories = storiesController.hasUploadingStories(dialogId);
        if (!hasUploadingStories && lastUploadingStory != null && lastUploadingStory.canceled) {
            progressWasDrawn = false;
            progressIsDone = false;
            this.progressToUploading.set(false, true);
        }
        boolean isUploading = (hasUploadingStories && !isFailed) || progressWasDrawn && !progressIsDone;
        float progressToUploading = this.progressToUploading.set(isUploading, !motionEnabled);
        progressToUploading = lerp(0f, progressToUploading, fragmentTransitionProgress);

        canvas.save();
        canvas.scale(bounceScale, bounceScale, rect1.centerX(), rect1.centerY());

        float cy = lerp(rect1.centerY(), this.expandY, expandProgress);

        Paint unreadPaint = null;
        lastUploadingStory = null;
        if (progressToUploading > 0) {
            rect2.set(rect1);
            rect2.inset(-dpf2(2.66f + 2.23f / 2), -dpf2(2.66f + 2.23f / 2));
            unreadPaint = gradientTools.getPaint(rect2);
            if (radialProgress == null) {
                radialProgress = new RadialProgress(this);
                radialProgress.setBackground(null, true, false);
                radialProgress.setRoundRectProgress(ChatObject.isForum(currentAccount, dialogId));
            }
            float uploadingProgress = 0;
            if (!storiesController.hasUploadingStories(dialogId) || storiesController.isLastUploadingFailed(dialogId)) {
                uploadingProgress = 1f;
            } else {
                ArrayList<StoriesController.UploadingStory> uploadingOrEditingStories = storiesController.getUploadingStories(dialogId);
                if (uploadingOrEditingStories != null) {
                    if (uploadingOrEditingStories.size() > 0) {
                        lastUploadingStory = uploadingOrEditingStories.get(0);
                    }
                    for (int i = 0; i < uploadingOrEditingStories.size(); i++) {
                        uploadingProgress += uploadingOrEditingStories.get(i).progress;
                    }
                    uploadingProgress = uploadingProgress / uploadingOrEditingStories.size();
                } else {
                    uploadingProgress = 0f;
                }
            }
            radialProgress.setDiff(0);
            int wasAlpha = unreadPaint.getAlpha();
            unreadPaint.setAlpha((int) (wasAlpha * segmentsAlpha * progressToUploading));
            unreadPaint.setStrokeWidth(dpf2(2.33f));
            radialProgress.setPaint(unreadPaint);
            radialProgress.setProgressRect((int) rect2.left, (int) rect2.top, (int) rect2.right, (int) rect2.bottom);
            radialProgress.setProgress(Utilities.clamp(uploadingProgress, 1f, 0), motionEnabled);
            if (avatarImage.drawAvatar) {
                radialProgress.draw(canvas);
            }
            unreadPaint.setAlpha(wasAlpha);
            progressWasDrawn = true;
            boolean oldIsDone = progressIsDone;
            progressIsDone = radialProgress.getAnimatedProgress() >= 0.98f;
            if (oldIsDone != progressIsDone) {
                segmentsCountAnimated.set(count, true);
                segmentsUnreadCountAnimated.set(unreadCount, true);
                animateBounce();
            }
        } else {
            progressWasDrawn = false;
        }
        if (progressToUploading < 1f) {
            segmentsAlpha = clamp(1f - expandProgress / 0.2f, 1, 0) * (1f - progressToUploading) * ringAlpha;
            final float segmentsCount = segmentsCountAnimated.set(count, !motionEnabled);
            final float segmentsUnreadCount = segmentsUnreadCountAnimated.set(unreadCount, !motionEnabled);

            if (isFailed) {
                rect2.set(rect1);
                rect2.inset(-dpf2(2.66f + 2.23f / 2), -dpf2(2.66f + 2.23f / 2));
                final Paint paint = StoriesUtilities.getErrorPaint(rect2);
                paint.setStrokeWidth(AndroidUtilities.dp(2));
                paint.setAlpha((int) (255 * segmentsAlpha));
                boolean isForum = ChatObject.isForum(currentAccount, dialogId);
                float roundedR = ringCornerRadius(rect2, isForum);
                if (isForum || roundedR >= 0) {
                    float r = roundedR >= 0 ? roundedR : rect2.height() * 0.32f;
                    canvas.drawRoundRect(rect2, r, r, paint);
                } else {
                    canvas.drawCircle(rect2.centerX(), rect2.centerY(), rect2.width() / 2f, paint);
                }
            } else if ((mainCircle != null || uploadingStoriesCount > 0) && segmentsAlpha > 0) {
                rect2.set(rect1);
                rect2.inset(-dpf2(2.66f + 2.23f / 2), -dpf2(2.66f + 2.23f / 2));
                rect3.set(rect1);
                rect3.inset(-dpf2(2.66f + 1.5f / 2), -dpf2(2.66f + 1.5f / 2));
                AndroidUtilities.lerp(rect2, rect3, avatarPullProgress, rect3);

                float separatorAngle = lerp(0, (float) (dpf2(2 + 2.23f) / (rect1.width() * Math.PI) * 360f), clamp(segmentsCount - 1, 1, 0) * segmentsAlpha);
                final float maxCount = 50;

                final int mcount = Math.min(count, (int) maxCount);
                final float animcount = Math.min(segmentsCount, maxCount);

                int gap = mcount > 20 ? 3 : 5;
                if (mcount <= 1) {
                    gap = 0;
                }
                float collapsedGapAngle = gap * 2;

                separatorAngle = lerp(collapsedGapAngle, separatorAngle, avatarPullProgress);

                final float widthAngle = (360 - Math.max(0, animcount) * separatorAngle) / Math.max(1, animcount);
                readPaint.setColor(ColorUtils.blendARGB(0x5affffff, 0x3a000000, actionBarProgress));
                readPaintAlpha = readPaint.getAlpha();
                float a = -90 - separatorAngle / 2f;

                boolean hasLive = false;
                for (int i = 0; i < mcount; ++i) {
                    final boolean isLive = i < circles.size() && circles.get(i).live;
                    if (isLive) hasLive = true;
                }
                if (hasLive) {
                    AndroidUtilities.rectTmp.set(rect3);
                    AndroidUtilities.rectTmp.inset(-dp(12), -dp(12));
                    canvas.saveLayerAlpha(AndroidUtilities.rectTmp, 0xFF, Canvas.ALL_SAVE_FLAG);

                    float bounceScale = 1 + (newStoryBounceT - 1) / 2.5f;

                    if (bounceScale != 1) {
                        canvas.save();
                        canvas.scale(bounceScale, bounceScale, rect2.centerX(), rect2.centerY());
                    }

                    final int wasAlpha = livePaint.getAlpha();
                    livePaint.setAlpha((int) (wasAlpha * segmentsAlpha));
                    AndroidUtilities.rectTmp.set(rect3);
                    AndroidUtilities.rectTmp.inset(-dp(3), -dp(3));
                    livePaint.setStrokeWidth(dpf2(2.5f));
                    drawArc(canvas, rect3, 0, 360, false, livePaint);
                    livePaint.setAlpha(wasAlpha);

                    if (bounceScale != 1) {
                        canvas.restore();
                    }
                } else for (int i = 0; i < mcount; ++i) {
                    final float read = 1f - clamp(segmentsUnreadCount - i, 1, 0);
                    final float appear = 1f - clamp(mcount - animcount - i, 1, 0);
                    if (appear < 0) {
                        continue;
                    }

                    float bounceScale = i == 0 ? 1 + (newStoryBounceT - 1) / 2.5f : 1f;

                    if (bounceScale != 1) {
                        canvas.save();
                        canvas.scale(bounceScale, bounceScale, rect2.centerX(), rect2.centerY());
                    }

                    final boolean isLive = i < circles.size() && circles.get(i).live;
                    Paint paint;
                    if (read < 1) {
                        paint = (isLive ? livePaint : (unreadPaint = gradientTools.getPaint(rect2)));
                        final int wasAlpha = paint.getAlpha();
                        paint.setAlpha((int) (wasAlpha * (1f - read) * segmentsAlpha));
                        paint.setStrokeWidth(dpf2(isLive ? 3 : 2.33f));
                        drawArc(canvas, rect2, a, -widthAngle * appear, false, paint);
                        paint.setAlpha(wasAlpha);
                    }

                    if (read > 0) {
                        paint = isLive ? livePaint : readPaint;
                        final int wasAlpha = paint.getAlpha();
                        paint.setAlpha((int) (wasAlpha * read * segmentsAlpha));
                        paint.setStrokeWidth(dpf2(isLive ? 3 : 1.5f));
                        drawArc(canvas, rect3, a, -widthAngle * appear, false, paint);
                        paint.setAlpha(wasAlpha);
                    }

                    if (bounceScale != 1) {
                        canvas.restore();
                    }

                    a -= widthAngle * appear + separatorAngle * appear;
                }

                if (hasLive) {
                    StoriesUtilities.drawLive(canvas, rect3, segmentsAlpha, avatarImage.getImageReceiver().getVisible(), fragmentTransitionProgress);
                    canvas.restore();
                }
            }
        }

        final float expandRight = getExpandRight();
        if (expandProgress > 0 && segmentsAlpha < 1) {
            float ix = 0;
            w = 0;
            for (int i = 0; i < circles.size(); ++i) {
                StoryCircle circle = circles.get(i);
                float scale = circle.cachedScale;
                w += dp(14) * scale;
            }
            for (int i = 0; i < circles.size(); ++i) {
                StoryCircle circle = circles.get(i);

                float scale = circle.cachedScale;
                float read = circle.cachedRead;

                float r = dp(28) / 2f * scale;
                float cx = left + r + ix;
                ix += dp(18) * scale;

                maxX = Math.max(maxX, cx + r);

                rect2.set(cx - r, cy - r, cx + r, cy + r);
                lerpCentered(rect1, rect2, expandProgress, rect3);

                circle.cachedRect.set(rect3);
                circle.borderRect.set(rect3);
                final float inset = lerp(dpf2(2.66f), lerp(dpf2(1.33f), dpf2(2.33f), expandProgress), read * expandProgress);
                circle.borderRect.inset(-inset * scale, -inset * scale);
            }
            readPaint.setColor(ColorUtils.blendARGB(0x5affffff, 0x80BBC4CC, expandProgress));
            readPaintAlpha = readPaint.getAlpha();
            unreadPaint = gradientTools.getPaint(rect2);
            unreadPaint.setStrokeWidth(lerp(dpf2(2.33f), dpf2(1.5f), expandProgress));
            readPaint.setStrokeWidth(lerp(dpf2(1.125f), dpf2(1.5f), expandProgress));
            livePaint.setStrokeWidth(lerp(dpf2(1.125f), dpf2(1.5f), expandProgress));
            for (int i = 0; i < circles.size(); ++i) {
                StoryCircle B = circles.get(i);
                StoryCircle A = nearest(i - 2 >= 0 ? circles.get(i - 2) : null, i - 1 >= 0 ? circles.get(i - 1) : null, B);
                StoryCircle C = nearest(i + 1 < circles.size() ? circles.get(i + 1) : null, i + 2 < circles.size() ? circles.get(i + 2) : null, B);

                if (A != null && (
                        Math.abs(A.borderRect.centerX() - B.borderRect.centerX()) < Math.abs(B.borderRect.width() / 2f - A.borderRect.width() / 2f) ||
                                Math.abs(A.borderRect.centerX() - B.borderRect.centerX()) > A.borderRect.width() / 2f + B.borderRect.width() / 2f
                )) {
                    A = null;
                }
                if (C != null && (
                        Math.abs(C.borderRect.centerX() - B.borderRect.centerX()) < Math.abs(B.borderRect.width() / 2f - C.borderRect.width() / 2f) ||
                                Math.abs(C.borderRect.centerX() - B.borderRect.centerX()) > C.borderRect.width() / 2f + B.borderRect.width() / 2f
                )) {
                    C = null;
                }

                if (B.cachedRead < 1) {
                    int wasAlpha = unreadPaint.getAlpha();
                    unreadPaint.setAlpha((int) (wasAlpha * B.cachedScale * (1f - B.cachedRead) * (1f - segmentsAlpha)));
                    drawArcs(canvas, A, B, C, unreadPaint);
                    unreadPaint.setAlpha(wasAlpha);
                }
                if (B.cachedRead > 0) {
                    Paint paint = B.live ? livePaint : readPaint;
                    final int wasAlpha = paint.getAlpha();
                    paint.setAlpha((int) (wasAlpha * B.cachedScale * B.cachedRead * (1f - segmentsAlpha)));
                    drawArcs(canvas, A, B, C, paint);
                    paint.setAlpha(wasAlpha);
                }
            }
            canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (0xFF * expandProgress * (1f - segmentsAlpha)), Canvas.ALL_SAVE_FLAG);
            for (int i = circles.size() - 1; i >= 0; i--) {
                StoryCircle circle = circles.get(i);
                if (!circle.imageReceiver.getVisible()) {
                    continue;
                }
                int r = canvas.getSaveCount();
                final StoryCircle nextCircle = nearest(i - 1 >= 0 ? circles.get(i - 1) : null, i - 2 >= 0 ? circles.get(i - 2) : null, circle);
                clipCircle(canvas, circle, nextCircle);
                circle.imageReceiver.setImageCoords(circle.cachedRect);
                circle.imageReceiver.draw(canvas);
                canvas.restoreToCount(r);
            }
            canvas.restore();
        }

        if (unreadPaint != null) {
            unreadPaint.setStrokeWidth(dpf2(2.3f));
        }

        canvas.restore();

        float titleAlpha = Math.max(0, (expandProgress - .5f) * 2f);
        if (titleAlpha > 0) {
            float left = lerp(rect1.right + dp(16), maxX + dp(12), expandProgress);
            float right = lerp(getWidth(), rright, expandProgress);
            float y = lerp(rect1.centerY(), this.cy, expandProgress);
            titleDrawable.setBounds((int) (left), (int) (y - dp(18)), (int) (right), (int) (y  + dp(18)));
            titleDrawable.setAlpha((int) (0xFF * titleAlpha));
            titleDrawable.draw(canvas);
        }
    }

    private void animateBounce() {
        if (!initialized || !attached || !ProfileAppearanceMotion.isEnabled()) {
            cancelStoryBounces();
            return;
        }
        cancelAvatarBounce();
        final AnimatorSet animatorSet = new AnimatorSet();
        avatarBounce = animatorSet;
        ValueAnimator inAnimator = ValueAnimator.ofFloat(1, 1.05f);
        inAnimator.setDuration(100);
        inAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);

        ValueAnimator outAnimator = ValueAnimator.ofFloat(1.05f, 1f);
        outAnimator.setDuration(250);
        outAnimator.setInterpolator(new OvershootInterpolator());

        ValueAnimator.AnimatorUpdateListener updater = animation -> {
            if (avatarBounce != animatorSet) return;
            if (!ProfileAppearanceMotion.isEnabled() || !attached) {
                cancelStoryBounces();
                return;
            }
            avatarImage.bounceScale = bounceScale = (float) animation.getAnimatedValue();
            avatarImage.invalidate();
            invalidate();
        };
        inAnimator.addUpdateListener(updater);
        outAnimator.addUpdateListener(updater);
        animatorSet.playSequentially(inAnimator, outAnimator);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (avatarBounce != animation) return;
                avatarBounce = null;
                avatarImage.bounceScale = bounceScale = 1f;
                avatarImage.invalidate();
                invalidate();
            }
        });
        animatorSet.start();
    }

    private void clipCircle(Canvas canvas, StoryCircle circle, StoryCircle nextCircle) {
        if (nextCircle == null) {
            return;
        }

        AndroidUtilities.rectTmp.set(nextCircle.cachedRect);
        final float inset = dpf2(1.66f) * nextCircle.cachedScale;
        AndroidUtilities.rectTmp.inset(-inset, -inset);
        float xA = nextCircle.cachedRect.centerX(), rA = nextCircle.cachedRect.width() / 2f;
        float xB = circle.cachedRect.centerX(), rB = circle.cachedRect.width() / 2f;

        clipPath.rewind();
        float mx, d;
        if (xA > xB) {
            mx = ((xA - rA) + (xB + rB)) / 2f;
            d = Math.abs(mx - xB);
            float angle = (float) Math.toDegrees(Math.acos(d / rB));
            clipPath.arcTo(AndroidUtilities.rectTmp, 180 + angle, -angle * 2);
            clipPath.arcTo(circle.cachedRect, angle, 360 - angle * 2);
        } else {
            mx = ((xA + rA) + (xB - rB)) / 2f;
            d = Math.abs(mx - xB);
            float angle = (float) Math.toDegrees(Math.acos(d / rB));
            clipPath.arcTo(AndroidUtilities.rectTmp, -angle, angle * 2);
            clipPath.arcTo(circle.cachedRect, 180 - angle, -(360 - angle * 2));
        }
        clipPath.close();
        canvas.save();
        canvas.clipPath(clipPath);
    }

    private StoryCircle nearest(StoryCircle a, StoryCircle b, StoryCircle c) {
        if (c == null || a == null && b == null) {
            return null;
        } else if (a == null || b == null) {
            if (a != null) {
                return a;
            }
            return b;
        }
        float ad = Math.min(Math.abs(a.borderRect.left - c.borderRect.right), Math.abs(a.borderRect.right - c.borderRect.left));
        float bd = Math.min(Math.abs(b.borderRect.left - c.borderRect.right), Math.abs(b.borderRect.right - c.borderRect.left));
        if (ad > bd) {
            return a;
        }
        return b;
    }

    private final Path forumRoundRectPath = new Path();
    private final Matrix forumRoundRectMatrix = new Matrix();
    private final PathMeasure forumRoundRectPathMeasure = new PathMeasure();
    private final Path forumSegmentPath = new Path();

    private float ringCornerRadius(RectF oval, boolean isForum) {
        if (app.nimarkogram.messenger.AppConfig.avatarCorners >= app.nimarkogram.messenger.AppConfig.AVATAR_CORNERS_MAX) {
            return -1;
        }
        float ringDp = oval.width() / org.telegram.messenger.AndroidUtilities.density;
        int r = isForum
                ? app.nimarkogram.messenger.AppConfig.getAvatarCornersForChat(ringDp, true)
                : app.nimarkogram.messenger.AppConfig.getAvatarCorners(ringDp, false);
        if (r >= oval.width() / 2f) {
            return -1; // behaves like a circle anyway
        }
        return r;
    }

    private void drawArc(Canvas canvas, RectF oval, float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
        boolean isForum = ChatObject.isForum(currentAccount, dialogId);
        float roundedR = ringCornerRadius(oval, isForum);
        if (isForum || roundedR >= 0) {
            float r = roundedR >= 0 ? roundedR : oval.height() * 0.32f;
            if (Math.abs(sweepAngle) == 360) {
                canvas.drawRoundRect(oval, r, r, paint);
                return;
            }
            startAngle = startAngle + sweepAngle;
            float endAngle = startAngle - sweepAngle;
            float rotateAngle = (((int) (startAngle)) / 90) * 90;

            float pathAngleStart = -199 + rotateAngle;
            float percentFrom = (startAngle - pathAngleStart) / 360;
            float percentTo = (endAngle - pathAngleStart) / 360;
            forumRoundRectPath.rewind();
            forumRoundRectPath.addRoundRect(oval, r, r, Path.Direction.CW);
            forumRoundRectMatrix.reset();
            forumRoundRectMatrix.postRotate(rotateAngle, oval.centerX(), oval.centerY());
            forumRoundRectPath.transform(forumRoundRectMatrix);

            forumRoundRectPathMeasure.setPath(forumRoundRectPath, false);
            float length = forumRoundRectPathMeasure.getLength();

            forumSegmentPath.reset();
            forumRoundRectPathMeasure.getSegment(length * percentFrom, length * percentTo, forumSegmentPath, true);
            forumSegmentPath.rLineTo(0, 0);
            canvas.drawPath(forumSegmentPath, paint);
        } else {
            canvas.drawArc(oval, startAngle, sweepAngle, useCenter, paint);
        }
    }

    private void drawArcs(Canvas canvas, StoryCircle A, StoryCircle B, StoryCircle C, Paint paint) {
        if (A == null && C == null) {
            drawArc(canvas, B.borderRect, 0, 360, false, paint);
        } else if (A != null && C != null) {
            float xA = A.borderRect.centerX(), rA = A.borderRect.width() / 2f;
            float xB = B.borderRect.centerX(), rB = B.borderRect.width() / 2f;
            float xC = C.borderRect.centerX(), rC = C.borderRect.width() / 2f;

            boolean d1, d2;
            float mx, d, angle, angle1, angle2;
            if (d1 = xA > xB) {
                mx = ((xA - rA) + (xB + rB)) / 2f;
                d = Math.abs(mx - xB);
                angle1 = (float) Math.toDegrees(Math.acos(d / rB));
            } else {
                mx = ((xA + rA) + (xB - rB)) / 2f;
                d = Math.abs(mx - xB);
                angle1 = (float) Math.toDegrees(Math.acos(d / rB));
            }

            if (d2 = xC > xB) {
                mx = ((xC - rC) + (xB + rB)) / 2f;
                d = Math.abs(mx - xB);
                angle2 = (float) Math.toDegrees(Math.acos(d / rB));
            } else {
                mx = ((xC + rC) + (xB - rB)) / 2f;
                d = Math.abs(mx - xB);
                angle2 = (float) Math.toDegrees(Math.acos(d / rB));
            }

            if (d1 && d2) {
                angle = Math.max(angle1, angle2);
                drawArc(canvas, B.borderRect, angle, 360 - angle * 2, false, paint);
            } else if (d1) { // d1 && !d2
                drawArc(canvas, B.borderRect, 180 + angle2, 180 - (angle1 + angle2), false, paint);
                drawArc(canvas, B.borderRect, angle1, 180 - angle2 - angle1, false, paint);
            } else if (d2) { // !d1 && d2
                drawArc(canvas, B.borderRect, 180 + angle1, 180 - (angle2 + angle1), false, paint);
                drawArc(canvas, B.borderRect, angle2, 180 - angle2 - angle1, false, paint);
            } else { // !d1 && !d2
                angle = Math.max(angle1, angle2);
                drawArc(canvas, B.borderRect, 180 + angle, 360 - angle * 2, false, paint);
            }

        } else if (A != null || C != null) {
            if (A == null) {
                A = C;
            }
            float xA = A.borderRect.centerX(), rA = A.borderRect.width() / 2f;
            float xB = B.borderRect.centerX(), rB = B.borderRect.width() / 2f;

            if (Math.abs(xA - xB) > rA + rB) {
                drawArc(canvas, B.borderRect, 0, 360, false, paint);
            } else {
                float mx, d;
                if (xA > xB) {
                    mx = ((xA - rA) + (xB + rB)) / 2f;
                    d = Math.abs(mx - xB);
                    float angle = (float) Math.toDegrees(Math.acos(d / rB));
                    drawArc(canvas, B.borderRect, angle, 360 - angle * 2, false, paint);
                } else {
                    mx = ((xA + rA) + (xB - rB)) / 2f;
                    d = Math.abs(mx - xB);
                    float angle = (float) Math.toDegrees(Math.acos(d / rB));
                    drawArc(canvas, B.borderRect, 180 + angle, 360 - angle * 2, false, paint);
                }
            }
        }
    }

    private void lerpCentered(RectF a, RectF b, float t, RectF c) {
        float cx = lerp(a.centerX(), b.centerX(), t);
        float cy = lerp(a.centerY(), b.centerY(), t);
        float r = lerp(
            Math.min(a.width(), a.height()),
            Math.min(b.width(), b.height()),
            t
        ) / 2f;
        c.set(cx - r, cy - r, cx + r, cy + r);
    }

    private float left, right, cy;
    private float expandRight, expandY;
    private boolean expandRightPad;
    private final AnimatedFloat expandRightPadAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat rightAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    public void setBounds(float left, float right, float cy, boolean animated) {
        boolean changed = Math.abs(left - this.left) > 0.1f || Math.abs(right - this.right) > 0.1f || Math.abs(cy - this.cy) > 0.1f;
        this.left = left;
        this.right = right;
        if (!animated || !ProfileAppearanceMotion.isEnabled()) {
            this.rightAnimated.set(this.right, true);
        }
        this.cy = cy;
        if (changed) {
            invalidate();
        }
    }

    public void setExpandCoords(float right, boolean rightPadded, float y) {
        this.expandRight = right;
        this.expandRightPad = rightPadded;
        this.expandY = y;
        invalidate();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.storiesUpdated && account == currentAccount) {
            updateStories(true, true);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        attached = true;
        for (int i = 0; i < circles.size(); ++i) {
            circles.get(i).imageReceiver.onAttachedToWindow();
        }

        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.storiesUpdated);
        refreshOnResume();
    }

    @Override
    protected void onDetachedFromWindow() {
        pauseRingPresentation();
        super.onDetachedFromWindow();

        attached = false;
        for (int i = 0; i < circles.size(); ++i) {
            circles.get(i).imageReceiver.onDetachedFromWindow();
        }

        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.storiesUpdated);
    }

    private final StoryViewer.PlaceProvider provider = new StoryViewer.PlaceProvider() {
        @Override
        public boolean findView(long dialogId, int messageId, int storyId, int type, StoryViewer.TransitionViewHolder holder) {
            holder.avatarImage = null;
            holder.storyImage = null;
            if (expandProgress < .2f) {
                holder.avatarImage = avatarImage.getImageReceiver();
                holder.storyImage = null;
                holder.view = avatarImage;
                holder.clipTop = 0;
                holder.clipBottom = AndroidUtilities.displaySize.y;
                holder.clipParent = (View) getParent();
                holder.radialProgressUpload = radialProgress;
                holder.checkParentScale = true;
                return true;
            }

            StoryCircle a = null, b = null;
            ImageReceiver imageReceiver = null;
            for (int i = 0; i < circles.size(); ++i) {
                StoryCircle circle = circles.get(i);
                if (circle.scale >= 1 && circle.storyId == storyId) {
                    a = circle;
                    b = nearest(i - 1 >= 0 ? circles.get(i - 1) : null, i - 2 >= 0 ? circles.get(i - 2) : null, circle);
                    imageReceiver = circle.imageReceiver;
                    break;
                }
            }
            if (imageReceiver == null) {
                return false;
            }

            holder.storyImage = imageReceiver;
            holder.avatarImage = null;
            holder.view = ProfileStoriesView.this;
            holder.clipTop = 0;
            holder.clipBottom = AndroidUtilities.displaySize.y;
            holder.clipParent = (View) getParent();
            if (a != null && b != null) {
                final RectF aRect = new RectF(a.cachedRect), bRect = new RectF(b.cachedRect);
                final StoryCircle circle = a, nextCircle = b;
                holder.drawClip = (canvas, bounds, alpha, opening) -> {
                    aRect.set(circle.cachedRect);
                    bRect.set(nextCircle.cachedRect);
                    circle.cachedRect.set(bounds);

                    try {
                        float scale = bounds.width() / aRect.width();
                        float bcx = bounds.centerX() - (aRect.centerX() - bRect.centerX()) * (scale + 2f * (1f - alpha));
                        float bcy = bounds.centerY(); // bounds.centerX() - (aRect.centerY() - bRect.centerY()) * scale;
                        float w2 = bRect.width() / 2f * scale, h2 = bRect.height() / 2f * scale;
                        nextCircle.cachedRect.set(bcx - w2, bcy - h2, bcx + w2, bcy + h2);
                    } catch (Exception ignore) {}

                    clipCircle(canvas, circle, nextCircle);

                    circle.cachedRect.set(aRect);
                    nextCircle.cachedRect.set(bRect);
                };
            } else {
                holder.drawClip = null;
            }
            return true;
        }

        @Override
        public void preLayout(long currentDialogId, int messageId, Runnable o) {
            updateStories(true, false);
            o.run();
        }
    };

    public boolean isEmpty() {
        return circles.isEmpty();
    }

    protected void onTap(StoryViewer.PlaceProvider provider) {

    }

    protected void onLongPress() {

    }

    private Runnable onLongPressRunnable = () -> onLongPress();

    private long tapTime;
    private float tapX, tapY;

    private float getExpandRight() {
        return expandRight - expandRightPadAnimated.set(expandRightPad, !ProfileAppearanceMotion.isEnabled()) * dp(71);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean hit;
        if (expandProgress < .9f) {
            hit = rect2.contains(event.getX(), event.getY());
        } else {
            hit = event.getX() >= left && event.getX() <= right && Math.abs(event.getY() - cy) < dp(32);
        }
        if (hit && event.getAction() == MotionEvent.ACTION_DOWN) {
            tapTime = System.currentTimeMillis();
            tapX = event.getX();
            tapY = event.getY();
            AndroidUtilities.cancelRunOnUIThread(onLongPressRunnable);
            AndroidUtilities.runOnUIThread(onLongPressRunnable, ViewConfiguration.getLongPressTimeout());
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            AndroidUtilities.cancelRunOnUIThread(onLongPressRunnable);
            if (hit && System.currentTimeMillis() - tapTime <= ViewConfiguration.getTapTimeout() && MathUtils.distance(tapX, tapY, event.getX(), event.getY()) <= AndroidUtilities.dp(12) && (storiesController.hasUploadingStories(dialogId) || storiesController.hasStories(dialogId) || !circles.isEmpty())) {
                onTap(provider);
                return true;
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            tapTime = -1;
            AndroidUtilities.cancelRunOnUIThread(onLongPressRunnable);
        }
        return super.onTouchEvent(event);
    }

    @Keep
    public void setFragmentTransitionProgress(float fragmentTransitionProgress) {
        if (this.fragmentTransitionProgress == fragmentTransitionProgress) {
            return;
        }
        this.fragmentTransitionProgress = fragmentTransitionProgress;
        invalidate();
    }

    @Keep
    public float getFragmentTransitionProgress() {
        return fragmentTransitionProgress;
    }
}
