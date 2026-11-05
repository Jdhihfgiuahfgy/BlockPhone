package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.together.ListenTogether;
import dev.blockphone.fabric.client.music.together.TogetherInvites;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Вкладка «Вместе»: совместное прослушивание ({@link ListenTogether}). Без комнаты — создать, войти по коду, приглашения;
 * ведущий — код, что транслируется, «Позвать» игроков с сервера, участники, закрыть; гость — что играет у ведущего,
 * чем слушать (Яндекс Музыка в телефоне / Spotify на ПК), состояние, выйти.
 */
final class TogetherView extends MusicView {
    private static final int ROW = 16;
    private final TextArea codeInput = new TextArea(false, 0.8f);
    private boolean creating;
    private String error;

    TogetherView(MusicApp app) {
        super(app);
        codeInput.setPlaceholder(tr("music.together.code_hint"));
        codeInput.setMaxLength(9);
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        return tr("music.together.title");
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int h = height();
        int top = 0;
        int y = 6 - (int) scroll;
        beginList(g, top, h);
        String notice = ListenTogether.notice();
        if (notice != null) {
            y = message(g, notice, y, PhoneTheme.danger());
        }
        if (error != null) {
            y = message(g, error, y, PhoneTheme.danger());
        }
        switch (ListenTogether.role()) {
            case NONE -> y = renderLobby(g, mouseX, mouseY, w, y);
            case HOST -> y = renderHost(g, mouseX, mouseY, w, y);
            case GUEST -> y = renderGuest(g, mouseX, mouseY, w, y);
        }
        endList(g);
        clampScroll(y + (int) scroll + 6, h);
    }

    private int message(GuiGraphics g, String text, int y, int color) {
        int lines = PhoneDraw.wrappedHeight(text, width() - 20, 0.6f);
        PhoneDraw.wrapped(g, text, 10, y, width() - 20, color, 0.6f, false);
        return y + lines + 6;
    }

    private int button(GuiGraphics g, int x, int y, int w, String label, int mx, int my, PhoneDraw.ButtonStyle style, Runnable action) {
        PhoneDraw.button(g, x, y, w, 14, label, mx, my, style);
        hit(x, y, w, 14, action);
        return y + 18;
    }

    // --- Без комнаты ---

