package dev.blockphone.common.protocol.data;

import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;

import java.util.List;

/** Стикеры мессенджера: наборы игроков (картинки на сервере, вид KIND_STICKER). */
public final class StickerData {
    public static final int MAX_TITLE = 32;
    public static final int HARD_MAX_PER_PACK = 120;
    public static final int HARD_MAX_PACKS = 100;

    /** Стикер: id, набор (0 — набор удалён), картинка. */
    public record StickerRef(long id, long packId, ImageRef image) {
        public static StickerRef read(PacketReader r) {
            return new StickerRef(r.readLong(), r.readLong(), ImageRef.read(r));
        }

        public static void write(PacketWriter w, StickerRef s) {
            w.writeLong(s.id).writeLong(s.packId);
            ImageRef.write(w, s.image);
        }
    }

    /** Набор: own — мой, installed — добавлен у получателя пакета. */
    public record PackInfo(long id, String title, UserInfo owner, boolean own, boolean installed, List<StickerRef> stickers) {
        public static PackInfo read(PacketReader r) {
            return new PackInfo(r.readLong(), r.readString(MAX_TITLE * 2), UserInfo.read(r), r.readBoolean(), r.readBoolean(),
                    r.readList(HARD_MAX_PER_PACK, StickerRef::read));
        }

        public static void write(PacketWriter w, PackInfo p) {
            w.writeLong(p.id).writeString(p.title);
            UserInfo.write(w, p.owner);
            w.writeBoolean(p.own).writeBoolean(p.installed).writeList(p.stickers, StickerRef::write);
        }
    }

    /**
     * Ограничения сервера (задаёт админ).
     *
     * @param enabled    стикеры включены и у игрока есть право
     * @param maxBytes   предел файла стикера
     * @param maxSide    предел стороны (телефон уменьшает сам)
     * @param maxPacks   своих наборов на игрока
     * @param maxPerPack стикеров в наборе
     */
    public record Limits(boolean enabled, int maxBytes, int maxSide, int maxPacks, int maxPerPack) {
        public static Limits read(PacketReader r) {
            return new Limits(r.readBoolean(), r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readVarInt());
        }

        public static void write(PacketWriter w, Limits l) {
            w.writeBoolean(l.enabled).writeVarInt(l.maxBytes).writeVarInt(l.maxSide).writeVarInt(l.maxPacks).writeVarInt(l.maxPerPack);
        }
    }

    private StickerData() {
    }
}
