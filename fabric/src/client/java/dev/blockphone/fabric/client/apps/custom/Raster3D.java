package dev.blockphone.fabric.client.apps.custom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 3D для скриптов приложений: программная растеризация на Java (одинаково на любой версии игры и видеокарте, скрипт не
 * получает доступа к графике игры). Треугольники с текстурой и перспективной коррекцией, буфер глубины, прозрачность
 * «вырезом» (альфа &lt; 128), освещение граней (направленный свет + окружающий), туман, отсечение по ближней плоскости,
 * спрайты-«билборды», буфер id для выбора объекта под прицелом.
 * Мир: Y — вверх; yaw 0 смотрит вдоль +Z, yaw 90 — вдоль +X; pitch &gt; 0 — вверх.
 */
final class Raster3D {
    /** Модель: вершины (x, y, z), текстурные координаты (u, v), треугольники (индексы). */
    static final class Mesh {
        final float[] pos;
        final float[] uv;
        final int[] idx;

        Mesh(float[] pos, float[] uv, int[] idx) {
            this.pos = pos;
            this.uv = uv;
            this.idx = idx;
        }

        int vertices() {
            return pos.length / 3;
        }
    }

    /** Текстура: ARGB-пиксели. */
    record Tex(int w, int h, int[] argb) {
    }

    static final float NEAR = 0.05f;

    int w;
    int h;
    int[] color = new int[0];
    float[] depth = new float[0];
    int[] ids = new int[0];
    int triangles;

    // Камера.
    float camX, camY, camZ, yaw, pitch, fov = 70;
    private float rX, rY, rZ, uX, uY, uZ, fX, fY, fZ, focal;
    // Свет и туман.
    float lightX = -0.4f, lightY = -1f, lightZ = -0.3f, ambient = 0.45f;
    int fogColor;
    float fogNear, fogFar;

