package dev.blockphone.common.voice;

import java.util.UUID;

/**
 * Что звонкам нужно от голосового чата (Simple Voice Chat): группы и «подключён ли игрок».
 * Реализация — {@link SvcBridge}; в тестах — заглушка. Вызывается из фонового потока BlockPhone.
 */
public interface VoiceBridge {
    /** У игрока стоит голосовой чат и он подключён к нему. */
    boolean connected(UUID player);

    /** Создать скрытую группу для звонка. Возвращает её id. */
    UUID createGroup(String name, boolean isolated);

    /** Перевести игрока в группу звонка. */
    void join(UUID groupId, UUID player);

    /** Вывести игрока из группы (если он ещё в ней). */
    void leave(UUID groupId, UUID player);

    /** Удалить группу (звонок закончился). */
    void removeGroup(UUID groupId);
}
