package dev.blockphone.fabric.client.apps.custom;

import dev.blockphone.common.apps.AppDefinition;
import dev.blockphone.fabric.client.phone.NotifyPrefs;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneNotifications;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.resources.Identifier;

/**
 * Уведомления своих приложений ({@code notify(title, text[, tag])}, разрешение notifications): звук, всплывающее окно
 * и центр уведомлений по общим настройкам телефона. Нажатие открывает приложение и вызывает {@code onNotification(tag)}.
 * Ключ настроек — «app.&lt;id&gt;» (можно выключить в списке notifyMuted).
 */
final class AppNotifications {
    private static final long SHOW_MS = 5000;
    private static final int MAX_TITLE = 64;
    private static final int MAX_TEXT = 200;

    static void show(AppDefinition app, String title, String text, String tag) {
        String prefsKey = CustomApps.PREFIX + app.id();
        String shortTitle = cut(title, MAX_TITLE);
        String shortText = cut(text.replace('\n', ' '), MAX_TEXT);
        NotifyPrefs.sound(prefsKey);
        if (NotifyPrefs.toast(prefsKey)) {
            Minecraft.getInstance().getToastManager().addToast(new AppToast(app, shortTitle, shortText));
        }
        if (!NotifyPrefs.center(prefsKey)) {
            return;
        }
        String appId = app.id();
        PhoneNotifications.add(key(app, tag), prefsKey, shortTitle, shortText, null, phone -> {
            phone.openApp(CustomApps.PREFIX + appId);
            if (PhoneState.existing(CustomApps.PREFIX + appId) instanceof CustomApp open) {
                open.notificationClicked(tag);
            }
        });
    }

    static void remove(AppDefinition app, String tag) {
        PhoneNotifications.remove(key(app, tag));
    }

    private static String key(AppDefinition app, String tag) {
        return "app:" + app.id() + ":" + tag;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /** Окошко в правом верхнем углу: значок приложения, заголовок, текст. */
    private static final class AppToast implements Toast {
        private final AppDefinition app;
        private final String title;
        private final String text;
        private Visibility visibility = Visibility.HIDE;
        private long firstShown = -1;

        AppToast(AppDefinition app, String title, String text) {
            this.app = app;
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
            visibility = time - firstShown < SHOW_MS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.SHOW : Visibility.HIDE;
        }

        @Override
        public void render(GuiGraphics g, Font font, long time) { // mc:render — в 26.1 extractRenderState/extractBackground
            PhoneDraw.rawRoundedRect(g, 0, 0, width(), height(), 4, PhoneTheme.bodyEdge());
            PhoneDraw.rawRoundedRect(g, 1, 1, width() - 1, height() - 1, 3, 0xF0111725);
            g.fill(1, 1, 3, height() - 1, PhoneTheme.accent());
            Identifier icon = CustomApps.icon(app);
            if (icon != null) {
                PhoneDraw.texture(g, icon, 7, 7, 18, 18, 64, 64);
            } else {
                PhoneDraw.rawRoundedRect(g, 7, 7, 25, 25, 4, app.color() | 0xFF000000);
            }
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, width() - 36, 1f), 31, 7, 0xFFFFFFFF, 1f, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(text, width() - 36, 1f), 31, 18, 0xFFB0B0B8, 1f, false);
        }
    }

    private AppNotifications() {
    }
}
