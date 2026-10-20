package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.yandex.YandexApi;
import dev.blockphone.fabric.client.music.yandex.YandexMusic;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Вкладка «Яндекс»: без входа — инструкция (страница входа → скопировать адрес → вставить); после входа —
 * «Моя волна», поиск и «Мне нравится». Неофициальный API: может перестать работать без предупреждения.
 */
final class YandexView extends MusicView {
    private final TextArea tokenInput = new TextArea(true, 0.7f);
    private final TextArea search = new TextArea(false, 0.75f);
    /** Что показано под поиском: «Мне нравится» или «Мои плейлисты». */
    private boolean showPlaylists;

    YandexView(MusicApp app) {
        super(app);
        tokenInput.setMaxLength(2000);
        search.setMaxLength(64);
        search.setOnChange(text -> {
            scroll = 0;
            YandexMusic.search(text.strip());
        });
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        return tr("music.yandex");
    }

    @Override
    String headerAction() {
        return YandexMusic.status() == YandexMusic.Status.READY ? tr("music.logout") : null;
    }

    @Override
    void onHeaderAction() {
        app.showDialog(new PhoneDialog(tr("music.logout_title"), tr("music.logout_message"), tr("music.logout"), true,
                YandexMusic::logout));
    }

    @Override
    void onHidden() {
        tokenInput.setFocused(false);
        search.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return tokenInput.isFocused() || search.isFocused();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        switch (YandexMusic.status()) {
            case NONE, ERROR -> renderLogin(g, mouseX, mouseY);
            case CHECKING -> hint(g, tr("music.checking"), height() / 2 - 10);
            case READY -> renderLibrary(g, mouseX, mouseY);
        }
    }

