package dev.blockphone.fabric.client.music.lyrics;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Текст песни на экране (не в мире) — режимы {@link #MODES}: «left»/«right» — столбик строк сбоку (прошлые, текущая,
 * следующие; плавная прокрутка), «actionbar» — над хотбаром, «subtitles» — снизу, как субтитры, с подложкой и следующей
 * строкой, «top» — крупно сверху. Слова текущей строки загораются по времени (караоке); размер — lyricsSize.
 * Режимы «world» и «view» рисует {@link LyricsWorld}.
 */
public final class LyricsHud {
    /** Все места показа: первые два — в мире (LyricsWorld), остальные — на экране (здесь). */
    public static final List<String> MODES = List.of("world", "view", "left", "right", "actionbar", "subtitles", "top");
    private static final float[] SIZES = {0.75f, 1f, 1.3f, 1.6f};
    private static final int LINE_GAP = 3;

    private static @Nullable Lyrics shownFor;
    private static float shownIndex = -1;
    private static long lastFrame;

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "lyrics_hud"), LyricsHud::render);
    }

    /** Режим на экране (не в мире). */
    public static boolean screenMode(String mode) {
        return MODES.indexOf(mode) >= 2;
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        PhoneSettings s = PhoneSettings.get();
        Lyrics lyrics = LyricsService.current();
        if (!s.lyricsWorld || !screenMode(s.lyricsMode) || lyrics == null || (!lyrics.synced() && !s.lyricsUnsynced)
                || mc.options.hideGui || CameraMode.active() || MusicPlayer.state() == MusicPlayer.State.STOPPED) {
            shownFor = null;
            return;
        }
        draw(g, mc.font, lyrics, LyricsService.positionMs(), s, g.guiWidth(), g.guiHeight());
    }

    /** Нарисовать по режиму из настроек в области w×h (её же использует предпросмотр в настройках). */
    public static void draw(GuiGraphics g, Font font, Lyrics lyrics, long pos, PhoneSettings s, int w, int h) {
        int line = lyrics.lineAt(pos);
        // Между строками (проигрыш) — держим последнюю спетую, чтобы текст не мигал.
        int anchor = line >= 0 ? line : lastBefore(lyrics, pos);
        float scale = SIZES[Math.max(1, Math.min(4, s.lyricsSize)) - 1];
        switch (s.lyricsMode) {
            case "left", "right" -> side(g, font, lyrics, anchor, line, pos, s, scale, w, h, s.lyricsMode.equals("right"));
            case "actionbar" -> {
                if (line >= 0) {
                    centered(g, font, lyrics.lines().get(line), pos, s, scale, w / 2f, h - 68 - 9 * scale, w - 20, false);
                }
            }
            case "subtitles" -> subtitles(g, font, lyrics, line, pos, s, scale, w, h);
            case "top" -> {
                if (line >= 0) {
                    centered(g, font, lyrics.lines().get(line), pos, s, scale * 1.4f, w / 2f, 8 + topTaken(s), w - 20, false);
                }
            }
            default -> {
            }
        }
    }

    /** Сколько места сверху занимает виджет музыки (если он прикреплён к верхнему краю) — текст встаёт под него. */
    private static float topTaken(PhoneSettings s) {
        if (!s.musicHud || !s.hudAnchor.startsWith("top")) {
            return 0;
        }
        dev.blockphone.fabric.client.music.MusicHud.Info info = dev.blockphone.fabric.client.music.MusicHud.current();
        if (info == null) {
            return 0;
        }
        float k = Math.max(0.5f, Math.min(2f, s.hudScale / 100f));
        return dev.blockphone.fabric.client.music.MusicHud.measure(info, s).h() * k + s.hudOffsetY + 4;
    }

    private static int lastBefore(Lyrics lyrics, long pos) {
        int found = -1;
        for (int i = 0; i < lyrics.lines().size() && lyrics.lines().get(i).startMs() <= pos; i++) {
            found = i;
        }
        return found;
    }

    /** Столбик сбоку: 2 прошлые строки, текущая (караоке), 3 следующие; прокрутка плавная. */
    private static void side(GuiGraphics g, Font font, Lyrics lyrics, int anchor, int line, long pos, PhoneSettings s,
                             float scale, int w, int h, boolean right) {
        long now = System.currentTimeMillis();
        if (shownFor != lyrics || shownIndex < 0) {
            shownFor = lyrics;
            shownIndex = Math.max(0, anchor);
        }
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        shownIndex += (Math.max(0, anchor) - shownIndex) * Math.min(1, dt * 8);
        int panelW = (int) Math.min(w * 0.36f, 220);
        int x = right ? w - panelW - 6 : 6;
        float rowH = (9 + LINE_GAP) * scale;
        float centerY = h * 0.42f;
        int from = Math.max(0, (int) Math.floor(shownIndex) - 3);
        int to = Math.min(lyrics.lines().size() - 1, (int) Math.ceil(shownIndex) + 4);
        // Строки разной высоты (перенос) — считаем от текущей вверх и вниз.
        float[] tops = new float[to - from + 2];
        List<List<String>> wrapped = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            wrapped.add(wrap(font, lyrics.lines().get(i).text(), panelW / scale));
            tops[i - from + 1] = tops[i - from] + wrapped.get(i - from).size() * rowH + LINE_GAP * scale;
        }
        int base = (int) Math.floor(shownIndex);
        float frac = shownIndex - base;
        int bi = Math.max(0, Math.min(to - from, base - from));
        float baseTop = tops[bi] + (tops[Math.min(tops.length - 1, bi + 1)] - tops[bi]) * frac;
        for (int i = from; i <= to; i++) {
            float y = centerY + tops[i - from] - baseTop;
            float distance = Math.abs(i - shownIndex);
            float alpha = Math.max(0, 1 - distance * 0.28f);
            if (alpha <= 0.05f || y < 0 || y > h - 30) {
                continue;
            }
            boolean current = i == line;
            List<String> rows = wrapped.get(i - from);
            for (String row : rows) {
                float rx = right ? x + panelW - font.width(row) * scale : x;
                if (current) {
                    karaokeRow(g, font, lyrics.lines().get(i), row, pos, s, rx, y, scale);
                } else {
                    text(g, font, row, rx, y, scale, withAlpha(0xFFFFFFFF, alpha * (i < line ? 0.55f : 0.75f)), s.lyricsOutline);
                }
                y += rowH;
            }
        }
    }

    /** Субтитры: текущая строка (до двух рядов) и мельче следующая, на полупрозрачной подложке. */
    private static void subtitles(GuiGraphics g, Font font, Lyrics lyrics, int line, long pos, PhoneSettings s, float scale, int w, int h) {
        if (line < 0) {
            return;
        }
        Lyrics.Line current = lyrics.lines().get(line);
        List<String> rows = wrap(font, current.text(), (w * 0.7f) / scale);
        String next = line + 1 < lyrics.lines().size() ? lyrics.lines().get(line + 1).text() : null;
        float rowH = (9 + LINE_GAP) * scale;
        float nextScale = scale * 0.75f;
        float height = rows.size() * rowH + (next != null ? 9 * nextScale + 4 : 0) + 6;
        float top = h - 64 - height;
        float maxW = 0;
        for (String r : rows) {
            maxW = Math.max(maxW, font.width(r) * scale);
        }
        if (next != null) {
            maxW = Math.max(maxW, Math.min(w * 0.7f, font.width(next) * nextScale));
        }
        g.fill((int) (w / 2f - maxW / 2 - 6), (int) top, (int) (w / 2f + maxW / 2 + 6), (int) (top + height), 0x90000000);
        float y = top + 4;
        for (String row : rows) {
            karaokeRow(g, font, current, row, pos, s, w / 2f - font.width(row) * scale / 2, y, scale);
            y += rowH;
        }
        if (next != null) {
            String n = font.plainSubstrByWidth(next, (int) (w * 0.7f / nextScale));
            text(g, font, n, w / 2f - font.width(n) * nextScale / 2, y + 1, nextScale, 0x99FFFFFF, false);
        }
    }

    /** Одна строка по центру (до двух рядов), слова загораются по времени. */
    private static void centered(GuiGraphics g, Font font, Lyrics.Line line, long pos, PhoneSettings s, float scale, float cx, float y,
                                 float maxW, boolean unused) {
        List<String> rows = wrap(font, line.text(), maxW / scale);
        if (rows.size() > 2) {
            rows = rows.subList(0, 2);
        }
        float rowH = (9 + LINE_GAP) * scale;
        float top = y - (rows.size() - 1) * rowH;
        for (String row : rows) {
            karaokeRow(g, font, line, row, pos, s, cx - font.width(row) * scale / 2, top, scale);
            top += rowH;
        }
    }

    /**
     * Ряд текущей строки: спетые слова — ярко (последнее — акцентом, если включено), ещё не спетые — приглушённо.
     * row — часть строки после переноса; слова сопоставляются по порядку.
     */
    private static void karaokeRow(GuiGraphics g, Font font, Lyrics.Line line, String row, long pos, PhoneSettings s,
                                   float x, float y, float scale) {
        String[] rowWords = row.split(" ");
        List<Lyrics.Word> words = line.words();
        // Где этот ряд начинается среди слов строки.
        int startWord = 0;
        int lineIdx = line.text().indexOf(row);
        if (lineIdx > 0) {
            startWord = line.text().substring(0, lineIdx).trim().isEmpty() ? 0 : line.text().substring(0, lineIdx).trim().split(" ").length;
        }
        int lastSung = -1;
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).startMs() <= pos) {
                lastSung = i;
            }
        }
        float cx = x;
        for (int i = 0; i < rowWords.length; i++) {
            int wi = startWord + i;
            boolean sung = wi <= lastSung || words.isEmpty();
            int color = !sung ? 0x88FFFFFF : (s.lyricsAccentWord && wi == lastSung ? PhoneTheme.accent() : 0xFFFFFFFF);
            String word = rowWords[i] + (i < rowWords.length - 1 ? " " : "");
            text(g, font, word, cx, y, scale, color, s.lyricsOutline);
            cx += font.width(word) * scale;
        }
    }

    private static List<String> wrap(Font font, String text, float maxWidth) {
        List<String> rows = new ArrayList<>();
        for (FormattedText part : font.getSplitter().splitLines(text, Math.max(20, (int) maxWidth), net.minecraft.network.chat.Style.EMPTY)) {
            rows.add(part.getString().strip());
        }
        return rows.isEmpty() ? List.of(text) : rows;
    }

    private static void text(GuiGraphics g, Font font, String s, float x, float y, float scale, int color, boolean outline) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        if (outline) {
            int shade = (color >>> 24) * 3 / 4 << 24;
            g.drawString(font, s, -1, 0, shade, false);
            g.drawString(font, s, 1, 0, shade, false);
            g.drawString(font, s, 0, -1, shade, false);
            g.drawString(font, s, 0, 1, shade, false);
            g.drawString(font, s, 0, 0, color, false);
        } else {
            g.drawString(font, s, 0, 0, color, true);
        }
        g.pose().popMatrix();
    }

    private static int withAlpha(int color, float alpha) {
        return Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | (color & 0xFFFFFF);
    }

    private LyricsHud() {
    }
}
