 
package app.nimarkogram.messenger.updater;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.StickerImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public class NimarkoUpdaterSheet extends BottomSheet implements NimarkoUpdater.DownloadUiOwner {

    private static final String STICKER_PACK = "PixelAnimeGirls";
    private static final int STICKER_NUM = 11;

    private BaseFragment fragment;
    private Theme.ResourcesProvider resourcesProvider;
    private boolean downloadButtonClicked = false;
    private boolean downloadFinished = false;
    private boolean bindingDetached;
    private long downloadBindingToken;
    private ButtonWithCounterView downloadButton;

    public NimarkoUpdaterSheet(Context context, Theme.ResourcesProvider resourcesProvider, boolean available, NimarkoUpdater.Update update) {
        super(context, false, resourcesProvider);
        setOpenNoDelay(true);
        fixNavigationBar();

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        FrameLayout header = new FrameLayout(context);
        linearLayout.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 10, 0, 10));

        if (available) {
            
            setCanDismissWithSwipe(true);
            setCanDismissWithTouchOutside(true);

            StickerImageView imageView = new StickerImageView(context, currentAccount);
            imageView.setStickerPackName(STICKER_PACK);
            imageView.setStickerNum(STICKER_NUM);
            imageView.getImageReceiver().setAutoRepeat(1);
            imageView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(16));
                }
            });
            imageView.setClipToOutline(true);
            header.addView(imageView, LayoutHelper.createFrame(60, 60, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            SimpleTextView nameView = new SimpleTextView(context);
            nameView.setTextSize(20);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            nameView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            nameView.setText(getString(R.string.UP_UpdateAvailable));
            header.addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 30, Gravity.LEFT, 75, 5, 0, 0));

            if (!TextUtils.isEmpty(update.uploadDate)) {
                AnimatedTextView timeView = new AnimatedTextView(context, true, true, false);
                timeView.setAnimationProperties(0.7f, 0, 450, CubicBezierInterpolator.EASE_OUT_QUINT);
                timeView.setIgnoreRTL(!LocaleController.isRTL);
                timeView.adaptWidth = false;
                timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                timeView.setTextSize(AndroidUtilities.dp(13));
                timeView.setTypeface(AndroidUtilities.bold());
                timeView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                timeView.setText(update.uploadDate);
                header.addView(timeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 20, Gravity.LEFT, 75, 35, 0, 0));
            }
        }

        TextCell version = new TextCell(context, resourcesProvider);
        version.setBackground(createRowPressedBackground(resourcesProvider));
        if (available) {
            version.setTextAndValueAndIcon(getString(R.string.UP_Version), update.version.replaceAll("v|-beta|-force", ""), R.drawable.msg_info, true);
        } else {
            version.setTextAndValueAndIcon(getString(R.string.UP_CurrentVersion), NimarkoUpdater.getCurrentVersionName(), R.drawable.msg_info, false);
        }
        version.setOnClickListener(v -> copyText(version.getTextView().getText() + ": " + version.getValueTextView().getText()));
        linearLayout.addView(version);

        View divider = new View(context) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawLine(0, AndroidUtilities.dp(1), getMeasuredWidth(), AndroidUtilities.dp(1), Theme.dividerPaint);
            }
        };

        FrameLayout buttonsView = new FrameLayout(context);
        buttonsView.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));

        if (available) {
            if (!TextUtils.isEmpty(update.changelog)) {
                TextCell changelog = new TextCell(context, resourcesProvider);
                changelog.setBackground(createRowPressedBackground(resourcesProvider));
                changelog.setTextAndIcon(getString(R.string.UP_Changelog), R.drawable.msg_log, false);
                linearLayout.addView(changelog);

                TextInfoPrivacyCell changelogTextView = new TextInfoPrivacyCell(context, resourcesProvider);
                changelogTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                changelogTextView.setText(NimarkoUpdater.replaceTags(update.changelog));
                linearLayout.addView(changelogTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            linearLayout.addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(1)));

            downloadButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            downloadButton.setFilled(true);
            downloadButton.setText(getUpdateSizeString(update), false);
            
            if (NimarkoUpdater.isUpdateDownloaded()) {
                downloadFinished = true;
                downloadButton.setText(getString(R.string.UP_Install), false);
            }
            downloadBindingToken = NimarkoUpdater.bindDownloadUi(downloadButton, this);
            NimarkoUpdater.DownloadUiState downloadState = NimarkoUpdater.getDownloadUiState();
            if (downloadState.finished) {
                downloadFinished = true;
                downloadButton.setText(getString(R.string.UP_Install), false);
            } else if (downloadState.downloading || downloadState.paused) {
                downloadButtonClicked = true;
                downloadButton.setText(
                        LocaleController.formatString(R.string.AppUpdateDownloading, downloadState.progress),
                        false);
            }
            downloadButton.setOnClickListener(v -> {
                if (downloadFinished) {
                    
                    if (NimarkoUpdater.apkFile != null) {
                        NimarkoUpdater.installApk(getContext(), NimarkoUpdater.apkFile.getAbsolutePath());
                    }
                    return;
                }
                NimarkoUpdater.DownloadUiState currentState = NimarkoUpdater.getDownloadUiState();
                if (currentState.paused) {
                    downloadButtonClicked = true;
                    NimarkoUpdater.resumeDownload(getContext().getApplicationContext());
                    return;
                }
                if (!downloadButtonClicked) {
                    downloadButtonClicked = true;
                    
                    NimarkoUpdater.downloadApk(getContext(), update.downloadURL,
                            "NimarkoGram " + update.version, downloadBindingToken);
                }
            });
            buttonsView.addView(downloadButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 16, 16, 16, 16));
        } else {
            TextCell checkOnLaunch = new TextCell(context, 23, false, true, resourcesProvider);
            checkOnLaunch.setBackground(createRowPressedBackground(resourcesProvider));
            checkOnLaunch.setTextAndCheckAndIcon(getString(R.string.UP_Auto_OTA), NimarkoUpdateConfig.getAutoOTA(), R.drawable.msg_retry, false);
            checkOnLaunch.setOnClickListener(v -> {
                NimarkoUpdateConfig.setAutoOTA(!NimarkoUpdateConfig.getAutoOTA());
                checkOnLaunch.setChecked(!checkOnLaunch.isChecked());
            });
            linearLayout.addView(checkOnLaunch);

            TextCell clearUpdates = new TextCell(context, resourcesProvider);
            clearUpdates.setBackground(createRowPressedBackground(resourcesProvider));
            clearUpdates.setTextAndIcon(getString(R.string.UP_ClearUpdatesCache), R.drawable.msg_clear, false);
            clearUpdates.setOnClickListener(v -> {
                if (NimarkoUpdater.getOtaDirSize().replaceAll("\\D+", "").equals("0")) {
                    BulletinFactory.of(getContainer(), null).createErrorBulletin(getString(R.string.UP_NothingToClear)).show();
                } else {
                    BulletinFactory.of(getContainer(), null).createErrorBulletin(LocaleController.formatString(R.string.UP_ClearedUpdatesCache, NimarkoUpdater.getOtaDirSize())).show();
                    NimarkoUpdater.cleanOtaDir();
                }
                NimarkoUpdater.cancelDownload(getContext(), NimarkoUpdater.id);
                NimarkoUpdateConfig.setUpdateAvailable(false);
            });
            linearLayout.addView(clearUpdates);

            linearLayout.addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(1)));

            ButtonWithCounterView checkUpdatesButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            checkUpdatesButton.setText(getString(R.string.UP_CheckForUpdates), true);
            checkUpdatesButton.setOnClickListener(v ->
                    NimarkoUpdater.checkUpdates(fragment, true,
                            
                            () -> BulletinFactory.of(getContainer(), resourcesProvider).createErrorBulletin(getString(R.string.UP_Not_Found)).show(),
                            this::dismiss,
                            
                            () -> BulletinFactory.of(getContainer(), resourcesProvider).createErrorBulletin(getString(R.string.UP_CheckFailed)).show()));
            buttonsView.addView(checkUpdatesButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 16, 16, 16, 16));
        }

        linearLayout.addView(buttonsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL));

        if (available) {
            ButtonWithCounterView scheduleButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            scheduleButton.setText(getString(R.string.AppUpdateRemindMeLater), false);
            scheduleButton.setOnClickListener(v -> {
                NimarkoUpdateConfig.setUpdateScheduleTimestamp(System.currentTimeMillis());
                dismiss();
            });
            linearLayout.addView(scheduleButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 16, 0, 16, 16));
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.setClipChildren(false);
        scrollView.setClipToPadding(false);
        scrollView.addView(linearLayout);
        setCustomView(scrollView);
        setOnDismissListener(() -> {
            bindingDetached = true;
            long token = downloadBindingToken;
            downloadBindingToken = 0L;
            if (token != 0L) NimarkoUpdater.unbindDownloadUi(token);
        });
    }

    private static Drawable createRowPressedBackground(
            Theme.ResourcesProvider resourcesProvider) {
        return Theme.createRadSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourcesProvider), 12, 12);
    }

    @Override
    public void onDownloadComplete() {
        if (bindingDetached || downloadButton == null || downloadBindingToken == 0L) return;
        downloadFinished = true;
        downloadButtonClicked = true;
        downloadButton.setText(getString(R.string.UP_Install), true);
    }

    @Override
    public void onDownloadError() {
        if (bindingDetached || downloadBindingToken == 0L) return;
        
        downloadButtonClicked = false;
    }

    private StringBuilder getUpdateSizeString(NimarkoUpdater.Update update) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.AppUpdateDownloadNow));
        if (!TextUtils.isEmpty(update.size)) {
            sb.append(" (").append(update.size).append(")");
        }
        return sb;
    }

    private void copyText(CharSequence text) {
        AndroidUtilities.addToClipboard(text);
        BulletinFactory.of(getContainer(), resourcesProvider).createCopyBulletin(getString(R.string.TextCopied)).show();
    }

    public void setFragmentParams(BaseFragment fragment) {
        this.fragment = fragment;
        this.resourcesProvider = fragment.getResourceProvider();
    }

    public static void showAlert(BaseFragment fragment, boolean available, NimarkoUpdater.Update update) {
        
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
        
        if (fragment == null || fragment.getParentActivity() == null || fragment.getContext() == null) {
            return;
        }
        NimarkoUpdaterSheet alert = new NimarkoUpdaterSheet(fragment.getContext(), fragment.getResourceProvider(), available, update);
        alert.setFragmentParams(fragment);
        fragment.showDialog(alert);
    }
}
