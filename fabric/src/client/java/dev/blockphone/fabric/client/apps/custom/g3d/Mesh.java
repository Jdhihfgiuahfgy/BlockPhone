package dev.blockphone.fabric.client.apps.custom.g3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель: вершины (x, y, z), текстурные координаты (u, v), треугольники (индексы) и, по желанию, нормали вершин
 * (плавное освещение; без них — освещение по граням). Ограничивающая сфера — для отсечения невидимых моделей.
 */
public final class Mesh {
    final float[] pos;
    final float[] uv;
    final int[] idx;
    final float[] normals;
    final float cx, cy, cz, radius;

    public Mesh(float[] pos, float[] uv, int[] idx, float[] normals) {
        this.pos = pos;
        this.uv = uv;
        this.idx = idx;
        this.normals = normals;
        int n = pos.length / 3;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, pos[i * 3]);
            maxX = Math.max(maxX, pos[i * 3]);
            minY = Math.min(minY, pos[i * 3 + 1]);
            maxY = Math.max(maxY, pos[i * 3 + 1]);
            minZ = Math.min(minZ, pos[i * 3 + 2]);
            maxZ = Math.max(maxZ, pos[i * 3 + 2]);
        }
        if (n == 0) {
            cx = cy = cz = radius = 0;
            return;
        }
        cx = (minX + maxX) / 2;
        cy = (minY + maxY) / 2;
        cz = (minZ + maxZ) / 2;
        float r2 = 0;
        for (int i = 0; i < n; i++) {
            float dx = pos[i * 3] - cx, dy = pos[i * 3 + 1] - cy, dz = pos[i * 3 + 2] - cz;
            r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
        }
        radius = (float) Math.sqrt(r2);
    }

    public int vertices() {
        return pos.length / 3;
    }

    public int triangles() {
        return idx.length / 3;
    }

    /** Коробка w×h×d с центром в нуле; uvScale — сколько раз текстура повторяется на метр (0 — по разу на грань). */
    public static Mesh box(float bw, float bh, float bd, float uvScale) {
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
    public static Mesh plane(float pw, float pd, float uvScale) {
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
        return new Mesh(pos, uv, idx, null);
    }

    /** Модель из файла OBJ (v, vt, vn, f; многоугольники — веером; v текстуры перевёрнута, как в Blender). */
    public static Mesh obj(String text, int maxVertices) {
        List<float[]> vs = new ArrayList<>();
        List<float[]> vts = new ArrayList<>();
        List<float[]> vns = new ArrayList<>();
        FloatList pos = new FloatList();
        FloatList uv = new FloatList();
        FloatList nor = new FloatList();
        boolean anyNormal = false;
        List<Integer> idx = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("v ")) {
                String[] p = line.split("\\s+");
                vs.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
            } else if (line.startsWith("vt ")) {
                String[] p = line.split("\\s+");
                vts.add(new float[]{Float.parseFloat(p[1]), p.length > 2 ? 1 - Float.parseFloat(p[2]) : 0});
            } else if (line.startsWith("vn ")) {
                String[] p = line.split("\\s+");
                vns.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
            } else if (line.startsWith("f ")) {
                String[] p = line.split("\\s+");
                int first = pos.size / 3;
                for (int k = 1; k < p.length; k++) {
                    String[] parts = p[k].split("/");
                    int vi = Integer.parseInt(parts[0]);
                    float[] v = vs.get(vi < 0 ? vs.size() + vi : vi - 1);
                    pos.add(v[0]);
                    pos.add(v[1]);
                    pos.add(v[2]);
                    float[] t = {0, 0};
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        int ti = Integer.parseInt(parts[1]);
                        t = vts.get(ti < 0 ? vts.size() + ti : ti - 1);
                    }
                    uv.add(t[0]);
                    uv.add(t[1]);
                    float[] nv = {0, 0, 0};
                    if (parts.length > 2 && !parts[2].isEmpty()) {
                        int ni = Integer.parseInt(parts[2]);
                        nv = vns.get(ni < 0 ? vns.size() + ni : ni - 1);
                        anyNormal = true;
                    }
                    nor.add(nv[0]);
                    nor.add(nv[1]);
                    nor.add(nv[2]);
                }
                int corners = p.length - 1;
                for (int k = 1; k + 1 < corners; k++) {
                    idx.add(first);
                    idx.add(first + k);
                    idx.add(first + k + 1);
                }
                if (pos.size / 3 > maxVertices) {
                    throw new IllegalArgumentException("model has too many vertices (max " + maxVertices + ")");
                }
            }
        }
        return new Mesh(pos.toArray(), uv.toArray(), idx.stream().mapToInt(Integer::intValue).toArray(),
                anyNormal ? normalize(nor.toArray()) : null);
    }

    static float[] normalize(float[] n) {
        for (int i = 0; i + 2 < n.length; i += 3) {
            float l = (float) Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
            if (l > 0) {
                n[i] /= l;
                n[i + 1] /= l;
                n[i + 2] /= l;
            }
        }
        return n;
    }

    /** Матрица 3×3 (по строкам): масштаб, затем поворот roll (Z), pitch (X), yaw (Y) — в градусах. */
    public static float[] matrix(float yawDeg, float pitchDeg, float rollDeg, float sx, float sy, float sz) {
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

    /** Растущий массив float. */
    static final class FloatList {
        float[] data = new float[64];
        int size;

        void add(float v) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
