package dev.blockphone.fabric.client.apps.lock;

import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneClock;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneNotifications;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.music.Covers;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Экран блокировки: часы, дата и центр уведомлений поверх обоев. Нажатие на уведомление открывает его,
 * нажатие в любом другом месте — разблокирует телефон (на последнее открытое приложение).
 */
public final class LockApp extends PhoneApp {
    private static final int CARD_H = 30;
    private static final int LIST_TOP = 74;
    /** Карточка музыки: под датой, над уведомлениями. */
    private static final int MUSIC_Y = 58;
    private static final int MUSIC_H = 58;

    private static boolean musicShown() {
        return MusicPlayer.current() != null && MusicPlayer.state() != MusicPlayer.State.STOPPED;
    }

    private int listTop() {
        return musicShown() ? MUSIC_Y + MUSIC_H + 18 : LIST_TOP;
    }

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath(dev.blockphone.common.BlockPhone.MOD_ID, "textures/gui/music/" + name + ".png");
    }

    /** Полоска перемотки внутри карточки. */
    private int barX() {
        return 12;
    }

    private int barW() {
        return contentWidth() - 24;
    }

    private static final int BAR_Y = MUSIC_Y + 28;
    private static final int BUTTONS_Y = MUSIC_Y + 38;
    private long seekPreview = -1;

    /** Как на настоящих телефонах: обложка, название, перемотка, назад / пауза / вперёд. */
    private void renderMusic(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        Track track = MusicPlayer.current();
        PhoneDraw.rawRoundedRect(g, 6, MUSIC_Y, w - 6, MUSIC_Y + MUSIC_H, 6, 0xB0161D2B);
        PhotoTextures.Image cover = Covers.get(track);
        int cs = 22;
        if (cover != null) {
            PhoneDraw.textureCover(g, cover.id(), 12, MUSIC_Y + 5, cs, cs, cover.width(), cover.height());
        } else {
            PhoneDraw.rawRoundedRect(g, 12, MUSIC_Y + 5, 12 + cs, MUSIC_Y + 5 + cs, 3, PhoneTheme.accent());
            PhoneDraw.tinted(g, icon("note"), 16, MUSIC_Y + 9, 14, 14, 32, 32, PhoneTheme.onAccent());
        }
        int tx = 12 + cs + 6;
        PhoneDraw.text(g, PhoneDraw.ellipsize(track.title(), w - tx - 12, 0.75f), tx, MUSIC_Y + 6, 0xFFFFFFFF, 0.75f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(track.artist(), w - tx - 12, 0.6f), tx, MUSIC_Y + 17, 0xC8DCE4F0, 0.6f, false);
        long duration = Math.max(1, MusicPlayer.durationMs());
        long pos = seekPreview >= 0 ? seekPreview : MusicPlayer.positionMs();
        int filled = (int) (barW() * Math.min(1, pos / (float) duration));
        g.fill(barX(), BAR_Y, barX() + barW(), BAR_Y + 2, 0x50FFFFFF);
        g.fill(barX(), BAR_Y, barX() + filled, BAR_Y + 2, 0xFFFFFFFF);
        boolean barHover = PhoneDraw.inside(mouseX, mouseY, barX(), BAR_Y - 3, barW(), 8);
        if (barHover || seekPreview >= 0) {
            PhoneDraw.rawRoundedRect(g, barX() + filled - 2, BAR_Y - 2, barX() + filled + 3, BAR_Y + 4, 2, 0xFFFFFFFF);
        }
        PhoneDraw.text(g, time(pos), barX(), BAR_Y + 4, 0xA0FFFFFF, 0.5f, false);
        PhoneDraw.rightText(g, time(duration), barX() + barW(), BAR_Y + 4, 0xA0FFFFFF, 0.5f);
        String[] buttons = {"prev", MusicPlayer.playing() ? "pause" : "play", "next"};
        for (int i = 0; i < 3; i++) {
            int bx = w / 2 - 8 + (i - 1) * 26;
            boolean hover = PhoneDraw.inside(mouseX, mouseY, bx - 2, BUTTONS_Y - 2, 20, 18);
            if (hover) {
                PhoneDraw.rawRoundedRect(g, bx - 2, BUTTONS_Y - 2, bx + 18, BUTTONS_Y + 16, 4, 0x30FFFFFF);
            }
            PhoneDraw.tinted(g, icon(buttons[i]), bx + 2, BUTTONS_Y, 14, 14, 32, 32, 0xFFFFFFFF);
        }
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    /** Клик по карточке музыки: перемотка или кнопки; true — клик был по карточке. */
    private boolean musicClicked(double x, double y) {
        if (!musicShown() || !PhoneDraw.inside(x, y, 6, MUSIC_Y, contentWidth() - 12, MUSIC_H)) {
            return false;
        }
        if (PhoneDraw.inside(x, y, barX(), BAR_Y - 4, barW(), 9)) {
            seekPreview = seekAt(x);
            return true;
        }
        int w = contentWidth();
        for (int i = 0; i < 3; i++) {
            int bx = w / 2 - 8 + (i - 1) * 26;
            if (PhoneDraw.inside(x, y, bx - 2, BUTTONS_Y - 2, 20, 18)) {
                switch (i) {
                    case 0 -> MusicPlayer.previous();
                    case 1 -> MusicPlayer.toggle();
                    default -> MusicPlayer.next();
                }
            }
        }
        return true;
    }

    private long seekAt(double x) {
        double t = Math.max(0, Math.min(1, (x - barX()) / barW()));
        return (long) (t * Math.max(1, MusicPlayer.durationMs()));
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (seekPreview >= 0) {
            seekPreview = seekAt(x);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (seekPreview >= 0) {
            MusicPlayer.seek(seekPreview);
            seekPreview = -1;
            return true;
        }
        return false;
    }


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
        if (musicShown()) {
            renderMusic(g, mouseX, mouseY);
        }

        List<PhoneNotifications.Notice> notices = PhoneNotifications.all();
        if (notices.isEmpty()) {
            PhoneDraw.centeredText(g, PhoneLang.tr("lock.no_notifications"), cx, listTop() + 10, 0xB0FFFFFF, 0.65f, true);
        } else {
            boolean clearHover = PhoneDraw.inside(mouseX, mouseY, w - 50, listTop() - 14, 44, 11);
            PhoneDraw.text(g, PhoneLang.tr("lock.notifications").toUpperCase(PhoneClock.locale()), 8, listTop() - 11, 0xC0FFFFFF, 0.6f, true);
            PhoneDraw.rightText(g, PhoneLang.tr("lock.clear"), w - 8, listTop() - 11, clearHover ? 0xFFFFFFFF : 0xC0FFFFFF, 0.6f);
            int bottom = listBottom();
            double max = Math.max(0, notices.size() * (CARD_H + 4) - (bottom - listTop()));
            scroll = Math.max(0, Math.min(max, scroll));
            Scrollbar.report(listTop(), bottom, scroll, max, false, v -> scroll = v, () -> scroll);
            g.enableScissor(0, listTop(), w, bottom);
            int y = listTop() - (int) scroll;
            for (PhoneNotifications.Notice n : notices) {
                if (y + CARD_H >= listTop() && y <= bottom) {
                    renderCard(g, n, y, w, PhoneDraw.inside(mouseX, mouseY, 6, y, w - 12, CARD_H) && mouseY >= listTop() && mouseY < bottom);
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
        if (musicClicked(x, y)) {
            return true;
        }
        int w = contentWidth();
        List<PhoneNotifications.Notice> notices = PhoneNotifications.all();
        if (!notices.isEmpty() && PhoneDraw.inside(x, y, w - 50, listTop() - 14, 44, 11)) {
            PhoneNotifications.clear();
            return true;
        }
        if (y >= listTop() && y < listBottom()) {
            int index = (int) ((y - listTop() + scroll) / (CARD_H + 4));
            double inCard = (y - listTop() + scroll) - index * (CARD_H + 4);
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
