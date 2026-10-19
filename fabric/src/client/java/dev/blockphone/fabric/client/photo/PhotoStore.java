package dev.blockphone.fabric.client.photo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.storage.ClientFiles;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Галерея на диске: {@code blockphone/gallery/IMG_yyyyMMdd_HHmmss.png} + {@code .json} со сведениями.
 * Файлы можно класть и удалять вручную — список перечитывается.
 */
public final class PhotoStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    /** Увеличивается при каждом изменении галереи — по нему приложения понимают, что список устарел. */
    private static volatile int version;

    public record Photo(Path file, long modified) {
        public String name() {
            String fileName = file.getFileName().toString();
            return fileName.substring(0, fileName.length() - 4);
        }

        public Path metaFile() {
            return file.resolveSibling(name() + ".json");
        }
    }

    public static int version() {
        return version;
    }

    /** Фото, новые сверху. Читает диск — вызывать из фонового потока или изредка. */
    public static List<Photo> list() {
        Path dir = ClientFiles.gallery();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Photo> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).forEach(p -> {
                try {
                    result.add(new Photo(p, Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("Failed to list gallery", e);
        }
        result.sort(Comparator.comparingLong(Photo::modified).reversed().thenComparing(p -> p.file().getFileName().toString(),
                Comparator.reverseOrder()));
        return result;
    }

    public static @Nullable PhotoMeta readMeta(Photo photo) {
        Path meta = photo.metaFile();
        if (!Files.exists(meta)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, PhotoMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Свободное имя файла (без расширения) для нового снимка. */
    public static String newName(String suffix) {
        return uniqueName("IMG_" + LocalDateTime.now().format(NAME_FORMAT) + suffix);
    }

    /** base, а если занято — base_2, base_3... */
    public static String uniqueName(String base) {
        String name = base;
        for (int i = 2; Files.exists(ClientFiles.gallery().resolve(name + ".png")); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    /** Сохраняет PNG и сведения. Вызывать из фонового потока. */
    public static Photo save(Picture picture, PhotoMeta meta, String name) throws IOException {
        Path file = ClientFiles.gallery().resolve(name + ".png");
        meta.width = picture.width();
        meta.height = picture.height();
        ImageOps.writePng(picture, file);
        Photo photo = new Photo(file, System.currentTimeMillis());
        try (Writer writer = Files.newBufferedWriter(photo.metaFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(meta, writer);
        }
        version++;
        return photo;
    }

    /** Удаляет фото и его сведения насовсем. */
    public static void delete(Photo photo) {
        try {
            Files.deleteIfExists(photo.file());
            Files.deleteIfExists(photo.metaFile());
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("Failed to delete {}", photo.file(), e);
        }
        version++;
    }

    private PhotoStore() {
    }
}
