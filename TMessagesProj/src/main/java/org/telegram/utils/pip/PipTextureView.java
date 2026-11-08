/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.pip;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

@SuppressLint("ViewConstructor")
public class PipTextureView extends TextureView implements TextureView.SurfaceTextureListener {
    private @Nullable SurfaceTexture surfaceTexture;
    private @Nullable SurfaceTextureListener externalListener;
    private @Nullable Runnable firstFrameCallback;
    private long firstFrameAfterTimestamp;

    public PipTextureView(@NonNull Context context) {
        super(context);
        setOpaque(false);
        super.setSurfaceTextureListener(this);
    }

    public void adoptSurfaceTexture(@NonNull SurfaceTexture texture) {
        surfaceTexture = texture;
        if (getSurfaceTexture() != texture) {
            setSurfaceTexture(texture);
        }
    }

    public @Nullable SurfaceTexture takeSurfaceTexture() {
        firstFrameCallback = null;
        final SurfaceTexture texture = surfaceTexture;
        surfaceTexture = null;
        return texture;
    }

    public void releaseSurfaceTexture() {
        final SurfaceTexture texture = takeSurfaceTexture();
        if (texture != null) {
            texture.release();
        }
    }

    public boolean hasSurfaceTexture() {
        return surfaceTexture != null;
    }

    public void setFirstFrameCallback(@Nullable Runnable callback) {
        firstFrameCallback = callback;
        firstFrameAfterTimestamp = surfaceTexture != null ? surfaceTexture.getTimestamp() : 0;
    }

    @Override
    protected void onAttachedToWindow() {
        if (surfaceTexture != null && getSurfaceTexture() != surfaceTexture) {
            setSurfaceTexture(surfaceTexture);
        }
        super.onAttachedToWindow();
    }

    @Override
    public void setSurfaceTextureListener(@Nullable SurfaceTextureListener listener) {
        externalListener = listener;
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        surfaceTexture = surface;
        if (externalListener != null) {
            externalListener.onSurfaceTextureAvailable(surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        if (externalListener != null) {
            externalListener.onSurfaceTextureSizeChanged(surface, width, height);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        if (surface == surfaceTexture) {
            post(() -> {
                if (surfaceTexture == surface && getSurfaceTexture() == null) {
                    setSurfaceTexture(surface);
                }
            });
            return false;
        }
        return externalListener == null || externalListener.onSurfaceTextureDestroyed(surface);
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        if (externalListener != null) {
            externalListener.onSurfaceTextureUpdated(surface);
        }
        if (firstFrameCallback != null && (firstFrameAfterTimestamp == 0 || surface.getTimestamp() != firstFrameAfterTimestamp)) {
            final Runnable callback = firstFrameCallback;
            firstFrameCallback = null;
            callback.run();
        }
    }
}
