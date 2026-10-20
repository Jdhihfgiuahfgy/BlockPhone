package dev.blockphone.fabric.client.music.spotify;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор ответа входа и состояния плеера Spotify (без сети). */
class SpotifyTest {

    @Test
    void callbackQuery() {
        Map<String, String> q = Spotify.queryOf("GET /callback?code=AQB%2Bxyz&state=abc123 HTTP/1.1");
        assertEquals("AQB+xyz", q.get("code"));
        assertEquals("abc123", q.get("state"));
        assertTrue(Spotify.queryOf("GET /favicon.ico HTTP/1.1").isEmpty());
    }

    @Test
    void parsePlayerState() {
        String json = """
                {"device":{"name":"Мой ПК","volume_percent":55},"shuffle_state":true,"repeat_state":"track",
                 "progress_ms":61000,"is_playing":true,
                 "item":{"name":"Track","duration_ms":200000,"artists":[{"name":"A"},{"name":"B"}],
                         "album":{"images":[{"url":"big"},{"url":"mid"},{"url":"small"}]}}}""";
        Spotify.Playing p = Spotify.parsePlaying(JsonParser.parseString(json).getAsJsonObject());
        assertEquals("Track", p.title());
        assertEquals("A, B", p.artist());
        assertEquals("mid", p.coverUrl());
        assertEquals(200000, p.durationMs());
        assertEquals(61000, p.progressMs());
        assertTrue(p.playing() && p.shuffle());
        assertEquals("track", p.repeat());
        assertEquals("Мой ПК", p.device());
        assertEquals(55, p.volume());
        assertNull(Spotify.parsePlaying(JsonParser.parseString("{\"is_playing\":false,\"item\":null}").getAsJsonObject()));
    }
}
