/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.Looper;
import android.view.View;

import android.view.TextureView;
import java.util.WeakHashMap;

import java.lang.ref.WeakReference;

public final class MotionBlurEffect {
    public interface OutputBoundsParent {}
    public static final float MAX_SPAN_PX = MotionBlurState.MAX_SPAN_PX;
    private static final float MAX_SPAN_DP = MotionBlurState.MAX_SPAN_DP;
    public static final float MIN_SPAN_PX = MotionBlurState.MIN_SPAN_PX;
    public static final int MAX_SAMPLES = 17;
    public static final int LIVE_CONTENT_SAMPLES = 9;
    private static final double CORNER_REUSE_PX = 0.5d;
    private static final double CORNER_REUSE_FRACTION = 0.125d;
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();

    private MotionBlurEffect() {
    }

    public static void apply(View view, float velocityX, float velocityY, float length) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return;
        double speed = Math.hypot(velocityX, velocityY);
        if (!Float.isFinite(length) || length < MIN_SPAN_PX
                || !Double.isFinite(speed) || speed < 1f) {
            clear(view);
            return;
        }
        float span = Math.min(length, MAX_SPAN_PX);
        float x = (float) (velocityX / speed * span);
        float y = (float) (velocityY / speed * span);
        apply(view, new float[]{x, y, x, y, x, y, x, y});
    }

    public static void apply(View view, float[] spanCorners) {
        apply(view, spanCorners, view instanceof TextureView ? LIVE_CONTENT_SAMPLES : MAX_SAMPLES);
    }

    public static void apply(View view, float[] spanCorners, int maxSamples) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return;
        if (spanCorners == null || spanCorners.length != 8
                || !view.isAttachedToWindow() || !view.isHardwareAccelerated()
                || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) {
            clear(view);
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        if (!Float.isFinite(density) || density <= 0f) {
            clear(view);
            return;
        }
        double maximum = 0;
        for (int i = 0; i < 8; i += 2) {
            if (!Float.isFinite(spanCorners[i]) || !Float.isFinite(spanCorners[i + 1])) {
                clear(view);
                return;
            }
            maximum = Math.max(maximum, Math.hypot(spanCorners[i], spanCorners[i + 1]));
        }
        if (maximum < MIN_SPAN_PX) {
            clear(view);
            return;
        }
        State state = stateFor(view);
        if (state.released) return;
        state.sampleLimit = normalizeSampleLimit(maxSamples);
        float maximumSpan = Math.min(getMaximumSpan(density), MotionBlurQualityPolicy.maximumSpan(state.sampleLimit));
        double scale = Math.min(1d, maximumSpan / maximum);
        if (maximum * scale < MIN_SPAN_PX) {
            clear(view);
            return;
        }
        for (int i = 0; i < 8; i++) state.pendingCorners[i] = (float) (spanCorners[i] * scale);
        int width = view.getWidth();
        int height = view.getHeight();
        state.pendingBounds.set(0, 0, width, height);
        if (view.getClipBounds(state.pendingClip)) {
            if (!state.pendingBounds.intersect(state.pendingClip)) {
                clear(view);
                return;
            }
        }
        boolean opaqueEdges = state.baseEffect == null && view.isOpaque()
                && state.pendingBounds.left == 0 && state.pendingBounds.top == 0
                && state.pendingBounds.right == width && state.pendingBounds.bottom == height;
        if (state.effect == null || state.renderedSpanLimit != maximumSpan
                || !canReuseCorners(state, maximum * scale)
                || state.width != width || state.height != height
                || !state.bounds.equals(state.pendingBounds) || state.opaqueEdges != opaqueEdges) {
            RenderEffect effect = createKernelEffect(state, state.pendingCorners, width, height,
                    state.pendingBounds, opaqueEdges, maximum * scale);
            if (effect == null) {
                clear(view);
                return;
            }
            state.effect = effect;
            state.renderedMaximum = maximum * scale;
            state.renderedSamples = sampleCount(maximum * scale, state.sampleLimit);
            state.renderedSampleLimit = state.sampleLimit;
            state.renderedSpanLimit = maximumSpan;
            System.arraycopy(state.pendingCorners, 0, state.corners, 0, 8);
            state.bounds.set(state.pendingBounds);
            state.opaqueEdges = opaqueEdges;
            state.width = width;
            state.height = height;
            state.composedEffect = state.baseEffect == null ? effect
                    : RenderEffect.createChainEffect(effect, state.baseEffect);
            view.setRenderEffect(state.composedEffect);
            state.owned = true;
        } else if (!state.owned) {
            view.setRenderEffect(state.composedEffect);
            state.owned = true;
        }
        invalidateOutputParent(view, state);
    }

    private static boolean canReuseCorners(State state, double maximum) {
        if (state.renderedSampleLimit != state.sampleLimit) return false;
        if (Build.VERSION.SDK_INT >= 33 && !state.shaderFailed && !Kernel33.unavailable
                && state.renderedSamples != sampleCount(maximum, state.sampleLimit)) return false;
        for (int i = 0; i < 8; i += 2) {
            double x = state.corners[i], y = state.corners[i + 1];
            double nextX = state.pendingCorners[i], nextY = state.pendingCorners[i + 1];
            if (x * nextX < 0d || y * nextY < 0d) return false;
            double dx = nextX - x, dy = nextY - y;
            double errorSquared = dx * dx + dy * dy;
            double spanSquared = Math.min(x * x + y * y, nextX * nextX + nextY * nextY);
            if (errorSquared > CORNER_REUSE_PX * CORNER_REUSE_PX
                    || errorSquared > spanSquared * CORNER_REUSE_FRACTION * CORNER_REUSE_FRACTION) {
                return false;
            }
        }
        return true;
    }

    private static RenderEffect createKernelEffect(State state, float[] corners, int width, int height,
            Rect bounds, boolean opaqueEdges, double maximum) {
        if (Build.VERSION.SDK_INT >= 33 && !state.shaderFailed && !Kernel33.unavailable) {
            try {
                RenderEffect effect = Kernel33.obtain(sampleCount(maximum, state.sampleLimit))
                        .createEffect(corners, width, height, bounds, opaqueEdges);
                state.runtimeKernel = true;
                return effect;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                state.shaderFailed = true;
                Kernel33.unavailable = true;
            }
        }
        state.runtimeKernel = false;
        return createFallback(corners, opaqueEdges, state.sampleLimit);
    }

    public static int clipOutput(Canvas canvas, View view) {
        if (view == null || canvas == null || Build.VERSION.SDK_INT < 33
                || Looper.myLooper() != Looper.getMainLooper() || !canvas.isHardwareAccelerated()) return -1;
        State state = STATES.get(view);
        if (state == null || !canClipOutput(view, state)) return -1;
        RectF output = state.outputBounds;
        float padding = (float) Math.ceil(state.renderedSpanLimit) + 1f;
        output.set(state.bounds.left - padding, state.bounds.top - padding,
                state.bounds.right + padding, state.bounds.bottom + padding);
        view.getMatrix().mapRect(output);
        output.offset(view.getLeft(), view.getTop());
        if (!Float.isFinite(output.left) || !Float.isFinite(output.top)
                || !Float.isFinite(output.right) || !Float.isFinite(output.bottom) || output.isEmpty()) return -1;
        int save = canvas.save();
        boolean clipped = false;
        try {
            canvas.clipRect(output);
            clipped = true;
            return save;
        } finally {
            if (!clipped) canvas.restoreToCount(save);
        }
    }

    private static boolean canClipOutput(View view, State state) {
        return state.owned && !state.released && state.runtimeKernel
                && view.getParent() instanceof OutputBoundsParent
                && view.getAnimation() == null && view.getZ() == 0f && view.getMatrix().isAffine();
    }

    private static void invalidateOutputParent(View view, State state) {
        View parent = canClipOutput(view, state) && view.getParent() instanceof View
                ? (View) view.getParent() : null;
        View previous = state.outputParent == null ? null : state.outputParent.get();
        if (previous != null && previous != parent) previous.invalidate();
        if (parent != null) {
            if (previous != parent) state.outputParent = new WeakReference<>(parent);
            parent.invalidate();
        } else {
            state.outputParent = null;
        }
    }

    public static final class ContentEffect {
        private final State cache = new State();

        public RenderEffect create(float[] spanCorners, int width, int height) {
            return create(spanCorners, width, height, MAX_SPAN_PX);
        }

        public RenderEffect create(float[] spanCorners, int width, int height, int maxSamples) {
            return create(spanCorners, width, height, MAX_SPAN_PX, maxSamples);
        }
        public RenderEffect createContentEffect(float[] spanCorners, int width, int height, float density) {
            return create(spanCorners, width, height, getMaximumSpan(density));
        }

        private RenderEffect create(float[] spanCorners, int width, int height, float maximumSpan) {
            return create(spanCorners, width, height, maximumSpan, MAX_SAMPLES);
        }

        private RenderEffect create(float[] spanCorners, int width, int height, float maximumSpan, int maxSamples) {
            if (Build.VERSION.SDK_INT < 31 || Looper.myLooper() != Looper.getMainLooper()) return null;
            if (spanCorners == null || spanCorners.length != 8 || width <= 0 || height <= 0
                    || !Float.isFinite(maximumSpan) || maximumSpan <= 0f) {
                clear();
                return null;
            }
            double maximum = 0d;
            for (int i = 0; i < 8; i += 2) {
                if (!Float.isFinite(spanCorners[i]) || !Float.isFinite(spanCorners[i + 1])) {
                    clear();
                    return null;
                }
                maximum = Math.max(maximum, Math.hypot(spanCorners[i], spanCorners[i + 1]));
            }
            if (maximum < MIN_SPAN_PX) {
                clear();
                return null;
            }
            cache.sampleLimit = normalizeSampleLimit(maxSamples);
            maximumSpan = Math.min(maximumSpan, MotionBlurQualityPolicy.maximumSpan(cache.sampleLimit));
            double scale = Math.min(1d, maximumSpan / maximum);
            if (maximum * scale < MIN_SPAN_PX) {
                clear();
                return null;
            }
            for (int i = 0; i < 8; i++) cache.pendingCorners[i] = (float) (spanCorners[i] * scale);
            cache.pendingBounds.set(0, 0, width, height);
            if (cache.effect != null && cache.width == width && cache.height == height
                    && cache.renderedSpanLimit == maximumSpan
                    && canReuseCorners(cache, maximum * scale)) return cache.effect;
            cache.effect = createKernelEffect(cache, cache.pendingCorners, width, height,
                    cache.pendingBounds, false, maximum * scale);
            if (cache.effect != null) {
                cache.renderedMaximum = maximum * scale;
                cache.renderedSamples = sampleCount(maximum * scale, cache.sampleLimit);
                cache.renderedSampleLimit = cache.sampleLimit;
                cache.renderedSpanLimit = maximumSpan;
                cache.width = width;
                cache.height = height;
                System.arraycopy(cache.pendingCorners, 0, cache.corners, 0, 8);
            }
            return cache.effect;
        }

        public void clear() {
            if (Looper.myLooper() == Looper.getMainLooper()) cache.effect = null;
        }
    }

    public static float getMaximumSpan(float density) {
        return Float.isFinite(density) && density > 0f ? Math.min(MAX_SPAN_PX, MAX_SPAN_DP * density) : 0f;
    }

    public static int normalizeSampleLimit(int samples) {
        return MotionBlurQualityPolicy.normalizeSamples(samples);
    }

    private static int sampleCount(double maximum, int limit) {
        return Math.min(maximum > 12d ? MAX_SAMPLES : maximum > 6d ? LIVE_CONTENT_SAMPLES : 5, limit);
    }

    private static RenderEffect createFallback(float[] corners, boolean opaqueEdges, int sampleLimit) {
        float x = corners[0], y = corners[1];
        for (int i = 2; i < 8; i += 2) {
            if (Math.hypot(corners[i] - x, corners[i + 1] - y) > 0.5d) return null;
        }
        double speed = Math.hypot(x, y);
        if (speed < MIN_SPAN_PX) return null;
        double scale = Math.min(1d, MotionBlurState.REDUCED_MAX_SPAN_PX / speed);
        x = (float) (x * scale);
        y = (float) (y * scale);
        BlendModeColorFilter[] weights = sampleLimit == 5 ? Fallback31.COMPACT_WEIGHTS : Fallback31.WEIGHTS;
        RenderEffect sum = null;
        for (int i = 0; i < weights.length; i++) {
            float t = i / (float) (weights.length - 1);
            RenderEffect tap = RenderEffect.createColorFilterEffect(weights[i],
                    RenderEffect.createOffsetEffect(-x * t, -y * t));
            sum = sum == null ? tap : RenderEffect.createBlendModeEffect(sum, tap, BlendMode.PLUS);
        }
        return opaqueEdges ? RenderEffect.createBlendModeEffect(
                RenderEffect.createOffsetEffect(0f, 0f), sum, BlendMode.SRC_OVER) : sum;
    }

    private static State stateFor(View view) {
        State state = STATES.get(view);
        if (state == null) {
            state = new State();
            STATES.put(view, state);
            view.addOnAttachStateChangeListener(state);
        }
        return state;
    }

    public static void setBaseEffect(View view, RenderEffect effect) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return;
        State state = stateFor(view);
        if (state.baseRegistered && state.baseEffect == effect && !state.released) return;
        boolean active = state.owned && !state.released;
        state.baseEffect = effect;
        state.baseRegistered = true;
        state.effect = null;
        state.composedEffect = null;
        state.released = false;
        state.owned = false;
        invalidateOutputParent(view, state);
        view.setRenderEffect(effect);
        if (active) apply(view, state.corners, state.sampleLimit);
    }

    public static boolean isApplied(View view) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return false;
        State state = STATES.get(view);
        return state != null && state.owned && state.composedEffect != null;
    }

    public static void clear(View view) {
        if (view == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return;
        State state = STATES.get(view);
        if (state != null && state.owned) {
            state.owned = false;
            invalidateOutputParent(view, state);
            view.setRenderEffect(state.baseEffect);
        }
    }

    public static void releaseOwnership(View view) {
        if (view == null || Looper.myLooper() != Looper.getMainLooper()) return;
        if (Build.VERSION.SDK_INT < 31) return;
        State state = stateFor(view);
        state.owned = false;
        state.released = true;
        invalidateOutputParent(view, state);
    }

    private static final class State implements View.OnAttachStateChangeListener {
        RenderEffect effect;
        RenderEffect baseEffect;
        RenderEffect composedEffect;
        final RectF outputBounds = new RectF();
        boolean runtimeKernel;
        WeakReference<View> outputParent;
        boolean shaderFailed;
        boolean owned;
        boolean released;
        boolean baseRegistered;
        boolean opaqueEdges;
        double renderedMaximum;
        int sampleLimit = MAX_SAMPLES;
        int renderedSamples;
        int renderedSampleLimit;
        float renderedSpanLimit;
        final float[] corners = new float[8];
        final float[] pendingCorners = new float[8];
        final Rect bounds = new Rect();
        final Rect pendingBounds = new Rect();
        final Rect pendingClip = new Rect();
        int width;
        int height;

        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            clear(view);
        }
    }

    private static final class Fallback31 {
        private static final BlendModeColorFilter[] COMPACT_WEIGHTS = {
                new BlendModeColorFilter(Color.argb(89, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(66, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(50, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(33, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(17, 255, 255, 255), BlendMode.DST_IN)
        };
        private static final BlendModeColorFilter[] WEIGHTS = {
                new BlendModeColorFilter(Color.argb(89, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(37, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(32, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(28, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(23, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(18, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(14, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(9, 255, 255, 255), BlendMode.DST_IN),
                new BlendModeColorFilter(Color.argb(5, 255, 255, 255), BlendMode.DST_IN)
        };
    }
    private static final class Kernel33 {
        private static Kernel33 compact;
        private static Kernel33 balanced;
        private static Kernel33 dense;
        private static boolean unavailable;
        private static final String SOURCE =
                "uniform shader content;\n"
                + "uniform float2 cornerTL;\n"
                + "uniform float2 cornerTR;\n"
                + "uniform float2 cornerBR;\n"
                + "uniform float2 cornerBL;\n"
                + "uniform float2 size;\n"
                + "uniform float4 bounds;\n"
                + "uniform float opaqueEdges;\n"
                + "half4 main(float2 position) {\n"
                + "    float2 uv = clamp(position / size, float2(0.0), float2(1.0));\n"
                + "    float2 span = mix(mix(cornerTL, cornerTR, uv.x),\n"
                + "            mix(cornerBL, cornerBR, uv.x), uv.y);\n"
                + "    half4 color = half4(0.0);\n"
                + "    bool inside = position.x >= bounds.x && position.y >= bounds.y\n"
                + "            && position.x < bounds.z && position.y < bounds.w;\n"
                + "    if (inside) {\n"
                + "        color = content.eval(position) * 0.35;\n"
                + "    }\n"
                + "    float2 endPosition = position + span;\n"
                + "    if (opaqueEdges > 0.5 && inside) {\n"
                + "        for (int i = 1; i < 17; ++i) {\n"
                + "            float2 samplePosition = position + span * (float(i) / 16.0);\n"
                + "            float weight = (17.0 - float(i)) * (0.65 / 136.0);\n"
                + "            samplePosition = clamp(samplePosition, float2(0.5), size - float2(0.5));\n"
                + "            color += content.eval(samplePosition) * weight;\n"
                + "        }\n"
                + "    } else if (inside && endPosition.x >= bounds.x && endPosition.y >= bounds.y\n"
                + "            && endPosition.x < bounds.z && endPosition.y < bounds.w) {\n"
                + "        for (int i = 1; i < 17; ++i) {\n"
                + "            float2 samplePosition = position + span * (float(i) / 16.0);\n"
                + "            float weight = (17.0 - float(i)) * (0.65 / 136.0);\n"
                + "            color += content.eval(samplePosition) * weight;\n"
                + "        }\n"
                + "    } else {\n"
                + "        for (int i = 1; i < 17; ++i) {\n"
                + "            float2 samplePosition = position + span * (float(i) / 16.0);\n"
                + "            float weight = (17.0 - float(i)) * (0.65 / 136.0);\n"
                + "            if (samplePosition.x >= bounds.x && samplePosition.y >= bounds.y\n"
                + "                    && samplePosition.x < bounds.z && samplePosition.y < bounds.w) {\n"
                + "                color += content.eval(samplePosition) * weight;\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "    return color;\n"
                + "}\n";

        private static final String SOURCE9 = SOURCE.replace("i < 17", "i < 9")
                .replace("float(i) / 16.0", "float(i) / 8.0")
                .replace("17.0 - float(i)", "9.0 - float(i)")
                .replace("0.65 / 136.0", "0.65 / 36.0");

        private static final String SOURCE5 = SOURCE9.replace("i < 9", "i < 5")
                .replace("float(i) / 8.0", "float(i) / 4.0")
                .replace("9.0 - float(i)", "5.0 - float(i)")
                .replace("0.65 / 36.0", "0.65 / 10.0");

        private final RuntimeShader shader;

        Kernel33(int samples) {
            shader = new RuntimeShader(samples == 17 ? SOURCE : samples == 9 ? SOURCE9 : SOURCE5);
        }


        static Kernel33 obtain(int samples) {
            if (samples == 17) {
                if (dense == null) dense = new Kernel33(17);
                return dense;
            }
            if (samples == 9) {
                if (balanced == null) balanced = new Kernel33(9);
                return balanced;
            }
            if (compact == null) compact = new Kernel33(5);
            return compact;
        }

        RenderEffect createEffect(float[] corners, int width, int height, Rect bounds, boolean opaqueEdges) {
            shader.setFloatUniform("cornerTL", corners[0], corners[1]);
            shader.setFloatUniform("cornerTR", corners[2], corners[3]);
            shader.setFloatUniform("cornerBR", corners[4], corners[5]);
            shader.setFloatUniform("cornerBL", corners[6], corners[7]);
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("bounds", bounds.left, bounds.top, bounds.right, bounds.bottom);
            shader.setFloatUniform("opaqueEdges", opaqueEdges ? 1f : 0f);
            return RenderEffect.createRuntimeShaderEffect(shader, "content");
        }
    }
}
