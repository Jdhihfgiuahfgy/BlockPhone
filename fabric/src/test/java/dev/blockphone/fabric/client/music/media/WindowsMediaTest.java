package dev.blockphone.fabric.client.music.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Название приложения по его id в системных медиа Windows и расчёт позиции (без PowerShell). */
class WindowsMediaTest {
    private static WindowsMedia.Playing playing(String app, boolean playing, long position, long duration, long receivedAt) {
        return new WindowsMedia.Playing(app, "t", "a", "", playing, position, duration, receivedAt, false, "None", true, true, true,
                false, false, null);
    }

    @Test
    void appNames() {
        assertEquals("Spotify", playing("Spotify.exe", true, 0, 0, 0).appName());
        assertEquals("Spotify", playing("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", true, 0, 0, 0).appName());
        assertEquals("Chrome", playing("Chrome", true, 0, 0, 0).appName());
        assertEquals("Яндекс Музыка", playing("ru.yandex.desktop.music", true, 0, 0, 0).appName());
        assertEquals("foobar2000", playing("C:\\Program Files\\foobar2000\\foobar2000.exe", true, 0, 0, 0).appName());
    }

    @Test
    void positionAdvancesOnlyWhilePlaying() {
        long now = System.currentTimeMillis();
        assertEquals(5000, playing("x", false, 5000, 10_000, now - 2000).position());
        long p = playing("x", true, 5000, 10_000, now - 2000).position();
        assertEquals(true, p >= 7000 && p <= 7100);
        assertEquals(10_000, playing("x", true, 9000, 10_000, now - 5000).position());
    }
}
