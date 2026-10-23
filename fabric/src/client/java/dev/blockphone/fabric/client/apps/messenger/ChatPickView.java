package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.Scrollbar;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.LongConsumer;

/** Выбор чата (переслать сообщение, поделиться постом): список своих чатов, нажатие — выбрать и вернуться. */
final class ChatPickView extends MessengerView {
    private static final int ROW_H = 28;
    private static final int AVATAR = 20;

    private final String title;
    private final LongConsumer onPick;
    private double scroll;

    ChatPickView(MessengerApp app, String title, LongConsumer onPick) {
        super(app);
        this.title = title;
        this.onPick = onPick;
    }

    @Override
    String title() {
        return title;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
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
            Avatars.drawChat(g, chat, 6, y + (ROW_H - AVATAR) / 2, AVATAR);
            PhoneDraw.text(g, PhoneDraw.ellipsize(MessengerClient.title(chat), w - 40, 0.8f), 32, y + 10, PhoneTheme.text(), 0.8f, false);
            if (i < chats.size() - 1) {
                g.fill(32, y + ROW_H - 1, w, y + ROW_H, PhoneTheme.separator());
            }
        }
    }

    private void clampScroll(int count) {
        double max = Math.max(0, count * ROW_H - height());
        scroll = Math.max(0, Math.min(max, scroll));
        Scrollbar.report(0, height(), scroll, max, false, v -> scroll = v, () -> scroll);
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
        List<ChatInfo> chats = MessengerClient.chats();
        int index = (int) Math.floor((y + scroll) / ROW_H);
        if (index >= 0 && index < chats.size()) {
            long chatId = chats.get(index).id();
            app.pop();
            onPick.accept(chatId);
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
