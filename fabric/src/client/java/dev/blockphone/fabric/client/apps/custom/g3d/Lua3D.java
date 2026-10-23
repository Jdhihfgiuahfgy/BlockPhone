package dev.blockphone.fabric.client.apps.custom.g3d;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Таблица g3d для скриптов: модели, текстуры, камера, свет, кадр, статическая сцена (уровень) с запечённым светом,
 * лучи и столкновения. Без Minecraft — всё, что нужно от игры, даёт {@link Host} (так движок проверяется и без игры).
 */
public final class Lua3D {
    /** Что нужно от приложения. */
    public interface Host {
        /** Файл из папки приложения или null. */
        byte[] asset(String path);

        /** Картинка PNG → ARGB {ширина, высота, пиксели...} (бросает исключение, если не PNG). */
        int[] decodePng(byte[] png) throws Exception;

        /** Сколько настоящих пикселей экрана в единице gfx сейчас (бросает LuaError вне onDraw). */
        double pixelScale();

        /** Показать готовый кадр в прямоугольнике (x, y, w, h) в координатах gfx. */
        void present(int[] argb, int w, int h, float x, float y, float rw, float rh);

        /** Цвет из Lua ("#RRGGBB", "#AARRGGBB" или число). */
        int color(LuaValue v, int fallback);
    }

    public static final int MAX_MESH_VERTICES = 400_000;
    public static final int MAX_MESHES = 2000;
    public static final int MAX_STATIC_TRIANGLES = 300_000;
    public static final int MAX_FRAME_PIXELS = 640_000;

    private final Host host;
    public final Engine3D engine = new Engine3D();
    public final Scene scene = new Scene();
    private final List<Mesh> meshes = new ArrayList<>();
    private final List<Texture> textures = new ArrayList<>();
    private final Map<String, Integer> textureIds = new HashMap<>();
    private int meshVertices;
    private int meshCount;
    private boolean in3d;
    private float regionX, regionY, regionW, regionH;

    public Lua3D(Host host) {
        this.host = host;
    }

    private interface Fn {
        Varargs call(Varargs a);
    }

