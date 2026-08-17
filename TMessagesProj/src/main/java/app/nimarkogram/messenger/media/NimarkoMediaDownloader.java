 
package app.nimarkogram.messenger.media;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public final class NimarkoMediaDownloader {

    public static final String API_URL = org.telegram.messenger.BuildConfig.NIMARKO_MEDIA_API_URL;
    public static final long TELEGRAM_UPLOAD_LIMIT_BYTES = 18L * 1024L * 1024L;

    public enum Platform {
        TIKTOK("tiktok", Pattern.compile(
            "(?:https?://)?(?:www\\.|vm\\.|vt\\.|m\\.)?tiktok\\.com/\\S+",
            Pattern.CASE_INSENSITIVE)),
        YOUTUBE("youtube", Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?v=|shorts/|live/|embed/)|youtu\\.be/)[\\w-]+",
            Pattern.CASE_INSENSITIVE)),
        SOUNDCLOUD("soundcloud", Pattern.compile(
            "(?:https?://)?(?:www\\.|on\\.|m\\.)?soundcloud\\.com/\\S+",
            Pattern.CASE_INSENSITIVE)),
        INSTAGRAM("instagram", Pattern.compile(
            "(?:https?://)?(?:www\\.)?instagram\\.com/(?:[\\w.-]+/)?(?:p|reel|reels|tv|stories)/[\\w-]+",
            Pattern.CASE_INSENSITIVE)),
        TWITTER("twitter", Pattern.compile(
            "(?:https?://)?(?:www\\.|mobile\\.)?(?:twitter|x|fxtwitter|vxtwitter|fixupx)\\.com/[\\w]+/status(?:es)?/\\d+",
            Pattern.CASE_INSENSITIVE)),
        REDDIT("reddit", Pattern.compile(
            "(?:https?://)?(?:(?:www|old|new|np|i|m)\\.)?reddit\\.com/(?:r/\\w+/(?:comments|s)/|gallery/)[\\w]+|(?:https?://)?(?:v\\.)?redd\\.it/[\\w]+",
            Pattern.CASE_INSENSITIVE)),
        VK("vk", Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.)?(?:vk\\.com|vkvideo\\.ru)/(?:video|clip)(?:-?\\d+_\\d+|s/[\\w-]+)",
            Pattern.CASE_INSENSITIVE)),
        PINTEREST("pinterest", Pattern.compile(
            "(?:https?://)?(?:[a-z]{2}\\.)?pinterest\\.[a-z.]+/pin/\\d+|(?:https?://)?pin\\.it/[\\w]+",
            Pattern.CASE_INSENSITIVE)),
        BLUESKY("bluesky", Pattern.compile(
            "(?:https?://)?bsky\\.app/profile/[\\w.:-]+/post/[\\w]+",
            Pattern.CASE_INSENSITIVE)),
        TWITCH("twitch", Pattern.compile(
            "(?:https?://)?(?:clips\\.twitch\\.tv/|(?:www\\.)?twitch\\.tv/\\w+/clip/)[\\w-]+",
            Pattern.CASE_INSENSITIVE)),
        VIMEO("vimeo", Pattern.compile(
            "(?:https?://)?(?:www\\.|player\\.)?vimeo\\.com/(?:video/|channels/[\\w]+/|groups/[\\w]+/videos/)?\\d+",
            Pattern.CASE_INSENSITIVE)),
        DAILYMOTION("dailymotion", Pattern.compile(
            "(?:https?://)?(?:www\\.)?(?:dailymotion\\.com/(?:video|embed/video)/|dai\\.ly/)\\w+",
            Pattern.CASE_INSENSITIVE));

        public final String id;
        public final Pattern pattern;

        Platform(String id, Pattern pattern) {
            this.id = id;
            this.pattern = pattern;
        }

        public boolean isAudioOnly() {
            return this == SOUNDCLOUD;
        }
    }

    public static final Pattern SUPPORTED_DOMAINS_REGEX = Pattern.compile(
        ".*https?://(?:[\\w.-]+\\.)?" +
        "(?:tiktok\\.com|youtu(?:be\\.com|\\.be)|soundcloud\\.com" +
        "|instagram\\.com|(?:fx|vx|fixup)?twitter\\.com|(?:fixup)?x\\.com" +
        "|reddit\\.com|redd\\.it|vk(?:video)?\\.(?:com|ru)" +
        "|pinterest\\.[a-z.]+|pin\\.it|bsky\\.app|twitch\\.tv" +
        "|vimeo\\.com|dailymotion\\.com|dai\\.ly).*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static final Pattern YOUTUBE_SHORTS_PATTERN = Pattern.compile(
        "youtube\\.com/shorts/", Pattern.CASE_INSENSITIVE);

    public static boolean messageHasSupportedUrl(CharSequence text) {
        if (text == null) return false;
        return SUPPORTED_DOMAINS_REGEX.matcher(text).matches();
    }

    public static final class UrlMatch {
        public final Platform platform;
        public final String url;
        public final boolean isShorts;
        public UrlMatch(Platform platform, String url, boolean isShorts) {
            this.platform = platform;
            this.url = url;
            this.isShorts = isShorts;
        }
    }

    public static UrlMatch findUrl(CharSequence text) {
        if (text == null) return null;
        String s = text.toString();
        for (Platform p : Platform.values()) {
            Matcher m = p.pattern.matcher(s);
            if (m.find()) {
                String url = m.group();
                boolean isShorts = p == Platform.YOUTUBE && YOUTUBE_SHORTS_PATTERN.matcher(url).find();
                return new UrlMatch(p, url, isShorts);
            }
        }
        return null;
    }

    public static final class Result {
        public boolean success;
         
        public String type;
        public String fileUrl;
        public String title;
        public String artist;
        public String originalUrl;
        public boolean fromCache;
        public String error;
         
        public String localPath;
        public long localSize;
         
        public java.util.List<String> images;
    }

    public static final class AuthRegister {
        public String code;
        public String botUsername;
        public boolean ok;
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "Telegram-Android/NimarkoGram okhttp/4.12.0")
                    .header("Accept", "application/json")
                    .build()))
            .build();

    private static final Gson GSON = new Gson();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static volatile long lastCacheCleanupMs;
    private static final Object UPLOAD_OWNERSHIP_LOCK = new Object();
    private static final ConcurrentHashMap<String, UploadOwnership> uploadOwnershipByPath =
            new ConcurrentHashMap<>();

    private static final class UploadOwnership {
        final String sourcePath;
        final String uploadPath;
        final AtomicBoolean finished = new AtomicBoolean();

        UploadOwnership(String sourcePath, String uploadPath) {
            this.sourcePath = sourcePath;
            this.uploadPath = uploadPath;
        }
    }

    public static void markUploadOwned(String path) {
        markUploadOwned(path, path);
    }

    public static void markUploadOwned(String sourcePath, String uploadPath) {
        if (sourcePath == null && uploadPath == null) return;
        UploadOwnership ownership = new UploadOwnership(sourcePath, uploadPath);
        
        synchronized (UPLOAD_OWNERSHIP_LOCK) {
            if (sourcePath != null) uploadOwnershipByPath.put(sourcePath, ownership);
            if (uploadPath != null) uploadOwnershipByPath.put(uploadPath, ownership);
        }
    }

    public static void finishUploadOwnership(String path) {
        if (path == null) return;
        synchronized (UPLOAD_OWNERSHIP_LOCK) {
            UploadOwnership ownership = uploadOwnershipByPath.get(path);
            if (ownership == null || !ownership.finished.compareAndSet(false, true)) return;
            if (ownership.sourcePath != null) {
                uploadOwnershipByPath.remove(ownership.sourcePath, ownership);
            }
            if (ownership.uploadPath != null) {
                uploadOwnershipByPath.remove(ownership.uploadPath, ownership);
            }
            
            deletePath(ownership.sourcePath);
            if (ownership.uploadPath != null && !ownership.uploadPath.equals(ownership.sourcePath)) {
                deletePath(ownership.uploadPath);
            }
        }
    }

    public static void deleteIfUnowned(String path) {
        if (path == null) return;
        synchronized (UPLOAD_OWNERSHIP_LOCK) {
            if (uploadOwnershipByPath.containsKey(path)) return;
            deletePath(path);
        }
    }

    private static void deletePath(String path) {
        if (path == null) return;
        try { new File(path).delete(); } catch (Throwable ignored) {}
    }

    public static volatile int lastRequestStatus = 0;

    public static Result requestDownload(String url, String type, String authToken) {
        Result out = new Result();
        if (!isApiConfigured()) {
            out.success = false;
            out.error = "Media service is not configured";
            return out;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("url", url);
            payload.addProperty("type", type);
            Request.Builder builder = new Request.Builder()
                    .url(API_URL + "/api/download")
                    .post(RequestBody.create(payload.toString(), JSON_TYPE));
            if (authToken != null && !authToken.isEmpty()) {
                builder.header("X-Auth-Token", authToken);
            }
            Request req = builder.build();
            try (Response resp = HTTP.newCall(req).execute()) {
                lastRequestStatus = resp.code();
                if (resp.code() == 401) {
                    out.success = false;
                    out.error = "auth";
                    return out;
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    out.success = false;
                    out.error = "Empty response";
                    return out;
                }
                String text = body.string();
                JsonObject data;
                try {
                    data = GSON.fromJson(text, JsonObject.class);
                } catch (JsonSyntaxException jse) {
                    out.success = false;
                    out.error = "Bad JSON: HTTP " + resp.code();
                    return out;
                }
                if (data == null) {
                    out.success = false;
                    out.error = "Empty response";
                    return out;
                }
                out.success = optBool(data, "success", false);
                if (!out.success) {
                    out.error = optString(data, "error", "Unknown error");
                    return out;
                }
                out.type = optString(data, "type", "video");
                out.fileUrl = optString(data, "fileUrl", null);
                out.title = optString(data, "title", null);
                out.artist = optString(data, "artist", null);
                out.originalUrl = optString(data, "originalUrl", url);
                out.fromCache = optBool(data, "fromCache", false);
                if (data.has("images") && data.get("images").isJsonArray()) {
                    out.images = new java.util.ArrayList<>();
                    for (JsonElement el : data.getAsJsonArray("images")) {
                        if (el != null && el.isJsonPrimitive()) {
                            out.images.add(el.getAsString());
                        }
                    }
                }
                return out;
            }
        } catch (IOException ioe) {
            FileLog.e("nimarko-media: requestDownload IO", ioe);
            out.success = false;
            out.error = "Connection error";
            return out;
        } catch (Throwable t) {
            FileLog.e("nimarko-media: requestDownload failed", t);
            out.success = false;
            out.error = t.getMessage() != null ? t.getMessage() : "Download error";
            return out;
        }
    }

    public static AuthRegister authRegister(long userId) {
        AuthRegister out = new AuthRegister();
        if (!isApiConfigured()) return out;
        try {
            Request req = new Request.Builder()
                    .url(API_URL + "/api/v1/auth/register?user_id=" + userId)
                    .post(RequestBody.create("", JSON_TYPE))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (resp.code() != 200 || resp.body() == null) {
                    return out;
                }
                String text = resp.body().string();
                JsonObject data = GSON.fromJson(text, JsonObject.class);
                if (data == null) return out;
                out.code = optString(data, "code", null);
                out.botUsername = optString(data, "bot_username", null);
                out.ok = out.code != null && !out.code.isEmpty()
                       && out.botUsername != null && !out.botUsername.isEmpty();
                return out;
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-media: authRegister failed", t);
            return out;
        }
    }

    public static String authPoll(long userId, String code) {
        if (!isApiConfigured() || code == null || code.isEmpty()) return null;
        try {
            Request req = new Request.Builder()
                    .url(API_URL + "/api/v1/auth/poll?user_id=" + userId + "&code=" + code)
                    .post(RequestBody.create("", JSON_TYPE))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (resp.code() == 404) {
                    
                    return null;
                }
                if (resp.code() != 200 || resp.body() == null) {
                    return null;
                }
                String text = resp.body().string();
                JsonObject data = GSON.fromJson(text, JsonObject.class);
                if (data == null) return null;
                String token = optString(data, "token", null);
                return (token != null && !token.isEmpty()) ? token : null;
            }
        } catch (Throwable t) {
            
            return null;
        }
    }

    private static boolean isApiConfigured() {
        return API_URL != null && !API_URL.trim().isEmpty();
    }

    public static String download(String fileUrl, String suggestedName) {
        if (fileUrl == null || fileUrl.isEmpty()) return null;
        File out = null;
        boolean complete = false;
        try {
            File baseDir = new File(ApplicationLoader.getFilesDirFixed().getParentFile(), "cache/nimarko_media");
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                FileLog.e("nimarko-media: could not create cache dir " + baseDir);
            }
            cleanupStaleCache(baseDir);
            String fname = (suggestedName != null && !suggestedName.isEmpty())
                    ? suggestedName : ("media_" + System.currentTimeMillis());
            String safe = sanitize(fname);
            int dot = safe.lastIndexOf('.');
            String prefix = dot > 0 ? safe.substring(0, dot) : safe;
            String suffix = dot > 0 ? safe.substring(dot) : ".tmp";
            if (prefix.length() < 3) prefix = "nmk_" + prefix;
            out = File.createTempFile(prefix + "_", suffix, baseDir);

            Request req = new Request.Builder().url(fileUrl).get().build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    FileLog.e("nimarko-media: download HTTP " + resp.code());
                    return null;
                }
                long declaredLength = resp.body().contentLength();
                if (declaredLength > TELEGRAM_UPLOAD_LIMIT_BYTES) {
                    FileLog.e("nimarko-media: response exceeds upload limit: " + declaredLength);
                    return null;
                }
                try (InputStream in = resp.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        total += n;
                        if (total > TELEGRAM_UPLOAD_LIMIT_BYTES) {
                            
                            fos.flush();
                            return null;
                        }
                    }
                    complete = total > 0;
                }
            }
            return complete ? out.getAbsolutePath() : null;
        } catch (Throwable t) {
            FileLog.e("nimarko-media: download failed", t);
            return null;
        } finally {
            if (!complete && out != null && out.exists()) {
                try { out.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void cleanupStaleCache(File dir) {
        long now = System.currentTimeMillis();
        synchronized (UPLOAD_OWNERSHIP_LOCK) {
            if (now - lastCacheCleanupMs < TimeUnit.HOURS.toMillis(1)) return;
            lastCacheCleanupMs = now;
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = now - TimeUnit.HOURS.toMillis(24);
        for (File file : files) {
            if (file != null && file.isFile() && file.lastModified() < cutoff) {
                synchronized (UPLOAD_OWNERSHIP_LOCK) {
                    if (!uploadOwnershipByPath.containsKey(file.getAbsolutePath())) {
                        try { file.delete(); } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    public static String suggestFileName(Platform platform, String mediaType, String title) {
        String ext;
        if ("audio".equals(mediaType)) {
            ext = ".mp3";
        } else {
            ext = ".mp4";
        }
        String base = title != null && !title.isEmpty() ? title : (platform != null ? platform.id : "media");
        return sanitize(base) + "_" + System.currentTimeMillis() + ext;
    }

    private static String sanitize(String in) {
        if (in == null) return "media";
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
            if (sb.length() >= 80) break;
        }
        return sb.length() == 0 ? "media" : sb.toString();
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try {
            return el.getAsString();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean optBool(JsonObject obj, String key, boolean fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try {
            return el.getAsBoolean();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private NimarkoMediaDownloader() {}
}
