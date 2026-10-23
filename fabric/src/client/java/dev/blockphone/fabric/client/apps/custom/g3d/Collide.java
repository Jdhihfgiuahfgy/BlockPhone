package dev.blockphone.fabric.client.apps.custom.g3d;

/**
 * Движение персонажа сквозь мир из треугольников: эллипсоид (радиус × половина роста) скользит вдоль стен и пола,
 * сам поднимается на ступеньки не выше step, не скатывается с пологих склонов, упирается в потолок.
 * Алгоритм — «скользящая сфера» (Fauerby, «Improved Collision detection and Response»): в пространстве, где эллипсоид —
 * единичная сфера, ищется первое касание с гранью, ребром или вершиной, остаток пути проецируется на плоскость касания.
 */
final class Collide {
    private final Bvh bvh;
    private double rx, ry, rz;
    // Треугольники рядом (в пространстве эллипсоида).
    private double[] tris = new double[9 * 256];
    private int count;
    private final int[] found = new int[4096];

    // Результат одного скольжения.
    private double hitT;
    private boolean hit;
    private double cpX, cpY, cpZ;
    boolean grounded;
    boolean ceiling;
    boolean wall;
    /** Нормаль последнего касания (в мире). */
    float normalX, normalY, normalZ;

    Collide(Bvh bvh) {
        this.bvh = bvh;
    }

