package dev.blockphone.fabric.client.music.yandex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.blockphone.fabric.client.music.Http;
import dev.blockphone.fabric.client.music.Track;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Каталог Яндекс Музыки сверх треков: «Моя волна» через сессии (с настройками и «под занятие», волна по треку и
 * артисту), главная (подборки «для вас», чарт, новинки, плейлисты), альбомы, артисты, книги и подкасты
 * (/non-music/catalogue), поиск по всему. Проверено на живом API (DevShots yprobe); вызывать не из потока рендера.
 */
public final class YandexCatalog {
    private final YandexApi api;

    public YandexCatalog(YandexApi api) {
        this.api = api;
    }

    // --- Модель ---

    /** Альбом, книга или подкаст (у книг и подкастов metaType = podcast). */
    public record Album(String id, String title, String artist, @Nullable String coverUrl, int year, int trackCount,
                       String type, String metaType) {
        /** Аудиокнига или подкаст: слушают по порядку, запоминается место. */
        public boolean spoken() {
            return metaType.equals("podcast") || type.equals("audiobook") || type.equals("podcast");
        }

        public boolean book() {
            return type.equals("audiobook");
        }
    }

    public record Artist(String id, String name, @Nullable String coverUrl) {
    }

    /** Вариант настройки волны: значение, подпись, seed для сессии («settingDiversity:discover», «activity:workout»). */
    public record Option(String value, String name, String seed) {
    }

    /** Настройки «Моей волны»: занятия (контексты) и три настройки (характер, настроение, язык). */
    public record WaveSettings(List<Option> contexts, Map<String, Setting> settings) {
    }

    public record Setting(String key, String name, List<Option> values) {
    }

    /** Сессия волны: id, batchId для отзывов и треки. */
    public record Session(String id, String batchId, List<Track> tracks, boolean unknown) {
    }

    /** Полка каталога: название и что на ней (альбомы/книги/подкасты или плейлисты). */
    public record Shelf(String title, List<Album> albums, List<YandexApi.Playlist> playlists) {
    }

    public record Landing(List<YandexApi.Playlist> forYou, List<Track> chart, List<Album> newReleases,
                          List<YandexApi.Playlist> playlists) {
    }

    /**
     * Страница альбома: треки и место прослушивания с аккаунта (ключ трека → позиция, мс; только у книг и подкастов),
     * сколько осталось (с) и дослушана ли книга.
     */
    public record AlbumPage(Album album, List<Track> tracks, Map<String, Long> progress, long leftSec, boolean finished) {
    }

    /** «Продолжить слушать» с аккаунта: книга/подкаст, на какой главе и где, когда слушали. */
    public record ContinueItem(Album album, String trackKey, String trackTitle, long positionMs, long durationMs, String listened) {
    }

    public record ArtistPage(Artist artist, List<Track> popular, List<Album> albums, List<Artist> similar) {
    }

    public record SearchAll(List<Track> tracks, List<Artist> artists, List<Album> albums, List<Album> spoken,
                            List<YandexApi.Playlist> playlists) {
    }

    // --- Волна ---

