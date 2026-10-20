package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.yandex.YandexApi;
import dev.blockphone.fabric.client.music.yandex.YandexMusic;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Треки плейлиста Яндекс Музыки: нажатие на трек — играть плейлист с него; в заголовке — «Играть всё». */
final class YandexPlaylistView extends MusicView {
    private final YandexApi.Playlist playlist;

    YandexPlaylistView(MusicApp app, YandexApi.Playlist playlist) {
        super(app);
        this.playlist = playlist;
    }

    @Override
    String title() {
        return playlist.title();
    }

    @Override
    String headerAction() {
        List<Track> tracks = YandexMusic.playlistTracks(playlist);
        return tracks != null && !tracks.isEmpty() ? tr("music.play_all") : null;
    }

    @Override
    void onHeaderAction() {
        List<Track> tracks = YandexMusic.playlistTracks(playlist);
        if (tracks != null && !tracks.isEmpty()) {
            MusicPlayer.play(tracks, 0, null, playlist.title());
        }
    }

    @Override
    void onShown() {
        YandexMusic.loadPlaylist(playlist);
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        List<Track> tracks = YandexMusic.playlistTracks(playlist);
        if (tracks == null) {
            hint(g, tr("music.loading"), height() / 2 - 10);
            return;
        }
        if (tracks.isEmpty()) {
            hint(g, tr("music.playlist_empty"), height() / 2 - 10);
            return;
        }
        clampScroll(tracks.size() * TrackRows.H + 16, height());
        beginList(g, 0, height());
        int y = 2 - (int) scroll;
        PhoneDraw.text(g, tr("music.tracks_count", tracks.size()), 8, y + 2, PhoneTheme.secondaryText(), 0.6f, false);
        y += 12;
        TrackRows.list(this, g, tracks, y, w, mouseX, mouseY, playlist.title());
        endList(g);
    }
}
