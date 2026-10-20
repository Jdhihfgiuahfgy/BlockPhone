package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.ImageRef.PhotoPolicy;

/**
 * Сервер → клиент: сведения о сессии после рукопожатия (и в ответ на {@link Refresh}).
 * Приходит, когда база данных сервера готова.
 *
 * @param serverId       постоянный id сервера (в сети серверов — всей сети); камера записывает его в сведения о снимке,
 *                       по нему работает политика «только фото с этого сервера»
 * @param photoPolicy    какие фото можно прикреплять к постам и сообщениям
 * @param maxPhotoBytes  предел размера файла фото
 * @param maxPhotoSide   предел длинной стороны фото в пикселях
 * @param rights         права игрока ({@link dev.blockphone.common.protocol.data.SocialData.Rights})
 * @param mutedUntil     игрок заглушён в ленте до этого времени (0 — нет)
 * @param openReports    необработанных жалоб (только для модераторов, иначе 0)
 * @param maxVoiceSeconds предел длины голосового сообщения (0 — голосовые выключены)
 * @param callsAvailable на сервере есть Simple Voice Chat и звонки включены
 */
public record SessionPacket(String serverId, boolean socialEnabled, PhotoPolicy photoPolicy, int maxPhotoBytes, int maxPhotoSide,
                            int rights, long mutedUntil, int openReports, int maxVoiceSeconds, boolean callsAvailable) implements Packet {

    public static SessionPacket read(PacketReader r) {
        return new SessionPacket(r.readString(64), r.readBoolean(), r.readEnum(PhotoPolicy.values()), r.readVarInt(), r.readVarInt(),
                r.readVarInt(), r.readLong(), r.readVarInt(), r.readVarInt(), r.readBoolean());
    }

    @Override
    public void write(PacketWriter w) {
        w.writeString(serverId).writeBoolean(socialEnabled).writeEnum(photoPolicy).writeVarInt(maxPhotoBytes).writeVarInt(maxPhotoSide)
                .writeVarInt(rights).writeLong(mutedUntil).writeVarInt(openReports).writeVarInt(maxVoiceSeconds)
                .writeBoolean(callsAvailable);
    }

    /** Клиент → сервер: прислать сведения о сессии заново (права могли измениться, например, в LuckPerms). */
    public record Refresh() implements Packet {
        public static Refresh read(PacketReader r) {
            return new Refresh();
        }

        @Override
        public void write(PacketWriter w) {
        }
    }
}
