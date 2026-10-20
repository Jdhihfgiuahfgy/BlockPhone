package dev.blockphone.common.voice;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * Текущий голосовой мост и настройки для него. Здесь нет типов Simple Voice Chat, поэтому класс можно
 * трогать, даже если SVC не установлен.
 */
public final class VoiceBridges {
    private static volatile VoiceBridge current;
    private static volatile Executor mainThread = Runnable::run;
    private static volatile BiConsumer<UUID, UUID> leaveListener = (player, group) -> {
    };

    /** Текущий мост или null (SVC не установлен или его сервер ещё не запущен). */
    public static VoiceBridge current() {
        return current;
    }

    public static void set(VoiceBridge bridge) {
        current = bridge;
    }

    /** Как выполнять действия в главном потоке сервера (задаёт платформа). */
    public static void setMainThread(Executor executor) {
        mainThread = executor;
    }

    static Executor mainThread() {
        return mainThread;
    }

    /** Кто-то сам вышел из группы звонка (через меню SVC): (игрок, id группы). */
    public static void setLeaveListener(BiConsumer<UUID, UUID> listener) {
        leaveListener = listener;
    }

    static void playerLeft(UUID player, UUID group) {
        leaveListener.accept(player, group);
    }

    private VoiceBridges() {
    }
}
