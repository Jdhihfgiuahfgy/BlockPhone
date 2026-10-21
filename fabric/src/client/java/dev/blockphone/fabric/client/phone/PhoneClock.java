package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Время для строки состояния и рабочего стола: реальное или игровое (по настройке). */
public final class PhoneClock {

    /** Время по настройке «Часы»: реальное или игровое; в 12-часовом формате — с AM/PM. */
    public static String time() {
        String suffix = suffix();
        return suffix.isEmpty() ? clock() : clock() + " " + suffix;
    }

    /** «AM»/«PM» в 12-часовом формате, иначе пустая строка (для крупных часов — рисуется отдельно, мельче). */
    public static String suffix() {
        if (PhoneSettings.get().clock24h) {
            return "";
        }
        Minecraft mc = Minecraft.getInstance();
        int hours;
        if (PhoneSettings.get().gameTimeClock && mc.level != null) {
            hours = (int) ((Math.floorMod(mc.level.getDayTime(), 24000L) / 1000 + 6) % 24);
        } else {
            hours = LocalTime.now().getHour();
        }
        return hours < 12 ? "AM" : "PM";
    }

    /** Только цифры времени (без AM/PM). */
    public static String clock() {
        Minecraft mc = Minecraft.getInstance();
        if (PhoneSettings.get().gameTimeClock && mc.level != null) {
            return gameClock(mc.level);
        }
        LocalTime now = LocalTime.now();
        return format(now.getHour(), now.getMinute());
    }

    /** Игровое время мира. В Minecraft 0 тиков = 6:00 утра. */
    public static String gameTime(Level level) {
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        int hours = (int) ((dayTime / 1000 + 6) % 24);
        int minutes = (int) (dayTime % 1000 * 60 / 1000);
        String text = format(hours, minutes);
        return PhoneSettings.get().clock24h ? text : text + (hours < 12 ? " AM" : " PM");
    }

    private static String gameClock(Level level) {
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        return format((int) ((dayTime / 1000 + 6) % 24), (int) (dayTime % 1000 * 60 / 1000));
    }

    /** Крупные часы (рабочий стол, экран блокировки): цифры, а AM/PM — мелко справа. */
    public static void drawBig(net.minecraft.client.gui.GuiGraphics g, float cx, float y) {
        String clock = clock();
        String suffix = suffix();
        float w = PhoneDraw.width(clock, 3f);
        float sw = suffix.isEmpty() ? 0 : PhoneDraw.width(suffix, 1f) + 2;
        float x = cx - (w + sw) / 2f;
        PhoneDraw.text(g, clock, x, y, 0xFFFFFFFF, 3f, true);
        if (!suffix.isEmpty()) {
            PhoneDraw.text(g, suffix, x + w + 2, y + 15, 0xFFFFFFFF, 1f, true);
        }
    }

    private static String format(int hours, int minutes) {
        if (PhoneSettings.get().clock24h) {
            return String.format("%d:%02d", hours, minutes);
        }
        int h12 = hours % 12 == 0 ? 12 : hours % 12;
        return String.format("%d:%02d", h12, minutes);
    }

    /** «Понедельник, 19 октября» / «Monday, October 19» (или «День 42» в режиме игрового времени). */
    public static String date() {
        Minecraft mc = Minecraft.getInstance();
        if (PhoneSettings.get().gameTimeClock && mc.level != null) {
            return PhoneLang.tr("clock.game_day", mc.level.getDayTime() / 24000L + 1);
        }
        Locale locale = locale();
        String pattern = locale.getLanguage().equals("ru") ? "EEEE, d MMMM" : "EEEE, MMMM d";
        String s = LocalDate.now().format(DateTimeFormatter.ofPattern(pattern, locale));
        return s.substring(0, 1).toUpperCase(locale) + s.substring(1);
    }

    public static Locale locale() {
        return PhoneLang.current().startsWith("ru") ? Locale.forLanguageTag("ru-RU") : Locale.US;
    }

    private PhoneClock() {
    }
}
