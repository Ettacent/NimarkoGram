/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.voip;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.voip.Instance;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.VoIPFragment;
import org.telegram.utils.pip.PipAction;
import org.telegram.utils.pip.PipContent;
import org.telegram.utils.pip.PipCoordinator;
import org.telegram.utils.pip.PipSession;
import org.telegram.utils.pip.PipTextureView;
import org.webrtc.RendererCommon;
import org.webrtc.TextureViewRenderer;

import java.util.List;

public class VoIPPipContent implements PipContent {
    private static final int PRIORITY = 10;

    private static VoIPPipContent instance;

    private final VoIPService ownerService;
    private PipSession session;
    private VoIPFragment systemFragment;
    private VoIPPiPView systemBubble;

    private VoIPPipContent(VoIPService ownerService) {
        this.ownerService = ownerService;
    }

    private boolean isCurrentOwner() {
        return instance == this && VoIPService.getSharedInstance() == ownerService;
    }

    public static void update() {
        final VoIPService service = VoIPService.getSharedInstance();
        final boolean hasUi = VoIPFragment.getInstance() != null || VoIPPiPView.getInstance() != null;
        if (service == null || !hasUi) {
            if (instance != null) {
                final VoIPPipContent content = instance;
                instance = null;
                content.release();
            }
            return;
        }
        if (instance != null && instance.ownerService != service) {
            final VoIPPipContent content = instance;
            instance = null;
            content.release();
        }
        if (instance == null) {
            instance = new VoIPPipContent(service);
        }
        instance.updateState();
    }

    private void updateState() {
        if (session == null || !session.isAlive()) {
            session = PipCoordinator.getInstance().register(this);
        }
        if (session.getState() == PipSession.STATE_SYSTEM) {
            session.invalidate();
            return;
        }
        final VoIPPiPView bubble = VoIPFragment.getInstance() == null ? VoIPPiPView.getInstance() : null;
        session.setOwnFloating(bubble != null, bubble != null && bubble.isOverlay());
        session.invalidate();
    }

    private void release() {
        AndroidUtilities.cancelRunOnUIThread(actionsTick);
        if (session != null) {
            final PipSession s = session;
            session = null;
            s.release();
        }
    }

    private static @Nullable TextureViewRenderer getRemoteRenderer() {
        final VoIPFragment fragment = VoIPFragment.getInstance();
        if (fragment != null && fragment.getCallingUserVideoView() instanceof VoIPTextureView) {
            return ((VoIPTextureView) fragment.getCallingUserVideoView()).renderer;
        }
        final VoIPPiPView bubble = VoIPPiPView.getInstance();
        if (bubble != null) {
            return bubble.callingUserTextureView.renderer;
        }
        return null;
    }

    private @Nullable CallSystemPipView systemView;

    @Override
    public int getContentWidth() {
        if (!CallSystemPipView.hasRemoteVideo(VoIPService.getSharedInstance())) {
            return 1;
        }
        final TextureViewRenderer renderer = getSizeRenderer();
        return renderer != null && renderer.rotatedFrameWidth > 0 ? renderer.rotatedFrameWidth : 9;
    }

    @Override
    public int getContentHeight() {
        if (!CallSystemPipView.hasRemoteVideo(VoIPService.getSharedInstance())) {
            return 1;
        }
        final TextureViewRenderer renderer = getSizeRenderer();
        return renderer != null && renderer.rotatedFrameHeight > 0 ? renderer.rotatedFrameHeight : 16;
    }

