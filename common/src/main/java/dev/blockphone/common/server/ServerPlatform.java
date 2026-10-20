package dev.blockphone.common.server;

import java.util.UUID;

/**
 * То, что серверная логика BlockPhone требует от платформы (Fabric-сервер, Paper, Velocity).
 * Реализации должны быть потокобезопасными: методы вызываются из фонового потока BlockPhone.
 */
public interface ServerPlatform {
    /** "fabric", "paper", "velocity" — уходит клиенту в приветствии (для отладки). */
    String name();

    /** Отправить игроку байты канала blockphone:main (если он ещё онлайн). */
    void send(UUID player, byte[] data);

    /**
     * Есть ли у игрока право (LuckPerms и т. п.). Если система прав ничего не знает об узле —
     * {@link Permission#defaultAllowed()} для обычных прав и «оператор ли игрок» для модераторских.
     * Вызывается из фонового потока.
     */
    default boolean hasPermission(UUID player, Permission permission) {
        return permission.defaultAllowed();
    }

    void info(String message);

    void warn(String message, Throwable error);
}
