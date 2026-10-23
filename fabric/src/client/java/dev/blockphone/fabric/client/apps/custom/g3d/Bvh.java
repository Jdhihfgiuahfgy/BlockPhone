package dev.blockphone.fabric.client.apps.custom.g3d;

import java.util.Arrays;

/**
 * Дерево ограничивающих коробок над треугольниками мира: быстрый луч (выстрелы, тени при запекании света) и поиск
 * треугольников рядом с точкой (столкновения). Строится один раз, только читается — можно из нескольких потоков.
 */
final class Bvh {
    /** Вершины треугольников: 9 чисел на треугольник. */
    final float[] tri;
    /** id объекта у каждого треугольника. */
    final int[] triId;
    final int count;
    // Узлы: min xyz, max xyz; для листа — первый треугольник и число, для узла — правый потомок (левый — следующий).
    private float[] box;
    private int[] nodeA;
    private int[] nodeB;
    private int nodes;
    private static final int LEAF = 4;

    Bvh(float[] tri, int[] triId) {
        this.count = tri.length / 9;
        int[] ord = new int[count];
        float[] cen = new float[count * 3];
        for (int i = 0; i < count; i++) {
            ord[i] = i;
            for (int k = 0; k < 3; k++) {
                cen[i * 3 + k] = (tri[i * 9 + k] + tri[i * 9 + 3 + k] + tri[i * 9 + 6 + k]) / 3;
            }
        }
        int cap = Math.max(1, count * 2);
        box = new float[cap * 6];
        nodeA = new int[cap];
        nodeB = new int[cap];
        if (count > 0) {
            build(tri, cen, ord, 0, count);
        }
        // Треугольники — в порядке листьев (ближе в памяти).
        this.tri = new float[count * 9];
        this.triId = new int[count];
        for (int i = 0; i < count; i++) {
            System.arraycopy(tri, ord[i] * 9, this.tri, i * 9, 9);
            this.triId[i] = triId[ord[i]];
        }
    }

