package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.packet.ModerationPackets;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.ModerationClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Чаты игрока для модератора (право blockphone.moderate.chats): личные и группы; нажатие — переписка (только чтение). */
final class ModChatsView extends SocialView {
    private static final int ROW_H = 30;
    private final UUID user;

    ModChatsView(SocialApp app, UUID user) {
        super(app);
        this.user = user;
    }

    @Override
    String title() {
        ModerationPackets.UserChats chats = ModerationClient.userChats(user);
        return chats != null ? tr("moderation.chats_of", chats.name()) : tr("moderation.chats");
    }

    @Override
    void onShown() {
        if (ModerationClient.userChats(user) == null) {
            ModerationClient.requestUserChats(user);
        }
    }

    /** Название чата для модератора: группа — её название, личный — собеседник(и), кроме самого игрока. */
    static String chatTitle(ChatInfo chat, UUID owner) {
        if (chat.type() == ChatType.GROUP) {
            return chat.title();
        }
        List<String> names = new ArrayList<>();
        for (UserInfo member : chat.members()) {
            if (!member.uuid().equals(owner)) {
                names.add(member.name());
            }
        }
        return names.isEmpty() ? "?" : String.join(", ", names);
    }

    /** Кратко о сообщении: текст или [фото]/[голосовое]/[стикер]. */
    static String preview(MessageInfo m) {
        if (m.deleted()) {
            return tr("moderation.deleted");
        }
        StringBuilder sb = new StringBuilder();
        if (m.sticker() != null) {
            sb.append(tr("moderation.sticker")).append(' ');
        } else if (m.image() != null) {
            sb.append(tr("messenger.photo")).append(' ');
        }
        if (m.voice() != null) {
            sb.append(tr("moderation.voice")).append(' ');
        }
        sb.append(m.text());
        return sb.toString().strip();
    }

    @Override
    void render(GuiGraphics g, int mx, int my) {
        int w = width();
        ModerationPackets.UserChats data = ModerationClient.userChats(user);
        if (data == null) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        PhoneDraw.wrapped(g, tr("moderation.chats_hint"), 8, 4, w - 16, PhoneTheme.secondaryText(), 0.55f, false);
        int top = PhoneDraw.wrappedHeight(tr("moderation.chats_hint"), w - 16, 0.55f) + 8;
        if (data.chats().isEmpty()) {
            hint(g, tr("moderation.no_chats"), top + 20);
            return;
        }
        clampScroll(data.chats().size() * ROW_H + 4, height() - top);
        beginList(g, top, height());
        int y = top - (int) scroll;
        for (ChatInfo chat : data.chats()) {
            if (y + ROW_H >= top && y <= height()) {
                boolean hover = PhoneDraw.inside(mx, my, 4, y, w - 8, ROW_H - 2);
                PhoneDraw.card(g, 4, y, w - 4, y + ROW_H - 2, hover);
                Avatars.drawChat(g, chat, 8, y + 4, 20);
                String title = (chat.type() == ChatType.GROUP ? "◆ " : "") + chatTitle(chat, user);
                PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - 70, 0.7f), 32, y + 5, PhoneTheme.text(), 0.7f, false);
                if (chat.lastMessage() != null) {
                    PhoneDraw.rightText(g, SocialFormat.ago(chat.lastMessage().sentAt()), w - 9, y + 5, PhoneTheme.secondaryText(), 0.5f);
                    String last = chat.lastMessage().sender().name() + ": " + preview(chat.lastMessage());
                    PhoneDraw.text(g, PhoneDraw.ellipsize(last, w - 44, 0.6f), 32, y + 16, PhoneTheme.secondaryText(), 0.6f, false);
                }
                hit(4, y, w - 8, ROW_H - 2, () -> app.push(new ModChatView(app, chat.id(), user)));
            }
            y += ROW_H;
        }
        endList(g);
    }
}
