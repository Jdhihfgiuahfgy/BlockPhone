package dev.blockphone.fabric.client.telegram;

import org.jcodec.api.SequenceEncoder;
import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.codecs.h264.encode.RateControl;
import org.jcodec.codecs.h264.io.model.SliceType;
import org.jcodec.common.model.Size;
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
    private final TargetRate rate;
    private final int size;
    private final Picture picture;
    private int frames;

    public VideoNoteEncoder(Path file, int size, int fps) throws IOException {
        this.size = size;
        this.encoder = SequenceEncoder.createSequenceEncoder(file.toFile(), fps);
        this.rate = new TargetRate(size, fps);
        useRateControl(encoder, rate);
        this.picture = Picture.create(size, size, ColorSpace.RGB);
    }

    /**
     * Целевой поток видео, бит/с: ~2,5 Мбит/с — минута «кружочка» ≈ 19 МБ (было 1 Мбит/с — картинка сильно «мылилась»;
     * стандартный JCodec давал ещё больше).
     */
    static final int BITRATE = 2_500_000;

    /**
     * JCodec по умолчанию почти не ограничивает размер кадра. Подменяем управление качеством: QP кадра подстраивается
     * по размеру прошлого кадра того же типа под {@link #BITRATE} (ключевым кадрам — вчетверо больше).
     */
    private static void useRateControl(SequenceEncoder encoder, RateControl control) throws IOException {
        try {
            java.lang.reflect.Field sinkField = SequenceEncoder.class.getDeclaredField("sink");
            sinkField.setAccessible(true);
            Object sink = sinkField.get(encoder);
            java.lang.reflect.Field videoField = sink.getClass().getDeclaredField("videoEncoder");
            videoField.setAccessible(true);
            videoField.set(sink, new H264Encoder(control));
        } catch (ReflectiveOperationException e) {
            throw new IOException("JCodec: cannot set rate control", e);
        }
    }

    static final class TargetRate implements RateControl {
        private final int frameBits;
        private int qpP = 26;
        private int qpI = 24;
        private boolean key;
        private int bits;

        TargetRate(int size, int fps) {
            this.frameBits = BITRATE / Math.max(1, fps);
        }

        @Override
        public int startPicture(Size size, int maxSize, SliceType type) {
            key = type == SliceType.I;
            bits = 0;
            return key ? qpI : qpP;
        }

        @Override
        public int initialQpDelta() {
            return 0;
        }

        @Override
        public int accept(int mbBits) {
            bits += mbBits;
            return 0;
        }

        /** После кадра: поправить QP следующего кадра того же типа. */
        void frameDone() {
            int target = key ? frameBits * 4 : frameBits;
            int qp = key ? qpI : qpP;
            if (bits > target * 1.15) {
                qp = Math.min(40, qp + (bits > target * 2 ? 2 : 1));
            } else if (bits < target * 0.7) {
                qp = Math.max(18, qp - 1);
            }
            if (key) {
                qpI = qp;
            } else {
                qpP = qp;
            }
        }
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
        rate.frameDone();
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
