package dev.blockphone.velocity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.server.BlockPhoneServer;
import dev.blockphone.common.server.Permission;
import dev.blockphone.common.server.ServerPlatform;
import dev.blockphone.common.server.ServerSettings;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Плагин для прокси Velocity. В сети серверов мессенджер и лента работают здесь: прокси сам обрабатывает канал
 * blockphone:main от клиентов и не пропускает его к серверам, поэтому чаты, посты и паблики общие для всей сети.
 * Права — через LuckPerms для Velocity; без него модераторы задаются списком moderators в config.json.
 * На серверах сети BlockPhone ставить не обязательно (для будущих функций в мире — можно, мессенджер там выключить).
 */
@Plugin(id = "blockphone", name = "BlockPhone", version = "0.9.16",
        description = "Network-wide messenger and feed for the BlockPhone mod", authors = {"Jdhihfgiuahfgy"})
public final class BlockPhoneVelocity implements ServerPlatform {
    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create(BlockPhone.CHANNEL_NAMESPACE, BlockPhone.CHANNEL_PATH);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private BlockPhoneServer core;

    @Inject
    public BlockPhoneVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    /** Настройки: plugins/blockphone/config.json. */
    static final class Config {
        boolean messengerEnabled = true;
        Social social = new Social();
        /** Голосовые сообщения. Звонков на прокси нет: Simple Voice Chat работает на серверах сети. */
        Voice voice = new Voice();
        /** Ники или UUID игроков с полными правами модератора (у прокси нет операторов). */
        List<String> moderators = new ArrayList<>();
        /** Автомодерация слов и ссылок для всей сети. */
        dev.blockphone.common.server.filter.FilterSettings filter = new dev.blockphone.common.server.filter.FilterSettings();
        /** Какие клиентские приложения игроков работают в сети (mode: blacklist | whitelist | none). */
        dev.blockphone.common.apps.AppsSettings apps = new dev.blockphone.common.apps.AppsSettings();
        /** Стикеры и квота загрузок игрока. */
        dev.blockphone.common.server.UploadSettings uploads = new dev.blockphone.common.server.UploadSettings();
        Storage storage = new Storage();

        static final class Voice {
            boolean messages = true;
            int maxSeconds = 60;
        }

        static final class Social {
            boolean enabled = true;
            /** "any" — любые фото, "server" — только снятые в этой сети, "none" — без фото. */
            String photos = "server";
            int maxPhotoKb = 600;
            int maxPhotoSide = 1280;
            int maxPublicsPerPlayer = 3;
        }

        static final class Storage {
            String type = "h2";
            String host = "localhost";
            int port = 3306;
            String database = "blockphone";
            String user = "root";
            String password = "";
            String tablePrefix = "bp_";
        }
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        Config config = loadConfig();
        ServerSettings.Storage storage = new ServerSettings.Storage(config.storage.type, dataDir.resolve("messenger"),
                config.storage.host, config.storage.port, config.storage.database, config.storage.user,
                config.storage.password, config.storage.tablePrefix);
        ServerSettings.Social social = new ServerSettings.Social(config.social.enabled,
                ImageRef.PhotoPolicy.parse(config.social.photos), config.social.maxPhotoKb * 1024, config.social.maxPhotoSide,
                config.social.maxPublicsPerPlayer);
        ServerSettings.Voice voice = new ServerSettings.Voice(config.voice.messages, config.voice.maxSeconds, false, "normal");
        core = new BlockPhoneServer(this, new ServerSettings(config.messengerEnabled, true, storage, social,
                List.copyOf(config.moderators), voice, config.filter, config.apps, config.uploads));
        core.setAppsDirectory(dataDir.resolve("apps"));
        core.start();
        proxy.getChannelRegistrar().register(CHANNEL);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.stop();
        }
    }

    /** После каждого подключения к серверу сети (в том числе при переходе) — приветствие. */
    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        core.onChannelReady(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        core.onQuit(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // Канал целиком принадлежит прокси: ни клиент → сервер, ни сервер → клиент дальше не идёт.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (event.getSource() instanceof Player player) {
            core.onMessage(player.getUniqueId(), player.getUsername(), event.getData());
        }
    }

    private Config loadConfig() {
        Path file = dataDir.resolve("config.json");
        Config config = null;
        try {
            Files.createDirectories(dataDir);
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    config = GSON.fromJson(reader, Config.class);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to read {}, using defaults", file, e);
        }
        if (config == null) {
            config = new Config();
        }
        if (config.storage == null) {
            config.storage = new Config.Storage();
        }
        if (config.social == null) {
            config.social = new Config.Social();
        }
        if (config.moderators == null) {
            config.moderators = new ArrayList<>();
        }
        if (config.voice == null) {
            config.voice = new Config.Voice();
        }
        if (config.filter == null) {
            config.filter = new dev.blockphone.common.server.filter.FilterSettings();
        }
        config.filter.fixNulls();
        if (config.apps == null) {
            config.apps = new dev.blockphone.common.apps.AppsSettings();
        }
        config.apps.fixNulls();
        if (config.uploads == null) {
            config.uploads = new dev.blockphone.common.server.UploadSettings();
        }
        config.uploads.fixNulls();
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            logger.warn("Failed to write {}", file, e);
        }
        return config;
    }

    // --- ServerPlatform ---

    @Override
    public String name() {
        return "velocity";
    }

    @Override
    public void send(UUID player, byte[] data) {
        proxy.getPlayer(player).ifPresent(p -> p.sendPluginMessage(CHANNEL, data));
    }

    /** LuckPerms (или другая система прав Velocity); не задано — значение по умолчанию (модераторских нет). */
    @Override
    public boolean hasPermission(UUID player, Permission permission) {
        return proxy.getPlayer(player).map(p -> {
            Tristate value = p.getPermissionValue(permission.node());
            return value == Tristate.UNDEFINED ? permission.defaultAllowed() : value.asBoolean();
        }).orElse(permission.defaultAllowed());
    }

    @Override
    public boolean hasPermissionNode(UUID player, String node, boolean defaultValue) {
        return proxy.getPlayer(player).map(p -> {
            Tristate value = p.getPermissionValue(node);
            return value == Tristate.UNDEFINED ? defaultValue : value.asBoolean();
        }).orElse(defaultValue);
    }

    /** Команда прокси (Velocity и плагины прокси) от игрока или консоли. */
    @Override
    public boolean runCommand(UUID player, String command, boolean console) {
        return proxy.getPlayer(player).map(p -> {
            proxy.getCommandManager().executeAsync(console ? proxy.getConsoleCommandSource() : p, command);
            return true;
        }).orElse(false);
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message, Throwable error) {
        logger.warn(message, error);
    }
}
