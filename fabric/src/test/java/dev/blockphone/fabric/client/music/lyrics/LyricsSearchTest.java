package dev.blockphone.fabric.client.music.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Поиск текста: очистка названия и исполнителя, выбор текста с временем строк по длине записи (данные придуманы). */
class LyricsSearchTest {

    @Test
    void cleansTitleAndArtist() {
        assertEquals("Blue Night", LyricsService.cleanTitle("Blue Night (Remastered 2011)"));
        assertEquals("Blue Night", LyricsService.cleanTitle("Blue Night [Live]"));
        assertEquals("Blue Night", LyricsService.cleanTitle("Blue Night - Radio Edit"));
        assertEquals("Blue Night", LyricsService.cleanTitle("Blue Night feat. Someone"));
        assertEquals("(Intro)", LyricsService.cleanTitle("(Intro)"));
        assertEquals("Alpha", LyricsService.mainArtist("Alpha, Beta"));
        assertEquals("Alpha", LyricsService.mainArtist("Alpha & Beta"));
        assertEquals("Alpha", LyricsService.mainArtist("Alpha feat. Beta"));
        assertEquals("Alpha Beta", LyricsService.mainArtist("Alpha Beta"));
    }

    @Test
    void picksSyncedByDuration() {
        JsonArray results = JsonParser.parseString("""
                [{"duration": 300, "syncedLyrics": "[00:01.00]far"},
                 {"duration": 181, "syncedLyrics": "[00:01.00]close"},
                 {"duration": 180, "plainLyrics": "plain only"}]""").getAsJsonArray();
        assertEquals("[00:01.00]close", LyricsService.bestSynced(results, 180));
        assertNull(LyricsService.bestSynced(results, 240));
    }
}
