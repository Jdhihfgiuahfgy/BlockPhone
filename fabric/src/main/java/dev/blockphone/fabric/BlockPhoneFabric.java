package dev.blockphone.fabric;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.network.RawPayload;
import dev.blockphone.fabric.server.FabricServerHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Общая точка входа (клиент и сервер). Регистрирует канал и серверную часть. */
public final class BlockPhoneFabric implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(BlockPhone.NAME);
    public static final String VERSION = FabricLoader.getInstance().getModContainer(BlockPhone.MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(RawPayload.TYPE, RawPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RawPayload.TYPE, RawPayload.CODEC);
        FabricServerHandler.init();
        LOGGER.info("BlockPhone {} initialized (protocol {})", VERSION, BlockPhone.PROTOCOL_VERSION);
    }
}
