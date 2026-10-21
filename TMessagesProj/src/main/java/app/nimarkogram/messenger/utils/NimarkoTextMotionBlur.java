/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.Looper;

import app.nimarkogram.messenger.NimarkoConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

public final class NimarkoTextMotionBlur {
    public static final class Budget {
        long time;
        int layers, pixels;

        public void start() {
            if (!isMainThread()) return;
            final long now = NimarkoAppMotionBlur.frameTimeNanos();
            if (time != now) {
                time = now;
            layers = pixels = 0;
            }
        }
    }

    static final class Motion {
        long time;
        float x, y, sx, sy, px, py;
        boolean moving;
        final float[] spans = new float[8];
        final float[] observed = new float[8];
        float strength;

        boolean sample(long now, float nx, float ny, float nsx, float nsy,
                       float npx, float npy, int left, int top, int width, int height, float cap) {
            long elapsed = now - time;
            if (time != 0 && elapsed == 0) return moving;
            boolean continuous = time != 0 && elapsed > 0 && elapsed <= 120_000_000L
                    && nsx > .01f && nsy > .01f && Float.isFinite(nx) && Float.isFinite(ny)
                    && Float.isFinite(nsx) && Float.isFinite(nsy);
            double maximum = 0;
            for (int i = 0; i < 4; i++) {
                float cx = left + (i == 1 || i == 2 ? width : 0);
                float cy = top + (i >= 2 ? height : 0);
                float dx = continuous ? ((nx + npx + (cx - npx) * nsx)
                        - (x + px + (cx - px) * sx)) / nsx : 0;
                float dy = continuous ? ((ny + npy + (cy - npy) * nsy)
                        - (y + py + (cy - py) * sy)) / nsy : 0;
                float shutter = continuous ? NimarkoMotionBlurState.exposureScale(elapsed) : 0;
                observed[i * 2] = dx * shutter;
                observed[i * 2 + 1] = dy * shutter;
                maximum = Math.max(maximum, Math.hypot(observed[i * 2], observed[i * 2 + 1]));
            }
            time = now;
            x = nx; y = ny; sx = nsx; sy = nsy; px = npx; py = npy;
            if (!continuous || !Double.isFinite(maximum)) {
                strength = 0f;
                for (int i = 0; i < 8; i++) spans[i] = 0f;
            } else if (maximum > 0d) {
                double direction = 0d;
                for (int i = 0; i < 8; i++) direction += (double) spans[i] * observed[i];
                if (direction < 0d) strength = 0f;
                strength = NimarkoMotionBlurState.smoothLength(strength, (float) Math.min(cap, maximum), elapsed);
                float factor = (float) (strength / maximum);
                for (int i = 0; i < 8; i++) spans[i] = observed[i] * factor;
            } else {
                strength = 0f;
                for (int i = 0; i < 8; i++) spans[i] = 0f;
            }
            moving = strength >= NimarkoMotionBlurEffect.MIN_SPAN_PX;
            return moving;
        }
    }

    private final Motion motion = new Motion();
    private RenderNode node;
    private boolean nodeActive;
    private NimarkoMotionBlurEffect.ContentEffect contentEffect;
    private RenderEffect installedEffect;

    private long recordedFrameNs;
    public static boolean enabled(Canvas canvas) {
        return isMainThread() && featureEnabled() && canvas.isHardwareAccelerated();
    }

    public static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    public static boolean featureEnabled() {
        return Build.VERSION.SDK_INT >= 31 && NimarkoConfig.motionBlur
                && SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE;
    }

    public Canvas begin(Canvas destination, Budget budget, float x, float y, float sx, float sy,
                        float pivotX, float pivotY, int left, int top, int width, int height) {
        if (!isMainThread()) return null;
        if (!featureEnabled()) {
            reset();
            return null;
        }
        if (!destination.isHardwareAccelerated()) return null;
        if (recordedFrameNs == budget.time && nodeActive) return null;
        float cap = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_HIGH
                ? NimarkoMotionBlurState.MAX_SPAN_PX : NimarkoMotionBlurState.REDUCED_MAX_SPAN_PX;
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || !motion.sample(budget.time, x, y, sx, sy, pivotX, pivotY, left, top, width, height, cap)) {
            releaseNode();
            return null;
        }
        long area = (long) width * height;
        final boolean high = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_HIGH;
        if (budget.layers >= (high ? 4 : 2) || area > (high ? 131072 : 65536) - budget.pixels) {
            releaseNode();
            return null;
        }
        if (contentEffect == null) contentEffect = new NimarkoMotionBlurEffect.ContentEffect();
        RenderEffect effect = contentEffect.createContentEffect(motion.spans, width, height, AndroidUtilities.density);
        if (effect == null) {
            releaseNode();
            return null;
        }
        if (node == null) node = new RenderNode("NimarkoTextPart");
        node.setPosition(left, top, left + width, top + height);
        node.setClipToBounds(true);
        if (installedEffect != effect) {
            node.setRenderEffect(effect);
            installedEffect = effect;
        }
        Canvas recording = node.beginRecording(width, height);
        recordedFrameNs = budget.time;
        nodeActive = true;
        recording.translate(-left, -top);
        budget.layers++;
        budget.pixels += (int) area;
        return recording;
    }

    public void end(Canvas destination) {
        node.endRecording();
        destination.drawRenderNode(node);
    }

    public void reset() {
        motion.time = 0;
        motion.moving = false;
        recordedFrameNs = 0L;
        releaseNode();
    }

    public void dispose() {
        reset();
        node = null;
        contentEffect = null;
    }

    private void releaseNode() {
        if (node != null && nodeActive) {
            nodeActive = false;
            node.setRenderEffect(null);
            installedEffect = null;
            node.discardDisplayList();
        }
    }
}
