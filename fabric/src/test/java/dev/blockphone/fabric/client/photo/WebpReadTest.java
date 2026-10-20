package dev.blockphone.fabric.client.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WEBP (стикеры Telegram) читается через ImageOps: со сжатием и без, прозрачность сохраняется. */
class WebpReadTest {
    @TempDir
    Path dir;

    private Picture read(String name) throws Exception {
        // Имя без расширения — как у файлов TDLib: формат определяется по содержимому.
        Path file = dir.resolve(name.replace(".webp", ""));
        try (InputStream in = WebpReadTest.class.getResourceAsStream("/webp/" + name)) {
            Files.copy(in, file);
        }
        return ImageOps.read(file);
    }

    @Test
    void lossless() throws Exception {
        Picture p = read("lossless.webp");
        assertEquals(24, p.width());
        assertEquals(16, p.height());
        assertEquals(0xFFFF0000, p.get(8, 8));
        assertEquals(0, p.get(0, 0) >>> 24, "transparent corner");
        assertEquals(128, p.get(19, 8) >>> 24, "half transparent");
    }

    @Test
    void lossy() throws Exception {
        Picture p = read("lossy.webp");
        assertEquals(24, p.width());
        int c = p.get(8, 8);
        assertTrue(((c >> 16) & 255) > 200 && (c & 255) < 60, Integer.toHexString(c));
        assertTrue((p.get(0, 0) >>> 24) < 40, "transparent corner");
    }
}
