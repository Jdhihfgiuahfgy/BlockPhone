package dev.blockphone.fabric.client.browser;

import com.mojang.blaze3d.platform.NativeImage;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.storage.ClientFiles;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.BlockPhoneBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Настоящий браузер (Chromium через JCEF) для своих приложений с разрешением «browser». При первом открытии скачивается
 * Chromium (~140 МБ, jcefmaven: GitHub или Maven Central) в blockphone/browser/jcef; cookies и вход на сайты хранятся в
 * blockphone/browser/profile. Страницы рисуются без окна: кадры BGRA → {@link DynamicTexture} → телефон.
 * Видео с защитой (DRM: Netflix и т. п.) Chromium без Widevine не показывает; H.264 в этой сборке тоже нет (YouTube — VP9).
 */
public final class WebEngine {
    public enum State { IDLE, DOWNLOADING, EXTRACTING, STARTING, READY, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile float progress = -1;
    private static volatile String error = "";
    private static volatile @Nullable CefApp app;
    private static volatile @Nullable CefClient client;
    private static final Map<CefBrowser, View> VIEWS = new ConcurrentHashMap<>();
    private static final List<View> ALL = new ArrayList<>();
    /** Страница сообщает в консоль, в поле ли ввода фокус (сообщения с этой меткой в журнал не попадают). */
    private static final String FOCUS_MARK = "__blockphone_focus:";
    private static final String FOCUS_SCRIPT = "(function(){if(window.__bpFocus)return;window.__bpFocus=1;"
            + "function ed(t){if(!t)return false;if(t.isContentEditable)return true;var n=t.tagName;"
            + "if(n==='TEXTAREA'||n==='SELECT')return true;if(n!=='INPUT')return false;"
            + "return !/^(button|checkbox|radio|range|submit|reset|file|color|image|hidden)$/i.test(t.type||'');}"
            + "document.addEventListener('focusin',function(e){console.log('" + FOCUS_MARK + "'+(ed(e.target)?1:0));},true);"
            + "document.addEventListener('focusout',function(){console.log('" + FOCUS_MARK + "0');},true);})();";

    private WebEngine() {
    }

    /** Вкладка браузера: адрес, заголовок, загрузка; кадр — своей текстурой. */
    public static final class View {
        private final String startUrl;
        private @Nullable BlockPhoneBrowser browser;
        private volatile String url;
        private volatile String title = "";
        private volatile boolean loading = true;
        private volatile boolean canBack;
        private volatile boolean canForward;
        /** В фокусе страницы — поле ввода (тогда клавиатура нужна странице; иначе «на ходу» её можно отпустить). */
        private volatile boolean editing;
        /** Сообщения страницы с меткой «__bp» (console.log из своих скриптов) — на поток рендера. */
        private volatile java.util.function.@org.jetbrains.annotations.Nullable Consumer<String> console;
        /** То же, но сразу в потоке Chromium (поток звука: без ожидания кадра игры). */
        private volatile java.util.function.@org.jetbrains.annotations.Nullable Consumer<String> directConsole;
        /** Свой скрипт, который выполняется после каждой загрузки страницы. */
        private volatile @Nullable String loadScript;
        /** Скрипт — и во встроенных окнах (iframe) страницы, не только в главном. */
        private volatile boolean loadScriptAllFrames;
        /** Отдельный профиль без cookies и входов игрока (страницы от других игроков — экраны в мире). */
        private final boolean isolated;
        private int wantW = 640;
        private int wantH = 360;
        private DynamicTexture texture;
        private Identifier textureId;
        private int texW;
        private int texH;
        private int[] buffer = new int[0];
        private long shownVersion = -1;
        private boolean closed;
        private int lastMoveX = -1;
        private int lastMoveY = -1;
        private int pressedButton = -1;

        View(String url, boolean isolated) {
            this.startUrl = url;
            this.url = url;
            this.isolated = isolated;
        }

