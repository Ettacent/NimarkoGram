 
package app.nimarkogram.messenger.media;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.NimarkoInlineAuth;

public final class NimarkoMediaController implements NotificationCenter.NotificationCenterDelegate {

    private static final int MAX_ALBUM_IMAGES = 50;
    private static final String AUTH_PREFS = "nimarko_media_auth";

    public static final class SendOptions {
        public final boolean notify;
        public final int scheduleDate;
        public final int scheduleRepeatPeriod;
        public final long payStars;
        public final long stars;
        public final long effectId;
        public final String quickReplyShortcut;
        public final int quickReplyShortcutId;
        public final long monoForumPeerId;
        public final MessageSuggestionParams suggestionParams;
        public final TL_stories.StoryItem replyToStoryItem;
        public final ChatActivity.ReplyQuote replyQuote;
        public final boolean invertMedia;

        public SendOptions(boolean notify, int scheduleDate, int scheduleRepeatPeriod) {
            this(notify, scheduleDate, scheduleRepeatPeriod, 0, 0, 0,
                    null, 0, 0, null, null, null, false);
        }

        public SendOptions(boolean notify, int scheduleDate, int scheduleRepeatPeriod,
                           long payStars, long stars, long effectId,
                           String quickReplyShortcut, int quickReplyShortcutId,
                           long monoForumPeerId, MessageSuggestionParams suggestionParams,
                           TL_stories.StoryItem replyToStoryItem,
                           ChatActivity.ReplyQuote replyQuote, boolean invertMedia) {
            this.notify = notify;
            this.scheduleDate = Math.max(0, scheduleDate);
            this.scheduleRepeatPeriod = Math.max(0, scheduleRepeatPeriod);
            this.payStars = Math.max(0, payStars);
            this.stars = Math.max(0, stars);
            this.effectId = effectId;
            this.quickReplyShortcut = quickReplyShortcut;
            this.quickReplyShortcutId = quickReplyShortcutId;
            this.monoForumPeerId = monoForumPeerId;
            this.suggestionParams = suggestionParams;
            this.replyToStoryItem = replyToStoryItem;
            this.replyQuote = replyQuote;
            this.invertMedia = invertMedia;
        }

        public static SendOptions immediate() { return new SendOptions(true, 0, 0); }

        private void applyTo(SendMessagesHelper.SendMessageParams params) {
            params.payStars = payStars;
            params.stars = stars;
            params.effect_id = effectId;
            params.quick_reply_shortcut = quickReplyShortcut;
            params.quick_reply_shortcut_id = quickReplyShortcutId;
            params.monoForumPeer = monoForumPeerId;
            params.suggestionParams = suggestionParams;
            params.replyToStoryItem = replyToStoryItem;
            params.replyQuote = replyQuote;
            params.invert_media = invertMedia;
        }
    }

    private static final class RequestContext {
        final SendOptions options;
        final MessageObject replyToTop;
        final int account;
        final long userId;

        RequestContext(SendOptions options, MessageObject replyToTop,
                       int account, long userId) {
            this.options = options != null ? options : SendOptions.immediate();
            this.replyToTop = replyToTop;
            this.account = account;
            this.userId = userId;
        }
    }

