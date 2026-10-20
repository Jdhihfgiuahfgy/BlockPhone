package dev.blockphone.fabric.client.music;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
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
 * Под ним — полоска звонка (входящий / идущий); она видна всегда, кроме F1, F3, камеры и открытого телефона.
 */
public final class MusicHud {
    private static final int W = 132;
    private static final int H = 30;
    private static final Identifier NOTE = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/music/note.png");
    private static final Identifier CALL = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/voice/call.png");

    public static void init() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "now_playing"), MusicHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || CameraMode.active() || mc.getDebugOverlay().showDebugScreen() || mc.screen instanceof PhoneScreen) {
            return;
        }
        int below = PhoneSettings.get().musicHud ? renderMusic(g, mc) : 4;
        renderCall(g, below);
    }

    /** Полоска звонка: входящий (мигает) или идущий — с длительностью. */
    private static void renderCall(GuiGraphics g, int y) {
        dev.blockphone.common.protocol.data.VoiceData.CallInfo incoming = dev.blockphone.fabric.client.voice.CallClient.incoming();
        dev.blockphone.common.protocol.data.VoiceData.CallInfo mine = dev.blockphone.fabric.client.voice.CallClient.myCall();
        String text;
        if (incoming != null) {
            text = PhoneLang.tr("call.hud_incoming", dev.blockphone.fabric.client.voice.CallClient.title(incoming));
        } else if (mine != null) {
            text = dev.blockphone.fabric.client.voice.CallClient.title(mine) + " · "
                    + dev.blockphone.fabric.client.voice.VoiceMessages.time(dev.blockphone.fabric.client.voice.CallClient.duration());
        } else {
            return;
        }
        int w = Math.min(W, (int) PhoneDraw.width(text, 0.7f) + 24);
        boolean blink = incoming != null && (System.currentTimeMillis() / 400) % 2 == 0;
        int color = blink ? 0xF02F7D4F : 0xE01C4A33;
        PhoneDraw.rawRoundedRect(g, 4, y, 4 + w, y + 14, 4, color);
        PhoneDraw.tinted(g, CALL, 7, y + 3, 8, 8, 32, 32, 0xFFB0F0C8);
        PhoneDraw.text(g, PhoneDraw.ellipsize(text, w - 20, 0.7f), 18, y + 4, 0xFFFFFFFF, 0.7f, false);
    }

    /** Виджет музыки. Возвращает y, с которого можно рисовать дальше. */
    private static int renderMusic(GuiGraphics g, Minecraft mc) {
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
                return 4;
            }
            title = p.title();
            artist = p.artist();
            cover = Covers.url(p.coverUrl());
            progress = p.durationMs() > 0 ? p.position() / (float) p.durationMs() : 0;
            playing = true;
        } else {
            return 4;
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
        return y + H + 4;
    }

    private MusicHud() {
    }
}
