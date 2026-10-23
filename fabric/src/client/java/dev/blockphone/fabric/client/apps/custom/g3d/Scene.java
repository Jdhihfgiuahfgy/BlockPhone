package dev.blockphone.fabric.client.apps.custom.g3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Статическая сцена (уровень): модели, которые не двигаются. Хранится в мире, рисуется кусками с отсечением невидимых,
 * служит миром для лучей ({@link #raycast}) и столкновений ({@link #move}), и для неё можно «запечь» свет
 * ({@link #bake}): карта освещения с тенями от солнца, затенением углов и закрытых мест (окружающий свет приходит только
 * с открытого неба), отражённым светом и лампами. Запекание идёт в фоне на всех ядрах; пока не готово — обычный свет.
 */
public final class Scene {
    /** Часть сцены: треугольники одной модели в мире (без общих вершин). */
    static final class Part {
        float[] pos; // 9 на треугольник
        float[] uv; // 6 на треугольник
        Texture tex;
        int tint;
        boolean lit;
        boolean cull;
        boolean solid;
        int id;
        int firstTri; // номер первого треугольника в сцене
    }

    /** Кусок для рисования: треугольники одной части в одной клетке сетки. */
    static final class Chunk {
        Part part;
        float[] pos;
        float[] uv;
        int[] idx;
        int[] tris; // номера треугольников сцены
        float[] lmFront;
        float[] lmBack;
        float cx, cy, cz, radius;
    }

    /** Настройки запекания. */
    public static final class BakeSettings {
        public float sunX = -0.4f, sunY = -1, sunZ = -0.3f;
        public float sunR = 1, sunG = 0.95f, sunB = 0.85f;
        public float skyR = 0.55f, skyG = 0.6f, skyB = 0.7f;
        public float bounce = 0.35f;
        public float minLight = 0.08f;
        public float aoDistance = 3f;
        public float density = 4;
        public int rays = 16;
        /** Лампы: x, y, z, r, g, b, радиус (по 7 чисел). */
        public float[] lamps = new float[0];
    }

    private final List<Part> parts = new ArrayList<>();
    private int triCount;
    private List<Chunk> chunks = new ArrayList<>();
    private boolean dirty;
    private Bvh bvh;
    private int[] triPart = new int[0];
    private int version;

    // Запекание.
    private volatile int bakeGeneration;
    private volatile float bakeProgress = 1;
    private volatile Baked baked;
    private Baked applied;
    private BakeSettings bakeSettings;
    private final Map<Long, float[]> probes = new HashMap<>();

    private record Baked(int sceneVersion, Texture atlas, float[] lmFront, float[] lmBack, BakeSettings settings) {
    }

    public int triangles() {
        return triCount;
    }

    /** Добавить модель в сцену (m — матрица 3×3 по строкам, t — сдвиг). Возвращает номер части (с 1). */
    public int add(Mesh mesh, Texture tex, float[] m, float tx, float ty, float tz, int tint, boolean lit, boolean cull,
                   boolean solid, int id) {
        Part p = new Part();
        int nt = mesh.triangles();
        p.pos = new float[nt * 9];
        p.uv = new float[nt * 6];
        for (int t = 0; t < nt; t++) {
            for (int k = 0; k < 3; k++) {
                int v = mesh.idx[t * 3 + k];
                float x = mesh.pos[v * 3], y = mesh.pos[v * 3 + 1], z = mesh.pos[v * 3 + 2];
                p.pos[t * 9 + k * 3] = m[0] * x + m[1] * y + m[2] * z + tx;
                p.pos[t * 9 + k * 3 + 1] = m[3] * x + m[4] * y + m[5] * z + ty;
                p.pos[t * 9 + k * 3 + 2] = m[6] * x + m[7] * y + m[8] * z + tz;
                p.uv[t * 6 + k * 2] = mesh.uv[v * 2];
                p.uv[t * 6 + k * 2 + 1] = mesh.uv[v * 2 + 1];
            }
        }
        p.tex = tex;
        p.tint = tint;
        p.lit = lit;
        p.cull = cull;
        p.solid = solid;
        p.id = id;
        p.firstTri = triCount;
        triCount += nt;
        parts.add(p);
        dirty = true;
        version++;
        return parts.size();
    }

    public void clear() {
        parts.clear();
        chunks.clear();
        triCount = 0;
        bvh = null;
        dirty = false;
        version++;
        bakeGeneration++;
        bakeProgress = 1;
        baked = null;
        applied = null;
        probes.clear();
    }

    private void rebuild() {
        if (!dirty) {
            return;
        }
        dirty = false;
        chunks = new ArrayList<>();
        final float cell = 8;
        triPart = new int[triCount];
        List<float[]> solidTris = new ArrayList<>();
        int solidCount = 0;
        for (int pi = 0; pi < parts.size(); pi++) {
            Part p = parts.get(pi);
            int nt = p.pos.length / 9;
            Arrays.fill(triPart, p.firstTri, p.firstTri + nt, pi);
            Map<Long, List<Integer>> cells = new HashMap<>();
            for (int t = 0; t < nt; t++) {
                float mx = (p.pos[t * 9] + p.pos[t * 9 + 3] + p.pos[t * 9 + 6]) / 3;
                float mz = (p.pos[t * 9 + 2] + p.pos[t * 9 + 5] + p.pos[t * 9 + 8]) / 3;
                long key = ((long) Math.floor(mx / cell) << 32) ^ ((long) Math.floor(mz / cell) & 0xFFFFFFFFL);
                cells.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
            for (List<Integer> list : cells.values()) {
                Chunk c = new Chunk();
                c.part = p;
                int n = list.size();
                c.pos = new float[n * 9];
                c.uv = new float[n * 6];
                c.idx = new int[n * 3];
                c.tris = new int[n];
                for (int k = 0; k < n; k++) {
                    int t = list.get(k);
                    System.arraycopy(p.pos, t * 9, c.pos, k * 9, 9);
                    System.arraycopy(p.uv, t * 6, c.uv, k * 6, 6);
                    c.idx[k * 3] = k * 3;
                    c.idx[k * 3 + 1] = k * 3 + 1;
                    c.idx[k * 3 + 2] = k * 3 + 2;
                    c.tris[k] = p.firstTri + t;
                }
                bound(c);
                chunks.add(c);
            }
            if (p.solid) {
                solidTris.add(p.pos);
                solidCount += nt;
            }
        }
        float[] all = new float[solidCount * 9];
        int[] ids = new int[solidCount];
        int o = 0;
        for (Part p : parts) {
            if (p.solid) {
                int nt = p.pos.length / 9;
                System.arraycopy(p.pos, 0, all, o * 9, nt * 9);
                Arrays.fill(ids, o, o + nt, p.id);
                o += nt;
            }
        }
        bvh = new Bvh(all, ids);
        applied = null;
    }

    private static void bound(Chunk c) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < c.pos.length; i += 3) {
            minX = Math.min(minX, c.pos[i]);
            maxX = Math.max(maxX, c.pos[i]);
            minY = Math.min(minY, c.pos[i + 1]);
            maxY = Math.max(maxY, c.pos[i + 1]);
            minZ = Math.min(minZ, c.pos[i + 2]);
            maxZ = Math.max(maxZ, c.pos[i + 2]);
        }
        c.cx = (minX + maxX) / 2;
        c.cy = (minY + maxY) / 2;
        c.cz = (minZ + maxZ) / 2;
        float dx = maxX - c.cx, dy = maxY - c.cy, dz = maxZ - c.cz;
        c.radius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.01f;
    }

    /** Нарисовать сцену (с картой освещения, если запечена). */
    public void draw(Engine3D e) {
        rebuild();
        Baked b = baked;
        if (b != null && b.sceneVersion == version && b != applied) {
            for (Chunk c : chunks) {
                int n = c.tris.length;
                c.lmFront = new float[n * 6];
                c.lmBack = new float[n * 6];
                for (int k = 0; k < n; k++) {
                    System.arraycopy(b.lmFront, c.tris[k] * 6, c.lmFront, k * 6, 6);
                    System.arraycopy(b.lmBack, c.tris[k] * 6, c.lmBack, k * 6, 6);
                }
            }
            applied = b;
        }
        boolean useLm = applied != null;
        e.lightmap = useLm ? applied.atlas : null;
        for (Chunk c : chunks) {
            if (e.visible(c.cx, c.cy, c.cz, c.radius)) {
                Part p = c.part;
                e.drawWorld(c.pos, c.uv, useLm ? c.lmFront : null, useLm ? c.lmBack : null, c.idx, p.tex, p.tint,
                        p.lit, p.cull, p.id);
            }
        }
    }

    // --- Лучи и столкновения ---

    /** Луч по твёрдым частям: {расстояние, nx, ny, nz, id} или null. */
    public float[] raycast(float ox, float oy, float oz, float dx, float dy, float dz, float max) {
        rebuild();
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l == 0 || bvh == null) {
            return null;
        }
        dx /= l;
        dy /= l;
        dz /= l;
        Bvh.Hit hit = new Bvh.Hit();
        if (!bvh.ray(ox, oy, oz, dx, dy, dz, max, false, hit)) {
            return null;
        }
        float[] n = triNormal(bvh.tri, hit.tri);
        if (n[0] * dx + n[1] * dy + n[2] * dz > 0) {
            n[0] = -n[0];
            n[1] = -n[1];
            n[2] = -n[2];
        }
        return new float[]{hit.t, n[0], n[1], n[2], bvh.triId[hit.tri]};
    }

    private static float[] triNormal(float[] tri, int k) {
        int o = k * 9;
        float ax = tri[o + 3] - tri[o], ay = tri[o + 4] - tri[o + 1], az = tri[o + 5] - tri[o + 2];
        float bx = tri[o + 6] - tri[o], by = tri[o + 7] - tri[o + 1], bz = tri[o + 8] - tri[o + 2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return l > 0 ? new float[]{nx / l, ny / l, nz / l} : new float[]{0, 1, 0};
    }

    /** Движение персонажа: {x, y, z, на земле (0/1), упёрся в потолок, упёрся в стену}. */
    public float[] move(float x, float y, float z, float dx, float dy, float dz, float radius, float height, float step) {
        rebuild();
        if (bvh == null) {
            return new float[]{x + dx, y + dy, z + dz, 0, 0, 0};
        }
        Collide c = new Collide(bvh);
        float[] p = c.move(x, y, z, dx, dy, dz, radius, height, step);
        return new float[]{p[0], p[1], p[2], c.grounded ? 1 : 0, c.ceiling ? 1 : 0, c.wall ? 1 : 0};
    }

    /**
     * Множитель света в точке для подвижных моделей (игрок в тени или в тоннеле темнее), по настройкам запекания.
     * null — сцена не запечена.
     */
    public float[] probe(float x, float y, float z) {
        BakeSettings s = applied != null ? applied.settings : null;
        if (s == null || bvh == null) {
            return null;
        }
        long key = ((long) Math.floor(x * 2) & 0x1FFFFF) << 42 | ((long) Math.floor(y * 2) & 0x1FFFFF) << 21
                | ((long) Math.floor(z * 2) & 0x1FFFFF);
        float[] cached = probes.get(key);
        if (cached != null) {
            return cached;
        }
        if (probes.size() > 50_000) {
            probes.clear();
        }
        float px = (float) (Math.floor(x * 2) + 0.5) / 2, py = (float) (Math.floor(y * 2) + 0.5) / 2;
        float pz = (float) (Math.floor(z * 2) + 0.5) / 2;
        Bvh.Hit hit = new Bvh.Hit();
        float[] sun = norm(-s.sunX, -s.sunY, -s.sunZ);
        float sunVis = bvh.ray(px, py, pz, sun[0], sun[1], sun[2], 400, true, hit) ? 0 : 1;
        int open = 0, n = 24;
        for (int i = 0; i < n; i++) {
            // Точки на верхней полусфере (спираль).
            double t = (i + 0.5) / n;
            double up = 0.05 + 0.95 * t;
            double r = Math.sqrt(1 - up * up), a = i * 2.39996;
            if (!bvh.ray(px, py, pz, (float) (r * Math.cos(a)), (float) up, (float) (r * Math.sin(a)), s.aoDistance * 2, true, hit)) {
                open++;
            }
        }
        float sky = open / (float) n;
        float amb = sky + s.bounce * (1 - sky);
        float full = 1 + 0.6f;
        float k = Math.max(0.25f, (amb + 0.6f * sunVis) / full);
        float[] v = {k, k, k};
        probes.put(key, v);
        return v;
    }

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return l > 0 ? new float[]{x / l, y / l, z / l} : new float[]{0, 1, 0};
    }

    // --- Запекание света ---

    public int atlasW() {
        Baked b = baked;
        return b != null ? b.atlas.width() : 0;
    }

    public int atlasH() {
        Baked b = baked;
        return b != null ? b.atlas.height() : 0;
    }

    public float bakeProgress() {
        return baked != null && baked.sceneVersion == version ? 1 : bakeProgress;
    }

    public boolean baked() {
        return baked != null && baked.sceneVersion == version;
    }

    /** Начать запекание в фоне (прошлое отменяется). */
    public void bake(BakeSettings s) {
        rebuild();
        int gen = ++bakeGeneration;
        bakeProgress = 0;
        bakeSettings = s;
        // Снимок сцены для фонового потока.
        int sceneVersion = version;
        List<Part> snapshot = new ArrayList<>(parts);
        int total = triCount;
        Bvh tracer = bvh;
        Thread t = new Thread(() -> {
            try {
                Baked result = new Baker(snapshot, total, tracer, s, () -> bakeGeneration != gen,
                        p -> bakeProgress = p).run(sceneVersion);
                if (result != null && bakeGeneration == gen) {
                    baked = result;
                    bakeProgress = 1;
                }
            } catch (RuntimeException e) {
                bakeProgress = 1;
            }
        }, "BlockPhone Bake");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Отменить запекание (приложение закрыто). */
    public void cancel() {
        bakeGeneration++;
    }

    /** Карта освещения: развёртка поверхностей по плоскостям, упаковка в атлас, расчёт света в каждом текселе. */
    private static final class Baker {
        private final List<Part> parts;
        private final int total;
        private final Bvh bvh;
        private final BakeSettings s;
        private final java.util.function.BooleanSupplier cancelled;
        private final java.util.function.Consumer<Float> progress;

        Baker(List<Part> parts, int total, Bvh bvh, BakeSettings s, java.util.function.BooleanSupplier cancelled,
              java.util.function.Consumer<Float> progress) {
            this.parts = parts;
            this.total = total;
            this.bvh = bvh;
            this.s = s;
            this.cancelled = cancelled;
            this.progress = progress;
        }

        /** Плоский кусок поверхности: треугольники одной плоскости одной части. */
        static final class Chart {
            int part;
            int[] tris; // номера треугольников сцены
            float nx, ny, nz;
            int axis; // ось, вдоль которой проецируем (0 — x, 1 — y, 2 — z)
            float minA, minB, maxA, maxB;
            float density;
            int w, h; // в текселях, с полями
            int x, y; // место в атласе (лицевая сторона; обратная — x2, y2)
            int x2, y2;
        }

        Baked run(int sceneVersion) {
            float[] pos = new float[total * 9];
            boolean[] lit = new boolean[total];
            int[] partOf = new int[total];
            for (int pi = 0; pi < parts.size(); pi++) {
                Part p = parts.get(pi);
                System.arraycopy(p.pos, 0, pos, p.firstTri * 9, p.pos.length);
                int nt = p.pos.length / 9;
                Arrays.fill(lit, p.firstTri, p.firstTri + nt, p.lit);
                Arrays.fill(partOf, p.firstTri, p.firstTri + nt, pi);
            }
            List<Chart> charts = charts(pos, lit, partOf);
            float density = Math.max(0.5f, Math.min(16, s.density));
            int atlasW = 1024, atlasH = 0;
            for (int attempt = 0; attempt < 8; attempt++) {
                for (Chart c : charts) {
                    c.density = density;
                    float ea = (c.maxA - c.minA) * density, eb = (c.maxB - c.minB) * density;
                    float scale = Math.min(1, 500 / Math.max(1, Math.max(ea, eb)));
                    c.density = density * scale;
                    c.w = (int) Math.ceil((c.maxA - c.minA) * c.density) + 3;
                    c.h = (int) Math.ceil((c.maxB - c.minB) * c.density) + 3;
                }
                atlasW = 1024;
                atlasH = pack(charts, atlasW);
                if (atlasH <= 2048) {
                    break;
                }
                atlasW = 2048;
                atlasH = pack(charts, atlasW);
                if (atlasH <= 2048) {
                    break;
                }
                density *= 0.7f;
            }
            if (cancelled.getAsBoolean()) {
                return null;
            }
            int aw = atlasW, ah = Math.max(1, atlasH);
            float[] light = new float[aw * ah * 3];
            byte[] state = new byte[aw * ah]; // 0 — пусто, 1 — посчитано, 2 — внутри стены (заполнить соседями)
            int[] owner = new int[aw * ah]; // чей кусок (заполнять только своими соседями)
            AtomicInteger done = new AtomicInteger();
            int jobs = charts.size() * 2;
            IntStream.range(0, jobs).parallel().forEach(j -> {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                bakeChart(charts.get(j / 2), j % 2 == 1, pos, light, state, owner, j + 1, aw);
                progress.accept(0.95f * done.incrementAndGet() / jobs);
            });
            if (cancelled.getAsBoolean()) {
                return null;
            }
            dilate(light, state, owner, aw, ah);
            int[] argb = new int[aw * ah];
            for (int i = 0; i < aw * ah; i++) {
                int r = (int) Math.min(255, light[i * 3] * 127.5f + 0.5f);
                int g = (int) Math.min(255, light[i * 3 + 1] * 127.5f + 0.5f);
                int b = (int) Math.min(255, light[i * 3 + 2] * 127.5f + 0.5f);
                argb[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
            Texture atlas = new Texture(aw, ah, argb, true, false, true);
            // Координаты вершин в атласе (обе стороны).
            float[] front = new float[total * 6], back = new float[total * 6];
            for (Chart c : charts) {
                for (int t : c.tris) {
                    for (int k = 0; k < 3; k++) {
                        float[] ab = project(c, pos[t * 9 + k * 3], pos[t * 9 + k * 3 + 1], pos[t * 9 + k * 3 + 2]);
                        float ca = 1.5f + (ab[0] - c.minA) * c.density, cb = 1.5f + (ab[1] - c.minB) * c.density;
                        front[t * 6 + k * 2] = (c.x + ca) / aw;
                        front[t * 6 + k * 2 + 1] = (c.y + cb) / ah;
                        back[t * 6 + k * 2] = (c.x2 + ca) / aw;
                        back[t * 6 + k * 2 + 1] = (c.y2 + cb) / ah;
                    }
                }
            }
            progress.accept(1f);
            return new Baked(sceneVersion, atlas, front, back, s);
        }

        /** Разбить освещаемые треугольники на плоские куски (одна плоскость, касаются друг друга). */
        private List<Chart> charts(float[] pos, boolean[] lit, int[] partOf) {
            Map<String, List<Integer>> planes = new HashMap<>();
            float[][] normals = new float[total][];
            for (int t = 0; t < total; t++) {
                if (!lit[t]) {
                    continue;
                }
                float[] n = triNormal(pos, t);
                normals[t] = n;
                float d = n[0] * pos[t * 9] + n[1] * pos[t * 9 + 1] + n[2] * pos[t * 9 + 2];
                String key = partOf[t] + ":" + Math.round(n[0] * 200) + ":" + Math.round(n[1] * 200) + ":"
                        + Math.round(n[2] * 200) + ":" + Math.round(d * 100);
                planes.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
            List<Chart> out = new ArrayList<>();
            for (List<Integer> group : planes.values()) {
                int first = group.get(0);
                float[] n = normals[first];
                int axis = Math.abs(n[0]) > Math.abs(n[1]) ? (Math.abs(n[0]) > Math.abs(n[2]) ? 0 : 2)
                        : (Math.abs(n[1]) > Math.abs(n[2]) ? 1 : 2);
                int m = group.size();
                float[][] boxes = new float[m][4];
                for (int i = 0; i < m; i++) {
                    int t = group.get(i);
                    float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                    for (int k = 0; k < 3; k++) {
                        float[] ab = project(axis, pos[t * 9 + k * 3], pos[t * 9 + k * 3 + 1], pos[t * 9 + k * 3 + 2]);
                        b[0] = Math.min(b[0], ab[0]);
                        b[1] = Math.min(b[1], ab[1]);
                        b[2] = Math.max(b[2], ab[0]);
                        b[3] = Math.max(b[3], ab[1]);
                    }
                    boxes[i] = b;
                }
                // Касающиеся треугольники — в один кусок (объединение множеств; поиск соседей через сетку).
                int[] parent = new int[m];
                for (int i = 0; i < m; i++) {
                    parent[i] = i;
                }
                Map<Long, List<Integer>> grid = new HashMap<>();
                float cell = 2;
                for (int i = 0; i < m; i++) {
                    float[] b = boxes[i];
                    for (long gx = (long) Math.floor((b[0] - 0.01f) / cell); gx <= (long) Math.floor((b[2] + 0.01f) / cell); gx++) {
                        for (long gy = (long) Math.floor((b[1] - 0.01f) / cell); gy <= (long) Math.floor((b[3] + 0.01f) / cell); gy++) {
                            List<Integer> list = grid.computeIfAbsent(gx << 32 ^ (gy & 0xFFFFFFFFL), k -> new ArrayList<>());
                            for (int j : list) {
                                float[] o = boxes[j];
                                if (b[0] <= o[2] + 0.01f && o[0] <= b[2] + 0.01f && b[1] <= o[3] + 0.01f && o[1] <= b[3] + 0.01f) {
                                    union(parent, i, j);
                                }
                            }
                            list.add(i);
                        }
                    }
                }
                Map<Integer, List<Integer>> comps = new HashMap<>();
                for (int i = 0; i < m; i++) {
                    comps.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
                }
                for (List<Integer> comp : comps.values()) {
                    Chart c = new Chart();
                    c.part = partOf[first];
                    c.tris = new int[comp.size()];
                    c.nx = n[0];
                    c.ny = n[1];
                    c.nz = n[2];
                    c.axis = axis;
                    c.minA = c.minB = Float.MAX_VALUE;
                    c.maxA = c.maxB = -Float.MAX_VALUE;
                    for (int k = 0; k < comp.size(); k++) {
                        int i = comp.get(k);
                        c.tris[k] = group.get(i);
                        c.minA = Math.min(c.minA, boxes[i][0]);
                        c.minB = Math.min(c.minB, boxes[i][1]);
                        c.maxA = Math.max(c.maxA, boxes[i][2]);
                        c.maxB = Math.max(c.maxB, boxes[i][3]);
                    }
                    out.add(c);
                }
            }
            return out;
        }

        private static int find(int[] p, int i) {
            while (p[i] != i) {
                p[i] = p[p[i]];
                i = p[i];
            }
            return i;
        }

        private static void union(int[] p, int a, int b) {
            a = find(p, a);
            b = find(p, b);
            if (a != b) {
                p[a] = b;
            }
        }

        private static float[] triNormal(float[] pos, int t) {
            return Scene.triNormal(pos, t);
        }

        private static float[] project(int axis, float x, float y, float z) {
            return axis == 0 ? new float[]{z, y} : axis == 1 ? new float[]{x, z} : new float[]{x, y};
        }

        private static float[] project(Chart c, float x, float y, float z) {
            return project(c.axis, x, y, z);
        }

        /** Точка плоскости куска по координатам (a, b). */
        private static float[] unproject(Chart c, float d, float a, float b) {
            if (c.axis == 0) {
                return new float[]{(d - c.ny * b - c.nz * a) / c.nx, b, a};
            } else if (c.axis == 1) {
                return new float[]{a, (d - c.nx * a - c.nz * b) / c.ny, b};
            }
            return new float[]{a, b, (d - c.nx * a - c.ny * b) / c.nz};
        }

        /** Полочная упаковка (обе стороны каждого куска): высота атласа. */
        private static int pack(List<Chart> charts, int width) {
            List<Chart> sorted = new ArrayList<>(charts);
            sorted.sort((a, b) -> b.h - a.h);
            int x = 0, y = 0, shelf = 0;
            for (Chart c : sorted) {
                for (int side = 0; side < 2; side++) {
                    if (x + c.w > width) {
                        x = 0;
                        y += shelf;
                        shelf = 0;
                    }
                    if (side == 0) {
                        c.x = x;
                        c.y = y;
                    } else {
                        c.x2 = x;
                        c.y2 = y;
                    }
                    x += c.w;
                    shelf = Math.max(shelf, c.h);
                }
            }
            return y + shelf;
        }

        private void bakeChart(Chart c, boolean backSide, float[] pos, float[] light, byte[] state, int[] owner, int me,
                               int aw) {
            float sign = backSide ? -1 : 1;
            float nx = c.nx * sign, ny = c.ny * sign, nz = c.nz * sign;
            int t0 = c.tris[0];
            float d = c.nx * pos[t0 * 9] + c.ny * pos[t0 * 9 + 1] + c.nz * pos[t0 * 9 + 2];
            int ox = backSide ? c.x2 : c.x, oy = backSide ? c.y2 : c.y;
            for (int j = 0; j < c.h; j++) {
                Arrays.fill(owner, (oy + j) * aw + ox, (oy + j) * aw + ox + c.w, me);
            }
            // Где тексели куска касаются его треугольников: ближайшая точка треугольника к центру текселя.
            float[] best = new float[c.w * c.h];
            float[] ptA = new float[c.w * c.h], ptB = new float[c.w * c.h];
            Arrays.fill(best, Float.MAX_VALUE);
            float reach = 0.75f;
            for (int t : c.tris) {
                float[] a = project(c, pos[t * 9], pos[t * 9 + 1], pos[t * 9 + 2]);
                float[] b = project(c, pos[t * 9 + 3], pos[t * 9 + 4], pos[t * 9 + 5]);
                float[] e = project(c, pos[t * 9 + 6], pos[t * 9 + 7], pos[t * 9 + 8]);
                float minA = Math.min(a[0], Math.min(b[0], e[0])), maxA = Math.max(a[0], Math.max(b[0], e[0]));
                float minB = Math.min(a[1], Math.min(b[1], e[1])), maxB = Math.max(a[1], Math.max(b[1], e[1]));
                int i0 = Math.max(0, (int) Math.floor(1.5f + (minA - c.minA) * c.density - 1.5f));
                int i1 = Math.min(c.w - 1, (int) Math.ceil(1.5f + (maxA - c.minA) * c.density + 0.5f));
                int j0 = Math.max(0, (int) Math.floor(1.5f + (minB - c.minB) * c.density - 1.5f));
                int j1 = Math.min(c.h - 1, (int) Math.ceil(1.5f + (maxB - c.minB) * c.density + 0.5f));
                for (int j = j0; j <= j1; j++) {
                    for (int i = i0; i <= i1; i++) {
                        float pa = c.minA + (i + 0.5f - 1.5f) / c.density, pb = c.minB + (j + 0.5f - 1.5f) / c.density;
                        float[] q = closest2d(pa, pb, a[0], a[1], b[0], b[1], e[0], e[1]);
                        float dist = (float) Math.hypot(q[0] - pa, q[1] - pb) * c.density;
                        int k = j * c.w + i;
                        if (dist <= reach && dist < best[k]) {
                            best[k] = dist;
                            // Чуть внутрь треугольника от края — луч не цепляет соседнюю стену.
                            ptA[k] = q[0];
                            ptB[k] = q[1];
                        }
                    }
                }
            }
            float[] sun = norm(-s.sunX, -s.sunY, -s.sunZ);
            float sunDot = Math.max(0, nx * sun[0] + ny * sun[1] + nz * sun[2]);
            // Базис полусферы вокруг нормали.
            float[] tx = Math.abs(ny) < 0.9f ? norm(nz, 0, -nx) : norm(0, -nz, ny);
            float[] ty = {ny * tx[2] - nz * tx[1], nz * tx[0] - nx * tx[2], nx * tx[1] - ny * tx[0]};
            Bvh.Hit hit = new Bvh.Hit();
            int rays = Math.max(4, Math.min(64, s.rays));
            java.util.Random rnd = new java.util.Random(c.tris[0] * 31L + (backSide ? 7 : 0));
            for (int j = 0; j < c.h; j++) {
                for (int i = 0; i < c.w; i++) {
                    int k = j * c.w + i;
                    if (best[k] == Float.MAX_VALUE) {
                        continue;
                    }
                    float[] p = unproject(c, d, ptA[k], ptB[k]);
                    float px = p[0] + nx * 0.02f, py = p[1] + ny * 0.02f, pz = p[2] + nz * 0.02f;
                    // Окружающий свет: доля открытого неба, остальное — отражённый свет.
                    int open = 0, nearHits = 0;
                    for (int r = 0; r < rays; r++) {
                        double u1 = (r + rnd.nextFloat()) / rays, u2 = rnd.nextFloat();
                        double rr = Math.sqrt(u1), phi = 2 * Math.PI * u2;
                        float lx = (float) (rr * Math.cos(phi)), ly = (float) (rr * Math.sin(phi));
                        float lz = (float) Math.sqrt(Math.max(0, 1 - u1));
                        float dx = tx[0] * lx + ty[0] * ly + nx * lz;
                        float dy = tx[1] * lx + ty[1] * ly + ny * lz;
                        float dz = tx[2] * lx + ty[2] * ly + nz * lz;
                        if (bvh.ray(px, py, pz, dx, dy, dz, s.aoDistance, false, hit)) {
                            if (hit.t < 0.6f) {
                                nearHits++;
                            }
                        } else {
                            open++;
                        }
                    }
                    float sky = open / (float) rays;
                    float amb = sky + s.bounce * (1 - sky);
                    float lr = s.skyR * amb, lg = s.skyG * amb, lb = s.skyB * amb;
                    if (sunDot > 0) {
                        int vis = 0;
                        for (int r = 0; r < 2; r++) {
                            float jx = sun[0] + (rnd.nextFloat() - 0.5f) * 0.03f, jy = sun[1] + (rnd.nextFloat() - 0.5f) * 0.03f;
                            float jz = sun[2] + (rnd.nextFloat() - 0.5f) * 0.03f;
                            if (!bvh.ray(px, py, pz, jx, jy, jz, 500, true, hit)) {
                                vis++;
                            }
                        }
                        float sv = sunDot * vis / 2f;
                        lr += s.sunR * sv;
                        lg += s.sunG * sv;
                        lb += s.sunB * sv;
                    }
                    for (int L = 0; L + 6 < s.lamps.length; L += 7) {
                        float ex = s.lamps[L] - px, ey = s.lamps[L + 1] - py, ez = s.lamps[L + 2] - pz;
                        float d2 = ex * ex + ey * ey + ez * ez, rad = s.lamps[L + 6];
                        if (d2 >= rad * rad) {
                            continue;
                        }
                        float dl = (float) Math.sqrt(d2);
                        float ndl = (ex * nx + ey * ny + ez * nz) / Math.max(1e-4f, dl);
                        if (ndl <= 0 || bvh.ray(px, py, pz, ex / dl, ey / dl, ez / dl, dl - 0.05f, true, hit)) {
                            continue;
                        }
                        float f = 1 - d2 / (rad * rad);
                        f *= f * ndl;
                        lr += s.lamps[L + 3] * f;
                        lg += s.lamps[L + 4] * f;
                        lb += s.lamps[L + 5] * f;
                    }
                    lr = Math.max(s.minLight, lr);
                    lg = Math.max(s.minLight, lg);
                    lb = Math.max(s.minLight, lb);
                    int a = (oy + j) * aw + ox + i;
                    light[a * 3] = lr;
                    light[a * 3 + 1] = lg;
                    light[a * 3 + 2] = lb;
                    // Почти все лучи упёрлись вплотную — точка внутри стены: цвет возьмём у соседей.
                    state[a] = (byte) (nearHits >= rays * 0.9f ? 2 : 1);
                }
            }
        }

        /** Пустые тексели и тексели «внутри стен» — средним соседей (сглаживание не тянет чёрное). */
        private static void dilate(float[] light, byte[] state, int[] owner, int w, int h) {
            for (int pass = 0; pass < 4; pass++) {
                byte[] next = state.clone();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = y * w + x;
                        if (state[i] == 1) {
                            continue;
                        }
                        float r = 0, g = 0, b = 0;
                        int n = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx, yy = y + dy;
                                if (xx < 0 || yy < 0 || xx >= w || yy >= h) {
                                    continue;
                                }
                                int j = yy * w + xx;
                                if (state[j] == 1 && owner[j] == owner[i]) {
                                    r += light[j * 3];
                                    g += light[j * 3 + 1];
                                    b += light[j * 3 + 2];
                                    n++;
                                }
                            }
                        }
                        if (n > 0) {
                            light[i * 3] = r / n;
                            light[i * 3 + 1] = g / n;
                            light[i * 3 + 2] = b / n;
                            next[i] = 1;
                        }
                    }
                }
                System.arraycopy(next, 0, state, 0, state.length);
            }
        }

        /** Ближайшая к (px, py) точка треугольника на плоскости. */
        private static float[] closest2d(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {
            float abx = bx - ax, aby = by - ay, acx = cx - ax, acy = cy - ay;
            float apx = px - ax, apy = py - ay;
            float d1 = abx * apx + aby * apy, d2 = acx * apx + acy * apy;
            if (d1 <= 0 && d2 <= 0) {
                return new float[]{ax, ay};
            }
            float bpx = px - bx, bpy = py - by;
            float d3 = abx * bpx + aby * bpy, d4 = acx * bpx + acy * bpy;
            if (d3 >= 0 && d4 <= d3) {
                return new float[]{bx, by};
            }
            float vc = d1 * d4 - d3 * d2;
            if (vc <= 0 && d1 >= 0 && d3 <= 0) {
                float v = d1 / (d1 - d3);
                return new float[]{ax + abx * v, ay + aby * v};
            }
            float cpx = px - cx, cpy = py - cy;
            float d5 = abx * cpx + aby * cpy, d6 = acx * cpx + acy * cpy;
            if (d6 >= 0 && d5 <= d6) {
                return new float[]{cx, cy};
            }
            float vb = d5 * d2 - d1 * d6;
            if (vb <= 0 && d2 >= 0 && d6 <= 0) {
                float w = d2 / (d2 - d6);
                return new float[]{ax + acx * w, ay + acy * w};
            }
            float va = d3 * d6 - d5 * d4;
            if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
                float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                return new float[]{bx + (cx - bx) * w, by + (cy - by) * w};
            }
            float denom = 1 / (va + vb + vc);
            float v = vb * denom, w = vc * denom;
            return new float[]{ax + abx * v + acx * w, ay + aby * v + acy * w};
        }
    }
}
