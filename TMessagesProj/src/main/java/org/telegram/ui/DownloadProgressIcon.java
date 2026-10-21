/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RLottieDrawable;

import java.util.ArrayList;
import java.util.HashMap;

import app.nimarkogram.messenger.icons.NimarkoIconResources;
import app.nimarkogram.messenger.NimarkoConfig;
public class DownloadProgressIcon extends View implements NotificationCenter.NotificationCenterDelegate {

    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Paint paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int currentAccount;
    ArrayList<ProgressObserver> currentListeners = new ArrayList<>();
    float progress;
    float currentProgress;
    float progressDt;

    ImageReceiver downloadImageReceiver = new ImageReceiver(this);
    ImageReceiver downloadCompleteImageReceiver = new ImageReceiver(this);
    RLottieDrawable downloadDrawable;
    RLottieDrawable downloadCompleteDrawable;
    boolean showCompletedIcon;
    boolean hasUnviewedDownloads;
    int currentColor;
    private boolean colorsInitialized;
    private boolean wasDrawn;

    private int appliedIconPack = NimarkoConfig.ICON_REPLACE_NONE;
    public DownloadProgressIcon(int currentAccount, Context context) {
        super(context);
        this.currentAccount = currentAccount;
        downloadImageReceiver.ignoreNotifications = true;
        downloadCompleteImageReceiver.ignoreNotifications = true;

        downloadDrawable = new RLottieDrawable(R.raw.download_progress, AndroidUtilities.dp(28), AndroidUtilities.dp(28), true, null);
        downloadCompleteDrawable = new RLottieDrawable(R.raw.download_finish, AndroidUtilities.dp(28), AndroidUtilities.dp(28), true, null);

        downloadImageReceiver.setImageBitmap(downloadDrawable);
        downloadCompleteImageReceiver.setImageBitmap(downloadCompleteDrawable);

        downloadImageReceiver.setAutoRepeat(1);
        downloadDrawable.setAutoRepeat(1);
        downloadDrawable.start();
    }

