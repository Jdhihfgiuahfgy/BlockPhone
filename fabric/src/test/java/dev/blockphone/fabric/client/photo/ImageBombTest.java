package dev.blockphone.fabric.client.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Картинка, объявляющая огромный размер, отклоняется до раскодирования (не роняет игру нехваткой памяти). */
class ImageBombTest {
    @TempDir
    Path dir;

    /** PNG 1×1, в заголовке которого записан размер w×h (CRC заголовка пересчитан). */
    private static byte[] pngClaiming(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] png = out.toByteArray();
        // IHDR: 8 байт подписи + 4 длины + 4 «IHDR» → ширина с 16, высота с 20, CRC с 29.
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(png);
        b.putInt(16, w);
        b.putInt(20, h);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(png, 12, 17);
        b.putInt(29, (int) crc.getValue());
        return png;
    }

    @Test
    void hugeDeclaredSizeIsRejected() throws Exception {
        byte[] bomb = pngClaiming(60000, 60000);
        assertThrows(IOException.class, () -> ImageOps.checkSize(bomb, "bomb"));
        assertThrows(IOException.class, () -> ImageOps.readImage(bomb, "bomb"));
        Path file = dir.resolve("bomb.png");
        Files.write(file, bomb);
        assertThrows(IOException.class, () -> ImageOps.read(file));
    }

    @Test
    void normalImageIsRead() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        ImageOps.checkSize(out.toByteArray(), "ok");
        assertEquals(40, ImageOps.readImage(out.toByteArray(), "ok").getWidth());
    }
}
