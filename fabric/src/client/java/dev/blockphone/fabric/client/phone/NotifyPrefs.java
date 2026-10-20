package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.config.PhoneSettings;

import java.util.List;

/**
 * Настройки уведомлений: общий выключатель, всплывающие окошки, центр уведомлений (и экран блокировки), звук звонка
 * и каждое приложение отдельно ({@link #APPS}). Хранятся в {@link PhoneSettings} (notify*).
 */
public final class NotifyPrefs {
    /** Приложения с уведомлениями (ключ — id приложения; "calls" — звонки). */
    public static final List<String> APPS = List.of("messenger", "feed", "telegram", "calls");

    /** Уведомления приложения вообще разрешены. */
    public static boolean allowed(String app) {
        PhoneSettings s = PhoneSettings.get();
        return s.notifyAll && !s.notifyMuted.contains(app);
    }

    /** Показать всплывающее окошко. */
    public static boolean toast(String app) {
        return allowed(app) && PhoneSettings.get().notifyToasts;
    }

    /** Положить в центр уведомлений (виден и на экране блокировки). */
    public static boolean center(String app) {
        return allowed(app) && PhoneSettings.get().notifyCenter;
    }

    /** Звонить (звук входящего звонка). */
    public static boolean ring() {
        return allowed("calls") && PhoneSettings.get().notifySound;
    }

    public static boolean appEnabled(String app) {
        return !PhoneSettings.get().notifyMuted.contains(app);
    }

    public static void toggleApp(String app) {
        PhoneSettings s = PhoneSettings.get();
        if (!s.notifyMuted.remove(app)) {
            s.notifyMuted.add(app);
        }
    }

    private NotifyPrefs() {
    }
}
