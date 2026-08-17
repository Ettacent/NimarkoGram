package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.Utilities;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.components.templates.PluginRuntimeDelegate;

public class UniversalFrameLayout extends app.nimarkogram.messenger.plugins.ui.components.templates.UniversalFrameLayout {

    private UniversalFrameLayoutListener bridgeListener;

    public UniversalFrameLayout(Context context) {
        super(context);
    }

    public UniversalFrameLayout(Context context, UniversalFrameLayoutListener listener) {
        super(context);
        setUniversalFrameLayoutListener(listener);
    }

    public interface UniversalFrameLayoutListener {
        default void dispatchDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) {
            originalMethod.run(canvas);
        }
        default boolean drawChild(Canvas canvas, View child, long drawingTime, Utilities.Callback3Return<Canvas, View, Long, Boolean> originalMethod) {
            return originalMethod.run(canvas, child, drawingTime);
        }
        default void invalidate(int l, int t, int r, int b, Runnable originalMethod) {
            originalMethod.run();
        }
        default void invalidate(Runnable originalMethod) {
            originalMethod.run();
        }
        default void onAttachedToWindow(Runnable originalMethod) {
            originalMethod.run();
        }
        default void onDetachedFromWindow(Runnable originalMethod) {
            originalMethod.run();
        }
        default void onDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) {
            originalMethod.run(canvas);
        }
        default void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> originalMethod) {
            originalMethod.run(info);
        }
        default boolean onInterceptTouchEvent(MotionEvent ev, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) {
            return originalMethod.run(ev);
        }
        default void onLayout(boolean changed, int left, int top, int right, int bottom, Utilities.Callback5<Boolean, Integer, Integer, Integer, Integer> originalMethod) {
            originalMethod.run(changed, left, top, right, bottom);
        }
        default void onMeasure(int widthMeasureSpec, int heightMeasureSpec, Utilities.Callback2<Integer, Integer> originalMethod) {
            originalMethod.run(widthMeasureSpec, heightMeasureSpec);
        }
        default boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) {
            return originalMethod.run(event);
        }
        default void requestLayout(Runnable originalMethod) {
            originalMethod.run();
        }
        default void setTranslationX(float translationX, Utilities.Callback<Float> originalMethod) {
            originalMethod.run(translationX);
        }
        default void setTranslationY(float translationY, Utilities.Callback<Float> originalMethod) {
            originalMethod.run(translationY);
        }
        default void setVisibility(int visibility, Utilities.Callback<Integer> originalMethod) {
            originalMethod.run(visibility);
        }
    }

    public UniversalFrameLayoutListener getUniversalFrameLayoutListenerBridge() {
        PluginRuntimeDelegate.requireMainThread();
        return this.bridgeListener;
    }

    public void setUniversalFrameLayoutListener(UniversalFrameLayoutListener listener) {
        if (listener == null) {
            super.setUniversalFrameLayoutListener(null);
            this.bridgeListener = null;
            return;
        }
        PluginsController.PluginRuntimeToken runtimeToken =
                PluginRuntimeDelegate.capture(listener);
        GuardedListener guardedListener =
                new GuardedListener(listener, runtimeToken);
        super.setUniversalFrameLayoutListener(guardedListener);
        if (super.getUniversalFrameLayoutListener() == guardedListener) {
            this.bridgeListener = guardedListener;
        } else {
            guardedListener.clear();
        }
    }

    @Override
    protected void onPluginDelegateCleared() {
        UniversalFrameLayoutListener listener = this.bridgeListener;
        this.bridgeListener = null;
        if (listener instanceof GuardedListener) {
            ((GuardedListener) listener).clear();
        }
    }

    private static final class GuardedListener implements
            UniversalFrameLayoutListener,
            app.nimarkogram.messenger.plugins.ui.components.templates
                    .UniversalFrameLayout.UniversalFrameLayoutListener {
        private UniversalFrameLayoutListener listener;
        private final PluginsController.PluginRuntimeToken runtimeToken;

        GuardedListener(
                UniversalFrameLayoutListener listener,
                PluginsController.PluginRuntimeToken runtimeToken) {
            this.listener = listener;
            this.runtimeToken = runtimeToken;
        }

        void clear() {
            this.listener = null;
        }

        @Override
        public void dispatchDraw(
                Canvas canvas, Utilities.Callback<Canvas> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.dispatchDraw(canvas, callback));
            }
        }

        @Override
        public boolean drawChild(
                Canvas canvas,
                View child,
                long drawingTime,
                Utilities.Callback3Return<Canvas, View, Long, Boolean> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            return listener != null && PluginRuntimeDelegate.call(
                    runtimeToken,
                    () -> listener.drawChild(
                            canvas, child, drawingTime, callback),
                    false);
        }

        @Override
        public void invalidate(
                int left,
                int top,
                int right,
                int bottom,
                Runnable superCall) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.invalidate(
                                left, top, right, bottom, superCall));
            }
        }

        @Override
        public void invalidate(Runnable superCall) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, () -> listener.invalidate(superCall));
            }
        }

        @Override
        public void onAttachedToWindow(Runnable superCall) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.onAttachedToWindow(superCall));
            }
        }

        @Override
        public void onDetachedFromWindow(Runnable superCall) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.onDetachedFromWindow(superCall));
            }
        }

        @Override
        public void onDraw(
                Canvas canvas, Utilities.Callback<Canvas> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.onDraw(canvas, callback));
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(
                AccessibilityNodeInfo info,
                Utilities.Callback<AccessibilityNodeInfo> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.onInitializeAccessibilityNodeInfo(
                                info, callback));
            }
        }

        @Override
        public boolean onInterceptTouchEvent(
                MotionEvent event,
                Utilities.CallbackReturn<MotionEvent, Boolean> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            return listener != null && PluginRuntimeDelegate.call(
                    runtimeToken,
                    () -> listener.onInterceptTouchEvent(event, callback),
                    false);
        }

        @Override
        public void onLayout(
                boolean changed,
                int left,
                int top,
                int right,
                int bottom,
                Utilities.Callback5<Boolean, Integer, Integer, Integer, Integer>
                        callback) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.onLayout(
                                changed, left, top, right, bottom, callback));
            }
        }

        @Override
        public void onMeasure(
                int widthMeasureSpec,
                int heightMeasureSpec,
                Utilities.Callback2<Integer, Integer> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.onMeasure(
                                widthMeasureSpec, heightMeasureSpec, callback));
            }
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event,
                Utilities.CallbackReturn<MotionEvent, Boolean> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            return listener != null && PluginRuntimeDelegate.call(
                    runtimeToken,
                    () -> listener.onTouchEvent(event, callback),
                    false);
        }

        @Override
        public void requestLayout(Runnable superCall) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, () -> listener.requestLayout(superCall));
            }
        }

        @Override
        public void setTranslationX(
                float translationX, Utilities.Callback<Float> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.setTranslationX(
                                translationX, callback));
            }
        }

        @Override
        public void setTranslationY(
                float translationY, Utilities.Callback<Float> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.setTranslationY(
                                translationY, callback));
            }
        }

        @Override
        public void setVisibility(
                int visibility, Utilities.Callback<Integer> callback) {
            UniversalFrameLayoutListener listener = this.listener;
            if (listener != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> listener.setVisibility(visibility, callback));
            }
        }
    }
}
