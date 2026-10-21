package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.phone.PhoneTheme;

/**
 * Цвета приложения Telegram — как у официального клиента (тёмная «Night» и светлая «Classic»), по теме телефона.
 * Имена методов — как у {@link PhoneTheme}, чтобы экраны Telegram брали цвета отсюда.
 */
final class TgTheme {
    static boolean dark() {
        return PhoneTheme.dark();
    }

    /** Фон списка чатов, шапки и панелей. */
    static int background() {
        return dark() ? 0xFF17212B : 0xFFFFFFFF;
    }

    /** Фон переписки. */
    static int chatBg() {
        return dark() ? 0xFF0E1621 : 0xFFDCE6CF;
    }

    static int header() {
        return dark() ? 0xFF17212B : 0xFFFFFFFF;
    }

    static int card() {
        return inBubble();
    }

    static int inBubble() {
        return dark() ? 0xFF182533 : 0xFFFFFFFF;
    }

    static int outBubble() {
        return dark() ? 0xFF2B5278 : 0xFFEFFDDE;
    }

    static int cardHover() {
        return dark() ? 0xFF202B36 : 0xFFF1F1F1;
    }

    static int text() {
        return dark() ? 0xFFF5F5F5 : 0xFF000000;
    }

    static int onAccent() {
        return 0xFFFFFFFF;
    }

    static int secondaryText() {
        return dark() ? 0xFF6D7F8F : 0xFF8A9AA9;
    }

    /** Время в своём пузыре. */
    static int outMeta() {
        return dark() ? 0xFF7DA8D3 : 0xFF5FA853;
    }

    /** Галочки «доставлено/прочитано». */
    static int check() {
        return dark() ? 0xFF64B9FA : 0xFF4FAE4E;
    }

    static int accent() {
        return dark() ? 0xFF5EB5F7 : 0xFF3390EC;
    }

    static int accentTint(int alpha) {
        return (alpha << 24) | (accent() & 0xFFFFFF);
    }

    static int accentHover() {
        return mix(accent(), 0xFFFFFFFF, 0.15f);
    }

    static int background2() {
        return chatBg();
    }

    static int separator() {
        return dark() ? 0xFF0F1A24 : 0xFFE7E7E7;
    }

    static int badge() {
        return dark() ? 0xFF4E8CD4 : 0xFF3390EC;
    }

    static int badgeMuted() {
        return dark() ? 0xFF3E546A : 0xFFB8C1CA;
    }

    static int online() {
        return 0xFF4DCD5E;
    }

    static int danger() {
        return PhoneTheme.danger();
    }

    static int mix(int a, int b, float t) {
        return PhoneTheme.mix(a, b, t);
    }

    private TgTheme() {
    }
}
