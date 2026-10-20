package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;

import java.util.UUID;

/**
 * «Телефон в руке»: клиент сообщает, что открыл/закрыл телефон, сервер рассылает это остальным игрокам с модом
 * (если в настройках сервера включено phone-in-hand.visible). Клиенты рисуют телефон в руке — настоящий предмет не меняется.
 */
public final class PhoneUsePackets {

    /** Клиент → сервер: я пользуюсь телефоном (или перестал). */
    public record InUse(boolean using) implements Packet {
        public static InUse read(PacketReader r) {
            return new InUse(r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeBoolean(using);
        }
    }

    /** Сервер → клиент: игрок пользуется телефоном (или перестал). */
    public record State(UUID player, boolean using) implements Packet {
        public static State read(PacketReader r) {
            return new State(r.readUuid(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(player).writeBoolean(using);
        }
    }

    private PhoneUsePackets() {
    }
}
