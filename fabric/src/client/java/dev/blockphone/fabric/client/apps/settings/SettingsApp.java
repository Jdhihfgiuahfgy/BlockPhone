package dev.blockphone.fabric.client.apps.settings;

import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.BlockPhoneClient;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.MusicHud;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneClock;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.phone.Wallpapers;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.storage.ClientFiles;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Настройки телефона: главная страница — меню разделов (Основные, Оформление, Уведомления, Музыка, Текст песни, Telegram,
 * Свои приложения, О телефоне); в разделе — строки (ЛКМ — следующее значение, ПКМ — предыдущее) и вложенные страницы
 * (акцентный цвет, обои, виджет музыки). «Назад» — к родительской странице. Все изменения сразу сохраняются в {@link PhoneSettings}.
 */
public final class SettingsApp extends PhoneApp {
    private static final List<String> LANGUAGES = List.of("auto", "ru_ru", "en_us");
    private static final int ROW_H = 20;
    private static final int MARGIN = 6;

    private enum Page { MAIN, GENERAL, LOOK, MUSIC, LYRICS, TELEGRAM, ABOUT, ACCENT, WALLPAPER, HUD, APPS, NOTIFY, SOUND }

    /** Пункт главного меню: страница, цвет плитки, значок, краткое «что внутри». */
    private record Category(Page page, int color, String glyph, String titleKey, Supplier<String> summary) {
    }

    private static Page parent(Page page) {
        return switch (page) {
            case ACCENT, WALLPAPER -> Page.LOOK;
            case HUD -> Page.MUSIC;
            case SOUND -> Page.NOTIFY;
            default -> Page.MAIN;
        };
    }

    /** Строка: подпись, значение, действие (+1 / -1). Без действия — только для чтения. */
    private record Row(Supplier<String> label, Supplier<String> value, IntConsumer action, Integer swatch, boolean opens) {
    }

    private record Section(String titleKey, List<Row> rows) {
    }

    /** Кликабельная область, записанная при отрисовке. */
    private record Hit(int x, int y, int w, int h, IntConsumer action) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final TextArea hex = new TextArea(false, 0.85f);
    private Page page = Page.MAIN;
    private double scroll;
    private boolean hexInvalid;
    private List<PhotoStore.Photo> customWallpapers = List.of();
    private int refreshTicks;

    public SettingsApp() {
        super("settings");
        hex.setMaxLength(7);
    }

    @Override
    public String title() {
        return switch (page) {
            case MAIN -> super.title();
            case GENERAL -> PhoneLang.tr("settings.section.general");
            case LOOK -> PhoneLang.tr("settings.section.appearance");
            case MUSIC -> PhoneLang.tr("settings.section.music");
            case LYRICS -> PhoneLang.tr("settings.section.lyrics");
            case TELEGRAM -> "Telegram";
            case ABOUT -> PhoneLang.tr("settings.section.about");
            case ACCENT -> PhoneLang.tr("settings.accent");
            case WALLPAPER -> PhoneLang.tr("settings.wallpaper");
            case HUD -> PhoneLang.tr("settings.music_hud");
            case APPS -> PhoneLang.tr("settings.apps");
            case NOTIFY -> PhoneLang.tr("settings.notify");
            case SOUND -> PhoneLang.tr("settings.sound." + soundKind.key());
        };
    }

    @Override
    public boolean onBack() {
        if (page != Page.MAIN) {
            open(parent(page));
            return true;
        }
        return false;
    }

    @Override
    public boolean capturesKeyboard() {
        return hex.isFocused();
    }

    @Override
    public void onHidden() {
        hex.setFocused(false);
    }

    /** Открыть страницу настроек по имени: "main", "accent", "wallpaper" (для DevShots). */
    public void openPage(String name) {
        open(Page.valueOf(name.toUpperCase(Locale.ROOT)));
    }

    private void open(Page next) {
        if (page == Page.SOUND && next != Page.SOUND) {
            dev.blockphone.fabric.client.sound.PhoneSounds.stopPreview();
        }
        if (next == Page.SOUND) {
            soundFiles = dev.blockphone.fabric.client.sound.PhoneSounds.files();
        }
        page = next;
        scroll = 0;
        hex.setFocused(false);
        if (next == Page.ACCENT) {
            hex.setText(String.format(Locale.ROOT, "#%06X", PhoneTheme.accent() & 0xFFFFFF));
            hexInvalid = false;
        }
        if (next == Page.WALLPAPER) {
            refreshWallpapers();
        }
        if (next == Page.APPS) {
            dev.blockphone.fabric.client.apps.custom.CustomApps.ensureFolder();
            dev.blockphone.fabric.client.apps.custom.CustomApps.scan(true);
        }
    }

    @Override
    public void tick() {
        if (page == Page.WALLPAPER && ++refreshTicks >= 40) {
            refreshTicks = 0;
            refreshWallpapers();
        }
    }

    private void refreshWallpapers() {
        ClientFiles.IO.execute(() -> {
            List<PhotoStore.Photo> list = Wallpapers.list();
            Minecraft.getInstance().execute(() -> customWallpapers = list);
        });
    }

    private static void save() {
        PhoneSettings.get().save();
    }

    // --- Главное меню и разделы ---

    private List<Category> categories() {
        PhoneSettings s = PhoneSettings.get();
        return List.of(
                new Category(Page.GENERAL, 0xFF8E8E93, "⚙", "settings.section.general",
                        () -> PhoneLang.tr("settings.language." + s.language) + " · "
                                + PhoneLang.tr(s.clock24h ? "settings.clock_format.24" : "settings.clock_format.12")),
                new Category(Page.LOOK, PhoneTheme.accent(), "◐", "settings.section.appearance",
                        () -> PhoneLang.tr(s.darkTheme ? "settings.theme.dark" : "settings.theme.light") + " · " + wallpaperName()),
                new Category(Page.NOTIFY, 0xFFE5534B, "!", "settings.section.notify",
                        () -> PhoneLang.tr(s.notifyAll ? "settings.on" : "settings.off")),
                new Category(Page.MUSIC, 0xFFEE5A8E, "♪", "settings.section.music",
                        () -> Math.round(s.musicVolume * 100) + "% · " + PhoneLang.tr("settings.music_hud") + ": "
                                + PhoneLang.tr(s.musicHud ? "settings.on" : "settings.off")),
                new Category(Page.LYRICS, 0xFFA695E7, "≡", "settings.section.lyrics",
                        () -> s.lyricsWorld ? PhoneLang.tr("settings.lyrics_mode." + s.lyricsMode) : PhoneLang.tr("settings.off")),
                new Category(Page.TELEGRAM, 0xFF2AABEE, "✈", "settings.section.telegram",
                        () -> PhoneLang.tr("settings.telegram.summary")),
                new Category(Page.APPS, 0xFF34C759, "▦", "settings.section.apps",
                        () -> PhoneLang.tr("settings.apps.count", dev.blockphone.fabric.client.apps.custom.CustomApps.all().size())),
                new Category(Page.ABOUT, 0xFF5B6B85, "i", "settings.section.about", () -> BlockPhoneFabric.VERSION));
    }

