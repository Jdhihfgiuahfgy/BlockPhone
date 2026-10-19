package dev.blockphone.common.protocol;

import java.nio.charset.StandardCharsets;

/** Читает данные пакета, записанные {@link PacketWriter}. Бросает {@link ProtocolException} на битых данных. */
public final class PacketReader {
    /** Защита от огромных строк/массивов от недоверенной стороны. */
    private static final int MAX_LENGTH = 8 * 1024 * 1024;

    private final byte[] data;
    private int pos;

    public PacketReader(byte[] data) {
        this.data = data;
    }

    public int readByte() {
        if (pos >= data.length) {
            throw new ProtocolException("Unexpected end of packet");
        }
        return data[pos++] & 0xFF;
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public int readVarInt() {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new ProtocolException("VarInt too long");
    }

    public long readLong() {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | readByte();
        }
        return value;
    }

    public String readString() {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    public byte[] readBytes() {
        int length = readVarInt();
        if (length < 0 || length > MAX_LENGTH || length > remaining()) {
            throw new ProtocolException("Bad length: " + length);
        }
        byte[] result = new byte[length];
        System.arraycopy(data, pos, result, 0, length);
        pos += length;
        return result;
    }

    public java.util.UUID readUuid() {
        return new java.util.UUID(readLong(), readLong());
    }

    /** Строка не длиннее maxChars символов (защита от огромных строк). */
    public String readString(int maxChars) {
        String value = readString();
        if (value.length() > maxChars) {
            throw new ProtocolException("String too long: " + value.length());
        }
        return value;
    }

    public <T> java.util.List<T> readList(int maxSize, java.util.function.Function<PacketReader, T> element) {
        int size = readVarInt();
        if (size < 0 || size > maxSize) {
            throw new ProtocolException("Bad list size: " + size);
        }
        java.util.List<T> result = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(element.apply(this));
        }
        return result;
    }

    public <T> T readNullable(java.util.function.Function<PacketReader, T> element) {
        return readBoolean() ? element.apply(this) : null;
    }

    public int remaining() {
        return data.length - pos;
    }
}
