package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Обои телефона: стандартный градиент ({@link PhoneTheme#WALLPAPERS}) или своя картинка из
 * {@code blockphone/wallpapers/} (игрок кладёт файлы сам или выбирает фото в Галерее → «Обои»).
 */
public final class Wallpapers {
    private static @Nullable String cachedName;
    private static PhotoStore.@Nullable Photo cachedPhoto;

    public static Path dir() {
        return ClientFiles.root().resolve("wallpapers");
    }

    /** Свои обои в папке (png/jpg), по имени. Читает диск — не вызывать каждый кадр. */
    public static List<PhotoStore.Photo> list() {
        List<PhotoStore.Photo> result = new ArrayList<>();
        if (!Files.isDirectory(dir())) {
            return result;
        }
        try (Stream<Path> files = Files.list(dir())) {
            files.filter(Wallpapers::isImage).sorted().forEach(p -> {
                try {
                    result.add(new PhotoStore.Photo(p, Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("Failed to list wallpapers", e);
        }
        return result;
    }

    private static boolean isImage(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    /** Поставить свою картинку обоями (имя файла в папке обоев; null — вернуть стандартные). */
    public static void setCustom(@Nullable String fileName) {
        PhoneSettings settings = PhoneSettings.get();
        settings.customWallpaper = fileName;
        settings.save();
        cachedName = null;
        cachedPhoto = null;
    }

    /** Скопировать картинку (например, фото из галереи) в папку обоев и поставить её. Фоновый поток. */
    public static void importAndSet(Path source) {
        ClientFiles.IO.execute(() -> {
            try {
                Files.createDirectories(dir());
                String name = source.getFileName().toString();
                Path target = dir().resolve(name);
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Minecraft.getInstance().execute(() -> setCustom(name));
            } catch (IOException e) {
                BlockPhoneFabric.LOGGER.warn("Failed to set wallpaper {}", source, e);
            }
        });
    }

    private static PhotoStore.@Nullable Photo customPhoto() {
        String name = PhoneSettings.get().customWallpaper;
        if (name == null) {
            return null;
        }
        if (!name.equals(cachedName)) {
            cachedName = name;
            Path file = dir().resolve(name);
            try {
                cachedPhoto = Files.exists(file) ? new PhotoStore.Photo(file, Files.getLastModifiedTime(file).toMillis()) : null;
            } catch (IOException e) {
                cachedPhoto = null;
            }
        }
        return cachedPhoto;
    }

    /** Нарисовать обои в прямоугольник (своя картинка — с обрезкой по центру и лёгким затемнением). */
    public static void draw(GuiGraphics g, int x, int y, int w, int h) {
        PhotoStore.Photo photo = customPhoto();
        if (photo != null) {
            PhotoTextures.Image image = PhotoTextures.preview(photo, 512);
            if (image != null) {
                PhoneDraw.textureCover(g, image.id(), x, y, w, h, image.width(), image.height());
                g.fillGradient(x, y, x + w, y + h, 0x30000000, 0x50000000);
                return;
            }
        }
        int[] wp = PhoneTheme.wallpaper();
        g.fillGradient(x, y, x + w, y + h, wp[0], wp[1]);
    }

    private Wallpapers() {
    }
}
