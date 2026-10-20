package dev.blockphone.fabric.client.social;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneClock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Форматирование времени и чисел ленты. */
public final class SocialFormat {

    /** «сейчас», «5 мин», «3 ч», «вчера», «19 окт». */
    public static String ago(long millis) {
        long diff = Math.max(0, System.currentTimeMillis() - millis);
        long minutes = diff / 60_000;
        if (minutes < 1) {
            return PhoneLang.tr("social.now");
        }
        if (minutes < 60) {
            return PhoneLang.tr("social.minutes", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return PhoneLang.tr("social.hours", hours);
        }
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        LocalDate today = LocalDate.now();
        if (t.toLocalDate().equals(today.minusDays(1))) {
            return PhoneLang.tr("messenger.yesterday").toLowerCase(PhoneClock.locale());
        }
        String pattern = t.getYear() == today.getYear() ? "d MMM" : "d MMM yyyy";
        return t.format(DateTimeFormatter.ofPattern(pattern, PhoneClock.locale()));
    }

    /** «20 октября, 14:05». */
    public static String dateTime(long millis) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        return t.format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", PhoneClock.locale()));
    }

    /** 999, 1.2K, 15K. */
    public static String count(int value) {
        if (value < 1000) {
            return String.valueOf(value);
        }
        if (value < 10_000) {
            return String.format(java.util.Locale.ROOT, "%.1fK", value / 1000f);
        }
        return (value / 1000) + "K";
    }

    private SocialFormat() {
    }
}
