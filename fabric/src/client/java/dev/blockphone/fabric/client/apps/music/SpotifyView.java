package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.Accounts;
import dev.blockphone.fabric.client.music.Covers;
import dev.blockphone.fabric.client.music.spotify.Spotify;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;

/**
 * Вкладка «Spotify» — пульт: звук играет сам Spotify (на компьютере или телефоне), здесь — что играет и управление.
 * Подключение: своё приложение в Spotify for Developers (Redirect URI http://127.0.0.1:43219/callback) → Client ID → вход в браузере.
 */
final class SpotifyView extends MusicView {
    private static final int COVER = 96;

    private final TextArea clientId = new TextArea(false, 0.7f);
    private int pendingVolume = -1;
    private boolean volumeDirty;
    private long volumeChangedAt;

    SpotifyView(MusicApp app) {
        super(app);
        clientId.setMaxLength(64);
        clientId.setText(Accounts.get().spotifyClientId);
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        return "Spotify";
    }

    @Override
    String headerAction() {
        return Spotify.status() == Spotify.Status.CONNECTED ? tr("music.logout") : null;
    }

    @Override
    void onHeaderAction() {
        app.showDialog(new PhoneDialog(tr("music.spotify_logout_title"), tr("music.spotify_logout_message"), tr("music.logout"), true,
                Spotify::disconnect));
    }

