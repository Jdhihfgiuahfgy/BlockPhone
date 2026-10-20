package dev.blockphone.fabric.client.music.yandex;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Разбор плейлиста из ответа Яндекс Музыки (данные придуманы). */
class PlaylistParseTest {

    @Test
    void parsesPlaylist() {
        YandexApi.Playlist p = YandexApi.parsePlaylist(JsonParser.parseString("""
                {"kind": 1003, "title": "Для стройки", "trackCount": 12, "owner": {"uid": 42},
                 "cover": {"uri": "avatars.example/get/abc/%%"}}""").getAsJsonObject(), "1");
        assertEquals("42", p.owner());
        assertEquals("1003", p.kind());
        assertEquals("Для стройки", p.title());
        assertEquals(12, p.trackCount());
        assertEquals("https://avatars.example/get/abc/200x200", p.coverUrl());
        assertNull(YandexApi.parsePlaylist(JsonParser.parseString("{\"title\": \"x\"}").getAsJsonObject(), "1"));
    }
}
