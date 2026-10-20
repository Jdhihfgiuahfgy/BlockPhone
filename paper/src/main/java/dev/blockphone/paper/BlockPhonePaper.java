package dev.blockphone.paper;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.server.BlockPhoneServer;
import dev.blockphone.common.server.Permission;
import dev.blockphone.common.server.ServerPlatform;
import dev.blockphone.common.server.ServerSettings;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Точка входа Paper-плагина. Вся логика — в common ({@link BlockPhoneServer}). */
public final class BlockPhonePaper extends JavaPlugin implements Listener, PluginMessageListener, ServerPlatform {
    private BlockPhoneServer core;
    /** Кому уже отправлено приветствие (канал может объявиться и до входа, и после). */
    private final Set<UUID> greeted = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Новые настройки после обновления плагина дописываются в config.yml.
        getConfig().options().copyDefaults(true);
        saveConfig();
        registerPermissions();
        core = new BlockPhoneServer(this, readSettings(getConfig()));
        core.start();
        getServer().getMessenger().registerOutgoingPluginChannel(this, BlockPhone.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, BlockPhone.CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        if (core != null) {
            core.stop();
        }
    }

    private ServerSettings readSettings(FileConfiguration c) {
        ServerSettings.Storage storage = new ServerSettings.Storage(
                c.getString("storage.type", "h2"),
                getDataFolder().toPath().resolve("messenger"),
                c.getString("storage.mysql.host", "localhost"),
                c.getInt("storage.mysql.port", 3306),
                c.getString("storage.mysql.database", "blockphone"),
                c.getString("storage.mysql.user", "root"),
                c.getString("storage.mysql.password", ""),
                c.getString("storage.table-prefix", "bp_"));
        ServerSettings.Social social = new ServerSettings.Social(c.getBoolean("social.enabled", true),
                ImageRef.PhotoPolicy.parse(c.getString("social.photos", "server")),
                c.getInt("social.max-photo-kb", 600) * 1024, c.getInt("social.max-photo-side", 1280),
                c.getInt("social.max-publics-per-player", 3));
        List<String> moderators = c.getStringList("moderators");
        return new ServerSettings(c.getBoolean("messenger.enabled", true), c.getBoolean("phone-in-hand.visible", true), storage,
                social, moderators);
    }

    /**
     * Узлы прав BlockPhone: обычные — у всех, модераторские — у операторов; blockphone.moderator включает все модераторские.
     * LuckPerms и другие системы прав работают с ними как обычно.
     */
    private void registerPermissions() {
        Map<String, Boolean> children = new HashMap<>();
        for (Permission permission : Permission.values()) {
            if (permission.moderator() && permission != Permission.MODERATOR) {
                children.put(permission.node(), true);
            }
        }
        for (Permission permission : Permission.values()) {
            PermissionDefault def = permission.defaultAllowed() ? PermissionDefault.TRUE : PermissionDefault.OP;
            org.bukkit.permissions.Permission bukkit = permission == Permission.MODERATOR
                    ? new org.bukkit.permissions.Permission(permission.node(), permission.description(), def, children)
                    : new org.bukkit.permissions.Permission(permission.node(), permission.description(), def);
            if (getServer().getPluginManager().getPermission(permission.node()) == null) {
                getServer().getPluginManager().addPermission(bukkit);
            }
        }
    }

    /** Клиент с модом объявил, что слушает наш канал — здороваемся (если он уже в игре). */
    @EventHandler
    public void onChannelRegister(PlayerRegisterChannelEvent event) {
        if (BlockPhone.CHANNEL.equals(event.getChannel()) && event.getPlayer().isOnline()) {
            greet(event.getPlayer());
        }
    }

    /** Мод Fabric объявляет каналы ещё до входа в мир — тогда здороваемся при входе. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().getListeningPluginChannels().contains(BlockPhone.CHANNEL)) {
            greet(event.getPlayer());
        }
    }

    private void greet(Player player) {
        if (greeted.add(player.getUniqueId())) {
            core.onChannelReady(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        greeted.remove(event.getPlayer().getUniqueId());
        core.onQuit(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (BlockPhone.CHANNEL.equals(channel)) {
            core.onMessage(player.getUniqueId(), player.getName(), message);
        }
    }

    public boolean hasMod(Player player) {
        return core.hasMod(player.getUniqueId());
    }

    // --- ServerPlatform ---

    @Override
    public String name() {
        return "paper";
    }

    @Override
    public void send(UUID player, byte[] data) {
        Runnable task = () -> {
            Player target = Bukkit.getPlayer(player);
            if (target != null) {
                target.sendPluginMessage(this, BlockPhone.CHANNEL, data);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    /** Права Bukkit (LuckPerms и др.). Узлы зарегистрированы с нужными значениями по умолчанию. */
    @Override
    public boolean hasPermission(UUID player, Permission permission) {
        Player target = Bukkit.getPlayer(player);
        return target != null ? target.hasPermission(permission.node()) : permission.defaultAllowed();
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
