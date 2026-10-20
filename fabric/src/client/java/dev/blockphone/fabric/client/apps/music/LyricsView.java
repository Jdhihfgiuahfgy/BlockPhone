package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.lyrics.Lyrics;
import dev.blockphone.fabric.client.music.lyrics.LyricsService;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

/** Текст песни в телефоне: текущая строка подсвечена и держится по центру. Справа в заголовке — «в мире» вкл/выкл. */
final class LyricsView extends MusicView {
    private static final int ROW = 16;

    LyricsView(MusicApp app) {
        super(app);
    }

    @Override
    String title() {
        return tr("music.lyrics");
    }

    @Override
    String headerAction() {
        return tr(PhoneSettings.get().lyricsWorld ? "music.lyrics.world_on" : "music.lyrics.world_off");
    }

    @Override
    void onHeaderAction() {
        PhoneSettings settings = PhoneSettings.get();
        settings.lyricsWorld = !settings.lyricsWorld;
        settings.save();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        Lyrics lyrics = LyricsService.current();
        if (lyrics == null) {
            String key = switch (LyricsService.status()) {
                case LOADING -> "music.lyrics.loading";
                case NOT_FOUND -> "music.lyrics.not_found";
                default -> "music.nothing_playing";
            };
            hint(g, tr(key), height() / 2 - 10);
            return;
        }
        // Без времени строк «текущая строка» — догадка: не подсвечиваем и не прокручиваем сами.
        int current = lyrics.synced() ? lyrics.lineAt(LyricsService.positionMs()) : -1;
        int w = width();
        int total = lyrics.lines().size() * ROW + height();
        if (current >= 0) {
            // Плавно к текущей строке, по центру области.
            double target = current * ROW;
            scroll += (target - scroll) * 0.2;
        }
        clampScroll(total, height());
        beginList(g, 0, height());
        int y = height() / 2 - ROW / 2 - (int) scroll;
        for (int i = 0; i < lyrics.lines().size(); i++) {
            if (y + ROW >= 0 && y <= height()) {
                boolean now = i == current;
                float scale = now ? 0.85f : 0.7f;
                int color = now ? PhoneTheme.accent() : PhoneTheme.secondaryText();
                String text = PhoneDraw.ellipsize(lyrics.lines().get(i).text(), w - 16, scale);
                PhoneDraw.centeredText(g, text, w / 2f, y + (ROW - 9 * scale) / 2f, color, scale, false);
            }
            y += ROW;
        }
        endList(g);
        if (!lyrics.synced()) {
            PhoneDraw.centeredText(g, tr("music.lyrics.unsynced"), w / 2f, height() - 9, PhoneTheme.secondaryText(), 0.55f, false);
        }
    }
}
