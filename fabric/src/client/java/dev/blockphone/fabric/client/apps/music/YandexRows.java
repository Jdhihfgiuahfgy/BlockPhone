package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.Covers;
import dev.blockphone.fabric.client.music.yandex.YandexApi;
import dev.blockphone.fabric.client.music.yandex.YandexCatalog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

/** Строки каталога Яндекса (та же высота, что у трека): альбом/книга/подкаст, артист, плейлист, «ещё». */
final class YandexRows {
    static final int H = TrackRows.H;
    private static final int COVER = 18;

    static void album(MusicView view, GuiGraphics g, YandexCatalog.Album album, int y, int w, int mx, int my) {
        String sub = album.artist();
        if (album.spoken()) {
            sub = MusicView.tr(album.book() ? "music.y.book" : "music.y.podcast") + (sub.isEmpty() ? "" : " · " + sub);
        } else if (album.year() > 0) {
            sub = (sub.isEmpty() ? "" : sub + " · ") + album.year();
        }
        row(view, g, album.coverUrl(), album.title(), sub, y, w, mx, my, false,
                () -> view.app.push(new YandexAlbumView(view.app, album.id(), album.title())));
    }

    static void artist(MusicView view, GuiGraphics g, YandexCatalog.Artist artist, int y, int w, int mx, int my) {
        row(view, g, artist.coverUrl(), artist.name(), MusicView.tr("music.y.artist"), y, w, mx, my, true,
                () -> view.app.push(new YandexArtistView(view.app, artist.id(), artist.name())));
    }

    static void playlist(MusicView view, GuiGraphics g, YandexApi.Playlist playlist, int y, int w, int mx, int my) {
        row(view, g, playlist.coverUrl(), playlist.title(), MusicView.tr("music.tracks_count", playlist.trackCount()), y, w, mx, my,
                false, () -> view.app.push(new YandexPlaylistView(view.app, playlist)));
    }

    /** Строка «Ещё →» под коротким списком. */
    static void more(MusicView view, GuiGraphics g, String label, int y, int w, int mx, int my, Runnable action) {
        boolean hover = PhoneDraw.inside(mx, my, 4, y, w - 8, 14);
        PhoneDraw.text(g, label + " ›", 10, y + 3, hover ? PhoneTheme.text() : PhoneTheme.accent(), 0.65f, false);
        view.hit(4, y, w - 8, 14, action);
    }

    static void row(MusicView view, GuiGraphics g, @Nullable String coverUrl, String title, String sub, int y, int w, int mx, int my,
                    boolean round, Runnable action) {
        boolean hover = PhoneDraw.inside(mx, my, 0, y, w, H);
        if (hover) {
            g.fill(0, y, w, y + H, PhoneTheme.cardHover());
        }
        PhotoTextures.Image cover = Covers.url(coverUrl);
        if (cover != null) {
            PhoneDraw.texture(g, cover.id(), 8, y + 3, COVER, COVER, cover.width(), cover.height());
            if (round) {
                // Круглое фото артиста: углы закрываются цветом фона строки.
                PhoneDraw.roundCornersMask(g, 8, y + 3, 8 + COVER, y + 3 + COVER, COVER / 2,
                        hover ? PhoneTheme.cardHover() : PhoneTheme.background());
            }
        } else {
            PhoneDraw.rawRoundedRect(g, 8, y + 3, 8 + COVER, y + 3 + COVER, round ? COVER / 2 : 3,
                    PhoneTheme.mix(PhoneTheme.accent(), PhoneTheme.card(), 0.7f));
            PhoneDraw.tinted(g, MusicView.NOTE, 8 + COVER / 2 - 5, y + 3 + COVER / 2 - 5, 10, 10, 32, 32, PhoneTheme.accent());
        }
        int tx = 8 + COVER + 6;
        PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - tx - 8, 0.8f), tx, y + 4, PhoneTheme.text(), 0.8f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(sub, w - tx - 8, 0.6f), tx, y + 14, PhoneTheme.secondaryText(), 0.6f, false);
        view.hit(0, y, w, H, action);
    }

    private YandexRows() {
    }
}
