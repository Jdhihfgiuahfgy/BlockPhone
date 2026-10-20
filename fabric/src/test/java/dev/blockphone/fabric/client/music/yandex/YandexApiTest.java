package dev.blockphone.fabric.client.music.yandex;

import com.google.gson.JsonParser;
import dev.blockphone.fabric.client.music.Track;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Разбор ответов Яндекс Музыки и подпись ссылки на mp3 (без сети). */
class YandexApiTest {

    @Test
    void md5MatchesKnownValue() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", YandexApi.md5("hello"));
    }

    @Test
    void signedUrlFromDownloadInfo() {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><download-info><host>s123.storage.yandex.net</host>"
                + "<path>/rmusic/U2FsdGVk/abc</path><ts>0005f1a2b3c4</ts><region>-1</region><s>deadbeef</s></download-info>";
        String expectedSign = YandexApi.md5("XGRlBW9FXlekgbPrRHuSiA" + "rmusic/U2FsdGVk/abc" + "deadbeef");
        assertEquals("https://s123.storage.yandex.net/get-mp3/" + expectedSign + "/0005f1a2b3c4/rmusic/U2FsdGVk/abc",
                YandexApi.signedUrl(xml));
        assertNull(YandexApi.signedUrl("<download-info><host>x</host></download-info>"));
    }

    @Test
    void parseTrack() {
        String json = """
                {"id":"12345","title":"Song","version":"Remix","durationMs":215000,"available":true,
                 "coverUri":"avatars.yandex.net/get-music-content/1/abc/%%",
                 "artists":[{"name":"Artist A"},{"name":"Artist B"}],
                 "albums":[{"id":678,"title":"Album"}]}""";
        Track t = YandexApi.parseTrack(JsonParser.parseString(json).getAsJsonObject());
        assertEquals("yandex:12345", t.key());
        assertEquals("Song (Remix)", t.title());
        assertEquals("Artist A, Artist B", t.artist());
        assertEquals("Album", t.album());
        assertEquals(215000, t.durationMs());
        assertEquals("12345:678", t.remoteId());
        assertEquals("https://avatars.yandex.net/get-music-content/1/abc/200x200", t.coverUrl());

        assertNull(YandexApi.parseTrack(JsonParser.parseString("{\"id\":\"1\",\"available\":false}").getAsJsonObject()));
    }
}
