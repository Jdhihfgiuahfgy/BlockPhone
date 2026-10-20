package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.yandex.YandexMusic;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

/** Полный плеер: обложка, название, перемотка, кнопки, громкость, лайк (Яндекс), очередь. */
final class PlayerView extends MusicView {
    private static final int COVER = 112;

    PlayerView(MusicApp app) {
        super(app);
    }

    @Override
    String title() {
        String name = MusicPlayer.queueName();
        return name.isEmpty() ? tr("music.now_playing") : name;
    }

    @Override
    String headerAction() {
        return tr("music.queue");
    }

    @Override
    void onHeaderAction() {
        app.push(new QueueView(app));
    }

    @Override
    void onHidden() {
        PhoneSettings.get().save();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        Track track = MusicPlayer.current();
        if (track == null) {
            hint(g, tr("music.nothing_playing"), height() / 2 - 10);
            return;
        }
        int cx = (w - COVER) / 2;
        int y = 10;
        TrackRows.cover(g, track, cx, y, COVER);
        y += COVER + 10;

        boolean yandex = track.source() == Track.Source.YANDEX && YandexMusic.account() != null;
        int textW = w - (yandex ? 40 : 16);
        PhoneDraw.text(g, PhoneDraw.ellipsize(track.title(), textW, 0.95f), 10, y, PhoneTheme.text(), 0.95f, false);
        String sub = MusicPlayer.state() == MusicPlayer.State.LOADING ? tr("music.loading") : track.artist();
        PhoneDraw.text(g, PhoneDraw.ellipsize(sub, textW, 0.7f), 10, y + 11, PhoneTheme.secondaryText(), 0.7f, false);
        if (yandex) {
            boolean liked = YandexMusic.liked(track);
            iconButton(g, liked ? LIKED : LIKE, w - 18, y + 8, 12, mouseX, mouseY, liked ? PhoneTheme.danger() : PhoneTheme.secondaryText(),
                    () -> YandexMusic.toggleLike(track));
        }
        y += 28;

        long duration = MusicPlayer.durationMs();
        long position = MusicPlayer.positionMs();
        bar(g, 10, y, w - 20, duration > 0 ? position / (float) duration : 0, true);
        slider(10, y, w - 20, 3, v -> MusicPlayer.seek((long) (v * duration)));
        PhoneDraw.text(g, time(position), 10, y + 6, PhoneTheme.secondaryText(), 0.55f, false);
        PhoneDraw.rightText(g, duration > 0 ? time(duration) : "--:--", w - 10, y + 6, PhoneTheme.secondaryText(), 0.55f);
        y += 30;

        int mid = w / 2;
        iconButton(g, SHUFFLE, mid - 56, y, 11, mouseX, mouseY, MusicPlayer.shuffle() ? PhoneTheme.accent() : PhoneTheme.secondaryText(),
                () -> MusicPlayer.setShuffle(!MusicPlayer.shuffle()));
        iconButton(g, PREV, mid - 28, y, 13, mouseX, mouseY, PhoneTheme.text(), MusicPlayer::previous);
        boolean playing = MusicPlayer.state() == MusicPlayer.State.PLAYING || MusicPlayer.state() == MusicPlayer.State.LOADING;
        boolean hover = PhoneDraw.inside(mouseX, mouseY, mid - 13, y - 13, 26, 26);
        PhoneDraw.roundedRect(g, mid - 13, y - 13, mid + 13, y + 13, 13, hover ? PhoneTheme.accentHover() : PhoneTheme.accent());
        PhoneDraw.tinted(g, playing ? PAUSE : PLAY, mid - 7, y - 7, 14, 14, 32, 32, PhoneTheme.onAccent());
        hit(mid - 13, y - 13, 26, 26, MusicPlayer::toggle);
        iconButton(g, NEXT, mid + 28, y, 13, mouseX, mouseY, PhoneTheme.text(), MusicPlayer::next);
        MusicPlayer.Repeat repeat = MusicPlayer.repeat();
        iconButton(g, repeat == MusicPlayer.Repeat.ONE ? REPEAT_ONE : REPEAT, mid + 56, y, 11, mouseX, mouseY,
                repeat == MusicPlayer.Repeat.OFF ? PhoneTheme.secondaryText() : PhoneTheme.accent(), MusicPlayer::cycleRepeat);
        y += 26;

        PhoneSettings settings = PhoneSettings.get();
        PhoneDraw.tinted(g, VOLUME, 10, y - 4, 9, 9, 32, 32, PhoneTheme.secondaryText());
        bar(g, 24, y, w - 34, settings.musicVolume, true);
        slider(24, y, w - 34, 3, v -> settings.musicVolume = v);
    }
}
