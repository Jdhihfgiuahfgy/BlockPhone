package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;

/**
 * Сервер → клиент, сразу после входа: «на сервере стоит BlockPhone».
 * Клиент отвечает {@link ClientHelloPacket}.
 *
 * @param protocolVersion    версия протокола сервера
 * @param serverPlatform     "fabric" или "paper" (для отладки)
 * @param messengerEnabled   включён ли серверный мессенджер
 * @param phoneInHandVisible показывать ли другим игрокам телефон в руке
 * @param socialEnabled      включена ли лента (посты, паблики, профили)
 */
public record ServerHelloPacket(int protocolVersion, String serverPlatform, boolean messengerEnabled,
                                boolean phoneInHandVisible, boolean socialEnabled) implements Packet {

    public static ServerHelloPacket read(PacketReader reader) {
        int version = reader.readVarInt();
        String platform = reader.readString();
        boolean messenger = reader.readBoolean();
        boolean phone = reader.readBoolean();
        // У серверов со старым протоколом поля ленты нет.
        boolean social = reader.remaining() > 0 && reader.readBoolean();
        return new ServerHelloPacket(version, platform, messenger, phone, social);
    }

    @Override
    public void write(PacketWriter writer) {
        writer.writeVarInt(protocolVersion).writeString(serverPlatform)
                .writeBoolean(messengerEnabled).writeBoolean(phoneInHandVisible).writeBoolean(socialEnabled);
    }
}
