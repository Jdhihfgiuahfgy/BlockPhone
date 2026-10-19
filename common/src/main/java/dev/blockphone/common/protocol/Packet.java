package dev.blockphone.common.protocol;

/** Пакет протокола BlockPhone. Регистрируется в {@link Packets}. */
public interface Packet {
    void write(PacketWriter writer);
}
