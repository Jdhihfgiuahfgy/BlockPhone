package dev.blockphone.fabric.server;

import dev.blockphone.common.server.BlockPhoneServer;
import dev.blockphone.common.server.Permission;
import dev.blockphone.common.server.ServerPlatform;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.network.RawPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная часть мода на Fabric (аналог Paper-плагина). Вся логика — в common ({@link BlockPhoneServer}).
 * Работает только на выделенном сервере: в одиночной игре мессенджера нет
 * (кроме режима разработки: переменная окружения {@code BLOCKPHONE_DEV_MESSENGER=1}).
 */
public final class FabricServerHandler {
    private static volatile @Nullable BlockPhoneServer core;
    private static @Nullable FabricPlatform platform;
    /**
     * Игроки с момента события входа. В JOIN игрок ещё не в списке игроков сервера,
     * поэтому для отправки приветствия держим ссылку сами.
     */
    private static final Map<UUID, ServerPlayer> PLAYERS = new ConcurrentHashMap<>();

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (!server.isDedicatedServer() && !devMessenger()) {
                return;
            }
            FabricServerConfig config = FabricServerConfig.load();
            // Simple Voice Chat: действия с группами — в потоке сервера.
            dev.blockphone.common.voice.VoiceBridges.setMainThread(server::execute);
            platform = new FabricPlatform(server);
            core = new BlockPhoneServer(platform, config.toSettings());
            core.setAppsDirectory(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("blockphone").resolve("apps"));
            core.start();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (core != null) {
                core.stop();
                core = null;
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PLAYERS.put(handler.player.getUUID(), handler.player);
            if (core != null && ServerPlayNetworking.canSend(handler.player, RawPayload.TYPE)) {
                core.onChannelReady(handler.player.getUUID());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PLAYERS.remove(handler.player.getUUID());
            if (core != null) {
                core.onQuit(handler.player.getUUID());
            }
        });

