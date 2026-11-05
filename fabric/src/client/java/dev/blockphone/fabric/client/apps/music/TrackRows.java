package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.Covers;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Строка трека: обложка, название, исполнитель и длительность; текущий трек — цветом акцента с «эквалайзером». */
final class TrackRows {
    static final int H = 24;
    private static final int COVER = 18;

    static void draw(MusicView view, GuiGraphics g, Track track, int y, int w, int mx, int my, Runnable action) {
        draw(view, g, track, track.title(), y, w, mx, my, action);
    }

    /** Строка трека со своей подписью (главы книги — без повторения названия книги). */
    static void draw(MusicView view, GuiGraphics g, Track track, String title, int y, int w, int mx, int my, Runnable action) {
        if (PhoneDraw.inside(mx, my, 0, y, w, H)) {
            g.fill(0, y, w, y + H, PhoneTheme.cardHover());
        }
        cover(g, track, 8, y + 3, COVER);
        Track current = MusicPlayer.current();
        boolean playing = current != null && current.key().equals(track.key());
        int tx = 8 + COVER + 6;
        float right = w - 8;
        if (track.durationMs() > 0) {
            String d = MusicView.time(track.durationMs());
            PhoneDraw.rightText(g, d, right, y + 9, PhoneTheme.secondaryText(), 0.6f);
            right -= PhoneDraw.width(d, 0.6f) + 4;
        }
        if (playing) {
            equalizer(g, (int) right - 8, y + 8, MusicPlayer.state() == MusicPlayer.State.PLAYING);
            right -= 12;
        }
        PhoneDraw.text(g, PhoneDraw.ellipsize(title, right - tx, 0.8f), tx, y + 4, playing ? PhoneTheme.accent() : PhoneTheme.text(),
                0.8f, false);
        String sub = track.artist().isEmpty() ? track.album() : track.artist();
        PhoneDraw.text(g, PhoneDraw.ellipsize(sub, right - tx, 0.6f), tx, y + 14, PhoneTheme.secondaryText(), 0.6f, false);
        view.hit(0, y, w, H, action, track.source() == Track.Source.YANDEX ? () -> view.app.trackMenu(track) : null);
    }

    /** Список треков с прокруткой (область top..bottom); клик — играть весь список с этого трека. */
    static int list(MusicView view, GuiGraphics g, List<Track> tracks, int y, int w, int mx, int my, String queueName) {
        for (int i = 0; i < tracks.size(); i++) {
            int index = i;
            if (y + H >= -H && y <= view.height() + H) {
                draw(view, g, tracks.get(i), y, w, mx, my, () -> MusicPlayer.play(tracks, index, null, queueName));
            }
            y += H;
        }
        return y;
    }

    /** Обложка трека или нота на плитке. */
    static void cover(GuiGraphics g, Track track, int x, int y, int size) {
        PhotoTextures.Image cover = Covers.get(track);
        if (cover != null) {
            PhoneDraw.texture(g, cover.id(), x, y, size, size, cover.width(), cover.height());
            return;
        }
        PhoneDraw.rawRoundedRect(g, x, y, x + size, y + size, Math.min(3, size / 6), PhoneTheme.mix(PhoneTheme.accent(), PhoneTheme.card(), 0.7f));
        int s = Math.max(8, size / 2);
        PhoneDraw.tinted(g, MusicView.NOTE, x + (size - s) / 2, y + (size - s) / 2, s, s, 32, 32, PhoneTheme.accent());
    }

    /** Три прыгающих столбика. */
    static void equalizer(GuiGraphics g, int x, int y, boolean animate) {
        long t = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            int h = animate ? 2 + (int) (3.5 + 3.5 * Math.sin(t / 140.0 + i * 1.7)) : 3;
            g.fill(x + i * 3, y + 8 - h, x + i * 3 + 2, y + 8, PhoneTheme.accent());
        }
    }

    private TrackRows() {
    }
}
