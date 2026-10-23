package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.ProtocolException;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.AppPackets;
import dev.blockphone.common.protocol.packet.PhoneUsePackets;
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
    /** Кто сейчас с телефоном в руках (для «телефона в руке» у других). */
    private final java.util.Set<UUID> phoneUsers = ConcurrentHashMap.newKeySet();
    private final AppService apps;
    private java.nio.file.Path appsDir;

    public BlockPhoneServer(ServerPlatform platform, ServerSettings settings) {
        this(platform, settings, dev.blockphone.common.voice.VoiceBridges::current);
    }

    /** bridges — откуда брать голосовой мост для звонков (тесты подставляют заглушку). */
    public BlockPhoneServer(ServerPlatform platform, ServerSettings settings,
                            java.util.function.Supplier<dev.blockphone.common.voice.VoiceBridge> bridges) {
        this.platform = platform;
        this.settings = settings;
        this.backend = settings.backendEnabled() ? new Backend(platform, settings, bridges) : null;
        this.apps = new AppService(platform, settings.apps(), modded);
    }

    /** Папка своих приложений (вызвать до {@link #start()}; null — только приложения плагинов). */
    public void setAppsDirectory(java.nio.file.Path dir) {
        this.appsDir = dir;
    }

    public AppService apps() {
        return apps;
    }

    public void start() {
        if (backend != null) {
            backend.start();
        }
        apps.start(appsDir);
        platform.info("BlockPhone server started (" + platform.name() + ", protocol " + BlockPhone.PROTOCOL_VERSION
                + ", messenger " + (settings.messengerEnabled() ? "on" : "off")
                + ", feed " + (settings.social().enabled() ? "on, photos " + settings.social().photos() : "off")
                + (backend != null ? ", storage " + settings.storage().type() : "") + ")");
    }

    public void stop() {
        apps.stop();
        if (backend != null) {
            backend.stop();
        }
        modded.clear();
    }

    /** Право на команду {@code /blockphone reload} (по умолчанию — операторы и консоль). */
    public static final String RELOAD_PERMISSION = "blockphone.admin.reload";

    /**
     * Перезагрузка настроек: старое ядро останавливается (база закрывается), новое запускается с новыми настройками,
     * а игрокам с модом заново отправляется приветствие — телефон сбрасывает серверное состояние и здоровается снова.
     * Вызывать в главном потоке платформы.
     */
    public static BlockPhoneServer restart(BlockPhoneServer old, ServerPlatform platform, ServerSettings settings) {
        java.util.Set<UUID> players = old != null ? java.util.Set.copyOf(old.modded.keySet()) : java.util.Set.of();
        java.nio.file.Path apps = old != null ? old.appsDir : null;
        if (old != null) {
            old.stop();
        }
        BlockPhoneServer next = new BlockPhoneServer(platform, settings);
        next.setAppsDirectory(apps);
        next.start();
        for (UUID player : players) {
            next.onChannelReady(player);
        }
        platform.info("BlockPhone config reloaded (" + players.size() + " players with the mod re-greeted)");
        return next;
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
        if (phoneUsers.remove(player)) {
            broadcastPhoneUse(player, false);
        }
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
            if (settings.phoneInHandVisible()) {
                for (UUID user : phoneUsers) {
                    platform.send(player, Packets.encode(new PhoneUsePackets.State(user, true)));
                }
            }
            if (backend != null) {
                backend.onJoin(player, name);
            }
            apps.sendPolicy(player);
            return;
        }
        if (packet.getClass().getEnclosingClass() == AppPackets.class) {
            if (modded.containsKey(player)) {
                apps.handle(player, packet);
            }
            return;
        }
        if (packet instanceof PhoneUsePackets.InUse use) {
            if (modded.containsKey(player) && settings.phoneInHandVisible()
                    && (use.using() ? phoneUsers.add(player) : phoneUsers.remove(player))) {
                broadcastPhoneUse(player, use.using());
            }
            return;
        }
        if (backend != null && modded.containsKey(player)) {
            backend.handle(player, packet);
        }
    }

    /** Разослать всем игрокам с модом (кроме самого игрока), что он взял/убрал телефон. */
    private void broadcastPhoneUse(UUID player, boolean using) {
        byte[] data = Packets.encode(new PhoneUsePackets.State(player, using));
        for (UUID other : modded.keySet()) {
            if (!other.equals(player)) {
                platform.send(other, data);
            }
        }
    }
}
