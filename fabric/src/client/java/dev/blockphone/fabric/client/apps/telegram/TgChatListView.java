package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.telegram.Telegram;
import dev.blockphone.fabric.client.telegram.TelegramAvatars;
import it.tdlight.jni.TdApi;
import net.minecraft.client.gui.GuiGraphics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Чаты Telegram (основной список, закреплённые сверху): аватар, название, последнее сообщение, время, непрочитанные. */
final class TgChatListView extends TgView {
    private static final int ROW_H = 34;
    private static final int AVATAR = 24;

    TgChatListView(TelegramApp app) {
        super(app);
    }

    @Override
    String title() {
        return "Telegram";
    }

    @Override
    String headerAction() {
        return tr("music.logout");
    }

    @Override
    void onHeaderAction() {
        app.showDialog(new PhoneDialog(tr("telegram.logout_title"), tr("telegram.logout_message"), tr("music.logout"), true,
                Telegram::logOut));
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        List<TdApi.Chat> chats = Telegram.chats();
        if (chats.isEmpty()) {
            hint(g, tr("telegram.loading_chats"), height() / 2 - 10);
            return;
        }
        clampScroll(chats.size() * ROW_H, height());
        beginList(g, 0, height());
        for (int i = 0; i < chats.size(); i++) {
            int y = i * ROW_H - (int) scroll;
            if (y + ROW_H < 0 || y > height()) {
                continue;
            }
            TdApi.Chat chat = chats.get(i);
            if (PhoneDraw.inside(mouseX, mouseY, 0, y, w, ROW_H)) {
                g.fill(0, y, w, y + ROW_H, PhoneTheme.cardHover());
            }
            TelegramAvatars.draw(g, chat, 6, y + (ROW_H - AVATAR) / 2, AVATAR);
            int tx = 6 + AVATAR + 6;
            String time = chat.lastMessage != null ? shortTime(chat.lastMessage.date) : "";
            float timeW = PhoneDraw.width(time, 0.6f);
            PhoneDraw.rightText(g, time, w - 6, y + 7, PhoneTheme.secondaryText(), 0.6f);
            PhoneDraw.text(g, PhoneDraw.ellipsize(Telegram.title(chat), w - tx - timeW - 10, 0.85f), tx, y + 6, PhoneTheme.text(), 0.85f, false);
            float badgeW = 0;
            if (chat.unreadCount > 0) {
                String count = chat.unreadCount > 999 ? "999+" : String.valueOf(chat.unreadCount);
                badgeW = Math.max(10, PhoneDraw.width(count, 0.6f) + 6);
                int bx = (int) (w - 6 - badgeW);
                boolean muted = chat.notificationSettings != null && chat.notificationSettings.muteFor > 0;
                PhoneDraw.roundedRect(g, bx, y + 18, w - 6, y + 28, 5, muted ? PhoneTheme.secondaryText() : 0xFF2AABEE);
                PhoneDraw.centeredText(g, count, bx + badgeW / 2f, y + 20.5f, 0xFFFFFFFF, 0.6f, false);
            }
            PhoneDraw.text(g, PhoneDraw.ellipsize(Telegram.preview(chat), w - tx - badgeW - 12, 0.7f), tx, y + 20, PhoneTheme.secondaryText(),
                    0.7f, false);
            if (i < chats.size() - 1) {
                g.fill(tx, y + ROW_H - 1, w, y + ROW_H, PhoneTheme.separator());
            }
            long id = chat.id;
            hit(0, y, w, ROW_H, () -> app.push(new TgChatView(app, id)));
        }
        endList(g);
    }

    /** Сегодня — время, иначе дата. */
    static String shortTime(int unixSeconds) {
        ZonedDateTime t = Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault());
        return t.toLocalDate().equals(LocalDate.now()) ? t.format(DateTimeFormatter.ofPattern("HH:mm"))
                : t.format(DateTimeFormatter.ofPattern("dd.MM"));
    }
}
