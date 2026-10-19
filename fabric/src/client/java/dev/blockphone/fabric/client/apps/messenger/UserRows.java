package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

/** Строка игрока в списках: аватар, ник, «в сети» / «не в сети», необязательная галочка. */
final class UserRows {
    static final int ROW_H = 26;
    private static final int AVATAR = 18;

    /**
     * @param checked null — без галочки, иначе состояние галочки
     * @param note    текст справа (например, «владелец»), может быть null
     */
    static void draw(GuiGraphics g, UserInfo user, int y, int width, int mouseX, int mouseY, Boolean checked, String note) {
        if (PhoneDraw.inside(mouseX, mouseY, 0, y, width, ROW_H)) {
            g.fill(0, y, width, y + ROW_H, PhoneTheme.cardHover());
        }
        int ay = y + (ROW_H - AVATAR) / 2;
        Avatars.drawUser(g, user, 8, ay, AVATAR);
        boolean online = MessengerClient.isOnline(user);
        if (online) {
            Avatars.drawOnlineDot(g, 8, ay, AVATAR, PhoneTheme.background());
        }
        int textX = 8 + AVATAR + 6;
        float right = width - 8;
        if (checked != null) {
            int bx = width - 20;
            PhoneDraw.roundedRect(g, bx, y + 7, bx + 12, y + 19, 6, checked ? PhoneTheme.ACCENT : PhoneTheme.separator());
            if (checked) {
                PhoneDraw.centeredText(g, "✔", bx + 6, y + 9, 0xFFFFFFFF, 0.75f, false);
            } else {
                PhoneDraw.roundedRect(g, bx + 1, y + 8, bx + 11, y + 18, 5, PhoneTheme.background());
            }
            right = bx - 4;
        }
        if (note != null) {
            PhoneDraw.rightText(g, note, right, y + 9, PhoneTheme.secondaryText(), 0.6f);
            right -= PhoneDraw.width(note, 0.6f) + 4;
        }
        PhoneDraw.text(g, PhoneDraw.ellipsize(user.name(), right - textX, 0.85f), textX, y + 5, PhoneTheme.text(), 0.85f, false);
        String status = MessengerApp.tr(online ? "messenger.online" : "messenger.offline");
        PhoneDraw.text(g, status, textX, y + 15, online ? PhoneTheme.ACCENT : PhoneTheme.secondaryText(), 0.6f, false);
    }

    private UserRows() {
    }
}
