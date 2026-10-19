package dev.blockphone.fabric.client.phone;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.apps.PlaceholderApp;
import dev.blockphone.fabric.client.apps.calculator.CalculatorApp;
import dev.blockphone.fabric.client.apps.settings.SettingsApp;
import dev.blockphone.fabric.client.apps.weather.WeatherApp;
import dev.blockphone.fabric.client.network.ClientNetwork;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Список приложений на рабочем столе (в порядке отображения).
 * Иконка: {@code assets/blockphone/textures/gui/app/<id>.png} (64×64, генерируется tools/gen_icons.py).
 */
public final class AppRegistry {
    public record Entry(String id, Supplier<PhoneApp> factory, BooleanSupplier visible) {
        public Identifier icon() {
            return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/app/" + id + ".png");
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        add("messenger", () -> new PlaceholderApp("messenger"), ClientNetwork::messengerAvailable);
        add("telegram", () -> new PlaceholderApp("telegram"));
        add("camera", () -> new PlaceholderApp("camera"));
        add("gallery", () -> new PlaceholderApp("gallery"));
        add("music", () -> new PlaceholderApp("music"));
        add("notes", () -> new PlaceholderApp("notes"));
        add("calculator", CalculatorApp::new);
        add("weather", WeatherApp::new);
        add("settings", SettingsApp::new);
    }

    private static void add(String id, Supplier<PhoneApp> factory) {
        add(id, factory, () -> true);
    }

    private static void add(String id, Supplier<PhoneApp> factory, BooleanSupplier visible) {
        ENTRIES.add(new Entry(id, factory, visible));
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
