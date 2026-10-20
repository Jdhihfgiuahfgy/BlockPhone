package dev.blockphone.bungee;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.server.BlockPhoneServer;
import dev.blockphone.common.server.Permission;
import dev.blockphone.common.server.ServerPlatform;
import dev.blockphone.common.server.ServerSettings;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Плагин для прокси BungeeCord/Waterfall — то же, что плагин Velocity: прокси сам обрабатывает канал blockphone:main
 * и не пропускает его к серверам, поэтому чаты, лента и голосовые общие для всей сети. Звонков на прокси нет.
 * Права: модераторские — через систему прав BungeeCord (LuckPerms) или список moderators; обычные — у всех
 * (у BungeeCord нет «не задано», поэтому запретить обычное право можно только выключив функцию в настройках).
 */
public final class BlockPhoneBungee extends Plugin implements Listener, ServerPlatform {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BlockPhoneServer core;

    /** Настройки: plugins/BlockPhone/config.json (как у Velocity). */
    static final class Config {
        boolean messengerEnabled = true;
        Social social = new Social();
        Voice voice = new Voice();
        /** Ники или UUID игроков с полными правами модератора. */
        List<String> moderators = new ArrayList<>();
        /** Автомодерация слов и ссылок для всей сети. */
        dev.blockphone.common.server.filter.FilterSettings filter = new dev.blockphone.common.server.filter.FilterSettings();
        Storage storage = new Storage();

        static final class Social {
            boolean enabled = true;
            /** "any" — любые фото, "server" — только снятые в этой сети, "none" — без фото. */
            String photos = "server";
            int maxPhotoKb = 600;
            int maxPhotoSide = 1280;
            int maxPublicsPerPlayer = 3;
        }

        static final class Voice {
            boolean messages = true;
            int maxSeconds = 60;
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

    @Override
    public void onEnable() {
        Config config = loadConfig();
        Path dir = getDataFolder().toPath();
        ServerSettings.Storage storage = new ServerSettings.Storage(config.storage.type, dir.resolve("messenger"), config.storage.host,
                config.storage.port, config.storage.database, config.storage.user, config.storage.password, config.storage.tablePrefix);
        ServerSettings.Social social = new ServerSettings.Social(config.social.enabled, ImageRef.PhotoPolicy.parse(config.social.photos),
                config.social.maxPhotoKb * 1024, config.social.maxPhotoSide, config.social.maxPublicsPerPlayer);
        ServerSettings.Voice voice = new ServerSettings.Voice(config.voice.messages, config.voice.maxSeconds, false, "normal");
        core = new BlockPhoneServer(this, new ServerSettings(config.messengerEnabled, true, storage, social,
                List.copyOf(config.moderators), voice, config.filter));
        core.start();
        getProxy().registerChannel(BlockPhone.CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.stop();
        }
        getProxy().unregisterChannel(BlockPhone.CHANNEL);
    }

    /** После каждого перехода на сервер сети (и при входе) — приветствие. */
    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        core.onChannelReady(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        core.onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!BlockPhone.CHANNEL.equals(event.getTag())) {
            return;
        }
        // Канал целиком принадлежит прокси: ни клиент → сервер, ни сервер → клиент дальше не идёт.
        event.setCancelled(true);
        if (event.getSender() instanceof ProxiedPlayer player) {
            core.onMessage(player.getUniqueId(), player.getName(), event.getData());
        }
    }

    private Config loadConfig() {
        Path dir = getDataFolder().toPath();
        Path file = dir.resolve("config.json");
        Config config = null;
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    config = GSON.fromJson(reader, Config.class);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to read " + file + ", using defaults", e);
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
        if (config.voice == null) {
            config.voice = new Config.Voice();
        }
        if (config.filter == null) {
            config.filter = new dev.blockphone.common.server.filter.FilterSettings();
        }
        config.filter.fixNulls();
        if (config.moderators == null) {
            config.moderators = new ArrayList<>();
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to write " + file, e);
        }
        return config;
    }

    // --- ServerPlatform ---

    @Override
    public String name() {
        return "bungeecord";
    }

    @Override
    public void send(UUID player, byte[] data) {
        ProxiedPlayer target = getProxy().getPlayer(player);
        if (target != null) {
            target.sendData(BlockPhone.CHANNEL, data);
        }
    }

    @Override
    public boolean hasPermission(UUID player, Permission permission) {
        if (permission.defaultAllowed()) {
            return true;
        }
        ProxiedPlayer target = getProxy().getPlayer(player);
        return target != null && target.hasPermission(permission.node());
    }

    @Override
    public void info(String message) {
        getLogger().info(message);
    }

    @Override
    public void warn(String message, Throwable error) {
        getLogger().log(Level.WARNING, message, error);
    }
}
