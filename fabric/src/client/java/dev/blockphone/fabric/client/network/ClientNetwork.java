package dev.blockphone.fabric.client.network;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.ProtocolException;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.network.RawPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jetbrains.annotations.Nullable;

/** Клиентская сеть: приём пакетов сервера и состояние «есть ли BlockPhone на сервере». */
public final class ClientNetwork {
    /** Приветствие сервера; {@code null}, если сервер без BlockPhone (или одиночная игра). */
    private static @Nullable ServerHelloPacket serverHello;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(RawPayload.TYPE, (payload, context) -> {
            Packet packet;
            try {
                packet = Packets.decode(payload.data());
            } catch (ProtocolException e) {
                BlockPhoneFabric.LOGGER.warn("Bad packet from server: {}", e.getMessage());
                return;
            }
            handle(packet);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            serverHello = null;
            PhoneState.reset();
            MessengerClient.reset();
        });
    }

    private static void handle(Packet packet) {
        if (packet instanceof ServerHelloPacket hello) {
            serverHello = hello;
            BlockPhoneFabric.LOGGER.info("Server has BlockPhone ({}, protocol {})", hello.serverPlatform(), hello.protocolVersion());
            if (hello.protocolVersion() != BlockPhone.PROTOCOL_VERSION) {
                BlockPhoneFabric.LOGGER.warn("Server BlockPhone protocol {} differs from ours ({}); messenger disabled",
                        hello.protocolVersion(), BlockPhone.PROTOCOL_VERSION);
                return;
            }
            // Через прокси пакеты могут прийти повторно при переходе между серверами — начинаем заново.
            MessengerClient.reset();
            send(new ClientHelloPacket(BlockPhone.PROTOCOL_VERSION, BlockPhoneFabric.VERSION));
        } else {
            MessengerClient.handle(packet);
        }
    }

    /**
     * Отправить пакет серверу. Если сервер поздоровался, шлём даже без объявленного канала:
     * в сети серверов канал обрабатывает прокси (Velocity), а сервер мира его может не знать.
     */
    public static void send(Packet packet) {
        if (serverHello != null || ClientPlayNetworking.canSend(RawPayload.TYPE)) {
            ClientPlayNetworking.send(new RawPayload(Packets.encode(packet)));
        }
    }

    public static @Nullable ServerHelloPacket serverHello() {
        return serverHello;
    }

    /** Сервер с совместимой версией BlockPhone и включённым мессенджером. */
    public static boolean messengerAvailable() {
        return serverHello != null && serverHello.messengerEnabled()
                && serverHello.protocolVersion() == BlockPhone.PROTOCOL_VERSION;
    }

    private ClientNetwork() {
    }
}
