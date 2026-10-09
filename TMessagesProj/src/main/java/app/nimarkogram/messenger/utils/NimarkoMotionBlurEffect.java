/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.Looper;
import android.view.View;

import java.util.Arrays;
import java.util.WeakHashMap;

public final class NimarkoMotionBlurEffect {
    public static final float MAX_SPAN_PX = 16f;
    private static final float MAX_SPAN_DP = 14f;
    public static final float MIN_SPAN_PX = 0.1f;
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();

    private NimarkoMotionBlurEffect() {
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
        double scale = Math.min(1d, getMaximumSpan(density) / maximum);
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
        if (state.effect == null || !Arrays.equals(state.corners, state.pendingCorners)
                || state.width != width || state.height != height
                || !state.bounds.equals(state.pendingBounds) || state.opaqueEdges != opaqueEdges) {
            RenderEffect effect = createKernelEffect(state, state.pendingCorners, width, height,
                    state.pendingBounds, opaqueEdges, maximum * scale);
            if (effect == null) {
                clear(view);
                return;
            }
            state.effect = effect;
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
    }

    private static RenderEffect createKernelEffect(State state, float[] corners, int width, int height,
            Rect bounds, boolean opaqueEdges, double maximum) {
        if (Build.VERSION.SDK_INT >= 33 && !state.shaderFailed) {
            try {
                Kernel33 kernel;
                if (maximum > 8d) {
                    if (state.kernel17 == null) state.kernel17 = new Kernel33(true);
                    kernel = state.kernel17;
                } else {
                    if (state.kernel9 == null) state.kernel9 = new Kernel33(false);
                    kernel = state.kernel9;
                }
                return kernel.createEffect(corners, width, height, bounds, opaqueEdges);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                state.shaderFailed = true;
                state.kernel9 = null;
                state.kernel17 = null;
            }
        }
        return createFallback(corners, opaqueEdges);
    }

    public static final class ContentEffect {
        private final State cache = new State();

        public RenderEffect create(float[] spanCorners, int width, int height) {
            return create(spanCorners, width, height, MAX_SPAN_PX);
        }

        public RenderEffect createContentEffect(float[] spanCorners, int width, int height, float density) {
            return create(spanCorners, width, height, getMaximumSpan(density));
        }

        private RenderEffect create(float[] spanCorners, int width, int height, float maximumSpan) {
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
            double scale = Math.min(1d, maximumSpan / maximum);
            if (maximum * scale < MIN_SPAN_PX) {
                clear();
                return null;
            }
            for (int i = 0; i < 8; i++) cache.pendingCorners[i] = (float) (spanCorners[i] * scale);
            cache.pendingBounds.set(0, 0, width, height);
            if (cache.effect != null && cache.width == width && cache.height == height
                    && Arrays.equals(cache.corners, cache.pendingCorners)) return cache.effect;
            cache.effect = createKernelEffect(cache, cache.pendingCorners, width, height,
                    cache.pendingBounds, false, maximum * scale);
            if (cache.effect != null) {
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

    private static RenderEffect createFallback(float[] corners, boolean opaqueEdges) {
        float x = corners[0], y = corners[1];
        for (int i = 2; i < 8; i += 2) {
            if (Math.hypot(corners[i] - x, corners[i + 1] - y) > 0.5d) return null;
        }
        double speed = Math.hypot(x, y);
        if (speed < MIN_SPAN_PX) return null;
        double scale = Math.min(1d, 8d / speed);
        x = (float) (x * scale);
        y = (float) (y * scale);
        BlendModeColorFilter weight28 = new BlendModeColorFilter(Color.argb(28, 255, 255, 255), BlendMode.DST_IN);
        BlendModeColorFilter weight29 = new BlendModeColorFilter(Color.argb(29, 255, 255, 255), BlendMode.DST_IN);
        RenderEffect sum = null;
        for (int i = 0; i < 9; i++) {
            float t = i / 8f - 0.5f;
            BlendModeColorFilter weight = i == 2 || i == 4 || i == 6 ? weight29 : weight28;
            RenderEffect tap = RenderEffect.createColorFilterEffect(weight,
                    RenderEffect.createOffsetEffect(x * t, y * t));
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
        view.setRenderEffect(effect);
        if (active) apply(view, state.corners);
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
            view.setRenderEffect(state.baseEffect);
        }
    }

    public static void releaseOwnership(View view) {
        if (view == null || Looper.myLooper() != Looper.getMainLooper()) return;
        if (Build.VERSION.SDK_INT < 31) return;
        State state = stateFor(view);
        state.owned = false;
        state.released = true;
    }

    private static final class State implements View.OnAttachStateChangeListener {
        Kernel33 kernel9;
        Kernel33 kernel17;
        RenderEffect effect;
        RenderEffect baseEffect;
        RenderEffect composedEffect;
        boolean shaderFailed;
        boolean owned;
        boolean released;
        boolean baseRegistered;
        boolean opaqueEdges;
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

    private static final class Kernel33 {
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
                + "    for (int i = 0; i < 17; ++i) {\n"
                + "        float2 samplePosition = position + span * (float(i) / 16.0 - 0.5);\n"
                + "        if (opaqueEdges > 0.5 && position.x >= bounds.x && position.y >= bounds.y\n"
                + "                && position.x < bounds.z && position.y < bounds.w) {\n"
                + "            samplePosition = clamp(samplePosition, float2(0.5), size - float2(0.5));\n"
                + "            color += content.eval(samplePosition);\n"
                + "        } else if (samplePosition.x >= bounds.x && samplePosition.y >= bounds.y\n"
                + "                && samplePosition.x < bounds.z && samplePosition.y < bounds.w) {\n"
                + "            color += content.eval(samplePosition);\n"
                + "        }\n"
                + "    }\n"
                + "    return color / 17.0;\n"
                + "}\n";

        private static final String SOURCE9 = SOURCE.replace("i < 17", "i < 9")
                .replace("float(i) / 16.0", "float(i) / 8.0").replace("color / 17.0", "color / 9.0");

        private final RuntimeShader shader;

        Kernel33(boolean dense) {
            shader = new RuntimeShader(dense ? SOURCE : SOURCE9);
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
