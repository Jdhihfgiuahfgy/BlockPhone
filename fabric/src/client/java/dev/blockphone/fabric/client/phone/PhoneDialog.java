package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Модальный диалог поверх приложения («Удалить фото?» — «Отмена» / «Удалить»).
 * Показывается через {@link PhoneScreen#showDialog(PhoneDialog)}.
 *
 * @param destructive красная кнопка подтверждения
 */
public record PhoneDialog(String title, String message, String confirmLabel, boolean destructive, Runnable onConfirm) {
    private static final int W = 130;
    private static final int BUTTON_H = 20;

    public static PhoneDialog confirmDelete(String title, String message, Runnable onConfirm) {
        return new PhoneDialog(title, message, PhoneLang.tr("dialog.delete"), true, onConfirm);
    }

    private int height() {
        return 14 + 12 + PhoneDraw.wrappedHeight(message, W - 16, 0.75f) + 10 + BUTTON_H;
    }

    private int left() {
        return (PhoneLayout.SCREEN_W - W) / 2;
    }

    private int top() {
        return (PhoneLayout.SCREEN_H - height()) / 2;
    }

    /** Координаты — экранные (весь экран телефона). */
    void render(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, PhoneLayout.SCREEN_W, PhoneLayout.SCREEN_H, 0x88000000);
        int x = left();
        int y = top();
        int h = height();
        PhoneDraw.roundedRect(g, x, y, x + W, y + h, 10, PhoneTheme.card());
        PhoneDraw.centeredText(g, title, x + W / 2f, y + 10, PhoneTheme.text(), 1f, false);
        PhoneDraw.wrapped(g, message, x + 8, y + 24, W - 16, PhoneTheme.secondaryText(), 0.75f, true);

        int by = y + h - BUTTON_H;
        g.fill(x, by, x + W, by + 1, PhoneTheme.separator());
        g.fill(x + W / 2, by, x + W / 2 + 1, y + h, PhoneTheme.separator());
        if (PhoneDraw.inside(mx, my, x, by, W / 2, BUTTON_H)) {
            g.fill(x, by + 1, x + W / 2, y + h - 4, PhoneTheme.cardHover());
        }
        if (PhoneDraw.inside(mx, my, x + W / 2, by, W / 2, BUTTON_H)) {
            g.fill(x + W / 2 + 1, by + 1, x + W, y + h - 4, PhoneTheme.cardHover());
        }
        PhoneDraw.centeredText(g, PhoneLang.tr("dialog.cancel"), x + W / 4f, by + 6, PhoneTheme.ACCENT, 1f, false);
        PhoneDraw.centeredText(g, confirmLabel, x + W * 3 / 4f, by + 6, destructive ? 0xFFFF3B30 : PhoneTheme.ACCENT, 1f, false);
    }

    /** @return true — диалог нужно закрыть. */
    boolean mouseClicked(double mx, double my) {
        int x = left();
        int by = top() + height() - BUTTON_H;
        if (PhoneDraw.inside(mx, my, x, by, W / 2, BUTTON_H)) {
            return true;
        }
        if (PhoneDraw.inside(mx, my, x + W / 2, by, W / 2, BUTTON_H)) {
            onConfirm.run();
            return true;
        }
        return false;
    }
}
