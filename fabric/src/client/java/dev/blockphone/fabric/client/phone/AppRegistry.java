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
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Список приложений на рабочем столе (в порядке отображения).
 * Иконка: {@code assets/blockphone/textures/gui/app/<id>.png} (64×64, генерируется tools/gen_icons.py).
 */
public final class AppRegistry {
    /** badge — число на красном значке иконки (0 — без значка). */
    public record Entry(String id, Supplier<PhoneApp> factory, BooleanSupplier visible, IntSupplier badge) {
        public Identifier icon() {
            return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/app/" + id + ".png");
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        ENTRIES.add(new Entry("messenger", MessengerApp::new, ClientNetwork::messengerAvailable, MessengerClient::unreadTotal));
        add("telegram", () -> new PlaceholderApp("telegram"));
        add("camera", CameraApp::new);
        add("gallery", GalleryApp::new);
        add("music", () -> new PlaceholderApp("music"));
        add("notes", NotesApp::new);
        add("calculator", CalculatorApp::new);
        add("weather", WeatherApp::new);
        add("settings", SettingsApp::new);
    }

    private static void add(String id, Supplier<PhoneApp> factory) {
        add(id, factory, () -> true);
    }

    private static void add(String id, Supplier<PhoneApp> factory, BooleanSupplier visible) {
        ENTRIES.add(new Entry(id, factory, visible, () -> 0));
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
