package dev.blockphone.common;

/** Общие константы мода и плагина. */
public final class BlockPhone {
    public static final String MOD_ID = "blockphone";
    public static final String NAME = "BlockPhone";

    /** Единственный канал связи клиент⇄сервер. Внутри — свои пакеты (см. {@link dev.blockphone.common.protocol.Packets}). */
    public static final String CHANNEL_NAMESPACE = MOD_ID;
    public static final String CHANNEL_PATH = "main";
    public static final String CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_PATH;

    /** Увеличивать при любом несовместимом изменении протокола. */
    public static final int PROTOCOL_VERSION = 5;

    private BlockPhone() {
    }
}
