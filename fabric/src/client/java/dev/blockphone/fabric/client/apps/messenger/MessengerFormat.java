package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.phone.PhoneClock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Форматирование времени и превью сообщений. */
final class MessengerFormat {

    static ZonedDateTime at(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
    }

    /** «14:05». */
    static String time(long millis) {
        return at(millis).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Для списка чатов: сегодня — время, иначе дата. */
    static String shortTime(long millis) {
        ZonedDateTime t = at(millis);
        return t.toLocalDate().equals(LocalDate.now()) ? time(millis) : t.format(DateTimeFormatter.ofPattern("dd.MM"));
    }

    /** Разделитель дней в переписке: «Сегодня», «Вчера», «19 октября». */
    static String day(long millis) {
        LocalDate date = at(millis).toLocalDate();
        LocalDate today = LocalDate.now();
        if (date.equals(today)) {
            return PhoneLang.tr("messenger.today");
        }
        if (date.equals(today.minusDays(1))) {
            return PhoneLang.tr("messenger.yesterday");
        }
        String pattern = date.getYear() == today.getYear() ? "d MMMM" : "d MMMM yyyy";
        return date.format(DateTimeFormatter.ofPattern(pattern, PhoneClock.locale()));
    }

    static boolean sameDay(long a, long b) {
        return at(a).toLocalDate().equals(at(b).toLocalDate());
    }

    /** Превью последнего сообщения для списка чатов. */
    static String preview(MessageInfo message, boolean group) {
        if (message == null) {
            return PhoneLang.tr("messenger.no_messages");
        }
        String text = message.deleted() ? PhoneLang.tr("messenger.deleted") : MessengerClient.previewText(message);
        if (MessengerClient.isSelf(message.sender().uuid())) {
            return PhoneLang.tr("messenger.you") + ": " + text;
        }
        return group ? message.sender().name() + ": " + text : text;
    }

    private MessengerFormat() {
    }
}
