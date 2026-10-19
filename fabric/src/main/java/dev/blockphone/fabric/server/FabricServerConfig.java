package dev.blockphone.fabric.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.fabric.BlockPhoneFabric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки сервера на Fabric: {@code config/blockphone/server.json}. Аналог config.yml Paper-плагина. */
public final class FabricServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Включить серверный мессенджер. */
    public boolean messengerEnabled = true;
    /** Показывать другим игрокам телефон в руке. */
    public boolean phoneInHandVisible = true;

    public static FabricServerConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("blockphone").resolve("server.json");
        FabricServerConfig config = null;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, FabricServerConfig.class);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to read {}, using defaults", file, e);
            }
        }
        if (config == null) {
            config = new FabricServerConfig();
        }
        // Перезаписываем файл, чтобы в нём появились новые поля после обновлений.
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to write {}", file, e);
        }
        return config;
    }
}
