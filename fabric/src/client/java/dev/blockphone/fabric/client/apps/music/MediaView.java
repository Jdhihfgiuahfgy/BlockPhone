package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.Covers;
import dev.blockphone.fabric.client.music.media.WindowsMedia;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Вкладка «На ПК» — пульт плеера компьютера через системные медиа Windows ({@link WindowsMedia}): что играет в Spotify,
 * Яндекс Музыке, браузере и т. п. (обложка, название, перемотка), пауза, вперёд/назад, перемешивание и повтор
 * (если приложение их поддерживает). Ничего настраивать не нужно; звук играет само приложение на компьютере.
 */
final class MediaView extends MusicView {
    private static final int COVER = 96;

    MediaView(MusicApp app) {
        super(app);
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        WindowsMedia.Playing p = WindowsMedia.playing();
        return p != null ? p.appName() : tr("music.tab.media");
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        WindowsMedia.watch();
        switch (WindowsMedia.status()) {
            case UNSUPPORTED -> hint(g, tr("music.media_unsupported"), height() / 2 - 20);
            case ERROR -> {
                String error = WindowsMedia.recentError();
                hint(g, tr("music.media_error") + (error != null ? "\n" + error : ""), height() / 2 - 24);
            }
            case STOPPED, STARTING -> hint(g, tr("music.loading"), height() / 2 - 10);
            case RUNNING -> renderRemote(g, mouseX, mouseY);
        }
    }

    private void renderRemote(GuiGraphics g, int mx, int my) {
        int w = width();
        WindowsMedia.Playing p = WindowsMedia.playing();
        if (p == null) {
            hint(g, tr("music.media_nothing"), height() / 2 - 24);
            return;
        }
        int accent = PhoneTheme.accent();
        int cx = (w - COVER) / 2;
        int y = 8;
        PhotoTextures.Image cover = p.cover() != null ? Covers.file(p.cover()) : null;
        if (cover != null) {
            PhoneDraw.textureCover(g, cover.id(), cx, y, COVER, COVER, cover.width(), cover.height());
        } else {
            PhoneDraw.rawRoundedRect(g, cx, y, cx + COVER, y + COVER, 4, PhoneTheme.card());
            PhoneDraw.tinted(g, NOTE, cx + COVER / 2 - 16, y + COVER / 2 - 16, 32, 32, 32, 32, accent);
        }
        y += COVER + 6;
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(p.title().isEmpty() ? tr("music.media_untitled") : p.title(), w - 16, 0.9f), w / 2f, y,
                PhoneTheme.text(), 0.9f, false);
        String artist = p.artist() + (p.album().isEmpty() || p.album().equals(p.artist()) ? "" : " — " + p.album());
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(artist, w - 16, 0.7f), w / 2f, y + 11, PhoneTheme.secondaryText(), 0.7f, false);
        y += 26;
        long position = p.position();
        if (p.durationMs() > 0) {
            bar(g, 12, y, w - 24, position / (float) p.durationMs(), p.canSeek());
            if (p.canSeek()) {
                slider(12, y, w - 24, 3, v -> WindowsMedia.seek((long) (v * p.durationMs())));
            }
            PhoneDraw.text(g, time(position), 12, y + 6, PhoneTheme.secondaryText(), 0.55f, false);
            PhoneDraw.rightText(g, time(p.durationMs()), w - 12, y + 6, PhoneTheme.secondaryText(), 0.55f);
        }
        y += 24;
        int mid = w / 2;
        if (p.canShuffle()) {
            iconButton(g, SHUFFLE, mid - 56, y, 11, mx, my, p.shuffle() ? accent : PhoneTheme.secondaryText(), WindowsMedia::toggleShuffle);
        }
        iconButton(g, PREV, mid - 28, y, 13, mx, my, p.canPrev() ? PhoneTheme.text() : PhoneTheme.secondaryText(), WindowsMedia::previous);
        PhoneDraw.roundedRect(g, mid - 12, y - 12, mid + 12, y + 12, 12, accent);
        PhoneDraw.tinted(g, p.playing() ? PAUSE : PLAY, mid - 7, y - 7, 14, 14, 32, 32, PhoneTheme.onAccent());
        hit(mid - 12, y - 12, 24, 24, WindowsMedia::togglePlay);
        iconButton(g, NEXT, mid + 28, y, 13, mx, my, p.canNext() ? PhoneTheme.text() : PhoneTheme.secondaryText(), WindowsMedia::next);
        if (p.canRepeat()) {
            iconButton(g, "Track".equals(p.repeat()) ? REPEAT_ONE : REPEAT, mid + 56, y, 11, mx, my,
                    "None".equals(p.repeat()) || p.repeat().isEmpty() ? PhoneTheme.secondaryText() : accent, WindowsMedia::cycleRepeat);
        }
        y += 24;
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(tr("music.media_source", p.appName()), w - 16, 0.6f), w / 2f, y, accent, 0.6f, false);
        y += 12;
        PhoneDraw.wrapped(g, tr("music.media_hint"), 10, y, w - 20, PhoneTheme.secondaryText(), 0.55f, true);
    }
}
