package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.config.PhoneSettings;

/** Цвета интерфейса телефона (ARGB). Светлая/тёмная тема переключается в настройках. */
public final class PhoneTheme {
    public static final int BODY = 0xFF1B1B1F;
    public static final int BODY_EDGE = 0xFF45454D;
    public static final int ACCENT = 0xFF0A84FF;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int BLACK = 0xFF000000;

    /** Обои: пары цветов градиента сверху вниз. Названия — {@code wallpaper.<index>} в lang. */
    public static final int[][] WALLPAPERS = {
            {0xFF1E3C72, 0xFF6A3093},
            {0xFFFF7E5F, 0xFFFEB47B},
            {0xFF134E5E, 0xFF71B280},
            {0xFF232526, 0xFF414345},
            {0xFF0F2027, 0xFF2C5364},
            {0xFFEE9CA7, 0xFFFFDDE1},
    };

    public static boolean dark() {
        return PhoneSettings.get().darkTheme;
    }

    /** Фон приложений. */
    public static int background() {
        return dark() ? 0xFF000000 : 0xFFF2F2F7;
    }

    /** Карточки/строки списков. */
    public static int card() {
        return dark() ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    public static int cardHover() {
        return dark() ? 0xFF2C2C2E : 0xFFE5E5EA;
    }

    public static int text() {
        return dark() ? 0xFFFFFFFF : 0xFF1C1C1E;
    }

    public static int secondaryText() {
        return dark() ? 0xFF8E8E93 : 0xFF6C6C70;
    }

    public static int separator() {
        return dark() ? 0xFF38383A : 0xFFD1D1D6;
    }

    public static int[] wallpaper() {
        int index = PhoneSettings.get().wallpaper;
        return WALLPAPERS[Math.floorMod(index, WALLPAPERS.length)];
    }

    private PhoneTheme() {
    }
}
