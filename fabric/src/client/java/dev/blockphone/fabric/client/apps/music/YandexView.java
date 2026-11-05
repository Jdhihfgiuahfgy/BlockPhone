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
 * Вкладка «Яндекс»: без входа — инструкция (страница входа → скопировать адрес → вставить); после входа — поиск по
 * всему и разделы «Главная» («Моя волна» с настройками, «для вас», чарт, новинки, плейлисты), «Коллекция» (треки,
 * плейлисты, альбомы) и «Книги» (аудиокниги и подкасты, «Продолжить»). ПКМ по треку — волна по треку, артист, альбом.
 * Неофициальный API: может перестать работать без предупреждения.
 */
final class YandexView extends MusicView {
    private final TextArea tokenInput = new TextArea(true, 0.7f);
    private final TextArea search = new TextArea(false, 0.75f);
    private static final net.minecraft.resources.Identifier SETTINGS_GLYPH = net.minecraft.resources.Identifier.fromNamespaceAndPath(
            dev.blockphone.common.BlockPhone.MOD_ID, "textures/gui/glyph/settings.png");

    private enum Section { HOME, COLLECTION, BOOKS }

    private enum Collection { TRACKS, PLAYLISTS, ALBUMS }

    private Section section = Section.HOME;
    private Collection collection = Collection.TRACKS;

