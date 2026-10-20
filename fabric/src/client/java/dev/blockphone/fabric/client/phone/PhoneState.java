package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.apps.home.HomeApp;

import java.util.HashMap;
import java.util.Map;

/**
 * Состояние телефона, которое переживает закрытие экрана: открытое приложение и экземпляры приложений.
 * Сбрасывается при выходе с сервера ({@link #reset()}).
 */
public final class PhoneState {
    public static final String HOME = "home";
    /** Экран блокировки (не приложение рабочего стола). */
    public static final String LOCK = "lock";
    /** Через сколько после закрытия телефон при открытии покажет экран блокировки. */
    private static final long LOCK_AFTER_MS = 15_000;
    private static long closedAt;

    private static final Map<String, PhoneApp> APPS = new HashMap<>();
    private static String currentId = HOME;

    public static PhoneApp current() {
        PhoneApp app = app(currentId);
        if (app == null) {
            currentId = HOME;
            app = app(HOME);
        }
        return app;
    }

    public static void setCurrent(String id) {
        currentId = id;
    }

    /** Экземпляр приложения (создаётся при первом обращении). */
    public static PhoneApp app(String id) {
        if (HOME.equals(id)) {
            return APPS.computeIfAbsent(HOME, k -> new HomeApp());
        }
        if (LOCK.equals(id)) {
            return APPS.computeIfAbsent(LOCK, k -> new dev.blockphone.fabric.client.apps.lock.LockApp());
        }
        PhoneApp existing = APPS.get(id);
        if (existing != null) {
            return existing;
        }
        AppRegistry.Entry entry = AppRegistry.get(id);
        if (entry == null || !entry.visible().getAsBoolean()) {
            return null;
        }
        PhoneApp created = entry.factory().get();
        APPS.put(id, created);
        return created;
    }

    /** Телефон закрыли (для экрана блокировки). */
    public static void markClosed() {
        closedAt = System.currentTimeMillis();
    }

    /** Показать экран блокировки: он включён и телефон был закрыт достаточно долго (или открывается впервые). */
    public static boolean shouldLock() {
        return dev.blockphone.fabric.client.config.PhoneSettings.get().lockScreen
                && (closedAt == 0 || System.currentTimeMillis() - closedAt > LOCK_AFTER_MS);
    }

    public static void reset() {
        APPS.clear();
        currentId = HOME;
    }

    private PhoneState() {
    }
}