    private static LuaValue fn(Fn body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return body.call(args);
            }
        };
    }

    /** Кадр начат (g3d.begin) и не закончен. */
    public boolean drawing() {
        return in3d;
    }

    /** Конец onDraw: скрипт забыл g3d.finish — показываем то, что нарисовал. */
    public void endFrame() {
        if (in3d) {
            finish();
        }
        in3d = false;
    }

    /** Кадр прерван (ошибка скрипта): не показывать. */
    public void abortFrame() {
        in3d = false;
    }

    /** Приложение закрыто: остановить запекание. */
    public void release() {
        scene.cancel();
        in3d = false;
    }

    /** Таблица g3d для нового запуска скрипта (всё прежнее — модели, текстуры, сцена — сбрасывается). */
    public LuaTable install() {
        meshes.clear();
        textures.clear();
        textureIds.clear();
        meshVertices = 0;
        meshCount = 0;
        in3d = false;
        scene.clear();
        engine.sky = null;
        LuaTable g = new LuaTable();
        g.set("texture", fn(a -> {
            LuaTable o = a.opttable(2, new LuaTable());
            boolean linear = "linear".equals(o.get("filter").optjstring("nearest"));
            boolean mip = o.get("mipmap").optboolean(true);
            return LuaValue.valueOf(texture(a.checkjstring(1), linear, mip));
        }));
        g.set("mesh", fn(a -> LuaValue.valueOf(addMesh(meshFrom(a.checktable(1))))));
        g.set("box", fn(a -> LuaValue.valueOf(addMesh(Mesh.box((float) a.checkdouble(1), (float) a.checkdouble(2),
                (float) a.checkdouble(3), (float) a.optdouble(4, 0))))));
        g.set("plane", fn(a -> LuaValue.valueOf(addMesh(Mesh.plane((float) a.checkdouble(1), (float) a.checkdouble(2),
                (float) a.optdouble(3, 0))))));
        g.set("obj", fn(a -> {
            String path = a.checkjstring(1);
            byte[] data = host.asset(path);
            if (data == null) {
                throw new LuaError("model \"" + path + "\" not found in the app folder");
            }
            try {
                return LuaValue.valueOf(addMesh(Mesh.obj(new String(data, StandardCharsets.UTF_8), MAX_MESH_VERTICES - meshVertices)));
            } catch (RuntimeException e) {
                throw new LuaError("model \"" + path + "\": " + e.getMessage());
            }
        }));
        // Освободить модель (новый уровень — старые модели больше не нужны; лимит вершин возвращается).
        g.set("free", fn(a -> {
            int id = a.checkint(1);
            if (id >= 1 && id <= meshes.size() && meshes.get(id - 1) != null) {
                meshVertices -= meshes.get(id - 1).vertices();
                meshCount--;
                meshes.set(id - 1, null);
            }
            return LuaValue.NONE;
        }));
        g.set("camera", fn(a -> {
            engine.camX = (float) a.checkdouble(1);
            engine.camY = (float) a.checkdouble(2);
            engine.camZ = (float) a.checkdouble(3);
            engine.yaw = (float) a.optdouble(4, 0);
            engine.pitch = (float) Math.max(-89, Math.min(89, a.optdouble(5, 0)));
            engine.fov = (float) a.optdouble(6, engine.fov);
            return LuaValue.NONE;
        }));
        g.set("fog", fn(a -> {
            if (a.isnil(1)) {
                engine.fogFar = engine.fogNear = 0;
            } else {
                engine.fogColor = host.color(a.arg(1), 0xFF000000);
                engine.fogNear = (float) a.optdouble(2, 5);
                engine.fogFar = (float) a.optdouble(3, 30);
            }
            return LuaValue.NONE;
        }));
        g.set("light", fn(a -> {
            engine.setLight((float) a.checkdouble(1), (float) a.checkdouble(2), (float) a.checkdouble(3),
                    (float) Math.max(0, Math.min(1, a.optdouble(4, 0.45))));
            LuaTable o = a.opttable(5, null);
            if (o != null) {
                if (!o.get("sun").isnil()) {
                    int c = host.color(o.get("sun"), 0xFFFFFFFF);
                    engine.sunR = ((c >> 16) & 255) / 255f;
                    engine.sunG = ((c >> 8) & 255) / 255f;
                    engine.sunB = (c & 255) / 255f;
                }
                if (!o.get("ambient").isnil()) {
                    int c = host.color(o.get("ambient"), 0xFFFFFFFF);
                    engine.ambR = ((c >> 16) & 255) / 255f;
                    engine.ambG = ((c >> 8) & 255) / 255f;
                    engine.ambB = (c & 255) / 255f;
                }
            }
            return LuaValue.NONE;
        }));
        g.set("sky", fn(a -> {
            if (a.isnil(1)) {
                engine.sky = null;
                return LuaValue.NONE;
            }
            LuaTable o = a.checktable(1);
            int hor = host.color(o.get("horizon"), 0xFFC8DCEC);
            engine.sky = new int[]{host.color(o.get("top"), 0xFF4A7FC0), hor, host.color(o.get("bottom"), hor)};
            engine.skySun = o.get("sun").optboolean(true);
            engine.skySunColor = host.color(o.get("sunColor"), 0xFFFFF4D6);
            return LuaValue.NONE;
        }));
        g.set("begin", fn(a -> {
            double pixel = host.pixelScale();
            regionX = (float) a.checkdouble(1);
            regionY = (float) a.checkdouble(2);
            regionW = (float) Math.max(1, a.checkdouble(3));
            regionH = (float) Math.max(1, a.checkdouble(4));
            int clear = host.color(a.arg(5), 0xFF000000);
            double quality = Math.max(0.2, Math.min(1, a.optdouble(6, 1)));
            // Разрешение кадра — по настоящим пикселям экрана (чётко), но не больше MAX_FRAME_PIXELS.
            double fw = regionW * pixel * quality, fh = regionH * pixel * quality;
            double k = Math.min(1, Math.sqrt(MAX_FRAME_PIXELS / Math.max(1, fw * fh)));
            engine.begin(Math.max(8, (int) (fw * k)), Math.max(8, (int) (fh * k)), clear);
            in3d = true;
            return LuaValue.NONE;
        }));
        g.set("draw", fn(a -> {
            need();
            Mesh mesh = mesh(a.checkint(1));
            float x = (float) a.optdouble(2, 0), y = (float) a.optdouble(3, 0), z = (float) a.optdouble(4, 0);
            LuaTable o = a.opttable(5, new LuaTable());
            boolean lit = o.get("lit").optboolean(true);
            float[] probe = lit && o.get("probe").optboolean(true) && scene.baked() ? scene.probe(x, y + 0.5f, z) : null;
            engine.draw(mesh, tex(o.get("texture")), matrix(o), x, y, z, host.color(o.get("tint"), 0xFFFFFFFF), lit,
                    o.get("cull").optboolean(false), pickId(o), blend(o), probe);
            return LuaValue.NONE;
        }));
        g.set("sprite", fn(a -> {
            need();
            LuaTable o = a.opttable(7, new LuaTable());
            engine.sprite(tex(a.arg(1)), (float) a.checkdouble(2), (float) a.checkdouble(3), (float) a.checkdouble(4),
                    (float) a.checkdouble(5), (float) a.checkdouble(6), host.color(o.get("tint"), 0xFFFFFFFF), pickId(o), blend(o));
            return LuaValue.NONE;
        }));
        g.set("pointLight", fn(a -> {
            need();
            engine.pointLight((float) a.checkdouble(1), (float) a.checkdouble(2), (float) a.checkdouble(3),
                    host.color(a.arg(4), 0xFFFFFFFF), (float) a.optdouble(5, 4), (float) a.optdouble(6, 1));
            return LuaValue.NONE;
        }));
        g.set("finish", fn(a -> {
            need();
            finish();
            in3d = false;
            return LuaValue.NONE;
        }));
        g.set("pick", fn(a -> {
            // Что видно в точке экрана (координаты как у gfx): id объекта и расстояние до него.
            engine.flush();
            int px = (int) ((a.checkdouble(1) - regionX) / regionW * engine.w);
            int py = (int) ((a.checkdouble(2) - regionY) / regionH * engine.h);
            if (px < 0 || py < 0 || px >= engine.w || py >= engine.h) {
                return LuaValue.varargsOf(LuaValue.valueOf(0), LuaValue.valueOf(-1));
            }
            int i = py * engine.w + px;
            float d = engine.pickDepth[i] > 0 ? 1 / engine.pickDepth[i] : -1;
            return LuaValue.varargsOf(LuaValue.valueOf(engine.ids[i]), LuaValue.valueOf(d));
        }));
        g.set("project", fn(a -> {
            float[] p = engine.project((float) a.checkdouble(1), (float) a.checkdouble(2), (float) a.checkdouble(3));
            if (p == null || engine.w == 0) {
                return LuaValue.NIL;
            }
            return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(regionX + p[0] / engine.w * regionW),
                    LuaValue.valueOf(regionY + p[1] / engine.h * regionH), LuaValue.valueOf(p[2])});
        }));
        g.set("forward", fn(a -> {
            double y = Math.toRadians(a.checkdouble(1)), p = Math.toRadians(a.optdouble(2, 0));
            return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(Math.sin(y) * Math.cos(p)), LuaValue.valueOf(Math.sin(p)),
                    LuaValue.valueOf(Math.cos(y) * Math.cos(p))});
        }));
        g.set("right", fn(a -> {
            double y = Math.toRadians(a.checkdouble(1));
            return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(Math.cos(y)), LuaValue.valueOf(0), LuaValue.valueOf(-Math.sin(y))});
        }));
        g.set("stats", fn(a -> LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(engine.triangles), LuaValue.valueOf(engine.w),
                LuaValue.valueOf(engine.h), LuaValue.valueOf(engine.rasterMs), LuaValue.valueOf(Engine3D.THREADS)})));

        // --- Статическая сцена (уровень) ---
        g.set("static", fn(a -> {
            Mesh mesh = mesh(a.checkint(1));
            if (scene.triangles() + mesh.triangles() > MAX_STATIC_TRIANGLES) {
                throw new LuaError("static scene is too big (max " + MAX_STATIC_TRIANGLES + " triangles)");
            }
            float x = (float) a.optdouble(2, 0), y = (float) a.optdouble(3, 0), z = (float) a.optdouble(4, 0);
            LuaTable o = a.opttable(5, new LuaTable());
            return LuaValue.valueOf(scene.add(mesh, tex(o.get("texture")), matrix(o), x, y, z,
                    host.color(o.get("tint"), 0xFFFFFFFF), o.get("lit").optboolean(true), o.get("cull").optboolean(false),
                    o.get("solid").optboolean(true), o.get("id").optint(0)));
        }));
        g.set("clearStatic", fn(a -> {
            scene.clear();
            return LuaValue.NONE;
        }));
        g.set("drawStatic", fn(a -> {
            need();
            scene.draw(engine);
            return LuaValue.NONE;
        }));
        g.set("bake", fn(a -> {
            LuaTable o = a.opttable(1, new LuaTable());
            Scene.BakeSettings s = new Scene.BakeSettings();
            LuaValue sun = o.get("sun");
            if (sun.istable()) {
                s.sunX = (float) sun.get(1).checkdouble();
                s.sunY = (float) sun.get(2).checkdouble();
                s.sunZ = (float) sun.get(3).checkdouble();
            } else {
                s.sunX = engine.lightX;
                s.sunY = engine.lightY;
                s.sunZ = engine.lightZ;
            }
            float sunPower = (float) o.get("sunPower").optdouble(1);
            int sc = host.color(o.get("sunColor"), 0xFFFFF2DE);
            s.sunR = ((sc >> 16) & 255) / 255f * sunPower;
            s.sunG = ((sc >> 8) & 255) / 255f * sunPower;
            s.sunB = (sc & 255) / 255f * sunPower;
            float skyPower = (float) o.get("skyPower").optdouble(0.6);
            int kc = host.color(o.get("skyColor"), 0xFFC8D8F0);
            s.skyR = ((kc >> 16) & 255) / 255f * skyPower;
            s.skyG = ((kc >> 8) & 255) / 255f * skyPower;
            s.skyB = (kc & 255) / 255f * skyPower;
            s.bounce = (float) Math.max(0, Math.min(1, o.get("bounce").optdouble(0.35)));
            s.minLight = (float) Math.max(0, Math.min(1, o.get("minLight").optdouble(0.08)));
            s.aoDistance = (float) Math.max(0.3, Math.min(20, o.get("ao").optdouble(3)));
            s.density = (float) Math.max(0.5, Math.min(16, o.get("density").optdouble(4)));
            s.rays = Math.max(4, Math.min(64, o.get("rays").optint(16)));
            LuaValue lamps = o.get("lamps");
            if (lamps.istable()) {
                int n = Math.min(64, lamps.length());
                s.lamps = new float[n * 7];
                for (int i = 0; i < n; i++) {
                    LuaTable l = lamps.get(i + 1).checktable();
                    int c = host.color(l.get("color"), 0xFFFFFFFF);
                    float power = (float) l.get("power").optdouble(1);
                    s.lamps[i * 7] = (float) l.get(1).checkdouble();
                    s.lamps[i * 7 + 1] = (float) l.get(2).checkdouble();
                    s.lamps[i * 7 + 2] = (float) l.get(3).checkdouble();
                    s.lamps[i * 7 + 3] = ((c >> 16) & 255) / 255f * power;
                    s.lamps[i * 7 + 4] = ((c >> 8) & 255) / 255f * power;
                    s.lamps[i * 7 + 5] = (c & 255) / 255f * power;
                    s.lamps[i * 7 + 6] = (float) l.get("radius").optdouble(6);
                }
            }
            scene.bake(s);
            return LuaValue.NONE;
        }));
        g.set("bakeProgress", fn(a -> LuaValue.valueOf(scene.bakeProgress())));
        g.set("raycast", fn(a -> {
            float[] r = scene.raycast((float) a.checkdouble(1), (float) a.checkdouble(2), (float) a.checkdouble(3),
                    (float) a.checkdouble(4), (float) a.checkdouble(5), (float) a.checkdouble(6), (float) a.optdouble(7, 1000));
            if (r == null) {
                return LuaValue.NIL;
            }
            return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(r[0]), LuaValue.valueOf(r[1]), LuaValue.valueOf(r[2]),
                    LuaValue.valueOf(r[3]), LuaValue.valueOf((int) r[4])});
        }));
        g.set("move", fn(a -> {
            LuaTable o = a.opttable(7, new LuaTable());
            float[] r = scene.move((float) a.checkdouble(1), (float) a.checkdouble(2), (float) a.checkdouble(3),
                    (float) a.checkdouble(4), (float) a.checkdouble(5), (float) a.checkdouble(6),
                    (float) o.get("radius").optdouble(0.3), (float) o.get("height").optdouble(1.8),
                    (float) o.get("step").optdouble(0.35));
            return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(r[0]), LuaValue.valueOf(r[1]), LuaValue.valueOf(r[2]),
                    LuaValue.valueOf(r[3] > 0), LuaValue.valueOf(r[4] > 0), LuaValue.valueOf(r[5] > 0)});
        }));
        g.set("lightAt", fn(a -> {
            float[] p = scene.probe((float) a.checkdouble(1), (float) a.checkdouble(2), (float) a.checkdouble(3));
            return LuaValue.valueOf(p == null ? 1 : p[0]);
        }));
        return g;
    }

    private void need() {
        if (!in3d) {
            throw new LuaError("call g3d.begin(...) in onDraw first");
        }
    }

    private void finish() {
        engine.flush();
        host.present(engine.color, engine.w, engine.h, regionX, regionY, regionW, regionH);
    }

    private static float[] matrix(LuaTable o) {
        float scale = (float) o.get("scale").optdouble(1);
        return Mesh.matrix((float) o.get("yaw").optdouble(0), (float) o.get("pitch").optdouble(0),
                (float) o.get("roll").optdouble(0), (float) o.get("sx").optdouble(scale), (float) o.get("sy").optdouble(scale),
                (float) o.get("sz").optdouble(scale));
    }

    private static int pickId(LuaTable o) {
        return o.get("pick").optboolean(true) ? o.get("id").optint(0) : Engine3D.NO_PICK;
    }

    private static int blend(LuaTable o) {
        String b = o.get("blend").optjstring("");
        return switch (b) {
            case "add" -> Engine3D.BLEND_ADD;
            case "alpha" -> Engine3D.BLEND_ALPHA;
            case "" -> Engine3D.BLEND_NONE;
            default -> throw new LuaError("blend must be \"alpha\" or \"add\"");
        };
    }

    private Mesh meshFrom(LuaTable t) {
        LuaTable v = t.get("vertices").checktable();
        LuaTable ix = t.get("indices").istable() ? (LuaTable) t.get("indices") : null;
        int n = v.length() / 5;
        float[] pos = new float[n * 3];
        float[] uv = new float[n * 2];
        for (int k = 0; k < n; k++) {
            pos[k * 3] = (float) v.get(k * 5 + 1).checkdouble();
            pos[k * 3 + 1] = (float) v.get(k * 5 + 2).checkdouble();
            pos[k * 3 + 2] = (float) v.get(k * 5 + 3).checkdouble();
            uv[k * 2] = (float) v.get(k * 5 + 4).checkdouble();
            uv[k * 2 + 1] = (float) v.get(k * 5 + 5).checkdouble();
        }
        int[] idx;
        if (ix != null) {
            idx = new int[ix.length()];
            for (int k = 0; k < idx.length; k++) {
                idx[k] = ix.get(k + 1).checkint() - 1;
                if (idx[k] < 0 || idx[k] >= n) {
                    throw new LuaError("g3d.mesh: index " + (idx[k] + 1) + " is out of 1.." + n);
                }
            }
        } else {
            idx = new int[n - n % 3];
            for (int k = 0; k < idx.length; k++) {
                idx[k] = k;
            }
        }
        float[] normals = null;
        if (t.get("normals").istable()) {
            LuaTable nt = (LuaTable) t.get("normals");
            if (nt.length() != n * 3) {
                throw new LuaError("g3d.mesh: normals must have 3 numbers per vertex");
            }
            normals = new float[n * 3];
            for (int k = 0; k < n * 3; k++) {
                normals[k] = (float) nt.get(k + 1).checkdouble();
            }
            Mesh.normalize(normals);
        }
        return new Mesh(pos, uv, idx, normals);
    }

    private int addMesh(Mesh mesh) {
        if (meshCount >= MAX_MESHES || meshVertices + mesh.vertices() > MAX_MESH_VERTICES) {
            throw new LuaError("too many 3D models (max " + MAX_MESHES + ", " + MAX_MESH_VERTICES
                    + " vertices; free unused with g3d.free)");
        }
        meshVertices += mesh.vertices();
        meshCount++;
        meshes.add(mesh);
        return meshes.size();
    }

    private Mesh mesh(int id) {
        if (id < 1 || id > meshes.size() || meshes.get(id - 1) == null) {
            throw new LuaError("unknown 3D model " + id);
        }
        return meshes.get(id - 1);
    }

    private Texture tex(LuaValue v) {
        if (v.isnil()) {
            return null;
        }
        int id = v.checkint();
        if (id < 1 || id > textures.size()) {
            throw new LuaError("unknown 3D texture " + id);
        }
        return textures.get(id - 1);
    }

    private int texture(String path, boolean linear, boolean mip) {
        String key = path + (linear ? "|l" : "|n") + (mip ? "m" : "");
        Integer known = textureIds.get(key);
        if (known != null) {
            return known;
        }
        byte[] png = host.asset(path);
        if (png == null) {
            throw new LuaError("image \"" + path + "\" not found in the app folder");
        }
        int[] img;
        try {
            img = host.decodePng(png);
        } catch (Exception e) {
            throw new LuaError("image \"" + path + "\" is not a PNG");
        }
        int w = img[0], h = img[1];
        int[] argb = new int[w * h];
        System.arraycopy(img, 2, argb, 0, w * h);
        textures.add(new Texture(w, h, argb, linear, mip));
        textureIds.put(key, textures.size());
        return textures.size();
    }
}
