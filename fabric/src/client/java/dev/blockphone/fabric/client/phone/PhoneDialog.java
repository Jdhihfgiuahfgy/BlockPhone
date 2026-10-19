package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Модальный диалог поверх приложения («Удалить фото?» — «Отмена» / «Удалить»).
 * Показывается через {@link PhoneScreen#showDialog(PhoneDialog)}.
 *
 * @param destructive красная кнопка подтверждения (иначе — залитая акцентом)
 */
public record PhoneDialog(String title, String message, String confirmLabel, boolean destructive, Runnable onConfirm) {
    private static final int W = 136;
    private static final int PAD = 8;
    private static final int BUTTON_H = 16;

    public static PhoneDialog confirmDelete(String title, String message, Runnable onConfirm) {
        return new PhoneDialog(title, message, PhoneLang.tr("dialog.delete"), true, onConfirm);
    }

    private int height() {
        return PAD + 12 + PhoneDraw.wrappedHeight(message, W - 2 * PAD, 0.75f) + 8 + BUTTON_H + PAD;
    }

    private int left() {
        return (PhoneLayout.SCREEN_W - W) / 2;
    }

    private int top() {
        return (PhoneLayout.SCREEN_H - height()) / 2;
    }

    private int buttonsY() {
        return top() + height() - PAD - BUTTON_H;
    }

    private int buttonW() {
        return (W - 2 * PAD - 4) / 2;
    }

    /** Координаты — экранные (весь экран телефона). */
    void render(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, PhoneLayout.SCREEN_W, PhoneLayout.SCREEN_H, 0x99000000);
        int x = left();
        int y = top();
        int h = height();
        PhoneDraw.rawRoundedRect(g, x, y, x + W, y + h, PhoneTheme.RADIUS, PhoneTheme.bodyEdge());
        PhoneDraw.rawRoundedRect(g, x + 1, y + 1, x + W - 1, y + h - 1, PhoneTheme.RADIUS - 1, PhoneTheme.card());
        g.fill(x + 2, y + 1, x + W - 2, y + 2, PhoneTheme.accent());
        PhoneDraw.centeredText(g, title, x + W / 2f, y + PAD, PhoneTheme.text(), 1f, false);
        PhoneDraw.wrapped(g, message, x + PAD, y + PAD + 13, W - 2 * PAD, PhoneTheme.secondaryText(), 0.75f, true);
        int by = buttonsY();
        int bw = buttonW();
        PhoneDraw.button(g, x + PAD, by, bw, BUTTON_H, PhoneLang.tr("dialog.cancel"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
        if (destructive) {
            PhoneDraw.button(g, x + PAD + bw + 4, by, bw, BUTTON_H, confirmLabel, mx, my, 0xFFB8403A, PhoneTheme.WHITE);
        } else {
            PhoneDraw.button(g, x + PAD + bw + 4, by, bw, BUTTON_H, confirmLabel, mx, my, PhoneDraw.ButtonStyle.PRIMARY);
        }
    }

    /** @return true — диалог нужно закрыть. */
    boolean mouseClicked(double mx, double my) {
        int x = left();
        int by = buttonsY();
        int bw = buttonW();
        if (PhoneDraw.inside(mx, my, x + PAD, by, bw, BUTTON_H)) {
            return true;
        }
        if (PhoneDraw.inside(mx, my, x + PAD + bw + 4, by, bw, BUTTON_H)) {
            onConfirm.run();
            return true;
        }
        return false;
    }
}