    void begin(int width, int height, int clear) {
        if (width * height != color.length) {
            color = new int[width * height];
            depth = new float[width * height];
            ids = new int[width * height];
        }
        w = width;
        h = height;
        Arrays.fill(color, clear);
        Arrays.fill(depth, 0f);
        Arrays.fill(ids, 0);
        triangles = 0;
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

    // Рабочие массивы под вершины одной модели.
    private float[] wx = new float[0], wy, wz, vx, vy, vz;

    /**
     * Нарисовать модель. m — матрица 3×3 (поворот и масштаб, по строкам), tx/ty/tz — сдвиг. tint — множитель цвета ARGB,
     * lit — освещать грани, cull — не рисовать изнанку, id — для выбора (0 — не выбирается).
     */
    void draw(Mesh mesh, Tex tex, float[] m, float tx, float ty, float tz, int tint, boolean lit, boolean cull, int id) {
        int n = mesh.vertices();
        if (wx.length < n) {
            wx = new float[n];
            wy = new float[n];
            wz = new float[n];
            vx = new float[n];
            vy = new float[n];
            vz = new float[n];
        }
        float[] p = mesh.pos;
        for (int i = 0; i < n; i++) {
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            float X = m[0] * x + m[1] * y + m[2] * z + tx;
            float Y = m[3] * x + m[4] * y + m[5] * z + ty;
            float Z = m[6] * x + m[7] * y + m[8] * z + tz;
            wx[i] = X;
            wy[i] = Y;
            wz[i] = Z;
            float dx = X - camX, dy = Y - camY, dz = Z - camZ;
            vx[i] = dx * rX + dy * rY + dz * rZ;
            vy[i] = dx * uX + dy * uY + dz * uZ;
            vz[i] = dx * fX + dy * fY + dz * fZ;
        }
        int[] idx = mesh.idx;
        float[] uv = mesh.uv;
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            if (vz[a] < NEAR && vz[b] < NEAR && vz[c] < NEAR) {
                continue;
            }
            float shade = 1f;
            if (lit) {
                float ax = wx[b] - wx[a], ay = wy[b] - wy[a], az = wz[b] - wz[a];
                float bx = wx[c] - wx[a], by = wy[c] - wy[a], bz = wz[c] - wz[a];
                float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 0) {
                    float d = Math.abs((nx * lightX + ny * lightY + nz * lightZ) / nl);
                    shade = ambient + (1 - ambient) * d;
                }
            }
            triangle(vx[a], vy[a], vz[a], uv[a * 2], uv[a * 2 + 1],
                    vx[b], vy[b], vz[b], uv[b * 2], uv[b * 2 + 1],
                    vx[c], vy[c], vz[c], uv[c * 2], uv[c * 2 + 1], tex, tint, shade, cull, id);
        }
    }

    /** Спрайт, всегда повёрнутый к камере (враги, деревья, вспышки): квадрат w×h с центром в точке. */
    void sprite(Tex tex, float x, float y, float z, float sw, float sh, int tint, int id) {
        float dx = x - camX, dy = y - camY, dz = z - camZ;
        float cx = dx * rX + dy * rY + dz * rZ;
        float cyv = dx * uX + dy * uY + dz * uZ;
        float cz = dx * fX + dy * fY + dz * fZ;
        if (cz < NEAR) {
            return;
        }
        float hw = sw / 2, hh = sh / 2;
        triangle(cx - hw, cyv + hh, cz, 0, 0, cx + hw, cyv + hh, cz, 1, 0, cx + hw, cyv - hh, cz, 1, 1, tex, tint, 1, false, id);
        triangle(cx - hw, cyv + hh, cz, 0, 0, cx + hw, cyv - hh, cz, 1, 1, cx - hw, cyv - hh, cz, 0, 1, tex, tint, 1, false, id);
    }

    // --- Треугольник: отсечение по ближней плоскости, проекция, растеризация ---

    private final float[][] poly = new float[8][5];
    private final float[][] clipped = new float[8][5];

    private void triangle(float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1, float v1,
                          float x2, float y2, float z2, float u2, float v2, Tex tex, int tint, float shade, boolean cull, int id) {
        int count;
        if (z0 >= NEAR && z1 >= NEAR && z2 >= NEAR) {
            set(poly[0], x0, y0, z0, u0, v0);
            set(poly[1], x1, y1, z1, u1, v1);
            set(poly[2], x2, y2, z2, u2, v2);
            count = 3;
        } else {
            set(clipped[0], x0, y0, z0, u0, v0);
            set(clipped[1], x1, y1, z1, u1, v1);
            set(clipped[2], x2, y2, z2, u2, v2);
            count = 0;
            for (int i = 0; i < 3; i++) {
                float[] cur = clipped[i];
                float[] next = clipped[(i + 1) % 3];
                boolean inCur = cur[2] >= NEAR, inNext = next[2] >= NEAR;
                if (inCur) {
                    System.arraycopy(cur, 0, poly[count++], 0, 5);
                }
                if (inCur != inNext) {
                    float t = (NEAR - cur[2]) / (next[2] - cur[2]);
                    float[] o = poly[count++];
                    for (int k = 0; k < 5; k++) {
                        o[k] = cur[k] + (next[k] - cur[k]) * t;
                    }
                    o[2] = NEAR;
                }
            }
            if (count < 3) {
                return;
            }
        }
        for (int i = 1; i + 1 < count; i++) {
            raster(poly[0], poly[i], poly[i + 1], tex, tint, shade, cull, id);
        }
    }

    private static void set(float[] o, float x, float y, float z, float u, float v) {
        o[0] = x;
        o[1] = y;
        o[2] = z;
        o[3] = u;
        o[4] = v;
    }

    private void raster(float[] a, float[] b, float[] c, Tex tex, int tint, float shade, boolean cull, int id) {
        float hw = w / 2f, hh = h / 2f;
        float iza = 1 / a[2], izb = 1 / b[2], izc = 1 / c[2];
        float ax = hw + a[0] * iza * focal, ay = hh - a[1] * iza * focal;
        float bx = hw + b[0] * izb * focal, by = hh - b[1] * izb * focal;
        float cx = hw + c[0] * izc * focal, cy = hh - c[1] * izc * focal;
        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (area == 0 || (cull && area < 0)) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        if (minX > maxX || minY > maxY) {
            return;
        }
        triangles++;
        float inv = 1 / area;
        // Атрибуты, делённые на z (для перспективной коррекции).
        float ua = a[3] * iza, ub = b[3] * izb, uc = c[3] * izc;
        float va = a[4] * iza, vb = b[4] * izb, vc = c[4] * izc;
        int tw = tex != null ? tex.w : 1, th = tex != null ? tex.h : 1;
        int[] tp = tex != null ? tex.argb : null;
        int tA = tint >>> 24, tR = (tint >> 16) & 255, tG = (tint >> 8) & 255, tB = tint & 255;
        boolean fog = fogFar > fogNear;
        int fR = (fogColor >> 16) & 255, fG = (fogColor >> 8) & 255, fB = fogColor & 255;
        int shadeQ = Math.round(shade * 256);
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            float px0 = minX + 0.5f;
            // Барицентрические веса в начале строки и шаг по x.
            float w0 = ((bx - px0) * (cy - py) - (by - py) * (cx - px0)) * inv;
            float w1 = ((cx - px0) * (ay - py) - (cy - py) * (ax - px0)) * inv;
            float d0 = -(cy - by) * inv;
            float d1 = -(ay - cy) * inv;
            int row = y * w;
            boolean inside = false;
            for (int x = minX; x <= maxX; x++, w0 += d0, w1 += d1) {
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) {
                    if (inside) {
                        break; // вышли из треугольника — дальше в строке его нет
                    }
                    continue;
                }
                inside = true;
                float iz = w0 * iza + w1 * izb + w2 * izc;
                int i = row + x;
                if (iz <= depth[i]) {
                    continue;
                }
                int texel;
                if (tp != null) {
                    float z = 1 / iz;
                    float u = (w0 * ua + w1 * ub + w2 * uc) * z;
                    float v = (w0 * va + w1 * vb + w2 * vc) * z;
                    int sx = Math.floorMod((int) Math.floor(u * tw), tw);
                    int sy = Math.floorMod((int) Math.floor(v * th), th);
                    texel = tp[sy * tw + sx];
                    if ((texel >>> 24) < 128) {
                        continue;
                    }
                } else {
                    texel = 0xFFFFFFFF;
                }
                int r = ((texel >> 16) & 255) * tR / 255 * shadeQ >> 8;
                int g = ((texel >> 8) & 255) * tG / 255 * shadeQ >> 8;
                int bl = (texel & 255) * tB / 255 * shadeQ >> 8;
                if (fog) {
                    float z = 1 / iz;
                    float f = Math.max(0, Math.min(1, (z - fogNear) / (fogFar - fogNear)));
                    r += (int) ((fR - r) * f);
                    g += (int) ((fG - g) * f);
                    bl += (int) ((fB - bl) * f);
                }
                if (tA < 255) {
                    // Полупрозрачный оттенок — смешать с тем, что уже нарисовано (без записи глубины).
                    int under = color[i];
                    r = (r * tA + ((under >> 16) & 255) * (255 - tA)) / 255;
                    g = (g * tA + ((under >> 8) & 255) * (255 - tA)) / 255;
                    bl = (bl * tA + (under & 255) * (255 - tA)) / 255;
                } else {
                    depth[i] = iz;
                    ids[i] = id;
                }
                color[i] = 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, bl);
            }
        }
    }

    /** Точка мира → (x, y) в пикселях кадра или null (позади камеры). */
    float[] project(float x, float y, float z) {
        float dx = x - camX, dy = y - camY, dz = z - camZ;
        float vx0 = dx * rX + dy * rY + dz * rZ;
        float vy0 = dx * uX + dy * uY + dz * uZ;
        float vz0 = dx * fX + dy * fY + dz * fZ;
        if (vz0 < NEAR) {
            return null;
        }
        return new float[]{w / 2f + vx0 / vz0 * focal, h / 2f - vy0 / vz0 * focal, vz0};
    }

    // --- Готовые модели ---

    /** Коробка w×h×d с центром в нуле; uvScale — сколько раз текстура повторяется на метр (0 — по разу на грань). */
    static Mesh box(float bw, float bh, float bd, float uvScale) {
        float x = bw / 2, y = bh / 2, z = bd / 2;
        float[][] faces = {
                {-x, -y, z, x, -y, z, x, y, z, -x, y, z, bw, bh},
                {x, -y, -z, -x, -y, -z, -x, y, -z, x, y, -z, bw, bh},
                {x, -y, z, x, -y, -z, x, y, -z, x, y, z, bd, bh},
                {-x, -y, -z, -x, -y, z, -x, y, z, -x, y, -z, bd, bh},
                {-x, y, z, x, y, z, x, y, -z, -x, y, -z, bw, bd},
                {-x, -y, -z, x, -y, -z, x, -y, z, -x, -y, z, bw, bd}};
        return quads(faces, uvScale);
    }

    /** Плоскость w×d (пол/потолок) на высоте 0. */
    static Mesh plane(float pw, float pd, float uvScale) {
        float x = pw / 2, z = pd / 2;
        return quads(new float[][]{{-x, 0, z, x, 0, z, x, 0, -z, -x, 0, -z, pw, pd}}, uvScale);
    }

    private static Mesh quads(float[][] faces, float uvScale) {
        float[] pos = new float[faces.length * 12];
        float[] uv = new float[faces.length * 8];
        int[] idx = new int[faces.length * 6];
        for (int f = 0; f < faces.length; f++) {
            float[] q = faces[f];
            System.arraycopy(q, 0, pos, f * 12, 12);
            float su = uvScale > 0 ? q[12] * uvScale : 1, sv = uvScale > 0 ? q[13] * uvScale : 1;
            float[] coords = {0, sv, su, sv, su, 0, 0, 0};
            System.arraycopy(coords, 0, uv, f * 8, 8);
            int base = f * 4;
            int[] t = {base, base + 1, base + 2, base, base + 2, base + 3};
            System.arraycopy(t, 0, idx, f * 6, 6);
        }
        return new Mesh(pos, uv, idx);
    }

    /** Модель из файла OBJ (v, vt, f; многоугольники — веером; v текстуры перевёрнута, как в Blender). */
    static Mesh obj(String text, int maxVertices) {
        List<float[]> vs = new ArrayList<>();
        List<float[]> vts = new ArrayList<>();
        List<Float> pos = new ArrayList<>();
        List<Float> uv = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("v ")) {
                String[] p = line.split("\\s+");
                vs.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
            } else if (line.startsWith("vt ")) {
                String[] p = line.split("\\s+");
                vts.add(new float[]{Float.parseFloat(p[1]), p.length > 2 ? 1 - Float.parseFloat(p[2]) : 0});
            } else if (line.startsWith("f ")) {
                String[] p = line.split("\\s+");
                int first = pos.size() / 3;
                for (int k = 1; k < p.length; k++) {
                    String[] parts = p[k].split("/");
                    int vi = Integer.parseInt(parts[0]);
                    float[] v = vs.get(vi < 0 ? vs.size() + vi : vi - 1);
                    pos.add(v[0]);
                    pos.add(v[1]);
                    pos.add(v[2]);
                    float[] t = new float[]{0, 0};
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        int ti = Integer.parseInt(parts[1]);
                        t = vts.get(ti < 0 ? vts.size() + ti : ti - 1);
                    }
                    uv.add(t[0]);
                    uv.add(t[1]);
                }
                int corners = p.length - 1;
                for (int k = 1; k + 1 < corners; k++) {
                    idx.add(first);
                    idx.add(first + k);
                    idx.add(first + k + 1);
                }
                if (pos.size() / 3 > maxVertices) {
                    throw new IllegalArgumentException("model has too many vertices (max " + maxVertices + ")");
                }
            }
        }
        float[] posA = new float[pos.size()];
        for (int i = 0; i < posA.length; i++) {
            posA[i] = pos.get(i);
        }
        float[] uvA = new float[uv.size()];
        for (int i = 0; i < uvA.length; i++) {
            uvA[i] = uv.get(i);
        }
        return new Mesh(posA, uvA, idx.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Матрица 3×3: масштаб, затем поворот roll (Z), pitch (X), yaw (Y) — в градусах. */
    static float[] matrix(float yawDeg, float pitchDeg, float rollDeg, float sx, float sy, float sz) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg), r = Math.toRadians(rollDeg);
        float cy = (float) Math.cos(y), syv = (float) Math.sin(y), cp = (float) Math.cos(p), sp = (float) Math.sin(p);
        float cr = (float) Math.cos(r), sr = (float) Math.sin(r);
        // Ry * Rx * Rz
        float[] rz = {cr, -sr, 0, sr, cr, 0, 0, 0, 1};
        float[] rx = {1, 0, 0, 0, cp, sp, 0, -sp, cp};
        float[] ry = {cy, 0, syv, 0, 1, 0, -syv, 0, cy};
        float[] m = mul(ry, mul(rx, rz));
        for (int row = 0; row < 3; row++) {
            m[row * 3] *= sx;
            m[row * 3 + 1] *= sy;
            m[row * 3 + 2] *= sz;
        }
        return m;
    }

    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return o;
    }
}
