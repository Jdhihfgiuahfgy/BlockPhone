package dev.blockphone.fabric.client.apps.custom.g3d;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 3D для скриптов приложений: программная растеризация на Java (одинаково на любой версии игры и видеокарте, скрипт не
 * получает доступа к графике игры). Кадр рисуется на всех ядрах процессора: треугольники собираются в очередь и
 * раскладываются по полосам экрана, каждую полосу растеризует свой поток (порядок треугольников в полосе сохраняется).
 * <p>
 * Возможности: текстуры с перспективной коррекцией, мипмапы и сглаживанием; буфер глубины; прозрачность «вырезом»,
 * смешиванием и сложением (свечение); цветной свет: окружающий + солнце (по граням или по нормалям вершин), точечные
 * источники попиксельно, карта освещения статической сцены (тени и затенение углов, см. {@link Scene}); туман; небо
 * (градиент и солнце); спрайты; буфер id для выбора объекта под прицелом.
 * Мир: Y — вверх; yaw 0 смотрит вдоль +Z, yaw 90 — вдоль +X; pitch &gt; 0 — вверх.
 */
public final class Engine3D {
    public static final float NEAR = 0.05f;
    /** id объекта, который рисуется, но не попадает в pick (виден, но «прозрачен» для выстрелов). */
    public static final int NO_PICK = Integer.MIN_VALUE;
    public static final int BLEND_NONE = 0, BLEND_ALPHA = 1, BLEND_ADD = 2;
    public static final int MAX_LIGHTS = 16;

    static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    private static final ExecutorService POOL = Executors.newFixedThreadPool(Math.max(1, THREADS - 1), r -> {
        Thread t = new Thread(r, "BlockPhone 3D");
        t.setDaemon(true);
        return t;
    });
    private static final int BAND = 16;

    public int w;
    public int h;
    public int[] color = new int[0];
    float[] depth = new float[0];
    public int[] ids = new int[0];
    /** Глубина того, что видит pick (без объектов с NO_PICK: дым не останавливает пули). */
    public float[] pickDepth = new float[0];
    public int triangles;
    /** Время растеризации кадра, мс. */
    public float rasterMs;

    // Камера.
    public float camX, camY, camZ, yaw, pitch, fov = 70;
    float rX, rY, rZ, uX, uY, uZ, fX, fY, fZ, focal;
    // Свет: направление лучей солнца, цвет солнца и окружающего света (1 — как есть).
    public float lightX = -0.4f, lightY = -1f, lightZ = -0.3f;
    public float sunR = 0.55f, sunG = 0.55f, sunB = 0.55f;
    public float ambR = 0.45f, ambG = 0.45f, ambB = 0.45f;
    public int fogColor;
    public float fogNear, fogFar;
    // Небо: null — заливка цветом begin.
    public int[] sky;
    public boolean skySun = true;
    public int skySunColor = 0xFFFFF4D6;
    private boolean skyPending;
    // Точечные источники кадра: x, y, z, r, g, b, радиус.
    final float[] lights = new float[MAX_LIGHTS * 7];
    int lightCount;
    /** Карта освещения статической сцены (для треугольников с F_LIGHTMAP). */
    Texture lightmap;

    // Очередь треугольников.
    static final int VS = 13; // sx, sy, 1/z, u/z, v/z, lu/z, lv/z, r/z, g/z, b/z, wx/z, wy/z, wz/z
    static final int TV = VS * 3 + 3; // + нормаль грани
    static final int TI = 6; // оттенок, флаги, id, маска источников, minY, maxY
    static final int F_LIGHTMAP = 1, F_ALPHA = 2, F_ADD = 4, F_CUTOUT = 8, F_POINT = 16, F_TEX = 32;
    float[] tv = new float[TV * 1024];
    int[] ti = new int[TI * 1024];
    Texture[] ttex = new Texture[1024];
    int queued;
    int[][] bins = new int[0][];
    int[] binCount = new int[0];

