package dev.blockphone.fabric.client.music.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Теги MP3: ID3v2.3 (Windows-1251, UTF-16, обложка), ID3v2.4, ID3v1. */
class TrackTagsTest {
    @TempDir
    Path dir;

    private static byte[] frame(String id, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body.length >>> 24);
        out.write(body.length >>> 16);
        out.write(body.length >>> 8);
        out.write(body.length);
        out.write(0);
        out.write(0);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] text(int encoding, byte[] value) {
        byte[] body = new byte[value.length + 1];
        body[0] = (byte) encoding;
        System.arraycopy(value, 0, body, 1, value.length);
        return body;
    }

    private static byte[] id3(int version, byte[]... frames) {
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            tag.writeBytes(f);
        }
        int size = tag.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'D', '3', (byte) version, 0, 0});
        out.write((size >> 21) & 0x7F);
        out.write((size >> 14) & 0x7F);
        out.write((size >> 7) & 0x7F);
        out.write(size & 0x7F);
        out.writeBytes(tag.toByteArray());
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00}); // Начало MP3-кадра.
        return out.toByteArray();
    }

    @Test
    void id3v23Cp1251Utf16AndCover() throws Exception {
        byte[] cover = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        ByteArrayOutputStream apic = new ByteArrayOutputStream();
        apic.write(0);
        apic.writeBytes("image/png".getBytes(StandardCharsets.ISO_8859_1));
        apic.write(0);
        apic.write(3);
        apic.writeBytes("desc".getBytes(StandardCharsets.ISO_8859_1));
        apic.write(0);
        apic.writeBytes(cover);
        Path file = dir.resolve("song.mp3");
        Files.write(file, id3(3,
                frame("TIT2", text(0, "Группа крови".getBytes(Charset.forName("windows-1251")))),
                frame("TPE1", text(1, "Кино".getBytes(StandardCharsets.UTF_16))),
                frame("TALB", text(3, "Альбом\0".getBytes(StandardCharsets.UTF_8))),
                frame("APIC", apic.toByteArray())));
        TrackTags.Tags tags = TrackTags.read(file, true);
        assertEquals("Группа крови", tags.title());
        assertEquals("Кино", tags.artist());
        assertEquals("Альбом", tags.album());
        assertArrayEquals(cover, tags.cover());
        assertNull(TrackTags.read(file, false).cover());
    }

    @Test
    void id3v1Fallback() throws Exception {
        byte[] tag = new byte[128];
        tag[0] = 'T';
        tag[1] = 'A';
        tag[2] = 'G';
        byte[] title = "Звезда".getBytes(Charset.forName("windows-1251"));
        System.arraycopy(title, 0, tag, 3, title.length);
        byte[] artist = "Artist".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(artist, 0, tag, 33, artist.length);
        byte[] audio = new byte[500];
        Arrays.fill(audio, (byte) 0);
        byte[] file = new byte[audio.length + tag.length];
        System.arraycopy(tag, 0, file, audio.length, tag.length);
        Path path = dir.resolve("v1.mp3");
        Files.write(path, file);
        TrackTags.Tags tags = TrackTags.read(path, false);
        assertEquals("Звезда", tags.title());
        assertEquals("Artist", tags.artist());
    }

    @Test
    void notMp3GivesEmptyTags() throws Exception {
        Path path = dir.resolve("x.wav");
        Files.write(path, new byte[10]);
        assertEquals("", TrackTags.read(path, true).title());
    }
}
