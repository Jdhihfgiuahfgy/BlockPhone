package dev.blockphone.fabric.client.music;

import com.mojang.blaze3d.platform.InputConstants;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.BlockPhoneClient;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Пульт музыки: отдельный виджет (не телефон). Открыт, пока зажата клавиша {@link BlockPhoneClient#musicRemoteKey}
 * (виден курсор), отпустил — закрылся. Назад / пауза / вперёд, перемотка, громкость, перемешивание и повтор.
 * Место и размер — настройки remoteAnchor / remoteOffsetX / remoteOffsetY / remoteScale (углы как у виджета на HUD).
 */
public final class MusicRemote extends Screen {
    public static final int W = 210;
    public static final int H = 78;
    private static final int BAR_X = 52;
    private static final int BAR_Y = 35;
    private static final int BUTTONS_Y = 53;
    private static final int VOL_X = 148;
    private static final int VOL_Y = 60;
    private static final String[] BUTTONS = {"shuffle", "prev", "play", "next", "repeat"};

    /** 1 — тянем перемотку, 2 — громкость. */
    private int drag;
    private long seekPreview = -1;

    private MusicRemote() {
        super(Component.literal("BlockPhone music"));
    }

    private static boolean debugOpen;

    /** DevShots: показать пульт без клавиши (false — закрыть). */
    public static void debugOpen(Minecraft mc, boolean open) {
        debugOpen = open;
        mc.setScreen(open ? new MusicRemote() : null);
    }

    /** Каждый такт: зажата клавиша — открыть пульт, отпущена — закрыть. */
    public static void tick(Minecraft mc) {
        boolean down = debugOpen || keyDown(mc, BlockPhoneClient.musicRemoteKey);
        if (down && mc.screen == null && mc.player != null && !CameraMode.active()) {
            mc.setScreen(new MusicRemote());
        } else if (!down && mc.screen instanceof MusicRemote) {
            mc.setScreen(null);
        }
    }

    private static boolean keyDown(Minecraft mc, KeyMapping key) {
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(key);
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() != InputConstants.UNKNOWN.getValue()
                && InputConstants.isKeyDown(mc.getWindow(), bound.getValue());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // Мир виден полностью.
    }

    static float scale() {
        return Math.max(0.5f, Math.min(2f, PhoneSettings.get().remoteScale / 100f));
    }

    private int originX() {
        PhoneSettings s = PhoneSettings.get();
        int w = Math.round(W * scale());
        String a = s.remoteAnchor;
        return a.endsWith("left") ? s.remoteOffsetX : a.endsWith("right") ? width - w - s.remoteOffsetX : (width - w) / 2 + s.remoteOffsetX;
    }

    private int originY() {
        PhoneSettings s = PhoneSettings.get();
        int h = Math.round(H * scale());
        String a = s.remoteAnchor;
        return a.startsWith("top") ? s.remoteOffsetY : a.startsWith("bottom") ? height - h - s.remoteOffsetY : (height - h) / 2 + s.remoteOffsetY;
    }

    private double lx(double x) {
        return (x - originX()) / scale();
    }

    private double ly(double y) {
        return (y - originY()) / scale();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.pose().pushMatrix();
        g.pose().translate(originX(), originY());
        g.pose().scale(scale());
        draw(g, (int) Math.floor(lx(mouseX)), (int) Math.floor(ly(mouseY)), seekPreview, drag);
        g.pose().popMatrix();
    }

    /** Что играет: плеер телефона или (если он пуст) Spotify на компьютере. */
    private static dev.blockphone.fabric.client.music.media.WindowsMedia.Playing spotify() {
        if (MusicPlayer.current() != null || !dev.blockphone.fabric.client.music.media.WindowsMedia.supported()) {
            return null;
        }
        dev.blockphone.fabric.client.music.media.WindowsMedia.watch();
        return dev.blockphone.fabric.client.music.media.WindowsMedia.playing();
    }

    private static long durationMs() {
        var sp = spotify();
        return sp != null ? sp.durationMs() : MusicPlayer.durationMs();
    }

    /** Рисует пульт в точке (0, 0) размером W×H (его же показывает предпросмотр в настройках). */
    public static void draw(GuiGraphics g, int mx, int my, long seekPreview, int drag) {
        PhoneDraw.panel(g, 0, 0, W, H, PhoneTheme.background());
        Track track = MusicPlayer.current();
        var sp = spotify();
        if (track == null && sp == null) {
            PhoneDraw.centeredText(g, PhoneLang.tr("music.remote.empty"), W / 2, H / 2 - 8, PhoneTheme.text(), 0.8f, false);
            PhoneDraw.centeredText(g, PhoneLang.tr("music.remote.hint"), W / 2, H / 2 + 4, PhoneTheme.secondaryText(), 0.6f, false);
            return;
        }
        String title = sp != null ? sp.title() : track.title();
        String artist = sp != null ? sp.artist() : track.artist();
        boolean playing = sp != null ? sp.playing() : MusicPlayer.playing();
        boolean shuffle = sp != null ? sp.shuffle() : MusicPlayer.shuffle();
        boolean repeatOne = sp != null ? "Track".equals(sp.repeat()) : MusicPlayer.repeat() == MusicPlayer.Repeat.ONE;
        boolean repeatOn = sp != null ? !"None".equals(sp.repeat()) && !sp.repeat().isEmpty() : MusicPlayer.repeat() != MusicPlayer.Repeat.OFF;
        PhotoTextures.Image cover = sp != null ? (sp.cover() != null ? Covers.file(sp.cover()) : null) : Covers.get(track);
        if (cover != null) {
            PhoneDraw.textureCover(g, cover.id(), 8, 8, 38, 38, cover.width(), cover.height());
        } else {
            PhoneDraw.rawRoundedRect(g, 8, 8, 46, 46, 4, PhoneTheme.accent());
            PhoneDraw.tinted(g, icon("note"), 15, 15, 24, 24, 32, 32, PhoneTheme.onAccent());
        }
        int tw = W - BAR_X - 8;
        PhoneDraw.text(g, PhoneDraw.ellipsize(title, tw, 0.85f), BAR_X, 9, PhoneTheme.text(), 0.85f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(artist, tw, 0.65f), BAR_X, 21, PhoneTheme.secondaryText(), 0.65f, false);

        long duration = Math.max(1, durationMs());
        long pos = seekPreview >= 0 ? seekPreview : sp != null ? sp.position() : MusicPlayer.positionMs();
        int filled = (int) (tw * Math.min(1, pos / (float) duration));
        g.fill(BAR_X, BAR_Y, BAR_X + tw, BAR_Y + 2, PhoneTheme.mix(PhoneTheme.background(), PhoneTheme.text(), 0.2f));
        g.fill(BAR_X, BAR_Y, BAR_X + filled, BAR_Y + 2, PhoneTheme.accent());
        if (drag == 1 || PhoneDraw.inside(mx, my, BAR_X - 2, BAR_Y - 4, tw + 4, 10)) {
            PhoneDraw.rawRoundedRect(g, BAR_X + filled - 2, BAR_Y - 2, BAR_X + filled + 3, BAR_Y + 4, 2, PhoneTheme.accent());
        }
        PhoneDraw.text(g, time(pos), BAR_X, BAR_Y + 5, PhoneTheme.secondaryText(), 0.5f, false);
        PhoneDraw.rightText(g, time(duration), BAR_X + tw, BAR_Y + 5, PhoneTheme.secondaryText(), 0.5f);

        for (int i = 0; i < BUTTONS.length; i++) {
            int bx = buttonX(i);
            boolean hover = PhoneDraw.inside(mx, my, bx - 3, BUTTONS_Y - 3, 22, 20);
            if (hover) {
                PhoneDraw.rawRoundedRect(g, bx - 3, BUTTONS_Y - 3, bx + 19, BUTTONS_Y + 17, 4, PhoneTheme.cardHover());
            }
            String name = BUTTONS[i];
            int color = PhoneTheme.text();
            if (i == 2) {
                name = playing ? "pause" : "play";
                color = PhoneTheme.accent();
            } else if (i == 0) {
                color = shuffle ? PhoneTheme.accent() : PhoneTheme.secondaryText();
            } else if (i == 4) {
                name = repeatOne ? "repeat_one" : "repeat";
                color = repeatOn ? PhoneTheme.accent() : PhoneTheme.secondaryText();
            }
            PhoneDraw.tinted(g, icon(name), bx, BUTTONS_Y, 16, 16, 32, 32, color);
        }

        if (sp != null) {
            // Громкость Spotify меняется в самом Spotify; здесь — подпись источника.
            PhoneDraw.text(g, PhoneLang.tr("music.remote.spotify"), VOL_X - 14, VOL_Y - 2, 0xFF1DB954, 0.6f, false);
            return;
        }
        float volume = PhoneSettings.get().musicVolume;
        int vw = W - VOL_X - 10;
        PhoneDraw.tinted(g, icon("volume"), VOL_X - 14, VOL_Y - 5, 11, 11, 32, 32, PhoneTheme.secondaryText());
        g.fill(VOL_X, VOL_Y, VOL_X + vw, VOL_Y + 2, PhoneTheme.mix(PhoneTheme.background(), PhoneTheme.text(), 0.2f));
        g.fill(VOL_X, VOL_Y, VOL_X + Math.round(vw * volume), VOL_Y + 2, PhoneTheme.accent());
        if (drag == 2 || PhoneDraw.inside(mx, my, VOL_X - 2, VOL_Y - 5, vw + 4, 12)) {
            int vx = VOL_X + Math.round(vw * volume);
            PhoneDraw.rawRoundedRect(g, vx - 2, VOL_Y - 2, vx + 3, VOL_Y + 4, 2, PhoneTheme.accent());
        }
        PhoneDraw.rightText(g, Math.round(volume * 100) + "%", W - 10, VOL_Y + 5, PhoneTheme.secondaryText(), 0.5f);
    }

    private static int buttonX(int i) {
        return 8 + i * 25;
    }

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/music/" + name + ".png");
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = lx(event.x());
        double y = ly(event.y());
        var sp = spotify();
        if (event.button() != 0 || (MusicPlayer.current() == null && sp == null) || !PhoneDraw.inside(x, y, 0, 0, W, H)) {
            return true;
        }
        int tw = W - BAR_X - 8;
        if (PhoneDraw.inside(x, y, BAR_X - 2, BAR_Y - 4, tw + 4, 10)) {
            drag = 1;
            seekPreview = seekAt(x);
            return true;
        }
        if (sp == null && PhoneDraw.inside(x, y, VOL_X - 2, VOL_Y - 5, W - VOL_X - 6, 12)) {
            drag = 2;
            setVolume(x);
            return true;
        }
        for (int i = 0; i < BUTTONS.length; i++) {
            if (PhoneDraw.inside(x, y, buttonX(i) - 3, BUTTONS_Y - 3, 22, 20)) {
                if (sp != null) {
                    switch (i) {
                        case 0 -> dev.blockphone.fabric.client.music.media.WindowsMedia.toggleShuffle();
                        case 1 -> dev.blockphone.fabric.client.music.media.WindowsMedia.previous();
                        case 2 -> dev.blockphone.fabric.client.music.media.WindowsMedia.togglePlay();
                        case 3 -> dev.blockphone.fabric.client.music.media.WindowsMedia.next();
                        default -> dev.blockphone.fabric.client.music.media.WindowsMedia.cycleRepeat();
                    }
                    continue;
                }
                switch (i) {
                    case 0 -> MusicPlayer.setShuffle(!MusicPlayer.shuffle());
                    case 1 -> MusicPlayer.previous();
                    case 2 -> MusicPlayer.toggle();
                    case 3 -> MusicPlayer.next();
                    default -> MusicPlayer.cycleRepeat();
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (drag == 1) {
            seekPreview = seekAt(lx(event.x()));
        } else if (drag == 2) {
            setVolume(lx(event.x()));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        finishDrag();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (spotify() != null) {
            return true;
        }
        PhoneSettings s = PhoneSettings.get();
        s.musicVolume = Math.max(0, Math.min(1, Math.round((s.musicVolume + scrollY * 0.05f) * 20) / 20f));
        return true;
    }

    @Override
    public void removed() {
        finishDrag();
        PhoneSettings.get().save();
    }

    private void finishDrag() {
        if (drag == 1 && seekPreview >= 0) {
            if (spotify() != null) {
                dev.blockphone.fabric.client.music.media.WindowsMedia.seek(seekPreview);
            } else {
                MusicPlayer.seek(seekPreview);
            }
        }
        drag = 0;
        seekPreview = -1;
    }

    private static long seekAt(double x) {
        double t = Math.max(0, Math.min(1, (x - BAR_X) / (W - BAR_X - 8)));
        return (long) (t * Math.max(1, durationMs()));
    }

    private static void setVolume(double x) {
        double t = Math.max(0, Math.min(1, (x - VOL_X) / (W - VOL_X - 10)));
        PhoneSettings.get().musicVolume = Math.round(t * 100) / 100f;
    }
}
