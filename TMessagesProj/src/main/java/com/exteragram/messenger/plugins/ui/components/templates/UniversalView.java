package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.Utilities;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.components.templates.PluginRuntimeDelegate;

public class UniversalView extends app.nimarkogram.messenger.plugins.ui.components.templates.UniversalView {

    private UniversalViewDelegate bridgeDelegate;

    public UniversalView(Context context) {
        super(context);
    }

    public UniversalView(Context context, UniversalViewDelegate delegate) {
        super(context);
        setDelegate(delegate);
    }

    public interface UniversalViewDelegate {
        default void onAttachedToWindow() {}
        default void onDetachedFromWindow() {}

        default void onDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) {
            originalMethod.run(canvas);
        }
        default void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> originalMethod) {
            originalMethod.run(info);
        }
        default void onMeasure(int widthMeasureSpec, int heightMeasureSpec, Utilities.Callback2<Integer, Integer> originalMethod) {
            originalMethod.run(widthMeasureSpec, heightMeasureSpec);
        }
        default boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) {
            return originalMethod.run(event);
        }
    }

    public UniversalViewDelegate getDelegateBridge() {
        PluginRuntimeDelegate.requireMainThread();
        return this.bridgeDelegate;
    }

    public void setDelegate(UniversalViewDelegate delegate) {
        if (delegate == null) {
            super.setDelegate(null);
            this.bridgeDelegate = null;
            return;
        }
        PluginsController.PluginRuntimeToken runtimeToken =
                PluginRuntimeDelegate.capture(delegate);
        GuardedDelegate guardedDelegate =
                new GuardedDelegate(delegate, runtimeToken);
        super.setDelegate(guardedDelegate);
        if (super.getDelegate() == guardedDelegate) {
            this.bridgeDelegate = guardedDelegate;
        } else {
            guardedDelegate.clear();
        }
    }

    @Override
    protected void onPluginDelegateCleared() {
        UniversalViewDelegate delegate = this.bridgeDelegate;
        this.bridgeDelegate = null;
        if (delegate instanceof GuardedDelegate) {
            ((GuardedDelegate) delegate).clear();
        }
    }

    private static final class GuardedDelegate implements
            UniversalViewDelegate,
            app.nimarkogram.messenger.plugins.ui.components.templates
                    .UniversalView.UniversalViewDelegate {
        private UniversalViewDelegate delegate;
        private final PluginsController.PluginRuntimeToken runtimeToken;

        GuardedDelegate(
                UniversalViewDelegate delegate,
                PluginsController.PluginRuntimeToken runtimeToken) {
            this.delegate = delegate;
            this.runtimeToken = runtimeToken;
        }

        void clear() {
            this.delegate = null;
        }

        @Override
        public void onAttachedToWindow() {
            UniversalViewDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, delegate::onAttachedToWindow);
            }
        }

        @Override
        public void onDetachedFromWindow() {
            UniversalViewDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, delegate::onDetachedFromWindow);
            }
        }

        @Override
        public void onDraw(
                Canvas canvas, Utilities.Callback<Canvas> callback) {
            UniversalViewDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken, () -> delegate.onDraw(canvas, callback));
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(
                AccessibilityNodeInfo info,
                Utilities.Callback<AccessibilityNodeInfo> callback) {
            UniversalViewDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> delegate.onInitializeAccessibilityNodeInfo(
                                info, callback));
            }
        }

        @Override
        public void onMeasure(
                int widthMeasureSpec,
                int heightMeasureSpec,
                Utilities.Callback2<Integer, Integer> callback) {
            UniversalViewDelegate delegate = this.delegate;
            if (delegate != null) {
                PluginRuntimeDelegate.run(
                        runtimeToken,
                        () -> delegate.onMeasure(
                                widthMeasureSpec, heightMeasureSpec, callback));
            }
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event,
                Utilities.CallbackReturn<MotionEvent, Boolean> callback) {
            UniversalViewDelegate delegate = this.delegate;
            return delegate != null && PluginRuntimeDelegate.call(
                    runtimeToken,
                    () -> delegate.onTouchEvent(event, callback),
                    false);
        }
    }
}
