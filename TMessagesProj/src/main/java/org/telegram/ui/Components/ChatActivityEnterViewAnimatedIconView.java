/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.animation.LinearInterpolator;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import java.util.HashMap;
import java.util.Map;

import app.nimarkogram.messenger.icons.NimarkoIconResources;
import app.nimarkogram.messenger.NimarkoConfig;
public class ChatActivityEnterViewAnimatedIconView extends RLottieImageView {
    private State currentState;
    private TransitState animatingState;
    private final int sizeDp;

    private final Theme.ResourcesProvider resourcesProvider;
    private FixedSizeIconDrawable packDrawable;
    private ValueAnimator packAnimator;
    private PackTransitionDrawable packTransition;
    private Runnable pendingLottieStart;
    private int lastPackSelection = Integer.MIN_VALUE;
    private long lastPackGeneration;
    private int lastDensityDpi;
    private float lastDensity;
    private Resources lastResources;
    private Configuration lastConfiguration;
    private final Map<TransitState, RLottieDrawable> stateMap = new HashMap<TransitState, RLottieDrawable>() {
        @Nullable
        @Override
        public RLottieDrawable get(@Nullable Object key) {
            RLottieDrawable obj = super.get(key);
            if (obj == null) {
                TransitState state = (TransitState) key;
                int res = state.resource;
                RLottieDrawable rLottieDrawable = new RLottieDrawable(res, AndroidUtilities.dp(sizeDp), AndroidUtilities.dp(sizeDp));
                put(state, rLottieDrawable);
                return rLottieDrawable;
            }
            return obj;
        }
    };

    public ChatActivityEnterViewAnimatedIconView(Context context) {
        this(context, 32);
    }

    public ChatActivityEnterViewAnimatedIconView(Context context, int sizeDp) {
        this(context, sizeDp, null);
    }

    public ChatActivityEnterViewAnimatedIconView(Context context, Theme.ResourcesProvider resourcesProvider) {
        this(context, 32, resourcesProvider);
    }

    public ChatActivityEnterViewAnimatedIconView(Context context, int sizeDp, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.sizeDp = sizeDp;
        this.resourcesProvider = resourcesProvider;
    }

