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
import java.util.Map;

/**
 * Текст текущего трека. Откуда: файл {@code <имя трека>.lrc} рядом с музыкой → тег (USLT / LYRICS) → LRCLIB (lrclib.net,
 * бесплатная база текстов с временем строк; можно выключить в настройках). Найденное в сети кэшируется в
 * {@code blockphone/cache/lyrics/} (пустой файл — «текста нет»). Поиск — в фоновом потоке {@link ClientFiles#IO}.
 */
public final class LyricsService {
    public enum Status { NONE, LOADING, FOUND, NOT_FOUND }

    private static @Nullable String trackKey;
    private static @Nullable Lyrics lyrics;
    private static Status status = Status.NONE;
    /** Растёт при каждой смене трека — устаревшие ответы фонового потока отбрасываются. */
    private static int generation;

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
        boolean online = PhoneSettings.get().lyricsOnline;
        ClientFiles.IO.execute(() -> {
            Lyrics found = null;
            try {
                found = load(track, online);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Lyrics for {} failed: {}", track.label(), e.toString());
            }
            Lyrics result = found;
            Minecraft.getInstance().execute(() -> {
                if (gen == generation) {
                    lyrics = result;
                    status = result != null ? Status.FOUND : Status.NOT_FOUND;
                }
            });
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

    /** Забыть текст (например, после смены настройки «искать в сети») — найдётся заново. */
    public static void reload() {
        trackKey = null;
    }

    private static @Nullable Lyrics load(Track track, boolean online) throws Exception {
        long duration = track.durationMs();
        if (track.file() != null && track.source() == Track.Source.LOCAL) {
            Path file = track.file();
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            Path lrc = file.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".lrc");
            if (Files.isRegularFile(lrc)) {
                Lyrics parsed = Lyrics.parse(Files.readString(lrc, StandardCharsets.UTF_8), duration);
                if (parsed != null) {
                    return parsed;
                }
            }
            String tag = TrackTags.read(file, false).lyrics();
            Lyrics parsed = tag == null ? null : Lyrics.parse(tag, duration);
            if (parsed != null && parsed.synced()) {
                return parsed;
            }
            if (!online) {
                return parsed;
            }
            Lyrics remote = fromCacheOrLrclib(track);
            return remote != null ? remote : parsed;
        }
        return online ? fromCacheOrLrclib(track) : null;
    }

    private static @Nullable Lyrics fromCacheOrLrclib(Track track) throws Exception {
        if (track.title().isBlank()) {
            return null;
        }
        Path dir = ClientFiles.root().resolve("cache").resolve("lyrics");
        Files.createDirectories(dir);
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest((track.artist() + "\n" + track.title()).toLowerCase().getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        // «v2» — после смены правил выбора (время строк, длина) старые найденные тексты ищутся заново.
        Path cached = dir.resolve(id + ".v2.lrc");
        String text;
        if (Files.exists(cached)) {
            text = Files.readString(cached, StandardCharsets.UTF_8);
        } else {
            text = lrclib(track);
            if (text == null) {
                return null; // ошибка сети — не кэшируем, попробуем в другой раз
            }
            Files.writeString(cached, text, StandardCharsets.UTF_8);
        }
        return text.isBlank() ? null : Lyrics.parse(text, track.durationMs());
    }

    /** Текст с LRCLIB: с временем строк, если есть, иначе обычный; «» — не нашли; {@code null} — ошибка сети. */
    private static @Nullable String lrclib(Track track) {
        Map<String, String> headers = Map.of("User-Agent", "BlockPhone/" + BlockPhoneFabric.VERSION + " (Minecraft mod)");
        String query = "artist_name=" + Http.encode(track.artist()) + "&track_name=" + Http.encode(track.title());
        try {
            String url = "https://lrclib.net/api/get?" + query
                    + (track.durationMs() > 0 ? "&duration=" + Math.round(track.durationMs() / 1000.0) : "");
            Http.Response response = Http.request("GET", url, headers, null);
            String plain = "";
            if (response.ok()) {
                JsonObject exact = JsonParser.parseString(response.body()).getAsJsonObject();
                if (has(exact, "syncedLyrics")) {
                    return exact.get("syncedLyrics").getAsString();
                }
                plain = has(exact, "plainLyrics") ? exact.get("plainLyrics").getAsString() : "";
            } else if (response.code() != 404) {
                return null;
            }
            // Нет точного совпадения или у него нет времени строк — ищем вариант с временем строк, ближайший по длине
            // (другая запись той же песни с другой длиной даст текст, который «спешит» или «отстаёт»).
            response = Http.request("GET", "https://lrclib.net/api/search?" + query, headers, null);
            if (!response.ok()) {
                return plain.isEmpty() ? null : plain;
            }
            JsonArray results = JsonParser.parseString(response.body()).getAsJsonArray();
            double duration = track.durationMs() / 1000.0;
            JsonObject best = null;
            double bestDiff = Double.MAX_VALUE;
            for (JsonElement e : results) {
                JsonObject o = e.getAsJsonObject();
                if (has(o, "syncedLyrics")) {
                    double d = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
                    double diff = duration > 0 && d > 0 ? Math.abs(d - duration) : 1000;
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = o;
                    }
                } else if (plain.isEmpty() && has(o, "plainLyrics")) {
                    plain = o.get("plainLyrics").getAsString();
                }
            }
            // Запись дольше/короче больше чем на 4 с — скорее другая версия, время строк не подойдёт.
            if (best != null && (duration <= 0 || bestDiff <= 4)) {
                return best.get("syncedLyrics").getAsString();
            }
            return plain;
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("LRCLIB request failed: {}", e.toString());
            return null;
        }
    }

    private static boolean has(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && !e.isJsonNull() && !e.getAsString().isBlank();
    }

    private LyricsService() {
    }
}
