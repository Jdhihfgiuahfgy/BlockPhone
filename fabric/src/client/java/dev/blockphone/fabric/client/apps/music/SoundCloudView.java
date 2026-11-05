package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.browser.WebEngine;
import dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Вкладка «SoundCloud»: сайт SoundCloud во встроенном браузере ({@link SoundCloudWeb}) — поиск, вход, лайки, плейлисты —
 * как на сайте; звук играет и с закрытым телефоном. Сверху — полоска: назад, на главную, обновить.
 */
final class SoundCloudView extends MusicView {
    private static final int BAR = 14;
    private boolean focused;
    private float scale = 1;
    private int pageTop;

    SoundCloudView(MusicApp app) {
        super(app);
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        return "SoundCloud";
    }

    @Override
    void onHidden() {
        focused = false;
        if (SoundCloudWeb.opened()) {
            SoundCloudWeb.view().setFocus(false);
        }
    }

    @Override
    boolean capturesKeyboard() {
        return focused && SoundCloudWeb.opened() && SoundCloudWeb.view().editing();
    }

    @Override
    void render(GuiGraphics g, int mx, int my) {
        int w = width();
        int h = height();
        switch (WebEngine.state()) {
            case DOWNLOADING, EXTRACTING, STARTING, IDLE -> {
                SoundCloudWeb.view();
                float p = WebEngine.progress();
                hint(g, tr("music.sc.starting") + (p >= 0 ? " " + Math.round(p * 100) + "%" : ""), h / 2 - 16);
                return;
            }
            case FAILED -> {
                hint(g, tr("music.sc.failed") + "\n" + WebEngine.error(), h / 2 - 16);
                return;
            }
            default -> {
            }
        }
        WebEngine.View view = SoundCloudWeb.view();
        // Полоска: назад, главная, обновить.
        g.fill(0, 0, w, BAR, PhoneTheme.card());
        button(g, 2, 1, 22, BAR - 2, "‹", mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> view.eval("history.back()"));
        button(g, 26, 1, 40, BAR - 2, tr("music.sc.home"), mx, my, PhoneDraw.ButtonStyle.NORMAL, SoundCloudWeb::home);
        button(g, 68, 1, 22, BAR - 2, "⟳", mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> view.eval("location.reload()"));
        if (view.loading()) {
            PhoneDraw.text(g, tr("music.loading"), 94, 4, PhoneTheme.secondaryText(), 0.55f, false);
        }
        pageTop = BAR;
        // Страница — в масштабе «как телефон»: 2 пикселя страницы на точку экрана телефона (мелко, но по-сайтовому).
        scale = 2.5f;
        int pw = Math.round(w * scale);
        int ph = Math.round((h - BAR) * scale);
        view.draw(g, 0, BAR, w, h - BAR, pw, ph);
        if (!view.ready()) {
            hint(g, tr("music.loading"), h / 2);
        }
    }

    private int px(double x) {
        return (int) Math.round(x * scale);
    }

    private int py(double y) {
        return (int) Math.round((y - pageTop) * scale);
    }

    private boolean onPage(double y) {
        return y >= pageTop && SoundCloudWeb.opened() && SoundCloudWeb.view().ready();
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (!onPage(y)) {
            focused = false;
            return super.mouseClicked(x, y, button);
        }
        WebEngine.View view = SoundCloudWeb.view();
        focused = true;
        view.setFocus(true);
        view.mouseMove(px(x), py(y));
        view.mouseButton(px(x), py(y), button, true);
        return true;
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        if (SoundCloudWeb.opened() && SoundCloudWeb.view().ready()) {
            SoundCloudWeb.view().mouseButton(px(x), py(y), button, false);
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        if (onPage(y)) {
            SoundCloudWeb.view().mouseMove(px(x), py(y));
            return true;
        }
        return super.mouseDragged(x, y, button);
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        if (onPage(y)) {
            SoundCloudWeb.view().wheel(px(x), py(y), amount);
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (focused && SoundCloudWeb.opened()) {
            SoundCloudWeb.view().key(event.key(), event.scancode(), event.modifiers());
            return SoundCloudWeb.view().editing();
        }
        return false;
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        if (focused && SoundCloudWeb.opened()) {
            SoundCloudWeb.view().typeChar(event.codepoint());
            return true;
        }
        return false;
    }
}
