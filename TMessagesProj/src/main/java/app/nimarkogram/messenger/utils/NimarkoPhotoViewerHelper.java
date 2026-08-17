package app.nimarkogram.messenger.utils;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.StickersAlert;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;

public class NimarkoPhotoViewerHelper {

    private final ArrayList<TLRPC.Photo> avatarsArr;
    private int switchingToIndex;
    private long avatarsDialogId;
    private final BaseFragment parentFragment;
    private final PhotoViewer.PhotoViewerActionBarContainer actionBarContainer;

    public NimarkoPhotoViewerHelper(
            ArrayList<TLRPC.Photo> avatarsArr,
            int switchingToIndex,
            long avatarsDialogId,
            BaseFragment parentFragment,
            PhotoViewer.PhotoViewerActionBarContainer actionBarContainer
    ) {
        this.avatarsArr = avatarsArr;
        this.switchingToIndex = switchingToIndex;
        this.avatarsDialogId = avatarsDialogId;
        this.parentFragment = parentFragment;
        this.actionBarContainer = actionBarContainer;
    }

    public TLRPC.UserFull getUserFull() {
        if (parentFragment == null) return null;
        return parentFragment.getMessagesController().getUserFull(avatarsDialogId);
    }

    private TLRPC.Photo getCurrentAvatar() {
        if (avatarsArr == null || switchingToIndex < 0 || switchingToIndex >= avatarsArr.size()) {
            return null;
        }
        return avatarsArr.get(switchingToIndex);
    }

    public String getUserAvatarDate() {
        TLRPC.Photo avatar = getCurrentAvatar();
        TLRPC.UserFull userFull = getUserFull();

        if (avatar != null && avatar.date != 0) {
            return formatDate(avatar.date, null);
        }
        if (userFull != null && userFull.fallback_photo != null && userFull.fallback_photo.date != 0) {
            return formatDate(userFull.fallback_photo.date, getString(R.string.FallbackTooltip));
        }
        if (userFull != null && userFull.personal_photo != null && userFull.personal_photo.date != 0) {
            return formatDate(userFull.personal_photo.date, getString(R.string.CustomAvatarTooltip));
        }
        return "";
    }

    private String formatDate(int date, String subtitle) {
        if (date == 0) return "";
        String formattedDate = ResourcesUtils.createDateAndTime((long) date);
        if (subtitle != null) {
            return formattedDate + " | " + subtitle;
        }
        return formattedDate;
    }

    public TLRPC.VideoSize getEmojiMarkup() {
        TLRPC.Photo avatar = getCurrentAvatar();
        TLRPC.UserFull userFull = getUserFull();

        boolean hasVideoAvatar = false;
        if (avatar != null && avatar.video_sizes != null && !avatar.video_sizes.isEmpty()) {
            hasVideoAvatar = true;
        } else if (userFull != null && userFull.profile_photo != null
                && userFull.profile_photo.video_sizes != null
                && !userFull.profile_photo.video_sizes.isEmpty()) {
            hasVideoAvatar = true;
        }

        boolean hasPublicVideoAvatar = userFull != null
                && userFull.fallback_photo != null
                && userFull.fallback_photo.video_sizes != null
                && !userFull.fallback_photo.video_sizes.isEmpty();

        if (hasVideoAvatar) {
            ArrayList<TLRPC.VideoSize> videoSizes;
            if (avatar != null && avatar.video_sizes != null && !avatar.video_sizes.isEmpty()) {
                videoSizes = avatar.video_sizes;
            } else {
                videoSizes = userFull.profile_photo.video_sizes;
            }
            return FileLoader.getEmojiMarkup(videoSizes);
        } else if (hasPublicVideoAvatar) {
            return FileLoader.getEmojiMarkup(userFull.fallback_photo.video_sizes);
        }
        return null;
    }

