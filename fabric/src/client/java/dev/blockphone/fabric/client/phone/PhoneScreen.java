package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.BlockPhoneClient;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.network.ClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import static dev.blockphone.fabric.client.phone.PhoneLayout.*;

/**
 * Экран телефона поверх игры. Рисует корпус, строку состояния, заголовок и текущее приложение,
 * переводит координаты мыши в локальные координаты приложения.
 */
public class PhoneScreen extends Screen {
    private static final long ANIM_MS = 220;

    private final long openedAt = System.currentTimeMillis();
    private long closingAt = -1;

    /** Масштаб и положение телефона на экране (пересчитываются в init при смене размера окна). */
    private float scale = 1f;
    private float originX;
    private float originY;

    private PhoneApp app;
    private PhoneDialog dialog;

    public PhoneScreen() {
        super(Component.translatable("key.blockphone.open"));
    }

    @Override
    protected void init() {
        float fit = (height - 16f) / PHONE_H;
        // Если места много — масштаб кратен 0.5, чтобы пиксели шрифта были ровными.
        scale = fit >= 1f ? Math.min(2f, (float) Math.floor(fit * 2) / 2f) : fit;
        originX = (width - PHONE_W * scale) / 2f;
        originY = (height - PHONE_H * scale) / 2f;
        if (app == null) {
            show(PhoneState.current());
        }
    }

    // --- Навигация ---

    public void openApp(String id) {
        PhoneApp target = PhoneState.app(id);
        if (target != null) {
            PhoneState.setCurrent(id);
            show(target);
        }
    }

    public void goHome() {
        openApp(PhoneState.HOME);
    }

    /** «Назад»: сначала внутри приложения, потом на рабочий стол, потом закрыть телефон. */
    public void back() {
        if (app.onBack()) {
            return;
        }
        if (!PhoneState.HOME.equals(app.id())) {
            goHome();
        } else {
            closePhone();
        }
    }

    public void showDialog(PhoneDialog dialog) {
        this.dialog = dialog;
    }

    /** Закрыть телефон сразу, без анимации (например, при переходе в камеру). */
    public void closeImmediately() {
        minecraft.setScreen(null);
    }

    public void closePhone() {
        if (closingAt >= 0) {
            return;
        }
        if (PhoneSettings.get().animations) {
            closingAt = System.currentTimeMillis();
        } else {
            minecraft.setScreen(null);
        }
    }

    private void show(PhoneApp next) {
        if (app != null && app != next) {
            app.onHidden();
        }
        dialog = null;
        app = next;
        app.attach(this);
        app.onShown();
    }

