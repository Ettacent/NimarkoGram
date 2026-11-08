/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.utils.twidget.WidgetGestureArbiter;
import org.telegram.utils.twidget.WidgetSandboxMessage;
import org.telegram.utils.twidget.WidgetSandboxSession;

public final class WidgetSandboxPlayer {
    public interface Host {
        void onWidgetResized();

        void invalidateWidget();

        void disallowParentIntercept(boolean disallow);

        default void onBubbleHold() {}

        default void setCursor(String cursor) {}
    }

    private static final long CLAIM_WAIT_MS = 150;
    private static final long STATIC_LAYER_MS = 333;
    private static final long DUPLICATE_DOWN_MS = 100;

    private final Host host;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix layerMatrix = new Matrix();
    private final float[] layerValues = new float[9];
    private final float[] layerKey = new float[7];
    private final Path layerClip = new Path();
    private final RectF layerRect = new RectF();
    private final float[] layerRadii = new float[8];
    private final Runnable claimTimeout = () -> onClaim(false, false, false);
    private final Runnable holdTick = this::onHoldTick;
    private final WidgetGestureArbiter arbiter = new WidgetGestureArbiter();
    private final Runnable staticLayer = this::onStaticFrame;
    private WidgetSandboxSession.Frame shownFrame;
    private WidgetSandboxMessage message;
    private boolean visible, previewing;
    private long lastDownAt, lastDownTime;
    private float lastDownX, lastDownY;
    private boolean pressOpen, claimed, claimX, claimHold, widgetCancelled, parentsHeld, parentsFree;
    private boolean holdTold;
    private boolean pressMouse, hovering;
    private boolean downPending;
    private int pressButton, pressModifiers;
    private float hoverX, hoverY;
    private int verdict = WidgetGestureArbiter.NONE;
    private long pressAt;
    private float pressX, pressY, lastX, lastY;
    private int drawnWidth;
    private long loadingSince = -1;
    private boolean placeholderShown;
    private static final long LOADING_HINT_DELAY_MS = 150;

    public WidgetSandboxPlayer(Host host, float density) {
        this.host = host;
        this.density = density;
        placeholderPaint.setTextSize(14 * density);
        placeholderPaint.setColor(0xff808080);
    }

    public void bind(WidgetSandboxMessage message) {
        if (this.message == message) return;
        unbind();
        this.message = message;
        loadingSince = -1;
        placeholderShown = false;
        if (message == null) return;
        message.bind(this);
        message.setVisible(visible);
    }

    public void preview(WidgetSandboxMessage message) {
        unbind();
        this.message = message;
        previewing = message != null;
    }

    public void unbind() {
        if (message == null) return;
        if (previewing) {
            previewing = false;
            message = null;
            return;
        }
        endWidgetPress();
        endHover();
        message.setVisible(false);
        message.unbind(this);
        message = null;
        main.removeCallbacks(staticLayer);
        shownFrame = null;
        endPress();
        arbiter.release();
    }

    long msSinceTouch() {
        return lastDownAt == 0 ? -1 : SystemClock.uptimeMillis() - lastDownAt;
    }

    public WidgetSandboxMessage message() {
        return message;
    }

    public void setVisible(boolean visible) {
        if (this.visible == visible) return;
        this.visible = visible;
        if (message != null && !previewing) message.setVisible(visible);
    }

    public boolean isVisible() {
        return visible;
    }

    public int heightPx() {
        return message != null && message.heightPx() > 0
                ? message.heightPx()
                : Math.round(WidgetSandboxMessage.PLACEHOLDER_DP * density);
    }

    public boolean draw(Canvas canvas, int width) {
        drawnWidth = width;
        WidgetSandboxSession.Frame frame = message == null ? null : message.frame();
        if (frame != null && frame.width > 0 && frame.node.canDraw(canvas) && message.isDisplayReady(frame)) {
            if (!frame.recorded()) message.ensureDisplayList();
            if (frame != shownFrame) onNewFrame(frame);
            boolean animated = SharedConfig.animationsEnabled()
                    && (android.os.Build.VERSION.SDK_INT >= 26 ? android.animation.ValueAnimator.areAnimatorsEnabled()
                        : org.telegram.messenger.AndroidUtilities.getAnimatorDurationScale() > 0);
            float alpha = message.contentAlpha(SystemClock.uptimeMillis(), visible && !previewing, animated);
            if (placeholderShown && !previewing && alpha < 1) drawPlaceholder(canvas, null, 1 - alpha);
            int revealSave = alpha < 1 ? canvas.saveLayerAlpha(0, 0, width,
                    Math.max(heightPx(), (float) frame.height * width / frame.width), Math.round(alpha * 255)) : -1;
            boolean scaled = Math.abs(frame.width - width) > 1;
            if (scaled) {
                canvas.save();
                float scale = width / (float) frame.width;
                canvas.scale(scale, scale);
            }
            frame.node.draw(canvas, 1);
            boolean moving = frame.layers.length > 0 && drawLayers(canvas, frame) && visible;
            if (scaled) canvas.restore();
            if (revealSave >= 0) canvas.restoreToCount(revealSave);
            loadingSince = -1;
            if (alpha >= 1) placeholderShown = false;
            return moving || visible && alpha < 1;
        }
        if (previewing) return false;
        if (message != null) message.ensureDisplayList();
        if (message != null && visible && canvas.isHardwareAccelerated()) message.contentUnavailable();
        String failure = message == null ? null : message.failure();
        long now = SystemClock.uptimeMillis();
        if (loadingSince < 0) loadingSince = now;
        if (failure == null && now - loadingSince < LOADING_HINT_DELAY_MS) return visible;
        placeholderShown = true;
        drawPlaceholder(canvas, failure, 1);
        return false;
    }

