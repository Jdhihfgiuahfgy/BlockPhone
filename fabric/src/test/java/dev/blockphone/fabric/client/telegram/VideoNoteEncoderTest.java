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
