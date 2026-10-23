package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
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

/**
 * Чаты Telegram, как в официальном клиенте: круглый аватар (зелёная точка — в сети), название, время и галочки своего
 * последнего сообщения, текст или «печатает…», счётчик непрочитанных (серый — чат без звука).
 */
final class TgChatListView extends TgView {
    private static final int ROW_H = 38;
    private static final int AVATAR = 28;

    /** Режим выбора чата («Переслать в…»): заголовок и что сделать с выбранным; null — обычный список. */
    private final String pickTitle;
    private final java.util.function.LongConsumer onPick;

    TgChatListView(TelegramApp app) {
        this(app, null, null);
    }

    TgChatListView(TelegramApp app, String pickTitle, java.util.function.LongConsumer onPick) {
        super(app);
        this.pickTitle = pickTitle;
        this.onPick = onPick;
    }

    @Override
    String title() {
        return pickTitle != null ? pickTitle : "Telegram";
    }

    @Override
    String headerAction() {
        return onPick != null ? null : "⋮";
    }

    @Override
    void onHeaderAction() {
        app.showDialog(new PhoneDialog(tr("telegram.logout_title"), tr("telegram.logout_message"), tr("music.logout"), true,
                Telegram::logOut));
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int bg = TgTheme.background();
        g.fill(0, 0, w, height(), bg);
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
            boolean hover = PhoneDraw.inside(mouseX, mouseY, 0, y, w, ROW_H);
            int rowBg = hover ? TgTheme.cardHover() : bg;
            if (hover) {
                g.fill(0, y, w, y + ROW_H, rowBg);
            }
            int ay = y + (ROW_H - AVATAR) / 2;
            TelegramAvatars.drawRound(g, chat, 6, ay, AVATAR, rowBg);
            if (Telegram.online(chat)) {
                PhoneDraw.rawRoundedRect(g, 6 + AVATAR - 9, ay + AVATAR - 9, 6 + AVATAR + 1, ay + AVATAR + 1, 5, rowBg);
                PhoneDraw.rawRoundedRect(g, 6 + AVATAR - 7, ay + AVATAR - 7, 6 + AVATAR - 1, ay + AVATAR - 1, 3, TgTheme.online());
            }
            int tx = 6 + AVATAR + 7;
            TdApi.Message last = chat.lastMessage;
            String time = last != null ? shortTime(last.date) : "";
            float timeW = PhoneDraw.width(time, 0.6f);
            PhoneDraw.rightText(g, time, w - 6, y + 8, TgTheme.secondaryText(), 0.6f);
            float right = w - 6 - timeW - 3;
            if (last != null && last.isOutgoing) {
                String checks = Telegram.read(last) ? "✓✓" : last.sendingState != null ? "◷" : "✓";
                float cw = PhoneDraw.width(checks, 0.6f);
                PhoneDraw.text(g, checks, right - cw, y + 8, TgTheme.check(), 0.6f, false);
                right -= cw + 2;
            }
            boolean muted = Telegram.muted(chat);
            String title = PhoneDraw.ellipsize(Telegram.title(chat), right - tx - 2, 0.8f);
            PhoneDraw.text(g, title, tx, y + 7, TgTheme.text(), 0.8f, false);
            float badgeW = 0;
            if (chat.unreadCount > 0) {
                String count = chat.unreadCount > 999 ? "999+" : String.valueOf(chat.unreadCount);
                badgeW = Math.max(12, PhoneDraw.width(count, 0.6f) + 7);
                int bx = (int) (w - 6 - badgeW);
                PhoneDraw.rawRoundedRect(g, bx, y + 20, w - 6, y + 31, 5, muted ? TgTheme.badgeMuted() : TgTheme.badge());
                PhoneDraw.centeredText(g, count, bx + badgeW / 2f, y + 22.5f, 0xFFFFFFFF, 0.6f, false);
            }
            String typing = Telegram.typing(chat.id);
            String preview = typing != null ? typing : Telegram.preview(chat);
            PhoneDraw.text(g, PhoneDraw.ellipsize(preview, w - tx - badgeW - 12, 0.7f), tx, y + 22,
                    typing != null ? TgTheme.accent() : TgTheme.secondaryText(), 0.7f, false);
            if (i < chats.size() - 1) {
                g.fill(tx, y + ROW_H - 1, w, y + ROW_H, TgTheme.separator());
            }
            long id = chat.id;
            if (onPick != null) {
                if (Telegram.access(chat) == Telegram.Access.WRITE) {
                    hit(0, y, w, ROW_H, () -> {
                        app.pop();
                        onPick.accept(id);
                    });
                } else {
                    g.fill(0, y, w, y + ROW_H, (bg & 0x00FFFFFF) | 0x90000000);
                }
            } else {
                hit(0, y, w, ROW_H, () -> app.push(new TgChatView(app, id)));
            }
        }
        endList(g);
    }

    /** Сегодня — время, на этой неделе — день, иначе дата. */
    static String shortTime(int unixSeconds) {
        ZonedDateTime t = Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault());
        LocalDate day = t.toLocalDate();
        if (day.equals(LocalDate.now())) {
            return t.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        if (day.isAfter(LocalDate.now().minusDays(7))) {
            return t.format(DateTimeFormatter.ofPattern("EE", dev.blockphone.fabric.client.phone.PhoneClock.locale()));
        }
        return t.format(DateTimeFormatter.ofPattern("dd.MM.yy"));
    }
}