        /** Получать console.log с меткой «__bp» прямо в потоке Chromium (быстрые данные, например звук). */
        public void setDirectConsoleListener(java.util.function.@Nullable Consumer<String> listener) {
            directConsole = listener;
        }

        /** Скрипт, выполняемый после каждой загрузки страницы (своё оформление и управление). */
        public void setLoadScript(@Nullable String js) {
            setLoadScript(js, false);
        }

        /** allFrames — выполнять и во встроенных окнах (плееры сайтов часто в iframe). */
        public void setLoadScript(@Nullable String js, boolean allFrames) {
            loadScript = js;
            loadScriptAllFrames = allFrames;
        }

        /** Выполнить свой скрипт во всех окнах страницы (главном и встроенных). */
        public void evalAll(String js) {
            BlockPhoneBrowser b = browser;
            if (b == null) {
                return;
            }
            try {
                for (String id : b.getFrameIdentifiers()) {
                    CefFrame f = b.getFrameByIdentifier(id);
                    if (f != null) {
                        if (f.isValid()) {
                            f.executeJavaScript(js, f.getURL(), 0);
                        }
                        f.dispose();
                    }
                }
            } catch (RuntimeException e) {
                eval(js);
            }
        }

        /**
         * Обновить текстуру кадра страницы размером pixelsW×pixelsH и вернуть её (null — кадра ещё нет). Для рисования
         * вне интерфейса (экраны в мире); размеры текстуры — {@link #textureWidth()}/{@link #textureHeight()}.
         */
        public @Nullable Identifier frameTexture(int pixelsW, int pixelsH) {
            wantW = Math.max(64, Math.min(2560, pixelsW));
            wantH = Math.max(64, Math.min(1600, pixelsH));
            ensureBrowser();
            BlockPhoneBrowser b = browser;
            if (b == null) {
                return null;
            }
            b.resize(wantW, wantH);
            int fw = b.frameWidth();
            int fh = b.frameHeight();
            if (fw > 0 && fh > 0 && (b.version() != shownVersion || texture == null || texW != fw || texH != fh)) {
                upload(b, fw, fh);
            }
            return texture == null ? null : textureId;
        }

        public int textureWidth() {
            return texW;
        }

        public int textureHeight() {
            return texH;
        }

        public String url() {
            return url;
        }

        public String title() {
            return title;
        }

        public boolean loading() {
            return loading;
        }

        public boolean canBack() {
            return canBack;
        }

        public boolean canForward() {
            return canForward;
        }

        public boolean editing() {
            return editing;
        }

        /** Получать console.log страницы, начинающиеся с «__bp» (свои скрипты через {@link #eval}). */
        public void setConsoleListener(java.util.function.@org.jetbrains.annotations.Nullable Consumer<String> listener) {
            console = listener;
        }

        /** Выполнить свой скрипт на странице (только для своих вкладок мода, не для приложений игроков). */
        public void eval(String js) {
            BlockPhoneBrowser b = browser;
            if (b != null) {
                b.executeJavaScript(js, url == null ? "about:blank" : url, 0);
            }
        }

        /** Создать страницу, даже если её сейчас не рисуют (фоновое проигрывание: вкладка живёт без экрана). */
        public void keepAlive() {
            ensureBrowser();
        }

        public boolean ready() {
            return browser != null;
        }

        public void load(String address) {
            address = safeUrl(address);
            url = address;
            if (browser != null) {
                browser.loadURL(address);
            }
        }

        public void back() {
            if (browser != null && browser.canGoBack()) {
                browser.goBack();
            }
        }

        public void forward() {
            if (browser != null && browser.canGoForward()) {
                browser.goForward();
            }
        }

        public void reload() {
            if (browser != null) {
                browser.reload();
            }
        }

        public void stopLoad() {
            if (browser != null) {
                browser.stopLoad();
            }
        }

        public void setFocus(boolean focus) {
            if (browser != null) {
                browser.setFocus(focus);
            }
        }

