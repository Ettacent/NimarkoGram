/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.voip;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.voip.Instance;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.LayoutHelper;
import org.webrtc.RendererCommon;

import static org.telegram.messenger.LocaleController.getString;

@SuppressLint("ViewConstructor")
final class CallSystemPipView extends FrameLayout {
    final VoIPTextureView textureView;
    private final CallPipAvatarView avatarView;
    private final TextView nameView;
    private final TextView statusView;
    private final ImageView micView;
    private final CallPipSelfView selfView;

    private static final long FIRST_FRAME_WAIT_MS = 1200;

    private @Nullable TLRPC.User shownUser;
    private boolean videoShown = false;
    private long createdAt = android.os.SystemClock.elapsedRealtime();
    private boolean released;

    CallSystemPipView(@NonNull Context context) {
        super(context);
        setBackgroundColor(0xFF1B1F23);

        textureView = new VoIPTextureView(context, false, true, false, false);
        textureView.renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        textureView.renderer.setEnableHardwareScaler(true);
        textureView.renderer.setRotateTextureWithScreen(true);
        textureView.scaleType = VoIPTextureView.SCALE_TYPE_FIT;
        textureView.renderer.init(VideoCapturerDevice.getEglBase().getEglBaseContext(), null);
        if (textureView.backgroundView != null) {
            textureView.backgroundView.setVisibility(View.GONE);
        }
        addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        avatarView = new CallPipAvatarView(context);
        addView(avatarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        nameView = new TextView(context);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        nameView.setSingleLine();
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setGravity(Gravity.CENTER_HORIZONTAL);
        nameView.setShadowLayer(AndroidUtilities.dp(2), 0, 0, 0x80000000);
        addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 12, 0, 12, 8));

        statusView = new TextView(context);
        statusView.setTextColor(0xB3FFFFFF);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        statusView.setSingleLine();
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setShadowLayer(AndroidUtilities.dp(2), 0, 0, 0x80000000);
        statusView.setVisibility(View.GONE);
        addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 32, 8, 32, 0));

        selfView = new CallPipSelfView(context);
        addView(selfView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        selfView.start();

        micView = new ImageView(context);
        micView.setScaleType(ImageView.ScaleType.CENTER);
        micView.setColorFilter(Color.WHITE);
        micView.setBackground(org.telegram.ui.ActionBar.Theme.createCircleDrawable(AndroidUtilities.dp(28), 0x66000000));
        addView(micView, LayoutHelper.createFrame(28, 28, Gravity.TOP | Gravity.LEFT, 6, 6, 0, 0));
    }

    static boolean hasRemoteVideo(@Nullable VoIPService service) {
        return service != null && service.getRemoteVideoState() == Instance.VIDEO_STATE_ACTIVE;
    }

    void update() {
        if (released) return;
        final VoIPService service = VoIPService.getSharedInstance();
        if (service == null) {
            return;
        }
        final TLRPC.User user = service.getUser();
        if (user != null && user != shownUser) {
            shownUser = user;
            avatarView.setPeer(service.getAccount(), user);
            nameView.setText(UserObject.getUserName(user));
        }
        final boolean remoteVideo = hasRemoteVideo(service);
        final boolean waitingForFrame = remoteVideo && !textureView.renderer.isFirstFrameRendered() && android.os.SystemClock.elapsedRealtime() - createdAt < FIRST_FRAME_WAIT_MS;
        setVideoShown(remoteVideo && (textureView.renderer.isFirstFrameRendered() || waitingForFrame));

        micView.setImageResource(service.isMicMute() ? R.drawable.msg_voice_muted : R.drawable.msg_voice_unmuted);
        selfView.update();

        final int state = service.getCallState();
        if (state == VoIPService.STATE_RECONNECTING) {
            statusView.setText(getString(R.string.VoipReconnecting));
            statusView.setVisibility(View.VISIBLE);
        } else if (state != VoIPService.STATE_ESTABLISHED) {
            statusView.setText(getString(R.string.VoipConnecting));
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }
    }

    private void setVideoShown(boolean shown) {
        if (videoShown == shown) {
            return;
        }
        videoShown = shown;
        avatarView.setVisibility(shown ? View.GONE : View.VISIBLE);
        nameView.setVisibility(shown ? View.GONE : View.VISIBLE);
    }

    void restartFrameWait() {
        createdAt = android.os.SystemClock.elapsedRealtime();
        update();
    }

    void release() {
        if (released) return;
        released = true;
        selfView.stop();
        textureView.renderer.release();
    }
}
