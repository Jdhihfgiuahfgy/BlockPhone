package dev.blockphone.fabric.client.photo;

import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Обработка изображений на CPU (фильтры, экспозиция, шум, поворот, кадрирование, масштаб, файлы). */
public final class ImageOps {
    static {
        // WEBP (стикеры Telegram) — плагин TwelveMonkeys; регистрируем сами: ImageIO может не найти службы из jar-ов мода.
        try {
            javax.imageio.spi.IIORegistry.getDefaultInstance()
                    .registerServiceProvider(new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
        } catch (Throwable e) {
            dev.blockphone.fabric.BlockPhoneFabric.LOGGER.warn("WEBP support is unavailable", e);
        }
    }


    // --- Цвет ---

    /** Та же формула, что в shaders/post/filter.fsh. */
    public static Picture applyFilter(Picture src, CameraFilter filter) {
        if (filter == CameraFilter.NONE) {
            return src;
        }
        CameraFilter.Params p = filter.params();
        int w = src.width();
        int h = src.height();
        int[] out = new int[src.argb().length];
        float cx = w / 2f;
        float cy = h / 2f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = src.argb()[y * w + x];
                float r = ((c >> 16) & 255) / 255f;
                float g = ((c >> 8) & 255) / 255f;
                float b = (c & 255) / 255f;
                float mr = p.rowR()[0] * r + p.rowR()[1] * g + p.rowR()[2] * b + p.rowR()[3];
                float mg = p.rowG()[0] * r + p.rowG()[1] * g + p.rowG()[2] * b + p.rowG()[3];
                float mb = p.rowB()[0] * r + p.rowB()[1] * g + p.rowB()[2] * b + p.rowB()[3];
                float lum = 0.299f * mr + 0.587f * mg + 0.114f * mb;
                mr = lum + (mr - lum) * p.saturation();
                mg = lum + (mg - lum) * p.saturation();
                mb = lum + (mb - lum) * p.saturation();
                mr = (mr - 0.5f) * p.contrast() + 0.5f;
                mg = (mg - 0.5f) * p.contrast() + 0.5f;
                mb = (mb - 0.5f) * p.contrast() + 0.5f;
                if (p.vignette() > 0) {
                    float dx = (x + 0.5f - cx) / w;
                    float dy = (y + 0.5f - cy) / h;
                    float d = (float) Math.sqrt(dx * dx + dy * dy) * 1.41421f;
                    float v = 1f - p.vignette() * smoothstep(0.35f, 1f, d);
                    mr *= v;
                    mg *= v;
                    mb *= v;
                }
                out[y * w + x] = rgb(mr, mg, mb);
            }
        }
        return new Picture(w, h, out);
    }

    /** Экспозиция в ступенях (+1 = в 2 раза светлее), в линейном пространстве. */
    public static Picture exposure(Picture src, float stops) {
        if (Math.abs(stops) < 0.01f) {
            return src;
        }
        double factor = Math.pow(2, stops);
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            double linear = Math.pow(i / 255.0, 2.2) * factor;
            lut[i] = clamp255((int) Math.round(Math.pow(Math.min(1.0, linear), 1 / 2.2) * 255));
        }
        return mapChannels(src, lut, lut, lut);
    }

    /** Шум матрицы (как на высоком ISO). amount 0..1. */
    public static Picture noise(Picture src, float amount, long seed) {
        if (amount <= 0.001f) {
            return src;
        }
        Random random = new Random(seed);
        int[] out = src.argb().clone();
        float strength = amount * 90f;
        for (int i = 0; i < out.length; i++) {
            int c = out[i];
            float n = (float) random.nextGaussian() * strength;
            float chroma = strength * 0.25f;
            int r = clamp255((int) (((c >> 16) & 255) + n + (float) random.nextGaussian() * chroma));
            int g = clamp255((int) (((c >> 8) & 255) + n));
            int b = clamp255((int) ((c & 255) + n + (float) random.nextGaussian() * chroma));
            out[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        return new Picture(src.width(), src.height(), out);
    }

    /**
     * Ручная коррекция из редактора. brightness/contrast/saturation: -1..1 (0 — без изменений).
     */
    public static Picture adjust(Picture src, float brightness, float contrast, float saturation) {
        if (brightness == 0 && contrast == 0 && saturation == 0) {
            return src;
        }
        float br = brightness * 0.35f;
        float ct = 1f + contrast * (contrast > 0 ? 1.0f : 0.7f);
        float st = 1f + saturation;
        int[] out = new int[src.argb().length];
        for (int i = 0; i < out.length; i++) {
            int c = src.argb()[i];
            float r = ((c >> 16) & 255) / 255f + br;
            float g = ((c >> 8) & 255) / 255f + br;
            float b = (c & 255) / 255f + br;
            float lum = 0.299f * r + 0.587f * g + 0.114f * b;
            r = lum + (r - lum) * st;
            g = lum + (g - lum) * st;
            b = lum + (b - lum) * st;
            r = (r - 0.5f) * ct + 0.5f;
            g = (g - 0.5f) * ct + 0.5f;
            b = (b - 0.5f) * ct + 0.5f;
            out[i] = rgb(r, g, b);
        }
        return new Picture(src.width(), src.height(), out);
    }

    // --- Геометрия ---

    /** Поворот на 90° по часовой стрелке. */
    public static Picture rotateClockwise(Picture src) {
        int w = src.width();
        int h = src.height();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[x * h + (h - 1 - y)] = src.argb()[y * w + x];
            }
        }
        return new Picture(h, w, out);
    }

    public static Picture flipHorizontal(Picture src) {
        int w = src.width();
        int h = src.height();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + (w - 1 - x)] = src.argb()[y * w + x];
            }
        }
        return new Picture(w, h, out);
    }

    /** Обрезка по центру до пропорций aspect = ширина/высота; aspect <= 0 — без обрезки. */
    public static Picture cropToAspect(Picture src, float aspect) {
        if (aspect <= 0) {
            return src;
        }
        int w = src.width();
        int h = src.height();
        int cw = w;
        int ch = Math.round(w / aspect);
        if (ch > h) {
            ch = h;
            cw = Math.round(h * aspect);
        }
        return crop(src, (w - cw) / 2, (h - ch) / 2, cw, ch);
    }

    public static Picture crop(Picture src, int x0, int y0, int cw, int ch) {
        int[] out = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            System.arraycopy(src.argb(), (y0 + y) * src.width() + x0, out, y * cw, cw);
        }
        return new Picture(cw, ch, out);
    }

    /** Уменьшение усреднением блоков (качественно для миниатюр). Не увеличивает. */
    public static Picture downscale(Picture src, int maxWidth, int maxHeight) {
        float k = Math.min(1f, Math.min(maxWidth / (float) src.width(), maxHeight / (float) src.height()));
        if (k >= 1f) {
            return src;
        }
        int w = Math.max(1, Math.round(src.width() * k));
        int h = Math.max(1, Math.round(src.height() * k));
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int sy0 = y * src.height() / h;
            int sy1 = Math.max(sy0 + 1, (y + 1) * src.height() / h);
            for (int x = 0; x < w; x++) {
                int sx0 = x * src.width() / w;
                int sx1 = Math.max(sx0 + 1, (x + 1) * src.width() / w);
                // Цвет усредняется с весом прозрачности (у стикеров края прозрачные), альфа — просто среднее.
                long r = 0, g = 0, b = 0, a = 0;
                int n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int c = src.argb()[sy * src.width() + sx];
                        int alpha = (c >>> 24) & 255;
                        r += (long) ((c >> 16) & 255) * alpha;
                        g += (long) ((c >> 8) & 255) * alpha;
                        b += (long) (c & 255) * alpha;
                        a += alpha;
                        n++;
                    }
                }
                out[y * w + x] = a == 0 ? 0 : (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return new Picture(w, h, out);
    }

    /** Квадратная миниатюра: обрезка по центру + уменьшение. */
    public static Picture squareThumbnail(Picture src, int size) {
        return downscale(cropToAspect(src, 1f), size, size);
    }

    // --- Файлы и текстуры ---

    public static Picture read(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("Unsupported image " + file);
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        if (!image.getColorModel().hasAlpha()) {
            for (int i = 0; i < argb.length; i++) {
                argb[i] |= 0xFF000000;
            }
        }
        return new Picture(w, h, argb);
    }

    public static void writePng(Picture picture, Path file) throws IOException {
        BufferedImage image = new BufferedImage(picture.width(), picture.height(), BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, picture.width(), picture.height(), picture.argb(), 0, picture.width());
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        if (!ImageIO.write(image, "png", tmp.toFile())) {
            throw new IOException("No PNG writer");
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Новая NativeImage для текстуры (вызывающий отвечает за close()). */
    public static NativeImage toNativeImage(Picture picture) {
        NativeImage image = new NativeImage(picture.width(), picture.height(), false);
        for (int y = 0; y < picture.height(); y++) {
            for (int x = 0; x < picture.width(); x++) {
                image.setPixel(x, y, picture.get(x, y)); // прозрачность сохраняется (стикеры)
            }
        }
        return image;
    }

    // --- Вспомогательное ---

    private static Picture mapChannels(Picture src, int[] lutR, int[] lutG, int[] lutB) {
        int[] out = new int[src.argb().length];
        for (int i = 0; i < out.length; i++) {
            int c = src.argb()[i];
            out[i] = 0xFF000000 | lutR[(c >> 16) & 255] << 16 | lutG[(c >> 8) & 255] << 8 | lutB[c & 255];
        }
        return new Picture(src.width(), src.height(), out);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    private static int rgb(float r, float g, float b) {
        return 0xFF000000 | clamp255(Math.round(r * 255)) << 16 | clamp255(Math.round(g * 255)) << 8 | clamp255(Math.round(b * 255));
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private ImageOps() {
    }
}
