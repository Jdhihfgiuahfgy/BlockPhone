package dev.blockphone.fabric.client.music.yandex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.storage.ClientFiles;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Где остановился в аудиокнигах и подкастах Яндекса: место с аккаунта (streamProgress глав, «Продолжить слушать» —
 * что слушали в приложении Яндекса) и своё (blockphone/yandex_books.json); берётся более свежее. Пока играет
 * книга/подкаст, открытые с его страницы, прослушивание уходит в Яндекс (/plays) раз в 15 с, на паузе и при смене
 * главы — книга поднимается в «Продолжить слушать» у Яндекса (точное место Яндекс хранит в своём Ynison, его не меняем).
 */
public final class YandexBooks {
    /** Место в книге или подкасте. */
    public static final class Entry {
        public String albumId = "";
        public String title = "";
        public String artist = "";
        public String coverUrl;
        public boolean book;
        public String trackKey = "";
        public String trackTitle = "";
        public int trackIndex;
        public long positionMs;
        public long durationMs;
        public long updated;
        /** Когда последний раз отправляли прослушивание в Яндекс (его «слушал» в аккаунте — это мы). */
        public long reported;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX = 50;
    private static @Nullable Map<String, Entry> entries;
    /** Ключ трека → книга, из которой он играет (только треки, открытые со страницы книги/подкаста). */
    private static final Map<String, YandexCatalog.Album> PLAYING = new HashMap<>();
    private static long lastSave;
    private static long pendingSeek = -1;
    private static @Nullable String pendingKey;
    private static long pendingAt;
    /** Место сейчас (живое, ещё не перечитанное с аккаунта): ключ трека → мс. */
    private static final Map<String, Long> LIVE = new HashMap<>();
    // Отчёт в Яндекс о текущей главе.
    private static @Nullable Track reportTrack;
    private static long reportPos;
    private static long reportPlayedMs;
    private static long lastReport;
    private static boolean wasPlaying;
    private static String playId = "";

    /** Место главы для показа: живое (только что слушали) или -1. */
    public static long livePosition(String trackKey) {
        Long v = LIVE.get(trackKey);
        return v == null ? -1 : v;
    }

    /** С какой главы и места продолжить: index в списке и мс (null — книгу ещё не слушали). */
    public record Resume(int index, long positionMs, boolean fromAccount) {
        static Resume at(List<Track> tracks, int index, long pos, boolean fromAccount) {
            long duration = tracks.get(index).durationMs();
            if (duration > 0 && pos >= duration - 10_000 && index + 1 < tracks.size()) {
                return new Resume(index + 1, 0, fromAccount);
            }
            return new Resume(index, pos, fromAccount);
        }
    }

    /**
     * Точка «Продолжить»: «Продолжить слушать» аккаунта или своё место — что новее; иначе по местам глав с аккаунта
     * (последняя начатая глава; дослушанная — следующая с начала).
     */
    public static @Nullable Resume resume(YandexCatalog.AlbumPage page) {
        List<Track> tracks = page.tracks();
        Entry local = entries().get(page.album().id());
        YandexCatalog.ContinueItem account = YandexBrowse.continueItem(page.album().id());
        long accountAt = 0;
        if (account != null) {
            try {
                accountAt = java.time.OffsetDateTime.parse(account.listened()).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                accountAt = 1; // дата неизвестна — своё место (если есть) важнее
            }
        }
        // Аккаунт важнее, только если там слушали позже нас (наши же отчёты двигают дату «слушал» в аккаунте).
        if (account != null && (local == null || accountAt > Math.max(local.updated, local.reported) + 60_000)) {
            int i = indexOf(tracks, account.trackKey());
            if (i >= 0) {
                long pos = account.positionMs() > 0 ? account.positionMs() : page.progress().getOrDefault(account.trackKey(), 0L);
                return Resume.at(tracks, i, pos, true);
            }
        }
        if (local != null) {
            int i = indexOf(tracks, local.trackKey);
            if (i >= 0) {
                return Resume.at(tracks, i, local.positionMs, false);
            }
        }
        int last = -1;
        for (int i = 0; i < tracks.size(); i++) {
            if (page.progress().getOrDefault(tracks.get(i).key(), 0L) > 0) {
                last = i;
            }
        }
        return last < 0 ? null : Resume.at(tracks, last, page.progress().get(tracks.get(last).key()), true);
    }

    private static int indexOf(List<Track> tracks, String key) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static Path file() {
        return ClientFiles.root().resolve("yandex_books.json");
    }

    private static Map<String, Entry> entries() {
        if (entries == null) {
            entries = new LinkedHashMap<>();
            try {
                if (Files.isRegularFile(file())) {
                    Map<String, Entry> loaded = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                            new TypeToken<LinkedHashMap<String, Entry>>() { }.getType());
                    if (loaded != null) {
                        entries.putAll(loaded);
                    }
                }
            } catch (IOException | RuntimeException e) {
                BlockPhoneFabric.LOGGER.warn("Yandex books progress not read", e);
            }
        }
        return entries;
    }

    public static @Nullable Entry get(String albumId) {
        return entries().get(albumId);
    }