    // --- Жизненный цикл Screen ---

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        closePhone();
    }

    @Override
    public void removed() {
        if (app != null) {
            app.onHidden();
            app.attach(null);
        }
    }

    @Override
    public void tick() {
        if (closingAt >= 0 && System.currentTimeMillis() - closingAt >= ANIM_MS) {
            minecraft.setScreen(null);
            return;
        }
        // Приложение могло стать недоступным (например, мессенджер после выхода с сервера).
        if (PhoneState.current() != app) {
            show(PhoneState.current());
        }
        app.tick();
    }

    // --- Отрисовка ---

    /** 0 — телефон спрятан внизу, 1 — полностью выдвинут. */
    private float slideProgress() {
        if (!PhoneSettings.get().animations) {
            return 1f;
        }
        long now = System.currentTimeMillis();
        float t = closingAt >= 0
                ? 1f - Math.min(1f, (now - closingAt) / (float) ANIM_MS)
                : Math.min(1f, (now - openedAt) / (float) ANIM_MS);
        float inv = 1f - t;
        return 1f - inv * inv * inv; // ease-out cubic
    }

    private float phoneY() {
        return originY + (1f - slideProgress()) * (height - originY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int alpha = (int) (0x60 * slideProgress());
        g.fillGradient(0, 0, width, height, alpha / 3 << 24, alpha << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        float py = phoneY();
        g.pose().pushMatrix();
        g.pose().translate(originX, py);
        g.pose().scale(scale, scale);

        int lx = (int) Math.floor((mouseX - originX) / scale);
        int ly = (int) Math.floor((mouseY - py) / scale);
        renderPhone(g, lx, ly, delta);

        g.pose().popMatrix();
    }

    private void renderPhone(GuiGraphics g, int mx, int my, float delta) {
        // Корпус с тонкой окантовкой.
        PhoneDraw.roundedRect(g, 0, 0, PHONE_W, PHONE_H, BODY_RADIUS, PhoneTheme.BODY_EDGE);
        PhoneDraw.roundedRect(g, 1, 1, PHONE_W - 1, PHONE_H - 1, BODY_RADIUS - 1, PhoneTheme.BODY);

        g.pose().pushMatrix();
        g.pose().translate(BEZEL, BEZEL);
        g.enableScissor(0, 0, SCREEN_W, SCREEN_H);
        int sx = mx - BEZEL;
        int sy = my - BEZEL;

        // Фон: обои или цвет приложения.
        if (app.transparentBackground()) {
            int[] wp = PhoneTheme.wallpaper();
            g.fillGradient(0, 0, SCREEN_W, SCREEN_H, wp[0], wp[1]);
        } else {
            g.fill(0, 0, SCREEN_W, SCREEN_H, app.backgroundColor());
        }

        // Содержимое приложения.
        int contentTop = STATUS_H + (app.hasHeader() ? HEADER_H : 0);
        g.pose().pushMatrix();
        g.pose().translate(0, contentTop);
        g.enableScissor(0, 0, app.contentWidth(), app.contentHeight());
        app.render(g, sx, sy - contentTop, delta);
        g.disableScissor();
        g.pose().popMatrix();

        if (app.hasHeader()) {
            renderHeader(g, sx, sy);
        }
        renderStatusBar(g);
        renderNavBar(g, sx, sy);
        if (dialog != null) {
            dialog.render(g, sx, sy);
        }

        g.disableScissor();
        // Скруглённые углы экрана и «островок» камеры.
        PhoneDraw.roundCornersMask(g, 0, 0, SCREEN_W, SCREEN_H, SCREEN_RADIUS, PhoneTheme.BODY);
        PhoneDraw.roundedRect(g, SCREEN_W / 2 - 18, 3, SCREEN_W / 2 + 18, 11, 4, PhoneTheme.BLACK);
        g.pose().popMatrix();
    }

    private void renderHeader(GuiGraphics g, int sx, int sy) {
        int top = STATUS_H;
        g.fill(0, top, SCREEN_W, top + HEADER_H, app.headerColor());
        int textColor = app.headerTextColor();
        boolean backHovered = PhoneDraw.inside(sx, sy, 0, top, 24, HEADER_H);
        PhoneDraw.text(g, "<", 8, top + 6, backHovered ? PhoneTheme.ACCENT : textColor, 1f, false);
        String action = app.headerAction();
        int actionWidth = 0;
        if (action != null) {
            actionWidth = (int) PhoneDraw.width(action, 0.75f) + 8;
            boolean hovered = PhoneDraw.inside(sx, sy, SCREEN_W - actionWidth - 4, top, actionWidth + 4, HEADER_H);
            int color = app.headerActionColor();
            PhoneDraw.rightText(g, action, SCREEN_W - 8, top + 7, hovered ? (color & 0x00FFFFFF) | 0xB0000000 : color, 0.75f);
        }
        int titleRoom = SCREEN_W - 2 * Math.max(28, actionWidth + 6);
        String title = PhoneDraw.ellipsize(app.title(), titleRoom, 1f);
        PhoneDraw.centeredText(g, title, SCREEN_W / 2f, top + 6, textColor, 1f, false);
    }

    private void renderStatusBar(GuiGraphics g) {
        boolean light = app.lightStatusBar();
        int color = light ? 0xFFFFFFFF : 0xFF1C1C1E;
        int dim = light ? 0x66FFFFFF : 0x661C1C1E;

        PhoneDraw.text(g, PhoneClock.time(), 12, 3.5f, color, 0.75f, false);

        // «Связь»: полные деления — на сервере стоит BlockPhone.
        int bars = ClientNetwork.serverHello() != null ? 4 : 1;
        int x = SCREEN_W - 40;
        for (int i = 0; i < 4; i++) {
            int h = 2 + i * 2;
            g.fill(x + i * 3, 10 - h, x + i * 3 + 2, 10, i < bars ? color : dim);
        }
        // Батарейка.
        int bx = SCREEN_W - 26;
        g.renderOutline(bx, 4, 14, 7, color);
        g.fill(bx + 14, 6, bx + 15, 9, color);
        g.fill(bx + 2, 6, bx + 12, 9, color);
    }

    private void renderNavBar(GuiGraphics g, int sx, int sy) {
        boolean hovered = PhoneDraw.inside(sx, sy, SCREEN_W / 2 - 30, SCREEN_H - NAV_H, 60, NAV_H);
        int color = app.lightStatusBar() || app.transparentBackground() ? 0xFFFFFFFF : 0xFF1C1C1E;
        if (!hovered) {
            color = (color & 0x00FFFFFF) | 0xB0000000;
        }
        PhoneDraw.roundedRect(g, SCREEN_W / 2 - 24, SCREEN_H - 7, SCREEN_W / 2 + 24, SCREEN_H - 4, 1, color);
    }

    // --- Ввод ---

    private double toScreenX(double mouseX) {
        return (mouseX - originX) / scale - BEZEL;
    }

    private double toScreenY(double mouseY) {
        return (mouseY - phoneY()) / scale - BEZEL;
    }

    private double contentTop() {
        return STATUS_H + (app.hasHeader() ? HEADER_H : 0);
    }

    private boolean inContent(double sx, double sy) {
        double top = contentTop();
        return PhoneDraw.inside(sx, sy, 0, top, app.contentWidth(), app.contentHeight());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closingAt >= 0) {
            return true;
        }
        double sx = toScreenX(event.x());
        double sy = toScreenY(event.y());
        if (dialog != null) {
            if (dialog.mouseClicked(sx, sy)) {
                dialog = null;
            }
            return true;
        }
        if (sy >= SCREEN_H - NAV_H && sy < SCREEN_H && sx >= 0 && sx < SCREEN_W) {
            goHome();
            return true;
        }
        if (app.hasHeader() && PhoneDraw.inside(sx, sy, 0, STATUS_H, 24, HEADER_H)) {
            back();
            return true;
        }
        String action = app.hasHeader() ? app.headerAction() : null;
        if (action != null) {
            int actionWidth = (int) PhoneDraw.width(action, 0.75f) + 8;
            if (PhoneDraw.inside(sx, sy, SCREEN_W - actionWidth - 4, STATUS_H, actionWidth + 4, HEADER_H)) {
                app.onHeaderAction();
                return true;
            }
        }
        if (inContent(sx, sy)) {
            return app.mouseClicked(sx, sy - contentTop(), event.button());
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dialog != null) {
            return true;
        }
        return app.mouseReleased(toScreenX(event.x()), toScreenY(event.y()) - contentTop(), event.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dialog != null) {
            return true;
        }
        return app.mouseDragged(toScreenX(event.x()), toScreenY(event.y()) - contentTop(), event.button(),
                dx / scale, dy / scale);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double sx = toScreenX(mouseX);
        double sy = toScreenY(mouseY);
        return inContent(sx, sy) && app.mouseScrolled(sx, sy - contentTop(), scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            if (dialog != null) {
                dialog = null;
            } else {
                back();
            }
            return true;
        }
        if (dialog != null) {
            return true;
        }
        if (app.keyPressed(event)) {
            return true;
        }
        if (!app.capturesKeyboard() && BlockPhoneClient.openPhoneKey.matches(event)) {
            closePhone();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return dialog == null && app.charTyped(event);
    }
}
