package dev.blockphone.fabric.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.server.ServerSettings;
import dev.blockphone.fabric.BlockPhoneFabric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Настройки сервера на Fabric: {@code config/blockphone/server.json}. Аналог config.yml Paper-плагина. */
public final class FabricServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Включить серверный мессенджер. */
    public boolean messengerEnabled = true;
    /** Показывать другим игрокам телефон в руке. */
    public boolean phoneInHandVisible = true;
    /** Лента: посты, паблики, профили, фото. */
    public Social social = new Social();
    /** Ники или UUID игроков с полными правами модератора (если нет LuckPerms; операторы — модераторы и так). */
    public List<String> moderators = new ArrayList<>();
    /** Голосовые сообщения и звонки (звонки — через мод Simple Voice Chat). */
    public Voice voice = new Voice();
    /** Хранилище переписки, ленты и фото. */
    public Storage storage = new Storage();

    public static final class Voice {
        public boolean messages = true;
        public int maxSeconds = 60;
        public boolean calls = true;
        /** "normal" — в звонке слышно и тех, кто рядом; "isolated" — только участников звонка. */
        public String callGroupType = "normal";
    }

    public static final class Social {
        public boolean enabled = true;
        /** Фото в ленте и чатах: "any" — любые, "server" — только снятые на этом сервере, "none" — без фото. */
        public String photos = "server";
        public int maxPhotoKb = 600;
        public int maxPhotoSide = 1280;
        public int maxPublicsPerPlayer = 3;
    }

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
        ServerSettings.Social feed = new ServerSettings.Social(social.enabled, ImageRef.PhotoPolicy.parse(social.photos),
                social.maxPhotoKb * 1024, social.maxPhotoSide, social.maxPublicsPerPlayer);
        ServerSettings.Voice v = new ServerSettings.Voice(voice.messages, voice.maxSeconds, voice.calls, voice.callGroupType);
        return new ServerSettings(messengerEnabled, phoneInHandVisible, s, feed, List.copyOf(moderators), v);
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
        if (config.social == null) {
            config.social = new Social();
        }
        if (config.voice == null) {
            config.voice = new Voice();
        }
        if (config.moderators == null) {
            config.moderators = new ArrayList<>();
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