    public void fetchSetId(boolean animated) {
        StringBuilder subtitle = new StringBuilder(getUserAvatarDate());

        if (actionBarContainer == null || actionBarContainer.subtitleTextView == null) return;
        actionBarContainer.subtitleTextView.setTag(this);
        actionBarContainer.subtitleTextView.setOnClickListener(null);
        
        actionBarContainer.setSubtitle(subtitle.toString(), animated);

        TLRPC.VideoSize emojiMarkup = getEmojiMarkup();
        if (emojiMarkup == null) return;

        if (emojiMarkup instanceof TLRPC.TL_videoSizeEmojiMarkup) {
            TLRPC.TL_videoSizeEmojiMarkup markup = (TLRPC.TL_videoSizeEmojiMarkup) emojiMarkup;
            long documentId = markup.emoji_id;
            AnimatedEmojiDrawable.getDocumentFetcher(parentFragment.getCurrentAccount())
                    .fetchDocument(documentId, document -> AndroidUtilities.runOnUIThread(() -> {
                        if (document == null || actionBarContainer.subtitleTextView == null
                                || actionBarContainer.subtitleTextView.getTag() != this) return;
                        actionBarContainer.subtitleTextView.setOnClickListener(v -> {
                            
                            android.content.Context ctx = parentFragment.getContext();
                            if (ctx == null) return;
                            ArrayList<TLRPC.InputStickerSet> inputSets = new ArrayList<>();
                            inputSets.add(MessageObject.getInputStickerSet(document));
                            new EmojiPacksAlert(
                                    parentFragment,
                                    ctx,
                                    parentFragment.getResourceProvider(),
                                    inputSets
                            ).show();
                        });
                    }));
            subtitle.append(" | ").append(getString(R.string.NG_OpenEmojiPack));
        } else if (emojiMarkup instanceof TLRPC.TL_videoSizeStickerMarkup) {
            TLRPC.TL_videoSizeStickerMarkup markup = (TLRPC.TL_videoSizeStickerMarkup) emojiMarkup;
            TLRPC.TL_inputStickerSetID inputStickerSet = new TLRPC.TL_inputStickerSetID();
            inputStickerSet.access_hash = markup.stickerset.access_hash;
            inputStickerSet.id = markup.stickerset.id;
            if (actionBarContainer.subtitleTextView != null
                    && actionBarContainer.subtitleTextView.getTag() == this) {
                actionBarContainer.subtitleTextView.setOnClickListener(v -> {
                    
                    android.content.Context ctx = parentFragment.getContext();
                    if (ctx == null) return;
                    new StickersAlert(
                            ctx,
                            parentFragment,
                            inputStickerSet,
                            null,
                            null,
                            parentFragment.getResourceProvider(),
                            true
                    ).show();
                });
            }
            subtitle.append(" | ").append(getString(R.string.NG_OpenStickerPack));
        }

        actionBarContainer.setSubtitle(subtitle.toString(), animated);
    }

    public CharSequence getSubtitle() {
        if (actionBarContainer != null && actionBarContainer.subtitleTextView != null) {
            actionBarContainer.subtitleTextView.setTag(this);
            actionBarContainer.subtitleTextView.setOnClickListener(null);
        }
        TLRPC.Photo avatar = getCurrentAvatar();
        TLRPC.Chat chat = parentFragment != null
                ? parentFragment.getMessagesController().getChat(-avatarsDialogId) : null;

        if (avatarsDialogId > 0) {
            return getUserAvatarDate();
        } else if (chat != null) {
            TLRPC.ChatFull chatFull = parentFragment.getMessagesController().getChatFull(-avatarsDialogId);
            if (avatar != null && avatar.date != 0) {
                return ResourcesUtils.createDateAndTime((long) avatar.date);
            }
            if (chatFull != null && chatFull.chat_photo != null && chatFull.chat_photo.date != 0) {
                return ResourcesUtils.createDateAndTime((long) chatFull.chat_photo.date);
            }
            return "";
        }
        return "";
    }

    public void release() {
        
        if (actionBarContainer != null && actionBarContainer.subtitleTextView != null
                && actionBarContainer.subtitleTextView.getTag() == this) {
            actionBarContainer.subtitleTextView.setOnClickListener(null);
            actionBarContainer.subtitleTextView.setTag(null);
        }
        switchingToIndex = 0;
        avatarsDialogId = 0;
    }
}
