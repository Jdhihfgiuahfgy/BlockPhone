package dev.blockphone.fabric.client.music.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Что за файл на самом деле: MP3 или что-то другое (для понятной ошибки вместо «не удалось декодировать»). */
public final class AudioSniffer {

    /** null — похоже на MP3; иначе — описание, что это (для журнала). */
    public static String problem(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(4096);
        }
        return problem(head, Files.size(file));
    }

    static String problem(byte[] head, long size) {
        if (size == 0 || head.length == 0) {
            return "empty file";
        }
        int offset = 0;
        if (head.length >= 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            // ID3v2: размер тега — 4 байта по 7 бит. Сам тег может быть больше прочитанного начала — тогда верим заголовку.
            int tagSize = (head[6] & 0x7F) << 21 | (head[7] & 0x7F) << 14 | (head[8] & 0x7F) << 7 | (head[9] & 0x7F);
            offset = 10 + tagSize;
            if (offset >= head.length) {
                return offset < size ? null : "ID3 tag without audio";
            }
        }
        // Ищем кадр MPEG Layer III недалеко от начала (бывает мусор/выравнивание).
        for (int i = offset; i + 1 < Math.min(head.length, offset + 2048); i++) {
            int b0 = head[i] & 0xFF;
            int b1 = head[i + 1] & 0xFF;
            if (b0 == 0xFF && (b1 & 0xE0) == 0xE0) {
                int layer = (b1 >> 1) & 3;
                if (layer == 1) {
                    return null; // Layer III — MP3
                }
                if (layer == 0) {
                    return "AAC (ADTS), not MP3";
                }
            }
        }
        if (head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return "MP4/M4A container, not MP3";
        }
        if (head.length >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
            return "FLAC, not MP3";
        }
        String text = new String(head, 0, Math.min(head.length, 300), StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
        if (text.startsWith("<") || text.startsWith("{")) {
            return "server sent text instead of audio: " + text;
        }
        return "unknown data, " + size + " bytes";
    }

    private AudioSniffer() {
    }
}
