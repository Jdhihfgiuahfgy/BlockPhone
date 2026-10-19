package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.config.PhoneSettings;

/**
 * Оформление телефона (ARGB): свой стиль — тёмно-синие панели с тонкими рамками, небольшие скругления
 * и акцентный цвет, который игрок выбирает сам. Есть тёмная (по умолчанию) и светлая основа.
 */
public final class PhoneTheme {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int BLACK = 0xFF000000;
    /** Скругление карточек и кнопок (круги — аватарки, точки, кнопка «отправить» — остаются кругами). */
    public static final int RADIUS = 4;

    /** Готовые акцентные цвета (названия не нужны — показываются образцами). */
    public static final int[] ACCENTS = {
            0xFF4C8DF6, 0xFF2EC4D6, 0xFF3FB97A, 0xFF8BC34A, 0xFFF2C14E, 0xFFF29544,
            0xFFE5534B, 0xFFE0559B, 0xFFA371F7, 0xFF7C83FD, 0xFF9AA5B8, 0xFFE8E8E8,
    };

    /** Стандартные обои: градиенты сверху вниз. Названия — {@code wallpaper.<index>} в lang. */
    public static final int[][] WALLPAPERS = {
            {0xFF0E1A33, 0xFF1F3A6B},
            {0xFF141E30, 0xFF35577D},
            {0xFF1A1033, 0xFF5B2A6E},
            {0xFF0F2A2A, 0xFF1E5A50},
            {0xFF2B1A12, 0xFF7A3F25},
            {0xFF121212, 0xFF2E2E2E},
            {0xFFDCE6F5, 0xFFA9BEDF},
    };

    public static boolean dark() {
        return PhoneSettings.get().darkTheme;
    }

    // --- Акцент ---

    public static int accent() {
        return PhoneSettings.get().accentColor | 0xFF000000;
    }

    /** Акцент с прозрачностью alpha (0..255) — подложка выбранных вкладок, выделений. */
    public static int accentTint(int alpha) {
        return (accent() & 0x00FFFFFF) | (alpha << 24);
    }

    /** Чуть светлее акцента — кнопка под курсором. */
    public static int accentHover() {
        return mix(accent(), WHITE, 0.15f);
    }

    /** Текст поверх акцента: белый, а на очень светлом акценте — тёмный. */
    public static int onAccent() {
        int c = accent();
        float lum = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
        return lum > 0.72f ? 0xFF10151F : WHITE;
    }

    // --- Основа ---

    /** Фон приложений. */
    public static int background() {
        return dark() ? 0xFF111725 : 0xFFEEF1F6;
    }

    /** Панели и карточки. */
    public static int card() {
        return dark() ? 0xFF1A2232 : 0xFFFFFFFF;
    }

    public static int cardHover() {
        return dark() ? 0xFF232D41 : 0xFFE4EAF3;
    }

    /** Тонкая рамка панелей. */
    public static int border() {
        return dark() ? 0xFF2B364C : 0xFFD2DAE6;
    }

    /** Поля ввода. */
    public static int field() {
        return dark() ? 0xFF0D121D : 0xFFF6F8FB;
    }

    /** Полоса заголовка приложения. */
    public static int header() {
        return dark() ? 0xFF151C2A : 0xFFF8FAFD;
    }

    public static int text() {
        return dark() ? 0xFFE6ECF5 : 0xFF1B2333;
    }

    public static int secondaryText() {
        return dark() ? 0xFF8A95AB : 0xFF6A7488;
    }

    public static int separator() {
        return dark() ? 0xFF243046 : 0xFFDDE3EC;
    }

    public static int danger() {
        return 0xFFE5534B;
    }

    public static int online() {
        return 0xFF3FB97A;
    }

    // --- Корпус ---

    public static int body() {
        return dark() ? 0xFF0B0F18 : 0xFF1C2230;
    }

    /** Окантовка корпуса — подсвечена акцентом. */
    public static int bodyEdge() {
        return mix(0xFF2B364C, accent(), 0.45f);
    }

    public static int[] wallpaper() {
        int index = PhoneSettings.get().wallpaper;
        return WALLPAPERS[Math.floorMod(index, WALLPAPERS.length)];
    }

    /** Смешать два цвета: t=0 — a, t=1 — b. */
    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        int al = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        return al << 24 | r << 16 | g << 8 | bl;
    }

    private PhoneTheme() {
    }
}
