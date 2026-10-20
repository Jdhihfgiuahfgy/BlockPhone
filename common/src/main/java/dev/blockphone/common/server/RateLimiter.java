package dev.blockphone.common.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Ограничение частоты: не больше limit действий за windowMs на игрока. Только из потока {@link Backend}. */
final class RateLimiter {
    private final int limit;
    private final long windowMs;
    private final Map<UUID, Deque<Long>> times = new HashMap<>();

    RateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    /** true — можно (и действие засчитано), false — слишком часто. */
    boolean allow(UUID player) {
        long now = System.currentTimeMillis();
        Deque<Long> list = times.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!list.isEmpty() && now - list.peekFirst() > windowMs) {
            list.pollFirst();
        }
        if (list.size() >= limit) {
            return false;
        }
        list.addLast(now);
        return true;
    }

    void forget(UUID player) {
        times.remove(player);
    }
}
