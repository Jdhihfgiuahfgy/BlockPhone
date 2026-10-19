package dev.blockphone.common.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Пишет данные пакета в байты. Формат совместим с {@link PacketReader}. */
public final class PacketWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public PacketWriter writeByte(int value) {
        out.write(value);
        return this;
    }

    public PacketWriter writeBoolean(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    public PacketWriter writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        return this;
    }

    public PacketWriter writeLong(long value) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) (value >>> (i * 8)));
        }
        return this;
    }

    public PacketWriter writeString(String value) {
        return writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public PacketWriter writeBytes(byte[] value) {
        writeVarInt(value.length);
        out.writeBytes(value);
        return this;
    }

    public PacketWriter writeUuid(java.util.UUID value) {
        return writeLong(value.getMostSignificantBits()).writeLong(value.getLeastSignificantBits());
    }

    /** Список: VarInt размер + элементы. */
    public <T> PacketWriter writeList(java.util.Collection<T> values, java.util.function.BiConsumer<PacketWriter, T> element) {
        writeVarInt(values.size());
        for (T value : values) {
            element.accept(this, value);
        }
        return this;
    }

    /** Необязательное значение: boolean «есть» + само значение. */
    public <T> PacketWriter writeNullable(T value, java.util.function.BiConsumer<PacketWriter, T> element) {
        writeBoolean(value != null);
        if (value != null) {
            element.accept(this, value);
        }
        return this;
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }
}
