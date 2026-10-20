package dev.blockphone.fabric.client.music.lyrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор текста песни (строки — придуманные для теста). */
class LyricsTest {

    @Test
    void lrcLinesAndProportionalWords() {
        Lyrics l = Lyrics.parse("[ar:Test]\n[00:01.00]red stone glows\n[00:05.50]creeper says hi\n\n[00:20.00]end", 30_000);
        assertNotNull(l);
        assertTrue(l.synced());
        assertEquals(3, l.lines().size());
        Lyrics.Line first = l.lines().get(0);
        assertEquals(1000, first.startMs());
        assertEquals(5500, first.endMs());
        assertEquals(3, first.words().size());
        assertEquals(1000, first.words().get(0).startMs());
        assertTrue(first.words().get(2).startMs() < 1000 + 4500 * 3 / 4 + 1);
        // После «creeper says hi» — пауза до 20 с: строка гаснет не позже чем через 8 с.
        assertEquals(13_500, l.lines().get(1).endMs());
        assertEquals(0, l.lineAt(1200));
        assertEquals(-1, l.lineAt(500));
        assertEquals(-1, l.lineAt(15_000));
        assertEquals(2, l.lineAt(21_000));
    }

    @Test
    void enhancedWordTimesOffsetAndRepeats() {
        Lyrics l = Lyrics.parse("[offset:500]\n[00:02.00][00:10.00]<00:02.00>dig <00:02.40>dig <00:03.10>down", 0);
        assertNotNull(l);
        assertEquals(2, l.lines().size());
        Lyrics.Line line = l.lines().get(0);
        assertEquals(1500, line.startMs());
        assertEquals("dig dig down", line.text());
        assertEquals(1500, line.words().get(0).startMs());
        assertEquals(1900, line.words().get(1).startMs());
        assertEquals(2600, line.words().get(2).startMs());
    }

    @Test
    void plainTextIsSpreadOverTrack() {
        Lyrics l = Lyrics.parse("first row\n\nsecond row\nthird row", 100_000);
        assertNotNull(l);
        assertFalse(l.synced());
        assertEquals(3, l.lines().size());
        assertEquals(8000, l.lines().get(0).startMs());
        assertNull(Lyrics.parse("  \n ", 1000));
    }
}