    @Override
    void onHidden() {
        clientId.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return clientId.isFocused();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        Spotify.watch();
        switch (Spotify.status()) {
            case DISCONNECTED -> renderConnect(g, mouseX, mouseY);
            case AUTHORIZING -> {
                hint(g, tr("music.spotify_waiting"), height() / 2 - 20);
                button(g, width() / 2 - 40, height() / 2 + 16, 80, 15, tr("dialog.cancel"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL,
                        Spotify::disconnect);
            }
            case CONNECTED -> renderRemote(g, mouseX, mouseY);
        }
    }

    private void renderConnect(GuiGraphics g, int mx, int my) {
        int w = width();
        int y = 8;
        PhoneDraw.text(g, tr("music.spotify_connect_title"), 8, y, PhoneTheme.text(), 0.85f, false);
        y += 14;
        y += PhoneDraw.wrapped(g, tr("music.spotify_step1"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.6f, false) + 3;
        button(g, 8, y, w - 16, 14, tr("music.spotify_open_dashboard"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                () -> Util.getPlatform().openUri("https://developer.spotify.com/dashboard"));
        y += 18;
        y += PhoneDraw.wrapped(g, tr("music.spotify_step2"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.6f, false) + 2;
        button(g, 8, y, w - 16, 14, Spotify.REDIRECT_URI, mx, my, PhoneDraw.ButtonStyle.SELECTED,
                () -> Minecraft.getInstance().keyboardHandler.setClipboard(Spotify.REDIRECT_URI));
        y += 18;
        y += PhoneDraw.wrapped(g, tr("music.spotify_step3"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.6f, false) + 3;
        clientId.setBounds(8, y, w - 16, 16);
        clientId.setPlaceholder("Client ID");
        clientId.render(g, mx, my);
        y += 20;
        if (!clientId.text().isBlank()) {
            button(g, 8, y, w - 16, 15, tr("music.spotify_connect"), mx, my, PhoneDraw.ButtonStyle.PRIMARY,
                    () -> Spotify.connect(clientId.text()));
        } else {
            PhoneDraw.button(g, 8, y, w - 16, 15, tr("music.spotify_connect"), -1000, -1000, PhoneDraw.ButtonStyle.NORMAL);
        }
        y += 20;
        PhoneDraw.wrapped(g, tr("music.spotify_note"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.55f, false);
    }

    private void renderRemote(GuiGraphics g, int mx, int my) {
        int w = width();
        Spotify.Playing p = Spotify.playing();
        if (p == null) {
            hint(g, tr(Spotify.nothingPlaying() ? "music.spotify_nothing" : "music.loading"), height() / 2 - 20);
            return;
        }
        int cx = (w - COVER) / 2;
        int y = 8;
        PhotoTextures.Image cover = Covers.url(p.coverUrl());
        if (cover != null) {
            PhoneDraw.texture(g, cover.id(), cx, y, COVER, COVER, cover.width(), cover.height());
        } else {
            PhoneDraw.rawRoundedRect(g, cx, y, cx + COVER, y + COVER, 4, PhoneTheme.card());
            PhoneDraw.tinted(g, NOTE, cx + COVER / 2 - 16, y + COVER / 2 - 16, 32, 32, 32, 32, 0xFF1DB954);
        }
        y += COVER + 8;
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(p.title(), w - 16, 0.9f), w / 2f, y, PhoneTheme.text(), 0.9f, false);
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(p.artist(), w - 16, 0.7f), w / 2f, y + 11, PhoneTheme.secondaryText(), 0.7f, false);
        y += 26;
        long position = p.position();
        bar(g, 12, y, w - 24, p.durationMs() > 0 ? position / (float) p.durationMs() : 0, true);
        slider(12, y, w - 24, 3, v -> Spotify.seek((long) (v * p.durationMs())));
        PhoneDraw.text(g, time(position), 12, y + 6, PhoneTheme.secondaryText(), 0.55f, false);
        PhoneDraw.rightText(g, time(p.durationMs()), w - 12, y + 6, PhoneTheme.secondaryText(), 0.55f);
        y += 24;
        int mid = w / 2;
        int green = 0xFF1DB954;
        iconButton(g, SHUFFLE, mid - 56, y, 11, mx, my, p.shuffle() ? green : PhoneTheme.secondaryText(), Spotify::toggleShuffle);
        iconButton(g, PREV, mid - 28, y, 13, mx, my, PhoneTheme.text(), Spotify::previous);
        PhoneDraw.roundedRect(g, mid - 12, y - 12, mid + 12, y + 12, 12, green);
        PhoneDraw.tinted(g, p.playing() ? PAUSE : PLAY, mid - 7, y - 7, 14, 14, 32, 32, 0xFF000000);
        hit(mid - 12, y - 12, 24, 24, Spotify::togglePlay);
        iconButton(g, NEXT, mid + 28, y, 13, mx, my, PhoneTheme.text(), Spotify::next);
        iconButton(g, "track".equals(p.repeat()) ? REPEAT_ONE : REPEAT, mid + 56, y, 11, mx, my,
                "off".equals(p.repeat()) ? PhoneTheme.secondaryText() : green, Spotify::cycleRepeat);
        y += 22;
        if (p.volume() >= 0) {
            int volume = pendingVolume >= 0 && System.currentTimeMillis() - volumeChangedAt < 3000 ? pendingVolume : p.volume();
            PhoneDraw.tinted(g, VOLUME, 12, y - 4, 9, 9, 32, 32, PhoneTheme.secondaryText());
            bar(g, 26, y, w - 40, volume / 100f, true);
            slider(26, y, w - 40, 3, v -> {
                int percent = Math.round(v * 100);
                if (percent != pendingVolume) {
                    pendingVolume = percent;
                    volumeChangedAt = System.currentTimeMillis();
                    volumeDirty = true;
                }
            });
            y += 12;
        }
        if (!p.device().isEmpty()) {
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(tr("music.spotify_device", p.device()), w - 16, 0.6f), w / 2f, y + 2,
                    0xFF1DB954, 0.6f, false);
        }
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        boolean handled = super.mouseReleased(x, y, button);
        if (volumeDirty) {
            volumeDirty = false;
            Spotify.setVolume(pendingVolume);
            volumeChangedAt = System.currentTimeMillis();
        }
        return clientId.mouseReleased() | handled;
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (Spotify.status() == Spotify.Status.DISCONNECTED && clientId.mouseClicked(x, y, button)) {
            return true;
        }
        clientId.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return clientId.mouseDragged(x, y) || super.mouseDragged(x, y, button);
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        return clientId.isFocused() && clientId.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return clientId.isFocused() && clientId.charTyped(event);
    }
}
