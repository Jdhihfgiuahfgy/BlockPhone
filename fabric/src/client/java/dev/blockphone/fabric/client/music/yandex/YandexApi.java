package dev.blockphone.fabric.client.music.yandex;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.music.audio.AudioSniffer;
import java.nio.file.Files;

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
        // Представляемся как известные клиенты API: на незнакомый User-Agent хранилище может ответить заглушкой.
        return Map.of("Authorization", "OAuth " + token, "X-Yandex-Music-Client", "YandexMusicAndroid/24023621",
                "Accept-Language", "ru", "User-Agent", "Yandex-Music-API");
    }

    /** Ключ подписи запроса текста песни (тот же, что в открытой библиотеке yandex-music-api). */
    private static final String LYRICS_SIGN_KEY = "p93jhgh689SBReK6ghtw62";

    /**
     * Текст песни из Яндекса в формате LRC (с временем строк); «» — у трека текста нет (или он недоступен аккаунту).
     * Ошибка сети — исключение.
     */
    public String lyrics(String trackId) throws IOException {
        long ts = System.currentTimeMillis() / 1000;
        String path = "/tracks/" + trackId + "/lyrics?format=LRC&timeStamp=" + ts + "&sign=" + Http.encode(lyricsSign(trackId + ts));
        Http.Response response = Http.request("GET", API + path, headers(), null);
        if (response.code() >= 500) {
            throw new IOException("HTTP " + response.code());
        }
        if (!response.ok()) {
            return ""; // 400/403/404 — текста нет или нужна подписка
        }
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
        if (result == null || !result.has("downloadUrl") || result.get("downloadUrl").isJsonNull()) {
            return "";
        }
        Http.Response file = Http.get(result.get("downloadUrl").getAsString(), Map.of(), 5_000, 10_000);
        if (!file.ok()) {
            throw new IOException("HTTP " + file.code());
        }
        return file.body();
    }

    static String lyricsSign(String message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(LYRICS_SIGN_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
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

    /** Плейлист пользователя: kind — номер плейлиста у владельца. */
    public record Playlist(String owner, String kind, String title, int trackCount, @Nullable String coverUrl) {
    }

    /** Свои плейлисты (созданные пользователем). */
    public List<Playlist> playlists(String uid) throws IOException {
        List<Playlist> result = new ArrayList<>();
        for (JsonElement e : get("/users/" + uid + "/playlists/list").getAsJsonArray("result")) {
            Playlist p = parsePlaylist(e.getAsJsonObject(), uid);
            if (p != null) {
                result.add(p);
            }
        }
        return result;
    }

    static @Nullable Playlist parsePlaylist(JsonObject o, String fallbackOwner) {
        String kind = str(o, "kind");
        if (kind.isEmpty()) {
            return null;
        }
        String owner = o.has("owner") && o.get("owner").isJsonObject() ? str(o.getAsJsonObject("owner"), "uid") : "";
        String cover = null;
        if (o.has("cover") && o.get("cover").isJsonObject()) {
            JsonObject c = o.getAsJsonObject("cover");
            String uri = str(c, "uri");
            if (uri.isEmpty() && c.has("itemsUri") && c.get("itemsUri").isJsonArray() && !c.getAsJsonArray("itemsUri").isEmpty()) {
                uri = c.getAsJsonArray("itemsUri").get(0).getAsString();
            }
            cover = uri.isEmpty() ? null : "https://" + uri.replace("%%", "200x200");
        }
        return new Playlist(owner.isEmpty() ? fallbackOwner : owner, kind, str(o, "title"),
                o.has("trackCount") ? o.get("trackCount").getAsInt() : 0, cover);
    }

    /** Треки плейлиста (по порядку; недоступные пропускаются). */
    public List<Track> playlistTracks(Playlist playlist) throws IOException {
        JsonObject result = get("/users/" + playlist.owner() + "/playlists/" + playlist.kind()).getAsJsonObject("result");
        List<Track> tracks = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (result.has("tracks") && result.get("tracks").isJsonArray()) {
            for (JsonElement e : result.getAsJsonArray("tracks")) {
                JsonObject item = e.getAsJsonObject();
                if (item.has("track") && item.get("track").isJsonObject()) {
                    Track t = parseTrack(item.getAsJsonObject("track"));
                    if (t != null) {
                        tracks.add(t);
                    }
                } else {
                    String id = str(item, "id");
                    String album = str(item, "albumId");
                    if (!id.isEmpty()) {
                        missing.add(album.isEmpty() ? id : id + ":" + album);
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            tracks.addAll(tracks(missing));
        }
        return tracks;
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

    /**
     * Отзыв для «Моей волны» (как у официальных клиентов): без них станция выдаёт одну и ту же пачку треков.
     * type: radioStarted | trackStarted | trackFinished | skip.
     */
    public void waveFeedback(String type, @Nullable String trackId, double playedSeconds, String batchId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("timestamp", java.time.Instant.now().toString());
        if (type.equals("radioStarted")) {
            body.addProperty("from", "mobile-radio-user-onyourwave");
        }
        if (trackId != null) {
            body.addProperty("trackId", trackId);
        }
        if (type.equals("trackFinished") || type.equals("skip")) {
            body.addProperty("totalPlayedSeconds", playedSeconds);
        }
        String url = API + "/rotor/station/" + WAVE + "/feedback" + (batchId.isEmpty() ? "" : "?batch-id=" + Http.encode(batchId));
        Http.Response r = Http.request("POST", url, headers(), body.toString(), "application/json");
        if (!r.ok()) {
            BlockPhoneFabric.LOGGER.info("Yandex wave feedback {} -> HTTP {}", type, r.code());
        }
    }

    /** Порция «Моей волны». afterTrackId — последний полученный трек (для продолжения), null — начало. */
    /** Пачка «Моей волны»: треки и batchId (нужен для отзывов). */
    public record WaveBatch(List<Track> tracks, String batchId) {
    }

    public static final String WAVE = "user:onyourwave";

    public List<Track> wave(@Nullable String afterTrackId) throws IOException {
        return waveBatch(afterTrackId).tracks();
    }

    /** Следующая пачка волны после трека afterTrackId («id:альбом»; null — начало). */
    public WaveBatch waveBatch(@Nullable String afterTrackId) throws IOException {
        String query = "/rotor/station/" + WAVE + "/tracks?settings2=true"
                + (afterTrackId != null ? "&queue=" + Http.encode(afterTrackId) : "");
        JsonObject result = get(query).getAsJsonObject("result");
        String batchId = result.has("batchId") && !result.get("batchId").isJsonNull() ? result.get("batchId").getAsString() : "";
        JsonArray sequence = result.getAsJsonArray("sequence");
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
        return new WaveBatch(list, batchId);
    }

    public void like(String uid, String trackId, boolean like) throws IOException {
        post("/users/" + uid + "/likes/tracks/" + (like ? "add-multiple" : "remove"), Map.of("track-ids", trackId));
    }

    /**
     * Скачать mp3 трека в файл: варианты mp3 от лучшего качества к худшему. Скачанное проверяется — если это не MP3
     * (страница-заглушка, другой формат), в журнал пишется, что пришло, файл удаляется и пробуется следующий вариант.
     */
    public void download(String trackId, Path target) throws IOException {
        JsonArray infos = get("/tracks/" + Http.encode(trackId) + "/download-info").getAsJsonArray("result");
        List<JsonObject> mp3 = new ArrayList<>();
        StringBuilder codecs = new StringBuilder();
        for (JsonElement e : infos) {
            JsonObject o = e.getAsJsonObject();
            codecs.append(str(o, "codec")).append('/').append(o.has("bitrateInKbps") ? o.get("bitrateInKbps").getAsInt() : 0).append(' ');
            if ("mp3".equals(str(o, "codec"))) {
                mp3.add(o);
            }
        }
        if (mp3.isEmpty()) {
            BlockPhoneFabric.LOGGER.warn("Yandex track {}: no mp3 variants ({})", trackId, codecs.toString().strip());
            throw new ApiException("music.error.yandex_unavailable");
        }
        mp3.sort((a, b) -> Integer.compare(b.get("bitrateInKbps").getAsInt(), a.get("bitrateInKbps").getAsInt()));
        String lastProblem = "";
        for (JsonObject variant : mp3) {
            Http.Response xml = Http.request("GET", str(variant, "downloadInfoUrl"), headers(), null);
            String url = xml.ok() ? signedUrl(xml.body()) : null;
            if (url == null) {
                lastProblem = "download-info " + xml.code() + ": " + snippet(xml.body());
                continue;
            }
            Http.Download result = Http.download(url, headers(), target, 64L * 1024 * 1024);
            if (!result.ok()) {
                // Хранилище иногда не любит заголовки API — пробуем ещё раз «как браузер», без токена.
                Http.Download plain = Http.download(url, Map.of("User-Agent", "Mozilla/5.0"), target, 64L * 1024 * 1024);
                if (!plain.ok()) {
                    lastProblem = variant.get("bitrateInKbps").getAsInt() + " kbps: storage " + result.describe() + "; without token "
                            + plain.describe();
                    continue;
                }
            }
            String problem = AudioSniffer.problem(target);
            if (problem == null) {
                return;
            }
            lastProblem = variant.get("bitrateInKbps").getAsInt() + " kbps: " + problem;
            Files.deleteIfExists(target);
        }
        BlockPhoneFabric.LOGGER.warn("Yandex track {} not downloaded ({}): {}", trackId, codecs.toString().strip(), lastProblem);
        throw new ApiException("music.error.yandex_format");
    }

    private static String snippet(String body) {
        String s = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
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
