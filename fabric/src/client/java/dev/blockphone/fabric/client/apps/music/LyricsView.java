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
    /** Высота второго ряда длинной строки. */
    private static final int ROW_EXTRA = 9;

    /** Строка в один или два ряда (делится по словам ближе к середине; что не влезло — с «…»). */
    static String[] twoRows(String text, float width, float scale) {
        if (PhoneDraw.width(text, scale) <= width) {
            return new String[]{text};
        }
        String[] words = text.split(" ");
        if (words.length < 2) {
            return new String[]{PhoneDraw.ellipsize(text, width, scale)};
        }
        int split = 1;
        float best = Float.MAX_VALUE;
        for (int i = 1; i < words.length; i++) {
            String left = String.join(" ", java.util.Arrays.copyOfRange(words, 0, i));
            String right = String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length));
            float diff = Math.abs(PhoneDraw.width(left, scale) - PhoneDraw.width(right, scale));
            if (diff < best) {
                best = diff;
                split = i;
            }
        }
        return new String[]{
                PhoneDraw.ellipsize(String.join(" ", java.util.Arrays.copyOfRange(words, 0, split)), width, scale),
                PhoneDraw.ellipsize(String.join(" ", java.util.Arrays.copyOfRange(words, split, words.length)), width, scale)};
    }

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
        int textW = w - 16;
        // Длинная строка — в два ряда; высота у строк разная, поэтому верх каждой считаем заранее.
        int n = lyrics.lines().size();
        String[][] rows = new String[n][];
        int[] tops = new int[n + 1];
        for (int i = 0; i < n; i++) {
            float scale = i == current ? 0.85f : 0.7f;
            rows[i] = twoRows(lyrics.lines().get(i).text(), textW, scale);
            tops[i + 1] = tops[i] + ROW + (rows[i].length - 1) * ROW_EXTRA;
        }
        int total = tops[n] + height();
        if (current >= 0) {
            // Плавно к текущей строке, по центру области.
            double target = tops[current] + (tops[current + 1] - tops[current]) / 2.0 - ROW / 2.0;
            scroll += (target - scroll) * 0.2;
        }
        clampScroll(total, height());
        beginList(g, 0, height());
        int base = height() / 2 - ROW / 2 - (int) scroll;
        boolean canSeek = lyrics.synced() && dev.blockphone.fabric.client.music.MusicPlayer.current() != null;
        for (int i = 0; i < n; i++) {
            int y = base + tops[i];
            int h = tops[i + 1] - tops[i];
            if (y + h < 0 || y > height()) {
                continue;
            }
            boolean now = i == current;
            boolean hover = canSeek && PhoneDraw.inside(mouseX, mouseY, 4, y, w - 8, h);
            if (hover) {
                PhoneDraw.roundedRect(g, 4, y + 1, w - 4, y + h - 1, 4, PhoneTheme.cardHover());
            }
            float scale = now ? 0.85f : 0.7f;
            int color = now ? PhoneTheme.accent() : hover ? PhoneTheme.text() : PhoneTheme.secondaryText();
            float ty = y + (ROW - 9 * scale) / 2f;
            for (String row : rows[i]) {
                PhoneDraw.centeredText(g, row, w / 2f, ty, color, scale, false);
                ty += ROW_EXTRA;
            }
            if (canSeek) {
                // Как в Яндекс Музыке: нажатие на строку — перемотка к ней.
                Lyrics.Line line = lyrics.lines().get(i);
                hit(4, y, w - 8, h, () -> LyricsService.seekTo(line));
            }
        }
        endList(g);
        if (!lyrics.synced()) {
            PhoneDraw.centeredText(g, tr("music.lyrics.unsynced"), w / 2f, height() - 9, PhoneTheme.secondaryText(), 0.55f, false);
        }
    }
}
