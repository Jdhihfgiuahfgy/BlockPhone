package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** Экран приложения Telegram. Клики — через области, записанные при отрисовке ({@link #hit}). */
abstract class TgView {
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    protected final TelegramApp app;
    private final List<Hit> hits = new ArrayList<>();
    protected double scroll;
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;

    protected TgView(TelegramApp app) {
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

    final void renderView(GuiGraphics g, int mouseX, int mouseY) {
        hits.clear();
        render(g, mouseX, mouseY);
    }

    abstract void render(GuiGraphics g, int mouseX, int mouseY);

    protected void hit(int x, int y, int w, int h, Runnable action) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            hits.add(new Hit(x, top, w, bottom - top, action));
        }
    }

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

    boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
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
        scroll -= amount * 20;
        return true;
    }

    protected void clampScroll(int total, int viewport) {
        scroll = Math.max(0, Math.min(Math.max(0, total - viewport), scroll));
    }

    boolean keyPressed(KeyEvent event) {
        return false;
    }

    boolean charTyped(CharacterEvent event) {
        return false;
    }

    boolean onBack() {
        return false;
    }

    boolean capturesKeyboard() {
        return false;
    }

    int width() {
        return app.contentWidth();
    }

    int height() {
        return app.contentHeight();
    }

    static String tr(String key, Object... args) {
        return PhoneLang.tr(key, args);
    }

    void hint(GuiGraphics g, String text, int top) {
        PhoneDraw.wrapped(g, text, 12, top, width() - 24, PhoneTheme.secondaryText(), 0.75f, true);
    }

    void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, PhoneDraw.ButtonStyle style, Runnable action) {
        PhoneDraw.button(g, x, y, w, h, label, mx, my, style);
        hit(x, y, w, h, action);
    }
}
