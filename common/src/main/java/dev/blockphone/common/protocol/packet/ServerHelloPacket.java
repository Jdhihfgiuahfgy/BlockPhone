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
 */
public record ServerHelloPacket(int protocolVersion, String serverPlatform, boolean messengerEnabled,
                                boolean phoneInHandVisible) implements Packet {

    public static ServerHelloPacket read(PacketReader reader) {
        return new ServerHelloPacket(reader.readVarInt(), reader.readString(), reader.readBoolean(), reader.readBoolean());
    }

    @Override
    public void write(PacketWriter writer) {
        writer.writeVarInt(protocolVersion).writeString(serverPlatform)
                .writeBoolean(messengerEnabled).writeBoolean(phoneInHandVisible);
    }
}
