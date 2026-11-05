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
    /** Скрипты хранят и таблицы (настройки, списки) — одно значение до 16 КБ (всё хранилище — до 64 КБ). */
    static final int MAX_SCRIPT_VALUE = 16 * 1024;
    private static final long SAVE_DELAY_MS = 1000;
    static final int MAX_BYTES = 64 * 1024;

    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();
    /** Когда изменили и ещё не записали на диск (0 — всё записано). */
    private long dirtySince;

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
        return put(key, value, MAX_VALUE);
    }

    /** Значение длиннее maxValue не сохраняется (обрезка испортила бы таблицу) — false. */
    boolean putScript(String key, String value) {
        if (value.length() > MAX_SCRIPT_VALUE) {
            return false;
        }
        boolean ok = put(key, value, MAX_SCRIPT_VALUE);
        if (ok && dirtySince == 0) {
            dirtySince = System.currentTimeMillis();
        }
        return ok;
    }

    private boolean put(String key, String value, int maxValue) {
        if (!values.containsKey(key) && values.size() >= MAX_KEYS) {
            return false;
        }
        String v = value.length() > maxValue ? value.substring(0, maxValue) : value;
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

    /** Записать изменения скрипта, если с первого прошла секунда (из такта приложения). */
    void saveIfDue() {
        if (dirtySince != 0 && System.currentTimeMillis() - dirtySince >= SAVE_DELAY_MS) {
            save();
        }
    }

    /** Записать изменения сейчас (приложение свернули или закрыли). */
    void flush() {
        if (dirtySince != 0) {
            save();
        }
    }

    void save() {
        dirtySince = 0;
        String json = GSON.toJson(values);
        ClientFiles.IO.execute(() -> write(json));
    }

    /** Закрытие игры: записать сразу, в этом потоке (фоновая очередь может уже не успеть). */
    void saveNow() {
        if (dirtySince != 0) {
            dirtySince = 0;
            write(GSON.toJson(values));
        }
    }

    private synchronized void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("App storage not saved: {}", e.toString());
        }
    }
}
