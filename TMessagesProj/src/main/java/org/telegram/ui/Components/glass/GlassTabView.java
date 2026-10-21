/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.glass;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import app.nimarkogram.messenger.icons.NimarkoIconResources;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumGradient;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.MainTabsLayout;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class GlassTabView extends FrameLayout implements MainTabsLayout.Tab, FactorAnimator.Target {
    private final TextView textView;
    private final RLottieImageView imageView;
    private BackupImageView backupImageView;
    private Theme.ResourcesProvider resourcesProvider;
    private final Paint paintCounterBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AnimatedTextView.AnimatedTextDrawable counter;

    private static final int ANIMATOR_ID_IS_SELECTED = 0;
    private static final int ANIMATOR_ID_COUNTER_VISIBLE = 1;
    private static final int ANIMATOR_ID_COUNTER_ERROR = 2;
    private static final int ANIMATOR_ID_SELECTOR = 3;

    private final BoolAnimator isSelectedAnimator = new BoolAnimator(ANIMATOR_ID_IS_SELECTED, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 320);
    private final FactorAnimator selectorAnimator = new FactorAnimator(ANIMATOR_ID_SELECTOR, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 320, 0f);
    private final BoolAnimator isHasCounterAnimator = new BoolAnimator(ANIMATOR_ID_COUNTER_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private final BoolAnimator isHasCounterErrorAnimator = new BoolAnimator(ANIMATOR_ID_COUNTER_ERROR, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private int colorSelected;
    private int colorSelectedText;
    private int colorDefault;
    private boolean usePremiumCounter;

    private TabAnimation tabAnimation;
    private boolean isMainTab;
    private TLRPC.TL_attachMenuBot tabAnimationBot;

    private final TextPaint defaultTextPaint;

    public GlassTabView(@NonNull Context context) {
        super(context);
        setFocusable(true);
        imageView = new RLottieImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        addView(imageView, LayoutHelper.createFrame(44, 44, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, -6, 0, 0));

        imageView.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        textView.setSingleLine();
        textView.setLines(1);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setGravity(Gravity.CENTER);

        defaultTextPaint = new TextPaint(textView.getPaint());
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 28.33f, 0, 0));

        counter = new AnimatedTextView.AnimatedTextDrawable();
        counter.setTypeface(AndroidUtilities.bold());
        counter.setCallback(this);
        counter.setGravity(Gravity.CENTER);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(dp(10));
    }

    private boolean hasVisualWidth;
    private float visualWidth;
    public void setVisualWidth(float width) {
        hasVisualWidth = true;
        if (visualWidth != width) {
            visualWidth = width;
            checkVisualWidth();
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        checkVisualWidth();
    }

    private void checkVisualWidth() {
        if (hasVisualWidth) {
            final float offset = (visualWidth - getMeasuredWidth()) / 2f;
            imageView.setTranslationX(offset);
            textView.setTranslationX(offset);
            if (backupImageView != null) {
                backupImageView.setTranslationX(offset);
            }
        }
    }

    private static final RectF tmpRectF = new RectF();

    private boolean hasGestureSelectedOverride;
    private boolean skipDrawSelector;

    public void setGestureSelectedOverride(float gestureSelectedOverride, boolean allow) {
        final boolean wasOverridden = hasGestureSelectedOverride;
        this.hasGestureSelectedOverride = allow;
        if (allow) {
            selectorAnimator.forceFactor(MathUtils.clamp(gestureSelectedOverride, 0f, 1f));
        } else if (wasOverridden) {
            updateSelectorTarget(isSelectedAnimator.getValue(), true);
        }
        invalidate();
    }
    private void updateSelectorTarget(boolean selected, boolean animated) {
        if (hasGestureSelectedOverride) return;
        final float target = selected ? 1f : 0f;
        if (!animated) {
            selectorAnimator.forceFactor(target);
        } else if (selectorAnimator.getToFactor() != target) {
            selectorAnimator.animateTo(target);
        }
    }

    public void setSkipDrawSelector(boolean skipDrawSelector) {
        if (this.skipDrawSelector != skipDrawSelector) {
            this.skipDrawSelector = skipDrawSelector;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (!SharedConfig.animationsEnabled()
                || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            finishPackSelection();
        }
        final float viewWidth = hasVisualWidth ? visualWidth : getWidth();
        final float selectedFactor = selectorAnimator.getFactor();
        if (selectedFactor > 0 && !skipDrawSelector) {
            final float alpha = AnimatorUtils.DECELERATE_INTERPOLATOR.getInterpolation(selectedFactor);

            paintCounterBackground.setColor(Theme.multAlpha(colorSelected, 0.09f * alpha));
            tmpRectF.set(0, 0, viewWidth, getHeight());
            final float r = Math.min(tmpRectF.width(), tmpRectF.height()) / 2f;
            final float s = lerp(0.6f, 1, selectedFactor) * MathUtils.clamp(attachScale, 0, 1);
            canvas.save();
            canvas.scale(s, s, tmpRectF.centerX(), tmpRectF.centerY());
            canvas.drawRoundRect(tmpRectF, r, r, paintCounterBackground);
            canvas.restore();
        }

        final float hasCounter = (usePremiumCounter ? 1f : isHasCounterAnimator.getFloatValue()) * attachScale;
        final boolean saveLayer = hasCounter > 0;
        if (saveLayer) {
            canvas.saveLayer(0, 0, viewWidth, getHeight(), null);
        }

        super.dispatchDraw(canvas);

        if (hasCounter > 0) {
            canvas.save();

            final float gap = dpf2(1.33f);
            final float cx = viewWidth / 2f + dpf2(11);
            final float cy = dpf2(10);
            final float height = dpf2(16);
            final float width = Math.max(height, counter.getCurrentWidth() + dp(8));
            final float rOuter = dpf2(9.333f);
            final float rInner = dpf2(8f);
            tmpRectF.set(
                    cx - width / 2f - gap,
                    cy - height / 2f - gap,
                    cx + width / 2f + gap,
                    cy + height / 2f + gap
            );

            canvas.scale(hasCounter, hasCounter, cx, cy);
            canvas.drawRoundRect(tmpRectF, rOuter, rOuter, Theme.PAINT_CLEAR);
            tmpRectF.inset(gap, gap);

            if (usePremiumCounter) {
                if (premiumStarDrawable == null) {
                    premiumStarDrawable = getContext().getResources().getDrawable(R.drawable.star).mutate();
                }

                PremiumGradient.getInstance().updateMainGradientMatrix(0, 0, dp(96), dp(16), 0, 0);
                canvas.drawRoundRect(tmpRectF, rInner, rInner, PremiumGradient.getInstance().getMainGradientPaint());
                int x = (int)(cx - dpf2(7f));
                int y = (int)(cy - dpf2(7f));
                premiumStarDrawable.setBounds(x, y, x + dp(14), y + dp(14));
                premiumStarDrawable.draw(canvas);
            } else {
                paintCounterBackground.setColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_telegram_color), Theme.getColor(Theme.key_fill_RedNormal), isHasCounterErrorAnimator.getFloatValue()));
                canvas.drawRoundRect(tmpRectF, rInner, rInner, paintCounterBackground);
                counter.setBounds(tmpRectF);
                counter.draw(canvas);
            }
            canvas.restore();
        }

        if (saveLayer) {
            canvas.restore();
        }
    }

    private Drawable premiumStarDrawable;

    public void setCounter(String text, boolean isError, boolean animated) {
        counter.setText(text, animated);
        isHasCounterAnimator.setValue(!TextUtils.isEmpty(text), animated);
        isHasCounterErrorAnimator.setValue(isError, animated);
    }

    public void setPremiumBadge(boolean usePremiumBadge) {
        usePremiumCounter = usePremiumBadge;
    }

    public void setSelected(boolean selected, boolean animated) {
        boolean changed = isSelectedAnimator.getValue() != selected;
        boolean animateSelection = animated && (lastIconReplacement == 0
                || (SharedConfig.animationsEnabled() && isAttachedToWindow()
                && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())));
        isSelectedAnimator.setValue(selected, animateSelection);
        updateSelectorTarget(selected, animateSelection);
        super.setSelected(selected);
        checkPlayAnimation(animateSelection);

        textView.setTypeface(selected ? AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD) : AndroidUtilities.bold());
        if (changed && selected && animated) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        }
    }

    public boolean isTabSelected() {
        return isSelectedAnimator.getValue();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_IS_SELECTED) {
            updateColors();
        }
        invalidate();
    }

    private boolean needUpdateBackupViewColor;

    private void updateColors() {
        if (packIconDrawable != null) {
            packIconDrawable.setSelectionProgress(tabAnimation != null && tabAnimation.iconStatic == -1
                    && SharedConfig.animationsEnabled() ? isSelectedAnimator.getFloatValue() : 0f);
        }
        final int color = ColorUtils.blendARGB(colorDefault, colorSelected, isSelectedAnimator.getFloatValue());
        final int colorText = ColorUtils.blendARGB(colorDefault, colorSelectedText, isSelectedAnimator.getFloatValue());

        final PorterDuffColorFilter filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (backupImageView != null && needUpdateBackupViewColor) {
            backupImageView.setColorFilter(filter);
            backupImageView.invalidate();
        }
        imageView.setColorFilter(filter);
        textView.setTextColor(colorText);
    }

    public void updateColorsLottie() {
        colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        updateColors();
        invalidate();
    }

    private boolean lastIsSelected;
    private int lastIconAnimationRaw;
    private long lastBotIconId;

    private int lastIconReplacement;
    private Resources lastIconReplacementResources;
    private long lastIconReplacementGeneration;
    private int lastIconReplacementDensityDpi;
    private FixedSizeIconDrawable packIconDrawable;
    private void checkPlayAnimation(boolean animated) {
        final boolean isSelected = isSelectedAnimator.getValue();

        if (tabAnimationBot !=  null) {
            boolean animatedIcon = true;
            TLRPC.TL_attachMenuBotIcon icon = MediaDataController.getAnimatedAttachMenuBotIcon(tabAnimationBot, isSelected);
            if (icon == null) {
                icon = MediaDataController.getStaticAttachMenuBotIcon(tabAnimationBot);
                animatedIcon = false;
            }
            if (icon != null && icon.icon != null) {
                TLRPC.Document iconDoc = icon.icon;
                if (lastBotIconId != icon.icon.id) {
                    String filter = "24_24" + (animatedIcon && !animated || true ? "_lastframe" : "");
                    backupImageView.setImage(
                        ImageLocation.getForDocument(iconDoc), filter,
                        ImageLocation.getForDocument(iconDoc), filter,
                        animatedIcon ? null : DocumentObject.getSvgThumb(iconDoc, Theme.key_windowBackgroundGray, 1f),
                        tabAnimationBot
                    );
                    lastBotIconId = iconDoc.id;
                }
            } else {
                backupImageView.clearImage();
            }
            updateColors();
            return;
        }

        if (tabAnimation == null) {
            return;
        }

        final Resources resources = getContext().getResources();
        final int replacementIcon = tabAnimation.getReplacementIcon();
        if (replacementIcon != 0 && resources instanceof NimarkoIconResources
                && ((NimarkoIconResources) resources).hasReplacement(replacementIcon)) {
            final long generation = ((NimarkoIconResources) resources).getReplacementGeneration();
            final int densityDpi = resources.getDisplayMetrics().densityDpi;
            if (lastIconReplacement != replacementIcon || lastIconReplacementResources != resources
                    || lastIconReplacementGeneration != generation
                    || lastIconReplacementDensityDpi != densityDpi) {
                imageView.stopAnimation();
                imageView.clearAnimationDrawable();
                packIconDrawable = new FixedSizeIconDrawable(
                        resources.getDrawable(replacementIcon, getContext().getTheme()).mutate(),
                        (int) Math.ceil(24f * resources.getDisplayMetrics().density),
                        !isMainTab || tabAnimation != TabAnimation.CONTACTS && tabAnimation != TabAnimation.CALLS
                                && tabAnimation != TabAnimation.CHATS && tabAnimation != TabAnimation.SETTINGS);
                imageView.setImageDrawable(packIconDrawable);
                lastIconReplacement = replacementIcon;
                lastIconReplacementResources = resources;
                lastIconReplacementGeneration = generation;
                lastIconReplacementDensityDpi = densityDpi;
                lastIconAnimationRaw = 0;
                isSelectedAnimator.setValue(isSelected, false);
            }
            if (!animated) {
                isSelectedAnimator.setValue(isSelected, false);
            }
            updateColors();
            return;
        }
        if (lastIconReplacement != 0) {
            packIconDrawable = null;
            lastIconReplacement = 0;
            lastIconReplacementResources = null;
            lastIconReplacementDensityDpi = 0;
            imageView.clearAnimationDrawable();
            lastIconAnimationRaw = 0;
            animated = false;
        }
        if (tabAnimation.iconStatic != -1) {
            imageView.setImageDrawable(resources.getDrawable(tabAnimation.iconStatic, getContext().getTheme()));
            updateColors();
            return;
        }

        final int animationToSet = isSelected ?
            tabAnimation.iconToFilled : tabAnimation.iconToOutline;

        if (tabAnimation.endFrameMid != -1) {
            boolean update = lastIsSelected != isSelected;
            if (lastIconAnimationRaw != animationToSet) {
                lastIconAnimationRaw = animationToSet;
                imageView.setAnimation(animationToSet, 24, 24);
                update = true;
            }

            if (update) {
                final RLottieDrawable drawable = imageView.getAnimatedDrawable();
                if (drawable == null) {
                    return;
                }

                if (isSelected) {
                    drawable.setCustomEndFrame(tabAnimation.endFrameMid);
                    if (drawable.getCurrentFrame() >= tabAnimation.endFrameEnd - 2) {
                        drawable.setCurrentFrame(0, false);
                    }
                    if (drawable.getCurrentFrame() <= tabAnimation.endFrameMid) {
                        drawable.start();
                    } else {
                        drawable.setCurrentFrame(tabAnimation.endFrameMid);
                    }
                } else {
                    if (drawable.getCurrentFrame() >= tabAnimation.endFrameMid - 1) {
                        drawable.setCustomEndFrame(tabAnimation.endFrameEnd - 1);
                        drawable.start();
                    } else {
                        drawable.setCustomEndFrame(0);
                        drawable.setCurrentFrame(0);
                    }
                }
            }
            lastIsSelected = isSelected;
            return;
        }

        if (tabAnimation.iconToFilled != tabAnimation.iconToOutline) {
            if (lastIconAnimationRaw != animationToSet) {
                lastIconAnimationRaw = animationToSet;

                imageView.setAnimation(animationToSet, 24, 24);
                imageView.getAnimatedDrawable().setPlayInDirectionOfCustomEndFrame(false);
                if (animated) {
                    imageView.getAnimatedDrawable().setCurrentFrame(0);
                    imageView.playAnimation();
                } else {
                    imageView.getAnimatedDrawable().setProgress(0.99f);
                }
            }
            return;
        }

        if (imageView.getAnimatedDrawable() == null) {
            imageView.setAnimation(tabAnimation.iconToFilled, 24, 24);
        }

        final RLottieDrawable drawable = imageView.getAnimatedDrawable();
        if (drawable == null) {
            return;
        }

        if (!animated || lastIsSelected != isSelected) {
            lastIsSelected = isSelected;
            final int lastFrame = Math.max(0, drawable.getFramesCount() - 1);
            final int targetFrame = isSelected ? lastFrame : 0;
            drawable.setPlayInDirectionOfCustomEndFrame(true);
            drawable.setCustomEndFrame(targetFrame);
            if (!animated) {
                imageView.stopAnimation();
                drawable.setCurrentFrame(targetFrame);
            } else {
                if (drawable.getCurrentFrame() > lastFrame) {
                    drawable.setCurrentFrame(lastFrame);
                }
                imageView.playAnimation();
            }
        }
    }

    public static GlassTabView createMainTab(Context context, Theme.ResourcesProvider resourcesProvider, TabAnimation tabAnimation, @StringRes int stringRes) {
        GlassTabView tab = new GlassTabView(context);
        tab.isMainTab = true;
        tab.resourcesProvider = resourcesProvider;
        tab.tabAnimation = tabAnimation;
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.setContentDescription(LocaleController.getString(stringRes));
        tab.checkPlayAnimation(false);
        tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public static GlassTabView createAvatar(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, @StringRes int stringRes) {
        GlassTabView tab = new GlassTabView(context);
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.setContentDescription(LocaleController.getString(stringRes));
        tab.imageView.setVisibility(GONE);

        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);

        BackupImageView backupImageView = new BackupImageView(context);
        backupImageView.setForUserOrChat(user, avatarDrawable);
        backupImageView.setRoundRadius(dp(11));
        tab.backupImageView = backupImageView;

        tab.addView(backupImageView, LayoutHelper.createFrame(22, 22, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 5, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public void updateUserAvatar(int currentAccount) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);
        backupImageView.setForUserOrChat(user, avatarDrawable);
    }

    public static GlassTabView createAttachTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        GlassTabView tab = new GlassTabView(context);
        
        tab.setClickable(false);
        tab.setFocusable(false);
        tab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tab.resourcesProvider = resourcesProvider;
        tab.selfMeasure = true;
        tab.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tab.textView.setPadding(dp(8), 0, dp(8), 0);
        tab.checkPlayAnimation(false);
        tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public static GlassTabView createAttachBotTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        GlassTabView tab = new GlassTabView(context);
        tab.setClickable(false);
        tab.setFocusable(false);
        tab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tab.resourcesProvider = resourcesProvider;
        tab.selfMeasure = true;
        tab.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tab.textView.setPadding(dp(8), 0, dp(8), 0);
        tab.imageView.setVisibility(GONE);
        tab.checkPlayAnimation(false);
        tab.backupImageView = new BackupImageView(context);
        tab.addView(tab.backupImageView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public BackupImageView getBackupImageView() {
        return backupImageView;
    }

    public static GlassTabView createStaticTab(Context context, Theme.ResourcesProvider resourcesProvider,
                                               @DrawableRes int iconRes, @StringRes int stringRes) {
        return createStaticTab(context, resourcesProvider, iconRes, stringRes,
                app.nimarkogram.messenger.NimarkoConfig.showMainTabsTitle);
    }

    public static GlassTabView createStaticTab(Context context, Theme.ResourcesProvider resourcesProvider,
                                               @DrawableRes int iconRes, @StringRes int stringRes,
                                               boolean showTitle) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.imageView.setImageResource(iconRes);
        tab.setContentDescription(LocaleController.getString(stringRes));
        if (showTitle) {
            tab.textView.setText(LocaleController.getString(stringRes));
            tab.textView.setVisibility(View.VISIBLE);
            tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        } else {
            tab.textView.setVisibility(View.GONE);
            tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER, 0, 0, 0, 0));
        }
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.imageView.setColorFilter(new PorterDuffColorFilter(tab.colorDefault, PorterDuff.Mode.SRC_IN));
        tab.updateColors();
        return tab;
    }

    public void setIcon(@DrawableRes int iconRes) {
        imageView.setImageResource(iconRes);
    }

    public void setEnabledVisual(boolean enabled) {
        setAlpha(enabled ? 1f : 0.4f);
        invalidate();
    }

    public void setTitleVisible(boolean visible) {
        textView.setVisibility(visible ? VISIBLE : GONE);
        if (imageView.getVisibility() != GONE) {
            if (visible) {
                imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
            } else {
                imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER, 0, 0, 0, 0));
            }
        }
        if (backupImageView != null) {
            if (visible) {
                backupImageView.setLayoutParams(LayoutHelper.createFrame(22, 22, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 5, 0, 0));
            } else {
                
                backupImageView.setLayoutParams(LayoutHelper.createFrame(29, 29, Gravity.CENTER, 0, 0, 0, 0));
            }
        }
        requestLayout();
    }

    private boolean selfMeasure;
    private int additionalWidth;

    public void setAdditionalWidth(int additionalWidth) {
        this.additionalWidth = additionalWidth;
        this.selfMeasure = true;
    }

    public void setStaticIcon(@DrawableRes int drawableRes) {
        imageView.clearAnimationDrawable();
        imageView.setImageResource(drawableRes);
    }

    public float measureAttachTabWidth() {
        final float textWidth = measureTextWidth();
        final float padding = lerp(dpf2(16), dp(8), MathUtils.clamp((textWidth - dp(40)) / dp(16), 0, 1));
        return Math.min(dp(84), (int) (textWidth + padding * 2));
    }

    public float attachScale = 1;
    public void setAttachScale(float scale) {
        textView.setScaleX(scale);
        textView.setScaleY(scale);
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);
        if (backupImageView != null) {
            backupImageView.setScaleX(scale);
            backupImageView.setScaleY(scale);
        }
        attachScale = scale;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (selfMeasure) {
            final int width = (int) (measureAttachTabWidth()) + additionalWidth;
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public float measureTextWidth() {
        return defaultTextPaint.measureText(textView.getText().toString());
    }

    private TextPaint scaledTextPaint;

    @Override
    public float measureTextWidth(float textSizeDp) {
        if (scaledTextPaint == null) {
            scaledTextPaint = new TextPaint(defaultTextPaint);
        }
        scaledTextPaint.setTextSize(dp(textSizeDp));
        return scaledTextPaint.measureText(textView.getText().toString());
    }

    @Override
    public void setTextSizeDp(float textSizeDp) {
        final float px = dp(textSizeDp);
        if (textView.getTextSize() != px) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSizeDp);
            defaultTextPaint.setTextSize(px);
        }
    }

    private enum TabAnimationType {
        LOTTIE,
        STATIC
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Resources resources = getContext().getResources();
        if (tabAnimation != null && (packIconDrawable != null || (resources instanceof NimarkoIconResources
                && tabAnimation.getReplacementIcon() != 0
                && ((NimarkoIconResources) resources).hasReplacement(tabAnimation.getReplacementIcon())))) {
            checkPlayAnimation(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        finishPackSelection();
        super.onDetachedFromWindow();
    }

    private void finishPackSelection() {
        if (packIconDrawable != null) {
            isSelectedAnimator.setValue(isSelectedAnimator.getValue(), false);
            updateSelectorTarget(isSelectedAnimator.getValue(), false);
            packIconDrawable.setSelectionProgress(0f);
        }
    }

    private static final class FixedSizeIconDrawable extends Drawable implements Drawable.Callback {
        private final Drawable icon;
        private final int size;
        private final boolean pulseOnSelection;
        private float selectionProgress;

        private FixedSizeIconDrawable(Drawable icon, int size, boolean pulseOnSelection) {
            this.icon = icon;
            this.size = size;
            this.pulseOnSelection = pulseOnSelection;
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
            if (!pulseOnSelection || selectionProgress <= 0f || selectionProgress >= 1f || !SharedConfig.animationsEnabled()
                    || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
                icon.draw(canvas);
                return;
            }
            Rect bounds = getBounds();
            int save = canvas.save();
            canvas.clipRect(bounds);
            float motion = (float) Math.sin(Math.PI * selectionProgress);
            float scale = 1f - 0.24f * motion;
            canvas.scale(scale, scale, bounds.exactCenterX(), bounds.exactCenterY());
            icon.draw(canvas);
            canvas.restoreToCount(save);
        }

        private void setSelectionProgress(float progress) {
            selectionProgress = progress;
            invalidateSelf();
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

    public enum TabAnimation {
        CONTACTS(R.raw.tab_contacts),
        CALLS(R.raw.tab_calls),
        CHATS(R.raw.tab_chats),
        SETTINGS(R.raw.tab_settings),

        CHECKLIST(R.raw.tab_checklist, R.raw.tab_checklist_reverse),
        COLORS(R.raw.tab_colors, R.raw.tab_colors_reverse),
        FILES(R.raw.tab_files, R.raw.tab_files_reverse),
        GALLERY(R.raw.tab_gallery, R.raw.tab_gallery_reverse),
        GIFT(R.raw.tab_gift, R.raw.tab_gift_reverse),
        LOCATION(R.raw.tab_location, R.raw.tab_location_reverse),
        STICKER(R.raw.tab_sticker, R.raw.tab_sticker_reverse),
        EMOJI(R.raw.tab_emoji, R.raw.tab_emoji_reverse),
        MODELS(R.raw.tab_models, R.raw.tab_models_reverse),
        MUSIC(R.raw.tab_music, R.raw.tab_music_reverse),
        POLL(R.raw.tab_poll, R.raw.tab_poll_reverse),
        SYMBOLS(R.raw.tab_symbols, R.raw.tab_symbols_reverse),
        REPLIES(R.raw.tab_reply, R.raw.tab_reply_reverse),
        WALLET(R.raw.tab_wallet, R.raw.tab_wallet_reverse),
        LINK(TabAnimationType.STATIC, R.drawable.tabs_link_24),
        ARTICLE(R.raw.tab_article, R.raw.tab_article_reverse),

        GRAM(R.raw.gram, R.raw.gram_reverse),
        BOOSTS(R.raw.boosts, 25, 49),
        MONETIZATION(R.raw.monetize, 19, 45);

        public final @RawRes int iconToFilled;
        public final @RawRes int iconToOutline;
        public final @DrawableRes int iconStatic;
        public final int endFrameMid, endFrameEnd;

        private int getReplacementIcon() {
            switch (this) {
                case CONTACTS: return R.drawable.msg_contacts;
                case CALLS: return R.drawable.msg_calls;
                case CHATS: return R.drawable.msg_discussion;
                case SETTINGS: return R.drawable.msg_settings;
                case CHECKLIST: return R.drawable.iv_todo;
                case COLORS: return R.drawable.msg_palette;
                case FILES: return R.drawable.msg_filled_data_files;
                case GALLERY: return R.drawable.msg_photos;
                case GIFT: return R.drawable.menu_gift;
                case LOCATION: return R.drawable.msg_location;
                case STICKER: return R.drawable.msg_sticker;
                case EMOJI: return R.drawable.msg_emoji_smiles;
                case MODELS: return R.drawable.menu_feature_unique;
                case MUSIC: return R.drawable.msg_filled_data_music;
                case POLL: return R.drawable.filled_chatlist_poll;
                case SYMBOLS: return R.drawable.msg_reactions;
                case REPLIES: return R.drawable.menu_quickreply;
                case WALLET: return 0;
                case LINK: return R.drawable.tabs_link_24;
                case ARTICLE: return R.drawable.large_article;
                case BOOSTS: return R.drawable.large_boosts;
                case MONETIZATION: return R.drawable.large_monetize;
                case GRAM: return 0;
                default: return 0;
            }
        }
        TabAnimation(int iconRes, int endFrameMid, int endFrameEnd) {
            this.iconToFilled = iconRes;
            this.iconToOutline = iconRes;
            this.endFrameMid = endFrameMid;
            this.endFrameEnd = endFrameEnd;
            this.iconStatic = -1;
        }

        TabAnimation(TabAnimationType type, int icon) {
            if (type == TabAnimationType.LOTTIE) {
                this.iconToFilled = icon;
                this.iconToOutline = icon;
                this.iconStatic = -1;
            } else {
                this.iconStatic = icon;
                this.iconToFilled = -1;
                this.iconToOutline = -1;
            }
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
        }

        TabAnimation(int iconRes) {
            this.iconToFilled = iconRes;
            this.iconToOutline = iconRes;
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
            this.iconStatic = -1;
        }

        TabAnimation(int iconToFilled, int iconToOutline) {
            this.iconToFilled = iconToFilled;
            this.iconToOutline = iconToOutline;
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
            this.iconStatic = -1;
        }
    }

    public void setTabAnimation(TabAnimation animation) {
        tabAnimation = animation;
        tabAnimationBot = null;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;
        lastIconReplacement = 0;
        lastIconReplacementResources = null;
        lastIconReplacementDensityDpi = 0;
        packIconDrawable = null;
        imageView.stopAnimation();
        imageView.clearAnimationDrawable();
        checkPlayAnimation(false);
    }

    public void setText(CharSequence text) {
        textView.setText(text);
        setContentDescription(text);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
        info.setSelected(isTabSelected());
        info.setClickable(hasOnClickListeners());
    }

    private AvatarDrawable avatarDrawable;

    public void setAttachBot(TLRPC.User user, TLRPC.TL_attachMenuBot bot, int currentAccount) {
        if (user == null || bot == null) {
            return;
        }
        tabAnimation = null;
        tabAnimationBot = bot;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;
        textView.setText(bot.short_name);

        backupImageView.setRoundRadius(0);
        backupImageView.setSize(dp(24), dp(24));
        backupImageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        needUpdateBackupViewColor = true;
        checkPlayAnimation(false);
        updateColors();
        invalidate();
    }

    public void setAttachBotUser(TLRPC.User user, int currentAccount) {
        if (user == null) {
            return;
        }
        tabAnimation = null;
        tabAnimationBot = null;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;

        textView.setText(ContactsController.formatName(user.first_name, user.last_name));
        if (avatarDrawable == null) {
            avatarDrawable = new AvatarDrawable();
        }
        avatarDrawable.setInfo(currentAccount, user);
        backupImageView.setForUserOrChat(user, avatarDrawable);
        backupImageView.setSize(-1, -1);
        backupImageView.setRoundRadius(dp(11.33f));
        backupImageView.setLayoutParams(LayoutHelper.createFrame(22, 22, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 5, 0, 0));
        backupImageView.setColorFilter(null);
        needUpdateBackupViewColor = false;
        invalidate();
    }

    public void onPreBind() {

    }
}
