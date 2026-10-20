package dev.blockphone.common.server;

import dev.blockphone.common.protocol.data.ImageRef.PhotoPolicy;

import java.nio.file.Path;
import java.util.List;

/**
 * Настройки серверной части (читаются из config.yml на Paper, config.json на Velocity и server.json на Fabric).
 *
 * @param messengerEnabled   включить мессенджер
 * @param phoneInHandVisible показывать телефон в руке другим игрокам
 * @param storage            где хранить данные (переписку, ленту, фото)
 * @param social             лента: посты, паблики, профили
 * @param moderators         ники или UUID игроков с полными правами модератора (на случай, если нет LuckPerms)
 */
public record ServerSettings(boolean messengerEnabled, boolean phoneInHandVisible, Storage storage, Social social,
                             List<String> moderators) {

    public ServerSettings(boolean messengerEnabled, boolean phoneInHandVisible, Storage storage) {
        this(messengerEnabled, phoneInHandVisible, storage, Social.defaults(), List.of());
    }

    /** Нужен ли фоновый поток с базой данных (мессенджер или лента включены). */
    public boolean backendEnabled() {
        return messengerEnabled || social.enabled();
    }

    /**
     * Хранилище. type = "h2" — встроенная база в файле (ничего настраивать не нужно);
     * "mysql" — сервер MySQL/MariaDB (нужен для сети серверов без прокси-плагина или для резервного копирования).
     */
    public record Storage(String type, Path h2File, String host, int port, String database, String user, String password,
                          String tablePrefix) {
        public boolean isMysql() {
            return "mysql".equalsIgnoreCase(type) || "mariadb".equalsIgnoreCase(type);
        }
    }

    /**
     * Лента.
     *
     * @param photos              какие фото можно прикреплять к постам и сообщениям (any / server / none)
     * @param maxPhotoBytes       предел размера одного фото
     * @param maxPhotoSide        предел длинной стороны фото (клиент уменьшает сам)
     * @param maxPublicsPerPlayer сколько пабликов может создать один игрок
     */
    public record Social(boolean enabled, PhotoPolicy photos, int maxPhotoBytes, int maxPhotoSide, int maxPublicsPerPlayer) {
        public static Social defaults() {
            return new Social(true, PhotoPolicy.SERVER, 600 * 1024, 1280, 3);
        }

        public Social {
            maxPhotoBytes = Math.max(32 * 1024, Math.min(dev.blockphone.common.protocol.data.ImageRef.HARD_MAX_BYTES, maxPhotoBytes));
            maxPhotoSide = Math.max(256, Math.min(4096, maxPhotoSide));
            maxPublicsPerPlayer = Math.max(0, maxPublicsPerPlayer);
        }
    }
}