    /** Недавние книги и подкасты (новые сверху). */
    public static List<Entry> recent() {
        List<Entry> list = new ArrayList<>(entries().values());
        list.sort((a, b) -> Long.compare(b.updated, a.updated));
        return list;
    }

    /** Играть книгу/подкаст с главы index и места seekMs (0 — с начала главы). */
    public static void play(YandexCatalog.Album album, List<Track> tracks, int index, long seekMs) {
        if (tracks.isEmpty()) {
            return;
        }
        for (Track t : tracks) {
            PLAYING.put(t.key(), album);
        }
        int start = Math.max(0, Math.min(tracks.size() - 1, index));
        long seek = seekMs;
        playId = java.util.UUID.randomUUID().toString();
        MusicPlayer.play(tracks, start, null, album.title());
        if (seek > 3000) {
            pendingSeek = seek;
            pendingKey = tracks.get(start).key();
            pendingAt = System.currentTimeMillis();
            MusicPlayer.seek(seek);
        }
    }

    /** Каждый такт: запомнить место (раз в 5 с) и довести перемотку «Продолжить», если трек грузился. */
    static void tick() {
        Track current = MusicPlayer.current();
        if (current == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingSeek >= 0 && current.key().equals(pendingKey)) {
            if (MusicPlayer.positionMs() < pendingSeek - 5000 && MusicPlayer.durationMs() > 0 && now - pendingAt > 800) {
                MusicPlayer.seek(pendingSeek);
                pendingSeek = -1;
            } else if (now - pendingAt > 10_000) {
                pendingSeek = -1;
            }
        }
        YandexCatalog.Album album = PLAYING.get(current.key());
        boolean playing = MusicPlayer.state() == MusicPlayer.State.PLAYING;
        report(current, album, playing, now);
        if (album == null || !playing || now - lastSave < 5000) {
            return;
        }
        lastSave = now;
        LIVE.put(current.key(), MusicPlayer.positionMs());
        Map<String, Entry> map = entries();
        Entry e = map.computeIfAbsent(album.id(), k -> new Entry());
        e.albumId = album.id();
        e.title = album.title();
        e.artist = album.artist();
        e.coverUrl = album.coverUrl();
        e.book = album.book();
        e.trackKey = current.key();
        e.trackTitle = current.title();
        e.trackIndex = MusicPlayer.index();
        e.positionMs = MusicPlayer.positionMs();
        e.durationMs = Math.max(current.durationMs(), MusicPlayer.durationMs());
        e.updated = now;
        while (map.size() > MAX) {
            String oldest = recent().get(map.size() - 1).albumId;
            map.remove(oldest);
        }
        String json = GSON.toJson(map);
        ClientFiles.IO.execute(() -> {
            try {
                Path tmp = file().resolveSibling("yandex_books.json.tmp");
                Files.createDirectories(tmp.getParent());
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                BlockPhoneFabric.LOGGER.warn("Yandex books progress not saved", ex);
            }
        });
    }

    /** Отчёты в Яндекс: раз в 15 с во время прослушивания, на паузе и при смене главы (последнее место прошлой). */
    private static void report(Track current, YandexCatalog.@Nullable Album album, boolean playing, long now) {
        if (reportTrack != null && reportTrack != current) {
            send(reportTrack, reportPos, reportPlayedMs, true);
            reportTrack = null;
        }
        if (album == null) {
            wasPlaying = false;
            return;
        }
        if (reportTrack == null) {
            reportTrack = current;
            reportPlayedMs = 0;
            lastReport = now;
        }
        long pos = MusicPlayer.positionMs();
        if (playing) {
            reportPlayedMs += Math.max(0, Math.min(1000, pos - reportPos));
        }
        reportPos = pos;
        if ((playing && now - lastReport > 15_000) || (wasPlaying && !playing)) {
            lastReport = now;
            send(current, pos, reportPlayedMs, !playing);
            reportPlayedMs = 0;
        }
        wasPlaying = playing;
    }

    private static void send(Track track, long posMs, long playedMs, boolean refresh) {
        YandexApi.Account account = YandexMusic.account();
        if (account == null || track.remoteId() == null) {
            return;
        }
        LIVE.put(track.key(), posMs);
        String[] ids = track.remoteId().split(":");
        Entry entry = ids.length > 1 ? entries().get(ids[1]) : null;
        if (entry != null) {
            entry.reported = System.currentTimeMillis();
        }
        String albumId = ids.length > 1 ? ids[1] : "";
        YandexApi api = YandexMusic.currentApi();
        String play = playId.isEmpty() ? java.util.UUID.randomUUID().toString() : playId;
        double length = Math.max(track.durationMs(), MusicPlayer.durationMs()) / 1000.0;
        YandexBrowse.NET.execute(() -> {
            try {
                api.reportPlay(ids[0], albumId, play, length, playedMs / 1000.0, posMs / 1000.0);
                if (refresh) {
                    net.minecraft.client.Minecraft.getInstance().execute(() -> YandexBrowse.refreshProgress(albumId));
                }
            } catch (IOException e) {
                BlockPhoneFabric.LOGGER.info("Yandex plays report failed: {}", e.toString());
            }
        });
    }

    private YandexBooks() {
    }
}
