package dev.blockphone.fabric.client.apps.custom;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.storage.ClientFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Хранилище приложения (разрешение storage): свои переменные в {@code blockphone/appdata/<id>.json}.
 * Приложение видит только своё хранилище; размер ограничен.
 */
final class AppStorage {
    private static final Gson GSON = new Gson();
    static final int MAX_KEYS = 200;
    static final int MAX_VALUE = 1000;
    static final int MAX_BYTES = 64 * 1024;

    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();

    AppStorage(String appId) {
        this.file = ClientFiles.root().resolve("appdata").resolve(appId + ".json");
        try {
            if (Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES) {
                Map<String, String> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() { }.getType());
                if (loaded != null) {
                    values.putAll(loaded);
                }
            }
        } catch (IOException | RuntimeException e) {
            BlockPhoneFabric.LOGGER.warn("App storage {} not read: {}", appId, e.toString());
        }
    }

    Map<String, String> values() {
        return values;
    }

    /** false — хранилище переполнено. */
    boolean put(String key, String value) {
        if (!values.containsKey(key) && values.size() >= MAX_KEYS) {
            return false;
        }
        String v = value.length() > MAX_VALUE ? value.substring(0, MAX_VALUE) : value;
        String previous = values.put(key, v);
        if (GSON.toJson(values).length() > MAX_BYTES) {
            if (previous == null) {
                values.remove(key);
            } else {
                values.put(key, previous);
            }
            return false;
        }
        return true;
    }

    void save() {
        String json = GSON.toJson(values);
        ClientFiles.IO.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                BlockPhoneFabric.LOGGER.warn("App storage not saved: {}", e.toString());
            }
        });
    }
}
