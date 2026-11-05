package dev.blockphone.common.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloodGuardTest {
    @Test
    void dropsPacketsOverLimitUntilWindowEnds() {
        FloodGuard guard = new FloodGuard();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < FloodGuard.LIMIT; i++) {
            assertTrue(guard.allow(player, 1000));
        }
        assertFalse(guard.allow(player, 1000 + FloodGuard.WINDOW_MS - 1));
        assertTrue(guard.allow(UUID.randomUUID(), 1000), "другой игрок не страдает");
        assertTrue(guard.allow(player, 1000 + FloodGuard.WINDOW_MS));
    }

    @Test
    void logsOncePerMinuteAndCountsSkipped() {
        FloodGuard guard = new FloodGuard();
        UUID player = UUID.randomUUID();
        assertEquals(0, guard.logSlot(player, 0));
        assertEquals(-1, guard.logSlot(player, 1000));
        assertEquals(-1, guard.logSlot(player, 2000));
        assertEquals(2, guard.logSlot(player, FloodGuard.LOG_EVERY_MS));
    }
}
