package dev.blockphone.fabric.client.clock;

import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «Часы» в фоне (работают и с закрытым телефоном): таймер, секундомер и будильники по реальному времени. Будильники
 * хранятся в настройках ({@link PhoneSettings#alarms}). Сработавший будильник или таймер звенит колокольчиком нотного
 * блока до минуты, показывает уведомление; остановить — в приложении «Часы» (или само через минуту).
 */
public final class ClockService {
    /** Звонок длится не дольше. */
    private static final long RING_MS = 60_000;

    // Таймер: идёт — endAt > 0; на паузе — remaining > 0 и endAt = 0.
    private static long timerTotal;
    private static long timerEndAt;
    private static long timerRemaining;

    // Секундомер.
    private static long swStartedAt;
    private static long swAccumulated;
    private static final List<Long> LAPS = new ArrayList<>();

    // Звонок.
    private static @Nullable String ringing;
    private static long ringStartedAt;

    /** Будильник → минута, в которую он уже сработал («2026-10-22T07:30»), чтобы не звенеть дважды. */
    private static final Map<PhoneSettings.Alarm, String> FIRED = new HashMap<>();
    private static String lastMinute = "";

    private ClockService() {
    }

    // --- Таймер ---

    public static boolean timerRunning() {
        return timerEndAt > 0;
    }

    public static boolean timerPaused() {
        return timerEndAt == 0 && timerRemaining > 0;
    }

    /** Осталось (мс); 0 — таймер не запущен. */
    public static long timerLeft() {
        return timerEndAt > 0 ? Math.max(0, timerEndAt - System.currentTimeMillis()) : timerRemaining;
    }

    public static long timerTotal() {
        return timerTotal;
    }

    public static void startTimer(long ms) {
        if (ms <= 0) {
            return;
        }
        timerTotal = ms;
        timerRemaining = 0;
        timerEndAt = System.currentTimeMillis() + ms;
    }

    public static void pauseTimer() {
        if (timerEndAt > 0) {
            timerRemaining = Math.max(1, timerEndAt - System.currentTimeMillis());
            timerEndAt = 0;
        }
    }

    public static void resumeTimer() {
        if (timerEndAt == 0 && timerRemaining > 0) {
            timerEndAt = System.currentTimeMillis() + timerRemaining;
            timerRemaining = 0;
        }
    }

    public static void cancelTimer() {
        timerEndAt = 0;
        timerRemaining = 0;
        timerTotal = 0;
    }

    // --- Секундомер ---

    public static boolean stopwatchRunning() {
        return swStartedAt > 0;
    }

    public static long stopwatchMs() {
        return swAccumulated + (swStartedAt > 0 ? System.currentTimeMillis() - swStartedAt : 0);
    }

    public static void toggleStopwatch() {
        if (swStartedAt > 0) {
            swAccumulated += System.currentTimeMillis() - swStartedAt;
            swStartedAt = 0;
        } else {
            swStartedAt = System.currentTimeMillis();
        }
    }

    public static void lap() {
        if (swStartedAt > 0) {
            LAPS.add(0, stopwatchMs());
            if (LAPS.size() > 99) {
                LAPS.remove(LAPS.size() - 1);
            }
        }
    }

    public static void resetStopwatch() {
        swStartedAt = 0;
        swAccumulated = 0;
        LAPS.clear();
    }

    /** Отметки кругов (время от старта), новые сверху. */
    public static List<Long> laps() {
        return LAPS;
    }

    // --- Звонок ---

    /** Что звенит (текст) или null. */
    public static @Nullable String ringing() {
        return ringing;
    }

    public static void stopRinging() {
        ringing = null;
        if (sound != null) {
            sound.stop();
            sound = null;
        }
        PhoneNotifications.remove("clock:");
    }

    /** Звучащий сигнал (свой звук или встроенный). */
    private static dev.blockphone.fabric.client.sound.PhoneSounds.@Nullable Handle sound;

    private static void ring(String title, String text, dev.blockphone.fabric.client.sound.PhoneSounds.Kind kind) {
        if (sound != null) {
            sound.stop();
        }
        sound = dev.blockphone.fabric.client.sound.PhoneSounds.play(kind, true);
        ringing = text.isEmpty() ? title : title + " · " + text;
        ringStartedAt = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        mc.getToastManager().addToast(SystemToast.multiline(mc, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(text.isEmpty() ? PhoneLang.tr("clock.open_to_stop") : text)));
        PhoneNotifications.add("clock:" + ringStartedAt, "clock", title, text.isEmpty() ? PhoneLang.tr("clock.open_to_stop") : text, null,
                phone -> phone.openApp("clock"));
    }

    // --- Каждый такт ---

    public static void tick() {
        long now = System.currentTimeMillis();
        if (timerEndAt > 0 && now >= timerEndAt) {
            timerEndAt = 0;
            timerRemaining = 0;
            ring(PhoneLang.tr("clock.timer_done"), "", dev.blockphone.fabric.client.sound.PhoneSounds.Kind.TIMER);
        }
        checkAlarms();
        if (ringing != null) {
            if (now - ringStartedAt > RING_MS) {
                stopRinging();
            }
        }
    }

    private static void checkAlarms() {
        LocalDateTime t = LocalDateTime.now();
        String minute = t.toLocalDate() + "T" + t.getHour() + ":" + t.getMinute();
        if (minute.equals(lastMinute)) {
            return;
        }
        lastMinute = minute;
        PhoneSettings settings = PhoneSettings.get();
        int day = t.getDayOfWeek().getValue() - 1; // 0 — понедельник
        boolean changed = false;
        for (PhoneSettings.Alarm alarm : settings.alarms) {
            if (!alarm.enabled || alarm.hour != t.getHour() || alarm.minute != t.getMinute() || minute.equals(FIRED.get(alarm))) {
                continue;
            }
            if (alarm.days != 0 && (alarm.days & (1 << day)) == 0) {
                continue;
            }
            FIRED.put(alarm, minute);
            if (alarm.days == 0) {
                alarm.enabled = false; // одноразовый
                changed = true;
            }
            ring(PhoneLang.tr("clock.alarm") + " " + String.format("%02d:%02d", alarm.hour, alarm.minute),
                    alarm.label == null ? "" : alarm.label, dev.blockphone.fabric.client.sound.PhoneSounds.Kind.ALARM);
        }
        if (changed) {
            settings.save();
        }
    }

    /** «1:02:03.45» / «02:03.45» (с сотыми) или без них. */
    public static String format(long ms, boolean hundredths) {
        long total = Math.max(0, ms);
        long h = total / 3_600_000;
        long m = total / 60_000 % 60;
        long s = total / 1000 % 60;
        String base = h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
        return hundredths ? base + String.format(".%02d", total / 10 % 100) : base;
    }
}
