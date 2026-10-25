/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterViewAnimatedIconView;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.icons.icon_replaces.BaseIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.LiquidGlassFullReplace;
import app.nimarkogram.messenger.icons.icon_replaces.PlumpyFullReplace;
import app.nimarkogram.messenger.icons.icon_replaces.SolarIconReplace;
import app.nimarkogram.messenger.utils.ui.MonetHelper;

public final class IconPackPreviewCard extends LinearLayout {

    private static final long APPLIED_ANIMATION_DURATION = 220L;

    private static final int[] GRID_ICONS = {
            R.drawable.msg_settings, R.drawable.msg_calls, R.drawable.msg_folders,
            R.drawable.msg_camera, R.drawable.msg_contacts, R.drawable.msg_archive
    };

    private final Theme.ResourcesProvider resourcesProvider;
    private final Resources rawResources;
    private final BaseIconReplace replacement;
    private final boolean rtl;
    private final ArrayList<Runnable> themeBindings = new ArrayList<>();
    private final GradientDrawable cardSurface = rounded(28);
    private final TextView titleView;
    private final TextView subtitleView;
    private final FrameLayout appliedMarker;
    private boolean applied;
    private boolean appliedInitialized;
    private float appliedProgress;
    private ValueAnimator appliedAnimator;
    private int unappliedStrokeColor;
    private int appliedStrokeColor;

