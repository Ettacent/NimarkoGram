/**
 * NG port of Cherrygram's LockAnimationView. Identical to CG's open-source
 * implementation — only the package is renamed. Used by the video-recording
 * lock indicator in the in-app camera (ChatAttachAlertPhotoLayout): when the
 * user is holding the record button and slides up, this view animates the
 * lock progress, and snaps to the locked state when the gesture completes.
 *
 * Originally missing from NG's camera port; ChatAttachAlertPhotoLayout's
 * lockAnimationView field + animations were dropped along with it. Restored
 * here for visual + UX parity with CG.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */
package app.nimarkogram.messenger.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class LockAnimationView extends LinearLayout {
    private float yAdd = 0;
    private boolean isLocked = false;

    public LockAnimationView(Context context) {
        super(context);
        setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView imageView = new ImageView(context) {
            float idleProgress;
            boolean incIdle;
            private final int lockColor = Theme.getColor(Theme.key_chat_messagePanelVoiceLock);
            private final int backgroundLockColor = Theme.getColor(Theme.key_chat_messagePanelVoiceLockBackground);
            private final Paint lockOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint backgroundCircle = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF lockRect = new RectF();
            private final RectF bodyRect = new RectF();
            private final Path clipPath = new Path();

            {
                lockOutlinePaint.setStyle(Paint.Style.STROKE);
                lockOutlinePaint.setStrokeCap(Paint.Cap.ROUND);
                lockOutlinePaint.setColor(lockColor);
                backgroundCircle.setColor(backgroundLockColor);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int mHeight = getMeasuredHeight();
                if (incIdle) {
                    idleProgress += 0.03f;
                    if (idleProgress > 1f) {
                        incIdle = false;
                        idleProgress = 1f;
                    }
                } else {
                    idleProgress -= 0.03f;
                    if (idleProgress < 0) {
                        incIdle = true;
                        idleProgress = 0;
                    }
                }
                if (isLocked) {
                    if (yAdd >= 0) {
                        yAdd -= 0.2f;
                    }
                }

                int sizeLock = AndroidUtilities.dp2(28);
                int sizeCircleBackground = Math.round(((sizeLock >> 1) * 150f) / 100f);
                int sizeLockPart = Math.round(((sizeLock - ((sizeLock * 40f) / 100f)) / 150f) * 100f);
                float strokeWidth = (sizeLockPart * 18.18f) / 100f;
                int heightWithLock = Math.round(mHeight - sizeLock - strokeWidth - (sizeCircleBackground - sizeLock));
                float moveProgress = 1.0f - yAdd;
                float lockRotation = 9 * (1f - moveProgress);
                lockOutlinePaint.setStrokeWidth(strokeWidth);
                int totalLine = sizeLockPart >> 1;
                int sizeLine = Math.round((totalLine * 15.2f) / 100f);
                int sizeLineAnimated = Math.round((totalLine * 38f) / 100f);
                int radius = Math.round((sizeLockPart * 18f) / 100f);
                int cx = (getMeasuredWidth() >> 1) - (sizeLockPart >> 1);
                int circleY = Math.round(heightWithLock + ((sizeLock + strokeWidth) / 2f));

                lockRect.set(
                        cx,
                        heightWithLock,
                        cx + sizeLockPart,
                        heightWithLock + sizeLockPart
                );
                canvas.save();
                canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), isLocked ? Math.round(255 * yAdd) : 255, Canvas.ALL_SAVE_FLAG);
                canvas.translate(0, -(AndroidUtilities.dpf2(50) / 2f - idleProgress * AndroidUtilities.dpf2(3f)));
                canvas.save();
                int startCy = Math.round(lockRect.bottom);
                int sizeLockBottom = (sizeLockPart * 150) / 100;
                int sizeCircle = ((sizeLockBottom * 25) / 100) >> 1;
                int cx2 = (getMeasuredWidth() >> 1) - (sizeLockBottom >> 1);
                bodyRect.set(
                        cx2,
                        startCy,
                        cx2 + sizeLockBottom,
                        startCy + sizeLockBottom
                );
                canvas.translate(0, Math.max(-((heightWithLock - sizeLock) * (1f - moveProgress)), -(heightWithLock - sizeLock - AndroidUtilities.dpf2(6f))));
                canvas.rotate(lockRotation, bodyRect.centerX(), bodyRect.centerY());
                canvas.drawCircle(getMeasuredWidth() >> 1, circleY, sizeCircleBackground, backgroundCircle);
                clipPath.rewind();
                clipPath.addCircle(bodyRect.centerX(), bodyRect.centerY(), sizeCircle, Path.Direction.CW);
                canvas.clipPath(clipPath, Region.Op.DIFFERENCE);
                for (int i = 0; i < 2; i++) {
                    canvas.drawRoundRect(bodyRect, radius, radius, lockOutlinePaint);
                    lockOutlinePaint.setStyle(Paint.Style.FILL);
                }
                lockOutlinePaint.setStyle(Paint.Style.STROKE);

                canvas.save();
                if (lockRotation > 0) {
                    canvas.rotate(lockRotation, lockRect.centerX(), lockRect.centerY());
                }
                canvas.drawArc(lockRect, 0, -180, false, lockOutlinePaint);
                canvas.drawLine(
                        cx,
                        lockRect.bottom - (sizeLockPart >> 1),
                        cx,
                        lockRect.bottom - (sizeLockPart >> 1) + (sizeLine + sizeLineAnimated) * (1f - idleProgress) * moveProgress,
                        lockOutlinePaint
                );
                canvas.drawLine(
                        lockRect.right, lockRect.bottom - (sizeLockPart >> 1),
                        lockRect.right, lockRect.bottom - (sizeLockPart >> 1) + totalLine,
                        lockOutlinePaint
                );
                canvas.restore();
                canvas.restore();
                canvas.restore();
                postInvalidateOnAnimation();
            }
        };
        addView(imageView, LayoutHelper.createLinear(AndroidUtilities.dp(50), LayoutHelper.MATCH_PARENT));
    }

    public void setCurrentMove(float value) {
        if (!isLocked) {
            yAdd = value;
        }
    }

    public void setLocked(boolean isLocked) {
        this.isLocked = isLocked;
    }
}