    private void renderLogin(GuiGraphics g, int mx, int my) {
        int w = width();
        int y = 8;
        PhoneDraw.text(g, tr("music.yandex_login_title"), 8, y, PhoneTheme.text(), 0.85f, false);
        y += 14;
        y += PhoneDraw.wrapped(g, tr("music.yandex_step1"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.65f, false) + 4;
        button(g, 8, y, w - 16, 15, tr("music.yandex_open_login"), mx, my, PhoneDraw.ButtonStyle.PRIMARY,
                () -> Util.getPlatform().openUri(YandexMusic.OAUTH_URL));
        y += 20;
        y += PhoneDraw.wrapped(g, tr("music.yandex_step2"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.65f, false) + 4;
        tokenInput.setBounds(8, y, w - 16, 36);
        tokenInput.setPlaceholder(tr("music.yandex_token_placeholder"));
        tokenInput.render(g, mx, my);
        y += 40;
        boolean ready = !tokenInput.text().isBlank();
        if (ready) {
            button(g, 8, y, w - 16, 15, tr("music.login"), mx, my, PhoneDraw.ButtonStyle.PRIMARY, () -> {
                YandexMusic.login(tokenInput.text());
                tokenInput.setText("");
                tokenInput.setFocused(false);
            });
        } else {
            PhoneDraw.button(g, 8, y, w - 16, 15, tr("music.login"), -1000, -1000, PhoneDraw.ButtonStyle.NORMAL);
        }
        y += 20;
        String error = YandexMusic.error();
        if (YandexMusic.status() == YandexMusic.Status.ERROR && error != null) {
            y += PhoneDraw.wrapped(g, error, 8, y, w - 16, PhoneTheme.danger(), 0.65f, false) + 4;
        }
        PhoneDraw.wrapped(g, tr("music.yandex_note"), 8, y + 2, w - 16, PhoneTheme.secondaryText(), 0.55f, false);
    }

    private void renderLibrary(GuiGraphics g, int mx, int my) {
        int w = width();
        YandexApi.Account account = YandexMusic.account();
        String who = account == null ? "" : account.name() + (account.plus() ? " · " + tr("music.plus") : "");
        PhoneDraw.text(g, PhoneDraw.ellipsize(who, w - 16, 0.6f), 8, 3, PhoneTheme.secondaryText(), 0.6f, false);

        // «Моя волна» — градиентная кнопка.
        int py = 12;
        boolean hover = PhoneDraw.inside(mx, my, 6, py, w - 12, 22);
        int left = PhoneTheme.mix(PhoneTheme.accent(), 0xFFE0559B, 0.5f);
        PhoneDraw.gradientPill(g, 6, py, w - 6, py + 22, hover ? PhoneTheme.mix(left, 0xFFFFFFFF, 0.15f) : left, 0xFFF29544);
        String wave = YandexMusic.startingWave() ? tr("music.loading") : "▶  " + tr("music.my_wave");
        PhoneDraw.centeredText(g, wave, w / 2f, py + 7, 0xFFFFFFFF, 0.85f, true);
        hit(6, py, w - 12, 22, YandexMusic::playWave);

        int sy = py + 28;
        search.setBounds(6, sy, w - 12, 16);
        search.setPlaceholder(tr("music.search_placeholder"));
        search.render(g, mx, my);
        int top = sy + 20;
        if (search.text().isBlank()) {
            // Переключатель «Мне нравится» / «Мои плейлисты».
            int half = (w - 16) / 2;
            PhoneDraw.button(g, 6, top, half, 14, tr("music.liked"), mx, my,
                    showPlaylists ? PhoneDraw.ButtonStyle.NORMAL : PhoneDraw.ButtonStyle.SELECTED);
            hit(6, top, half, 14, () -> {
                showPlaylists = false;
                scroll = 0;
            });
            PhoneDraw.button(g, 10 + half, top, half, 14, tr("music.playlists"), mx, my,
                    showPlaylists ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            hit(10 + half, top, half, 14, () -> {
                showPlaylists = true;
                scroll = 0;
                YandexMusic.loadPlaylists();
            });
            top += 18;
            if (showPlaylists) {
                renderPlaylists(g, mx, my, top);
                return;
            }
        }
        int listMouseY = my >= top ? my : -1000;
        List<Track> list;
        String name;
        String empty;
        boolean loading;
        if (!search.text().isBlank()) {
            list = YandexMusic.searchResults();
            name = tr("music.search_results");
            empty = tr("music.nothing_found");
            loading = YandexMusic.searching();
        } else {
            list = YandexMusic.likes();
            name = tr("music.liked");
            empty = tr("music.no_likes");
            loading = YandexMusic.loadingLikes();
        }
        beginList(g, top, height());
        int y = top - (int) scroll;
        section(g, name + (list != null && !list.isEmpty() ? " · " + list.size() : ""), y + 2);
        y += 12;
        if (list == null || (loading && list.isEmpty())) {
            PhoneDraw.text(g, tr("music.loading"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
        } else if (list.isEmpty()) {
            PhoneDraw.text(g, empty, 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
        } else {
            y = TrackRows.list(this, g, list, y, w, mx, listMouseY, name);
        }
        endList(g);
        clampScroll((list == null ? 0 : list.size()) * TrackRows.H + 20, height() - top);
    }

    /** Список своих плейлистов: обложка, название, число треков; нажатие — треки плейлиста. */
    private void renderPlaylists(GuiGraphics g, int mx, int my, int top) {
        int w = width();
        List<YandexApi.Playlist> playlists = YandexMusic.playlists();
        int listMouseY = my >= top ? my : -1000;
        beginList(g, top, height());
        int y = top - (int) scroll;
        if (playlists == null) {
            PhoneDraw.text(g, tr("music.loading"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
        } else if (playlists.isEmpty()) {
            PhoneDraw.text(g, tr("music.no_playlists"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
        } else {
            for (YandexApi.Playlist playlist : playlists) {
                int rowH = TrackRows.H;
                boolean hover = PhoneDraw.inside(mx, listMouseY, 4, y, w - 8, rowH);
                if (hover) {
                    PhoneDraw.roundedRect(g, 4, y, w - 4, y + rowH, 4, PhoneTheme.cardHover());
                }
                int cs = rowH - 6;
                dev.blockphone.fabric.client.photo.PhotoTextures.Image cover = dev.blockphone.fabric.client.music.Covers.url(playlist.coverUrl());
                if (cover != null) {
                    PhoneDraw.texture(g, cover.id(), 8, y + 3, cs, cs, cover.width(), cover.height());
                } else {
                    PhoneDraw.roundedRect(g, 8, y + 3, 8 + cs, y + 3 + cs, 3, PhoneTheme.card());
                    PhoneDraw.tinted(g, NOTE, 8 + cs / 2 - 5, y + 3 + cs / 2 - 5, 10, 10, 32, 32, PhoneTheme.accent());
                }
                PhoneDraw.text(g, PhoneDraw.ellipsize(playlist.title(), w - cs - 24, 0.75f), 14 + cs, y + 5, PhoneTheme.text(), 0.75f, false);
                PhoneDraw.text(g, tr("music.tracks_count", playlist.trackCount()), 14 + cs, y + 14, PhoneTheme.secondaryText(), 0.6f, false);
                if (y + rowH > top && y < height()) {
                    hit(4, Math.max(y, top), w - 8, rowH - Math.max(0, top - y), () -> app.push(new YandexPlaylistView(app, playlist)));
                }
                y += rowH;
            }
        }
        endList(g);
        clampScroll((playlists == null ? 1 : playlists.size()) * TrackRows.H + 10, height() - top);
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        YandexMusic.Status status = YandexMusic.status();
        TextArea field = status == YandexMusic.Status.READY ? search : tokenInput;
        if (status != YandexMusic.Status.CHECKING && field.mouseClicked(x, y, button)) {
            return true;
        }
        field.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return tokenInput.mouseDragged(x, y) || search.mouseDragged(x, y) || super.mouseDragged(x, y, button);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return tokenInput.mouseReleased() | search.mouseReleased() | super.mouseReleased(x, y, button);
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (tokenInput.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ENTER && !tokenInput.text().isBlank()) {
                YandexMusic.login(tokenInput.text());
                tokenInput.setText("");
                tokenInput.setFocused(false);
                return true;
            }
            return tokenInput.keyPressed(event);
        }
        return search.isFocused() && search.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        if (tokenInput.isFocused()) {
            return tokenInput.charTyped(event);
        }
        return search.isFocused() && search.charTyped(event);
    }

    @Override
    boolean onBack() {
        if (!search.text().isEmpty()) {
            search.setText("");
            return true;
        }
        return false;
    }
}
