package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.ProtocolException;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная часть BlockPhone, общая для всех платформ: рукопожатие, учёт игроков с модом
 * и передача пакетов мессенджеру. Платформа только сообщает о событиях и доставляет байты.
 *
 * <pre>
 * сервер: onChannelReady → ServerHello → клиент: ClientHello → onJoin мессенджера → ChatList
 * </pre>
 */
public final class BlockPhoneServer {
    private final ServerPlatform platform;
    private final ServerSettings settings;
    private final MessengerService messenger;
    /** Игроки, приславшие ClientHello с совместимой версией: UUID → ник. */
    private final Map<UUID, String> modded = new ConcurrentHashMap<>();

    public BlockPhoneServer(ServerPlatform platform, ServerSettings settings) {
        this.platform = platform;
        this.settings = settings;
        this.messenger = settings.messengerEnabled() ? new MessengerService(platform, settings.storage()) : null;
    }

    public void start() {
        if (messenger != null) {
            messenger.start();
        }
        platform.info("BlockPhone server started (" + platform.name() + ", protocol " + BlockPhone.PROTOCOL_VERSION
                + ", messenger " + (messenger != null ? "on, storage " + settings.storage().type() : "off") + ")");
    }

    public void stop() {
        if (messenger != null) {
            messenger.stop();
        }
        modded.clear();
    }

    public MessengerService messenger() {
        return messenger;
    }

    public ServerSettings settings() {
        return settings;
    }

    /** Клиент может принимать наш канал — здороваемся. */
    public void onChannelReady(UUID player) {
        ServerHelloPacket hello = new ServerHelloPacket(BlockPhone.PROTOCOL_VERSION, platform.name(),
                messenger != null, settings.phoneInHandVisible());
        platform.send(player, Packets.encode(hello));
    }

    public void onQuit(UUID player) {
        if (modded.remove(player) != null && messenger != null) {
            messenger.onQuit(player);
        }
    }

    public boolean hasMod(UUID player) {
        return modded.containsKey(player);
    }

    /** Байты из канала blockphone:main от игрока. */
    public void onMessage(UUID player, String name, byte[] data) {
        Packet packet;
        try {
            packet = Packets.decode(data);
        } catch (ProtocolException | IndexOutOfBoundsException e) {
            platform.warn("Bad packet from " + name + ": " + e.getMessage(), null);
            return;
        }
        if (packet instanceof ClientHelloPacket hello) {
            if (hello.protocolVersion() != BlockPhone.PROTOCOL_VERSION) {
                platform.info(name + " has incompatible BlockPhone " + hello.modVersion() + " (protocol "
                        + hello.protocolVersion() + ", server " + BlockPhone.PROTOCOL_VERSION + ")");
                return;
            }
            boolean first = modded.put(player, name) == null;
            if (first) {
                platform.info(name + " joined with BlockPhone " + hello.modVersion());
            }
            if (messenger != null) {
                messenger.onJoin(player, name);
            }
            return;
        }
        if (messenger != null && modded.containsKey(player)) {
            messenger.handle(player, packet);
        }
    }
}