    public void updateColors() {
        downloadDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.SRC_IN));
        downloadCompleteDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.SRC));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
        updateImageBounds();
    }

    private int updateImageBounds() {
        appliedIconPack = NimarkoConfig.ICON_REPLACE_NONE;
        NimarkoIconResources.NavigationIcons icons = NimarkoIconResources.activeNavigationIcons();
        NimarkoIconResources.PreparedIcon icon = icons == null ? null : icons.getIcon(R.drawable.ng_header_download);
        if (icon != null && icon.bitmap != null && !icon.bitmap.isRecycled()) {
            appliedIconPack = icons.selection;
            int size = Math.max(0, Math.min(AndroidUtilities.dp(24), Math.min(getMeasuredWidth(), getMeasuredHeight())));
            float x = (getMeasuredWidth() - size) / 2f;
            float y = (getMeasuredHeight() - size) / 2f;
            downloadImageReceiver.setImageCoords(x, y, size, size);
            downloadCompleteImageReceiver.setImageCoords(x, y, size, size);
            return size;
        }
        int padding = AndroidUtilities.dp(15);
        downloadImageReceiver.setImageCoords(padding, padding, getMeasuredWidth() - padding * 2, getMeasuredHeight() - padding * 2);
        downloadCompleteImageReceiver.setImageCoords(padding, padding, getMeasuredWidth() - padding * 2, getMeasuredHeight() - padding * 2);
        return -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getAlpha() == 0) {
            return;
        }

        int customIconSize = updateImageBounds();

        if (!colorsInitialized || currentColor != Theme.getColor(Theme.key_actionBarDefaultIcon)) {
            colorsInitialized = true;
            currentColor = Theme.getColor(Theme.key_actionBarDefaultIcon);
            paint.setColor(Theme.getColor(Theme.key_actionBarDefaultIcon));
            paint2.setColor(Theme.getColor(Theme.key_actionBarDefaultIcon));
            downloadImageReceiver.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.SRC_IN));
            downloadCompleteImageReceiver.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.SRC_IN));
            paint2.setAlpha(100);
        }

        int materialAlpha = appliedIconPack == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS ? 128
                : appliedIconPack == NimarkoConfig.ICON_REPLACE_PLUMPY ? 89 : 255;
        int foregroundAlpha = Color.alpha(currentColor);
        paint.setAlpha((foregroundAlpha * materialAlpha + 127) / 255);
        paint2.setAlpha(customIconSize >= 0 ? Math.round(100f * materialAlpha * foregroundAlpha / (255f * 255f)) : 100);
        if (currentProgress != progress) {
            currentProgress += progressDt;
            if (progressDt > 0 && currentProgress > progress) {
                currentProgress = progress;
            } else if (progressDt < 0 && currentProgress < progress) {
                currentProgress = progress;
            } else {
                invalidate();
            }
        }

        float cy = customIconSize >= 0 ? getMeasuredHeight() / 2f + customIconSize * (7f / 24f)
                : getMeasuredHeight() / 2 + AndroidUtilities.dp(8);
        float r = customIconSize >= 0 ? customIconSize / 32f : AndroidUtilities.dp(1f);

        float startPadding = customIconSize >= 0 ? (getMeasuredWidth() - customIconSize * (14f / 24f)) / 2f
                : AndroidUtilities.dp(16);
        float width = getMeasuredWidth() - startPadding * 2;
        AndroidUtilities.rectTmp.set(startPadding, cy - r, getMeasuredWidth() - startPadding, cy + r);
        canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, paint2);

        AndroidUtilities.rectTmp.set(startPadding, cy - r, startPadding + width * currentProgress, cy + r);
        canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, paint);

        canvas.save();
        canvas.clipRect(0, 0, getMeasuredWidth(), cy - r);
        if (progress != 1f) {
            showCompletedIcon = false;
        }
        if (showCompletedIcon) {
            downloadCompleteImageReceiver.draw(canvas);
        } else {
            downloadImageReceiver.draw(canvas);
        }

        if (progress == 1f && !showCompletedIcon) {
            if (downloadDrawable.getCurrentFrame() == 0) {
                downloadCompleteDrawable.setCurrentFrame(0, false);
                downloadCompleteDrawable.start();
                showCompletedIcon = true;
            }
        }
        canvas.restore();
        if (getAlpha() != 0) {
            wasDrawn = true;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateDownloadingListeners();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.onDownloadingFilesChanged);
        downloadImageReceiver.onAttachedToWindow();
        downloadCompleteImageReceiver.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        detachCurrentListeners();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
        downloadImageReceiver.onDetachedFromWindow();
        downloadCompleteImageReceiver.onDetachedFromWindow();
    }


    private void updateDownloadingListeners() {
        DownloadController downloadController = DownloadController.getInstance(currentAccount);
        HashMap<String, ProgressObserver> observerHashMap = new HashMap<>();
        for (int i = 0; i < currentListeners.size(); i++) {
            observerHashMap.put(currentListeners.get(i).fileName, currentListeners.get(i));
            DownloadController.getInstance(currentAccount).removeLoadingFileObserver(currentListeners.get(i));
        }
        currentListeners.clear();
        for (int i = 0; i < downloadController.downloadingFiles.size(); i++) {
            String filename = downloadController.downloadingFiles.get(i).getFileName();
            if (FileLoader.getInstance(currentAccount).isLoadingFile(filename)) {
                ProgressObserver progressObserver = observerHashMap.get(filename);
                if (progressObserver == null) {
                    progressObserver = new ProgressObserver(filename);
                }
                DownloadController.getInstance(currentAccount).addLoadingFileObserver(filename, progressObserver);
                currentListeners.add(progressObserver);
            }
        }
        if (currentListeners.size() == 0 && !wasDrawn) {
            if (DownloadController.getInstance(currentAccount).hasUnviewedDownloads()) {
                progress = 1f;
                currentProgress = 1f;
                showCompletedIcon = true;
            } else {
                progress = 0;
                currentProgress = 0;
                showCompletedIcon = false;
            }
        }
    }

    public void updateProgress() {
        MessagesStorage messagesStorage = MessagesStorage.getInstance(currentAccount);
        long total = 0;
        long downloaded = 0;
        for (int i = 0; i < currentListeners.size(); i++) {
            total += currentListeners.get(i).total;
            downloaded += currentListeners.get(i).downloaded;
        }
        if (total == 0) {
            progress = 1f;
        } else {
            progress = downloaded / (float) total;
        }
        if (progress > 1f) {
            progress = 1f;
        } else if (progress < 0) {
            progress = 0;
        }

        progressDt = (progress - currentProgress) * 16f / 150f;
        invalidate();
    }

    private void detachCurrentListeners() {
        for (int i = 0; i < currentListeners.size(); i++) {
            DownloadController.getInstance(currentAccount).removeLoadingFileObserver(currentListeners.get(i));
        }
        currentListeners.clear();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.onDownloadingFilesChanged) {
            updateDownloadingListeners();
            updateProgress();
        }
    }

    private class ProgressObserver implements DownloadController.FileDownloadProgressListener {

        long total;
        long downloaded;
        private final String fileName;

        private ProgressObserver(String fileName) {
            this.fileName = fileName;
        }

        @Override
        public void onFailedDownload(String fileName, boolean canceled) {

        }

        @Override
        public void onSuccessDownload(String fileName) {

        }

        @Override
        public void onProgressDownload(String fileName, long downloadSize, long totalSize) {
            downloaded = downloadSize;
            total = totalSize;
            updateProgress();
        }

        @Override
        public void onProgressUpload(String fileName, long downloadSize, long totalSize, boolean isEncrypted) {

        }

        @Override
        public int getObserverTag() {
            return 0;
        }
    }

    @Override
    public void setAlpha(float alpha) {
        if (alpha == 0) {
            wasDrawn = false;
        }
        super.setAlpha(alpha);
    }

    @Override
    public void setVisibility(int visibility) {
        if (visibility != View.VISIBLE) {
            wasDrawn = false;
        }
        super.setVisibility(visibility);
    }
}
