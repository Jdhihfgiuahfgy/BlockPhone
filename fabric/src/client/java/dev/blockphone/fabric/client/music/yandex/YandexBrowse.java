package dev.blockphone.fabric.client.music.yandex;

import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Состояние каталога Яндекса для экранов «Музыки»: главная, книги и подкасты, настройки волны, альбомы, артисты,
 * понравившиеся альбомы, поиск по всему. Всё грузится по запросу экрана в фоне ({@link ClientFiles#IO}) и кэшируется
 * до выхода из аккаунта. Только поток рендера.
 */
public final class YandexBrowse {
    /** Свои потоки для запросов каталога: долгий ответ Яндекса не задерживает файлы и картинки (ClientFiles.IO). */
    static final java.util.concurrent.ExecutorService NET = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "BlockPhone Yandex");
        t.setDaemon(true);
        return t;
    });

    private static YandexCatalog.@Nullable Landing landing;
    private static YandexCatalog.@Nullable Spoken spoken;
    private static YandexCatalog.@Nullable WaveSettings waveSettings;
    private static @Nullable List<Track> chart;
    private static @Nullable List<YandexCatalog.Album> likedAlbums;
    private static final Set<String> LIKED_ALBUMS = new HashSet<>();
    private static final Map<String, YandexCatalog.AlbumPage> ALBUMS = new HashMap<>();
    private static final Map<String, YandexCatalog.ArtistPage> ARTISTS = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    private static YandexCatalog.@Nullable SearchAll search;
    private static String searchText = "";
    private static int searchCounter;
    private static boolean searching;

    public static YandexCatalog.@Nullable Landing landing() {
        load("landing", c -> {
            YandexCatalog.Landing r = c.landing();
            return () -> landing = r;
        }, landing != null);
        return landing;
    }

    /** Каталог книг и подкастов с «Продолжить слушать» аккаунта. */
    public static YandexCatalog.@Nullable Spoken spoken() {
        load("spoken", c -> {
            YandexCatalog.Spoken r = c.spokenCatalogue();
            return () -> spoken = r;
        }, spoken != null);
        return spoken;
    }

    /** Перечитать «Продолжить слушать» и страницу книги (после прослушивания — свежее место с аккаунта). */
    public static void refreshProgress(String albumId) {
        spoken = null;
        ALBUMS.remove(albumId);
    }

    /** «Продолжить слушать» аккаунта для этой книги (или null). */
    public static YandexCatalog.@Nullable ContinueItem continueItem(String albumId) {
        if (spoken == null) {
            return null;
        }
        for (YandexCatalog.ContinueItem item : spoken.continueItems()) {
            if (item.album().id().equals(albumId)) {
                return item;
            }
        }
        return null;
    }

    public static YandexCatalog.@Nullable WaveSettings waveSettings() {
        load("wave", c -> {
            YandexCatalog.WaveSettings r = c.waveSettings();
            return () -> waveSettings = r;
        }, waveSettings != null);
        return waveSettings;
    }

    public static @Nullable List<Track> chart() {
        load("chart", c -> {
            List<Track> r = c.chart();
            return () -> chart = r;
        }, chart != null);
        return chart;
    }

    public static @Nullable List<YandexCatalog.Album> likedAlbums() {
        YandexApi.Account account = YandexMusic.account();
        if (account != null) {
            String uid = account.uid();
            load("likedAlbums", c -> {
                List<YandexCatalog.Album> r = c.likedAlbums(uid);
                return () -> {
                    likedAlbums = r;
                    LIKED_ALBUMS.clear();
                    r.forEach(a -> LIKED_ALBUMS.add(a.id()));
                };
            }, likedAlbums != null);
        }
        return likedAlbums;
    }

    public static boolean albumLiked(String id) {
        likedAlbums();
        return LIKED_ALBUMS.contains(id);
    }

    public static void toggleAlbumLike(YandexCatalog.Album album) {
        YandexApi.Account account = YandexMusic.account();
        if (account == null) {
            return;
        }
        boolean like = !LIKED_ALBUMS.contains(album.id());
        if (like) {
            LIKED_ALBUMS.add(album.id());
        } else {
            LIKED_ALBUMS.remove(album.id());
        }
        String uid = account.uid();
        YandexCatalog catalog = new YandexCatalog(YandexMusic.currentApi());
        NET.execute(() -> {
            try {
                catalog.likeAlbum(uid, album.id(), like);
                Minecraft.getInstance().execute(() -> likedAlbums = null);
            } catch (IOException e) {
                YandexMusic.report(e);
            }
        });
    }

    public static YandexCatalog.@Nullable AlbumPage album(String id) {
        load("album:" + id, c -> {
            YandexCatalog.AlbumPage r = c.album(id);
            return () -> ALBUMS.put(id, r);
        }, ALBUMS.containsKey(id));
        return ALBUMS.get(id);
    }

    public static YandexCatalog.@Nullable ArtistPage artist(String id) {
        load("artist:" + id, c -> {
            YandexCatalog.ArtistPage r = c.artist(id);
            return () -> ARTISTS.put(id, r);
        }, ARTISTS.containsKey(id));
        return ARTISTS.get(id);
    }

    public static boolean loading(String key) {
        return LOADING.contains(key);
    }

    // --- Поиск по всему ---

    public static void search(String text) {
        searchText = text;
        if (text.isBlank()) {
            search = null;
            searching = false;
            return;
        }
        int request = ++searchCounter;
        searching = true;
        YandexCatalog catalog = new YandexCatalog(YandexMusic.currentApi());
        NET.execute(() -> {
            try {
                YandexCatalog.SearchAll r = catalog.search(text);
                Minecraft.getInstance().execute(() -> {
                    if (request == searchCounter) {
                        search = r;
                        searching = false;
                    }
                });
            } catch (IOException e) {
                Minecraft.getInstance().execute(() -> searching = false);
                YandexMusic.report(e);
            }
        });
    }

    public static YandexCatalog.@Nullable SearchAll searchResults() {
        return search;
    }

    public static boolean searching() {
        return searching;
    }

    /** Выход из аккаунта — всё забыть. */
    public static void reset() {
        landing = null;
        spoken = null;
        waveSettings = null;
        chart = null;
        likedAlbums = null;
        LIKED_ALBUMS.clear();
        ALBUMS.clear();
        ARTISTS.clear();
        search = null;
        searchText = "";
    }

    // --- Загрузка ---

    private interface Loader {
        Runnable run(YandexCatalog catalog) throws IOException;
    }

    /** Загрузить один раз (пока грузится — повторно не запрашивать; ошибка — можно снова при следующем показе). */
    private static void load(String key, Loader loader, boolean have) {
        if (have || YandexMusic.account() == null || recentlyFailed(key) || !LOADING.add(key)) {
            return;
        }
        YandexCatalog catalog = new YandexCatalog(YandexMusic.currentApi());
        NET.execute(() -> {
            try {
                Runnable apply = loader.run(catalog);
                Minecraft.getInstance().execute(() -> {
                    LOADING.remove(key);
                    apply.run();
                });
            } catch (IOException | RuntimeException e) {
                Minecraft.getInstance().execute(() -> FAILED.put(key, System.currentTimeMillis()));
                Minecraft.getInstance().execute(() -> LOADING.remove(key));
                if (e instanceof IOException io) {
                    YandexMusic.report(io);
                } else {
                    dev.blockphone.fabric.BlockPhoneFabric.LOGGER.warn("Yandex catalog {} failed", key, e);
                }
            }
        });
    }

    /** Когда загрузка падала (чтобы экран не долбил запросами каждый кадр — повтор не раньше чем через 15 с). */
    private static final Map<String, Long> FAILED = new HashMap<>();

    static boolean recentlyFailed(String key) {
        Long at = FAILED.get(key);
        return at != null && System.currentTimeMillis() - at < 15_000;
    }

    private YandexBrowse() {
    }
}