        /** Нарисовать вид в прямоугольнике (координаты текущей матрицы); pixelsW×pixelsH — желаемое разрешение страницы. */
        public void draw(GuiGraphics g, float x, float y, float w, float h, int pixelsW, int pixelsH) {
            wantW = Math.max(64, Math.min(2560, pixelsW));
            wantH = Math.max(64, Math.min(1600, pixelsH));
            ensureBrowser();
            BlockPhoneBrowser b = browser;
            if (b == null) {
                return;
            }
            b.resize(wantW, wantH);
            int fw = b.frameWidth();
            int fh = b.frameHeight();
            if (fw <= 0 || fh <= 0) {
                return;
            }
            if (b.version() != shownVersion || texture == null || texW != fw || texH != fh) {
                upload(b, fw, fh);
            }
            if (texture == null) {
                return;
            }
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(w / texW, h / texH);
            g.blit(RenderPipelines.GUI_TEXTURED, textureId, 0, 0, 0, 0, texW, texH, texW, texH, texW, texH);
            g.pose().popMatrix();
        }

        private void upload(BlockPhoneBrowser b, int fw, int fh) {
            Minecraft mc = Minecraft.getInstance();
            if (buffer.length < fw * fh) {
                buffer = new int[fw * fh];
            }
            long version = b.version();
            if (!b.copyFrame(buffer, fw, fh)) {
                return;
            }
            if (texture == null || texW != fw || texH != fh) {
                if (textureId != null) {
                    mc.getTextureManager().release(textureId);
                }
                texW = fw;
                texH = fh;
                texture = new DynamicTexture(() -> "BlockPhone browser", texW, texH, false);
                textureId = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "dynamic/browser/" + System.nanoTime());
                mc.getTextureManager().register(textureId, texture);
            }
            NativeImage image = texture.getPixels();
            if (image == null) {
                return;
            }
            // ARGB → в памяти NativeImage байты RGBA (ABGR как int): поменять местами R и B, альфа — непрозрачная.
            long ptr = image.getPointer();
            int n = fw * fh;
            for (int i = 0; i < n; i++) {
                int v = buffer[i];
                MemoryUtil.memPutInt(ptr + 4L * i, 0xFF000000 | (v & 0x0000FF00) | ((v >> 16) & 0xFF) | ((v & 0xFF) << 16));
            }
            texture.upload();
            shownVersion = version;
        }

        private void ensureBrowser() {
            if (browser != null || closed || state != State.READY || client == null) {
                return;
            }
            BlockPhoneBrowser b = new BlockPhoneBrowser(client, url == null ? startUrl : url, wantW, wantH,
                    isolated ? org.cef.browser.CefRequestContext.createContext(null) : null);
            VIEWS.put(b, this);
            b.createImmediately();
            browser = b;
        }

        public void close() {
            closed = true;
            BlockPhoneBrowser b = browser;
            browser = null;
            if (b != null) {
                VIEWS.remove(b);
                b.close(true);
            }
            if (textureId != null) {
                Minecraft.getInstance().getTextureManager().release(textureId);
                textureId = null;
                texture = null;
            }
            synchronized (ALL) {
                ALL.remove(this);
            }
        }

        // --- Ввод: координаты — пиксели страницы ---

        public void mouseMove(int x, int y) {
            try {
                BlockPhoneBrowser b = browser;
                if (b == null || (x == lastMoveX && y == lastMoveY)) {
                    return;
                }
                lastMoveX = x;
                lastMoveY = y;
                if (pressedButton >= 0) {
                    b.mouse(MouseEvent.MOUSE_DRAGGED, x, y, buttonMask(pressedButton), 0, MouseEvent.NOBUTTON);
                } else {
                    b.mouse(MouseEvent.MOUSE_MOVED, x, y, 0, 0, MouseEvent.NOBUTTON);
                }
            } catch (Throwable e) {
                inputError(e);
            }
        }