    private int renderLobby(GuiGraphics g, int mx, int my, int w, int y) {
        PhoneDraw.wrapped(g, tr("music.together.about"), 10, y, w - 20, PhoneTheme.secondaryText(), 0.6f, false);
        y += PhoneDraw.wrappedHeight(tr("music.together.about"), w - 20, 0.6f) + 8;
        y = button(g, 10, y, w - 20, creating ? tr("music.loading") : tr("music.together.create"), mx, my, PhoneDraw.ButtonStyle.PRIMARY,
                () -> {
                    if (creating) {
                        return;
                    }
                    creating = true;
                    error = null;
                    ListenTogether.create(err -> {
                        creating = false;
                        error = err != null ? tr(err) : null;
                    });
                });
        y += 6;
        PhoneDraw.text(g, tr("music.together.join_code"), 10, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        codeInput.setBounds(10, y, w - 64, 14);
        codeInput.render(g, mx, my);
        PhoneDraw.button(g, w - 50, y, 40, 14, tr("music.together.join"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
        hit(w - 50, y, 40, 14, this::joinTyped);
        y += 22;
        List<TogetherInvites.Invite> invites = TogetherInvites.invites();
        if (!invites.isEmpty()) {
            PhoneDraw.text(g, tr("music.together.invites"), 10, y, PhoneTheme.secondaryText(), 0.6f, false);
            y += 11;
            for (TogetherInvites.Invite invite : invites) {
                PhoneDraw.card(g, 8, y, w - 8, y + 20, false);
                String who = invite.from().isEmpty() ? "BP-" + invite.code() : tr("music.together.invite_from", invite.from());
                PhoneDraw.text(g, PhoneDraw.ellipsize(who, w - 70, 0.65f), 13, y + 6.5f, PhoneTheme.text(), 0.65f, false);
                PhoneDraw.button(g, w - 52, y + 3, 40, 14, tr("music.together.join"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
                String code = invite.code();
                hit(w - 52, y + 3, 40, 14, () -> {
                    error = null;
                    ListenTogether.join(code);
                });
                y += 24;
            }
        }
        return y;
    }

    private void joinTyped() {
        error = null;
        if (!ListenTogether.join(codeInput.text())) {
            error = tr("music.together.error.bad_code");
        } else {
            codeInput.setText("");
            codeInput.setFocused(false);
        }
    }

    // --- Ведущий ---

    private int renderHost(GuiGraphics g, int mx, int my, int w, int y) {
        PhoneDraw.text(g, tr("music.together.room"), 10, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 9;
        String code = ListenTogether.code();
        PhoneDraw.text(g, code, 10, y, PhoneTheme.accent(), 1.5f, false);
        PhoneDraw.button(g, w - 62, y + 1, 52, 12, tr("music.together.copy"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
        hit(w - 62, y + 1, 52, 12, () -> Minecraft.getInstance().keyboardHandler.setClipboard("BP-" + code));
        y += 18;
        y = link(g, y);
        ListenTogether.Shared s = ListenTogether.shared();
        String now = s.title().isEmpty() ? tr("music.together.host_nothing")
                : tr(s.app().equals("phone") ? "music.together.host_phone" : "music.together.host_pc", s.label(), s.app());
        y = message(g, now, y, s.title().isEmpty() ? PhoneTheme.secondaryText() : PhoneTheme.text());
        y = members(g, w, y);
        List<String> players = TogetherInvites.onlinePlayers();
        PhoneDraw.text(g, tr("music.together.invite_players"), 10, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 11;
        if (players.isEmpty()) {
            y = message(g, tr("music.together.invite_none"), y, PhoneTheme.secondaryText());
        }
        List<String> inRoom = ListenTogether.members();
        for (String name : players) {
            boolean here = inRoom.stream().anyMatch(n -> n.equalsIgnoreCase(name));
            boolean sent = TogetherInvites.invitedRecently(name);
            PhoneDraw.text(g, PhoneDraw.ellipsize(name, w - 74, 0.65f), 12, y + 4, PhoneTheme.text(), 0.65f, false);
            if (here) {
                PhoneDraw.rightText(g, tr("music.together.in_room"), w - 12, y + 4.5f, PhoneTheme.accent(), 0.55f);
            } else {
                PhoneDraw.button(g, w - 58, y, 48, 13, tr(sent ? "music.together.invited_sent" : "music.together.invite"), mx, my,
                        sent ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
                if (!sent) {
                    hit(w - 58, y, 48, 13, () -> TogetherInvites.invite(name));
                }
            }
            y += ROW;
        }
        y += 6;
        return button(g, 10, y, w - 20, tr("music.together.close"), mx, my, PhoneDraw.ButtonStyle.DANGER, ListenTogether::leave);
    }

    // --- Гость ---

    private int renderGuest(GuiGraphics g, int mx, int my, int w, int y) {
        String host = ListenTogether.hostName().isEmpty() ? "…" : ListenTogether.hostName();
        y = message(g, tr("music.together.guest_room", "BP-" + ListenTogether.code(), host), y, PhoneTheme.secondaryText());
        y = link(g, y);
        ListenTogether.Shared s = ListenTogether.shared();
        if (s.title().isEmpty()) {
            y = message(g, tr("music.together.guest_nothing"), y, PhoneTheme.secondaryText());
        } else {
            PhoneDraw.text(g, PhoneDraw.ellipsize(s.title(), w - 20, 0.9f), 10, y, PhoneTheme.text(), 0.9f, false);
            y += 11;
            String sub = s.artist() + (s.playing() ? "" : " · " + tr("music.together.paused"));
            PhoneDraw.text(g, PhoneDraw.ellipsize(sub, w - 20, 0.65f), 10, y, PhoneTheme.secondaryText(), 0.65f, false);
            y += 12;
        }
        PhoneDraw.text(g, tr("music.together.via"), 10, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        ListenTogether.Via via = ListenTogether.via();
        int bw = (w - 24) / 2;
        PhoneDraw.button(g, 10, y, bw, 14, tr("music.together.via_yandex"), mx, my,
                via == ListenTogether.Via.YANDEX ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
        hit(10, y, bw, 14, () -> ListenTogether.setVia("yandex"));
        PhoneDraw.button(g, 14 + bw, y, bw, 14, tr("music.together.via_spotify"), mx, my,
                via == ListenTogether.Via.SPOTIFY ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
        hit(14 + bw, y, bw, 14, () -> ListenTogether.setVia("spotify"));
        y += 20;
        String status = tr("music.together.status." + ListenTogether.guestStatus().name().toLowerCase(java.util.Locale.ROOT));
        y = message(g, status, y, ListenTogether.guestStatus() == ListenTogether.GuestStatus.PLAYING
                || ListenTogether.guestStatus() == ListenTogether.GuestStatus.SPOTIFY_SYNCED ? PhoneTheme.accent() : PhoneTheme.text());
        y = members(g, w, y);
        return button(g, 10, y + 4, w - 20, tr("music.together.leave"), mx, my, PhoneDraw.ButtonStyle.DANGER, ListenTogether::leave);
    }

    private int link(GuiGraphics g, int y) {
        if (ListenTogether.link() != ListenTogether.Link.ONLINE) {
            return message(g, tr(ListenTogether.link() == ListenTogether.Link.CONNECTING ? "music.together.connecting"
                    : "music.together.offline"), y, PhoneTheme.secondaryText());
        }
        return y;
    }

    private int members(GuiGraphics g, int w, int y) {
        List<String> list = ListenTogether.members();
        PhoneDraw.text(g, tr("music.together.members", list.size()), 10, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        String names = String.join(", ", list);
        PhoneDraw.wrapped(g, names, 10, y, w - 20, PhoneTheme.text(), 0.65f, false);
        return y + PhoneDraw.wrappedHeight(names, w - 20, 0.65f) + 8;
    }

    // --- Ввод ---

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (ListenTogether.role() == ListenTogether.Role.NONE && codeInput.mouseClicked(x, y, button)) {
            return true;
        }
        codeInput.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (!codeInput.isFocused()) {
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            joinTyped();
            return true;
        }
        return codeInput.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return codeInput.isFocused() && codeInput.charTyped(event);
    }

    @Override
    boolean capturesKeyboard() {
        return codeInput.isFocused();
    }
}
