package dev.blockphone.fabric.client.telegram;

import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Кодирование «кружочка» в MP4 (H.264, без звука) через JCodec — чистая Java, без нативных библиотек.
 * Кадры — квадрат size×size в ARGB. Не потокобезопасен: один поток кодирования.
 */
public final class VideoNoteEncoder implements AutoCloseable {
    private final SequenceEncoder encoder;
    private final int size;
    private final Picture picture;
    private int frames;

    public VideoNoteEncoder(Path file, int size, int fps) throws IOException {
        this.size = size;
        this.encoder = SequenceEncoder.createSequenceEncoder(file.toFile(), fps);
        this.picture = Picture.create(size, size, ColorSpace.RGB);
    }

    /** Добавить кадр (argb.length = size × size). */
    public void addFrame(int[] argb) throws IOException {
        byte[] data = picture.getPlaneData(0);
        for (int i = 0, o = 0; i < size * size; i++) {
            int c = argb[i];
            data[o++] = (byte) (((c >> 16) & 0xFF) - 128);
            data[o++] = (byte) (((c >> 8) & 0xFF) - 128);
            data[o++] = (byte) ((c & 0xFF) - 128);
        }
        encoder.encodeNativeFrame(picture);
        frames++;
    }

    public int frames() {
        return frames;
    }

    /** Дописать файл. */
    public void finish() throws IOException {
        encoder.finish();
    }

    @Override
    public void close() throws IOException {
        finish();
    }

    /**
     * Квадрат из середины кадра, уменьшенный до size×size (усреднение блоками — без «лесенки»).
     *
     * @param argb пиксели кадра width×height (ARGB построчно)
     */
    public static int[] cropSquare(int[] argb, int width, int height, int size) {
        int side = Math.min(width, height);
        int x0 = (width - side) / 2;
        int y0 = (height - side) / 2;
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            int sy0 = y0 + y * side / size;
            int sy1 = Math.max(sy0 + 1, y0 + (y + 1) * side / size);
            for (int x = 0; x < size; x++) {
                int sx0 = x0 + x * side / size;
                int sx1 = Math.max(sx0 + 1, x0 + (x + 1) * side / size);
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                // Не больше 3×3 отсчётов на пиксель — быстро и достаточно гладко.
                int stepY = Math.max(1, (sy1 - sy0) / 3);
                int stepX = Math.max(1, (sx1 - sx0) / 3);
                for (int sy = sy0; sy < sy1; sy += stepY) {
                    for (int sx = sx0; sx < sx1; sx += stepX) {
                        int c = argb[sy * width + sx];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                out[y * size + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return out;
    }
}
