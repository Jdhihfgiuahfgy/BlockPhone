package dev.blockphone.fabric.client.music;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.music.lyrics.Lyrics;
import dev.blockphone.fabric.client.music.lyrics.LyricsService;
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
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Виджет «сейчас играет» на экране игры. Показывает плеер телефона, а если он молчит — что играет на компьютере (системные медиа Windows).
 * Оформление ({@link #STYLES}), угол экрана, отступы, масштаб, прозрачность, цвет и что показывать — в настройках телефона
 * ({@code PhoneSettings.hud*}). {@link #draw} рисует виджет в точке (0, 0) — им же пользуется предпросмотр в настройках.
 * Полоска звонка (входящий / идущий) — в левом верхнем углу (под виджетом, если он там же); видна всегда, кроме F1, F3,
 * камеры и открытого телефона.
 */
public final class MusicHud {
    public static final List<String> STYLES = List.of("card", "compact", "large", "minimal", "cover");
    public static final List<String> ANCHORS = List.of("top_left", "top_center", "top_right", "middle_left", "middle_right",
            "bottom_left", "bottom_center", "bottom_right");
    public static final List<String> COLORS = List.of("dark", "light", "accent");

    private static final Identifier NOTE = icon("music/note");
    private static final Identifier PLAY = icon("music/play");
    private static final Identifier PAUSE = icon("music/pause");
    private static final Identifier CALL = icon("voice/call");

    /** Что показывать. */
    public record Info(String title, String artist, @Nullable PhotoTextures.Image cover, long positionMs, long durationMs,
                       boolean playing, @Nullable String lyric) {
        float progress() {
            return durationMs > 0 ? Math.max(0, Math.min(1, positionMs / (float) durationMs)) : 0;
        }
    }

    /** Размер нарисованного виджета (без масштаба). */
    public record Size(int w, int h) {
    }

    public static void init() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "now_playing"), MusicHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || CameraMode.active() || mc.getDebugOverlay().showDebugScreen() || mc.screen instanceof PhoneScreen) {
            return;
        }
        PhoneSettings s = PhoneSettings.get();
        int callY = 4;
        Info info = s.musicHud ? current() : null;
        if (info != null && (info.playing() || s.hudShowPaused)) {
            float scale = Math.max(0.5f, Math.min(2f, s.hudScale / 100f));
            Size size = measure(info, s);
            int w = Math.round(size.w() * scale);
            int h = Math.round(size.h() * scale);
            int sw = g.guiWidth();
            int sh = g.guiHeight();
            String anchor = s.hudAnchor;
            int x = anchor.endsWith("left") ? s.hudOffsetX : anchor.endsWith("right") ? sw - w - s.hudOffsetX : (sw - w) / 2 + s.hudOffsetX;
            int y = anchor.startsWith("top") ? s.hudOffsetY : anchor.startsWith("bottom") ? sh - h - s.hudOffsetY : (sh - h) / 2 + s.hudOffsetY;
            x = Math.max(0, Math.min(sw - w, x));
            y = Math.max(0, Math.min(sh - h, y));
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(scale, scale);
            draw(g, info, s);
            g.pose().popMatrix();
            if (anchor.equals("top_left")) {
                callY = y + h + 4;
            }
        }
        renderCall(g, callY);
    }

    /** Текущий трек: плеер телефона, иначе плеер компьютера (Windows); {@code null} — ничего не играет. */
    public static @Nullable Info current() {
        Track track = MusicPlayer.current();
        if (track != null && MusicPlayer.state() != MusicPlayer.State.STOPPED) {
            Lyrics lyrics = LyricsService.current();
            String lyric = null;
            if (lyrics != null) {
                int line = lyrics.lineAt(LyricsService.positionMs());
                lyric = line >= 0 ? lyrics.lines().get(line).text() : null;
            }
            return new Info(track.title(), track.artist(), Covers.get(track), MusicPlayer.positionMs(), MusicPlayer.durationMs(),
                    MusicPlayer.state() != MusicPlayer.State.PAUSED, lyric);
        }
        if (dev.blockphone.fabric.client.music.media.WindowsMedia.supported() && PhoneSettings.get().hudWindowsMedia) {
            // Плеер телефона молчит — то, что играет на компьютере (Spotify, браузер, Яндекс Музыка…).
            dev.blockphone.fabric.client.music.media.WindowsMedia.watch();
            dev.blockphone.fabric.client.music.media.WindowsMedia.Playing p = dev.blockphone.fabric.client.music.media.WindowsMedia.playing();
            if (p != null && (p.playing() || PhoneSettings.get().hudShowPaused)) {
                return new Info(p.title(), p.artist(), p.cover() != null ? Covers.file(p.cover()) : null, p.position(), p.durationMs(),
                        p.playing(), null);
            }
        }
        return null;
    }

    // --- Оформление ---

    public static Size measure(Info info, PhoneSettings s) {
        Size base = switch (s.hudStyle) {
            case "compact" -> new Size(150, 14);
            case "large" -> new Size(176, 52);
            case "minimal" -> new Size(140, s.hudShowArtist ? 22 : 13);
            case "cover" -> new Size(72, 72);
            default -> new Size(132, 30);
        };
        return lyricShown(info, s) ? new Size(base.w(), base.h() + 13) : base;
    }

    private static boolean lyricShown(Info info, PhoneSettings s) {
        return s.hudShowLyrics && info.lyric() != null && !info.lyric().isBlank();
    }

    /** Нарисовать виджет в точке (0, 0) по настройкам. */
    public static void draw(GuiGraphics g, Info info, PhoneSettings s) {
        Size size = measure(info, s);
        boolean lyric = lyricShown(info, s);
        int h = lyric ? size.h() - 13 : size.h();
        Palette p = palette(s);
        switch (s.hudStyle) {
            case "compact" -> compact(g, info, s, p, size.w(), h);
            case "large" -> large(g, info, s, p, size.w(), h);
            case "minimal" -> minimal(g, info, s, p, size.w());
            case "cover" -> cover(g, info, s, p, size.w(), h);
            default -> card(g, info, s, p, size.w(), h);
        }
        if (lyric) {
            int y = h + 2;
            if (!s.hudStyle.equals("minimal")) {
                PhoneDraw.rawRoundedRect(g, 0, y, size.w(), y + 11, 3, p.bg);
            }
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(info.lyric(), size.w() - 8, 0.62f), size.w() / 2f, y + 3,
                    p.accent, 0.62f, s.hudStyle.equals("minimal"));
        }
    }

    private record Palette(int bg, int edge, int text, int sub, int track, int accent, int coverBg) {
    }

    private static Palette palette(PhoneSettings s) {
        int alpha = Math.max(0, Math.min(100, s.hudOpacity)) * 255 / 100 << 24;
        int accent = PhoneTheme.accent();
        return switch (s.hudColor) {
            case "light" -> new Palette(alpha | 0xF2F4F8, alpha | 0xD0D6E0, 0xFF1A2030, 0xFF5A6478, 0xFFD6DCE6, accent, 0xFFE2E6EE);
            case "accent" -> new Palette(alpha | PhoneTheme.mix(0xFF101522, accent, 0.55f) & 0xFFFFFF,
                    alpha | PhoneTheme.mix(0xFFFFFFFF, accent, 0.5f) & 0xFFFFFF, 0xFFFFFFFF, 0xFFDDE3F0, 0x60FFFFFF, 0xFFFFFFFF,
                    0x40000000);
            default -> new Palette(alpha | 0x111725, alpha | PhoneTheme.mix(0xFF2B364C, accent, 0.45f) & 0xFFFFFF, 0xFFFFFFFF,
                    0xFF8A95AB, 0xFF243046, accent, 0xFF1A2232);
        };
    }

    private static void panel(GuiGraphics g, Palette p, int w, int h, int radius) {
        PhoneDraw.rawRoundedRect(g, 0, 0, w, h, radius, p.edge);
        PhoneDraw.rawRoundedRect(g, 1, 1, w - 1, h - 1, Math.max(1, radius - 1), p.bg);
    }

    private static void coverArt(GuiGraphics g, Info info, Palette p, int x, int y, int size) {
        if (info.cover() != null) {
            PhoneDraw.textureCover(g, info.cover().id(), x, y, size, size, info.cover().width(), info.cover().height());
        } else {
            PhoneDraw.rawRoundedRect(g, x, y, x + size, y + size, 3, p.coverBg);
            int icon = Math.max(8, size / 2);
            PhoneDraw.tinted(g, NOTE, x + (size - icon) / 2, y + (size - icon) / 2, icon, icon, 32, 32, p.accent);
        }
    }

    private static void bar(GuiGraphics g, Palette p, int x, int y, int w, int h, float progress) {
        g.fill(x, y, x + w, y + h, p.track);
        g.fill(x, y, x + Math.round(w * progress), y + h, p.accent);
    }

    private static String times(Info info) {
        String pos = time(info.positionMs());
        return info.durationMs() > 0 ? pos + " / " + time(info.durationMs()) : pos;
    }

    private static String time(long ms) {
        long sec = Math.max(0, ms / 1000);
        return sec / 60 + ":" + String.format("%02d", sec % 60);
    }

    /** Карточка: обложка, название, исполнитель, полоска (как раньше). */
    private static void card(GuiGraphics g, Info info, PhoneSettings s, Palette p, int w, int h) {
        panel(g, p, w, h, 4);
        int cs = h - 8;
        int tx = 6;
        if (s.hudShowCover) {
            coverArt(g, info, p, 4, 4, cs);
            tx = cs + 9;
        }
        int tw = w - tx - 5;
        int titleY = s.hudShowArtist ? 6 : 9;
        PhoneDraw.text(g, PhoneDraw.ellipsize(info.title(), tw, 0.75f), tx, titleY, info.playing() ? p.text : p.sub, 0.75f, false);
        float subW = tw;
        if (s.hudShowTime) {
            String t = time(info.positionMs());
            PhoneDraw.rightText(g, t, w - 5, 14, p.sub, 0.55f);
            subW -= PhoneDraw.width(t, 0.55f) + 4;
        }
        if (s.hudShowArtist) {
            PhoneDraw.text(g, PhoneDraw.ellipsize(info.artist(), subW, 0.6f), tx, 14, p.sub, 0.6f, false);
        }
        if (s.hudShowProgress) {
            bar(g, p, tx, h - 7, tw, 2, info.progress());
        }
    }

    /** Строка-«таблетка»: значок, «Название — Исполнитель», время; тонкая полоска снизу. */
    private static void compact(GuiGraphics g, Info info, PhoneSettings s, Palette p, int w, int h) {
        panel(g, p, w, h, h / 2);
        int x = 4;
        if (s.hudShowCover && info.cover() != null) {
            PhoneDraw.textureCover(g, info.cover().id(), 3, 2, h - 4, h - 4, info.cover().width(), info.cover().height());
        } else {
            PhoneDraw.tinted(g, info.playing() ? NOTE : PAUSE, 4, 3, 8, 8, 32, 32, p.accent);
        }
        x += h - 2;
        float right = w - 6;
        if (s.hudShowTime) {
            String t = time(info.positionMs());
            PhoneDraw.rightText(g, t, right, 4.5f, p.sub, 0.6f);
            right -= PhoneDraw.width(t, 0.6f) + 4;
        }
        String text = s.hudShowArtist && !info.artist().isEmpty() ? info.title() + " — " + info.artist() : info.title();
        PhoneDraw.text(g, PhoneDraw.ellipsize(text, right - x, 0.65f), x, 4.5f, info.playing() ? p.text : p.sub, 0.65f, false);
        if (s.hudShowProgress) {
            bar(g, p, h / 2, h - 2, w - h, 1, info.progress());
        }
    }

    /** Большой: крупная обложка, название, исполнитель, полоска со временем и значок паузы. */
    private static void large(GuiGraphics g, Info info, PhoneSettings s, Palette p, int w, int h) {
        panel(g, p, w, h, 5);
        int cs = h - 10;
        int tx = 7;
        if (s.hudShowCover) {
            coverArt(g, info, p, 5, 5, cs);
            tx = cs + 11;
        }
        int tw = w - tx - 18;
        PhoneDraw.text(g, PhoneDraw.ellipsize(info.title(), tw, 0.9f), tx, 7, info.playing() ? p.text : p.sub, 0.9f, false);
        if (s.hudShowArtist) {
            PhoneDraw.text(g, PhoneDraw.ellipsize(info.artist(), tw, 0.7f), tx, 18, p.sub, 0.7f, false);
        }
        PhoneDraw.tinted(g, info.playing() ? PLAY : PAUSE, w - 15, 7, 9, 9, 32, 32, p.accent);
        int barW = w - tx - 7;
        if (s.hudShowProgress) {
            bar(g, p, tx, h - 15, barW, 3, info.progress());
        }
        if (s.hudShowTime) {
            PhoneDraw.text(g, time(info.positionMs()), tx, h - 10, p.sub, 0.55f, false);
            if (info.durationMs() > 0) {
                PhoneDraw.rightText(g, time(info.durationMs()), tx + barW, h - 10, p.sub, 0.55f);
            }
        }
    }

    /** Без подложки: нота, название и исполнитель с тенью, тонкая полоска. */
    private static void minimal(GuiGraphics g, Info info, PhoneSettings s, Palette p, int w) {
        PhoneDraw.tinted(g, info.playing() ? NOTE : PAUSE, 0, 1, 9, 9, 32, 32, p.accent);
        String title = info.title() + (s.hudShowTime ? "  " + times(info) : "");
        PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - 12, 0.75f), 12, 2, 0xFFFFFFFF, 0.75f, true);
        int y = 11;
        if (s.hudShowArtist) {
            PhoneDraw.text(g, PhoneDraw.ellipsize(info.artist(), w - 12, 0.62f), 12, y, 0xFFD0D6E2, 0.62f, true);
            y += 9;
        }
        if (s.hudShowProgress) {
            g.fill(12, y + 1, w, y + 2, 0x60FFFFFF);
            g.fill(12, y + 1, 12 + Math.round((w - 12) * info.progress()), y + 2, p.accent);
        }
    }

    /** Обложка: квадрат с обложкой, снизу затемнение с названием и полоской. */
    private static void cover(GuiGraphics g, Info info, PhoneSettings s, Palette p, int w, int h) {
        panel(g, p, w, h, 5);
        coverArt(g, info, p, 2, 2, w - 4);
        int shade = s.hudShowArtist ? 24 : 16;
        g.fillGradient(2, h - 2 - shade - 6, w - 2, h - 2, 0x00000000, 0xD0000000);
        int y = h - shade - 1;
        PhoneDraw.text(g, PhoneDraw.ellipsize(info.title(), w - 10, 0.62f), 5, y, 0xFFFFFFFF, 0.62f, false);
        if (s.hudShowArtist) {
            PhoneDraw.text(g, PhoneDraw.ellipsize(info.artist(), w - 10, 0.52f), 5, y + 8, 0xFFD0D6E2, 0.52f, false);
        }
        if (s.hudShowTime) {
            PhoneDraw.rightText(g, time(info.positionMs()), w - 5, 5, 0xFFFFFFFF, 0.52f);
        }
        if (!info.playing()) {
            PhoneDraw.tinted(g, PAUSE, 5, 4, 8, 8, 32, 32, 0xFFFFFFFF);
        }
        if (s.hudShowProgress) {
            g.fill(2, h - 4, w - 2, h - 2, 0x60FFFFFF);
            g.fill(2, h - 4, 2 + Math.round((w - 4) * info.progress()), h - 2, p.accent);
        }
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
        int w = Math.min(132, (int) PhoneDraw.width(text, 0.7f) + 24);
        boolean blink = incoming != null && (System.currentTimeMillis() / 400) % 2 == 0;
        int color = blink ? 0xF02F7D4F : 0xE01C4A33;
        PhoneDraw.rawRoundedRect(g, 4, y, 4 + w, y + 14, 4, color);
        PhoneDraw.tinted(g, CALL, 7, y + 3, 8, 8, 32, 32, 0xFFB0F0C8);
        PhoneDraw.text(g, PhoneDraw.ellipsize(text, w - 20, 0.7f), 18, y + 4, 0xFFFFFFFF, 0.7f, false);
    }

    private static Identifier icon(String path) {
        return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/" + path + ".png");
    }

    private MusicHud() {
    }
}
