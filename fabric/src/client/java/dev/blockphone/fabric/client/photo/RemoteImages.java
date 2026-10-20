package dev.blockphone.fabric.client.photo;

import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Фото с сервера (в постах и сообщениях): скачивание кусками, кэш на диске
 * ({@code blockphone/cache/<id сервера>/<id>.img}) и текстуры нужного размера (LRU).
 * Только поток рендера; файлы читаются и декодируются в {@link ClientFiles#IO}.
 */
public final class RemoteImages {
    private static final int MAX_TEXTURES = 80;

    /** Скачиваемая картинка. */
    private static final class Download {
        final byte[] data;
        int received;

        Download(int total) {
            data = new byte[total];
        }
    }

    private static final Map<String, PhotoTextures.Image> TEXTURES = new LinkedHashMap<>(32, 0.75f, true);
    private static final Set<String> LOADING = new HashSet<>();
    private static final Set<Long> FAILED = new HashSet<>();
    private static final Set<Long> REQUESTED = new HashSet<>();
    private static final Map<Long, Download> DOWNLOADS = new HashMap<>();
    /** Растёт при выходе с сервера: результаты старых фоновых задач отбрасываются. */
    private static int generation;

    /**
     * Текстура фото не больше maxSize по длинной стороне или null, пока грузится (или если фото недоступно —
     * см. {@link #failed}). Можно вызывать каждый кадр.
     */
    public static PhotoTextures.@Nullable Image get(long imageId, int maxSize) {
        String key = imageId + ":" + maxSize;
        PhotoTextures.Image cached = TEXTURES.get(key);
        if (cached != null || LOADING.contains(key) || FAILED.contains(imageId)) {
            return cached;
        }
        LOADING.add(key);
        Path file = cacheFile(imageId);
        int gen = generation;
        ClientFiles.IO.execute(() -> {
            if (!Files.exists(file)) {
                Minecraft.getInstance().execute(() -> {
                    if (gen == generation) {
                        download(imageId);
                    }
                });
                return;
            }
            Picture picture;
            try {
                picture = ImageOps.downscale(ImageOps.read(file), maxSize, maxSize);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Bad cached image {}", file, e);
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
                picture = null;
            }
            Picture result = picture;
            Minecraft.getInstance().execute(() -> {
                if (gen != generation) {
                    return;
                }
                LOADING.remove(key);
                if (result == null) {
                    FAILED.add(imageId);
                    return;
                }
                TEXTURES.put(key, PhotoTextures.upload(result, "remote"));
                trim();
            });
        });
        return null;
    }

    /** Фото не удалось получить (удалено или нет доступа). */
    public static boolean failed(long imageId) {
        return FAILED.contains(imageId);
    }

    private static void download(long imageId) {
        if (REQUESTED.add(imageId)) {
            ClientNetwork.send(new ImagePackets.RequestImage(imageId));
        }
    }

    /** Кусок картинки от сервера. */
    public static void onData(ImagePackets.ImageData data) {
        long id = data.imageId();
        if (data.total() <= 0) {
            REQUESTED.remove(id);
            DOWNLOADS.remove(id);
            FAILED.add(id);
            LOADING.removeIf(k -> k.startsWith(id + ":"));
            return;
        }
        Download download = DOWNLOADS.computeIfAbsent(id, k -> new Download(data.total()));
        if (download.data.length != data.total() || data.offset() != download.received
                || data.offset() + data.data().length > download.data.length) {
            DOWNLOADS.remove(id);
            REQUESTED.remove(id);
            LOADING.removeIf(k -> k.startsWith(id + ":"));
            return;
        }
        System.arraycopy(data.data(), 0, download.data, download.received, data.data().length);
        download.received += data.data().length;
        if (download.received < download.data.length) {
            return;
        }
        DOWNLOADS.remove(id);
        Path file = cacheFile(id);
        byte[] bytes = download.data;
        int gen = generation;
        ClientFiles.IO.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                BlockPhoneFabric.LOGGER.warn("Failed to cache image {}", file, e);
            }
            Minecraft.getInstance().execute(() -> {
                if (gen == generation) {
                    REQUESTED.remove(id);
                    // Следующий кадр прочитает файл из кэша.
                    LOADING.removeIf(k -> k.startsWith(id + ":"));
                }
            });
        });
    }

    private static Path cacheFile(long imageId) {
        String server = ClientSession.serverId();
        String folder = server == null ? "unknown" : server.replaceAll("[^A-Za-z0-9-]", "_");
        return ClientFiles.root().resolve("cache").resolve(folder).resolve(imageId + ".img");
    }

    private static void trim() {
        Iterator<Map.Entry<String, PhotoTextures.Image>> it = TEXTURES.entrySet().iterator();
        while (TEXTURES.size() > MAX_TEXTURES && it.hasNext()) {
            PhotoTextures.release(it.next().getValue());
            it.remove();
        }
    }

    /** Выход с сервера: освободить текстуры и забыть загрузки (файлы кэша остаются). */
    public static void reset() {
        generation++;
        for (PhotoTextures.Image image : TEXTURES.values()) {
            PhotoTextures.release(image);
        }
        TEXTURES.clear();
        LOADING.clear();
        FAILED.clear();
        REQUESTED.clear();
        DOWNLOADS.clear();
    }

    private RemoteImages() {
    }
}
