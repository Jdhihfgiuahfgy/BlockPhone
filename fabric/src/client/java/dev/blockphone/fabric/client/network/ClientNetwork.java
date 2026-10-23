package dev.blockphone.fabric.client.network;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.ProtocolException;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.common.protocol.packet.PhoneUsePackets;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.fabric.client.voice.CallClient;
import dev.blockphone.fabric.client.voice.VoiceMessages;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.RemoteImages;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.phone.PhoneHolders;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.network.RawPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jetbrains.annotations.Nullable;

/** Клиентская сеть: приём пакетов сервера и состояние «есть ли BlockPhone на сервере». */
public final class ClientNetwork {
    /** Приветствие сервера; {@code null}, если сервер без BlockPhone (или одиночная игра). */
    private static volatile @Nullable ServerHelloPacket serverHello;

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

        // Событие приходит из сетевого потока, а текстуры освобождать можно только в потоке рендера.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientNetwork::forgetServer));
        // Новое подключение (сервер или одиночная игра) начинается «без BlockPhone», пока сервер не поздоровается.
        // Раньше состояние прошлого сервера оставалось, если событие отключения не пришло (FastQuit и т. п.).
        // INIT приходит до любых пакетов, а сброс встаёт в очередь потока рендера раньше приветствия.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            serverHello = null;
            client.execute(ClientNetwork::forgetServer);
        });
    }

    /** Вышли из мира или подключаемся заново: забыть сервер (вызывать в потоке рендера). */
    public static void forgetServer() {
        serverHello = null;
        // Вышли из мира — музыка и голосовые замолкают (то же — в BlockPhoneClient по исчезновению мира).
        dev.blockphone.fabric.client.music.MusicPlayer.stop();
        VoiceMessages.stop();
        PhoneState.reset();
        resetServerState();
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
            resetServerState();
            send(new ClientHelloPacket(BlockPhone.PROTOCOL_VERSION, BlockPhoneFabric.VERSION));
            return;
        }
        Class<?> group = packet.getClass().getEnclosingClass();
        if (packet instanceof SessionPacket session) {
            ClientSession.set(session);
            SocialClient.onSession();
        } else if (group == SocialPackets.class) {
            SocialClient.handle(packet);
        } else if (group == dev.blockphone.common.protocol.packet.ModerationPackets.class) {
            dev.blockphone.fabric.client.social.ModerationClient.handle(packet);
        } else if (packet instanceof ImagePackets.UploadResult result) {
            ImageUploads.onResult(result);
        } else if (packet instanceof ImagePackets.ImageData data) {
            RemoteImages.onData(data);
        } else if (packet instanceof VoicePackets.UploadResult result) {
            VoiceMessages.onUploadResult(result);
        } else if (packet instanceof VoicePackets.VoiceChunk chunk) {
            VoiceMessages.onChunk(chunk);
        } else if (packet instanceof dev.blockphone.common.protocol.packet.PrivacyPackets.State state) {
            dev.blockphone.fabric.client.social.PrivacyClient.onState(state);
        } else if (group == dev.blockphone.common.protocol.packet.StickerPackets.class) {
            dev.blockphone.fabric.client.messenger.StickerClient.handle(packet);
        } else if (packet instanceof dev.blockphone.common.protocol.packet.AppPackets.Policy policy) {
            dev.blockphone.fabric.client.apps.custom.CustomApps.onPolicy(policy);
        } else if (packet instanceof dev.blockphone.common.protocol.packet.AppPackets.Variables variables) {
            dev.blockphone.fabric.client.apps.custom.CustomApps.onVariables(variables);
        } else if (packet instanceof dev.blockphone.common.protocol.packet.AppPackets.CallResult result) {
            dev.blockphone.fabric.client.apps.custom.CustomApps.onCallResult(result);
        } else if (packet instanceof dev.blockphone.common.protocol.packet.AppPackets.Relayed relayed) {
            dev.blockphone.fabric.client.apps.custom.CustomApps.onRelayed(relayed);
        } else if (packet instanceof dev.blockphone.common.protocol.packet.AppPackets.Players players) {
            dev.blockphone.fabric.client.apps.custom.CustomApps.onPlayers(players);
        } else if (packet instanceof PhoneUsePackets.State state) {
            PhoneHolders.handle(state);
        } else if (packet instanceof VoicePackets.CallState state) {
            CallClient.handle(state);
        } else if (packet instanceof VoicePackets.CallAudioFrom audio) {
            dev.blockphone.fabric.client.voice.CallAudio.onAudio(audio);
        } else {
            MessengerClient.handle(packet);
        }
    }

    /** Всё, что относится к текущему серверу (чаты, лента, загрузки, сессия). */
    private static void resetServerState() {
        MessengerClient.reset();
        SocialClient.reset();
        dev.blockphone.fabric.client.social.ModerationClient.reset();
        ImageUploads.reset();
        RemoteImages.reset();
        VoiceMessages.reset();
        CallClient.reset();
        ClientSession.reset();
        PhoneHolders.reset();
        dev.blockphone.fabric.client.social.PrivacyClient.reset();
        dev.blockphone.fabric.client.apps.custom.CustomApps.reset();
        dev.blockphone.fabric.client.messenger.StickerClient.reset();
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

    /** Сервер с совместимой версией BlockPhone и включённой лентой. */
    public static boolean socialAvailable() {
        return serverHello != null && serverHello.socialEnabled()
                && serverHello.protocolVersion() == BlockPhone.PROTOCOL_VERSION;
    }

    /** Сервер с совместимой версией BlockPhone и включённым мессенджером. */
    public static boolean messengerAvailable() {
        return serverHello != null && serverHello.messengerEnabled()
                && serverHello.protocolVersion() == BlockPhone.PROTOCOL_VERSION;
    }

    private ClientNetwork() {
    }
}
