package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.ModerationClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.UUID;

/**
 * Переписка чата для модератора — только чтение: автор, время, текст (фото, голосовые и стикеры — подписью).
 * Сверху — «Загрузить раньше», если есть ещё. Открытие пишется в журнал модерации на сервере.
 */
final class ModChatView extends SocialView {
    private final long chatId;
    private final UUID owner;
    /** Прокрутка от низа (новые сообщения внизу). */
    private boolean stickToBottom = true;

    ModChatView(SocialApp app, long chatId, UUID owner) {
        super(app);
        this.chatId = chatId;
        this.owner = owner;
    }

    @Override
    String title() {
        ChatInfo chat = ModerationClient.chat(chatId);
        return chat != null ? ModChatsView.chatTitle(chat, owner) : tr("moderation.chats");
    }

    @Override
    void onShown() {
        if (ModerationClient.history(chatId) == null) {
            ModerationClient.requestChat(chatId, false);
        }
    }

    private int messageHeight(MessageInfo m, int w) {
        return 12 + PhoneDraw.wrappedHeight(ModChatsView.preview(m).isEmpty() ? " " : ModChatsView.preview(m), w - 16, 0.65f) + 4;
    }

    @Override
    void render(GuiGraphics g, int mx, int my) {
        int w = width();
        PhoneDraw.rawRoundedRect(g, 4, 2, w - 4, 14, 3, PhoneTheme.accentTint(0x30));
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(tr("moderation.read_only"), w - 12, 0.55f), w / 2f, 5, PhoneTheme.accent(), 0.55f, false);
        int top = 18;
        List<MessageInfo> messages = ModerationClient.history(chatId);
        if (messages == null) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        if (messages.isEmpty()) {
            hint(g, tr("moderation.no_messages"), height() / 2 - 10);
            return;
        }
        boolean more = ModerationClient.historyHasMore(chatId);
        int total = (more ? 20 : 0) + 4;
        for (MessageInfo m : messages) {
            total += messageHeight(m, w - 8) + 3;
        }
        int viewport = height() - top;
        if (stickToBottom) {
            scroll = Math.max(0, total - viewport);
        }
        clampScroll(total, viewport);
        beginList(g, top, height());
        int y = top - (int) scroll;
        if (more) {
            button(g, 4, y, w - 8, 16, tr("moderation.earlier"), mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> {
                stickToBottom = false;
                ModerationClient.requestChat(chatId, true);
            });
            y += 20;
        }
        for (MessageInfo m : messages) {
            int h = messageHeight(m, w - 8);
            if (y + h >= top && y <= height()) {
                boolean mine = m.sender().uuid().equals(owner);
                PhoneDraw.panel(g, 4, y, w - 4, y + h, mine ? PhoneTheme.accentTint(0x22) : PhoneTheme.card());
                PhoneDraw.text(g, PhoneDraw.ellipsize(m.sender().name(), w - 70, 0.6f), 9, y + 3, mine ? PhoneTheme.accent() : PhoneTheme.text(),
                        0.6f, false);
                PhoneDraw.rightText(g, SocialFormat.dateTime(m.sentAt()), w - 9, y + 3, PhoneTheme.secondaryText(), 0.5f);
                String text = ModChatsView.preview(m);
                PhoneDraw.wrapped(g, text, 9, y + 12, w - 24, m.deleted() ? PhoneTheme.secondaryText() : PhoneTheme.text(), 0.65f, false);
            }
            y += h + 3;
        }
        endList(g);
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        stickToBottom = false;
        return super.mouseScrolled(x, y, amount);
    }
}
