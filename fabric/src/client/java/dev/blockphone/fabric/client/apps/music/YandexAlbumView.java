package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.Covers;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.yandex.YandexBooks;
import dev.blockphone.fabric.client.music.yandex.YandexBrowse;
import dev.blockphone.fabric.client.music.yandex.YandexCatalog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Альбом, аудиокнига или подкаст Яндекса: обложка, название, «Играть»/«Продолжить» (книги и подкасты помнят главу и
 * место — {@link YandexBooks}), «в коллекцию» (лайк альбома), список треков/глав/выпусков.
 */
final class YandexAlbumView extends MusicView {
    private final String albumId;
    private final String fallbackTitle;

    YandexAlbumView(MusicApp app, String albumId, String title) {
        super(app);
        this.albumId = albumId;
        this.fallbackTitle = title;
    }

    /** Глава книги без повторения названия книги («Джон Грэй. «…». Часть 1» → «Часть 1»). */
    private static String chapterTitle(YandexCatalog.Album album, Track track) {
        String t = track.title();
        if (album.spoken() && t.startsWith(album.title()) && t.length() > album.title().length() + 1) {
            String rest = t.substring(album.title().length()).replaceFirst("^[\\s.,:;—–-]+", "");
            if (!rest.isEmpty()) {
                return rest;
            }
        }
        return t;
    }

    /** «12 ч 5 мин» / «45 мин». */
    private static String hours(long sec) {
        long h = sec / 3600;
        long m = sec % 3600 / 60;
        return h > 0 ? tr("music.y.hours", h, m) : tr("music.y.minutes", Math.max(1, m));
    }

    @Override
    String title() {
        YandexCatalog.AlbumPage page = YandexBrowse.album(albumId);
        return page != null ? page.album().title() : fallbackTitle;
    }

    @Override
    void render(GuiGraphics g, int mx, int my) {
        YandexCatalog.AlbumPage page = YandexBrowse.album(albumId);
        if (page == null) {
            hint(g, tr("music.loading"), height() / 2 - 10);
            return;
        }
        YandexCatalog.Album album = page.album();
        List<Track> tracks = page.tracks();
        int w = width();
        int header = 70;
        clampScroll(header + tracks.size() * TrackRows.H + 8, height());
        beginList(g, 0, height());
        int y = 4 - (int) scroll;
        int cs = 52;
        PhotoTextures.Image cover = Covers.url(album.coverUrl());
        if (cover != null) {
            PhoneDraw.texture(g, cover.id(), 8, y, cs, cs, cover.width(), cover.height());
        } else {
            PhoneDraw.roundedRect(g, 8, y, 8 + cs, y + cs, 4, PhoneTheme.card());
            PhoneDraw.tinted(g, NOTE, 8 + cs / 2 - 8, y + cs / 2 - 8, 16, 16, 32, 32, PhoneTheme.accent());
        }
        int tx = 8 + cs + 6;
        String kind = album.spoken() ? tr(album.book() ? "music.y.book" : "music.y.podcast")
                : (album.year() > 0 ? String.valueOf(album.year()) : tr("music.y.album"));
        PhoneDraw.text(g, PhoneDraw.ellipsize(kind, w - tx - 8, 0.55f), tx, y, PhoneTheme.secondaryText(), 0.55f, false);
        String title = album.title();
        // Не больше трёх строк названия, под ним — автор (чтец).
        while (PhoneDraw.wrappedHeight(title, w - tx - 8, 0.75f) > 23 && title.length() > 4) {
            title = title.substring(0, title.length() - 2).strip() + "…";
            title = title.replace("……", "…");
        }
        int lines = PhoneDraw.wrapped(g, title, tx, y + 8, w - tx - 8, PhoneTheme.text(), 0.75f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(album.artist(), w - tx - 8, 0.6f), tx, y + 9 + lines,
                PhoneTheme.secondaryText(), 0.6f, false);
        // Кнопки: играть/продолжить и «в коллекцию».
        int by = y + cs + 4;
        if (album.spoken()) {
            YandexBrowse.spoken(); // «Продолжить слушать» аккаунта
        }
        YandexBooks.Resume resume = album.spoken() ? YandexBooks.resume(page) : null;
        int half = (w - 20) / 2;
        String play = resume != null ? tr("music.y.continue", resume.index() + 1, time(resume.positionMs())) : tr("music.play_all");
        button(g, 8, by, w - 16 - half - 4, 14, play, mx, my, PhoneDraw.ButtonStyle.PRIMARY, () -> {
            if (album.spoken()) {
                YandexBooks.play(album, tracks, resume != null ? resume.index() : 0, resume != null ? resume.positionMs() : 0);
            } else {
                MusicPlayer.play(tracks, 0, null, album.title());
            }
        });
        if (album.spoken() && page.leftSec() >= 0) {
            String left = page.finished() ? tr("music.y.finished") : tr("music.y.left", hours(page.leftSec()));
            PhoneDraw.text(g, left, tx, by - 9, PhoneTheme.secondaryText(), 0.5f, false);
        }
        boolean liked = YandexBrowse.albumLiked(album.id());
        button(g, w - 8 - half, by, half, 14, tr(liked ? "music.y.in_collection" : "music.y.add_collection"), mx, my,
                liked ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL, () -> YandexBrowse.toggleAlbumLike(album));
        y += header;
        for (int i = 0; i < tracks.size(); i++) {
            int index = i;
            if (y + TrackRows.H >= 0 && y <= height()) {
                Track track = tracks.get(i);
                long pos = album.spoken() ? Math.max(YandexBooks.livePosition(track.key()), page.progress().getOrDefault(track.key(), 0L)) : 0;
                TrackRows.draw(this, g, track, chapterTitle(album, track), y, w, mx, my, () -> {
                    if (album.spoken()) {
                        // Начатая, но не дослушанная глава — с её места.
                        boolean done = track.durationMs() > 0 && pos >= track.durationMs() - 10_000;
                        YandexBooks.play(album, tracks, index, done ? 0 : pos);
                    } else {
                        MusicPlayer.play(tracks, index, null, album.title());
                    }
                });
                if (pos > 0 && track.durationMs() > 0) {
                    // Прогресс главы (с аккаунта) — полоска внизу строки.
                    int bw = w - 34;
                    int filled = (int) Math.min(bw, bw * pos / track.durationMs());
                    g.fill(32, y + TrackRows.H - 3, 32 + bw, y + TrackRows.H - 2, PhoneTheme.separator());
                    g.fill(32, y + TrackRows.H - 3, 32 + filled, y + TrackRows.H - 2, PhoneTheme.accent());
                }
            }
            y += TrackRows.H;
        }
        endList(g);
    }
}
