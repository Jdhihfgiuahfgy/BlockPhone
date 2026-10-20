package dev.blockphone.fabric.client.music.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Выбор декодера по содержимому файла (подпись в начале), а если не понятно — по расширению. */
public final class Decoders {
    /** Расширения, которые показываются в библиотеке. */
    public static final Set<String> EXTENSIONS = Set.of("mp3", "ogg", "oga", "wav", "aiff", "aif", "au");

    public static boolean supported(Path file) {
        return EXTENSIONS.contains(extension(file));
    }

    public static AudioDecoder open(Path file) throws IOException {
        byte[] head = new byte[12];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        if (n >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
            return new OggDecoder(file);
        }
        if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
            return new WavDecoder(file);
        }
        if (n >= 3 && ((head[0] == 'I' && head[1] == 'D' && head[2] == '3') || ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0))) {
            return new Mp3Decoder(file);
        }
        return switch (extension(file)) {
            case "ogg", "oga" -> new OggDecoder(file);
            case "mp3" -> new Mp3Decoder(file);
            default -> new WavDecoder(file);
        };
    }

    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Decoders() {
    }
}
