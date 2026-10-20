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

    private static final long[] RETRY_MS = {3_000, 8_000, 20_000};
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
                if (done.lyrics() == null && done.networkError() && attempt < RETRY_MS.length) {
                    // Сеть подвела — пробуем ещё раз сами, игроку не нужно перезапускать песню.
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
                    fromCacheOrLrclib(track);
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
        return online ? fromCacheOrLrclib(track) : new Result(null, false);
    }

    private static Result fromCacheOrLrclib(Track track) throws Exception {
        if (track.title().isBlank()) {
            return new Result(null, false);
        }
        Path dir = ClientFiles.root().resolve("cache").resolve("lyrics");
        Files.createDirectories(dir);
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest((track.artist() + "\n" + track.title()).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        // «v3» — после смены правил поиска (очистка названия, порядок запросов) старые «не нашли» ищутся заново.
        Path cached = dir.resolve(id + ".v3.lrc");
        String text;
        if (Files.exists(cached)) {
            text = Files.readString(cached, StandardCharsets.UTF_8);
        } else {
            text = lrclib(track);
            if (text == null) {
                return new Result(null, true); // ошибка сети — не кэшируем, повторим
            }
            Files.writeString(cached, text, StandardCharsets.UTF_8);
        }
        return new Result(text.isBlank() ? null : Lyrics.parse(text, track.durationMs()), false);
    }

    // --- LRCLIB ---

    /**
     * Текст с LRCLIB: с временем строк, если есть, иначе обычный; «» — не нашли; {@code null} — сеть не ответила ни на один
     * запрос. Порядок: точный из базы (быстро) → поиск по полям → поиск по строке → точный с внешними источниками (медленно).
     */
    private static @Nullable String lrclib(Track track) {
        Map<String, String> headers = Map.of("User-Agent", "BlockPhone/" + BlockPhoneFabric.VERSION + " (Minecraft mod)");
        String artist = mainArtist(track.artist());
        String title = cleanTitle(track.title());
        double duration = track.durationMs() / 1000.0;
        String fields = "artist_name=" + Http.encode(artist) + "&track_name=" + Http.encode(title);
        String durationParam = duration > 0 ? "&duration=" + Math.round(duration) : "";
        boolean anyAnswer = false;
        String plain = "";

        // 1. Точное совпадение только из базы LRCLIB — быстро.
        Http.Response exact = call("https://lrclib.net/api/get-cached?" + fields + durationParam, headers, 8_000);
        if (exact != null) {
            anyAnswer = true;
            if (exact.ok()) {
                JsonObject o = JsonParser.parseString(exact.body()).getAsJsonObject();
                if (has(o, "syncedLyrics")) {
                    return o.get("syncedLyrics").getAsString();
                }
                plain = has(o, "plainLyrics") ? o.get("plainLyrics").getAsString() : "";
            }
        }
        // 2–3. Поиск: по полям, затем одной строкой «исполнитель название» (если поля записаны иначе).
        for (String query : new String[]{fields, "q=" + Http.encode(artist + " " + title)}) {
            Http.Response search = call("https://lrclib.net/api/search?" + query, headers, 10_000);
            if (search == null) {
                continue;
            }
            anyAnswer = true;
            if (!search.ok()) {
                continue;
            }
            JsonArray results = JsonParser.parseString(search.body()).getAsJsonArray();
            String synced = bestSynced(results, duration);
            if (synced != null) {
                return synced;
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
                    return o.get("syncedLyrics").getAsString();
                }
                if (plain.isEmpty() && has(o, "plainLyrics")) {
                    plain = o.get("plainLyrics").getAsString();
                }
            }
        }
        return anyAnswer ? plain : null;
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