    private void drawPlaceholder(Canvas canvas, String failure, float alpha) {
        placeholderPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawText(
                LocaleController.getString(failure == null ? R.string.WidgetLoading : R.string.WidgetLoadFailed),
                12 * density,
                28 * density,
                placeholderPaint);
        placeholderPaint.setAlpha(255);
    }

    private boolean drawLayers(Canvas canvas, WidgetSandboxSession.Frame frame) {
        double previewTime = message == null ? Double.NaN : message.previewTime(frame);
        boolean frozen = !Double.isNaN(previewTime);
        double now = frozen ? previewTime : System.nanoTime() / 1e9 + frame.clockOffset;
        boolean moving = false;
        for (WidgetSandboxSession.Layer layer : frame.layers) {
            int keys = layer.keys.length / 8;
            if (keys == 0 || !layer.node.recorded()) continue;
            double elapsed = Math.max(0, now - layer.start);
            double phase;
            if (layer.duration <= 0) phase = 1;
            else if (layer.repeat) {
                phase = (elapsed % layer.duration) / layer.duration;
                moving = true;
            } else {
                phase = Math.min(1, elapsed / layer.duration);
                moving |= phase < 1;
            }
            sample(layer.keys, keys, (float) phase);
            float alpha = Math.max(0, Math.min(1, layerKey[6]));
            if (alpha <= 0) continue;
            canvas.save();
            if (layer.clip != null) {
                layerRect.set(
                        layer.clip[0] * density,
                        layer.clip[1] * density,
                        (layer.clip[0] + layer.clip[2]) * density,
                        (layer.clip[1] + layer.clip[3]) * density);
                if (layer.clipRadii == null) canvas.clipRect(layerRect);
                else {
                    for (int i = 0; i < 8; i++) layerRadii[i] = layer.clipRadii[i] * density;
                    layerClip.rewind();
                    layerClip.addRoundRect(layerRect, layerRadii, Path.Direction.CW);
                    canvas.clipPath(layerClip);
                }
            }
            layerValues[0] = layerKey[0];
            layerValues[1] = layerKey[2];
            layerValues[2] = layerKey[4] * density;
            layerValues[3] = layerKey[1];
            layerValues[4] = layerKey[3];
            layerValues[5] = layerKey[5] * density;
            layerValues[6] = 0;
            layerValues[7] = 0;
            layerValues[8] = 1;
            layerMatrix.setValues(layerValues);
            canvas.concat(layerMatrix);
            int alphaSave = alpha < 1 ? canvas.saveLayerAlpha(null, Math.round(alpha * 255)) : -1;
            layer.node.draw(canvas, 1);
            if (alphaSave != -1) canvas.restoreToCount(alphaSave);
            canvas.restore();
        }
        return moving && !frozen;
    }

    private void sample(float[] keys, int count, float phase) {
        int from = 0;
        while (from + 1 < count && keys[(from + 1) * 8] <= phase) from++;
        int to = Math.min(from + 1, count - 1);
        float t0 = keys[from * 8], t1 = keys[to * 8];
        float mix = to == from || t1 <= t0 ? 0 : (phase - t0) / (t1 - t0);
        mix = Math.max(0, Math.min(1, mix));
        for (int i = 0; i < 7; i++) {
            float a = keys[from * 8 + 1 + i], b = keys[to * 8 + 1 + i];
            layerKey[i] = a + (b - a) * mix;
        }
    }

    private void onNewFrame(WidgetSandboxSession.Frame frame) {
        boolean first = shownFrame == null;
        shownFrame = frame;
        main.removeCallbacks(staticLayer);
        if (!SharedConfig.widgetStaticLayers) return;
        if (first) frame.node.setLayered(true);
        else main.postDelayed(staticLayer, STATIC_LAYER_MS);
    }

