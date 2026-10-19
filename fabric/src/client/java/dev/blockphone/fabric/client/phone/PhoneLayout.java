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
    public static final int BODY_RADIUS = 24;

    /** Экран внутри корпуса. */
    public static final int SCREEN_W = PHONE_W - BEZEL * 2;
    public static final int SCREEN_H = PHONE_H - BEZEL * 2;
    public static final int SCREEN_RADIUS = 16;

    /** Строка состояния сверху и полоска «домой» снизу. */
    public static final int STATUS_H = 14;
    public static final int NAV_H = 12;
    /** Заголовок приложения (если {@link PhoneApp#hasHeader()}). */
    public static final int HEADER_H = 20;

    /** Область приложения: между строкой состояния и полоской «домой». */
    public static final int APP_W = SCREEN_W;
    public static final int APP_H = SCREEN_H - STATUS_H - NAV_H;

    private PhoneLayout() {
    }
}
