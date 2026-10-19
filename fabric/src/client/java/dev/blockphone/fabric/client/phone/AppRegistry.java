package dev.blockphone.fabric.client.phone;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.apps.PlaceholderApp;
import dev.blockphone.fabric.client.apps.calculator.CalculatorApp;
import dev.blockphone.fabric.client.apps.camera.CameraApp;
import dev.blockphone.fabric.client.apps.gallery.GalleryApp;
import dev.blockphone.fabric.client.apps.messenger.MessengerApp;
import dev.blockphone.fabric.client.apps.notes.NotesApp;
import dev.blockphone.fabric.client.apps.settings.SettingsApp;
import dev.blockphone.fabric.client.apps.weather.WeatherApp;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Список приложений на рабочем столе (в порядке отображения).
 * Иконка — тёмная плитка в оттенке цвета приложения и светлый символ
 * {@code assets/blockphone/textures/gui/glyph/<id>.png} (белая маска 64×64, генерирует tools/gen_icons.py).
 */
public final class AppRegistry {
    /**
     * @param color цвет приложения (оттенок плитки и символа)
     * @param badge число на красном значке иконки (0 — без значка)
     */
    public record Entry(String id, Supplier<PhoneApp> factory, BooleanSupplier visible, IntSupplier badge, int color) {
        public Identifier glyph() {
            return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/glyph/" + id + ".png");
        }

        /** Иконка size×size: плитка с градиентом и рамкой, символ по центру. */
        public void drawIcon(GuiGraphics g, int x, int y, int size) {
            int r = Math.max(3, size / 5);
            int top = PhoneTheme.mix(color, 0xFF0B0F18, 0.55f);
            int bottom = PhoneTheme.mix(color, 0xFF0B0F18, 0.78f);
            PhoneDraw.rawRoundedRect(g, x, y, x + size, y + size, r, PhoneTheme.mix(color, 0xFF0B0F18, 0.35f));
            int inner = size - 2;
            for (int i = 0; i < inner; i++) {
                int rowColor = PhoneTheme.mix(top, bottom, i / (float) Math.max(1, inner - 1));
                int inset = i < r - 1 ? cornerInset(r - 1, i) : (i >= inner - (r - 1) ? cornerInset(r - 1, inner - 1 - i) : 0);
                g.fill(x + 1 + inset, y + 1 + i, x + size - 1 - inset, y + 2 + i, rowColor);
            }
            int glyph = Math.round(size * 0.6f);
            int offset = (size - glyph) / 2;
            PhoneDraw.tinted(g, glyph(), x + offset, y + offset, glyph, glyph, 64, 64, PhoneTheme.mix(color, PhoneTheme.WHITE, 0.3f));
        }

        private static int cornerInset(int r, int row) {
            double dy = r - row - 0.5;
            return (int) Math.round(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        ENTRIES.add(new Entry("messenger", MessengerApp::new, ClientNetwork::messengerAvailable, MessengerClient::unreadTotal, 0xFF3FB97A));
        add("telegram", () -> new PlaceholderApp("telegram"), 0xFF2AABEE);
        add("camera", CameraApp::new, 0xFF9AA5B8);
        add("gallery", GalleryApp::new, 0xFFE0559B);
        add("music", () -> new PlaceholderApp("music"), 0xFFE5534B);
        add("notes", NotesApp::new, 0xFFF2C14E);
        add("calculator", CalculatorApp::new, 0xFFF29544);
        add("weather", WeatherApp::new, 0xFF4C8DF6);
        add("settings", SettingsApp::new, 0xFFA371F7);
    }

    private static void add(String id, Supplier<PhoneApp> factory, int color) {
        ENTRIES.add(new Entry(id, factory, () -> true, () -> 0, color));
    }

    public static List<Entry> visible() {
        return ENTRIES.stream().filter(e -> e.visible().getAsBoolean()).toList();
    }

    public static Entry get(String id) {
        for (Entry entry : ENTRIES) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private AppRegistry() {
    }
}
