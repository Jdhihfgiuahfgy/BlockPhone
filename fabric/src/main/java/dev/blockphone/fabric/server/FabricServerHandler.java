package dev.blockphone.fabric.server;

import dev.blockphone.common.server.BlockPhoneServer;
import dev.blockphone.common.server.ServerPlatform;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.network.RawPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная часть мода на Fabric (аналог Paper-плагина). Вся логика — в common ({@link BlockPhoneServer}).
 * Работает только на выделенном сервере: в одиночной игре мессенджера нет
 * (кроме режима разработки: переменная окружения {@code BLOCKPHONE_DEV_MESSENGER=1}).
 */
public final class FabricServerHandler {
    private static @Nullable BlockPhoneServer core;
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
            core = new BlockPhoneServer(new FabricPlatform(server), config.toSettings());
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

    private static boolean devMessenger() {
        return "1".equals(System.getenv("BLOCKPHONE_DEV_MESSENGER"));
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
