package app.nimarkogram.messenger.plugins.ui.components.templates;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;

import org.telegram.messenger.Utilities;

import java.util.WeakHashMap;

public class UniversalFrameLayout extends FrameLayout
        implements PluginUiRegistry.RuntimeOwnedUi {
    private static final Object FRAME_CALLBACK_LAYOUT = new Object();
    private static final Object FRAME_CALLBACK_MEASURE = new Object();
    private static final Object FRAME_CALLBACK_DRAW = new Object();
    private static final Object FRAME_CALLBACK_DISPATCH_DRAW = new Object();
    private static final Object FRAME_CALLBACK_INVALIDATE = new Object();
    private static final Object FRAME_CALLBACK_REQUEST_LAYOUT = new Object();
    private static final Object FRAME_CALLBACK_TRANSLATION_X = new Object();
    private static final Object FRAME_CALLBACK_TRANSLATION_Y = new Object();
    private static final Object FRAME_CALLBACK_VISIBILITY = new Object();
    private UniversalFrameLayoutListener universalFrameLayoutListener;
    private PluginsController.PluginRuntimeToken listenerRuntimeToken;
    private PluginRuntimeDelegate.FrameCallbackQueue frameCallbackQueue;
    private DrawState drawState;
    private DrawState dispatchDrawState;
    private MeasureState measureState;
    private LayoutState layoutState;
    private final WeakHashMap<View, ChildDrawState> childDrawStates =
            new WeakHashMap<>();
    private long drawRevision = 1L;
    private long drawRequestId;
    private long dispatchDrawRequestId;
    private long measureRequestId;
    private long layoutRequestId;

    public interface UniversalFrameLayoutListener {
        void dispatchDraw(Canvas canvas, Utilities.Callback<Canvas> callback);
        boolean drawChild(Canvas canvas, View view, long drawingTime, Utilities.Callback3Return<Canvas, View, Long, Boolean> callback);
        void invalidate(int l, int t, int r, int b, Runnable superCall);
        void invalidate(Runnable superCall);
        void onAttachedToWindow(Runnable superCall);
        void onDetachedFromWindow(Runnable superCall);
        void onDraw(Canvas canvas, Utilities.Callback<Canvas> callback);
        void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> callback);
        boolean onInterceptTouchEvent(MotionEvent ev, Utilities.CallbackReturn<MotionEvent, Boolean> callback);
        void onLayout(boolean changed, int left, int top, int right, int bottom, Utilities.Callback5<Boolean, Integer, Integer, Integer, Integer> callback);
        void onMeasure(int widthMeasureSpec, int heightMeasureSpec, Utilities.Callback2<Integer, Integer> callback);
        boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> callback);
        void requestLayout(Runnable superCall);
        void setTranslationX(float translationX, Utilities.Callback<Float> callback);
        void setTranslationY(float translationY, Utilities.Callback<Float> callback);
        void setVisibility(int visibility, Utilities.Callback<Integer> callback);
    }

    public UniversalFrameLayout(Context context) {
        super(context);
        PluginRuntimeDelegate.requireMainThread();
    }

    public UniversalFrameLayout(Context context, UniversalFrameLayoutListener listener) {
        super(context);
        setUniversalFrameLayoutListener(listener);
    }

    public void setUniversalFrameLayoutListener(UniversalFrameLayoutListener listener) {
        PluginsController.PluginRuntimeToken runtimeToken =
                PluginRuntimeDelegate.capture(listener);
        clearUniversalFrameLayoutListener(null);
        if (listener == null) {
            return;
        }
        this.universalFrameLayoutListener = listener;
        this.listenerRuntimeToken = runtimeToken;
        this.frameCallbackQueue =
                PluginRuntimeDelegate.newFrameCallbackQueue(runtimeToken);
        if (!PluginUiRegistry.registerRuntimeOwnedUi(runtimeToken, this)) {
            clearUniversalFrameLayoutListener(runtimeToken);
        }
    }

    public UniversalFrameLayoutListener getUniversalFrameLayoutListener() {
        PluginRuntimeDelegate.requireMainThread();
        return this.universalFrameLayoutListener;
    }

    private void clearUniversalFrameLayoutListener(
            PluginsController.PluginRuntimeToken runtimeToken) {
        PluginsController.PluginRuntimeToken ownedToken =
                this.listenerRuntimeToken;
        if (runtimeToken != null && !runtimeToken.equals(ownedToken)) {
            return;
        }
        boolean hadListener =
                this.universalFrameLayoutListener != null || ownedToken != null;
        PluginRuntimeDelegate.FrameCallbackQueue ownedQueue =
                this.frameCallbackQueue;
        this.universalFrameLayoutListener = null;
        this.listenerRuntimeToken = null;
        this.frameCallbackQueue = null;
        this.drawState = null;
        this.dispatchDrawState = null;
        this.measureState = null;
        this.layoutState = null;
        this.childDrawStates.clear();
        this.drawRevision++;
        this.drawRequestId++;
        this.dispatchDrawRequestId++;
        this.measureRequestId++;
        this.layoutRequestId++;
        if (ownedQueue != null && ownedToken != null) {
            ownedQueue.clear(ownedToken);
        }
        if (ownedToken != null) {
            PluginUiRegistry.unregisterRuntimeOwnedUi(ownedToken, this);
        }
        if (hadListener) {
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
        clearUniversalFrameLayoutListener(runtimeToken);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        PluginRuntimeDelegate.requireMainThread();
        if (changed) {
            drawRevision++;
        }
        LayoutState state = this.layoutState;
        if (state != null && state.matches(
                changed, left, top, right, bottom)
                && state.hasHostLayout) {
            super.onLayout(
                    state.delegatedChanged,
                    state.delegatedLeft,
                    state.delegatedTop,
                    state.delegatedRight,
                    state.delegatedBottom);
        } else {
            super.onLayout(changed, left, top, right, bottom);
        }
        scheduleLayoutCallback(changed, left, top, right, bottom);
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
    public void setTranslationX(float translationX) {
        PluginRuntimeDelegate.requireMainThread();
        super.setTranslationX(translationX);
        enqueueFrameCallback(
                FRAME_CALLBACK_TRANSLATION_X,
                (listener, superCallScope) -> listener.setTranslationX(
                        translationX,
                        delegatedTranslation -> superCallScope.run(
                                () -> super.setTranslationX(
                                        delegatedTranslation))));
    }

    @Override
    public void setTranslationY(float translationY) {
        PluginRuntimeDelegate.requireMainThread();
        super.setTranslationY(translationY);
        enqueueFrameCallback(
                FRAME_CALLBACK_TRANSLATION_Y,
                (listener, superCallScope) -> listener.setTranslationY(
                        translationY,
                        delegatedTranslation -> superCallScope.run(
                                () -> super.setTranslationY(
                                        delegatedTranslation))));
    }

    @Override
    protected void onAttachedToWindow() {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFrameLayoutListener listener = this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken = this.listenerRuntimeToken;
        if (listener == null || !PluginRuntimeDelegate.runScoped(
                runtimeToken,
                superCallScope -> listener.onAttachedToWindow(
                        () -> superCallScope.run(super::onAttachedToWindow)))) {
            super.onAttachedToWindow();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFrameLayoutListener listener = this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken = this.listenerRuntimeToken;
        if (listener == null || !PluginRuntimeDelegate.runScoped(
                runtimeToken,
                superCallScope -> listener.onDetachedFromWindow(
                        () -> superCallScope.run(
                                super::onDetachedFromWindow)))) {
            super.onDetachedFromWindow();
        }
        
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        PluginRuntimeDelegate.requireMainThread();
        DrawState state = this.dispatchDrawState;
        if (state == null || state.drawHost) {
            super.dispatchDraw(canvas);
        }
        if (state != null) {
            state.picture.draw(canvas);
        }
        scheduleDispatchDrawCallback();
    }

    @Override
    public void requestLayout() {
        PluginRuntimeDelegate.requireMainThread();
        super.requestLayout();
        enqueueFrameCallback(
                FRAME_CALLBACK_REQUEST_LAYOUT,
                (listener, superCallScope) -> listener.requestLayout(
                        () -> superCallScope.run(super::requestLayout)));
    }

    @Override
    public void invalidate() {
        PluginRuntimeDelegate.requireMainThread();
        drawRevision++;
        super.invalidate();
        enqueueFrameCallback(
                FRAME_CALLBACK_INVALIDATE,
                (listener, superCallScope) -> listener.invalidate(
                        () -> superCallScope.run(super::invalidate)));
    }

    @Override
    public void invalidate(int l, int t, int r, int b) {
        PluginRuntimeDelegate.requireMainThread();
        drawRevision++;
        super.invalidate(l, t, r, b);
        enqueueFrameCallback(
                FRAME_CALLBACK_INVALIDATE,
                (listener, superCallScope) -> listener.invalidate(
                        l, t, r, b,
                        () -> superCallScope.run(
                                () -> super.invalidate(l, t, r, b))));
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
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFrameLayoutListener listener = this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken = this.listenerRuntimeToken;
        if (listener == null || !PluginRuntimeDelegate.runScoped(
                runtimeToken,
                superCallScope -> listener.onInitializeAccessibilityNodeInfo(
                        info,
                        delegatedInfo -> superCallScope.run(
                                () -> super.onInitializeAccessibilityNodeInfo(
                                        delegatedInfo))))) {
            super.onInitializeAccessibilityNodeInfo(info);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFrameLayoutListener listener = this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken = this.listenerRuntimeToken;
        if (listener != null) {
            Boolean result = PluginRuntimeDelegate.callScoped(
                    runtimeToken,
                    superCallScope -> listener.onInterceptTouchEvent(
                            ev,
                            delegatedEvent -> superCallScope.call(
                                    () -> super.onInterceptTouchEvent(delegatedEvent),
                                    false)),
                    null);
            if (result != null) {
                return result;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        PluginRuntimeDelegate.requireMainThread();
        UniversalFrameLayoutListener listener = this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken = this.listenerRuntimeToken;
        if (listener != null) {
            Boolean result = PluginRuntimeDelegate.callScoped(
                    runtimeToken,
                    superCallScope -> listener.onTouchEvent(
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
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        PluginRuntimeDelegate.requireMainThread();
        ChildDrawState state = this.childDrawStates.get(child);
        boolean hostResult = true;
        if (state == null || state.drawHost) {
            hostResult = super.drawChild(canvas, child, drawingTime);
        }
        if (state != null) {
            state.picture.draw(canvas);
        }
        scheduleDrawChildCallback(child, drawingTime);
        return state != null ? state.result : hostResult;
    }

    private void scheduleLayoutCallback(
            boolean changed, int left, int top, int right, int bottom) {
        UniversalFrameLayoutListener callbackListener =
                this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken =
                this.listenerRuntimeToken;
        PluginRuntimeDelegate.FrameCallbackQueue queue =
                this.frameCallbackQueue;
        LayoutState state = this.layoutState;
        if (callbackListener == null || queue == null
                || (state != null && state.matches(
                        changed, left, top, right, bottom))) {
            return;
        }
        long requestId = ++this.layoutRequestId;
        queue.enqueue(FRAME_CALLBACK_LAYOUT, superCallScope -> {
            boolean[] delegatedChanged = new boolean[1];
            int[] delegatedBounds = new int[4];
            boolean[] hasHostLayout = new boolean[1];
            callbackListener.onLayout(
                    changed, left, top, right, bottom,
                    (c, l, t, r, b) -> superCallScope.run(() -> {
                        hasHostLayout[0] = true;
                        delegatedChanged[0] = c;
                        delegatedBounds[0] = l;
                        delegatedBounds[1] = t;
                        delegatedBounds[2] = r;
                        delegatedBounds[3] = b;
                    }));
            if (!isCurrentFrameCallback(
                    callbackListener, runtimeToken, queue)
                    || requestId != layoutRequestId) {
                return;
            }
            layoutState = new LayoutState(
                    changed, left, top, right, bottom,
                    hasHostLayout[0],
                    delegatedChanged[0],
                    delegatedBounds[0],
                    delegatedBounds[1],
                    delegatedBounds[2],
                    delegatedBounds[3]);
            if (hasHostLayout[0] && (delegatedChanged[0] != changed
                    || delegatedBounds[0] != left
                    || delegatedBounds[1] != top
                    || delegatedBounds[2] != right
                    || delegatedBounds[3] != bottom)) {
                super.requestLayout();
            }
        });
    }

    private void scheduleMeasureCallback(
            int widthMeasureSpec, int heightMeasureSpec) {
        UniversalFrameLayoutListener callbackListener =
                this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken =
                this.listenerRuntimeToken;
        PluginRuntimeDelegate.FrameCallbackQueue queue =
                this.frameCallbackQueue;
        MeasureState state = this.measureState;
        if (callbackListener == null || queue == null
                || (state != null && state.matches(
                        widthMeasureSpec, heightMeasureSpec))) {
            return;
        }
        long requestId = ++this.measureRequestId;
        queue.enqueue(FRAME_CALLBACK_MEASURE, superCallScope -> {
            int[] delegatedSpecs = new int[2];
            boolean[] hasHostMeasure = new boolean[1];
            callbackListener.onMeasure(
                    widthMeasureSpec,
                    heightMeasureSpec,
                    (delegatedWidth, delegatedHeight) ->
                            superCallScope.run(() -> {
                                delegatedSpecs[0] = delegatedWidth;
                                delegatedSpecs[1] = delegatedHeight;
                                hasHostMeasure[0] = true;
                            }));
            if (!isCurrentFrameCallback(
                    callbackListener, runtimeToken, queue)
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

    private void scheduleDrawCallback() {
        schedulePictureCallback(false);
    }

    private void scheduleDispatchDrawCallback() {
        schedulePictureCallback(true);
    }

    private void schedulePictureCallback(boolean dispatch) {
        UniversalFrameLayoutListener callbackListener =
                this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken =
                this.listenerRuntimeToken;
        PluginRuntimeDelegate.FrameCallbackQueue queue =
                this.frameCallbackQueue;
        int width = getWidth();
        int height = getHeight();
        long revision = this.drawRevision;
        DrawState state = dispatch ? this.dispatchDrawState : this.drawState;
        if (callbackListener == null || queue == null
                || width <= 0 || height <= 0
                || (state != null && state.matches(
                        width, height, revision))) {
            return;
        }
        long requestId = dispatch
                ? ++this.dispatchDrawRequestId : ++this.drawRequestId;
        Object key = dispatch
                ? FRAME_CALLBACK_DISPATCH_DRAW : FRAME_CALLBACK_DRAW;
        queue.enqueue(key, superCallScope -> {
            Picture picture = new Picture();
            Canvas recordingCanvas =
                    picture.beginRecording(width, height);
            boolean[] drawHost = new boolean[1];
            try {
                Utilities.Callback<Canvas> hostCall =
                        ignoredCanvas -> superCallScope.run(
                                () -> drawHost[0] = true);
                if (dispatch) {
                    callbackListener.dispatchDraw(
                            recordingCanvas, hostCall);
                } else {
                    callbackListener.onDraw(
                            recordingCanvas, hostCall);
                }
            } finally {
                picture.endRecording();
            }
            if (!isCurrentFrameCallback(
                    callbackListener, runtimeToken, queue)
                    || revision != drawRevision
                    || width != getWidth() || height != getHeight()
                    || requestId != (dispatch
                            ? dispatchDrawRequestId : drawRequestId)) {
                return;
            }
            DrawState published = new DrawState(
                    width, height, revision, drawHost[0], picture);
            if (dispatch) {
                dispatchDrawState = published;
            } else {
                drawState = published;
            }
            super.invalidate();
        });
    }

    private void scheduleDrawChildCallback(
            View child, long drawingTime) {
        UniversalFrameLayoutListener callbackListener =
                this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken =
                this.listenerRuntimeToken;
        PluginRuntimeDelegate.FrameCallbackQueue queue =
                this.frameCallbackQueue;
        int width = getWidth();
        int height = getHeight();
        long revision = this.drawRevision;
        ChildDrawState state = this.childDrawStates.get(child);
        if (callbackListener == null || queue == null || child == null
                || width <= 0 || height <= 0
                || (state != null && state.revision == revision)) {
            return;
        }
        queue.enqueue(child, superCallScope -> {
            Picture picture = new Picture();
            Canvas recordingCanvas =
                    picture.beginRecording(width, height);
            boolean[] drawHost = new boolean[1];
            boolean result;
            try {
                result = callbackListener.drawChild(
                        recordingCanvas,
                        child,
                        drawingTime,
                        (delegatedCanvas, delegatedChild, delegatedTime) ->
                                superCallScope.call(() -> {
                                    drawHost[0] = true;
                                    return true;
                                }, false));
            } finally {
                picture.endRecording();
            }
            if (!isCurrentFrameCallback(
                    callbackListener, runtimeToken, queue)
                    || revision != drawRevision
                    || child.getParent() != this) {
                return;
            }
            childDrawStates.remove(child);
            childDrawStates.put(
                    child,
                    new ChildDrawState(
                            revision, drawHost[0], result, picture));
            super.invalidate();
        });
    }

    private boolean enqueueFrameCallback(
            Object key, FrameListenerCallback callback) {
        UniversalFrameLayoutListener callbackListener =
                this.universalFrameLayoutListener;
        PluginsController.PluginRuntimeToken runtimeToken =
                this.listenerRuntimeToken;
        PluginRuntimeDelegate.FrameCallbackQueue queue =
                this.frameCallbackQueue;
        if (callbackListener == null || queue == null) {
            return false;
        }
        return queue.enqueue(key, superCallScope -> {
            if (!isCurrentFrameCallback(
                    callbackListener, runtimeToken, queue)) {
                return;
            }
            callback.run(callbackListener, superCallScope);
        });
    }

    private boolean isCurrentFrameCallback(
            UniversalFrameLayoutListener expectedListener,
            PluginsController.PluginRuntimeToken expectedRuntime,
            PluginRuntimeDelegate.FrameCallbackQueue expectedQueue) {
        return expectedListener != null
                && this.universalFrameLayoutListener == expectedListener
                && expectedRuntime != null
                && expectedRuntime.equals(this.listenerRuntimeToken)
                && this.frameCallbackQueue == expectedQueue
                && expectedQueue.isOpenFor(expectedRuntime);
    }

    private interface FrameListenerCallback {
        void run(
                UniversalFrameLayoutListener listener,
                PluginRuntimeDelegate.SuperCallScope superCallScope);
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

    private static final class ChildDrawState {
        final long revision;
        final boolean drawHost;
        final boolean result;
        final Picture picture;

        ChildDrawState(
                long revision,
                boolean drawHost,
                boolean result,
                Picture picture) {
            this.revision = revision;
            this.drawHost = drawHost;
            this.result = result;
            this.picture = picture;
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

    private static final class LayoutState {
        final boolean changed;
        final int left;
        final int top;
        final int right;
        final int bottom;
        final boolean hasHostLayout;
        final boolean delegatedChanged;
        final int delegatedLeft;
        final int delegatedTop;
        final int delegatedRight;
        final int delegatedBottom;

        LayoutState(
                boolean changed,
                int left,
                int top,
                int right,
                int bottom,
                boolean hasHostLayout,
                boolean delegatedChanged,
                int delegatedLeft,
                int delegatedTop,
                int delegatedRight,
                int delegatedBottom) {
            this.changed = changed;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.hasHostLayout = hasHostLayout;
            this.delegatedChanged = delegatedChanged;
            this.delegatedLeft = delegatedLeft;
            this.delegatedTop = delegatedTop;
            this.delegatedRight = delegatedRight;
            this.delegatedBottom = delegatedBottom;
        }

        boolean matches(
                boolean changed, int left, int top, int right, int bottom) {
            return this.changed == changed
                    && this.left == left
                    && this.top == top
                    && this.right == right
                    && this.bottom == bottom;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        PluginRuntimeDelegate.requireMainThread();
        super.setVisibility(visibility);
        enqueueFrameCallback(
                FRAME_CALLBACK_VISIBILITY,
                (listener, superCallScope) -> listener.setVisibility(
                        visibility,
                        delegatedVisibility -> superCallScope.run(
                                () -> super.setVisibility(
                                        delegatedVisibility))));
    }
}
