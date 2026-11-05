package dev.blockphone.fabric.client.screens;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.apps.custom.CloudRelay;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;

/**
 * Свои экраны и колонки: файл на этом компьютере, отдельный для каждого сервера (по адресу) и мира одиночной игры —
 * {@code blockphone/screens/<ключ>.json}. Только поток рендера; запись — в фоне.
 */
public final class ScreenStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static @Nullable String key;
    private static ScreenData.Set data = new ScreenData.Set();
    private static int version;

    /** Свои экраны и колонки текущего сервера (пустые вне мира). */
    public static ScreenData.Set data() {
        ensureLoaded();
        return data;
    }

    /** Растёт при каждом изменении (рассылка, приложение). */
    public static int version() {
        return version;
    }

    private static void ensureLoaded() {
        String k = currentKey();
        if (java.util.Objects.equals(k, key)) {
            return;
        }
        key = k;
        data = new ScreenData.Set();
        version++;
        if (k == null) {
            return;
        }
        try {
            Path file = file(k);
            if (Files.exists(file)) {
                ScreenData.Set loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ScreenData.Set.class);
                if (loaded != null) {
                    if (loaded.screens == null) {
                        loaded.screens = new java.util.ArrayList<>();
                    }
                    if (loaded.speakers == null) {
                        loaded.speakers = new java.util.ArrayList<>();
                    }
                    if (loaded.phone == null) {
                        loaded.phone = new ScreenData.PhoneOut();
                    }
                    if (loaded.groups == null) {
                        loaded.groups = new java.util.ArrayList<>();
                    }
                    for (ScreenData.Screen sc : loaded.screens) {
                        if (sc.actions == null) {
                            sc.actions = new java.util.ArrayList<>();
                        }
                    }
                    for (ScreenData.Speaker sp : loaded.speakers) {
                        if (sp.channel == null) {
                            sp.channel = "mix";
                        }
                    }
                    data = loaded;
                }
            }
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Screens: could not read {}", k, e);
        }
    }

    /** Ключ места: сервер по адресу или мир одиночной игры по папке; null — не в мире. */
    private static @Nullable String currentKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            return null;
        }
        if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            String name = root.getFileName() != null ? root.getFileName().toString() : "world";
            return "world-" + name.replaceAll("[^A-Za-z0-9_.-]", "_");
        }
        ServerData server = mc.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return null;
        }
        return "server-" + CloudRelay.serverHash(server.ip);
    }

    private static Path file(String k) {
        return ClientFiles.root().resolve("screens").resolve(k + ".json");
    }

    /** Сохранить после изменения (и разослать другим). */
    public static void changed() {
        version++;
        ScreenSync.ownChanged();
        String k = key;
        if (k == null) {
            return;
        }
        String json = GSON.toJson(data);
        ClientFiles.IO.execute(() -> {
            try {
                Path file = file(k);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Screens: could not save {}", k, e);
            }
        });
    }

    public static @Nullable ScreenData.Screen screen(String id) {
        for (ScreenData.Screen s : data().screens) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    public static void remove(ScreenData.Screen screen) {
        data().screens.remove(screen);
        data.speakers.removeIf(sp -> sp.screen.equals(screen.id));
        data.forget(screen.id);
        changed();
    }

    public static String newId() {
        SecureRandom r = new SecureRandom();
        String abc = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(abc.charAt(r.nextInt(abc.length())));
        }
        return sb.toString();
    }

    private ScreenStore() {
    }
}