    public void setState(State state, boolean animate) {
        Resources resources = getContext().getResources();
        int iconId = getStaticIcon(state);
        boolean usePack = resources instanceof NimarkoIconResources
                && ((NimarkoIconResources) resources).hasReplacement(iconId);
        int selection = resources instanceof NimarkoIconResources
                ? ((NimarkoIconResources) resources).getAppliedSelection() : NimarkoConfig.ICON_REPLACE_NONE;
        long generation = resources instanceof NimarkoIconResources
                ? ((NimarkoIconResources) resources).getReplacementGeneration() : -1L;
        int densityDpi = resources.getDisplayMetrics().densityDpi;
        boolean sameResources = hasSameIconResources(resources);
        boolean packAnimationsEnabled = SharedConfig.animationsEnabled();
        packAnimationsEnabled &= isAttachedToWindow()
                && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
        if (animate && state == currentState && usePack == (packDrawable != null)
                && selection == lastPackSelection && generation == lastPackGeneration && densityDpi == lastDensityDpi
                && sameResources && (packAnimator == null || packAnimationsEnabled)) {
            return;
        }
        boolean samePack = selection == lastPackSelection && generation == lastPackGeneration
                && sameResources;
        lastPackSelection = selection;
        lastPackGeneration = generation;
        lastDensityDpi = densityDpi;
        lastDensity = resources.getDisplayMetrics().density;
        lastResources = resources;
        if (!sameResources) {
            lastConfiguration = new Configuration(resources.getConfiguration());
        }
        State fromState = currentState;
        currentState = state;
        updateContentDescription(state);
        cancelPendingAnimation();
        if (!sameResources) {
            stateMap.clear();
        }
        if (usePack) {
            FixedSizeIconDrawable previous = samePack ? packDrawable : null;
            stopAnimation();
            clearAnimationDrawable();
            setScaleType(ScaleType.CENTER_INSIDE);
            int iconSize = (int) Math.ceil(Math.min(sizeDp, 24) * resources.getDisplayMetrics().density);
            TransitState transitState = getState(fromState, state);
            if (animate && packAnimationsEnabled && previous != null && transitState != null) {
                if (packTransition == null) {
                    packTransition = new PackTransitionDrawable(fromState, previous);
                }
                PackTransitionDrawable transition = packTransition;
                FixedSizeIconDrawable next = transition.icons[state.ordinal()];
                if (next == null) {
                    next = new FixedSizeIconDrawable(loadComposerIcon(getContext(), iconId, resourcesProvider), iconSize);
                }
                packDrawable = next;
                float end = transition.retarget(fromState, state, next);
                setImageDrawable(transition);
                packAnimator = ValueAnimator.ofFloat(transition.progress, end);
                packAnimator.setInterpolator(new LinearInterpolator());
                packAnimator.setDuration(Math.max(1, Math.round(transitState.duration * Math.abs(end - transition.progress))));
                packAnimator.addUpdateListener(animation -> {
                    transition.setProgress((float) animation.getAnimatedValue());
                });
                packAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (packAnimator != animation) {
                            return;
                        }
                        packAnimator = null;
                        packTransition = null;
                        packDrawable.setAlpha(255);
                        setImageDrawable(packDrawable);
                    }
                });
                packAnimator.start();
            } else {
                packTransition = null;
                packDrawable = new FixedSizeIconDrawable(loadComposerIcon(getContext(), iconId, resourcesProvider), iconSize);
                setImageDrawable(packDrawable);
            }
            return;
        }
        packDrawable = null;
        packTransition = null;
        setScaleType(ScaleType.FIT_CENTER);
        if (!animate || fromState == null || getState(fromState, currentState) == null) {
            RLottieDrawable drawable = stateMap.get(getAnyState(currentState));
            drawable.stop();

            drawable.setProgress(state == State.VOICE ? 0.5f : 0, false);
            setAnimation(drawable);
        } else {
            TransitState transitState = getState(fromState, currentState);
            if (transitState == animatingState) {
                return;
            }

            animatingState = transitState;
            RLottieDrawable drawable = stateMap.get(transitState);
            drawable.stop();
            if (transitState == TransitState.VIDEO_TO_VOICE) {
                drawable.setCustomEndFrame(30);
                drawable.setProgress(0, false);
            } else if (transitState == TransitState.VOICE_TO_VIDEO) {
                drawable.setCustomEndFrame(60);
                drawable.setProgress(0.5f, false);
            } else {
                drawable.setProgress(0, false);
            }
            drawable.setAutoRepeat(0);
            drawable.setOnAnimationEndListener(() -> animatingState = null);
            setAnimation(drawable);
            pendingLottieStart = () -> {
                pendingLottieStart = null;
                if (getAnimatedDrawable() == drawable && packDrawable == null) {
                    drawable.start();
                }
            };
            AndroidUtilities.runOnUIThread(pendingLottieStart);
        }
    }

    private void updateContentDescription(State state) {

        switch (state) {
            case VOICE:
                setContentDescription(LocaleController.getString(R.string.AccDescrVoiceMessage));
                break;
            case VIDEO:
                setContentDescription(LocaleController.getString(R.string.AccDescrVideoMessage));
                break;
        }
    }

    private static int getStaticIcon(State state) {
        switch (state) {
            case VOICE: return R.drawable.input_mic;
            case VIDEO: return R.drawable.input_video;
            case SMILE: return R.drawable.input_smile;
            case KEYBOARD: return R.drawable.input_keyboard;
            case STICKER: return R.drawable.msg_sticker;
            case GIF: return R.drawable.msg_gif;
            default: throw new IllegalArgumentException();
        }
    }

    private void cancelPendingAnimation() {
        if (pendingLottieStart != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingLottieStart);
            pendingLottieStart = null;
        }
        if (packAnimator != null) {
            packAnimator.removeAllListeners();
            packAnimator.removeAllUpdateListeners();
            packAnimator.cancel();
            packAnimator = null;
        }
        RLottieDrawable drawable = getAnimatedDrawable();
        if (drawable != null) {
            drawable.setOnAnimationEndListener(null);
            drawable.stop();
        }
        animatingState = null;
    }

    private boolean hasSameIconResources(Resources resources) {
        return resources == lastResources
                && resources.getDisplayMetrics().densityDpi == lastDensityDpi
                && resources.getDisplayMetrics().density == lastDensity
                && resources.getConfiguration().equals(lastConfiguration);
    }

    private void refreshIconResources() {
        if (currentState == null) {
            return;
        }
        Resources resources = getContext().getResources();
        NimarkoIconResources icons = resources instanceof NimarkoIconResources ? (NimarkoIconResources) resources : null;
        int selection = icons == null ? NimarkoConfig.ICON_REPLACE_NONE : icons.getAppliedSelection();
        long generation = icons == null ? -1L : icons.getReplacementGeneration();
        boolean usePack = icons != null && icons.hasReplacement(getStaticIcon(currentState));
        if (selection != lastPackSelection || generation != lastPackGeneration
                || usePack != (packDrawable != null) || !hasSameIconResources(resources)) {
            setState(currentState, false);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshIconResources();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        refreshIconResources();
        if (packAnimator != null && (!SharedConfig.animationsEnabled()
                || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()))) {
            setState(getCurrentState(), false);
        }
        super.onDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        State desired = getCurrentState();
        cancelPendingAnimation();
        if (desired != null && packDrawable != null) {
            setState(desired, false);
        }
        super.onDetachedFromWindow();
    }
    public State getCurrentState() {
        return currentState;
    }

    public static final float LIQUID_SEND_ARTWORK_FRACTION = 32f / 38f;

    static Drawable wrapComposerIcon(Context context, int resourceId, Drawable drawable) {
        Resources resources = context.getResources();
        if (drawable == null || !(resources instanceof NimarkoIconResources)
                || !((NimarkoIconResources) resources).hasReplacement(resourceId)) {
            return drawable;
        }
        int selection = ((NimarkoIconResources) resources).getAppliedSelection();
        boolean filledPack = selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS
                || selection == NimarkoConfig.ICON_REPLACE_PLUMPY;
        boolean notification = resourceId == R.drawable.input_notify_on || resourceId == R.drawable.input_notify_off;
        float sizeDp = notification && filledPack ? 22 : 24;
        if (resourceId == R.drawable.input_bot1 || resourceId == R.drawable.input_bot2) {
            if (selection == NimarkoConfig.ICON_REPLACE_SOLAR) {
                sizeDp = 23;
            } else if (selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS
                    && resourceId == R.drawable.input_bot1) {
                sizeDp = 26.5f;
            } else if (selection == NimarkoConfig.ICON_REPLACE_PLUMPY
                    && resourceId == R.drawable.input_bot2) {
                sizeDp = 26;
            }
        }
        int iconSize = (int) Math.ceil(sizeDp * resources.getDisplayMetrics().density);
        return new FixedSizeIconDrawable(drawable, iconSize);
    }

    static Drawable loadComposerIcon(Context context, int resourceId, Theme.ResourcesProvider resourcesProvider) {
        Resources resources = context.getResources();
        Drawable drawable = resources.getDrawable(resourceId).mutate();
        if (resourcesProvider != null && resources instanceof NimarkoIconResources
                && ((NimarkoIconResources) resources).hasReplacement(resourceId)) {
            Drawable copy = NimarkoIconResources.copyDrawable(drawable, resources, resourcesProvider::isDark);
            if (copy != null) {
                drawable = copy;
            }
        }
        if ((resourceId == R.drawable.input_done || resourceId == R.drawable.attach_send)
                && resources instanceof NimarkoIconResources
                && ((NimarkoIconResources) resources).hasReplacement(resourceId)) {
            int size = drawable.getIntrinsicWidth();
            if (size > 0 && size == drawable.getIntrinsicHeight()) {
                if (resourceId == R.drawable.input_done) {
                    float opticalScale = Math.min(1f, (float) Math.ceil(16 * resources.getDisplayMetrics().density) / size);
                    drawable = new FixedSizeIconDrawable(drawable, size, opticalScale);
                } else if (resourceId == R.drawable.attach_send
                        && ((NimarkoIconResources) resources).hasReplacement(R.drawable.ic_send)) {
                    Drawable glyph = loadComposerIcon(context, R.drawable.ic_send, resourcesProvider);
                    float artworkDp = ((NimarkoIconResources) resources).getAppliedSelection()
                            == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS ? 32f : 24f;
                    float opticalScale = Math.min(1f, (float) Math.ceil(artworkDp * resources.getDisplayMetrics().density) / size);
                    drawable = new FixedSizeIconDrawable(glyph, size, opticalScale);
                }
            }
        }
        return drawable;
    }

    static void fitComposerIconBounds(Context context, int resourceId, Rect bounds) {
        Resources resources = context.getResources();
        if (!(resources instanceof NimarkoIconResources)
                || !((NimarkoIconResources) resources).hasReplacement(resourceId)) {
            return;
        }
        int size = Math.min(Math.min(bounds.width(), bounds.height()),
                (int) Math.ceil(24 * resources.getDisplayMetrics().density));
        int left = bounds.left + (bounds.width() - size) / 2;
        int top = bounds.top + (bounds.height() - size) / 2;
        bounds.set(left, top, left + size, top + size);
    }

    private static final class PackTransitionDrawable extends Drawable implements Drawable.Callback {
        private static final CubicBezierInterpolator EASING = new CubicBezierInterpolator(.6f, 0f, .4f, 1f);
        private final FixedSizeIconDrawable[] icons = new FixedSizeIconDrawable[State.values().length];
        private final float[] alphas = new float[icons.length];
        private final float[] scales = new float[icons.length];
        private final float[] startAlphas = new float[icons.length];
        private final float[] startScales = new float[icons.length];
        private final int size;
        private State origin;
        private State destination;
        private boolean reversible = true;
        private float progress;
        private int alpha = 255;

        private PackTransitionDrawable(State state, FixedSizeIconDrawable icon) {
            size = icon.getIntrinsicWidth();
            origin = state;
            icons[state.ordinal()] = icon;
            alphas[state.ordinal()] = 1f;
            for (int i = 0; i < scales.length; i++) {
                scales[i] = 1f;
            }
            icon.setCallback(this);
        }

        private float retarget(State from, State to, FixedSizeIconDrawable icon) {
            if (destination != null && reversible && (to == origin || to == destination)) {
                return to == origin ? 0f : 1f;
            }
            if (destination != null) {
                reversible = false;
            }
            origin = from;
            destination = to;
            icons[to.ordinal()] = icon;
            icon.setCallback(this);
            icon.setBounds(getBounds());
            System.arraycopy(alphas, 0, startAlphas, 0, alphas.length);
            System.arraycopy(scales, 0, startScales, 0, scales.length);
            progress = 0f;
            return 1f;
        }

        private static float ease(float value) {
            return EASING.getInterpolation(Math.max(0f, Math.min(1f, value)));
        }

        private void setProgress(float value) {
            progress = value;
            boolean voice = origin == State.VOICE || origin == State.VIDEO;
            float t = ease(value * (voice ? 1f : 12f / 10f));
            for (int i = 0; i < icons.length; i++) {
                boolean incoming = i == destination.ordinal();
                alphas[i] = startAlphas[i] + ((incoming ? 1f : 0f) - startAlphas[i]) * t;
                scales[i] = startScales[i] + (1f - startScales[i]) * t;
            }
            if (reversible && (origin == State.GIF || destination == State.GIF)) {
                int gif = State.GIF.ordinal();
                boolean incoming = destination == State.GIF;
                alphas[gif] = incoming ? ease((value * 12f - 4f) / 6f) : 1f - ease(value * 12f / 6f);
                scales[gif] = incoming ? .5f + .5f * t : 1f - .5f * t;
                if (origin == State.SMILE || destination == State.SMILE) {
                    int smile = State.SMILE.ordinal();
                    alphas[smile] = incoming ? 1f - ease(value * 12f / 6f) : ease((value * 12f - 4f) / 6f);
                    scales[smile] = incoming ? 1f - .5f * t : .5f + .5f * t;
                }
            }
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            for (int i = 0; i < icons.length; i++) {
                if (icons[i] == null || alphas[i] <= 0f) {
                    continue;
                }
                int save = canvas.save();
                canvas.scale(scales[i], scales[i], bounds.exactCenterX(), bounds.exactCenterY());
                icons[i].setAlpha(Math.round(alpha * alphas[i]));
                icons[i].draw(canvas);
                canvas.restoreToCount(save);
            }
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            for (FixedSizeIconDrawable icon : icons) {
                if (icon != null) {
                    icon.setBounds(bounds);
                }
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            for (FixedSizeIconDrawable icon : icons) {
                if (icon != null) {
                    icon.setColorFilter(filter);
                }
            }
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
    }

    private static final class FixedSizeIconDrawable extends Drawable implements Drawable.Callback {
        private final Drawable icon;
        private final int size;
        private final float opticalScale;

        private FixedSizeIconDrawable(Drawable icon, int size) {
            this(icon, size, 1f);
        }

        private FixedSizeIconDrawable(Drawable icon, int size, float opticalScale) {
            this.icon = icon;
            this.size = size;
            this.opticalScale = opticalScale;
            icon.setCallback(this);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            int width = icon.getIntrinsicWidth();
            int height = icon.getIntrinsicHeight();
            if (width <= 0 || height <= 0) {
                icon.setBounds(bounds);
                return;
            }
            float scale = Math.min((float) bounds.width() / width, (float) bounds.height() / height);
            int fittedWidth = Math.round(width * scale);
            int fittedHeight = Math.round(height * scale);
            int left = bounds.left + (bounds.width() - fittedWidth) / 2;
            int top = bounds.top + (bounds.height() - fittedHeight) / 2;
            icon.setBounds(left, top, left + fittedWidth, top + fittedHeight);
        }

        @Override
        public void draw(Canvas canvas) {
            if (opticalScale == 1f) {
                icon.draw(canvas);
                return;
            }
            Rect bounds = getBounds();
            int save = canvas.save();
            canvas.clipRect(bounds);
            canvas.scale(opticalScale, opticalScale, bounds.exactCenterX(), bounds.exactCenterY());
            icon.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            icon.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            icon.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
    }

    private TransitState getAnyState(State from) {
        for (TransitState transitState : TransitState.values()) {
            if (transitState.firstState == from) {
                return transitState;
            }
        }
        return null;
    }

    private TransitState getState(State from, State to) {
        for (TransitState transitState : TransitState.values()) {
            if (transitState.firstState == from && transitState.secondState == to) {
                return transitState;
            }
        }
        return null;
    }

    private enum TransitState {
        VOICE_TO_VIDEO(State.VOICE, State.VIDEO, R.raw.voice_and_video),
        STICKER_TO_KEYBOARD(State.STICKER, State.KEYBOARD, R.raw.sticker_to_keyboard),
        SMILE_TO_KEYBOARD(State.SMILE, State.KEYBOARD, R.raw.smile_to_keyboard),
        VIDEO_TO_VOICE(State.VIDEO, State.VOICE, R.raw.voice_and_video),
        KEYBOARD_TO_STICKER(State.KEYBOARD, State.STICKER, R.raw.keyboard_to_sticker),
        KEYBOARD_TO_GIF(State.KEYBOARD, State.GIF, R.raw.keyboard_to_gif),
        KEYBOARD_TO_SMILE(State.KEYBOARD, State.SMILE, R.raw.keyboard_to_smile),
        GIF_TO_KEYBOARD(State.GIF, State.KEYBOARD, R.raw.gif_to_keyboard),
        GIF_TO_SMILE(State.GIF, State.SMILE, R.raw.gif_to_smile),
        SMILE_TO_GIF(State.SMILE, State.GIF, R.raw.smile_to_gif),
        SMILE_TO_STICKER(State.SMILE, State.STICKER, R.raw.smile_to_sticker),
        STICKER_TO_SMILE(State.STICKER, State.SMILE, R.raw.sticker_to_smile);

        final State firstState, secondState;
        final int resource;

        final long duration;
        TransitState(State firstState, State secondState, int resource) {
            this.firstState = firstState;
            this.secondState = secondState;
            this.resource = resource;
            duration = resource == R.raw.voice_and_video ? 500 : 200;
        }
    }

    public enum State {
        VOICE,
        VIDEO,
        STICKER,
        KEYBOARD,
        SMILE,
        GIF
    }
}
