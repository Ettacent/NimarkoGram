/* Modifications Copyright (C) 2026 Ettacent */


package app.nimarkogram.messenger.media;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.TimeUnit;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.MessageObject;
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

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.utils.InlineAuth;

public final class MediaControllerExt {

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
        final long generation;
        final MediaDownloader.TransferSession transfer = new MediaDownloader.TransferSession();
        final Set<String> paths = ConcurrentHashMap.newKeySet();
        Future<?> worker;
        Runnable deadline;
        final AtomicBoolean downloadStarted = new AtomicBoolean();

        RequestContext(SendOptions options, MessageObject replyToTop,
                       int account, long userId, long generation) {
            this.options = options != null ? options : SendOptions.immediate();
            this.replyToTop = replyToTop;
            this.account = account;
            this.userId = userId;
            this.generation = generation;
        }
    }

    private static volatile MediaControllerExt instance;
    public static MediaControllerExt getInstance() {
        MediaControllerExt current = instance;
        if (current == null) {
            synchronized (MediaControllerExt.class) {
                current = instance;
                if (current == null) instance = current = new MediaControllerExt();
            }
        }
        return current;
    }

    public static void onAccountLoggedOut(int account) {
        MediaControllerExt current = instance;
        if (current == null) return;
        current.generations.incrementAndGet(account);
        RequestContext request = current.activeRequest.get();
        if (request != null && request.account == account) current.clearInflight(request);
    }

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
    private final AtomicLongArray generations = new AtomicLongArray(UserConfig.MAX_ACCOUNT_COUNT);

    private MediaControllerExt() {}

    public void tryHandle(MessageObject message, ChatActivity chatActivity) {
        tryHandle(message, chatActivity, SendOptions.immediate());
    }

    public void tryHandle(MessageObject message, ChatActivity chatActivity, SendOptions options) {
        if (message == null || chatActivity == null) return;
        CharSequence text = message.messageText;
        if (text == null) return;
        MediaDownloader.UrlMatch match = MediaDownloader.findUrl(text);
        if (match == null) {
            showError(chatActivity, LocaleController.getString(R.string.NM_NM_UnsupportedUrl));
            return;
        }
        if (hasActiveRequest()) {
            showError(chatActivity, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
            return;
        }
        kickoff(chatActivity, chatActivity.getDialogId(), chatActivity.getCurrentAccount(),
                match, message, chatActivity.getThreadMessage(), options, null);
    }

    public static final int INTERCEPT_NONE = 0;

    public static final int INTERCEPT_HIJACKED = 1;

    public static final int INTERCEPT_BLOCKED = 2;
    public static final int INTERCEPT_PENDING = 3;

    public int interceptOutgoingMessage(CharSequence text, ChatActivity chatActivity) {
        return interceptOutgoingMessage(text, chatActivity, SendOptions.immediate());
    }

    public int interceptOutgoingMessage(CharSequence text, ChatActivity chatActivity, SendOptions options) {
        return interceptOutgoingMessage(text, chatActivity, options, null);
    }
    public static boolean isAutoDownloadCandidate(CharSequence text) {
        if (!AppConfig.nimarkoMediaAuto || text == null) return false;
        String trimmed = text.toString().trim();
        return !trimmed.isEmpty() && trimmed.startsWith("http")
                && !trimmed.contains(" ") && !trimmed.contains("\n") && !trimmed.contains("\t")
                && MediaDownloader.findUrl(trimmed) != null;
    }

    public static boolean isSendChoiceEnabled(CharSequence text) {
        if (!isAutoDownloadCandidate(text)) return false;
        if (AppConfig.mediaSendChoice) return true;
        MediaDownloader.UrlMatch match = MediaDownloader.findUrl(text);
        return AppConfig.nimarkoMediaYtAsk && match != null
                && match.platform == MediaDownloader.Platform.YOUTUBE;
    }

    public static int getLastSendChoice(MediaDownloader.Platform platform) {
        int fallback = platform == MediaDownloader.Platform.YOUTUBE
                ? (AppConfig.nimarkoMediaYtFmt == 1 ? MediaSendChoiceView.AUDIO : MediaSendChoiceView.VIDEO)
                : MediaSendChoiceView.MEDIA;
        if (platform == null) return fallback;
        try {
            int choice = AppConfig.getPreferences().getInt("mediaSendChoice." + platform.id, fallback);
            return isValidSendChoice(platform, choice) ? choice : fallback;
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    public static void rememberSendChoice(MediaDownloader.Platform platform, int choice) {
        if (isValidSendChoice(platform, choice)) {
            AppConfig.getEditor().putInt("mediaSendChoice." + platform.id, choice).apply();
        }
    }

    private static boolean isValidSendChoice(MediaDownloader.Platform platform, int choice) {
        return platform != null && (choice == MediaSendChoiceView.LINK
                || (platform == MediaDownloader.Platform.YOUTUBE
                ? choice == MediaSendChoiceView.VIDEO || choice == MediaSendChoiceView.AUDIO
                : choice == MediaSendChoiceView.MEDIA));
    }
    public int interceptOutgoingMessage(CharSequence text, ChatActivity chatActivity, SendOptions options,
                                        Runnable onAccepted) {
        return interceptOutgoingMessage(text, chatActivity, options, onAccepted, null);
    }

    public int interceptOutgoingMessage(CharSequence text, ChatActivity chatActivity, SendOptions options,
                                        Runnable onAccepted, String selectedMediaType) {
        if (chatActivity == null || !isAutoDownloadCandidate(text)) return INTERCEPT_NONE;
        String trimmed = text.toString().trim();
        MediaDownloader.UrlMatch match = MediaDownloader.findUrl(trimmed);
        if (match == null) return INTERCEPT_NONE;
        if (hasActiveRequest()) {

            showError(chatActivity, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
            return INTERCEPT_BLOCKED;
        }

        return kickoff(chatActivity, chatActivity.getDialogId(), chatActivity.getCurrentAccount(),
                match, chatActivity.replyingMessageObject, chatActivity.getThreadMessage(), options, onAccepted, selectedMediaType);
    }

    public void downloadFromUrl(BaseFragment fragment, String url) {
        if (fragment == null || url == null) return;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return;
        MediaDownloader.UrlMatch match = MediaDownloader.findUrl(trimmed);
        if (match == null) {
            showError(fragment, LocaleController.getString(R.string.NM_NM_UnsupportedUrl));
            return;
        }
        if (hasActiveRequest()) {
            showError(fragment, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
            return;
        }
        int account = fragment.getCurrentAccount();
        long savedMessages = UserConfig.getInstance(account).getClientUserId();
        kickoff(fragment, savedMessages, account, match, null, null, SendOptions.immediate(), null);
    }

    private int kickoff(BaseFragment fragmentForBulletins, long dialogId, int account,
                         MediaDownloader.UrlMatch match, MessageObject replyTo,
                         MessageObject replyToTop, SendOptions options, Runnable onAccepted) {
        return kickoff(fragmentForBulletins, dialogId, account, match, replyTo, replyToTop,
                options, onAccepted, null);
    }

    private int kickoff(BaseFragment fragmentForBulletins, long dialogId, int account,
                         MediaDownloader.UrlMatch match, MessageObject replyTo,
                         MessageObject replyToTop, SendOptions options, Runnable onAccepted,
                         String selectedMediaType) {
        if (selectedMediaType != null && !"video".equals(selectedMediaType) && !"audio".equals(selectedMediaType)) {
            return INTERCEPT_BLOCKED;
        }
        RequestContext request = new RequestContext(
                options, replyToTop, account, accountUserId(account), generations.get(account));
        if (!activeRequest.compareAndSet(null, request)) {
            showError(fragmentForBulletins, LocaleController.getString(R.string.NM_NM_AlreadyDownloading));
            return INTERCEPT_BLOCKED;
        }
        request.deadline = () -> {
            if (activeRequest.get() == request) {
                showError(fragmentForBulletins, LocaleController.getString(R.string.NM_NM_GenericError));
                clearInflight(request);
            }
        };
        AndroidUtilities.runOnUIThread(request.deadline, TimeUnit.MINUTES.toMillis(5));
        String platform = match.platform.id;

        if (selectedMediaType == null && "youtube".equals(platform) && AppConfig.nimarkoMediaYtAsk) {
            final Activity parent = fragmentForBulletins != null ? fragmentForBulletins.getParentActivity() : null;
            final View hostView = fragmentForBulletins != null ? fragmentForBulletins.getFragmentView() : null;
            if (parent == null || hostView == null) {
                clearInflight(request);
                return INTERCEPT_BLOCKED;
            }
            final BooleanSupplier canSelect = () ->
                    isRequestIdentityValid(request, account)
                    && !parent.isFinishing() && !parent.isDestroyed()
                    && !fragmentForBulletins.isFinished && !fragmentForBulletins.isPaused()
                    && fragmentForBulletins.getParentActivity() == parent
                    && fragmentForBulletins.getFragmentView() == hostView
                    && fragmentForBulletins.getCurrentAccount() == account
                    && hostView.isAttachedToWindow();
            final Runnable releasePending = () -> {
                if (!request.downloadStarted.get()) clearInflight(request);
            };
            AndroidUtilities.runOnUIThread(() -> {
                if (!canSelect.getAsBoolean()) {
                    releasePending.run();
                    return;
                }
                try {
                    AlertDialog.Builder b = new AlertDialog.Builder(parent,
                            fragmentForBulletins.getResourceProvider());
                    b.setTitle("▶️ YouTube");
                    b.setItems(new CharSequence[]{
                            LocaleController.getString(R.string.NM_NM_FormatVideo),
                            LocaleController.getString(R.string.NM_NM_FormatAudio)
                    }, (dialog, which) -> {
                        if (!canSelect.getAsBoolean()) {
                            releasePending.run();
                            return;
                        }
                        String mt = which == 1 ? "audio" : "video";
                        startDownload(request, fragmentForBulletins,
                                dialogId, account, match, mt, replyTo, onAccepted);
                    });
                    b.setNegativeButton(LocaleController.getString(R.string.Cancel),
                            (dialog, which) -> releasePending.run());
                    AlertDialog d = b.create();
                    d.setOnCancelListener(dialog -> releasePending.run());
                    d.setOnDismissListener(dialog -> releasePending.run());
                    if (fragmentForBulletins.showDialog(d, dialog -> releasePending.run()) == null) {
                        releasePending.run();
                    }
                } catch (RuntimeException e) {
                    FileLog.e(e);
                    releasePending.run();
                }
            });
            return INTERCEPT_PENDING;
        }

        String mediaType = selectedMediaType != null ? selectedMediaType : resolveMediaType(match.platform);
        return startDownload(request, fragmentForBulletins, dialogId, account, match, mediaType, replyTo, onAccepted)
                ? INTERCEPT_HIJACKED : INTERCEPT_BLOCKED;
    }
    private boolean startDownload(RequestContext request, BaseFragment fragmentForBulletins,
                                  long dialogId, int account, MediaDownloader.UrlMatch match,
                                  String mediaType, MessageObject replyTo, Runnable onAccepted) {
        if (!isRequestIdentityValid(request, account)) {
            clearInflight(request);
            return false;
        }
        if (!request.downloadStarted.compareAndSet(false, true)) return false;
        try {
            synchronized (request) {
                if (!isRequestIdentityValid(request, account)) return false;
                request.worker = executor.submit(() -> processDownload(request, fragmentForBulletins,
                    dialogId, account, match, mediaType, replyTo));
            }
        } catch (RuntimeException e) {
            FileLog.e(e);
            finishWithError(request, fragmentForBulletins,
                    LocaleController.getString(R.string.NM_NM_GenericError));
            return false;
        }
        if (onAccepted != null) {
            AndroidUtilities.executeOnUIThread(() -> {
                try {
                    onAccepted.run();
                } catch (RuntimeException e) {
                    FileLog.e(e);
                }
            });
        }
        return true;
    }

    private String resolveMediaType(MediaDownloader.Platform p) {
        if (p.isAudioOnly()) return "audio";
        if (p == MediaDownloader.Platform.YOUTUBE && AppConfig.nimarkoMediaYtFmt == 1) {
            return "audio";
        }
        return "video";
    }

    private void processDownload(RequestContext request, BaseFragment fragmentForBulletins, long dialogId, int account,
                                 MediaDownloader.UrlMatch match, String mediaType,
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
            InlineAuth.Backend authBackend = authBackendForAccount(account, request.transfer);
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

            MediaDownloader.Result result =
                    MediaDownloader.requestDownload(match.url, mediaType, token, request.transfer);
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
                result = MediaDownloader.requestDownload(match.url, mediaType, token, request.transfer);
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

    private String ensureToken(RequestContext request, InlineAuth.Backend backend) {
        String scope = request.account + ":" + request.userId;
        Object lock = authLocks.computeIfAbsent(scope, ignored -> new Object());
        return InlineAuth.ensureToken(
                request.account, request.userId, lock, backend,
                () -> isRequestIdentityValid(request, request.account));
    }

    public static InlineAuth.Backend authBackendForAccount(final int account) {
        return authBackendForAccount(account, null);
    }

    private static InlineAuth.Backend authBackendForAccount(final int account, MediaDownloader.TransferSession transfer) {
        if (AppConfig.getPreferences().contains("nimarkoMediaAuthToken")) {
            AppConfig.getEditor().remove("nimarkoMediaAuthToken").apply();
        }
        final long uid = accountUserId(account);
        final String tokenKey = "token_" + account + "_" + uid;
        return new InlineAuth.Backend() {
            @Override public String cachedToken() {
                if (uid == 0 || accountUserId(account) != uid || transfer != null && !transfer.isActive()) return null;
                String token = authPreferences().getString(tokenKey, null);
                return token == null || token.isEmpty() ? null : token;
            }

            @Override public void cacheToken(String token) {
                if (uid == 0 || accountUserId(account) != uid || transfer != null && !transfer.isActive()) return;
                SharedPreferences.Editor editor = authPreferences().edit();
                if (token == null || token.isEmpty()) {
                    editor.remove(tokenKey);
                } else {
                    editor.putString(tokenKey, token);
                }
                editor.apply();
            }

            @Override public InlineAuth.Reg register(long registeredUid) {
                if (uid == 0 || registeredUid != uid
                        || accountUserId(account) != uid) {
                    return new InlineAuth.Reg();
                }
                MediaDownloader.AuthRegister r = MediaDownloader.authRegister(registeredUid, transfer);
                InlineAuth.Reg reg = new InlineAuth.Reg();
                reg.code = r.code;
                reg.botUsername = r.botUsername;
                reg.ok = r.ok;
                return reg;
            }

            @Override public String poll(long registeredUid, String code) {
                if (uid == 0 || registeredUid != uid
                        || accountUserId(account) != uid) {
                    return InlineAuth.GIVE_UP;
                }
                return MediaDownloader.authPoll(registeredUid, code, transfer);
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
                             MediaDownloader.Result result, String platform,
                             boolean isShorts, MessageObject replyTo) {
        String title = ("youtube".equals(platform) && !isShorts) ? result.title : null;
        sendHyperlinkedMessage(request, account, dialogId, result.fileUrl, result.originalUrl,
                title, false, replyTo, () -> successBulletin(fragmentForBulletins, result.fromCache));
    }

    private void handleAudio(RequestContext request, BaseFragment fragmentForBulletins, int account, long dialogId,
                             MediaDownloader.Result result, MessageObject replyTo) {
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
                             MediaDownloader.Result result, MessageObject replyTo) {
        if (!isRequestIdentityValid(request, account)) {
            clearInflight(request);
            return;
        }
        if (result.images == null || result.images.isEmpty() || result.images.size() > MAX_ALBUM_IMAGES) {
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
        if (paths.size() != result.images.size()) {
            cleanupPaths(paths);
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
        if (photos.size() != paths.size()) {
            cleanupUnownedPhotos(photos);
            cleanupPaths(paths);
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
                if (sent == result.images.size()) {
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
            if (!photo.sourcePath.equals(photo.retainedPath)) MediaDownloader.deleteIfUnowned(photo.sourcePath);
            if (!photo.sourcePath.equals(photo.uploadPath) && !photo.uploadPath.equals(photo.retainedPath)) {
                MediaDownloader.deleteIfUnowned(photo.uploadPath);
            }
        }
    }

    private List<String> downloadImagesParallel(RequestContext request, List<String> urls) {
        List<String> out = new ArrayList<>();
        if (urls == null || urls.isEmpty() || urls.size() > MAX_ALBUM_IMAGES
                || !isRequestIdentityValid(request, request.account)) {
            return out;
        }
        int count = urls.size();
        List<Future<String>> futures = new ArrayList<>(count);
        String batch = Long.toHexString(System.nanoTime());
        boolean failed = false;
        boolean interrupted = false;
        AtomicBoolean accepting = new AtomicBoolean(true);
        try {
        for (int i = 0; i < count; i++) {
            final int idx = i;
            final String url = urls.get(i);
                futures.add(imagePool.submit(() -> {
                    if (!isRequestIdentityValid(request, request.account)) return null;
                    String path = MediaDownloader.download(url, "album_" + batch + "_" + idx + ".jpg", request.transfer);
                    synchronized (request) {
                        if (!accepting.get() || !isRequestIdentityValid(request, request.account)) {
                            MediaDownloader.deleteIfUnowned(path);
                            return null;
                        }
                        if (path != null) request.paths.add(path);
                        return path;
                    }
                }));
            }
        } catch (RuntimeException e) {
            FileLog.e("nimarko-media: album download submission failed", e);
            failed = true;
        }
        waiting:
        for (Future<String> f : futures) {
            while (true) {
                if (!isRequestIdentityValid(request, request.account)) {
                    failed = true;
                    break waiting;
                }
                try {
                    String p = f.get(100, TimeUnit.MILLISECONDS);
                if (p != null) {
                        out.add(p);
                    } else {
                        failed = true;
                    }
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                    failed = true;
                    break waiting;
                } catch (java.util.concurrent.TimeoutException e) {
                    continue;
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
                    FileLog.e("nimarko-media: album download failed", e);
                    failed = true;
                    break;
                }
            }
        }
        interrupted |= Thread.currentThread().isInterrupted();
        if (failed || interrupted || out.size() != count || !isRequestIdentityValid(request, request.account)) {
            synchronized (request) {
                accepting.set(false);
                request.transfer.cancelCalls();
                cleanupPaths(new ArrayList<>(request.paths));
                request.paths.clear();
            }
            for (Future<String> future : futures) future.cancel(true);
            cleanupPaths(out);
            out.clear();
        }
        if (interrupted) Thread.currentThread().interrupt();
        return out;
    }

    private static final class PreparedPhoto {
        final String sourcePath;
        final String uploadPath;
        final TLRPC.TL_photo photo;
        String retainedPath;
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
                    File uploadFile = new File(uploadPath);
                    if (!uploadFile.isFile() || uploadFile.length() == 0) {
                        FileLog.e("nimarko-media: prepared album photo is missing for " + path);
                        MediaDownloader.deleteIfUnowned(uploadPath);
                        break;
                    }
                    out.add(new PreparedPhoto(path, uploadPath, photo));
                } else {
                    FileLog.e("nimarko-media: generatePhotoSizes failed for " + path);
                    MediaDownloader.deleteIfUnowned(path);
                }
            } catch (Throwable t) {
                FileLog.e("nimarko-media: album photo decode failed", t);
                MediaDownloader.deleteIfUnowned(path);
            }
        }
        if (out.size() != paths.size() || !isRequestIdentityValid(request, account)) {
            cleanupUnownedPhotos(out);
            cleanupPaths(paths);
            out.clear();
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
        SendOptions options = request.options;
        int sent = 0;
        for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
            int from = chunkIdx * CHUNK;
            int to = Math.min(from + CHUNK, photos.size());
            boolean isLast = (chunkIdx == totalChunks - 1);
            long groupId = Utilities.random.nextLong();

            try {
            for (int i = from; i < to; i++) {
                if (!isRequestIdentityValid(request, account)) {
                    return -1;
                }
                PreparedPhoto prepared = photos.get(i);
                String path = prepared.sourcePath;
                boolean isFinal = (i == to - 1);

                HashMap<String, String> sendParams = new HashMap<>();
                sendParams.put("groupId", Long.toString(groupId));
                if (isFinal) sendParams.put("final", "1");

                String captionForThis = (isLast && isFinal) ? sourceCaption : null;
                ArrayList<TLRPC.MessageEntity> entsForThis = (isLast && isFinal)
                        ? sourceEntities : null;
                MessageObject replyForThis = chunkIdx == 0 ? replyTo : null;

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
                    MediaDownloader.markUploadOwned(prepared.sourcePath, prepared.uploadPath);
                    try {
                        SendMessagesHelper.MediaSendResult admission = helper.sendMediaMessage(
                                params, () -> isRequestIdentityValid(request, account));
                        if (admission.message != null) {
                            prepared.retainedPath = admission.message.messageOwner.attachPath;
                            request.paths.remove(prepared.sourcePath);
                        }
                        if (!admission.accepted || admission.message == null
                                || admission.message.getGroupId() != groupId
                                || admission.message.getDialogId() != dialogId
                                || (admission.message.messageOwner.params != null
                                && admission.message.messageOwner.params.containsKey("final")) != isFinal) {
                            return -1;
                        }
                    } finally {
                        MediaDownloader.transferUploadOwnership(prepared.sourcePath, prepared.retainedPath);
                }
                sent++;
            }
            } finally {
                helper.cancelPendingMediaGroup(groupId);
            }
        }
        return sent;
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
        synchronized (request) {
            request.transfer.cancel();
            if (request.deadline != null) AndroidUtilities.cancelRunOnUIThread(request.deadline);
            if (request.worker != null) request.worker.cancel(true);
            cleanupPaths(new ArrayList<>(request.paths));
            request.paths.clear();
        }
    }

    private boolean hasActiveRequest() {
        RequestContext request = activeRequest.get();
        if (request != null && !isRequestIdentityValid(request, request.account)) clearInflight(request);
        return activeRequest.get() != null;
    }

    private boolean isRequestIdentityValid(RequestContext request, int account) {
        return request != null
                && activeRequest.get() == request
                && request.account == account
                && request.userId != 0
                && request.generation == generations.get(account)
                && request.transfer.isActive()
                && accountUserId(account) == request.userId;
    }

    private void cleanupPaths(List<String> paths) {
        if (paths == null) return;
        for (String path : paths) {
            if (path != null) {
                MediaDownloader.deleteIfUnowned(path);
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
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
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