    private void onStaticFrame() {
        if (shownFrame == null || message == null || message.frame() != shownFrame) return;
        shownFrame.node.setLayered(true);
        host.invalidateWidget();
    }

    public void onMessageFrame(boolean resized) {
        if (resized) host.onWidgetResized();
        host.invalidateWidget();
    }

    public void onMessageFailure() {
        host.invalidateWidget();
    }

    public void onGesture(boolean x, boolean y, boolean hold) {
        if (pressOpen && !claimed) onClaim(x, y, hold);
    }

    private void onClaim(boolean x, boolean y, boolean hold) {
        if (!pressOpen || claimed) return;
        main.removeCallbacks(claimTimeout);
        claimed = true;
        int v = arbiter.down(pressAt, leading(pressX), pressY, x, y, hold, pressMouse, pressButton);
        boolean edge = WidgetGestureArbiter.edge(v);
        claimX = x && !edge;
        claimHold = hold && !edge;
        apply(v);
        if (lastX != pressX || lastY != pressY) apply(arbiter.move(SystemClock.uptimeMillis(), leading(lastX), lastY));
        if (!claimX && !claimHold && !pressMouse) {
            freeParents();
        } else {
            holdParents();
            if ((claimX || pressMouse) && !parentsFree) host.disallowParentIntercept(true);
        }
        scheduleHold();
    }

    private void apply(int v) {
        if (v == WidgetGestureArbiter.NONE) return;
        verdict = v;
        if (WidgetGestureArbiter.cancel(v) && !widgetCancelled) {
            widgetCancelled = true;
            message.touch(3, lastX, lastY, 0, true, pressMouse, pressModifiers, pressButton);
        }
        int owner = WidgetGestureArbiter.owner(v);
        if (owner == WidgetGestureArbiter.OWNER_BUBBLE) {
            freeParents();
            if (WidgetGestureArbiter.action(v) == WidgetGestureArbiter.ACTION_MENU && !holdTold) {
                holdTold = true;
                host.onBubbleHold();
            }
        } else if (owner == WidgetGestureArbiter.OWNER_WIDGET && ownsDragOrHold() && !parentsFree) {
            if (parentsHeld) host.disallowParentIntercept(true);
            else holdParents();
        }
    }

    private boolean ownsDragOrHold() {
        int gesture = WidgetGestureArbiter.gesture(verdict);
        return WidgetGestureArbiter.owner(verdict) == WidgetGestureArbiter.OWNER_WIDGET
                && (gesture == WidgetGestureArbiter.DRAG_X || gesture == WidgetGestureArbiter.DRAG_Y || gesture == WidgetGestureArbiter.HOLD);
    }

    private void scheduleHold() {
        main.removeCallbacks(holdTick);
        double deadline = arbiter.holdDeadline();
        if (pressOpen && deadline >= 0) main.postDelayed(holdTick, Math.max(0, (long) Math.ceil(deadline) - SystemClock.uptimeMillis()));
    }

    private void onHoldTick() {
        if (!pressOpen || !claimed) return;
        apply(arbiter.tick(SystemClock.uptimeMillis()));
        scheduleHold();
    }

    private void holdParents() {
        if (parentsHeld || parentsFree) return;
        parentsHeld = true;
        host.disallowParentIntercept(true);
    }

    private void freeParents() {
        if (parentsFree) return;
        parentsFree = true;
        if (parentsHeld) host.disallowParentIntercept(false);
    }

    private void endWidgetPress() {
        if (downPending) {
            downPending = false;
            widgetCancelled = true;
        }
        if (pressOpen && !widgetCancelled && message != null) {
            widgetCancelled = true;
            message.touch(3, lastX, lastY, 0, true, pressMouse, pressModifiers, pressButton);
        }
    }

    private void endPress() {
        main.removeCallbacks(claimTimeout);
        main.removeCallbacks(holdTick);
        pressOpen = false;
        claimed = false;
    }

    private void flushDown() {
        if (!downPending) return;
        downPending = false;
        if (message != null) message.touch(0, pressX, pressY, 0, true, pressMouse, pressModifiers, pressButton);
    }

    private float leading(float x) {
        return LocaleController.isRTL && drawnWidth > 0 ? drawnWidth / density - x : x;
    }

    public boolean blocksMessageMenu() {
        if (!pressOpen || WidgetGestureArbiter.owner(verdict) == WidgetGestureArbiter.OWNER_BUBBLE) return false;
        return claimHold || ownsDragOrHold();
    }

    public boolean mayGiveSwipe() {
        if (!pressOpen || pressMouse) return false;
        if (!claimed) return true;
        return !claimX && WidgetGestureArbiter.owner(verdict) != WidgetGestureArbiter.OWNER_WIDGET;
    }

