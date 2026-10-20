package dev.blockphone.fabric.client.music;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.spotify.Spotify;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/**
 * Виджет «сейчас играет» в левом верхнем углу экрана игры: обложка, название, исполнитель, полоска прогресса.
 * Показывает плеер телефона, а если он молчит — Spotify. Выключается в настройках телефона.
 */
public final class MusicHud {
    private static final int W = 132;
    private static final int H = 30;
    private static final Identifier NOTE = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/music/note.png");

    public static void init() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "now_playing"), MusicHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!PhoneSettings.get().musicHud || mc.options.hideGui || CameraMode.active() || mc.getDebugOverlay().showDebugScreen()
                || mc.screen instanceof PhoneScreen) {
            return;
        }
        Track track = MusicPlayer.current();
        String title;
        String artist;
        PhotoTextures.Image cover;
        float progress;
        boolean playing;
        if (track != null && MusicPlayer.state() != MusicPlayer.State.STOPPED) {
            title = track.title();
            artist = track.artist();
            cover = Covers.get(track);
            long duration = MusicPlayer.durationMs();
            progress = duration > 0 ? MusicPlayer.positionMs() / (float) duration : 0;
            playing = MusicPlayer.state() != MusicPlayer.State.PAUSED;
        } else if (Spotify.status() == Spotify.Status.CONNECTED) {
            Spotify.watch();
            Spotify.Playing p = Spotify.playing();
            if (p == null || !p.playing()) {
                return;
            }
            title = p.title();
            artist = p.artist();
            cover = Covers.url(p.coverUrl());
            progress = p.durationMs() > 0 ? p.position() / (float) p.durationMs() : 0;
            playing = true;
        } else {
            return;
        }
        int x = 4;
        int y = 4;
        PhoneDraw.rawRoundedRect(g, x, y, x + W, y + H, 4, PhoneTheme.mix(0xFF2B364C, PhoneTheme.accent(), 0.45f));
        PhoneDraw.rawRoundedRect(g, x + 1, y + 1, x + W - 1, y + H - 1, 3, 0xE0111725);
        int cs = H - 8;
        if (cover != null) {
            PhoneDraw.texture(g, cover.id(), x + 4, y + 4, cs, cs, cover.width(), cover.height());
        } else {
            PhoneDraw.rawRoundedRect(g, x + 4, y + 4, x + 4 + cs, y + 4 + cs, 3, 0xFF1A2232);
            PhoneDraw.tinted(g, NOTE, x + 4 + cs / 2 - 6, y + 4 + cs / 2 - 6, 12, 12, 32, 32, PhoneTheme.accent());
        }
        int tx = x + cs + 9;
        int tw = W - cs - 14;
        PhoneDraw.text(g, PhoneDraw.ellipsize(title, tw, 0.75f), tx, y + 6, playing ? 0xFFFFFFFF : 0xFFB0B8C8, 0.75f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(artist, tw, 0.6f), tx, y + 14, 0xFF8A95AB, 0.6f, false);
        g.fill(tx, y + H - 7, tx + tw, y + H - 5, 0xFF243046);
        g.fill(tx, y + H - 7, tx + Math.round(tw * Math.max(0, Math.min(1, progress))), y + H - 5, PhoneTheme.accent());
    }

    private MusicHud() {
    }
}
