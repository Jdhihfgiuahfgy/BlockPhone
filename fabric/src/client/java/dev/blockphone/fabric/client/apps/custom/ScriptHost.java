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
    private static final ExecutorService HTTP = Executors.newFixedThreadPool(2, r -> {
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
            return LuaValue.NONE;
        }), LuaValue.valueOf(""), LuaValue.valueOf(HOOK_STEP)}));
        installApi(g);
        return g;
    }

    // --- Вызовы скрипта ---

    private interface Fn {
        Varargs call(Varargs args);
    }

    private static LuaValue fn(Fn body) {
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

    private Varargs invoke(LuaValue f, Varargs args) {
        if (error != null) {
            return LuaValue.NONE;
        }
        budget = CALL_BUDGET;
        used = 0;
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

    private void fail(Throwable e) {
        unlockMouse();
        if (e instanceof Timeout) {
            error = PhoneLang.tr("app.script.timeout");
        } else if (e instanceof LuaError le) {
            error = le.getMessage();
        } else if (e instanceof StackOverflowError) {
            error = PhoneLang.tr("app.script.stack");
        } else if (e instanceof OutOfMemoryError) {
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
        if (sounds.size() >= 16) {
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
            sounds.put(id, Float.isNaN(pan) && pitch == 1
                    ? dev.blockphone.fabric.client.sound.PhoneSounds.playFile(file, v, loop)
                    : dev.blockphone.fabric.client.sound.PhoneSounds.playFile(file, v, loop, Float.isNaN(pan) ? 0 : pan, muffle, pitch));
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
        unlockMouse();
        stopSounds();
        inputs.values().forEach(t -> t.setFocused(false));
        callback("onHide");
    }

    void tick() {
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
        clicks.clear();
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
            return true;
        }
        clicks.add(new double[]{x, y, button});
        callback("onClick", LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(button + 1));
        return true;
    }

    boolean mouseReleased(double x, double y, int button) {
        inputs.values().forEach(TextArea::mouseReleased);
        callback("onRelease", LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(button + 1));
        return true;
    }

    boolean mouseDragged(double x, double y, double dx, double dy) {
        for (TextArea area : inputs.values()) {
            if (area.isFocused() && area.mouseDragged(x, y)) {
                return true;
            }
        }
        callback("onDrag", LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(dx), LuaValue.valueOf(dy));
        return true;
    }

    boolean mouseScrolled(double x, double y, double amount) {
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
        callback("onChar", LuaValue.valueOf(new String(Character.toChars(event.codepoint()))));
        return captureKeys;
    }

    void debugKey(String name) {
        callback("onKey", LuaValue.valueOf(name));
    }

    boolean onBack() {
        if (mouseLocked) {
            unlockMouse(); // Esc сначала отпускает мышь
            callback("onMouseUnlock");
            return true;
        }
        return callback("onBack").arg1().toboolean();
    }

    boolean typingText() {
        return error == null && inputs.values().stream().anyMatch(TextArea::isFocused);
    }

    boolean capturesKeyboard() {
        return error == null && (captureKeys || inputs.values().stream().anyMatch(TextArea::isFocused));
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
        unlockMouse();
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

    private void need(AppPermission permission) {
        if (!app.has(permission)) {
            throw new LuaError("permission \"" + permission.key() + "\" is not declared in app.json");
        }
    }

    private GuiGraphics gfx() {
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

    private static int i(Varargs a, int n) {
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
            for (Iterator<double[]> it = clicks.iterator(); it.hasNext(); ) {
                double[] c = it.next();
                org.joml.Vector2f p = toLocal((float) c[0], (float) c[1]);
                if (c[2] == 0 && PhoneDraw.inside(p.x, p.y, x, y, w, h)) {
                    it.remove();
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.25f));
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
            boolean ok = storage != null && storage.put(a.checkjstring(1), toJson(a.arg(2), 0).toString());
            return LuaValue.valueOf(ok);
        }));
        G.set("storage", store);

        // --- toast, sound, clipboard, links, commands ---
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
        G.set("http", http);

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
            ClientNetwork.send(new AppPackets.RequestPlayers(app.id()));
            return LuaValue.NONE;
        }));
        net.set("online", fn(a -> LuaValue.valueOf(ClientNetwork.serverHello() != null)));
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

    private void relay(String target, LuaValue data) {
        String payload = toJson(data, 0).toString();
        if (payload.length() > AppPackets.MAX_RELAY) {
            throw new LuaError("net: message is too big (max " + AppPackets.MAX_RELAY + " chars)");
        }
        if (ClientNetwork.serverHello() == null) {
            throw new LuaError("net: the server has no BlockPhone");
        }
        ClientNetwork.send(new AppPackets.Relay(app.id(), target, payload));
    }

    private void http(String method, String url, @Nullable String body, @Nullable LuaValue callback, @Nullable LuaTable headers) {
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
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpRequest built = request.build();
        HTTP.execute(() -> {
            int status = 0;
            String text;
            try {
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


    // --- 3D ---

    /** Что движку g3d нужно от игры: файлы приложения, PNG, масштаб экрана, показ кадра. */
    private final class G3dHost implements Lua3D.Host {
        @Override
        public byte[] asset(String path) {
            return script.assets().get(path);
        }

        @Override
        public int[] decodePng(byte[] png) throws Exception {
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
            org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(gfx().pose());
            return Math.hypot(pose.m00(), pose.m01()) * Minecraft.getInstance().getWindow().getGuiScale();
        }

        @Override
        public void present(int[] argb, int w, int h, float x, float y, float rw, float rh) {
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
        if (!(mc.screen instanceof PhoneScreen)) {
            return false; // телефон не в фокусе — клавиши у игры
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