    private @Nullable TextureViewRenderer getSizeRenderer() {
        if (systemView != null && systemView.textureView.renderer.rotatedFrameWidth > 0) {
            return systemView.textureView.renderer;
        }
        return getRemoteRenderer();
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public boolean needsPipOutsideApp() {
        final VoIPService service = VoIPService.getSharedInstance();
        return isCurrentOwner() && service != null && !service.isHangingUp() && service.getCallState() != VoIPService.STATE_ENDED && service.getCallState() != VoIPService.STATE_HANGING_UP;
    }

    @Override
    public boolean isPrimaryVisible() {
        final VoIPFragment fragment = VoIPFragment.getInstance();
        return isCurrentOwner() && fragment != null && fragment.getCallingUserVideoView() != null && fragment.getCallingUserVideoView().isAttachedToWindow();
    }

    @Override
    public void getPrimaryVideoRect(@NonNull Rect outScreenRect) {
        outScreenRect.setEmpty();
    }

    @Override
    public void getSystemActions(@NonNull List<PipAction> out, int maxActions) {
        if (isCurrentOwner()) CallPipActions.build(out, maxActions, false);
    }

    @Override
    public void onSystemAction(int actionId) {
        if (!isCurrentOwner()) return;
        CallPipActions.perform(actionId, false);
        AndroidUtilities.runOnUIThread(VoIPPipContent::update, 300);
    }

    private long actionsKey;
    private boolean lastRemoteVideo;
    private final Runnable actionsTick = new Runnable() {
        @Override
        public void run() {
            if (!isCurrentOwner() || session == null || !session.isAlive()) return;
            if (systemView != null) {
                systemView.update();
            }
            final long key = CallPipActions.stateKey();
            final boolean remoteVideo = CallSystemPipView.hasRemoteVideo(VoIPService.getSharedInstance());
            if (key != actionsKey || remoteVideo != lastRemoteVideo) {
                actionsKey = key;
                lastRemoteVideo = remoteVideo;
                if (session != null) {
                    session.invalidate();
                }
            }
            AndroidUtilities.runOnUIThread(this, 500);
        }
    };

    @Override
    public View createSystemView(@NonNull Context context) {
        return new CallSystemPipView(context);
    }

    @Override
    public void onSystemEnter(@NonNull View systemView) {
        if (!isCurrentOwner()) return;
        systemFragment = VoIPFragment.getInstance();
        systemBubble = null;
        CallPipActions.onSystemEnter();
        this.systemView = (CallSystemPipView) systemView;
        this.systemView.update();
        actionsKey = CallPipActions.stateKey();
        lastRemoteVideo = CallSystemPipView.hasRemoteVideo(VoIPService.getSharedInstance());
        AndroidUtilities.cancelRunOnUIThread(actionsTick);
        AndroidUtilities.runOnUIThread(actionsTick, 500);
        fragmentInSystem = false;
        if (VoIPFragment.getInstance() == null && VoIPPiPView.getInstance() != null) {
            systemBubble = VoIPPiPView.getInstance();
            systemBubble.enterSystemPip(this.systemView.textureView);
        }
    }

    private boolean fragmentInSystem;

    @Override
    public void onSystemEntered(@NonNull View systemView) {
        final VoIPFragment fragment = VoIPFragment.getInstance();
        if (isCurrentOwner() && fragment != null && fragment == systemFragment && this.systemView == systemView && !fragmentInSystem) {
            fragmentInSystem = true;
            systemFragment = fragment;
            this.systemView.restartFrameWait();
            fragment.enterSystemPip(this.systemView.textureView);
        }
    }

    @Override
    public void onSystemExit(@NonNull View systemView) {
        AndroidUtilities.cancelRunOnUIThread(actionsTick);
        this.systemView = null;
        if (isCurrentOwner()) {
            CallPipActions.onSystemExit();
            if (systemFragment != null && VoIPFragment.getInstance() == systemFragment) {
                systemFragment.exitSystemPip();
            } else if (systemBubble != null && VoIPPiPView.getInstance() == systemBubble) {
                systemBubble.exitSystemPip();
            }
        }
        systemFragment = null;
        systemBubble = null;
    }

    @Override
    public void releaseSystemView(@NonNull View systemView) {
        AndroidUtilities.cancelRunOnUIThread(actionsTick);
        if (this.systemView == systemView) {
            this.systemView = null;
        }
        ((CallSystemPipView) systemView).release();
    }

    @Override
    public void onDismissed() {
        AndroidUtilities.cancelRunOnUIThread(actionsTick);
        session = null;
        if (!isCurrentOwner()) return;
        instance = null;
        final VoIPFragment fragment = VoIPFragment.getInstance();
        if (fragment != null && fragment == systemFragment) {
            fragment.closeWithoutPip();
        }
        if (VoIPPiPView.getInstance() == systemBubble && systemBubble != null) {
            VoIPPiPView.finish();
        }
        systemFragment = null;
        systemBubble = null;
    }

    @Override
    public boolean isPlaying() {
        return true;
    }

    @Override
    public boolean isCompleted() {
        return false;
    }

    @Override
    public void play() {}

    @Override
    public void pause() {}

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
    public void releasePrimary(@NonNull PipTextureView target) {}

    @Override
    public void restorePrimary(@NonNull SurfaceTexture surfaceTexture) {
        surfaceTexture.release();
    }

    @Override
    public void onExpandRequested() {}
}
