package dev.blockphone.fabric.client.telegram;

import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import it.tdlight.jni.TdApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/** Уведомления о новых сообщениях Telegram (если приложение не открыто). */
final class TelegramNotifications {
    static final int COLOR = 0xFF2AABEE;

    static void notify(TdApi.Chat chat, TdApi.Message message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PhoneScreen && "telegram".equals(PhoneState.current().id())) {
            return;
        }
        // Как в самом Telegram: беззвучные чаты, каналы и группы и архив не уведомляют.
        if (Telegram.muted(chat) || !Telegram.inMain(chat)) {
            return;
        }
        String title = Telegram.title(chat);
        String text = Telegram.text(message).replace('\n', ' ');
        if (!(chat.type instanceof TdApi.ChatTypePrivate)) {
            text = Telegram.senderName(message) + ": " + text;
        }
        if (dev.blockphone.fabric.client.phone.NotifyPrefs.toast("telegram")) {
            mc.getToastManager().addToast(new TelegramToast(chat, title, text));
        }
        if (!dev.blockphone.fabric.client.phone.NotifyPrefs.center("telegram")) {
            return;
        }
        long chatId = chat.id;
        dev.blockphone.fabric.client.phone.PhoneNotifications.add("telegram:" + chatId, "telegram", title, text, null, phone -> {
            phone.openApp("telegram");
            if (PhoneState.app("telegram") instanceof dev.blockphone.fabric.client.apps.telegram.TelegramApp app) {
                app.openChat(chatId);
            }
        });
    }

    private static final class TelegramToast implements Toast {
        private final TdApi.Chat chat;
        private final String title;
        private final String text;
        private Visibility visibility = Visibility.HIDE;
        private long firstShown = -1;

        TelegramToast(TdApi.Chat chat, String title, String text) {
            this.chat = chat;
            this.title = title;
            this.text = text;
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
            visibility = time - firstShown < 5000 * manager.getNotificationDisplayTimeMultiplier() ? Visibility.SHOW : Visibility.HIDE;
        }

        @Override
        public void render(GuiGraphics g, Font font, long time) {
            PhoneDraw.rawRoundedRect(g, 0, 0, width(), height(), 4, PhoneTheme.bodyEdge());
            PhoneDraw.rawRoundedRect(g, 1, 1, width() - 1, height() - 1, 3, 0xF0111725);
            g.fill(1, 1, 3, height() - 1, COLOR);
            TelegramAvatars.draw(g, chat, 7, 7, 18);
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, width() - 36, 1f), 31, 7, 0xFFFFFFFF, 1f, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(text, width() - 36, 1f), 31, 18, 0xFFB0B0B8, 1f, false);
        }
    }

    private TelegramNotifications() {
    }
}