    private static final NimarkoMediaController INSTANCE = new NimarkoMediaController();
    public static NimarkoMediaController getInstance() { return INSTANCE; }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "nimarko-media");
            t.setDaemon(true);
            return t;
        }
    });

    private final ExecutorService imagePool = Executors.newFixedThreadPool(5, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "nimarko-photo");
            t.setDaemon(true);
            return t;
        }
    });

    private final AtomicReference<RequestContext> activeRequest = new AtomicReference<>();
    private final ConcurrentHashMap<String, Object> authLocks = new ConcurrentHashMap<>();
    private final boolean[] observingUploads = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private NimarkoMediaController() {}

    public void tryHandle(MessageObject message, ChatActivity chatActivity) {
        tryHandle(message, chatActivity, SendOptions.immediate());
    }

    public void tryHandle(MessageObject message, ChatActivity chatActivity, SendOptions options) {
        if (message == null || chatActivity == null) return;
        CharSequence text = message.messageText;
        if (text == null) return;
        NimarkoMediaDownloader.UrlMatch match = NimarkoMediaDownloader.findUrl(text);
        if (match == null) {
            showError(chatActivity, LocaleController.getString(R.string.NM_NM_UnsupportedUrl));
            return;
        }
        if (activeRequest.get() != null) {
            showError(chatActivity, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
            return;
        }
        if (!kickoff(chatActivity, chatActivity.getDialogId(), chatActivity.getCurrentAccount(),
                match, message, chatActivity.getThreadMessage(), options)) {
            showError(chatActivity, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
        }
    }

    public static final int INTERCEPT_NONE = 0;
     
    public static final int INTERCEPT_HIJACKED = 1;
     
    public static final int INTERCEPT_BLOCKED = 2;

    public int interceptOutgoingMessage(CharSequence text, ChatActivity chatActivity) {
        return interceptOutgoingMessage(text, chatActivity, SendOptions.immediate());
    }

    public int interceptOutgoingMessage(CharSequence text, ChatActivity chatActivity, SendOptions options) {
        if (!NimarkoConfig.nimarkoMediaAuto) return INTERCEPT_NONE;
        if (text == null || chatActivity == null) return INTERCEPT_NONE;
        String trimmed = text.toString().trim();
        if (trimmed.isEmpty()) return INTERCEPT_NONE;
        if (!trimmed.startsWith("http")) return INTERCEPT_NONE;
        if (trimmed.contains(" ") || trimmed.contains("\n") || trimmed.contains("\t")) return INTERCEPT_NONE;
        NimarkoMediaDownloader.UrlMatch match = NimarkoMediaDownloader.findUrl(trimmed);
        if (match == null) return INTERCEPT_NONE;
        if (activeRequest.get() != null) {
            
            showError(chatActivity, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
            return INTERCEPT_BLOCKED;
        }
        
        if (kickoff(chatActivity, chatActivity.getDialogId(), chatActivity.getCurrentAccount(),
                match, chatActivity.replyingMessageObject, chatActivity.getThreadMessage(), options)) {
            return INTERCEPT_HIJACKED;
        }
        showError(chatActivity, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
        return INTERCEPT_BLOCKED;
    }

    public void downloadFromUrl(BaseFragment fragment, String url) {
        if (fragment == null || url == null) return;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return;
        NimarkoMediaDownloader.UrlMatch match = NimarkoMediaDownloader.findUrl(trimmed);
        if (match == null) {
            showError(fragment, LocaleController.getString(R.string.NM_NM_UnsupportedUrl));
            return;
        }
        if (activeRequest.get() != null) {
            showError(fragment, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
            return;
        }
        int account = fragment.getCurrentAccount();
        long savedMessages = UserConfig.getInstance(account).getClientUserId();
        if (!kickoff(fragment, savedMessages, account, match, null, null, SendOptions.immediate())) {
            showError(fragment, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
        }
    }

    private boolean kickoff(BaseFragment fragmentForBulletins, long dialogId, int account,
                         NimarkoMediaDownloader.UrlMatch match, MessageObject replyTo,
                         MessageObject replyToTop, SendOptions options) {
        RequestContext request = new RequestContext(
                options, replyToTop, account, accountUserId(account));
        if (!activeRequest.compareAndSet(null, request)) {
            return false;
        }
        String platform = match.platform.id;

        if ("youtube".equals(platform) && NimarkoConfig.nimarkoMediaYtAsk) {
            Activity parent = fragmentForBulletins != null ? fragmentForBulletins.getParentActivity() : null;
            if (parent != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    AlertDialog.Builder b = new AlertDialog.Builder(parent,
                            fragmentForBulletins.getResourceProvider());
                    b.setTitle("▶️ YouTube");
                    b.setItems(new CharSequence[]{
                            LocaleController.getString(R.string.NM_NM_FormatVideo),
                            LocaleController.getString(R.string.NM_NM_FormatAudio)
                    }, (dialog, which) -> {
                        String mt = which == 1 ? "audio" : "video";
                        executor.submit(() -> processDownload(request, fragmentForBulletins,
                                dialogId, account, match, mt, replyTo));
                    });
                    b.setNegativeButton(LocaleController.getString(R.string.Cancel),
                            (dialog, which) -> {
                        dialog.dismiss();
                        clearInflight(request);
                    });
                    AlertDialog d = b.create();
                    d.setOnCancelListener(dialog -> clearInflight(request));
                    d.show();
                });
                return true;
            }
        }

        String mediaType = resolveMediaType(match.platform);
        executor.submit(() -> processDownload(request, fragmentForBulletins, dialogId, account, match, mediaType, replyTo));
        return true;
    }

    private String resolveMediaType(NimarkoMediaDownloader.Platform p) {
        if (p.isAudioOnly()) return "audio";
        if (p == NimarkoMediaDownloader.Platform.YOUTUBE && NimarkoConfig.nimarkoMediaYtFmt == 1) {
            return "audio";
        }
        return "video";
    }

    private void processDownload(RequestContext request, BaseFragment fragmentForBulletins, long dialogId, int account,
                                 NimarkoMediaDownloader.UrlMatch match, String mediaType,
                                 MessageObject replyTo) {
        if (!isRequestIdentityValid(request, account)) {
            clearInflight(request);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (isRequestIdentityValid(request, account)) {
                showInfo(fragmentForBulletins,
                        LocaleController.getString(R.string.NM_NM_Downloading));
            }
        });

        try {
            if (!isRequestIdentityValid(request, account)) {
                clearInflight(request);
                return;
            }
            NimarkoInlineAuth.Backend authBackend = authBackendForAccount(account);
            String token = ensureToken(request, authBackend);
            if (!isRequestIdentityValid(request, account)) {
                clearInflight(request);
                return;
            }
            if (token == null) {
                finishWithError(request, fragmentForBulletins,
                        LocaleController.getString(R.string.NM_NM_AuthFailed));
                return;
            }

            NimarkoMediaDownloader.Result result =
                    NimarkoMediaDownloader.requestDownload(match.url, mediaType, token);
            if (!isRequestIdentityValid(request, account)) {
                clearInflight(request);
                return;
            }
            if (!result.success && "auth".equals(result.error)) {
                authBackend.cacheToken(null);
                token = ensureToken(request, authBackend);
                if (!isRequestIdentityValid(request, account)) {
                    clearInflight(request);
                    return;
                }
                if (token == null) {
                    finishWithError(request, fragmentForBulletins,
                            LocaleController.getString(R.string.NM_NM_AuthFailed));
                    return;
                }
                result = NimarkoMediaDownloader.requestDownload(match.url, mediaType, token);
            }

            if (!isRequestIdentityValid(request, account)) {
                clearInflight(request);
                return;
            }
            if (!result.success) {
                String errMsg = result.error != null ? result.error :
                        LocaleController.getString(R.string.NM_NM_GenericError);
                finishWithError(request, fragmentForBulletins, errMsg);
                return;
            }

            if ("album".equals(result.type)) {
                handleAlbum(request, fragmentForBulletins, account, dialogId, result, replyTo);
            } else if ("audio".equals(result.type)) {
                handleAudio(request, fragmentForBulletins, account, dialogId, result, replyTo);
            } else {
                handleVideo(request, fragmentForBulletins, account, dialogId, result,
                        match.platform.id, match.isShorts, replyTo);
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-media: processDownload failed", t);
            finishWithError(request, fragmentForBulletins,
                    LocaleController.getString(R.string.NM_NM_GenericError));
        }
    }

    private String ensureToken(RequestContext request, NimarkoInlineAuth.Backend backend) {
        String scope = request.account + ":" + request.userId;
        Object lock = authLocks.computeIfAbsent(scope, ignored -> new Object());
        return NimarkoInlineAuth.ensureToken(
                request.account, lock, backend,
                () -> isRequestIdentityValid(request, request.account));
    }

    public static NimarkoInlineAuth.Backend authBackendForAccount(final int account) {
        
        if (NimarkoConfig.getPreferences().contains("nimarkoMediaAuthToken")) {
            NimarkoConfig.getEditor().remove("nimarkoMediaAuthToken").apply();
        }
        final long uid = accountUserId(account);
        final String tokenKey = "token_" + account + "_" + uid;
        return new NimarkoInlineAuth.Backend() {
            @Override public String cachedToken() {
                if (uid == 0 || accountUserId(account) != uid) return null;
                String token = authPreferences().getString(tokenKey, null);
                return token == null || token.isEmpty() ? null : token;
            }

            @Override public void cacheToken(String token) {
                if (uid == 0 || accountUserId(account) != uid) return;
                SharedPreferences.Editor editor = authPreferences().edit();
                if (token == null || token.isEmpty()) {
                    editor.remove(tokenKey);
                } else {
                    editor.putString(tokenKey, token);
                }
                editor.apply();
            }

            @Override public NimarkoInlineAuth.Reg register(long registeredUid) {
                if (uid == 0 || registeredUid != uid
                        || accountUserId(account) != uid) {
                    return new NimarkoInlineAuth.Reg();
                }
                NimarkoMediaDownloader.AuthRegister r = NimarkoMediaDownloader.authRegister(registeredUid);
                NimarkoInlineAuth.Reg reg = new NimarkoInlineAuth.Reg();
                reg.code = r.code;
                reg.botUsername = r.botUsername;
                reg.ok = r.ok;
                return reg;
            }

            @Override public String poll(long registeredUid, String code) {
                if (uid == 0 || registeredUid != uid
                        || accountUserId(account) != uid) {
                    return NimarkoInlineAuth.GIVE_UP;
                }
                return NimarkoMediaDownloader.authPoll(registeredUid, code);
            }
        };
    }

    private static SharedPreferences authPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
    }

    private static long accountUserId(int account) {
        try {
            return UserConfig.getInstance(account).getClientUserId();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void handleVideo(RequestContext request, BaseFragment fragmentForBulletins, int account, long dialogId,
                             NimarkoMediaDownloader.Result result, String platform,
                             boolean isShorts, MessageObject replyTo) {
        String title = ("youtube".equals(platform) && !isShorts) ? result.title : null;
        sendHyperlinkedMessage(request, account, dialogId, result.fileUrl, result.originalUrl,
                title, false, replyTo, () -> successBulletin(fragmentForBulletins, result.fromCache));
    }

    private void handleAudio(RequestContext request, BaseFragment fragmentForBulletins, int account, long dialogId,
                             NimarkoMediaDownloader.Result result, MessageObject replyTo) {
        String caption;
        if (result.artist != null && !result.artist.isEmpty()
                && result.title != null && !result.title.isEmpty()) {
            caption = result.artist + " - " + result.title;
        } else {
            caption = result.title != null ? result.title : "";
        }
        sendHyperlinkedMessage(request, account, dialogId, result.fileUrl, result.originalUrl,
                caption, true, replyTo, () -> successBulletin(fragmentForBulletins, result.fromCache));
    }

    private void handleAlbum(RequestContext request, BaseFragment fragmentForBulletins, int account, long dialogId,
                             NimarkoMediaDownloader.Result result, MessageObject replyTo) {
        if (!isRequestIdentityValid(request, account)) {
            clearInflight(request);
            return;
        }
        if (result.images == null || result.images.isEmpty()) {
            finishWithError(request, fragmentForBulletins,
                    LocaleController.getString(R.string.NM_NM_GenericError));
            return;
        }
        List<String> paths = downloadImagesParallel(request, result.images);
        if (!isRequestIdentityValid(request, account)) {
            cleanupPaths(paths);
            clearInflight(request);
            return;
        }
        if (paths.isEmpty()) {
            finishWithError(request, fragmentForBulletins,
                    LocaleController.getString(R.string.NM_NM_GenericError));
            return;
        }
        List<PreparedPhoto> photos = prepareAlbumPhotos(request, paths);
        if (!isRequestIdentityValid(request, account)) {
            cleanupUnownedPhotos(photos);
            cleanupPaths(paths);
            clearInflight(request);
            return;
        }
        if (photos.isEmpty()) {
            finishWithError(request, fragmentForBulletins, LocaleController.getString(R.string.NM_NM_GenericError));
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!isRequestIdentityValid(request, account)) {
                cleanupUnownedPhotos(photos);
                clearInflight(request);
                return;
            }
            try {
                int sent = sendAlbumGrouped(request, account, dialogId, photos, result.originalUrl, replyTo);
                if (sent > 0) {
                    successBulletinAlbum(fragmentForBulletins, sent, result.fromCache);
                    clearInflight(request);
                } else {
                    cleanupUnownedPhotos(photos);
                    finishWithError(request, fragmentForBulletins, LocaleController.getString(R.string.NM_NM_GenericError));
                }
            } catch (Throwable t) {
                FileLog.e("nimarko-media: sendAlbumGrouped failed", t);
                cleanupUnownedPhotos(photos);
                finishWithError(request, fragmentForBulletins, LocaleController.getString(R.string.NM_NM_GenericError));
            }
        });
    }

    private void cleanupUnownedPhotos(List<PreparedPhoto> photos) {
        for (PreparedPhoto photo : photos) {
            NimarkoMediaDownloader.deleteIfUnowned(photo.sourcePath);
            if (!photo.sourcePath.equals(photo.uploadPath)) {
                NimarkoMediaDownloader.deleteIfUnowned(photo.uploadPath);
            }
        }
    }

    private List<String> downloadImagesParallel(RequestContext request, List<String> urls) {
        int count = Math.min(urls.size(), MAX_ALBUM_IMAGES);
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>(count);
        String batch = Long.toHexString(System.nanoTime());
        for (int i = 0; i < count; i++) {
            final int idx = i;
            final String url = urls.get(i);
            futures.add(imagePool.submit(() ->
                    NimarkoMediaDownloader.download(url, "album_" + batch + "_" + idx + ".jpg")));
        }
        List<String> out = new ArrayList<>(count);
        for (java.util.concurrent.Future<String> f : futures) {
            try {
                String p = f.get();
                if (p != null) {
                    if (isRequestIdentityValid(request, request.account)) {
                        out.add(p);
                    } else {
                        NimarkoMediaDownloader.deleteIfUnowned(p);
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (!isRequestIdentityValid(request, request.account)) {
            cleanupPaths(out);
            out.clear();
        }
        return out;
    }

    private static final class PreparedPhoto {
        final String sourcePath;
        final String uploadPath;
        final TLRPC.TL_photo photo;
        PreparedPhoto(String sourcePath, String uploadPath, TLRPC.TL_photo photo) {
            this.sourcePath = sourcePath;
            this.uploadPath = uploadPath;
            this.photo = photo;
        }
    }

    private List<PreparedPhoto> prepareAlbumPhotos(RequestContext request, List<String> paths) {
        ArrayList<PreparedPhoto> out = new ArrayList<>(paths.size());
        int account = request.account;
        if (!isRequestIdentityValid(request, account)) {
            cleanupPaths(paths);
            return out;
        }
        SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
        for (String path : paths) {
            if (!isRequestIdentityValid(request, account)) {
                cleanupUnownedPhotos(out);
                cleanupPaths(paths);
                out.clear();
                return out;
            }
            try {
                TLRPC.TL_photo photo = helper.generatePhotoSizes(path, null);
                if (photo != null && photo.sizes != null && !photo.sizes.isEmpty()) {
                    TLRPC.PhotoSize uploadSize = photo.sizes.get(photo.sizes.size() - 1);
                    String uploadPath = FileLoader.getInstance(account)
                            .getPathToAttach(uploadSize.location, true).getAbsolutePath();
                    out.add(new PreparedPhoto(path, uploadPath, photo));
                } else {
                    FileLog.e("nimarko-media: generatePhotoSizes failed for " + path);
                    NimarkoMediaDownloader.deleteIfUnowned(path);
                }
            } catch (Throwable t) {
                FileLog.e("nimarko-media: album photo decode failed", t);
                NimarkoMediaDownloader.deleteIfUnowned(path);
            }
        }
        return out;
    }

    private void sendHyperlinkedMessage(RequestContext request, int account, long dialogId, String fileUrl,
                                        String originalUrl, String title, boolean isAudio,
                                        MessageObject replyTo, Runnable onSent) {
        if (!isRequestIdentityValid(request, account)) {
            clearInflight(request);
            return;
        }
        if (fileUrl == null || fileUrl.isEmpty()) {
            
            finishWithError(request, fallbackFragment(),
                    LocaleController.getString(R.string.NM_NM_GenericError));
            return;
        }
        String downloadText = LocaleController.getString(R.string.NM_NM_Download);
        String sourceText = LocaleController.getString(R.string.NM_NM_Source);

        StringBuilder body = new StringBuilder();
        body.append("📥 ").append(downloadText).append(" • 🔗 ").append(sourceText);
        if (title != null && !title.isEmpty() && !isAudio) {
            body.append("\n\n🎬 ").append(title);
        }
        String message = body.toString();

        int downloadOffset = 3;
        int sourceOffset = 3 + downloadText.length() + 3 + 3;

        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        TLRPC.TL_messageEntityTextUrl e1 = new TLRPC.TL_messageEntityTextUrl();
        e1.offset = downloadOffset;
        e1.length = downloadText.length();
        e1.url = fileUrl;
        entities.add(e1);
        if (originalUrl != null && !originalUrl.isEmpty()) {
            TLRPC.TL_messageEntityTextUrl e2 = new TLRPC.TL_messageEntityTextUrl();
            e2.offset = sourceOffset;
            e2.length = sourceText.length();
            e2.url = originalUrl;
            entities.add(e2);
        }

        AndroidUtilities.runOnUIThread(() -> {
            if (!isRequestIdentityValid(request, account)) {
                clearInflight(request);
                return;
            }
            try {
                SendOptions options = request.options;
                SendMessagesHelper.SendMessageParams params =
                        SendMessagesHelper.SendMessageParams.of(
                                message,            
                                dialogId,           
                                replyTo,            
                                request.replyToTop, 
                                null,               
                                true,               
                                entities,           
                                null,               
                                null,               
                                options.notify,
                                options.scheduleDate,
                                options.scheduleRepeatPeriod,
                                null,               
                                false               
                        );
                options.applyTo(params);
                SendMessagesHelper.getInstance(account).sendMessage(params);
                if (onSent != null) onSent.run();
            } catch (Throwable t) {
                FileLog.e("nimarko-media: sendHyperlinkedMessage failed", t);
                showError(fallbackFragment(), LocaleController.getString(R.string.NM_NM_GenericError));
            } finally {
                clearInflight(request);
            }
        });
    }

    private int sendAlbumGrouped(RequestContext request, int account, long dialogId, List<PreparedPhoto> photos,
                                  String originalUrl, MessageObject replyTo) {
        if (!isRequestIdentityValid(request, account)) {
            return -1;
        }
        
        final int CHUNK = 10;
        int totalChunks = (photos.size() + CHUNK - 1) / CHUNK;
        String sourceLabel = LocaleController.getString(R.string.NM_NM_Source);
        String sourceCaption = "🔗 " + sourceLabel;
        ArrayList<TLRPC.MessageEntity> sourceEntities = new ArrayList<>();
        if (originalUrl != null && !originalUrl.isEmpty()) {
            TLRPC.TL_messageEntityTextUrl e = new TLRPC.TL_messageEntityTextUrl();
            e.offset = 3; 
            e.length = sourceLabel.length();
            e.url = originalUrl;
            sourceEntities.add(e);
        }

        SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
        ensureUploadObserver(account);
        SendOptions options = request.options;
        int sent = 0;
        for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
            int from = chunkIdx * CHUNK;
            int to = Math.min(from + CHUNK, photos.size());
            boolean isLast = (chunkIdx == totalChunks - 1);
            long groupId = Utilities.random.nextLong();

            for (int i = from; i < to; i++) {
                if (!isRequestIdentityValid(request, account)) {
                    return -1;
                }
                PreparedPhoto prepared = photos.get(i);
                String path = prepared.sourcePath;
                boolean isFinal = (i == to - 1);

                java.util.HashMap<String, String> sendParams = new java.util.HashMap<>();
                sendParams.put("groupId", Long.toString(groupId));
                if (isFinal) sendParams.put("final", "1");

                String captionForThis = (isLast && isFinal) ? sourceCaption : null;
                ArrayList<TLRPC.MessageEntity> entsForThis = (isLast && isFinal)
                        ? sourceEntities : null;
                MessageObject replyForThis = (chunkIdx == 0 && i == from) ? replyTo : null;

                SendMessagesHelper.SendMessageParams params =
                        SendMessagesHelper.SendMessageParams.of(
                                prepared.photo,
                                path,                       
                                dialogId,                   
                                replyForThis,               
                                request.replyToTop,         
                                captionForThis,             
                                entsForThis,                
                                null,                       
                                sendParams,                 
                                options.notify,
                                options.scheduleDate,
                                options.scheduleRepeatPeriod,
                                0,                          
                                null,                       
                                false,                      
                                false                       
                        );
                options.applyTo(params);
                NimarkoMediaDownloader.markUploadOwned(prepared.sourcePath, prepared.uploadPath);
                try {
                    helper.sendMessage(params);
                } catch (Throwable t) {
                    NimarkoMediaDownloader.finishUploadOwnership(prepared.sourcePath);
                    throw t;
                }
                sent++;
            }
        }
        return sent;
    }

    private void ensureUploadObserver(int account) {
        if (account < 0 || account >= observingUploads.length || observingUploads[account]) return;
        observingUploads[account] = true;
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.fileUploaded);
        center.addObserver(this, NotificationCenter.fileUploadFailed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if ((id == NotificationCenter.fileUploaded || id == NotificationCenter.fileUploadFailed)
                && args != null && args.length > 0 && args[0] instanceof String) {
            NimarkoMediaDownloader.finishUploadOwnership((String) args[0]);
        }
    }

    private void successBulletin(BaseFragment f, boolean fromCache) {
        String msg = fromCache
                ? LocaleController.getString(R.string.NM_NM_SentFromCache)
                : LocaleController.getString(R.string.NM_NM_Sent);
        showSuccess(f, msg);
    }

    private void successBulletinAlbum(BaseFragment f, int count, boolean fromCache) {
        String msg;
        if (fromCache) {
            msg = LocaleController.getString(R.string.NM_NM_SentFromCache);
        } else {
            msg = LocaleController.formatString(R.string.NM_NM_PhotosSent, count);
        }
        showSuccess(f, msg);
    }

    private void finishWithError(RequestContext request, BaseFragment f, String msg) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!isRequestIdentityValid(request, request.account)) {
                clearInflight(request);
                return;
            }
            showError(f, msg != null ? msg :
                    LocaleController.getString(R.string.NM_NM_GenericError));
            clearInflight(request);
        });
    }

    private void clearInflight(RequestContext request) {
        activeRequest.compareAndSet(request, null);
    }

    private boolean isRequestIdentityValid(RequestContext request, int account) {
        return request != null
                && activeRequest.get() == request
                && request.account == account
                && request.userId != 0
                && accountUserId(account) == request.userId;
    }

    private void cleanupPaths(List<String> paths) {
        if (paths == null) return;
        for (String path : paths) {
            if (path != null) {
                NimarkoMediaDownloader.deleteIfUnowned(path);
            }
        }
    }

    private BaseFragment fallbackFragment() {
        try {
            return LaunchActivity.getLastFragment();
        } catch (Throwable t) {
            return null;
        }
    }

    private void showText(BaseFragment fragment, String fallbackEmoji, CharSequence msg) {
        if (msg == null) return;
        final String full = msg.toString();
        
        final String emoji;
        final String text;
        int cp = full.isEmpty() ? 0 : full.codePointAt(0);
        boolean leadingEmoji = cp > 0xFFFF || (cp >= 0x2190 && cp <= 0x2BFF);
        if (leadingEmoji) {
            int len = Character.charCount(cp);
            emoji = full.substring(0, len);
            text = full.substring(len).trim();
        } else {
            emoji = fallbackEmoji;
            text = full;
        }
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment f = fragment != null ? fragment : fallbackFragment();
            try {
                BulletinFactory bf = (f != null && BulletinFactory.canShowBulletin(f))
                        ? BulletinFactory.of(f) : BulletinFactory.global();
                if (emoji != null) {
                    bf.createEmojiBulletin(emoji, text).show();
                } else {
                    bf.createSimpleBulletin(0, text).show();
                }
            } catch (Throwable t) {
                FileLog.e("nimarko-media: showText bulletin failed", t);
            }
        });
    }

    private void showError(BaseFragment fragment, String msg) { showText(fragment, "❌", msg); }
    private void showSuccess(BaseFragment fragment, String msg) { showText(fragment, "✅", msg); }
    private void showInfo(BaseFragment fragment, String msg) { showText(fragment, "📥", msg); }

    public static boolean isNimarkoMediaPluginFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return false;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null && lines < 30) {
                lines++;
                String trimmed = line.trim();
                if (trimmed.startsWith("__id__")) {
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) continue;
                    String rhs = trimmed.substring(eq + 1).trim();
                    if (rhs.length() < 2) continue;
                    char q = rhs.charAt(0);
                    if (q != '"' && q != '\'') continue;
                    int end = rhs.indexOf(q, 1);
                    if (end < 0) continue;
                    String id = rhs.substring(1, end);
                    return "NimarkoMedia".equals(id);
                }
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-media: isNimarkoMediaPluginFile failed", t);
        }
        return false;
    }
}