    /** Главная: плитки-разделы (значок, название, кратко — что выбрано). */
    private int renderMenu(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        int y = 6 - (int) scroll;
        int rowH = 28;
        List<Category> list = categories();
        PhoneDraw.card(g, MARGIN, y, w - MARGIN, y + list.size() * rowH, false);
        for (int i = 0; i < list.size(); i++) {
            Category c = list.get(i);
            int ry = y + i * rowH;
            if (PhoneDraw.inside(mouseX, mouseY, MARGIN, ry, w - 2 * MARGIN, rowH)) {
                g.fill(MARGIN + 1, ry + (i == 0 ? 1 : 0), w - MARGIN - 1, ry + rowH - (i == list.size() - 1 ? 1 : 0), PhoneTheme.cardHover());
            }
            if (i > 0) {
                g.fill(MARGIN + 30, ry, w - MARGIN - 6, ry + 1, PhoneTheme.separator());
            }
            PhoneDraw.rawRoundedRect(g, MARGIN + 6, ry + 5, MARGIN + 24, ry + 23, 4, c.color());
            PhoneDraw.centeredText(g, c.glyph(), MARGIN + 15, ry + 10, 0xFFFFFFFF, 0.9f, false);
            int textW = w - 2 * MARGIN - 30 - 14;
            PhoneDraw.text(g, PhoneDraw.ellipsize(PhoneLang.tr(c.titleKey()), textW, 0.8f), MARGIN + 30, ry + 6, PhoneTheme.text(), 0.8f, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(c.summary().get(), textW, 0.6f), MARGIN + 30, ry + 16, PhoneTheme.secondaryText(), 0.6f, false);
            PhoneDraw.text(g, ">", w - MARGIN - 10, ry + 10, PhoneTheme.secondaryText(), 0.75f, false);
            Page target = c.page();
            hits.add(new Hit(MARGIN, ry, w - 2 * MARGIN, rowH, dir -> open(target)));
        }
        return y + list.size() * rowH + 6;
    }

