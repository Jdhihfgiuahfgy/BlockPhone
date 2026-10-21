package dev.blockphone.fabric.client.phone;

/**
 * Размеры телефона в «виртуальных» единицах. Телефон всегда рисуется в этой системе координат,
 * а {@link PhoneScreen} масштабирует её под размер окна. Поэтому приложения верстаются
 * под фиксированную ширину {@link #SCREEN_W}.
 */
public final class PhoneLayout {
    /** Корпус. */
    public static final int PHONE_W = 176;
    public static final int PHONE_H = 356;
    public static final int BEZEL = 8;
    public static final int BODY_RADIUS = 14;

    /** Экран внутри корпуса. */
    public static final int SCREEN_W = PHONE_W - BEZEL * 2;
    public static final int SCREEN_H = PHONE_H - BEZEL * 2;
    public static final int SCREEN_RADIUS = 8;

    /** Строка состояния сверху и панель навигации («назад», «домой») снизу. */
    public static final int STATUS_H = 14;
    public static final int NAV_H = 14;
    /** Заголовок приложения (если {@link PhoneApp#hasHeader()}). */
    public static final int HEADER_H = 20;

    /** Область приложения: между строкой состояния и панелью навигации. */
    public static final int APP_W = SCREEN_W;
    public static final int APP_H = SCREEN_H - STATUS_H - NAV_H;

    // --- Текущая ориентация (ставит PhoneScreen перед отрисовкой и вводом; телефон на экране один) ---

    /** Телефон повёрнут набок (приложение попросило альбомную ориентацию). */
    static boolean landscape;
    /** Полный экран: без строки состояния и навигации (игры). */
    static boolean immersive;

    public static boolean landscape() {
        return landscape;
    }

    public static int phoneW() {
        return landscape ? PHONE_H : PHONE_W;
    }

    public static int phoneH() {
        return landscape ? PHONE_W : PHONE_H;
    }

    public static int screenW() {
        return phoneW() - BEZEL * 2;
    }

    public static int screenH() {
        return phoneH() - BEZEL * 2;
    }

    public static int statusH() {
        return immersive ? 0 : STATUS_H;
    }

    public static int navH() {
        return immersive ? 0 : NAV_H;
    }

    /** Область приложения (без строки состояния и навигации, с заголовком). */
    public static int appW() {
        return screenW();
    }

    public static int appH() {
        return screenH() - statusH() - navH();
    }

    private PhoneLayout() {
    }
}
