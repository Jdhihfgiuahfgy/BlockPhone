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
        YandexBrowse.reset();
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

    /** Идущая волна (сессия): загрузчик (по нему узнаём, что играет именно она), сессия, seeds, трек для отзывов. */
    private static @Nullable java.util.function.Supplier<List<Track>> waveLoader;
    private static volatile String sessionId = "";
    private static volatile String waveBatch = "";
    private static volatile List<String> sessionSeeds = List.of();
    private static @Nullable Track waveTrack;
    private static long waveTrackPos;
    private static String waveName = "";

    /** Seeds «Моей волны» с настройками игрока (характер, настроение, язык — «Настройки волны»). */
    public static List<String> waveSeeds() {
        List<String> seeds = new ArrayList<>();
        seeds.add(YandexApi.WAVE);
        dev.blockphone.fabric.client.config.PhoneSettings s = dev.blockphone.fabric.client.config.PhoneSettings.get();
        for (String seed : new String[]{s.yandexWaveDiversity, s.yandexWaveMood, s.yandexWaveLanguage}) {
            if (seed != null && !seed.isBlank()) {
                seeds.add(seed);
            }
        }
        return seeds;
    }

    /** Запустить «Мою волну» (с настройками): очередь сама подгружается, когда треки заканчиваются. */
    public static void playWave() {
        playStation(waveSeeds(), PhoneLang.tr("music.my_wave"));
    }

    /** Волна по seeds: занятие («activity:workout»), трек («track:id»), артист («artist:id») или «Моя волна». */
    public static void playStation(List<String> seeds, String name) {
        if (startingWave) {
            return;
        }
        startingWave = true;
        YandexCatalog catalog = new YandexCatalog(api());
        ClientFiles.IO.execute(() -> {
            try {
                YandexCatalog.Session session = catalog.sessionNew(seeds);
                sessionId = session.id();
                waveBatch = session.batchId();
                sessionSeeds = List.copyOf(seeds);
                feedback(catalog, "radioStarted", null, 0);
                Minecraft.getInstance().execute(() -> {
                    startingWave = false;
                    if (!session.tracks().isEmpty()) {
                        waveLoader = waveLoader(catalog);
                        waveTrack = null;
                        waveName = name;
                        MusicPlayer.play(session.tracks(), 0, waveLoader, name);
                    }
                });
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /** Играет волна (любая станция) — для подписи и перезапуска после смены настроек. */
    public static boolean wavePlaying() {
        return waveLoader != null && MusicPlayer.loader() == waveLoader;
    }

    /**
     * Продолжение волны (в фоне, плеером): следующая пачка сессии с очередью уже полученных треков. Устаревшая сессия —
     * новая с теми же seeds. Повторы отбрасываются.
     */
    private static java.util.function.Supplier<List<Track>> waveLoader(YandexCatalog catalog) {
        return () -> {
            List<Track> queue = new ArrayList<>(MusicPlayer.queue());
            java.util.Set<String> known = new java.util.HashSet<>();
            queue.forEach(t -> known.add(t.key()));
            List<String> ids = new ArrayList<>();
            for (int i = Math.max(0, queue.size() - 30); i < queue.size(); i++) {
                String remote = queue.get(i).remoteId();
                if (remote != null) {
                    ids.add(remote.split(":")[0]);
                }
            }
            List<Track> fresh = new ArrayList<>();
            try {
                YandexCatalog.Session next = catalog.sessionTracks(sessionId, ids);
                if (next.unknown() || next.tracks().isEmpty()) {
                    next = catalog.sessionNew(sessionSeeds);
                    sessionId = next.id();
                }
                if (!next.batchId().isEmpty()) {
                    waveBatch = next.batchId();
                }
                for (Track t : next.tracks()) {
                    if (known.add(t.key())) {
                        fresh.add(t);
                    }
                }
            } catch (IOException e) {
                return List.of();
            }
            return fresh;
        };
    }

    /** Каждый такт: смена трека волны → отзывы Яндексу (дослушан или пропущен, начат следующий); место в книгах. */
    public static void tick() {
        YandexBooks.tick();
        Track current = MusicPlayer.current();
        if (!wavePlaying()) {
            waveTrack = null;
            return;
        }
        if (current == waveTrack) {
            waveTrackPos = Math.max(waveTrackPos, MusicPlayer.positionMs());
            return;
        }
        YandexCatalog catalog = new YandexCatalog(api());
        Track previous = waveTrack;
        long played = waveTrackPos;
        waveTrack = current;
        waveTrackPos = 0;
        ClientFiles.IO.execute(() -> {
            if (previous != null && previous.remoteId() != null) {
                boolean finished = previous.durationMs() > 0 && played >= previous.durationMs() - 15_000;
                feedback(catalog, finished ? "trackFinished" : "skip", previous.remoteId().split(":")[0], played / 1000.0);
            }
            if (current != null && current.remoteId() != null) {
                feedback(catalog, "trackStarted", current.remoteId().split(":")[0], 0);
            }
        });
    }

    private static void feedback(YandexCatalog catalog, String type, @Nullable String trackId, double seconds) {
        if (sessionId.isEmpty()) {
            return;
        }
        try {
            catalog.sessionFeedback(sessionId, waveBatch, type, trackId, seconds);
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.info("Yandex wave feedback {} failed: {}", type, e.toString());
        }
    }

    static YandexApi currentApi() {
        return api();
    }

    static void report(IOException e) {
        fail(e);
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