    public IconPackPreviewCard(Context context, int value, CharSequence title,
                               CharSequence subtitle, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        rtl = LocaleController.isRTL;
        replacement = replacementFor(value);
        rawResources = resolveRawResources(context);

        setOrientation(VERTICAL);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setPadding(dp(20), dp(20), dp(20), dp(20));
        setBackground(cardSurface);
        setClipToOutline(true);
        setElevation(dp(2));
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(TextUtils.isEmpty(subtitle) ? title
                : TextUtils.concat(title == null ? "" : title, "\n", subtitle));

        LinearLayout heading = row();
        heading.setGravity(Gravity.TOP);
        titleView = text(title, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        heading.addView(titleView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        appliedMarker = new FrameLayout(context);
        appliedMarker.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        bindSurface(appliedMarker, 14, Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhiteBlueIcon, 0.12f, false);
        appliedMarker.addView(icon(R.drawable.ic_ab_done,
                        Theme.key_windowBackgroundWhiteBlueIcon, true),
                new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        LayoutParams markerParams = new LayoutParams(dp(28), dp(28));
        setHorizontalMargins(markerParams, 10, 0);
        markerParams.topMargin = dp(2);
        heading.addView(appliedMarker, markerParams);
        appliedMarker.setVisibility(INVISIBLE);
        addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        subtitleView = text(subtitle, 14);
        subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? GONE : VISIBLE);
        LayoutParams subtitleParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(6);
        addView(subtitleView, subtitleParams);

        LinearLayout preview = column();
        preview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        preview.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        preview.addView(createChat(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams gridParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(16);
        preview.addView(createGrid(), gridParams);
        LayoutParams previewParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f);
        previewParams.topMargin = dp(18);
        addView(preview, previewParams);

        refreshTheme();
    }

    public void refreshTheme() {
        titleView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        subtitleView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        for (int i = 0; i < themeBindings.size(); i++) {
            themeBindings.get(i).run();
        }
        int surface = color(Theme.key_windowBackgroundWhite);
        cardSurface.setColor(surface);
        unappliedStrokeColor = ColorUtils.blendARGB(surface,
                color(Theme.key_windowBackgroundWhiteBlackText), 0.09f);
        appliedStrokeColor = ColorUtils.blendARGB(surface,
                color(Theme.key_windowBackgroundWhiteBlueIcon), 0.45f);
        setAppliedProgress(appliedProgress);
    }

    public void setApplied(boolean applied) {
        setApplied(applied, false);
    }

    public void setApplied(boolean applied, boolean animated) {
        boolean changed = this.applied != applied;
        boolean initialized = appliedInitialized;
        appliedInitialized = true;
        this.applied = applied;
        setSelected(applied);
        if (!animated || !initialized || !canAnimateApplied()) {
            finishAppliedAnimation();
            return;
        }
        if (!changed) {
            return;
        }

        cancelAppliedAnimation();
        float target = applied ? 1f : 0f;
        if (appliedProgress == target) {
            setAppliedProgress(target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(appliedProgress, target);
        appliedAnimator = animator;
        animator.setDuration(APPLIED_ANIMATION_DURATION);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        animator.addUpdateListener(animation -> {
            if (appliedAnimator != animation) {
                return;
            }
            if (!canAnimateApplied()) {
                finishAppliedAnimation();
                return;
            }
            setAppliedProgress((float) animation.getAnimatedValue());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (appliedAnimator == animation) {
                    appliedAnimator = null;
                    setAppliedProgress(IconPackPreviewCard.this.applied ? 1f : 0f);
                }
            }
        });
        animator.start();
    }

    private boolean canAnimateApplied() {
        return isAttachedToWindow() && isLaidOut() && isShown()
                && getWindowVisibility() == VISIBLE
                && SharedConfig.animationsEnabled()
                && !AndroidUtilities.isAccessibilityTouchExplorationEnabled()
                && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? ValueAnimator.areAnimatorsEnabled() : AndroidUtilities.getAnimatorDurationScale() > 0f);
    }

    private void cancelAppliedAnimation() {
        ValueAnimator animator = appliedAnimator;
        appliedAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
    }

    private void finishAppliedAnimation() {
        cancelAppliedAnimation();
        setAppliedProgress(applied ? 1f : 0f);
    }

    private void setAppliedProgress(float progress) {
        appliedProgress = Math.max(0f, Math.min(1f, progress));
        appliedMarker.setAlpha(appliedProgress);
        float scale = 0.85f + 0.15f * appliedProgress;
        appliedMarker.setScaleX(scale);
        appliedMarker.setScaleY(scale);
        appliedMarker.setVisibility(applied || appliedProgress > 0f ? VISIBLE : INVISIBLE);
        refreshAppliedSurface();
    }

    @Override
    protected void onDetachedFromWindow() {
        finishAppliedAnimation();
        super.onDetachedFromWindow();
    }

    private LinearLayout createChat() {
        LinearLayout chat = column();
        chat.setPadding(dp(2), dp(4), dp(2), dp(4));

        LinearLayout toolbar = row();
        toolbar.setMinimumHeight(dp(36));
        FrameLayout avatar = new FrameLayout(getContext());
        bindOutline(avatar, 16, Theme.key_windowBackgroundWhiteBlueIcon, 0.3f);
        avatar.addView(icon(R.drawable.chats_saved, Theme.key_windowBackgroundWhiteBlueIcon, true),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        toolbar.addView(avatar, new LayoutParams(dp(32), dp(32)));

        LinearLayout identity = column();
        addLine(identity, 0.76f, 5, 0, Theme.key_windowBackgroundWhiteBlackText, 0.22f);
        addLine(identity, 0.46f, 4, 6, Theme.key_windowBackgroundWhiteGrayText, 0.22f);
        LayoutParams identityParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        setHorizontalMargins(identityParams, 10, 8);
        toolbar.addView(identity, identityParams);
        toolbar.addView(icon(R.drawable.ic_ab_other, Theme.key_windowBackgroundWhiteGrayIcon, true),
                new LayoutParams(dp(20), dp(20)));
        chat.addView(toolbar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        View divider = new View(getContext());
        bindSurface(divider, 0, Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhiteBlackText, 0.08f, false);
        LayoutParams dividerParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(10);
        chat.addView(divider, dividerParams);

        LinearLayout messages = column();
        messages.setGravity(Gravity.BOTTOM);
        LayoutParams incomingParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        incomingParams.topMargin = dp(12);
        messages.addView(createBubble(false), incomingParams);
        LayoutParams outgoingParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        outgoingParams.topMargin = dp(6);
        messages.addView(createBubble(true), outgoingParams);
        chat.addView(messages, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f));

        LayoutParams composerParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        composerParams.topMargin = dp(10);
        chat.addView(createComposer(), composerParams);
        return chat;
    }

    private LinearLayout createBubble(boolean outgoing) {
        LinearLayout line = row();
        LinearLayout bubble = row();
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        int bubbleKey = outgoing ? Theme.key_chat_outBubble : Theme.key_chat_inBubble;
        int inkKey = outgoing ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn;
        bindSurface(bubble, 14, bubbleKey, bubbleKey, 0f, !outgoing);
        LinearLayout message = column();
        addLine(message, 0.92f, 4, 0, inkKey, 0.22f);
        addLine(message, outgoing ? 0.62f : 0.73f, 4, 6, inkKey, 0.13f);
        bubble.addView(message, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        if (outgoing) {
            LayoutParams checkParams = new LayoutParams(dp(12), dp(12));
            setHorizontalMargins(checkParams, 6, 0);
            checkParams.gravity = Gravity.BOTTOM;
            bubble.addView(icon(R.drawable.msg_check_s, Theme.key_chat_outSentCheck, true), checkParams);
            line.addView(new View(getContext()), new LayoutParams(0, 0, 0.16f));
        }
        line.addView(bubble, new LayoutParams(0, LayoutParams.WRAP_CONTENT, outgoing ? 0.84f : 0.78f));
        if (!outgoing) {
            line.addView(new View(getContext()), new LayoutParams(0, 0, 0.22f));
        }
        return line;
    }

    private LinearLayout createComposer() {
        LinearLayout composer = row();
        composer.setPadding(0, dp(6), 0, dp(6));
        FrameLayout attachment = new FrameLayout(getContext());
        bindSurface(attachment, 16, Theme.key_chat_messagePanelBackground,
                Theme.key_chat_messagePanelBackground, 0f, true);
        attachment.addView(icon(R.drawable.msg_input_attach2, Theme.key_chat_messagePanelIcons, true),
                new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        composer.addView(attachment, new LayoutParams(dp(32), dp(32)));

        LinearLayout field = row();
        field.setPadding(dp(8), dp(4), dp(8), dp(4));
        field.setMinimumHeight(dp(32));
        bindSurface(field, 16, Theme.key_chat_messagePanelBackground,
                Theme.key_chat_messagePanelBackground, 0f, true);
        LinearLayout hint = column();
        addLine(hint, 0.85f, 4, 0, Theme.key_chat_messagePanelHint, 0.26f);
        LayoutParams hintParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        setHorizontalMargins(hintParams, 0, 8);
        field.addView(hint, hintParams);
        field.addView(icon(R.drawable.msg_sticker, Theme.key_chat_messagePanelIcons, true),
                new LayoutParams(dp(20), dp(20)));
        LayoutParams fieldParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        setHorizontalMargins(fieldParams, 6, 6);
        composer.addView(field, fieldParams);

        FrameLayout send = new FrameLayout(getContext());
        boolean customSend = replacement != null;
        bindSurface(send, 16, customSend ? Theme.key_chat_messagePanelBackground : Theme.key_chat_messagePanelSend,
                Theme.key_chat_messagePanelSend, customSend ? 0.12f : 0f, false);
        View sendIcon = icon(customSend ? R.drawable.ic_send : R.drawable.send_plane_24,
                Theme.key_chat_messagePanelSend, customSend, !customSend);
        float sendScale = replacement instanceof LiquidGlassFullReplace
                ? ChatActivityEnterViewAnimatedIconView.LIQUID_SEND_ARTWORK_FRACTION * (32f / 20f)
                : replacement instanceof SolarIconReplace ? 1.10f
                : replacement instanceof PlumpyFullReplace ? 1.14f : 1f;
        sendIcon.setScaleX(sendScale);
        sendIcon.setScaleY(sendScale);
        send.addView(sendIcon,
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        LayoutParams sendParams = new LayoutParams(dp(32), dp(32));
        composer.addView(send, sendParams);
        return composer;
    }

    private LinearLayout createGrid() {
        LinearLayout grid = column();
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = row();
            for (int column = 0; column < 3; column++) {
                FrameLayout tile = new FrameLayout(getContext());
                bindSurface(tile, 16, Theme.key_windowBackgroundWhite,
                        Theme.key_windowBackgroundWhiteBlackText, 0.045f, false);
                tile.addView(icon(GRID_ICONS[rowIndex * 3 + column],
                                Theme.key_windowBackgroundWhiteGrayIcon, true),
                        new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
                LayoutParams tileParams = new LayoutParams(0, dp(56), 1f);
                if (column > 0) {
                    setHorizontalMargins(tileParams, 8, 0);
                }
                row.addView(tile, tileParams);
            }
            LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = rowIndex == 0 ? 0 : dp(8);
            grid.addView(row, rowParams);
        }
        return grid;
    }

    private TextView text(CharSequence value, int sizeSp) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        view.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private ImageView icon(int stockId, int tintKey, boolean packSample) {
        return icon(stockId, tintKey, packSample, false);
    }

    private ImageView icon(int stockId, int tintKey, boolean packSample, boolean onSendBackground) {
        ImageView view = new ImageView(getContext());
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int resolvedId = packSample && replacement != null ? replacement.wrap(stockId) : stockId;
        boolean noTint = packSample && replacement != null && replacement.isNoTint(stockId);
        if (packSample && replacement instanceof LiquidGlassFullReplace && stockId == R.drawable.ic_send) {
            resolvedId = R.drawable.ng_liquid_send_master;
        }
        Drawable drawable;
        try {
            drawable = rawResources.getDrawable(resolvedId, null).mutate();
        } catch (Resources.NotFoundException missingTwin) {
            if (resolvedId == stockId) {
                throw missingTwin;
            }
            drawable = rawResources.getDrawable(stockId, null).mutate();
            noTint = false;
        }
        final Drawable ownedDrawable = drawable;
        if (noTint) {
            ownedDrawable.clearColorFilter();
        } else {
            themeBindings.add(() -> {
                int tint = onSendBackground
                        ? MonetHelper.ensureReadableIconForeground(MonetHelper.getOnPrimaryColor(Color.WHITE), color(tintKey))
                        : color(tintKey);
                if (packSample && replacement != null && stockId == R.drawable.ic_send) {
                    int surface = ColorUtils.blendARGB(color(Theme.key_chat_messagePanelBackground),
                            color(Theme.key_chat_messagePanelSend), 0.12f);
                    tint = MonetHelper.ensureReadableIconForeground(tint, surface);
                }
                ownedDrawable.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
            });
        }
        view.setImageDrawable(ownedDrawable);
        return view;
    }

    private void addLine(LinearLayout parent, float fraction, int heightDp, int topDp,
                         int colorKey, float opacity) {
        LinearLayout track = row();
        View ink = new View(getContext());
        bindSurface(ink, 3, colorKey, colorKey, 0f, false);
        ink.setAlpha(opacity);
        track.addView(ink, new LayoutParams(0, dp(heightDp), fraction));
        track.addView(new View(getContext()), new LayoutParams(0, 0, 1f - fraction));
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        parent.addView(track, params);
    }

    private void bindSurface(View view, int radiusDp, int baseKey, int accentKey,
                             float accentAmount, boolean stroke) {
        GradientDrawable surface = rounded(radiusDp);
        view.setBackground(surface);
        themeBindings.add(() -> {
            int fill = ColorUtils.blendARGB(color(baseKey), color(accentKey), accentAmount);
            surface.setColor(fill);
            if (stroke) {
                surface.setStroke(dp(1), ColorUtils.blendARGB(fill,
                        color(Theme.key_windowBackgroundWhiteBlackText), 0.055f));
            }
        });
    }

    private void bindOutline(View view, int radiusDp, int colorKey, float opacity) {
        GradientDrawable surface = rounded(radiusDp);
        surface.setColor(Color.TRANSPARENT);
        view.setBackground(surface);
        themeBindings.add(() -> surface.setStroke(dp(1), Theme.multAlpha(color(colorKey), opacity)));
    }

    private void refreshAppliedSurface() {
        cardSurface.setStroke(dp(1), ColorUtils.blendARGB(
                unappliedStrokeColor, appliedStrokeColor, appliedProgress));
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext()) {
            @Override
            public void addView(View child, int index, ViewGroup.LayoutParams params) {
                if (rtl) {
                    if (index < 0) {
                        index = 0;
                    } else if (index <= getChildCount()) {
                        index = getChildCount() - index;
                    }
                }
                super.addView(child, index, params);
            }
        };
        row.setOrientation(HORIZONTAL);
        row.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        return row;
    }

    private void setHorizontalMargins(LayoutParams params, int startDp, int endDp) {
        params.leftMargin = dp(rtl ? endDp : startDp);
        params.rightMargin = dp(rtl ? startDp : endDp);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        return column;
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private static GradientDrawable rounded(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private static int dp(int value) {
        return AndroidUtilities.dp(value);
    }

    @SuppressWarnings("deprecation")
    private static Resources resolveRawResources(Context context) {
        Resources raw = ApplicationLoader.rawResources();
        if (raw != null) {
            return raw;
        }
        Resources source = context.getResources();
        return new Resources(source.getAssets(), source.getDisplayMetrics(), source.getConfiguration());
    }

    private static BaseIconReplace replacementFor(int value) {
        switch (value) {
            case AppConfig.ICON_REPLACE_SOLAR:
                return new SolarIconReplace();
            case AppConfig.ICON_REPLACE_LIQUID_GLASS:
                return new LiquidGlassFullReplace();
            case AppConfig.ICON_REPLACE_PLUMPY:
                return new PlumpyFullReplace();
            case AppConfig.ICON_REPLACE_NONE:
            default:
                return null;
        }
    }
}
