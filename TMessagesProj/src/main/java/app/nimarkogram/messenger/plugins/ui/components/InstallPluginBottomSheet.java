package app.nimarkogram.messenger.plugins.ui.components;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.style.StrikethroughSpan;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Stories.recorder.HintView2;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import app.nimarkogram.messenger.plugins.Plugin;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginUiDiskExecutor;
import app.nimarkogram.messenger.plugins.ui.PluginUiRegistry;
import app.nimarkogram.messenger.plugins.ui.PluginsActivity;
import app.nimarkogram.messenger.plugins.ui.components.VerticalImageSpan;
import app.nimarkogram.messenger.utils.chats.ChatUtils;
import app.nimarkogram.messenger.utils.text.LocaleUtils;

public class InstallPluginBottomSheet extends BottomSheet {
     
    public interface HostInstallAuthority {
        boolean transfer(Utilities.Callback<String> callback);
        void revoke();
    }

    private enum InstallTransferState {
        UNBOUND,
        BOUND,
        TRANSFERRING,
        QUEUED,
        REVOKED
    }

    private static final long EXTERNAL_VIEW_SOURCE_GRACE_MS = 60_000L;
    private HintView2 currentHint;
    private boolean enableAfterInstallation = false;
    private FrameLayout container;
    private ButtonWithCounterView installBtn;
    private volatile boolean installing = false;
    private final BaseFragment hostFragment;
    private final View hostFragmentView;
    private final String pickerImportSourcePath;
    private volatile HostInstallAuthority hostInstallAuthority;
    private final AtomicReference<InstallTransferState> installTransferState =
            new AtomicReference<>(InstallTransferState.UNBOUND);
    private volatile long lifecycleEpoch = 1;
    private volatile long installOperationEpoch;
    private volatile long sourceViewOperationEpoch;
    private boolean pickerImportSourceClaimed;
    private boolean pickerImportReleaseDeferred;

    @Override
    public void setLastVisible(boolean z) {
        super.setLastVisible(z);
    }

