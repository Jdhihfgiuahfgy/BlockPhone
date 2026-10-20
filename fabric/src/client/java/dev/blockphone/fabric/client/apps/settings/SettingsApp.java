package dev.blockphone.fabric.client.apps.settings;

import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.BlockPhoneClient;
import dev.blockphone.fabric.client.config.PhoneSettings;
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
 * Настройки телефона: основной список (ЛКМ — следующее значение, ПКМ — предыдущее) и два экрана —
 * выбор акцентного цвета (готовые + свой HEX) и выбор обоев (стандартные + свои картинки).
 * Все изменения сразу сохраняются в {@link PhoneSettings}.
 */
public final class SettingsApp extends PhoneApp {
    private static final List<String> LANGUAGES = List.of("auto", "ru_ru", "en_us");
    private static final int ROW_H = 20;
    private static final int MARGIN = 6;

    private enum Page { MAIN, ACCENT, WALLPAPER }

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
            case ACCENT -> PhoneLang.tr("settings.accent");
            case WALLPAPER -> PhoneLang.tr("settings.wallpaper");
        };
    }

    @Override
    public boolean onBack() {
        if (page != Page.MAIN) {
            open(Page.MAIN);
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

    // --- Основной список ---

    private List<Section> sections() {
        PhoneSettings s = PhoneSettings.get();
        List<Section> result = new ArrayList<>();
        result.add(new Section("settings.section.general", List.of(
                new Row(() -> PhoneLang.tr("settings.language"), () -> PhoneLang.tr("settings.language." + s.language),
                        dir -> s.language = cycle(LANGUAGES, s.language, dir), null, false),
                new Row(() -> PhoneLang.tr("settings.clock_source"),
                        () -> PhoneLang.tr(s.gameTimeClock ? "settings.clock_source.game" : "settings.clock_source.real"),
                        dir -> s.gameTimeClock = !s.gameTimeClock, null, false),
                new Row(() -> PhoneLang.tr("settings.clock_format"),
                        () -> PhoneLang.tr(s.clock24h ? "settings.clock_format.24" : "settings.clock_format.12"),
                        dir -> s.clock24h = !s.clock24h, null, false))));
        result.add(new Section("settings.section.appearance", List.of(
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
        result.add(new Section("settings.section.music", List.of(
                new Row(() -> PhoneLang.tr("settings.music_hud"),
                        () -> PhoneLang.tr(s.musicHud ? "settings.on" : "settings.off"),
                        dir -> s.musicHud = !s.musicHud, null, false),
                new Row(() -> PhoneLang.tr("settings.music_volume"),
                        () -> Math.round(s.musicVolume * 100) + "%",
                        dir -> s.musicVolume = Math.floorMod(Math.round(s.musicVolume * 10) + dir, 11) / 10f, null, false))));

        ServerHelloPacket hello = ClientNetwork.serverHello();
        String server = hello == null ? PhoneLang.tr("settings.server.none") : PhoneLang.tr("settings.server.connected", hello.serverPlatform());
        result.add(new Section("settings.section.about", List.of(
                new Row(() -> PhoneLang.tr("settings.version"), () -> BlockPhoneFabric.VERSION, null, null, false),
                new Row(() -> PhoneLang.tr("settings.server"), () -> server, null, null, false),
                new Row(() -> PhoneLang.tr("settings.key"),
                        () -> BlockPhoneClient.openPhoneKey.getTranslatedKeyMessage().getString(), null, null, false))));
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
            case MAIN -> renderMain(g, mouseX, mouseY);
            case ACCENT -> renderAccent(g, mouseX, mouseY);
            case WALLPAPER -> renderWallpapers(g, mouseX, mouseY);
        };
        double max = Math.max(0, bottom + (int) scroll - contentHeight() + 6);
        scroll = Math.max(0, Math.min(max, scroll));
    }

    private int renderMain(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        int y = 4 - (int) scroll;
        for (Section section : sections()) {
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
