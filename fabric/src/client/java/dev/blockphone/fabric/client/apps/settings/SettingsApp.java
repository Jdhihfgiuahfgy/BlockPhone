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
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Настройки телефона. Клик ЛКМ по строке — следующее значение, ПКМ — предыдущее.
 * Все изменения сразу сохраняются в {@link PhoneSettings}.
 */
public final class SettingsApp extends PhoneApp {
    private static final List<String> LANGUAGES = List.of("auto", "ru_ru", "en_us");
    private static final int ROW_H = 20;
    private static final int MARGIN = 8;

    /** Строка: подпись, значение, обработчик (+1 / -1). Без обработчика — только для чтения. */
    private record Row(Supplier<String> label, Supplier<String> value, IntConsumer onCycle, Integer swatch) {
    }

    private record Section(String titleKey, List<Row> rows) {
    }

    private double scroll;

    public SettingsApp() {
        super("settings");
    }

    private List<Section> sections() {
        PhoneSettings s = PhoneSettings.get();
        List<Section> result = new ArrayList<>();

        result.add(new Section("settings.section.general", List.of(
                new Row(() -> PhoneLang.tr("settings.language"),
                        () -> PhoneLang.tr("settings.language." + s.language),
                        dir -> s.language = cycle(LANGUAGES, s.language, dir), null),
                new Row(() -> PhoneLang.tr("settings.clock_source"),
                        () -> PhoneLang.tr(s.gameTimeClock ? "settings.clock_source.game" : "settings.clock_source.real"),
                        dir -> s.gameTimeClock = !s.gameTimeClock, null),
                new Row(() -> PhoneLang.tr("settings.clock_format"),
                        () -> PhoneLang.tr(s.clock24h ? "settings.clock_format.24" : "settings.clock_format.12"),
                        dir -> s.clock24h = !s.clock24h, null))));

        result.add(new Section("settings.section.appearance", List.of(
                new Row(() -> PhoneLang.tr("settings.theme"),
                        () -> PhoneLang.tr(s.darkTheme ? "settings.theme.dark" : "settings.theme.light"),
                        dir -> s.darkTheme = !s.darkTheme, null),
                new Row(() -> PhoneLang.tr("settings.wallpaper"),
                        () -> PhoneLang.tr("wallpaper." + Math.floorMod(s.wallpaper, PhoneTheme.WALLPAPERS.length)),
                        dir -> s.wallpaper = Math.floorMod(s.wallpaper + dir, PhoneTheme.WALLPAPERS.length),
                        PhoneTheme.wallpaper()[0]),
                new Row(() -> PhoneLang.tr("settings.animations"),
                        () -> PhoneLang.tr(s.animations ? "settings.on" : "settings.off"),
                        dir -> s.animations = !s.animations, null))));

        ServerHelloPacket hello = ClientNetwork.serverHello();
        String server = hello == null
                ? PhoneLang.tr("settings.server.none")
                : PhoneLang.tr("settings.server.connected", hello.serverPlatform());
        result.add(new Section("settings.section.about", List.of(
                new Row(() -> PhoneLang.tr("settings.version"), () -> BlockPhoneFabric.VERSION, null, null),
                new Row(() -> PhoneLang.tr("settings.server"), () -> server, null, null),
                new Row(() -> PhoneLang.tr("settings.key"),
                        () -> BlockPhoneClient.openPhoneKey.getTranslatedKeyMessage().getString(), null, null))));
        return result;
    }

    private static String cycle(List<String> values, String current, int dir) {
        int index = values.indexOf(current);
        return values.get(Math.floorMod(index + dir, values.size()));
    }

    private int totalHeight(List<Section> sections) {
        int h = 4;
        for (Section section : sections) {
            h += 14 + section.rows().size() * ROW_H + 8;
        }
        return h;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        List<Section> sections = sections();
        clampScroll(sections);
        int y = 4 - (int) scroll;
        int w = contentWidth();
        for (Section section : sections) {
            PhoneDraw.text(g, PhoneLang.tr(section.titleKey()).toUpperCase(PhoneClock.locale()), MARGIN + 4, y + 3,
                    PhoneTheme.secondaryText(), 0.75f, false);
            y += 14;
            int top = y;
            int bottom = y + section.rows().size() * ROW_H;
            PhoneDraw.roundedRect(g, MARGIN, top, w - MARGIN, bottom, 8, PhoneTheme.card());
            for (int i = 0; i < section.rows().size(); i++) {
                Row row = section.rows().get(i);
                int ry = top + i * ROW_H;
                boolean hovered = row.onCycle() != null && PhoneDraw.inside(mouseX, mouseY, MARGIN, ry, w - 2 * MARGIN, ROW_H);
                if (hovered) {
                    g.fill(MARGIN, ry, w - MARGIN, ry + ROW_H, PhoneTheme.cardHover());
                }
                if (i > 0) {
                    g.fill(MARGIN + 6, ry, w - MARGIN, ry + 1, PhoneTheme.separator());
                }
                int inner = w - 2 * MARGIN - 12;
                String label = PhoneDraw.ellipsize(row.label().get(), inner * 0.55f, 0.75f);
                PhoneDraw.text(g, label, MARGIN + 6, ry + 7, PhoneTheme.text(), 0.75f, false);
                int valueRight = w - MARGIN - 6;
                int valueColor = row.onCycle() != null ? PhoneTheme.ACCENT : PhoneTheme.secondaryText();
                float valueMax = inner - PhoneDraw.width(label, 0.75f) - 8 - (row.swatch() != null ? 12 : 0);
                String value = PhoneDraw.ellipsize(row.value().get(), valueMax, 0.75f);
                PhoneDraw.rightText(g, value, valueRight, ry + 7, valueColor, 0.75f);
                if (row.swatch() != null) {
                    int sx = (int) (valueRight - PhoneDraw.width(value, 0.75f)) - 12;
                    PhoneDraw.roundedRect(g, sx, ry + 5, sx + 9, ry + 14, 3, row.swatch());
                }
            }
            // Закругления карточки поверх подсветки строк.
            PhoneDraw.roundCornersMask(g, MARGIN, top, w - MARGIN, bottom, 8, PhoneTheme.background());
            y = bottom + 8;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 && button != 1) {
            return false;
        }
        int y = 4 - (int) scroll;
        int w = contentWidth();
        for (Section section : sections()) {
            y += 14;
            for (Row row : section.rows()) {
                if (row.onCycle() != null && PhoneDraw.inside(mx, my, MARGIN, y, w - 2 * MARGIN, ROW_H)) {
                    row.onCycle().accept(button == 0 ? 1 : -1);
                    PhoneSettings.get().save();
                    return true;
                }
                y += ROW_H;
            }
            y += 8;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 16;
        clampScroll(sections());
        return true;
    }

    private void clampScroll(List<Section> sections) {
        int max = Math.max(0, totalHeight(sections) - contentHeight());
        scroll = Math.max(0, Math.min(max, scroll));
    }
}
