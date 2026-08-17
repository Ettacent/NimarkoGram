/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Build;
import androidx.annotation.Keep;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Bitmaps;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;

public class PipRoundVideoView implements NotificationCenter.NotificationCenterDelegate {

    private FrameLayout windowView;
    private Activity parentActivity;
    private int currentAccount;
    private TextureView textureView;
    private ImageView imageView;
    private AspectRatioFrameLayout aspectRatioFrameLayout;
    private Bitmap bitmap;
    private int videoWidth;
    private int videoHeight;
    private AnimatorSet hideShowAnimation;
    private ValueAnimator boundsAnimation;
    private Runnable dismissFinishRunnable;
    private Runnable dismissFinishFallbackRunnable;
    private Runnable closeAnimationStartRunnable;
    private Runnable closeAnimationFallbackRunnable;
    private ViewTreeObserver.OnPreDrawListener closeCoverPreDrawListener;
    private Runnable onCloseRunnable;
    private Runnable closeCompleteRunnable;
    private Runnable closeCompleteDispatchRunnable;
    private boolean windowAttached;
    private boolean observerRegistered;
    private boolean closing;
    private boolean closed;
    private boolean closeCompletionDispatched;

    private WindowManager.LayoutParams windowLayoutParams;
    private WindowManager windowManager;
    private SharedPreferences preferences;
    private DecelerateInterpolator decelerateInterpolator;

    private RectF rect = new RectF();

    @SuppressLint("StaticFieldLeak")
    private static PipRoundVideoView instance;

    public class PipFrameLayout extends FrameLayout {
        public PipFrameLayout(Context context) {
            super(context);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            windowAttached = false;
            releaseSnapshot();
            
            scheduleCloseComplete();
        }
    }

