package dev.blockphone.common.protocol;

import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Реестр пакетов. Формат сообщения в канале: VarInt id + тело пакета.
 * Новые пакеты добавлять только в конец, id не переиспользовать.
 */
public final class Packets {
    private static final Map<Integer, Function<PacketReader, ? extends Packet>> DECODERS = new HashMap<>();
    private static final Map<Class<? extends Packet>, Integer> IDS = new HashMap<>();

    static {
        // Сервер → клиент
        register(0, ServerHelloPacket.class, ServerHelloPacket::read);
        // Клиент → сервер
        register(1, ClientHelloPacket.class, ClientHelloPacket::read);
    }

    private static <T extends Packet> void register(int id, Class<T> type, Function<PacketReader, T> decoder) {
        DECODERS.put(id, decoder);
        IDS.put(type, id);
    }

    public static byte[] encode(Packet packet) {
        Integer id = IDS.get(packet.getClass());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered packet " + packet.getClass().getName());
        }
        PacketWriter writer = new PacketWriter().writeVarInt(id);
        packet.write(writer);
        return writer.toByteArray();
    }

    public static Packet decode(byte[] data) {
        PacketReader reader = new PacketReader(data);
        int id = reader.readVarInt();
        Function<PacketReader, ? extends Packet> decoder = DECODERS.get(id);
        if (decoder == null) {
            throw new ProtocolException("Unknown packet id " + id);
        }
        return decoder.apply(reader);
    }

    private Packets() {
    }
}