    public long lastDownTime() {
        return lastDownTime;
    }

    public boolean onTouchEvent(MotionEvent event) {
        int masked = event.getActionMasked();
        boolean ending = pressOpen && (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL);
        if (message == null || previewing || !message.scriptsRunning() && !ending) return false;
        if (masked == MotionEvent.ACTION_CANCEL && !pressOpen) return false;
        int action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                action = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                action = 1;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                action = 2;
                break;
            case MotionEvent.ACTION_CANCEL:
                action = 3;
                break;
            default:
                return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            if (now - lastDownAt < DUPLICATE_DOWN_MS
                    && Math.abs(event.getX() - lastDownX) < 1
                    && Math.abs(event.getY() - lastDownY) < 1) {
                if (event.getDownTime() != 0) lastDownTime = event.getDownTime();
                if (!pressMouse && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE && !claimed) {
                    pressMouse = true;
                    pressButton = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 ? 2 : 0;
                    pressModifiers = modifiers(event);
                    endHover();
                }
                flushDown();
                return true;
            }
            endWidgetPress();
            lastDownAt = now;
            lastDownTime = event.getDownTime();
            lastDownX = event.getX();
            lastDownY = event.getY();
            endPress();
            pressOpen = true;
            pressMouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
            pressButton = pressMouse && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 ? 2 : 0;
            pressModifiers = modifiers(event);
            if (pressMouse) endHover();
            downPending = event.getDownTime() == 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN;
            widgetCancelled = false;
            holdTold = false;
            parentsHeld = false;
            parentsFree = false;
            verdict = WidgetGestureArbiter.NONE;
            pressAt = now;
            pressX = lastX = event.getX() / density;
            pressY = lastY = event.getY() / density;
            main.postDelayed(claimTimeout, CLAIM_WAIT_MS);
        } else if (pressOpen && event.getActionMasked() != MotionEvent.ACTION_POINTER_DOWN && event.getActionMasked() != MotionEvent.ACTION_POINTER_UP) {
            lastX = event.getX() / density;
            lastY = event.getY() / density;
        }
        if (masked != MotionEvent.ACTION_DOWN) flushDown();
        if (!downPending && (!widgetCancelled || masked == MotionEvent.ACTION_DOWN)) {
            int first = action == 1 || action == 3 ? 0 : event.getActionIndex();
            int end = action == 1 || action == 3 ? event.getPointerCount() : first + 1;
            for (int i = first; i < end; i++) {
                boolean mouse = i == 0 && pressOpen ? pressMouse : event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE;
                message.touch(
                        action,
                        event.getX(i) / density,
                        event.getY(i) / density,
                        event.getPointerId(i),
                        i == 0,
                        mouse,
                        modifiers(event),
                        mouse && i == 0 && pressOpen ? pressButton : 0);
            }
        }
        if (pressOpen) {
            long now = SystemClock.uptimeMillis();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (claimed) apply(arbiter.move(now, leading(lastX), lastY));
                    else holdParents();
                    break;
                case MotionEvent.ACTION_UP:
                    if (!claimed) onClaim(false, false, false);
                    apply(arbiter.up(now, leading(lastX), lastY));
                    endPress();
                    freeParents();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    arbiter.cancel();
                    endPress();
                    freeParents();
                    break;
            }
        }
        return true;
    }

    public void onHoverEvent(MotionEvent event, boolean inside) {
        int masked = event.getActionMasked();
        boolean over = inside && masked != MotionEvent.ACTION_HOVER_EXIT && message != null && !previewing && message.scriptsRunning();
        if (!over) {
            endHover();
            return;
        }
        hovering = true;
        hoverX = event.getX() / density;
        hoverY = event.getY() / density;
        message.hover(0, hoverX, hoverY, modifiers(event));
    }

    private void endHover() {
        if (!hovering) return;
        hovering = false;
        if (message != null && !previewing) message.hover(1, hoverX, hoverY, 0);
        host.setCursor("auto");
    }

    public void onCursor(String cursor) {
        if (hovering || pressOpen && pressMouse) host.setCursor(cursor);
    }

    private static int modifiers(MotionEvent event) {
        int meta = event.getMetaState();
        return ((meta & android.view.KeyEvent.META_ALT_ON) != 0 ? 1 : 0)
                | ((meta & android.view.KeyEvent.META_CTRL_ON) != 0 ? 2 : 0)
                | ((meta & android.view.KeyEvent.META_META_ON) != 0 ? 4 : 0)
                | ((meta & android.view.KeyEvent.META_SHIFT_ON) != 0 ? 8 : 0);
    }
}
