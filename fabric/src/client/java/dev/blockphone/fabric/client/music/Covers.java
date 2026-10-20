package dev.blockphone.fabric.client.music;

import dev.blockphone.fabric.client.music.audio.TrackTags;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.photo.Picture;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Обложки треков как текстуры: из тегов локального файла или по адресу (Яндекс, Spotify). Грузятся в фоне, LRU.
 */
public final class Covers {
    private static final int SIZE = 200;
    private static final int MAX = 40;
    private static final Map<String, PhotoTextures.Image> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static final Set<String> LOADING = new HashSet<>();
    private static final Set<String> MISSING = new HashSet<>();

    /** Обложка трека или null (грузится или её нет). */
    public static PhotoTextures.@Nullable Image get(@Nullable Track track) {
        if (track == null) {
            return null;
        }
        if (track.coverUrl() != null) {
            return url(track.coverUrl());
        }
        if (track.file() != null) {
            return load("file:" + track.file(), () -> TrackTags.read(track.file(), true).cover());
        }
        return null;
    }

    /** Картинка из файла (аватары и фото Telegram). Квадратная обрезка по центру. */
    public static PhotoTextures.@Nullable Image file(java.nio.file.Path path) {
        return load("path:" + path, () -> java.nio.file.Files.readAllBytes(path));
    }

    /** Обложка по адресу картинки. */
    public static PhotoTextures.@Nullable Image url(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        return load("url:" + url, () -> download(url));
    }

    private interface Loader {
        byte @Nullable [] load() throws Exception;
    }

    private static PhotoTextures.@Nullable Image load(String key, Loader loader) {
        PhotoTextures.Image cached = CACHE.get(key);
        if (cached != null || LOADING.contains(key) || MISSING.contains(key)) {
            return cached;
        }
        LOADING.add(key);
        ClientFiles.IO.execute(() -> {
            Picture picture = null;
            try {
                byte[] bytes = loader.load();
                BufferedImage image = bytes != null ? ImageIO.read(new ByteArrayInputStream(bytes)) : null;
                if (image != null) {
                    int w = image.getWidth();
                    int h = image.getHeight();
                    int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
                    picture = ImageOps.squareThumbnail(new Picture(w, h, argb), SIZE);
                }
            } catch (Exception ignored) {
                // Нет обложки.
            }
            Picture result = picture;
            Minecraft.getInstance().execute(() -> {
                LOADING.remove(key);
                if (result == null) {
                    MISSING.add(key);
                    return;
                }
                CACHE.put(key, PhotoTextures.upload(result, "cover"));
                Iterator<Map.Entry<String, PhotoTextures.Image>> it = CACHE.entrySet().iterator();
                while (CACHE.size() > MAX && it.hasNext()) {
                    PhotoTextures.release(it.next().getValue());
                    it.remove();
                }
            });
        });
        return null;
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "BlockPhone");
        try (InputStream in = connection.getInputStream()) {
            return in.readNBytes(4 * 1024 * 1024);
        } finally {
            connection.disconnect();
        }
    }

    private Covers() {
    }
}
