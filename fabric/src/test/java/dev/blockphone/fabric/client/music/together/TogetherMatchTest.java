package dev.blockphone.fabric.client.music.together;

import dev.blockphone.fabric.client.music.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Совместное прослушивание: выбор трека в Яндексе по названию, сравнение названий, приглашения в чате. */
class TogetherMatchTest {
    private static Track yandex(String id, String title, String artist, long duration) {
        return new Track("yandex:" + id, Track.Source.YANDEX, title, artist, "", duration, null, null, id);
    }

    private static ListenTogether.Shared shared(String title, String artist, long duration) {
        return new ListenTogether.Shared(title, artist, duration, null, "Spotify", 0, true);
    }

    @Test
    void picksMatchingTitleAndDurationOverFirstResult() {
        List<Track> results = List.of(
                yandex("1", "Bohemian Rhapsody (Live Aid)", "Queen", 360_000),
                yandex("2", "Bohemian Rhapsody", "Queen", 354_000),
                yandex("3", "Bohemian Rhapsody", "Panic! At The Disco", 230_000));
        assertEquals("2", ListenTogether.best(results, shared("Bohemian Rhapsody", "Queen", 355_000)).remoteId());
    }

    @Test
    void rejectsUnrelatedResults() {
        List<Track> results = List.of(yandex("1", "Совсем другая песня", "Кто-то", 200_000));
        assertNull(ListenTogether.best(results, shared("Numb", "Linkin Park", 185_000)));
    }

    @Test
    void namesCompareWithoutFeatAndBrackets() {
        assertEquals("numb", ListenTogether.norm("Numb (Remastered 2023)"));
        assertTrue(ListenTogether.sameSong("Blinding Lights", "Blinding Lights - Radio Edit"));
        assertTrue(ListenTogether.sameSong("Song feat. Someone", "song"));
        assertFalse(ListenTogether.sameSong("", "song"));
        assertFalse(ListenTogether.sameSong("Yesterday", "Tomorrow"));
    }

    @Test
    void recognizesInvitesFromVanillaAndPluginWhispers() {
        String[] texts = {
                "Steve whispers to you: ♫ BlockPhone: let's listen to music together! Room code BP-4A4LDK",
                "Steve шепчет вам: ♫ BlockPhone: слушаем музыку вместе! Код комнаты BP-4A4LDK",
                "[Steve -> me] ♫ BlockPhone: слушаем музыку вместе! Код комнаты BP-4A4LDK",
        };
        for (String text : texts) {
            Matcher m = TogetherInvites.INVITE.matcher(text);
            assertTrue(m.find(), text);
            assertEquals("4A4LDK", m.group(1));
        }
        assertFalse(TogetherInvites.INVITE.matcher("BP-4A4LDK без названия мода").find());
        assertFalse(TogetherInvites.INVITE.matcher("BlockPhone BP-4A4L1K").find()); // «1» в кодах нет
    }
}
