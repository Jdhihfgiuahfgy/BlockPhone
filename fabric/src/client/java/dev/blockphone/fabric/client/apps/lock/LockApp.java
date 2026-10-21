package dev.blockphone.fabric.client.apps.lock;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneClock;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneNotifications;
import dev.blockphone.fabric.client.phone.PhoneState;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Экран блокировки: часы, дата и центр уведомлений поверх обоев. Нажатие на уведомление открывает его,
 * нажатие в любом другом месте — разблокирует телефон (на последнее открытое приложение).
 */
public final class LockApp extends PhoneApp {
    private static final int CARD_H = 30;
    private static final int LIST_TOP = 74;

    private double scroll;

    public LockApp() {
        super(PhoneState.LOCK);
    }

    @Override
    public boolean hasHeader() {
        return false;
    }

    @Override
    public boolean transparentBackground() {
        return true;
    }

    @Override
    public boolean lightStatusBar() {
        return true;
    }

    @Override
    public void onShown() {
        scroll = 0;
    }

    private int listBottom() {
        return contentHeight() - 24;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int w = contentWidth();
        float cx = w / 2f;
        PhoneClock.drawBig(g, cx, 14);
        PhoneDraw.centeredText(g, PhoneClock.date(), cx, 44, 0xE6FFFFFF, 0.75f, true);

        List<PhoneNotifications.Notice> notices = PhoneNotifications.all();
        if (notices.isEmpty()) {
            PhoneDraw.centeredText(g, PhoneLang.tr("lock.no_notifications"), cx, LIST_TOP + 10, 0xB0FFFFFF, 0.65f, true);
        } else {
            boolean clearHover = PhoneDraw.inside(mouseX, mouseY, w - 50, LIST_TOP - 14, 44, 11);
            PhoneDraw.text(g, PhoneLang.tr("lock.notifications").toUpperCase(PhoneClock.locale()), 8, LIST_TOP - 11, 0xC0FFFFFF, 0.6f, true);
            PhoneDraw.rightText(g, PhoneLang.tr("lock.clear"), w - 8, LIST_TOP - 11, clearHover ? 0xFFFFFFFF : 0xC0FFFFFF, 0.6f);
            int bottom = listBottom();
            double max = Math.max(0, notices.size() * (CARD_H + 4) - (bottom - LIST_TOP));
            scroll = Math.max(0, Math.min(max, scroll));
            g.enableScissor(0, LIST_TOP, w, bottom);
            int y = LIST_TOP - (int) scroll;
            for (PhoneNotifications.Notice n : notices) {
                if (y + CARD_H >= LIST_TOP && y <= bottom) {
                    renderCard(g, n, y, w, PhoneDraw.inside(mouseX, mouseY, 6, y, w - 12, CARD_H) && mouseY >= LIST_TOP && mouseY < bottom);
                }
                y += CARD_H + 4;
            }
            g.disableScissor();
        }
        PhoneDraw.centeredText(g, PhoneLang.tr("lock.unlock_hint"), cx, contentHeight() - 14, 0xC0FFFFFF, 0.65f, true);
        PhoneDraw.roundedRect(g, w / 2 - 20, contentHeight() - 5, w / 2 + 20, contentHeight() - 3, 1, 0xA0FFFFFF);
    }

    private void renderCard(GuiGraphics g, PhoneNotifications.Notice n, int y, int w, boolean hover) {
        PhoneDraw.rawRoundedRect(g, 6, y, w - 6, y + CARD_H, 5, hover ? 0xD0242E44 : 0xB0161D2B);
        int icon = 18;
        if (n.avatar() != null) {
            Avatars.drawUser(g, new UserInfo(n.avatar(), "", false), 11, y + 6, icon);
        } else {
            AppRegistry.Entry entry = AppRegistry.get(n.appId());
            if (entry != null) {
                entry.drawIcon(g, 11, y + 6, icon);
            }
        }
        int tx = 11 + icon + 6;
        String time = PhoneClock.locale() != null ? ago(n.time()) : "";
        PhoneDraw.rightText(g, time, w - 11, y + 5, 0xA0FFFFFF, 0.55f);
        PhoneDraw.text(g, PhoneDraw.ellipsize(n.title(), w - tx - 40, 0.75f), tx, y + 5, 0xFFFFFFFF, 0.75f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(n.text(), w - tx - 14, 0.65f), tx, y + 17, 0xC8DCE4F0, 0.65f, false);
    }

    private static String ago(long time) {
        long minutes = (System.currentTimeMillis() - time) / 60_000;
        if (minutes < 1) {
            return PhoneLang.tr("social.now");
        }
        return minutes < 60 ? PhoneLang.tr("social.minutes", minutes) : PhoneLang.tr("social.hours", minutes / 60);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        int w = contentWidth();
        List<PhoneNotifications.Notice> notices = PhoneNotifications.all();
        if (!notices.isEmpty() && PhoneDraw.inside(x, y, w - 50, LIST_TOP - 14, 44, 11)) {
            PhoneNotifications.clear();
            return true;
        }
        if (y >= LIST_TOP && y < listBottom()) {
            int index = (int) ((y - LIST_TOP + scroll) / (CARD_H + 4));
            double inCard = (y - LIST_TOP + scroll) - index * (CARD_H + 4);
            if (index >= 0 && index < notices.size() && inCard < CARD_H) {
                PhoneNotifications.Notice n = notices.get(index);
                PhoneNotifications.remove(n.key());
                phone.unlock();
                n.open().accept(phone);
                return true;
            }
        }
        phone.unlock();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 20;
        return true;
    }
}
