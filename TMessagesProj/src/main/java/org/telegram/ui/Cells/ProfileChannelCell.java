/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ClickableAnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LoadingDrawable;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.StoriesListPlaceProvider;

import app.nimarkogram.messenger.utils.ProfileAppearanceMotion;
import app.nimarkogram.messenger.utils.UiAnimationClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import java.util.function.BooleanSupplier;
import java.util.HashSet;
public class ProfileChannelCell extends FrameLayout implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;

    private final int currentAccount;
    private final TextView headerView;
    private final AnimatedTextView subscribersView;

    public final DialogCell dialogCell;

    public ProfileChannelCell(BaseFragment fragment) {
        this(fragment.getContext(), fragment);
    }

    public ProfileChannelCell(Context context, BaseFragment fragment) {
        super(context);
        this.resourcesProvider = fragment.getResourceProvider();

        currentAccount = fragment.getCurrentAccount();
        LinearLayout headerLayout = new LinearLayout(context);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        addView(headerLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP, 16.66f, 11.6f, 16.66f, 0));

        headerView = new TextView(context);
        headerView.setTypeface(AndroidUtilities.bold());
        headerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        headerView.setText(LocaleController.getString(R.string.ProfileChannel));
        headerLayout.addView(headerView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        subscribersView = new ClickableAnimatedTextView(context);
        subscribersView.getDrawable().setHacks(true, true, true);
        subscribersView.setAnimationProperties(.3f, 0, 165, CubicBezierInterpolator.EASE_OUT_QUINT);
        subscribersView.setTypeface(AndroidUtilities.bold());
        subscribersView.setTextSize(dp(11));
        subscribersView.setPadding(dp(4.33f), 0, dp(4.33f), 0);
        subscribersView.setGravity(Gravity.LEFT);
        headerLayout.addView(subscribersView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 17, Gravity.LEFT | Gravity.TOP, 4, 1, 4, 0));

        dialogCell = new DialogCell(null, context, false, true, currentAccount, resourcesProvider);
        dialogCell.setBackgroundColor(0);
        dialogCell.setDialogCellDelegate(new DialogCell.DialogCellDelegate() {
            @Override
            public void onButtonClicked(DialogCell dialogCell) {

            }

            @Override
            public void onButtonLongPress(DialogCell dialogCell) {

            }

            @Override
            public boolean canClickButtonInside() {
                return true;
            }

            @Override
            public void openStory(DialogCell dialogCell, Runnable onDone) {
                if (fragment.getMessagesController().getStoriesController().hasStories(dialogCell.getDialogId())) {
                    fragment.getOrCreateStoryViewer().doOnAnimationReady(onDone);
                    fragment.getOrCreateStoryViewer().open(fragment.getContext(), dialogCell.getDialogId(), StoriesListPlaceProvider.of(ProfileChannelCell.this));
                    return;
                }
            }

            @Override
            public void showChatPreview(DialogCell dialogCell) {

            }

            @Override
            public void openHiddenStories() {
                StoriesController storiesController = fragment.getMessagesController().getStoriesController();
                if (storiesController.getHiddenList().isEmpty()) {
                    return;
                }
                boolean unreadOnly = storiesController.getUnreadState(DialogObject.getPeerDialogId(storiesController.getHiddenList().get(0).peer)) != StoriesController.STATE_READ;
                ArrayList<Long> peerIds = new ArrayList<>();
                for (int i = 0; i < storiesController.getHiddenList().size(); i++) {
                    long dialogId = DialogObject.getPeerDialogId(storiesController.getHiddenList().get(i).peer);
                    if (!unreadOnly || storiesController.getUnreadState(dialogId) != StoriesController.STATE_READ) {
                        peerIds.add(dialogId);
                    }
                }

                fragment.getOrCreateStoryViewer().open(context, null, peerIds, 0, null, null, StoriesListPlaceProvider.of(ProfileChannelCell.this), false);
            }
        });
        dialogCell.avatarStart = 15;
        dialogCell.messagePaddingStart = 83;
        addView(dialogCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM));

        updateColors();

        setWillNotDraw(false);

        loadingDrawable = new LoadingDrawable();
        loadingDrawable.setColors(
                Theme.multAlpha(Theme.getColor(Theme.key_listSelector, resourcesProvider), 1.25f),
                Theme.multAlpha(Theme.getColor(Theme.key_listSelector, resourcesProvider), .8f)
        );
        loadingDrawable.setRadiiDp(8);
    }

    private boolean loading;
    private final AnimatedFloat loadingAlpha = new AnimatedFloat(320, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat contentAlpha = new AnimatedFloat(220, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final LoadingDrawable loadingDrawable;
    private final Paint contentMaskPaint = new Paint();

    private final Rect previewVisibleRect = new Rect();
    private PreviewState previewState;
    private long previewBinding;
    private int boundMessageId;
    private int previewCaptureDepth;
    private PreviewReplayObserver previewReplayObserver;
    private PreviewRevealObserver previewRevealObserver;

    public static class PreviewState {
        private final BooleanSupplier presentationVisible, canStartReveal;
        private int account = -1;
        private long channelId;
        private long binding;
        private boolean loadingPresented;
        private boolean loading;
        private boolean revealPending;
        private boolean revealing;
        private float loadingValue, contentValue = 1f;
        private long elapsed, lastFrame = -1;
        private ProfileChannelCell presentingCell;
        private PreviewReplayObserver replayObserver;
        private PreviewRevealObserver revealObserver;

        public PreviewState(BooleanSupplier presentationVisible, BooleanSupplier canStartReveal) {
            this.presentationVisible = presentationVisible;
            this.canStartReveal = canStartReveal;
        }

        private long bind(ProfileChannelCell cell, int currentAccount, long id, boolean nextLoading) {
            if (replayObserver != null) replayObserver.stop(false);
            if (revealObserver != null) revealObserver.stop();
            if (presentingCell != cell && !cell.isPreviewPresentationVisible()) suspend(presentingCell);
            if (account != currentAccount || channelId != id) {
                account = currentAccount;
                channelId = id;
                loading = nextLoading;
                finish();
            } else if (loading != nextLoading) {
                loading = nextLoading;
                if (!loading && loadingPresented && previewMotionEnabled()) {
                    loadingPresented = false;
                    revealPending = true;
                    revealing = false;
                    elapsed = 0;
                    suspend(presentingCell);
                } else {
                    finish();
                }
            }
            if (!previewMotionEnabled()) finish();
            return ++binding;
        }

        public void finish() {
            if (replayObserver != null) replayObserver.stop(false);
            if (revealObserver != null) revealObserver.stop();
            loadingPresented = revealPending = revealing = false;
            loadingValue = loading ? 1f : 0f;
            contentValue = loading ? 0f : 1f;
            elapsed = 0;
            suspend(presentingCell);
        }

        public void onPresentationChanged() {
            if (revealObserver != null) revealObserver.onPreDraw();
        }

        private void suspend(ProfileChannelCell cell) {
            if (presentingCell != cell) return;
            presentingCell = null;
            lastFrame = -1;
        }

        private boolean present(ProfileChannelCell cell) {
            if (loading) {
                loadingPresented = true;
                return true;
            }
            if (!canStartReveal.getAsBoolean()) {
                suspend(presentingCell);
                return false;
            }
            if (revealPending) {
                revealPending = false;
                revealing = true;
            }
            if (!revealing) return false;
            final long now = UiAnimationClock.now();
            if (lastFrame >= 0) {
                elapsed += Math.min(34L, Math.max(0L, now - lastFrame));
            }
            presentingCell = cell;
            lastFrame = now;
            loadingValue = 1f - CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Math.min(1f, elapsed / 320f));
            contentValue = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Math.min(1f, elapsed / 220f));
            if (elapsed >= 320) finish();
            return revealing;
        }
    }

    private static boolean previewMotionEnabled() {
        return ProfileAppearanceMotion.isEnabled();
    }

    private boolean matchesPreviewIdentity() {
        return previewState != null && previewState.account == currentAccount && previewState.channelId == boundChannelId;
    }

    private boolean ownsPreviewState() {
        return matchesPreviewIdentity() && previewState.binding == previewBinding;
    }

    private boolean isPreviewPresentationVisible() {
        if (UiAnimationClock.isPaused()
                || !isAttachedToWindow() || !isShown() || !hasWindowFocus() || getWindowVisibility() != View.VISIBLE
                || getWidth() <= 0 || getHeight() <= 0 || !getGlobalVisibleRect(previewVisibleRect)
                || !previewState.presentationVisible.getAsBoolean()) return false;
        for (View view = this; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.getAlpha() <= 0f) return false;
        }
        return true;
    }

    private boolean canPresentPreview(Canvas canvas) {
        return canvas.isHardwareAccelerated() && isPreviewPresentationVisible()
                && !canvas.quickReject(dialogCell.getX() + dp(dialogCell.messagePaddingStart + 2),
                        dialogCell.getY() + dp(31), dialogCell.getRight(), dialogCell.getBottom(), Canvas.EdgeType.AA);
    }

    private class PreviewReplayObserver implements ViewTreeObserver.OnDrawListener {
        final PreviewState state = previewState;
        final long binding = previewBinding;
        final ViewTreeObserver observer = getViewTreeObserver();
        boolean stopped;

        @Override
        public void onDraw() {
            if (stopped) return;
            if (previewReplayObserver != this || state.replayObserver != this || state != previewState
                    || !ownsPreviewState() || binding != previewBinding
                    || !loading || !state.loading || state.loadingPresented
                    || !previewMotionEnabled() || !observer.isAlive()
                    || observer != getViewTreeObserver()) {
                stop(true);
                return;
            }
            if (previewCaptureDepth > 0 || !isHardwareAccelerated()
                    || !isPreviewPresentationVisible()
                    || !getLocalVisibleRect(previewVisibleRect)
                    || !previewVisibleRect.intersect(
                            (int) (dialogCell.getX() + dp(dialogCell.messagePaddingStart + 2)),
                            (int) (dialogCell.getY() + dp(31)),
                            dialogCell.getRight(), dialogCell.getBottom())) return;
            state.loadingPresented = true;
            stop(true);
        }

        void stop(boolean fromDraw) {
            if (stopped) return;
            stopped = true;
            if (previewReplayObserver == this) previewReplayObserver = null;
            if (state.replayObserver == this) state.replayObserver = null;
            if (fromDraw) {
                post(this::remove);
            } else {
                remove();
            }
        }

        private void remove() {
            if (observer.isAlive()) observer.removeOnDrawListener(this);
        }
    }

    private void stopPreviewReplayObservation() {
        if (previewReplayObserver != null) previewReplayObserver.stop(false);
    }

    private void watchRecordedPreviewLoader() {
        if (!ownsPreviewState() || !loading || previewState.loadingPresented
                || !previewMotionEnabled() || !isAttachedToWindow()
                || !isHardwareAccelerated()) return;
        if (previewReplayObserver != null) return;
        final PreviewReplayObserver observer = new PreviewReplayObserver();
        if (!observer.observer.isAlive()) return;
        previewReplayObserver = previewState.replayObserver = observer;
        observer.observer.addOnDrawListener(observer);
    }

    private boolean needsPreviewRevealWake() {
        return ownsPreviewState() && !loading && !previewState.loading
                && (previewState.revealPending
                || previewState.revealing && previewState.presentingCell != this);
    }

    private class PreviewRevealObserver implements ViewTreeObserver.OnPreDrawListener {
        final PreviewState state = previewState;
        final long binding = previewBinding;
        final ViewTreeObserver observer = getViewTreeObserver();
        boolean stopped;

        @Override
        public boolean onPreDraw() {
            if (stopped || previewCaptureDepth > 0) return true;
            if (previewRevealObserver != this || state.revealObserver != this || state != previewState
                    || binding != previewBinding || !needsPreviewRevealWake()
                    || !isAttachedToWindow()
                    || !observer.isAlive() || observer != getViewTreeObserver()) {
                stop();
                return true;
            }
            if (!previewMotionEnabled()) {
                state.finish();
                invalidate();
                return true;
            }
            if (!isHardwareAccelerated() || !isPreviewPresentationVisible()
                    || !state.canStartReveal.getAsBoolean()
                    || !getLocalVisibleRect(previewVisibleRect)
                    || !previewVisibleRect.intersect(
                            (int) (dialogCell.getX() + dp(dialogCell.messagePaddingStart + 2)),
                            (int) (dialogCell.getY() + dp(31)),
                            dialogCell.getRight(), dialogCell.getBottom())) return true;
            stop();
            invalidate();
            return true;
        }

        void stop() {
            if (stopped) return;
            stopped = true;
            if (previewRevealObserver == this) previewRevealObserver = null;
            if (state.revealObserver == this) state.revealObserver = null;
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        }
    }

    private void stopPreviewRevealObservation() {
        if (previewRevealObserver != null) previewRevealObserver.stop();
    }

    private void watchPreviewReveal() {
        if (previewCaptureDepth > 0 || !needsPreviewRevealWake() || !previewMotionEnabled()
                || !isAttachedToWindow() || !isHardwareAccelerated() || previewRevealObserver != null) return;
        final PreviewRevealObserver observer = new PreviewRevealObserver();
        if (!observer.observer.isAlive()) return;
        previewRevealObserver = previewState.revealObserver = observer;
        observer.observer.addOnPreDrawListener(observer);
    }

    public void beginPreviewCapture() {
        previewCaptureDepth++;
    }

    public void endPreviewCapture() {
        if (previewCaptureDepth > 0) previewCaptureDepth--;
    }
    @Override
    protected void dispatchDraw(Canvas canvas) {
        float loading, content;
        final boolean capturingPreview = previewCaptureDepth > 0;
        boolean invalidatePreview = !capturingPreview && previewState == null;
        if (matchesPreviewIdentity() && previewState.loading == this.loading) {
            if (!capturingPreview && ownsPreviewState()) {
                if (!previewMotionEnabled()) previewState.finish();
                else if (canPresentPreview(canvas)) invalidatePreview = previewState.present(this);
                else if (canvas.isHardwareAccelerated()) previewState.suspend(this);
            }
            loading = previewState.loadingValue;
            content = previewState.contentValue;
        } else if (previewState != null) {
            loading = this.loading ? 1f : 0f;
            content = this.loading ? 0f : 1f;
        } else if (capturingPreview) {
            loading = loadingAlpha.get();
            content = contentAlpha.get();
        } else {
            loading = loadingAlpha.set(this.loading);
            content = contentAlpha.set(this.loading ? 0f : 1f);
        }
        super.dispatchDraw(canvas);

        if (content < 1f) {
            contentMaskPaint.setAlpha(Math.round(255f * (1f - content)));
            canvas.drawRect(
                    dialogCell.getX() + dp(dialogCell.messagePaddingStart + 2),
                    dialogCell.getY() + dp(31),
                    dialogCell.getRight(),
                    dialogCell.getBottom(),
                    contentMaskPaint
            );
            canvas.drawRect(
                    dialogCell.getRight() - dp(76),
                    dialogCell.getY() + dp(4),
                    dialogCell.getRight(),
                    dialogCell.getY() + dp(27),
                    contentMaskPaint
            );
            if (invalidatePreview) invalidate();
        }
        if (loading > 0) {
            loadingDrawable.setAlpha((int) (0xFF * loading));

            AndroidUtilities.rectTmp.set(
                dialogCell.getX() + dp(dialogCell.messagePaddingStart + 6),
                dialogCell.getY() + dp(38),
                dialogCell.getX() + dp(dialogCell.messagePaddingStart + 6) + getWidth() * .5f,
                dialogCell.getY() + dp(38 + 8.33f)
            );
            loadingDrawable.setBounds(AndroidUtilities.rectTmp);
            loadingDrawable.draw(canvas);

            AndroidUtilities.rectTmp.set(
                    dialogCell.getX() + dp(dialogCell.messagePaddingStart + 6),
                    dialogCell.getY() + dp(38 + 18),
                    dialogCell.getX() + dp(dialogCell.messagePaddingStart + 6) + getWidth() * .36f,
                    dialogCell.getY() + dp(38 + 18 + 8.33f)
            );
            loadingDrawable.setBounds(AndroidUtilities.rectTmp);
            loadingDrawable.draw(canvas);

            AndroidUtilities.rectTmp.set(
                    dialogCell.getX() + dialogCell.getWidth() - dp(16) - dp(43),
                    dialogCell.getY() + dp(12),
                    dialogCell.getX() + dialogCell.getWidth() - dp(16),
                    dialogCell.getY() + dp(12 + 8.33f)
            );
            loadingDrawable.setBounds(AndroidUtilities.rectTmp);
            loadingDrawable.draw(canvas);

            if (invalidatePreview) invalidate();
        }
        if (!capturingPreview && canvas.isHardwareAccelerated()) {
            if (this.loading && loading > 0f && !canvas.quickReject(
                    dialogCell.getX() + dp(dialogCell.messagePaddingStart + 2),
                    dialogCell.getY() + dp(31), dialogCell.getRight(), dialogCell.getBottom(), Canvas.EdgeType.AA)) {
                watchRecordedPreviewLoader();
            } else {
                stopPreviewReplayObservation();
            }
            if (needsPreviewRevealWake()) watchPreviewReveal();
            else stopPreviewRevealObservation();
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return loadingDrawable == who || super.verifyDrawable(who);
    }

    public void setNimarko() {
        stopPreviewReplayObservation();
        stopPreviewRevealObservation();
        previewState = null;
        boundChannelId = 0;
        boundMessageId = 0;
        loading = false;
        finishPreviewReveal();
        subscribersView.cancelAnimation();
        subscribersView.setPivotX(0);
        subscribersView.setAlpha(1f);
        subscribersView.setScaleX(1f);
        subscribersView.setScaleY(1f);

        int[] result = new int[1];
        boolean ignoreShort = AndroidUtilities.isAccessibilityScreenReaderEnabled();
        String shortNumber = ignoreShort ? String.valueOf(result[0] = 1234567) : LocaleController.formatShortNumber(1234567, result);
        subscribersView.setText(LocaleController.formatPluralString("Subscribers", result[0]).replace(String.format("%d", result[0]), shortNumber), true);

        ArrayList<DialogCell.CustomDialog> dialogs = new ArrayList<>();

        DialogCell.CustomDialog customDialog = new DialogCell.CustomDialog();
        customDialog.name = "NimarkoGram 💎";
        customDialog.message = LocaleController.getString(R.string.NM_MP_PreviewChannelMessage);
        customDialog.id = 1390;
        customDialog.unread_count = 0;
        customDialog.pinned = false;
        customDialog.muted = false;
        customDialog.type = 0;
        customDialog.date = (int) (System.currentTimeMillis() / 1000);
        customDialog.verified = true;
        customDialog.isMedia = false;
        customDialog.sent = DialogCell.SENT_STATE_NOTHING;
        dialogs.add(customDialog);

        dialogCell.setDialog(dialogs.get(0));

        invalidate();

        set = true;
    }

    private boolean set = false;
    private long boundChannelId;
    public void set(TLRPC.Chat channel, ArrayList<MessageObject> messageObjects) {
        set(channel, messageObjects, null);
    }

    public void set(TLRPC.Chat channel, ArrayList<MessageObject> messageObjects,
                    PreviewState presentation) {
        set(channel, messageObjects, presentation, 0);
    }

    public void set(TLRPC.Chat channel, ArrayList<MessageObject> messageObjects,
                    PreviewState presentation, long expectedChannelId) {
        if (expectedChannelId != 0 && channel != null && channel.id != expectedChannelId) {
            channel = null;
            messageObjects = null;
        }
        final long channelId = channel == null ? expectedChannelId : channel.id;
        final boolean nextLoading = channel == null || messageObjects == null || messageObjects.isEmpty();
        final int messageId = nextLoading ? 0 : messageObjects.get(messageObjects.size() - 1).getId();
        final boolean sameOwner = previewState == presentation;
        final boolean sameChannel = set && boundChannelId == channelId && sameOwner;
        final boolean keepRecordedLoader = sameChannel && loading && nextLoading
                && ownsPreviewState() && previewReplayObserver != null;
        stopPreviewReplayObservation();
        stopPreviewRevealObservation();
        if (!sameOwner && ownsPreviewState()) previewState.suspend(this);
        previewState = presentation;
        previewBinding = presentation != null ? presentation.bind(this, currentAccount, channelId, nextLoading) : 0;
        final boolean animated = sameChannel && isAttachedToWindow()
                && previewMotionEnabled();
        final boolean subscribersShown = channel == null || channel.participants_count > 0;
        if (!animated) subscribersView.cancelAnimation();
        subscribersView.animate().cancel();
        boundChannelId = channelId;
        boundMessageId = messageId;
        subscribersView.setPivotX(0);
        if (animated) {
            subscribersView.animate().alpha(subscribersShown ? 1f : 0f).scaleX(subscribersShown ? 1f : .8f).scaleY(subscribersShown ? 1f : .8f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        } else {
            subscribersView.setAlpha(subscribersShown ? 1f : 0f);
            subscribersView.setScaleX(subscribersShown ? 1f : 0f);
            subscribersView.setScaleY(subscribersShown ? 1f : 0f);
        }

        loading = nextLoading;
        if (channel != null) {
            int[] result = new int[1];
            boolean ignoreShort = AndroidUtilities.isAccessibilityScreenReaderEnabled();
            String shortNumber = ignoreShort ? String.valueOf(result[0] = channel.participants_count) : LocaleController.formatShortNumber(channel.participants_count, result);
            subscribersView.setText(LocaleController.formatPluralString("Subscribers", result[0]).replace(String.format("%d", result[0]), shortNumber), animated);

            if (loading) {
                dialogCell.setDialog(-channel.id, null, 0, false, animated);
            } else {
                final MessageObject lastMessage = messageObjects.get(messageObjects.size() - 1);
                dialogCell.setDialog(-channel.id, lastMessage, messageObjects, lastMessage.messageOwner.date, false, animated);
            }
        } else {
            dialogCell.setDialog(0, null, 0, false, false);
            subscribersView.setText("", false);
        }

        if (presentation == null && !animated) {
            loadingAlpha.set(loading, true);
            contentAlpha.set(loading ? 0f : 1f, true);
        }
        dialogCell.invalidateProfilePreview();
        invalidate();

        set = true;
        if (keepRecordedLoader) watchRecordedPreviewLoader();
        watchPreviewReveal();
    }

    public void finishPreviewReveal() {
        stopPreviewReplayObservation();
        stopPreviewRevealObservation();
        if (ownsPreviewState()) previewState.finish();
        loadingAlpha.set(loading, true);
        contentAlpha.set(loading ? 0f : 1f, true);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPreviewReplayObservation();
        stopPreviewRevealObservation();
        if (previewState == null) finishPreviewReveal();
        else if (ownsPreviewState()) previewState.suspend(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        watchPreviewReveal();
        invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus && ownsPreviewState()) {
            previewState.suspend(this);
            watchPreviewReveal();
        }
        if (hasWindowFocus) invalidate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE && ownsPreviewState()) {
            previewState.suspend(this);
            watchPreviewReveal();
        }
        if (visibility == View.VISIBLE) invalidate();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (!isVisible && ownsPreviewState()) {
            previewState.suspend(this);
            watchPreviewReveal();
        }
        if (isVisible) invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(102), MeasureSpec.EXACTLY));
    }

    public static class ChannelMessageFetcher {

        public final int currentAccount;
        public boolean loading, loaded, error;
        public ArrayList<MessageObject> messageObjects = new ArrayList<>();

        public ChannelMessageFetcher(int currentAccount) {
            this.currentAccount = currentAccount;
        }

        private int searchId;
        private long searchUserId;
        public long channel_id;
        public int message_id;

        public void fetch(TLRPC.UserFull userInfo) {
            if (userInfo == null || (userInfo.flags2 & 64) == 0) {
                searchId++;
                loading = false;
                channel_id = 0;
                message_id = 0;
                loaded = true;
                messageObjects.clear();
                done(false);
                return;
            }
            fetch(userInfo.personal_channel_id, userInfo.personal_channel_message);
        }

        public void fetch(long channel_id, int message_id) {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            if (channel_id <= 0) {
                ++searchId;
                searchUserId = selfId;
                this.channel_id = 0;
                this.message_id = 0;
                messageObjects.clear();
                done(false);
                return;
            }
            if (searchUserId == selfId && this.channel_id == channel_id && this.message_id == message_id
                    && (loading || loaded && !error)) {
                return;
            }
            final int thisSearchId = ++this.searchId;
            final boolean keepPreview = searchUserId == selfId && this.channel_id == channel_id
                    && !messageObjects.isEmpty();
            searchUserId = selfId;
            loading = true;
                    loaded = false;
            error = false;
            if (!keepPreview) messageObjects.clear();

            this.channel_id = channel_id;
            this.message_id = message_id;

            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            storage.getStorageQueue().postRunnable(() -> {
                ArrayList<TLRPC.Message> messages = new ArrayList<>();
                ArrayList<TLRPC.User> users = new ArrayList<>();
                ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                SQLiteCursor cursor = null;
                try {
                    if (message_id <= 0) {
                        cursor = storage.getDatabase().queryFinalized("SELECT data, mid FROM messages_v2 WHERE uid = ? ORDER BY mid DESC LIMIT 10", -channel_id);
                    } else {
                        cursor = storage.getDatabase().queryFinalized("SELECT data, mid FROM messages_v2 WHERE uid = ? AND mid <= ? ORDER BY mid DESC LIMIT 10", -channel_id, message_id);
                    }
                    ArrayList<Long> usersToLoad = new ArrayList<>();
                    ArrayList<Long> chatsToLoad = new ArrayList<>();
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            try {
                            final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                if (message == null) continue;
                            message.readAttachPath(data, selfId);
                            message.id = cursor.intValue(1);
                                if (!isUsableMessage(message)) continue;
                            message.dialog_id = -channel_id;
                            MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                            messages.add(message);
                            } finally {
                                data.reuse();
                            }
                        }
                    }

                    if (!messages.isEmpty()) {
                        if (!usersToLoad.isEmpty()) {
                            storage.getUsersInternal(usersToLoad, users);
                        }
                        if (!chatsToLoad.isEmpty()) {
                            storage.getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    messages.clear();
                    users.clear();
                    chats.clear();
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isCurrent(thisSearchId, selfId)) return;
                    final MessagesController controller = MessagesController.getInstance(currentAccount);
                    if (!users.isEmpty()) controller.putUsers(users, true);
                    if (!chats.isEmpty()) controller.putChats(chats, true);
                    boolean hasTarget = message_id <= 0;
                            for (TLRPC.Message message : messages) {
                        if (message.id == message_id) hasTarget = true;
                    }
                    if (hasTarget && selectMessages(messages)) {
                            done(false);
                            return;
                        }
                    requestMessages(thisSearchId, selfId, channel_id, message_id, message_id <= 0);
                });
            });
        }

        private boolean isCurrent(int generation, long selfId) {
            return generation == searchId && selfId == UserConfig.getInstance(currentAccount).getClientUserId();
        }

        private static boolean isUsableMessage(TLRPC.Message message) {
            return message != null && !(message instanceof TLRPC.TL_messageEmpty) && message.id > 0;
        }

        private boolean selectMessages(ArrayList<TLRPC.Message> messages) {
            final ArrayList<TLRPC.Message> usable = new ArrayList<>();
            for (TLRPC.Message message : messages) {
                if (isUsableMessage(message)) usable.add(message);
            }
            if (usable.isEmpty()) return false;
            Collections.sort(usable, Comparator.comparingInt(msg -> msg.id));
            final TLRPC.Message lastMessage = usable.get(usable.size() - 1);
            messageObjects.clear();
            for (TLRPC.Message message : usable) {
                if (message == lastMessage || (lastMessage.grouped_id != 0 && message.grouped_id == lastMessage.grouped_id)) {
                    messageObjects.add(new MessageObject(currentAccount, message, false, true));
                }
            }
            return true;
        }

        private void requestMessages(int generation, long selfId, long channelId, int messageId, boolean history) {
            if (!isCurrent(generation, selfId)) return;
            final MessagesController controller = MessagesController.getInstance(currentAccount);
            final org.telegram.tgnet.TLObject request;
            if (history) {
                final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = controller.getInputPeer(-channelId);
                req.limit = 20;
                request = req;
            } else {

                    final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
                req.channel = controller.getInputChannel(channelId);
                    for (int i = 10; i >= 0; --i) {
                    final int id = messageId - i;
                    if (id > 0) req.id.add(id);
                }
                request = req;
            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (!isCurrent(generation, selfId)) return;
                if (err == null && response instanceof TLRPC.messages_Messages) {
                            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    controller.putUsers(res.users, false);
                    controller.putChats(res.chats, false);
                    final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
                            storage.putUsersAndChats(res.users, res.chats, true, true);
                    storage.putMessages(res, -channelId, MessagesController.LOAD_AROUND_MESSAGE, 0, false, 0, 0);
                    if (selectMessages(res.messages)) {
                                    done(false);
                                    return;
                                }
                    if (!history) {
                        requestMessages(generation, selfId, channelId, messageId, true);
                        return;
                    }
                            }
                            done(true);
                    }));
        }

        private ArrayList<Runnable> callbacks = new ArrayList<>();

        public void subscribe(Runnable callback) {
            if (loaded) {
                callback.run();
            } else {
                callbacks.add(callback);
            }
        }

        private void done(boolean error) {
            loading = false;
            loaded = true;
            this.error = error;
            if (error) messageObjects.clear();
            final ArrayList<Runnable> completedCallbacks = new ArrayList<>(callbacks);
            callbacks.clear();
            for (Runnable callback : completedCallbacks) callback.run();
        }
    }

    public int processColor(int color) {
        return color;
    }

    @Override
    public void updateColors() {
        final int headerColor = processColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        subscribersView.setTextColor(headerColor);
        subscribersView.setBackground(Theme.createRoundRectDrawable(dp(9f), dp(9f), Theme.multAlpha(headerColor, .1f)));
        headerView.setTextColor(headerColor);
        contentMaskPaint.setColor(processColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
    }

    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        colors.add(Theme.key_windowBackgroundWhiteBlueHeader);
    }

}