    public WaveSettings waveSettings() throws IOException {
        JsonObject r = api.getJson("/rotor/wave/settings?seeds=user:onyourwave").getAsJsonObject("result");
        List<Option> contexts = new ArrayList<>();
        if (r.has("blocks")) {
            for (JsonElement b : r.getAsJsonArray("blocks")) {
                JsonObject block = b.getAsJsonObject();
                if (!"contexts".equals(str(block, "type")) || !block.has("items")) {
                    continue;
                }
                for (JsonElement i : block.getAsJsonArray("items")) {
                    JsonObject item = i.getAsJsonObject();
                    if (item.has("id") && item.get("id").isJsonObject()) {
                        JsonObject id = item.getAsJsonObject("id");
                        String seed = str(id, "type") + ":" + str(id, "tag");
                        contexts.add(new Option(str(id, "tag"), str(item, "name"), seed));
                    }
                }
            }
        }
        Map<String, Setting> settings = new LinkedHashMap<>();
        if (r.has("settingRestrictions")) {
            for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("settingRestrictions").entrySet()) {
                JsonObject s = e.getValue().getAsJsonObject();
                List<Option> values = new ArrayList<>();
                if (s.has("possibleValues")) {
                    for (JsonElement v : s.getAsJsonArray("possibleValues")) {
                        JsonObject o = v.getAsJsonObject();
                        values.add(new Option(str(o, "value"), str(o, "name"), str(o, "serializedSeed")));
                    }
                }
                settings.put(e.getKey(), new Setting(e.getKey(), str(s, "name"), values));
            }
        }
        return new WaveSettings(contexts, settings);
    }

    /** Новая сессия волны по seeds («user:onyourwave» + настройки, «activity:workout», «track:id», «artist:id»). */
    public Session sessionNew(List<String> seeds) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray array = new JsonArray();
        seeds.forEach(array::add);
        body.add("seeds", array);
        body.addProperty("includeTracksInResponse", true);
        return parseSession(api.postJson("/rotor/session/new", body.toString()).getAsJsonObject("result"), null);
    }

    /** Следующая пачка сессии; queue — id уже полученных треков (последние в конце). unknown — сессия устарела. */
    public Session sessionTracks(String sessionId, List<String> queue) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray array = new JsonArray();
        queue.forEach(array::add);
        body.add("queue", array);
        return parseSession(api.postJson("/rotor/session/" + Http.encode(sessionId) + "/tracks", body.toString())
                .getAsJsonObject("result"), sessionId);
    }

    /** Отзыв сессии: radioStarted | trackStarted | trackFinished | skip. */
    public void sessionFeedback(String sessionId, String batchId, String type, @Nullable String trackId, double seconds)
            throws IOException {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("timestamp", java.time.Instant.now().toString());
        if (trackId != null) {
            event.addProperty("trackId", trackId);
        }
        if (type.equals("trackFinished") || type.equals("skip")) {
            event.addProperty("totalPlayedSeconds", seconds);
        }
        JsonObject body = new JsonObject();
        body.add("event", event);
        if (!batchId.isEmpty()) {
            body.addProperty("batchId", batchId);
        }
        if (type.equals("radioStarted")) {
            body.addProperty("from", "mobile-radio-user-onyourwave");
        }
        api.postJson("/rotor/session/" + Http.encode(sessionId) + "/feedback", body.toString());
    }

    private static Session parseSession(JsonObject r, @Nullable String knownId) {
        boolean unknown = r.has("unknownSession") && r.get("unknownSession").getAsBoolean();
        String id = r.has("radioSessionId") ? str(r, "radioSessionId") : knownId == null ? "" : knownId;
        List<Track> tracks = new ArrayList<>();
        if (r.has("sequence") && r.get("sequence").isJsonArray()) {
            for (JsonElement e : r.getAsJsonArray("sequence")) {
                JsonObject item = e.getAsJsonObject();
                if (item.has("track")) {
                    Track t = YandexApi.parseTrack(item.getAsJsonObject("track"));
                    if (t != null) {
                        tracks.add(t);
                    }
                }
            }
        }
        return new Session(id, str(r, "batchId"), tracks, unknown);
    }

    // --- Главная, книги и подкасты ---

    public Landing landing() throws IOException {
        JsonObject r = api.getJson("/landing3?blocks=personalplaylists,chart,new-releases,new-playlists").getAsJsonObject("result");
        List<YandexApi.Playlist> forYou = new ArrayList<>();
        List<Track> chart = new ArrayList<>();
        List<Album> releases = new ArrayList<>();
        List<YandexApi.Playlist> playlists = new ArrayList<>();
        for (JsonElement b : arr(r, "blocks")) {
            JsonObject block = b.getAsJsonObject();
            String type = str(block, "type");
            for (JsonElement en : arr(block, "entities")) {
                JsonObject entity = en.getAsJsonObject();
                JsonObject data = entity.has("data") && entity.get("data").isJsonObject() ? entity.getAsJsonObject("data") : null;
                if (data == null) {
                    continue;
                }
                switch (type) {
                    case "personal-playlists" -> {
                        if (data.has("data") && data.get("data").isJsonObject()) {
                            YandexApi.Playlist p = YandexApi.parsePlaylist(data.getAsJsonObject("data"), "");
                            if (p != null) {
                                forYou.add(p);
                            }
                        }
                    }
                    case "chart" -> {
                        if (data.has("track") && data.get("track").isJsonObject()) {
                            Track t = YandexApi.parseTrack(data.getAsJsonObject("track"));
                            if (t != null) {
                                chart.add(t);
                            }
                        }
                    }
                    case "new-releases" -> {
                        Album a = parseAlbum(data);
                        if (a != null) {
                            releases.add(a);
                        }
                    }
                    case "new-playlists" -> {
                        YandexApi.Playlist p = YandexApi.parsePlaylist(data, "");
                        if (p != null) {
                            playlists.add(p);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return new Landing(forYou, chart, releases, playlists);
    }

    /** Чарт целиком (Россия): треки по местам. */
    public List<Track> chart() throws IOException {
        JsonObject r = api.getJson("/landing3/chart").getAsJsonObject("result");
        List<Track> list = new ArrayList<>();
        if (r.has("chart") && r.get("chart").isJsonObject()) {
            for (JsonElement e : arr(r.getAsJsonObject("chart"), "tracks")) {
                JsonObject item = e.getAsJsonObject();
                if (item.has("track") && item.get("track").isJsonObject()) {
                    Track t = YandexApi.parseTrack(item.getAsJsonObject("track"));
                    if (t != null) {
                        list.add(t);
                    }
                }
            }
        }
        return list;
    }

    /** Место в треке с аккаунта (streamProgress.endPositionSec), мс; 0 — не слушали. */
    static long streamPosition(JsonObject track) {
        if (track.has("streamProgress") && track.get("streamProgress").isJsonObject()) {
            JsonObject p = track.getAsJsonObject("streamProgress");
            if (p.has("endPositionSec") && !p.get("endPositionSec").isJsonNull()) {
                return Math.round(p.get("endPositionSec").getAsDouble() * 1000);
            }
        }
        return 0;
    }

    /** Каталог «Подкасты и книги» и «Продолжить слушать» с аккаунта. */
    public record Spoken(List<Shelf> shelves, List<ContinueItem> continueItems) {
    }

    /** Каталог «Подкасты и книги»: полки с книгами, подкастами и плейлистами выпусков. */
    public Spoken spokenCatalogue() throws IOException {
        JsonObject r = api.getJson("/non-music/catalogue").getAsJsonObject("result");
        List<Shelf> shelves = new ArrayList<>();
        List<ContinueItem> continueItems = new ArrayList<>();
        for (JsonElement b : arr(r, "blocks")) {
            JsonObject block = b.getAsJsonObject();
            String title = str(block, "title").strip();
            if (title.isEmpty()) {
                continue;
            }
            List<Album> albums = new ArrayList<>();
            List<YandexApi.Playlist> playlists = new ArrayList<>();
            for (JsonElement en : arr(block, "entities")) {
                JsonObject entity = en.getAsJsonObject();
                if (!entity.has("data") || !entity.get("data").isJsonObject()) {
                    continue;
                }
                JsonObject data = entity.getAsJsonObject("data");
                String type = str(entity, "type");
                if (type.equals("last-recently-played") && data.has("playedItem")) {
                    JsonObject played = data.getAsJsonObject("playedItem");
                    Album a = played.has("album") ? parseAlbum(played.getAsJsonObject("album")) : null;
                    if (a != null && played.has("track") && played.get("track").isJsonObject()) {
                        JsonObject t = played.getAsJsonObject("track");
                        Track track = YandexApi.parseTrack(t);
                        if (track != null) {
                            continueItems.add(new ContinueItem(a, track.key(), track.title(), streamPosition(t), track.durationMs(),
                                    str(data, "listeningDate")));
                        }
                    }
                    continue;
                }
                JsonObject albumJson = switch (type) {
                    case "album" -> data;
                    case "album-chart-item" -> data.has("album") ? data.getAsJsonObject("album") : null;
                    case "last-recently-played" -> data.has("playedItem") && data.getAsJsonObject("playedItem").has("album")
                            ? data.getAsJsonObject("playedItem").getAsJsonObject("album") : null;
                    default -> null;
                };
                if (albumJson != null) {
                    Album a = parseAlbum(albumJson);
                    if (a != null) {
                        albums.add(a);
                    }
                } else if (type.equals("playlist")) {
                    YandexApi.Playlist p = YandexApi.parsePlaylist(data, "");
                    if (p != null) {
                        playlists.add(p);
                    }
                }
            }
            if (!albums.isEmpty() || !playlists.isEmpty()) {
                shelves.add(new Shelf(title, albums, playlists));
            }
        }
        return new Spoken(shelves, continueItems);
    }

    // --- Альбомы и артисты ---

    public AlbumPage album(String id) throws IOException {
        JsonObject r = api.getJson("/albums/" + Http.encode(id) + "/with-tracks").getAsJsonObject("result");
        Album album = parseAlbum(r);
        if (album == null) {
            throw new YandexApi.ApiException("music.error.yandex_unavailable");
        }
        List<Track> tracks = new ArrayList<>();
        Map<String, Long> progress = new LinkedHashMap<>();
        for (JsonElement v : arr(r, "volumes")) {
            for (JsonElement t : v.getAsJsonArray()) {
                JsonObject o = t.getAsJsonObject();
                Track track = YandexApi.parseTrack(o);
                if (track != null) {
                    tracks.add(track);
                    long pos = streamPosition(o);
                    if (pos > 0) {
                        progress.put(track.key(), pos);
                    }
                }
            }
        }
        long left = r.has("durationLeft") ? r.get("durationLeft").getAsLong() : -1;
        boolean finished = r.has("listeningFinished") && r.get("listeningFinished").getAsBoolean();
        return new AlbumPage(album, tracks, progress, left, finished);
    }

    /** Понравившиеся альбомы, книги и подкасты. */
    public List<Album> likedAlbums(String uid) throws IOException {
        List<Album> list = new ArrayList<>();
        for (JsonElement e : api.getJson("/users/" + uid + "/likes/albums?rich=true").getAsJsonArray("result")) {
            JsonObject o = e.getAsJsonObject();
            Album a = o.has("album") ? parseAlbum(o.getAsJsonObject("album")) : null;
            if (a != null) {
                list.add(a);
            }
        }
        return list;
    }

    public void likeAlbum(String uid, String albumId, boolean like) throws IOException {
        api.postForm("/users/" + uid + "/likes/albums/" + (like ? "add-multiple" : "remove"), Map.of("album-ids", albumId));
    }

    public ArtistPage artist(String id) throws IOException {
        JsonObject r = api.getJson("/artists/" + Http.encode(id) + "/brief-info").getAsJsonObject("result");
        Artist artist = parseArtist(r.getAsJsonObject("artist"));
        if (artist == null) {
            throw new YandexApi.ApiException("music.error.yandex_unavailable");
        }
        List<Track> popular = new ArrayList<>();
        for (JsonElement e : arr(r, "popularTracks")) {
            Track t = YandexApi.parseTrack(e.getAsJsonObject());
            if (t != null) {
                popular.add(t);
            }
        }
        List<Album> albums = new ArrayList<>();
        for (JsonElement e : arr(r, "albums")) {
            Album a = parseAlbum(e.getAsJsonObject());
            if (a != null) {
                albums.add(a);
            }
        }
        List<Artist> similar = new ArrayList<>();
        for (JsonElement e : arr(r, "similarArtists")) {
            Artist a = parseArtist(e.getAsJsonObject());
            if (a != null) {
                similar.add(a);
            }
        }
        return new ArtistPage(artist, popular, albums, similar);
    }

    // --- Поиск ---

    public SearchAll search(String text) throws IOException {
        JsonObject r = api.getJson("/search?type=all&page=0&nocorrect=false&text=" + Http.encode(text)).getAsJsonObject("result");
        List<Track> tracks = new ArrayList<>();
        for (JsonElement e : results(r, "tracks")) {
            Track t = YandexApi.parseTrack(e.getAsJsonObject());
            if (t != null) {
                tracks.add(t);
            }
        }
        List<Artist> artists = new ArrayList<>();
        for (JsonElement e : results(r, "artists")) {
            Artist a = parseArtist(e.getAsJsonObject());
            if (a != null) {
                artists.add(a);
            }
        }
        List<Album> albums = new ArrayList<>();
        for (JsonElement e : results(r, "albums")) {
            Album a = parseAlbum(e.getAsJsonObject());
            if (a != null) {
                albums.add(a);
            }
        }
        List<Album> spoken = new ArrayList<>();
        for (JsonElement e : results(r, "podcasts")) {
            Album a = parseAlbum(e.getAsJsonObject());
            if (a != null) {
                spoken.add(a);
            }
        }
        List<YandexApi.Playlist> playlists = new ArrayList<>();
        for (JsonElement e : results(r, "playlists")) {
            YandexApi.Playlist p = YandexApi.parsePlaylist(e.getAsJsonObject(), "");
            if (p != null) {
                playlists.add(p);
            }
        }
        return new SearchAll(tracks, artists, albums, spoken, playlists);
    }

    // --- Разбор ---

    static @Nullable Album parseAlbum(JsonObject o) {
        String id = str(o, "id");
        if (id.isEmpty() || (o.has("available") && !o.get("available").getAsBoolean())) {
            return null;
        }
        List<String> artists = new ArrayList<>();
        for (JsonElement a : arr(o, "artists")) {
            artists.add(str(a.getAsJsonObject(), "name"));
        }
        String cover = str(o, "coverUri");
        return new Album(id, str(o, "title"), String.join(", ", artists),
                cover.isEmpty() ? null : "https://" + cover.replace("%%", "200x200"),
                o.has("year") ? o.get("year").getAsInt() : 0, o.has("trackCount") ? o.get("trackCount").getAsInt() : 0,
                str(o, "type"), str(o, "metaType"));
    }

    static @Nullable Artist parseArtist(@Nullable JsonObject o) {
        if (o == null) {
            return null;
        }
        String id = str(o, "id");
        if (id.isEmpty()) {
            return null;
        }
        String cover = null;
        if (o.has("cover") && o.get("cover").isJsonObject()) {
            String uri = str(o.getAsJsonObject("cover"), "uri");
            cover = uri.isEmpty() ? null : "https://" + uri.replace("%%", "200x200");
        }
        return new Artist(id, str(o, "name"), cover);
    }

    private static JsonArray results(JsonObject r, String key) {
        if (r.has(key) && r.get(key).isJsonObject()) {
            return arr(r.getAsJsonObject(key), "results");
        }
        return new JsonArray();
    }

    static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}
