package dev.blockphone.fabric.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.ProtocolException;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.network.RawPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная часть мода на Fabric (аналог Paper-плагина).
 * Работает только на выделенном сервере: в одиночной игре мессенджера нет.
 */
public final class FabricServerHandler {
    private static final Set<UUID> MODDED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static FabricServerConfig config = new FabricServerConfig();

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (server.isDedicatedServer()) {
                config = FabricServerConfig.load();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!server.isDedicatedServer() || !ServerPlayNetworking.canSend(handler.player, RawPayload.TYPE)) {
                return;
            }
            send(handler.player, new ServerHelloPacket(BlockPhone.PROTOCOL_VERSION, "fabric",
                    config.messengerEnabled, config.phoneInHandVisible));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MODDED_PLAYERS.remove(handler.player.getUUID()));

        ServerPlayNetworking.registerGlobalReceiver(RawPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Packet packet;
            try {
                packet = Packets.decode(payload.data());
            } catch (ProtocolException e) {
                BlockPhoneFabric.LOGGER.warn("Bad packet from {}: {}", player.getName().getString(), e.getMessage());
                return;
            }
            if (packet instanceof ClientHelloPacket hello) {
                MODDED_PLAYERS.add(player.getUUID());
                BlockPhoneFabric.LOGGER.info("{} joined with BlockPhone {} (protocol {})",
                        player.getName().getString(), hello.modVersion(), hello.protocolVersion());
            }
        });
    }

    public static void send(ServerPlayer player, Packet packet) {
        ServerPlayNetworking.send(player, new RawPayload(Packets.encode(packet)));
    }

    public static boolean hasMod(ServerPlayer player) {
        return MODDED_PLAYERS.contains(player.getUUID());
    }

    private FabricServerHandler() {
    }
}
