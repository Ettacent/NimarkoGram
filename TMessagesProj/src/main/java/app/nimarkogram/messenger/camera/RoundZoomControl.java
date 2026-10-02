/* Modifications Copyright (C) 2026 Ettacent */

/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.camera;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import android.graphics.Rect;
import android.view.KeyEvent;
import java.util.List;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

import app.nimarkogram.messenger.NimarkoConfig;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

@SuppressLint("ViewConstructor")
public final class RoundZoomControl extends View {
    public interface Listener {
        void onZoom(float ratio, boolean preset);
        default void onGestureStart() {}
        default void onGestureEnd() {}
    }
    private final Theme.ResourcesProvider resourcesProvider;
    private final Listener listener;
    private Object session;
    private boolean presented, interactionReady, directGesture, pendingPreset;
    private boolean rangeInitialized;
    private float hostAlpha, requested = 1f;
    private float presentationProgress = 1f;
    private ValueAnimator presentationAnimator;
    private int pointerId = -1, cachedTenths = Integer.MIN_VALUE;
    private long lastSoftHaptic = -1000L;
    private long lastMajorHaptic = -1000L;
    private float compactScroll;
    private boolean scrollingShortcuts;
    private final ZoomAccessibility accessibility;
    private String cachedBubble = "1×";
    private String[] toggleLabels = new String[0];
    private float fadeLeft = Float.NaN, fadeRight = Float.NaN;
    private float rangeProgress = 1f;
    private ValueAnimator rangeAnimator;
    private android.graphics.Bitmap oldRangeContent;
    private final Paint rangePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rangeBlendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contentClip = new Path();
    private static final PorterDuffXfermode RANGE_ADD_XFERMODE = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    private static final double LOG_2 = Math.log(2.0);
    private static final TimeInterpolator MORPH_INTERPOLATOR = new CubicBezierInterpolator(0.2, 0, 0, 1);
    private static final PorterDuffXfermode DST_IN_XFERMODE = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);

    private static final int TOGGLE_SIZE_DP_DEFAULT = 48;
    private static final int EXPANDED_BACKGROUND_WIDTH_DP = 224;

    private static final long AUTO_COLLAPSE_DELAY = 1500L;
    private static final float RESIZE_STIFFNESS = 900f;

    private static final FloatPropertyCompat<RoundZoomControl> CONTROL_WIDTH = new FloatPropertyCompat<>("controlWidth") {
        @Override
        public float getValue(RoundZoomControl view) {
            return view.animatedControlWidth;
        }

        @Override
        public void setValue(RoundZoomControl view, float value) {
            view.animatedControlWidth = Math.max(0f, value);
            view.invalidate();
        }
    };

    private static final FloatPropertyCompat<RoundZoomControl> SELECTOR_OFFSET = new FloatPropertyCompat<>("selectorOffset") {
        @Override
        public float getValue(RoundZoomControl view) {
            return view.animatedSelectorOffset;
        }

        @Override
        public void setValue(RoundZoomControl view, float value) {
            view.animatedSelectorOffset = value;
            view.invalidate();
        }
    };

    private static final FloatPropertyCompat<RoundZoomControl> EXPANDED_PROGRESS = new FloatPropertyCompat<>("expandedProgress") {
        @Override
        public float getValue(RoundZoomControl view) {
            return view.expandedProgress;
        }

        @Override
        public void setValue(RoundZoomControl view, float value) {
            view.expandedProgress = clamp(value, 0f, 1f);
            view.invalidate();
        }
    };

    private static final FloatPropertyCompat<RoundZoomControl> COMPACT_SCROLL = new FloatPropertyCompat<>("compactScroll") {
        @Override
        public float getValue(RoundZoomControl view) {
            return view.compactScroll;
        }

        @Override
        public void setValue(RoundZoomControl view, float value) {
            view.compactScroll = clamp(value, 0f, Math.max(0f, view.getCompactWidth() - view.compactBounds.width()));
            view.invalidate();
        }
    };

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint toggleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint selectedToggleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint edgeFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Drawable protectionBackgroundDrawable;
    private BlurredBackgroundDrawableViewFactory liquidGlassFactory;
    private BlurredBackgroundColorProvider liquidGlassColorProvider;

    private final RectF controlBounds = new RectF();
    private final RectF compactBounds = new RectF();
    private final RectF rulerBounds = new RectF();
    private final RectF compactTouchBounds = new RectF();
    private final RectF rulerTouchBounds = new RectF();
    private final RectF selectorBounds = new RectF();
    private final RectF oldRangeBounds = new RectF();
    private final RectF oldSelectorBounds = new RectF();
    private float oldSelectorAlpha;

    private float toggleCellWidth = dp(TOGGLE_SIZE_DP_DEFAULT);
    private float maximumReadoutWidth;

    private final SparseArray<String> primaryLabels = new SparseArray<>();
    private final int touchSlop;
    private final SpringAnimation widthSpring;
    private final SpringAnimation expandedSpring;
    private final SpringAnimation selectorSpring;
    private final SpringAnimation compactScrollSpring;

    private float minZoom = 0.5f;
    private float maxZoom = 30f;
    private float zoom = 1f;
    private float[] toggleStops = {0.5f, 1f, 2f, 5f};
    private float[] rulerStops = {0.5f, 1f, 2f, 5f, 10f, 30f};
    private int[] primaryTickIndices = new int[0];
    private int oneXTick = -1;
    private int intervalCount;
    private float tickSpacing;
    private float displayNormalizationFactor = 1f;

    private int protectionBackgroundColor;
    private int primaryColor;
    private int minorTickColor;
    private int secondaryFixedColor;
    private int onSecondaryFixedColor;
    private int unselectedToggleColor;
    private int markerColor;
    private int protectionScrimColor;
    private int cachedBackground, cachedForeground, cachedAccent;
    private boolean colorsConfigured;

    private float animatedControlWidth;
    private float animatedSelectorOffset;
    private boolean geometryPresented;
    private float expandedProgress;
    private boolean expanded;

    private int selectedToggleIndex;
    private boolean selectedShowsStopValue;

    private boolean dragging;
    private boolean compactGestureDown;
    private boolean dragStartedFromCompact;
    private boolean movedPastSlop;
    private int pressedToggleIndex = -1;
    private float downX;
    private float downY;
    private float lastTouchX;
    private float dragTick;
    private int lastHapticTick = Integer.MIN_VALUE;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!compactGestureDown || movedPastSlop || expanded) return;
            performHapticSafe(HapticFeedbackConstants.CONTEXT_CLICK);
            dragStartedFromCompact = true;
            beginDrag(downX);
            setExpanded(true, true);
        }
    };

    private final Runnable autoCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            if (expanded && !dragging && !isFocused() && !isAccessibilityFocused()) setExpanded(false, true);
        }
    };

    public RoundZoomControl(Context context, Theme.ResourcesProvider provider, Listener listener) {
        super(context);
        resourcesProvider = provider;
        this.listener = listener;
        accessibility = new ZoomAccessibility();
        ViewCompat.setAccessibilityDelegate(this, accessibility);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        tickSpacing = Math.max(1f, Math.round((float) dp(8)));
        widthSpring = createSpring(CONTROL_WIDTH, 1f, RESIZE_STIFFNESS, dp(0.1f));
        expandedSpring = createSpring(EXPANDED_PROGRESS, 1f, RESIZE_STIFFNESS, .001f);
        selectorSpring = createSpring(SELECTOR_OFFSET, 1f, 1400f, dp(0.1f));
        compactScrollSpring = createSpring(COMPACT_SCROLL, 1f, 1400f, dp(0.1f));
        configurePaints();
        minZoom = maxZoom = zoom = 1f;
        toggleStops = rulerStops = new float[]{1f};
        rebuildScale();
        cacheLabels();
        selectedToggleIndex = 0;
        animatedControlWidth = getCompactBackgroundWidth();
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(LocaleController.getString(R.string.NM_CAM_RoundZoomScale));
        setEnabled(false);
        setColors(provider);

    }

    private void setColors(Theme.ResourcesProvider resourcesProvider) {
        int background = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), 255);
        int foreground = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelText, resourcesProvider), 255);
        int accent = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider), 255);
        if (colorsConfigured && cachedBackground == background && cachedForeground == foreground
                && cachedAccent == accent) return;
        colorsConfigured = true;
        cachedBackground = background;
        cachedForeground = foreground;
        cachedAccent = accent;
        protectionBackgroundColor = background;
        unselectedToggleColor = readableColor(foreground, background);
        primaryColor = unselectedToggleColor;
        secondaryFixedColor = ColorUtils.blendARGB(background, accent, .24f);
        onSecondaryFixedColor = readableColor(accent, secondaryFixedColor);
        protectionScrimColor = background;
        for (int alpha = 96; alpha < 255; alpha += 8) {
            int candidate = ColorUtils.setAlphaComponent(background, alpha);
            if (hasBackdropContrast(unselectedToggleColor, candidate, 4.5)) {
                protectionScrimColor = candidate;
                break;
            }
        }
        minorTickColor = unselectedToggleColor;
        for (float mix = .55f; mix < 1f; mix += .05f) {
            int candidate = ColorUtils.blendARGB(background, unselectedToggleColor, mix);
            if (hasBackdropContrast(candidate, protectionScrimColor, 3)) {
                minorTickColor = candidate;
                break;
            }
        }
        markerColor = hasBackdropContrast(accent, protectionScrimColor, 3) ? accent : unselectedToggleColor;
        tickPaint.setColor(minorTickColor);
        markerPaint.setColor(markerColor);
        rulerLabelPaint.setColor(primaryColor);
        selectorPaint.setColor(secondaryFixedColor);
    }

    public static int getControlForegroundColor(Theme.ResourcesProvider provider) {
        int background = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, provider), 255);
        int foreground = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelText, provider), 255);
        return readableColor(foreground, background);
    }

    public static int getControlBackgroundColor(Theme.ResourcesProvider provider, boolean blurred) {
        int background = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, provider), 255);
        if (blurred) {
            int foreground = getControlForegroundColor(provider);
            for (int alpha = 96; alpha < 255; alpha += 8) {
                int candidate = ColorUtils.setAlphaComponent(background, alpha);
                if (hasBackdropContrast(foreground, candidate, 4.5)) return candidate;
            }
        }
        return background;
    }

    public static BlurredBackgroundColorProvider createControlColorProvider(
            Theme.ResourcesProvider provider, BlurredBackgroundColorProvider base) {
        return new BlurredBackgroundColorProviderThemed(provider, Theme.key_chat_messagePanelBackground) {
            @Override
            public int getBackgroundColor() {
                boolean blurred = SharedConfig.chatBlurEnabled()
                        && base != null && Color.alpha(base.getBackgroundColor()) < 255;
                return getControlBackgroundColor(provider, blurred);
            }
        };
    }

    private static int readableColor(int foreground, int background) {
        if (ColorUtils.calculateContrast(foreground, background) >= 4.5) return foreground;
        return ColorUtils.calculateContrast(0xffffffff, background)
                >= ColorUtils.calculateContrast(0xff000000, background) ? 0xffffffff : 0xff000000;
    }

    private static boolean hasBackdropContrast(int foreground, int scrim, double minimum) {
        int light = ColorUtils.compositeColors(scrim, 0xffffffff);
        int dark = ColorUtils.compositeColors(scrim, 0xff000000);
        double luminance = ColorUtils.calculateLuminance(foreground);
        boolean outside = luminance <= ColorUtils.calculateLuminance(dark)
                || luminance >= ColorUtils.calculateLuminance(light);
        return outside && ColorUtils.calculateContrast(foreground, light) >= minimum
                && ColorUtils.calculateContrast(foreground, dark) >= minimum;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float zoomToTick(float value) {
        if (!RoundZoomScale.valid(minZoom, maxZoom)) return 0f;
        final float clamped = clamp(value, minZoom, maxZoom);
        if (clamped <= minZoom) return 0f;
        if (clamped >= maxZoom) return intervalCount;
        if (oneXTick >= 0) {
            if (clamped == displayNormalizationFactor) return oneXTick;
            if (clamped > displayNormalizationFactor) {
                final float progress = (float) (Math.log(clamped / displayNormalizationFactor) / Math.log(maxZoom / displayNormalizationFactor));
                return oneXTick + progress * (intervalCount - oneXTick);
            }
            return (float) (Math.log(clamped / minZoom) / Math.log(displayNormalizationFactor / minZoom)) * oneXTick;
        }
        return (float) (Math.log(clamped / minZoom) / Math.log(maxZoom / minZoom)) * intervalCount;
    }

    private float tickToZoom(float tick) {
        if (!RoundZoomScale.valid(minZoom, maxZoom)) return minZoom;
        final float clamped = clamp(tick, 0f, intervalCount);
        if (clamped <= 0f) return minZoom;
        if (clamped >= intervalCount) return maxZoom;
        if (oneXTick < 0) {
            return (float) (minZoom * Math.exp(Math.log(maxZoom / minZoom) * (clamped / intervalCount)));
        }
        if (clamped == oneXTick) return displayNormalizationFactor;
        if (clamped <= oneXTick) {
            return (float) (minZoom * Math.exp(Math.log(displayNormalizationFactor / minZoom) * (clamped / oneXTick)));
        }
        return (float) (displayNormalizationFactor * Math.exp(Math.log(maxZoom / displayNormalizationFactor) * ((clamped - oneXTick) / (float) (intervalCount - oneXTick))));
    }

    private void rebuildScale() {
        if (!RoundZoomScale.valid(minZoom, maxZoom)) {
            intervalCount = 0;
            oneXTick = -1;
            primaryLabels.clear();
            primaryTickIndices = new int[0];
            return;
        }
        final float octaves = (float) (Math.log(maxZoom / minZoom) / LOG_2);
        if (minZoom >= displayNormalizationFactor || maxZoom < displayNormalizationFactor) {
            oneXTick = -1;
            intervalCount = Math.max(1, Math.round(octaves * 5));
        } else {
            oneXTick = Math.max(3, Math.round((float) (Math.log(displayNormalizationFactor / minZoom) / LOG_2) * 5));
            intervalCount = oneXTick + (maxZoom > displayNormalizationFactor
                    ? Math.max(1, Math.round((float) (Math.log(maxZoom / displayNormalizationFactor) / LOG_2) * 5))
                    : 0
            );
        }
        primaryLabels.clear();
        final int[] indices = new int[rulerStops.length];
        int count = 0;
        for (float stop : rulerStops) {
            if (stop < minZoom || stop > maxZoom) continue;
            final int tick = Math.round(zoomToTick(stop));
            primaryLabels.put(tick, formatRuler(stop));
            boolean known = false;
            for (int i = 0; i < count; i++) {
                if (indices[i] == tick) {
                    known = true;
                    break;
                }
            }
            if (!known) indices[count++] = tick;
        }
        primaryTickIndices = Arrays.copyOf(indices, count);
        Arrays.sort(primaryTickIndices);
    }

    private int findToggleSegment(float value) {
        int nearest = -1;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < toggleStops.length; i++) {
            double next = Math.abs(Math.log(value / toggleStops[i]));
            if (next < distance) { nearest = i; distance = next; }
        }
        return nearest;
    }

    private int findToggleIndexAt(float x) {
        if (x < compactBounds.left || x > compactBounds.right || toggleStops.length == 0) return -1;
        float position = x - compactBounds.left + compactScroll;
        for (int i = 0; i < toggleStops.length; i++) {
            if (position < getToggleCellStart(i + 1)) return i;
        }
        return toggleStops.length - 1;
    }

    private float normalizeDisplayZoom(float value) {
        return Math.round(value / displayNormalizationFactor * 10f) / 10f;
    }

    private String formatZoomNumber(float value) {
        final float normalized = normalizeDisplayZoom(value);
        final Locale locale = Locale.getDefault();
        if (normalized % 1f == 0f) return String.format(locale, "%.0f", normalized);
        final String formatted = String.format(locale, "%.1f", normalized);
        return formatted;
    }

    private String formatBubble(float value) {
        return formatZoomNumber(value) + "×";
    }

    private String formatRuler(float value) {
        return formatZoomNumber(value);
    }

    private String formatToggle(float value) {
        return formatBubble(value);
    }

    private String getToggleLabel(int index) {
        if (index != selectedToggleIndex || selectedShowsStopValue) return toggleLabels[index];
        return cachedBubble;
    }

    private float getCompactWidth() {
        return toggleCellWidth * toggleStops.length;
    }

    private float getToggleCellStart(int index) {
        return index * toggleCellWidth;
    }

    private float getToggleCellCenter(int index) {
        return (getToggleCellStart(index) + getToggleCellStart(index + 1)) / 2f;
    }

    private float getCompactBackgroundWidth() {
        return getCompactWidth() + dp(8);
    }

    private float getExpandedBackgroundWidth() {
        return dp(EXPANDED_BACKGROUND_WIDTH_DP);
    }

    private float getSelectorOffset(int index) {
        return toggleCellWidth * Math.max(0, index);
    }

    private float centeredChildLeft(float available, float width) {
        return getPaddingLeft() + (available - width) / 2f;
    }

    private void updateLayoutBounds() {
        float available = Math.max(0f, getWidth() - getPaddingLeft() - getPaddingRight());
        float usable = Math.max(0f, available - dp(16));
        float bottom = getHeight() - getPaddingBottom() - dp(4);
        float top = bottom - getPanelHeight();
        float width = Math.min(usable, Math.max(dp(12), animatedControlWidth));
        float left = centeredChildLeft(available, width);
        controlBounds.set(left, top, left + width, bottom);
        float compactWidth = Math.min(getCompactWidth(), Math.max(0f, usable - dp(8)));
        float compactLeft = centeredChildLeft(available, compactWidth);
        compactBounds.set(compactLeft, top, compactLeft + compactWidth, bottom);
        compactScroll = clamp(compactScroll, 0f, Math.max(0f, getCompactWidth() - compactWidth));
        rulerBounds.set(left + dp(12), top, Math.max(left + dp(12), left + width - dp(12)), bottom);
        compactTouchBounds.set(compactBounds);
        rulerTouchBounds.set(controlBounds);
        float touchExtra = Math.max(0f, (dp(48) - controlBounds.height()) / 2f);
        compactTouchBounds.inset(0, -touchExtra);
        rulerTouchBounds.inset(0, -touchExtra);
    }

    private float getPanelHeight() {
        float compactHeight = getCompactPanelHeight();
        return compactHeight + (getExpandedPanelHeight() - compactHeight) * expandedProgress;
    }

    private float getCompactPanelHeight() {
        Paint.FontMetricsInt labels = selectedToggleTextPaint.getFontMetricsInt();
        return Math.max(dp(44), labels.descent - labels.ascent + dp(16));
    }

    private float getExpandedPanelHeight() {
        Paint.FontMetricsInt ruler = rulerLabelPaint.getFontMetricsInt();
        return Math.max(getCompactPanelHeight(), ruler.descent - ruler.ascent + dp(28));
    }

    public int getReservedHeight() {
        return (int) Math.ceil(getExpandedPanelHeight() + dp(8))
                + getPaddingTop() + getPaddingBottom();
    }

    private void measureShortcutCells() {
        float width = dp(TOGGLE_SIZE_DP_DEFAULT);
        for (String label : toggleLabels) width = Math.max(width, selectedToggleTextPaint.measureText(label) + dp(28));
        Locale locale = Locale.getDefault();
        String widestDigit = "0";
        float digitWidth = 0;
        for (int i = 0; i < 10; i++) {
            String digit = String.format(locale, "%d", i);
            float measured = selectedToggleTextPaint.measureText(digit);
            if (measured > digitWidth) {
                digitWidth = measured;
                widestDigit = digit;
            }
        }
        char separator = java.text.DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        String number = formatZoomNumber(maxZoom);
        int dot = number.indexOf(separator);
        int integerDigits = Math.max(2, dot >= 0 ? dot : number.length());
        StringBuilder readout = new StringBuilder();
        for (int i = 0; i < integerDigits; i++) readout.append(widestDigit);
        readout.append(separator).append(widestDigit).append('×');
        maximumReadoutWidth = selectedToggleTextPaint.measureText(readout.toString());
        toggleCellWidth = Math.max(width, maximumReadoutWidth + dp(16));
    }

    private void revealSelectedShortcut() {
        if (selectedToggleIndex < 0 || compactBounds.isEmpty()) return;
        float left = getToggleCellStart(selectedToggleIndex);
        float right = getToggleCellStart(selectedToggleIndex + 1);
        if (left < compactScroll) compactScroll = left;
        if (right > compactScroll + compactBounds.width()) compactScroll = right - compactBounds.width();
        compactScroll = clamp(compactScroll, 0, Math.max(0, getCompactWidth() - compactBounds.width()));
    }

    private SpringAnimation createSpring(
            FloatPropertyCompat<RoundZoomControl> property,
            float damping, float stiffness, float minimumVisibleChange
    ) {
        final SpringAnimation animation = new SpringAnimation(this, property);
        animation.setSpring(new SpringForce().setDampingRatio(damping).setStiffness(stiffness));
        animation.setMinimumVisibleChange(minimumVisibleChange);
        return animation;
    }

    private void configurePaints() {
        backgroundPaint.setStyle(Paint.Style.FILL);
        selectorPaint.setStyle(Paint.Style.FILL);

        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        markerPaint.setStrokeCap(Paint.Cap.ROUND);

        toggleTextPaint.setTextAlign(Paint.Align.CENTER);
        toggleTextPaint.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
        toggleTextPaint.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));

        selectedToggleTextPaint.setTextAlign(Paint.Align.CENTER);
        selectedToggleTextPaint.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
        selectedToggleTextPaint.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));

        rulerLabelPaint.setTextAlign(Paint.Align.CENTER);
        rulerLabelPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        rulerLabelPaint.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));

    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        setColors(resourcesProvider);
        updateBlurAlpha();
        updateLayoutBounds();
        if (!geometryPresented) {
            selectorSpring.cancel();
            animatedSelectorOffset = getSelectorOffset(selectedToggleIndex);
            geometryPresented = true;
        }

        if (!controlBounds.isEmpty()) {
            drawProtectionBackground(canvas);
        }

        drawSelector(canvas);
        drawRangeContents(canvas);
    }

    private void releaseOldRange() {
        if (oldRangeContent != null) {
            oldRangeContent.recycle();
            oldRangeContent = null;
        }
        oldRangeBounds.setEmpty();
        oldSelectorBounds.setEmpty();
        oldSelectorAlpha = 0f;
    }

    private void drawRangeContents(Canvas canvas) {
        int save = canvas.save();
        contentClip.rewind();
        contentClip.addRoundRect(controlBounds, controlBounds.height() / 2f,
                controlBounds.height() / 2f, Path.Direction.CW);
        canvas.clipPath(contentClip);
        drawRangeLayers(canvas);
        canvas.restoreToCount(save);
    }

    private void drawRangeLayers(Canvas canvas) {
        if (oldRangeContent != null && rangeProgress < 1f) {
            int composite = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            rangePaint.setAlpha(Math.round(255f * (1f - rangeProgress)));
            int outgoing = canvas.save();
            canvas.translate(controlBounds.centerX(), controlBounds.centerY());
            canvas.translate(-oldRangeBounds.centerX(), -oldRangeBounds.centerY());
            canvas.drawBitmap(oldRangeContent, 0f, 0f, rangePaint);
            canvas.restoreToCount(outgoing);
            if (rangeProgress > 0f) {
                rangeBlendPaint.setAlpha(255 - rangePaint.getAlpha());
                rangeBlendPaint.setXfermode(RANGE_ADD_XFERMODE);
                int incoming = canvas.saveLayer(0, 0, getWidth(), getHeight(), rangeBlendPaint);
                drawContents(canvas);
                canvas.restoreToCount(incoming);
            }
            canvas.restoreToCount(composite);
            return;
        }
        drawContents(canvas);
    }

    private void finishRangeTransition() {
        if (rangeAnimator != null) {
            rangeAnimator.cancel();
        }
        rangeAnimator = null;
        rangeProgress = 1f;
        releaseOldRange();
        updateReady();
    }

    private void drawContents(Canvas canvas) {
        final float compactAlpha = 1f - expandedProgress;
        final float expandedAlpha = expandedProgress;
        final int layer = canvas.save();

        if (compactAlpha > 0.001f) {
            final int save = compactAlpha < 1f ? canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), Math.round(compactAlpha * 255f)) : canvas.save();
            drawToggleRow(canvas);
            canvas.restoreToCount(save);
        }

        if (expandedAlpha > 0.001f) {
            final int save = expandedAlpha < 1f ? canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), Math.round(expandedAlpha * 255f)) : canvas.save();
            drawRuler(canvas);
            canvas.restoreToCount(save);
        }
        canvas.restoreToCount(layer);
    }

    private void createZoomButtonBackgrounds() {
        if (liquidGlassFactory == null || !isAttachedToWindow() || protectionBackgroundDrawable != null) return;
        BlurredBackgroundDrawable plate = liquidGlassFactory.create(this, liquidGlassColorProvider);
        configureGlassMaterial(plate);
        plate.setRadius(getPanelHeight() / 2f);
        plate.setCallback(this);
        protectionBackgroundDrawable = plate;
        updateBlurAlpha();
        invalidate();
    }

    private void drawProtectionBackground(Canvas canvas) {
        if (protectionBackgroundDrawable != null) {
            ((BlurredBackgroundDrawable) protectionBackgroundDrawable).setRadius(controlBounds.height() / 2f);
            protectionBackgroundDrawable.setBounds(
                    Math.round(controlBounds.left) - dp(6), Math.round(controlBounds.top) - dp(6),
                    Math.round(controlBounds.right) + dp(6), Math.round(controlBounds.bottom) + dp(6)
            );
            protectionBackgroundDrawable.draw(canvas);
        } else {
            backgroundPaint.setColor(protectionBackgroundColor);
            canvas.drawRoundRect(controlBounds, controlBounds.height() / 2f, controlBounds.height() / 2f, backgroundPaint);
        }
    }

    public static void configureGlassMaterial(BlurredBackgroundDrawable drawable) {
        drawable.setPadding(dp(6));
        drawable.setIntensity(drawable.getIntensity() * .75f);
        drawable.setStrokeWidth(dp(.5f), dp(.5f));
        drawable.setShadowAlpha(.35f);
    }

    public void drawControlsBackgroundFallback(Canvas canvas, int width, int height) {
        int inset = dp(6);
        if (width <= inset * 2 || height <= inset * 2) return;
        setColors(resourcesProvider);
        backgroundPaint.setColor(protectionBackgroundColor);
        float radius = (height - inset * 2) / 2f;
        canvas.drawRoundRect(inset, inset, width - inset, height - inset,
                radius, radius, backgroundPaint);
    }

    private void drawToggleRow(Canvas canvas) {
        if (toggleStops.length == 0 || compactBounds.isEmpty()) return;
        int save = canvas.save();
        canvas.clipRect(compactBounds);
        drawToggleLabels(canvas);
        canvas.restoreToCount(save);
    }

    private float updateSelectorBounds() {
        selectorBounds.setEmpty();
        float alpha = 0f;
        if (selectedToggleIndex >= 0 && toggleStops.length > 0 && !compactBounds.isEmpty()) {
            float width = Math.max(dp(36), Math.min(toggleCellWidth - dp(8), maximumReadoutWidth + dp(12)));
            Paint.FontMetricsInt metrics = selectedToggleTextPaint.getFontMetricsInt();
            float height = Math.max(dp(32), metrics.descent - metrics.ascent + dp(8));
            float offset = clamp(animatedSelectorOffset, 0f, toggleCellWidth * (toggleStops.length - 1));
            float center = compactBounds.left + offset - compactScroll + toggleCellWidth / 2f;
            float cy = compactBounds.centerY();
            selectorBounds.set(center - width / 2f, cy - height / 2f,
                    center + width / 2f, cy + height / 2f);
            alpha = 1f - expandedProgress;
        }
        if (oldRangeContent != null && rangeProgress < 1f && !oldSelectorBounds.isEmpty()) {
            float p = clamp(rangeProgress, 0f, 1f);
            float cx = controlBounds.centerX(), cy = controlBounds.centerY();
            if (selectorBounds.isEmpty()) selectorBounds.set(
                    cx + oldSelectorBounds.left, cy + oldSelectorBounds.top,
                    cx + oldSelectorBounds.right, cy + oldSelectorBounds.bottom);
            selectorBounds.set(
                    (cx + oldSelectorBounds.left) * (1f - p) + selectorBounds.left * p,
                    (cy + oldSelectorBounds.top) * (1f - p) + selectorBounds.top * p,
                    (cx + oldSelectorBounds.right) * (1f - p) + selectorBounds.right * p,
                    (cy + oldSelectorBounds.bottom) * (1f - p) + selectorBounds.bottom * p);
            alpha = oldSelectorAlpha * (1f - p) + alpha * p;
        }
        return clamp(alpha, 0f, 1f);
    }

    private void drawSelector(Canvas canvas) {
        float alpha = updateSelectorBounds();
        if (alpha <= 0f || selectorBounds.isEmpty()) return;
        int save = canvas.save();
        contentClip.rewind();
        contentClip.addRoundRect(controlBounds, controlBounds.height() / 2f,
                controlBounds.height() / 2f, Path.Direction.CW);
        canvas.clipPath(contentClip);
        selectorPaint.setColor(secondaryFixedColor);
        selectorPaint.setAlpha(Math.round(Color.alpha(secondaryFixedColor) * alpha));
        float radius = selectorBounds.height() / 2f;
        canvas.drawRoundRect(selectorBounds, radius, radius, selectorPaint);
        canvas.restoreToCount(save);
    }

    private void drawToggleLabels(Canvas canvas) {
        for (int i = 0; i < toggleStops.length; i++) {
            float highlight = selectedToggleIndex < 0 ? 0f
                    : clamp(1f - Math.abs(animatedSelectorOffset - getSelectorOffset(i)) / toggleCellWidth, 0f, 1f);
            Paint paint = toggleTextPaint;
            paint.setColor(ColorUtils.blendARGB(unselectedToggleColor, onSecondaryFixedColor, highlight));
            float left = compactBounds.left + getToggleCellStart(i) - compactScroll;
            float right = compactBounds.left + getToggleCellStart(i + 1) - compactScroll;
            float cx = (left + right) / 2f;
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            float baseline = compactBounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
            int save = canvas.save();
            canvas.clipRect(left, compactBounds.top, right, compactBounds.bottom);
            canvas.drawText(getToggleLabel(i), cx, baseline, paint);
            canvas.restoreToCount(save);
        }
    }

    private void drawRuler(Canvas canvas) {
        if (rulerBounds.isEmpty()) return;
        final int layer = canvas.saveLayer(rulerBounds.left, controlBounds.top, rulerBounds.right, controlBounds.bottom, null);
        canvas.clipRect(rulerBounds.left, controlBounds.top, rulerBounds.right, controlBounds.bottom);

        final float centerX = rulerBounds.centerX();
        final float currentTick = zoomToTick(zoom);
        final Paint.FontMetricsInt labelMetrics = rulerLabelPaint.getFontMetricsInt();
        final float ticksBottom = controlBounds.bottom - dp(10) - (labelMetrics.descent - labelMetrics.ascent);
        final float markerBottom = ticksBottom;
        final float halfTicks = (rulerBounds.width() / 2f) / tickSpacing;

        int from = Math.max(0, (int) Math.floor(currentTick - halfTicks) - 1);
        final int to = Math.min(intervalCount, (int) Math.ceil(currentTick + halfTicks) + 1);
        final Paint.FontMetricsInt metrics = rulerLabelPaint.getFontMetricsInt();
        final float labelBaseline = controlBounds.bottom - dp(4f) - (metrics.descent - metrics.ascent) - metrics.ascent;

        float lastLabelRight = -Float.MAX_VALUE;
        for (; from <= to; from++) {
            final float x = centerX + (from - currentTick) * tickSpacing;
            final String label = primaryLabels.get(from);
            final boolean primary = label != null;
            tickPaint.setColor(primary ? primaryColor : minorTickColor);
            tickPaint.setStrokeWidth(dp(1f));
            canvas.drawLine(x, ticksBottom - dp(primary ? 12f : 6f), x, ticksBottom, tickPaint);
            if (primary && x - rulerLabelPaint.measureText(label) / 2f >= lastLabelRight + dp(8)) {
                lastLabelRight = x + rulerLabelPaint.measureText(label) / 2f;
                rulerLabelPaint.setColor(primaryColor);
                float clearance = Math.min(x - rulerBounds.left, rulerBounds.right - x)
                        - rulerLabelPaint.measureText(label) / 2f;
                rulerLabelPaint.setAlpha(Math.round(android.graphics.Color.alpha(primaryColor)
                        * clamp(clearance / dp(8), 0f, 1f)));
                canvas.drawText(label, x, labelBaseline, rulerLabelPaint);
            }
        }

        markerPaint.setColor(markerColor);
        markerPaint.setStrokeWidth(dp(4f));
        canvas.drawLine(centerX, markerBottom - dp(12f), centerX, markerBottom, markerPaint);

        if (fadeLeft != rulerBounds.left || fadeRight != rulerBounds.right) {
            fadeLeft = rulerBounds.left;
            fadeRight = rulerBounds.right;
            edgeFadePaint.setShader(new LinearGradient(fadeLeft, 0f, fadeRight, 0f,
                    new int[]{0x00000000, 0xff000000, 0xff000000, 0x00000000},
                    new float[]{0f, .12f, .88f, 1f}, Shader.TileMode.CLAMP));
        }
        edgeFadePaint.setXfermode(DST_IN_XFERMODE);
        canvas.drawRect(rulerBounds.left, controlBounds.top, rulerBounds.right, controlBounds.bottom, edgeFadePaint);
        edgeFadePaint.setXfermode(null);
        canvas.restoreToCount(layer);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureShortcutCells();
        int width = Math.round(Math.max(getExpandedBackgroundWidth(), getCompactBackgroundWidth()) + dp(16))
                + getPaddingLeft() + getPaddingRight();
        int height = getReservedHeight();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    private void animateExpandedProgress(float target, boolean animated) {
        if (!animated || !SharedConfig.animationsEnabled() || !isLaidOut()) {
            expandedSpring.cancel();
            expandedProgress = target;
            invalidate();
            return;
        }
        expandedSpring.animateToFinalPosition(target);
    }

    private void animateControlWidth(float target, boolean animated) {
        if (animated && SharedConfig.animationsEnabled() && isLaidOut()) {
            widthSpring.animateToFinalPosition(target);
        } else {
            widthSpring.cancel();
            animatedControlWidth = target;
            invalidate();
        }
    }

    private void animateSelectorTo(int index, boolean animated) {
        float previousScroll = compactScroll;
        revealSelectedShortcut();
        float targetScroll = compactScroll;
        final float offset = getSelectorOffset(index);
        if (animated && geometryPresented && presented && hostAlpha > .05f
                && SharedConfig.animationsEnabled() && isLaidOut()) {
            compactScroll = previousScroll;
            if (compactScrollSpring.isRunning() || targetScroll != previousScroll) {
                compactScrollSpring.animateToFinalPosition(targetScroll);
            }
            selectorSpring.animateToFinalPosition(offset);
            return;
        }
        compactScrollSpring.cancel();
        selectorSpring.cancel();
        animatedSelectorOffset = offset;
        invalidate();
    }

    private void animateZoomTo(float target, boolean notify) {
        animateZoomTo(target, notify, -1);
    }

    private void animateZoomTo(float target, boolean notify, int toggleIndex) {
        float next = clamp(target, minZoom, maxZoom);
        if (toggleIndex >= 0 && toggleIndex < toggleStops.length) {
            selectedToggleIndex = toggleIndex;
            selectedShowsStopValue = true;
            animateSelectorTo(toggleIndex, true);
        }
        pendingPreset = Math.abs(next - zoom) > .001f;
        requested = next;
        if (notify && isEnabled()) listener.onZoom(next, true);
        else setValue(next, next);
        invalidate();
    }

    private boolean clearPendingPreset() {
        boolean wasPending = pendingPreset;
        pendingPreset = false;
        return wasPending;
    }

    private void cancelTransientSprings() {
        expandedSpring.cancel();
        widthSpring.cancel();
        selectorSpring.cancel();
        compactScrollSpring.cancel();
    }

    private void settleTransientAnimationValues() {
        expandedProgress = expanded ? 1f : 0f;
        animatedControlWidth = expanded ? getExpandedBackgroundWidth() : getCompactBackgroundWidth();
        animatedSelectorOffset = getSelectorOffset(selectedToggleIndex);
    }

    private void setZoomInternal(float value, boolean notify, boolean syncToggle) {
        zoom = clamp(value, minZoom, maxZoom);
        updateValueLabel();
        if (syncToggle) syncSelectedToggle(!expanded);
        invalidate();
        if (notify) dispatchZoomIntent(zoom);
    }

    private void syncSelectedToggle(boolean animated) {
        final int segment = findToggleSegment(zoom);

        if (segment < 0) {
            selectedShowsStopValue = false;
            selectedToggleIndex = -1;
            animatedSelectorOffset = 0f;
            return;
        }

        final boolean isExactPreset = Math.abs(zoom - toggleStops[segment]) <= 0.001f;

        if (selectedToggleIndex != segment) {
            selectedToggleIndex = segment;
            selectedShowsStopValue = isExactPreset;
            animateSelectorTo(segment, animated);
        } else {
            selectedShowsStopValue = isExactPreset;

            if (!animated) {
                selectorSpring.cancel();
                animatedSelectorOffset = getSelectorOffset(segment);
            }
        }

        invalidate();
    }

    private void selectToggle(int index) {
        if (index < 0 || index >= toggleStops.length) return;
        if (index == selectedToggleIndex && selectedShowsStopValue) return;
        performHapticSafe(HapticFeedbackConstants.CONTEXT_CLICK);
        animateZoomTo(toggleStops[index], true, index);
    }

    private void resetAutoCollapseTimeout() {
        removeCallbacks(autoCollapseRunnable);
        if (!presented || !expanded || dragging || isFocused() || isAccessibilityFocused()) return;
        postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY);
    }

    private void beginDrag(float x) {
        beginDirectGesture();
        if (!isEnabled()) return;
        if (clearPendingPreset()) syncSelectedToggle(true);
        dragging = true;
        lastTouchX = x;
        dragTick = zoomToTick(zoom);
        lastHapticTick = (int) Math.floor(dragTick);
        removeCallbacks(autoCollapseRunnable);
    }

    private void moveDrag(float x) {
        float delta = x - lastTouchX;
        lastTouchX = x;
        if (Math.abs(delta) < 1.0E-4f) return;
        final float tick = dragTick;
        final float target = clamp(tick - delta / tickSpacing, 0f, intervalCount);
        boolean primaryHaptic = false;
        for (int anchor : primaryTickIndices) {
            if (tick < anchor && target >= anchor || tick > anchor && target <= anchor) {
                primaryHaptic = performHapticSafe(HapticFeedbackConstants.KEYBOARD_TAP);
                break;
            }
        }
        performTickHaptic((int) Math.floor(target), primaryHaptic);
        setDragTick(target);
    }

    private boolean performHapticSafe(int feedbackConstant) {
        boolean major = feedbackConstant != HapticFeedbackConstants.CLOCK_TICK;
        long now = SystemClock.uptimeMillis();
        if (NimarkoConfig.disableVibration || !isHapticFeedbackEnabled()
                || now - (major ? lastMajorHaptic : lastSoftHaptic) < (major ? 40L : 35L)) return false;
        int effect = major ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.CLOCK_TICK;
        boolean accepted = performHapticFeedback(effect);
        if (!accepted && major && effect != HapticFeedbackConstants.CLOCK_TICK) {
            accepted = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (accepted) {
            lastSoftHaptic = now;
            if (major) lastMajorHaptic = now;
        }
        return accepted;
    }

    public void onExternalGestureZoom(float previous, float next) {
        if (!presented || !isEnabled() || !interactionReady || !isShown() || getAlpha() <= .5f
                || !RoundZoomScale.valid(minZoom, maxZoom)
                || !Float.isFinite(previous) || !Float.isFinite(next)) return;
        previous = clamp(previous, minZoom, maxZoom);
        next = clamp(next, minZoom, maxZoom);
        if (previous == next) return;
        for (float stop : rulerStops) {
            if (previous < stop && next >= stop || previous > stop && next <= stop) {
                performHapticSafe(HapticFeedbackConstants.CONTEXT_CLICK);
                return;
            }
        }
        if ((int) Math.floor(zoomToTick(previous)) != (int) Math.floor(zoomToTick(next))) {
            performHapticSafe(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void performTickHaptic(int tick, boolean skip) {
        if (lastHapticTick == tick) return;
        lastHapticTick = tick;
        if (skip) return;
        performHapticSafe(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void setDragTick(float tick) {
        dragTick = clamp(tick, 0f, intervalCount);
        setTickInternal(dragTick, true);
    }

    private void setTickInternal(float tick, boolean notify) {
        setZoomInternal(tickToZoom(tick), notify, true);
    }

    private void setZoomFromRulerTap(float x) {
        animateZoomTo(tickToZoom(zoomToTick(zoom) + (x - rulerBounds.centerX()) / tickSpacing), true);
    }

    private void finishDrag(boolean click, boolean haptic) {
        if (click && haptic) performHapticSafe(HapticFeedbackConstants.CONTEXT_CLICK);
        dragging = false;
        scrollingShortcuts = false;
        compactGestureDown = false;
        dragStartedFromCompact = false;
        pressedToggleIndex = -1;
        setPressed(false);
        requestParentIntercept(true);
        resetAutoCollapseTimeout();
        endDirectGesture();
        pointerId = -1;
        if (click) performClick();
    }

    private void clearTouchState() {
        endDirectGesture();
        pointerId = -1;
        dragging = false;
        scrollingShortcuts = false;
        compactGestureDown = false;
        dragStartedFromCompact = false;
        movedPastSlop = false;
        pressedToggleIndex = -1;
        setPressed(false);
        requestParentIntercept(true);
    }

    private void requestParentIntercept(boolean allow) {
        final ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(!allow);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || toggleStops.length == 0) return false;
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointerId) {
            cancelTouch();
            return true;
        }
        if (action != MotionEvent.ACTION_DOWN && pointerId == -1) return false;
        if (action == MotionEvent.ACTION_DOWN) {
            compactScrollSpring.cancel();
            pointerId = event.getPointerId(0);
            updateLayoutBounds();

            final RectF bounds = expanded ? rulerTouchBounds : compactTouchBounds;
            if (!bounds.contains(event.getX(), event.getY())) { pointerId = -1; return false; }
            downX = event.getX();
            downY = event.getY();
            lastTouchX = downX;
            movedPastSlop = false;
            compactGestureDown = !expanded;
            dragStartedFromCompact = false;
            pressedToggleIndex = !expanded && compactTouchBounds.contains(downX, downY)
                    ? findToggleIndexAt(downX) : -1;
            setPressed(true);
            requestParentIntercept(false);
            if (expanded) {
                beginDrag(downX);
            } else {
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
            }
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (scrollingShortcuts) {
                compactScroll = clamp(compactScroll + lastTouchX - event.getX(), 0f,
                        Math.max(0f, getCompactWidth() - compactBounds.width()));
                lastTouchX = event.getX();
                invalidate();
                return true;
            }
            if (dragging) {
                if (!movedPastSlop && Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    movedPastSlop = true;
                }
                moveDrag(event.getX());
                return true;
            }
            if (!compactGestureDown) return true;
            final float dx = event.getX() - downX;
            final float dy = event.getY() - downY;
            if (!movedPastSlop && Math.hypot(dx, dy) > touchSlop) {
                movedPastSlop = true;
                removeCallbacks(longPressRunnable);
                if (Math.abs(dx) >= Math.abs(dy)) {
                    if (getCompactWidth() > compactBounds.width() + 1f) {
                        scrollingShortcuts = true;
                        compactScroll = clamp(compactScroll - dx, 0f, getCompactWidth() - compactBounds.width());
                        lastTouchX = event.getX();
                        invalidate();
                    } else {
                        dragStartedFromCompact = true;
                        beginDrag(downX + Math.copySign(touchSlop, dx));
                        if (dragging) moveDrag(event.getX());
                        setExpanded(true, true);
                    }
                } else {
                    cancelTouch();
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(longPressRunnable);
            if (dragging) {
                final boolean tap = !movedPastSlop && !dragStartedFromCompact;
                if (tap) setZoomFromRulerTap(event.getX());
                finishDrag(true, tap);
            } else if (movedPastSlop || pressedToggleIndex < 0) {
                clearTouchState();
            } else {
                final int index = findToggleIndexAt(event.getX());
                if (compactTouchBounds.contains(event.getX(), event.getY()) && index == pressedToggleIndex) {
                    selectToggle(index);
                }
                clearTouchState();
                performClick();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            cancelTouch();
            return true;
        }

        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        geometryPresented = false;
        cancelTransientSprings();
        settleTransientAnimationValues();
        createZoomButtonBackgrounds();
    }

    @Override
    protected void onDetachedFromWindow() {
        resetPresentation();
        releaseBackgrounds();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        finishRangeTransition();
        geometryPresented = false;
        cancelTransientSprings();
        tickSpacing = Math.max(1f, Math.round((float) dp(8f)));
        configurePaints();
        rebuildScale();
        cacheLabels();
        settleTransientAnimationValues();
        requestLayout();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw == 0 || oldh == 0) geometryPresented = false;
        compactScrollSpring.cancel();
        updateLayoutBounds();
        revealSelectedShortcut();
        selectorSpring.cancel();
        animatedSelectorOffset = getSelectorOffset(selectedToggleIndex);
    }

    private static void addDistinctStop(ArrayList<Float> stops, float value) {
        for (int i = 0; i < stops.size(); i++) {
            final float existing = stops.get(i);
            if (Math.abs(existing - value) <= 1.0E-4f) return;
            if (existing > value) {
                stops.add(i, value);
                return;
            }
        }
        stops.add(value);
    }

    private static float[] boundStops(float[] values, float min, float max, boolean withEdges) {
        final ArrayList<Float> stops = new ArrayList<>(values.length + 2);
        if (withEdges) addDistinctStop(stops, min);
        for (float value : values) {
            if (value <= 0f || !Float.isFinite(value)) continue;
            if (value < min - 1.0E-4f) {
                if (!withEdges) addDistinctStop(stops, min);
            } else if (value <= max + 1.0E-4f) {
                addDistinctStop(stops, clamp(value, min, max));
            }
        }
        if (withEdges) addDistinctStop(stops, max);
        final float[] result = new float[stops.size()];
        for (int i = 0; i < stops.size(); i++) result[i] = stops.get(i);
        return result;
    }

    private static float[] buildRulerStops(float min, float max, float unit) {
        final float[] stops = boundStops(new float[]{min, unit, 2f * unit, 5f * unit, 10f * unit, 30f * unit}, min, max, false);
        if (stops.length == 0 || stops[stops.length - 1] * 1.15f < max) return boundStops(stops, min, max, true);
        return stops;
    }

    private void dispatchZoomIntent(float value) {
        if (!isEnabled()) return;
        requested = clamp(value, minZoom, maxZoom);
        listener.onZoom(requested, false);
    }

    private void beginDirectGesture() {
        if (directGesture || !isEnabled()) return;
        directGesture = true;
        listener.onGestureStart();
    }

    private void endDirectGesture() {
        if (!directGesture) return;
        directGesture = false;
        listener.onGestureEnd();
    }

    private void cancelTouch() {
        removeCallbacks(longPressRunnable);
        clearTouchState();
        resetAutoCollapseTimeout();
    }

    public void cancelGesture() {
        cancelTouch();
        removeCallbacks(autoCollapseRunnable);
        cancelTransientSprings();
        expanded = false;
        settleTransientAnimationValues();
        invalidate();
    }

    public void prepareForCameraSwitch() {
        setInteractionReady(false);
        cancelTouch();
        pendingPreset = false;
        setExpanded(false, true);
    }

    private void updateReady() {
        boolean ready = interactionReady && presented && hostAlpha * presentationProgress > .5f
                && rangeProgress >= 1f && RoundZoomScale.valid(minZoom, maxZoom);
        if (!ready && (pointerId != -1 || directGesture)) cancelTouch();
        if (isEnabled() != ready) {
            setEnabled(ready);
            accessibility.invalidateRoot();
        }
    }

    public void setInteractionReady(boolean ready) {
        if (interactionReady == ready) return;
        interactionReady = ready;
        updateReady();
    }

    public void setHostAlpha(float alpha) {
        float next = Float.isFinite(alpha) ? clamp(alpha, 0f, 1f) : 0f;
        float output = getVisibility() == VISIBLE ? next * presentationProgress : 0f;
        boolean changed = hostAlpha != next || getAlpha() != output;
        hostAlpha = next;
        updateBlurAlpha();
        if (!changed) return;
        setAlpha(output);
        updateReady();
    }

    public void setPresented(boolean visible) {
        if (presented == visible) return;
        float start = getVisibility() == VISIBLE ? presentationProgress : 0f;
        ValueAnimator previous = presentationAnimator;
        presentationAnimator = null;
        if (previous != null) previous.cancel();
        presented = visible;
        if (!visible) {
            cancelTouch();
            removeCallbacks(autoCollapseRunnable);
        }
        float target = visible ? 1f : 0f;
        if (hostAlpha > .5f && SharedConfig.animationsEnabled() && isLaidOut() && start != target) {
            setVisibility(VISIBLE);
            presentationProgress = start;
            presentationAnimator = ValueAnimator.ofFloat(start, target);
            presentationAnimator.setDuration(180);
            presentationAnimator.setInterpolator(MORPH_INTERPOLATOR);
            presentationAnimator.addUpdateListener(a -> {
                presentationProgress = (float) a.getAnimatedValue();
                setHostAlpha(hostAlpha);
            });
            presentationAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (presentationAnimator != animation) return;
                    presentationAnimator = null;
                    if (!presented) {
                        setVisibility(INVISIBLE);
                        finishRangeTransition();
                        cancelGesture();
                    }
                }
            });
            presentationAnimator.start();
        } else {
            presentationProgress = target;
            setVisibility(visible ? VISIBLE : INVISIBLE);
            if (!visible) {
                finishRangeTransition();
                cancelGesture();
            }
        }
        setHostAlpha(hostAlpha);
        updateReady();
    }

    public void resetPresentation() {
        if (presentationAnimator != null) presentationAnimator.cancel();
        presentationProgress = 1f;
        cancelGesture();
        if (rangeAnimator != null) rangeAnimator.cancel();
        rangeAnimator = null;
        rangeProgress = 1f;
        releaseOldRange();
        interactionReady = false;
        presented = false;
        pendingPreset = false;
        setHostAlpha(0f);
        updateReady();
        setVisibility(INVISIBLE);
    }

    public boolean matchesSession(Object identity) {
        return identity != null && session == identity;
    }

    public void prepareForCamera() {
        setInteractionReady(false);
        geometryPresented = false;
        setRange(null, 1f, 1f, new float[]{1f});
        setValue(1f, 1f);
    }

    public void prepareForCamera(float min, float max, float[] shortcuts, float value) {
        setInteractionReady(false);
        geometryPresented = false;
        setRange(null, min, max, shortcuts);
        setValue(value, value);
    }

    public void prepareForCameraSwitch(float min, float max, float[] shortcuts, float value) {
        prepareForCameraSwitch();
        setRange(null, min, max, shortcuts);
        setValue(value, value);
    }

    public boolean hasKnownRange() { return rangeInitialized; }

    public void setRange(Object identity, float min, float max) {
        setRange(identity, min, max, null);
    }

    public void setRange(Object identity, float min, float max, float[] shortcuts) {
        rangeInitialized = identity != null || RoundZoomScale.valid(min, max);
        if (!RoundZoomScale.valid(min, max)) min = max = 1f;
        float[] nextStops = sanitizeShortcuts(shortcuts, min, max);
        boolean shortcutsChanged = !Arrays.equals(toggleStops, nextStops);
        if (minZoom == min && maxZoom == max && !shortcutsChanged
                && (session == identity || session == null)) {
            if (session != identity) {
                prepareForCameraSwitch();
                session = identity;
                pendingPreset = false;
                syncSelectedToggle(!expanded && rangeProgress >= 1f);
            }
            updateReady();
            return;
        }
        if (rangeAnimator != null) rangeAnimator.cancel();
        rangeAnimator = null;
        boolean fade = geometryPresented && (session != identity || minZoom != min || maxZoom != max || shortcutsChanged)
                && isShown() && getAlpha() > .05f && SharedConfig.animationsEnabled()
                && getWidth() > 0 && getHeight() > 0;
        android.graphics.Bitmap snapshot = null;
        RectF snapshotSelector = null;
        float snapshotSelectorAlpha = 0f;
        if (fade) {
            snapshot = android.graphics.Bitmap.createBitmap(getWidth(), getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            updateLayoutBounds();
            snapshotSelectorAlpha = updateSelectorBounds();
            snapshotSelector = new RectF(selectorBounds);
            snapshotSelector.offset(-controlBounds.centerX(), -controlBounds.centerY());
            drawRangeContents(new Canvas(snapshot));
        }
        RectF snapshotBounds = snapshot == null ? null : new RectF(controlBounds);
        releaseOldRange();
        oldRangeContent = snapshot;
        if (snapshotBounds != null) oldRangeBounds.set(snapshotBounds);
        if (snapshotSelector != null) oldSelectorBounds.set(snapshotSelector);
        oldSelectorAlpha = snapshotSelectorAlpha;
        cancelTouch();
        removeCallbacks(autoCollapseRunnable);
        if (session != identity) {
            expanded = false;
            animateExpandedProgress(0f, fade);
        }
        session = identity;
        minZoom = min;
        maxZoom = max;
        displayNormalizationFactor = 1f;
        toggleStops = nextStops;
        compactScrollSpring.cancel();
        compactScroll = 0f;
        rulerStops = buildRulerStops(min, max, 1f);
        ArrayList<Float> detents = new ArrayList<>();
        for (float stop : rulerStops) addDistinctStop(detents, stop);
        for (float stop : toggleStops) addDistinctStop(detents, stop);
        rulerStops = new float[detents.size()];
        for (int i = 0; i < detents.size(); i++) rulerStops[i] = detents.get(i);
        zoom = clamp(zoom, min, max);
        requested = clamp(requested, min, max);
        pendingPreset = false;
        rebuildScale();
        cacheLabels();
        syncSelectedToggle(false);
        updateLayoutBounds();
        revealSelectedShortcut();
        animateControlWidth(expanded ? getExpandedBackgroundWidth() : getCompactBackgroundWidth(), fade);
        accessibility.invalidateRoot();
        rangeProgress = fade ? 0f : 1f;
        updateReady();
        if (fade) {
            rangeAnimator = ValueAnimator.ofFloat(0f, 1f);
            rangeAnimator.setDuration(220);
            rangeAnimator.setInterpolator(MORPH_INTERPOLATOR);
            rangeAnimator.addUpdateListener(a -> {
                if (rangeAnimator != a) return;
                rangeProgress = (float) a.getAnimatedValue();
                if (rangeProgress >= 1f) {
                    releaseOldRange();
                    resetAutoCollapseTimeout();
                }
                updateReady();
                invalidate();
            });
            rangeAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (rangeAnimator == animation) rangeAnimator = null;
                }
            });
            rangeAnimator.start();
        } else {
            resetAutoCollapseTimeout();
        }
        requestLayout();
        invalidate();
    }

    public void setValue(float value, float target) {
        float next = RoundZoomScale.clamp(value, minZoom, maxZoom);
        float nextTarget = RoundZoomScale.clamp(target, minZoom, maxZoom);
        if (zoom == next && requested == nextTarget) return;
        zoom = next;
        requested = nextTarget;
        if (pendingPreset && (Math.abs(zoom - requested) < .001f
                || selectedToggleIndex >= 0 && Math.abs(requested - toggleStops[selectedToggleIndex]) > .001f)) {
            pendingPreset = false;
        }
        updateValueLabel();
        if (!pendingPreset) syncSelectedToggle(!expanded && rangeProgress >= 1f);
        accessibility.invalidateRoot();
        invalidate();
    }

    private void cacheLabels() {
        toggleLabels = new String[toggleStops.length];
        for (int i = 0; i < toggleStops.length; i++) {
            toggleLabels[i] = formatToggle(toggleStops[i]);
        }
        measureShortcutCells();
        cachedTenths = Integer.MIN_VALUE;
        updateValueLabel();
    }

    private static float[] sanitizeShortcuts(float[] shortcuts, float min, float max) {
        ArrayList<Float> values = new ArrayList<>();
        if (shortcuts != null) for (float value : shortcuts) {
            if (Float.isFinite(value) && value > 0f && value >= min && value <= max) addDistinctStop(values, value);
        }
        if (values.isEmpty()) values.add(clamp(1f, min, max));
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private void updateValueLabel() {
        int tenths = Math.round(normalizeDisplayZoom(zoom) * 10f);
        if (tenths == cachedTenths) return;
        cachedTenths = tenths;
        cachedBubble = formatBubble(zoom);
    }

    public void setBlurBackground(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider provider) {
        releaseBackgrounds();
        liquidGlassFactory = factory;
        liquidGlassColorProvider = provider;
        createZoomButtonBackgrounds();
        invalidate();
    }

    private void releaseBackgrounds() {
        releaseBackground(protectionBackgroundDrawable);
        protectionBackgroundDrawable = null;
    }

    private void releaseBackground(Drawable drawable) {
        if (!(drawable instanceof BlurredBackgroundDrawable)) return;
        unscheduleDrawable(drawable);
        drawable.setCallback(null);
        if (liquidGlassFactory != null) liquidGlassFactory.release(this, (BlurredBackgroundDrawable) drawable);
    }

    private void updateBlurAlpha() {
        boolean visible = getVisibility() == VISIBLE && hostAlpha > 0f;
        setDrawableAlpha(protectionBackgroundDrawable, visible ? 255 : 0);
    }

    private void setDrawableAlpha(Drawable drawable, int alpha) {
        if (drawable != null && drawable.getAlpha() != alpha) drawable.setAlpha(alpha);
    }

    @Override
    protected boolean verifyDrawable(Drawable drawable) {
        return drawable == protectionBackgroundDrawable || super.verifyDrawable(drawable);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelTouch();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        info.setText(cachedBubble);
        info.setContentDescription(LocaleController.getString(R.string.NM_CAM_RoundZoomScale) + ", " + cachedBubble);
        if (RoundZoomScale.valid(minZoom, maxZoom)) {
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, minZoom, maxZoom, zoom));
            if (isEnabled()) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.addAction(expanded ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            }
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (!isEnabled()) return super.performAccessibilityAction(action, args);
        if (action == AccessibilityNodeInfo.ACTION_EXPAND || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
            setExpanded(action == AccessibilityNodeInfo.ACTION_EXPAND, true);
            return true;
        }
        if (action == android.R.id.accessibilityActionSetProgress && args != null
                && args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
            float target = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
            if (!Float.isFinite(target)) return false;
            beginDirectGesture();
            if (isEnabled()) {
                onExternalGestureZoom(zoom, target);
                setZoomInternal(target, true, true);
            }
            endDirectGesture();
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            if (!expanded && getCompactWidth() > compactBounds.width() + 1f) {
                compactScrollSpring.cancel();
                compactScroll = clamp(compactScroll + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1f : -1f)
                        * compactBounds.width(), 0f, getCompactWidth() - compactBounds.width());
                accessibility.invalidateRoot();
                invalidate();
                return true;
            }
            beginDirectGesture();
            if (isEnabled()) {
                float target = tickToZoom(zoomToTick(zoom)
                        + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1f : -1f));
                onExternalGestureZoom(zoom, target);
                setZoomInternal(target, true, true);
            }
            endDirectGesture();
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    public void setExpanded(boolean value, boolean animated) {
        animated = animated && SharedConfig.animationsEnabled();
        final float progress = value ? 1f : 0f;
        final float width = value ? getExpandedBackgroundWidth() : getCompactBackgroundWidth();
        if (expanded == value
                && Math.abs(expandedProgress - progress) < 1.0E-4f
                && Math.abs(animatedControlWidth - width) < 0.1f
        ) {
            if (value) resetAutoCollapseTimeout();
            return;
        }
        expanded = value;
        accessibility.invalidateRoot();
        removeCallbacks(longPressRunnable);
        removeCallbacks(autoCollapseRunnable);
        animateExpandedProgress(progress, animated);
        animateControlWidth(width, animated);
        if (value && !dragging) resetAutoCollapseTimeout();
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        accessibility.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }

    private final class ZoomAccessibility extends ExploreByTouchHelper {
        ZoomAccessibility() { super(RoundZoomControl.this); }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            if (expanded || !compactTouchBounds.contains(x, y)) return INVALID_ID;
            int index = findToggleIndexAt(x);
            return index < 0 ? INVALID_ID : index;
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            if (!expanded) for (int i = 0; i < toggleStops.length; i++) ids.add(i);
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, AccessibilityNodeInfoCompat node) {
            if (id < 0 || id >= toggleStops.length) {
                node.setContentDescription("");
                node.setBoundsInParent(new Rect());
                return;
            }
            node.setClassName("android.widget.Button");
            node.setContentDescription(formatToggle(toggleStops[id])
                    + (Math.abs(toggleStops[id] - 1f) < .001f ? "" : "×"));
            node.setSelected(id == selectedToggleIndex);
            node.setEnabled(isEnabled());
            node.setFocusable(true);
            node.setClickable(true);
            float left = compactBounds.left + getToggleCellStart(id) - compactScroll;
            float right = compactBounds.left + getToggleCellStart(id + 1) - compactScroll;
            node.setBoundsInParent(new Rect(Math.round(left), Math.round(compactTouchBounds.top),
                    Math.round(right), Math.round(compactTouchBounds.bottom)));
            if (isEnabled()) node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfo.ACTION_CLICK, null));
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, Bundle args) {
            if (!isEnabled() || expanded || id < 0 || id >= toggleStops.length) return false;
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false;
            selectToggle(id);
            revealSelectedShortcut();
            invalidateRoot();
            return true;
        }

        @Override
        protected void onVirtualViewKeyboardFocusChanged(int id, boolean focused) {
            if (!focused || id < 0 || id >= toggleStops.length) return;
            compactScroll = clamp(getToggleCellCenter(id) - compactBounds.width() / 2f,
                    0f, Math.max(0f, getCompactWidth() - compactBounds.width()));
            invalidateRoot();
            invalidate();
        }
    }

}
