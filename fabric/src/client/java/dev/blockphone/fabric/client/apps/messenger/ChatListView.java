package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Список чатов: аватар, название, превью последнего сообщения, время, непрочитанные. */
final class ChatListView extends MessengerView {
    private static final int ROW_H = 34;
    private static final int AVATAR = 24;

    private double scroll;

    ChatListView(MessengerApp app) {
        super(app);
    }

    @Override
    String title() {
        return MessengerApp.tr("app.messenger");
    }

    @Override
    String headerAction() {
        return "+";
    }

    @Override
    void onHeaderAction() {
        app.push(new NewChatView(app));
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        if (!MessengerClient.loaded()) {
            MessengerApp.centerHint(g, this, MessengerApp.tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        List<ChatInfo> chats = MessengerClient.chats();
        if (chats.isEmpty()) {
            MessengerApp.centerHint(g, this, MessengerApp.tr("messenger.no_chats"), height() / 2 - 20);
            return;
        }
        clampScroll(chats.size());
        int w = width();
        for (int i = 0; i < chats.size(); i++) {
            int y = i * ROW_H - (int) scroll;
            if (y + ROW_H < 0 || y > height()) {
                continue;
            }
            ChatInfo chat = chats.get(i);
            if (PhoneDraw.inside(mouseX, mouseY, 0, y, w, ROW_H)) {
                g.fill(0, y, w, y + ROW_H, PhoneTheme.cardHover());
            }
            int ay = y + (ROW_H - AVATAR) / 2;
            Avatars.drawChat(g, chat, 6, ay, AVATAR);
            UserInfo partner = MessengerClient.partner(chat);
            if (partner != null && MessengerClient.isOnline(partner)) {
                Avatars.drawOnlineDot(g, 6, ay, AVATAR, PhoneTheme.background());
            }

            int textX = 6 + AVATAR + 6;
            String time = chat.lastMessage() != null ? MessengerFormat.shortTime(chat.lastMessage().sentAt()) : "";
            float timeW = PhoneDraw.width(time, 0.6f);
            PhoneDraw.rightText(g, time, w - 6, y + 7, PhoneTheme.secondaryText(), 0.6f);
            String title = PhoneDraw.ellipsize(MessengerClient.title(chat), w - textX - timeW - 10, 0.85f);
            PhoneDraw.text(g, title, textX, y + 6, PhoneTheme.text(), 0.85f, false);

            int unread = chat.unread();
            float badgeW = 0;
            if (unread > 0) {
                String count = unread > 99 ? "99+" : String.valueOf(unread);
                badgeW = Math.max(10, PhoneDraw.width(count, 0.6f) + 6);
                int bx = (int) (w - 6 - badgeW);
                PhoneDraw.roundedRect(g, bx, y + 18, w - 6, y + 28, 5, PhoneTheme.accent());
                PhoneDraw.centeredText(g, count, bx + badgeW / 2f, y + 20.5f, PhoneTheme.onAccent(), 0.6f, false);
            }
            String preview = MessengerFormat.preview(chat.lastMessage(), chat.type() == ChatType.GROUP);
            List<String> typing = MessengerClient.typing(chat.id());
            int previewColor = PhoneTheme.secondaryText();
            if (!typing.isEmpty()) {
                preview = MessengerApp.tr("messenger.typing");
                previewColor = PhoneTheme.accent();
            }
            PhoneDraw.text(g, PhoneDraw.ellipsize(preview, w - textX - badgeW - 12, 0.7f), textX, y + 20, previewColor, 0.7f, false);
            if (i < chats.size() - 1) {
                g.fill(textX, y + ROW_H - 1, w, y + ROW_H, PhoneTheme.separator());
            }
        }
    }

    private void clampScroll(int count) {
        double max = Math.max(0, count * ROW_H - height());
        scroll = Math.max(0, Math.min(max, scroll));
        Scrollbar.report(0, height(), scroll, max, false, v -> scroll = v);
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
        List<ChatInfo> chats = MessengerClient.chats();
        int index = (int) Math.floor((y + scroll) / ROW_H);
        if (index >= 0 && index < chats.size()) {
            app.openChat(chats.get(index).id());
            return true;
        }
        return false;
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 20;
        clampScroll(MessengerClient.chats().size());
        return true;
    }
}
