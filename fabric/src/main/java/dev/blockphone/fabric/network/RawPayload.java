package dev.blockphone.fabric.network;

import dev.blockphone.common.BlockPhone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Обёртка над сырыми байтами канала {@code blockphone:main}. Содержимое кодируется/декодируется
 * в common ({@link dev.blockphone.common.protocol.Packets}), чтобы формат совпадал с Paper-плагином,
 * который получает те же байты как plugin message.
 */
public record RawPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<RawPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(BlockPhone.CHANNEL_NAMESPACE, BlockPhone.CHANNEL_PATH));

    public static final StreamCodec<FriendlyByteBuf, RawPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new RawPayload(bytes);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
