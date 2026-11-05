package dev.blockphone.common.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Защита от потока пакетов: не больше {@link #LIMIT} пакетов игрока за {@link #WINDOW_MS} (лишние отбрасываются ещё до
 * очереди фонового потока — она не растёт) и не больше одной записи в лог об игроке в минуту. Потокобезопасно: пакеты
 * приходят из сетевых потоков платформы.
 */
final class FloodGuard {
    static final int LIMIT = 200;
    static final long WINDOW_MS = 10_000;
    static final long LOG_EVERY_MS = 60_000;

    private static final class State {
        long windowStart = Long.MIN_VALUE / 2;
        int count;
        long lastLog = Long.MIN_VALUE / 2;
        int suppressed;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    /** true — пакет можно обработать, false — игрок шлёт слишком часто. */
    boolean allow(UUID player, long now) {
        State s = states.computeIfAbsent(player, k -> new State());
        synchronized (s) {
            if (now - s.windowStart >= WINDOW_MS) {
                s.windowStart = now;
                s.count = 0;
            }
            return ++s.count <= LIMIT;
        }
    }

    /**
     * Можно ли сейчас написать в лог об игроке: −1 — нет (запись засчитана как пропущенная), иначе — сколько записей
     * пропущено с прошлого раза.
     */
    int logSlot(UUID player, long now) {
        State s = states.computeIfAbsent(player, k -> new State());
        synchronized (s) {
            if (now - s.lastLog < LOG_EVERY_MS) {
                s.suppressed++;
                return -1;
            }
            s.lastLog = now;
            int skipped = s.suppressed;
            s.suppressed = 0;
            return skipped;
        }
    }

    void forget(UUID player) {
        states.remove(player);
    }
}