    YandexView(MusicApp app) {
        super(app);
        tokenInput.setMaxLength(2000);
        search.setMaxLength(64);
        search.setOnChange(text -> {
            scroll = 0;
            dev.blockphone.fabric.client.music.yandex.YandexBrowse.search(text.strip());
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
        int sy = 12;
        search.setBounds(6, sy, w - 12, 16);
        search.setPlaceholder(tr("music.y.search_all"));
        search.render(g, mx, my);
        int top = sy + 20;
        if (!search.text().isBlank()) {
            renderSearch(g, mx, my, top);
            return;
        }
        // Разделы: Главная / Коллекция / Книги.
        Section[] sections = Section.values();
        int bw = (w - 12 - 4 * (sections.length - 1)) / sections.length;
        for (int i = 0; i < sections.length; i++) {
            Section sec = sections[i];
            int bx = 6 + i * (bw + 4);
            PhoneDraw.button(g, bx, top, bw, 14, tr("music.y.section." + sec.name().toLowerCase()), mx, my,
                    sec == section ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            hit(bx, top, bw, 14, () -> {
                section = sec;
                scroll = 0;
            });
        }
        top += 18;
        switch (section) {
            case HOME -> renderHome(g, mx, my, top);
            case COLLECTION -> renderCollection(g, mx, my, top);
            case BOOKS -> renderBooks(g, mx, my, top);
        }
    }

    // --- Главная ---

    private void renderHome(GuiGraphics g, int mx, int my, int top) {
        int w = width();
        int listMouseY = my >= top ? my : -1000;
        dev.blockphone.fabric.client.music.yandex.YandexCatalog.Landing landing = dev.blockphone.fabric.client.music.yandex.YandexBrowse.landing();
        beginList(g, top, height());
        int y = top + 2 - (int) scroll;
        // «Моя волна» — градиентная кнопка и шестерёнка настроек.
        int pw = w - 12 - 26;
        boolean hover = PhoneDraw.inside(mx, listMouseY, 6, y, pw, 22);
        int left = PhoneTheme.mix(PhoneTheme.accent(), 0xFFE0559B, 0.5f);
        PhoneDraw.gradientPill(g, 6, y, 6 + pw, y + 22, hover ? PhoneTheme.mix(left, 0xFFFFFFFF, 0.15f) : left, 0xFFF29544);
        String wave = YandexMusic.startingWave() ? tr("music.loading") : "▶  " + tr("music.my_wave");
        PhoneDraw.centeredText(g, wave, 6 + pw / 2f, y + 7, 0xFFFFFFFF, 0.85f, true);
        hit(6, y, pw, 22, YandexMusic::playWave);
        int gx = w - 6 - 22;
        PhoneDraw.button(g, gx, y, 22, 22, "", mx, listMouseY, PhoneDraw.ButtonStyle.NORMAL);
        PhoneDraw.tinted(g, SETTINGS_GLYPH, gx + 5, y + 5, 12, 12, 32, 32, PhoneTheme.text());
        hit(gx, y, 22, 22, () -> app.push(new YandexWaveSettingsView(app)));
        y += 28;
        if (landing == null) {
            PhoneDraw.text(g, tr("music.loading"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
            y += 14;
        } else {
            if (!landing.forYou().isEmpty()) {
                section(g, tr("music.y.for_you"), y + 2);
                y += 12;
                for (YandexApi.Playlist p : landing.forYou()) {
                    YandexRows.playlist(this, g, p, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
            if (!landing.chart().isEmpty()) {
                section(g, tr("music.y.chart"), y + 2);
                y += 12;
                List<Track> chart = landing.chart();
                y = TrackRows.list(this, g, chart.subList(0, Math.min(5, chart.size())), y, w, mx, listMouseY, tr("music.y.chart"));
                YandexRows.more(this, g, tr("music.y.whole_chart"), y, w, mx, listMouseY, () -> app.push(new YandexTracksView(app,
                        tr("music.y.chart"), dev.blockphone.fabric.client.music.yandex.YandexBrowse::chart)));
                y += 16;
            }
            if (!landing.newReleases().isEmpty()) {
                section(g, tr("music.y.new_releases"), y + 2);
                y += 12;
                for (var album : landing.newReleases()) {
                    YandexRows.album(this, g, album, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
            if (!landing.playlists().isEmpty()) {
                section(g, tr("music.y.popular_playlists"), y + 2);
                y += 12;
                for (YandexApi.Playlist p : landing.playlists()) {
                    YandexRows.playlist(this, g, p, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
        }
        endList(g);
        clampScroll(y + (int) scroll - top + 6, height() - top);
    }

    // --- Коллекция ---

    private void renderCollection(GuiGraphics g, int mx, int my, int top) {
        int w = width();
        Collection[] kinds = Collection.values();
        int bw = (w - 12 - 4 * (kinds.length - 1)) / kinds.length;
        for (int i = 0; i < kinds.length; i++) {
            Collection c = kinds[i];
            int bx = 6 + i * (bw + 4);
            PhoneDraw.button(g, bx, top, bw, 13, tr("music.y.collection." + c.name().toLowerCase()), mx, my,
                    c == collection ? PhoneDraw.ButtonStyle.PRIMARY : PhoneDraw.ButtonStyle.NORMAL);
            hit(bx, top, bw, 13, () -> {
                collection = c;
                scroll = 0;
                if (c == Collection.PLAYLISTS) {
                    YandexMusic.loadPlaylists();
                }
            });
        }
        top += 17;
        switch (collection) {
            case PLAYLISTS -> renderPlaylists(g, mx, my, top);
            case ALBUMS -> renderAlbums(g, mx, my, top, false);
            case TRACKS -> {
                List<Track> list = YandexMusic.likes();
                int listMouseY = my >= top ? my : -1000;
                beginList(g, top, height());
                int y = top - (int) scroll;
                if (list == null || (YandexMusic.loadingLikes() && list.isEmpty())) {
                    PhoneDraw.text(g, tr("music.loading"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
                } else if (list.isEmpty()) {
                    PhoneDraw.text(g, tr("music.no_likes"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
                } else {
                    TrackRows.list(this, g, list, y, w, mx, listMouseY, tr("music.liked"));
                }
                endList(g);
                clampScroll((list == null ? 0 : list.size()) * TrackRows.H + 8, height() - top);
            }
        }
    }

    /** Понравившиеся альбомы (spoken = false) или книги и подкасты (spoken = true). */
    private void renderAlbums(GuiGraphics g, int mx, int my, int top, boolean spoken) {
        int w = width();
        var all = dev.blockphone.fabric.client.music.yandex.YandexBrowse.likedAlbums();
        int listMouseY = my >= top ? my : -1000;
        beginList(g, top, height());
        int y = top - (int) scroll;
        int count = 0;
        if (all == null) {
            PhoneDraw.text(g, tr("music.loading"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
        } else {
            for (var album : all) {
                if (album.spoken() == spoken) {
                    YandexRows.album(this, g, album, y, w, mx, listMouseY);
                    y += YandexRows.H;
                    count++;
                }
            }
            if (count == 0) {
                PhoneDraw.text(g, tr("music.y.nothing_here"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
            }
        }
        endList(g);
        clampScroll(count * YandexRows.H + 8, height() - top);
    }

    // --- Книги и подкасты ---

    private void renderBooks(GuiGraphics g, int mx, int my, int top) {
        int w = width();
        int listMouseY = my >= top ? my : -1000;
        beginList(g, top, height());
        int y = top - (int) scroll;
        // «Продолжить слушать»: с аккаунта Яндекса (как в его приложении) и своё, чего там ещё нет.
        var catalogue = dev.blockphone.fabric.client.music.yandex.YandexBrowse.spoken();
        java.util.Set<String> shown = new java.util.HashSet<>();
        int continueRows = 0;
        if (catalogue != null) {
            for (var item : catalogue.continueItems()) {
                if (continueRows == 0) {
                    section(g, tr("music.y.continue_listening"), y + 2);
                    y += 12;
                }
                var album = item.album();
                long pos = Math.max(item.positionMs(), dev.blockphone.fabric.client.music.yandex.YandexBooks.livePosition(item.trackKey()));
                String chapter = item.trackTitle().startsWith(album.title()) && item.trackTitle().length() > album.title().length() + 1
                        ? item.trackTitle().substring(album.title().length()).replaceFirst("^[\\s.,:;—–-]+", "") : item.trackTitle();
                String sub = chapter + " · " + time(pos) + (item.durationMs() > 0 ? " / " + time(item.durationMs()) : "");
                YandexRows.row(this, g, album.coverUrl(), album.title(), sub, y, w, mx, listMouseY, false,
                        () -> app.push(new YandexAlbumView(app, album.id(), album.title())));
                y += YandexRows.H;
                shown.add(album.id());
                continueRows++;
            }
        }
        for (var e : dev.blockphone.fabric.client.music.yandex.YandexBooks.recent()) {
            if (continueRows >= 6 || shown.contains(e.albumId)) {
                continue;
            }
            if (continueRows == 0) {
                section(g, tr("music.y.continue_listening"), y + 2);
                y += 12;
            }
            String sub = tr("music.y.part", e.trackIndex + 1) + " · " + time(e.positionMs)
                    + (e.durationMs > 0 ? " / " + time(e.durationMs) : "");
            YandexRows.row(this, g, e.coverUrl, e.title, sub, y, w, mx, listMouseY, false,
                    () -> app.push(new YandexAlbumView(app, e.albumId, e.title)));
            y += YandexRows.H;
            continueRows++;
        }
        // Моя полка: понравившиеся книги и подкасты.
        var liked = dev.blockphone.fabric.client.music.yandex.YandexBrowse.likedAlbums();
        if (liked != null && liked.stream().anyMatch(a -> a.spoken())) {
            section(g, tr("music.y.bookshelf"), y + 2);
            y += 12;
            for (var album : liked) {
                if (album.spoken()) {
                    YandexRows.album(this, g, album, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
        }
        var shelves = catalogue == null ? null : catalogue.shelves();
        if (shelves == null) {
            PhoneDraw.text(g, tr("music.loading"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
            y += 14;
        } else {
            for (var shelf : shelves) {
                section(g, shelf.title(), y + 2);
                y += 12;
                for (var album : shelf.albums().subList(0, Math.min(6, shelf.albums().size()))) {
                    YandexRows.album(this, g, album, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
                for (var p : shelf.playlists().subList(0, Math.min(4, shelf.playlists().size()))) {
                    YandexRows.playlist(this, g, p, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
        }
        endList(g);
        clampScroll(y + (int) scroll - top + 6, height() - top);
    }

    // --- Поиск ---

    private void renderSearch(GuiGraphics g, int mx, int my, int top) {
        int w = width();
        var r = dev.blockphone.fabric.client.music.yandex.YandexBrowse.searchResults();
        int listMouseY = my >= top ? my : -1000;
        beginList(g, top, height());
        int y = top - (int) scroll;
        if (r == null) {
            PhoneDraw.text(g, tr(dev.blockphone.fabric.client.music.yandex.YandexBrowse.searching() ? "music.loading" : "music.nothing_found"),
                    8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
        } else {
            boolean any = false;
            if (!r.tracks().isEmpty()) {
                any = true;
                section(g, tr("music.y.tracks"), y + 2);
                y += 12;
                y = TrackRows.list(this, g, r.tracks().subList(0, Math.min(8, r.tracks().size())), y, w, mx, listMouseY,
                        tr("music.search_results"));
            }
            if (!r.artists().isEmpty()) {
                any = true;
                section(g, tr("music.y.artists"), y + 2);
                y += 12;
                for (var a : r.artists().subList(0, Math.min(3, r.artists().size()))) {
                    YandexRows.artist(this, g, a, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
            if (!r.albums().isEmpty()) {
                any = true;
                section(g, tr("music.y.albums"), y + 2);
                y += 12;
                for (var a : r.albums().subList(0, Math.min(4, r.albums().size()))) {
                    YandexRows.album(this, g, a, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
            if (!r.spoken().isEmpty()) {
                any = true;
                section(g, tr("music.y.books_podcasts"), y + 2);
                y += 12;
                for (var a : r.spoken().subList(0, Math.min(5, r.spoken().size()))) {
                    YandexRows.album(this, g, a, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
            if (!r.playlists().isEmpty()) {
                any = true;
                section(g, tr("music.y.playlists"), y + 2);
                y += 12;
                for (var p : r.playlists().subList(0, Math.min(3, r.playlists().size()))) {
                    YandexRows.playlist(this, g, p, y, w, mx, listMouseY);
                    y += YandexRows.H;
                }
            }
            if (!any) {
                PhoneDraw.text(g, tr("music.nothing_found"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
            }
        }
        endList(g);
        clampScroll(y + (int) scroll - top + 6, height() - top);
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
                YandexRows.playlist(this, g, playlist, y, w, mx, listMouseY);
                y += YandexRows.H;
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

    void debugShow(String what) {
        scroll = 0;
        switch (what) {
            case "home" -> section = Section.HOME;
            case "collection" -> {
                section = Section.COLLECTION;
                collection = Collection.ALBUMS;
            }
            case "books" -> section = Section.BOOKS;
            default -> {
                if (what.startsWith("search:")) {
                    search.setText(what.substring(7));
                    dev.blockphone.fabric.client.music.yandex.YandexBrowse.search(what.substring(7));
                }
            }
        }
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