        public void mouseButton(int x, int y, int button, boolean down) {
            try {
                BlockPhoneBrowser b = browser;
                if (b == null) {
                    return;
                }
                int awt = button == 1 ? MouseEvent.BUTTON3 : button == 2 ? MouseEvent.BUTTON2 : MouseEvent.BUTTON1;
                pressedButton = down ? button : -1;
                b.mouse(down ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_RELEASED, x, y, down ? buttonMask(button) : 0, 1, awt);
                if (!down) {
                    b.mouse(MouseEvent.MOUSE_CLICKED, x, y, 0, 1, awt);
                }
            } catch (Throwable e) {
                inputError(e);
            }
        }

        public void wheel(int x, int y, double amount) {
            try {
                BlockPhoneBrowser b = browser;
                if (b != null && amount != 0) {
                    b.wheel(x, y, 0, amount > 0 ? 1 : -1);
                }
            } catch (Throwable e) {
                inputError(e);
            }
        }

        /** Клавиша (код GLFW, модификаторы GLFW): нажатие и отпускание. */
        public void key(int glfwKey, int glfwMods) {
            key(glfwKey, 0, glfwMods);
        }

        /** Клавиша с аппаратным скан-кодом (GLFW под Windows = скан-код Win32; бит 0x100 — «расширенная»). */
        public void key(int glfwKey, int scancode, int glfwMods) {
            try {
                BlockPhoneBrowser b = browser;
                int code = awtKey(glfwKey);
                if (b == null || code == KeyEvent.VK_UNDEFINED) {
                    return;
                }
                int mods = awtMods(glfwMods);
                long scan = scancode != 0 ? (scancode & 0xFF) : fallbackScancode(glfwKey);
                b.key(KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, mods, scan);
                char typed = switch (glfwKey) {
                    case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> '\r';
                    case GLFW.GLFW_KEY_BACKSPACE -> '\b';
                    case GLFW.GLFW_KEY_TAB -> '\t';
                    default -> KeyEvent.CHAR_UNDEFINED;
                };
                if (typed != KeyEvent.CHAR_UNDEFINED) {
                    b.key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, typed, mods);
                }
                b.key(KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED, mods, scan);
            } catch (Throwable e) {
                inputError(e);
            }
        }

