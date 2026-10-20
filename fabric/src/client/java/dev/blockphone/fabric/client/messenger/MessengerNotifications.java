package dev.blockphone.fabric.client.messenger;

import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/** Всплывающие уведомления о новых сообщениях (в правом верхнем углу, как достижения). */
public final class MessengerNotifications {
    private static final long SHOW_MS = 5000;

    public static void notify(ChatInfo chat, MessageInfo message) {
        Minecraft mc = Minecraft.getInstance();
        // Если мессенджер открыт в телефоне — новые сообщения и так видны.
        if (mc.screen instanceof PhoneScreen && "messenger".equals(PhoneState.current().id())) {
            return;
        }
        String title = chat.type() == ChatType.GROUP
                ? message.sender().name() + " → " + MessengerClient.title(chat)
                : message.sender().name();
        mc.getToastManager().addToast(new MessageToast(message, title));
    }

    /** Карточка уведомления в стиле телефона: аватар, кто написал, начало текста. */
    private static final class MessageToast implements Toast {
        private final MessageInfo message;
        private final String title;
        private Visibility visibility = Visibility.HIDE;
        private long firstShown = -1;

        MessageToast(MessageInfo message, String title) {
            this.message = message;
            this.title = title;
        }

        @Override
        public Visibility getWantedVisibility() {
            return visibility;
        }

        @Override
        public void update(ToastManager manager, long time) {
            if (firstShown < 0) {
                firstShown = time;
            }
            double duration = SHOW_MS * manager.getNotificationDisplayTimeMultiplier();
            visibility = time - firstShown < duration ? Visibility.SHOW : Visibility.HIDE;
        }

        @Override
        public void render(GuiGraphics g, Font font, long time) {
            PhoneDraw.rawRoundedRect(g, 0, 0, width(), height(), 4, PhoneTheme.bodyEdge());
            PhoneDraw.rawRoundedRect(g, 1, 1, width() - 1, height() - 1, 3, 0xF0111725);
            g.fill(1, 1, 3, height() - 1, PhoneTheme.accent());
            Avatars.drawUser(g, message.sender(), 7, 7, 18);
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, width() - 36, 1f), 31, 7, 0xFFFFFFFF, 1f, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(MessengerClient.previewText(message), width() - 36, 1f), 31, 18,
                    0xFFB0B0B8, 1f, false);
        }
    }

    private MessengerNotifications() {
    }
}
