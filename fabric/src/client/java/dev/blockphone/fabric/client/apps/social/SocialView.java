package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.PopupMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран внутри приложения «Лента». {@link SocialApp} хранит их стеком: «назад» снимает верхний экран.
 * Кликабельные области записываются при отрисовке ({@link #hit}) — так не нужно дублировать раскладку в обработке клика.
 */
abstract class SocialView {
    /** Кликабельная область, записанная при последней отрисовке. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    protected final SocialApp app;
    private final List<Hit> hits = new ArrayList<>();
    /** Открытое всплывающее меню (null — нет). */
    protected PopupMenu menu;
    protected double scroll;
    /** Видимая по вертикали область для кликов (прокручиваемый список). */
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;

    protected SocialView(SocialApp app) {
        this.app = app;
    }

    abstract String title();

    String headerAction() {
        return null;
    }

    void onHeaderAction() {
    }

    void onShown() {
    }

    void onHidden() {
    }

    void tick() {
    }

    /** Корневой экран вкладки (под ним — панель вкладок). */
    boolean root() {
        return false;
    }

    final void renderView(GuiGraphics g, int mouseX, int mouseY) {
        hits.clear();
        dirHits.clear();
        boolean blocked = menu != null && menu.isOpen();
        render(g, blocked ? -1000 : mouseX, blocked ? -1000 : mouseY);
        if (menu != null) {
            menu.render(g, mouseX, mouseY);
        }
    }

    abstract void render(GuiGraphics g, int mouseX, int mouseY);

    /** Записать кликабельную область (координаты экрана приложения). */
    protected void hit(int x, int y, int w, int h, Runnable action) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            hits.add(new Hit(x, top, w, bottom - top, action));
        }
    }

    /** Кликабельная область с направлением: ЛКМ — +1, ПКМ — −1 (переключатели значений). */
    protected void hitDir(int x, int y, int w, int h, java.util.function.IntConsumer action) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            dirHits.add(new DirHit(x, top, w, bottom - top, action));
        }
    }

    private record DirHit(int x, int y, int w, int h, java.util.function.IntConsumer action) {
    }

    private final List<DirHit> dirHits = new java.util.ArrayList<>();

    /** Начать прокручиваемую область: клики только между top и bottom (и scissor для отрисовки). */
    protected void beginList(GuiGraphics g, int top, int bottom) {
        clipTop = top;
        clipBottom = bottom;
        g.enableScissor(0, top, width(), bottom);
    }

    protected void endList(GuiGraphics g) {
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        g.disableScissor();
    }

    /** Показать меню у точки. */
    protected void showMenu(PopupMenu next, int x, int y) {
        menu = next;
        menu.show(x, y, width(), height());
    }

    boolean mouseClicked(double x, double y, int button) {
        if (menu != null && menu.mouseClicked(x, y)) {
            return true;
        }
        if (button == 0 || button == 1) {
            for (int i = dirHits.size() - 1; i >= 0; i--) {
                DirHit hit = dirHits.get(i);
                if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                    hit.action().accept(button == 0 ? 1 : -1);
                    return true;
                }
            }
        }
        if (button != 0) {
            return false;
        }
        // Последние записанные — поверх остальных.
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    boolean mouseReleased(double x, double y, int button) {
        return false;
    }

    boolean mouseDragged(double x, double y, int button) {
        return false;
    }

    boolean mouseScrolled(double x, double y, double amount) {
        if (menu != null) {
            menu.close();
        }
        scroll -= amount * 20;
        return true;
    }

    /** Ограничить прокрутку: содержимое высотой total в окне высотой viewport. */
    protected void clampScroll(int total, int viewport) {
        scroll = Math.max(0, Math.min(Math.max(0, total - viewport), scroll));
        Scrollbar.report(height() - viewport, height(), scroll, Math.max(0, total - viewport), false, v -> scroll = v);
    }

    boolean keyPressed(KeyEvent event) {
        return false;
    }

    boolean charTyped(CharacterEvent event) {
        return false;
    }

    boolean onBack() {
        if (menu != null && menu.isOpen()) {
            menu.close();
            return true;
        }
        return false;
    }

    boolean capturesKeyboard() {
        return false;
    }

    int width() {
        return app.contentWidth();
    }

    int height() {
        return app.viewHeight(this);
    }

    // --- Общая отрисовка ---

    static String tr(String key, Object... args) {
        return PhoneLang.tr(key, args);
    }

    /** Подсказка по центру («Загрузка...», «Пусто»). */
    void hint(GuiGraphics g, String text, int top) {
        PhoneDraw.wrapped(g, text, 12, top, width() - 24, PhoneTheme.secondaryText(), 0.75f, true);
    }

    /** Заголовок раздела капсом. */
    void section(GuiGraphics g, String text, int y) {
        PhoneDraw.text(g, text.toUpperCase(dev.blockphone.fabric.client.phone.PhoneClock.locale()), 8, y,
                PhoneTheme.secondaryText(), 0.6f, false);
    }

    /** Кнопка с действием (записывает область клика). */
    void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, PhoneDraw.ButtonStyle style, Runnable action) {
        PhoneDraw.button(g, x, y, w, h, label, mx, my, style);
        hit(x, y, w, h, action);
    }
}