    private List<Section> pageSections(Page which) {
        PhoneSettings s = PhoneSettings.get();
        List<Section> result = new ArrayList<>();
        switch (which) {
            case GENERAL -> {
                result.add(new Section("settings.section.language_time", List.of(
                        new Row(() -> PhoneLang.tr("settings.language"), () -> PhoneLang.tr("settings.language." + s.language),
                                dir -> s.language = cycle(LANGUAGES, s.language, dir), null, false),
                        new Row(() -> PhoneLang.tr("settings.clock_source"),
                                () -> PhoneLang.tr(s.gameTimeClock ? "settings.clock_source.game" : "settings.clock_source.real"),
                                dir -> s.gameTimeClock = !s.gameTimeClock, null, false),
                        new Row(() -> PhoneLang.tr("settings.clock_format"),
                                () -> PhoneLang.tr(s.clock24h ? "settings.clock_format.24" : "settings.clock_format.12"),
                                dir -> s.clock24h = !s.clock24h, null, false))));
                result.add(new Section("settings.section.control", List.of(
                        new Row(() -> PhoneLang.tr("settings.key"),
                                () -> BlockPhoneClient.openPhoneKey.getTranslatedKeyMessage().getString(), null, null, false),
                        new Row(() -> PhoneLang.tr("settings.compact"),
                                () -> BlockPhoneClient.compactKey.getTranslatedKeyMessage().getString() + " · "
                                        + PhoneLang.tr("settings.compact_cursor", BlockPhoneClient.cursorKey.getTranslatedKeyMessage().getString()),
                                dir -> {
                                    if (phone != null) {
                                        dev.blockphone.fabric.client.phone.PhonePin.toggle(Minecraft.getInstance());
                                    }
                                }, null, false),
                        new Row(() -> PhoneLang.tr("settings.lock_screen"),
                                () -> PhoneLang.tr(s.lockScreen ? "settings.on" : "settings.off"),
                                dir -> s.lockScreen = !s.lockScreen, null, false))));
            }
            case LOOK -> result.add(new Section("settings.section.appearance", List.of(
                    new Row(() -> PhoneLang.tr("settings.theme"),
                            () -> PhoneLang.tr(s.darkTheme ? "settings.theme.dark" : "settings.theme.light"),
                            dir -> s.darkTheme = !s.darkTheme, null, false),
                    new Row(() -> PhoneLang.tr("settings.accent"),
                            () -> String.format(Locale.ROOT, "#%06X", PhoneTheme.accent() & 0xFFFFFF),
                            dir -> open(Page.ACCENT), PhoneTheme.accent(), true),
                    new Row(() -> PhoneLang.tr("settings.wallpaper"), SettingsApp::wallpaperName,
                            dir -> open(Page.WALLPAPER), null, true),
                    new Row(() -> PhoneLang.tr("settings.animations"),
                            () -> PhoneLang.tr(s.animations ? "settings.on" : "settings.off"),
                            dir -> s.animations = !s.animations, null, false))));
            case MUSIC -> {
                result.add(new Section("settings.section.music", List.of(
                        new Row(() -> PhoneLang.tr("settings.music_volume"),
                                () -> Math.round(s.musicVolume * 100) + "%",
                                dir -> s.musicVolume = Math.floorMod(Math.round(s.musicVolume * 10) + dir, 11) / 10f, null, false),
                        new Row(() -> PhoneLang.tr("settings.music_hud"),
                                () -> PhoneLang.tr(s.musicHud ? "settings.hud.style." + s.hudStyle : "settings.off"),
                                dir -> open(Page.HUD), null, true),
                        new Row(() -> PhoneLang.tr("settings.section.lyrics"),
                                () -> s.lyricsWorld ? PhoneLang.tr("settings.lyrics_mode." + s.lyricsMode) : PhoneLang.tr("settings.off"),
                                dir -> open(Page.LYRICS), null, true))));
                result.add(new Section("settings.section.music_keys", List.of(
                        new Row(() -> PhoneLang.tr("settings.music_key_play"),
                                () -> BlockPhoneClient.musicPlayKey.getTranslatedKeyMessage().getString(), null, null, false),
                        new Row(() -> PhoneLang.tr("settings.music_key_next"),
                                () -> BlockPhoneClient.musicNextKey.getTranslatedKeyMessage().getString(), null, null, false))));
                result.add(new Section("settings.section.music_remote", List.of(
                        new Row(() -> PhoneLang.tr("settings.remote.key"),
                                () -> BlockPhoneClient.musicRemoteKey.getTranslatedKeyMessage().getString(), null, null, false),
                        new Row(() -> PhoneLang.tr("settings.hud.anchor"),
                                () -> PhoneLang.tr("settings.hud.anchor." + s.remoteAnchor),
                                dir -> s.remoteAnchor = cycle(MusicHud.ANCHORS, s.remoteAnchor, dir), null, false),
                        new Row(() -> PhoneLang.tr("settings.hud.offset_x"),
                                () -> String.valueOf(s.remoteOffsetX),
                                dir -> s.remoteOffsetX = Math.floorMod(s.remoteOffsetX / 8 + dir, 41) * 8, null, false),
                        new Row(() -> PhoneLang.tr("settings.hud.offset_y"),
                                () -> String.valueOf(s.remoteOffsetY),
                                dir -> s.remoteOffsetY = Math.floorMod(s.remoteOffsetY / 8 + dir, 41) * 8, null, false),
                        new Row(() -> PhoneLang.tr("settings.hud.scale"),
                                () -> s.remoteScale + "%",
                                dir -> s.remoteScale = Math.floorMod((s.remoteScale - 50) / 10 + dir, 16) * 10 + 50, null, false))));
            }
            case LYRICS -> {
                boolean world = !dev.blockphone.fabric.client.music.lyrics.LyricsHud.screenMode(s.lyricsMode);
                result.add(new Section("settings.lyrics.where", List.of(
                        new Row(() -> PhoneLang.tr("settings.lyrics_world"),
                                () -> PhoneLang.tr(s.lyricsWorld ? "settings.on" : "settings.off"),
                                dir -> s.lyricsWorld = !s.lyricsWorld, null, false),
                        new Row(() -> PhoneLang.tr("settings.lyrics_mode"),
                                () -> PhoneLang.tr("settings.lyrics_mode." + s.lyricsMode),
                                dir -> s.lyricsMode = cycle(dev.blockphone.fabric.client.music.lyrics.LyricsHud.MODES, s.lyricsMode, dir),
                                null, false),
                        new Row(() -> PhoneLang.tr("settings.lyrics_size"),
                                () -> PhoneLang.tr("settings.lyrics_size." + s.lyricsSize),
                                dir -> s.lyricsSize = Math.floorMod(s.lyricsSize - 1 + dir, 4) + 1, null, false))));
                List<Row> look = new ArrayList<>();
                if (world) {
                    look.add(new Row(() -> PhoneLang.tr("settings.lyrics_height"),
                            () -> PhoneLang.tr("settings.lyrics_blocks_f", String.format(Locale.ROOT, "%.2f", s.lyricsHeight)),
                            dir -> s.lyricsHeight = Math.floorMod(Math.round(s.lyricsHeight * 4) + dir, 13) / 4f, null, false));
                    look.add(new Row(() -> PhoneLang.tr("settings.lyrics_distance"),
                            () -> PhoneLang.tr("settings.lyrics_blocks", s.lyricsDistance),
                            dir -> s.lyricsDistance = Math.floorMod(s.lyricsDistance - 3 + dir, 28) + 3, null, false));
                    look.add(new Row(() -> PhoneLang.tr("settings.lyrics_scatter"),
                            () -> PhoneLang.tr(s.lyricsScatter ? "settings.on" : "settings.off"),
                            dir -> s.lyricsScatter = !s.lyricsScatter, null, false));
                }
                look.add(new Row(() -> PhoneLang.tr("settings.lyrics_outline"),
                        () -> PhoneLang.tr(s.lyricsOutline ? "settings.on" : "settings.off"),
                        dir -> s.lyricsOutline = !s.lyricsOutline, null, false));
                look.add(new Row(() -> PhoneLang.tr("settings.lyrics_accent"),
                        () -> PhoneLang.tr(s.lyricsAccentWord ? "settings.on" : "settings.off"),
                        dir -> s.lyricsAccentWord = !s.lyricsAccentWord, null, false));
                result.add(new Section("settings.lyrics.look", look));
                result.add(new Section("settings.lyrics.text", List.of(
                        new Row(() -> PhoneLang.tr("settings.lyrics_unsynced"),
                                () -> PhoneLang.tr(s.lyricsUnsynced ? "settings.on" : "settings.off"),
                                dir -> s.lyricsUnsynced = !s.lyricsUnsynced, null, false),
                        new Row(() -> PhoneLang.tr("settings.lyrics_offset"),
                                () -> (s.lyricsOffsetMs > 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f", s.lyricsOffsetMs / 1000f)
                                        + PhoneLang.tr("settings.lyrics_seconds"),
                                dir -> s.lyricsOffsetMs = Math.max(-3000, Math.min(3000, s.lyricsOffsetMs + dir * 250)), null, false),
                        new Row(() -> PhoneLang.tr("settings.lyrics_online"),
                                () -> PhoneLang.tr(s.lyricsOnline ? "settings.on" : "settings.off"),
                                dir -> {
                                    s.lyricsOnline = !s.lyricsOnline;
                                    dev.blockphone.fabric.client.music.lyrics.LyricsService.reload();
                                }, null, false))));
            }
            case TELEGRAM -> result.add(new Section("settings.telegram.video_notes", List.of(
                    toggle("settings.telegram.note_mic", () -> s.videoNoteMic, v -> s.videoNoteMic = v),
                    toggle("settings.telegram.note_music", () -> s.videoNoteMusic, v -> s.videoNoteMusic = v))));
            case ABOUT -> {
                ServerHelloPacket hello = ClientNetwork.serverHello();
                String server = hello == null ? PhoneLang.tr("settings.server.none") : PhoneLang.tr("settings.server.connected", hello.serverPlatform());
                result.add(new Section("settings.section.about", List.of(
                        new Row(() -> PhoneLang.tr("settings.version"), () -> BlockPhoneFabric.VERSION, null, null, false),
                        new Row(() -> PhoneLang.tr("settings.server"), () -> server, null, null, false),
                        new Row(() -> PhoneLang.tr("settings.signal"),
                                () -> dev.blockphone.fabric.client.phone.PhoneScreen.signalBars() + "/4", null, null, false))));
            }
            default -> {
            }
        }
        return result;
    }

    private static String wallpaperName() {
        PhoneSettings s = PhoneSettings.get();
        if (s.customWallpaper != null) {
            return PhoneLang.tr("settings.wallpaper.custom");
        }
        return PhoneLang.tr("wallpaper." + Math.floorMod(s.wallpaper, PhoneTheme.WALLPAPERS.length));
    }

    private static String cycle(List<String> values, String current, int dir) {
        int index = values.indexOf(current);
        return values.get(Math.floorMod(index + dir, values.size()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        hits.clear();
        int bottom = switch (page) {
            case MAIN -> renderMenu(g, mouseX, mouseY);
            case GENERAL, LOOK, MUSIC, LYRICS, TELEGRAM, ABOUT -> renderSections(g, mouseX, mouseY, pageSections(page), 4 - (int) scroll);
            case ACCENT -> renderAccent(g, mouseX, mouseY);
            case WALLPAPER -> renderWallpapers(g, mouseX, mouseY);
            case HUD -> renderHud(g, mouseX, mouseY);
            case APPS -> renderApps(g, mouseX, mouseY);
            case NOTIFY -> renderSections(g, mouseX, mouseY, notifySections(), 4 - (int) scroll);
            case SOUND -> renderSounds(g, mouseX, mouseY);
        };
        double max = Math.max(0, bottom + (int) scroll - contentHeight() + 6);
        scroll = Math.max(0, Math.min(max, scroll));
        Scrollbar.report(0, contentHeight(), scroll, max, false, v -> scroll = v);
    }

    // --- Виджет музыки ---

    private int renderHud(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        int y = 6 - (int) scroll;
        // Предпросмотр: «экран игры» в пропорциях 16:9 с виджетом в выбранном месте и масштабе.
        int pw = w - 2 * MARGIN;
        int ph = pw * 9 / 16;
        PhoneDraw.rawRoundedRect(g, MARGIN, y, MARGIN + pw, y + ph, 4, 0xFF5B8C3E);
        g.fillGradient(MARGIN, y, MARGIN + pw, y + ph * 2 / 3, 0xFF7DB4F0, 0xFFB8D8F8);
        g.fill(MARGIN + pw / 2 - 30, y + ph - 9, MARGIN + pw / 2 + 30, y + ph - 3, 0xA0202020);
        PhoneSettings s = PhoneSettings.get();
        MusicHud.Info info = MusicHud.current();
        if (info == null) {
            info = new MusicHud.Info(PhoneLang.tr("settings.hud.sample_title"), PhoneLang.tr("settings.hud.sample_artist"), null,
                    83_000, 215_000, true, PhoneLang.tr("settings.hud.sample_lyric"));
        }
        if (s.musicHud) {
            // Экран игры ≈ 427×240 интерфейсных пикселей → уменьшаем до ширины предпросмотра.
            float k = pw / 427f;
            float scale = k * Math.max(0.5f, Math.min(2f, s.hudScale / 100f));
            MusicHud.Size size = MusicHud.measure(info, s);
            float ww = size.w() * scale;
            float hh = size.h() * scale;
            String a = s.hudAnchor;
            float x = a.endsWith("left") ? s.hudOffsetX * k : a.endsWith("right") ? pw - ww - s.hudOffsetX * k : (pw - ww) / 2 + s.hudOffsetX * k;
            float yy = a.startsWith("top") ? s.hudOffsetY * k : a.startsWith("bottom") ? ph - hh - s.hudOffsetY * k : (ph - hh) / 2 + s.hudOffsetY * k;
            x = Math.max(0, Math.min(pw - ww, x));
            yy = Math.max(0, Math.min(ph - hh, yy));
            g.enableScissor(MARGIN, y, MARGIN + pw, y + ph);
            g.pose().pushMatrix();
            g.pose().translate(MARGIN + x, y + yy);
            g.pose().scale(scale, scale);
            MusicHud.draw(g, info, s);
            g.pose().popMatrix();
            g.disableScissor();
        }
        return renderSections(g, mouseX, mouseY, hudSections(), y + ph + 8);
    }

    private List<Section> hudSections() {
        PhoneSettings s = PhoneSettings.get();
        List<Section> result = new ArrayList<>();
        result.add(new Section("settings.hud.look", List.of(
                new Row(() -> PhoneLang.tr("settings.music_hud"),
                        () -> PhoneLang.tr(s.musicHud ? "settings.on" : "settings.off"),
                        dir -> s.musicHud = !s.musicHud, null, false),
                new Row(() -> PhoneLang.tr("settings.hud.style"),
                        () -> PhoneLang.tr("settings.hud.style." + s.hudStyle),
                        dir -> s.hudStyle = cycle(MusicHud.STYLES, s.hudStyle, dir), null, false),
                new Row(() -> PhoneLang.tr("settings.hud.color"),
                        () -> PhoneLang.tr("settings.hud.color." + s.hudColor),
                        dir -> s.hudColor = cycle(MusicHud.COLORS, s.hudColor, dir), null, false),
                new Row(() -> PhoneLang.tr("settings.hud.opacity"),
                        () -> s.hudOpacity + "%",
                        dir -> s.hudOpacity = Math.floorMod(s.hudOpacity / 10 + dir, 11) * 10, null, false),
                new Row(() -> PhoneLang.tr("settings.hud.scale"),
                        () -> s.hudScale + "%",
                        dir -> s.hudScale = Math.floorMod((s.hudScale - 50) / 10 + dir, 16) * 10 + 50, null, false))));
        result.add(new Section("settings.hud.place", List.of(
                new Row(() -> PhoneLang.tr("settings.hud.anchor"),
                        () -> PhoneLang.tr("settings.hud.anchor." + s.hudAnchor),
                        dir -> s.hudAnchor = cycle(MusicHud.ANCHORS, s.hudAnchor, dir), null, false),
                new Row(() -> PhoneLang.tr("settings.hud.offset_x"),
                        () -> String.valueOf(s.hudOffsetX),
                        dir -> s.hudOffsetX = Math.floorMod(s.hudOffsetX / 4 + dir, 31) * 4, null, false),
                new Row(() -> PhoneLang.tr("settings.hud.offset_y"),
                        () -> String.valueOf(s.hudOffsetY),
                        dir -> s.hudOffsetY = Math.floorMod(s.hudOffsetY / 4 + dir, 31) * 4, null, false))));
        result.add(new Section("settings.hud.show", List.of(
                toggle("settings.hud.cover", () -> s.hudShowCover, v -> s.hudShowCover = v),
                toggle("settings.hud.artist", () -> s.hudShowArtist, v -> s.hudShowArtist = v),
                toggle("settings.hud.progress", () -> s.hudShowProgress, v -> s.hudShowProgress = v),
                toggle("settings.hud.time", () -> s.hudShowTime, v -> s.hudShowTime = v),
                toggle("settings.hud.lyrics", () -> s.hudShowLyrics, v -> s.hudShowLyrics = v),
                toggle("settings.hud.paused", () -> s.hudShowPaused, v -> s.hudShowPaused = v))));
        result.add(new Section("settings.hud.reset_section", List.of(
                new Row(() -> PhoneLang.tr("settings.hud.reset"), () -> "", dir -> resetHud(s), null, false))));
        return result;
    }

    private List<Section> notifySections() {
        PhoneSettings s = PhoneSettings.get();
        List<Section> result = new ArrayList<>();
        result.add(new Section("settings.notify.general", List.of(
                toggle("settings.notify.all", () -> s.notifyAll, v -> s.notifyAll = v),
                toggle("settings.notify.toasts", () -> s.notifyToasts, v -> s.notifyToasts = v),
                toggle("settings.notify.center", () -> s.notifyCenter, v -> s.notifyCenter = v),
                toggle("settings.notify.ring", () -> s.notifySound, v -> s.notifySound = v))));
        List<Row> apps = new ArrayList<>();
        for (String app : dev.blockphone.fabric.client.phone.NotifyPrefs.APPS) {
            apps.add(new Row(() -> PhoneLang.tr("settings.notify.app." + app),
                    () -> PhoneLang.tr(!s.notifyAll ? "settings.notify.all_off"
                            : dev.blockphone.fabric.client.phone.NotifyPrefs.appEnabled(app) ? "settings.on" : "settings.off"),
                    dir -> dev.blockphone.fabric.client.phone.NotifyPrefs.toggleApp(app), null, false));
        }
        result.add(new Section("settings.notify.apps", apps));
        List<Row> sounds = new ArrayList<>();
        for (dev.blockphone.fabric.client.sound.PhoneSounds.Kind kind : dev.blockphone.fabric.client.sound.PhoneSounds.Kind.values()) {
            sounds.add(new Row(() -> PhoneLang.tr("settings.sound." + kind.key()),
                    () -> dev.blockphone.fabric.client.sound.PhoneSounds.label(dev.blockphone.fabric.client.sound.PhoneSounds.choice(kind)),
                    dir -> {
                        soundKind = kind;
                        open(Page.SOUND);
                    }, null, true));
        }
        sounds.add(new Row(() -> PhoneLang.tr("settings.sound.volume"), () -> s.soundVolume + "%",
                dir -> s.soundVolume = Math.floorMod(s.soundVolume / 10 + dir, 11) * 10, null, false));
        result.add(new Section("settings.sound.section", sounds));
        return result;
    }

    private static Row toggle(String key, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
        return new Row(() -> PhoneLang.tr(key), () -> PhoneLang.tr(get.getAsBoolean() ? "settings.on" : "settings.off"),
                dir -> set.accept(!get.getAsBoolean()), null, false);
    }

    private static void resetHud(PhoneSettings s) {
        PhoneSettings d = new PhoneSettings();
        s.hudStyle = d.hudStyle;
        s.hudAnchor = d.hudAnchor;
        s.hudOffsetX = d.hudOffsetX;
        s.hudOffsetY = d.hudOffsetY;
        s.hudScale = d.hudScale;
        s.hudOpacity = d.hudOpacity;
        s.hudColor = d.hudColor;
        s.hudShowCover = d.hudShowCover;
        s.hudShowArtist = d.hudShowArtist;
        s.hudShowProgress = d.hudShowProgress;
        s.hudShowTime = d.hudShowTime;
        s.hudShowLyrics = d.hudShowLyrics;
        s.hudShowPaused = d.hudShowPaused;
    }

    private int renderSections(GuiGraphics g, int mouseX, int mouseY, List<Section> list, int startY) {
        int w = contentWidth();
        int y = startY;
        for (Section section : list) {
            PhoneDraw.text(g, PhoneLang.tr(section.titleKey()).toUpperCase(PhoneClock.locale()), MARGIN + 2, y + 3,
                    PhoneTheme.secondaryText(), 0.6f, false);
            y += 12;
            int top = y;
            int bottom = y + section.rows().size() * ROW_H;
            PhoneDraw.card(g, MARGIN, top, w - MARGIN, bottom, false);
            for (int i = 0; i < section.rows().size(); i++) {
                Row row = section.rows().get(i);
                int ry = top + i * ROW_H;
                boolean hovered = row.action() != null && PhoneDraw.inside(mouseX, mouseY, MARGIN, ry, w - 2 * MARGIN, ROW_H);
                if (hovered) {
                    g.fill(MARGIN + 1, ry + (i == 0 ? 1 : 0), w - MARGIN - 1, ry + ROW_H - (i == section.rows().size() - 1 ? 1 : 0),
                            PhoneTheme.cardHover());
                }
                if (i > 0) {
                    g.fill(MARGIN + 6, ry, w - MARGIN - 6, ry + 1, PhoneTheme.separator());
                }
                int inner = w - 2 * MARGIN - 12;
                String label = PhoneDraw.ellipsize(row.label().get(), inner * 0.55f, 0.75f);
                PhoneDraw.text(g, label, MARGIN + 6, ry + 7, PhoneTheme.text(), 0.75f, false);
                int valueRight = w - MARGIN - 6 - (row.opens() ? 8 : 0);
                if (row.opens()) {
                    PhoneDraw.text(g, ">", w - MARGIN - 10, ry + 6.5f, PhoneTheme.secondaryText(), 0.75f, false);
                }
                int valueColor = row.action() != null ? PhoneTheme.accent() : PhoneTheme.secondaryText();
                float valueMax = inner - PhoneDraw.width(label, 0.75f) - 8 - (row.swatch() != null ? 12 : 0) - (row.opens() ? 8 : 0);
                String value = PhoneDraw.ellipsize(row.value().get(), valueMax, 0.75f);
                PhoneDraw.rightText(g, value, valueRight, ry + 7, valueColor, 0.75f);
                if (row.swatch() != null) {
                    int sx = (int) (valueRight - PhoneDraw.width(value, 0.75f)) - 12;
                    PhoneDraw.rawRoundedRect(g, sx, ry + 5, sx + 9, ry + 14, 2, row.swatch());
                }
                if (row.action() != null) {
                    IntConsumer action = row.action();
                    hits.add(new Hit(MARGIN, ry, w - 2 * MARGIN, ROW_H, dir -> {
                        action.accept(dir);
                        save();
                    }));
                }
            }
            y = bottom + 8;
        }
        return y;
    }

    // --- Акцентный цвет ---

    private int renderAccent(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        int y = 6 - (int) scroll;

        // Предпросмотр: «таблетка»-градиент, кнопки, вкладка.
        PhoneDraw.card(g, MARGIN, y, w - MARGIN, y + 52, false);
        PhoneDraw.gradientPill(g, MARGIN + 6, y + 6, w - MARGIN - 6, y + 20, PhoneTheme.accent(),
                PhoneTheme.mix(PhoneTheme.accent(), 0xFFF29544, 0.65f));
        PhoneDraw.text(g, PhoneLang.tr("settings.accent.preview"), MARGIN + 12, y + 10, PhoneTheme.onAccent(), 0.75f, false);
        int bw = (w - 2 * MARGIN - 18) / 2;
        PhoneDraw.button(g, MARGIN + 6, y + 26, bw, 18, PhoneLang.tr("settings.accent.button"), -1, -1, PhoneDraw.ButtonStyle.PRIMARY);
        PhoneDraw.button(g, MARGIN + 12 + bw, y + 26, bw, 18, PhoneLang.tr("settings.accent.tab"), -1, -1, PhoneDraw.ButtonStyle.SELECTED);
        y += 60;

        PhoneDraw.text(g, PhoneLang.tr("settings.accent.presets").toUpperCase(PhoneClock.locale()), MARGIN + 2, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        int cols = 6;
        int size = 18;
        int gap = (w - 2 * MARGIN - cols * size) / (cols - 1);
        int current = PhoneTheme.accent();
        for (int i = 0; i < PhoneTheme.ACCENTS.length; i++) {
            int color = PhoneTheme.ACCENTS[i];
            int x = MARGIN + (i % cols) * (size + gap);
            int sy = y + (i / cols) * (size + gap);
            boolean selected = (color | 0xFF000000) == current;
            boolean hovered = PhoneDraw.inside(mouseX, mouseY, x, sy, size, size);
            if (selected || hovered) {
                PhoneDraw.rawRoundedRect(g, x - 2, sy - 2, x + size + 2, sy + size + 2, 5, selected ? PhoneTheme.text() : PhoneTheme.border());
                PhoneDraw.rawRoundedRect(g, x - 1, sy - 1, x + size + 1, sy + size + 1, 4, PhoneTheme.background());
            }
            PhoneDraw.rawRoundedRect(g, x, sy, x + size, sy + size, 4, color);
            hits.add(new Hit(x, sy, size, size, dir -> setAccent(color)));
        }
        y += 2 * (size + gap) + 4;

        PhoneDraw.text(g, PhoneLang.tr("settings.accent.custom").toUpperCase(PhoneClock.locale()), MARGIN + 2, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        int applyW = 56;
        hex.setBounds(MARGIN, y, w - 2 * MARGIN - applyW - 4, 18);
        hex.setPlaceholder("#4C8DF6");
        hex.render(g, mouseX, mouseY);
        int ax = w - MARGIN - applyW;
        PhoneDraw.button(g, ax, y, applyW, 18, PhoneLang.tr("settings.accent.apply"), mouseX, mouseY, PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(ax, y, applyW, 18, dir -> applyHex()));
        y += 22;
        if (hexInvalid) {
            PhoneDraw.text(g, PhoneLang.tr("settings.accent.invalid"), MARGIN + 2, y, PhoneTheme.danger(), 0.6f, false);
            y += 9;
        }
        PhoneDraw.wrapped(g, PhoneLang.tr("settings.accent.hint"), MARGIN + 2, y, w - 2 * MARGIN - 4, PhoneTheme.secondaryText(), 0.6f, false);
        return y + 20;
    }

    private void setAccent(int color) {
        PhoneSettings.get().accentColor = color | 0xFF000000;
        save();
        hex.setText(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
        hexInvalid = false;
    }

    private void applyHex() {
        String text = hex.text().strip().replace("#", "");
        if (text.matches("[0-9a-fA-F]{6}")) {
            setAccent(0xFF000000 | Integer.parseInt(text, 16));
            hex.setFocused(false);
        } else {
            hexInvalid = true;
        }
    }

    // --- Обои ---

    private int renderWallpapers(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        int y = 6 - (int) scroll;
        PhoneSettings s = PhoneSettings.get();
        int cols = 3;
        int gap = 6;
        int tileW = (w - 2 * MARGIN - (cols - 1) * gap) / cols;
        int tileH = Math.round(tileW * 1.7f);

        PhoneDraw.text(g, PhoneLang.tr("settings.wallpaper.standard").toUpperCase(PhoneClock.locale()), MARGIN + 2, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        for (int i = 0; i < PhoneTheme.WALLPAPERS.length; i++) {
            int x = MARGIN + (i % cols) * (tileW + gap);
            int ty = y + (i / cols) * (tileH + gap);
            boolean selected = s.customWallpaper == null && Math.floorMod(s.wallpaper, PhoneTheme.WALLPAPERS.length) == i;
            tileFrame(g, x, ty, tileW, tileH, selected, PhoneDraw.inside(mouseX, mouseY, x, ty, tileW, tileH));
            g.fillGradient(x, ty, x + tileW, ty + tileH, PhoneTheme.WALLPAPERS[i][0], PhoneTheme.WALLPAPERS[i][1]);
            int index = i;
            hits.add(new Hit(x, ty, tileW, tileH, dir -> {
                s.wallpaper = index;
                Wallpapers.setCustom(null);
            }));
        }
        y += ((PhoneTheme.WALLPAPERS.length + cols - 1) / cols) * (tileH + gap) + 4;

        PhoneDraw.text(g, PhoneLang.tr("settings.wallpaper.mine").toUpperCase(PhoneClock.locale()), MARGIN + 2, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        for (int i = 0; i < customWallpapers.size(); i++) {
            PhotoStore.Photo photo = customWallpapers.get(i);
            int x = MARGIN + (i % cols) * (tileW + gap);
            int ty = y + (i / cols) * (tileH + gap);
            String name = photo.file().getFileName().toString();
            boolean selected = name.equals(s.customWallpaper);
            tileFrame(g, x, ty, tileW, tileH, selected, PhoneDraw.inside(mouseX, mouseY, x, ty, tileW, tileH));
            PhotoTextures.Image image = PhotoTextures.thumbnail(photo);
            if (image != null) {
                PhoneDraw.textureCover(g, image.id(), x, ty, tileW, tileH, image.width(), image.height());
            } else {
                g.fill(x, ty, x + tileW, ty + tileH, PhoneTheme.cardHover());
            }
            hits.add(new Hit(x, ty, tileW, tileH, dir -> Wallpapers.setCustom(name)));
        }
        if (!customWallpapers.isEmpty()) {
            y += ((customWallpapers.size() + cols - 1) / cols) * (tileH + gap);
        }
        int textH = PhoneDraw.wrapped(g, PhoneLang.tr("settings.wallpaper.hint"), MARGIN + 2, y, w - 2 * MARGIN - 4, PhoneTheme.secondaryText(), 0.6f, false);
        y += textH + 6;
        PhoneDraw.button(g, MARGIN, y, w - 2 * MARGIN, 18, PhoneLang.tr("settings.wallpaper.folder"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL);
        hits.add(new Hit(MARGIN, y, w - 2 * MARGIN, 18, dir -> openWallpaperFolder()));
        return y + 24;
    }

    /** Рамка плитки: акцентная у выбранной. */
    private static void tileFrame(GuiGraphics g, int x, int y, int w, int h, boolean selected, boolean hovered) {
        if (selected) {
            PhoneDraw.rawRoundedRect(g, x - 2, y - 2, x + w + 2, y + h + 2, 3, PhoneTheme.accent());
        } else if (hovered) {
            PhoneDraw.rawRoundedRect(g, x - 1, y - 1, x + w + 1, y + h + 1, 2, PhoneTheme.border());
        }
    }

    // --- Звуки ---

    private dev.blockphone.fabric.client.sound.PhoneSounds.Kind soundKind = dev.blockphone.fabric.client.sound.PhoneSounds.Kind.NOTIFY;
    private List<String> soundFiles = List.of();

    /** Выбор звука: встроенные мелодии и свои файлы из папки; нажатие — выбрать и прослушать. */
    private int renderSounds(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        int y = 6 - (int) scroll;
        String current = dev.blockphone.fabric.client.sound.PhoneSounds.choice(soundKind);
        PhoneDraw.text(g, PhoneLang.tr("settings.sound.builtin").toUpperCase(PhoneClock.locale()), MARGIN + 2, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        List<String> builtins = dev.blockphone.fabric.client.sound.PhoneSounds.BUILTIN;
        y = soundList(g, mouseX, mouseY, w, y, builtins.stream().map(n -> "builtin:" + n).toList(), current);
        y += 6;
        PhoneDraw.text(g, PhoneLang.tr("settings.sound.mine").toUpperCase(PhoneClock.locale()), MARGIN + 2, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        if (!soundFiles.isEmpty()) {
            y = soundList(g, mouseX, mouseY, w, y, soundFiles.stream().map(n -> "file:" + n).toList(), current);
            y += 4;
        }
        y += PhoneDraw.wrapped(g, PhoneLang.tr("settings.sound.hint"), MARGIN + 2, y, w - 2 * MARGIN - 4, PhoneTheme.secondaryText(), 0.6f, false) + 6;
        int half = (w - 2 * MARGIN - 6) / 2;
        PhoneDraw.button(g, MARGIN, y, half, 18, PhoneLang.tr("settings.wallpaper.folder"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL);
        hits.add(new Hit(MARGIN, y, half, 18, dir -> openSoundFolder()));
        PhoneDraw.button(g, MARGIN + half + 6, y, half, 18, PhoneLang.tr("settings.sound.refresh"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL);
        hits.add(new Hit(MARGIN + half + 6, y, half, 18, dir -> soundFiles = dev.blockphone.fabric.client.sound.PhoneSounds.files()));
        return y + 24;
    }

    private int soundList(GuiGraphics g, int mouseX, int mouseY, int w, int y, List<String> choices, String current) {
        int h = choices.size() * ROW_H;
        PhoneDraw.card(g, MARGIN, y, w - MARGIN, y + h, false);
        for (int i = 0; i < choices.size(); i++) {
            String choice = choices.get(i);
            int ry = y + i * ROW_H;
            boolean hover = PhoneDraw.inside(mouseX, mouseY, MARGIN, ry, w - 2 * MARGIN, ROW_H);
            if (hover) {
                g.fill(MARGIN + 1, ry + 1, w - MARGIN - 1, ry + ROW_H - 1, PhoneTheme.cardHover());
            }
            boolean selected = choice.equals(current);
            PhoneDraw.text(g, PhoneDraw.ellipsize(dev.blockphone.fabric.client.sound.PhoneSounds.label(choice), w - 2 * MARGIN - 30, 0.75f),
                    MARGIN + 8, ry + 6, selected ? PhoneTheme.accent() : PhoneTheme.text(), 0.75f, false);
            if (selected) {
                PhoneDraw.rightText(g, "✔", w - MARGIN - 8, ry + 6, PhoneTheme.accent(), 0.75f);
            }
            if (i > 0) {
                g.fill(MARGIN + 8, ry, w - MARGIN - 8, ry + 1, PhoneTheme.separator());
            }
            hits.add(new Hit(MARGIN, ry, w - 2 * MARGIN, ROW_H, dir -> {
                dev.blockphone.fabric.client.sound.PhoneSounds.setChoice(soundKind, choice);
                dev.blockphone.fabric.client.sound.PhoneSounds.preview(choice);
            }));
        }
        return y + h;
    }

    /** DevShots: страница выбора звука таймера. */
    public void debugSoundPage() {
        soundKind = dev.blockphone.fabric.client.sound.PhoneSounds.Kind.TIMER;
        open(Page.SOUND);
    }

    private static void openSoundFolder() {
        try {
            Files.createDirectories(dev.blockphone.fabric.client.sound.PhoneSounds.dir());
            Util.getPlatform().openPath(dev.blockphone.fabric.client.sound.PhoneSounds.dir());
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to open sounds folder", e);
        }
    }

    // --- Свои приложения ---

    private int renderApps(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        int y = 6 - (int) scroll;
        PhoneDraw.wrapped(g, PhoneLang.tr("settings.apps.intro"), MARGIN + 2, y, w - 2 * MARGIN - 4, PhoneTheme.secondaryText(), 0.6f,
                false);
        y += PhoneDraw.wrappedHeight(PhoneLang.tr("settings.apps.intro"), w - 2 * MARGIN - 4, 0.6f) + 6;
        PhoneDraw.button(g, MARGIN, y, w - 2 * MARGIN, 18, PhoneLang.tr("settings.apps.folder"), mouseX, mouseY,
                PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(MARGIN, y, w - 2 * MARGIN, 18, dir -> openAppsFolder()));
        y += 26;
        List<dev.blockphone.common.apps.AppDefinition> apps = dev.blockphone.fabric.client.apps.custom.CustomApps.all();
        if (apps.isEmpty()) {
            PhoneDraw.centeredText(g, PhoneLang.tr("settings.apps.none"), w / 2f, y + 4, PhoneTheme.secondaryText(), 0.7f, false);
            y += 20;
        }
        for (dev.blockphone.common.apps.AppDefinition app : apps) {
            int top = y;
            int h = 62;
            PhoneDraw.card(g, MARGIN, top, w - MARGIN, top + h, false);
            dev.blockphone.fabric.client.phone.AppRegistry.Entry entry = dev.blockphone.fabric.client.phone.AppRegistry.get(dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX + app.id());
            if (entry != null) {
                entry.drawIcon(g, MARGIN + 6, top + 6, 22);
            }
            int tx = MARGIN + 34;
            int tw = w - MARGIN - 6 - tx;
            String title = dev.blockphone.fabric.client.apps.custom.CustomApps.name(app) + "  " + app.version();
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, tw, 0.75f), tx, top + 6, PhoneTheme.text(), 0.75f, false);
            String by = app.author().isEmpty() ? app.id() : app.id() + " · " + app.author();
            PhoneDraw.text(g, PhoneDraw.ellipsize(by, tw, 0.55f), tx, top + 16, PhoneTheme.secondaryText(), 0.55f, false);
            boolean allowed = dev.blockphone.fabric.client.apps.custom.CustomApps.allowedHere(app);
            String status = PhoneLang.tr(allowed ? "settings.apps.allowed" : "settings.apps.blocked")
                    + (dev.blockphone.fabric.client.apps.custom.CustomApps.serverPart(app.id()) ? " · " + PhoneLang.tr("settings.apps.server") : "");
            PhoneDraw.text(g, PhoneDraw.ellipsize(status, tw, 0.55f), tx, top + 25, allowed ? PhoneTheme.online() : PhoneTheme.danger(),
                    0.55f, false);
            List<String> perms = new java.util.ArrayList<>();
            for (dev.blockphone.common.apps.AppPermission p : app.permissions()) {
                perms.add(PhoneLang.tr("app.perm." + p.key()));
            }
            String permText = perms.isEmpty() ? PhoneLang.tr("settings.apps.no_permissions") : String.join(", ", perms);
            PhoneDraw.text(g, PhoneDraw.ellipsize(PhoneLang.tr("settings.apps.permissions", permText), w - 2 * MARGIN - 12, 0.55f),
                    MARGIN + 6, top + 36, PhoneTheme.text(), 0.55f, false);
            String hash = "sha256:" + app.sha256();
            boolean hover = PhoneDraw.inside(mouseX, mouseY, MARGIN, top + 44, w - 2 * MARGIN, 16);
            PhoneDraw.text(g, PhoneDraw.ellipsize(hash, w - 2 * MARGIN - 12, 0.5f), MARGIN + 6, top + 48,
                    hover ? PhoneTheme.accent() : PhoneTheme.secondaryText(), 0.5f, false);
            hits.add(new Hit(MARGIN, top + 44, w - 2 * MARGIN, 16, dir -> Minecraft.getInstance().keyboardHandler.setClipboard(hash)));
            y += h + 6;
        }
        List<String> errors = dev.blockphone.fabric.client.apps.custom.CustomApps.errors();
        if (!errors.isEmpty()) {
            PhoneDraw.text(g, PhoneLang.tr("settings.apps.errors").toUpperCase(PhoneClock.locale()), MARGIN + 2, y + 3,
                    PhoneTheme.secondaryText(), 0.6f, false);
            y += 13;
            for (String error : errors) {
                PhoneDraw.wrapped(g, error, MARGIN + 2, y, w - 2 * MARGIN - 4, PhoneTheme.danger(), 0.55f, false);
                y += PhoneDraw.wrappedHeight(error, w - 2 * MARGIN - 4, 0.55f) + 4;
            }
        }
        return y + 6;
    }

    private static void openAppsFolder() {
        try {
            Files.createDirectories(dev.blockphone.fabric.client.apps.custom.CustomApps.folder());
            dev.blockphone.fabric.client.apps.custom.CustomApps.ensureFolder();
            Util.getPlatform().openPath(dev.blockphone.fabric.client.apps.custom.CustomApps.folder());
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to open apps folder", e);
        }
    }

    private static void openWallpaperFolder() {
        try {
            Files.createDirectories(Wallpapers.dir());
            Util.getPlatform().openPath(Wallpapers.dir());
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to open wallpaper folder", e);
        }
    }

    // --- Ввод ---

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (page == Page.ACCENT && hex.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button != 0 && button != 1) {
            return false;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (PhoneDraw.inside(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().accept(button == 0 ? 1 : -1);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 16;
        if (scroll < 0) {
            scroll = 0;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (page == Page.ACCENT && hex.isFocused()
                && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            applyHex();
            return true;
        }
        return page == Page.ACCENT && hex.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return page == Page.ACCENT && hex.charTyped(event);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return page == Page.ACCENT && hex.mouseDragged(x, y);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return page == Page.ACCENT && hex.mouseReleased();
    }
}
