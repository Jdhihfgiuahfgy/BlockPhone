package dev.blockphone.fabric.client.music.lyrics;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Текст песни, разбитый на строки и слова со временем появления. Понимает LRC (время строк), расширенный LRC
 * ({@code <мм:сс.хх>} перед словами) и обычный текст (строки распределяются по длительности трека — «примерно»).
 * Если у слов нет своего времени, они распределяются внутри строки пропорционально длине.
 */
public record Lyrics(List<Line> lines, boolean synced) {
    public record Word(long startMs, String text) {
    }

    public record Line(long startMs, long endMs, String text, List<Word> words) {
    }

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TIME = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);
    /** Самая долгая строка: дальше — пауза (проигрыш), строка гаснет. */
    private static final long MAX_LINE_MS = 8000;

    /** Номер строки, которая звучит в момент {@code ms}, или −1 (до первой строки / в паузе). */
    public int lineAt(long ms) {
        int lo = 0;
        int hi = lines.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startMs() <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found >= 0 && ms < lines.get(found).endMs() ? found : -1;
    }

    /** Разобрать текст; {@code null}, если в нём нет ни одной строки. */
    public static @Nullable Lyrics parse(String text, long durationMs) {
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        return TIME.matcher(text).find() ? parseLrc(text, durationMs) : parsePlain(text, durationMs);
    }

    private static @Nullable Lyrics parseLrc(String text, long durationMs) {
        long offset = 0;
        Matcher off = OFFSET.matcher(text);
        if (off.find()) {
            offset = Long.parseLong(off.group(1));
        }
        record Raw(long start, String body) {
        }
        List<Raw> raw = new ArrayList<>();
        for (String row : text.split("\n")) {
            Matcher m = TIME.matcher(row);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                times.add(millis(m.group(1), m.group(2), m.group(3)));
                end = m.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String body = row.substring(end).strip();
            for (long t : times) {
                // [offset:+500] — текст на 500 мс раньше.
                raw.add(new Raw(Math.max(0, t - offset), body));
            }
        }
        raw.sort((a, b) -> Long.compare(a.start, b.start));
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Raw r = raw.get(i);
            String plain = WORD_TIME.matcher(r.body).replaceAll("").replaceAll("\\s+", " ").strip();
            if (plain.isEmpty()) {
                continue; // пустая строка — пауза: предыдущая строка заканчивается здесь
            }
            long next = i + 1 < raw.size() ? raw.get(i + 1).start : (durationMs > 0 ? durationMs : r.start + MAX_LINE_MS);
            long end = Math.min(next, r.start + MAX_LINE_MS);
            if (end <= r.start) {
                end = r.start + 1000;
            }
            lines.add(new Line(r.start, end, plain, words(r.body, plain, r.start, end, offset)));
        }
        return lines.isEmpty() ? null : new Lyrics(List.copyOf(lines), true);
    }

    /** Обычный текст: строки равномерно по треку (без первых и последних 8 %). */
    private static @Nullable Lyrics parsePlain(String text, long durationMs) {
        List<String> rows = new ArrayList<>();
        for (String row : text.split("\n")) {
            String plain = row.replaceAll("\\s+", " ").strip();
            if (!plain.isEmpty()) {
                rows.add(plain);
            }
        }
        if (rows.isEmpty()) {
            return null;
        }
        long total = durationMs > 0 ? durationMs : rows.size() * 4000L;
        long from = total * 8 / 100;
        long step = Math.max(1000, (total - 2 * from) / rows.size());
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            long start = from + i * step;
            long end = Math.min(start + step, start + MAX_LINE_MS);
            lines.add(new Line(start, end, rows.get(i), words(rows.get(i), rows.get(i), start, end, 0)));
        }
        return new Lyrics(List.copyOf(lines), false);
    }

    /**
     * Слова строки со временем: из меток {@code <мм:сс.хх>}, иначе — пропорционально длине слов,
     * чтобы последнее слово появилось к 75 % времени строки.
     */
    static List<Word> words(String body, String plain, long start, long end, long offset) {
        List<Word> result = new ArrayList<>();
        Matcher m = WORD_TIME.matcher(body);
        if (m.find()) {
            long time = start;
            int pos = 0;
            m.reset();
            while (true) {
                boolean found = m.find();
                String chunk = body.substring(pos, found ? m.start() : body.length());
                for (String w : chunk.strip().split("\\s+")) {
                    if (!w.isEmpty()) {
                        result.add(new Word(time, w));
                    }
                }
                if (!found) {
                    break;
                }
                time = Math.max(start, millis(m.group(1), m.group(2), m.group(3)) - offset);
                pos = m.end();
            }
            return result;
        }
        String[] parts = plain.split(" ");
        int letters = 0;
        for (String p : parts) {
            letters += p.length() + 1;
        }
        long span = (end - start) * 3 / 4;
        int before = 0;
        for (String p : parts) {
            result.add(new Word(start + span * before / Math.max(1, letters), p));
            before += p.length() + 1;
        }
        return result;
    }

    private static long millis(String min, String sec, @Nullable String frac) {
        long ms = Long.parseLong(min) * 60_000 + Long.parseLong(sec) * 1000;
        if (frac != null) {
            ms += switch (frac.length()) {
                case 1 -> Long.parseLong(frac) * 100;
                case 2 -> Long.parseLong(frac) * 10;
                default -> Long.parseLong(frac);
            };
        }
        return ms;
    }
}
