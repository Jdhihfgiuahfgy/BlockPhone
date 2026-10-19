package dev.blockphone.common.server;

import java.util.UUID;

/**
 * То, что серверная логика BlockPhone требует от платформы (Fabric-сервер, Paper, Velocity).
 * Реализации должны быть потокобезопасными: методы вызываются из фонового потока мессенджера.
 */
public interface ServerPlatform {
    /** "fabric", "paper", "velocity" — уходит клиенту в приветствии (для отладки). */
    String name();

    /** Отправить игроку байты канала blockphone:main (если он ещё онлайн). */
    void send(UUID player, byte[] data);

    void info(String message);

    void warn(String message, Throwable error);
}
