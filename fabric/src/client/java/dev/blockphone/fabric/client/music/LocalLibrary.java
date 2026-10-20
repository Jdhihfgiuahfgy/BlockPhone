package dev.blockphone.fabric.client.music;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.music.audio.AudioDecoder;
import dev.blockphone.fabric.client.music.audio.Decoders;
import dev.blockphone.fabric.client.music.audio.TrackTags;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Своя музыка: файлы из {@code blockphone/music/} (и подпапок). Теги и длительность читаются в фоне и
 * запоминаются по времени изменения файла, поэтому повторное сканирование быстрое.
 */
public final class LocalLibrary {
    private record Cached(long modified, Track track) {
    }

    private static final Map<Path, Cached> CACHE = new HashMap<>();
    private static List<Track> tracks = List.of();
    private static boolean scanning;
    private static boolean scanned;

    public static Path dir() {
        return ClientFiles.root().resolve("music");
    }

    public static List<Track> tracks() {
        return tracks;
    }

    public static boolean scanned() {
        return scanned;
    }

    public static boolean scanning() {
        return scanning;
    }

    /** Пересканировать папку в фоне. */
    public static void rescan() {
        if (scanning) {
            return;
        }
        scanning = true;
        Map<Path, Cached> known = new HashMap<>(CACHE);
        ClientFiles.IO.execute(() -> {
            Map<Path, Cached> found = new HashMap<>();
            try {
                Files.createDirectories(dir());
                try (Stream<Path> files = Files.walk(dir(), 4)) {
                    files.filter(Files::isRegularFile).filter(Decoders::supported).forEach(file -> {
                        try {
                            long modified = Files.getLastModifiedTime(file).toMillis();
                            Cached cached = known.get(file);
                            found.put(file, cached != null && cached.modified() == modified ? cached : new Cached(modified, read(file)));
                        } catch (IOException ignored) {
                        }
                    });
                }
            } catch (IOException e) {
                BlockPhoneFabric.LOGGER.warn("Failed to scan music folder", e);
            }
            List<Track> list = new ArrayList<>();
            found.values().forEach(c -> list.add(c.track()));
            list.sort(Comparator.comparing((Track t) -> t.artist().toLowerCase(Locale.ROOT))
                    .thenComparing(t -> t.title().toLowerCase(Locale.ROOT)));
            Minecraft.getInstance().execute(() -> {
                CACHE.clear();
                CACHE.putAll(found);
                tracks = List.copyOf(list);
                scanning = false;
                scanned = true;
            });
        });
    }

    /** Трек из файла: теги (или имя файла) и длительность. */
    static Track read(Path file) {
        TrackTags.Tags tags = TrackTags.read(file, false);
        String title = tags.title();
        String artist = tags.artist();
        if (title.isEmpty()) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            name = dot > 0 ? name.substring(0, dot) : name;
            // «Исполнитель - Название.mp3»
            int dash = name.indexOf(" - ");
            if (artist.isEmpty() && dash > 0) {
                artist = name.substring(0, dash).strip();
                name = name.substring(dash + 3);
            }
            title = name.replace('_', ' ').strip();
        }
        long duration = 0;
        try (AudioDecoder decoder = Decoders.open(file)) {
            duration = Math.max(0, decoder.durationMs());
        } catch (Exception ignored) {
            // Не читается — покажем без длительности; при воспроизведении будет ошибка.
        }
        return new Track(file.toString(), Track.Source.LOCAL, title, artist, tags.album(), duration, file, null, null);
    }

    private LocalLibrary() {
    }
}
