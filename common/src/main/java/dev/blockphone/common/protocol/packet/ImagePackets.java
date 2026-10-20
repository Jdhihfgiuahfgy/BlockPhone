package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.ImageRef;

/**
 * Передача фото кусками по {@link ImageRef#CHUNK} байт.
 * <pre>
 * загрузка:  UploadBegin → UploadChunk × N → (сервер) UploadResult(image) → SendMessage/CreatePost с image.id
 * просмотр:  RequestImage → (сервер) ImageData × N
 * </pre>
 */
public final class ImagePackets {

    /**
     * Клиент → сервер: начало загрузки. serverId — из сведений о снимке (для политики «только с этого сервера»).
     *
     * @param uploadId номер загрузки, придуманный клиентом
     */
    public record UploadBegin(int uploadId, int size, ImageRef.Purpose purpose, String serverId) implements Packet {
        public static UploadBegin read(PacketReader r) {
            return new UploadBegin(r.readVarInt(), r.readVarInt(), r.readEnum(ImageRef.Purpose.values()), r.readString(64));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(uploadId).writeVarInt(size).writeEnum(purpose).writeString(serverId);
        }
    }

    public record UploadChunk(int uploadId, int offset, byte[] data) implements Packet {
        public static UploadChunk read(PacketReader r) {
            return new UploadChunk(r.readVarInt(), r.readVarInt(), r.readBytes(ImageRef.CHUNK));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(uploadId).writeVarInt(offset).writeBytes(data);
        }
    }

    /** Сервер → клиент: загрузка завершена (image) или отклонена (image = null, error — ключ перевода). */
    public record UploadResult(int uploadId, ImageRef image, String error) implements Packet {
        public static UploadResult read(PacketReader r) {
            return new UploadResult(r.readVarInt(), r.readNullable(ImageRef::read), r.readString(128));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(uploadId).writeNullable(image, ImageRef::write).writeString(error);
        }
    }

    public record RequestImage(long imageId) implements Packet {
        public static RequestImage read(PacketReader r) {
            return new RequestImage(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(imageId);
        }
    }

    /** Сервер → клиент: кусок картинки. total = 0 — картинки нет или нет доступа. */
    public record ImageData(long imageId, int offset, int total, byte[] data) implements Packet {
        public static ImageData read(PacketReader r) {
            return new ImageData(r.readLong(), r.readVarInt(), r.readVarInt(), r.readBytes(ImageRef.CHUNK));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(imageId).writeVarInt(offset).writeVarInt(total).writeBytes(data);
        }
    }

    private ImagePackets() {
    }
}
