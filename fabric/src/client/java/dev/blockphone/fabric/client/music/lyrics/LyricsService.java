package dev.blockphone.fabric.client.music.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.Http;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.audio.TrackTags;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Текст текущего трека. Откуда: файл {@code <имя трека>.lrc} рядом с музыкой → тег (USLT / LYRICS) → LRCLIB (lrclib.net,
 * бесплатная база текстов с временем строк; можно выключить в настройках). Найденное в сети кэшируется в
 * {@code blockphone/cache/lyrics/} (пустой файл — «текста нет»).
 * <p>
 * Поиск — в своих потоках (не ждёт загрузку картинок), сначала быстрые запросы LRCLIB (только его база), медленный
 * (LRCLIB ищет во внешних источниках) — последним. При сетевой ошибке — повторы через 3, 8 и 20 с, пока играет тот же трек.
 * Текст следующего трека очереди ищется заранее.
 */
public final class LyricsService {
    public enum Status { NONE, LOADING, FOUND, NOT_FOUND }

    private static final long[] RETRY_MS = {3_000, 8_000, 20_000, 45_000};
    /** Сохранённые «текст без времени» и «не нашли» через столько времени ищутся заново (вдруг появилось). */
    private static final long RECHECK_MS = 12 * 3600_000L;
    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "BlockPhone Lyrics");
        t.setDaemon(true);
        return t;
    });
    /** Треки, текст которых уже ищется (чтобы не искать дважды текущий и заранее). */
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

    private static @Nullable String trackKey;
    private static @Nullable Lyrics lyrics;
    private static Status status = Status.NONE;
    /** Растёт при каждой смене трека — устаревшие ответы фонового потока отбрасываются. */
    private static int generation;

    /** Ответ поиска: текст (может быть null — не нашли) или сетевая ошибка (стоит повторить). */
    private record Result(@Nullable Lyrics lyrics, boolean networkError) {
    }

    /** Каждый такт (поток рендера): трек сменился — искать текст заново. */
    public static void tick() {
        Track track = MusicPlayer.current();
        String key = track == null ? null : track.key();
        if (java.util.Objects.equals(key, trackKey)) {
            return;
        }
        trackKey = key;
        lyrics = null;
        int gen = ++generation;
        if (track == null) {
            status = Status.NONE;
            return;
        }
        status = Status.LOADING;
        search(track, gen, 0);
    }

    private static void search(Track track, int gen, int attempt) {
        boolean online = PhoneSettings.get().lyricsOnline;
        EXECUTOR.execute(() -> {
            Result result;
            try {
                result = load(track, online);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Lyrics for {} failed: {}", track.label(), e.toString());
                result = new Result(null, true);
            }
            Result done = result;
            Minecraft.getInstance().execute(() -> {
                if (gen != generation) {
                    return;
                }
                boolean synced = done.lyrics() != null && done.lyrics().synced();
                if (!synced && done.networkError() && attempt < RETRY_MS.length) {
                    // Сеть подвела (хотя бы один запрос) — пробуем ещё раз сами, игроку не нужно перезапускать песню.
                    // Пока ищем текст с временем строк, показываем то, что есть (обычный текст).
                    if (done.lyrics() != null) {
                        lyrics = done.lyrics();
                        status = Status.FOUND;
                    }
                    EXECUTOR.schedule(() -> Minecraft.getInstance().execute(() -> {
                        if (gen == generation) {
                            search(track, gen, attempt + 1);
                        }
                    }), RETRY_MS[attempt], TimeUnit.MILLISECONDS);
                    return;
                }
                lyrics = done.lyrics();
                status = done.lyrics() != null ? Status.FOUND : Status.NOT_FOUND;
                prefetchNext();
            });
        });
    }

    /** Заранее найти текст следующего трека очереди (в кэш), чтобы он появился сразу. */
    private static void prefetchNext() {
        List<Track> queue = MusicPlayer.queue();
        int next = MusicPlayer.index() + 1;
        if (!PhoneSettings.get().lyricsOnline || next <= 0 || next >= queue.size()) {
            return;
        }
        Track track = queue.get(next);
        if (track.title().isBlank() || IN_FLIGHT.putIfAbsent(track.key(), true) != null) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                if (track.source() != Track.Source.LOCAL || track.file() == null) {
                    load(track, true);
                }
            } catch (Exception ignored) {
                // заранее — не страшно
            } finally {
                IN_FLIGHT.remove(track.key());
            }
        });
    }

    public static @Nullable Lyrics current() {
        return lyrics;
    }

    public static Status status() {
        return status;
    }

    /** Позиция в треке с учётом сдвига из настроек (положительный — текст раньше). */
    public static long positionMs() {
        return MusicPlayer.positionMs() + PhoneSettings.get().lyricsOffsetMs;
    }

    /** Перемотать музыку к строке (нажатие на строку в телефоне). */
    public static void seekTo(Lyrics.Line line) {
        MusicPlayer.seek(Math.max(0, line.startMs() - PhoneSettings.get().lyricsOffsetMs));
    }

    /** Забыть текст (например, после смены настройки «искать в сети») — найдётся заново. */
    public static void reload() {
        trackKey = null;
    }

    private static Result load(Track track, boolean online) throws Exception {
        long duration = track.durationMs();
        if (track.file() != null && track.source() == Track.Source.LOCAL) {
            Path file = track.file();
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            Path lrc = file.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".lrc");
            if (Files.isRegularFile(lrc)) {
                Lyrics parsed = Lyrics.parse(Files.readString(lrc, StandardCharsets.UTF_8), duration);
                if (parsed != null) {
                    return new Result(parsed, false);
                }
            }
            String tag = TrackTags.read(file, false).lyrics();
            Lyrics parsed = tag == null ? null : Lyrics.parse(tag, duration);
            if (parsed != null && parsed.synced()) {
                return new Result(parsed, false);
            }
            if (!online) {
                return new Result(parsed, false);
            }
            Result remote = fromCacheOrLrclib(track);
            return remote.lyrics() != null ? remote : new Result(parsed, remote.networkError() && parsed == null);
        }
        if (!online) {
            return new Result(null, false);
        }
        Result yandex = track.source() == Track.Source.YANDEX ? fromYandex(track) : new Result(null, false);
        if (yandex.lyrics() != null && yandex.lyrics().synced()) {
            return yandex;
        }
        Result remote = fromCacheOrLrclib(track);
        if (remote.lyrics() != null && (remote.lyrics().synced() || yandex.lyrics() == null)) {
            return remote;
        }
        return yandex.lyrics() != null ? new Result(yandex.lyrics(), remote.networkError()) : remote;
    }

    /** Текст самого Яндекса (обычно с временем строк) — для треков из Яндекс Музыки. Кэш — как у LRCLIB. */
    private static Result fromYandex(Track track) throws Exception {
        String token = dev.blockphone.fabric.client.music.Accounts.get().yandexToken;
        if (token.isEmpty() || track.remoteId() == null) {
            return new Result(null, false);
        }
        String id = track.remoteId().split(":")[0];
        Path cached = cacheDir().resolve("yandex_" + id.replaceAll("[^0-9A-Za-z]", "_") + ".v1.lrc");
        String text = readFresh(cached);
        if (text == null) {
            try {
                text = new dev.blockphone.fabric.client.music.yandex.YandexApi(token).lyrics(id);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Yandex lyrics for {} failed: {}", track.label(), e.toString());
                String old = Files.exists(cached) ? Files.readString(cached, StandardCharsets.UTF_8) : null;
                return new Result(old == null || old.isBlank() ? null : Lyrics.parse(old, track.durationMs()), true);
            }
            Files.writeString(cached, text, StandardCharsets.UTF_8);
        }
        return new Result(text.isBlank() ? null : Lyrics.parse(text, track.durationMs()), false);
    }

    private static Path cacheDir() throws java.io.IOException {
        Path dir = ClientFiles.root().resolve("cache").resolve("lyrics");
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Текст из кэша или null — искать заново: файла нет, либо в нём «не нашли»/текст без времени строк и он старше
     * {@link #RECHECK_MS}.
     */
    private static @Nullable String readFresh(Path cached) throws java.io.IOException {
        if (!Files.exists(cached)) {
            return null;
        }
        String text = Files.readString(cached, StandardCharsets.UTF_8);
        Lyrics parsed = text.isBlank() ? null : Lyrics.parse(text, 0);
        boolean synced = parsed != null && parsed.synced();
        long age = System.currentTimeMillis() - Files.getLastModifiedTime(cached).toMillis();
        return synced || age < RECHECK_MS ? text : null;
    }

    private static Result fromCacheOrLrclib(Track track) throws Exception {
        if (track.title().isBlank()) {
            return new Result(null, false);
        }
        Path dir = cacheDir();
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest((track.artist() + "\n" + track.title()).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        // «v3» — после смены правил поиска (очистка названия, порядок запросов) старые «не нашли» ищутся заново.
        Path cached = dir.resolve(id + ".v3.lrc");
        String text = readFresh(cached);
        if (text == null) {
            Found found = lrclib(track);
            String old = Files.exists(cached) ? Files.readString(cached, StandardCharsets.UTF_8) : "";
            if (found == null) {
                // Сеть не ответила — не кэшируем, повторим; пока — что было сохранено раньше.
                return new Result(old.isBlank() ? null : Lyrics.parse(old, track.durationMs()), true);
            }
            Lyrics parsed = found.text().isBlank() ? null : Lyrics.parse(found.text(), track.durationMs());
            boolean synced = parsed != null && parsed.synced();
            if (synced || found.complete()) {
                Files.writeString(cached, found.text(), StandardCharsets.UTF_8);
            }
            if (!synced && !old.isBlank() && found.text().isBlank()) {
                return new Result(Lyrics.parse(old, track.durationMs()), !found.complete());
            }
            // Часть запросов не ответила, а текста с временем строк нет — не запоминаем, повторим.
            return new Result(parsed, !synced && !found.complete());
        }
        return new Result(text.isBlank() ? null : Lyrics.parse(text, track.durationMs()), false);
    }

    // --- LRCLIB ---

    /**
     * Текст с LRCLIB: с временем строк, если есть, иначе обычный; «» — не нашли; {@code null} — сеть не ответила ни на один
     * запрос. Порядок: точный из базы (быстро) → поиск по полям → поиск по строке → точный с внешними источниками (медленно).
     */
    /** Ответ LRCLIB: текст («» — не нашли) и ответили ли все запросы (иначе «не нашли» может быть ошибкой сети). */
    private record Found(String text, boolean complete) {
    }

    private static @Nullable Found lrclib(Track track) {
        Map<String, String> headers = Map.of("User-Agent", "BlockPhone/" + BlockPhoneFabric.VERSION + " (Minecraft mod)");
        String artist = mainArtist(track.artist());
        String title = cleanTitle(track.title());
        double duration = track.durationMs() / 1000.0;
        String fields = "artist_name=" + Http.encode(artist) + "&track_name=" + Http.encode(title);
        String durationParam = duration > 0 ? "&duration=" + Math.round(duration) : "";
        boolean anyAnswer = false;
        boolean failed = false;
        String plain = "";

        // 1. Точное совпадение только из базы LRCLIB — быстро.
        Http.Response exact = call("https://lrclib.net/api/get-cached?" + fields + durationParam, headers, 8_000);
        if (exact != null) {
            anyAnswer = true;
            if (exact.ok()) {
                JsonObject o = JsonParser.parseString(exact.body()).getAsJsonObject();
                if (has(o, "syncedLyrics")) {
                    return new Found(o.get("syncedLyrics").getAsString(), true);
                }
                plain = has(o, "plainLyrics") ? o.get("plainLyrics").getAsString() : "";
            } else if (exact.code() != 404) {
                failed = true;
            }
        } else {
            failed = true;
        }
        // 2–3. Поиск: по полям, затем одной строкой «исполнитель название» (если поля записаны иначе).
        for (String query : new String[]{fields, "q=" + Http.encode(artist + " " + title)}) {
            Http.Response search = call("https://lrclib.net/api/search?" + query, headers, 10_000);
            if (search == null || !search.ok()) {
                failed = true;
                continue;
            }
            anyAnswer = true;
            JsonArray results = JsonParser.parseString(search.body()).getAsJsonArray();
            String synced = bestSynced(results, duration);
            if (synced != null) {
                return new Found(synced, true);
            }
            if (plain.isEmpty()) {
                for (JsonElement e : results) {
                    if (has(e.getAsJsonObject(), "plainLyrics")) {
                        plain = e.getAsJsonObject().get("plainLyrics").getAsString();
                        break;
                    }
                }
            }
        }
        // 4. Точное с поиском во внешних источниках — бывает долгим.
        Http.Response slow = call("https://lrclib.net/api/get?" + fields + durationParam, headers, 25_000);
        if (slow != null) {
            anyAnswer = true;
            if (slow.ok()) {
                JsonObject o = JsonParser.parseString(slow.body()).getAsJsonObject();
                if (has(o, "syncedLyrics")) {
                    return new Found(o.get("syncedLyrics").getAsString(), true);
                }
                if (plain.isEmpty() && has(o, "plainLyrics")) {
                    plain = o.get("plainLyrics").getAsString();
                }
            } else if (slow.code() != 404) {
                failed = true;
            }
        } else {
            failed = true;
        }
        return anyAnswer ? new Found(plain, !failed) : null;
    }

    /** Текст с временем строк из результатов поиска: ближайший по длине, не дальше 4 с (иначе — другая запись песни). */
    static @Nullable String bestSynced(JsonArray results, double duration) {
        JsonObject best = null;
        double bestDiff = Double.MAX_VALUE;
        for (JsonElement e : results) {
            JsonObject o = e.getAsJsonObject();
            if (!has(o, "syncedLyrics")) {
                continue;
            }
            double d = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
            double diff = duration > 0 && d > 0 ? Math.abs(d - duration) : 1000;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = o;
            }
        }
        return best != null && (duration <= 0 || bestDiff <= 4) ? best.get("syncedLyrics").getAsString() : null;
    }

    private static @Nullable Http.Response call(String url, Map<String, String> headers, int readMs) {
        try {
            return Http.get(url, headers, 5_000, readMs);
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("LRCLIB request failed ({}): {}", url.substring(0, url.indexOf('?')), e.toString());
            return null;
        }
    }

    /** Название без пометок «(feat. …)», «(Remastered 2011)», «[Live]», « - Radio Edit» — так их пишут не везде. */
    static String cleanTitle(String title) {
        String t = title.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "");
        t = t.replaceAll("(?i)\\s+-\\s+(remaster|remastered|radio edit|live|mono|stereo|single version|bonus).*$", "");
        t = t.replaceAll("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", "");
        t = t.strip();
        return t.isEmpty() ? title.strip() : t;
    }

    /** Первый исполнитель из «A, B», «A & B», «A feat. B», «A x B». */
    static String mainArtist(String artist) {
        String[] parts = artist.split("(?i)\\s*(,|&|;|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+x\\s+)\\s*");
        return parts.length > 0 && !parts[0].isBlank() ? parts[0].strip() : artist.strip();
    }

    private static boolean has(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && !e.isJsonNull() && !e.getAsString().isBlank();
    }

    private LyricsService() {
    }
}