    public InstallPluginBottomSheet(final BaseFragment baseFragment, final PluginsController.PluginValidationResult result, final PluginInstallParams params) {
        super(baseFragment.getParentActivity(), false, baseFragment.getResourceProvider());
        hostFragment = baseFragment;
        hostFragmentView = baseFragment.getFragmentView();
        pickerImportSourcePath = params != null ? params.filePath : null;
        
        Activity context = baseFragment.getParentActivity();
        fixNavigationBar();
        
        boolean isUpdate = PluginsController.getInstance().plugins.containsKey(result.plugin.getId());
        
        container = new FrameLayout(context);
        container.setClipChildren(false);
        container.setClipToPadding(false);

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setClipChildren(false);
        linearLayout.setClipToPadding(false);
        container.addView(linearLayout);

        if (result.plugin.getPack() != null && result.plugin.getIndex() >= 0) {
            BackupImageView iconView = new BackupImageView(context) {
                private final Paint paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Paint paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Drawable badgeDrawable;
                {
                    paintStroke.setStyle(Paint.Style.STROKE);
                    paintStroke.setStrokeWidth(AndroidUtilities.dp(4.0f));
                    paintFill.setStyle(Paint.Style.FILL);
                    Drawable d = ContextCompat.getDrawable(getContext(), R.drawable.plugin_large);
                    if (d != null) {
                        badgeDrawable = d.mutate();
                    } else {
                        badgeDrawable = null;
                    }
                }

                @Override
                @SuppressLint("DrawAllocation")
                protected void onDraw(Canvas canvas) {
                    Path path = new Path();
                    float fDp = AndroidUtilities.dp(12.0f);
                    path.addRoundRect(new RectF(0.0f, 0.0f, getWidth(), getHeight()), fDp, fDp, Path.Direction.CW);
                    canvas.save();
                    canvas.clipPath(path);
                    super.onDraw(canvas);
                    canvas.restore();

                    paintStroke.setColor(getThemedColor(Theme.key_dialogBackground));
                    paintFill.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                    float badgeX = getMeasuredWidth() - AndroidUtilities.dp(10.0f);
                    float badgeY = getMeasuredHeight() - AndroidUtilities.dp(10.0f);
                    float badgeRadius = AndroidUtilities.dp(12.0f);
                    canvas.drawCircle(badgeX, badgeY, badgeRadius, paintStroke);
                    canvas.drawCircle(badgeX, badgeY, badgeRadius, paintFill);
                    if (badgeDrawable != null) {
                        badgeDrawable.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
                        int size = AndroidUtilities.dp(16.0f); 
                        badgeDrawable.setBounds(
                                (int) (badgeX - size / 2),
                                (int) (badgeY - size / 2),
                                (int) (badgeX + size / 2),
                                (int) (badgeY + size / 2)
                        );
                        badgeDrawable.draw(canvas);
                    }
                }
            };
            iconView.setRoundRadius(AndroidUtilities.dp(12.0f));
            iconView.getImageReceiver().setAutoRepeat(1);
            iconView.getImageReceiver().setAutoRepeatCount(0);
            iconView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12.0f), getThemedColor(Theme.key_windowBackgroundGray)));
            linearLayout.addView(iconView, LayoutHelper.createLinear(78, 78, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));
            MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImageByIndex(iconView, result.plugin.getPack(), result.plugin.getIndex(), "150_150");
        } else {
            RLottieImageView lottieView = new RLottieImageView(context);
            lottieView.setScaleType(ImageView.ScaleType.CENTER);
            lottieView.setImageResource(R.drawable.plugin_large);
            lottieView.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
            lottieView.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(78.0f), getThemedColor(Theme.key_featuredStickers_addButton)));
            linearLayout.addView(lottieView, LayoutHelper.createLinear(78, 78, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));
        }

        TextView titleView = new TextView(context);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextSize(1, 18.0f);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText(result.plugin.getName());
        linearLayout.addView(titleView, LayoutHelper.createLinear(-1, -2, 0, 40, 16, 40, 0));

        EffectsTextView subtitleView = new EffectsTextView(context);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
        subtitleView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        subtitleView.setLinkTextColor(getThemedColor(Theme.key_dialogTextLink));
        subtitleView.setTextSize(1, 14.0f);
        int grayColor = Theme.key_windowBackgroundWhiteGrayText;
        subtitleView.setTextColor(getThemedColor(grayColor));

        SpannableStringBuilder ssb = new SpannableStringBuilder(LocaleController.getString(R.string.PluginVersion)).append(" ");
        if (isUpdate) {
            Plugin oldPlugin = PluginsController.getInstance().plugins.get(result.plugin.getId());
            if (oldPlugin != null) {
                int start = ssb.length();
                ssb.append(oldPlugin.getVersion()).append(" -> ").append(result.plugin.getVersion());
                ssb = VerticalImageSpan.createSpan(getContext(), R.drawable.msg_mini_arrow_mediathin, ssb.toString(), "->", grayColor, this.resourcesProvider);
                ssb.setSpan(new StrikethroughSpan(), start, start + oldPlugin.getVersion().length(), 33);
            } else {
                ssb.append(result.plugin.getVersion());
            }
        } else {
            ssb.append(result.plugin.getVersion());
        }
        ssb.append(" • ").append(LocaleUtils.formatWithUsernames(result.plugin.getAuthor(), baseFragment, this::dismiss));
        subtitleView.setText(ssb);
        linearLayout.addView(subtitleView, LayoutHelper.createLinear(-1, -2, 0, 21, 4, 21, 0));

        if (params.incompatible) {
            int badgeIcon = R.drawable.msg_warning;
            int badgeColor = getThemedColor(Theme.key_color_yellow);

            LinearLayout badgeLayout = new LinearLayout(context);
            ScaleStateListAnimator.apply(badgeLayout, 0.05f, 1.5f);
            badgeLayout.setOrientation(LinearLayout.HORIZONTAL);
            badgeLayout.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20.0f), AndroidUtilities.dp(20.0f), AndroidUtilities.multiplyAlphaComponent(badgeColor, 0.1f)));
            badgeLayout.setPadding(AndroidUtilities.dp(12.0f), AndroidUtilities.dp(6.0f), AndroidUtilities.dp(16.0f), AndroidUtilities.dp(6.0f));
            badgeLayout.setGravity(Gravity.CENTER);

            ImageView badgeImg = new ImageView(context);
            badgeImg.setImageResource(badgeIcon);
            badgeImg.setColorFilter(new PorterDuffColorFilter(badgeColor, PorterDuff.Mode.SRC_IN));
            badgeLayout.addView(badgeImg, LayoutHelper.createLinear(14, 14, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

            TextView badgeTxt = new TextView(context);
            badgeTxt.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
            badgeTxt.setTextColor(badgeColor);
            badgeTxt.setTextSize(1, 13.0f);
            badgeTxt.setText(LocaleController.formatString(R.string.PluginIncompatible));
            badgeLayout.addView(badgeTxt);

            linearLayout.addView(badgeLayout, LayoutHelper.createLinear(-2, -2, Gravity.CENTER, 0, 12, 0, 0));
            
            badgeLayout.setOnClickListener(v -> showIncompatibleHint(badgeLayout, params));
            AndroidUtilities.runOnUIThread(() -> {
                if (!isDismissed() && badgeLayout.isAttachedToWindow()) {
                    showIncompatibleHint(badgeLayout, params);
                }
            }, 600L);
        }

        EffectsTextView descView = new EffectsTextView(context);
        descView.setGravity(Gravity.CENTER_HORIZONTAL);
        descView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
        descView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        descView.setLinkTextColor(getThemedColor(Theme.key_dialogTextLink));
        descView.setTextSize(1, 15.0f);
        descView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        descView.setText(LocaleUtils.fullyFormatText(result.plugin.getDescription(), baseFragment, this::dismiss));
        linearLayout.addView(descView, LayoutHelper.createLinear(-1, -2, 0, 21, 28, 21, 0));

        installBtn = new ButtonWithCounterView(context, true, this.resourcesProvider);
        installBtn.setText(LocaleController.getString((int) (isUpdate ? R.string.UpdatePlugin : R.string.InstallPlugin)), false);
        installBtn.setSubText(null, false);
        installBtn.setOnClickListener(v -> installPlugin(params, result, baseFragment, isUpdate));
        
        linearLayout.addView(installBtn, LayoutHelper.createLinear(-1, 48, 0, 16, 28, 16, 16));

        if (!result.plugin.isEnabled()) {
            CheckBox2 checkBox = new CheckBox2(context, 21, this.resourcesProvider);
            checkBox.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
            checkBox.setDrawUnchecked(true);
            checkBox.setChecked(this.enableAfterInstallation, false);
            checkBox.setDrawBackgroundAsArc(10);
            
            TextView checkText = new TextView(context);
            checkText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            checkText.setTextSize(1, 14.0f);
            checkText.setText(LocaleController.getString(R.string.EnableAfterInstallation));

            FrameLayout checkFrame = new FrameLayout(context);
            checkFrame.addView(checkBox, LayoutHelper.createFrame(21, 21.0f, Gravity.CENTER, 0.0f, 0.0f, 0.0f, 0.0f));

            LinearLayout checkLayout = new LinearLayout(context);
            checkLayout.setOrientation(LinearLayout.HORIZONTAL);
            checkLayout.setPadding(AndroidUtilities.dp(8.0f), AndroidUtilities.dp(6.0f), AndroidUtilities.dp(10.0f), AndroidUtilities.dp(6.0f));
            checkLayout.setGravity(Gravity.CENTER_VERTICAL);
            checkLayout.setFocusable(true);
            checkLayout.setContentDescription(checkText.getText());
            checkLayout.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.CheckBox");
                    info.setCheckable(true);
                    info.setChecked(checkBox.isChecked());
                    info.setClickable(true);
                }
            });
            
            checkLayout.addView(checkFrame, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
            checkLayout.addView(checkText, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));
            
            checkLayout.setOnClickListener(v -> {
                checkBox.setChecked(!checkBox.isChecked(), true);
                this.enableAfterInstallation = checkBox.isChecked();
                checkLayout.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            });
            
            ScaleStateListAnimator.apply(checkLayout, 0.05f, 1.2f);
            checkLayout.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 8, 8));
            linearLayout.addView(checkLayout, LayoutHelper.createLinear(-2, -2, Gravity.CENTER, 0, 0, 0, 8));
        }

        ImageView openBtn = new ImageView(context);
        ScaleStateListAnimator.apply(openBtn, 0.15f, 1.5f);
        openBtn.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.msg_openin).mutate());
        openBtn.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        openBtn.setScaleType(ImageView.ScaleType.CENTER);
        openBtn.setContentDescription(LocaleController.getString(R.string.Open));
        openBtn.setOnClickListener(v -> openSourceFile(params, baseFragment));
        openBtn.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector), 1, AndroidUtilities.dp(20.0f)));
        container.addView(openBtn, LayoutHelper.createFrame(40, 40.0f, Gravity.TOP | Gravity.END, 0.0f, 16.0f, 16.0f, 0.0f));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setClipChildren(false);
        scrollView.setClipToPadding(false);
        scrollView.addView(container);
        setCustomView(scrollView);
    }

    public final synchronized boolean bindHostInstallAuthority(
            HostInstallAuthority authority) {
        if (authority == null
                || installTransferState.get()
                        != InstallTransferState.UNBOUND
                || isDismissed()
                || !isHostFragmentActive()) {
            return false;
        }
        hostInstallAuthority = authority;
        if (installTransferState.compareAndSet(
                InstallTransferState.UNBOUND,
                InstallTransferState.BOUND)) {
            return true;
        }
        hostInstallAuthority = null;
        return false;
    }

    public final void onHostFragmentTeardown() {
        
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(this::onHostFragmentTeardown);
            return;
        }
        revokeUnqueuedAuthority();
        releasePickerImportSource(false);
    }

    private void showIncompatibleHint(View anchor, PluginInstallParams params) {
        if (isDismissed() || !anchor.isAttachedToWindow()) {
            return;
        }
        if (currentHint != null) {
            currentHint.hide();
            currentHint = null;
        }
        
        HintView2 hint = new HintView2(getContext(), 3);
        hint.setMultilineText(true);
        hint.setBgColor(getThemedColor(Theme.key_undo_background));
        hint.setTextColor(getThemedColor(Theme.key_undo_infoColor));
        
        hint.setText(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginIncompatibleInfo)));
        
        hint.setTextAlign(Layout.Alignment.ALIGN_CENTER);
        hint.allowBlur(true);
        hint.setRounding(12.0f);
        hint.setMaxWidthPx(HintView2.cutInFancyHalf(hint.getText(), hint.getTextPaint()));
        
        this.container.addView(hint, LayoutHelper.createFrame(-1, 100.0f, Gravity.TOP | Gravity.LEFT, 32.0f, 0.0f, 32.0f, 0.0f));
        this.currentHint = hint;
        
        this.container.post(() -> {
            if (isDismissed() || currentHint != hint
                    || hint.getParent() != container
                    || !anchor.isAttachedToWindow()
                    || !container.isAttachedToWindow()) {
                return;
            }
            int[] anchorPos = new int[2];
            anchor.getLocationInWindow(anchorPos);
            int[] containerPos = new int[2];
            this.container.getLocationInWindow(containerPos);
            
            int relativeY = anchorPos[1] - containerPos[1];
            int relativeX = anchorPos[0] - containerPos[0];
            
            hint.setTranslationY(relativeY - AndroidUtilities.dp(100.0f) - AndroidUtilities.dp(6.0f));
            
            float jointX = -AndroidUtilities.dp(32.0f) + relativeX + (anchor.getMeasuredWidth() / 2.0f);
            hint.setJointPx(0.0f, jointX);
            
            hint.setDuration(5500L);
            hint.show();
        });
    }

    private void installPlugin(PluginInstallParams params, PluginsController.PluginValidationResult result, BaseFragment fragment, boolean update) {
        app.nimarkogram.messenger.plugins.PluginDebugLog.log("InstallBtn TAP id=" + (result != null && result.plugin != null ? result.plugin.getId() : "?")
                + " file=" + (params != null ? params.filePath : "?") + " update=" + update + " installingAlready=" + installing);
        final HostInstallAuthority authority;
        final long callbackLifecycleEpoch;
        final long operationEpoch;
        synchronized (this) {
            if (installing
                    || installTransferState.get()
                            != InstallTransferState.BOUND
                    || hostInstallAuthority == null
                    || !isHostFragmentActive()) {
                showInstallTransferError(fragment, result);
                return;
            }
            callbackLifecycleEpoch = lifecycleEpoch;
            operationEpoch = ++installOperationEpoch;
            if (!installTransferState.compareAndSet(
                    InstallTransferState.BOUND,
                    InstallTransferState.TRANSFERRING)) {
                showInstallTransferError(fragment, result);
                return;
            }
            installing = true;
            authority = hostInstallAuthority;
        }

        final boolean shouldEnableAfterInstallation = enableAfterInstallation;
        installBtn.setClickable(false);

        if (!isTransferAttemptCurrent(
                authority, callbackLifecycleEpoch, operationEpoch)) {
            failInstallTransfer(
                    operationEpoch, fragment, result);
            return;
        }

        final boolean queued;
        try {
            queued = authority.transfer(error ->
                    onInstallCompleted(
                            error, result, fragment, update,
                            shouldEnableAfterInstallation,
                            callbackLifecycleEpoch,
                            operationEpoch));
        } catch (Throwable transferFailure) {
            app.nimarkogram.messenger.plugins.PluginDebugLog.log(
                    "Install authority transfer failed", transferFailure);
            failInstallTransfer(
                    operationEpoch, fragment, result);
            return;
        }
        if (!queued) {
            failInstallTransfer(
                    operationEpoch, fragment, result);
            return;
        }

        boolean queueConfirmed;
        synchronized (this) {
            queueConfirmed =
                    lifecycleEpoch == callbackLifecycleEpoch
                    && installOperationEpoch == operationEpoch
                    && hostInstallAuthority == authority
                    && installTransferState.compareAndSet(
                            InstallTransferState.TRANSFERRING,
                            InstallTransferState.QUEUED);
            if (queueConfirmed) {
                hostInstallAuthority = null;
            }
        }
        if (!queueConfirmed) {
            
            app.nimarkogram.messenger.plugins.PluginDebugLog.log(
                    "Install queue confirmation lost its lifecycle nonce");
            try {
                authority.revoke();
            } catch (Throwable revokeFailure) {
                app.nimarkogram.messenger.plugins.PluginDebugLog.log(
                        "Could not revoke unconfirmed queued install",
                        revokeFailure);
            }
            finishInstallOperation(operationEpoch);
            return;
        }

        try {
            dismiss();
        } catch (Throwable dismissFailure) {
            
            app.nimarkogram.messenger.plugins.PluginDebugLog.log(
                    "Install sheet dismiss failed after queue confirmation",
                    dismissFailure);
        }
    }

    private void onInstallCompleted(
            String error,
            PluginsController.PluginValidationResult result,
            BaseFragment fragment,
            boolean update,
            boolean shouldEnableAfterInstallation,
            long callbackLifecycleEpoch,
            long operationEpoch) {
        AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                boolean canShow = isInstallUiCurrent(
                        fragment, callbackLifecycleEpoch, operationEpoch);
                if (!finishInstallOperation(operationEpoch) || !canShow) {
                    return;
                }
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.error,
                        AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginInstallError, result.plugin.getName())),
                        LocaleUtils.createCopySpan(fragment),
                        () -> {
                            if (PluginUiRegistry.isFragmentUiActive(fragment)) {
                                AndroidUtilities.addToClipboard(error);
                            }
                        }
                ).show();
            } else if (shouldEnableAfterInstallation) {
                PluginsController.getInstance().setPluginEnabled(result.plugin.getId(), true, err -> {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (err == null) {
                            showSuccessBulletin(
                                    fragment,
                                    result.plugin,
                                    update,
                                    callbackLifecycleEpoch,
                                    operationEpoch);
                            return;
                        }
                        boolean canShow = isInstallUiCurrent(
                                fragment, callbackLifecycleEpoch, operationEpoch);
                        if (!finishInstallOperation(operationEpoch) || !canShow) {
                            return;
                        }
                        BulletinFactory.of(fragment).createSimpleBulletin(
                                R.raw.error,
                                AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginInstalledButFailedToEnable, result.plugin.getName())),
                                LocaleUtils.createCopySpan(fragment),
                                () -> {
                                    if (PluginUiRegistry.isFragmentUiActive(fragment)) {
                                        AndroidUtilities.addToClipboard(err);
                                    }
                                }
                        ).show();
                    });
                });
            } else {
                showSuccessBulletin(
                        fragment,
                        result.plugin,
                        update,
                        callbackLifecycleEpoch,
                        operationEpoch);
            }
        });
    }

    private void failInstallTransfer(
            long operationEpoch,
            BaseFragment fragment,
            PluginsController.PluginValidationResult result) {
        boolean canShow = isHostFragmentActive();
        revokeUnqueuedAuthority();
        finishInstallOperation(operationEpoch);
        try {
            dismiss();
        } catch (Throwable dismissFailure) {
            app.nimarkogram.messenger.plugins.PluginDebugLog.log(
                    "Could not dismiss rejected install sheet",
                    dismissFailure);
        }
        if (canShow && PluginUiRegistry.isFragmentUiActive(fragment)) {
            showInstallTransferError(fragment, result);
        }
    }

    private void showInstallTransferError(
            BaseFragment fragment,
            PluginsController.PluginValidationResult result) {
        if (!PluginUiRegistry.isFragmentUiActive(fragment)) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(
                R.raw.error,
                AndroidUtilities.replaceTags(
                        LocaleController.formatString(
                                R.string.PluginInstallError,
                                result != null && result.plugin != null
                                        ? result.plugin.getName()
                                        : ""))).show();
    }

    private boolean isHostFragmentActive() {
        return hostFragment != null
                && hostFragment.getFragmentView() == hostFragmentView
                && hostFragmentView != null
                && hostFragmentView.isAttachedToWindow()
                && PluginUiRegistry.isFragmentUiActive(hostFragment);
    }

    private boolean isTransferAttemptCurrent(
            HostInstallAuthority authority,
            long expectedLifecycleEpoch,
            long expectedOperationEpoch) {
        synchronized (this) {
            return authority != null
                    && authority == hostInstallAuthority
                    && lifecycleEpoch == expectedLifecycleEpoch
                    && installOperationEpoch == expectedOperationEpoch
                    && installTransferState.get()
                            == InstallTransferState.TRANSFERRING
                    && isHostFragmentActive();
        }
    }

    private void revokeUnqueuedAuthority() {
        final HostInstallAuthority authority;
        synchronized (this) {
            while (true) {
                InstallTransferState state = installTransferState.get();
                if (state == InstallTransferState.QUEUED
                        || state == InstallTransferState.REVOKED) {
                    return;
                }
                if (installTransferState.compareAndSet(
                        state, InstallTransferState.REVOKED)) {
                    break;
                }
            }
            lifecycleEpoch++;
            authority = hostInstallAuthority;
            hostInstallAuthority = null;
        }
        if (authority != null) {
            try {
                authority.revoke();
            } catch (Throwable revokeFailure) {
                app.nimarkogram.messenger.plugins.PluginDebugLog.log(
                        "Could not revoke install sheet authority",
                        revokeFailure);
            }
        }
    }

    private boolean isInstallOperationEpochCurrent(long expectedOperationEpoch) {
        return installOperationEpoch == expectedOperationEpoch;
    }

    private boolean isInstallUiCurrent(
            BaseFragment fragment,
            long expectedLifecycleEpoch,
            long expectedOperationEpoch) {
        return isInstallOperationEpochCurrent(expectedOperationEpoch)
                && lifecycleEpoch == expectedLifecycleEpoch
                && fragment == hostFragment
                && isHostFragmentActive();
    }

    private boolean finishInstallOperation(long expectedOperationEpoch) {
        synchronized (this) {
            if (!isInstallOperationEpochCurrent(expectedOperationEpoch)) {
                return false;
            }
            installOperationEpoch++;
            installing = false;
            return true;
        }
    }

    private void openSourceFile(PluginInstallParams params, BaseFragment fragment) {
        final long callbackLifecycleEpoch = lifecycleEpoch;
        final long operationEpoch = ++sourceViewOperationEpoch;
        if (!isSourceViewUiCurrent(
                fragment, callbackLifecycleEpoch, operationEpoch)) {
            return;
        }
        final File file = new File(params.filePath);
        boolean accepted = PluginUiDiskExecutor.execute(
                "inspect plugin source",
                () -> {
                    if (!isSourceViewIoCurrent(
                            callbackLifecycleEpoch, operationEpoch)) {
                        return;
                    }
                    final boolean exists = file.exists();
                    if (!isSourceViewIoCurrent(
                            callbackLifecycleEpoch, operationEpoch)) {
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() ->
                            publishOpenSourceResult(
                                    file,
                                    exists,
                                    fragment,
                                    callbackLifecycleEpoch,
                                    operationEpoch));
                });
        if (!accepted
                && isSourceViewUiCurrent(
                        fragment,
                        callbackLifecycleEpoch,
                        operationEpoch)) {
            publishOpenSourceResult(
                    file,
                    false,
                    fragment,
                    callbackLifecycleEpoch,
                    operationEpoch);
        }
    }

    private void publishOpenSourceResult(
            File file,
            boolean exists,
            BaseFragment fragment,
            long callbackLifecycleEpoch,
            long operationEpoch) {
        if (!isSourceViewUiCurrent(
                fragment, callbackLifecycleEpoch, operationEpoch)) {
            return;
        }
        if (exists) {
            boolean opened = AndroidUtilities.openForView(
                    file, file.getName(), "text/plain",
                    fragment.getParentActivity(),
                    fragment.getResourceProvider(), false);
            if (opened && deferPickerImportRelease()) {
                AndroidUtilities.runOnUIThread(
                        () -> releasePickerImportSource(true),
                        EXTERNAL_VIEW_SOURCE_GRACE_MS);
            }
        }
        dismiss();
    }

    private boolean isSourceViewIoCurrent(
            long expectedLifecycleEpoch,
            long expectedOperationEpoch) {
        return lifecycleEpoch == expectedLifecycleEpoch
                && sourceViewOperationEpoch == expectedOperationEpoch;
    }

    private boolean isSourceViewUiCurrent(
            BaseFragment fragment,
            long expectedLifecycleEpoch,
            long expectedOperationEpoch) {
        return isSourceViewIoCurrent(
                expectedLifecycleEpoch, expectedOperationEpoch)
                && fragment == hostFragment
                && !isDismissed()
                && isHostFragmentActive();
    }

    private void showSuccessBulletin(
            BaseFragment fragment,
            Plugin plugin,
            boolean update,
            long callbackLifecycleEpoch,
            long operationEpoch) {
        if (!isInstallUiCurrent(
                fragment, callbackLifecycleEpoch, operationEpoch)) {
            finishInstallOperation(operationEpoch);
            return;
        }
        String name = plugin.getName();
        String text = LocaleController.formatString(update ? R.string.PluginUpdated : R.string.PluginInstalled, name);

        if (plugin.getPack() == null || plugin.getIndex() < 0) {
            if (finishInstallOperation(operationEpoch)) {
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.contact_check, AndroidUtilities.replaceTags(text)).show();
            }
            return;
        }

        TLRPC.TL_inputStickerSetShortName stickerSet = new TLRPC.TL_inputStickerSetShortName();
        stickerSet.short_name = plugin.getPack();
        
        AtomicBoolean shown = new AtomicBoolean(false);
        Runnable fallback = () -> {
            if (shown.getAndSet(true)) {
                return;
            }
            boolean canShow = isInstallUiCurrent(
                    fragment, callbackLifecycleEpoch, operationEpoch);
            if (finishInstallOperation(operationEpoch) && canShow) {
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.contact_check, AndroidUtilities.replaceTags(text)).show();
            }
        };
        AndroidUtilities.runOnUIThread(fallback, 300L);

        MediaDataController.getInstance(UserConfig.selectedAccount).getStickerSet(stickerSet, 0, true, (res) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (shown.get()) return;
                if (!isInstallUiCurrent(
                        fragment, callbackLifecycleEpoch, operationEpoch)) {
                    if (!shown.getAndSet(true)) {
                        AndroidUtilities.cancelRunOnUIThread(fallback);
                        finishInstallOperation(operationEpoch);
                    }
                    return;
                }
                
                TLRPC.TL_messages_stickerSet set = (TLRPC.TL_messages_stickerSet) res;
                if (set != null && set.documents != null && plugin.getIndex() < set.documents.size()) {
                    TLRPC.Document doc = set.documents.get(plugin.getIndex());
                    if (doc != null && !shown.getAndSet(true)) {
                        AndroidUtilities.cancelRunOnUIThread(fallback);
                        if (finishInstallOperation(operationEpoch)) {
                            BulletinFactory.of(fragment).createSimpleBulletin(
                                    doc, AndroidUtilities.replaceTags(text)).show();
                        }
                    }
                }
            });
        });
    }

    @Override
    public void show() {
        boolean claimed = claimPickerImportSource();
        try {
            super.show();
            if (!isShowing() || isDismissed()) {
                releasePickerImportSource(true);
            }
        } catch (RuntimeException | Error failure) {
            if (claimed) {
                releasePickerImportSource(true);
            }
            throw failure;
        }
    }

    private synchronized boolean claimPickerImportSource() {
        if (pickerImportSourceClaimed) {
            return true;
        }
        pickerImportSourceClaimed =
                PluginsActivity.claimPickerImportSource(
                        pickerImportSourcePath);
        return pickerImportSourceClaimed;
    }

    private synchronized boolean deferPickerImportRelease() {
        if (!pickerImportSourceClaimed) {
            return false;
        }
        pickerImportReleaseDeferred = true;
        return true;
    }

    private void releasePickerImportSource(boolean force) {
        final String path;
        synchronized (this) {
            if (!pickerImportSourceClaimed
                    || (!force && pickerImportReleaseDeferred)) {
                return;
            }
            pickerImportSourceClaimed = false;
            pickerImportReleaseDeferred = false;
            path = pickerImportSourcePath;
        }
        PluginsActivity.releasePickerImportSource(path);
    }

    @Override
    public void dismiss() {
        try {
            sourceViewOperationEpoch++;
            if (currentHint != null) {
                currentHint.hide();
                currentHint = null;
            }
            super.dismiss();
        } finally {
            
            revokeUnqueuedAuthority();
            releasePickerImportSource(false);
        }
    }
    
    @Override
    protected void onSwipeStarts() {
        if (currentHint != null) {
            currentHint.hide();
            currentHint = null;
        }
    }

    public static class PluginInstallParams {
        public String filePath;
        public boolean incompatible;

        public PluginInstallParams(String path, boolean incompatible) {
            this.filePath = path;
            this.incompatible = incompatible;
        }

        public static PluginInstallParams of(MessageObject message) {
            String path = ChatUtils.getInstance().getPathToMessage(message);
            boolean incompatible = pluginCompatibilityCheck(path);
            return new PluginInstallParams(path, incompatible);
        }

        private static boolean pluginCompatibilityCheck(String path) {
            
            return false;
        }
    }
}
