package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.telegram.Telegram;
import dev.blockphone.fabric.client.telegram.TelegramAvatars;
import it.tdlight.jni.TdApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** Сведения о собеседнике, группе или канале: аватар, имя, статус, имя пользователя, телефон, «о себе» / описание, уведомления. */
final class TgProfileView extends TgView {
    private final long chatId;

    TgProfileView(TelegramApp app, long chatId) {
        super(app);
        this.chatId = chatId;
    }

    @Override
    String title() {
        TdApi.Chat chat = Telegram.chat(chatId);
        return chat == null ? "" : tr(chat.type instanceof TdApi.ChatTypePrivate ? "telegram.profile.user" : "telegram.profile.group");
    }

    @Override
    int background() {
        return TgTheme.chatBg();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        TdApi.Chat chat = Telegram.chat(chatId);
        if (chat == null) {
            return;
        }
        int w = width();
        int y = 10 - (int) scroll;
        g.fill(0, Math.min(0, y - 10), w, y + 84, TgTheme.background());
        int size = 46;
        TelegramAvatars.drawRound(g, chat, (w - size) / 2, y, size, TgTheme.background());
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(Telegram.title(chat), w - 16, 1f), w / 2f, y + size + 6, TgTheme.text(), 1f, false);
        String status = Telegram.status(chat);
        PhoneDraw.centeredText(g, status, w / 2f, y + size + 18, Telegram.online(chat) ? TgTheme.accent() : TgTheme.secondaryText(), 0.7f, false);
        y += 92;

        List<String[]> rows = new ArrayList<>();
        TdApi.User user = Telegram.peer(chat);
        TdApi.Object info = Telegram.fullInfo(chat);
        if (user != null) {
            if (user.phoneNumber != null && !user.phoneNumber.isEmpty()) {
                rows.add(new String[]{"+" + user.phoneNumber, tr("telegram.profile.phone")});
            }
            if (user.usernames != null && user.usernames.activeUsernames.length > 0) {
                rows.add(new String[]{"@" + user.usernames.activeUsernames[0], tr("telegram.profile.username")});
            }
            if (info instanceof TdApi.UserFullInfo full && full.bio != null && !full.bio.text.isEmpty()) {
                rows.add(new String[]{full.bio.text, tr("telegram.profile.bio")});
            }
        } else {
            TdApi.Supergroup sg = Telegram.supergroup(chat);
            if (sg != null && sg.usernames != null && sg.usernames.activeUsernames.length > 0) {
                rows.add(new String[]{"t.me/" + sg.usernames.activeUsernames[0], tr("telegram.profile.link")});
            }
            String description = info instanceof TdApi.SupergroupFullInfo s ? s.description
                    : info instanceof TdApi.BasicGroupFullInfo b ? b.description : "";
            if (description != null && !description.isEmpty()) {
                rows.add(new String[]{description, tr("telegram.profile.description")});
            }
        }
        if (!rows.isEmpty()) {
            int top = y;
            int inner = w - 24;
            int h = 0;
            for (String[] r : rows) {
                h += PhoneDraw.wrappedHeight(r[0], inner, 0.75f) + 14;
            }
            g.fill(0, top, w, top + h + 6, TgTheme.background());
            y += 4;
            for (String[] r : rows) {
                int th = PhoneDraw.wrapped(g, r[0], 12, y, inner, TgTheme.text(), 0.75f, false);
                PhoneDraw.text(g, r[1], 12, y + th + 1, TgTheme.secondaryText(), 0.6f, false);
                String value = r[0];
                hit(0, y - 2, w, th + 12, () -> Minecraft.getInstance().keyboardHandler.setClipboard(value));
                y += th + 14;
            }
            y += 8;
        }
        boolean muted = Telegram.muted(chat);
        g.fill(0, y, w, y + 22, TgTheme.background());
        PhoneDraw.text(g, tr("telegram.profile.notifications"), 12, y + 7, TgTheme.text(), 0.75f, false);
        PhoneDraw.rightText(g, tr(muted ? "settings.off" : "settings.on"), w - 12, y + 7, TgTheme.accent(), 0.75f);
        hit(0, y, w, 22, () -> Telegram.setMuted(chat, !muted));
        y += 30;
        if (chat.type instanceof TdApi.ChatTypePrivate) {
            hint(g, tr("telegram.profile.copy_hint"), y);
        }
        clampScroll(y + (int) scroll + 20, height());
    }
}
