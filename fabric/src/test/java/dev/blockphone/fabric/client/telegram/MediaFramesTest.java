package dev.blockphone.fabric.client.telegram;

import dev.blockphone.fabric.client.photo.Picture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GIF/«кружочек»: MP4 декодируется в уменьшенные кадры с верными цветами, лишние кадры пропускаются. */
class MediaFramesTest {
    @TempDir
    Path dir;

    @Test
    void decodesAndThinsFrames() throws Exception {
        Path file = dir.resolve("clip.mp4");
        int size = 64;
        try (VideoNoteEncoder encoder = new VideoNoteEncoder(file, size, 30)) {
            for (int f = 0; f < 30; f++) {
                int[] frame = new int[size * size];
                java.util.Arrays.fill(frame, f % 2 == 0 ? 0xFFE03020 : 0xFF2040E0);
                encoder.addFrame(frame);
            }
        }
        MediaFrames.Decoded d = MediaFrames.decode(file, 32, 15, 8);
        // 1 с при 30 к/с → около 15 кадров при 15 к/с.
        assertTrue(d.frames().size() >= 13 && d.frames().size() <= 16, "frames " + d.frames().size());
        Picture first = d.frames().get(0);
        assertEquals(32, first.width());
        int c = first.get(16, 16);
        assertTrue(((c >> 16) & 255) > 180 && (c & 255) < 90, Integer.toHexString(c));
        assertEquals(66, d.frameMs());
    }
}
