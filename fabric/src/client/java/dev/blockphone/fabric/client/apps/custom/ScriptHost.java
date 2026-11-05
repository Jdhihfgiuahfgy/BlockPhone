package dev.blockphone.fabric.client.apps.custom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.apps.AppDefinition;
import dev.blockphone.common.apps.AppPermission;
import dev.blockphone.common.apps.AppScript;
import dev.blockphone.common.protocol.packet.AppPackets;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.apps.custom.g3d.Lua3D;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Скрипт своего приложения (Lua, LuaJ) — песочница: только базовые функции Lua, string, table, math, bit32 и API телефона
 * (gfx, ui, screen, input, timer, storage, json, http, net, server, sound, clipboard, links, commands). Нет io, os, load,
 * dofile, require из файлов, debug, coroutine и доступа к Java; каждый вызов скрипта ограничен числом инструкций
 * (бесконечный цикл не повесит игру — скрипт останавливается с ошибкой, pcall её не перехватит).
 * Обратные вызовы скрипта — глобальные функции: onStart, onShow, onHide, onDraw(dt), onTick, onClick(x, y, button),
 * onRelease, onDrag(x, y, dx, dy), onScroll(amount, x, y), onKey(name), onChar(text), onBack(), onMessage(from, data).
 * Формат и примеры — docs/CUSTOM_APPS.md, раздел «Скрипты».
 */
