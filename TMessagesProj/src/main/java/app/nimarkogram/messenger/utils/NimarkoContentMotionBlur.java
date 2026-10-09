/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.SharedConfig;

import java.util.WeakHashMap;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoContentMotionBlur {
    public interface Target {
        boolean canBlurMotionContent();
    }

    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();

    private NimarkoContentMotionBlur() {
    }

    public static boolean canUse(View view) {
        return view != null && Build.VERSION.SDK_INT >= 31
                && Looper.myLooper() == Looper.getMainLooper() && NimarkoConfig.motionBlur
                && SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE
                && view.isAttachedToWindow() && view.isShown() && view.isHardwareAccelerated()
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    public static void apply(View view, float[] span) {
        if (!canUse(view) || !(view instanceof Target) || !((Target) view).canBlurMotionContent()
                || span == null || span.length != 8) {
            clear(view);
            return;
        }
        double maximum = 0d;
        for (int i = 0; i < 8; i += 2) {
            if (!Float.isFinite(span[i]) || !Float.isFinite(span[i + 1])) {
                clear(view);
                return;
            }
            maximum = Math.max(maximum, Math.hypot(span[i], span[i + 1]));
        }
        if (maximum < NimarkoMotionBlurEffect.MIN_SPAN_PX) {
            clear(view);
            return;
        }
        State state = STATES.get(view);
        if (state == null) {
            state = new State();
            STATES.put(view, state);
            view.addOnAttachStateChangeListener(state);
        }
        float cap = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_HIGH ? 16f : 8f;
        float scale = (float) Math.min(1d, cap / maximum);
        for (int i = 0; i < 8; i++) state.span[i] = span[i] * scale;
        state.active = true;
        view.invalidate();
    }

    public static boolean isApplied(View view) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return false;
        State state = STATES.get(view);
        return state != null && state.active;
    }

    public static void clear(View view) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return;
        State state = STATES.get(view);
        if (state == null || !state.active) return;
        state.active = false;
        state.stop();
        view.invalidate();
    }

    public static void dispose(View view) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return;
        State state = STATES.remove(view);
        if (state == null) return;
        view.removeOnAttachStateChangeListener(state);
        if (state.active) state.stop();
        state.active = false;
        state.display.node = null;
        if (state.capture != null) state.capture.node = null;
        view.invalidate();
    }

    public static Canvas begin(View view, Canvas destination) {
        return begin(view, destination, false);
    }

    public static Canvas begin(View view, Canvas destination, boolean capture) {
        if (!canUse(view) || !(view instanceof Target) || !((Target) view).canBlurMotionContent()) {
            clear(view);
            return null;
        }
        if (destination == null || !destination.isHardwareAccelerated()) return null;
        State state = STATES.get(view);
        if (state == null || !state.active) return null;
        Recording slot = state.slot(capture);
        if (slot.recording) return null;
        int width = view.getWidth();
        int height = view.getHeight();
        if (!destination.getClipBounds(slot.clip)
                || !slot.clip.intersect(0, 0, width, height)) return null;
        float haloX = 0f;
        float haloY = 0f;
        for (int i = 0; i < 8; i += 2) {
            haloX = Math.max(haloX, Math.abs(state.span[i]) * .5f);
            haloY = Math.max(haloY, Math.abs(state.span[i + 1]) * .5f);
        }
        int paddingX = (int) Math.ceil(haloX);
        int paddingY = (int) Math.ceil(haloY);
        slot.clip.set(Math.max(0, slot.clip.left - paddingX), Math.max(0, slot.clip.top - paddingY),
                Math.min(width, slot.clip.right + paddingX), Math.min(height, slot.clip.bottom + paddingY));
        RenderEffect effect;
        try {
            effect = slot.effect.create(state.span, width, height);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            clear(view);
            return null;
        }
        if (effect == null) {
            clear(view);
            return null;
        }
        if (slot.node == null) slot.node = new RenderNode("NimarkoMotionContent");
        slot.node.setPosition(0, 0, width, height);
        slot.node.setClipToBounds(false);
        if (slot.installedEffect != effect) {
            slot.node.setRenderEffect(effect);
            slot.installedEffect = effect;
        }
        Canvas recording = slot.node.beginRecording(width, height);
        recording.clipRect(slot.clip);
        slot.recording = true;
        return recording;
    }

    public static void end(View view, Canvas destination) {
        end(view, destination, false);
    }

    public static void end(View view, Canvas destination, boolean capture) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return;
        State state = STATES.get(view);
        if (state == null) return;
        Recording slot = capture ? state.capture : state.display;
        if (slot == null || !slot.recording) return;
        slot.node.endRecording();
        slot.recording = false;
        if (!state.active || !canUse(view)) {
            clear(view);
            return;
        }
        int save = destination.save();
        try {
            destination.clipRect(0, 0, view.getWidth(), view.getHeight());
            if (view instanceof ViewGroup && ((ViewGroup) view).getClipToPadding()) {
                destination.clipRect(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getWidth() - view.getPaddingRight(), view.getHeight() - view.getPaddingBottom());
            }
            destination.drawRenderNode(slot.node);
        } finally {
            destination.restoreToCount(save);
        }
    }

    private static final class State implements View.OnAttachStateChangeListener {
        final float[] span = new float[8];
        final Recording display = new Recording();
        Recording capture;
        boolean active;

        Recording slot(boolean capturing) {
            if (!capturing) return display;
            if (capture == null) capture = new Recording();
            return capture;
        }

        void stop() {
            display.stop();
            if (capture != null) capture.stop();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            dispose(view);
        }
    }

    private static final class Recording {
        final Rect clip = new Rect();
        final NimarkoMotionBlurEffect.ContentEffect effect = new NimarkoMotionBlurEffect.ContentEffect();
        RenderNode node;
        RenderEffect installedEffect;
        boolean recording;

        void stop() {
            if (node != null) {
                if (recording) node.endRecording();
                recording = false;
                node.setRenderEffect(null);
                installedEffect = null;
                node.discardDisplayList();
            }
        }
    }
}
