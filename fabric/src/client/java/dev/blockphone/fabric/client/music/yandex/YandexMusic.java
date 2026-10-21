package dev.blockphone.fabric.client.music.yandex;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.music.Accounts;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.TrackSourceException;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Яндекс Музыка в телефоне: вход по токену, «Моя волна», «Мне нравится», поиск, лайк текущего трека.
 * Состояние — на потоке рендера, сеть — в {@link ClientFiles#IO}. Скачанные треки кэшируются
 * в {@code blockphone/cache/music/} (последние 40).
 */
public final class YandexMusic {
    /** Страница входа: после неё токен виден в адресной строке (access_token=...). Клиент — мобильное приложение. */
    public static final String OAUTH_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d";
    private static final int CACHE_FILES = 40;

    public enum Status { NONE, CHECKING, READY, ERROR }

    private static Status status = Status.NONE;
    private static YandexApi.@Nullable Account account;
    private static @Nullable String error;
    private static @Nullable List<Track> likes;
    private static boolean loadingLikes;
    private static final Set<String> LIKED = new HashSet<>();
    private static @Nullable List<Track> searchResults;
    private static boolean searching;
    private static boolean startingWave;
    private static int searchCounter;

    public static Status status() {
        if (status == Status.NONE && !Accounts.get().yandexToken.isEmpty()) {
            check();
        }
        return status;
    }

    public static YandexApi.@Nullable Account account() {
        return account;
    }

    public static @Nullable String error() {
        return error;
    }

    public static @Nullable List<Track> likes() {
        return likes;
    }

    public static boolean loadingLikes() {
        return loadingLikes;
    }

    public static @Nullable List<Track> searchResults() {
        return searchResults;
    }

    public static boolean searching() {
        return searching;
    }

    public static boolean startingWave() {
        return startingWave;
    }

    public static boolean liked(Track track) {
        return track.source() == Track.Source.YANDEX && LIKED.contains(track.key());
    }

    /** Токен из адресной строки или «access_token=...». */
    public static void login(String pasted) {
        String token = pasted.strip();
        int at = token.indexOf("access_token=");
        if (at >= 0) {
            token = token.substring(at + "access_token=".length());
            int amp = token.indexOf('&');
            if (amp >= 0) {
                token = token.substring(0, amp);
            }
        }
        Accounts.get().yandexToken = token;
        Accounts.save();
        status = Status.NONE;
        check();
    }

    public static void logout() {
        playlists = null;
        PLAYLIST_TRACKS.clear();
        Accounts.get().yandexToken = "";
        Accounts.save();
        status = Status.NONE;
        account = null;
        likes = null;
        LIKED.clear();
        searchResults = null;
        error = null;
    }

    private static YandexApi api() {
        return new YandexApi(Accounts.get().yandexToken);
    }

    private static void check() {
        status = Status.CHECKING;
        YandexApi api = api();
        ClientFiles.IO.execute(() -> {
            try {
                YandexApi.Account result = api.account();
                Minecraft.getInstance().execute(() -> {
                    account = result;
                    status = Status.READY;
                    error = null;
                    loadLikes();
                });
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    private static void fail(IOException e) {
        String key = e instanceof YandexApi.ApiException api ? api.key : "music.error.network";
        if (!(e instanceof YandexApi.ApiException)) {
            BlockPhoneFabric.LOGGER.warn("Yandex Music request failed", e);
        }
        Minecraft.getInstance().execute(() -> {
            error = PhoneLang.tr(key);
            if (status == Status.CHECKING || key.equals("music.error.yandex_auth")) {
                status = Status.ERROR;
            }
            loadingLikes = false;
            searching = false;
            startingWave = false;
        });
    }

    private static @Nullable List<YandexApi.Playlist> playlists;
    private static boolean loadingPlaylists;
    private static final java.util.Map<String, List<Track>> PLAYLIST_TRACKS = new java.util.HashMap<>();
    private static final Set<String> LOADING_PLAYLISTS = new HashSet<>();

    public static @Nullable List<YandexApi.Playlist> playlists() {
        return playlists;
    }

    public static boolean loadingPlaylists() {
        return loadingPlaylists;
    }

    /** Загрузить список своих плейлистов (один раз; повторно — после выхода и входа). */
    public static void loadPlaylists() {
        if (account == null || loadingPlaylists || playlists != null) {
            return;
        }
        loadingPlaylists = true;
        YandexApi api = api();
        String uid = account.uid();
        ClientFiles.IO.execute(() -> {
            try {
                List<YandexApi.Playlist> result = api.playlists(uid);
                Minecraft.getInstance().execute(() -> {
                    playlists = result;
                    loadingPlaylists = false;
                });
            } catch (IOException e) {
                Minecraft.getInstance().execute(() -> loadingPlaylists = false);
                fail(e);
            }
        });
    }

    public static @Nullable List<Track> playlistTracks(YandexApi.Playlist playlist) {
        return PLAYLIST_TRACKS.get(playlist.owner() + ":" + playlist.kind());
    }

    public static void loadPlaylist(YandexApi.Playlist playlist) {
        String key = playlist.owner() + ":" + playlist.kind();
        if (account == null || PLAYLIST_TRACKS.containsKey(key) || !LOADING_PLAYLISTS.add(key)) {
            return;
        }
        YandexApi api = api();
        ClientFiles.IO.execute(() -> {
            try {
                List<Track> result = api.playlistTracks(playlist);
                Minecraft.getInstance().execute(() -> {
                    PLAYLIST_TRACKS.put(key, result);
                    LOADING_PLAYLISTS.remove(key);
                });
            } catch (IOException e) {
                Minecraft.getInstance().execute(() -> LOADING_PLAYLISTS.remove(key));
                fail(e);
            }
        });
    }

    public static void loadLikes() {
        if (account == null || loadingLikes) {
            return;
        }
        loadingLikes = true;
        YandexApi api = api();
        String uid = account.uid();
        ClientFiles.IO.execute(() -> {
            try {
                List<Track> result = api.likes(uid, 300);
                Minecraft.getInstance().execute(() -> {
                    likes = result;
                    LIKED.clear();
                    result.forEach(t -> LIKED.add(t.key()));
                    loadingLikes = false;
                });
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    public static void search(String text) {
        if (text.isBlank()) {
            searchResults = null;
            return;
        }
        int request = ++searchCounter;
        searching = true;
        YandexApi api = api();
        ClientFiles.IO.execute(() -> {
            try {
                List<Track> result = api.search(text);
                Minecraft.getInstance().execute(() -> {
                    if (request == searchCounter) {
                        searchResults = result;
                        searching = false;
                    }
                });
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /** Идущая «Моя волна»: загрузчик (по нему узнаём, что играет именно она), batchId, трек для отзывов. */
    private static @Nullable java.util.function.Supplier<List<Track>> waveLoader;
    private static volatile String waveBatch = "";
    private static @Nullable Track waveTrack;
    private static long waveTrackPos;

    /** Запустить «Мою волну»: очередь сама подгружается, когда треки заканчиваются. */
    public static void playWave() {
        if (startingWave) {
            return;
        }
        startingWave = true;
        YandexApi api = api();
        ClientFiles.IO.execute(() -> {
            try {
                YandexApi.WaveBatch batch = api.waveBatch(null);
                waveBatch = batch.batchId();
                feedback(api, "radioStarted", null, 0);
                Minecraft.getInstance().execute(() -> {
                    startingWave = false;
                    if (!batch.tracks().isEmpty()) {
                        waveLoader = waveLoader(api);
                        waveTrack = null;
                        MusicPlayer.play(batch.tracks(), 0, waveLoader, PhoneLang.tr("music.my_wave"));
                    }
                });
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /**
     * Продолжение волны (в фоне, плеером): после последнего трека очереди. Если пачка повторяет уже сыгранное —
     * ещё раз от другого трека (до 3 попыток), чтобы волна не крутила одно и то же.
     */
    private static java.util.function.Supplier<List<Track>> waveLoader(YandexApi api) {
        return () -> {
            List<Track> queue = new ArrayList<>(MusicPlayer.queue());
            java.util.Set<String> known = new java.util.HashSet<>();
            queue.forEach(t -> known.add(t.key()));
            List<Track> fresh = new ArrayList<>();
            for (int attempt = 0; attempt < 3 && fresh.isEmpty(); attempt++) {
                int from = queue.size() - 1 - attempt * 3;
                String after = from >= 0 ? queue.get(from).remoteId() : null;
                try {
                    YandexApi.WaveBatch batch = api.waveBatch(after);
                    if (!batch.batchId().isEmpty()) {
                        waveBatch = batch.batchId();
                    }
                    for (Track t : batch.tracks()) {
                        if (known.add(t.key())) {
                            fresh.add(t);
                        }
                    }
                } catch (IOException e) {
                    return List.of();
                }
            }
            return fresh;
        };
    }

    /** Каждый такт: смена трека «Моей волны» → отзывы Яндексу (дослушан или пропущен, начат следующий). */
    public static void tick() {
        Track current = MusicPlayer.current();
        boolean wave = waveLoader != null && MusicPlayer.loader() == waveLoader;
        if (!wave) {
            waveTrack = null;
            return;
        }
        if (current == waveTrack) {
            waveTrackPos = Math.max(waveTrackPos, MusicPlayer.positionMs());
            return;
        }
        YandexApi api = api();
        Track previous = waveTrack;
        long played = waveTrackPos;
        waveTrack = current;
        waveTrackPos = 0;
        ClientFiles.IO.execute(() -> {
            if (previous != null && previous.remoteId() != null) {
                boolean finished = previous.durationMs() > 0 && played >= previous.durationMs() - 15_000;
                feedback(api, finished ? "trackFinished" : "skip", previous.remoteId(), played / 1000.0);
            }
            if (current != null && current.remoteId() != null) {
                feedback(api, "trackStarted", current.remoteId(), 0);
            }
        });
    }

    private static void feedback(YandexApi api, String type, @Nullable String trackId, double seconds) {
        try {
            api.waveFeedback(type, trackId, seconds, waveBatch);
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.info("Yandex wave feedback {} failed: {}", type, e.toString());
        }
    }

    public static void toggleLike(Track track) {
        if (account == null || track.source() != Track.Source.YANDEX || track.remoteId() == null) {
            return;
        }
        boolean like = !LIKED.contains(track.key());
        if (like) {
            LIKED.add(track.key());
        } else {
            LIKED.remove(track.key());
        }
        YandexApi api = api();
        String uid = account.uid();
        ClientFiles.IO.execute(() -> {
            try {
                api.like(uid, track.remoteId(), like);
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /** Файл трека (скачивается при первом проигрывании). Вызывается из потока воспроизведения. */
    public static Path download(Track track) throws TrackSourceException {
        String id = track.remoteId() != null ? track.remoteId().split(":")[0] : track.key().substring("yandex:".length());
        // Один трек качается один раз: второй поток (предзагрузка/повтор) ждёт и берёт готовый файл.
        synchronized (DOWNLOAD_LOCKS.computeIfAbsent(id, k -> new Object())) {
            return downloadLocked(id);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> DOWNLOAD_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Path downloadLocked(String id) throws TrackSourceException {
        Path dir = ClientFiles.root().resolve("cache").resolve("music");
        Path file = dir.resolve("yandex_" + id.replaceAll("[^0-9A-Za-z]", "_") + ".mp3");
        if (Files.isRegularFile(file)) {
            try {
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException ignored) {
            }
            return file;
        }
        String token = Accounts.get().yandexToken;
        if (token.isEmpty()) {
            throw new TrackSourceException("music.error.yandex_auth", null);
        }
        try {
            new YandexApi(token).download(id, file);
        } catch (YandexApi.ApiException e) {
            throw new TrackSourceException(e.key, null);
        } catch (IOException e) {
            throw new TrackSourceException("music.error.network", e);
        }
        trimCache(dir);
        return file;
    }

    private static void trimCache(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> list = new ArrayList<>(files.filter(p -> p.getFileName().toString().endsWith(".mp3")).toList());
            list.sort(Comparator.comparingLong(p -> -p.toFile().lastModified()));
            for (int i = CACHE_FILES; i < list.size(); i++) {
                Files.deleteIfExists(list.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    private YandexMusic() {
    }
}
