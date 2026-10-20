package dev.blockphone.fabric.client.phone;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.apps.calculator.CalculatorApp;
import dev.blockphone.fabric.client.apps.camera.CameraApp;
import dev.blockphone.fabric.client.apps.gallery.GalleryApp;
import dev.blockphone.fabric.client.apps.messenger.MessengerApp;
import dev.blockphone.fabric.client.apps.music.MusicApp;
import dev.blockphone.fabric.client.apps.notes.NotesApp;
import dev.blockphone.fabric.client.apps.settings.SettingsApp;
import dev.blockphone.fabric.client.apps.social.SocialApp;
import dev.blockphone.fabric.client.apps.weather.WeatherApp;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.social.SocialClient;
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
    public record Entry(String id, Supplier<PhoneApp> factory, BooleanSupplier visible, IntSupplier badge, int color,
                        dev.blockphone.common.apps.AppDefinition custom) {
        public Entry(String id, Supplier<PhoneApp> factory, BooleanSupplier visible, IntSupplier badge, int color) {
            this(id, factory, visible, badge, color, null);
        }

        /** Подпись под иконкой. */
        public String label() {
            return custom != null ? dev.blockphone.fabric.client.apps.custom.CustomApps.name(custom)
                    : dev.blockphone.fabric.client.i18n.PhoneLang.tr("app." + id);
        }

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
            if (custom != null) {
                // Своё приложение: картинка иконки (PNG) или первая буква названия.
                Identifier icon = dev.blockphone.fabric.client.apps.custom.CustomApps.icon(custom);
                if (icon != null) {
                    int pad = Math.max(2, size / 8);
                    g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, icon, x + pad, y + pad, 0, 0, size - 2 * pad,
                            size - 2 * pad, 1, 1, 1, 1);
                } else {
                    String name = dev.blockphone.fabric.client.apps.custom.CustomApps.name(custom);
                    String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
                    float scale = size / 16f;
                    PhoneDraw.centeredText(g, letter, x + size / 2f, y + size / 2f - 4 * scale, PhoneTheme.mix(color, PhoneTheme.WHITE, 0.3f),
                            scale, false);
                }
                return;
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
        ENTRIES.add(new Entry("feed", SocialApp::new, ClientNetwork::socialAvailable, SocialClient::badge, 0xFF2EC4D6));
        ENTRIES.add(new Entry("telegram", dev.blockphone.fabric.client.apps.telegram.TelegramApp::new, () -> true,
                dev.blockphone.fabric.client.telegram.Telegram::unreadTotal, 0xFF2AABEE));
        add("camera", CameraApp::new, 0xFF9AA5B8);
        add("gallery", GalleryApp::new, 0xFFE0559B);
        add("music", MusicApp::new, 0xFFE5534B);
        add("notes", NotesApp::new, 0xFFF2C14E);
        add("calculator", CalculatorApp::new, 0xFFF29544);
        add("weather", WeatherApp::new, 0xFF4C8DF6);
        add("settings", SettingsApp::new, 0xFFA371F7);
    }

    private static void add(String id, Supplier<PhoneApp> factory, int color) {
        ENTRIES.add(new Entry(id, factory, () -> true, () -> 0, color));
    }

    public static List<Entry> visible() {
        List<Entry> result = new ArrayList<>(ENTRIES.stream().filter(e -> e.visible().getAsBoolean()).toList());
        for (dev.blockphone.common.apps.AppDefinition app : dev.blockphone.fabric.client.apps.custom.CustomApps.all()) {
            result.add(custom(app));
        }
        return result;
    }

    public static Entry get(String id) {
        for (Entry entry : ENTRIES) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        if (id.startsWith(dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX)) {
            dev.blockphone.common.apps.AppDefinition app = dev.blockphone.fabric.client.apps.custom.CustomApps.get(
                    id.substring(dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX.length()));
            return app != null ? custom(app) : null;
        }
        return null;
    }

    /** Клиентское приложение из папки игрока. */
    private static Entry custom(dev.blockphone.common.apps.AppDefinition app) {
        String id = dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX + app.id();
        return new Entry(id, () -> new dev.blockphone.fabric.client.apps.custom.CustomApp(id), () -> true, () -> 0, app.color(), app);
    }

    private AppRegistry() {
    }
}
