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

    /**
     * Клиент → сервер: я пользуюсь телефоном (или перестал); sideways — держу набок двумя руками (приложение набок, игра на
     * экране). sideways — необязательный байт в конце: старые сервера его не читают, от старых клиентов — false.
     */
    public record InUse(boolean using, boolean sideways) implements Packet {
        public InUse(boolean using) {
            this(using, false);
        }

        public static InUse read(PacketReader r) {
            boolean using = r.readBoolean();
            return new InUse(using, r.remaining() > 0 && r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeBoolean(using).writeBoolean(sideways);
        }
    }

    /** Сервер → клиент: игрок пользуется телефоном (или перестал), sideways — набок (необязательный байт в конце). */
    public record State(UUID player, boolean using, boolean sideways) implements Packet {
        public State(UUID player, boolean using) {
            this(player, using, false);
        }

        public static State read(PacketReader r) {
            UUID player = r.readUuid();
            boolean using = r.readBoolean();
            return new State(player, using, r.remaining() > 0 && r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(player).writeBoolean(using).writeBoolean(sideways);
        }
    }

    private PhoneUsePackets() {
    }
}
