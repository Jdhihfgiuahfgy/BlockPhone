package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.data.SocialData.Role;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialFormat;
import net.minecraft.client.gui.GuiGraphics;

/** Строки списков ленты: игрок и паблик. */
final class Rows {
    static final int H = 26;
    private static final int AVATAR = 18;

    /** Строка игрока: аватар, ник, «в сети»; note — текст справа (может быть null). */
    static void user(SocialView view, GuiGraphics g, UserInfo user, int y, int w, int mx, int my, String note, Runnable action) {
        if (PhoneDraw.inside(mx, my, 0, y, w, H)) {
            g.fill(0, y, w, y + H, PhoneTheme.cardHover());
        }
        int ay = y + (H - AVATAR) / 2;
        Avatars.drawUser(g, user, 8, ay, AVATAR);
        boolean online = MessengerClient.isOnline(user);
        if (online) {
            Avatars.drawOnlineDot(g, 8, ay, AVATAR, PhoneTheme.background());
        }
        int tx = 8 + AVATAR + 6;
        float right = w - 8;
        if (note != null) {
            PhoneDraw.rightText(g, note, right, y + 9, PhoneTheme.secondaryText(), 0.6f);
            right -= PhoneDraw.width(note, 0.6f) + 4;
        }
        PhoneDraw.text(g, PhoneDraw.ellipsize(user.name(), right - tx, 0.85f), tx, y + 5, PhoneTheme.text(), 0.85f, false);
        PhoneDraw.text(g, SocialView.tr(online ? "messenger.online" : "messenger.offline"), tx, y + 15,
                online ? PhoneTheme.accent() : PhoneTheme.secondaryText(), 0.6f, false);
        if (action != null) {
            view.hit(0, y, w, H, action);
        }
    }

    /** Строка паблика: аватар, название, подписчики, отметка роли. */
    static void pub(SocialView view, GuiGraphics g, PublicInfo info, int y, int w, int mx, int my) {
        if (PhoneDraw.inside(mx, my, 0, y, w, H)) {
            g.fill(0, y, w, y + H, PhoneTheme.cardHover());
        }
        Avatars.drawPublic(g, info.title(), info.color(), info.avatar(), 8, y + (H - AVATAR) / 2, AVATAR);
        int tx = 8 + AVATAR + 6;
        float right = w - 8;
        if (info.role() != Role.NONE) {
            String note = SocialView.tr(info.role() == Role.OWNER ? "social.role.owner" : "social.role.admin");
            PhoneDraw.rightText(g, note, right, y + 9, PhoneTheme.accent(), 0.6f);
            right -= PhoneDraw.width(note, 0.6f) + 4;
        }
        PhoneDraw.text(g, PhoneDraw.ellipsize(info.title(), right - tx, 0.85f), tx, y + 5, PhoneTheme.text(), 0.85f, false);
        PhoneDraw.text(g, SocialView.tr("social.subscribers", SocialFormat.count(info.subscribers())), tx, y + 15,
                PhoneTheme.secondaryText(), 0.6f, false);
        view.hit(0, y, w, H, () -> view.app.openPublic(info.id()));
    }

    private Rows() {
    }
}
