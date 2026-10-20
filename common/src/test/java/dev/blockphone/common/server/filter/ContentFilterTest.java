package dev.blockphone.common.server.filter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Автомодерация: слова (с обходами), белый список, ссылки и их форматы, автомьют. Слова — условные. */
class ContentFilterTest {

    private static FilterSettings words(String... blacklist) {
        FilterSettings s = new FilterSettings();
        s.words.blacklist = List.of(blacklist);
        return s;
    }

    @Test
    void censorsWholeWordsAndBypassTricks() {
        ContentFilter f = new ContentFilter(words("плохослово", "badword"));
        assertEquals("ты " + "*".repeat("плохослово".length()) + "!", f.check("ты плохослово!").text());
        assertEquals("**********", f.check("ПЛОХОСЛОВО").text());
        assertTrue(f.check("п.л.о.х.о.с.л.о.в.о").violation());
        assertTrue(f.check("плоооохослово").violation());
        assertTrue(f.check("b@dw0rd").violation());
        // Латинские буквы, похожие на русские: «плохослово» с латинской «o».
        assertTrue(f.check("плoхoслoвo").violation());
        // Целое слово: внутри другого слова не срабатывает.
        assertFalse(f.check("суперплохословище").violation());
        assertNull(f.check("обычный текст").reason());
    }

    @Test
    void wildcardsWhitelistRegexAndBlock() {
        FilterSettings s = words("*ass*", "плох*", "regex:\\bspam+\\b");
        s.words.whitelist = List.of("class", "glass");
        ContentFilter f = new ContentFilter(s);
        assertFalse(f.check("first class glass").violation());
        assertTrue(f.check("massive").violation());
        assertTrue(f.check("плохой день").violation());
        assertTrue(f.check("spammm").violation());
        s.words.action = "block";
        ContentFilter.Verdict v = new ContentFilter(s).check("плохой");
        assertTrue(v.blocked());
        assertEquals("filter_words", v.reason());
    }

    @Test
    void linkFormatsAndLists() {
        FilterSettings s = new FilterSettings();
        ContentFilter f = new ContentFilter(s);
        // По умолчанию: обычные ссылки можно, IP и замаскированные — нет (block).
        assertFalse(f.check("заходи на https://example.com/page").violation());
        assertTrue(f.check("сервер 123.45.67.89:25565").blocked());
        assertTrue(f.check("сайт example[.]com").blocked());
        assertTrue(f.check("сайт example (dot) com").blocked());
        assertTrue(f.check("сайт example точка ru").blocked());
        assertFalse(f.check("Конец предложения. Начало").violation());
        assertFalse(f.check("версия 1.21.11 и файл notes.txt").violation());

        s.links.mode = "block";
        s.links.domainWhitelist = List.of("example.com", "youtube.com/watch");
        s.links.formatBlacklist = List.of("invite");
        f = new ContentFilter(s);
        assertFalse(f.check("www.example.com и play.example.com").violation());
        assertFalse(f.check("https://youtube.com/watch?v=1").violation());
        assertTrue(f.check("https://youtube.com/shorts/1").violation());
        assertTrue(f.check("другой-сайт.рф").violation());
        assertTrue(f.check("discord.gg/abc").violation());

        s.links.mode = "allow";
        s.links.domainWhitelist = List.of();
        s.links.formatBlacklist = List.of("scheme:ftp", "email", "my");
        s.links.customFormats = java.util.Map.of("my", "mc\\.[a-z]+\\.net");
        s.links.action = "censor";
        f = new ContentFilter(s);
        assertTrue(f.check("ftp://files.example.com").violation());
        assertFalse(f.check("https://files.example.com").violation());
        assertEquals("пиши " + "*".repeat("steve@example.com".length()), f.check("пиши steve@example.com").text());
        assertTrue(f.check("ip mc.server.net").violation());
    }

    @Test
    void autoMuteAfterRepeatedViolations() {
        FilterSettings s = words("badword");
        s.autoMute.violations = 3;
        ContentFilter f = new ContentFilter(s);
        UUID p = UUID.randomUUID();
        long now = 1_000_000;
        assertFalse(f.recordViolation(p, now));
        assertFalse(f.recordViolation(p, now + 1000));
        assertTrue(f.recordViolation(p, now + 2000));
        assertTrue(f.mutedUntil(p, now + 3000) > now);
        assertEquals(0, f.mutedUntil(p, now + 2000 + 16 * 60_000L));
    }
}
