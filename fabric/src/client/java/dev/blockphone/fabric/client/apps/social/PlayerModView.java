package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.ModerationData.PlayerStatus;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.ModerationPackets.Action;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.ModerationClient;
import dev.blockphone.fabric.client.ui.PopupMenu;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.UUID;

/**
 * Карточка игрока в панели модерации: состояние (в сети, мут, бан с причинами), причина для нового наказания,
 * «Мут…» и «Бан…» (меню сроков), «Снять мут», «Снять бан», «Чаты игрока» (право читать чаты), «Профиль».
 */
final class PlayerModView extends SocialView {
    /** Сроки в меню: минуты (-1 — навсегда). */
    private static final int[] DURATIONS = {15, 60, 24 * 60, 7 * 24 * 60, 30 * 24 * 60, -1};

    private final UUID user;
    private final String name;
    private final TextArea reason = new TextArea(false, 0.7f);

    PlayerModView(SocialApp app, UUID user, String name) {
        super(app);
        this.user = user;
        this.name = name;
        reason.setMaxLength(100);
    }

    @Override
    String title() {
        PlayerStatus s = ModerationClient.player(user);
        return s != null ? s.user().name() : name;
    }

    @Override
    void onShown() {
        // Свежее состояние: поиск по нику вернёт и этого игрока.
        ModerationClient.search(name);
    }

    @Override
    void onHidden() {
        reason.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return reason.isFocused();
    }

    @Override
    void render(GuiGraphics g, int mx, int my) {
        int w = width();
        PlayerStatus s = ModerationClient.player(user);
        if (s == null) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        int y = 6 - (int) scroll;
        Avatars.drawUser(g, s.user(), 8, y, 28);
        PhoneDraw.text(g, PhoneDraw.ellipsize(s.user().name(), w - 48, 0.95f), 42, y + 3, PhoneTheme.text(), 0.95f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(s.user().online() ? tr("messenger.online")
                : tr("moderation.last_seen", dev.blockphone.fabric.client.social.SocialFormat.ago(s.lastSeen())), w - 48, 0.6f), 42, y + 16,
                PhoneTheme.secondaryText(), 0.6f, false);
        y += 36;

        // Состояние.
        y = statusCard(g, y, w, tr("moderation.mute"), s.mutedUntil(), s.muteReason());
        y = statusCard(g, y, w, tr("moderation.ban"), s.bannedUntil(), s.banReason());

        section(g, tr("moderation.reason"), y + 2);
        y += 12;
        reason.setBounds(6, y, w - 12, 16);
        reason.setPlaceholder(tr("moderation.reason_placeholder"));
        reason.render(g, mx, my);
        y += 22;

        int bw = (w - 16) / 2;
        boolean canMute = ClientSession.has(Rights.MOD_MUTE);
        boolean canBan = ClientSession.has(Rights.MOD_BAN);
        if (canMute) {
            int muteY = y;
            button(g, 6, y, bw, 16, tr("moderation.do_mute"), mx, my, PhoneDraw.ButtonStyle.DANGER,
                    () -> durations(Action.MUTE, 6, muteY + 16));
            if (s.mutedUntil() > 0) {
                button(g, 10 + bw, y, bw, 16, tr("moderation.do_unmute"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                        () -> ModerationClient.punish(user, Action.UNMUTE, 0, reason.text().strip()));
            }
            y += 20;
        }
        if (canBan) {
            int banY = y;
            button(g, 6, y, bw, 16, tr("moderation.do_ban"), mx, my, PhoneDraw.ButtonStyle.DANGER,
                    () -> durations(Action.BAN, 6, banY + 16));
            if (s.bannedUntil() > 0) {
                button(g, 10 + bw, y, bw, 16, tr("moderation.do_unban"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                        () -> ModerationClient.punish(user, Action.UNBAN, 0, reason.text().strip()));
            }
            y += 20;
        }
        if (ClientSession.has(Rights.MOD_CHATS)) {
            button(g, 6, y, w - 12, 16, tr("moderation.chats"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                    () -> app.push(new ModChatsView(app, user)));
            y += 20;
        }
        button(g, 6, y, w - 12, 16, tr("social.profile"), mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> app.openProfile(user));
        y += 22;
        PhoneDraw.wrapped(g, tr("moderation.ban_hint"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.55f, false);
        y += PhoneDraw.wrappedHeight(tr("moderation.ban_hint"), w - 16, 0.55f) + 6;
        clampScroll(y + (int) scroll, height());
    }

    private int statusCard(GuiGraphics g, int y, int w, String label, long until, String why) {
        boolean active = until > 0;
        String text = active ? tr("moderation.until", ModerationView.until(until)) : tr("moderation.no");
        int h = active && !why.isEmpty() ? 26 : 16;
        PhoneDraw.card(g, 6, y, w - 6, y + h, false);
        PhoneDraw.text(g, label, 11, y + 4, PhoneTheme.text(), 0.7f, false);
        PhoneDraw.rightText(g, PhoneDraw.ellipsize(text, w - 70, 0.6f), w - 11, y + 4.5f, active ? PhoneTheme.danger() : PhoneTheme.secondaryText(),
                0.6f);
        if (active && !why.isEmpty()) {
            PhoneDraw.text(g, PhoneDraw.ellipsize(tr("moderation.reason_is", why), w - 22, 0.55f), 11, y + 15, PhoneTheme.secondaryText(),
                    0.55f, false);
        }
        return y + h + 4;
    }

    /** Меню сроков; бан навсегда — с подтверждением. */
    private void durations(Action action, int x, int y) {
        PopupMenu next = new PopupMenu();
        for (int minutes : DURATIONS) {
            String label = tr("moderation.for." + (minutes < 0 ? "forever" : String.valueOf(minutes)));
            Runnable apply = () -> ModerationClient.punish(user, action, minutes, reason.text().strip());
            if (minutes < 0 && action == Action.BAN) {
                next.addDanger(label, () -> app.showDialog(new PhoneDialog(tr("moderation.do_ban"),
                        tr("moderation.ban_forever_confirm", title()), tr("moderation.do_ban"), true, apply)));
            } else {
                next.add(label, apply);
            }
        }
        showMenu(next, x + 80, y);
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (menu != null && menu.isOpen()) {
            return super.mouseClicked(x, y, button);
        }
        if (reason.mouseClicked(x, y, button)) {
            return true;
        }
        reason.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return reason.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return reason.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        return reason.isFocused() && reason.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return reason.isFocused() && reason.charTyped(event);
    }
}