    /**
     * Переместить ноги (x, y, z) на (dx, dy, dz). Возвращает {x, y, z}; grounded — стоит на полу, ceiling — упёрся
     * головой, wall — упёрся в стену.
     */
    float[] move(float x, float y, float z, float dx, float dy, float dz, float radius, float height, float step) {
        rx = rz = Math.max(0.05, radius);
        ry = Math.max(rx, height / 2.0);
        grounded = ceiling = wall = false;
        double reach = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) + step + 0.2;
        gather(x - rx - reach, y - reach, z - rz - reach, x + rx + reach, y + 2 * ry + reach, z + rz + reach);
        // Центр в пространстве эллипсоида.
        double[] c = {x / rx, (y + ry) / ry, z / rz};
        depenetrate(c);
        double hx = dx / rx, hz = dz / rz;
        if (hx != 0 || hz != 0) {
            double[] plain = c.clone();
            slide(plain, hx, 0, hz, false);
            boolean plainWall = wall;
            if (step > 0) {
                // Попробовать «через ступеньку»: вверх, вперёд, вниз — и взять, что прошло дальше.
                double[] up = c.clone();
                slide(up, 0, step / ry, 0, false);
                double rise = up[1] - c[1];
                double[] across = up.clone();
                wall = false;
                slide(across, hx, 0, hz, false);
                boolean acrossWall = wall;
                double[] down = across.clone();
                grounded = false;
                slide(down, 0, -rise, 0, true);
                double dPlain = sq(plain[0] - c[0], plain[2] - c[2]), dStep = sq(down[0] - c[0], down[2] - c[2]);
                if (dStep > dPlain + 1e-6 && grounded) {
                    c = down;
                    wall = acrossWall;
                } else {
                    c = plain;
                    wall = plainWall;
                }
            } else {
                c = plain;
            }
        }
        grounded = false;
        if (dy != 0) {
            slide(c, 0, dy / ry, 0, true);
        }
        if (!grounded && dy <= 0) {
            // Проверка пола под ногами (не двигаясь).
            double[] probe = c.clone();
            slide(probe, 0, -0.02 / ry, 0, true);
        }
        return new float[]{(float) (c[0] * rx), (float) (c[1] * ry - ry), (float) (c[2] * rz)};
    }

    private static double sq(double a, double b) {
        return a * a + b * b;
    }

    private void gather(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int n = bvh.query((float) minX, (float) minY, (float) minZ, (float) maxX, (float) maxY, (float) maxZ, found);
        if (tris.length < n * 9) {
            tris = new double[n * 9];
        }
        float[] t = bvh.tri;
        for (int i = 0; i < n; i++) {
            int o = found[i] * 9;
            for (int v = 0; v < 3; v++) {
                tris[i * 9 + v * 3] = t[o + v * 3] / rx;
                tris[i * 9 + v * 3 + 1] = t[o + v * 3 + 1] / ry;
                tris[i * 9 + v * 3 + 2] = t[o + v * 3 + 2] / rz;
            }
        }
        count = n;
    }

    /** Вытолкнуть сферу из треугольников, в которые она уже влезла. */
    private void depenetrate(double[] c) {
        double[] cp = new double[3];
        for (int iter = 0; iter < 4; iter++) {
            boolean moved = false;
            for (int i = 0; i < count; i++) {
                closest(i, c, cp);
                double ex = c[0] - cp[0], ey = c[1] - cp[1], ez = c[2] - cp[2];
                double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (d < 1 - 1e-4) {
                    if (d < 1e-6) {
                        double[] n = normal(i);
                        c[0] += n[0];
                        c[1] += n[1];
                        c[2] += n[2];
                    } else {
                        double push = (1 - d + 1e-4) / d;
                        c[0] += ex * push;
                        c[1] += ey * push;
                        c[2] += ez * push;
                    }
                    moved = true;
                }
            }
            if (!moved) {
                return;
            }
        }
    }

    private double[] normal(int i) {
        int o = i * 9;
        double ax = tris[o + 3] - tris[o], ay = tris[o + 4] - tris[o + 1], az = tris[o + 5] - tris[o + 2];
        double bx = tris[o + 6] - tris[o], by = tris[o + 7] - tris[o + 1], bz = tris[o + 8] - tris[o + 2];
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return l > 0 ? new double[]{nx / l, ny / l, nz / l} : new double[]{0, 1, 0};
    }

    /** Ближайшая к p точка треугольника i (Ericson, «Real-Time Collision Detection»). */
    private void closest(int i, double[] p, double[] out) {
        int o = i * 9;
        double ax = tris[o], ay = tris[o + 1], az = tris[o + 2];
        double bx = tris[o + 3], by = tris[o + 4], bz = tris[o + 5];
        double cx = tris[o + 6], cy = tris[o + 7], cz = tris[o + 8];
        double abx = bx - ax, aby = by - ay, abz = bz - az, acx = cx - ax, acy = cy - ay, acz = cz - az;
        double apx = p[0] - ax, apy = p[1] - ay, apz = p[2] - az;
        double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0 && d2 <= 0) {
            set(out, ax, ay, az);
            return;
        }
        double bpx = p[0] - bx, bpy = p[1] - by, bpz = p[2] - bz;
        double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0 && d4 <= d3) {
            set(out, bx, by, bz);
            return;
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double v = d1 / (d1 - d3);
            set(out, ax + abx * v, ay + aby * v, az + abz * v);
            return;
        }
        double cpx = p[0] - cx, cpy = p[1] - cy, cpz = p[2] - cz;
        double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0 && d5 <= d6) {
            set(out, cx, cy, cz);
            return;
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double w = d2 / (d2 - d6);
            set(out, ax + acx * w, ay + acy * w, az + acz * w);
            return;
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            set(out, bx + (cx - bx) * w, by + (cy - by) * w, bz + (cz - bz) * w);
            return;
        }
        double denom = 1 / (va + vb + vc);
        double v = vb * denom, w = vc * denom;
        set(out, ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w);
    }

    private static void set(double[] o, double x, double y, double z) {
        o[0] = x;
        o[1] = y;
        o[2] = z;
    }

    /** Скольжение центра c на (vx, vy, vz); stopOnGround — коснулся пола — стоп (гравитация не тянет по склону). */
    private void slide(double[] c, double vx, double vy, double vz, boolean stopOnGround) {
        final double close = 0.002;
        for (int iter = 0; iter < 5; iter++) {
            double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (len < 1e-7) {
                return;
            }
            sweep(c, vx, vy, vz);
            if (!hit) {
                c[0] += vx;
                c[1] += vy;
                c[2] += vz;
                return;
            }
            double dist = hitT * len;
            double nx = vx / len, ny = vy / len, nz = vz / len;
            if (dist >= close) {
                double k = dist - close;
                c[0] += nx * k;
                c[1] += ny * k;
                c[2] += nz * k;
                cpX -= nx * close;
                cpY -= ny * close;
                cpZ -= nz * close;
            }
            // Плоскость скольжения — через точку касания, нормаль к центру сферы.
            double sx = c[0] - cpX, sy = c[1] - cpY, sz = c[2] - cpZ;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-9) {
                return;
            }
            sx /= sl;
            sy /= sl;
            sz /= sl;
            // Нормаль в мире.
            double wx = sx / rx, wy = sy / ry, wz = sz / rz;
            double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
            normalX = (float) (wx / wl);
            normalY = (float) (wy / wl);
            normalZ = (float) (wz / wl);
            if (normalY > 0.7) {
                grounded = true;
            } else if (normalY < -0.7) {
                ceiling = true;
            } else {
                wall = true;
            }
            if (stopOnGround && normalY > 0.7) {
                return;
            }
            // Остаток пути — вдоль плоскости: конец исходного пути, спроецированный на неё.
            double destX = c[0] + vx - (dist >= close ? nx * (dist - close) : 0);
            double destY = c[1] + vy - (dist >= close ? ny * (dist - close) : 0);
            double destZ = c[2] + vz - (dist >= close ? nz * (dist - close) : 0);
            double d = (destX - cpX) * sx + (destY - cpY) * sy + (destZ - cpZ) * sz;
            double ndx = destX - sx * d, ndy = destY - sy * d, ndz = destZ - sz * d;
            vx = ndx - cpX;
            vy = ndy - cpY;
            vz = ndz - cpZ;
        }
    }

    /** Первое касание единичной сферы с центром c, движущейся на v (доля пути hitT 0..1, точка касания cp). */
    private void sweep(double[] c, double vx, double vy, double vz) {
        hit = false;
        hitT = 1;
        double bx = c[0], by = c[1], bz = c[2];
        double vv = vx * vx + vy * vy + vz * vz;
        for (int i = 0; i < count; i++) {
            int o = i * 9;
            double p1x = tris[o], p1y = tris[o + 1], p1z = tris[o + 2];
            double p2x = tris[o + 3], p2y = tris[o + 4], p2z = tris[o + 5];
            double p3x = tris[o + 6], p3y = tris[o + 7], p3z = tris[o + 8];
            double e1x = p2x - p1x, e1y = p2y - p1y, e1z = p2z - p1z;
            double e2x = p3x - p1x, e2y = p3y - p1y, e2z = p3z - p1z;
            double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1e-12) {
                continue;
            }
            nx /= nl;
            ny /= nl;
            nz /= nl;
            double dist = (bx - p1x) * nx + (by - p1y) * ny + (bz - p1z) * nz;
            if (dist < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
                dist = -dist;
            }
            double ndv = nx * vx + ny * vy + nz * vz;
            if (ndv >= 0) {
                continue; // удаляется от плоскости или вдоль неё
            }
            double t0 = (1 - dist) / ndv, t1 = (-1 - dist) / ndv;
            if (t0 > t1) {
                double tmp = t0;
                t0 = t1;
                t1 = tmp;
            }
            if (t0 > 1 || t1 < 0) {
                continue;
            }
            t0 = Math.max(0, t0);
            if (t0 >= hitT) {
                continue;
            }
            // Касание внутри грани.
            double ix = bx - nx + vx * t0, iy = by - ny + vy * t0, iz = bz - nz + vz * t0;
            if (inside(ix, iy, iz, p1x, p1y, p1z, e1x, e1y, e1z, e2x, e2y, e2z)) {
                hit = true;
                hitT = t0;
                cpX = ix;
                cpY = iy;
                cpZ = iz;
                continue;
            }
            // Вершины.
            vertex(bx, by, bz, vx, vy, vz, vv, p1x, p1y, p1z);
            vertex(bx, by, bz, vx, vy, vz, vv, p2x, p2y, p2z);
            vertex(bx, by, bz, vx, vy, vz, vv, p3x, p3y, p3z);
            // Рёбра.
            edge(bx, by, bz, vx, vy, vz, vv, p1x, p1y, p1z, p2x, p2y, p2z);
            edge(bx, by, bz, vx, vy, vz, vv, p2x, p2y, p2z, p3x, p3y, p3z);
            edge(bx, by, bz, vx, vy, vz, vv, p3x, p3y, p3z, p1x, p1y, p1z);
        }
    }

    private static boolean inside(double px, double py, double pz, double ax, double ay, double az, double e1x, double e1y,
                                  double e1z, double e2x, double e2y, double e2z) {
        double qx = px - ax, qy = py - ay, qz = pz - az;
        double d00 = e1x * e1x + e1y * e1y + e1z * e1z, d01 = e1x * e2x + e1y * e2y + e1z * e2z;
        double d11 = e2x * e2x + e2y * e2y + e2z * e2z;
        double d20 = qx * e1x + qy * e1y + qz * e1z, d21 = qx * e2x + qy * e2y + qz * e2z;
        double den = d00 * d11 - d01 * d01;
        if (den == 0) {
            return false;
        }
        double v = (d11 * d20 - d01 * d21) / den, w = (d00 * d21 - d01 * d20) / den;
        return v >= -1e-9 && w >= -1e-9 && v + w <= 1 + 1e-9;
    }

    private void vertex(double bx, double by, double bz, double vx, double vy, double vz, double vv,
                        double px, double py, double pz) {
        double a = vv;
        double b = 2 * (vx * (bx - px) + vy * (by - py) + vz * (bz - pz));
        double qx = px - bx, qy = py - by, qz = pz - bz;
        double c = qx * qx + qy * qy + qz * qz - 1;
        double t = lowestRoot(a, b, c, hitT);
        if (t >= 0) {
            hit = true;
            hitT = t;
            cpX = px;
            cpY = py;
            cpZ = pz;
        }
    }

    private void edge(double bx, double by, double bz, double vx, double vy, double vz, double vv,
                      double ax, double ay, double az, double cx, double cy, double cz) {
        double ex = cx - ax, ey = cy - ay, ez = cz - az;
        double tx = ax - bx, ty = ay - by, tz = az - bz;
        double ee = ex * ex + ey * ey + ez * ez;
        double ev = ex * vx + ey * vy + ez * vz;
        double et = ex * tx + ey * ty + ez * tz;
        double a = ee * -vv + ev * ev;
        double b = ee * (2 * (vx * tx + vy * ty + vz * tz)) - 2 * ev * et;
        double c = ee * (1 - (tx * tx + ty * ty + tz * tz)) + et * et;
        double t = lowestRoot(a, b, c, hitT);
        if (t >= 0) {
            double f = (ev * t - et) / ee;
            if (f >= 0 && f <= 1) {
                hit = true;
                hitT = t;
                cpX = ax + ex * f;
                cpY = ay + ey * f;
                cpZ = az + ez * f;
            }
        }
    }

    /** Наименьший корень a·t² + b·t + c = 0 в (0, max) или −1. */
    private static double lowestRoot(double a, double b, double c, double max) {
        if (Math.abs(a) < 1e-12) {
            return -1;
        }
        double det = b * b - 4 * a * c;
        if (det < 0) {
            return -1;
        }
        double s = Math.sqrt(det);
        double r1 = (-b - s) / (2 * a), r2 = (-b + s) / (2 * a);
        if (r1 > r2) {
            double tmp = r1;
            r1 = r2;
            r2 = tmp;
        }
        if (r1 > 0 && r1 < max) {
            return r1;
        }
        if (r2 > 0 && r2 < max && r1 >= 0) {
            return r2;
        }
        return -1;
    }
}
