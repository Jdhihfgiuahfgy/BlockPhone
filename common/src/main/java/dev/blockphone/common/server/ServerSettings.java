package dev.blockphone.common.server;

import java.nio.file.Path;

/**
 * Настройки серверной части (читаются из config.yml на Paper/Velocity и из server.json на Fabric).
 *
 * @param messengerEnabled   включить мессенджер
 * @param phoneInHandVisible показывать телефон в руке другим игрокам
 * @param storage            где хранить переписку
 */
public record ServerSettings(boolean messengerEnabled, boolean phoneInHandVisible, Storage storage) {

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
}
