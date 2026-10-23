package dev.blockphone.fabric.client.social;

import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/** Всплывающие уведомления ленты: лайк, комментарий, подписка, жалоба (модераторам). */
public final class SocialNotifications {
    private static final long SHOW_MS = 5000;

    /** Открыта ли лента в телефоне прямо сейчас. */
    public static boolean feedOpen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen instanceof PhoneScreen && "feed".equals(PhoneState.current().id());
    }

    static void notify(SocialPackets.Notice notice) {
        if (feedOpen()) {
            return;
        }
        String title = switch (notice.type()) {
            case LIKE -> PhoneLang.tr("social.notice.like", notice.actor().name());
            case COMMENT -> PhoneLang.tr("social.notice.comment", notice.actor().name());
            case FOLLOW -> PhoneLang.tr("social.notice.follow", notice.actor().name());
            case REPORT -> PhoneLang.tr("social.notice.report");
            case NEW_POST -> PhoneLang.tr("social.notice.new_post", notice.actor().name());
        };
        String text = switch (notice.type()) {
            case REPORT -> PhoneLang.tr("social.reason." + notice.text());
            case FOLLOW -> PhoneLang.tr("social.notice.follow_text");
            default -> notice.text();
        };
        dev.blockphone.fabric.client.phone.NotifyPrefs.sound("feed");
        if (dev.blockphone.fabric.client.phone.NotifyPrefs.toast("feed")) {
            Minecraft.getInstance().getToastManager().addToast(new NoticeToast(notice, title, text));
        }
        if (!dev.blockphone.fabric.client.phone.NotifyPrefs.center("feed")) {
            return;
        }
        long postId = notice.postId();
        java.util.UUID actor = notice.actor().uuid();
        dev.blockphone.common.protocol.data.SocialData.NoticeType type = notice.type();
        dev.blockphone.fabric.client.phone.PhoneNotifications.add("feed:" + type + ":" + postId + ":" + actor, "messenger", title, text,
                actor, phone -> {
                    phone.openApp(dev.blockphone.fabric.client.apps.hub.HubApp.ID);
                    dev.blockphone.fabric.client.apps.hub.HubApp hub = dev.blockphone.fabric.client.apps.hub.HubApp.get();
                    if (hub != null) {
                        dev.blockphone.fabric.client.apps.social.SocialApp app = hub.social();
                        if (type != dev.blockphone.common.protocol.data.SocialData.NoticeType.REPORT) {
                            hub.showFeed();
                        }
                        switch (type) {
                            case FOLLOW -> app.openProfile(actor);
                            case REPORT -> hub.showModeration();
                            default -> {
                                if (postId > 0) {
                                    app.openPost(postId);
                                }
                            }
                        }
                    }
                });
    }

    private static final class NoticeToast implements Toast {
        private final SocialPackets.Notice notice;
        private final String title;
        private final String text;
        private Visibility visibility = Visibility.HIDE;
        private long firstShown = -1;

        NoticeToast(SocialPackets.Notice notice, String title, String text) {
            this.notice = notice;
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
        public void render(GuiGraphics g, Font font, long time) {
            PhoneDraw.rawRoundedRect(g, 0, 0, width(), height(), 4, PhoneTheme.bodyEdge());
            PhoneDraw.rawRoundedRect(g, 1, 1, width() - 1, height() - 1, 3, 0xF0111725);
            g.fill(1, 1, 3, height() - 1, PhoneTheme.accent());
            Avatars.drawUser(g, notice.actor(), 7, 7, 18);
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, width() - 36, 1f), 31, 7, 0xFFFFFFFF, 1f, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(text.replace('\n', ' '), width() - 36, 1f), 31, 18, 0xFFB0B0B8, 1f, false);
        }
    }

    private SocialNotifications() {
    }
}
