package dev.blockphone.fabric.client.ui;

import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2f;

import java.util.function.DoubleConsumer;

/**
 * Общий ползунок прокрутки телефона. Приложение во время отрисовки сообщает, что у него прокручивается
 * ({@link #report}), а {@code PhoneScreen} рисует полосу у правого края и даёт тянуть её мышью или нажать в любое
 * место полосы — листать можно без колеса. Координаты — области содержимого приложения (вложенные сдвиги
 * {@code g.pose()} учитываются сами).
 */
public final class Scrollbar {
    /** Ширина зоны нажатия у правого края. */
    private static final int HIT_W = 6;
    private static final int MIN_THUMB = 12;

    private record Bar(int top, int bottom, double scroll, double max, boolean inverted, DoubleConsumer set,
                       java.util.function.DoubleSupplier get) {
    }

    private static GuiGraphics graphics;
    private static final Matrix3x2f base = new Matrix3x2f();
    private static Bar current;
    private static Bar dragging;
    private static double grab;
    private static int width;

    private Scrollbar() {
    }

    /** PhoneScreen: перед отрисовкой приложения (матрица уже сдвинута к области содержимого). */
    public static void begin(GuiGraphics g, int contentWidth) {
        graphics = g;
        base.set(g.pose());
        width = contentWidth;
        current = null;
    }

    /** PhoneScreen: после отрисовки приложения — нарисовать полосу. */
    public static void end(GuiGraphics g, double mouseX, double mouseY) {
        graphics = null;
        Bar bar = dragging != null ? dragging : current;
        if (bar == null || bar.max <= 0.5) {
            return;
        }
        boolean hot = dragging != null || near(bar, mouseX, mouseY);
        int x = width - (hot ? 4 : 3);
        int w = hot ? 3 : 2;
        g.fill(x, bar.top + 2, x + w, bar.bottom - 2, PhoneTheme.mix(PhoneTheme.background(), PhoneTheme.text(), 0.12f));
        int[] t = thumb(bar);
        g.fill(x, t[0], x + w, t[1], hot ? PhoneTheme.accent() : PhoneTheme.mix(PhoneTheme.background(), PhoneTheme.text(), 0.45f));
    }

    /** Прокручиваемая область: от top до bottom (в координатах текущей матрицы), прокрутка scroll из 0..max. */
    public static void report(int top, int bottom, double scroll, double max, DoubleConsumer set) {
        report(top, bottom, scroll, max, false, set);
    }

    /**
     * То же; inverted — прокрутка считается от низа (переписка: 0 — последние сообщения внизу).
     * Вне отрисовки ничего не делает, поэтому можно вызывать из любого clampScroll.
     */
    public static void report(int top, int bottom, double scroll, double max, boolean inverted, DoubleConsumer set) {
        report(top, bottom, scroll, max, inverted, set, null);
    }

    /**
     * То же с чтением текущей прокрутки (get): тогда {@link #clampCurrent()} сразу после колеса не даёт уйти за край —
     * без этого содержимое на кадр уезжало за предел и возвращалось («тряска» у края списка).
     */
    public static void report(int top, int bottom, double scroll, double max, boolean inverted, DoubleConsumer set,
                              java.util.function.DoubleSupplier get) {
        if (graphics == null || bottom - top < 20) {
            return;
        }
        Matrix3x2f m = graphics.pose();
        float s = base.m00 == 0 ? 1 : base.m00;
        int dy = Math.round((m.m21 - base.m21) / s);
        current = new Bar(top + dy, bottom + dy, scroll, max, inverted, set, get);
        if (dragging != null && dragging.set == set) {
            dragging = new Bar(top + dy, bottom + dy, dragging.scroll, max, inverted, set, get);
        }
    }

    /** PhoneScreen: после прокрутки колесом — удержать прокрутку последней области в пределах 0..max. */
    public static void clampCurrent() {
        Bar bar = current;
        if (bar == null || bar.get == null) {
            return;
        }
        double value = bar.get.getAsDouble();
        double clamped = Math.max(0, Math.min(Math.max(0, bar.max), value));
        if (clamped != value) {
            bar.set.accept(clamped);
        }
    }

    /** Нажатие в области содержимого: true — попали в полосу (дальше тянем её). */
    public static boolean mouseClicked(double x, double y) {
        Bar bar = current;
        if (bar == null || bar.max <= 0.5 || !near(bar, x, y)) {
            return false;
        }
        int[] t = thumb(bar);
        grab = y >= t[0] && y < t[1] ? y - t[0] : (t[1] - t[0]) / 2.0;
        dragging = bar;
        drag(y);
        return true;
    }

    public static boolean mouseDragged(double y) {
        if (dragging == null) {
            return false;
        }
        drag(y);
        return true;
    }

    public static boolean mouseReleased() {
        boolean was = dragging != null;
        dragging = null;
        return was;
    }

    /** Приложение сменилось — забыть полосу. */
    public static void reset() {
        current = null;
        dragging = null;
    }

    private static void drag(double y) {
        Bar bar = dragging;
        int[] t = thumb(bar);
        double track = (bar.bottom - 2) - (bar.top + 2) - (t[1] - t[0]);
        double f = track <= 0 ? 0 : Math.max(0, Math.min(1, (y - grab - (bar.top + 2)) / track));
        double value = (bar.inverted ? 1 - f : f) * bar.max;
        dragging = new Bar(bar.top, bar.bottom, value, bar.max, bar.inverted, bar.set, bar.get);
        bar.set.accept(value);
    }

    private static boolean near(Bar bar, double x, double y) {
        return x >= width - HIT_W && x < width && y >= bar.top && y < bar.bottom;
    }

    private static int[] thumb(Bar bar) {
        int top = bar.top + 2;
        int len = bar.bottom - 2 - top;
        double view = bar.bottom - bar.top;
        int size = (int) Math.max(MIN_THUMB, Math.min(len, len * view / (view + bar.max)));
        double f = Math.max(0, Math.min(1, bar.scroll / bar.max));
        if (bar.inverted) {
            f = 1 - f;
        }
        int y = top + (int) Math.round((len - size) * f);
        return new int[]{y, y + size};
    }
}
