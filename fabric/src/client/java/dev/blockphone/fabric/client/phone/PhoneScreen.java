package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.ui.Scrollbar;
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
    /** Ориентация, в которой телефон нарисован сейчас, и когда её поменяли (для анимации поворота). */
    private boolean shownLandscape;
    private long rotatedAt;
    private static final long ROTATE_MS = 260;
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

    /** Ориентация и полный экран — по текущему приложению (перед отрисовкой и вводом). */
    private void applyOrientation() {
        boolean wanted = app != null && !locked && (app.landscape() || compact() && PhoneSettings.get().compactLandscape);
        PhoneLayout.immersive = app != null && !locked && app.immersive();
        if (wanted != shownLandscape) {
            shownLandscape = wanted;
            rotatedAt = System.currentTimeMillis();
        }
        PhoneLayout.landscape = wanted;
        layoutPhone();
    }

    /** Размер и положение телефона: по центру или маленький в правом нижнем углу (режим «на ходу»). */
    private void layoutPhone() {
        if (compact()) {
            // Набок — шире (до 55 % ширины окна), стоя — до 60 % высоты.
            scale = PhoneLayout.landscape
                    ? Math.max(0.4f, Math.min(1.2f, Math.min(height * 0.55f / phoneH(), width * 0.55f / phoneW())))
                    : Math.max(0.4f, Math.min(1f, Math.min(height * 0.6f / phoneH(), width * 0.5f / phoneW())));
            PhoneSettings settings = PhoneSettings.get();
            scale *= Math.max(60, Math.min(160, settings.compactSize)) / 100f;
            scale = Math.max(0.3f, Math.min(scale, Math.min((height - 12f) / phoneH(), (width - 12f) / phoneW())));
            originX = 6 + (width - phoneW() * scale - 12) * clamp01(settings.compactX);
            originY = 6 + (height - phoneH() * scale - 12) * clamp01(settings.compactY);
            return;
        }
        float fit = Math.min((height - 16f) / phoneH(), (width - 16f) / phoneW());
        // Если места много — масштаб кратен 0.5, чтобы пиксели шрифта были ровными.
        scale = fit >= 1f ? Math.min(2f, (float) Math.floor(fit * 2) / 2f) : fit;
        originX = (width - phoneW() * scale) / 2f;
        originY = (height - phoneH() * scale) / 2f;
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 1f : Math.max(0f, Math.min(1f, v));
    }

    /** Телефон «на ходу» перетаскивают за рамку корпуса: смещение мыши от угла телефона. */
    private boolean dragging;
    private double grabX;
    private double grabY;

    /** Точка на рамке корпуса (внутри корпуса, но не на экране). */
    private boolean onBezel(double sx, double sy) {
        boolean inBody = sx >= -PhoneLayout.BEZEL && sy >= -PhoneLayout.BEZEL
                && sx < screenW() + PhoneLayout.BEZEL && sy < screenH() + PhoneLayout.BEZEL;
        return inBody && !PhoneDraw.inside(sx, sy, 0, 0, screenW(), screenH());
    }

    /** Режим «на ходу» ({@link PhonePin}): телефон маленький в углу, фон не затемняется, можно ходить и крутить камеру. */
    /** Телефон без рамки (только экран) или с корпусом. */
    public static void toggleFrameless() {
        PhoneSettings settings = PhoneSettings.get();
        settings.frameless = !settings.frameless;
        settings.save();
    }

    /** Повернуть телефон «на ходу» набок или обратно. */
    public static void toggleCompactLandscape() {
        PhoneSettings settings = PhoneSettings.get();
        settings.compactLandscape = !settings.compactLandscape;
        settings.save();
    }

    public boolean compact() {
        return PhonePin.pinned(this);
    }

    /** Открыт диалог (тогда курсор не прячется, пока не ответишь). */
    public boolean dialogOpen() {
        return dialog != null;
    }

    /** Приложение ждёт ввода текста (тогда клавиши ходьбы не пропускаются в игру). */
    public boolean capturesKeyboard() {
        return app != null && app.capturesKeyboard();
    }

    /** «На ходу»: приложение держит курсор (вводят текст) — не прятать курсор при отпускании клавиши. */
    public boolean holdsCursor() {
        return app != null && app.holdsCursor();
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

    /** Экран, к которому вернуться при закрытии (главное меню, меню паузы); null — в игру. */
    private @org.jetbrains.annotations.Nullable Screen parent;

    public PhoneScreen withParent(@org.jetbrains.annotations.Nullable Screen parent) {
        this.parent = parent;
        return this;
    }

    public void showDialog(PhoneDialog dialog) {
        this.dialog = dialog;
    }

    /** Закрыть телефон сразу, без анимации (например, при переходе в камеру). */
    public void closeImmediately() {
        minecraft.setScreen(null);
    }

    public void closePhone() {
        if (PhonePin.pinned(this)) {
            PhonePin.unpin(minecraft);
            return;
        }
        if (closingAt >= 0) {
            return;
        }
        if (PhoneSettings.get().animations) {
            closingAt = System.currentTimeMillis();
        } else {
            minecraft.setScreen(parent);
        }
    }

    /** Закрыть открытый диалог (как «Отмена»). */
    public void closeDialog() {
        dialog = null;
    }

    /**
     * Приложения общие у полного телефона и телефона «на ходу»: если приложение привязано к другому экрану (или
     * отвязано), привязать к этому перед отрисовкой и вводом.
     */
    private void ensureAttached() {
        if (app != null && app.attachedTo() != this) {
            app.attach(this);
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
        if (PhonePin.hiding()) {
            return; // «на ходу» телефон лишь отдаёт мышь игре — приложения остаются открытыми
        }
        if (PhonePin.pinned(this)) {
            return; // прикреплённый телефон остаётся на экране (его закрыло другое окно игры) — приложения не отвязывать
        }
        PhoneState.markClosed();
        if (app != null) {
            app.onHidden();
            if (app.attachedTo() == this) {
                app.attach(null);
            }
        }
    }

    @Override
    public void tick() {
        if (closingAt >= 0 && System.currentTimeMillis() - closingAt >= ANIM_MS) {
            minecraft.setScreen(parent);
            return;
        }
        // Приложение могло стать недоступным (например, мессенджер после выхода с сервера).
        if (!locked && PhoneState.current() != app) {
            show(PhoneState.current());
        }
        ensureAttached();
        app.tick();
    }

    // --- Отрисовка ---

    /** 0 — телефон спрятан внизу, 1 — полностью выдвинут. */
    private float slideProgress() {
        if (!PhoneSettings.get().animations || (PhonePin.pinned(this) && closingAt < 0)) {
            return 1f;
        }
        long now = System.currentTimeMillis();
        float t = closingAt >= 0
                ? 1f - Math.min(1f, (now - closingAt) / (float) ANIM_MS)
                : Math.min(1f, (now - openedAt) / (float) ANIM_MS);
        float inv = 1f - t;
        return 1f - inv * inv * inv; // ease-out cubic
    }

    private static volatile float @org.jetbrains.annotations.Nullable [] shownRect;
    private static volatile long shownAt;

    /** Экран телефона на окне в последнем кадре: x, y, ширина, высота (координаты интерфейса); null — давно не рисовался. */
    public static float @org.jetbrains.annotations.Nullable [] shownScreenRect() {
        return System.currentTimeMillis() - shownAt < 250 ? shownRect : null;
    }

    private float phoneY() {
        return originY + (1f - slideProgress()) * (height - originY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) { // mc:render — в 26.1 extractRenderState/extractBackground
        if (compact()) {
            return; // «На ходу» мир виден полностью.
        }
        int alpha = (int) (0x60 * slideProgress());
        g.fillGradient(0, 0, width, height, alpha / 3 << 24, alpha << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) { // mc:render — в 26.1 extractRenderState/extractBackground
        ensureAttached();
        applyOrientation();
        float py = phoneY();
        g.pose().pushMatrix();
        g.pose().translate(originX, py);
        g.pose().scale(scale, scale);
        // Поворот набок/обратно: раскладка уже новая, а корпус доворачивается из прежнего положения.
        float t = PhoneSettings.get().animations ? Math.min(1f, (System.currentTimeMillis() - rotatedAt) / (float) ROTATE_MS) : 1f;
        if (t < 1f) {
            float eased = 1f - (1f - t) * (1f - t) * (1f - t);
            float angle = (float) Math.toRadians((shownLandscape ? 90 : -90) * (1f - eased));
            g.pose().translate(phoneW() / 2f, phoneH() / 2f);
            g.pose().rotate(angle);
            g.pose().translate(-phoneW() / 2f, -phoneH() / 2f);
        }

        int lx = (int) Math.floor((mouseX - originX) / scale);
        int ly = (int) Math.floor((mouseY - py) / scale);
        renderPhone(g, lx, ly, delta);
        if (t >= 1f && slideProgress() >= 1f) {
            // Где на окне экран телефона (координаты интерфейса) — для трансляции на экран в мире.
            shownRect = new float[]{originX + BEZEL * scale, py + BEZEL * scale, screenW() * scale, screenH() * scale};
            shownAt = System.currentTimeMillis();
        }

        g.pose().popMatrix();
    }

    private void renderPhone(GuiGraphics g, int mx, int my, float delta) {
        boolean frameless = PhoneSettings.get().frameless;
        if (!frameless) {
            // Корпус: тонкая окантовка, подсвеченная акцентом.
            PhoneDraw.rawRoundedRect(g, 0, 0, phoneW(), phoneH(), BODY_RADIUS, PhoneTheme.bodyEdge());
            PhoneDraw.rawRoundedRect(g, 1, 1, phoneW() - 1, phoneH() - 1, BODY_RADIUS - 1, PhoneTheme.body());
        } else if (compact() && (dragging || onBezel(mx - BEZEL, my - BEZEL))) {
            // Без рамки «на ходу» телефон всё равно таскают за край — показать, где он.
            PhoneDraw.outline(g, BEZEL - 2, BEZEL - 2, screenW() + 4, screenH() + 4, PhoneTheme.accent());
        }

        g.pose().pushMatrix();
        g.pose().translate(BEZEL, BEZEL);
        g.enableScissor(0, 0, screenW(), screenH());
        int sx = mx - BEZEL;
        int sy = my - BEZEL;

        // Фон: обои или цвет приложения.
        if (app.transparentBackground()) {
            Wallpapers.draw(g, 0, 0, screenW(), screenH());
        } else {
            g.fill(0, 0, screenW(), screenH(), app.backgroundColor());
        }

        // Содержимое приложения.
        int contentTop = statusH() + (app.hasHeader() ? HEADER_H : 0);
        g.pose().pushMatrix();
        g.pose().translate(0, contentTop);
        g.enableScissor(0, 0, app.contentWidth(), app.contentHeight());
        Scrollbar.begin(g, app.contentWidth());
        app.render(g, sx, sy - contentTop, delta);
        Scrollbar.end(g, sx, sy - contentTop);
        g.disableScissor();
        g.pose().popMatrix();

        if (app.hasHeader()) {
            renderHeader(g, sx, sy);
        }
        if (!PhoneLayout.immersive) {
            renderStatusBar(g);
            renderNavBar(g, sx, sy);
        } else if (!app.capturesMouse() && sy >= screenH() - NAV_H - 4 && sy < screenH()) {
            // Пока мышь захвачена приложением (камера в игре), «назад/домой» не всплывают — курсора нет.
            renderFloatingNav(g, sx, sy);
        }
        if (dialog != null) {
            dialog.render(g, sx, sy);
        }
        if (CallOverlay.visible()) {
            CallOverlay.render(g, sx, sy);
        }

        g.disableScissor();
        if (frameless) {
            g.pose().popMatrix();
            return;
        }
        // Скруглённые углы экрана и камера-«точка».
        PhoneDraw.roundCornersMask(g, 0, 0, screenW(), screenH(), SCREEN_RADIUS, PhoneTheme.body());
        // Набок — камера сбоку (слева), как у настоящего телефона.
        int camX = PhoneLayout.landscape ? 6 : screenW() / 2;
        int camY = PhoneLayout.landscape ? screenH() / 2 : 6;
        PhoneDraw.rawRoundedRect(g, camX - 3, camY - 3, camX + 3, camY + 3, 3, 0xFF05070C);
        g.fill(camX - 1, camY - 1, camX, camY, 0xFF2A3A5A);
        g.pose().popMatrix();
    }

    private static final int BACK_X = 4;
    private static final int BACK_W = 18;
    private static final int HEADER_BUTTON_H = 14;

    private int headerButtonY() {
        return statusH() + (HEADER_H - HEADER_BUTTON_H) / 2;
    }

    private static int actionWidth(String action) {
        return Math.max(BACK_W, (int) PhoneDraw.width(action, 0.75f) + 10);
    }

    private void renderHeader(GuiGraphics g, int sx, int sy) {
        int top = statusH();
        g.fill(0, top, screenW(), top + HEADER_H, app.headerColor());
        g.fill(0, top + HEADER_H - 1, screenW(), top + HEADER_H, PhoneTheme.separator());
        int by = headerButtonY();
        boolean backHovered = PhoneDraw.inside(sx, sy, BACK_X, by, BACK_W, HEADER_BUTTON_H);
        PhoneDraw.card(g, BACK_X, by, BACK_X + BACK_W, by + HEADER_BUTTON_H, backHovered);
        PhoneDraw.centeredText(g, "<", BACK_X + BACK_W / 2f, by + 3, PhoneTheme.text(), 1f, false);

        String action = app.headerAction();
        int actionWidth = 0;
        if (action != null) {
            actionWidth = actionWidth(action);
            int ax = screenW() - 4 - actionWidth;
            boolean hovered = PhoneDraw.inside(sx, sy, ax, by, actionWidth, HEADER_BUTTON_H);
            PhoneDraw.card(g, ax, by, ax + actionWidth, by + HEADER_BUTTON_H, hovered);
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(action, actionWidth - 4, 0.75f), ax + actionWidth / 2f, by + 4,
                    app.headerActionColor(), 0.75f, false);
        }
        int titleRoom = screenW() - 2 * (Math.max(BACK_W, actionWidth) + 10);
        String title = PhoneDraw.ellipsize(app.title(), titleRoom, 1f);
        PhoneDraw.centeredText(g, title, screenW() / 2f, top + 6, app.headerTextColor(), 1f, false);
    }

    /** Текст строки состояния и навигации: белый на обоях и тёмных экранах, иначе по теме. */
    private int barColor() {
        return app.transparentBackground() || app.lightStatusBar() ? PhoneTheme.WHITE : PhoneTheme.text();
    }

    private void renderStatusBar(GuiGraphics g) {
        int color = barColor();
        int dim = (color & 0x00FFFFFF) | 0x55000000;

        PhoneDraw.text(g, PhoneClock.time(), 8, 3.5f, color, 0.75f, false);

        // «Связь»: по пингу на сервере; в одиночной игре — всегда полная.
        int bars = signalBars();
        int x = screenW() - 36;
        for (int i = 0; i < 4; i++) {
            int h = 2 + i * 2;
            g.fill(x + i * 3, 10 - h, x + i * 3 + 2, 10, i < bars ? color : dim);
        }
        // Батарейка.
        int bx = screenW() - 22;
        PhoneDraw.outline(g, bx, 4, 13, 7, color);
        g.fill(bx + 13, 6, bx + 14, 9, color);
        g.fill(bx + 2, 6, bx + 11, 9, PhoneTheme.accent());
    }

    /** Деления связи 0..4: одиночная игра — 4; на сервере — по пингу (до 80 мс — 4, 150 — 3, 300 — 2, 600 — 1). */
    public static int signalBars() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.hasSingleplayerServer() || mc.player == null || mc.getConnection() == null) {
            return 4;
        }
        var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info == null) {
            return 4;
        }
        int ping = info.getLatency();
        return ping <= 0 || ping < 80 ? 4 : ping < 150 ? 3 : ping < 300 ? 2 : ping < 600 ? 1 : 0;
    }

    private static final int NAV_BUTTON_W = 26;

    private int navBackX() {
        return screenW() / 2 - NAV_BUTTON_W - 4;
    }

    private int navHomeX() {
        return screenW() / 2 + 4;
    }

    /** Нижняя навигация: «назад» и «домой». */
    private void renderNavBar(GuiGraphics g, int sx, int sy) {
        int top = screenH() - navH();
        if (!app.transparentBackground()) {
            g.fill(0, top, screenW(), screenH(), app.headerColor());
            g.fill(0, top, screenW(), top + 1, PhoneTheme.separator());
        }
        int color = barColor();
        int cy = top + navH() / 2;
        int backX = navBackX();
        int homeX = navHomeX();
        if (PhoneDraw.inside(sx, sy, backX, top, NAV_BUTTON_W, navH())) {
            PhoneDraw.roundedRect(g, backX, top + 1, backX + NAV_BUTTON_W, screenH() - 1, 3, (color & 0x00FFFFFF) | 0x26000000);
        }
        if (PhoneDraw.inside(sx, sy, homeX, top, NAV_BUTTON_W, navH())) {
            PhoneDraw.roundedRect(g, homeX, top + 1, homeX + NAV_BUTTON_W, screenH() - 1, 3, (color & 0x00FFFFFF) | 0x26000000);
        }
        // Стрелка «назад» — треугольник из полосок.
        int ax = backX + NAV_BUTTON_W / 2 - 2;
        for (int i = 0; i < 4; i++) {
            g.fill(ax + i, cy - i, ax + i + 1, cy + i + 1, color);
        }
        // «Домой» — квадратик с рамкой.
        int hx = homeX + NAV_BUTTON_W / 2 - 3;
        PhoneDraw.outline(g, hx, cy - 3, 7, 7, color);
    }

    /** Полный экран: «назад» и «домой» на полупрозрачной полосе, пока мышь у нижнего края. */
    private void renderFloatingNav(GuiGraphics g, int sx, int sy) {
        int top = screenH() - NAV_H;
        g.fill(0, top, screenW(), screenH(), 0xA0000000);
        int cy = top + NAV_H / 2;
        int ax = navBackX() + NAV_BUTTON_W / 2 - 2;
        for (int i = 0; i < 4; i++) {
            g.fill(ax + i, cy - i, ax + i + 1, cy + i + 1, PhoneTheme.WHITE);
        }
        PhoneDraw.outline(g, navHomeX() + NAV_BUTTON_W / 2 - 3, cy - 3, 7, 7, PhoneTheme.WHITE);
    }

    // --- Ввод ---

    private double toScreenX(double mouseX) {
        return (mouseX - originX) / scale - BEZEL;
    }

    private double toScreenY(double mouseY) {
        return (mouseY - phoneY()) / scale - BEZEL;
    }

    private double contentTop() {
        return statusH() + (app.hasHeader() ? HEADER_H : 0);
    }

    private boolean inContent(double sx, double sy) {
        double top = contentTop();
        return PhoneDraw.inside(sx, sy, 0, top, app.contentWidth(), app.contentHeight());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        ensureAttached();
        if (closingAt >= 0) {
            return true;
        }
        applyOrientation();
        if (dialog == null && app.capturesMouse()) {
            return app.mouseClicked(app.contentWidth() / 2.0, app.contentHeight() / 2.0, event.button());
        }
        double sx = toScreenX(event.x());
        double sy = toScreenY(event.y());
        if (CallOverlay.mouseClicked(sx, sy)) {
            return true;
        }
        dragging = false;
        if (compact() && event.button() == 0 && onBezel(sx, sy)) {
            dragging = true;
            grabX = event.x() - originX;
            grabY = event.y() - originY;
            return true;
        }
        // ПКМ мимо корпуса телефона — «назад» (если включено в настройках; закрывает и диалог).
        if (event.button() == 1 && dev.blockphone.fabric.client.config.PhoneSettings.get().rightClickBack
                && (sx < -PhoneLayout.BEZEL || sy < -PhoneLayout.BEZEL || sx >= screenW() + PhoneLayout.BEZEL || sy >= screenH() + PhoneLayout.BEZEL)) {
            if (dialog != null) {
                dialog = null;
            } else {
                back();
            }
            return true;
        }
        if (dialog != null) {
            if (dialog.mouseClicked(sx, sy)) {
                dialog = null;
            }
            return true;
        }
        if (sy >= screenH() - NAV_H && sy < screenH()) {
            boolean onBack = PhoneDraw.inside(sx, sy, navBackX(), screenH() - NAV_H, NAV_BUTTON_W, NAV_H);
            boolean onHome = PhoneDraw.inside(sx, sy, navHomeX(), screenH() - NAV_H, NAV_BUTTON_W, NAV_H);
            if (!PhoneLayout.immersive || onBack || onHome) {
                if (onBack) {
                    back();
                } else if (onHome) {
                    goHome();
                }
                return true;
            }
        }
        if (app.hasHeader() && PhoneDraw.inside(sx, sy, 0, statusH(), BACK_X + BACK_W + 2, HEADER_H)) {
            back();
            return true;
        }
        String action = app.hasHeader() ? app.headerAction() : null;
        if (action != null) {
            int actionWidth = actionWidth(action);
            if (PhoneDraw.inside(sx, sy, screenW() - 6 - actionWidth, statusH(), actionWidth + 6, HEADER_H)) {
                app.onHeaderAction();
                return true;
            }
        }
        if (app.hasHeader() && event.button() == 0 && PhoneDraw.inside(sx, sy, 0, statusH(), screenW(), HEADER_H) && app.onTitleClick()) {
            return true;
        }
        if (inContent(sx, sy)) {
            if (event.button() == 0 && Scrollbar.mouseClicked(sx, sy - contentTop())) {
                return true;
            }
            return app.mouseClicked(sx, sy - contentTop(), event.button());
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        applyOrientation();
        if (dragging) {
            dragging = false;
            PhoneSettings.get().save();
            return true;
        }
        if (dialog != null) {
            return true;
        }
        if (app.capturesMouse()) {
            return app.mouseReleased(app.contentWidth() / 2.0, app.contentHeight() / 2.0, event.button());
        }
        if (Scrollbar.mouseReleased()) {
            return true;
        }
        return app.mouseReleased(toScreenX(event.x()), toScreenY(event.y()) - contentTop(), event.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        applyOrientation();
        if (dragging) {
            PhoneSettings settings = PhoneSettings.get();
            float freeW = width - phoneW() * scale - 12;
            float freeH = height - phoneH() * scale - 12;
            settings.compactX = freeW > 0 ? clamp01((float) ((event.x() - grabX - 6) / freeW)) : 1f;
            settings.compactY = freeH > 0 ? clamp01((float) ((event.y() - grabY - 6) / freeH)) : 1f;
            layoutPhone();
            return true;
        }
        if (dialog != null) {
            return true;
        }
        if (Scrollbar.mouseDragged(toScreenY(event.y()) - contentTop())) {
            return true;
        }
        return app.mouseDragged(toScreenX(event.x()), toScreenY(event.y()) - contentTop(), event.button(),
                dx / scale, dy / scale);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        applyOrientation();
        double sx = toScreenX(mouseX);
        double sy = toScreenY(mouseY);
        boolean handled = inContent(sx, sy) && app.mouseScrolled(sx, sy - contentTop(), scrollY);
        if (handled) {
            Scrollbar.clampCurrent();
        }
        return handled;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        ensureAttached();
        // «На ходу» с курсором: клавиша поворота кладёт телефон набок и обратно (если не набирается текст).
        if (compact() && !app.capturesKeyboard() && BlockPhoneClient.rotateKey.matches(event)) {
            toggleCompactLandscape();
            return true;
        }
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
        if (!app.typingText() && BlockPhoneClient.openPhoneKey.matches(event)) {
            closePhone();
            return true;
        }
        if (!app.capturesKeyboard() && BlockPhoneClient.compactKey.matches(event)) {
            PhonePin.toggle(minecraft);
            return true;
        }
        if (!app.capturesKeyboard() && BlockPhoneClient.framelessKey.matches(event)) {
            toggleFrameless();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return dialog == null && app.charTyped(event);
    }
}
