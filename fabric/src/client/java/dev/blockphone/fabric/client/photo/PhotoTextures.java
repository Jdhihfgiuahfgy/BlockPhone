package dev.blockphone.fabric.client.photo;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Текстуры фотографий для отрисовки в телефоне: квадратные миниатюры и уменьшенные копии для просмотра.
 * Файлы читаются и уменьшаются в фоне ({@link ClientFiles#IO}), текстуры создаются на потоке рендера.
 */
public final class PhotoTextures {
    /** Текстура с плавным масштабированием (фото при уменьшении не «рябят»). */
    public static final class SmoothTexture extends DynamicTexture {
        public SmoothTexture(String label, NativeImage image) {
            super(() -> label, image);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    /** Готовая текстура и её размер в пикселях. */
    public record Image(Identifier id, int width, int height) {
    }

    private static final int MAX_CACHED = 120;
    public static final int THUMB_SIZE = 96;

    private static final Map<String, Image> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static final Set<String> LOADING = new HashSet<>();
    private static int counter;

    /** Миниатюра или null, пока грузится. */
    public static @Nullable Image thumbnail(PhotoStore.Photo photo) {
        return get(photo, "thumb", THUMB_SIZE, THUMB_SIZE, true);
    }

    /** Уменьшенная копия для просмотра (не больше maxSize по длинной стороне) или null, пока грузится. */
    public static @Nullable Image preview(PhotoStore.Photo photo, int maxSize) {
        return get(photo, "view" + maxSize, maxSize, maxSize, false);
    }

    private static @Nullable Image get(PhotoStore.Photo photo, String kind, int maxW, int maxH, boolean square) {
        String key = kind + "|" + photo.file() + "|" + photo.modified();
        Image cached = CACHE.get(key);
        if (cached != null || LOADING.contains(key)) {
            return cached;
        }
        LOADING.add(key);
        ClientFiles.IO.execute(() -> {
            Picture picture;
            try {
                Picture full = ImageOps.read(photo.file());
                picture = square ? ImageOps.squareThumbnail(full, maxW) : ImageOps.downscale(full, maxW, maxH);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to load photo {}", photo.file(), e);
                picture = null;
            }
            Picture result = picture;
            Minecraft.getInstance().execute(() -> {
                LOADING.remove(key);
                if (result != null) {
                    CACHE.put(key, upload(result, kind));
                    trim();
                }
            });
        });
        return null;
    }

    /** Создаёт текстуру из картинки (поток рендера). */
    public static Image upload(Picture picture, String kind) {
        Identifier id = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "dynamic/photo/" + kind + "_" + (counter++));
        Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture("BlockPhone " + kind, ImageOps.toNativeImage(picture)));
        return new Image(id, picture.width(), picture.height());
    }

    public static void release(Image image) {
        Minecraft.getInstance().getTextureManager().release(image.id());
    }

    /** Забыть все текстуры этого файла (после удаления или правки). */
    public static void invalidate(PhotoStore.Photo photo) {
        String marker = "|" + photo.file() + "|";
        Iterator<Map.Entry<String, Image>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Image> entry = it.next();
            if (entry.getKey().contains(marker)) {
                release(entry.getValue());
                it.remove();
            }
        }
    }

    private static void trim() {
        Iterator<Map.Entry<String, Image>> it = CACHE.entrySet().iterator();
        while (CACHE.size() > MAX_CACHED && it.hasNext()) {
            release(it.next().getValue());
            it.remove();
        }
    }

    private PhotoTextures() {
    }
}