        public void typeChar(int codepoint) {
            try {
                BlockPhoneBrowser b = browser;
                if (b == null) {
                    return;
                }
                for (char c : Character.toChars(codepoint)) {
                    b.key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c, 0);
                }
            } catch (Throwable e) {
                inputError(e);
            }
        }

        public int pageWidth() {
            return wantW;
        }

        public int pageHeight() {
            return wantH;
        }
    }

    private static long lastInputError;

    /** Ошибка передачи ввода странице — в журнал (не чаще раза в 10 с), игра продолжает работать. */
    private static void inputError(Throwable e) {
        long now = System.currentTimeMillis();
        if (now - lastInputError > 10_000) {
            lastInputError = now;
            BlockPhoneFabric.LOGGER.warn("Browser input failed", e);
        }
    }

    /** Скан-коды Win32 (набор 1) для клавиш, если игра не дала свой. */
    private static long fallbackScancode(int k) {
        return switch (k) {
            case GLFW.GLFW_KEY_BACKSPACE -> 0x0E;
            case GLFW.GLFW_KEY_TAB -> 0x0F;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x1C;
            case GLFW.GLFW_KEY_ESCAPE -> 0x01;
            case GLFW.GLFW_KEY_DELETE -> 0x53;
            case GLFW.GLFW_KEY_INSERT -> 0x52;
            case GLFW.GLFW_KEY_HOME -> 0x47;
            case GLFW.GLFW_KEY_END -> 0x4F;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x49;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x51;
            case GLFW.GLFW_KEY_UP -> 0x48;
            case GLFW.GLFW_KEY_DOWN -> 0x50;
            case GLFW.GLFW_KEY_LEFT -> 0x4B;
            case GLFW.GLFW_KEY_RIGHT -> 0x4D;
            case GLFW.GLFW_KEY_SPACE -> 0x39;
            default -> 0;
        };
    }

    private static int buttonMask(int button) {
        return button == 1 ? InputEvent.BUTTON3_DOWN_MASK : button == 2 ? InputEvent.BUTTON2_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
    }

    private static int awtMods(int glfw) {
        int m = 0;
        if ((glfw & GLFW.GLFW_MOD_SHIFT) != 0) {
            m |= InputEvent.SHIFT_DOWN_MASK;
        }
        if ((glfw & GLFW.GLFW_MOD_CONTROL) != 0) {
            m |= InputEvent.CTRL_DOWN_MASK;
        }
        if ((glfw & GLFW.GLFW_MOD_ALT) != 0) {
            m |= InputEvent.ALT_DOWN_MASK;
        }
        return m;
    }

    /** Код клавиши GLFW → код AWT (буквы и цифры совпадают). */
    private static int awtKey(int k) {
        if ((k >= GLFW.GLFW_KEY_A && k <= GLFW.GLFW_KEY_Z) || (k >= GLFW.GLFW_KEY_0 && k <= GLFW.GLFW_KEY_9) || k == GLFW.GLFW_KEY_SPACE) {
            return k;
        }
        if (k >= GLFW.GLFW_KEY_F1 && k <= GLFW.GLFW_KEY_F12) {
            return KeyEvent.VK_F1 + (k - GLFW.GLFW_KEY_F1);
        }
        return switch (k) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> KeyEvent.VK_ENTER;
            case GLFW.GLFW_KEY_BACKSPACE -> KeyEvent.VK_BACK_SPACE;
            case GLFW.GLFW_KEY_TAB -> KeyEvent.VK_TAB;
            case GLFW.GLFW_KEY_ESCAPE -> KeyEvent.VK_ESCAPE;
            case GLFW.GLFW_KEY_DELETE -> KeyEvent.VK_DELETE;
            case GLFW.GLFW_KEY_INSERT -> KeyEvent.VK_INSERT;
            case GLFW.GLFW_KEY_LEFT -> KeyEvent.VK_LEFT;
            case GLFW.GLFW_KEY_RIGHT -> KeyEvent.VK_RIGHT;
            case GLFW.GLFW_KEY_UP -> KeyEvent.VK_UP;
            case GLFW.GLFW_KEY_DOWN -> KeyEvent.VK_DOWN;
            case GLFW.GLFW_KEY_PAGE_UP -> KeyEvent.VK_PAGE_UP;
            case GLFW.GLFW_KEY_PAGE_DOWN -> KeyEvent.VK_PAGE_DOWN;
            case GLFW.GLFW_KEY_HOME -> KeyEvent.VK_HOME;
            case GLFW.GLFW_KEY_END -> KeyEvent.VK_END;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    // --- Свой адрес bpcap://w/<имя> — код для страниц ---

    public static final String SCHEME = "bpcap";
    /** Файлы, которые отдаёт bpcap://w/<имя> (JavaScript). */
    private static final Map<String, String> CODE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Положить код по адресу bpcap://w/&lt;name&gt; (например, модуль AudioWorklet). */
    public static void putCode(String name, String js) {
        CODE.put(name, js);
    }

    private static final class CodeHandler extends org.cef.handler.CefResourceHandlerAdapter {
        private final byte[] data;
        private int pos;

        CodeHandler(String url) {
            String name = url == null ? "" : url.replaceFirst("^" + SCHEME + "://w/", "").replaceAll("[?#].*$", "");
            String js = CODE.get(name);
            data = js == null ? null : js.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public boolean processRequest(org.cef.network.CefRequest request, org.cef.callback.CefCallback callback) {
            callback.Continue();
            return true;
        }

        @Override
        public void getResponseHeaders(org.cef.network.CefResponse response, org.cef.misc.IntRef length, org.cef.misc.StringRef redirect) {
            if (data == null) {
                response.setStatus(404);
                length.set(0);
                return;
            }
            response.setStatus(200);
            response.setMimeType("application/javascript");
            response.setHeaderByName("Access-Control-Allow-Origin", "*", true);
            length.set(data.length);
        }

        @Override
        public boolean readResponse(byte[] out, int toRead, org.cef.misc.IntRef read, org.cef.callback.CefCallback callback) {
            if (data == null || pos >= data.length) {
                read.set(0);
                return false;
            }
            int n = Math.min(toRead, data.length - pos);
            System.arraycopy(data, pos, out, 0, n);
            pos += n;
            read.set(n);
            return true;
        }
    }

    // --- Запуск ---

    public static State state() {
        return state;
    }

    /** Ход скачивания 0..1 (или −1, если неизвестно). */
    public static float progress() {
        return progress;
    }

    public static String error() {
        return error;
    }

    /** Открыть вкладку (запускает Chromium, если нужно; при первом разе — скачивание). */
    /**
     * Адрес, который можно открыть по просьбе приложения: только http/https (и about:blank). javascript: выполнил бы код
     * приложения на открытом сайте (с входом игрока), file:/chrome:/devtools: открыли бы файлы и служебные страницы.
     */
    public static String safeUrl(@org.jetbrains.annotations.Nullable String url) {
        if (url == null) {
            return "about:blank";
        }
        String s = url.strip();
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? s : "about:blank";
    }

    public static View open(String url) {
        return open(url, false);
    }

    /** isolated — отдельный профиль без cookies и входов игрока (страницы, которые выбрал не он). */
    public static View open(String url, boolean isolated) {
        start();
        View view = new View(safeUrl(url), isolated);
        synchronized (ALL) {
            ALL.add(view);
        }
        return view;
    }

    public static synchronized void start() {
        if (state != State.IDLE && state != State.FAILED) {
            return;
        }
        state = State.DOWNLOADING;
        error = "";
        progress = -1;
        Thread thread = new Thread(WebEngine::boot, "BlockPhone Browser setup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void boot() {
        try {
            File base = ClientFiles.root().resolve("browser").toFile();
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(new File(base, "jcef"));
            builder.setProgressHandler((s, percent) -> {
                switch (s) {
                    case DOWNLOADING -> state = State.DOWNLOADING;
                    case EXTRACTING -> state = State.EXTRACTING;
                    case INITIALIZING -> state = State.STARTING;
                    default -> {
                    }
                }
                progress = percent == EnumProgress.NO_ESTIMATION ? -1 : percent / 100f;
            });
            builder.getCefSettings().windowless_rendering_enabled = true;
            builder.getCefSettings().cache_path = new File(base, "profile").getAbsolutePath();
            builder.getCefSettings().root_cache_path = new File(base, "profile").getAbsolutePath();
            builder.getCefSettings().locale = "ru";
            builder.getCefSettings().log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING;
            builder.addJcefArgs("--autoplay-policy=no-user-gesture-required", "--disable-features=HardwareMediaKeyHandling");
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                @Override
                public void onRegisterCustomSchemes(org.cef.callback.CefSchemeRegistrar registrar) {
                    // Свой адрес bpcap:// — стандартный, «защищённый», с CORS и в обход правил сайта (CSP): с него страницы
                    // грузят свой код (сборщик звука в AudioWorklet), даже если сайт (YouTube) запрещает чужие скрипты.
                    registrar.addCustomScheme(SCHEME, true, false, false, true, true, true, true);
                }
            });
            CefApp cef = builder.build();
            CefClient c = cef.createClient();
            // Адрес bpcap:// — через обработчик запросов клиента (все профили; фабрику схемы до запуска CEF не зарегистрировать).
            c.addRequestHandler(new org.cef.handler.CefRequestHandlerAdapter() {
                @Override
                public org.cef.handler.CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame,
                        org.cef.network.CefRequest request, boolean navigation, boolean download, String initiator,
                        org.cef.misc.BoolRef disable) {
                    if (request.getURL() == null || !request.getURL().startsWith(SCHEME + "://")) {
                        return null;
                    }
                    return new org.cef.handler.CefResourceRequestHandlerAdapter() {
                        @Override
                        public org.cef.handler.CefResourceHandler getResourceHandler(CefBrowser b, CefFrame f, org.cef.network.CefRequest r) {
                            return new CodeHandler(r.getURL());
                        }
                    };
                }
            });
            c.addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override
                public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                    View v = VIEWS.get(browser);
                    if (v != null && (frame == null || frame.isMain())) {
                        v.url = url;
                    }
                }

                @Override
                public boolean onConsoleMessage(CefBrowser browser, org.cef.CefSettings.LogSeverity level, String message,
                                                String source, int line) {
                    if (message != null && message.startsWith(FOCUS_MARK)) {
                        View v = VIEWS.get(browser);
                        if (v != null) {
                            v.editing = message.endsWith("1");
                        }
                        return true;
                    }
                    if (message != null && message.startsWith("__bp")) {
                        View v = VIEWS.get(browser);
                        java.util.function.Consumer<String> direct = v != null ? v.directConsole : null;
                        if (direct != null) {
                            direct.accept(message);
                            return true;
                        }
                        java.util.function.Consumer<String> listener = v != null ? v.console : null;
                        if (listener != null) {
                            Minecraft.getInstance().execute(() -> listener.accept(message));
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public void onTitleChange(CefBrowser browser, String title) {
                    View v = VIEWS.get(browser);
                    if (v != null) {
                        v.title = title == null ? "" : title;
                    }
                }
            });
            c.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                    View v = VIEWS.get(browser);
                    if (v != null) {
                        v.loading = isLoading;
                        v.canBack = canGoBack;
                        v.canForward = canGoForward;
                    }
                }

                @Override
                public void onLoadStart(CefBrowser browser, CefFrame frame, org.cef.network.CefRequest.TransitionType type) {
                    // Свой скрипт — и в самом начале загрузки: успеть раньше, чем страница сама запустит видео со звуком.
                    View v = VIEWS.get(browser);
                    String script = v != null ? v.loadScript : null;
                    if (frame != null && script != null && (frame.isMain() || v.loadScriptAllFrames)) {
                        frame.executeJavaScript(script, frame.getURL(), 0);
                    }
                }

                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    if (frame != null) {
                        frame.executeJavaScript(FOCUS_SCRIPT, frame.getURL(), 0);
                        View v = VIEWS.get(browser);
                        String script = v != null ? v.loadScript : null;
                        if (script != null && (frame.isMain() || v.loadScriptAllFrames)) {
                            frame.executeJavaScript(script, frame.getURL(), 0);
                        }
                    }
                }
            });
            c.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                @Override
                public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl, String targetFrameName) {
                    // Новые окна (target=_blank, всплывающие) — открываем в той же вкладке (только http/https: такой
                    // переход идёт «от браузера», страница не должна так открыть file: или chrome:).
                    if (targetUrl != null && !targetUrl.isBlank() && !"about:blank".equals(safeUrl(targetUrl))) {
                        browser.loadURL(targetUrl);
                    }
                    return true;
                }
            });
            app = cef;
            client = c;
            state = State.READY;
            BlockPhoneFabric.LOGGER.info("Browser (Chromium) ready");
        } catch (Throwable e) {
            BlockPhoneFabric.LOGGER.warn("Browser (Chromium) failed to start", e);
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            state = State.FAILED;
        }
    }

    /** Закрытие игры: закрыть вкладки (Chromium сам выключится при выходе — обработчик jcefmaven). */
    public static void shutdown() {
        List<View> views;
        synchronized (ALL) {
            views = List.copyOf(ALL);
        }
        for (View v : views) {
            try {
                v.close();
            } catch (Throwable ignored) {
                // выходим всё равно
            }
        }
    }
}