    public void show(Activity activity, Runnable closeRunnable) {
        if (activity == null) {
            return;
        }
        closing = false;
        closed = false;
        windowAttached = false;
        observerRegistered = false;
        closeCompletionDispatched = false;
        closeCompleteRunnable = null;
        closeCompleteDispatchRunnable = null;
        onCloseRunnable = closeRunnable;
        windowView = new PipFrameLayout(activity) {

            private float startX;
            private float startY;
            private boolean dragging;
            private boolean startDragging;

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                if (closing || closed) {
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    cancelBoundsAnimation();
                    startX = event.getRawX();
                    startY = event.getRawY();
                    startDragging = true;
                }
                return true;
            }

            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
                super.requestDisallowInterceptTouchEvent(disallowIntercept);
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (closing || closed) {
                    return false;
                }
                if (!startDragging && !dragging) {
                    return false;
                }
                float x = event.getRawX();
                float y = event.getRawY();
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = (x - startX);
                    float dy = (y - startY);
                    if (startDragging) {
                        if (Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.3f, true) || Math.abs(dy) >= AndroidUtilities.getPixelsInCM(0.3f, false)) {
                            dragging = true;
                            startDragging = false;
                        }
                    } else if (dragging) {
                        windowLayoutParams.x += dx;
                        windowLayoutParams.y += dy;
                        int maxDiff = videoWidth / 2;
                        if (windowLayoutParams.x < -maxDiff) {
                            windowLayoutParams.x = -maxDiff;
                        } else if (windowLayoutParams.x > AndroidUtilities.displaySize.x - windowLayoutParams.width + maxDiff) {
                            windowLayoutParams.x = AndroidUtilities.displaySize.x - windowLayoutParams.width + maxDiff;
                        }
                        float alpha = 1.0f;
                        if (windowLayoutParams.x < 0) {
                            alpha = 1.0f + windowLayoutParams.x / (float) maxDiff * 0.5f;
                        } else if (windowLayoutParams.x > AndroidUtilities.displaySize.x - windowLayoutParams.width) {
                            alpha = 1.0f - (windowLayoutParams.x - AndroidUtilities.displaySize.x + windowLayoutParams.width) / (float) maxDiff * 0.5f;
                        }
                        windowLayoutParams.alpha = alpha;
                        maxDiff = 0;
                        if (windowLayoutParams.y < -maxDiff) {
                            windowLayoutParams.y = -maxDiff;
                        } else if (windowLayoutParams.y > AndroidUtilities.displaySize.y - windowLayoutParams.height + maxDiff) {
                            windowLayoutParams.y = AndroidUtilities.displaySize.y - windowLayoutParams.height + maxDiff;
                        }
                        windowManager.updateViewLayout(windowView, windowLayoutParams);
                        startX = x;
                        startY = y;
                    }
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    if (event.getAction() == MotionEvent.ACTION_UP && startDragging && !dragging) {
                        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
                        if (messageObject != null) {
                            if (MediaController.getInstance().isMessagePaused()) {
                                MediaController.getInstance().playMessage(messageObject);
                            } else {
                                MediaController.getInstance().pauseMessage(messageObject);
                            }
                        }
                    }
                    dragging = false;
                    startDragging = false;
                    animateToBoundsMaybe();
                }
                return true;
            }

            @Override
            protected void onDraw(Canvas canvas) {
                if (Theme.chat_roundVideoShadow != null ) {
                    final int previousShadowAlpha = Theme.chat_roundVideoShadow.getAlpha();
                    final int previousPaintColor = Theme.chat_docBackPaint.getColor();
                    final int previousPaintAlpha = Theme.chat_docBackPaint.getAlpha();
                    try {
                        
                        Theme.chat_roundVideoShadow.setAlpha(255);
                        Theme.chat_roundVideoShadow.setBounds(AndroidUtilities.dp(1), AndroidUtilities.dp(2), AndroidUtilities.dp(125), AndroidUtilities.dp(125));
                        Theme.chat_roundVideoShadow.draw(canvas);

                        Theme.chat_docBackPaint.setColor(Theme.getColor(Theme.key_chat_inBubble));
                        Theme.chat_docBackPaint.setAlpha(255);
                        canvas.drawCircle(AndroidUtilities.dp(3 + 60), AndroidUtilities.dp(3 + 60), AndroidUtilities.dp(59.5f), Theme.chat_docBackPaint);
                    } finally {
                        Theme.chat_roundVideoShadow.setAlpha(previousShadowAlpha);
                        Theme.chat_docBackPaint.setColor(previousPaintColor);
                        Theme.chat_docBackPaint.setAlpha(previousPaintAlpha);
                    }
                }
            }
        };
        windowView.setWillNotDraw(false);

        videoWidth = AndroidUtilities.dp(120 + 6);
        videoHeight = AndroidUtilities.dp(120 + 6);

        if (Build.VERSION.SDK_INT >= 21) {
            aspectRatioFrameLayout = new AspectRatioFrameLayout(activity) {
                @Override
                protected void dispatchDraw(Canvas canvas) {
                    super.dispatchDraw(canvas);
                    drawProgressArc(canvas, getMeasuredWidth(), getMeasuredHeight());
                }
            };
            aspectRatioFrameLayout.setOutlineProvider(new ViewOutlineProvider() {
                @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, AndroidUtilities.dp(120), AndroidUtilities.dp(120));
                }
            });
            aspectRatioFrameLayout.setClipToOutline(true);
        } else {
            final Paint aspectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            aspectPaint.setColor(0xff000000);
            aspectPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            aspectRatioFrameLayout = new AspectRatioFrameLayout(activity) {

                private Path aspectPath = new Path();

                @Override
                protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                    super.onSizeChanged(w, h, oldw, oldh);
                    aspectPath.reset();
                    aspectPath.addCircle(w / 2, h / 2, w / 2, Path.Direction.CW);
                    aspectPath.toggleInverseFillType();
                }

                @Override
                protected void dispatchDraw(Canvas canvas) {
                    super.dispatchDraw(canvas);
                    canvas.drawPath(aspectPath, aspectPaint);
                    drawProgressArc(canvas, getMeasuredWidth(), getMeasuredHeight());
                }

                @Override
                protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                    boolean result;
                    try {
                        result = super.drawChild(canvas, child, drawingTime);
                    } catch (Throwable ignore) {
                        result = false;
                    }
                    return result;
                }
            };
            aspectRatioFrameLayout.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        aspectRatioFrameLayout.setAspectRatio(1.0f, 0);
        windowView.addView(aspectRatioFrameLayout, LayoutHelper.createFrame(120, 120, Gravity.LEFT | Gravity.TOP, 3, 3, 0, 0));
        windowView.setAlpha(1.0f);
        windowView.setScaleX(0.8f);
        windowView.setScaleY(0.8f);

        textureView = new TextureView(activity);
        float scale = (AndroidUtilities.dpf2(120) + AndroidUtilities.dpf2(2)) / AndroidUtilities.dpf2(120);
        textureView.setScaleX(scale);
        textureView.setScaleY(scale);
        aspectRatioFrameLayout.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        imageView = new ImageView(activity);
        aspectRatioFrameLayout.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        imageView.setVisibility(View.INVISIBLE);

        windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);

        preferences = ApplicationLoader.applicationContext.getSharedPreferences("pipconfig", Context.MODE_PRIVATE);

        int sidex = preferences.getInt("sidex", 1);
        int sidey = preferences.getInt("sidey", 0);
        float px = preferences.getFloat("px", 0);
        float py = preferences.getFloat("py", 0);

        try {
            windowLayoutParams = new WindowManager.LayoutParams();
            windowLayoutParams.width = videoWidth;
            windowLayoutParams.height = videoHeight;
            windowLayoutParams.x = getSideCoord(true, sidex, px, videoWidth);
            windowLayoutParams.y = getSideCoord(false, sidey, py, videoHeight);
            windowLayoutParams.format = PixelFormat.TRANSLUCENT;
            windowLayoutParams.alpha = 1f;
            windowLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
            windowLayoutParams.type = WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
            windowLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            AndroidUtilities.setPreferredMaxRefreshRate(windowManager, windowView, windowLayoutParams);
            windowManager.addView(windowView, windowLayoutParams);
            windowAttached = true;
        } catch (Exception e) {
            FileLog.e(e);
            close(false);
            throw new IllegalStateException("Unable to attach round-video PiP window", e);
        }
        parentActivity = activity;
        currentAccount = UserConfig.selectedAccount;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        observerRegistered = true;
        instance = this;
        runShowHideAnimation(true);
    }

    private static int getSideCoord(boolean isX, int side, float p, int sideSize) {
        int total;
        if (isX) {
            total = AndroidUtilities.displaySize.x - sideSize;
        } else {
            total = AndroidUtilities.displaySize.y - sideSize - ActionBar.getCurrentActionBarHeight();
        }
        int result;
        if (side == 0) {
            result = AndroidUtilities.dp(10);
        } else if (side == 1) {
            result = total - AndroidUtilities.dp(10);
        } else {
            result = Math.round((total - AndroidUtilities.dp(20)) * p) + AndroidUtilities.dp(10);
        }
        if (!isX) {
            result += ActionBar.getCurrentActionBarHeight();
        }
        return result;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!closing && !closed && id == NotificationCenter.messagePlayingProgressDidChanged) {
            if (aspectRatioFrameLayout != null) {
                aspectRatioFrameLayout.invalidate();
            }
        }
    }

    public TextureView getTextureView() {
        return textureView;
    }

    private void drawProgressArc(Canvas canvas, int width, int height) {
        MessageObject currentMessageObject = MediaController.getInstance().getPlayingMessageObject();
        if (currentMessageObject == null) {
            return;
        }
        rect.set(
                AndroidUtilities.dpf2(1.5f),
                AndroidUtilities.dpf2(1.5f),
                width - AndroidUtilities.dpf2(1.5f),
                height - AndroidUtilities.dpf2(1.5f));
        canvas.drawArc(rect, -90, 360 * currentMessageObject.audioProgress, false, Theme.chat_radialProgressPaint);
    }

    public void close(boolean animated) {
        close(animated, null);
    }

    public void close(boolean animated, Runnable onComplete) {
        addCloseCompletion(onComplete);
        if (closed) {
            return;
        }
        if (animated) {
            if (closing) {
                return;
            }
            closing = true;
            cancelBoundsAnimation();

            if (textureView != null && textureView.getParent() != null && imageView != null && aspectRatioFrameLayout != null
                    && textureView.isAvailable()) {
                Bitmap frame = null;
                try {
                    if (textureView.getWidth() > 0 && textureView.getHeight() > 0) {
                        frame = Bitmaps.createBitmap(textureView.getWidth(), textureView.getHeight(), Bitmap.Config.ARGB_8888);
                        bitmap = textureView.getBitmap(frame);
                        if (bitmap == null && !frame.isRecycled()) {
                            frame.recycle();
                        }
                    }
                } catch (Throwable e) {
                    if (frame != null && frame != bitmap && !frame.isRecycled()) {
                        frame.recycle();
                    }
                    bitmap = null;
                }
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setScaleX(textureView.getScaleX());
                    imageView.setScaleY(textureView.getScaleY());
                    imageView.setVisibility(View.VISIBLE);
                    closeAnimationStartRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (closeAnimationStartRunnable != this || closed) {
                                return;
                            }
                            if (closeAnimationFallbackRunnable != null) {
                                AndroidUtilities.cancelRunOnUIThread(closeAnimationFallbackRunnable);
                                closeAnimationFallbackRunnable = null;
                            }
                            removeCloseCoverPreDrawListener();
                            closeAnimationStartRunnable = null;
                            
                            runShowHideAnimation(false);
                        }
                    };
                    
                    closeCoverPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            removeCloseCoverPreDrawListener();
                            if (!closed && closeAnimationStartRunnable != null) {
                                runCloseAnimationAfterCoverCommit(closeAnimationStartRunnable);
                            }
                            return true;
                        }
                    };
                    imageView.getViewTreeObserver().addOnPreDrawListener(closeCoverPreDrawListener);
                    imageView.invalidate();
                    closeAnimationFallbackRunnable = () -> {
                        closeAnimationFallbackRunnable = null;
                        removeCloseCoverPreDrawListener();
                        Runnable start = closeAnimationStartRunnable;
                        if (start != null && !closed) {
                            start.run();
                        }
                    };
                    AndroidUtilities.runOnUIThread(closeAnimationFallbackRunnable, 250);
                    return;
                }
            }
            runShowHideAnimation(false);
        } else {
            closed = true;
            closing = true;
            cancelCloseAnimationStart();
            cancelBoundsAnimation();
            cancelHideShowAnimation();
            boolean removalRequested = false;
            if (windowAttached && windowManager != null && windowView != null) {
                try {
                    windowManager.removeView(windowView);
                    removalRequested = true;
                    windowAttached = false;
                } catch (Exception e) {
                    FileLog.e(e);
                    
                    try {
                        windowManager.removeViewImmediate(windowView);
                        removalRequested = true;
                        windowAttached = false;
                    } catch (Exception immediateError) {
                        FileLog.e(immediateError);
                    }
                }
            }
            if (instance == this) {
                instance = null;
            }
            parentActivity = null;
            if (observerRegistered) {
                observerRegistered = false;
                NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            }
            onCloseRunnable = null;
            if (windowView != null && windowView.isAttachedToWindow() && !removalRequested) {
                
                try {
                    if (textureView != null && textureView.getParent() == aspectRatioFrameLayout) {
                        aspectRatioFrameLayout.removeView(textureView);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                windowView.setVisibility(View.INVISIBLE);
                releaseSnapshot();
            }
            if (!removalRequested || windowView == null || !windowView.isAttachedToWindow()) {
                scheduleCloseComplete();
            }
        }
    }

    public void onConfigurationChanged() {
        if (closing || closed || !windowAttached || preferences == null || windowLayoutParams == null || windowManager == null || windowView == null) {
            return;
        }
        int sidex = preferences.getInt("sidex", 1);
        int sidey = preferences.getInt("sidey", 0);
        float px = preferences.getFloat("px", 0);
        float py = preferences.getFloat("py", 0);
        windowLayoutParams.x = getSideCoord(true, sidex, px, videoWidth);
        windowLayoutParams.y = getSideCoord(false, sidey, py, videoHeight);
        windowManager.updateViewLayout(windowView, windowLayoutParams);
    }

    public void showTemporary(boolean show) {
        if (closing || closed || !windowAttached || windowView == null) {
            return;
        }
        cancelHideShowAnimation();
        hideShowAnimation = new AnimatorSet();
        hideShowAnimation.playTogether(
                ObjectAnimator.ofFloat(windowView, View.ALPHA, show ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(windowView, View.SCALE_X, show ? 1.0f : 0.8f),
                ObjectAnimator.ofFloat(windowView, View.SCALE_Y, show ? 1.0f : 0.8f));
        hideShowAnimation.setDuration(150);
        if (decelerateInterpolator == null) {
            decelerateInterpolator = new DecelerateInterpolator();
        }
        hideShowAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation.equals(hideShowAnimation)) {
                    hideShowAnimation = null;
                }
            }
        });
        hideShowAnimation.setInterpolator(decelerateInterpolator);
        hideShowAnimation.start();
    }

    private void runShowHideAnimation(final boolean show) {
        if (closed || !windowAttached || windowView == null) {
            if (!show) {
                close(false);
            }
            return;
        }
        cancelHideShowAnimation();
        hideShowAnimation = new AnimatorSet();
        hideShowAnimation.playTogether(
                ObjectAnimator.ofFloat(windowView, View.ALPHA, show ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(windowView, View.SCALE_X, show ? 1.0f : 0.8f),
                ObjectAnimator.ofFloat(windowView, View.SCALE_Y, show ? 1.0f : 0.8f));
        hideShowAnimation.setDuration(150);
        if (decelerateInterpolator == null) {
            decelerateInterpolator = new DecelerateInterpolator();
        }
        hideShowAnimation.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation.equals(hideShowAnimation)) {
                    hideShowAnimation = null;
                    if (!cancelled && !show) {
                        scheduleFinishAfterFrame(false);
                    }
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
                if (animation.equals(hideShowAnimation)) {
                    hideShowAnimation = null;
                }
            }
        });
        hideShowAnimation.setInterpolator(decelerateInterpolator);
        hideShowAnimation.start();
    }

    private void cancelHideShowAnimation() {
        AnimatorSet animation = hideShowAnimation;
        hideShowAnimation = null;
        if (animation != null) {
            animation.cancel();
        }
    }

    private void removeCloseCoverPreDrawListener() {
        if (closeCoverPreDrawListener == null || imageView == null) {
            closeCoverPreDrawListener = null;
            return;
        }
        ViewTreeObserver observer = imageView.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(closeCoverPreDrawListener);
        }
        closeCoverPreDrawListener = null;
    }

    private void runCloseAnimationAfterCoverCommit(Runnable expectedStart) {
        if (expectedStart == null || closed || windowView == null || imageView == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ViewTreeObserver observer = windowView.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.registerFrameCommitCallback(() -> AndroidUtilities.runOnUIThread(() -> {
                    if (!closed && closeAnimationStartRunnable == expectedStart) {
                        expectedStart.run();
                    }
                }));
                return;
            }
        }
        
        imageView.postOnAnimation(() -> imageView.postOnAnimation(() -> {
            if (!closed && closeAnimationStartRunnable == expectedStart) {
                expectedStart.run();
            }
        }));
    }

    private void cancelCloseAnimationStart() {
        removeCloseCoverPreDrawListener();
        if (closeAnimationFallbackRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(closeAnimationFallbackRunnable);
            closeAnimationFallbackRunnable = null;
        }
        if (closeAnimationStartRunnable != null && imageView != null) {
            imageView.removeCallbacks(closeAnimationStartRunnable);
        }
        closeAnimationStartRunnable = null;
    }

    private void addCloseCompletion(Runnable completion) {
        if (completion == null) {
            return;
        }
        if (closeCompletionDispatched) {
            completion.run();
            return;
        }
        Runnable previous = closeCompleteRunnable;
        closeCompleteRunnable = previous == null ? completion : () -> {
            previous.run();
            completion.run();
        };
    }

    private void dispatchCloseComplete() {
        if (closeCompletionDispatched) {
            return;
        }
        if (closeCompleteDispatchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(closeCompleteDispatchRunnable);
            closeCompleteDispatchRunnable = null;
        }
        closeCompletionDispatched = true;
        Runnable completion = closeCompleteRunnable;
        closeCompleteRunnable = null;
        if (completion != null) {
            completion.run();
        }
    }

    private void scheduleCloseComplete() {
        if (closeCompletionDispatched || closeCompleteDispatchRunnable != null) {
            return;
        }
        closeCompleteDispatchRunnable = () -> {
            closeCompleteDispatchRunnable = null;
            dispatchCloseComplete();
        };
        AndroidUtilities.runOnUIThread(closeCompleteDispatchRunnable);
    }

    private void scheduleFinishAfterFrame(boolean fromUser) {
        if (closed) {
            return;
        }
        cancelFinishAfterFrame();
        dismissFinishRunnable = new Runnable() {
            @Override
            public void run() {
                if (dismissFinishRunnable != this) {
                    return;
                }
                dismissFinishRunnable = null;
                if (!closed) {
                    if (fromUser) {
                        closeFromUser();
                    } else {
                        close(false);
                    }
                }
            }
        };
        if (windowView != null && windowAttached) {
            final Runnable expectedFinish = dismissFinishRunnable;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ViewTreeObserver observer = windowView.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.registerFrameCommitCallback(() -> AndroidUtilities.runOnUIThread(() -> {
                        if (dismissFinishRunnable == expectedFinish) {
                            expectedFinish.run();
                        }
                    }));
                    windowView.invalidate();
                } else {
                    postFinishAfterTwoFrames(expectedFinish);
                }
            } else {
                postFinishAfterTwoFrames(expectedFinish);
            }
            dismissFinishFallbackRunnable = () -> {
                dismissFinishFallbackRunnable = null;
                if (dismissFinishRunnable == expectedFinish) {
                    expectedFinish.run();
                }
            };
            AndroidUtilities.runOnUIThread(dismissFinishFallbackRunnable, 250);
        } else {
            dismissFinishRunnable.run();
        }
    }

    private void postFinishAfterTwoFrames(Runnable expectedFinish) {
        windowView.postOnAnimation(() -> windowView.postOnAnimation(() -> {
            if (dismissFinishRunnable == expectedFinish) {
                expectedFinish.run();
            }
        }));
    }

    private void cancelFinishAfterFrame() {
        dismissFinishRunnable = null;
        if (dismissFinishFallbackRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(dismissFinishFallbackRunnable);
            dismissFinishFallbackRunnable = null;
        }
    }

    private void cancelBoundsAnimation() {
        cancelFinishAfterFrame();
        ValueAnimator animation = boundsAnimation;
        boundsAnimation = null;
        if (animation != null) {
            animation.cancel();
        }
    }

    private void closeFromUser() {
        Runnable closeRunnable = onCloseRunnable;
        onCloseRunnable = null;
        close(false);
        if (closeRunnable != null) {
            closeRunnable.run();
        }
    }

    private void releaseSnapshot() {
        if (imageView != null) {
            imageView.setImageDrawable(null);
        }
        
        bitmap = null;
    }

    private void animateToBoundsMaybe() {
        if (closing || closed || !windowAttached || windowView == null || windowLayoutParams == null || preferences == null) {
            return;
        }
        cancelBoundsAnimation();
        final int startX = getSideCoord(true, 0, 0, videoWidth);
        final int endX = getSideCoord(true, 1, 0, videoWidth);
        final int startY = getSideCoord(false, 0, 0, videoHeight);
        final int endY = getSideCoord(false, 1, 0, videoHeight);
        final int fromX = windowLayoutParams.x;
        final int fromY = windowLayoutParams.y;
        final float fromAlpha = windowLayoutParams.alpha;
        int toX = fromX;
        int toY = fromY;
        float toAlpha = 1f;
        boolean animate = false;
        SharedPreferences.Editor editor = preferences.edit();
        int maxDiff = AndroidUtilities.dp(20);
        boolean slideOut = false;
        if (Math.abs(startX - windowLayoutParams.x) <= maxDiff || windowLayoutParams.x < 0 && windowLayoutParams.x > -videoWidth / 4) {
            editor.putInt("sidex", 0);
            toX = startX;
            animate = toX != fromX || fromAlpha != 1f;
        } else if (Math.abs(endX - windowLayoutParams.x) <= maxDiff || windowLayoutParams.x > AndroidUtilities.displaySize.x - videoWidth && windowLayoutParams.x < AndroidUtilities.displaySize.x - videoWidth / 4 * 3) {
            editor.putInt("sidex", 1);
            toX = endX;
            animate = toX != fromX || fromAlpha != 1f;
        } else if (fromAlpha != 1f) {
            toX = windowLayoutParams.x < 0 ? -videoWidth : AndroidUtilities.displaySize.x;
            toAlpha = 0f;
            animate = true;
            slideOut = true;
        } else {
            editor.putFloat("px", (windowLayoutParams.x - startX) / (float) (endX - startX));
            editor.putInt("sidex", 2);
        }
        if (!slideOut) {
            if (Math.abs(startY - windowLayoutParams.y) <= maxDiff || windowLayoutParams.y <= ActionBar.getCurrentActionBarHeight()) {
                editor.putInt("sidey", 0);
                toY = startY;
                animate |= toY != fromY;
            } else if (Math.abs(endY - windowLayoutParams.y) <= maxDiff) {
                editor.putInt("sidey", 1);
                toY = endY;
                animate |= toY != fromY;
            } else {
                editor.putFloat("py", (windowLayoutParams.y - startY) / (float) (endY - startY));
                editor.putInt("sidey", 2);
            }
            editor.apply();
        }
        if (animate) {
            if (decelerateInterpolator == null) {
                decelerateInterpolator = new DecelerateInterpolator();
            }
            final int targetX = toX;
            final int targetY = toY;
            final float targetAlpha = toAlpha;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            boundsAnimation = animator;
            animator.setInterpolator(decelerateInterpolator);
            animator.setDuration(150);
            if (slideOut) {
                closing = true;
                cancelHideShowAnimation();
            }
            final boolean dismiss = slideOut;
            animator.addUpdateListener(valueAnimator -> {
                if (closed || !windowAttached || windowManager == null || windowView == null) {
                    return;
                }
                final float progress = (float) valueAnimator.getAnimatedValue();
                windowLayoutParams.x = Math.round(fromX + (targetX - fromX) * progress);
                windowLayoutParams.y = Math.round(fromY + (targetY - fromY) * progress);
                windowLayoutParams.alpha = fromAlpha + (targetAlpha - fromAlpha) * progress;
                try {
                    
                    windowManager.updateViewLayout(windowView, windowLayoutParams);
                } catch (Exception error) {
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                    if (animation == boundsAnimation) {
                        boundsAnimation = null;
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == boundsAnimation) {
                        boundsAnimation = null;
                        if (!cancelled && dismiss) {
                            
                            scheduleFinishAfterFrame(true);
                        }
                    }
                }
            });
            animator.start();
        }
    }

    @Keep
    public int getX() {
        return windowLayoutParams.x;
    }

    @Keep
    public int getY() {
        return windowLayoutParams.y;
    }

    @Keep
    public void setX(int value) {
        if (closed || !windowAttached || windowLayoutParams == null || windowManager == null || windowView == null) {
            return;
        }
        windowLayoutParams.x = value;
        try {
            windowManager.updateViewLayout(windowView, windowLayoutParams);
        } catch (Exception error) {
        }
    }

    @Keep
    public void setY(int value) {
        if (closed || !windowAttached || windowLayoutParams == null || windowManager == null || windowView == null) {
            return;
        }
        windowLayoutParams.y = value;
        try {
            windowManager.updateViewLayout(windowView, windowLayoutParams);
        } catch (Exception error) {
        }
    }

    public static PipRoundVideoView getInstance() {
        return instance;
    }
}
