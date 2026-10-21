package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Экран приложения «Музыка». Клики — через области, записанные при отрисовке ({@link #hit}); ползунки
 * (перемотка, громкость) — через {@link #slider}: нажатие и перетаскивание меняют значение.
 */
abstract class MusicView {
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private record Slider(int x, int y, int w, int h, Consumer<Float> onChange) {
    }

    protected final MusicApp app;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    private Slider dragging;
    protected double scroll;
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;

    protected MusicView(MusicApp app) {
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

    /** Корневой экран вкладки: под ним мини-плеер и вкладки. */
    boolean root() {
        return false;
    }

    final void renderView(GuiGraphics g, int mouseX, int mouseY) {
        hits.clear();
        sliders.clear();
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

    /** Ползунок: клик или перетаскивание внутри области → onChange(0..1). */
    protected void slider(int x, int y, int w, int h, Consumer<Float> onChange) {
        sliders.add(new Slider(x, y, w, h, onChange));
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
        for (Slider s : sliders) {
            if (PhoneDraw.inside(x, y, s.x(), s.y() - 3, s.w(), s.h() + 6)) {
                dragging = s;
                drag(x);
                return true;
            }
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

    boolean mouseDragged(double x, double y, int button) {
        if (dragging != null) {
            drag(x);
            return true;
        }
        return false;
    }

    boolean mouseReleased(double x, double y, int button) {
        boolean was = dragging != null;
        dragging = null;
        return was;
    }

    private void drag(double x) {
        float value = (float) ((x - dragging.x()) / Math.max(1, dragging.w()));
        dragging.onChange().accept(Math.max(0f, Math.min(1f, value)));
    }

    boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 20;
        return true;
    }

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

    static final Identifier PLAY = icon("play");
    static final Identifier PAUSE = icon("pause");
    static final Identifier NEXT = icon("next");
    static final Identifier PREV = icon("prev");
    static final Identifier SHUFFLE = icon("shuffle");
    static final Identifier REPEAT = icon("repeat");
    static final Identifier REPEAT_ONE = icon("repeat_one");
    static final Identifier NOTE = icon("note");
    static final Identifier VOLUME = icon("volume");
    static final Identifier LIKE = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/social/like.png");
    static final Identifier LIKED = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/social/liked.png");

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/music/" + name + ".png");
    }

    static String tr(String key, Object... args) {
        return PhoneLang.tr(key, args);
    }

    void hint(GuiGraphics g, String text, int top) {
        PhoneDraw.wrapped(g, text, 12, top, width() - 24, PhoneTheme.secondaryText(), 0.75f, true);
    }

    void section(GuiGraphics g, String text, int y) {
        PhoneDraw.text(g, text.toUpperCase(dev.blockphone.fabric.client.phone.PhoneClock.locale()), 8, y, PhoneTheme.secondaryText(),
                0.6f, false);
    }

    void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, PhoneDraw.ButtonStyle style, Runnable action) {
        PhoneDraw.button(g, x, y, w, h, label, mx, my, style);
        hit(x, y, w, h, action);
    }

    /** Кнопка-значок (круглая подсветка под курсором). */
    void iconButton(GuiGraphics g, Identifier icon, int cx, int cy, int size, int mx, int my, int color, Runnable action) {
        int half = size / 2 + 3;
        if (PhoneDraw.inside(mx, my, cx - half, cy - half, half * 2, half * 2)) {
            PhoneDraw.roundedRect(g, cx - half, cy - half, cx + half, cy + half, half, PhoneTheme.cardHover());
        }
        PhoneDraw.tinted(g, icon, cx - size / 2, cy - size / 2, size, size, 32, 32, color);
        hit(cx - half, cy - half, half * 2, half * 2, action);
    }

    /** Полоса прогресса/громкости с бегунком. */
    void bar(GuiGraphics g, int x, int y, int w, float value, boolean knob) {
        int filled = Math.round(w * Math.max(0, Math.min(1, value)));
        PhoneDraw.roundedRect(g, x, y, x + w, y + 3, 1, PhoneTheme.separator());
        PhoneDraw.roundedRect(g, x, y, x + filled, y + 3, 1, PhoneTheme.accent());
        if (knob) {
            PhoneDraw.roundedRect(g, x + filled - 3, y - 2, x + filled + 4, y + 5, 3, PhoneTheme.accent());
        }
    }

    static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}
