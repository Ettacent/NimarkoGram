/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;

import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.AppConfig;

public class MessageMenuItemsPreferencesActivity extends BasePreferencesActivity {
    private static final int ID_SAVE_FOR_NOTIFICATIONS = 1;
    private static final int ID_REPLY = 2;
    private static final int ID_SAVE_TO_GALLERY = 3;
    private static final int ID_COPY_PHOTO = 4;
    private static final int ID_COPY_PHOTO_AS_STICKER = 5;
    private static final int ID_SAVE_TO_DOWNLOADS = 6;
    private static final int ID_SHARE = 7;
    private static final int ID_CLEAR_FROM_CACHE = 8;
    private static final int ID_FORWARD = 9;
    private static final int ID_FORWARD_WO_AUTHORSHIP = 10;
    private static final int ID_VIEW_HISTORY = 11;
    private static final int ID_SAVE_MESSAGE = 12;
    private static final int ID_REPORT = 13;
    private static final int ID_JSON = 14;
    private static final int ID_FORWARD_WO_CAPTION = 15;
    private static final int ID_DOWNLOAD_STICKER = 16;
    private static final int ID_GET_CUSTOM_REACTIONS = 17;
    private static final int ID_DETAILS = 18;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_MM_MessageMenuItems);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionPrimaryActions)));
        items.add(UItem.asCheck(ID_SAVE_FOR_NOTIFICATIONS, LocaleController.getString(R.string.SaveForNotifications))
                .setChecked(AppConfig.showSaveForNotifications));
        items.add(UItem.asCheck(ID_REPLY, LocaleController.getString(R.string.Reply))
                .setChecked(AppConfig.showReply));
        items.add(UItem.asCheck(ID_SAVE_TO_GALLERY, LocaleController.getString(R.string.SaveToGallery))
                .setChecked(AppConfig.showSaveToGallery));
        items.add(UItem.asCheck(ID_COPY_PHOTO, LocaleController.getString(R.string.NM_MI_CopyPhoto))
                .setChecked(AppConfig.showCopyPhoto));
        items.add(UItem.asCheck(ID_COPY_PHOTO_AS_STICKER, LocaleController.getString(R.string.NM_MI_CopyPhotoAsSticker))
                .setChecked(AppConfig.showCopyPhotoAsSticker));
        items.add(UItem.asCheck(ID_SAVE_TO_DOWNLOADS, LocaleController.getString(R.string.SaveToDownloads))
                .setChecked(AppConfig.showSaveToDownloads));
        items.add(UItem.asCheck(ID_SHARE, LocaleController.getString(R.string.ShareFile))
                .setChecked(AppConfig.showShare));
        items.add(UItem.asCheck(ID_CLEAR_FROM_CACHE, LocaleController.getString(R.string.NM_MI_ClearFromCache))
                .setChecked(AppConfig.showClearFromCache));
        items.add(UItem.asCheck(ID_FORWARD, LocaleController.getString(R.string.Forward))
                .setChecked(AppConfig.showForward));
        items.add(UItem.asCheck(ID_FORWARD_WO_AUTHORSHIP, LocaleController.getString(R.string.NM_MI_ForwardWoAuthorship))
                .setChecked(AppConfig.showForwardWoAuthorship));
        items.add(UItem.asCheck(ID_VIEW_HISTORY, LocaleController.getString(R.string.AvatarPreviewSearchMessages))
                .setChecked(AppConfig.showViewHistory));
        items.add(UItem.asCheck(ID_SAVE_MESSAGE, LocaleController.getString(R.string.NM_MI_SaveToSaved))
                .setChecked(AppConfig.showSaveMessage));
        items.add(UItem.asCheck(ID_REPORT, LocaleController.getString(R.string.ReportChat))
                .setChecked(AppConfig.showReport));
        items.add(UItem.asCheck(ID_JSON, LocaleController.getString(R.string.NM_MI_JSON))
                .setChecked(AppConfig.showJSON));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionExtraActions)));
        items.add(UItem.asCheck(ID_FORWARD_WO_CAPTION, LocaleController.getString(R.string.NM_MI_ForwardWoCaption))
                .setChecked(AppConfig.showForwardWoCaption));
        items.add(UItem.asCheck(ID_DOWNLOAD_STICKER, LocaleController.getString(R.string.NM_MI_DownloadSticker))
                .setChecked(AppConfig.showDownloadSticker));
        items.add(UItem.asCheck(ID_GET_CUSTOM_REACTIONS, LocaleController.getString(R.string.AccDescrCustomEmoji))
                .setChecked(AppConfig.showGetCustomReactions));
        items.add(UItem.asCheck(ID_DETAILS, LocaleController.getString(R.string.NM_MI_Details))
                .setChecked(AppConfig.showDetails));
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_SAVE_FOR_NOTIFICATIONS) {
            AppConfig.toggleShowSaveForNotifications();
            applyCheck(item, view, AppConfig.showSaveForNotifications);
        } else if (id == ID_REPLY) {
            AppConfig.toggleShowReply();
            applyCheck(item, view, AppConfig.showReply);
        } else if (id == ID_SAVE_TO_GALLERY) {
            AppConfig.toggleShowSaveToGallery();
            applyCheck(item, view, AppConfig.showSaveToGallery);
        } else if (id == ID_COPY_PHOTO) {
            AppConfig.toggleShowCopyPhoto();
            applyCheck(item, view, AppConfig.showCopyPhoto);
        } else if (id == ID_COPY_PHOTO_AS_STICKER) {
            AppConfig.toggleShowCopyPhotoAsSticker();
            applyCheck(item, view, AppConfig.showCopyPhotoAsSticker);
        } else if (id == ID_SAVE_TO_DOWNLOADS) {
            AppConfig.toggleShowSaveToDownloads();
            applyCheck(item, view, AppConfig.showSaveToDownloads);
        } else if (id == ID_SHARE) {
            AppConfig.toggleShowShare();
            applyCheck(item, view, AppConfig.showShare);
        } else if (id == ID_CLEAR_FROM_CACHE) {
            AppConfig.toggleShowClearFromCache();
            applyCheck(item, view, AppConfig.showClearFromCache);
        } else if (id == ID_FORWARD) {
            AppConfig.toggleShowForward();
            applyCheck(item, view, AppConfig.showForward);
        } else if (id == ID_FORWARD_WO_AUTHORSHIP) {
            AppConfig.toggleShowForwardWoAuthorship();
            applyCheck(item, view, AppConfig.showForwardWoAuthorship);
        } else if (id == ID_VIEW_HISTORY) {
            AppConfig.toggleShowViewHistory();
            applyCheck(item, view, AppConfig.showViewHistory);
        } else if (id == ID_SAVE_MESSAGE) {
            AppConfig.toggleShowSaveMessage();
            applyCheck(item, view, AppConfig.showSaveMessage);
        } else if (id == ID_REPORT) {
            AppConfig.toggleShowReport();
            applyCheck(item, view, AppConfig.showReport);
        } else if (id == ID_JSON) {
            AppConfig.toggleShowJSON();
            applyCheck(item, view, AppConfig.showJSON);
        } else if (id == ID_FORWARD_WO_CAPTION) {
            AppConfig.toggleShowForwardWoCaption();
            applyCheck(item, view, AppConfig.showForwardWoCaption);
        } else if (id == ID_DOWNLOAD_STICKER) {
            AppConfig.toggleShowDownloadSticker();
            applyCheck(item, view, AppConfig.showDownloadSticker);
        } else if (id == ID_GET_CUSTOM_REACTIONS) {
            AppConfig.toggleShowGetCustomReactions();
            applyCheck(item, view, AppConfig.showGetCustomReactions);
        } else if (id == ID_DETAILS) {
            AppConfig.toggleShowDetails();
            applyCheck(item, view, AppConfig.showDetails);
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        updateCheckState(view, value);
    }
}
