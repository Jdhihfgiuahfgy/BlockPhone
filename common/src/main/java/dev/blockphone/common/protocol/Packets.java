package dev.blockphone.common.protocol;

import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.MessengerPackets;
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
        // Рукопожатие
        register(0, ServerHelloPacket.class, ServerHelloPacket::read);
        register(1, ClientHelloPacket.class, ClientHelloPacket::read);
        // Мессенджер: сервер → клиент
        register(2, MessengerPackets.ChatList.class, MessengerPackets.ChatList::read);
        register(3, MessengerPackets.ChatUpdate.class, MessengerPackets.ChatUpdate::read);
        register(4, MessengerPackets.ChatRemoved.class, MessengerPackets.ChatRemoved::read);
        register(5, MessengerPackets.History.class, MessengerPackets.History::read);
        register(6, MessengerPackets.NewMessage.class, MessengerPackets.NewMessage::read);
        register(7, MessengerPackets.MessageUpdated.class, MessengerPackets.MessageUpdated::read);
        register(8, MessengerPackets.UserList.class, MessengerPackets.UserList::read);
        register(9, MessengerPackets.ChatOpened.class, MessengerPackets.ChatOpened::read);
        register(10, MessengerPackets.TypingState.class, MessengerPackets.TypingState::read);
        register(11, MessengerPackets.Presence.class, MessengerPackets.Presence::read);
        register(12, MessengerPackets.Failure.class, MessengerPackets.Failure::read);
        // Мессенджер: клиент → сервер
        register(13, MessengerPackets.RequestHistory.class, MessengerPackets.RequestHistory::read);
        register(14, MessengerPackets.SendMessage.class, MessengerPackets.SendMessage::read);
        register(15, MessengerPackets.EditMessage.class, MessengerPackets.EditMessage::read);
        register(16, MessengerPackets.DeleteMessage.class, MessengerPackets.DeleteMessage::read);
        register(17, MessengerPackets.MarkRead.class, MessengerPackets.MarkRead::read);
        register(18, MessengerPackets.SearchUsers.class, MessengerPackets.SearchUsers::read);
        register(19, MessengerPackets.OpenDirect.class, MessengerPackets.OpenDirect::read);
        register(20, MessengerPackets.CreateGroup.class, MessengerPackets.CreateGroup::read);
        register(21, MessengerPackets.GroupAction.class, MessengerPackets.GroupAction::read);
        register(22, MessengerPackets.Typing.class, MessengerPackets.Typing::read);
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
