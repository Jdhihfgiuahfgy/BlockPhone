package dev.blockphone.common.protocol.data;

import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;

/**
 * Картинка, хранящаяся на сервере (фото в посте или сообщении). Сами байты клиент запрашивает отдельно
 * ({@link dev.blockphone.common.protocol.packet.ImagePackets.RequestImage}), размер нужен заранее для раскладки.
 */
public record ImageRef(long id, int width, int height) {
    /** Размер куска картинки в одном пакете (клиент → сервер в ванилле не больше ~32 КБ). */
    public static final int CHUNK = 30_000;
    /** Абсолютный предел размера файла (настройка сервера может быть меньше). */
    public static final int HARD_MAX_BYTES = 4 * 1024 * 1024;

    public static ImageRef read(PacketReader r) {
        return new ImageRef(r.readLong(), r.readVarInt(), r.readVarInt());
    }

    public static void write(PacketWriter w, ImageRef image) {
        w.writeLong(image.id).writeVarInt(image.width).writeVarInt(image.height);
    }

    /** Политика фото на сервере (задаёт администратор). */
    public enum PhotoPolicy {
        /** Любые картинки из галереи. */
        ANY,
        /** Только снимки, сделанные камерой BlockPhone на этом сервере (в сети серверов — в этой сети). */
        SERVER,
        /** Фото запрещены. */
        NONE;

        public static PhotoPolicy parse(String value) {
            if (value == null) {
                return SERVER;
            }
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "any", "all" -> ANY;
                case "none", "off", "false" -> NONE;
                default -> SERVER;
            };
        }
    }

    /** Для чего загружается картинка (у ленты свои ограничения — заглушение, право на фото; у стикеров — свои размеры). */
    public enum Purpose { POST, CHAT, STICKER }
}
