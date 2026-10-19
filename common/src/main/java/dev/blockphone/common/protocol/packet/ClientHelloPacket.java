package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;

/** Клиент → сервер, ответ на {@link ServerHelloPacket}: «у меня стоит мод такой-то версии». */
public record ClientHelloPacket(int protocolVersion, String modVersion) implements Packet {

    public static ClientHelloPacket read(PacketReader reader) {
        return new ClientHelloPacket(reader.readVarInt(), reader.readString());
    }

    @Override
    public void write(PacketWriter writer) {
        writer.writeVarInt(protocolVersion).writeString(modVersion);
    }
}
