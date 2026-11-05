package org.cef.browser;

import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Браузер Chromium без окна (offscreen) для BlockPhone: кадры приходят в {@link #onPaint} пикселями BGRA и копируются в
 * {@link #frame}; телефон сам выводит их текстурой. Лежит в пакете org.cef.browser, потому что базовый класс JCEF
 * {@code CefBrowser_N} виден только внутри пакета (у стандартного {@code CefBrowserOsr} — окно JOGL, в игре его нет).
 */
public final class BlockPhoneBrowser extends CefBrowser_N implements CefRenderHandler {
    /** Источник событий клавиатуры и мыши (JCEF берёт поля AWT-событий; окно не нужно). */
    private static final Component SOURCE = new java.awt.Canvas();

    private final Rectangle rect;
    private final Rectangle popupRect = new Rectangle();
    private boolean popupShown;

    private final Object lock = new Object();
    /** Кадр ARGB (основной вид), размер frameW × frameH; растёт version при каждом новом кадре. */
    private int[] frame = new int[0];
    private int frameW;
    private int frameH;
    private int[] popup = new int[0];
    private int popupW;
    private int popupH;
    private volatile long version;

    public BlockPhoneBrowser(CefClient client, String url, int width, int height) {
        this(client, url, width, height, null);
    }

    /** context — свой профиль (null — общий профиль браузера телефона с входами игрока на сайты). */
    public BlockPhoneBrowser(CefClient client, String url, int width, int height, CefRequestContext context) {
        super(client, url, context, null, null, null);
        rect = new Rectangle(0, 0, Math.max(1, width), Math.max(1, height));
    }

    @Override
    public void createImmediately() {
        createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
    }

    @Override
    public Component getUIComponent() {
        return null;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context, CefBrowser_N parent,
                                                 Point inspectAt) {
        return null;
    }

    /** Новый размер вида (пиксели). */
    public void resize(int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (width != rect.width || height != rect.height) {
            rect.setSize(width, height);
            wasResized(width, height);
        }
    }

    public int width() {
        return rect.width;
    }

    public int height() {
        return rect.height;
    }

    public long version() {
        return version;
    }

    /**
     * Скопировать последний кадр (с открытым выпадающим списком поверх) в out (ARGB, размер w×h); false — кадра ещё нет
     * или размер другой.
     */
    public boolean copyFrame(int[] out, int w, int h) {
        synchronized (lock) {
            if (frameW != w || frameH != h || out.length < w * h) {
                return false;
            }
            System.arraycopy(frame, 0, out, 0, w * h);
            if (popupShown && popupW > 0) {
                for (int y = 0; y < popupH; y++) {
                    int ty = popupRect.y + y;
                    if (ty < 0 || ty >= h) {
                        continue;
                    }
                    for (int x = 0; x < popupW; x++) {
                        int tx = popupRect.x + x;
                        if (tx >= 0 && tx < w) {
                            out[ty * w + tx] = popup[y * popupW + x];
                        }
                    }
                }
            }
            return true;
        }
    }

    public int frameWidth() {
        return frameW;
    }

    public int frameHeight() {
        return frameH;
    }

    // --- CefRenderHandler ---

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return rect;
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        screenInfo.Set(1.0, 32, 8, false, rect.getBounds(), rect.getBounds());
        return true;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        return new Point(viewPoint);
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        popupShown = show;
        if (!show) {
            synchronized (lock) {
                popupW = 0;
            }
            version++;
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        popupRect.setBounds(size);
    }

    @Override
    public void onPaint(CefBrowser browser, boolean isPopup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        // BGRA в памяти = ARGB как int с порядком байтов little-endian.
        java.nio.IntBuffer ints = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        synchronized (lock) {
            if (isPopup) {
                if (popup.length < width * height) {
                    popup = new int[width * height];
                }
                ints.get(popup, 0, Math.min(ints.remaining(), width * height));
                popupW = width;
                popupH = height;
            } else {
                if (frame.length != width * height) {
                    frame = new int[width * height];
                }
                ints.get(frame, 0, Math.min(ints.remaining(), width * height));
                frameW = width;
                frameH = height;
            }
        }
        version++;
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return true;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    // --- Ввод (AWT-события — так их принимает JCEF) ---

    public void mouse(int id, int x, int y, int modifiers, int clicks, int button) {
        sendMouseEvent(new MouseEvent(SOURCE, id, System.currentTimeMillis(), modifiers, x, y, x, y, clicks, false, button));
    }

    /**
     * Колесо: JCEF берёт getUnitsToScroll() = scrollAmount × rotation как сдвиг в пикселях (Chromium: +120 — вверх на
     * один щелчок). rotation +1 — вверх.
     */
    public void wheel(int x, int y, int modifiers, int rotation) {
        sendMouseWheelEvent(new MouseWheelEvent(SOURCE, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers, x, y, x, y, 0,
                false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 120, rotation));
    }

    public void key(int id, int keyCode, char keyChar, int modifiers) {
        key(id, keyCode, keyChar, modifiers, 0);
    }

    /**
     * Клавиша. Под Windows JCEF определяет клавишу по скан-коду (приватное поле KeyEvent.scancode → MapVirtualKey):
     * без него Backspace, стрелки, Delete и т. п. не работают. Поле записывается через Unsafe (java.desktop закрыт).
     */
    public void key(int id, int keyCode, char keyChar, int modifiers, long scancode) {
        // У KEY_TYPED AWT требует KEY_LOCATION_UNKNOWN (иначе IllegalArgumentException «invalid keyLocation»).
        int location = id == KeyEvent.KEY_TYPED ? KeyEvent.KEY_LOCATION_UNKNOWN : KeyEvent.KEY_LOCATION_STANDARD;
        KeyEvent event = new KeyEvent(SOURCE, id, System.currentTimeMillis(), modifiers, keyCode, keyChar, location);
        if (scancode != 0) {
            setScancode(event, scancode);
        }
        sendKeyEvent(event);
    }

    private static final sun.misc.Unsafe UNSAFE;
    private static final long SCANCODE_OFFSET;

    static {
        sun.misc.Unsafe unsafe = null;
        long offset = -1;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (sun.misc.Unsafe) f.get(null);
            offset = unsafe.objectFieldOffset(KeyEvent.class.getDeclaredField("scancode"));
        } catch (Throwable ignored) {
            // без скан-кодов: работают только набор букв
        }
        UNSAFE = unsafe;
        SCANCODE_OFFSET = offset;
    }

    @SuppressWarnings("removal")
    private static void setScancode(KeyEvent event, long scancode) {
        if (UNSAFE != null && SCANCODE_OFFSET >= 0) {
            UNSAFE.putLong(event, SCANCODE_OFFSET, scancode);
        }
    }
}
