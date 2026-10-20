package dev.blockphone.fabric.client.photo;

/**
 * Стикер из фото: квадрат по центру (со сдвигом и приближением), форма (квадрат, скруглённый, круг) с прозрачным фоном
 * вокруг и, по желанию, белая обводка по краю формы.
 */
public final class StickerMaker {
    public enum Shape { SQUARE, ROUNDED, CIRCLE }

    /**
     * @param zoom    1 — весь короткий край фото, 2 — вдвое ближе
     * @param offsetX сдвиг центра по горизонтали, −1..1 (от края до края)
     * @param offsetY сдвиг центра по вертикали, −1..1
     */
    public static Picture make(Picture src, int side, Shape shape, boolean outline, float zoom, float offsetX, float offsetY) {
        int crop = Math.max(1, Math.round(Math.min(src.width(), src.height()) / Math.max(1f, zoom)));
        int cx = Math.round(src.width() / 2f + offsetX * (src.width() - crop) / 2f);
        int cy = Math.round(src.height() / 2f + offsetY * (src.height() - crop) / 2f);
        int x0 = Math.max(0, Math.min(src.width() - crop, cx - crop / 2));
        int y0 = Math.max(0, Math.min(src.height() - crop, cy - crop / 2));
        Picture square = ImageOps.crop(src, x0, y0, crop, crop);
        Picture scaled = crop > side ? ImageOps.downscale(square, side, side) : square;
        int n = scaled.width();
        int[] out = new int[n * n];
        float border = outline ? Math.max(2f, n * 0.035f) : 0f;
        float radius = shape == Shape.CIRCLE ? n / 2f : shape == Shape.ROUNDED ? n * 0.18f : 0f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float d = inside(x + 0.5f, y + 0.5f, n, shape, radius); // расстояние до края формы (>0 — внутри)
                if (d <= 0) {
                    continue;
                }
                int argb = scaled.get(x, y) | 0xFF000000;
                if (d < border) {
                    argb = 0xFFFFFFFF;
                }
                // Сглаживание края: последний пиксель полупрозрачный.
                int alpha = d >= 1 ? 255 : Math.round(d * 255);
                out[y * n + x] = (alpha << 24) | (argb & 0xFFFFFF);
            }
        }
        return new Picture(n, n, out);
    }

    /** Расстояние от точки до края формы внутрь (отрицательное — снаружи). */
    private static float inside(float x, float y, int n, Shape shape, float r) {
        float edge = Math.min(Math.min(x, n - x), Math.min(y, n - y));
        if (shape == Shape.SQUARE || r <= 0) {
            return edge;
        }
        // Скругление: в углах — расстояние до дуги.
        float cx = Math.max(r, Math.min(n - r, x));
        float cy = Math.max(r, Math.min(n - r, y));
        float dx = x - cx;
        float dy = y - cy;
        if (dx == 0 && dy == 0) {
            return Math.max(edge, 0);
        }
        float toArc = r - (float) Math.sqrt(dx * dx + dy * dy);
        return Math.min(edge, toArc);
    }

    private StickerMaker() {
    }
}
