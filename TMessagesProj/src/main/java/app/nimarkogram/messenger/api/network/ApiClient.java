package app.nimarkogram.messenger.api.network;

import app.nimarkogram.messenger.api.dto.BadgeDTO;
import app.nimarkogram.messenger.api.dto.ProfileDTO;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class ApiClient {

    public static final class BadgeFetchResult {
        public final boolean success;
        public final Map<Long, BadgeDTO> badges;

        private BadgeFetchResult(boolean success, Map<Long, BadgeDTO> badges) {
            this.success = success;
            this.badges = badges;
        }
    }

    private static final String NIMARKO_BASE_URL = org.telegram.messenger.BuildConfig.NIMARKO_API_BASE_URL;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "Telegram-Android/NimarkoGram okhttp/4.12.0")
                    .header("Accept", "application/json")
                    .build()))
            .build();

    private static final Gson GSON = new Gson();

    private ApiClient() {}

    public static List<ProfileDTO> getAllProfiles() {
        return Collections.emptyList();
    }

    public static Map<Long, BadgeDTO> getNimarkoBadges() {
        BadgeFetchResult result = fetchNimarkoBadges();
        return result.success ? result.badges : Collections.emptyMap();
    }

    public static BadgeFetchResult fetchNimarkoBadges() {
        if (NIMARKO_BASE_URL == null || NIMARKO_BASE_URL.trim().isEmpty()) {
            return new BadgeFetchResult(false, Collections.emptyMap());
        }
        Request req = new Request.Builder().url(NIMARKO_BASE_URL + "badges").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                return new BadgeFetchResult(false, Collections.emptyMap());
            }
            JsonObject root = GSON.fromJson(resp.body().charStream(), JsonObject.class);
            if (root == null || !root.has("badges") || !root.get("badges").isJsonObject()) {
                return new BadgeFetchResult(false, Collections.emptyMap());
            }
            JsonObject badges = root.getAsJsonObject("badges");
            Map<Long, BadgeDTO> out = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : badges.entrySet()) {
                try {
                    long uid = Long.parseLong(entry.getKey());
                    if (uid == 0L || !entry.getValue().isJsonObject()) {
                        return new BadgeFetchResult(false, Collections.emptyMap());
                    }
                    JsonObject row = entry.getValue().getAsJsonObject();
                    if (!row.has("emoji_doc_id") || !row.get("emoji_doc_id").isJsonPrimitive()) {
                        return new BadgeFetchResult(false, Collections.emptyMap());
                    }
                    long docId = row.get("emoji_doc_id").getAsLong();
                    if (docId <= 0L) return new BadgeFetchResult(false, Collections.emptyMap());
                    String text = null;
                    if (row.has("text") && !row.get("text").isJsonNull()) {
                        if (!row.get("text").isJsonPrimitive()) {
                            return new BadgeFetchResult(false, Collections.emptyMap());
                        }
                        text = row.get("text").getAsString();
                    }
                    out.put(uid, new BadgeDTO(docId, text));
                } catch (Throwable malformedRow) {
                    return new BadgeFetchResult(false, Collections.emptyMap());
                }
            }
            return new BadgeFetchResult(true, out);
        } catch (IOException e) {
            FileLog.e("nimarko-badges: getNimarkoBadges failed", e);
            return new BadgeFetchResult(false, Collections.emptyMap());
        } catch (Throwable t) {
            FileLog.e("nimarko-badges: getNimarkoBadges parse failed", t);
            return new BadgeFetchResult(false, Collections.emptyMap());
        }
    }
}
