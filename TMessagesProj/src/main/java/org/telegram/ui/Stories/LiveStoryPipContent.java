/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Stories;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.voip.PipEglRenderer;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.LivePlayerView;
import org.telegram.utils.pip.PipContent;
import org.telegram.utils.pip.PipCoordinator;
import org.telegram.utils.pip.PipSession;
import org.telegram.utils.pip.PipTextureView;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class LiveStoryPipContent implements PipContent {
    private static @Nullable LiveStoryPipContent instance;

    private @Nullable PipSession session;
    private final LivePlayer player;
    private final int account;
    private final int primaryGeneration;
    private @Nullable LivePlayerView primaryView;
    private @Nullable View primaryWindow;
    private @Nullable VideoSink ownerSink;
    private boolean primaryGone;
    private boolean retired;
    private boolean dismissed;
    private boolean playerTransferred;
    private @Nullable StoryViewer viewer;

    private final FrameTap tap = new FrameTap();
    private @Nullable PipEglRenderer floatingRenderer;
    private @Nullable LivePlayerView systemView;
    private @Nullable LivePlayerView createdSystemView;
    private @Nullable VideoSink primarySink;

    private final Runnable onUserLeaveHint = () -> {
        final StoryViewer v = viewer;
        if (v != null && isPrimaryVisible() && session != null && session.getState() == PipSession.STATE_PRIMARY
            && LaunchActivity.instance != null && org.telegram.utils.pip.PipEnvironment.canDrawOverlays(LaunchActivity.instance)) {
            v.switchToPip();
        }
    };
    private @Nullable LaunchActivity leaveHintActivity;

    private LiveStoryPipContent(StoryViewer viewer, LivePlayer player) {
        this.viewer = viewer;
        this.player = player;
        account = player.currentAccount;
        primaryGeneration = viewer.livePlayerGeneration;
        primaryView = viewer.liveView;
        primaryWindow = viewer.windowView;
        ownerSink = primaryView != null ? primaryView.getSink() : null;
    }

    private boolean isCurrent() {
        return instance == this && !retired && !dismissed && !playerTransferred && !player.destroyed
                && session != null && session.isAlive();
    }

    private boolean ownsPrimary() {
        final StoryViewer v = viewer;
        return !primaryGone && v != null && v.currentAccount == account && v.livePlayer == player
                && v.livePlayerGeneration == primaryGeneration && v.liveView == primaryView
                && v.windowView == primaryWindow && primaryWindow != null;
    }

    private void clearPrimaryOwner() {
        primaryGone = true;
        viewer = null;
        primaryView = null;
        primaryWindow = null;
        ownerSink = null;
        primarySink = null;
    }

    private boolean playerOwnedElsewhere() {
        final LiveStoryPipContent content = instance;
        return content != null && content != this && content.player == player
                && !content.retired && !content.dismissed && !content.playerTransferred;
    }

    private void retire() {
        if (retired) return;
        retired = true;
        setLeaveHintRegistered(false);
        tap.enabled = false;
        AndroidUtilities.cancelRunOnUIThread(tap.onSizeChanged);
        if (instance == this) instance = null;
        final PipSession previous = session;
        if (previous != null) {
            final boolean primary = previous.getState() == PipSession.STATE_PRIMARY;
            previous.release();
            if (primary) {
                session = null;
                clearPrimaryOwner();
            }
        } else {
            clearPrimaryOwner();
        }
    }

    private void setLeaveHintRegistered(boolean registered) {
        final LaunchActivity target = registered && isCurrent() && ownsPrimary() ? LaunchActivity.instance : null;
        if (leaveHintActivity == target) {
            return;
        }
        if (leaveHintActivity != null) {
            leaveHintActivity.removeOnUserLeaveHintListener(onUserLeaveHint);
        }
        leaveHintActivity = target;
        if (target != null) {
            target.addOnUserLeaveHintListener(onUserLeaveHint);
        }
    }

    private static LiveStoryPipContent get(StoryViewer viewer, LivePlayer player) {
        final LiveStoryPipContent previous = instance;
        if (previous != null && previous.player == player && previous.viewer == viewer
                && previous.ownsPrimary() && previous.isCurrent()) {
            return previous;
        }
        final LiveStoryPipContent content = new LiveStoryPipContent(viewer, player);
        instance = content;
        if (previous != null) previous.retire();
        return content;
    }

    public static void onPrimary(@NonNull StoryViewer viewer, @NonNull LivePlayer player) {
        if (player.destroyed || viewer.currentAccount != player.currentAccount || viewer.livePlayer != player
                || viewer.liveView == null || viewer.windowView == null) return;
        final LiveStoryPipContent previous = instance;
        if (previous != null && previous.isCurrent() && previous.session.getState() != PipSession.STATE_PRIMARY) {
            if (previous.player == player && previous.viewer == viewer && previous.ownsPrimary()) return;
        }
        final LiveStoryPipContent content = get(viewer, player);
        content.ensureSession();
        content.setLeaveHintRegistered(true);
        if (content.isCurrent()) content.session.invalidate();
    }

    public static void onPrimaryGone(@NonNull StoryViewer viewer) {
        final LiveStoryPipContent content = instance;
        if (content == null || content.viewer != viewer || content.player != viewer.livePlayer
                || content.primaryGeneration != viewer.livePlayerGeneration || content.primaryView != viewer.liveView
                || content.primaryWindow != viewer.windowView) return;
        content.primaryGone = true;
        content.setLeaveHintRegistered(false);
        if (content.session != null && content.session.isAlive() && content.session.getState() == PipSession.STATE_PRIMARY) {
            content.retire();
        } else {
            content.clearPrimaryOwner();
        }
    }

    public static boolean keepsPlayer(@Nullable LivePlayer player) {
        final LiveStoryPipContent content = instance;
        if (player == null || content == null || content.player != player || !content.isCurrent()) {
            return false;
        }
        return content.session.getState() != PipSession.STATE_PRIMARY;
    }

    public static float getPipVolume() {
        final LiveStoryPipContent content = instance;
        return content != null && content.isCurrent() && content.pausedInBackground ? 0f : 1f;
    }

    public static boolean isInPip(@Nullable LivePlayer player) {
        return keepsPlayer(player);
    }

    public static boolean isFloating() {
        final LiveStoryPipContent content = instance;
        return content != null && content.isCurrent() && content.session.isFloating();
    }

    public static @Nullable LivePlayer takePlayer(int account, long dialogId, @NonNull TLRPC.InputGroupCall call) {
        final LiveStoryPipContent content = instance;
        if (content == null || !content.isCurrent() || content.account != account || content.player.dialogId != dialogId
                || content.session.getState() != PipSession.STATE_FLOATING || !content.session.isFloating()
                || call == null || !content.player.equals(call)) return null;
        final LivePlayer player = content.player;
        content.playerTransferred = true;
        content.closeFloatingForViewer();
        return player;
    }

    public static void closeFloating() {
        final LiveStoryPipContent content = instance;
        if (content != null && content.isCurrent() && content.session.isFloating()) {
            content.session.dismiss();
        }
    }

    public static boolean openFloating(@NonNull Activity activity, @NonNull StoryViewer viewer, @NonNull LivePlayer player, @Nullable Bitmap placeholder) {
        if (activity.isFinishing() || activity.isDestroyed() || player.destroyed || viewer.currentAccount != player.currentAccount
                || viewer.livePlayer != player || !viewer.isShown() || viewer.liveView == null || viewer.windowView == null) return false;
        final LiveStoryPipContent previous = instance;
        if (previous != null && previous.isCurrent() && previous.player == player
                && previous.session.getState() != PipSession.STATE_PRIMARY) {
            return previous.session.getState() == PipSession.STATE_FLOATING && previous.session.isFloating();
        }
        final LiveStoryPipContent content = get(viewer, player);
        content.ensureSession();
        if (!content.isCurrent() || !content.ownsPrimary()) return false;
        final PipSession session = content.session;
        final PipTextureView target = session.startFloating(activity, placeholder, true);
        if (target == null || !content.isCurrent() || content.session != session || session.getState() != PipSession.STATE_FLOATING) {
            content.retire();
            return false;
        }
        try {
            final PipEglRenderer renderer = new PipEglRenderer(target);
            if (!content.isCurrent() || !content.ownsPrimary()) {
                renderer.release();
                content.retire();
                return false;
            }
            content.floatingRenderer = renderer;
            content.tap.target = renderer;
            content.tap.enabled = true;
            player.setDisplaySink(content.tap);
            player.setVolume(1f);
            content.primaryGone = true;
            content.setLeaveHintRegistered(false);
            content.clearPrimaryOwner();
            log("floating, call " + player.getCallId());
            return true;
        } catch (RuntimeException error) {
            FileLog.e(error);
            content.retire();
            return false;
        }
    }

    private void ensureSession() {
        if (session == null && instance == this && !retired && !dismissed && !playerTransferred) {
            session = PipCoordinator.getInstance().register(this);
            session.invalidate();
        }
    }

    private void closeFloatingForViewer() {
        final PipSession previous = session;
        if (previous == null) return;
        retired = true;
        primaryGone = true;
        setLeaveHintRegistered(false);
        if (instance == this) instance = null;
        tap.enabled = false;
        tap.target = null;
        AndroidUtilities.cancelRunOnUIThread(tap.onSizeChanged);
        if (player.getDisplaySink() == tap) player.setDisplaySink(null);
        final PipEglRenderer renderer = floatingRenderer;
        floatingRenderer = null;
        previous.completeExpand(texture -> {
            if (renderer != null) {
                renderer.release();
            }
            if (texture != null) {
                texture.release();
            }
        });
        session = null;
        previous.release();
        clearPrimaryOwner();
    }

    private void setViewerHidden(boolean hidden) {
        if (ownsPrimary() && !playerOwnedElsewhere() && primaryWindow != null) {
            primaryWindow.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public int getContentWidth() {
        if (tap.frameWidth > 0) {
            return tap.frameWidth;
        }
        final org.webrtc.TextureViewRenderer renderer = getViewerRenderer();
        return renderer != null && renderer.rotatedFrameWidth > 0 ? renderer.rotatedFrameWidth : 9;
    }

    @Override
    public int getContentHeight() {
        if (tap.frameHeight > 0) {
            return tap.frameHeight;
        }
        final org.webrtc.TextureViewRenderer renderer = getViewerRenderer();
        return renderer != null && renderer.rotatedFrameHeight > 0 ? renderer.rotatedFrameHeight : 16;
    }

    private @Nullable org.webrtc.TextureViewRenderer getViewerRenderer() {
        final StoryViewer v = viewer;
        return ownsPrimary() && primaryView != null ? primaryView.textureView : null;
    }

    @Override
    public boolean hasPlaybackControls() {
        return false;
    }

    @Override
    public boolean resumesWithApp() {
        return true;
    }

    private boolean pausedInBackground;

    @Override
    public boolean isPlaying() {
        return isCurrent() && !pausedInBackground;
    }

    @Override
    public boolean isCompleted() {
        return false;
    }

    @Override
    public void play() {
        if (!isCurrent()) return;
        if (pausedInBackground) {
            pausedInBackground = false;
            if (player != null) {
                player.setVolume(1f);
            }
        }
    }

    @Override
    public void pause() {
        if (!isCurrent()) return;
        if (!pausedInBackground) {
            pausedInBackground = true;
            if (player != null) {
                player.setVolume(0f);
            }
        }
    }

    @Override
    public long getDuration() {
        return 0;
    }

    @Override
    public long getCurrentPosition() {
        return 0;
    }

    @Override
    public float getBufferedProgress() {
        return 0;
    }

    @Override
    public void seekTo(long positionMs) {}

    @Override
    public boolean needsPipOutsideApp() {
        return isCurrent() && (session.getState() != PipSession.STATE_PRIMARY || ownsPrimary());
    }

    @Override
    public boolean isPrimaryVisible() {
        final StoryViewer v = viewer;
        return isCurrent() && ownsPrimary() && v.isShown() && player.getDisplaySink() == ownerSink;
    }

    @Override
    public void getPrimaryVideoRect(@NonNull Rect outScreenRect) {
        outScreenRect.setEmpty();
    }

    @Override
    public void releasePrimary(@NonNull PipTextureView target) {
        if (!isCurrent() || !ownsPrimary() || player.getDisplaySink() != ownerSink) return;
        floatingRenderer = new PipEglRenderer(target);
        tap.target = floatingRenderer;
        tap.enabled = true;
        primarySink = ownerSink;
        player.setDisplaySink(tap);
    }

    @Override
    public void restorePrimary(@NonNull SurfaceTexture surfaceTexture) {
        final PipEglRenderer renderer = floatingRenderer;
        floatingRenderer = null;
        tap.enabled = false;
        tap.target = null;
        if (isCurrent() && ownsPrimary() && player.getDisplaySink() == tap && !playerOwnedElsewhere()) {
            player.setDisplaySink(ownerSink);
        }
        primarySink = null;
        if (renderer != null) {
            renderer.release();
        }
        surfaceTexture.release();
    }

    @Override
    public void onExpandRequested() {
        if (!isCurrent() || session.getState() != PipSession.STATE_FLOATING) return;
        final PipSession expandingSession = session;
        final LivePlayer p = player;
        final LaunchActivity activity = LaunchActivity.instance;
        if (p.destroyed || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            expandingSession.dismiss();
            return;
        }
        if (p.currentAccount != UserConfig.selectedAccount) {
            activity.switchToAccount(p.currentAccount, true);
        }
        if (!isCurrent() || session != expandingSession || expandingSession.getState() != PipSession.STATE_FLOATING
                || LaunchActivity.instance != activity || activity.isFinishing() || activity.isDestroyed()
                || UserConfig.selectedAccount != account) return;
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        TL_stories.StoryItem storyItem = MessagesController.getInstance(account).getStoriesController().findStory(p.dialogId, p.storyId);
        if (storyItem == null) {
            storyItem = p.storyItem;
        }
        if (fragment == null || fragment.getCurrentAccount() != account || storyItem == null || fragment.getContext() == null) {
            expandingSession.dismiss();
            return;
        }
        fragment.getOrCreateStoryViewer().open(account, fragment.getContext(), storyItem, null);
    }

    @Override
    public void onDismissed() {
        if (dismissed) return;
        final boolean elsewhere = playerTransferred || playerOwnedElsewhere();
        final boolean fromViewer = systemView != null || primarySink != null;
        final boolean restoreViewer = !elsewhere && ownsPrimary()
                && (player.getDisplaySink() == tap || player.getDisplaySink() == ownerSink);
        dismissed = true;
        retired = true;
        pausedInBackground = false;
        session = null;
        setLeaveHintRegistered(false);
        if (instance == this) instance = null;
        tap.enabled = false;
        tap.target = null;
        AndroidUtilities.cancelRunOnUIThread(tap.onSizeChanged);
        final LivePlayer p = player;
        final StoryViewer v = viewer;
        if (!elsewhere && p.getDisplaySink() == tap) p.setDisplaySink(null);
        if (floatingRenderer != null) {
            floatingRenderer.release();
            floatingRenderer = null;
        }
        if (fromViewer && restoreViewer && v != null) {
            setViewerHidden(false);
            if (!p.destroyed) p.setDisplaySink(ownerSink);
            primarySink = null;
            v.close(false);
            clearPrimaryOwner();
            return;
        }
        primarySink = null;
        if (!elsewhere && !p.destroyed && !ownsPrimary()
                && (p.getDisplaySink() == null || p.getDisplaySink() == tap || p.getDisplaySink() == ownerSink)) {
            if (p.outgoing || p == LivePlayer.recording) {
                if (p.getDisplaySink() == tap || p.getDisplaySink() == ownerSink) p.setDisplaySink(null);
            } else {
                p.destroy();
            }
        }
        clearPrimaryOwner();
    }

    @Override
    public View createSystemView(@NonNull Context context) {
        createdSystemView = new LivePlayerView(context, account, false);
        return createdSystemView;
    }

    @Override
    public void onSystemEnter(@NonNull View view) {
        if (!isCurrent() || !ownsPrimary() || view != createdSystemView) return;
        systemView = (LivePlayerView) view;
    }

    @Override
    public void onSystemEntered(@NonNull View view) {
        if (!isCurrent() || !ownsPrimary() || view != systemView || player.getDisplaySink() != ownerSink) {
            return;
        }
        primarySink = ownerSink;
        tap.target = systemView.getSink();
        tap.enabled = true;
        player.setDisplaySink(tap);
        player.setVolume(1f);
        setViewerHidden(true);
    }

    @Override
    public void onSystemExit(@NonNull View view) {
        if (view != systemView || view != createdSystemView) return;
        final boolean restore = isCurrent() && ownsPrimary() && !playerOwnedElsewhere()
                && (player.getDisplaySink() == tap || player.getDisplaySink() == ownerSink);
        if (restore) {
            setViewerHidden(false);
            if (player.getDisplaySink() == tap) player.setDisplaySink(ownerSink);
        } else if (!playerOwnedElsewhere() && !playerTransferred && player.getDisplaySink() == tap) {
            player.setDisplaySink(null);
        }
        tap.enabled = false;
        tap.target = null;
        primarySink = null;
        systemView = null;
        if (!restore && session != null && session.isAlive() && session.content == this) session.dismiss();
    }

    @Override
    public void releaseSystemView(@NonNull View view) {
        if (view != createdSystemView) return;
        createdSystemView = null;
        if (view == systemView) {
            tap.enabled = false;
            tap.target = null;
            systemView = null;
        }
        ((LivePlayerView) view).release();
    }

    private static void log(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("live story pip: " + message);
        }
    }

    private final class FrameTap implements VideoSink {
        volatile @Nullable VideoSink target;
        volatile boolean enabled;
        volatile int frameWidth, frameHeight;

        private final Runnable onSizeChanged = () -> {
            if (isCurrent()) {
                session.invalidate();
            }
        };

        @Override
        public void onFrame(VideoFrame frame) {
            if (!enabled) return;
            final int w = frame.getRotatedWidth(), h = frame.getRotatedHeight();
            if (w != frameWidth || h != frameHeight) {
                frameWidth = w;
                frameHeight = h;
                AndroidUtilities.runOnUIThread(onSizeChanged);
            }
            final VideoSink t = target;
            if (t != null) {
                t.onFrame(frame);
            }
        }
    }
}
