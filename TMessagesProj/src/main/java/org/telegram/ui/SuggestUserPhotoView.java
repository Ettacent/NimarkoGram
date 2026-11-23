/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import android.view.ViewTreeObserver;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.Components.AvatarDrawable;
public class SuggestUserPhotoView extends View implements ViewTreeObserver.OnPreDrawListener {

    interface Preview {
        boolean draw(Canvas canvas, RectF bounds);
    }

    private final ImageReceiver currentPhoto = new ImageReceiver(this);
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable arrow, placeholder;
    private final View editor;
    private final Preview preview;
    private ViewTreeObserver editorObserver;

    SuggestUserPhotoView(Context context, int account, TLObject recipient, View editor, Preview preview) {
        super(context);
        this.editor = editor;
        this.preview = preview;
        AvatarDrawable avatar = new AvatarDrawable();
        avatar.setInfo(account, recipient);
        currentPhoto.setCurrentAccount(account);
        currentPhoto.setRoundRadius(AndroidUtilities.dp(30));
        currentPhoto.setForUserOrChat(recipient, avatar);
        arrow = ContextCompat.getDrawable(context, R.drawable.msg_arrow_avatar).mutate();
        arrow.setAlpha(100);
        placeholder = ContextCompat.getDrawable(context, R.drawable.msg_photos).mutate();
        placeholder.setTint(0x99ffffff);
        placeholderPaint.setColor(0x25ffffff);
    }


    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int center = getWidth() / 2, radius = AndroidUtilities.dp(30), offset = AndroidUtilities.dp(46);
        int y = getHeight() - radius, x = center + offset;
        currentPhoto.setImageCoords(center - offset - radius, y - radius, 2 * radius, 2 * radius);

        currentPhoto.draw(canvas);

        int hw = arrow.getIntrinsicWidth() / 2, hh = arrow.getIntrinsicHeight() / 2;
        arrow.setBounds(center - hw, y - hh, center + hw, y + hh);
        arrow.draw(canvas);

        bounds.set(x - radius, y - radius, x + radius, y + radius);
        clip.rewind();
        clip.addCircle(x, y, radius, Path.Direction.CW);
        int save = canvas.save();
        try {
            canvas.clipPath(clip);
            if (!preview.draw(canvas, bounds)) {
                canvas.drawCircle(x, y, radius, placeholderPaint);
                int half = AndroidUtilities.dp(12);
                placeholder.setBounds(x - half, y - half, x + half, y + half);
                placeholder.draw(canvas);
        }
        } finally {
            canvas.restoreToCount(save);
    }

    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(86), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        currentPhoto.onAttachedToWindow();
        editorObserver = editor.getViewTreeObserver();
        editorObserver.addOnPreDrawListener(this);
    }

    @Override
    public boolean onPreDraw() {
        postInvalidateOnAnimation();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (editorObserver != null) {
            ViewTreeObserver observer = editorObserver.isAlive() ? editorObserver : editor.getViewTreeObserver();
            observer.removeOnPreDrawListener(this);
        }
        editorObserver = null;
        currentPhoto.onDetachedFromWindow();
        super.onDetachedFromWindow();
    }
}
