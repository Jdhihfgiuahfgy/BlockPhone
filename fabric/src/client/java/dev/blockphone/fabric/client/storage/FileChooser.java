package dev.blockphone.fabric.client.storage;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.PhotoMeta;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.Picture;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Системное окно «Открыть файл» (tinyfd из LWJGL — есть в игре) для картинок с компьютера: фото в чат, пост, баннер,
 * стикер, Telegram. Окно открывается в своём потоке — игра не замирает. Выбранные картинки копируются в галерею телефона
 * (PNG + сведения «импортировано»), дальше они работают как обычные снимки.
 */
public final class FileChooser {
    private static final String[] IMAGE_PATTERNS = {"*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"};
    /** Самая длинная сторона импортированной картинки (больше всё равно уменьшится при отправке). */
    private static final int MAX_SIDE = 4096;
    private static final AtomicBoolean OPEN = new AtomicBoolean();

    /** Открыто ли окно выбора сейчас. */
    public static boolean busy() {
        return OPEN.get();
    }

    /**
     * Выбрать картинки и импортировать их в галерею. done — на потоке рендера: список снимков (пустой — отмена) и текст
     * ошибки (null — без ошибок).
     */
    public static void pickImages(String title, boolean multiple, java.util.function.BiConsumer<List<PhotoStore.Photo>, String> done) {
        if (!OPEN.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            List<PhotoStore.Photo> photos = new ArrayList<>();
            String error = null;
            try {
                for (Path path : open(title, multiple)) {
                    try {
                        photos.add(importImage(path));
                    } catch (IOException | RuntimeException e) {
                        BlockPhoneFabric.LOGGER.warn("Cannot import image {}", path, e);
                        error = path.getFileName() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }
            } catch (Throwable e) {
                BlockPhoneFabric.LOGGER.warn("File dialog failed", e);
                error = e.toString();
            } finally {
                OPEN.set(false);
            }
            String result = error;
            Minecraft.getInstance().execute(() -> done.accept(photos, result));
        }, "BlockPhone File Dialog");
        thread.setDaemon(true);
        thread.start();
    }

    /** То же для одной картинки. */
    public static void pickImage(String title, Consumer<PhotoStore.Photo> onPhoto, Consumer<String> onError) {
        pickImages(title, false, (photos, error) -> {
            if (error != null) {
                onError.accept(error);
            } else if (!photos.isEmpty()) {
                onPhoto.accept(photos.get(0));
            }
        });
    }

    private static List<Path> open(String title, boolean multiple) {
        Path start = Path.of(System.getProperty("user.home", "."));
        Path pictures = start.resolve("Pictures");
        if (Files.isDirectory(pictures)) {
            start = pictures;
        }
        String result;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer patterns = stack.mallocPointer(IMAGE_PATTERNS.length);
            for (String pattern : IMAGE_PATTERNS) {
                patterns.put(stack.UTF8(pattern));
            }
            patterns.flip();
            result = TinyFileDialogs.tinyfd_openFileDialog(title, start.toAbsolutePath() + java.io.File.separator, patterns,
                    "PNG, JPEG, GIF, BMP, WEBP", multiple);
        }
        List<Path> paths = new ArrayList<>();
        if (result == null || result.isBlank()) {
            return paths;
        }
        for (String part : result.split("\\|")) {
            if (!part.isBlank()) {
                paths.add(Path.of(part));
            }
        }
        return paths;
    }

    /** Картинка с диска → снимок галереи (фоновый поток). */
    public static PhotoStore.Photo importImage(Path file) throws IOException {
        if (Files.size(file) > 40L << 20) {
            throw new IOException("file is too big (max 40 MB)");
        }
        Picture picture = ImageOps.read(file);
        if (picture.width() > MAX_SIDE || picture.height() > MAX_SIDE) {
            picture = ImageOps.downscale(picture, MAX_SIDE, MAX_SIDE);
        }
        PhotoMeta meta = new PhotoMeta();
        meta.takenAt = System.currentTimeMillis();
        meta.importedFrom = file.getFileName().toString();
        return PhotoStore.save(picture, meta, PhotoStore.newName("_PC"));
    }

    private FileChooser() {
    }
}