        // /blockphone reload — перечитать config/blockphone/server.json без перезапуска сервера.
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(net.minecraft.commands.Commands.literal("blockphone")
                        .then(net.minecraft.commands.Commands.literal("reload")
                                .requires(FabricServerHandler::canReload)
                                .executes(context -> {
                                    var source = context.getSource();
                                    if (core == null || platform == null) {
                                        source.sendFailure(net.minecraft.network.chat.Component.literal(
                                                "BlockPhone server part is not running here"));
                                        return 0;
                                    }
                                    try {
                                        core = BlockPhoneServer.restart(core, platform, FabricServerConfig.load().toSettings());
                                        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("BlockPhone: config reloaded"), true);
                                        return 1;
                                    } catch (RuntimeException e) {
                                        BlockPhoneFabric.LOGGER.warn("BlockPhone reload failed", e);
                                        source.sendFailure(net.minecraft.network.chat.Component.literal("BlockPhone: reload failed — " + e.getMessage()));
                                        return 0;
                                    }
                                }))));

        ServerPlayNetworking.registerGlobalReceiver(RawPayload.TYPE, (payload, context) -> {
            if (core != null) {
                ServerPlayer player = context.player();
                core.onMessage(player.getUUID(), player.getName().getString(), payload.data());
            }
        });
    }

    /** Ядро сервера (null — не выделенный сервер или ещё не запущен). Для отладки. */
    public static @Nullable BlockPhoneServer core() {
        return core;
    }

    /** Консоль — всегда; игрок — узел blockphone.admin.reload (LuckPerms) или оператор уровня 2+. */
    private static boolean canReload(net.minecraft.commands.CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return source.getEntity() == null;
        }
        boolean fallback = dev.blockphone.fabric.compat.Compat.isOperator(player);
        if (PERMISSIONS_CHECK != null) {
            try {
                return (boolean) PERMISSIONS_CHECK.invoke(null, player, BlockPhoneServer.RELOAD_PERMISSION, fallback);
            } catch (ReflectiveOperationException | RuntimeException e) {
                BlockPhoneFabric.LOGGER.warn("Permission check failed for {}", BlockPhoneServer.RELOAD_PERMISSION, e);
            }
        }
        return fallback;
    }

    private static boolean devMessenger() {
        return "1".equals(System.getenv("BLOCKPHONE_DEV_MESSENGER"));
    }

    /**
     * {@code me.lucko.fabric.api.permissions.v0.Permissions.check(Entity, String, boolean)} из fabric-permissions-api
     * (его ставят вместе с LuckPerms). Вызывается через отражение, чтобы мод работал и без него.
     */
    private static final @Nullable Method PERMISSIONS_CHECK = findPermissionsApi();

    private static @Nullable Method findPermissionsApi() {
        try {
            Class<?> api = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            Method method = api.getMethod("check", Entity.class, String.class, boolean.class);
            BlockPhoneFabric.LOGGER.info("BlockPhone: using fabric-permissions-api for permissions");
            return method;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Доставка пакетов на Fabric: в поток сервера, затем в соединение игрока. */
    private record FabricPlatform(MinecraftServer server) implements ServerPlatform {
        @Override
        public String name() {
            return "fabric";
        }

        @Override
        public void send(UUID player, byte[] data) {
            server.execute(() -> {
                ServerPlayer target = PLAYERS.get(player);
                if (target != null && !target.hasDisconnected()) {
                    ServerPlayNetworking.send(target, new RawPayload(data));
                }
            });
        }

        @Override
        public void runOnMain(Runnable task) {
            server.execute(task);
        }

        @Override
        public java.util.Map<UUID, Position> positions() {
            java.util.Map<UUID, Position> result = new java.util.HashMap<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                result.put(player.getUUID(), new Position(player.level().dimension().identifier().toString(), player.getX(), player.getY(),
                        player.getZ()));
            }
            return result;
        }

        @Override
        public java.util.Collection<UUID> onlinePlayers() {
            java.util.List<UUID> result = new java.util.ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                result.add(player.getUUID());
            }
            return result;
        }

        /** Текст с кодами § — как в ванильном чате (коды форматирования работают и в обычном тексте). */
        @Override
        public void sendChat(UUID player, String text) {
            ServerPlayer target = server.getPlayerList().getPlayer(player);
            if (target != null) {
                target.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
            }
        }

        @Override
        public boolean runConsoleCommand(String command) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            return true;
        }

        /** fabric-permissions-api (LuckPerms), иначе: обычные права — у всех, модераторские — у операторов уровня 2+. */
        @Override
        public boolean hasPermission(UUID player, Permission permission) {
            ServerPlayer target = PLAYERS.get(player);
            if (target == null) {
                return permission.defaultAllowed();
            }
            boolean fallback = permission.defaultAllowed()
                    || dev.blockphone.fabric.compat.Compat.isOperator(target);
            if (PERMISSIONS_CHECK != null) {
                try {
                    return (boolean) PERMISSIONS_CHECK.invoke(null, target, permission.node(), fallback);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    BlockPhoneFabric.LOGGER.warn("Permission check failed for {}", permission.node(), e);
                }
            }
            return fallback;
        }

        @Override
        public boolean hasPermissionNode(UUID player, String node, boolean defaultValue) {
            ServerPlayer target = PLAYERS.get(player);
            if (target == null) {
                return defaultValue;
            }
            boolean fallback = defaultValue || dev.blockphone.fabric.compat.Compat.isOperator(target);
            if (PERMISSIONS_CHECK != null) {
                try {
                    return (boolean) PERMISSIONS_CHECK.invoke(null, target, node, fallback);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    BlockPhoneFabric.LOGGER.warn("Permission check failed for {}", node, e);
                }
            }
            return fallback;
        }

        /** Команда как из чата игрока (его права) или из консоли сервера. */
        @Override
        public boolean runCommand(UUID player, String command, boolean console) {
            ServerPlayer target = PLAYERS.get(player);
            if (target == null || target.hasDisconnected()) {
                return false;
            }
            server.getCommands().performPrefixedCommand(console ? server.createCommandSourceStack() : target.createCommandSourceStack(),
                    command);
            return true;
        }

        @Override
        public void info(String message) {
            BlockPhoneFabric.LOGGER.info(message);
        }

        @Override
        public void warn(String message, Throwable error) {
            BlockPhoneFabric.LOGGER.warn(message, error);
        }
    }

    private FabricServerHandler() {
    }
}
