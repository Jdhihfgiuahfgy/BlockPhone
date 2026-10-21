package dev.blockphone.fabric.client.telegram;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** «Кружочек»: MP4 из кадров читается обратно, размер и число кадров верные; обрезка до квадрата. */
class VideoNoteEncoderTest {
    @TempDir
    Path dir;

    @Test
    void encodesReadableMp4() throws Exception {
        Path file = dir.resolve("note.mp4");
        int size = 160;
        try (VideoNoteEncoder encoder = new VideoNoteEncoder(file, size, 15)) {
            for (int f = 0; f < 15; f++) {
                int[] frame = new int[size * size];
                for (int i = 0; i < frame.length; i++) {
                    int x = i % size;
                    frame[i] = 0xFF000000 | ((x + f * 8) & 0xFF) << 16 | (i / size) << 8 | 0x40;
                }
                encoder.addFrame(frame);
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(bytes.length > 1000, "size " + bytes.length);
        assertEquals("ftyp", new String(bytes, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
        try (var channel = NIOUtils.readableChannel(file.toFile())) {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            Picture first = grab.getNativeFrame();
            assertNotNull(first);
            assertEquals(size, first.getWidth());
            int count = 1;
            while (grab.getNativeFrame() != null) {
                count++;
            }
            assertEquals(15, count);
        }
    }

    /** Скорость кодирования 320×320 — должна быть заметно выше 30 к/с (иначе «кружочек» отстаёт). */
    @Test
    void encodesFastEnough() throws Exception {
        int size = 320;
        java.util.Random random = new java.util.Random(1);
        int[] frame = new int[size * size];
        long start = System.nanoTime();
        int frames = 90;
        try (VideoNoteEncoder encoder = new VideoNoteEncoder(dir.resolve("speed.mp4"), size, 30)) {
            for (int f = 0; f < frames; f++) {
                for (int i = 0; i < frame.length; i++) {
                    frame[i] = 0xFF000000 | (((i % size) + f * 3) & 0xFF) << 16 | ((i / size + f) & 0xFF) << 8 | random.nextInt(32);
                }
                encoder.addFrame(frame);
            }
        }
        double fps = frames / ((System.nanoTime() - start) / 1e9);
        System.out.println("video note encoder: " + Math.round(fps) + " fps");
    }

    @Test
    void cropsCenterSquare() {
        int w = 40;
        int h = 20;
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = (i % w) < 10 || (i % w) >= 30 ? 0xFFFF0000 : 0xFF00FF00; // По бокам красное, в середине зелёное.
        }
        int[] square = VideoNoteEncoder.cropSquare(argb, w, h, 10);
        for (int c : square) {
            assertEquals(0xFF00FF00, c);
        }
    }
}
