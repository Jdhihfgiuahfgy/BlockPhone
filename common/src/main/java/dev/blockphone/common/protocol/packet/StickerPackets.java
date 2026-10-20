package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.StickerData;
import dev.blockphone.common.protocol.data.StickerData.Limits;
import dev.blockphone.common.protocol.data.StickerData.PackInfo;

import java.util.List;

/** Стикеры (обрабатывает {@code StickerService}; ошибки — {@link MessengerPackets.Failure} с ключами sticker.error.*). */
public final class StickerPackets {

    /** C→S: мои наборы (свои и добавленные). */
    public record RequestMine() implements Packet {
        public static RequestMine read(PacketReader r) {
            return new RequestMine();
        }

        @Override
        public void write(PacketWriter w) {
        }
    }

    /** S→C: ограничения сервера и мои наборы (после запроса и каждого изменения). */
    public record Mine(Limits limits, List<PackInfo> packs) implements Packet {
        public static Mine read(PacketReader r) {
            return new Mine(Limits.read(r), r.readList(StickerData.HARD_MAX_PACKS * 2, PackInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            Limits.write(w, limits);
            w.writeList(packs, PackInfo::write);
        }
    }

    /** C→S: показать набор (например, по стикеру из чата). */
    public record RequestPack(long packId) implements Packet {
        public static RequestPack read(PacketReader r) {
            return new RequestPack(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(packId);
        }
    }

    /** S→C: набор. */
    public record Pack(PackInfo pack) implements Packet {
        public static Pack read(PacketReader r) {
            return new Pack(PackInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            PackInfo.write(w, pack);
        }
    }

    public enum PackAction { CREATE, RENAME, DELETE, INSTALL, UNINSTALL }

    /** C→S: действие с набором (CREATE — packId 0 и title; RENAME — title). */
    public record EditPack(PackAction action, long packId, String title) implements Packet {
        public static EditPack read(PacketReader r) {
            return new EditPack(r.readEnum(PackAction.values()), r.readLong(), r.readString(StickerData.MAX_TITLE));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeEnum(action).writeLong(packId).writeString(title);
        }
    }

    /** C→S: добавить в свой набор загруженную картинку (ImagePackets с назначением STICKER). */
    public record AddSticker(long packId, long imageId) implements Packet {
        public static AddSticker read(PacketReader r) {
            return new AddSticker(r.readLong(), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(packId).writeLong(imageId);
        }
    }

    /** C→S: убрать стикер из своего набора. */
    public record RemoveSticker(long stickerId) implements Packet {
        public static RemoveSticker read(PacketReader r) {
            return new RemoveSticker(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(stickerId);
        }
    }

    private StickerPackets() {
    }
}