final class ScriptHost {
    private static final long LOAD_BUDGET = 20_000_000;
    private static final long CALL_BUDGET = 3_000_000;
    private static final int HOOK_STEP = 1000;
    private static final int MAX_STRING = 1 << 20;
    private static final int MAX_HTTP_BYTES = 1 << 20;
    private static final int MAX_QUEUED_MESSAGES = 64;
    private static final List<String> SOUND_PREFIXES = List.of("ui.", "block.note_block.", "entity.experience_orb.pickup",
            "entity.player.levelup", "entity.arrow.hit_player", "block.chest.", "entity.item.pickup", "block.wooden_button.",
            "block.stone_button.", "entity.villager.", "block.anvil.land", "entity.generic.explode");
    // Четыре потока: «долгий запрос» приложения-мессенджера держит один до 10 с, остальным (картинки, отправка) хватит.
    private static final ExecutorService HTTP = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "BlockPhone App HTTP");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8)).build();
    /** Сообщения от других игроков для приложений, скрипт которых ещё не запущен. */
    private static final Map<String, Deque<Object[]>> PENDING = new HashMap<>();

    /** Превышен лимит инструкций — Error, а не исключение: pcall скрипта его не поймает. */
    private static final class Timeout extends Error {
        Timeout() {
            super("script runs too long", null, false, false);
        }
    }

    private final CustomApp owner;
    private final AppDefinition app;
    private final AppScript script;
    private final @Nullable AppStorage storage;
    private Globals globals;
    private LuaValue sethook;
    private long budget;
    private long used;
    private @Nullable String error;
    private boolean started;

    // Состояние экрана, которое меняет скрипт.
    boolean landscape;
    boolean fullscreen;
    boolean captureKeys;
    /** screen.background(true): onTick идёт и с закрытым телефоном (хост сетевой игры). */
    boolean background;
    /** screen.minimizeKey(true): клавиша телефона сворачивает приложение, даже когда оно забирает клавиши. */
    boolean minimizeKey;
    private @Nullable String title;

    // Рисование (только в onDraw).
    private @Nullable GuiGraphics g;
    private int depth;
    private int mouseX;
    private int mouseY;
    private long lastFrame;
    private final List<double[]> clicks = new ArrayList<>();
    private final Map<String, TextArea> inputs = new LinkedHashMap<>();
    private final Map<String, Identifier> textures = new HashMap<>();
    private final Map<String, int[]> textureSizes = new HashMap<>();

    // 3D (g3d): движок, модели, текстуры, сцена — в Lua3D; здесь — кадр-текстура для показа.
    private final Lua3D g3d = new Lua3D(new G3dHost());
    /** Фото, картинки по адресу, голос (разрешения photos, microphone). */
    final ScriptMedia media = new ScriptMedia(this);
    private @Nullable DynamicTexture frame;
    private @Nullable Identifier frameId;
    private int frameW;
    private int frameH;
    // Захват мыши (игры с обзором мышью).
    private boolean mouseLocked;
    private double lastCursorX = Double.NaN, lastCursorY;
    private double mouseDX, mouseDY;

    // Таймеры, ответы сервера/сети.
    private record Timer(int id, LuaValue fn, long period) {
    }

    private final Map<Integer, long[]> timerDue = new HashMap<>();
    private final Map<Integer, Timer> timers = new LinkedHashMap<>();
    private int nextTimer = 1;
    private final Deque<LuaValue> serverCallbacks = new ArrayDeque<>();
    private final Deque<LuaValue> varsCallbacks = new ArrayDeque<>();
    private final Deque<LuaValue> playersCallbacks = new ArrayDeque<>();
    private final Deque<Long> httpTimes = new ArrayDeque<>();
    private long toastAt;
    private String toast = "";

    ScriptHost(CustomApp owner, AppDefinition app, @Nullable AppStorage storage) {
        this.owner = owner;
        this.app = app;
        this.script = app.script();
        this.storage = storage;
        this.landscape = script.landscape();
        this.fullscreen = script.fullscreen();
    }

    // --- Запуск ---

    /** Создать окружение и выполнить главный файл (один раз). */
    void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            globals = sandbox();
            budget = LOAD_BUDGET;
            memoryAtCall = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            used = 0;
            LuaValue chunk = globals.load(script.sources().get(script.main()), "@" + script.main());
            chunk.call();
            callback("onStart");
            Deque<Object[]> pending = PENDING.remove(app.id());
            if (pending != null) {
                for (Object[] m : pending) {
                    deliver((String) m[0], (String) m[1]);
                }
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    boolean failed() {
        return error != null;
    }

    @Nullable String title() {
        return title;
    }

    private Globals sandbox() {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new org.luaj.vm2.lib.PackageLib()); // библиотеки регистрируются в package.loaded; сам package потом убирается
        g.load(new Bit32Lib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        g.load(new DebugLib());
        LoadState.install(g);
        LuaC.install(g);
        sethook = g.get("debug").get("sethook");
        // Убрать всё, что достаёт до файлов, загружает код или байткод, управляет сборщиком мусора.
        for (String name : new String[]{"debug", "dofile", "loadfile", "load", "loadstring", "collectgarbage", "package", "require"}) {
            g.set(name, LuaValue.NIL);
        }
        LuaValue string = g.get("string");
        string.set("dump", LuaValue.NIL);
        // Поиск по образцу со счётчиком шагов (в LuaJ он не виден лимиту инструкций — «.-b» по 1 МБ вешал игру).
        new LuaPatterns(n -> {
            used += n;
            if (used > budget) {
                throw new Timeout();
            }
        }).install((org.luaj.vm2.LuaTable) string);
        LuaValue rep = string.get("rep");
        string.set("rep", fn(a -> {
            long size = (long) a.checkjstring(1).length() * a.checkint(2);
            if (size > MAX_STRING) {
                throw new LuaError("string.rep: result is too big");
            }
            return rep.invoke(a);
        }));
        g.set("print", fn(a -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= a.narg(); i++) {
                sb.append(i > 1 ? "\t" : "").append(a.arg(i).tojstring());
            }
            BlockPhoneFabric.LOGGER.info("[app {}] {}", app.id(), sb);
            return LuaValue.NONE;
        }));
        // require — только файлы *.lua самого приложения: require("cards") → cards.lua, "lib.util" → lib/util.lua.
        LuaTable loaded = new LuaTable();
        g.set("require", fn(a -> {
            String name = a.checkjstring(1);
            LuaValue cached = loaded.get(name);
            if (!cached.isnil()) {
                return cached;
            }
            String path = name.replace('.', '/') + ".lua";
            String code = script.sources().get(path);
            if (code == null) {
                throw new LuaError("module '" + name + "' not found (" + path + ")");
            }
            LuaValue result = globals.load(code, "@" + path).call();
            loaded.set(name, result.isnil() ? LuaValue.TRUE : result);
            return loaded.get(name);
        }));
        // Счётчик инструкций: каждые HOOK_STEP инструкций; вышли за бюджет вызова — остановить.
        sethook.invoke(LuaValue.varargsOf(new LuaValue[]{fn(a -> {
            used += HOOK_STEP;
            if (used > budget) {
                throw new Timeout();
            }
            checkMemory();
            return LuaValue.NONE;
        }), LuaValue.valueOf(""), LuaValue.valueOf(HOOK_STEP)}));
        installApi(g);
        return g;
    }

    // --- Вызовы скрипта ---

    interface Fn {
        Varargs call(Varargs args);
    }

    static LuaValue fn(Fn body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return body.call(args);
            }
        };
    }

    /** Вызвать глобальную функцию скрипта (если есть); вернуть её результат. */
    private Varargs callback(String name, LuaValue... args) {
        if (error != null || globals == null) {
            return LuaValue.NONE;
        }
        LuaValue f = globals.get(name);
        return f.isfunction() ? invoke(f, LuaValue.varargsOf(args)) : LuaValue.NONE;
    }

    /** Вызвать функцию скрипта, если это функция (ответы на запросы, выбор фото, запись голоса). */
    void call(@Nullable LuaValue f, LuaValue... args) {
        if (f != null && f.isfunction()) {
            invoke(f, LuaValue.varargsOf(args));
        }
    }

    private Varargs invoke(LuaValue f, Varargs args) {
        if (error != null) {
            return LuaValue.NONE;
        }
        budget = CALL_BUDGET;
        used = 0;
        memoryAtCall = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        try {
            return f.invoke(args);
        } catch (Throwable e) {
            fail(e);
            return LuaValue.NONE;
        }
    }

    boolean mouseLocked() {
        return mouseLocked && error == null;
    }

    /** Приложение забирает память: остановить его раньше, чем игра упадёт с OutOfMemoryError. */
    private static final class MemoryLimit extends Error {
        MemoryLimit() {
            super("memory", null, false, false);
        }
    }

    private long memoryAtCall;
    private static long lastGc;

    private static final long CALL_MEMORY = 512L * 1024 * 1024;

    /**
     * Каждые HOOK_STEP инструкций: занятая память выросла за вызов скрипта больше чем на 512 МБ или свободной памяти
     * игры меньше 8 % — собрать мусор (не чаще раза в 5 с; в занятом есть и мусор) и, если всё ещё так, остановить
     * приложение (его данные освободятся).
     */
    private void checkMemory() {
        Runtime rt = Runtime.getRuntime();
        long usedNow = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        boolean full = usedNow > max * 0.92;
        if (!full && usedNow - memoryAtCall <= CALL_MEMORY) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastGc > 5000) {
            lastGc = now;
            System.gc();
            usedNow = rt.totalMemory() - rt.freeMemory();
        } else if (!full) {
            memoryAtCall = usedNow; // проверить пока нечем — новая точка отсчёта, проверка после следующей сборки
            return;
        }
        if (usedNow > max * 0.92 || usedNow - memoryAtCall > CALL_MEMORY) {
            throw new MemoryLimit();
        }
    }

    private void fail(Throwable e) {
        unlockMouse();
        if (e instanceof Timeout) {
            error = PhoneLang.tr("app.script.timeout");
        } else if (e instanceof LuaError le) {
            error = le.getMessage();
        } else if (e instanceof StackOverflowError) {
            error = PhoneLang.tr("app.script.stack");
        } else if (e instanceof OutOfMemoryError || e instanceof MemoryLimit) {
            error = PhoneLang.tr("app.script.memory");
        } else {
            error = e.toString();
        }
        BlockPhoneFabric.LOGGER.warn("[app {}] script stopped: {}", app.id(), error, e instanceof LuaError ? null : e);
        g = null;
        depth = 0;
    }

    // --- События от телефона ---

    // --- Свои звуки приложения (*.ogg/*.wav/*.mp3 из его папки) ---

    private final java.util.Map<Integer, dev.blockphone.fabric.client.sound.PhoneSounds.Handle> sounds = new java.util.HashMap<>();
    private int nextSound = 1;

    /** Сыграть звук из файлов приложения; возвращает номер (для sound.stop). Не больше 16 звуков сразу. */
    /** Сколько своих звуков приложения звучит одновременно (их сводит общий микшер). */
    static final int MAX_SOUNDS = 32;

    private int playOwnSound(String path, float volume, boolean loop) {
        return playOwnSound(path, volume, loop, Float.NaN, 0, 1);
    }

    /** pan = NaN — обычный звук; иначе объёмный (панорама, глухость, высота тона). */
    private int playOwnSound(String path, float volume, boolean loop, float pan, float muffle, float pitch) {
        String name = path.replace('\\', '/');
        byte[] data = script.assets().get(name);
        if (data == null || !dev.blockphone.common.apps.AppScript.isSound(name)) {
            throw new LuaError("sound \"" + path + "\" not found in the app folder");
        }
        sounds.values().removeIf(h -> !h.playing());
        if (sounds.size() >= MAX_SOUNDS) {
            return 0;
        }
        try {
            // Декодеру нужен файл: копия в кэше по отпечатку содержимого (один раз).
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
            String ext = name.substring(name.lastIndexOf('.'));
            java.nio.file.Path file = dev.blockphone.fabric.client.storage.ClientFiles.root().resolve("cache").resolve("appsounds")
                    .resolve(hash + ext);
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.write(file, data);
            }
            int id = nextSound++;
            float v = Math.max(0, Math.min(1, volume));
            sounds.put(id, dev.blockphone.fabric.client.sound.PhoneSounds.playApp(file, v, loop, pan, muffle, pitch));
            return id;
        } catch (Exception e) {
            throw new LuaError("sound: " + e.getMessage());
        }
    }

    private void stopSounds() {
        sounds.values().forEach(dev.blockphone.fabric.client.sound.PhoneSounds.Handle::stop);
        sounds.clear();
    }

    void onShow() {
        start();
        callback("onShow");
    }

    void onHide() {
        if (storage != null) {
            storage.flush();
        }
        media.onHide();
        unlockMouse();
        if (!displaying()) {
            stopSounds(); // приложение на экране в мире (телефон — джойстик) звучит и со свёрнутым телефоном
        }
        inputs.values().forEach(t -> t.setFocused(false));
        callback("onHide");
    }

    void tick() {
        if (storage != null) {
            storage.saveIfDue();
        }
        if (error != null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Timer t : List.copyOf(timers.values())) {
            long[] due = timerDue.get(t.id());
            if (due != null && now >= due[0]) {
                if (t.period() > 0) {
                    due[0] = now + t.period();
                } else {
                    timers.remove(t.id());
                    timerDue.remove(t.id());
                }
                invoke(t.fn(), LuaValue.NONE);
            }
        }
        String invite = dev.blockphone.fabric.client.screens.AppDisplays.takeInvite(app.id());
        if (invite != null) {
            int slash = invite.indexOf('/');
            var l = dev.blockphone.fabric.client.screens.AppDisplays.live(invite);
            callback("onDisplayInvite", LuaValue.valueOf(l != null ? l.owner() : invite.substring(0, slash)),
                    LuaValue.valueOf(invite.substring(slash + 1)), LuaValue.valueOf(l != null && l.mine()));
        }
        callback("onTick");
    }

    void render(GuiGraphics graphics, int mx, int my, int w, int h) {
        if (error != null) {
            renderError(graphics, mx, my, w, h);
            return;
        }
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        mouseX = mx;
        mouseY = my;
        pollMouseLock();
        g = graphics;
        basePose = new org.joml.Matrix3x2f(graphics.pose());
        webAreas.clear();
        uiRects.clear();
        depth = 0;
        graphics.enableScissor(0, 0, w, h);
        callback("onDraw", LuaValue.valueOf(dt));
        if (g != null) {
            try {
                g3d.endFrame(); // скрипт забыл g3d.finish — показываем то, что нарисовал
            } catch (RuntimeException e) {
                BlockPhoneFabric.LOGGER.warn("[apps] {}: 3D frame failed", app.id(), e);
                g3d.abortFrame();
            }
        } else {
            g3d.abortFrame();
        }
        // Скрипт мог забыть gfx.pop/unclip — возвращаем как было.
        if (g != null) {
            while (depth > 0) {
                graphics.pose().popMatrix();
                depth--;
            }
        }
        g = null;
        graphics.disableScissor();
        media.renderOverlay(graphics, mx, my, w, h);
        clicks.clear();
        lastWebAreas = List.copyOf(webAreas);
        lastUiRects = List.copyOf(uiRects);
        // Движение мыши над страницей (подсветка ссылок, меню по наведению).
        for (WebArea area : lastWebAreas) {
            int[] p = webPoint(area, mx, my);
            if (p != null) {
                area.view().mouseMove(p[0], p[1]);
            }
        }
        if (!toast.isEmpty() && now - toastAt < 3000) {
            int tw = Math.min(w - 16, (int) PhoneDraw.width(toast, 0.7f) + 16);
            int th = PhoneDraw.wrappedHeight(toast, tw - 12, 0.7f) + 8;
            int tx = (w - tw) / 2;
            int ty = h - th - 8;
            PhoneDraw.rawRoundedRect(graphics, tx, ty, tx + tw, ty + th, 4, 0xF0202838);
            PhoneDraw.wrapped(graphics, toast, tx + 6, ty + 4, tw - 12, 0xFFFFFFFF, 0.7f, false);
        }
    }

    /** Скрипт остановился: текст ошибки и «Перезапустить». */
    private void renderError(GuiGraphics graphics, int mx, int my, int w, int h) {
        graphics.fill(0, 0, w, h, PhoneTheme.background());
        PhoneDraw.text(graphics, PhoneLang.tr("app.script.stopped"), 8, 8, PhoneTheme.danger(), 0.85f, false);
        PhoneDraw.wrapped(graphics, error, 8, 22, w - 16, PhoneTheme.text(), 0.65f, false);
        PhoneDraw.button(graphics, 8, h - 26, w - 16, 18, PhoneLang.tr("app.script.restart"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
    }

    boolean mouseClicked(double x, double y, int button, int w, int h) {
        if (error == null && media.pickerOpen()) {
            return media.mouseClicked(x, y, button) || true;
        }
        if (error != null) {
            if (PhoneDraw.inside(x, y, 8, h - 26, w - 16, 18)) {
                owner.restartScript();
            }
            return true;
        }
        boolean onInput = false;
        for (TextArea area : inputs.values()) {
            if (area.mouseClicked(x, y, button)) {
                onInput = true;
            } else {
                area.setFocused(false);
            }
        }
        if (onInput) {
            webFocus = null;
            return true;
        }
        WebArea area = onUi(x, y) ? null : webAt(x, y);
        if (area != null) {
            int[] p = webPoint(area, x, y);
            webFocus = area.view();
            webPressed = area.view();
            area.view().setFocus(true);
            area.view().mouseButton(p[0], p[1], button, true);
            return true;
        }
        if (webFocus != null) {
            webFocus.setFocus(false);
            webFocus = null;
        }
        clicks.add(new double[]{x, y, button});
        callback("onClick", LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(button + 1));
        return true;
    }

    boolean mouseReleased(double x, double y, int button) {
        inputs.values().forEach(TextArea::mouseReleased);
        if (webPressed != null) {
            for (WebArea area : lastWebAreas) {
                if (area.view() == webPressed) {
                    org.joml.Vector2f p = basePose.transformPosition(new org.joml.Vector2f((float) x, (float) y));
                    org.joml.Vector2f local = new org.joml.Matrix3x2f(area.pose()).invert().transformPosition(p);
                    int px = (int) ((local.x - area.x()) / area.w() * area.view().pageWidth());
                    int py = (int) ((local.y - area.y()) / area.h() * area.view().pageHeight());
                    area.view().mouseButton(px, py, button, false);
                }
            }
            webPressed = null;
            return true;
        }
        callback("onRelease", LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(button + 1));
        return true;
    }

    boolean mouseDragged(double x, double y, double dx, double dy) {
        if (webPressed != null) {
            for (WebArea area : lastWebAreas) {
                if (area.view() == webPressed) {
                    org.joml.Vector2f p = basePose.transformPosition(new org.joml.Vector2f((float) x, (float) y));
                    org.joml.Vector2f local = new org.joml.Matrix3x2f(area.pose()).invert().transformPosition(p);
                    area.view().mouseMove((int) ((local.x - area.x()) / area.w() * area.view().pageWidth()),
                            (int) ((local.y - area.y()) / area.h() * area.view().pageHeight()));
                }
            }
            return true;
        }
        for (TextArea area : inputs.values()) {
            if (area.isFocused() && area.mouseDragged(x, y)) {
                return true;
            }
        }
        callback("onDrag", LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(dx), LuaValue.valueOf(dy));
        return true;
    }

    boolean mouseScrolled(double x, double y, double amount) {
        if (media.pickerOpen()) {
            return media.mouseScrolled(amount) || true;
        }
        WebArea web = webAt(x, y);
        if (web != null) {
            int[] p = webPoint(web, x, y);
            web.view().wheel(p[0], p[1], amount);
            return true;
        }
        for (TextArea area : inputs.values()) {
            if (area.mouseScrolled(x, y, amount)) {
                return true;
            }
        }
        callback("onScroll", LuaValue.valueOf(amount), LuaValue.valueOf(x), LuaValue.valueOf(y));
        return true;
    }

    boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        for (TextArea area : inputs.values()) {
            if (area.isFocused()) {
                if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                    String id = idOf(area);
                    callback("onSubmit", LuaValue.valueOf(id), LuaValue.valueOf(area.text()));
                    return true;
                }
                return area.keyPressed(event);
            }
        }
        if (webFocus != null) {
            webFocus.key(event.key(), event.scancode(), event.modifiers());
            return true;
        }
        if (minimizeKey && dev.blockphone.fabric.client.BlockPhoneClient.openPhoneKey.matches(event)) {
            return false; // телефон свернётся (PhoneScreen), приложение получит onHide
        }
        String name = keyName(event.key());
        if (name == null) {
            return false;
        }
        Varargs r = callback("onKey", LuaValue.valueOf(name));
        return captureKeys || r.arg1().toboolean();
    }

    boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        for (TextArea area : inputs.values()) {
            if (area.isFocused()) {
                return area.charTyped(event);
            }
        }
        if (webFocus != null) {
            webFocus.typeChar(event.codepoint());
            return true;
        }
        callback("onChar", LuaValue.valueOf(new String(Character.toChars(event.codepoint()))));
        return captureKeys;
    }

    void debugChar(int codepoint) {
        charTyped(new net.minecraft.client.input.CharacterEvent(codepoint, 0));
    }

    void debugSubmit(String id, String text) {
        callback("onSubmit", LuaValue.valueOf(id), LuaValue.valueOf(text));
    }

    void debugKey(String name) {
        callback("onKey", LuaValue.valueOf(name));
    }

    boolean onBack() {
        if (media.onBack()) {
            return true;
        }
        if (mouseLocked) {
            unlockMouse(); // Esc сначала отпускает мышь
            callback("onMouseUnlock");
            return true;
        }
        return callback("onBack").arg1().toboolean();
    }

    boolean typingText() {
        return error == null && (inputs.values().stream().anyMatch(TextArea::isFocused) || (webFocus != null && webFocus.editing()));
    }

    boolean capturesKeyboard() {
        return error == null && (captureKeys || webFocus != null || inputs.values().stream().anyMatch(TextArea::isFocused));
    }

    /**
     * «На ходу»: держать курсор у телефона и после отпускания клавиши курсора — пока вводят текст (своё поле или поле на
     * странице браузера) или приложение забирает клавиши. Просто открытая страница (видео) курсор не держит.
     */
    boolean holdsCursor() {
        return error == null && (captureKeys || typingText() || (webFocus != null && webFocus.editing()));
    }

    private String idOf(TextArea area) {
        for (Map.Entry<String, TextArea> e : inputs.entrySet()) {
            if (e.getValue() == area) {
                return e.getKey();
            }
        }
        return "";
    }

    void release() {
        released = true;
        dev.blockphone.fabric.client.screens.AppDisplays.unbind(display);
        if (storage != null) {
            storage.flush();
        }
        media.release();
        unlockMouse();
        closeWebViews();
        Minecraft mc = Minecraft.getInstance();
        g3d.release();
        if (frameId != null) {
            mc.getTextureManager().release(frameId);
            frameId = null;
            frame = null;
        }
        textures.values().forEach(id -> mc.getTextureManager().release(id));
        textures.clear();
        timers.clear();
        timerDue.clear();
    }

    // --- Сеть: ответы ---

    void onServerResult(AppPackets.CallResult r) {
        LuaValue cb = serverCallbacks.poll();
        if (cb != null && cb.isfunction()) {
            invoke(cb, LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(!r.error()), LuaValue.valueOf(r.toast()), toTable(r.values())}));
        }
    }

    void onServerVariables(Map<String, String> values) {
        LuaValue cb = varsCallbacks.poll();
        if (cb != null && cb.isfunction()) {
            invoke(cb, LuaValue.varargsOf(new LuaValue[]{toTable(values)}));
        }
    }

    void onPlayers(List<String> names) {
        LuaValue cb = playersCallbacks.poll();
        if (cb != null && cb.isfunction()) {
            LuaTable t = new LuaTable();
            for (int i = 0; i < names.size(); i++) {
                t.set(i + 1, LuaValue.valueOf(names.get(i)));
            }
            invoke(cb, LuaValue.varargsOf(new LuaValue[]{t}));
        }
    }

    void deliver(String from, String payload) {
        LuaValue data;
        try {
            data = fromJson(JsonParser.parseString(payload));
        } catch (RuntimeException e) {
            data = LuaValue.valueOf(payload);
        }
        callback("onMessage", LuaValue.valueOf(from), data);
    }

    /** Сообщение пришло, а скрипт ещё не запущен — отдать при запуске. */
    static void queue(String appId, String from, String payload) {
        Deque<Object[]> q = PENDING.computeIfAbsent(appId, k -> new ArrayDeque<>());
        if (q.size() >= MAX_QUEUED_MESSAGES) {
            q.poll();
        }
        q.add(new Object[]{from, payload});
    }

    static void resetPending() {
        PENDING.clear();
    }

    private static LuaTable toTable(Map<String, String> map) {
        LuaTable t = new LuaTable();
        map.forEach((k, v) -> t.set(k, LuaValue.valueOf(v)));
        return t;
    }

    // --- API ---

    void need(AppPermission permission) {
        if (!app.has(permission)) {
            throw new LuaError("permission \"" + permission.key() + "\" is not declared in app.json");
        }
    }

    GuiGraphics gfx() {
        if (g == null) {
            throw new LuaError("gfx.* works only inside onDraw");
        }
        return g;
    }

    private static int color(LuaValue v, int fallback) {
        if (v.isnil()) {
            return fallback;
        }
        if (v.type() == LuaValue.TSTRING) {
            String s = v.tojstring().strip();
            if (s.startsWith("#")) {
                s = s.substring(1);
            }
            try {
                long c = Long.parseLong(s, 16);
                return s.length() <= 6 ? (int) (0xFF000000L | c) : (int) c;
            } catch (NumberFormatException e) {
                throw new LuaError("bad color \"" + v.tojstring() + "\" (use \"#RRGGBB\" or 0xAARRGGBB)");
            }
        }
        long c = (long) v.checkdouble();
        return c <= 0xFFFFFF && c >= 0 ? (int) (0xFF000000L | c) : (int) c;
    }

    static int i(Varargs a, int n) {
        return (int) Math.floor(a.checkdouble(n));
    }

    private void installApi(Globals G) {
        Minecraft mc = Minecraft.getInstance();
        LuaTable appT = new LuaTable();
        appT.set("id", app.id());
        appT.set("version", app.version());
        appT.set("player", mc.getUser().getName());
        appT.set("lang", PhoneLang.current());
        G.set("app", appT);

        // --- gfx ---
        LuaTable gfx = new LuaTable();
        gfx.set("clear", fn(a -> {
            GuiGraphics gg = gfx();
            gg.fill(-10000, -10000, 10000, 10000, color(a.arg(1), PhoneTheme.background()));
            return LuaValue.NONE;
        }));
        gfx.set("rect", fn(a -> {
            int x = i(a, 1);
            int y = i(a, 2);
            gfx().fill(x, y, x + i(a, 3), y + i(a, 4), color(a.arg(5), 0xFFFFFFFF));
            return LuaValue.NONE;
        }));
        gfx.set("border", fn(a -> {
            PhoneDraw.outline(gfx(), i(a, 1), i(a, 2), i(a, 3), i(a, 4), color(a.arg(5), 0xFFFFFFFF));
            return LuaValue.NONE;
        }));
        gfx.set("roundRect", fn(a -> {
            int x = i(a, 1);
            int y = i(a, 2);
            PhoneDraw.rawRoundedRect(gfx(), x, y, x + i(a, 3), y + i(a, 4), Math.max(0, i(a, 5)), color(a.arg(6), 0xFFFFFFFF));
            return LuaValue.NONE;
        }));
        gfx.set("circle", fn(a -> {
            int cx = i(a, 1);
            int cy = i(a, 2);
            int r = Math.max(1, i(a, 3));
            PhoneDraw.rawRoundedRect(gfx(), cx - r, cy - r, cx + r, cy + r, r, color(a.arg(4), 0xFFFFFFFF));
            return LuaValue.NONE;
        }));
        gfx.set("gradient", fn(a -> {
            int x = i(a, 1);
            int y = i(a, 2);
            gfx().fillGradient(x, y, x + i(a, 3), y + i(a, 4), color(a.arg(5), 0xFFFFFFFF), color(a.arg(6), 0xFF000000));
            return LuaValue.NONE;
        }));
        gfx.set("line", fn(a -> {
            line(gfx(), a.checkdouble(1), a.checkdouble(2), a.checkdouble(3), a.checkdouble(4),
                    Math.max(1, a.optint(6, 1)), color(a.arg(5), 0xFFFFFFFF));
            return LuaValue.NONE;
        }));
        gfx.set("panel", fn(a -> {
            int x = i(a, 1);
            int y = i(a, 2);
            PhoneDraw.card(gfx(), x, y, x + i(a, 3), y + i(a, 4), false);
            return LuaValue.NONE;
        }));
        gfx.set("text", fn(a -> {
            PhoneDraw.text(gfx(), a.checkjstring(1), (float) a.checkdouble(2), (float) a.checkdouble(3), color(a.arg(4), PhoneTheme.text()),
                    (float) a.optdouble(5, 1), a.optboolean(6, false));
            return LuaValue.NONE;
        }));
        gfx.set("textCenter", fn(a -> {
            PhoneDraw.centeredText(gfx(), a.checkjstring(1), (float) a.checkdouble(2), (float) a.checkdouble(3),
                    color(a.arg(4), PhoneTheme.text()), (float) a.optdouble(5, 1), a.optboolean(6, false));
            return LuaValue.NONE;
        }));
        gfx.set("textWidth", fn(a -> LuaValue.valueOf(PhoneDraw.width(a.checkjstring(1), (float) a.optdouble(2, 1)))));
        gfx.set("wrap", fn(a -> {
            float scale = (float) a.optdouble(3, 1);
            List<net.minecraft.network.chat.FormattedText> parts = PhoneDraw.font().getSplitter()
                    .splitLines(a.checkjstring(1), Math.max(10, (int) (a.checkdouble(2) / scale)), PhoneDraw.style());
            LuaTable t = new LuaTable();
            for (int k = 0; k < parts.size(); k++) {
                t.set(k + 1, LuaValue.valueOf(parts.get(k).getString()));
            }
            return t;
        }));
        gfx.set("urlImage", fn(media::drawUrlImage));
        gfx.set("image", fn(a -> {
            String path = a.checkjstring(1);
            Identifier id = texture(path);
            int[] size = textureSizes.get(path);
            int x = i(a, 2);
            int y = i(a, 3);
            int w = a.isnil(4) ? size[0] : i(a, 4);
            int h = a.isnil(5) ? size[1] : i(a, 5);
            float u = (float) a.optdouble(6, 0);
            float v = (float) a.optdouble(7, 0);
            int rw = a.isnil(8) ? size[0] : i(a, 8);
            int rh = a.isnil(9) ? size[1] : i(a, 9);
            gfx().blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, rw, rh, size[0], size[1], color(a.arg(10), 0xFFFFFFFF));
            return LuaValue.NONE;
        }));
        gfx.set("imageSize", fn(a -> {
            texture(a.checkjstring(1));
            int[] size = textureSizes.get(a.checkjstring(1));
            return LuaValue.varargsOf(LuaValue.valueOf(size[0]), LuaValue.valueOf(size[1]));
        }));
        gfx.set("push", fn(a -> {
            if (depth >= 32) {
                throw new LuaError("gfx.push: too deep");
            }
            gfx().pose().pushMatrix();
            depth++;
            return LuaValue.NONE;
        }));
        gfx.set("pop", fn(a -> {
            if (depth > 0) {
                gfx().pose().popMatrix();
                depth--;
            }
            return LuaValue.NONE;
        }));
        gfx.set("translate", fn(a -> {
            gfx().pose().translate((float) a.checkdouble(1), (float) a.checkdouble(2));
            return LuaValue.NONE;
        }));
        gfx.set("scale", fn(a -> {
            float sx = (float) a.checkdouble(1);
            gfx().pose().scale(sx, (float) a.optdouble(2, sx));
            return LuaValue.NONE;
        }));
        gfx.set("rotate", fn(a -> {
            gfx().pose().rotate((float) Math.toRadians(a.checkdouble(1)));
            return LuaValue.NONE;
        }));
        gfx.set("clip", fn(a -> {
            int x = i(a, 1);
            int y = i(a, 2);
            gfx().enableScissor(x, y, x + i(a, 3), y + i(a, 4));
            return LuaValue.NONE;
        }));
        gfx.set("unclip", fn(a -> {
            gfx().disableScissor();
            return LuaValue.NONE;
        }));
        gfx.set("nine", fn(a -> {
            nine(a.checkjstring(1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), Math.max(1, a.optint(6, 4)), color(a.arg(7), 0xFFFFFFFF));
            return LuaValue.NONE;
        }));
        G.set("gfx", gfx);

        // --- ui (кнопки и поля; рисуются в onDraw) ---
        LuaTable ui = new LuaTable();
        ui.set("button", fn(a -> {
            int x = i(a, 1);
            int y = i(a, 2);
            int w = i(a, 3);
            int h = i(a, 4);
            String style = a.optjstring(6, "normal");
            PhoneDraw.ButtonStyle s = switch (style) {
                case "primary" -> PhoneDraw.ButtonStyle.PRIMARY;
                case "danger" -> PhoneDraw.ButtonStyle.DANGER;
                case "selected" -> PhoneDraw.ButtonStyle.SELECTED;
                default -> PhoneDraw.ButtonStyle.NORMAL;
            };
            org.joml.Vector2f local = toLocal(mouseX, mouseY);
            PhoneDraw.button(gfx(), x, y, w, h, a.checkjstring(5), (int) local.x, (int) local.y, s);
            uiRect(x, y, w, h);
            for (Iterator<double[]> it = clicks.iterator(); it.hasNext(); ) {
                double[] c = it.next();
                org.joml.Vector2f p = toLocal((float) c[0], (float) c[1]);
                if (c[2] == 0 && PhoneDraw.inside(p.x, p.y, x, y, w, h)) {
                    it.remove();
                    return LuaValue.TRUE;
                }
            }
            return LuaValue.FALSE;
        }));
        // Кнопка со своими картинками: обычная и (необязательно) при наведении; картинка тянется «девяткой» (края не мнутся).
        ui.set("imageButton", fn(a -> {
            int x = i(a, 1);
            int y = i(a, 2);
            int w = i(a, 3);
            int h = i(a, 4);
            org.joml.Vector2f local = toLocal(mouseX, mouseY);
            boolean hover = PhoneDraw.inside(local.x, local.y, x, y, w, h);
            String img = hover && !a.isnil(6) ? a.checkjstring(6) : a.checkjstring(5);
            nine(img, x, y, w, h, Math.max(0, a.optint(9, 4)), 0xFFFFFFFF);
            if (!a.isnil(7)) {
                PhoneDraw.centeredText(gfx(), a.checkjstring(7), x + w / 2f, y + h / 2f - 3.5f, color(a.arg(8), 0xFFFFFFFF), 0.8f, true);
            }
            for (Iterator<double[]> it = clicks.iterator(); it.hasNext(); ) {
                double[] c = it.next();
                org.joml.Vector2f p = toLocal((float) c[0], (float) c[1]);
                if (c[2] == 0 && PhoneDraw.inside(p.x, p.y, x, y, w, h)) {
                    it.remove();
                    return LuaValue.TRUE;
                }
            }
            return LuaValue.FALSE;
        }));
        ui.set("hovered", fn(a -> {
            org.joml.Vector2f p = toLocal(mouseX, mouseY);
            return LuaValue.valueOf(PhoneDraw.inside(p.x, p.y, i(a, 1), i(a, 2), i(a, 3), i(a, 4)));
        }));
        ui.set("input", fn(a -> {
            String id = a.checkjstring(1);
            TextArea area = inputs.computeIfAbsent(id, k -> {
                TextArea t = new TextArea(a.optboolean(7, false), 0.75f);
                t.setMaxLength(1000);
                return t;
            });
            area.setBounds(i(a, 2), i(a, 3), i(a, 4), i(a, 5));
            area.setPlaceholder(a.optjstring(6, ""));
            area.render(gfx(), mouseX, mouseY);
            return LuaValue.valueOf(area.text());
        }));
        ui.set("setText", fn(a -> {
            TextArea area = inputs.get(a.checkjstring(1));
            if (area != null) {
                area.setText(a.optjstring(2, ""));
            }
            return LuaValue.NONE;
        }));
        ui.set("getText", fn(a -> {
            TextArea area = inputs.get(a.checkjstring(1));
            return LuaValue.valueOf(area != null ? area.text() : "");
        }));
        ui.set("focus", fn(a -> {
            inputs.forEach((k, v) -> v.setFocused(k.equals(a.optjstring(1, ""))));
            return LuaValue.NONE;
        }));
        ui.set("focused", fn(a -> {
            TextArea area = inputs.get(a.checkjstring(1));
            return LuaValue.valueOf(area != null && area.isFocused());
        }));
        G.set("ui", ui);

        // --- theme ---
        LuaTable theme = new LuaTable();
        theme.set("accent", fn(a -> LuaValue.valueOf(PhoneTheme.accent() & 0xFFFFFFFFL)));
        theme.set("onAccent", fn(a -> LuaValue.valueOf(PhoneTheme.onAccent() & 0xFFFFFFFFL)));
        theme.set("text", fn(a -> LuaValue.valueOf(PhoneTheme.text() & 0xFFFFFFFFL)));
        theme.set("secondary", fn(a -> LuaValue.valueOf(PhoneTheme.secondaryText() & 0xFFFFFFFFL)));
        theme.set("background", fn(a -> LuaValue.valueOf(PhoneTheme.background() & 0xFFFFFFFFL)));
        theme.set("card", fn(a -> LuaValue.valueOf(PhoneTheme.card() & 0xFFFFFFFFL)));
        theme.set("danger", fn(a -> LuaValue.valueOf(PhoneTheme.danger() & 0xFFFFFFFFL)));
        theme.set("dark", fn(a -> LuaValue.valueOf(PhoneTheme.dark())));
        G.set("theme", theme);

        // --- screen ---
        LuaTable screen = new LuaTable();
        screen.set("width", fn(a -> LuaValue.valueOf(owner.contentWidth())));
        screen.set("height", fn(a -> LuaValue.valueOf(owner.contentHeight())));
        screen.set("landscape", fn(a -> {
            if (!a.isnil(1)) {
                landscape = a.toboolean(1);
            }
            return LuaValue.valueOf(landscape);
        }));
        screen.set("fullscreen", fn(a -> {
            if (!a.isnil(1)) {
                fullscreen = a.toboolean(1);
            }
            return LuaValue.valueOf(fullscreen);
        }));
        screen.set("title", fn(a -> {
            title = a.isnil(1) ? null : a.checkjstring(1);
            return LuaValue.NONE;
        }));
        screen.set("captureKeys", fn(a -> {
            captureKeys = a.optboolean(1, true);
            return LuaValue.NONE;
        }));
        screen.set("background", fn(a -> {
            background = a.optboolean(1, true);
            return LuaValue.NONE;
        }));
        screen.set("minimizeKey", fn(a -> {
            minimizeKey = a.optboolean(1, true);
            return LuaValue.NONE;
        }));
        screen.set("close", fn(a -> {
            if (owner.phone() != null) {
                owner.phone().goHome();
            }
            return LuaValue.NONE;
        }));
        G.set("screen", screen);

        // --- input ---
        LuaTable input = new LuaTable();
        input.set("mouse", fn(a -> LuaValue.varargsOf(LuaValue.valueOf(mouseX), LuaValue.valueOf(mouseY))));
        input.set("down", fn(a -> LuaValue.valueOf(keyDown(a.checkjstring(1)))));
        // Захват мыши: курсор прячется, движение мыши — в input.mouseDelta() и onMouseMove(dx, dy); Esc — отпустить.
        input.set("lockMouse", fn(a -> {
            if (a.optboolean(1, true)) {
                lockMouse();
            } else {
                unlockMouse();
            }
            return LuaValue.NONE;
        }));
        input.set("mouseLocked", fn(a -> LuaValue.valueOf(mouseLocked)));
        input.set("mouseDelta", fn(a -> {
            Varargs r = LuaValue.varargsOf(LuaValue.valueOf(mouseDX), LuaValue.valueOf(mouseDY));
            mouseDX = 0;
            mouseDY = 0;
            return r;
        }));
        G.set("input", input);
        installDisplay(G);

        // --- time, timer ---
        LuaTable time = new LuaTable();
        long started = System.currentTimeMillis();
        time.set("now", fn(a -> LuaValue.valueOf((double) System.currentTimeMillis())));
        time.set("clock", fn(a -> LuaValue.valueOf((System.currentTimeMillis() - started) / 1000.0)));
        time.set("date", fn(a -> LuaValue.valueOf(java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern(a.optjstring(1, "dd.MM.yyyy HH:mm"))))));
        G.set("time", time);
        LuaTable timer = new LuaTable();
        timer.set("after", fn(a -> addTimer(a.checkdouble(1), a.checkfunction(2), false)));
        timer.set("every", fn(a -> addTimer(a.checkdouble(1), a.checkfunction(2), true)));
        timer.set("cancel", fn(a -> {
            timers.remove(a.checkint(1));
            timerDue.remove(a.checkint(1));
            return LuaValue.NONE;
        }));
        G.set("timer", timer);

        // --- json ---
        LuaTable json = new LuaTable();
        json.set("encode", fn(a -> LuaValue.valueOf(toJson(a.arg(1), 0).toString())));
        json.set("decode", fn(a -> {
            try {
                return fromJson(JsonParser.parseString(a.checkjstring(1)));
            } catch (RuntimeException e) {
                throw new LuaError("json.decode: " + e.getMessage());
            }
        }));
        G.set("json", json);

        // --- storage ---
        LuaTable store = new LuaTable();
        store.set("get", fn(a -> {
            need(AppPermission.STORAGE);
            String v = storage != null ? storage.values().get(a.checkjstring(1)) : null;
            if (v == null) {
                return a.arg(2);
            }
            try {
                return fromJson(JsonParser.parseString(v));
            } catch (RuntimeException e) {
                return LuaValue.valueOf(v);
            }
        }));
        store.set("set", fn(a -> {
            need(AppPermission.STORAGE);
            // Раньше значение только попадало в память и пропадало при перезапуске игры — теперь пишется на диск.
            boolean ok = storage != null && storage.putScript(a.checkjstring(1), toJson(a.arg(2), 0).toString());
            return LuaValue.valueOf(ok);
        }));
        G.set("storage", store);

        // --- toast, sound, clipboard, links, commands ---
        G.set("notify", fn(a -> {
            need(AppPermission.NOTIFICATIONS);
            long now = System.currentTimeMillis();
            while (!notifyTimes.isEmpty() && now - notifyTimes.peek() > 60_000) {
                notifyTimes.poll();
            }
            if (notifyTimes.size() >= 20) {
                return LuaValue.FALSE;
            }
            notifyTimes.add(now);
            AppNotifications.show(app, a.checkjstring(1), a.optjstring(2, ""), a.optjstring(3, ""));
            return LuaValue.TRUE;
        }));
        G.set("unnotify", fn(a -> {
            need(AppPermission.NOTIFICATIONS);
            AppNotifications.remove(app, a.optjstring(1, ""));
            return LuaValue.NONE;
        }));
        G.set("toast", fn(a -> {
            toast = a.checkjstring(1);
            toastAt = System.currentTimeMillis();
            return LuaValue.NONE;
        }));
        LuaTable sound = new LuaTable();
        sound.set("play", fn(a -> {
            String name = a.checkjstring(1).replace("minecraft:", "");
            // Объёмный звук: 4-й параметр — панорама −1..1, 5-й — глухость 0..1 (за стеной), 3-й — высота тона.
            float pan = a.isnoneornil(4) ? Float.NaN : (float) a.checkdouble(4);
            if (dev.blockphone.common.apps.AppScript.isSound(name)) {
                return LuaValue.valueOf(playOwnSound(name, (float) a.optdouble(2, 1), false, pan, (float) a.optdouble(5, 0),
                        (float) a.optdouble(3, 1)));
            }
            // Шаги и падение на блоки (block.stone.step, block.grass.fall…) — для игр с ходьбой.
            boolean footstep = name.matches("block\\.[a-z_]+\\.(step|fall)");
            if (!footstep && SOUND_PREFIXES.stream().noneMatch(name::startsWith)) {
                throw new LuaError("sound \"" + name + "\" is not allowed (ui.*, block.note_block.*, ...)");
            }
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.withDefaultNamespace(name));
            if (event != null) {
                float pitch = (float) Math.max(0.5, Math.min(2, a.optdouble(3, 1)));
                float volume = (float) Math.max(0, Math.min(1, a.optdouble(2, 1)));
                Minecraft game = Minecraft.getInstance();
                if (!Float.isNaN(pan) && game.player != null) {
                    // Панорама ванильного звука: источник в 2 блоках от игрока под нужным углом (OpenAL сам разведёт по ушам).
                    double side = Math.asin(Math.max(-1, Math.min(1, pan)));
                    double yaw = Math.toRadians(game.player.getYRot());
                    double fx = -Math.sin(yaw), fz = Math.cos(yaw);
                    double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
                    double dx = fx * Math.cos(side) + rx * Math.sin(side), dz = fz * Math.cos(side) + rz * Math.sin(side);
                    game.getSoundManager().play(new SimpleSoundInstance(event, net.minecraft.sounds.SoundSource.MASTER, volume, pitch,
                            net.minecraft.util.RandomSource.create(), game.player.getX() + dx * 2, game.player.getEyeY(), game.player.getZ() + dz * 2));
                } else {
                    game.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
                }
            }
            return LuaValue.NONE;
        }));
        sound.set("place", fn(a -> {
            // Подвинуть играющий объёмный звук: sound.place(номер, панорама, глухость).
            dev.blockphone.fabric.client.sound.PhoneSounds.Handle h = sounds.get(a.checkint(1));
            if (h != null) {
                h.place((float) a.checkdouble(2), (float) a.optdouble(3, 0));
            }
            return LuaValue.NONE;
        }));
        sound.set("loop", fn(a -> LuaValue.valueOf(playOwnSound(a.checkjstring(1), (float) a.optdouble(2, 1), true))));
        // Громкость (множитель 0..2) и высота тона (0.25..4) играющего своего звука на ходу: мотор, ветер, сирена.
        sound.set("tune", fn(a -> {
            dev.blockphone.fabric.client.sound.PhoneSounds.Handle h = sounds.get(a.checkint(1));
            if (h != null) {
                h.tune((float) a.optdouble(2, 1), (float) a.optdouble(3, 1));
            }
            return LuaValue.NONE;
        }));
        sound.set("stop", fn(a -> {
            if (a.isnoneornil(1)) {
                stopSounds();
            } else {
                dev.blockphone.fabric.client.sound.PhoneSounds.Handle h = sounds.remove(a.checkint(1));
                if (h != null) {
                    h.stop();
                }
            }
            return LuaValue.NONE;
        }));
        G.set("sound", sound);
        LuaTable clipboard = new LuaTable();
        clipboard.set("set", fn(a -> {
            need(AppPermission.CLIPBOARD);
            Minecraft.getInstance().keyboardHandler.setClipboard(a.checkjstring(1));
            return LuaValue.NONE;
        }));
        G.set("clipboard", clipboard);
        G.set("openUrl", fn(a -> {
            need(AppPermission.LINKS);
            String url = a.checkjstring(1).strip();
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw new LuaError("openUrl: only http(s) links");
            }
            confirm(PhoneLang.tr("app.confirm_url") + "\n" + url, () -> net.minecraft.util.Util.getPlatform().openUri(url));
            return LuaValue.NONE;
        }));
        G.set("runCommand", fn(a -> {
            need(AppPermission.COMMANDS);
            String command = a.checkjstring(1).strip().replaceFirst("^/", "");
            confirm(PhoneLang.tr("app.confirm_command") + "\n/" + command, () -> {
                if (mc.player != null) {
                    mc.player.connection.sendCommand(command);
                }
            });
            return LuaValue.NONE;
        }));

        // --- http ---
        LuaTable http = new LuaTable();
        http.set("get", fn(a -> {
            http("GET", a.checkjstring(1), null, a.optfunction(2, null), a.opttable(3, null));
            return LuaValue.NONE;
        }));
        http.set("post", fn(a -> {
            LuaValue body = a.arg(2);
            String text = body.istable() ? toJson(body, 0).toString() : body.checkjstring();
            LuaTable headers = a.opttable(4, null);
            if (body.istable()) {
                headers = headers == null ? new LuaTable() : headers;
                if (headers.get("Content-Type").isnil()) {
                    headers.set("Content-Type", "application/json");
                }
            }
            http("POST", a.checkjstring(1), text, a.optfunction(3, null), headers);
            return LuaValue.NONE;
        }));
        http.set("upload", fn(media::upload));
        G.set("http", http);
        G.set("images", media.imagesTable());
        media.install(G);

        // --- net (игроки с этим же приложением) ---
        LuaTable net = new LuaTable();
        net.set("send", fn(a -> {
            need(AppPermission.MULTIPLAYER);
            relay(a.checkjstring(1), a.arg(2));
            return LuaValue.NONE;
        }));
        net.set("broadcast", fn(a -> {
            need(AppPermission.MULTIPLAYER);
            relay("", a.arg(1));
            return LuaValue.NONE;
        }));
        net.set("players", fn(a -> {
            need(AppPermission.MULTIPLAYER);
            playersCallbacks.add(a.checkfunction(1));
            if (viaPlugin()) {
                ClientNetwork.send(new AppPackets.RequestPlayers(app.id()));
            } else {
                // Без плагина — игроки облачной комнаты (ответ на следующем такте, как от сервера).
                List<String> names = relay().players();
                Minecraft.getInstance().execute(() -> onPlayers(names));
            }
            return LuaValue.NONE;
        }));
        net.set("online", fn(a -> LuaValue.valueOf(ClientNetwork.serverHello() != null || relay().online())));
        // Как идёт связь: "plugin" — через плагин сервера, "cloud" — через облачную комнату, "connecting", "taken" —
        // ник уже занят в комнате, "none" — никак.
        net.set("mode", fn(a -> {
            if (viaPlugin()) {
                return LuaValue.valueOf("plugin");
            }
            return LuaValue.valueOf(switch (relay().status()) {
                case ONLINE -> "cloud";
                case CONNECTING -> "connecting";
                case NAME_TAKEN -> "taken";
                case OFF -> "none";
            });
        }));
        // Комната по коду: играть с разных серверов и из одиночной игры (без плагина).
        net.set("code", fn(a -> relay().code() == null ? LuaValue.NIL : LuaValue.valueOf(relay().code())));
        net.set("newCode", fn(a -> {
            need(AppPermission.MULTIPLAYER);
            String code = CloudRelay.newCode();
            relay().joinCode(code);
            return LuaValue.valueOf(code);
        }));
        net.set("joinCode", fn(a -> {
            need(AppPermission.MULTIPLAYER);
            return LuaValue.valueOf(relay().joinCode(a.checkjstring(1)));
        }));
        net.set("leaveCode", fn(a -> {
            relay().joinCode(null);
            return LuaValue.NONE;
        }));
        G.set("net", net);

        // --- server (серверная часть приложения) ---
        LuaTable server = new LuaTable();
        server.set("call", fn(a -> {
            need(AppPermission.SERVER);
            Map<String, String> inputs = new LinkedHashMap<>();
            LuaTable t = a.opttable(2, new LuaTable());
            for (LuaValue k = t.next(LuaValue.NIL).arg1(); !k.isnil(); k = t.next(k).arg1()) {
                inputs.put(k.tojstring(), t.get(k).tojstring());
            }
            serverCallbacks.add(a.arg(3).isfunction() ? a.arg(3) : LuaValue.NIL);
            ClientNetwork.send(new AppPackets.Call(app.id(), a.checkjstring(1), "script", inputs));
            return LuaValue.NONE;
        }));
        server.set("vars", fn(a -> {
            need(AppPermission.SERVER);
            varsCallbacks.add(a.checkfunction(1));
            ClientNetwork.send(new AppPackets.RequestVariables(app.id(), a.optjstring(2, "script")));
            return LuaValue.NONE;
        }));
        G.set("server", server);
        G.set("g3d", g3d.install());
        G.set("web", installWeb());
    }

    private LuaValue addTimer(double seconds, LuaValue fn, boolean repeat) {
        if (timers.size() >= 64) {
            throw new LuaError("too many timers (max 64)");
        }
        long ms = Math.max(50, (long) (seconds * 1000));
        int id = nextTimer++;
        timers.put(id, new Timer(id, fn, repeat ? ms : 0));
        timerDue.put(id, new long[]{System.currentTimeMillis() + ms});
        return LuaValue.valueOf(id);
    }

    private void confirm(String text, Runnable action) {
        PhoneScreen phone = owner.phone();
        if (phone != null) {
            phone.showDialog(new PhoneDialog(CustomApps.name(app), text, PhoneLang.tr("app.confirm"), false, action));
        }
    }

    /** Сетевые сообщения — через плагин сервера (если он есть и не выбрана комната по коду), иначе — облачная комната. */
    /** Облачный сервер комнат этого приложения (его "relay" или общий). */
    private CloudRelay relay() {
        return CloudRelay.forApp(app);
    }

    private boolean viaPlugin() {
        return ClientNetwork.serverHello() != null && relay().code() == null;
    }

    private void relay(String target, LuaValue data) {
        String payload = toJson(data, 0).toString();
        if (payload.length() > AppPackets.MAX_RELAY) {
            throw new LuaError("net: message is too big (max " + AppPackets.MAX_RELAY + " chars)");
        }
        if (viaPlugin()) {
            ClientNetwork.send(new AppPackets.Relay(app.id(), target, payload));
        } else if (relay().online()) {
            relay().send(app.id(), target, payload);
        } else {
            throw new LuaError("net: offline (no BlockPhone on the server and no cloud room)");
        }
    }

    private void http(String method, String url, @Nullable String body, @Nullable LuaValue callback, @Nullable LuaTable headers) {
        http(method, url, body == null ? null : body.getBytes(StandardCharsets.UTF_8), null, callback, headers);
    }

    /** Адрес для запроса приложения: только http(s) и только сайты из "hosts" (иначе — ошибка скрипта). */
    URI checkedUri(String url) {
        need(AppPermission.INTERNET);
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new LuaError("http: bad url");
        }
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new LuaError("http: only http(s) urls");
        }
        if (!script.hostAllowed(uri.getHost())) {
            throw new LuaError("http: host \"" + uri.getHost() + "\" is not in \"hosts\" of app.json");
        }
        return uri;
    }

    /** Запрос с телом из байтов (фото, звук — {@link ScriptMedia}); contentType — заголовок Content-Type или null. */
    void http(String method, String url, byte @Nullable [] body, @Nullable String contentType, @Nullable LuaValue callback,
              @Nullable LuaTable headers) {
        URI uri = checkedUri(url);
        long now = System.currentTimeMillis();
        while (!httpTimes.isEmpty() && now - httpTimes.peek() > 60_000) {
            httpTimes.poll();
        }
        if (httpTimes.size() >= 60) {
            throw new LuaError("http: too many requests (max 60 per minute)");
        }
        httpTimes.add(now);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "BlockPhone/" + BlockPhoneFabric.VERSION + " app/" + app.id());
        if (headers != null) {
            for (LuaValue k = headers.next(LuaValue.NIL).arg1(); !k.isnil(); k = headers.next(k).arg1()) {
                String name = k.tojstring();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("cookie") || lower.equals("host") || lower.equals("user-agent") || lower.startsWith("sec-")) {
                    continue;
                }
                request.header(name, headers.get(k).tojstring());
            }
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpRequest built = request.build();
        HTTP.execute(() -> {
            int status = 0;
            String text;
            try {
                // Только интернет: адреса домашней сети и этого компьютера (роутер, localhost, «в сети» по DNS) — нельзя.
                for (java.net.InetAddress address : java.net.InetAddress.getAllByName(built.uri().getHost())) {
                    if (localAddress(address)) {
                        throw new java.io.IOException("local network addresses are not allowed (" + address.getHostAddress() + ")");
                    }
                }
                HttpResponse<InputStream> response = CLIENT.send(built, HttpResponse.BodyHandlers.ofInputStream());
                status = response.statusCode();
                try (InputStream in = response.body()) {
                    byte[] bytes = in.readNBytes(MAX_HTTP_BYTES + 1);
                    text = bytes.length > MAX_HTTP_BYTES ? "" : new String(bytes, StandardCharsets.UTF_8);
                    if (bytes.length > MAX_HTTP_BYTES) {
                        status = -1;
                    }
                }
            } catch (Exception e) {
                text = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            int code = status;
            String result = text;
            Minecraft.getInstance().execute(() -> {
                if (callback != null && callback.isfunction()) {
                    invoke(callback, LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(code >= 200 && code < 300),
                            LuaValue.valueOf(code), LuaValue.valueOf(result)}));
                }
            });
        });
    }


    /**
     * Скачать файл (картинка, звук) по адресу из "hosts": те же проверки, что у http, свой предел — 120 в минуту.
     * Ответ — в потоке рендера: done(bytes) или fail(причина).
     */
    void fetchBytes(String url, int maxBytes, java.util.function.Consumer<byte[]> done, java.util.function.Consumer<String> fail) {
        URI uri = checkedUri(url);
        long now = System.currentTimeMillis();
        while (!fetchTimes.isEmpty() && now - fetchTimes.peek() > 60_000) {
            fetchTimes.poll();
        }
        if (fetchTimes.size() >= 120) {
            fail.accept("too many downloads");
            return;
        }
        fetchTimes.add(now);
        HttpRequest built = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "BlockPhone/" + BlockPhoneFabric.VERSION + " app/" + app.id()).GET().build();
        HTTP.execute(() -> {
            byte[] result = null;
            String error = null;
            try {
                for (java.net.InetAddress address : java.net.InetAddress.getAllByName(built.uri().getHost())) {
                    if (localAddress(address)) {
                        throw new java.io.IOException("local network addresses are not allowed");
                    }
                }
                HttpResponse<InputStream> response = CLIENT.send(built, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    byte[] bytes = in.readNBytes(maxBytes + 1);
                    if (response.statusCode() / 100 != 2) {
                        error = "HTTP " + response.statusCode();
                    } else if (bytes.length > maxBytes) {
                        error = "file is too big";
                    } else {
                        result = bytes;
                    }
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            byte[] ok = result;
            String err = error;
            Minecraft.getInstance().execute(() -> {
                if (ok != null) {
                    done.accept(ok);
                } else {
                    fail.accept(err);
                }
            });
        });
    }

    private final Deque<Long> fetchTimes = new ArrayDeque<>();
    private final Deque<Long> notifyTimes = new ArrayDeque<>();

    /** Нажали на уведомление этого приложения — onNotification(метка). */
    void onNotification(String tag) {
        start();
        callback("onNotification", LuaValue.valueOf(tag));
    }

    // --- 3D ---

    /** Что движку g3d нужно от игры: файлы приложения, PNG, масштаб экрана, показ кадра. */

    // --- Режим экрана (display): картинка приложения на экране в мире ---

    private static final int DISPLAY_MAX_PIXELS = 640_000;
    private final DisplaySource display = new DisplaySource();
    /** Сейчас идёт onDisplay: g3d рисует в кадр экрана, display.* — тоже. */
    private boolean inDisplay;
    private int[] displayFrame = new int[0];
    private int displayW = 384;
    private int displayH = 216;
    private long displayLast;
    private final List<dev.blockphone.fabric.client.screens.AppDisplays.Text> displayTexts = new ArrayList<>();
    private final Map<String, int[]> displayImages = new HashMap<>();
    private boolean released;

    /** Приложение показывает что-то на экране в мире (тогда оно работает и с закрытым телефоном). */
    boolean displaying() {
        return dev.blockphone.fabric.client.screens.AppDisplays.boundKey(display) != null;
    }

    private final class DisplaySource implements dev.blockphone.fabric.client.screens.AppDisplays.Source {
        @Override
        public String appId() {
            return app.id();
        }

        @Override
        public boolean alive() {
            return started && !released && error == null;
        }

        @Override
        public int width() {
            return displayW;
        }

        @Override
        public int height() {
            return displayH;
        }

        @Override
        public int @Nullable [] frame(long now) {
            if (!alive()) {
                return null;
            }
            if (displayFrame.length != displayW * displayH) {
                displayFrame = new int[displayW * displayH];
            }
            float dt = displayLast == 0 ? 0 : Math.min(0.25f, (now - displayLast) / 1000f);
            displayLast = now;
            displayTexts.clear();
            inDisplay = true;
            try {
                callback("onDisplay", LuaValue.valueOf(dt), LuaValue.valueOf(displayW), LuaValue.valueOf(displayH));
                try {
                    g3d.endFrame();
                } catch (RuntimeException e) {
                    g3d.abortFrame();
                }
            } finally {
                inDisplay = false;
            }
            return displayFrame;
        }

        @Override
        public List<dev.blockphone.fabric.client.screens.AppDisplays.Text> texts() {
            return displayTexts;
        }
    }

    private void needDisplayFrame() {
        if (!inDisplay) {
            throw new LuaError("display drawing works only inside onDisplay");
        }
    }

    /** Размер кадра для экрана: по ширине opts.w (по умолчанию 384) и пропорциям экрана. */
    private void displaySize(dev.blockphone.fabric.client.screens.ScreenData.Screen s, LuaTable o) {
        int w = Math.max(64, Math.min(960, o.get("w").optint(384)));
        int h = o.get("h").isnil() ? Math.round(w * s.heightBlocks() / (float) Math.max(1, s.widthBlocks())) : o.get("h").checkint();
        h = Math.max(48, Math.min(720, h));
        if (w * h > DISPLAY_MAX_PIXELS) {
            double k = Math.sqrt(DISPLAY_MAX_PIXELS / (double) (w * h));
            w = (int) (w * k);
            h = (int) (h * k);
        }
        displayW = w;
        displayH = h;
        displayFrame = new int[w * h];
    }

    private static LuaTable screenInfo(dev.blockphone.fabric.client.screens.Screens.Live l, net.minecraft.world.phys.Vec3 eye) {
        LuaTable t = new LuaTable();
        dev.blockphone.fabric.client.screens.ScreenData.Screen s = l.def();
        t.set("owner", l.owner());
        t.set("id", s.id);
        t.set("name", s.name);
        t.set("mine", LuaValue.valueOf(l.mine()));
        t.set("width", s.widthBlocks());
        t.set("height", s.heightBlocks());
        t.set("app", s.app.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(s.app));
        t.set("distance", eye == null ? 0 : s.center().distanceTo(eye));
        return t;
    }

    private void installDisplay(Globals G) {
        LuaTable d = new LuaTable();
        Minecraft mc = Minecraft.getInstance();
        // Свои экраны (в этом измерении).
        d.set("screens", fn(a -> {
            need(AppPermission.DISPLAY);
            LuaTable out = new LuaTable();
            var eye = mc.player != null ? mc.player.getEyePosition() : null;
            for (var l : dev.blockphone.fabric.client.screens.Screens.all()) {
                if (l.mine()) {
                    out.set(out.length() + 1, screenInfo(l, eye));
                }
            }
            return out;
        }));
        // Экраны рядом (до 64 блоков), на которых сейчас это приложение — к ним можно подключиться.
        d.set("nearby", fn(a -> {
            need(AppPermission.DISPLAY);
            LuaTable out = new LuaTable();
            var eye = mc.player != null ? mc.player.getEyePosition() : null;
            double reach = Math.max(4, Math.min(64, a.optdouble(1, 64)));
            for (var l : dev.blockphone.fabric.client.screens.Screens.all()) {
                if (l.on() && app.id().equals(l.def().app) && (eye == null || l.def().center().distanceTo(eye) <= reach)) {
                    out.set(out.length() + 1, screenInfo(l, eye));
                }
            }
            return out;
        }));
        // Вывести приложение на свой экран (игрок — ведущий).
        d.set("start", fn(a -> {
            need(AppPermission.DISPLAY);
            var s = dev.blockphone.fabric.client.screens.ScreenStore.screen(a.checkjstring(1));
            if (s == null || mc.player == null) {
                return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf("no such screen"));
            }
            displaySize(s, a.opttable(2, new LuaTable()));
            s.app = app.id();
            s.appTitle = CustomApps.name(app);
            s.on = true;
            s.mirror = false;
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
            dev.blockphone.fabric.client.screens.AppDisplays.bind(display,
                    dev.blockphone.fabric.client.screens.AppDisplays.key(mc.getUser().getName(), s.id), true);
            return LuaValue.TRUE;
        }));
        // Подключиться к экрану другого игрока (или своему), где уже это приложение.
        d.set("join", fn(a -> {
            need(AppPermission.DISPLAY);
            String key = dev.blockphone.fabric.client.screens.AppDisplays.key(a.checkjstring(1), a.checkjstring(2));
            var l = dev.blockphone.fabric.client.screens.AppDisplays.live(key);
            if (l == null || !app.id().equals(l.def().app)) {
                return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf("the screen does not show this app"));
            }
            displaySize(l.def(), a.opttable(3, new LuaTable()));
            dev.blockphone.fabric.client.screens.AppDisplays.bind(display, key, l.mine());
            return LuaValue.TRUE;
        }));
        // Отключиться; ведущий ещё и убирает приложение со своего экрана.
        d.set("stop", fn(a -> {
            need(AppPermission.DISPLAY);
            String key = dev.blockphone.fabric.client.screens.AppDisplays.boundKey(display);
            if (key != null && dev.blockphone.fabric.client.screens.AppDisplays.isHost(display)) {
                var l = dev.blockphone.fabric.client.screens.AppDisplays.live(key);
                if (l != null && l.mine() && app.id().equals(l.def().app)) {
                    l.def().app = "";
                    l.def().appTitle = "";
                    dev.blockphone.fabric.client.screens.ScreenStore.changed();
                }
            }
            dev.blockphone.fabric.client.screens.AppDisplays.unbind(display);
            return LuaValue.NONE;
        }));
        d.set("current", fn(a -> {
            String key = dev.blockphone.fabric.client.screens.AppDisplays.boundKey(display);
            var l = key == null ? null : dev.blockphone.fabric.client.screens.AppDisplays.live(key);
            if (l == null) {
                return LuaValue.NIL;
            }
            LuaTable t = screenInfo(l, mc.player != null ? mc.player.getEyePosition() : null);
            t.set("host", LuaValue.valueOf(dev.blockphone.fabric.client.screens.AppDisplays.isHost(display)));
            t.set("w", displayW);
            t.set("h", displayH);
            return t;
        }));
        d.set("size", fn(a -> LuaValue.varargsOf(LuaValue.valueOf(displayW), LuaValue.valueOf(displayH))));
        // Режим джойстика: телефон закрывается, в руках набок; клавиши и мышь — сюда (input.down).
        d.set("control", fn(a -> {
            need(AppPermission.DISPLAY);
            boolean on = a.optboolean(1, true);
            String hint = a.isnil(2) ? null : a.checkjstring(2);
            if (on && owner.phone() != null && Minecraft.getInstance().screen == owner.phone()) {
                owner.phone().closeImmediately(); // без анимации — иначе она закроет и джойстик
            }
            return LuaValue.valueOf(dev.blockphone.fabric.client.screens.AppDisplays.control(display, on, hint));
        }));
        d.set("controlling", fn(a -> LuaValue.valueOf(dev.blockphone.fabric.client.screens.AppDisplays.controls(display))));
        // Рисование в кадр экрана (только в onDisplay): заливка, прямоугольник, картинка, текст шрифтом игры.
        d.set("clear", fn(a -> {
            needDisplayFrame();
            java.util.Arrays.fill(displayFrame, color(a.arg(1), 0xFF000000) | 0xFF000000);
            return LuaValue.NONE;
        }));
        d.set("rect", fn(a -> {
            needDisplayFrame();
            fillDisplay((int) Math.floor(a.checkdouble(1)), (int) Math.floor(a.checkdouble(2)), (int) Math.ceil(a.checkdouble(3)),
                    (int) Math.ceil(a.checkdouble(4)), color(a.arg(5), 0xFFFFFFFF));
            return LuaValue.NONE;
        }));
        d.set("image", fn(a -> {
            needDisplayFrame();
            int[] img = displayImage(a.checkjstring(1));
            LuaTable o = a.opttable(6, new LuaTable());
            blitDisplay(img, (int) Math.floor(a.checkdouble(2)), (int) Math.floor(a.checkdouble(3)),
                    (int) Math.round(a.optdouble(4, img[0])), (int) Math.round(a.optdouble(5, img[1])),
                    (int) Math.round(Math.max(0, Math.min(1, o.get("alpha").optdouble(1))) * 255));
            return LuaValue.NONE;
        }));
        d.set("text", fn(a -> {
            needDisplayFrame();
            if (displayTexts.size() >= 400) {
                return LuaValue.NONE;
            }
            LuaTable o = a.opttable(4, new LuaTable());
            String align = o.get("align").optjstring("left");
            String text = a.checkjstring(1);
            displayTexts.add(new dev.blockphone.fabric.client.screens.AppDisplays.Text(text.length() > 200 ? text.substring(0, 200) : text,
                    (float) a.checkdouble(2), (float) a.checkdouble(3), (float) Math.max(2, Math.min(200, o.get("size").optdouble(10))),
                    color(o.get("color"), 0xFFFFFFFF) | 0xFF000000, align.equals("center") ? 1 : align.equals("right") ? 2 : 0,
                    o.get("shadow").optboolean(true)));
            return LuaValue.NONE;
        }));
        d.set("textWidth", fn(a -> {
            double size = a.optdouble(2, 10);
            return LuaValue.valueOf(mc.font.width(a.checkjstring(1)) * size / mc.font.lineHeight);
        }));
        G.set("display", d);
    }

    private void fillDisplay(int x, int y, int w, int h, int color) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(displayW, x + w), y1 = Math.min(displayH, y + h);
        int alpha = color >>> 24;
        for (int yy = y0; yy < y1; yy++) {
            int row = yy * displayW;
            for (int xx = x0; xx < x1; xx++) {
                displayFrame[row + xx] = alpha == 255 ? color : blendPixel(displayFrame[row + xx], color, alpha);
            }
        }
    }

    private static int blendPixel(int dst, int src, int a) {
        int r = ((src >> 16 & 255) * a + (dst >> 16 & 255) * (255 - a)) / 255;
        int g = ((src >> 8 & 255) * a + (dst >> 8 & 255) * (255 - a)) / 255;
        int b = ((src & 255) * a + (dst & 255) * (255 - a)) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Картинка PNG из папки приложения: {ширина, высота, пиксели…} (кэш). */
    private int[] displayImage(String path) {
        int[] img = displayImages.get(path);
        if (img != null) {
            return img;
        }
        byte[] png = script.assets().get(path);
        if (png == null) {
            throw new LuaError("image \"" + path + "\" not found in the app folder");
        }
        try {
            img = new G3dHost().decodePng(png);
        } catch (Exception e) {
            throw new LuaError("image \"" + path + "\" is not a PNG");
        }
        if (displayImages.size() > 64) {
            displayImages.clear();
        }
        displayImages.put(path, img);
        return img;
    }

    /** Вписать картинку {w, h, пиксели…} в прямоугольник кадра (ближайший пиксель, прозрачность). */
    private void blitDisplay(int[] img, int x, int y, int w, int h, int alpha) {
        int iw = img[0], ih = img[1];
        if (w <= 0 || h <= 0 || iw <= 0 || ih <= 0) {
            return;
        }
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(displayW, x + w), y1 = Math.min(displayH, y + h);
        for (int yy = y0; yy < y1; yy++) {
            int sy = (int) ((yy - y) * (long) ih / h);
            int row = yy * displayW;
            for (int xx = x0; xx < x1; xx++) {
                int sx = (int) ((xx - x) * (long) iw / w);
                int c = img[2 + sy * iw + sx];
                int a = (c >>> 24) * alpha / 255;
                if (a >= 255) {
                    displayFrame[row + xx] = c;
                } else if (a > 0) {
                    displayFrame[row + xx] = blendPixel(displayFrame[row + xx], c, a);
                }
            }
        }
    }

    private final class G3dHost implements Lua3D.Host {
        @Override
        public byte[] asset(String path) {
            return script.assets().get(path);
        }

        @Override
        public int[] decodePng(byte[] png) throws Exception {
            dev.blockphone.fabric.client.photo.ImageOps.checkSize(png, "app image");
            try (NativeImage image = NativeImage.read(png)) {
                int w = image.getWidth(), h = image.getHeight();
                int[] out = new int[w * h + 2];
                out[0] = w;
                out[1] = h;
                System.arraycopy(image.getPixels(), 0, out, 2, w * h);
                return out;
            }
        }

        @Override
        public double pixelScale() {
            if (inDisplay) {
                return 1; // кадр экрана: единица g3d.begin — пиксель кадра
            }
            org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(gfx().pose());
            return Math.hypot(pose.m00(), pose.m01()) * Minecraft.getInstance().getWindow().getGuiScale();
        }

        @Override
        public void present(int[] argb, int w, int h, float x, float y, float rw, float rh) {
            if (inDisplay) {
                int[] img = new int[w * h + 2];
                img[0] = w;
                img[1] = h;
                System.arraycopy(argb, 0, img, 2, w * h);
                blitDisplay(img, Math.round(x), Math.round(y), Math.round(rw), Math.round(rh), 255);
                return;
            }
            GuiGraphics gg = gfx();
            Minecraft mc = Minecraft.getInstance();
            if (frame == null || frameW != w || frameH != h) {
                if (frameId != null) {
                    mc.getTextureManager().release(frameId);
                }
                frameW = w;
                frameH = h;
                frame = new DynamicTexture(() -> "BlockPhone app 3D", frameW, frameH, false);
                frameId = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "dynamic/app3d/" + app.id() + "_" + System.nanoTime());
                mc.getTextureManager().register(frameId, frame);
            }
            NativeImage pixels = frame.getPixels();
            for (int yy = 0; yy < h; yy++) {
                int row = yy * w;
                for (int xx = 0; xx < w; xx++) {
                    pixels.setPixel(xx, yy, argb[row + xx]);
                }
            }
            frame.upload();
            gg.pose().pushMatrix();
            gg.pose().translate(x, y);
            gg.pose().scale(rw / w, rh / h);
            gg.blit(RenderPipelines.GUI_TEXTURED, frameId, 0, 0, 0, 0, w, h, w, h, w, h);
            gg.pose().popMatrix();
        }

        @Override
        public int color(LuaValue v, int fallback) {
            return ScriptHost.color(v, fallback);
        }
    }

    /** Картинка «девяткой»: углы border×border как есть, края и середина растягиваются (кнопки любого размера). */
    private void nine(String path, int x, int y, int w, int h, int border, int tint) {
        Identifier id = texture(path);
        int[] size = textureSizes.get(path);
        int tw = size[0], th = size[1];
        int b = Math.min(border, Math.min(Math.min(tw, th) / 2, Math.min(w, h) / 2));
        GuiGraphics gg = gfx();
        int[] xs = {x, x + b, x + w - b, x + w};
        int[] ys = {y, y + b, y + h - b, y + h};
        int[] us = {0, b, tw - b, tw};
        int[] vs = {0, b, th - b, th};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int dw = xs[col + 1] - xs[col], dh = ys[row + 1] - ys[row];
                int sw = us[col + 1] - us[col], sh = vs[row + 1] - vs[row];
                if (dw > 0 && dh > 0 && sw > 0 && sh > 0) {
                    gg.blit(RenderPipelines.GUI_TEXTURED, id, xs[col], ys[row], us[col], vs[row], dw, dh, sw, sh, tw, th, tint);
                }
            }
        }
    }

    // --- Захват мыши ---

    private void lockMouse() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof PhoneScreen)) {
            return;
        }
        mouseLocked = true;
        lastCursorX = Double.NaN;
        GLFW.glfwSetInputMode(mc.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    private void unlockMouse() {
        if (!mouseLocked) {
            return;
        }
        mouseLocked = false;
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().handle();
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        GLFW.glfwSetCursorPos(window, mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() / 2.0);
    }

    /** Каждый кадр: накопить сдвиг мыши; телефон закрыли — отпустить. */
    private void pollMouseLock() {
        if (!mouseLocked) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof PhoneScreen)) {
            unlockMouse();
            return;
        }
        double[] cx = new double[1];
        double[] cy = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().handle(), cx, cy);
        if (!Double.isNaN(lastCursorX)) {
            double sens = 0.15 + mc.options.sensitivity().get() * 0.6;
            double dx = (cx[0] - lastCursorX) * sens;
            double dy = (cy[0] - lastCursorY) * sens;
            if (dx != 0 || dy != 0) {
                mouseDX += dx;
                mouseDY += dy;
                callback("onMouseMove", LuaValue.valueOf(dx), LuaValue.valueOf(dy));
            }
        }
        lastCursorX = cx[0];
        lastCursorY = cy[0];
    }

    // --- Картинки ---

    private Identifier texture(String path) {
        Identifier id = textures.get(path);
        if (id != null) {
            return id;
        }
        byte[] png = script.assets().get(path);
        if (png == null) {
            throw new LuaError("image \"" + path + "\" not found in the app folder");
        }
        try {
            dev.blockphone.fabric.client.photo.ImageOps.checkSize(png, "app image " + path);
            NativeImage image = NativeImage.read(png);
            textureSizes.put(path, new int[]{image.getWidth(), image.getHeight()});
            id = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "dynamic/app/" + app.id() + "/" + textures.size());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "BlockPhone app image", image));
            textures.put(path, id);
            return id;
        } catch (Exception e) {
            throw new LuaError("image \"" + path + "\" is not a PNG");
        }
    }

    // --- Помощники ---

    /** Точка мыши в координатах текущего преобразования (после gfx.translate/scale). */
    private org.joml.Vector2f toLocal(float x, float y) {
        if (g == null) {
            return new org.joml.Vector2f(x, y);
        }
        // Мышь — в координатах приложения (базовая матрица кадра); переводим в текущие (после gfx.translate/scale).
        org.joml.Vector2f p = basePose.transformPosition(new org.joml.Vector2f(x, y));
        return new org.joml.Matrix3x2f(g.pose()).invert().transformPosition(p);
    }

    private org.joml.Matrix3x2f basePose = new org.joml.Matrix3x2f();

    // --- Браузер (web.*, разрешение browser) ---

    private final Map<Integer, dev.blockphone.fabric.client.browser.WebEngine.View> webViews = new HashMap<>();
    private int nextWeb = 1;
    /** Где в этом кадре нарисована вкладка: матрица (экранная), прямоугольник и размер страницы в пикселях. */
    private record WebArea(dev.blockphone.fabric.client.browser.WebEngine.View view, org.joml.Matrix3x2f pose, float x, float y,
                           float w, float h) {
    }

    private final List<WebArea> webAreas = new ArrayList<>();
    /** Кнопки скрипта этого кадра в координатах приложения: нажатие по ним не уходит странице под ними. */
    private final List<float[]> uiRects = new ArrayList<>();
    private List<float[]> lastUiRects = List.of();

    private void uiRect(float x, float y, float w, float h) {
        if (g == null) {
            return;
        }
        org.joml.Matrix3x2f toApp = new org.joml.Matrix3x2f(basePose).invert().mul(new org.joml.Matrix3x2f(g.pose()));
        org.joml.Vector2f a = toApp.transformPosition(new org.joml.Vector2f(x, y));
        org.joml.Vector2f b = toApp.transformPosition(new org.joml.Vector2f(x + w, y + h));
        uiRects.add(new float[]{Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y)});
    }

    private boolean onUi(double x, double y) {
        for (float[] r : lastUiRects) {
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) {
                return true;
            }
        }
        return false;
    }
    private List<WebArea> lastWebAreas = List.of();
    /** Вкладка, на которую нажали последней: ей идут клавиши. */
    private dev.blockphone.fabric.client.browser.WebEngine.@Nullable View webFocus;
    private dev.blockphone.fabric.client.browser.WebEngine.@Nullable View webPressed;

    /** Точка приложения (x, y) → пиксели страницы вкладки, если она под точкой; иначе null. */
    private int @Nullable [] webPoint(WebArea a, double x, double y) {
        org.joml.Vector2f p = basePose.transformPosition(new org.joml.Vector2f((float) x, (float) y));
        org.joml.Vector2f local = new org.joml.Matrix3x2f(a.pose()).invert().transformPosition(p);
        float fx = (local.x - a.x()) / a.w();
        float fy = (local.y - a.y()) / a.h();
        if (fx < 0 || fy < 0 || fx >= 1 || fy >= 1) {
            return null;
        }
        return new int[]{(int) (fx * a.view().pageWidth()), (int) (fy * a.view().pageHeight())};
    }

    private @Nullable WebArea webAt(double x, double y) {
        for (int i = lastWebAreas.size() - 1; i >= 0; i--) {
            if (webPoint(lastWebAreas.get(i), x, y) != null) {
                return lastWebAreas.get(i);
            }
        }
        return null;
    }

    private dev.blockphone.fabric.client.browser.WebEngine.View webView(LuaValue id) {
        dev.blockphone.fabric.client.browser.WebEngine.View v = webViews.get(id.checkint());
        if (v == null) {
            throw new LuaError("web: no tab " + id);
        }
        return v;
    }

    /** Адрес этого компьютера или локальной сети (в том числе CGNAT 100.64/10 и IPv6 fc00::/7). */
    static boolean localAddress(java.net.InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0 || (first == 100 && second >= 64 && second < 128) || (first == 169 && second == 254);
        }
        return (b[0] & 0xFE) == 0xFC;
    }

    private static String webAddress(String text) {
        String s = text.strip();
        if (s.isEmpty()) {
            return "about:blank";
        }
        if (s.matches("(?i)^[a-z][a-z0-9+.-]*://.*") || s.startsWith("about:")) {
            return s;
        }
        if (!s.contains(" ") && s.contains(".")) {
            return "https://" + s;
        }
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private LuaTable installWeb() {
        LuaTable web = new LuaTable();
        web.set("status", fn(a -> {
            need(AppPermission.BROWSER);
            var s = dev.blockphone.fabric.client.browser.WebEngine.state();
            return LuaValue.varargsOf(LuaValue.valueOf(s.name().toLowerCase(java.util.Locale.ROOT)),
                    LuaValue.valueOf(dev.blockphone.fabric.client.browser.WebEngine.progress()),
                    LuaValue.valueOf(dev.blockphone.fabric.client.browser.WebEngine.error()));
        }));
        web.set("start", fn(a -> {
            need(AppPermission.BROWSER);
            dev.blockphone.fabric.client.browser.WebEngine.start();
            return LuaValue.NONE;
        }));
        web.set("open", fn(a -> {
            need(AppPermission.BROWSER);
            if (webViews.size() >= 4) {
                throw new LuaError("web: at most 4 tabs");
            }
            int id = nextWeb++;
            webViews.put(id, dev.blockphone.fabric.client.browser.WebEngine.open(webAddress(a.optjstring(1, "about:blank"))));
            return LuaValue.valueOf(id);
        }));
        web.set("draw", fn(a -> {
            var v = webView(a.arg(1));
            GuiGraphics gg = gfx();
            float x = (float) a.checkdouble(2);
            float y = (float) a.checkdouble(3);
            float w = (float) a.checkdouble(4);
            float h = (float) a.checkdouble(5);
            org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(gg.pose());
            double scale = Math.hypot(pose.m00(), pose.m01()) * Minecraft.getInstance().getWindow().getGuiScale();
            double zoom = Math.max(0.25, Math.min(4, a.optdouble(6, 1)));
            v.draw(gg, x, y, w, h, (int) Math.round(w * scale / zoom), (int) Math.round(h * scale / zoom));
            webAreas.add(new WebArea(v, pose, x, y, w, h));
            return LuaValue.NONE;
        }));
        web.set("go", fn(a -> {
            webView(a.arg(1)).load(webAddress(a.checkjstring(2)));
            return LuaValue.NONE;
        }));
        web.set("back", fn(a -> {
            webView(a.arg(1)).back();
            return LuaValue.NONE;
        }));
        web.set("forward", fn(a -> {
            webView(a.arg(1)).forward();
            return LuaValue.NONE;
        }));
        web.set("reload", fn(a -> {
            webView(a.arg(1)).reload();
            return LuaValue.NONE;
        }));
        web.set("stop", fn(a -> {
            webView(a.arg(1)).stopLoad();
            return LuaValue.NONE;
        }));
        web.set("info", fn(a -> {
            var v = webView(a.arg(1));
            LuaTable t = new LuaTable();
            t.set("url", LuaValue.valueOf(v.url() == null ? "" : v.url()));
            t.set("title", LuaValue.valueOf(v.title()));
            t.set("loading", LuaValue.valueOf(v.loading()));
            t.set("canBack", LuaValue.valueOf(v.canBack()));
            t.set("canForward", LuaValue.valueOf(v.canForward()));
            t.set("ready", LuaValue.valueOf(v.ready()));
            t.set("focused", LuaValue.valueOf(v == webFocus));
            return t;
        }));
        web.set("focus", fn(a -> {
            var v = webView(a.arg(1));
            boolean on = a.optboolean(2, true);
            if (on) {
                webFocus = v;
                inputs.values().forEach(t -> t.setFocused(false));
            } else if (webFocus == v) {
                webFocus = null;
            }
            v.setFocus(on);
            return LuaValue.NONE;
        }));
        web.set("close", fn(a -> {
            var v = webViews.remove(a.checkint(1));
            if (v != null) {
                if (webFocus == v) {
                    webFocus = null;
                }
                v.close();
            }
            return LuaValue.NONE;
        }));
        return web;
    }

    private void closeWebViews() {
        webViews.values().forEach(dev.blockphone.fabric.client.browser.WebEngine.View::close);
        webViews.clear();
        webFocus = null;
        webPressed = null;
        lastWebAreas = List.of();
    }

    private static void line(GuiGraphics g, double x1, double y1, double x2, double y2, int thickness, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        int steps = (int) Math.max(1, Math.min(2000, Math.max(Math.abs(dx), Math.abs(dy))));
        int half = thickness / 2;
        for (int s = 0; s <= steps; s++) {
            int x = (int) Math.round(x1 + dx * s / steps);
            int y = (int) Math.round(y1 + dy * s / steps);
            g.fill(x - half, y - half, x - half + thickness, y - half + thickness, color);
        }
    }

    private boolean keyDown(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (dev.blockphone.fabric.client.screens.AppDisplays.controls(display)
                && dev.blockphone.fabric.client.screens.AppDisplays.debugKeys.contains(name)) {
            return true;
        }
        if (!(mc.screen instanceof PhoneScreen) && !dev.blockphone.fabric.client.screens.AppDisplays.controls(display)) {
            return false; // телефон не в фокусе (и не режим джойстика этого приложения) — клавиши у игры
        }
        if (name.equals("mouse1") || name.equals("mouse2")) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), name.equals("mouse1") ? 0 : 1) == GLFW.GLFW_PRESS;
        }
        Integer key = KEYS.get(name.toLowerCase(Locale.ROOT));
        return key != null && InputConstants.isKeyDown(mc.getWindow(), key);
    }

    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        for (char c = 'a'; c <= 'z'; c++) {
            KEYS.put(String.valueOf(c), GLFW.GLFW_KEY_A + (c - 'a'));
        }
        for (char c = '0'; c <= '9'; c++) {
            KEYS.put(String.valueOf(c), GLFW.GLFW_KEY_0 + (c - '0'));
        }
        KEYS.put("space", GLFW.GLFW_KEY_SPACE);
        KEYS.put("enter", GLFW.GLFW_KEY_ENTER);
        KEYS.put("tab", GLFW.GLFW_KEY_TAB);
        KEYS.put("backspace", GLFW.GLFW_KEY_BACKSPACE);
        KEYS.put("left", GLFW.GLFW_KEY_LEFT);
        KEYS.put("right", GLFW.GLFW_KEY_RIGHT);
        KEYS.put("up", GLFW.GLFW_KEY_UP);
        KEYS.put("down", GLFW.GLFW_KEY_DOWN);
        KEYS.put("shift", GLFW.GLFW_KEY_LEFT_SHIFT);
        KEYS.put("ctrl", GLFW.GLFW_KEY_LEFT_CONTROL);
    }

    private static @Nullable String keyName(int key) {
        for (Map.Entry<String, Integer> e : KEYS.entrySet()) {
            if (e.getValue() == key) {
                return e.getKey();
            }
        }
        return key == GLFW.GLFW_KEY_RIGHT_SHIFT ? "shift" : key == GLFW.GLFW_KEY_KP_ENTER ? "enter" : null;
    }

    // --- JSON ↔ Lua ---

    private static JsonElement toJson(LuaValue v, int depth) {
        if (depth > 32) {
            throw new LuaError("json: too deep");
        }
        if (v.isnil()) {
            return JsonNull.INSTANCE;
        }
        if (v.isboolean()) {
            return new JsonPrimitive(v.toboolean());
        }
        if (v.type() == LuaValue.TNUMBER) {
            double d = v.todouble();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
        }
        if (v.isstring()) {
            return new JsonPrimitive(v.tojstring());
        }
        if (v.istable()) {
            LuaTable t = (LuaTable) v;
            int n = t.length();
            boolean array = n > 0;
            int keys = 0;
            for (LuaValue k = t.next(LuaValue.NIL).arg1(); !k.isnil(); k = t.next(k).arg1()) {
                keys++;
            }
            if (array && keys == n) {
                JsonArray arr = new JsonArray();
                for (int i = 1; i <= n; i++) {
                    arr.add(toJson(t.get(i), depth + 1));
                }
                return arr;
            }
            JsonObject obj = new JsonObject();
            for (LuaValue k = t.next(LuaValue.NIL).arg1(); !k.isnil(); k = t.next(k).arg1()) {
                obj.add(k.tojstring(), toJson(t.get(k), depth + 1));
            }
            return obj;
        }
        throw new LuaError("json: cannot encode " + v.typename());
    }

    private static LuaValue fromJson(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return LuaValue.NIL;
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return LuaValue.valueOf(p.getAsBoolean());
            }
            if (p.isNumber()) {
                return LuaValue.valueOf(p.getAsDouble());
            }
            return LuaValue.valueOf(p.getAsString());
        }
        LuaTable t = new LuaTable();
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                t.set(i + 1, fromJson(a.get(i)));
            }
        } else {
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                t.set(entry.getKey(), fromJson(entry.getValue()));
            }
        }
        return t;
    }
}
