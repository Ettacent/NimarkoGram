/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.pip;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import org.telegram.ui.Components.SeekSpeedDrawable;

public interface PipContent {

    int getContentWidth();

    int getContentHeight();

    default @Nullable View getFloatingView() {
        return null;
    }

    default boolean isOverlayAllowed() {
        return true;
    }

    default boolean hasPlaybackControls() {
        return true;
    }

    default boolean resumesWithApp() {
        return false;
    }

    boolean isPlaying();

    boolean isCompleted();

    void play();

    void pause();

    long getDuration();

    long getCurrentPosition();

    float getBufferedProgress();

    void seekTo(long positionMs);

    default boolean startRewind(boolean forward, float x, @NonNull SeekSpeedDrawable seekSpeedDrawable) {
        return false;
    }

    default void updateRewind(float x) {}

    default void cancelRewind() {}

    default boolean isRewinding() {
        return false;
    }

    default void drawFloatingOverlay(@NonNull Canvas canvas, int width, int height) {}

    default @Nullable Player getMediaSessionPlayer() {
        return null;
    }

    default int getPriority() {
        return 0;
    }

    default @Nullable View createSystemView(@NonNull android.content.Context context) {
        return null;
    }

    default void onSystemEnter(@NonNull View systemView) {}

    default void onSystemEntered(@NonNull View systemView) {}

    default void onSystemExit(@NonNull View systemView) {}

    default void releaseSystemView(@NonNull View systemView) {}

    default void getSystemActions(@NonNull java.util.List<PipAction> out, int maxActions) {}

    default void onSystemAction(int actionId) {}

    default boolean canEnterWithoutUi() {
        return false;
    }

    boolean needsPipOutsideApp();

    boolean isPrimaryVisible();

    void getPrimaryVideoRect(@NonNull Rect outScreenRect);

    void releasePrimary(@NonNull PipTextureView target);

    void restorePrimary(@NonNull SurfaceTexture surfaceTexture);

    void onExpandRequested();

    void onDismissed();
}