    public void begin(int width, int height, int clear) {
        if (width * height != color.length) {
            color = new int[width * height];
            depth = new float[width * height];
            ids = new int[width * height];
            pickDepth = new float[width * height];
        }
        w = width;
        h = height;
        Arrays.fill(color, clear);
        Arrays.fill(depth, 0f);
        Arrays.fill(ids, 0);
        Arrays.fill(pickDepth, 0f);
        int nb = (h + BAND - 1) / BAND;
        if (bins.length != nb) {
            bins = new int[nb][64];
            binCount = new int[nb];
        }
        queued = 0;
        Arrays.fill(binCount, 0);
        triangles = 0;
        rasterMs = 0;
        lightCount = 0;
        skyPending = sky != null;
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        float sy = (float) Math.sin(y), cy = (float) Math.cos(y), sp = (float) Math.sin(p), cp = (float) Math.cos(p);
        fX = sy * cp;
        fY = sp;
        fZ = cy * cp;
        rX = cy;
        rY = 0;
        rZ = -sy;
        uX = -sy * sp;
        uY = cp;
        uZ = -cy * sp;
        focal = (float) (h / 2.0 / Math.tan(Math.toRadians(Math.max(20, Math.min(150, fov))) / 2));
        float len = (float) Math.sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ);
        if (len > 0) {
            lightX /= len;
            lightY /= len;
            lightZ /= len;
        }
    }

    /** Старый вид g3d.light: окружающий свет ambient (серый), солнце — остальное. */
    public void setLight(float x, float y, float z, float ambient) {
        lightX = x;
        lightY = y;
        lightZ = z;
        ambR = ambG = ambB = ambient;
        sunR = sunG = sunB = 1 - ambient;
    }

    /** Точечный источник на этот кадр (вспышка выстрела, взрыв, лампа). */
    public void pointLight(float x, float y, float z, int rgb, float radius, float intensity) {
        if (lightCount >= MAX_LIGHTS || radius <= 0) {
            return;
        }
        int o = lightCount++ * 7;
        lights[o] = x;
        lights[o + 1] = y;
        lights[o + 2] = z;
        lights[o + 3] = ((rgb >> 16) & 255) / 255f * intensity;
        lights[o + 4] = ((rgb >> 8) & 255) / 255f * intensity;
        lights[o + 5] = (rgb & 255) / 255f * intensity;
        lights[o + 6] = radius;
    }

    // --- Модели ---

    // Рабочие массивы вершин: мир, вид, текстура, карта освещения, свет.
    private float[] wx = new float[0], wy, wz, vx, vy, vz, lr, lg, lb;
    private final float[] m3 = new float[9];

    private void ensure(int n) {
        if (wx.length < n) {
            wx = new float[n];
            wy = new float[n];
            wz = new float[n];
            vx = new float[n];
            vy = new float[n];
            vz = new float[n];
            lr = new float[n];
            lg = new float[n];
            lb = new float[n];
        }
    }

    /** Видна ли сфера (центр в мире, радиус). */
    boolean visible(float x, float y, float z, float r) {
        float dx = x - camX, dy = y - camY, dz = z - camZ;
        float cx = dx * rX + dy * rY + dz * rZ;
        float cy = dx * uX + dy * uY + dz * uZ;
        float cz = dx * fX + dy * fY + dz * fZ;
        if (cz + r < NEAR) {
            return false;
        }
        float hw = w / 2f, hh = h / 2f;
        float lx = (float) Math.sqrt(focal * focal + hw * hw), ly = (float) Math.sqrt(focal * focal + hh * hh);
        return (Math.abs(cx) * focal - cz * hw) / lx <= r && (Math.abs(cy) * focal - cz * hh) / ly <= r;
    }

    /**
     * Нарисовать модель. m — матрица 3×3 (поворот и масштаб, по строкам), tx/ty/tz — сдвиг. tint — множитель цвета ARGB,
     * lit — освещать, cull — не рисовать изнанку, id — для выбора (0 — не выбирается), probe — множитель света в точке
     * модели (тень, тоннель; null — нет).
     */
    public void draw(Mesh mesh, Texture tex, float[] m, float tx, float ty, float tz, int tint, boolean lit, boolean cull,
                     int id, int blend, float[] probe) {
        int n = mesh.vertices();
        if (n == 0) {
            return;
        }
        // Отсечение целиком: сфера модели вне поля зрения.
        float scale = 0;
        for (int row = 0; row < 3; row++) {
            scale = Math.max(scale, (float) Math.sqrt(m[row] * m[row] + m[3 + row] * m[3 + row] + m[6 + row] * m[6 + row]));
        }
        float cx = m[0] * mesh.cx + m[1] * mesh.cy + m[2] * mesh.cz + tx;
        float cy = m[3] * mesh.cx + m[4] * mesh.cy + m[5] * mesh.cz + ty;
        float cz = m[6] * mesh.cx + m[7] * mesh.cy + m[8] * mesh.cz + tz;
        if (!visible(cx, cy, cz, mesh.radius * scale + 0.01f)) {
            return;
        }
        ensure(n);
        float[] p = mesh.pos;
        for (int i = 0; i < n; i++) {
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            wx[i] = m[0] * x + m[1] * y + m[2] * z + tx;
            wy[i] = m[3] * x + m[4] * y + m[5] * z + ty;
            wz[i] = m[6] * x + m[7] * y + m[8] * z + tz;
        }
        float pr = probe != null ? probe[0] : 1, pg = probe != null ? probe[1] : 1, pb = probe != null ? probe[2] : 1;
        boolean smooth = lit && mesh.normals != null;
        if (smooth) {
            float[] nn = mesh.normals;
            for (int i = 0; i < n; i++) {
                float x = nn[i * 3], y = nn[i * 3 + 1], z = nn[i * 3 + 2];
                float nx = m[0] * x + m[1] * y + m[2] * z;
                float ny = m[3] * x + m[4] * y + m[5] * z;
                float nz = m[6] * x + m[7] * y + m[8] * z;
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                float d = l > 0 ? Math.max(0, -(nx * lightX + ny * lightY + nz * lightZ) / l) : 0;
                lr[i] = (ambR + sunR * d) * pr;
                lg[i] = (ambG + sunG * d) * pg;
                lb[i] = (ambB + sunB * d) * pb;
            }
        } else if (!lit) {
            Arrays.fill(lr, 0, n, 1f);
            Arrays.fill(lg, 0, n, 1f);
            Arrays.fill(lb, 0, n, 1f);
        }
        submit(n, mesh.uv, null, null, mesh.idx, 0, mesh.idx.length, tex, tint, lit && !smooth, pr, pg, pb, cull, id, blend,
                false);
    }

    /** Треугольники, уже заданные в мире (статическая сцена): lm — координаты в карте освещения или null. */
    void drawWorld(float[] pos, float[] uv, float[] lmFront, float[] lmBack, int[] idx, Texture tex, int tint, boolean lit,
                   boolean cull, int id) {
        int n = pos.length / 3;
        ensure(n);
        for (int i = 0; i < n; i++) {
            wx[i] = pos[i * 3];
            wy[i] = pos[i * 3 + 1];
            wz[i] = pos[i * 3 + 2];
        }
        boolean baked = lmFront != null && lightmap != null && lit;
        if (baked || !lit) {
            Arrays.fill(lr, 0, n, 1f);
            Arrays.fill(lg, 0, n, 1f);
            Arrays.fill(lb, 0, n, 1f);
        }
        submit(n, uv, baked ? lmFront : null, baked ? lmBack : null, idx, 0, idx.length, tex, tint, lit && !baked, 1, 1, 1,
                cull, id, BLEND_NONE, baked);
    }

    private final float[][] tri = new float[3][VS];

    private void submit(int n, float[] uv, float[] lmFront, float[] lmBack, int[] idx, int from, int to, Texture tex,
                        int tint, boolean flat,
                        float pr, float pg, float pb, boolean cull, int id, int blend, boolean baked) {
        for (int i = 0; i < n; i++) {
            float dx = wx[i] - camX, dy = wy[i] - camY, dz = wz[i] - camZ;
            vx[i] = dx * rX + dy * rY + dz * rZ;
            vy[i] = dx * uX + dy * uY + dz * uZ;
            vz[i] = dx * fX + dy * fY + dz * fZ;
        }
        int flags = (tex != null ? F_TEX : 0) | (baked ? F_LIGHTMAP : 0) | (tex != null && tex.cutout ? F_CUTOUT : 0);
        if (blend == BLEND_ADD) {
            flags |= F_ADD;
        } else if (blend == BLEND_ALPHA || (tint >>> 24) < 255) {
            flags |= F_ALPHA;
        }
        for (int t = from; t + 2 < to; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            if (vz[a] < NEAR && vz[b] < NEAR && vz[c] < NEAR) {
                continue;
            }
            // Нормаль грани (свет по граням и точечные источники).
            float ax = wx[b] - wx[a], ay = wy[b] - wy[a], az = wz[b] - wz[a];
            float bx = wx[c] - wx[a], by = wy[c] - wy[a], bz = wz[c] - wz[a];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl > 0) {
                nx /= nl;
                ny /= nl;
                nz /= nl;
            }
            float fr = 1, fg = 1, fb = 1;
            if (flat) {
                float d = Math.abs(nx * lightX + ny * lightY + nz * lightZ);
                fr = (ambR + sunR * d) * pr;
                fg = (ambG + sunG * d) * pg;
                fb = (ambB + sunB * d) * pb;
            }
            int mask = 0;
            if (lightCount > 0 && (flags & (F_ADD | F_ALPHA)) == 0) {
                float mx = (wx[a] + wx[b] + wx[c]) / 3, my = (wy[a] + wy[b] + wy[c]) / 3, mz = (wz[a] + wz[b] + wz[c]) / 3;
                float rad = 0;
                for (int k : new int[]{a, b, c}) {
                    float ex = wx[k] - mx, ey = wy[k] - my, ez = wz[k] - mz;
                    rad = Math.max(rad, ex * ex + ey * ey + ez * ez);
                }
                rad = (float) Math.sqrt(rad);
                for (int l = 0; l < lightCount; l++) {
                    float ex = lights[l * 7] - mx, ey = lights[l * 7 + 1] - my, ez = lights[l * 7 + 2] - mz;
                    float reach = lights[l * 7 + 6] + rad;
                    if (ex * ex + ey * ey + ez * ez < reach * reach) {
                        mask |= 1 << l;
                    }
                }
            }
            // Карта освещения: своя для каждой стороны грани (видна та, что к камере).
            float[] lm = lmFront == null ? null
                    : (nx * (camX - wx[a]) + ny * (camY - wy[a]) + nz * (camZ - wz[a]) >= 0 ? lmFront : lmBack);
            int[] vs = {a, b, c};
            for (int k = 0; k < 3; k++) {
                int v = vs[k];
                float[] o = tri[k];
                o[0] = vx[v];
                o[1] = vy[v];
                o[2] = vz[v];
                o[3] = uv[v * 2];
                o[4] = uv[v * 2 + 1];
                o[5] = lm != null ? lm[v * 2] : 0;
                o[6] = lm != null ? lm[v * 2 + 1] : 0;
                o[7] = flat ? fr : lr[v];
                o[8] = flat ? fg : lg[v];
                o[9] = flat ? fb : lb[v];
                o[10] = wx[v];
                o[11] = wy[v];
                o[12] = wz[v];
            }
            clip(tex, tint, flags | (mask != 0 ? F_POINT : 0), id, mask, nx, ny, nz, cull);
        }
    }

    /** Спрайт, всегда повёрнутый к камере (враги, деревья, вспышки): квадрат w×h с центром в точке. */
    public void sprite(Texture tex, float x, float y, float z, float sw, float sh, int tint, int id, int blend) {
        float dx = x - camX, dy = y - camY, dz = z - camZ;
        float cx = dx * rX + dy * rY + dz * rZ;
        float cyv = dx * uX + dy * uY + dz * uZ;
        float cz = dx * fX + dy * fY + dz * fZ;
        if (cz < NEAR) {
            return;
        }
        float hw = sw / 2, hh = sh / 2;
        int flags = (tex != null ? F_TEX : 0) | (tex != null && tex.cutout ? F_CUTOUT : 0);
        if (blend == BLEND_ADD) {
            flags |= F_ADD;
        } else if (blend == BLEND_ALPHA || (tint >>> 24) < 255) {
            flags |= F_ALPHA;
        }
        float[][] q = {{cx - hw, cyv + hh, 0, 0}, {cx + hw, cyv + hh, 1, 0}, {cx + hw, cyv - hh, 1, 1}, {cx - hw, cyv - hh, 0, 1}};
        int[][] tris = {{0, 1, 2}, {0, 2, 3}};
        for (int[] t : tris) {
            for (int k = 0; k < 3; k++) {
                float[] s = q[t[k]];
                float[] o = tri[k];
                o[0] = s[0];
                o[1] = s[1];
                o[2] = cz;
                o[3] = s[2];
                o[4] = s[3];
                o[5] = o[6] = 0;
                o[7] = o[8] = o[9] = 1;
                o[10] = x;
                o[11] = y;
                o[12] = z;
            }
            clip(tex, tint, flags, id, 0, 0, 1, 0, false);
        }
    }

    // --- Отсечение по ближней плоскости и проекция ---

    private final float[][] poly = new float[8][VS];

    private void clip(Texture tex, int tint, int flags, int id, int mask, float nx, float ny, float nz, boolean cull) {
        int count = 0;
        if (tri[0][2] >= NEAR && tri[1][2] >= NEAR && tri[2][2] >= NEAR) {
            for (int k = 0; k < 3; k++) {
                System.arraycopy(tri[k], 0, poly[k], 0, VS);
            }
            count = 3;
        } else {
            for (int i = 0; i < 3; i++) {
                float[] cur = tri[i];
                float[] next = tri[(i + 1) % 3];
                boolean inCur = cur[2] >= NEAR, inNext = next[2] >= NEAR;
                if (inCur) {
                    System.arraycopy(cur, 0, poly[count++], 0, VS);
                }
                if (inCur != inNext) {
                    float t = (NEAR - cur[2]) / (next[2] - cur[2]);
                    float[] o = poly[count++];
                    for (int k = 0; k < VS; k++) {
                        o[k] = cur[k] + (next[k] - cur[k]) * t;
                    }
                    o[2] = NEAR;
                }
            }
            if (count < 3) {
                return;
            }
        }
        // Проекция: экран + атрибуты, делённые на z.
        float hw = w / 2f, hh = h / 2f;
        for (int i = 0; i < count; i++) {
            float[] o = poly[i];
            float iz = 1 / o[2];
            float sx = hw + o[0] * iz * focal, sy = hh - o[1] * iz * focal;
            o[0] = sx;
            o[1] = sy;
            o[2] = iz;
            for (int k = 3; k < VS; k++) {
                o[k] *= iz;
            }
        }
        for (int i = 1; i + 1 < count; i++) {
            emit(poly[0], poly[i], poly[i + 1], tex, tint, flags, id, mask, nx, ny, nz, cull);
        }
    }

    private void emit(float[] a, float[] b, float[] c, Texture tex, int tint, int flags, int id, int mask,
                      float nx, float ny, float nz, boolean cull) {
        float area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (area == 0 || Float.isNaN(area) || (cull && area < 0)) {
            return;
        }
        int minY = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        float minX = Math.min(a[0], Math.min(b[0], c[0])), maxX = Math.max(a[0], Math.max(b[0], c[0]));
        if (minY > maxY || maxX < 0 || minX > w) {
            return;
        }
        if (queued == ttex.length) {
            tv = Arrays.copyOf(tv, tv.length * 2);
            ti = Arrays.copyOf(ti, ti.length * 2);
            ttex = Arrays.copyOf(ttex, ttex.length * 2);
        }
        int o = queued * TV;
        System.arraycopy(a, 0, tv, o, VS);
        System.arraycopy(b, 0, tv, o + VS, VS);
        System.arraycopy(c, 0, tv, o + VS * 2, VS);
        tv[o + VS * 3] = nx;
        tv[o + VS * 3 + 1] = ny;
        tv[o + VS * 3 + 2] = nz;
        int q = queued * TI;
        ti[q] = tint;
        ti[q + 1] = flags;
        ti[q + 2] = id;
        ti[q + 3] = mask;
        ti[q + 4] = minY;
        ti[q + 5] = maxY;
        ttex[queued] = tex;
        for (int band = minY / BAND; band <= maxY / BAND; band++) {
            int[] bin = bins[band];
            if (binCount[band] == bin.length) {
                bins[band] = bin = Arrays.copyOf(bin, bin.length * 2);
            }
            bin[binCount[band]++] = queued;
        }
        queued++;
        triangles++;
    }

    // --- Растеризация (на всех ядрах) ---

    /** Нарисовать всё из очереди (перед pick и в конце кадра). */
    public void flush() {
        if (queued == 0 && !skyPending) {
            return;
        }
        long start = System.nanoTime();
        int nb = bins.length;
        AtomicInteger next = new AtomicInteger();
        Runnable work = () -> {
            for (int band; (band = next.getAndIncrement()) < nb; ) {
                rasterBand(band);
            }
        };
        int helpers = Math.min(THREADS - 1, nb - 1);
        CountDownLatch done = new CountDownLatch(Math.max(0, helpers));
        Throwable[] failure = new Throwable[1];
        for (int i = 0; i < helpers; i++) {
            POOL.execute(() -> {
                try {
                    work.run();
                } catch (Throwable e) {
                    failure[0] = e;
                } finally {
                    done.countDown();
                }
            });
        }
        work.run();
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure[0] != null) {
            throw new IllegalStateException("3D render failed", failure[0]);
        }
        skyPending = false;
        queued = 0;
        Arrays.fill(binCount, 0);
        rasterMs += (System.nanoTime() - start) / 1e6f;
    }

    private void rasterBand(int band) {
        int y0 = band * BAND, y1 = Math.min(h, y0 + BAND);
        if (skyPending) {
            drawSky(y0, y1);
        }
        int[] bin = bins[band];
        int n = binCount[band];
        float[] scratch = new float[VS * 3];
        for (int k = 0; k < n; k++) {
            raster(bin[k], y0, y1, scratch);
        }
    }

    private void drawSky(int y0, int y1) {
        // Цвет неба считается раз в 8 пикселей и плавно смешивается между ними.
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            int prev = skyAt(0, y);
            for (int x = 0; x < w; x += 8) {
                int next = skyAt(Math.min(w, x + 8), y);
                int end = Math.min(w, x + 8);
                for (int k = x; k < end; k++) {
                    color[row + k] = Texture.lerp(prev, next, (k - x) * 32);
                }
                prev = next;
            }
        }
    }

    private int skyAt(int x, int y) {
        float py = h / 2f - (y + 0.5f), px = x + 0.5f - w / 2f;
        float dx = fX * focal + rX * px + uX * py;
        float dy = fY * focal + rY * px + uY * py;
        float dz = fZ * focal + rZ * px + uZ * py;
        float inv = 1 / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float e = dy * inv;
        int top = sky[0], hor = sky[1], bot = sky[2];
        int c;
        if (e >= 0) {
            c = Texture.lerp(hor, top, (int) (Math.min(1, (float) Math.sqrt(e) * 1.15f) * 256));
        } else {
            c = Texture.lerp(hor, bot, (int) (Math.min(1, -e * 5) * 256));
        }
        if (skySun) {
            float s = -(dx * lightX + dy * lightY + dz * lightZ) * inv;
            if (s > 0) {
                float s2 = s * s, s4 = s2 * s2, s8 = s4 * s4, s16 = s8 * s8, s64 = s16 * s16 * s16 * s16;
                float s256 = s64 * s64 * s64 * s64;
                float k = Math.min(1, s256 * s256 * 3 + s16 * 0.35f);
                c = Texture.lerp(c, skySunColor, (int) (k * 256));
            }
        }
        return c | 0xFF000000;
    }

    private void raster(int t, int y0, int y1, float[] scratch) {
        int q = t * TI;
        int minY = Math.max(y0, ti[q + 4]), maxY = Math.min(y1 - 1, ti[q + 5]);
        if (minY > maxY) {
            return;
        }
        int o = t * TV;
        float[] v = tv;
        int a = o, b = o + VS, c = o + VS * 2;
        float ax = v[a], ay = v[a + 1], bx = v[b], by = v[b + 1], cx = v[c], cy = v[c + 1];
        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        float inv = 1 / area;
        // Барицентрические веса a и b как плоскости: w = A·x + B·y + C (x, y — центр пикселя).
        float A0 = (by - cy) * inv, B0 = (cx - bx) * inv, C0 = (bx * cy - by * cx) * inv;
        float A1 = (cy - ay) * inv, B1 = (ax - cx) * inv, C1 = (cx * ay - cy * ax) * inv;
        float A2 = -A0 - A1, B2 = -B0 - B1, C2 = 1 - C0 - C1;
        // Атрибуты: значение = c + w0·(a − c) + w1·(b − c); шаг по x и по y.
        float[] dx = scratch;
        final int DY = VS, BASE = VS * 2;
        for (int k = 2; k < VS; k++) {
            float da = v[a + k] - v[c + k], db = v[b + k] - v[c + k];
            dx[k] = A0 * da + A1 * db;
            dx[DY + k] = B0 * da + B1 * db;
            dx[BASE + k] = v[c + k] + C0 * da + C1 * db;
        }
        int tint = ti[q], flags = ti[q + 1], id = ti[q + 2], mask = ti[q + 3];
        Texture tex = ttex[t];
        float nx = v[o + VS * 3], ny = v[o + VS * 3 + 1], nz = v[o + VS * 3 + 2];
        boolean textured = (flags & F_TEX) != 0, cutout = (flags & F_CUTOUT) != 0;
        boolean blendAlpha = (flags & F_ALPHA) != 0, blendAdd = (flags & F_ADD) != 0;
        boolean opaque = !blendAlpha && !blendAdd;
        boolean useLm = (flags & F_LIGHTMAP) != 0 && lightmap != null, point = (flags & F_POINT) != 0;
        boolean mip = textured && tex.levels.length > 1;
        float tw = textured ? tex.widths[0] : 1, th = textured ? tex.heights[0] : 1;
        float kR = ((tint >> 16) & 255) / 255f, kG = ((tint >> 8) & 255) / 255f, kB = (tint & 255) / 255f;
        int tA = tint >>> 24;
        boolean fog = fogFar > fogNear;
        float fogK = fog ? 1 / (fogFar - fogNear) : 0;
        int fR = (fogColor >> 16) & 255, fG = (fogColor >> 8) & 255, fB = fogColor & 255;
        Texture lm = lightmap;
        final float eps = -1e-5f;
        // Шаги атрибутов по x (в локальных — быстрее) и по y (для уровня мипмапа).
        final float sIZ = dx[2], sU = dx[3], sV = dx[4], sLU = dx[5], sLV = dx[6], sR = dx[7], sG = dx[8], sB = dx[9];
        final float sWX = dx[10], sWY = dx[11], sWZ = dx[12];
        final float yIZ = dx[DY + 2], yU = dx[DY + 3], yV = dx[DY + 4];
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            // Отрезок строки внутри треугольника: w_i(x) = A_i·x + K_i ≥ 0.
            float lo = 0, hi = w;
            boolean empty = false;
            for (int e = 0; e < 3; e++) {
                float A = e == 0 ? A0 : e == 1 ? A1 : A2;
                float K = e == 0 ? B0 * py + C0 : e == 1 ? B1 * py + C1 : B2 * py + C2;
                if (A > 0) {
                    lo = Math.max(lo, (eps - K) / A);
                } else if (A < 0) {
                    hi = Math.min(hi, (eps - K) / A);
                } else if (K < eps) {
                    empty = true;
                }
            }
            if (empty) {
                continue;
            }
            int xs = Math.max(0, (int) Math.ceil(lo - 0.5f)), xe = Math.min(w - 1, (int) Math.floor(hi - 0.5f));
            if (xs > xe) {
                continue;
            }
            float px = xs + 0.5f;
            float iz = dx[BASE + 2] + dx[2] * px + dx[DY + 2] * py;
            float U = dx[BASE + 3] + dx[3] * px + dx[DY + 3] * py, V = dx[BASE + 4] + dx[4] * px + dx[DY + 4] * py;
            float LU = dx[BASE + 5] + dx[5] * px + dx[DY + 5] * py, LV = dx[BASE + 6] + dx[6] * px + dx[DY + 6] * py;
            float R = dx[BASE + 7] + dx[7] * px + dx[DY + 7] * py, G = dx[BASE + 8] + dx[8] * px + dx[DY + 8] * py;
            float B = dx[BASE + 9] + dx[9] * px + dx[DY + 9] * py;
            float WX = dx[BASE + 10] + dx[10] * px + dx[DY + 10] * py, WY = dx[BASE + 11] + dx[11] * px + dx[DY + 11] * py;
            float WZ = dx[BASE + 12] + dx[12] * px + dx[DY + 12] * py;
            int row = y * w;
            int level = 0, lodNext = xs;
            for (int x = xs; x <= xe; x++, iz += sIZ, U += sU, V += sV, LU += sLU, LV += sLV, R += sR, G += sG, B += sB,
                    WX += sWX, WY += sWY, WZ += sWZ) {
                int i = row + x;
                if (iz <= depth[i]) {
                    continue;
                }
                float z = 1 / iz;
                int texel;
                if (textured) {
                    float u = U * z, vv = V * z;
                    if (mip && x >= lodNext) {
                        // Уровень мипмапа — по размеру пикселя в текселях (раз в 8 пикселей).
                        float dudx = (sU - u * sIZ) * z * tw, dvdx = (sV - vv * sIZ) * z * th;
                        float dudy = (yU - u * yIZ) * z * tw, dvdy = (yV - vv * yIZ) * z * th;
                        float rho = Math.max(dudx * dudx + dvdx * dvdx, dudy * dudy + dvdy * dvdy);
                        level = rho > 2f ? Math.getExponent(rho) >> 1 : 0;
                        lodNext = x + 8;
                    }
                    texel = tex.sample(u, vv, level);
                    if (cutout && opaque && (texel >>> 24) < 128) {
                        continue;
                    }
                } else {
                    texel = 0xFFFFFFFF;
                }
                float lr = R * z, lg = G * z, lb = B * z;
                if (useLm) {
                    int l = lm.sample(LU * z, LV * z, 0);
                    lr *= ((l >> 16) & 255) * (2 / 255f);
                    lg *= ((l >> 8) & 255) * (2 / 255f);
                    lb *= (l & 255) * (2 / 255f);
                }
                if (point) {
                    float qx = WX * z, qy = WY * z, qz = WZ * z;
                    for (int bits = mask; bits != 0; bits &= bits - 1) {
                        int L = Integer.numberOfTrailingZeros(bits) * 7;
                        float ex = lights[L] - qx, ey = lights[L + 1] - qy, ez = lights[L + 2] - qz;
                        float d2 = ex * ex + ey * ey + ez * ez, r2 = lights[L + 6] * lights[L + 6];
                        if (d2 < r2) {
                            float f = 1 - d2 / r2;
                            f *= f;
                            float nd = Math.abs(ex * nx + ey * ny + ez * nz) / (float) Math.sqrt(d2 + 1e-6f);
                            f *= 0.3f + 0.7f * nd;
                            lr += lights[L + 3] * f;
                            lg += lights[L + 4] * f;
                            lb += lights[L + 5] * f;
                        }
                    }
                }
                float r = ((texel >> 16) & 255) * kR * lr;
                float g = ((texel >> 8) & 255) * kG * lg;
                float bl = (texel & 255) * kB * lb;
                float f = 0;
                if (fog) {
                    f = Math.max(0, Math.min(1, (z - fogNear) * fogK));
                }
                if (opaque) {
                    if (fog) {
                        r += (fR - r) * f;
                        g += (fG - g) * f;
                        bl += (fB - bl) * f;
                    }
                    depth[i] = iz;
                    if (id != NO_PICK) {
                        ids[i] = id;
                        pickDepth[i] = iz;
                    }
                    color[i] = 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(bl);
                } else {
                    int under = color[i];
                    float alpha = (texel >>> 24) * tA / (255f * 255f);
                    if (alpha <= 0) {
                        continue;
                    }
                    if (blendAdd) {
                        float k = alpha * (1 - f);
                        r = ((under >> 16) & 255) + r * k;
                        g = ((under >> 8) & 255) + g * k;
                        bl = (under & 255) + bl * k;
                    } else {
                        if (fog) {
                            r += (fR - r) * f;
                            g += (fG - g) * f;
                            bl += (fB - bl) * f;
                        }
                        r = ((under >> 16) & 255) + (r - ((under >> 16) & 255)) * alpha;
                        g = ((under >> 8) & 255) + (g - ((under >> 8) & 255)) * alpha;
                        bl = (under & 255) + (bl - (under & 255)) * alpha;
                    }
                    color[i] = 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(bl);
                }
            }
        }
    }

    private static int clamp(float v) {
        return v <= 0 ? 0 : v >= 255 ? 255 : (int) v;
    }

    /** Точка мира → (x, y) в пикселях кадра и глубина, или null (позади камеры). */
    public float[] project(float x, float y, float z) {
        float dx = x - camX, dy = y - camY, dz = z - camZ;
        float vx0 = dx * rX + dy * rY + dz * rZ;
        float vy0 = dx * uX + dy * uY + dz * uZ;
        float vz0 = dx * fX + dy * fY + dz * fZ;
        if (vz0 < NEAR) {
            return null;
        }
        return new float[]{w / 2f + vx0 / vz0 * focal, h / 2f - vy0 / vz0 * focal, vz0};
    }
}
