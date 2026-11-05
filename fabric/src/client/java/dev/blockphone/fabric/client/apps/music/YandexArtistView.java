package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.yandex.YandexBrowse;
import dev.blockphone.fabric.client.music.yandex.YandexCatalog;
import dev.blockphone.fabric.client.music.yandex.YandexMusic;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Артист Яндекса: «Волна по артисту», популярные треки, альбомы, похожие артисты. */
final class YandexArtistView extends MusicView {
    private final String artistId;
    private final String name;

    YandexArtistView(MusicApp app, String artistId, String name) {
        super(app);
        this.artistId = artistId;
        this.name = name;
    }

    @Override
    String title() {
        YandexCatalog.ArtistPage page = YandexBrowse.artist(artistId);
        return page != null ? page.artist().name() : name;
    }

    @Override
    void render(GuiGraphics g, int mx, int my) {
        YandexCatalog.ArtistPage page = YandexBrowse.artist(artistId);
        if (page == null) {
            hint(g, tr("music.loading"), height() / 2 - 10);
            return;
        }
        int w = width();
        int total = 26 + 12 + page.popular().size() * TrackRows.H + 12 + page.albums().size() * YandexRows.H
                + 12 + page.similar().size() * YandexRows.H + 10;
        clampScroll(total, height());
        beginList(g, 0, height());
        int y = 4 - (int) scroll;
        int half = (w - 20) / 2;
        button(g, 8, y, half, 16, "▶ " + tr("music.y.wave_artist"), mx, my, PhoneDraw.ButtonStyle.PRIMARY,
                () -> YandexMusic.playStation(List.of("artist:" + artistId), tr("music.y.wave_of", name)));
        List<Track> popular = page.popular();
        button(g, 12 + half, y, half, 16, tr("music.play_all"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                () -> MusicPlayer.play(popular, 0, null, name));
        y += 22;
        if (!popular.isEmpty()) {
            section(g, tr("music.y.popular"), y + 2);
            y += 12;
            y = TrackRows.list(this, g, popular, y, w, mx, my, name);
        }
        if (!page.albums().isEmpty()) {
            section(g, tr("music.y.albums"), y + 2);
            y += 12;
            for (YandexCatalog.Album album : page.albums()) {
                YandexRows.album(this, g, album, y, w, mx, my);
                y += YandexRows.H;
            }
        }
        if (!page.similar().isEmpty()) {
            section(g, tr("music.y.similar"), y + 2);
            y += 12;
            for (YandexCatalog.Artist artist : page.similar()) {
                YandexRows.artist(this, g, artist, y, w, mx, my);
                y += YandexRows.H;
            }
        }
        endList(g);
    }
}
