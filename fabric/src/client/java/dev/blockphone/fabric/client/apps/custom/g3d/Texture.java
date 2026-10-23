package dev.blockphone.fabric.client.apps.custom.g3d;

/**
 * Текстура для {@link Engine3D}: ARGB-пиксели и уменьшенные копии (мипмапы — вдали текстура не «рябит»).
 * linear — сглаживание (билинейная фильтрация), иначе — чёткие пиксели; clamp — не повторять за краем (карта освещения).
 */
public final class Texture {
    final int[][] levels;
    final int[] widths;
    final int[] heights;
    final boolean linear;
    final boolean clamp;
    /** Есть пиксели с прозрачностью &lt; 128 (вырез). */
    final boolean cutout;

    public Texture(int w, int h, int[] argb, boolean linear, boolean mipmap) {
        this(w, h, argb, linear, mipmap, false);
    }

    Texture(int w, int h, int[] argb, boolean linear, boolean mipmap, boolean clamp) {
        if (w <= 0 || h <= 0 || argb.length < w * h) {
            throw new IllegalArgumentException("bad texture size");
        }
        this.linear = linear;
        this.clamp = clamp;
        boolean cut = false;
        for (int i = 0; i < w * h; i++) {
            if ((argb[i] >>> 24) < 128) {
                cut = true;
                break;
            }
        }
        cutout = cut;
        int count = 1;
        if (mipmap) {
            for (int s = Math.max(w, h); s > 1; s >>= 1) {
                count++;
            }
        }
        levels = new int[count][];
        widths = new int[count];
        heights = new int[count];
        levels[0] = argb;
        widths[0] = w;
        heights[0] = h;
        for (int l = 1; l < count; l++) {
            int pw = widths[l - 1], ph = heights[l - 1];
            int nw = Math.max(1, pw / 2), nh = Math.max(1, ph / 2);
            int[] src = levels[l - 1];
            int[] dst = new int[nw * nh];
            for (int y = 0; y < nh; y++) {
                int y0 = Math.min(ph - 1, y * 2), y1 = Math.min(ph - 1, y * 2 + 1);
                for (int x = 0; x < nw; x++) {
                    int x0 = Math.min(pw - 1, x * 2), x1 = Math.min(pw - 1, x * 2 + 1);
                    dst[y * nw + x] = average(src[y0 * pw + x0], src[y0 * pw + x1], src[y1 * pw + x0], src[y1 * pw + x1]);
                }
            }
            levels[l] = dst;
            widths[l] = nw;
            heights[l] = nh;
        }
    }

    public int width() {
        return widths[0];
    }

    public int height() {
        return heights[0];
    }

    /** Среднее четырёх пикселей; цвет — с учётом прозрачности (края вырезанных спрайтов не темнеют). */
    private static int average(int a, int b, int c, int d) {
        int aa = a >>> 24, ab = b >>> 24, ac = c >>> 24, ad = d >>> 24;
        int at = aa + ab + ac + ad;
        int alpha = (at + 2) / 4;
        if (at == 0) {
            return 0;
        }
        int r = (((a >> 16) & 255) * aa + ((b >> 16) & 255) * ab + ((c >> 16) & 255) * ac + ((d >> 16) & 255) * ad) / at;
        int g = (((a >> 8) & 255) * aa + ((b >> 8) & 255) * ab + ((c >> 8) & 255) * ac + ((d >> 8) & 255) * ad) / at;
        int bl = ((a & 255) * aa + (b & 255) * ab + (c & 255) * ac + (d & 255) * ad) / at;
        return alpha << 24 | r << 16 | g << 8 | bl;
    }

    /** Пиксель уровня level в точке (u, v) (0..1 — вся текстура). */
    int sample(float u, float v, int level) {
        if (level >= levels.length) {
            level = levels.length - 1;
        }
        int w = widths[level], h = heights[level];
        int[] p = levels[level];
        if (!linear) {
            return p[wrap((int) Math.floor(u * w), w) + wrap((int) Math.floor(v * h), h) * w];
        }
        float fx = u * w - 0.5f, fy = v * h - 0.5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        int tx = (int) ((fx - x0) * 256), ty = (int) ((fy - y0) * 256);
        int xa = wrap(x0, w), xb = wrap(x0 + 1, w), ya = wrap(y0, h) * w, yb = wrap(y0 + 1, h) * w;
        return lerp(lerp(p[ya + xa], p[ya + xb], tx), lerp(p[yb + xa], p[yb + xb], tx), ty);
    }

    private int wrap(int i, int n) {
        if ((n & (n - 1)) == 0 && !clamp) {
            return i & (n - 1);
        }
        if (clamp) {
            return i < 0 ? 0 : i >= n ? n - 1 : i;
        }
        i %= n;
        return i < 0 ? i + n : i;
    }

    /** Смешать два ARGB: t — 0..256. */
    static int lerp(int a, int b, int t) {
        if (a == b) {
            return a;
        }
        int s = 256 - t;
        int ag = (((a >>> 8) & 0x00FF00FF) * s + ((b >>> 8) & 0x00FF00FF) * t) & 0xFF00FF00;
        int rb = ((a & 0x00FF00FF) * s + (b & 0x00FF00FF) * t) >>> 8 & 0x00FF00FF;
        return ag | rb;
    }
}
