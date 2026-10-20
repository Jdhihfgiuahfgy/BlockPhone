package dev.blockphone.fabric.client.music.audio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Распознавание «что скачалось»: MP3 или заглушка/другой формат. */
class AudioSnifferTest {

    @Test
    void recognisesMp3AndOthers() {
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64, 0, 0, 0, 0};
        assertNull(AudioSniffer.problem(mp3, 5000));
        byte[] id3 = new byte[64];
        id3[0] = 'I';
        id3[1] = 'D';
        id3[2] = '3';
        id3[9] = 10; // тег 10 байт
        id3[20] = (byte) 0xFF;
        id3[21] = (byte) 0xFB;
        assertNull(AudioSniffer.problem(id3, 5000));
        byte[] adts = {(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80, 0, 0, 0, 0};
        assertTrue(AudioSniffer.problem(adts, 5000).startsWith("AAC"));
        byte[] mp4 = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
        assertTrue(AudioSniffer.problem(mp4, 5000).startsWith("MP4"));
        byte[] html = "<html><body>captcha</body></html>".getBytes(StandardCharsets.UTF_8);
        assertTrue(AudioSniffer.problem(html, html.length).contains("captcha"));
        assertTrue(AudioSniffer.problem(new byte[0], 0).startsWith("empty"));
    }
}
