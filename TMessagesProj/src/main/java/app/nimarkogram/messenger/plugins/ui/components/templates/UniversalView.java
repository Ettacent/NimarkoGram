package app.nimarkogram.messenger.plugins.ui.components.templates;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;

import org.telegram.messenger.Utilities;

public class UniversalView extends View
        implements PluginUiRegistry.RuntimeOwnedUi {
    private static final Object FRAME_CALLBACK_DRAW = new Object();
    private static final Object FRAME_CALLBACK_MEASURE = new Object();

    private UniversalViewDelegate delegate;
    private PluginsController.PluginRuntimeToken delegateRuntimeToken;
    private PluginRuntimeDelegate.FrameCallbackQueue frameCallbackQueue;
    private DrawState drawState;
    private MeasureState measureState;
    private long drawRevision = 1L;
    private long drawRequestId;
    private long measureRequestId;

    public interface UniversalViewDelegate {
        default void onAttachedToWindow() {}
        default void onDetachedFromWindow() {}
        
        void onDraw(Canvas canvas, Utilities.Callback<Canvas> callback);
        void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> callback);
        void onMeasure(int widthMeasureSpec, int heightMeasureSpec, Utilities.Callback2<Integer, Integer> callback);
        boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> callback);
    }

    public UniversalView(Context context) {
        super(context);
        PluginRuntimeDelegate.requireMainThread();
    }

    public UniversalView(Context context, UniversalViewDelegate delegate) {
        super(context);
        setDelegate(delegate);
    }

    public UniversalViewDelegate getDelegate() {
        PluginRuntimeDelegate.requireMainThread();
        return this.delegate;
    }

    public void setDelegate(UniversalViewDelegate delegate) {
        PluginsController.PluginRuntimeToken runtimeToken =
                PluginRuntimeDelegate.capture(delegate);
        clearDelegate(null);
        if (delegate == null) {
            return;
        }
        this.delegate = delegate;
        this.delegateRuntimeToken = runtimeToken;
        this.frameCallbackQueue =
                PluginRuntimeDelegate.newFrameCallbackQueue(runtimeToken);
        if (!PluginUiRegistry.registerRuntimeOwnedUi(runtimeToken, this)) {
            clearDelegate(runtimeToken);
        }
    }

    private void clearDelegate(
            PluginsController.PluginRuntimeToken runtimeToken) {
        PluginsController.PluginRuntimeToken ownedToken =
                this.delegateRuntimeToken;
        if (runtimeToken != null && !runtimeToken.equals(ownedToken)) {
            return;
        }
        boolean hadDelegate = this.delegate != null || ownedToken != null;
        PluginRuntimeDelegate.FrameCallbackQueue ownedQueue =
                this.frameCallbackQueue;
        this.delegate = null;
        this.delegateRuntimeToken = null;
        this.frameCallbackQueue = null;
        this.drawState = null;
        this.measureState = null;
        this.drawRevision++;
        this.drawRequestId++;
        this.measureRequestId++;
        if (ownedQueue != null && ownedToken != null) {
            ownedQueue.clear(ownedToken);
        }
        if (ownedToken != null) {
            PluginUiRegistry.unregisterRuntimeOwnedUi(ownedToken, this);
        }
        if (hadDelegate) {
            onPluginDelegateCleared();
            super.invalidate();
            super.requestLayout();
        }
    }

    protected void onPluginDelegateCleared() {
    }

    @Override
    public void clearPluginUiReferences(
            PluginsController.PluginRuntimeToken runtimeToken) {
        PluginRuntimeDelegate.requireMainThread();
        clearDelegate(runtimeToken);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        PluginRuntimeDelegate.requireMainThread();
        DrawState state = this.drawState;
        if (state == null || state.drawHost) {
            super.onDraw(canvas);
        }
        if (state != null) {
            state.picture.draw(canvas);
        }
        scheduleDrawCallback();
    }

    @Override
    public void invalidate() {
        PluginRuntimeDelegate.requireMainThread();
        drawRevision++;
        super.invalidate();
    }

    @Override
    public void invalidate(int left, int top, int right, int bottom) {
        PluginRuntimeDelegate.requireMainThread();
        drawRevision++;
        super.invalidate(left, top, right, bottom);
    }

    @Override
    protected void onAttachedToWindow() {
        PluginRuntimeDelegate.requireMainThread();
        super.onAttachedToWindow();
        UniversalViewDelegate delegate = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken = this.delegateRuntimeToken;
        if (delegate != null) {
            PluginRuntimeDelegate.run(runtimeToken, delegate::onAttachedToWindow);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        PluginRuntimeDelegate.requireMainThread();
        super.onDetachedFromWindow();
        UniversalViewDelegate delegate = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken = this.delegateRuntimeToken;
        if (delegate != null) {
            PluginRuntimeDelegate.run(runtimeToken, delegate::onDetachedFromWindow);
        }
        
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalViewDelegate delegate = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken = this.delegateRuntimeToken;
        if (delegate != null) {
            Boolean result = PluginRuntimeDelegate.callScoped(
                    runtimeToken,
                    superCallScope -> delegate.onTouchEvent(
                            event,
                            delegatedEvent -> superCallScope.call(
                                    () -> super.onTouchEvent(delegatedEvent),
                                    false)),
                    null);
            if (result != null) {
                return result;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        PluginRuntimeDelegate.requireMainThread();
        MeasureState state = this.measureState;
        if (state != null && state.matches(
                widthMeasureSpec, heightMeasureSpec)
                && state.hasHostMeasure) {
            super.onMeasure(
                    state.delegatedWidthMeasureSpec,
                    state.delegatedHeightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
        scheduleMeasureCallback(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalViewDelegate delegate = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken = this.delegateRuntimeToken;
        if (delegate == null || !PluginRuntimeDelegate.runScoped(
                runtimeToken,
                superCallScope -> delegate.onInitializeAccessibilityNodeInfo(
                        info,
                        delegatedInfo -> superCallScope.run(
                                () -> super.onInitializeAccessibilityNodeInfo(
                                        delegatedInfo))))) {
            super.onInitializeAccessibilityNodeInfo(info);
        }
    }

    private void scheduleDrawCallback() {
        UniversalViewDelegate callbackDelegate = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken =
                this.delegateRuntimeToken;
        PluginRuntimeDelegate.FrameCallbackQueue queue =
                this.frameCallbackQueue;
        int width = getWidth();
        int height = getHeight();
        long revision = this.drawRevision;
        DrawState state = this.drawState;
        if (callbackDelegate == null || queue == null
                || width <= 0 || height <= 0
                || (state != null && state.matches(
                        width, height, revision))) {
            return;
        }
        long requestId = ++this.drawRequestId;
        queue.enqueue(FRAME_CALLBACK_DRAW, superCallScope -> {
            Picture picture = new Picture();
            Canvas recordingCanvas =
                    picture.beginRecording(width, height);
            boolean[] drawHost = new boolean[1];
            try {
                callbackDelegate.onDraw(
                        recordingCanvas,
                        ignoredCanvas -> superCallScope.run(
                                () -> drawHost[0] = true));
            } finally {
                picture.endRecording();
            }
            if (!isCurrentFrameCallback(
                    callbackDelegate, runtimeToken, queue)
                    || requestId != drawRequestId
                    || revision != drawRevision
                    || width != getWidth() || height != getHeight()) {
                return;
            }
            drawState = new DrawState(
                    width, height, revision, drawHost[0], picture);
            
            super.invalidate();
        });
    }

    private void scheduleMeasureCallback(
            int widthMeasureSpec, int heightMeasureSpec) {
        UniversalViewDelegate callbackDelegate = this.delegate;
        PluginsController.PluginRuntimeToken runtimeToken =
                this.delegateRuntimeToken;
        PluginRuntimeDelegate.FrameCallbackQueue queue =
                this.frameCallbackQueue;
        MeasureState state = this.measureState;
        if (callbackDelegate == null || queue == null
                || (state != null && state.matches(
                        widthMeasureSpec, heightMeasureSpec))) {
            return;
        }
        long requestId = ++this.measureRequestId;
        queue.enqueue(FRAME_CALLBACK_MEASURE, superCallScope -> {
            int[] delegatedSpecs = new int[2];
            boolean[] hasHostMeasure = new boolean[1];
            callbackDelegate.onMeasure(
                    widthMeasureSpec,
                    heightMeasureSpec,
                    (delegatedWidth, delegatedHeight) ->
                            superCallScope.run(() -> {
                                delegatedSpecs[0] = delegatedWidth;
                                delegatedSpecs[1] = delegatedHeight;
                                hasHostMeasure[0] = true;
                            }));
            if (!isCurrentFrameCallback(
                    callbackDelegate, runtimeToken, queue)
                    || requestId != measureRequestId) {
                return;
            }
            measureState = new MeasureState(
                    widthMeasureSpec,
                    heightMeasureSpec,
                    hasHostMeasure[0],
                    delegatedSpecs[0],
                    delegatedSpecs[1]);
            if (hasHostMeasure[0]
                    && (delegatedSpecs[0] != widthMeasureSpec
                    || delegatedSpecs[1] != heightMeasureSpec)) {
                
                super.requestLayout();
            }
        });
    }

    private boolean isCurrentFrameCallback(
            UniversalViewDelegate expectedDelegate,
            PluginsController.PluginRuntimeToken expectedRuntime,
            PluginRuntimeDelegate.FrameCallbackQueue expectedQueue) {
        return expectedDelegate != null
                && this.delegate == expectedDelegate
                && expectedRuntime != null
                && expectedRuntime.equals(this.delegateRuntimeToken)
                && this.frameCallbackQueue == expectedQueue
                && expectedQueue.isOpenFor(expectedRuntime);
    }

    private static final class DrawState {
        final int width;
        final int height;
        final long revision;
        final boolean drawHost;
        final Picture picture;

        DrawState(
                int width,
                int height,
                long revision,
                boolean drawHost,
                Picture picture) {
            this.width = width;
            this.height = height;
            this.revision = revision;
            this.drawHost = drawHost;
            this.picture = picture;
        }

        boolean matches(int width, int height, long revision) {
            return this.width == width
                    && this.height == height
                    && this.revision == revision;
        }
    }

    private static final class MeasureState {
        final int widthMeasureSpec;
        final int heightMeasureSpec;
        final boolean hasHostMeasure;
        final int delegatedWidthMeasureSpec;
        final int delegatedHeightMeasureSpec;

        MeasureState(
                int widthMeasureSpec,
                int heightMeasureSpec,
                boolean hasHostMeasure,
                int delegatedWidthMeasureSpec,
                int delegatedHeightMeasureSpec) {
            this.widthMeasureSpec = widthMeasureSpec;
            this.heightMeasureSpec = heightMeasureSpec;
            this.hasHostMeasure = hasHostMeasure;
            this.delegatedWidthMeasureSpec = delegatedWidthMeasureSpec;
            this.delegatedHeightMeasureSpec = delegatedHeightMeasureSpec;
        }

        boolean matches(int widthMeasureSpec, int heightMeasureSpec) {
            return this.widthMeasureSpec == widthMeasureSpec
                    && this.heightMeasureSpec == heightMeasureSpec;
        }
    }
}
