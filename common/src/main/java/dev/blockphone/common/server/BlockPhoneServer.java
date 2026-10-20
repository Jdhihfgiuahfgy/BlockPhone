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
 * и передача пакетов бэкенду (мессенджер, лента, фото). Платформа только сообщает о событиях и доставляет байты.
 *
 * <pre>
 * сервер: onChannelReady → ServerHello → клиент: ClientHello → Backend.onJoin → Session, ChatList
 * </pre>
 */
public final class BlockPhoneServer {
    private final ServerPlatform platform;
    private final ServerSettings settings;
    private final Backend backend;
    /** Игроки, приславшие ClientHello с совместимой версией: UUID → ник. */
    private final Map<UUID, String> modded = new ConcurrentHashMap<>();

    public BlockPhoneServer(ServerPlatform platform, ServerSettings settings) {
        this.platform = platform;
        this.settings = settings;
        this.backend = settings.backendEnabled() ? new Backend(platform, settings) : null;
    }

    public void start() {
        if (backend != null) {
            backend.start();
        }
        platform.info("BlockPhone server started (" + platform.name() + ", protocol " + BlockPhone.PROTOCOL_VERSION
                + ", messenger " + (settings.messengerEnabled() ? "on" : "off")
                + ", feed " + (settings.social().enabled() ? "on, photos " + settings.social().photos() : "off")
                + (backend != null ? ", storage " + settings.storage().type() : "") + ")");
    }

    public void stop() {
        if (backend != null) {
            backend.stop();
        }
        modded.clear();
    }

    /** Бэкенд с базой данных (null — мессенджер и лента выключены). */
    public Backend backend() {
        return backend;
    }

    public MessengerService messenger() {
        return backend != null ? backend.messenger() : null;
    }

    public SocialService social() {
        return backend != null ? backend.social() : null;
    }

    public ServerSettings settings() {
        return settings;
    }

    /** Клиент может принимать наш канал — здороваемся. */
    public void onChannelReady(UUID player) {
        ServerHelloPacket hello = new ServerHelloPacket(BlockPhone.PROTOCOL_VERSION, platform.name(),
                settings.messengerEnabled(), settings.phoneInHandVisible(), settings.social().enabled());
        platform.send(player, Packets.encode(hello));
    }

    public void onQuit(UUID player) {
        if (modded.remove(player) != null && backend != null) {
            backend.onQuit(player);
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
            if (backend != null) {
                backend.onJoin(player, name);
            }
            return;
        }
        if (backend != null && modded.containsKey(player)) {
            backend.handle(player, packet);
        }
    }
}
