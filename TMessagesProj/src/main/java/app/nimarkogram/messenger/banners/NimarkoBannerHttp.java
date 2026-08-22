 
package app.nimarkogram.messenger.banners;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import org.telegram.messenger.FileLog;
import android.util.AtomicFile;

public final class NimarkoBannerHttp {

    public static final String API = org.telegram.messenger.BuildConfig.NIMARKO_BANNER_API_URL;
    public static final String PLACEHOLDER_URL = org.telegram.messenger.BuildConfig.NIMARKO_BANNER_PLACEHOLDER_URL;
    public static final long MAX_SIZE = 8L << 20; 
    private static final long MAX_JSON_SIZE = 256L << 10;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private static final Gson GSON = new Gson();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    public static final class Status {
        public int httpCode = -1;
        public boolean ok;            
        public String status = "none";
        public boolean hideAvatar;
        public boolean hasSound;
        public String rawJson;        
    }

    public static Status fetchStatus(long userId) {
        Status out = new Status();
        if (!isConfigured()) return out;
        try {
            Request req = new Request.Builder().url(API + "/status/" + userId).get().build();
            try (Response resp = HTTP.newCall(req).execute()) {
                out.httpCode = resp.code();
                if (resp.code() != 200 || resp.body() == null) return out;
                String text = readBodyLimited(resp.body(), MAX_JSON_SIZE);
                if (text == null) return out;
                JsonObject d = parse(text);
                if (d == null) return out;
                out.ok = true;
                out.rawJson = text;
                out.status = optString(d, "status", "none");
                out.hideAvatar = optBool(d, "hide_avatar", false);
                out.hasSound = optBool(d, "has_sound", false);
                return out;
            }
        } catch (Throwable t) {
            return out;
        }
    }

    public static final class BannerInfo {
        public int httpCode;
        public boolean hasBanner;
        public String url;
        public String type = "jpg";
        public String version = "";
        public boolean hasSound;
        public boolean hideAvatar;
    }