    private int build(float[] tri, float[] cen, int[] ord, int from, int to) {
        int node = nodes++;
        if (node >= nodeA.length) {
            box = Arrays.copyOf(box, box.length * 2);
            nodeA = Arrays.copyOf(nodeA, nodeA.length * 2);
            nodeB = Arrays.copyOf(nodeB, nodeB.length * 2);
        }
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float[] cb = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = from; i < to; i++) {
            int t = ord[i];
            for (int v = 0; v < 3; v++) {
                for (int k = 0; k < 3; k++) {
                    float c = tri[t * 9 + v * 3 + k];
                    b[k] = Math.min(b[k], c);
                    b[3 + k] = Math.max(b[3 + k], c);
                }
            }
            for (int k = 0; k < 3; k++) {
                cb[k] = Math.min(cb[k], cen[t * 3 + k]);
                cb[3 + k] = Math.max(cb[3 + k], cen[t * 3 + k]);
            }
        }
        System.arraycopy(b, 0, box, node * 6, 6);
        int n = to - from;
        int axis = 0;
        float ext = cb[3] - cb[0];
        for (int k = 1; k < 3; k++) {
            if (cb[3 + k] - cb[k] > ext) {
                ext = cb[3 + k] - cb[k];
                axis = k;
            }
        }
        if (n <= LEAF || ext <= 1e-6f) {
            nodeA[node] = from;
            nodeB[node] = -n; // лист: отрицательное число треугольников
            return node;
        }
        // Деление по середине центров; если всё по одну сторону — пополам по порядку.
        float mid = (cb[axis] + cb[3 + axis]) / 2;
        int i = from, j = to - 1;
        while (i <= j) {
            if (cen[ord[i] * 3 + axis] < mid) {
                i++;
            } else {
                int tmp = ord[i];
                ord[i] = ord[j];
                ord[j] = tmp;
                j--;
            }
        }
        int split = i;
        if (split == from || split == to) {
            split = (from + to) / 2;
            final int ax = axis;
            Integer[] part = new Integer[n];
            for (int k = 0; k < n; k++) {
                part[k] = ord[from + k];
            }
            Arrays.sort(part, (p, q) -> Float.compare(cen[p * 3 + ax], cen[q * 3 + ax]));
            for (int k = 0; k < n; k++) {
                ord[from + k] = part[k];
            }
        }
        build(tri, cen, ord, from, split);
        int right = build(tri, cen, ord, split, to);
        nodeA[node] = right;
        nodeB[node] = 1;
        return node;
    }

    /** Результат луча: расстояние (t при единичном направлении), треугольник. */
    static final class Hit {
        float t;
        int tri = -1;
    }

    /**
     * Ближайшее пересечение луча (o + d·t, 0 &lt; t &lt; max) с треугольниками; any — достаточно любого (тени).
     * skipId — не учитывать треугольники с таким id (0 — учитывать все).
     */
    boolean ray(float ox, float oy, float oz, float dx, float dy, float dz, float max, boolean any, Hit hit) {
        if (count == 0) {
            return false;
        }
        float ix = 1 / dx, iy = 1 / dy, iz = 1 / dz;
        int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = 0;
        float best = max;
        int bestTri = -1;
        while (sp > 0) {
            int node = stack[--sp];
            int bo = node * 6;
            float t0 = (box[bo] - ox) * ix, t1 = (box[bo + 3] - ox) * ix;
            float tmin = Math.min(t0, t1), tmax = Math.max(t0, t1);
            t0 = (box[bo + 1] - oy) * iy;
            t1 = (box[bo + 4] - oy) * iy;
            tmin = Math.max(tmin, Math.min(t0, t1));
            tmax = Math.min(tmax, Math.max(t0, t1));
            t0 = (box[bo + 2] - oz) * iz;
            t1 = (box[bo + 5] - oz) * iz;
            tmin = Math.max(tmin, Math.min(t0, t1));
            tmax = Math.min(tmax, Math.max(t0, t1));
            if (Float.isNaN(tmin) || Float.isNaN(tmax)) {
                // Луч в плоскости грани коробки (0·∞) — проверяем детей, не рискуя пропустить.
                tmin = 0;
                tmax = best;
            }
            if (tmax < Math.max(0, tmin) || tmin > best) {
                continue;
            }
            if (nodeB[node] <= 0) {
                int from = nodeA[node], n = -nodeB[node];
                for (int k = from; k < from + n; k++) {
                    float t = intersect(k, ox, oy, oz, dx, dy, dz);
                    if (t > 0 && t < best) {
                        best = t;
                        bestTri = k;
                        if (any) {
                            hit.t = t;
                            hit.tri = k;
                            return true;
                        }
                    }
                }
            } else if (sp + 2 <= stack.length) {
                stack[sp++] = nodeA[node];
                stack[sp++] = node + 1;
            }
        }
        if (bestTri >= 0) {
            hit.t = best;
            hit.tri = bestTri;
            return true;
        }
        return false;
    }

    /** Луч и треугольник (Мёллер — Трумбор, обе стороны): t или −1. */
    float intersect(int k, float ox, float oy, float oz, float dx, float dy, float dz) {
        int o = k * 9;
        float ax = tri[o], ay = tri[o + 1], az = tri[o + 2];
        float e1x = tri[o + 3] - ax, e1y = tri[o + 4] - ay, e1z = tri[o + 5] - az;
        float e2x = tri[o + 6] - ax, e2y = tri[o + 7] - ay, e2z = tri[o + 8] - az;
        float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-9f) {
            return -1;
        }
        float inv = 1 / det;
        float sx = ox - ax, sy = oy - ay, sz = oz - az;
        float u = (sx * px + sy * py + sz * pz) * inv;
        if (u < 0 || u > 1) {
            return -1;
        }
        float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        float v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0 || u + v > 1) {
            return -1;
        }
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
    }

    /** Треугольники, чьи коробки пересекают коробку [min, max] (столкновения). Пишет номера в out, возвращает число. */
    int query(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int[] out) {
        if (count == 0) {
            return 0;
        }
        int[] stack = new int[64];
        int sp = 0, n = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int node = stack[--sp];
            int bo = node * 6;
            if (box[bo] > maxX || box[bo + 3] < minX || box[bo + 1] > maxY || box[bo + 4] < minY || box[bo + 2] > maxZ
                    || box[bo + 5] < minZ) {
                continue;
            }
            if (nodeB[node] <= 0) {
                int from = nodeA[node], c = -nodeB[node];
                for (int k = from; k < from + c && n < out.length; k++) {
                    out[n++] = k;
                }
            } else if (sp + 2 <= stack.length) {
                stack[sp++] = nodeA[node];
                stack[sp++] = node + 1;
            }
        }
        return n;
    }
}
