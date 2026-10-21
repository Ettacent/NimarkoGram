/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import app.nimarkogram.messenger.icons.NimarkoIconResources;
import org.telegram.messenger.R;

public final class PhotoProgressPackRenderer {
    private final BoundedPackIconRenderer cancel = new BoundedPackIconRenderer();
    private final BoundedPackIconRenderer download = new BoundedPackIconRenderer();
    private final Paint foreground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean ready;

    public PhotoProgressPackRenderer() {
        foreground.setColor(Color.WHITE);
    }

    public void prepare() {
        NimarkoIconResources.NavigationIcons batch = NimarkoIconResources.activeNavigationIcons();
        ready = cancel.prepare(batch, R.drawable.ng_media_cancel)
                && download.prepare(batch, R.drawable.ng_file_download);
    }

    public boolean draw(Canvas canvas, int role, Drawable background, int alpha,
                        int x, int y, int size) {
        if (!ready || background == null || size <= 0
                || (role != R.drawable.ng_media_cancel && role != R.drawable.ng_file_download)) {
            return false;
        }
        background.setAlpha(alpha);
        background.setBounds(x, y, x + size, y + size);
        background.draw(canvas);
        float unit = size / 64f;
        if (role == R.drawable.ng_media_cancel) {
            cancel.draw(canvas, foreground, x + size / 2f, y + size / 2f,
                    unit * (64f / 3f), unit * (64f / 3f), alpha / 255f, false);
        } else {
            download.draw(canvas, foreground, x + size / 2f, y + unit * (100f / 3f),
                    unit * 18f, unit * 26f, alpha / 255f, false);
        }
        return true;
    }
}
