package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import app.nimarkogram.messenger.NimarkoConfig;

import org.telegram.messenger.ImageReceiver;
import org.telegram.utils.settings.SharedSettings;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;


public abstract class InstantCameraViewBase extends FrameLayout {


    public interface AnimationCallback {
        void onAnimation(boolean open, boolean fromPaused);
    }


    public interface TrimCallback {
        void onTrimChanged(float start, float end);
    }


    public interface RecordingUiFrameCallback {
        void onActiveChanged(boolean active);
        void onFrame(long durationMs);
    }

    private AnimationCallback animationCallback;
    private TrimCallback trimCallback;
    private RecordingUiFrameCallback recordingUiFrameCallback;
    private boolean recordingUiFrameClockActive;

    protected InstantCameraViewBase(Context context) {
        super(context);
    }


    public static InstantCameraViewBase create(
            Context context,
            InstantCameraView.Delegate delegate,
            Theme.ResourcesProvider resourcesProvider,
            boolean isNewDesign
    ) {
        return isUsingCamera2Implementation()
                ? new InstantCameraView2(context, delegate, resourcesProvider, isNewDesign)
                : new InstantCameraView(context, delegate, resourcesProvider, isNewDesign);
    }


    public static void setUseCamera2Implementation(boolean enabled) {
        SharedSettings.roundVideoCamera2Enabled.set(enabled);
    }


    public static boolean isUsingCamera2Implementation() {


        return NimarkoConfig.cameraType != NimarkoConfig.CAMERA_X
                && SharedSettings.roundVideoCamera2Enabled.get();
    }


    public final void setAnimationCallback(AnimationCallback animationCallback) {
        this.animationCallback = animationCallback;
    }


    public final void setTrimCallback(TrimCallback trimCallback) {
        this.trimCallback = trimCallback;
    }


    public final void setRecordingUiFrameCallback(RecordingUiFrameCallback callback) {
        recordingUiFrameCallback = callback;
        if (callback != null) callback.onActiveChanged(recordingUiFrameClockActive);
    }


    protected final void dispatchAnimationState(boolean open, boolean fromPaused) {
        if (animationCallback != null) animationCallback.onAnimation(open, fromPaused);
    }


    protected final void dispatchTrimRange(float start, float end) {
        if (trimCallback != null) trimCallback.onTrimChanged(start, end);
    }


    protected final void dispatchRecordingUiFrameClockActive(boolean active) {
        if (recordingUiFrameClockActive == active) return;
        recordingUiFrameClockActive = active;
        if (recordingUiFrameCallback != null) {
            recordingUiFrameCallback.onActiveChanged(active);
        }
    }


    protected final void dispatchRecordingUiFrame(long durationMs) {
        if (recordingUiFrameCallback != null) recordingUiFrameCallback.onFrame(durationMs);
    }


    public abstract void setButtonsBackground(
            BlurredBackgroundDrawableViewFactory factory,
            BlurredBackgroundColorProvider colorProvider
    );


    public abstract void setInternalPadding(int padding);


    public abstract void destroy(boolean async);


    public abstract void togglePause();


    public abstract boolean isPaused();


    public abstract void showCamera(boolean fromPaused);


    public abstract void startAnimation(boolean open, boolean fromPaused);


    public abstract RectF getCameraRect();


    public abstract void changeVideoPreviewState(int state, float progress);


    public abstract void send(
            int state,
            boolean notify,
            int scheduleDate,
            int scheduleRepeatPeriod,
            int ttl,
            long effectId,
            long stars
    );


    public abstract void cancel(boolean byGesture);


    public abstract View getButtonsLayout();


    public abstract View getMuteImageView();


    public abstract Paint getPaint();


    public abstract void hideCamera(boolean async);


    public abstract TextureView getTextureView();


    public abstract InstantViewCameraContainer getCameraContainer();


    public abstract void setIsMessageTransition(boolean messageTransition);


    public abstract void resetCameraFile();


    public abstract void onPanTranslationUpdate(float translationY);


    public abstract static class InstantViewCameraContainer extends FrameLayout {

        public InstantViewCameraContainer(Context context) {
            super(context);
        }


        public abstract void setImageReceiver(ImageReceiver imageReceiver);
    }
}
