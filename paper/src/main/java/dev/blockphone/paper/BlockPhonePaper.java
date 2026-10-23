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
        dev.blockphone.common.voice.VoiceBridges.setMainThread(this::runOnMain);
        if (getServer().getPluginManager().getPlugin("voicechat") != null) {
            PaperVoicechat.register(this);
        }
        core = new BlockPhoneServer(this, readSettings(getConfig()));
        core.setAppsDirectory(getDataFolder().toPath().resolve("apps"));
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

    /** /blockphone reload — перечитать config.yml и перезапустить серверную часть без перезапуска сервера. */
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§7BlockPhone " + getPluginMeta().getVersion() + " — §f/" + label + " reload");
            return true;
        }
        if (!sender.hasPermission(BlockPhoneServer.RELOAD_PERMISSION)) {
            sender.sendMessage("§cNo permission (" + BlockPhoneServer.RELOAD_PERMISSION + ")");
            return true;
        }
        try {
            reloadConfig();
            getConfig().options().copyDefaults(true);
            saveConfig();
            core = BlockPhoneServer.restart(core, this, readSettings(getConfig()));
            sender.sendMessage("§aBlockPhone: config reloaded");
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "BlockPhone reload failed", e);
            sender.sendMessage("§cBlockPhone: reload failed — " + e.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        return args.length == 1 && "reload".startsWith(args[0].toLowerCase(java.util.Locale.ROOT)) ? List.of("reload") : List.of();
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
                ImageRef.PhotoPolicy.parse(c.getString("social.photos", "any")),
                c.getInt("social.max-photo-kb", 600) * 1024, c.getInt("social.max-photo-side", 1280),
                c.getInt("social.max-publics-per-player", 3));
        List<String> moderators = c.getStringList("moderators");
        ServerSettings.Voice voice = new ServerSettings.Voice(c.getBoolean("voice.messages", true), c.getInt("voice.max-seconds", 60),
                c.getBoolean("voice.calls", true), c.getString("voice.call-group-type", "normal"), c.getInt("voice.speaker-radius", 8));
        return new ServerSettings(c.getBoolean("messenger.enabled", true), c.getBoolean("phone-in-hand.visible", true), storage,
                social, moderators, voice, readFilter(c), readApps(c), readUploads(c), readModeration(c));
    }

    private static dev.blockphone.common.server.ModerationSettings readModeration(FileConfiguration c) {
        dev.blockphone.common.server.ModerationSettings m = new dev.blockphone.common.server.ModerationSettings();
        m.chatLog.enabled = c.getBoolean("moderation.chat-log.enabled", m.chatLog.enabled);
        m.chatLog.groups = c.getBoolean("moderation.chat-log.groups", m.chatLog.groups);
        m.chatLog.console = c.getBoolean("moderation.chat-log.console", m.chatLog.console);
        m.chatLog.format = c.getString("moderation.chat-log.format", m.chatLog.format);
        m.chatLog.groupFormat = c.getString("moderation.chat-log.group-format", m.chatLog.groupFormat);
        m.chatLog.photo = c.getString("moderation.chat-log.photo", m.chatLog.photo);
        m.chatLog.voice = c.getString("moderation.chat-log.voice", m.chatLog.voice);
        m.chatLog.sticker = c.getString("moderation.chat-log.sticker", m.chatLog.sticker);
        m.banCommand = c.getString("moderation.ban-command", m.banCommand);
        m.unbanCommand = c.getString("moderation.unban-command", m.unbanCommand);
        return m.fixNulls();
    }

    private static dev.blockphone.common.server.UploadSettings readUploads(FileConfiguration c) {
        dev.blockphone.common.server.UploadSettings u = new dev.blockphone.common.server.UploadSettings();
        u.stickers.enabled = c.getBoolean("uploads.stickers.enabled", u.stickers.enabled);
        u.stickers.maxKb = c.getInt("uploads.stickers.max-kb", u.stickers.maxKb);
        u.stickers.maxSide = c.getInt("uploads.stickers.max-side", u.stickers.maxSide);
        u.stickers.maxPacks = c.getInt("uploads.stickers.max-packs", u.stickers.maxPacks);
        u.stickers.maxPerPack = c.getInt("uploads.stickers.max-per-pack", u.stickers.maxPerPack);
        u.quota.perPlayerMb = c.getInt("uploads.quota.per-player-mb", u.quota.perPlayerMb);
        u.quota.uploadsPerDay = c.getInt("uploads.quota.uploads-per-day", u.quota.uploadsPerDay);
        return u.fixNulls();
    }

    private static dev.blockphone.common.apps.AppsSettings readApps(FileConfiguration c) {
        dev.blockphone.common.apps.AppsSettings a = new dev.blockphone.common.apps.AppsSettings();
        a.mode = c.getString("apps.mode", a.mode);
        a.blacklist = c.getStringList("apps.blacklist");
        a.whitelist = c.getStringList("apps.whitelist");
        return a.fixNulls();
    }

    private static dev.blockphone.common.server.filter.FilterSettings readFilter(FileConfiguration c) {
        dev.blockphone.common.server.filter.FilterSettings f = new dev.blockphone.common.server.filter.FilterSettings();
        f.enabled = c.getBoolean("filter.enabled", f.enabled);
        f.bypassForModerators = c.getBoolean("filter.bypass-for-moderators", f.bypassForModerators);
        f.log = c.getBoolean("filter.log", f.log);
        f.applyTo.messages = c.getBoolean("filter.apply-to.messages", true);
        f.applyTo.groupNames = c.getBoolean("filter.apply-to.group-names", true);
        f.applyTo.posts = c.getBoolean("filter.apply-to.posts", true);
        f.applyTo.comments = c.getBoolean("filter.apply-to.comments", true);
        f.applyTo.profiles = c.getBoolean("filter.apply-to.profiles", true);
        f.applyTo.publics = c.getBoolean("filter.apply-to.publics", true);
        f.words.blacklist = c.getStringList("filter.words.blacklist");
        f.words.whitelist = c.getStringList("filter.words.whitelist");
        f.words.action = c.getString("filter.words.action", f.words.action);
        f.links.mode = c.getString("filter.links.mode", f.links.mode);
        f.links.domainWhitelist = c.getStringList("filter.links.domain-whitelist");
        f.links.domainBlacklist = c.getStringList("filter.links.domain-blacklist");
        f.links.formatWhitelist = c.getStringList("filter.links.format-whitelist");
        if (c.isList("filter.links.format-blacklist")) {
            f.links.formatBlacklist = c.getStringList("filter.links.format-blacklist");
        }
        org.bukkit.configuration.ConfigurationSection custom = c.getConfigurationSection("filter.links.custom-formats");
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                f.links.customFormats.put(key, custom.getString(key, ""));
            }
        }
        f.links.action = c.getString("filter.links.action", f.links.action);
        f.autoMute.violations = c.getInt("filter.auto-mute.violations", f.autoMute.violations);
        f.autoMute.windowMinutes = c.getInt("filter.auto-mute.window-minutes", f.autoMute.windowMinutes);
        f.autoMute.muteMinutes = c.getInt("filter.auto-mute.mute-minutes", f.autoMute.muteMinutes);
        return f.fixNulls();
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
        if (getServer().getPluginManager().getPermission(BlockPhoneServer.RELOAD_PERMISSION) == null) {
            getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(BlockPhoneServer.RELOAD_PERMISSION,
                    "Reload the BlockPhone config (/blockphone reload)", PermissionDefault.OP));
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
    public boolean hasPermissionNode(UUID player, String node, boolean defaultValue) {
        Player target = Bukkit.getPlayer(player);
        if (target == null) {
            return defaultValue;
        }
        return target.isPermissionSet(node) ? target.hasPermission(node) : defaultValue || target.isOp();
    }

    @Override
    public boolean runCommand(UUID player, String command, boolean console) {
        Player target = Bukkit.getPlayer(player);
        if (target == null) {
            return false;
        }
        return Bukkit.dispatchCommand(console ? Bukkit.getConsoleSender() : target, command);
    }

    private java.lang.reflect.Method papi;
    private boolean papiChecked;

    /** PlaceholderAPI (%vault_eco_balance% и т. п.), если установлен. */
    @Override
    public String placeholders(UUID player, String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        if (!papiChecked) {
            papiChecked = true;
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                try {
                    papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                            .getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
                } catch (ReflectiveOperationException e) {
                    getLogger().warning("PlaceholderAPI found but not usable: " + e);
                }
            }
        }
        Player target = Bukkit.getPlayer(player);
        if (papi == null || target == null) {
            return text;
        }
        try {
            return (String) papi.invoke(null, target, text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return text;
        }
    }

    @Override
    public java.util.Collection<UUID> onlinePlayers() {
        List<UUID> result = new java.util.ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            result.add(player.getUniqueId());
        }
        return result;
    }

    @Override
    public Map<UUID, Position> positions() {
        Map<UUID, Position> result = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            org.bukkit.Location l = player.getLocation();
            result.put(player.getUniqueId(), new Position(player.getWorld().getName(), l.getX(), l.getY(), l.getZ()));
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void sendChat(UUID player, String text) {
        Player target = Bukkit.getPlayer(player);
        if (target != null) {
            target.sendMessage(text);
        }
    }

    @Override
    public boolean runConsoleCommand(String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
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
