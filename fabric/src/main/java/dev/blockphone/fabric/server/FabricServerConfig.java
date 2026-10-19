package dev.blockphone.fabric.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.common.server.ServerSettings;
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
    /** Хранилище переписки. */
    public Storage storage = new Storage();

    public static final class Storage {
        /** "h2" — файл в папке сервера (по умолчанию), "mysql" — MySQL/MariaDB. */
        public String type = "h2";
        public String host = "localhost";
        public int port = 3306;
        public String database = "blockphone";
        public String user = "root";
        public String password = "";
        public String tablePrefix = "bp_";
    }

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("blockphone");
    }

    public ServerSettings toSettings() {
        Path h2 = FabricLoader.getInstance().getGameDir().resolve("blockphone").resolve("messenger");
        ServerSettings.Storage s = new ServerSettings.Storage(storage.type, h2, storage.host, storage.port, storage.database,
                storage.user, storage.password, storage.tablePrefix);
        return new ServerSettings(messengerEnabled, phoneInHandVisible, s);
    }

    public static FabricServerConfig load() {
        Path file = dir().resolve("server.json");
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
        if (config.storage == null) {
            config.storage = new Storage();
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
