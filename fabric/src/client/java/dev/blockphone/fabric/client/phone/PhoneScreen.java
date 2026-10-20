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
    /** Показан экран блокировки (текущее приложение откроется после разблокировки). */
    private boolean locked;

    public PhoneScreen() {
        super(Component.translatable("key.blockphone.open"));
    }

    @Override
    protected void init() {
        layoutPhone();
        if (app == null) {
            if (PhoneState.shouldLock()) {
                locked = true;
                show(PhoneState.app(PhoneState.LOCK));
            } else {
                show(PhoneState.current());
            }
        }
    }

    /** Размер и положение телефона: по центру или маленький в правом нижнем углу (режим «на ходу»). */
    private void layoutPhone() {
        if (compact()) {
            scale = Math.max(0.4f, Math.min(1f, height * 0.6f / PHONE_H));
            originX = width - PHONE_W * scale - 6;
            originY = height - PHONE_H * scale - 6;
            return;
        }
        float fit = (height - 16f) / PHONE_H;
        // Если места много — масштаб кратен 0.5, чтобы пиксели шрифта были ровными.
        scale = fit >= 1f ? Math.min(2f, (float) Math.floor(fit * 2) / 2f) : fit;
        originX = (width - PHONE_W * scale) / 2f;
        originY = (height - PHONE_H * scale) / 2f;
    }

    /** Режим «на ходу»: телефон маленький в углу, фон не затемняется, можно ходить (пока не печатаешь). */
    public boolean compact() {
        return PhoneSettings.get().compactPhone;
    }

    /** Переключить режим «на ходу». */
    public void toggleCompact() {
        PhoneSettings settings = PhoneSettings.get();
        settings.compactPhone = !settings.compactPhone;
        settings.save();
        layoutPhone();
    }

    /** Приложение ждёт ввода текста (тогда клавиши ходьбы не пропускаются в игру). */
    public boolean capturesKeyboard() {
        return app != null && app.capturesKeyboard();
    }

    /** Снять блокировку: открыть приложение, которое было открыто до блокировки. */
    public void unlock() {
        if (locked) {
            locked = false;
            show(PhoneState.current());
        }
    }

    public boolean locked() {
        return locked;
    }

    // --- Навигация ---

    public void openApp(String id) {
        locked = false;
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
        if (locked) {
            closePhone();
            return;
        }
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

    /** Закрыть открытый диалог (как «Отмена»). */
    public void closeDialog() {
        dialog = null;
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
        PhoneState.markClosed();
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
        if (!locked && PhoneState.current() != app) {
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
        if (compact()) {
            return; // «На ходу» мир виден полностью.
        }
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
        // Корпус: тонкая окантовка, подсвеченная акцентом.
        PhoneDraw.rawRoundedRect(g, 0, 0, PHONE_W, PHONE_H, BODY_RADIUS, PhoneTheme.bodyEdge());
        PhoneDraw.rawRoundedRect(g, 1, 1, PHONE_W - 1, PHONE_H - 1, BODY_RADIUS - 1, PhoneTheme.body());

        g.pose().pushMatrix();
        g.pose().translate(BEZEL, BEZEL);
        g.enableScissor(0, 0, SCREEN_W, SCREEN_H);
        int sx = mx - BEZEL;
        int sy = my - BEZEL;

        // Фон: обои или цвет приложения.
        if (app.transparentBackground()) {
            Wallpapers.draw(g, 0, 0, SCREEN_W, SCREEN_H);
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
        if (CallOverlay.visible()) {
            CallOverlay.render(g, sx, sy);
        }

        g.disableScissor();
        // Скруглённые углы экрана и камера-«точка».
        PhoneDraw.roundCornersMask(g, 0, 0, SCREEN_W, SCREEN_H, SCREEN_RADIUS, PhoneTheme.body());
        PhoneDraw.rawRoundedRect(g, SCREEN_W / 2 - 3, 3, SCREEN_W / 2 + 3, 9, 3, 0xFF05070C);
        g.fill(SCREEN_W / 2 - 1, 5, SCREEN_W / 2, 6, 0xFF2A3A5A);
        g.pose().popMatrix();
    }

    private static final int BACK_X = 4;
    private static final int BACK_W = 18;
    private static final int HEADER_BUTTON_H = 14;

    private int headerButtonY() {
        return STATUS_H + (HEADER_H - HEADER_BUTTON_H) / 2;
    }

    private static int actionWidth(String action) {
        return Math.max(BACK_W, (int) PhoneDraw.width(action, 0.75f) + 10);
    }

    private void renderHeader(GuiGraphics g, int sx, int sy) {
        int top = STATUS_H;
        g.fill(0, top, SCREEN_W, top + HEADER_H, app.headerColor());
        g.fill(0, top + HEADER_H - 1, SCREEN_W, top + HEADER_H, PhoneTheme.separator());
        int by = headerButtonY();
        boolean backHovered = PhoneDraw.inside(sx, sy, BACK_X, by, BACK_W, HEADER_BUTTON_H);
        PhoneDraw.card(g, BACK_X, by, BACK_X + BACK_W, by + HEADER_BUTTON_H, backHovered);
        PhoneDraw.centeredText(g, "<", BACK_X + BACK_W / 2f, by + 3, PhoneTheme.text(), 1f, false);

        String action = app.headerAction();
        int actionWidth = 0;
        if (action != null) {
            actionWidth = actionWidth(action);
            int ax = SCREEN_W - 4 - actionWidth;
            boolean hovered = PhoneDraw.inside(sx, sy, ax, by, actionWidth, HEADER_BUTTON_H);
            PhoneDraw.card(g, ax, by, ax + actionWidth, by + HEADER_BUTTON_H, hovered);
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(action, actionWidth - 4, 0.75f), ax + actionWidth / 2f, by + 4,
                    app.headerActionColor(), 0.75f, false);
        }
        int titleRoom = SCREEN_W - 2 * (Math.max(BACK_W, actionWidth) + 10);
        String title = PhoneDraw.ellipsize(app.title(), titleRoom, 1f);
        PhoneDraw.centeredText(g, title, SCREEN_W / 2f, top + 6, app.headerTextColor(), 1f, false);
    }

    /** Текст строки состояния и навигации: белый на обоях и тёмных экранах, иначе по теме. */
    private int barColor() {
        return app.transparentBackground() || app.lightStatusBar() ? PhoneTheme.WHITE : PhoneTheme.text();
    }

    private void renderStatusBar(GuiGraphics g) {
        int color = barColor();
        int dim = (color & 0x00FFFFFF) | 0x55000000;

        PhoneDraw.text(g, PhoneClock.time(), 8, 3.5f, color, 0.75f, false);

        // «Связь»: полные деления — на сервере стоит BlockPhone.
        int bars = ClientNetwork.serverHello() != null ? 4 : 1;
        int x = SCREEN_W - 36;
        for (int i = 0; i < 4; i++) {
            int h = 2 + i * 2;
            g.fill(x + i * 3, 10 - h, x + i * 3 + 2, 10, i < bars ? color : dim);
        }
        // Батарейка.
        int bx = SCREEN_W - 22;
        g.renderOutline(bx, 4, 13, 7, color);
        g.fill(bx + 13, 6, bx + 14, 9, color);
        g.fill(bx + 2, 6, bx + 11, 9, PhoneTheme.accent());
    }

    private static final int NAV_BUTTON_W = 26;

    private int navBackX() {
        return SCREEN_W / 2 - NAV_BUTTON_W - 4;
    }

    private int navHomeX() {
        return SCREEN_W / 2 + 4;
    }

    /** Нижняя навигация: «назад» и «домой». */
    private void renderNavBar(GuiGraphics g, int sx, int sy) {
        int top = SCREEN_H - NAV_H;
        if (!app.transparentBackground()) {
            g.fill(0, top, SCREEN_W, SCREEN_H, app.headerColor());
            g.fill(0, top, SCREEN_W, top + 1, PhoneTheme.separator());
        }
        int color = barColor();
        int cy = top + NAV_H / 2;
        int backX = navBackX();
        int homeX = navHomeX();
        if (PhoneDraw.inside(sx, sy, backX, top, NAV_BUTTON_W, NAV_H)) {
            PhoneDraw.roundedRect(g, backX, top + 1, backX + NAV_BUTTON_W, SCREEN_H - 1, 3, (color & 0x00FFFFFF) | 0x26000000);
        }
        if (PhoneDraw.inside(sx, sy, homeX, top, NAV_BUTTON_W, NAV_H)) {
            PhoneDraw.roundedRect(g, homeX, top + 1, homeX + NAV_BUTTON_W, SCREEN_H - 1, 3, (color & 0x00FFFFFF) | 0x26000000);
        }
        // Стрелка «назад» — треугольник из полосок.
        int ax = backX + NAV_BUTTON_W / 2 - 2;
        for (int i = 0; i < 4; i++) {
            g.fill(ax + i, cy - i, ax + i + 1, cy + i + 1, color);
        }
        // «Домой» — квадратик с рамкой.
        int hx = homeX + NAV_BUTTON_W / 2 - 3;
        g.renderOutline(hx, cy - 3, 7, 7, color);
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
        if (CallOverlay.mouseClicked(sx, sy)) {
            return true;
        }
        if (dialog != null) {
            if (dialog.mouseClicked(sx, sy)) {
                dialog = null;
            }
            return true;
        }
        if (sy >= SCREEN_H - NAV_H && sy < SCREEN_H) {
            if (PhoneDraw.inside(sx, sy, navBackX(), SCREEN_H - NAV_H, NAV_BUTTON_W, NAV_H)) {
                back();
            } else if (PhoneDraw.inside(sx, sy, navHomeX(), SCREEN_H - NAV_H, NAV_BUTTON_W, NAV_H)) {
                goHome();
            }
            return true;
        }
        if (app.hasHeader() && PhoneDraw.inside(sx, sy, 0, STATUS_H, BACK_X + BACK_W + 2, HEADER_H)) {
            back();
            return true;
        }
        String action = app.hasHeader() ? app.headerAction() : null;
        if (action != null) {
            int actionWidth = actionWidth(action);
            if (PhoneDraw.inside(sx, sy, SCREEN_W - 6 - actionWidth, STATUS_H, actionWidth + 6, HEADER_H)) {
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
        if (!app.capturesKeyboard() && BlockPhoneClient.compactKey.matches(event)) {
            toggleCompact();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return dialog == null && app.charTyped(event);
    }
}