    public static BannerInfo getBanner(long eid) {
        BannerInfo out = new BannerInfo();
        if (!isConfigured()) return out;
        try {
            String url = API + "/get/" + eid;
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = HTTP.newCall(req).execute()) {
                out.httpCode = resp.code();
                if (resp.code() != 200 || resp.body() == null) {
                    return out;
                }
                String bodyStr = readBodyLimited(resp.body(), MAX_JSON_SIZE);
                if (bodyStr == null) return out;
                JsonObject d = parse(bodyStr);
                if (d == null) return out;
                out.hasBanner = optBool(d, "has_banner", false);
                if (!out.hasBanner) return out;
                out.url = optString(d, "url", null);
                out.type = optString(d, "type", "jpg");
                out.version = optString(d, "version", "");
                out.hasSound = optBool(d, "has_sound", false);
                out.hideAvatar = optBool(d, "hide_avatar", false);
                return out;
            }
        } catch (Throwable t) {
            out.httpCode = -1;
            return out;
        }
    }

    public static int setHideAvatar(long userId, boolean hide) {
        if (!isConfigured()) return -1;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("user_id", userId);
            payload.addProperty("hide_avatar", hide);
            Request req = new Request.Builder()
                    .url(API + "/settings")
                    .post(RequestBody.create(payload.toString(), JSON_TYPE))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                return resp.code();
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    public static final class AuthRegister {
        public String code;
        public String botUsername;
        public boolean ok;
    }

    public static AuthRegister authRegister(long userId) {
        AuthRegister out = new AuthRegister();
        if (!isConfigured()) return out;
        try {
            Request req = new Request.Builder()
                    .url(API + "/auth/register?user_id=" + userId)
                    .post(RequestBody.create("", JSON_TYPE))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (resp.code() != 200 || resp.body() == null) return out;
                String text = readBodyLimited(resp.body(), MAX_JSON_SIZE);
                if (text == null) return out;
                JsonObject d = parse(text);
                if (d == null) return out;
                out.code = optString(d, "code", null);
                out.botUsername = optString(d, "bot_username", null);
                out.ok = out.code != null && !out.code.isEmpty()
                       && out.botUsername != null && !out.botUsername.isEmpty();
                return out;
            }
        } catch (Throwable t) {
            return out;
        }
    }

    public static String authPoll(long userId, String code) {
        if (!isConfigured() || code == null || code.isEmpty()) return null;
        try {
            Request req = new Request.Builder()
                    .url(API + "/auth/poll?user_id=" + userId + "&code=" + code)
                    .post(RequestBody.create("", JSON_TYPE))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (resp.code() == 404) return AUTH_GIVE_UP;
                if (resp.code() != 200 || resp.body() == null) return null;
                String text = readBodyLimited(resp.body(), MAX_JSON_SIZE);
                if (text == null) return null;
                JsonObject d = parse(text);
                if (d == null) return null;
                String token = optString(d, "token", null);
                return (token != null && !token.isEmpty()) ? token : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static final String AUTH_GIVE_UP = "\0give_up";

    public static final class SubmitResult {
        public int httpCode;
        public boolean success;
        public String error;
    }

    public static SubmitResult submit(File file, String ext, long userId, long size, String token) {
        SubmitResult out = new SubmitResult();
        if (!isConfigured()) return out;
        try {
            String mime = ".mp4".equals(ext) ? "video/mp4"
                    : ".png".equals(ext) ? "image/png" : "image/jpeg";
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "banner" + ext,
                            RequestBody.create(file, MediaType.parse(mime)))
                    .addFormDataPart("user_id", String.valueOf(userId))
                    .addFormDataPart("file_size", String.valueOf(size))
                    .build();
            Request.Builder builder = new Request.Builder().url(API + "/submit").post(body);
            if (token != null && !token.isEmpty()) {
                builder.header("X-Auth-Token", token);
            }
            try (Response resp = HTTP.newCall(builder.build()).execute()) {
                out.httpCode = resp.code();
                if (resp.code() == 200 && resp.body() != null) {
                    String text = readBodyLimited(resp.body(), MAX_JSON_SIZE);
                    if (text == null) return out;
                    JsonObject d = parse(text);
                    if (d != null) {
                        out.success = optBool(d, "success", false);
                        out.error = optString(d, "error", null);
                    }
                }
                return out;
            }
        } catch (Throwable t) {
            FileLog.e("nimarko-banner: submit failed", t);
            out.httpCode = -1;
            return out;
        }
    }

    public static boolean download(String url, File dest) {
        if (url == null || url.isEmpty() || dest == null) return false;
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null || !"https".equalsIgnoreCase(parsed.scheme())) return false;
        AtomicFile atomic = new AtomicFile(dest);
        java.io.FileOutputStream out = null;
        try {
            Request req = new Request.Builder().url(parsed).get().build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return false;
                long declared = resp.body().contentLength();
                if (declared > MAX_SIZE) return false;
                out = atomic.startWrite();
                try (InputStream in = resp.body().byteStream()) {
                    byte[] buf = new byte[8192];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_SIZE) throw new IOException("banner exceeds size limit");
                        out.write(buf, 0, n);
                    }
                    if (declared >= 0 && total != declared) throw new IOException("truncated banner");
                }
                atomic.finishWrite(out);
                out = null;
            }
            return true;
        } catch (Throwable t) {
            if (out != null) atomic.failWrite(out);
            return false;
        }
    }

    private static boolean isConfigured() {
        return API != null && !API.trim().isEmpty();
    }

    private static String readBodyLimited(ResponseBody body, long maxBytes) throws IOException {
        if (body == null) return null;
        long declared = body.contentLength();
        if (declared > maxBytes) return null;
        try (InputStream in = body.byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) return null;
                out.write(buffer, 0, read);
            }
            if (declared >= 0 && total != declared) return null;
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JsonObject parse(String text) {
        try {
            return GSON.fromJson(text, JsonObject.class);
        } catch (JsonSyntaxException jse) {
            return null;
        }
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

    private NimarkoBannerHttp() {}
}
