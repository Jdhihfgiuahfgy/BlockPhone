package dev.blockphone.fabric.client.music.yandex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.client.music.Http;
import dev.blockphone.fabric.client.music.Track;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Неофициальный API Яндекс Музыки (как в мобильном приложении). Работает по OAuth-токену игрока.
 * Только сетевые вызовы и разбор ответов, без состояния; вызывать не из потока рендера.
 */
public final class YandexApi {
    static final String API = "https://api.music.yandex.net";
    /** Соль подписи ссылки на mp3 (из открытых реализаций клиента). */
    private static final String SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA";

    /** Ошибка с ключом перевода. */
    public static final class ApiException extends IOException {
        public final String key;

        public ApiException(String key) {
            super(key);
            this.key = key;
        }
    }

    public record Account(String uid, String login, String name, boolean plus) {
    }

    private final String token;

    public YandexApi(String token) {
        this.token = token;
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", "OAuth " + token, "X-Yandex-Music-Client", "YandexMusicAndroid/24023621",
                "Accept-Language", "ru");
    }

    private JsonObject get(String path) throws IOException {
        return check(Http.request("GET", API + path, headers(), null));
    }

    private JsonObject post(String path, Map<String, String> form) throws IOException {
        return check(Http.request("POST", API + path, headers(), Http.form(form)));
    }

    private static JsonObject check(Http.Response response) throws ApiException {
        if (response.code() == 401 || response.code() == 403) {
            throw new ApiException("music.error.yandex_auth");
        }
        if (!response.ok()) {
            throw new ApiException("music.error.network");
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ApiException("music.error.network");
        }
    }

    public Account account() throws IOException {
        JsonObject result = get("/account/status").getAsJsonObject("result");
        JsonObject account = result.getAsJsonObject("account");
        String uid = str(account, "uid");
        if (uid.isEmpty() || uid.equals("0")) {
            throw new ApiException("music.error.yandex_auth");
        }
        String name = str(account, "displayName");
        boolean plus = result.has("plus") && result.getAsJsonObject("plus").has("hasPlus")
                && result.getAsJsonObject("plus").get("hasPlus").getAsBoolean();
        return new Account(uid, str(account, "login"), name.isEmpty() ? str(account, "login") : name, plus);
    }

    /** «Мне нравится»: id треков, затем сами треки пачками. */
    public List<Track> likes(String uid, int limit) throws IOException {
        JsonArray items = get("/users/" + uid + "/likes/tracks").getAsJsonObject("result").getAsJsonObject("library")
                .getAsJsonArray("tracks");
        List<String> ids = new ArrayList<>();
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "id");
            String album = str(o, "albumId");
            ids.add(album.isEmpty() ? id : id + ":" + album);
            if (ids.size() >= limit) {
                break;
            }
        }
        return tracks(ids);
    }

    public List<Track> tracks(List<String> ids) throws IOException {
        List<Track> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 100) {
            List<String> part = ids.subList(i, Math.min(ids.size(), i + 100));
            JsonArray array = post("/tracks", Map.of("track-ids", String.join(",", part), "with-positions", "false"))
                    .getAsJsonArray("result");
            for (JsonElement e : array) {
                Track t = parseTrack(e.getAsJsonObject());
                if (t != null) {
                    result.add(t);
                }
            }
        }
        return result;
    }

    public List<Track> search(String text) throws IOException {
        JsonObject result = get("/search?type=track&page=0&nocorrect=false&text=" + Http.encode(text)).getAsJsonObject("result");
        List<Track> list = new ArrayList<>();
        if (result.has("tracks") && result.get("tracks").isJsonObject()) {
            for (JsonElement e : result.getAsJsonObject("tracks").getAsJsonArray("results")) {
                Track t = parseTrack(e.getAsJsonObject());
                if (t != null) {
                    list.add(t);
                }
            }
        }
        return list;
    }

    /** Порция «Моей волны». afterTrackId — последний полученный трек (для продолжения), null — начало. */
    public List<Track> wave(@Nullable String afterTrackId) throws IOException {
        String query = "/rotor/station/user:onyourwave/tracks?settings2=true"
                + (afterTrackId != null ? "&queue=" + Http.encode(afterTrackId) : "");
        JsonArray sequence = get(query).getAsJsonObject("result").getAsJsonArray("sequence");
        List<Track> list = new ArrayList<>();
        for (JsonElement e : sequence) {
            JsonObject item = e.getAsJsonObject();
            if (item.has("track")) {
                Track t = parseTrack(item.getAsJsonObject("track"));
                if (t != null) {
                    list.add(t);
                }
            }
        }
        return list;
    }

    public void like(String uid, String trackId, boolean like) throws IOException {
        post("/users/" + uid + "/likes/tracks/" + (like ? "add-multiple" : "remove"), Map.of("track-ids", trackId));
    }

    /** Скачать mp3 трека (лучшее качество, доступное аккаунту) в файл. */
    public void download(String trackId, Path target) throws IOException {
        JsonArray infos = get("/tracks/" + Http.encode(trackId) + "/download-info").getAsJsonArray("result");
        JsonObject best = null;
        for (JsonElement e : infos) {
            JsonObject o = e.getAsJsonObject();
            if (!"mp3".equals(str(o, "codec"))) {
                continue;
            }
            if (best == null || o.get("bitrateInKbps").getAsInt() > best.get("bitrateInKbps").getAsInt()) {
                best = o;
            }
        }
        if (best == null) {
            throw new ApiException("music.error.yandex_unavailable");
        }
        Http.Response xml = Http.request("GET", str(best, "downloadInfoUrl"), headers(), null);
        if (!xml.ok()) {
            throw new ApiException("music.error.network");
        }
        String url = signedUrl(xml.body());
        if (url == null) {
            throw new ApiException("music.error.yandex_unavailable");
        }
        int code = Http.download(url, Map.of(), target, 64L * 1024 * 1024);
        if (code >= 400) {
            throw new ApiException("music.error.network");
        }
    }

    // --- Разбор ---

    /** Ссылка на mp3 из XML download-info: https://host/get-mp3/md5(соль + path[1:] + s)/ts + path. */
    static @Nullable String signedUrl(String xml) {
        String host = tag(xml, "host");
        String path = tag(xml, "path");
        String ts = tag(xml, "ts");
        String s = tag(xml, "s");
        if (host == null || path == null || ts == null || s == null || path.isEmpty()) {
            return null;
        }
        return "https://" + host + "/get-mp3/" + md5(SIGN_SALT + path.substring(1) + s) + "/" + ts + path;
    }

    static String md5(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @Nullable String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).strip() : null;
    }

    /** Трек из JSON; null — недоступен. */
    static @Nullable Track parseTrack(JsonObject o) {
        if (o.has("available") && !o.get("available").getAsBoolean()) {
            return null;
        }
        String id = str(o, "id");
        if (id.isEmpty()) {
            return null;
        }
        String title = str(o, "title");
        String version = str(o, "version");
        if (!version.isEmpty()) {
            title = title + " (" + version + ")";
        }
        List<String> artists = new ArrayList<>();
        if (o.has("artists")) {
            for (JsonElement a : o.getAsJsonArray("artists")) {
                artists.add(str(a.getAsJsonObject(), "name"));
            }
        }
        String album = "";
        String albumId = "";
        if (o.has("albums") && !o.getAsJsonArray("albums").isEmpty()) {
            JsonObject first = o.getAsJsonArray("albums").get(0).getAsJsonObject();
            album = str(first, "title");
            albumId = str(first, "id");
        }
        String cover = str(o, "coverUri");
        String coverUrl = cover.isEmpty() ? null : "https://" + cover.replace("%%", "200x200");
        long duration = o.has("durationMs") ? o.get("durationMs").getAsLong() : 0;
        String remote = albumId.isEmpty() ? id : id + ":" + albumId;
        return new Track("yandex:" + id, Track.Source.YANDEX, title, String.join(", ", artists), album, duration, null, coverUrl, remote);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
