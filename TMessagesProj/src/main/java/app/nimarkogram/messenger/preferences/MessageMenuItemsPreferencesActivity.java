package app.nimarkogram.messenger.preferences;

import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;

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
                .setChecked(NimarkoConfig.showSaveForNotifications));
        items.add(UItem.asCheck(ID_REPLY, LocaleController.getString(R.string.Reply))
                .setChecked(NimarkoConfig.showReply));
        items.add(UItem.asCheck(ID_SAVE_TO_GALLERY, LocaleController.getString(R.string.SaveToGallery))
                .setChecked(NimarkoConfig.showSaveToGallery));
        items.add(UItem.asCheck(ID_COPY_PHOTO, LocaleController.getString(R.string.NM_MI_CopyPhoto))
                .setChecked(NimarkoConfig.showCopyPhoto));
        items.add(UItem.asCheck(ID_COPY_PHOTO_AS_STICKER, LocaleController.getString(R.string.NM_MI_CopyPhotoAsSticker))
                .setChecked(NimarkoConfig.showCopyPhotoAsSticker));
        items.add(UItem.asCheck(ID_SAVE_TO_DOWNLOADS, LocaleController.getString(R.string.SaveToDownloads))
                .setChecked(NimarkoConfig.showSaveToDownloads));
        items.add(UItem.asCheck(ID_SHARE, LocaleController.getString(R.string.ShareFile))
                .setChecked(NimarkoConfig.showShare));
        items.add(UItem.asCheck(ID_CLEAR_FROM_CACHE, LocaleController.getString(R.string.NM_MI_ClearFromCache))
                .setChecked(NimarkoConfig.showClearFromCache));
        items.add(UItem.asCheck(ID_FORWARD, LocaleController.getString(R.string.Forward))
                .setChecked(NimarkoConfig.showForward));
        items.add(UItem.asCheck(ID_FORWARD_WO_AUTHORSHIP, LocaleController.getString(R.string.NM_MI_ForwardWoAuthorship))
                .setChecked(NimarkoConfig.showForwardWoAuthorship));
        items.add(UItem.asCheck(ID_VIEW_HISTORY, LocaleController.getString(R.string.AvatarPreviewSearchMessages))
                .setChecked(NimarkoConfig.showViewHistory));
        items.add(UItem.asCheck(ID_SAVE_MESSAGE, LocaleController.getString(R.string.NM_MI_SaveToSaved))
                .setChecked(NimarkoConfig.showSaveMessage));
        items.add(UItem.asCheck(ID_REPORT, LocaleController.getString(R.string.ReportChat))
                .setChecked(NimarkoConfig.showReport));
        items.add(UItem.asCheck(ID_JSON, LocaleController.getString(R.string.NM_MI_JSON))
                .setChecked(NimarkoConfig.showJSON));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionExtraActions)));
        items.add(UItem.asCheck(ID_FORWARD_WO_CAPTION, LocaleController.getString(R.string.NM_MI_ForwardWoCaption))
                .setChecked(NimarkoConfig.showForwardWoCaption));
        items.add(UItem.asCheck(ID_DOWNLOAD_STICKER, LocaleController.getString(R.string.NM_MI_DownloadSticker))
                .setChecked(NimarkoConfig.showDownloadSticker));
        items.add(UItem.asCheck(ID_GET_CUSTOM_REACTIONS, LocaleController.getString(R.string.AccDescrCustomEmoji))
                .setChecked(NimarkoConfig.showGetCustomReactions));
        items.add(UItem.asCheck(ID_DETAILS, LocaleController.getString(R.string.NM_MI_Details))
                .setChecked(NimarkoConfig.showDetails));
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_SAVE_FOR_NOTIFICATIONS) {
            NimarkoConfig.toggleShowSaveForNotifications();
            applyCheck(item, view, NimarkoConfig.showSaveForNotifications);
        } else if (id == ID_REPLY) {
            NimarkoConfig.toggleShowReply();
            applyCheck(item, view, NimarkoConfig.showReply);
        } else if (id == ID_SAVE_TO_GALLERY) {
            NimarkoConfig.toggleShowSaveToGallery();
            applyCheck(item, view, NimarkoConfig.showSaveToGallery);
        } else if (id == ID_COPY_PHOTO) {
            NimarkoConfig.toggleShowCopyPhoto();
            applyCheck(item, view, NimarkoConfig.showCopyPhoto);
        } else if (id == ID_COPY_PHOTO_AS_STICKER) {
            NimarkoConfig.toggleShowCopyPhotoAsSticker();
            applyCheck(item, view, NimarkoConfig.showCopyPhotoAsSticker);
        } else if (id == ID_SAVE_TO_DOWNLOADS) {
            NimarkoConfig.toggleShowSaveToDownloads();
            applyCheck(item, view, NimarkoConfig.showSaveToDownloads);
        } else if (id == ID_SHARE) {
            NimarkoConfig.toggleShowShare();
            applyCheck(item, view, NimarkoConfig.showShare);
        } else if (id == ID_CLEAR_FROM_CACHE) {
            NimarkoConfig.toggleShowClearFromCache();
            applyCheck(item, view, NimarkoConfig.showClearFromCache);
        } else if (id == ID_FORWARD) {
            NimarkoConfig.toggleShowForward();
            applyCheck(item, view, NimarkoConfig.showForward);
        } else if (id == ID_FORWARD_WO_AUTHORSHIP) {
            NimarkoConfig.toggleShowForwardWoAuthorship();
            applyCheck(item, view, NimarkoConfig.showForwardWoAuthorship);
        } else if (id == ID_VIEW_HISTORY) {
            NimarkoConfig.toggleShowViewHistory();
            applyCheck(item, view, NimarkoConfig.showViewHistory);
        } else if (id == ID_SAVE_MESSAGE) {
            NimarkoConfig.toggleShowSaveMessage();
            applyCheck(item, view, NimarkoConfig.showSaveMessage);
        } else if (id == ID_REPORT) {
            NimarkoConfig.toggleShowReport();
            applyCheck(item, view, NimarkoConfig.showReport);
        } else if (id == ID_JSON) {
            NimarkoConfig.toggleShowJSON();
            applyCheck(item, view, NimarkoConfig.showJSON);
        } else if (id == ID_FORWARD_WO_CAPTION) {
            NimarkoConfig.toggleShowForwardWoCaption();
            applyCheck(item, view, NimarkoConfig.showForwardWoCaption);
        } else if (id == ID_DOWNLOAD_STICKER) {
            NimarkoConfig.toggleShowDownloadSticker();
            applyCheck(item, view, NimarkoConfig.showDownloadSticker);
        } else if (id == ID_GET_CUSTOM_REACTIONS) {
            NimarkoConfig.toggleShowGetCustomReactions();
            applyCheck(item, view, NimarkoConfig.showGetCustomReactions);
        } else if (id == ID_DETAILS) {
            NimarkoConfig.toggleShowDetails();
            applyCheck(item, view, NimarkoConfig.showDetails);
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        updateCheckState(view, value);
    }
}
